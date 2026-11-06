package com.lexinote.nativeapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import com.lexinote.nativeapp.storage.LibraryStore;
import com.lexinote.nativeapp.storage.contract.*;
import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reproduces editing a result while its initial history save is still in flight. */
public final class QuerySaveRaceInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

  @Override public void onStart() {
    Bundle report = new Bundle();
    MainActivity activity = null;
    BlockingBackend backend = null;
    boolean installed = false;
    Throwable failure = null;
    try {
      File dir = new File(getTargetContext().getCacheDir(), "query-save-race-" + System.nanoTime());
      if (!dir.mkdirs()) throw new AssertionError("isolated directory unavailable");
      SqliteLibraryStore sqlite = new SqliteLibraryStore(getTargetContext(), new File(dir, "library.db").getAbsolutePath());
      sqlite.initialize();
      backend = new BlockingBackend(sqlite);
      LibraryStore.setInstanceForTest(new LibraryStore(backend));
      installed = true;
      activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      LibraryStore store = LibraryStore.getInstance(getTargetContext());
      store.awaitIdle(30, TimeUnit.SECONDS);
      waitForIdleSync();
      MainActivity current = activity;
      runOnMainSync(() -> {
        current.query.draft = "went";
        current.query.homeMode = "text";
        current.query.context = "";
        current.query.submit();
      });
      if (!backend.entered.await(30, TimeUnit.SECONDS)) throw new AssertionError("initial session save not reached");
      String[] sessionId = {null};
      runOnMainSync(() -> {
        JSONObject session = current.session;
        sessionId[0] = session.optString("id");
        JSONObject changed = Library.copy(session.optJSONObject("query_results"));
        Library.put(changed.optJSONArray("meanings").optJSONObject(0), "example", "latest edit");
        current.query.updateResult(changed);
      });
      backend.release.countDown();
      store.awaitIdle(30, TimeUnit.SECONDS);
      waitForIdleSync();
      store.awaitIdle(30, TimeUnit.SECONDS);
      waitForIdleSync();
      String[] visible = {null};
      runOnMainSync(() -> {
        JSONObject result = current.session == null ? null : current.session.optJSONObject("query_results");
        JSONArray meanings = result == null ? null : result.optJSONArray("meanings");
        visible[0] = meanings == null ? "" : meanings.optJSONObject(0).optString("example");
      });
      JSONObject saved = new Library(sqlite.loadSnapshot().getRawDocument()).find("conversations", sessionId[0]);
      String persisted = saved == null ? "" : saved.getJSONObject("query_results")
          .getJSONArray("meanings").getJSONObject(0).optString("example");
      if (!"latest edit".equals(visible[0]) || !"latest edit".equals(persisted))
        throw new AssertionError("latest edit lost: visible=" + visible[0] + ", persisted=" + persisted);
      report.putString("status", "passed");
    } catch (Throwable error) {
      failure = error;
      report.putString("error", error.toString());
    } finally {
      if (backend != null) backend.release.countDown();
      if (activity != null) {
        MainActivity current = activity;
        runOnMainSync(() -> { current.session = null; current.finish(); });
      }
      if (installed) LibraryStore.resetInstanceForTest();
    }
    finish(failure == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, report);
  }

  private static final class BlockingBackend implements StoreBackend {
    final StoreBackend delegate;
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    BlockingBackend(StoreBackend delegate) { this.delegate = delegate; }
    @Override public void initialize() throws Exception { delegate.initialize(); }
    @Override public long getGeneration() { return delegate.getGeneration(); }
    @Override public long getRevision() { return delegate.getRevision(); }
    @Override public ReadOnlyView loadSnapshot() throws Exception { return delegate.loadSnapshot(); }
    @Override public ReadOnlyView query(QueryRequest request) throws Exception { return delegate.query(request); }
    @Override public CommitResult execute(LibraryCommand command) throws Exception {
      if (command.getType() == LibraryCommand.Type.SAVE_SESSION && entered.getCount() != 0) {
        entered.countDown();
        if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("test save release timed out");
      }
      return delegate.execute(command);
    }
    @Override public CommitResult applyTransaction(List<LibraryCommand> commands) throws Exception { return delegate.applyTransaction(commands); }
    @Override public CommitResult restore(JSONObject backup, long generation) throws Exception { return delegate.restore(backup, generation); }
    @Override public CommitResult rollback(long generation) throws Exception { return delegate.rollback(generation); }
    @Override public void migrateFrom(File file) throws Exception { delegate.migrateFrom(file); }
    @Override public void close() { delegate.close(); }
  }
}
