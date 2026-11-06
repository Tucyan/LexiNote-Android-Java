package com.lexinote.nativeapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import com.lexinote.nativeapp.storage.JsonStoreBackend;
import com.lexinote.nativeapp.storage.LibraryStore;
import com.lexinote.nativeapp.storage.contract.CommitResult;
import com.lexinote.nativeapp.storage.contract.LibraryCommand;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Installs an isolated store before launch and blocks the first settings save. */
public final class SequentialSettingsInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  @Override public void onStart() {
    Bundle report = new Bundle(); int status = Activity.RESULT_OK;
    MainActivity activity = null;
    CountDownLatch release = new CountDownLatch(1);
    try {
      File directory = new File(getTargetContext().getCacheDir(), "settings-serial-" + System.nanoTime());
      if (!directory.mkdirs()) throw new IllegalStateException("cannot create isolated repository");
      CountDownLatch entered = new CountDownLatch(1), done = new CountDownLatch(2);
      AtomicReference<Throwable> failure = new AtomicReference<>();
      JsonStoreBackend backend = new JsonStoreBackend(new Repository(directory), new Library()) {
        boolean first = true;
        @Override public synchronized CommitResult execute(LibraryCommand command) throws Exception {
          if (first) { first = false; entered.countDown(); if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("gate timed out"); }
          return super.execute(command);
        }
      };
      LibraryStore.setInstanceForTest(new LibraryStore(backend));
      activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      DeviceAsync.ready(this, activity);
      final MainActivity a = activity;
      runOnMainSync(() -> {
        a.screen = "tabs"; a.page = 4;
        a.changeAsync(() -> Library.put(a.library.settings(), "theme_mode", "dark"), ok -> { if (!ok) failure.set(new AssertionError("theme save rejected")); done.countDown(); });
        a.changeAsync(() -> Library.put(a.library.settings(), "accent_color", "#123456"), ok -> { if (!ok) failure.set(new AssertionError("accent save rejected")); done.countDown(); });
      });
      if (!entered.await(10, TimeUnit.SECONDS)) throw new AssertionError("first save did not reach worker");
      release.countDown();
      if (!done.await(15, TimeUnit.SECONDS)) throw new AssertionError("settings acknowledgement timeout");
      if (failure.get() != null) throw new AssertionError("settings save failed", failure.get());
      org.json.JSONObject persisted = backend.loadSnapshot().getRawDocument().getJSONObject("settings");
      if (!"dark".equals(persisted.optString("theme_mode")) || !"#123456".equals(persisted.optString("accent_color"))) throw new AssertionError("one settings change was lost");
      report.putString("stream", "PASS: consecutive theme and accent changes both durably acknowledged");
    } catch (Throwable error) { status = Activity.RESULT_CANCELED; report.putString("stream", android.util.Log.getStackTraceString(error)); }
    finally {
      release.countDown();
      if (activity != null) { MainActivity a = activity; runOnMainSync(a::finish); }
      LibraryStore.resetInstanceForTest();
    }
    finish(status, report);
  }
}
