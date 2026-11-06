package com.lexinote.nativeapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import com.lexinote.nativeapp.storage.contract.*;
import java.io.*;
import org.json.*;

/** Separates incremental history storage from the UI harness's whole-library copies. */
public final class HistoryStorageInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  @Override public void onStart() {
    Bundle report = new Bundle(); int status = Activity.RESULT_OK;
    SqliteLibraryStore store = null;
    try {
      File dir = new File(getTargetContext().getCacheDir(), "history-storage-" + System.nanoTime());
      if (!dir.mkdirs()) throw new IOException("isolated directory unavailable");
      File input = new File(dir, "fixture.json"), database = new File(dir, "history.db");
      try (InputStream in = getContext().getAssets().open("scale-3000-events-10000.json");
           OutputStream out = new FileOutputStream(input)) {
        byte[] buffer = new byte[8192]; int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
      }
      store = new SqliteLibraryStore(getTargetContext(), database.getAbsolutePath()); store.initialize();
      CommitResult imported = store.importJson(input, store.getGeneration());
      if (!imported.isSuccess()) throw new AssertionError(imported.getMessage());
      String plan;
      try (SQLiteDatabase read = SQLiteDatabase.openDatabase(database.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
           Cursor row = read.rawQuery("SELECT id FROM review_plans WHERE generation=? ORDER BY ordinal LIMIT 1",
               new String[]{Long.toString(store.getGeneration())})) {
        if (!row.moveToFirst()) throw new AssertionError("missing plan"); plan = row.getString(0);
      }
      long now = System.currentTimeMillis();
      CommitResult prepared = store.execute(LibraryCommand.prepareDailyQueue(store.getGeneration(), plan, now));
      if (!prepared.isSuccess()) throw new AssertionError(prepared.getMessage());
      JSONArray tasks = prepared.getUpdatedEntity().getJSONArray("days").getJSONObject(0).getJSONArray("tasks");
      if (tasks.length() == 0) throw new AssertionError("fixture has no rating task");
      LibraryCommand score = LibraryCommand.recordReview(store.getGeneration(), "history-test-event", plan,
          tasks.getJSONObject(0).getString("vocab_id"), "简单", 1000L, now);
      CommitResult scored = store.execute(score);
      if (!scored.isSuccess() || scored.getSnapshot() != null) throw new AssertionError("rating failed or built full snapshot");
      if (store.execute(score).getStatus() != CommitResult.Status.IDEMPOTENT_IGNORED)
        throw new AssertionError("rating replay was not idempotent");
      long generation = store.getGeneration(); store.close(); store.initialize();
      try (SQLiteDatabase read = SQLiteDatabase.openDatabase(database.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
           Cursor row = read.rawQuery("SELECT COUNT(*) FROM review_events WHERE generation=?", new String[]{Long.toString(generation)})) {
        if (!row.moveToFirst() || row.getInt(0) != 10001) throw new AssertionError("event count changed");
      }
      if (!store.query(QueryRequest.reviewEvent("history-test-event")).getRawDocument().has("event"))
        throw new AssertionError("accepted event missing after reopen");
      report.putString("stream", "PASS: streaming 3000 vocabulary/10000 event import, local rating/replay/reopen; 10001 events retained");
    } catch (Throwable error) {
      status = Activity.RESULT_CANCELED; report.putString("stream", android.util.Log.getStackTraceString(error));
    } finally { if (store != null) store.close(); }
    finish(status, report);
  }
}
