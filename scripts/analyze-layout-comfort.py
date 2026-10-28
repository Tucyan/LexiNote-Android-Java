"""Read-only layout measurements from actual Android UI dumps (no screenshot editing)."""
import json, re, struct
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/verification/layout-comfort"
SCREENS = OUT / "screens"
SCALE = 420 / 160

def tree(name):
    return ET.parse(SCREENS / f"{name}.xml").getroot()

def bounds(node):
    return list(map(int, re.findall(r"\d+", node.attrib["bounds"])))

def node(name, text, cls=None):
    return next(n for n in tree(name).iter("node")
        if (n.get("text") == text or n.get("content-desc") == text or n.get("hint") == text)
        and (cls is None or n.get("class") == cls))

def measure(name, text, cls=None):
    n=node(name,text,cls); x,y,r,b=bounds(n)
    return {"screen": name, "label": text, "bounds_px":[x,y,r,b],
        "width_dp":round((r-x)/SCALE,2), "height_dp":round((b-y)/SCALE,2),
        "clickable":n.get("clickable"),"class":n.get("class")}

def ancestry(name, text):
    root=tree(name); parents={c:p for p in root.iter() for c in p}
    n=next(n for n in root.iter("node") if n.get("text")==text)
    result=[]
    while n in parents:
        n=parents[n]
        if n.tag=="node":
            result.append({"class":n.get("class"),"bounds":bounds(n),"clickable":n.get("clickable")})
    return result

def geometry(name):
    return [(n.get("class"),n.get("text"),n.get("content-desc"),n.get("bounds"))
        for n in tree(name).iter("node")]

def lum(color):
    rgb=[int(color[i:i+2],16)/255 for i in (1,3,5)]
    vals=[v/12.92 if v<=0.04045 else ((v+0.055)/1.055)**2.4 for v in rgb]
    return sum(v*w for v,w in zip(vals,(0.2126,0.7152,0.0722)))

def contrast(a,b):
    l1,l2=sorted([lum(a),lum(b)])
    return round((l2+.05)/(l1+.05),3)

items=[
 ("01-home-standard","文本查询","android.widget.Button"),
 ("01-home-standard","日期筛选",None),
 ("02-notebook-standard","全部","android.widget.Button"),
 ("02-notebook-standard","搜索词汇、含义或例句…","android.widget.EditText"),
 ("02-notebook-standard","编辑词汇",None),
 ("08-review-overview-standard","10 词 / 组","android.widget.Button"),
 ("06-assistant-standard","proof",None),
 ("21-assistant-large","proof",None),
 ("06-assistant-standard","修改名称","android.widget.Button"),
 ("21-assistant-large","修改名称","android.widget.Button"),
 ("03-detail-standard","预算",None),
 ("24-home-360dp-large","最近查询",None)]
measurements=[measure(*item) for item in items]
n1=measure("06-assistant-standard","proof")
n2=measure("21-assistant-large","proof")
same = geometry("18-notebook-large-comfortable")==geometry("22-notebook-large-compact")
manifest=[]
for file in sorted(SCREENS.glob("*.png")):
    with file.open("rb") as f:
        header=f.read(24)
    w,h=struct.unpack(">II",header[16:24])
    manifest.append({"file":file.name,"width_px":w,"height_px":h,
        "xml":file.with_suffix(".xml").name})
data={"density_dpi":420,"px_per_dp":SCALE,"system_font_scale":1.0,
 "app_font_medium_scale":1.0,"app_font_large_scale":1.15,
 "measurements":measurements,
 "density_switch":{"same_visible_text_and_geometry":same,
    "comfortable":"18-notebook-large-comfortable","compact":"22-notebook-large-compact"},
 "assistant_app_font":{"table_cell_same_width_height":
    (n1["width_dp"],n1["height_dp"])==(n2["width_dp"],n2["height_dp"]),
    "standard":n1,"large":n2},
 "notebook_ancestors":ancestry("02-notebook-standard","agenda")[:5],
 "contrast":{"accent_vs_white":contrast("#10B981","#FFFFFF"),
             "body_vs_white":contrast("#0F172A","#FFFFFF"),
             "border_vs_white":contrast("#E2E8F0","#FFFFFF")},
 "screenshots":manifest}
OUT.joinpath("measurements.json").write_text(json.dumps(data,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
print(json.dumps({"screenshots":len(manifest),"density_same":same,"contrast":data["contrast"],
  "font_cell_same":data["assistant_app_font"]["table_cell_same_width_height"]},ensure_ascii=False))
