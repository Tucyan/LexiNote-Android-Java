// Reproducible usage data; ZIP container timestamps may vary. Original encoder validates ZIP v2.2.
// node scripts/create-normal-usage-fixture.cjs [Expo source root] [output directory]
const fs = require('fs');
const path = require('path');
const assert = require('assert/strict');
const nativeRoot = path.resolve(__dirname, '..');
const source = path.resolve(process.argv[2] || path.join(nativeRoot, '..', 'LexiNote'));
const output = path.resolve(process.argv[3] || path.join(nativeRoot, 'docs/fixtures/normal-usage'));
const ts = require(path.join(source, 'node_modules/typescript'));
require.extensions['.ts'] = (module, filename) => module._compile(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText, filename);
const { encodeBackup, decodeBackup } = require(path.join(source, 'src/services/storage/backupFormat.ts'));
const seed = JSON.parse(fs.readFileSync(path.join(__dirname, 'fixtures/normal-usage-lexicon.json'), 'utf8'));
const day = 86400000, anchor = Date.parse('2026-10-28T10:00:00+08:00');
const date = offset => new Date(anchor + offset).toISOString();
const fields = { zh_definition:true,en_definition:true,phonetic:true,example:true,source:true,source_images:true,annotations:true,remarks:true };
const themes = { work:'工作沟通',reading:'阅读表达',daily:'日常生活',academic:'学术学习' };
const additions = {
  character:['noun','性格；品格','the qualities that make a person different from other people','Her honesty reveals her strong character.'],
  plot:['verb','密谋；策划','to make a secret plan to do something','Several characters plot to steal the painting.'],
  chapter:['noun','人生或历史的一个阶段','a period in a person’s life or in history','Starting college marked a new chapter in her life.'],
  budget:['verb','为……编制预算','to plan how much money to spend','We budgeted carefully for the family holiday.'],
  schedule:['verb','安排；预定','to arrange for an event to happen at a particular time','They scheduled the interview for Monday.'],
  estimate:['noun','估算；估计值','a calculation of the likely size, cost, or value of something','The builder gave us an estimate for the repairs.'],
  approach:['verb','接近；靠近','to move nearer to a person or place','Please slow down as you approach the crossing.'],
  contrast:['noun','明显差异；对比','an obvious difference between two things','There is a sharp contrast between the two designs.'],
};
const phonetics={ agenda:'/əˈdʒendə/',deadline:'/ˈdedlaɪn/',feedback:'/ˈfiːdbæk/',budget:'/ˈbʌdʒɪt/',context:'/ˈkɒntekst/',chapter:'/ˈtʃæptə/',character:'/ˈkærəktə/',plot:'/plɒt/',theme:'/θiːm/',fiction:'/ˈfɪkʃən/',clue:'/kluː/',borrow:'/ˈbɒrəʊ/',repair:'/rɪˈpeə/',routine:'/ruːˈtiːn/',reliable:'/rɪˈlaɪəbl/',method:'/ˈmeθəd/',theory:'/ˈθɪəri/',evidence:'/ˈevɪdəns/',concept:'/ˈkɒnsept/',factor:'/ˈfæktə/' };
const all = [...seed.words,...seed.phrases];
assert.equal(seed.words.length,80); assert.equal(seed.phrases.length,40);
assert.equal(new Set(all.map(x=>x.content.normalize('NFKC').toLowerCase())).size,120);
for(const item of all) for(const field of ['content','type','part_of_speech','zh_definition','en_definition','example','topic'])
  assert.equal(typeof item[field], 'string', `${item.content}: ${field}`);
