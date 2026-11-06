package com.lexinote.nativeapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Trace;
import android.text.Spannable;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.TextView;
import com.lexinote.nativeapp.storage.LibraryStore;
import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import org.json.JSONArray;
import org.json.JSONObject;

/** Isolated, in-memory search interaction checks and trace markers. */
public final class NotebookSearchInstrumentation extends Instrumentation {
  private boolean testStoreInstalled;
  private MainActivity activity;
  private EditText input;
  private Throwable failure;
  @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
  @Override public void onStart() {
    Bundle result = new Bundle();
    try {
      File dir = new File(getTargetContext().getCacheDir(), "search-test-" + System.nanoTime());
      if (!dir.mkdirs()) throw new AssertionError("isolated directory unavailable");
      SqliteLibraryStore backend = new SqliteLibraryStore(getTargetContext(),
          new File(dir, "search.db").getAbsolutePath());
      backend.initialize();
      LibraryStore.setInstanceForTest(new LibraryStore(backend));
      testStoreInstalled = true;
      activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      if (!LibraryStore.getInstance(getTargetContext()).awaitIdle(30, TimeUnit.SECONDS))
        throw new AssertionError("store did not load");
      waitForIdleSync();
      runOnMainSync(() -> {
        if (activity.library == null || activity.query == null) throw new AssertionError("activity did not load");
        activity.library = new Library();
        JSONArray vocab = activity.library.array("vocabularies");
        for (int i = 0; i < 100; i++) {
          JSONObject item = new JSONObject();
          Library.put(item, "id", "perf-" + i);
          Library.put(item, "content", i == 99 ? "needle" : "ordinary-" + i);
          Library.put(item, "tags", new JSONArray());
          Library.put(item, "meanings", new JSONArray());
          vocab.put(item);
        }
        activity.page = 2;
        activity.screen = "tabs";
        activity.notebooks.selected = null;
        activity.notebooks.filter = "";
        activity.render();
        input = (EditText) FeatureInstrumentation.find(activity.root, v -> v instanceof EditText);
        if (input == null) throw new AssertionError("search input missing");
        input.requestFocus();
        input.setText("nee");
        BaseInputConnection.setComposingSpans((Spannable) input.getText());
      });
      waitForIdleSync();
      runOnMainSync(() -> {
        View current = FeatureInstrumentation.find(activity.root, v -> v instanceof EditText);
        if (current != input) throw new AssertionError("bookshelf replaced the input");
        if (BaseInputConnection.getComposingSpanStart(input.getText()) < 0)
          throw new AssertionError("composition span lost");
      });
      long start = SystemClock.elapsedRealtime();
      Trace.beginSection("search.interaction");
      runOnMainSync(() -> {
        input.setText("ordinary");
        if (activity.body.isEnabled()) throw new AssertionError("stale result area remains enabled");
        input.setText("needle");
      });
      boolean found = false;
      for (int i = 0; i < 120 && !found; i++) {
        SystemClock.sleep(25);
        final boolean[] hit = {false};
        runOnMainSync(() -> hit[0] = activity.body.isEnabled()
            && FeatureInstrumentation.find(activity.body, v -> v instanceof TextView
                && !(v instanceof EditText) && "needle".contentEquals(((TextView) v).getText())) != null
            && FeatureInstrumentation.find(activity.root, v -> v instanceof TextView
                && "结果 1 条".contentEquals(((TextView) v).getText())) != null);
        found = hit[0];
      }
      Trace.endSection();
      if (!found) throw new AssertionError("latest query did not render");
      result.putLong("search_ms", SystemClock.elapsedRealtime() - start);
      CountDownLatch workerEntered = new CountDownLatch(1), workerRelease = new CountDownLatch(1);
      activity.notebooks.searchExecutor.submit(() -> {
        workerEntered.countDown();
        try { workerRelease.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      });
      if (!workerEntered.await(5, TimeUnit.SECONDS)) throw new AssertionError("search worker did not start");
      try {
        runOnMainSync(() -> input.setText("ordinary"));
        SystemClock.sleep(150);
        waitForIdleSync();
        runOnMainSync(() -> {
          JSONObject first = activity.library.array("vocabularies").optJSONObject(0);
          Library.put(first, "content", "changed");
          activity.dataRevision++;
        });
      } finally { workerRelease.countDown(); }
      if (!waitForResult("结果 98 条")) throw new AssertionError("stale search was not retried");
      runOnMainSync(() -> input.setText("needle"));
      if (!waitForResult("结果 1 条")) throw new AssertionError("latest search did not recover");
      runOnMainSync(() -> {
        activity.notebooks.selecting = true;
        activity.render();
        View originalBody = activity.body;
        CheckBox box = (CheckBox) FeatureInstrumentation.find(activity.body, v -> v instanceof CheckBox);
        if (box == null) throw new AssertionError("selection checkbox missing");
        box.performClick();
        if (activity.body != originalBody || !box.isAttachedToWindow())
          throw new AssertionError("selection rebuilt the whole page");
        if (activity.notebooks.selectedVocabularyIds.size() != 1 || !box.isChecked())
          throw new AssertionError("selection state was not updated");
        activity.notebooks.filter = "";
        activity.notebooks.selected = "nb_global_root";
        activity.render();
        View currentBody = activity.body;
        View selectAll = FeatureInstrumentation.find(activity.root, v -> v instanceof TextView
            && "全选当前筛选".contentEquals(((TextView) v).getText()));
        if (selectAll == null || !selectAll.performClick()) throw new AssertionError("select all unavailable");
        if (activity.body != currentBody || activity.notebooks.selectedVocabularyIds.size() != 100)
          throw new AssertionError("select all did not cover all pages without rebuilding");
        View clear = FeatureInstrumentation.find(activity.root, v -> v instanceof TextView
            && "清空".contentEquals(((TextView) v).getText()));
        if (clear == null || !clear.performClick() || !activity.notebooks.selectedVocabularyIds.isEmpty())
          throw new AssertionError("clear selection failed");
        activity.notebooks.selecting = false;
        activity.notebooks.selectedVocabularyIds.clear();
        activity.render();
        input = (EditText) FeatureInstrumentation.find(activity.root, v -> v instanceof EditText);
      });
      runOnMainSync(() -> {
        input.setText("ordinary");
        activity.page = 3;
        activity.render();
      });
      SystemClock.sleep(250);
      waitForIdleSync();
      runOnMainSync(() -> {
        if (activity.page != 3 || FeatureInstrumentation.find(activity.root, v ->
            v instanceof TextView && "结果 99 条".contentEquals(((TextView) v).getText())) != null)
          throw new AssertionError("detached search overwrote navigation");
      });
      Trace.beginSection("notebook.scroll.sample");
      runOnMainSync(() -> activity.scroll.scrollTo(0, activity.body.getHeight()));
      waitForIdleSync();
      Trace.endSection();
      result.putString("status", "passed");
    } catch (Throwable error) {
      failure = error;
      result.putString("error", error.toString());
    } finally {
      if (activity != null) runOnMainSync(() -> { activity.session = null; activity.finish(); });
      if (testStoreInstalled) LibraryStore.resetInstanceForTest();
    }
    finish(failure == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
  }
  private boolean waitForResult(String label) {
    for (int i = 0; i < 120; i++) {
      SystemClock.sleep(25);
      final boolean[] found = {false};
      runOnMainSync(() -> found[0] = activity.body.isEnabled()
          && FeatureInstrumentation.find(activity.root, v -> v instanceof TextView
              && label.contentEquals(((TextView) v).getText())) != null);
      if (found[0]) return true;
    }
    return false;
  }

}
