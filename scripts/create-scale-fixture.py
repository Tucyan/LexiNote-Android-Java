"""Deterministic synthetic learning histories; no network or real account data."""
import copy, datetime, itertools, json, pathlib, zipfile, zlib
ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = json.loads((ROOT / "docs/fixtures/normal-usage/lexinote-normal-usage-20261028.json").read_text(encoding="utf-8"))
OUT = ROOT / "docs/fixtures/scale-usage"
OUT.mkdir(parents=True, exist_ok=True)
ASSETS = ROOT / "app/src/androidTest/assets"
contexts = dict(x.split(":") for x in "project:项目|research:研究|training:培训|safety:安全|quality:质量|risk:风险|cost:成本|resource:资源|performance:绩效|marketing:市场营销|sales:销售|customer:客户|product:产品|service:服务|software:软件|system:系统|network:网络|security:安全防护|business:业务|financial:财务|operational:运营|strategic:战略|technical:技术|environmental:环境|educational:教育|medical:医疗|construction:施工|production:生产|transport:运输|energy:能源|investment:投资|recruitment:招聘|staff:员工|team:团队|department:部门|company:公司|community:社区|regional:区域|annual:年度|monthly:月度".split("|"))
terms = dict(x.split(":") for x in "budget:预算|plan:计划|report:报告|target:目标|schedule:日程|feedback:反馈|update:进展更新|analysis:分析|review:审查|summary:摘要|assessment:评估|recommendation:建议|proposal:提案|strategy:策略|policy:政策|process:流程|procedure:程序|standard:标准|requirement:要求|objective:目标|priority:优先事项|challenge:挑战|opportunity:机会|issue:问题|solution:解决方案|decision:决策|initiative:举措|activity:活动|task:任务|meeting:会议|workshop:研讨会|presentation:演示|discussion:讨论|documentation:文档|guideline:指南|checklist:检查清单|framework:框架|model:模型|method:方法|approach:方法|practice:实践|program:项目方案|development:发展|improvement:改进|evaluation:评价|monitoring:监测|management:管理|coordination:协调|communication:沟通|support:支持|delivery:交付|implementation:实施|integration:整合|maintenance:维护|planning:规划|forecast:预测|estimate:估算|record:记录|data:数据|information:信息|result:结果|outcome:成果|indicator:指标|metric:衡量标准|benchmark:基准|capacity:能力|capability:能力水平|efficiency:效率|effectiveness:有效性|compliance:合规性|audit:审计|inspection:检查".split("|"))
assert len(contexts) * len(terms) == 2880
anchor = datetime.datetime(2026,11,1,2,tzinfo=datetime.timezone.utc)
def stamp(i): return (anchor-datetime.timedelta(hours=i)).isoformat(timespec="milliseconds").replace("+00:00","Z")
def generate(count):
 d=copy.deepcopy(BASE)
 for i,((left,lzh),(right,rzh)) in enumerate(itertools.product(contexts.items(),terms.items())):
  if len(d["vocabularies"]) >= count: break
  ident=f"scale-phrase-{i:04d}"; content=f"{left} {right}"; date=stamp(i)
  meaning={"id":ident+"-m1","created_at":date,"updated_at":date,"part_of_speech":"noun phrase","zh_definition":lzh+rzh,"en_definition":f"{right.capitalize()} relating to {left} work or activities.","example":f"We discussed the {content} during the meeting and agreed on the next steps.","phonetic":"","source":"合成工作英语阅读材料","source_images":[],"annotations":[]}
  if i%12==0:
   meaning["annotations"]=[{"id":ident+"-a","meaning_id":meaning["id"],"target_field":"example","target_text":content,"content":"先读例句，回忆搭配含义，再核对中文。","created_at":date,"updated_at":date}]
  d["vocabularies"].append({"id":ident,"type":"phrase","content":content,"created_at":date,"updated_at":date,"tags":["工作沟通",lzh,"搭配练习"],"meanings":[meaning],"review_count":i%6,"review_due":1793498400000+(i%10-5)*86400000,"review_grade":["再次","困难","简单"][i%3]})
 ids=[v["id"] for v in d["vocabularies"]]
 for n in d["notebooks"]:
  if n["id"]=="nb_global_root": n["vocabulary_ids"]=ids.copy()
 template=copy.deepcopy(d["notebooks"][0]); template["vocabulary_display"]={}
 for i in range(24):
  n=copy.deepcopy(template); n.update(id=f"scale-note-{i:02d}", name=f"阅读搭配 · 第{i+1:02d}辑",is_default=False,vocabulary_ids=ids[i::24])
  d["notebooks"].append(n)
 # One large notebook exposes membership scans; another group forms a three-level tree.
 n=copy.deepcopy(template); n.update(id="scale-heavy",name="工作英语总集",is_default=False,vocabulary_ids=ids.copy()); d["notebooks"].append(n)
 for i in range(3):
  n=copy.deepcopy(template); n.update(id=f"scale-parent-{i}",name=["长期学习","工作阅读","本月积累"][i],is_default=False,parent_id=None if i==0 else f"scale-parent-{i-1}",children_type="notebook" if i<2 else "vocabulary", child_notebook_ids=[f"scale-parent-{i+1}"] if i<2 else [],vocabulary_ids=[] if i<2 else ids[::5]); d["notebooks"].append(n)
 for i in range(80):
  word=d["vocabularies"][i%count]["content"]
  messages=[]
  for j in range(600 if i==0 else 4):
   content=f"How can I use {word} in a work email?" if j%2==0 else f"You can write: We discussed the {word} during the meeting.\n\n先交代背景，再提出明确的下一步行动。\n\n| 表达 | 语气 |\n|---|---|\n| Could you review it? | 礼貌请求 |\n| Please share an update. | 直接清楚 |"
   messages.append({"id":f"scale-chat-{i}-{j}","role":"user" if j%2==0 else "assistant","content":content,"created_at":stamp(i+j)})
  d["conversations"].append({"id":f"scale-chat-{i}","created_at":stamp(i),"query_type":"text","input_text":word,"ai_messages":messages,"metadata":{"retention":"saved","origin":"assistant","title":f"工作邮件表达 · {word} · {i+1}","saved_at":stamp(i),"last_used_at":stamp(i)}})
 d["export_date"]=stamp(0)
 payload=json.dumps(d,ensure_ascii=False,separators=(",",":")).encode()
 assert len(set(v["content"].casefold() for v in d["vocabularies"]))==count
 path=OUT/f"lexinote-scale-{count}.json"; path.write_bytes(payload)
 (ASSETS/f"scale-{count}.json").write_bytes(payload)
 portable=copy.deepcopy(d); portable["version"]="2.2"
 raw=json.dumps(portable,ensure_ascii=False,separators=(",",":")).encode()
 with zipfile.ZipFile(OUT/f"lexinote-scale-{count}.zip","w",zipfile.ZIP_DEFLATED) as z:
  z.writestr("backup.json",raw); z.writestr("checksums.json",json.dumps({"backup.json":format(zlib.crc32(raw)&0xffffffff,"x")}))
 (ASSETS/f"scale-{count}.zip").write_bytes((OUT/f"lexinote-scale-{count}.zip").read_bytes())
 return {"vocabularies":count,"notebooks":len(d["notebooks"]),"saved_sessions":sum(c.get("metadata",{}).get("retention")=="saved" for c in d["conversations"]),"messages":sum(len(c.get("ai_messages",[])) for c in d["conversations"]),"largest_conversation":600,"json_bytes":len(payload),"unique_content":True}
summaries=[generate(n) for n in [1000,3000]]
(OUT/"summary.json").write_text(json.dumps(summaries,indent=2),encoding="utf-8")
print(json.dumps(summaries,ensure_ascii=False))