for(const topic of Object.keys(themes)) {
  assert.equal(seed.words.filter(x=>x.topic===topic).length,20);
  assert.equal(seed.phrases.filter(x=>x.topic===topic).length,10);
}
const vocabularies = all.map((item,i)=>{
  const id=`usage-${item.type}-${String(i+1).padStart(3,'0')}`;
  const created=date(-((28-i%28)*day)+(i%5)*3600000);
  const updated=new Date(Math.max(Date.parse(created),anchor-(2+i%7)*day)).toISOString();
  const makeMeaning=(values,index)=>({
    id:`${id}-meaning-${index+1}`,created_at:created,updated_at:updated,
    part_of_speech:values[0],zh_definition:values[1],en_definition:values[2],example:values[3],
    phonetic:phonetics[item.content]||item.phonetic||'',source:`每日学习 · ${themes[item.topic]}`,
    source_images:[],annotations:[],...(item.remarks?{remarks:item.remarks}:{}),
  });
  const meanings=[makeMeaning([item.part_of_speech,item.zh_definition,item.en_definition,item.example],0)];
  if(additions[item.content]) meanings.push(makeMeaning(additions[item.content],1));
  if(i%10===0) {
    const start=item.example.toLowerCase().indexOf(item.content.toLowerCase());
    const target=start<0?item.example:item.example.slice(start,start+item.content.length);
    meanings[0].annotations.push({id:`${id}-annotation`,meaning_id:meanings[0].id,target_field:'example',target_text:target,
      content:`保留这句作为${themes[item.topic]}中的用法例句，复习时先遮住中文再回忆。`,created_at:updated,updated_at:updated});
  }
  return {id,type:item.type,content:item.content,created_at:created,updated_at:updated,
    tags:[themes[item.topic],item.type==='word'?'词汇积累':'常用短语',i%3===0?'待复习':'已学习'],meanings,
    review_count:i%5,review_due:i%5===0?anchor-3600000:anchor+((i%4)-1)*day,
    ...(i%5?{review_grade:['再次','困难','简单'][i%3],reviewed_at:updated}:{})};
});
const byContent=new Map(vocabularies.map(v=>[v.content,v]));
const notebook=(id,name,ids,extra={})=>({id,name,created_at:date(-30*day),updated_at:date(-3600000),parent_id:null,
  children_type:'vocabulary',child_notebook_ids:[],vocabulary_ids:ids,tags:[],vocabulary_display:{},...extra});
const notebooks=[
  notebook('nb_default','默认笔记',vocabularies.filter((_,i)=>i%6===0).map(v=>v.id),{is_default:true}),
  notebook('nb_global_root','全部词汇',vocabularies.map(v=>v.id),{is_global_root:true}),
  notebook('usage-topics','主题学习',[],{children_type:'notebook',child_notebook_ids:Object.keys(themes).map(t=>`usage-${t}`)}),
  ...Object.entries(themes).map(([topic,name])=>notebook(`usage-${topic}`,name,vocabularies.filter((_,i)=>all[i].topic===topic).map(v=>v.id),{parent_id:'usage-topics',tags:[name]})),
  notebook('usage-phrases','短语精练',vocabularies.filter(v=>v.type==='phrase').map(v=>v.id),{tags:['口语','写作']}),
];
// References in different notebooks share the complete entity but may show different fields.
for(const n of notebooks.filter(n=>n.id!=='nb_global_root')) n.vocabulary_ids.forEach((id,i)=>{
  const v=vocabularies.find(v=>v.id===id);
  n.vocabulary_display[id]={meaning_ids:v.meanings.map(m=>m.id),fields:{...fields},meaning_fields:{}};
  if(n.id==='usage-phrases'&&i%4===0) n.vocabulary_display[id].meaning_fields[v.meanings[0].id]={zh_definition:false,en_definition:true};
});
const queryResult=v=>({word:v.content,type:v.type,phonetic:v.meanings[0].phonetic,
  meanings:v.meanings.map(m=>({...m,selected:true,definition_choice:'zh',save_zh:true,save_en:true,save_example:true})),
  is_from_local:true,local_vocabulary_id:v.id,follow_up_questions:[`能用 ${v.content} 再造一个句子吗？`]});
