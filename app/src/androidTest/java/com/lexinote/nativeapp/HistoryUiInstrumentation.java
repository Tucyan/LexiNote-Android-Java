package com.lexinote.nativeapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import com.lexinote.nativeapp.storage.LibraryStore;
import com.lexinote.nativeapp.storage.contract.*;
import java.io.*;
import java.util.concurrent.TimeUnit;
import org.json.*;

/** Low-heap diagnostic: actual Activity with a streamed, isolated historical fixture. */
public final class HistoryUiInstrumentation extends Instrumentation {
  private MainActivity activity;
  private String stage = "fixture";
  private final JSONObject metrics = new JSONObject();
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  private void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
  private void idle() throws Exception {
    require(LibraryStore.getInstance(getTargetContext()).awaitIdle(30, TimeUnit.SECONDS), "store timeout");
    waitForIdleSync();
  }
  private long count(File db, long generation, String table, String extra) {
    try (SQLiteDatabase read = SQLiteDatabase.openDatabase(db.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
         Cursor row = read.rawQuery("SELECT COUNT(*) FROM " + table + " WHERE generation=?" + extra,
             new String[]{Long.toString(generation)})) { row.moveToFirst(); return row.getLong(0); }
  }
  private long referenceCount(File db, long generation) throws Exception {
    long count = 0;
    try (SQLiteDatabase read = SQLiteDatabase.openDatabase(db.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
         Cursor rows = read.rawQuery("SELECT json FROM entities WHERE generation=? AND kind='notebooks'",
             new String[]{Long.toString(generation)})) {
      while (rows.moveToNext()) {
        JSONArray references = new JSONObject(rows.getString(0)).optJSONArray("vocabulary_ids");
        if (references != null) count += references.length();
      }
    }
    return count;
  }
  private static View ratingButton(View root, String label) {
    if (!(root instanceof ViewGroup)) return null;
    ViewGroup group = (ViewGroup) root;
    for (int i = 0; i < group.getChildCount(); i++) {
      View child = group.getChildAt(i);
      if (child instanceof TextView && label.contentEquals(((TextView) child).getText()) && group.isClickable()) return group;
      View found = ratingButton(child, label);
      if (found != null) return found;
    }
    return null;
  }
  @Override public void onStart() {
    Bundle result = new Bundle(); int status = Activity.RESULT_OK;
    boolean installed = false;
    try {
      long begin = SystemClock.elapsedRealtime();
      File dir = new File(getTargetContext().getCacheDir(), "history-ui-" + System.nanoTime());
      require(dir.mkdirs(), "cannot create cache directory");
      File seed = new File(dir, "seed.json"), db = new File(dir, "library.db");
      try (InputStream in = getContext().getAssets().open("scale-3000-events-10000.json");
           OutputStream out = new BufferedOutputStream(new FileOutputStream(seed))) {
        byte[] buffer = new byte[32768]; int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      }
      SqliteLibraryStore backend = new SqliteLibraryStore(getTargetContext(), db.getAbsolutePath());
      backend.initialize();
      CommitResult imported = backend.importJson(seed, backend.getGeneration());
      require(imported.isSuccess(), "import failed: " + imported.getMessage());
      long generation = backend.getGeneration();
      require(count(db, generation, "entities", " AND kind='vocabularies'") == 3000, "vocabulary count");
      require(count(db, generation, "review_events", "") == 10000, "event count");
      long references = referenceCount(db, generation);
      metrics.put("notebook_references", references);
      String plan;
      try (SQLiteDatabase read = SQLiteDatabase.openDatabase(db.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
           Cursor row = read.rawQuery("SELECT id FROM review_plans WHERE generation=? ORDER BY ordinal LIMIT 1", new String[]{Long.toString(generation)})) {
        require(row.moveToFirst(), "missing plan"); plan = row.getString(0);
      }
      long now = System.currentTimeMillis();
      CommitResult prepared = backend.execute(LibraryCommand.prepareDailyQueue(generation, plan, now));
      require(prepared.isSuccess(), "queue preparation failed");
      require(prepared.getUpdatedEntity().getJSONArray("days").getJSONObject(0).getJSONArray("tasks").length() > 0, "empty queue");
      metrics.put("fixture_ms", SystemClock.elapsedRealtime() - begin);
      LibraryStore.setInstanceForTest(new LibraryStore(backend)); installed = true;
      stage = "activity_startup"; begin = SystemClock.elapsedRealtime();
      activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      idle();
      require(activity.library != null && activity.query != null && !activity.recoveryOnly, "activity did not load");
      metrics.put("startup_ms", SystemClock.elapsedRealtime() - begin);
      stage = "search"; begin = SystemClock.elapsedRealtime();
      runOnMainSync(() -> {
        activity.screen = "tabs"; activity.page = 2; activity.notebooks.selected = "nb_global_root";
        activity.notebooks.filter = ""; activity.notebooks.resultPage = 0; activity.render();
        View found = FeatureInstrumentation.find(activity.root, v -> v instanceof EditText && "搜索词汇、含义或例句…".contentEquals(((EditText)v).getHint()));
        require(found != null, "search field missing"); ((EditText)found).setText("project budget");
      });
      long deadline = SystemClock.uptimeMillis() + 30000; boolean found = false;
      while (SystemClock.uptimeMillis() < deadline) {
        final boolean[] match = {false};
        runOnMainSync(() -> match[0] = FeatureInstrumentation.find(activity.root, v -> v instanceof TextView && "结果 1 条".contentEquals(((TextView)v).getText())) != null);
        if (match[0]) { found = true; break; } SystemClock.sleep(20);
      }
      require(found, "search result missing"); metrics.put("search_ms", SystemClock.elapsedRealtime() - begin);
      stage = "vocabulary_detail"; begin = SystemClock.elapsedRealtime();
      runOnMainSync(() -> {
        View item = FeatureInstrumentation.find(activity.root, v -> v instanceof TextView
            && !(v instanceof EditText) && "project budget".contentEquals(((TextView)v).getText()));
        while (item != null && !item.isClickable())
          item = item.getParent() instanceof View ? (View)item.getParent() : null;
        require(item != null && item.performClick(), "vocabulary entry unavailable");
      });
      waitForIdleSync();
      require("vocabulary".equals(activity.screen), "vocabulary detail did not open");
      metrics.put("vocabulary_detail_ms", SystemClock.elapsedRealtime() - begin);
      stage = "local_query"; begin = SystemClock.elapsedRealtime();
      runOnMainSync(() -> {
        activity.screen = "tabs"; activity.page = 0; activity.session = null;
        activity.query.draft = "project budget"; activity.render(); activity.query.submit();
      });
      idle();
      require(activity.session != null && activity.session.optJSONObject("query_results") != null
          && activity.session.optJSONObject("query_results").optBoolean("is_from_local"), "local query missed");
      metrics.put("local_query_ms", SystemClock.elapsedRealtime() - begin);
      stage = "rating"; begin = SystemClock.elapsedRealtime();
      runOnMainSync(() -> {
        activity.screen = "tabs"; activity.page = 3; activity.review = new ReviewUi(activity);
        activity.review.activePlanId = plan; activity.render(); activity.review.startDailyReview();
      });
      deadline = SystemClock.uptimeMillis() + 30000; boolean active = false;
      while (SystemClock.uptimeMillis() < deadline) {
        idle(); final boolean[] ready = {false};
        runOnMainSync(() -> ready[0] = "active".equals(activity.review.phase));
        if (ready[0]) { active = true; break; }
        SystemClock.sleep(20);
      }
      require(active, "review did not present an active card");
      final boolean[] clicked = {false};
      runOnMainSync(() -> {
        activity.review.revealed = true; activity.render();
        View button = ratingButton(activity.root, "简单");
        clicked[0] = button != null && button.performClick();
      });
      require(clicked[0], "rating control missing or not clickable");
      idle();
      require(count(db, generation, "review_events", "") == 10001, "rating did not append exactly once");
      metrics.put("rating_ms", SystemClock.elapsedRealtime() - begin);
      stage = "reopen"; begin = SystemClock.elapsedRealtime();
      runOnMainSync(() -> activity.finish()); waitForIdleSync();
      LibraryStore.resetInstanceForTest(); installed = false;
      SqliteLibraryStore reopened = new SqliteLibraryStore(getTargetContext(), db.getAbsolutePath());
      reopened.initialize();
      require(reopened.getGeneration() == generation, "reopened generation changed");
      LibraryStore.setInstanceForTest(new LibraryStore(reopened)); installed = true;
      activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); idle();
      require(activity.library != null && !activity.recoveryOnly, "reopen failed");
      require(count(db, generation, "entities", " AND kind='vocabularies'") == 3000, "reopened vocabulary count");
      require(count(db, generation, "review_events", "") == 10001, "reopened event count");
      require(referenceCount(db, generation) == references, "reopened notebook references changed");
      metrics.put("reopen_ms", SystemClock.elapsedRealtime() - begin); stage = "passed";
    } catch (Throwable error) {
      status = Activity.RESULT_CANCELED;
      result.putString("error", android.util.Log.getStackTraceString(error));
    } finally {
      if (activity != null) try { runOnMainSync(() -> activity.finish()); } catch (Throwable ignored) { }
      if (installed) LibraryStore.resetInstanceForTest();
    }
    result.putString("stream", "stage=" + stage + " metrics=" + metrics + "\n" + result.getString("error", ""));
    finish(status, result);
  }
}
