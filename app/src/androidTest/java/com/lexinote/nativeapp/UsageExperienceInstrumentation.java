package com.lexinote.nativeapp;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Offline regression of the nine issues found with the normal-usage backup. */
public final class UsageExperienceInstrumentation extends Instrumentation {
  MainActivity a;
  JSONObject before, fixture;
  TimeZone originalZone;
  String focus;
  final List<String> passed = new ArrayList<>(), failed = new ArrayList<>();
  interface Action { void run() throws Exception; }

  public void onCreate(Bundle args) {
    super.onCreate(args);
    focus = args == null ? "" : args.getString("focus", "");
    start();
  }

  public void onStart() {
    Bundle result = new Bundle();
    try {
      try (InputStream input = getContext().getAssets().open("normal-usage.json")) {
        fixture = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
      }
      originalZone = TimeZone.getDefault();
      a = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      waitForIdleSync();
      main(() -> before = a.library.snapshot());
      if (focus.equals("context")) {
        runCase("UX02 query context entering assistant survives history clear", this::queryContext);
      } else if (focus.equals("quota")) {
        runCase("UX02 empty draft preserves quota then first message evicts oldest", this::sixthTemporary);
      } else {
      if (!focus.equals("root")) {
        runCase("UX01 local query preserves all five AI sessions", this::localQuery);
        runCase("UX02 sixth AI session evicts oldest by time", this::sixthTemporary);
        runCase("UX02 query context entering assistant survives history clear", this::queryContext);
        runCase("UX03 Again never increases mastery and denominator stays ten", this::review);
        runCase("UX04 source searches text values and selects four", this::search);
        runCase("UX05 unsubmitted search survives type switch", this::searchDraft);
      }
      runCase("UX06 saved-day filter and display use local timezone", this::dates);
      runCase("UX07 recent deletion/clear refreshes open dialog", this::recentDelete);
      if (!focus.equals("root")) {
        runCase("UX08 parent and filtered-empty states differ", this::emptyStates);
        runCase("UX09 actual title tap opens full vocabulary", this::titleTap);
      }
      }
    } catch (Throwable error) {
      failed.add("SETUP: " + android.util.Log.getStackTraceString(error));
    } finally {
      if (originalZone != null) TimeZone.setDefault(originalZone);
      if (a != null && before != null) {
        try {
          main(() -> {
            a.cancel(); a.titles.reset(); a.session = null; a.query.assistantView = null;
            a.library = new Library(before); a.repository.save(a.library); a.finish();
          });
        } catch (Throwable error) { failed.add("RESTORE: " + error); }
      }
    }
    result.putString("stream", "\nPASS " + passed.size() + "\n" + String.join("\n", passed)
        + "\nFAIL " + failed.size() + "\n" + String.join("\n", failed) + "\n");
    finish(failed.isEmpty() ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
  }

  void runCase(String name, Action action) {
    try {
      main(() -> {
        a.cancel(); a.titles.reset(); a.session = null; a.clearImage();
        a.query = new QueryUi(a); a.notebooks = new NotebookUi(a); a.review = new ReviewUi(a);
        a.library = new Library(Library.copy(fixture));
        a.screen = "tabs"; a.page = 0; a.render();
      });
      action.run();
      capture(name.contains("context") ? "UX02-context" : name.substring(0, 4));
      passed.add(name);
    } catch (Throwable error) {
      failed.add(name + ": " + error);
      try { capture(name.substring(0, 4) + "-failed"); } catch (Exception ignored) {}
    } finally {
      // Dismiss owned dialogs through their buttons. Repeated Back during a window-focus
      // transition can accidentally finish the Activity and produce invisible false positives.
      for (int n=0; n<3; n++) {
        AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow(), close=null;
        for(String text:new String[]{"完成","关闭","取消"}) {
          AccessibilityNodeInfo candidate=exactNode(root,text);
          if(candidate!=null && "android.widget.Button".contentEquals(candidate.getClassName())) {
            close=candidate; break;
          }
        }
        if(close==null || !close.performAction(AccessibilityNodeInfo.ACTION_CLICK)) break;
        waitForIdleSync(); SystemClock.sleep(100);
      }
    }
  }

  void localQuery() {
    main(() -> { a.query.draft = "budget"; a.query.submit(); });
    main(() -> {
      check(a.session.getJSONObject("query_results").getBoolean("is_from_local"), "not local");
      check(a.session.getJSONObject("metadata").getString("retention").equals("query"), "query used temporary quota");
      check(countRetention("temporary") == 5 && messages() == 54, "AI messages evicted");
      check(a.library.find("conversations", "usage-query-10") != null, "newest agenda lost");
      check(a.library.array("recent_queries").length() == 10, "history count");
      check(a.library.find("recent_queries", "usage-query-1") == null, "oldest deadline retained");
      check(a.library.find("recent_queries", "usage-query-10") != null, "newest agenda history lost");
      a.library = new Library(a.library.backup());
      check(messages() == 54, "roundtrip lost messages");

    });
  }

  void sixthTemporary() {
    main(() -> {
      button(a.root, "＋ 新建").performClick();
      check(a.screen.equals("assistant"), "new did not enter assistant");
      check(a.library.find("conversations",a.session.optString("id")) == null, "empty draft consumed quota");
      check(messages() == 54, "empty draft evicted AI messages");
      a.query.message("user","offline first-message quota check",null);
      Library.promoteTemporary(a.session);
      a.library.touchSession(a.session, false);
      check(countRetention("temporary") == 5, "sixth did not apply cap");
      check(a.library.find("conversations", "usage-query-10") != null, "newest evicted");
      check(a.library.find("conversations", "usage-assistant-1") == null, "oldest tie was not evicted");
      check(countRetention("saved") == 8, "saved lost");

    });
  }

  void queryContext() {
    main(() -> {
      a.query.draft="budget"; a.query.submit();
      String id=a.session.optString("id");
      button(a.root,"打开 AI 助手继续提问").performClick();
      check(Library.isTemporary(a.session),"nonempty query context was not promoted");
      JSONArray recent=a.library.array("recent_queries");
      while(recent.length()>0) a.library.delete("recent_queries",recent.optJSONObject(0).optString("id"));
      check(a.library.find("conversations",id)!=null,"AI context lost on history clear");
    });
  }

  void review() {
    main(() -> {
      a.page = 3; a.render();
      check(has(a.root, "16"), "legacy grade-aware baseline is not16");
      button(a.root, "默认笔记 (20)").performClick();
      button(a.root, "开启本组复习").performClick();
      check(has(a.root, "agenda"), "first due card");
      button(a.root, "显示答案").performClick(); button(a.root, "简单").performClick();
      check(has(a.root, "budget"), "second due card");
      button(a.root, "显示答案").performClick(); button(a.root, "再次").performClick();
      check(has(a.root, "已完成 1/10 张"), "Again changed group denominator");
      a.review = new ReviewUi(a); a.render();
      check(has(a.root, "16"), "Again changed grade-aware mastery baseline");

    });
  }

  void search() {
    main(() -> {
      a.page = 2; a.render();
      edit(a.root, "搜索词汇、含义或例句…").setText("source");
    });
    main(() -> {
      check(a.notebooks.visible().size() == 4, "source matched JSON keys");
      button(a.root, "批量管理").performClick(); button(a.root, "全选当前筛选").performClick();
      check(has(a.root, "操作 (4)"), "batch selection exceeds visible text matches");

    });
  }

  void searchDraft() {
    main(() -> {
      a.page = 2; a.render();
      edit(a.root, "搜索词汇、含义或例句…").setText("meeting");
      button(a.root, "短语").performClick();
      check(edit(a.root, "搜索词汇、含义或例句…").getText().toString().equals("meeting"), "draft lost");
      check(a.notebooks.filter.equals("meeting"), "filter state lost");
      check(a.notebooks.visible().size() < 40, "type ignored text");

    });
  }

  void dates() {
    main(() -> {
      TimeZone.setDefault(TimeZone.getTimeZone("Asia/Singapore"));
      a.query.dateField = "last_used_at"; a.query.startDate = "2026-10-28";
      a.query.endDate = "2026-10-28"; a.query.showDateFields = true;
      int matched = 0;
      JSONArray all = a.library.array("conversations");
      for (int i = 0; i < all.length(); i++)
        if (Library.isSaved(all.getJSONObject(i)) && a.query.matchesDate(all.getJSONObject(i))) matched++;
      check(matched == 8, "expected8 local-day sessions, actual" + matched);
      check(a.query.sessionDate(a.library.find("conversations", "usage-query-5"), "last_used_at")
          .startsWith("2026-10-28 07:00"), "date still displayed in UTC");
      a.render();
    });
  }

  void recentDelete() {
    main(() -> a.query.showRecent());
    AccessibilityNodeInfo context = awaitNode("context");
    check(context != null, "context not in recent dialog");
    AccessibilityNodeInfo delete = exactNode(context.getParent(), "删除");
    check(delete != null && delete.performAction(AccessibilityNodeInfo.ACTION_CLICK), "delete failed");
    waitForIdleSync();
    main(() -> check(a.library.array("recent_queries").length() == 9, "delete not persisted"));
    check(exactNode(getUiAutomation().getRootInActiveWindow(), "context") == null, "deleted row remains");
    AccessibilityNodeInfo clear = awaitNode("清空最近查询");
    check(clear != null && clear.performAction(AccessibilityNodeInfo.ACTION_CLICK), "clear action missing");
    waitForIdleSync();
    AccessibilityNodeInfo confirm = awaitNode("清空");
    check(confirm != null && confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK), "clear confirmation missing");
    waitForIdleSync();
    check(awaitNode("暂无最近查询") != null, "empty dialog not refreshed");
    main(() -> { check(a.library.array("recent_queries").length() == 0, "clear not persisted");  });
  }