const querySpecs=[
  ['deadline','deadline：如何礼貌确认截止时间','项目邮件里的 deadline 怎么理解？','deadline 是必须完成某事的最后期限。邮件里可以问：Could you confirm the deadline for the draft?','deadline 和 due date 有什么区别？','deadline 更强调最后期限；due date 常用于作业、账单或交付的到期日期。语境有重叠。确认项目进度时，两者都可以使用。'],
  ['context','context：从上下文猜词','阅读时总看到 in context，这里的 context 是什么？','context 指帮助理解某个词句的上下文或背景。先看前后句，再判断当前词义，比孤立背一个中文对应更可靠。','如果上下文不够，应该怎么办？','先标记不确定的词义，再读下一段。仍无法确定时查词典，并核对词性、搭配和例句，而不是只采用第一个释义。'],
  ['character','character 的两个常见含义','小说中的 character 和评价人的 character 一样吗？','不完全一样。小说里常指“人物、角色”；描述一个人的品质时常指“性格、品格”。词条里保留了这两个义项。','怎么区分？','比较：The main character is a doctor.（主要角色）与 Her honesty shows her character.（品格）。看它是否指故事中的某个人。'],
  ['reliable','reliable 与 dependable 的区别','reliable 可以用来形容同事吗？','可以。A reliable colleague 指做事可信赖、通常能够按承诺完成工作的人。也可以形容信息、设备或服务。','和 dependable 的区别大吗？','两者都可以表示“可靠的”。dependable 常突出遇到需要时可以依靠；reliable 的使用范围更广，也常用于数据和消息来源。'],
  ['evidence','evidence 和 proof 的语气区别','evidence 和 proof 在文章里怎么区分？','| 词 | 常见侧重点 |\n| --- | --- |\n| evidence | 支持判断的证据或材料 |\n| proof | 足以证明某事的证据，或数学证明 |\n\n一般论证中，evidence 不必意味着结论已经完全确定。','evidence 可以加 s 吗？','日常和学术英语里 evidence 通常是不可数名词。可说 a piece of evidence 或 several pieces of evidence。proof 的可数性要看具体义项。'],
  ['take into account','take into account 的句型','如何用 take into account 表达考虑实际情况？','它表示“把某因素纳入考虑”。可以说：We should take the weather into account when planning the trip.','宾语一定放在中间吗？','不是。常见形式有 take something into account，也有 take into account something；长宾语常放后面。代词通常放中间，例如 take it into account。'],
  ['get the hang of','get the hang of 的日常用法','get the hang of 能表示学会用一个软件吗？','可以，尤其表示逐渐掌握操作窍门。例如：I’m getting the hang of this app. 语气较口语化。','后面能接动词吗？','可以接名词或动名词：get the hang of using the app。它通常指实际操作逐渐熟练，不等于已经精通所有功能。'],
  ['follow up on','follow up on 的邮件表达','想跟进客户请求，follow up on 怎么写？','可以写：I’m writing to follow up on your request. on 后面接要跟进的事项；如果强调联系谁，可以用 follow up with someone。','想礼貌一点呢？','可以补充明确目的和时间：I’m following up on our discussion to see whether you need any further information. 尽量说明你希望对方确认什么。'],
  ['in terms of','in terms of 的比较句','in terms of 的意思是什么？','它表示“就某个方面而言”。例如：The two methods differ in terms of cost and speed. 用它先限定比较的维度。','每次写 about 都能换成它吗？','不能。in terms of 常用于指定方面或评价标准，不是 about 的万能替换。谈某个话题本身时，about 往往更直接。'],
  ['agenda','agenda：会议日程','会议里的 agenda 是什么意思？','agenda 是会议要讨论的事项清单，也就是议程。提前看议程能知道自己需要准备哪些材料。','会议中能说 next item 吗？','可以。Let’s move on to the next item on the agenda. 是常见的主持会议表达。'],
];
const querySessions=querySpecs.map((spec,i)=>{
  const [word,title,q,a,q2,a2]=spec,v=byContent.get(word),id=`usage-query-${i+1}`;
  assert(v,word);
  const created=date(-(9-i)*day-(10-i)*10*60000);
  const last=new Date(Math.max(Date.parse(created)+3*60000,anchor-(10-i)*30*60000)).toISOString();
  const messages=[['user',q],['assistant',a],['user',q2],['assistant',a2]].map(([role,content],j)=>({id:`${id}-message-${j+1}`,role,content,created_at:new Date(Date.parse(created)+j*60000).toISOString(),
    ...(role==='assistant'?{action:{kind:'answer',message:content}}:{})}));
  messages[1].query_result=queryResult(v);
  return {id,created_at:created,query_type:'text',input_text:word,source_context:v.meanings[0].example,
    query_results:queryResult(v),ai_messages:messages,revision:2,session_state:'ready',
    saved_content:{saved:true,vocabulary_id:v.id,notebook_ids:notebooks.filter(n=>n.id!=='nb_global_root'&&n.vocabulary_ids.includes(v.id)).map(n=>n.id),saved_at:created},
    metadata:{title,title_source:i%2?'ai':'user',title_revision:1,retention:i<8?'saved':'temporary',origin:'query',created_at:created,last_used_at:last,updated_at:last,...(i<8?{saved_at:created}:{})}};
});
const assistantSpecs=[
  ['used to 与 be used to 的区别',[
    ['user','used to do 和 be used to doing 总是混淆，能对比一下吗？'],
    ['assistant','- used to do：过去常常做，现在通常不再如此。\n- be used to doing：习惯做某事。\n\n例：I used to walk to work. / I’m used to walking to work.'],
    ['user','那 get used to 呢？'],['assistant','get used to 表示逐渐适应的过程，后面接名词或动名词。例如：I’m getting used to the new schedule. be used to 更强调已经习惯的状态。'],
    ['user','所以 I used to getting up early 不对？'],['assistant','对，这里应根据意思改成 I used to get up early.（过去常早起），或者 I’m used to getting up early.（已习惯早起）。'],
  ]],
  ['data 和 evidence 的主谓一致',[
    ['user','evidence 和 data 后面的动词都用复数吗？'],
    ['assistant','evidence 通常是不可数名词，常配单数动词：The evidence suggests a link. data 在学术写作中常作复数，在一般用法中也常作集合性的不可数名词；应与所用风格保持一致。'],
    ['user','那我写报告时怎么处理比较稳妥？'],['assistant','先看学校、期刊或公司的写作规范。你也可以用明确表达：The results show... 或 The dataset contains...，减少 data 单复数选择带来的歧义。'],
  ]],
  ['读书笔记中的改写练习',[
    ['user','帮我看看这句改写：The author uses a journey to illustrate personal growth.'],
    ['assistant','这句自然，意思是作者用一次旅程来说明个人成长。可以更具体地说：The character’s journey illustrates how experience can change a person.'],
    ['user','如果不想重复 illustrate 呢？'],['assistant','可以根据重点换成 show、explore 或 reflect，但意义并非完全相同。show 较直接；explore 强调探讨；reflect 强调体现。先确定你想表达“展示”还是“探讨”。'],
  ]],
];
const temporaryAssistants=assistantSpecs.map(([title,pairs],i)=>{
  const id=`usage-assistant-${i+1}`,created=date(-(3-i)*6*3600000),last=date(-(3-i)*20*60000);
  return {id,created_at:created,query_type:'text',input_text:pairs[0][1],revision:pairs.length/2,session_state:'awaiting_user',
    ai_messages:pairs.map(([role,content],j)=>({id:`${id}-message-${j+1}`,role,content,created_at:new Date(Date.parse(created)+j*60000).toISOString(),...(role==='assistant'?{action:{kind:'answer',message:content}}:{})})),
    metadata:{title,title_source:'ai',title_revision:1,retention:'temporary',origin:'assistant',created_at:created,last_used_at:last,updated_at:last}};
});
const conversations=[...querySessions,...temporaryAssistants].sort((a,b)=>b.metadata.last_used_at.localeCompare(a.metadata.last_used_at));
const backup={version:'2.2',export_date:date(0),vocabularies,notebooks,
  recent_queries:[...querySessions].sort((a,b)=>b.created_at.localeCompare(a.created_at)),conversations,
  settings:{theme_mode:'light',accent_color:'#10B981',font_size:'medium',density:'comfortable',default_notebook_id:'nb_default',auto_record_extra_info:false,default_fields_to_save:{...fields}}};
