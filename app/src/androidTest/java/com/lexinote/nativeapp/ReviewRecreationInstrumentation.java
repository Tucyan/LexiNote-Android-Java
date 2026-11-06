package com.lexinote.nativeapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.lexinote.nativeapp.storage.LibraryStore;
import com.lexinote.nativeapp.storage.contract.*;
import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Recreates the real review screen while an isolated SQLite rating is in flight. */
public final class ReviewRecreationInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  @Override public void onStart() {
    Bundle report = new Bundle(); int status = Activity.RESULT_OK;
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicInteger submissions = new AtomicInteger(); AtomicReference<String> eventId = new AtomicReference<>();
    MainActivity first = null, replacement = null;
    try {
      File path = new File(getTargetContext().getCacheDir(), "review-recreate-" + System.nanoTime() + ".db");
      SqliteLibraryStore sqlite = new SqliteLibraryStore(getTargetContext(), path.getAbsolutePath());
      sqlite.initialize();
      Library lib = new Library(); String note = lib.createNotebook("复习测试", null);
      String word = lib.saveResult(new JSONObject("{word:'recreate',meanings:[{zh_definition:'重建'}]}"), new String[]{note}, new JSONObject());
      long now = System.currentTimeMillis();
      String plan = lib.reviewPlans().create("测试计划", note, 1, 1, new int[]{1,2,3,4,5,6,7}, java.time.ZoneId.systemDefault().getId(), false, now);
      lib.reviewPlans().dailyQueue(plan, now);
      if (!sqlite.restore(lib.snapshot(), sqlite.getGeneration()).isSuccess()) throw new AssertionError("fixture import failed");
      StoreBackend blocking = new StoreBackend() {
        public void initialize() throws Exception { sqlite.initialize(); }
        public long getGeneration() { return sqlite.getGeneration(); }
        public long getRevision() { return sqlite.getRevision(); }
        public ReadOnlyView loadSnapshot() throws Exception { return sqlite.loadSnapshot(); }
        public ReadOnlyView query(QueryRequest request) throws Exception { return sqlite.query(request); }
        public CommitResult execute(LibraryCommand command) throws Exception {
          if (command.getType() == LibraryCommand.Type.RECORD_REVIEW) {
            submissions.incrementAndGet(); eventId.set(command.getEventId()); entered.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("rating gate timed out");
          }
          return sqlite.execute(command);
        }
        public CommitResult applyTransaction(List<LibraryCommand> commands) throws Exception { return sqlite.applyTransaction(commands); }
        public CommitResult restore(JSONObject backup, long generation) throws Exception { return sqlite.restore(backup, generation); }
        public CommitResult rollback(long generation) throws Exception { return sqlite.rollback(generation); }
        public void migrateFrom(File file) throws Exception { sqlite.migrateFrom(file); }
        public void close() { sqlite.close(); }
      };
      LibraryStore testStore = new LibraryStore(blocking);
      LibraryStore.setInstanceForTest(testStore);
      first = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      DeviceAsync.ready(this, first);
      final MainActivity original = first;
      runOnMainSync(() -> {
        original.page = 3; original.screen = "tabs"; original.review.activePlanId = plan;
        original.review.startDailyReview(); original.review.revealed = true; original.render();
        View rating = ratingButton(original.root, "简单");
        if (rating == null || !rating.performClick()) throw new AssertionError("rating button not clickable");
      });
      if (!entered.await(10, TimeUnit.SECONDS)) throw new AssertionError("rating did not reach worker");
      ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
      runOnMainSync(original::recreate);
      Activity recreated = waitForMonitorWithTimeout(monitor, 10000);
      removeMonitor(monitor);
      if (!(recreated instanceof MainActivity) || recreated == original) throw new AssertionError("Activity was not recreated");
      replacement = (MainActivity) recreated;
      release.countDown();
      DeviceAsync.ready(this, replacement);
      DeviceAsync.drain(this, replacement);
      long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
      boolean[] settled = {false};
      final MainActivity current = replacement;
      while (android.os.SystemClock.elapsedRealtime() < deadline) {
        runOnMainSync(() -> settled[0] = current.review != null && !current.review.ratingState.isSubmitting());
        if (settled[0]) break;
        Thread.sleep(20);
      }
      if (!settled[0]) throw new AssertionError("rating recovery did not settle");
      DeviceAsync.drain(this, current);
      JSONObject document = sqlite.loadSnapshot().getRawDocument();
      org.json.JSONArray events = document.getJSONObject("review").getJSONArray("events");
      if (submissions.get() != 1 || events.length() != 1 || !events.getJSONObject(0).getString("id").equals(eventId.get()) || !events.getJSONObject(0).getString("vocab_id").equals(word)) throw new AssertionError("rating duplicated or changed after recreation");
      runOnMainSync(() -> {
        if (current.review.sessionSummary.easyCount != 1) throw new AssertionError("recreated session did not account for committed rating");
      });
      report.putString("stream", "PASS: Activity recreation retained one accepted rating and did not resend it");
    } catch (Throwable error) { status = Activity.RESULT_CANCELED; report.putString("stream", android.util.Log.getStackTraceString(error)); }
    finally {
      release.countDown();
      MainActivity current = replacement != null ? replacement : first;
      if (current != null) { MainActivity last = current; runOnMainSync(last::finish); }
      LibraryStore.resetInstanceForTest();
    }
    finish(status, report);
  }
  private static View ratingButton(View root, String label) {
    if (root instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) root;
      for (int i = 0; i < group.getChildCount(); i++) {
        View child = group.getChildAt(i);
        if (child instanceof TextView && label.contentEquals(((TextView) child).getText()) && group.isClickable()) return group;
        View found = ratingButton(child, label); if (found != null) return found;
      }
    }
    return null;
  }
}
