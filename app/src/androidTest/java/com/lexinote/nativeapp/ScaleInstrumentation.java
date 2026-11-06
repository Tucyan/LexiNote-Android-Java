package com.lexinote.nativeapp;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import com.lexinote.nativeapp.storage.LibraryStore;
import java.util.concurrent.TimeUnit;

/** Real Android views, persistence and pointer input against reproducible offline datasets. */
public final class ScaleInstrumentation extends Instrumentation {
  boolean testStoreInstalled;
  MainActivity a;
  JSONObject before, metrics = new JSONObject();
  String size, mode;
  File output, testDirectory;
  Repository originalRepository;
  List<String> passed = new ArrayList<>(), failed = new ArrayList<>();
  interface Action { void run() throws Exception; }
  @Override public void onCreate(Bundle args) {
    super.onCreate(args); size=args.getString("size","1000"); mode=args.getString("mode","baseline"); start();
  }
  @Override public void onStart() {
    Bundle result = new Bundle();
    try {
      output=new File(getTargetContext().getExternalFilesDir(null),"scale-"+mode+"-"+size); output.mkdirs();
      long started=SystemClock.elapsedRealtime();
      JSONObject fixture;
      try(InputStream in=getContext().getAssets().open("scale-"+size+".json")) {
        fixture=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));
      }
      Library lib=new Library(fixture);
      metrics.put("decode_and_library_ms",SystemClock.elapsedRealtime()-started);
      // Native decoder must accept the distributable archive as well as the JSON.
      try(InputStream in=getContext().getAssets().open("scale-"+size+".zip")) {
        check(BackupArchive.decode(in.readAllBytes()).backup.optJSONArray("vocabularies").length()==Integer.parseInt(size),"ZIP fixture decode");
      }
      long now=System.currentTimeMillis();
      for(int i=0;i<12;i++) lib.reviewPlans().create("每日阅读 "+(i+1),i<3?"scale-heavy":String.format(Locale.ROOT,"scale-note-%02d",i),20,50,new int[]{1,2,3,4,5,6,7},"Asia/Singapore",false,now);
      try(FileOutputStream out=new FileOutputStream(new File(output,"fixture-with-plans.zip"))) {
        out.write(BackupArchive.encode(lib.backup(),uri -> new byte[0]));
      }
      testDirectory=new File(getTargetContext().getCacheDir(),"scale-repository-"+System.nanoTime());
      check(testDirectory.mkdirs(),"isolated repository directory");
      Repository isolatedRepository = new Repository(testDirectory);
      isolatedRepository.save(lib);
      SqliteLibraryStore backend = new SqliteLibraryStore(getTargetContext(),
          new File(testDirectory, "scale.db").getAbsolutePath());
      backend.initialize();
      check(backend.restore(lib.snapshot(), backend.getGeneration()).isSuccess(), "fixture import failed");
      LibraryStore.setInstanceForTest(new LibraryStore(backend));
      testStoreInstalled = true;
      a=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      awaitStore();
      main(() -> { check(a.library!=null && a.query!=null,"activity did not finish loading");
        before=a.library.snapshot(); originalRepository=a.repository;
        a.repository=isolatedRepository;
        a.cancel(); a.titles.reset(); a.session=null; a.committed=a.library.snapshot();
        a.screen="tabs"; a.page=0; a.render(); });
      if(mode.equals("empty-states")) {
        verify("paused plans remain visible and manageable",() -> main(() -> {
          JSONArray disabled=new JSONArray();
          JSONArray plans=a.library.reviewPlans().list(false);
          for(int i=0;i<plans.length();i++) disabled.put(plans.optJSONObject(i).optString("id"));
          Library.put(a.library.settings(),"disabled_review_plans",disabled);
          a.screen="tabs"; a.page=3; a.review.phase="dashboard"; a.render();
          check(has(a.root,"所有复习计划均已暂停"),"paused guide missing");
          Button manage=findButton(a.root,"⚙ 前往计划管理"); check(manage!=null,"paused manage missing");
          manage.performClick(); check(a.review.phase.equals("manage"),"paused manage action failed");
        }));
        verify("archived-only plans can reopen management",() -> main(() -> {
          JSONArray plans=a.library.reviewPlans().list(false);
          for(int i=0;i<plans.length();i++) a.library.reviewPlans().archive(plans.optJSONObject(i).optString("id"),now);
          a.review=new ReviewUi(a); a.render();
          check(has(a.root,"暂无进行中的复习计划"),"archived-only guide missing");
          Button manage=findButton(a.root,"计划管理"); check(manage!=null,"archived-only manage missing");
          manage.performClick(); check(a.review.phase.equals("manage"),"archived-only manage action failed");
          check(a.library.reviewPlans().list(true).length()==12,"archived plans lost");
        }));
        capture("empty-states");
      } else if(mode.startsWith("title")) {
        main(() -> {a.page=2;a.notebooks.selected="nb_global_root";a.notebooks.filter="project budget";a.render();});
        if(mode.equals("title-legacy")) main(() -> {
          TextView label=(TextView)FeatureInstrumentation.find(a.root,v -> v instanceof TextView && !(v instanceof EditText) && "project budget".contentEquals(((TextView)v).getText()));
          label.setTextIsSelectable(true);
        });
        capture("title-before");
        tap("project budget");
        verify("actual title pointer enters detail and returns to filter",() -> main(() -> {
          check(a.screen.equals("vocabulary"),"title pointer swallowed by selectable label");
          a.handleBack();
          check(a.notebooks.selected.equals("nb_global_root") && a.notebooks.filter.equals("project budget"),"detail return lost scope");
        }));
        capture("title-after");
      } else {
      timed("notebook_global_render",() -> { a.page=2; a.notebooks.selected="nb_global_root"; a.render(); });
      main(() -> metrics.put("global_body_children",a.body.getChildCount()));
      capture("01-global");
      verify("bounded notebook card count",() -> check(metrics.optInt("global_body_children")<=55,"eagerly rendered "+metrics.optInt("global_body_children")+" children"));
      if(!mode.equals("baseline")) {
        verify("pagination reaches final word and returns",() -> {
          tap("末页");
          main(() -> check(has(a.root,"第 "+((Integer.parseInt(size)+39)/40)+" / "+((Integer.parseInt(size)+39)/40)+" 页"),"last page indicator"));
          capture("02-last-page"); tap("首页");
          main(() -> {a.notebooks.selecting=true; a.render();});
          tap("全选当前筛选");
          main(() -> check(a.notebooks.selectedVocabularyIds.size()==Integer.parseInt(size),"selection truncated at page boundary"));
          main(() -> {a.notebooks.selecting=false; a.notebooks.selectedVocabularyIds.clear(); a.render();});
        });
      }
      AccessibilityNodeInfo input=findClass(getUiAutomation().getRootInActiveWindow(),"android.widget.EditText");
      check(input!=null,"search input missing");
      Bundle args=new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"project budget");
      long search=SystemClock.elapsedRealtime(); check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args),"search action failed"); waitForIdleSync();
      metrics.put("search_action_to_idle_ms",SystemClock.elapsedRealtime()-search);
      capture("03-search");
      verify("search returns matching phrase",() -> main(() -> check(a.notebooks.visible().size()==1,"search result count")));
      timed("notebook_heavy_render",() -> { a.notebooks.filter=""; a.notebooks.selected="scale-heavy"; a.render(); });
      capture("04-heavy-notebook");
      timed("review_dashboard",() -> { a.page=3; a.render(); });
      capture("05-review");
      verify("12 review plans visible",() -> main(() -> check(a.library.reviewPlans().list(false).length()==12,"plans missing")));
      awaitStore();
      long written=a.store.currentRevision();
      SystemClock.sleep(50);
      timed("review_same_plan",() -> a.render());
      awaitStore();
      verify("read-only dashboard avoids writes",() -> check(a.store.currentRevision()==written,"read-only dashboard committed a write"));
      timed("review_next_plan",() -> { Button next=findButton(a.root,"›"); check(next!=null,"review next button"); next.performClick(); });
      capture("06-review-next");
      timed("review_start",() -> a.review.startDailyReview());
      tap("查看答案 / 翻转卡片");
      capture("06b-answer");
      long rating=SystemClock.elapsedRealtime(); tap("简单");
      awaitStore();
      metrics.put("rating_action_to_persist_callback_ms",SystemClock.elapsedRealtime()-rating);
      verify("rating persists independent progress",() -> check(backend.loadSnapshot().getRawDocument().optJSONObject("review").optJSONArray("events").length()==1,"rating not persisted"));
      capture("06c-rated");
      timed("long_assistant",() -> { a.page=0; a.session=a.library.find("conversations","scale-chat-0"); a.screen="assistant"; a.render(); });
      capture("07-long-conversation");
      verify("600 message history retained",() -> main(() -> check(a.session.optJSONArray("ai_messages").length()==600,"message loss")));
      timed("local_query",() -> { a.screen="tabs"; a.page=0; a.session=null; a.query.draft="project budget"; a.render(); a.query.submit(); });
      verify("local query stays offline",() -> main(() -> check(a.session.optJSONObject("query_results").optBoolean("is_from_local"),"local miss")));
      capture("08-local-result");
      awaitStore();
      File separateDir=new File(getTargetContext().getCacheDir(),"scale-json-check-"+System.nanoTime());
      check(separateDir.mkdirs(),"separate JSON test directory");
      Repository jsonCheck=new Repository(separateDir);
      long saved=SystemClock.elapsedRealtime(); jsonCheck.save(a.library); metrics.put("repository_save_worker_ms",SystemClock.elapsedRealtime()-saved);
      long zipped=SystemClock.elapsedRealtime(); byte[] bytes=BackupArchive.encode(a.library.backup(),uri -> new byte[0]); metrics.put("zip_roundtrip_ms",SystemClock.elapsedRealtime()-zipped);
      check(BackupArchive.decode(bytes).backup.optJSONArray("vocabularies").length()==Integer.parseInt(size),"roundtrip vocabulary loss");
      passed.add("backup ZIP roundtrip preserves vocabulary");
      verify("over-capacity write preserves prior file",() -> {
        File file=new File(separateDir,"library.json"); byte[] prior=java.nio.file.Files.readAllBytes(file.toPath());
        Library tooLarge=new Library(a.library.snapshot());
        Library.put(tooLarge.settings(),"scale_padding",new String(new char[BackupArchive.MAX_JSON_BYTES]).replace('\0','x'));
        boolean rejected=false;
        try {jsonCheck.save(tooLarge);} catch(IOException expected){rejected=true;}
        check(rejected,"oversized save accepted");
        check(Arrays.equals(prior,java.nio.file.Files.readAllBytes(file.toPath())),"oversized save changed prior data");
      });
      metrics.put("heap_used_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
      {
        verify("empty review guide attached",() -> main(() -> { a.library=new Library(fixture); a.session=null; a.review=new ReviewUi(a); a.screen="tabs"; a.page=3; a.render(); check(has(a.root,"暂无进行中的复习计划"),"empty plan guide missing from hierarchy"); }));
        capture("09-empty-review");
      }
      }
    } catch(Throwable e) { failed.add("RUN: "+android.util.Log.getStackTraceString(e)); }
    finally {
      if(a!=null) try { awaitStore(); main(() -> { a.cancel(); a.titles.reset(); a.session=null; a.finish(); }); } catch(Throwable e) {failed.add("TEARDOWN: "+e);}
      if (testStoreInstalled) LibraryStore.resetInstanceForTest();
      try { metrics.put("passed",new JSONArray(passed));metrics.put("failed",new JSONArray(failed)); try(FileOutputStream out=new FileOutputStream(new File(output,"metrics.json"))){out.write(metrics.toString(2).getBytes(StandardCharsets.UTF_8));} } catch(Exception e){failed.add("METRICS: "+e);}
    }
    result.putString("stream","\n"+metrics.toString()+"\nPASS "+passed.size()+" FAIL "+failed.size()+"\n");
    finish(failed.isEmpty()?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
  }
  void awaitStore() throws Exception {
    LibraryStore store = LibraryStore.getInstance(getTargetContext());
    check(store.awaitIdle(30, TimeUnit.SECONDS), "store did not become idle");
    waitForIdleSync();
  }
  void timed(String name,Action task) throws Exception {
    final long[] duration={0}; long start=SystemClock.elapsedRealtime();
    main(() -> {long nano=System.nanoTime();task.run();duration[0]=(System.nanoTime()-nano)/1000000;});
    metrics.put(name+"_main_ms",duration[0]);metrics.put(name+"_to_idle_ms",SystemClock.elapsedRealtime()-start);
  }
  void verify(String name,Action task) {try{task.run();passed.add(name);}catch(Throwable e){failed.add(name+": "+e);}}
  void main(Action task) {final Throwable[] error={null};runOnMainSync(() -> {try{task.run();}catch(Throwable e){error[0]=e;}});waitForIdleSync();if(error[0]!=null)throw new RuntimeException(error[0]);}
  static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
  static boolean has(View root,String label){return FeatureInstrumentation.find(root,v -> v instanceof TextView && label.contentEquals(((TextView)v).getText()))!=null;}
  static Button findButton(View root,String label){View v=FeatureInstrumentation.find(root,w -> w instanceof Button && label.contentEquals(((Button)w).getText()));return (Button)v;}
  static AccessibilityNodeInfo findClass(AccessibilityNodeInfo n,String name){if(n==null)return null;if(name.contentEquals(n.getClassName()))return n;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo r=findClass(n.getChild(i),name);if(r!=null)return r;}return null;}
  static AccessibilityNodeInfo targetNode(AccessibilityNodeInfo node,String label) {
    if(node==null)return null;
    if(label.contentEquals(node.getText()==null?"":node.getText()) && !"android.widget.EditText".contentEquals(node.getClassName()))return node;
    for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo result=targetNode(node.getChild(i),label);if(result!=null)return result;}
    return null;
  }
  void tap(String label) {
    AccessibilityNodeInfo n=targetNode(getUiAutomation().getRootInActiveWindow(),label);check(n!=null,"missing visible "+label);
    Rect r=new Rect();n.getBoundsInScreen(r);check(!r.isEmpty(),"offscreen "+label);
    long t=SystemClock.uptimeMillis();sendPointerSync(MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,r.centerX(),r.centerY(),0));sendPointerSync(MotionEvent.obtain(t,t+60,MotionEvent.ACTION_UP,r.centerX(),r.centerY(),0));waitForIdleSync();
  }
  void capture(String name) throws Exception {waitForIdleSync(); SystemClock.sleep(250); waitForIdleSync();try(FileOutputStream out=new FileOutputStream(new File(output,name+".png"))){Bitmap b=getUiAutomation().takeScreenshot();check(b!=null,"screenshot missing");b.compress(Bitmap.CompressFormat.PNG,100,out);b.recycle();}}
}