async function main(){
  const archive=await encodeBackup(backup,async()=>{throw Error('This usage dataset has no images.');});
  const decoded=decodeBackup(archive.bytes).backup;
  assert.equal(decoded.vocabularies.length,120); assert.equal(decoded.recent_queries.length,10);
  assert.equal(decoded.conversations.filter(c=>c.metadata.retention==='temporary').length,5);
  assert.equal(decoded.conversations.filter(c=>c.metadata.retention==='saved').length,8);
  fs.mkdirSync(output,{recursive:true});
  const name='lexinote-normal-usage-20261028';
  fs.writeFileSync(path.join(output,name+'.zip'),archive.bytes);
  fs.writeFileSync(path.join(output,name+'.json'),JSON.stringify({...archive.backup,version:'1.1'},null,2)+'\n');
  const summary={words:80,phrases:40,vocabularies:120,meanings:vocabularies.reduce((n,v)=>n+v.meanings.length,0),notebooks:notebooks.length,recent_queries:10,temporary_conversations:5,saved_conversations:8,
    messages:conversations.reduce((n,c)=>n+c.ai_messages.length,0),annotations:vocabularies.reduce((n,v)=>n+v.meanings.reduce((a,m)=>a+m.annotations.length,0),0),images:0,zip_bytes:archive.bytes.length};
  fs.writeFileSync(path.join(output,'summary.json'),JSON.stringify(summary,null,2)+'\n');
  console.log(JSON.stringify(summary));
}
main().catch(error=>{console.error(error);process.exitCode=1;});
