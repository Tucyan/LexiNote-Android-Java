package com.lexinote.nativeapp;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Layout assertions use real View geometry; root captures a matching set of raw screenshots. */
public final class LayoutComfortInstrumentation extends Instrumentation {
  MainActivity a; JSONObject before, fixture;
  boolean large; String focus;
  final List<String> passed=new ArrayList<>(), failed=new ArrayList<>();
  interface Action { void run() throws Exception; }
  public void onCreate(Bundle args) {
    super.onCreate(args); large=args!=null&&"true".equals(args.getString("large"));
    focus=args==null?"":args.getString("focus",""); start();
  }
  public void onStart() {
    Bundle out=new Bundle();
    try {
      try(InputStream in=getContext().getAssets().open("normal-usage.json")) {
        fixture=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));
      }
      a=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      waitForIdleSync(); main(()->before=a.library.snapshot());
      if(focus.equals("MANAGE")){
        run("MANAGE delete target",this::manageDelete);
        run("MANAGE create target",this::manageCreate);
        run("MANAGE root target",this::manageRoot);
      }
      run("LC06 targets",this::targets);
      run("LC01 dialog gutter",this::dialog);
      run("LC08 markdown scale",this::markdown);
      run("LC09 density",this::density);
      run("LC10 header bounds",this::header);
      run("MODES label bounds",this::modeBounds);
      run("LC02 compact context",this::context);
      run("LC11 latest outside timeline",this::latest);
      run("LC04 notebook control depth",this::notebookDepth);
      run("LC05 optional credentials",this::provider);
      run("LC12 readable action colors",this::colors);
      run("LC03 review card spacing",this::review);
      run("STATE context disclosure",this::contextState);
      run("LONGTAG filter visible",this::longTag);
      run("SCOPE selected visible",this::selectedScope);
      run("IMAGE selected controls",this::selectedImage);
      run("VISUAL states",this::visualStates);
    } catch(Throwable e) { failed.add("SETUP "+android.util.Log.getStackTraceString(e)); }
    finally {
      if(a!=null&&before!=null) try { main(()->{
        a.cancel();a.titles.reset();a.session=null;a.query.assistantView=null;
        a.library=new Library(before);a.repository.save(a.library);a.finish();
      }); } catch(Throwable e) { failed.add("RESTORE "+android.util.Log.getStackTraceString(e)); }
    }
    out.putString("stream","\nPASS "+passed.size()+"\n"+String.join("\n",passed)+"\nFAIL "+failed.size()+"\n"+String.join("\n",failed)+"\n");
    finish(failed.isEmpty()?Activity.RESULT_OK:Activity.RESULT_CANCELED,out);
  }
  void run(String name,Action test) {
    if(!focus.isEmpty()&&!name.startsWith(focus))return;
    Bundle progress=new Bundle();progress.putString("stream","\nRUN "+name+"\n");sendStatus(1,progress);
    try {
      main(()->{
        a.cancel();a.titles.reset();a.session=null;
        a.library=new Library(Library.copy(fixture));
        Library.put(a.library.settings(),"font_size",large?"large":"medium");
        Library.put(a.library.settings(),"theme_mode","light");
        Library.put(a.library.settings(),"density","comfortable");
        a.query=new QueryUi(a);a.notebooks=new NotebookUi(a);a.review=new ReviewUi(a);a.settingsUi=new SettingsUi(a);
        a.screen="tabs";a.page=0;a.render();
      });
      frame();test.run();frame();shot(name.split(" ")[0]);passed.add(name);
    } catch(Throwable e) {
      failed.add(name+": "+e);
      try{shot(name.split(" ")[0]+"-failed");}catch(Exception ignored){}
    } finally {
      for(int n=0;n<3;n++){
        AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow(),close=null;
        for(String label:new String[]{"关闭","取消","完成"}){
          AccessibilityNodeInfo candidate=node(root,label);
          if(candidate!=null&&"android.widget.Button".contentEquals(candidate.getClassName())){close=candidate;break;}
        }
        if(close==null||!close.performAction(AccessibilityNodeInfo.ACTION_CLICK))break;
        waitForIdleSync();SystemClock.sleep(100);
      }
    }
  }
  void targets(){
    main(()->{
      check(button(a.root,"文本查询").getHeight()>=a.dp(48),"query mode target below48dp");
      check(text(a.root,"日期筛选").getHeight()>=a.dp(48),"date target below48dp");
    });
    main(()->{a.page=2;a.render();});frame();
    main(()->check(FeatureInstrumentation.edit(a.root,"搜索词汇、含义或例句…").getHeight()>=a.dp(48),"search target below48dp"));
  }
  void dialog(){
    main(()->a.notebooks.detail(a.library.local("budget")));frame();
    AccessibilityNodeInfo value=await("预算"),ancestor=value;
    check(value!=null,"meaning missing");
    while(ancestor!=null&&!"android.widget.ScrollView".contentEquals(ancestor.getClassName()))ancestor=ancestor.getParent();
    check(ancestor!=null,"dialog scroll missing");
    Rect vr=new Rect(),sr=new Rect();value.getBoundsInScreen(vr);ancestor.getBoundsInScreen(sr);
    check(vr.left-sr.left>=a.dp(16),"meaning has no dialog gutter: "+(vr.left-sr.left)+"px");
  }
  void markdown(){
    main(()->{
      Library.put(a.library.settings(),"font_size","medium");
      a.query.open(a.library.find("conversations","usage-query-5"));
      a.screen="assistant";a.render();
    });frame();
    final float[] size={0};
    main(()->size[0]=text(a.root,"proof").getTextSize());
    main(()->{Library.put(a.library.settings(),"font_size","large");a.render();});frame();
    main(()->{
      float actual=text(a.root,"proof").getTextSize();
      float expected=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,14*1.15f,a.getResources().getDisplayMetrics());
      Bundle metrics=new Bundle();metrics.putString("stream","Markdown medium="+size[0]+"px large="+actual+"px expected="+expected+"px system="+a.getResources().getConfiguration().fontScale+"\n");sendStatus(1,metrics);
      check(actual>size[0]&&Math.abs(actual-expected)<.5f,"Markdown did not follow app and system font mapping");
    });
  }
  void density(){
    main(()->{a.page=2;a.render();});frame();
    final int[] comfortable={0};
    main(()->comfortable[0]=cardOf(text(a.root,"agenda")).getPaddingTop());
    main(()->{Library.put(a.library.settings(),"density","compact");a.render();});frame();
    main(()->check(cardOf(text(a.root,"agenda")).getPaddingTop()<comfortable[0],"density has no card spacing effect"));
  }
  void header(){
    main(()->{
      View recent=find(a.root,v->v.getContentDescription()!=null&&"最近查询".contentEquals(v.getContentDescription()));
      ViewGroup parent=(ViewGroup)recent.getParent();
      check(recent.getRight()<=parent.getWidth()-parent.getPaddingRight(),"recent action overflows header");
      check(recent.getLeft()>=parent.getPaddingLeft(),"recent action starts outside header");
    });
  }
  void modeBounds(){
    main(()->{
      for(String label:new String[]{"文本查询","图片查询","图文+提问"}){
        Button b=button(a.root,label);android.text.Layout l=b.getLayout();
        check(l!=null&&l.getLineBottom(l.getLineCount()-1)<=b.getHeight()-b.getCompoundPaddingTop()-b.getCompoundPaddingBottom(),"mode text clipped: "+label);
      }
    });
  }
  void context(){
    main(()->{
      View card=a.root.findViewWithTag("query-input-card");
      check(card.getHeight()<=a.dp(large||a.getResources().getConfiguration().fontScale>1.1?320:272),"default query card still oversized");
      check(find(a.root,v->v instanceof EditText&&String.valueOf(((EditText)v).getHint()).startsWith("粘贴包含"))==null,"optional context is not collapsed");
      button(a.root,"＋ 语境与要求（选填）").performClick();
    });frame();
    main(()->check(find(a.root,v->v instanceof EditText&&String.valueOf(((EditText)v).getHint()).startsWith("粘贴包含"))!=null,"context disclosure failed"));
  }
  void latest(){
    main(()->{a.query.open(a.library.find("conversations","usage-query-5"));a.screen="assistant";a.render();});frame();
    main(()->{
      View action=button(a.root,"回到最新消息");
      View list=find(a.root,v->v instanceof ListView);
      action.setVisibility(View.VISIBLE);
      Rect actionBounds=new Rect(),listBounds=new Rect();
      action.getGlobalVisibleRect(actionBounds);list.getGlobalVisibleRect(listBounds);
      check(!Rect.intersects(actionBounds,listBounds),"latest action still overlays timeline");
    });
  }
  void notebookDepth(){
    main(()->{a.page=2;a.render();});frame();
    main(()->{
      View card=cardOf(text(a.root,"agenda"));int[] pos={0,0};card.getLocationOnScreen(pos);
      View header=a.root.findViewWithTag("page-header");int[] hp={0,0};header.getLocationOnScreen(hp);
      check(pos[1]-(hp[1]+header.getHeight())<a.dp(198),"notebook default controls still too deep");
    });
  }
  void provider(){
    main(()->{a.page=4;a.render();});frame();
    main(()->{
      check(find(a.root,v->v instanceof EditText&&"留空保留当前供应商密钥".contentEquals(((EditText)v).getHint()))==null,"manual key still on default screen");
      button(a.root,"手动密钥与连接诊断").performClick();
    });frame();
    main(()->check(find(a.root,v->v instanceof EditText&&"留空保留当前供应商密钥".contentEquals(((EditText)v).getHint()))!=null,"diagnostics inaccessible"));
  }
  void colors(){
    main(()->{
      Button b=button(a.root,"✦  智能查询");
      int fill=((GradientDrawable)b.getBackground()).getColor().getDefaultColor();
      double hi=Math.max(Color.luminance(fill),Color.luminance(b.getCurrentTextColor()));
      double lo=Math.min(Color.luminance(fill),Color.luminance(b.getCurrentTextColor()));
      check((hi+.05)/(lo+.05)>=4.5,"primary text contrast below4.5");
      a.page=3;a.render();
      int green=text(a.root,"16").getCurrentTextColor();
      hi=Math.max(Color.luminance(green),Color.luminance(a.inputBg));
      lo=Math.min(Color.luminance(green),Color.luminance(a.inputBg));
      check((hi+.05)/(lo+.05)>=4.5,"mastery metric text contrast below4.5");
    });
  }
  void review(){
    main(()->{a.page=3;a.render();button(a.root,"开启本组复习").performClick();});frame();
    main(()->{
      View card=cardOf(text(a.root,"agenda"));View answer=button(a.root,"显示答案");
      int[] cp={0,0},ap={0,0};card.getLocationOnScreen(cp);answer.getLocationOnScreen(ap);
      check(ap[1]-(cp[1]+card.getHeight())<a.dp(100),"review card disconnected from controls");
    });
  }
  void contextState(){
    main(()->{
      a.query.context="retained context";a.query.contextExpanded=false;
      Bundle state=new Bundle();a.onSaveInstanceState(state);
      check(state.containsKey("contextExpanded")&&!state.getBoolean("contextExpanded"),"collapsed context state was not saved");
      check("retained context".equals(state.getString("context")),"context draft lost");
    });
  }
  void longTag(){
    main(()->{a.notebooks.tagFilter="非常长的标签用于验证筛选操作始终可见以及不会被摘要推到屏幕外";a.page=2;a.render();});frame();
    main(()->{
      Button filter=button(a.root,"筛选");Rect r=new Rect();
      check(filter.getGlobalVisibleRect(r)&&r.width()>=a.dp(48)&&r.height()>=a.dp(48),"long tag hides filter action");
    });
  }
  void selectedScope(){
    main(()->{a.review.notebook="usage-phrases";a.page=3;a.render();});frame();
    main(()->{
      JSONObject note=a.library.find("notebooks","usage-phrases");
      Button chosen=button(a.root,note.optString("name")+" (40)");Rect r=new Rect();
      check(chosen.getGlobalVisibleRect(r)&&r.width()==chosen.getWidth(),"selected scope is outside viewport after render");
    });
  }
  void selectedImage() throws Exception {
    File sample=new File(a.getCacheDir(),"layout-sample.png");
    android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(120,120,android.graphics.Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(0xffcbd5e1);
    try(FileOutputStream out=new FileOutputStream(sample)){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
    main(()->{a.image=android.net.Uri.fromFile(sample);a.query.homeMode="both";a.render();});frame();
    main(()->{
      View change=button(a.root,"更换"),remove=button(a.root,"移除"),submit=button(a.root,"✦  智能查询");
      Rect r=new Rect();check(remove.getGlobalVisibleRect(r)&&r.height()>=a.dp(48),"selected image remove control clipped");
      int[] rb={0,0},sb={0,0};remove.getLocationOnScreen(rb);submit.getLocationOnScreen(sb);
      check(rb[1]+remove.getHeight()<=sb[1],"image actions overlap query submission");
    });
    main(()->a.clearImage());
  }
  void visualStates() throws Exception {
    for(String mode:new String[]{"text","image","both"}){
      main(()->{a.clearImage();a.query.contextExpanded=false;a.query.homeMode=mode;a.render();});frame();shot("home-"+mode);
    }
    main(()->{a.page=2;a.render();});frame();shot("notebook-comfortable");
    main(()->{Library.put(a.library.settings(),"density","compact");a.render();});frame();shot("notebook-compact");
    main(()->{Library.put(a.library.settings(),"density","comfortable");Library.put(a.library.settings(),"theme_mode","dark");a.render();});frame();shot("notebook-dark");
    main(()->{Library.put(a.library.settings(),"theme_mode","light");a.page=4;a.settingsUi.models.diagnosticsExpanded=false;a.render();});frame();shot("settings-default");
    main(()->{a.page=3;a.render();button(a.root,"开启本组复习").performClick();});frame();
    main(()->button(a.root,"显示答案").performClick());frame();shot("review-answer");
    main(()->{a.query.open(a.library.find("conversations","usage-query-5"));a.screen="assistant";a.render();});frame();
    main(()->{EditText composer=FeatureInstrumentation.edit(a.root,"继续提问…");composer.requestFocus();
      ((android.view.inputmethod.InputMethodManager)a.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(composer,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});
    SystemClock.sleep(900);frame();
    main(()->{EditText composer=FeatureInstrumentation.edit(a.root,"继续提问…");Rect visible=new Rect();a.root.getWindowVisibleDisplayFrame(visible);int[] cp={0,0};composer.getLocationOnScreen(cp);
      check(cp[1]+composer.getHeight()<=visible.bottom,"keyboard covers composer");});shot("assistant-keyboard");
    main(()->((android.view.inputmethod.InputMethodManager)a.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(a.root.getWindowToken(),0));
  }
  void manageDelete(){
    main(()->{a.notebooks.selected="usage-work";a.page=2;a.render();a.notebooks.manage(a.library.find("notebooks","usage-reading"));});frame();
    check(clickText("删除笔记树"),"delete menu inaccessible");frame();
    check(await("删除").performAction(AccessibilityNodeInfo.ACTION_CLICK),"delete confirmation inaccessible");frame();
    main(()->{
      check(a.library.find("notebooks","usage-reading")==null,"long-pressed target was not deleted");
      check(a.library.find("notebooks","usage-work")!=null,"different selected notebook was deleted");
      check(a.library.array("vocabularies").length()==120,"notebook delete lost vocabulary entities");
    });
  }
  void manageCreate(){
    main(()->{a.notebooks.selected="usage-work";a.page=2;a.render();a.notebooks.manage(a.library.find("notebooks","usage-topics"));});frame();
    check(clickText("创建子分类"),"child menu inaccessible");frame();
    AccessibilityNodeInfo input=editable(getUiAutomation().getRootInActiveWindow());
    Bundle value=new Bundle();value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"Layout child");
    check(input!=null&&input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,value),"child name inaccessible");
    check(await("创建").performAction(AccessibilityNodeInfo.ACTION_CLICK),"create confirmation inaccessible");frame();
    main(()->check(a.library.find("notebooks","usage-topics").optJSONArray("child_notebook_ids").length()==5,"child used selected notebook instead of long-pressed parent"));
  }
  void manageRoot(){
    main(()->{a.notebooks.selected="usage-topics";a.page=2;a.render();a.notebooks.manage(null);});frame();
    check(clickText("创建分类"),"root menu inaccessible");frame();
    AccessibilityNodeInfo input=editable(getUiAutomation().getRootInActiveWindow());Bundle value=new Bundle();
    value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"Layout root");
    check(input!=null&&input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,value),"root name inaccessible");
    check(await("创建").performAction(AccessibilityNodeInfo.ACTION_CLICK),"root confirmation inaccessible");frame();
    main(()->{
      JSONObject created=null;JSONArray notes=a.library.array("notebooks");
      for(int i=0;i<notes.length();i++)if("Layout root".equals(notes.optJSONObject(i).optString("name")))created=notes.optJSONObject(i);
      check(created!=null&&created.isNull("parent_id"),"global category action used selected parent");
    });
  }
  boolean clickText(String label){
    AccessibilityNodeInfo n=await(label);if(n==null)return false;
    Rect r=new Rect();n.getBoundsInScreen(r);long now=SystemClock.uptimeMillis();
    sendPointerSync(MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,r.exactCenterX(),r.exactCenterY(),0));
    sendPointerSync(MotionEvent.obtain(now,now+40,MotionEvent.ACTION_UP,r.exactCenterX(),r.exactCenterY(),0));
    waitForIdleSync();return true;
  }
  static AccessibilityNodeInfo editable(AccessibilityNodeInfo n){
    if(n==null)return null;if(n.isEditable())return n;
    for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo child=editable(n.getChild(i));if(child!=null)return child;}return null;
  }
  static View cardOf(View v){
    while(v!=null){
      if(v instanceof LinearLayout&&v.isClickable())return v;
      if(v.getParent() instanceof LinearLayout&&v.getParent().getParent() instanceof ScrollView)return v;
      v=v.getParent() instanceof View?(View)v.getParent():null;
    }
    throw new AssertionError("card ancestor missing");
  }
  static View find(View r,FeatureInstrumentation.Match m){return FeatureInstrumentation.find(r,m);}
  static Button button(View r,String s){return FeatureInstrumentation.button(r,s);}
  static TextView text(View r,String s){
    View v=find(r,x->x instanceof TextView&&!(x instanceof EditText)&&s.equals(((TextView)x).getText().toString().trim()));
    check(v!=null,"missing text "+s);return(TextView)v;
  }
  static void check(boolean value,String msg){if(!value)throw new AssertionError(msg);}
  void main(Action work){
    final Throwable[] e={null};runOnMainSync(()->{try{work.run();}catch(Throwable t){e[0]=t;}});
    waitForIdleSync();if(e[0]!=null)throw new RuntimeException(e[0]);
  }
  void frame(){waitForIdleSync();SystemClock.sleep(200);waitForIdleSync();}
  AccessibilityNodeInfo await(String text){
    long end=SystemClock.uptimeMillis()+2500;do{
      AccessibilityNodeInfo n=node(getUiAutomation().getRootInActiveWindow(),text);if(n!=null)return n;
      SystemClock.sleep(100);
    }while(SystemClock.uptimeMillis()<end);return null;
  }
  static AccessibilityNodeInfo node(AccessibilityNodeInfo r,String text){
    if(r==null)return null;if(text.contentEquals(r.getText()==null?"":r.getText()))return r;
    for(int i=0;i<r.getChildCount();i++){AccessibilityNodeInfo n=node(r.getChild(i),text);if(n!=null)return n;}return null;
  }
  void shot(String label)throws Exception{
    frame();File dir=new File(a.getExternalFilesDir(null),"layout-optimization");dir.mkdirs();
    try(FileOutputStream out=new FileOutputStream(new File(dir,label+".png"))){
      getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);
    }
  }
}