  void emptyStates() {
    main(() -> {
      a.page = 2; a.notebooks.selected = "usage-topics"; a.render();
      check(!has(a.root, "此分类暂无词汇记录"), "parent falsely empty");
      a.notebooks.selected = null; a.notebooks.filter = "zzzzNoMatch"; a.render();
      check(!has(a.root, "此分类暂无词汇记录"), "no-match falsely empty");
      check(has(a.root, "未找到匹配内容"), "no-match guidance missing");

    });
  }

  void titleTap() {
    main(() -> { a.page = 2; a.notebooks.filter = "budget"; a.render(); });
    final int[] point = new int[2];
    main(() -> {
      View title = FeatureInstrumentation.find(a.root,
          v -> v instanceof TextView && !(v instanceof EditText) && "budget".contentEquals(((TextView)v).getText()));
      check(title != null, "budget title missing");
      title.getLocationOnScreen(point); point[0] += title.getWidth()/2; point[1] += title.getHeight()/2;
    });
    long now = SystemClock.uptimeMillis();
    sendPointerSync(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, point[0], point[1], 0));
    sendPointerSync(MotionEvent.obtain(now, now+50, MotionEvent.ACTION_UP, point[0], point[1], 0));
    waitForIdleSync();
    check(exactNode(getUiAutomation().getRootInActiveWindow(), "词库完整内容") != null, "title swallowed tap");

  }

  int countRetention(String kind) {
    int count=0; JSONArray all=a.library.array("conversations");
    for(int i=0;i<all.length();i++) if(all.optJSONObject(i).optJSONObject("metadata") != null
        && kind.equals(all.optJSONObject(i).optJSONObject("metadata").optString("retention"))) count++;
    return count;
  }
  int messages() {
    int count=0; JSONArray all=a.library.array("conversations");
    for(int i=0;i<all.length();i++) count+=all.optJSONObject(i).optJSONArray("ai_messages").length();
    return count;
  }
  void main(Action action) {
    final Throwable[] error={null};
    runOnMainSync(() -> {try{action.run();}catch(Throwable e){error[0]=e;}});
    waitForIdleSync(); if(error[0]!=null) throw new RuntimeException(error[0]);
  }
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  static Button button(View root,String text){return FeatureInstrumentation.button(root,text);}
  static EditText edit(View root,String hint){return FeatureInstrumentation.edit(root,hint);}
  static boolean has(View root,String text){
    return FeatureInstrumentation.find(root,v->v instanceof TextView&&text.contentEquals(((TextView)v).getText()))!=null;
  }
  static AccessibilityNodeInfo exactNode(AccessibilityNodeInfo node,String text){
    if(node==null)return null;
    if(text.contentEquals(node.getText()==null?"":node.getText()))return node;
    for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=exactNode(node.getChild(i),text);if(found!=null)return found;}
    return null;
  }
  AccessibilityNodeInfo awaitNode(String text) {
    long end=SystemClock.uptimeMillis()+3500;
    do {
      AccessibilityNodeInfo found=exactNode(getUiAutomation().getRootInActiveWindow(),text);
      if(found!=null) return found;
      SystemClock.sleep(100);
    } while(SystemClock.uptimeMillis()<end);
    return null;
  }
  void capture(String name) throws Exception {
    waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync();
    File folder=new File(a.getExternalFilesDir(null),"usage-fixes");folder.mkdirs();
    try(FileOutputStream out=new FileOutputStream(new File(folder,name+".png"))){
      getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);
    }
  }
}
