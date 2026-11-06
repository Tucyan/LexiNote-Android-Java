package com.lexinote.nativeapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import com.lexinote.nativeapp.storage.LibraryStore;
import java.io.File;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Checks that a confirmed image query retains its context for later verification. */
public final class QueryContextInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

  @Override public void onStart() {
    Bundle result = new Bundle();
    MainActivity activity = null;
    boolean installed = false;
    Throwable failure = null;
    try {
      File dir = new File(getTargetContext().getCacheDir(), "query-context-" + System.nanoTime());
      if (!dir.mkdirs()) throw new AssertionError("isolated directory unavailable");
      SqliteLibraryStore backend = new SqliteLibraryStore(getTargetContext(), new File(dir, "library.db").getAbsolutePath());
      backend.initialize();
      LibraryStore.setInstanceForTest(new LibraryStore(backend));
      installed = true;
      activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      if (!LibraryStore.getInstance(getTargetContext()).awaitIdle(30, TimeUnit.SECONDS))
        throw new AssertionError("initial store load timed out");
      waitForIdleSync();
      MainActivity current = activity;
      String[] retainedContext = {null};
      runOnMainSync(() -> {
        JSONObject session = current.library.newSession("");
        Library.put(session, "query_type", "image");
        Library.put(session, "source_context", "");
        JSONObject intent = new JSONObject();
        Library.put(intent, "target", "went");
        Library.put(intent, "context_quote", "She went home.");
        Library.put(intent, "user_requirement", "Explain the tense.");
        Library.put(session, "pending_lookup", new JSONObject());
        Library.put(session.optJSONObject("pending_lookup"), "lookup", intent);
        current.session = session;
        current.screen = "assistant";
        current.query.lookup(intent);
        // Leave before the asynchronous dictionary callback; no network request is made.
        current.screen = "tabs";
        retainedContext[0] = session.optString("source_context");
      });
      if (!"She went home.\nExplain the tense.".equals(retainedContext[0]))
        throw new AssertionError("confirmed context was not retained for web verification");
      result.putString("status", "passed");
    } catch (Throwable error) {
      failure = error;
      result.putString("error", error.toString());
    } finally {
      if (activity != null) {
        MainActivity current = activity;
        runOnMainSync(() -> { current.session = null; current.finish(); });
      }
      if (installed) LibraryStore.resetInstanceForTest();
    }
    finish(failure == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
  }
}
