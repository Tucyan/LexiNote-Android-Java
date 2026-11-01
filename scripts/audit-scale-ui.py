"""ADB-only interaction helpers. Select targets from freshly observed accessibility nodes."""
import json,pathlib,re,subprocess,time,xml.etree.ElementTree as ET
ADB=str(pathlib.Path.home()/"AppData/Local/Android/Sdk/platform-tools/adb.exe")
ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/"docs/verification/scale-usage/release-interaction"
OUT.mkdir(parents=True,exist_ok=True)
LOG=OUT/"actions.jsonl"
def adb(*args,timeout=30):
 p=subprocess.run([ADB,*map(str,args)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
 if p.returncode: raise RuntimeError(p.stderr.decode(errors="replace"))
 return p.stdout
def record(action,**values):
 with LOG.open("a",encoding="utf-8") as f:f.write(json.dumps(dict(action=action,**values),ensure_ascii=False)+"\n")
def tree():
 for attempt in range(3):
  output=adb("shell","uiautomator","dump","/sdcard/scale-interaction.xml").decode(errors="replace")
  if "dumped" in output:return ET.fromstring(adb("exec-out","cat","/sdcard/scale-interaction.xml"))
  time.sleep(.3)
 raise RuntimeError("UI dump failed after 3 attempts")
def labels():
 return [{k:n.get(k) for k in ["text","content-desc","class","bounds"]} for n in tree().iter("node") if n.get("text") or n.get("content-desc")]
def tap(label,kind=""):
 matches=[n for n in tree().iter("node") if (n.get("text")==label or n.get("content-desc")==label) and (not kind or n.get("class")==kind)]
 if not matches:raise RuntimeError("Visible target missing: "+label)
 n=matches[-1];x1,y1,x2,y2=map(int,re.findall(r"\d+",n.get("bounds")))
 if x2<=x1 or y2<=y1:raise RuntimeError("Target has no visible bounds: "+label)
 started=time.perf_counter();adb("shell","input","tap",(x1+x2)//2,(y1+y2)//2);record("tap",label=label,bounds=n.get("bounds"),adb_ms=round((time.perf_counter()-started)*1000))
 time.sleep(.35)
def text(label,value):
 assert re.fullmatch(r"[A-Za-z0-9 _-]+",value)
 tap(label);adb("shell","input","keycombination",113,29);adb("shell","input","keyevent",67);adb("shell","input","text",value.replace(" ","%s"));record("type",value=value);time.sleep(.4)
def swipe(x1,y1,x2,y2,duration=300):
 adb("shell","input","swipe",x1,y1,x2,y2,duration);record("swipe",start=[x1,y1],end=[x2,y2],duration=duration);time.sleep(.25)
def snapshot(name):
 t=tree();ET.ElementTree(t).write(OUT/(name+".xml"),encoding="utf-8",xml_declaration=True)
 (OUT/(name+".png")).write_bytes(adb("exec-out","screencap","-p"));record("snapshot",name=name)
if __name__=="__main__": print(json.dumps(labels(),ensure_ascii=False))
