package com.lexinote.nativeapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import com.lexinote.nativeapp.storage.LibraryStore;
import com.lexinote.nativeapp.storage.contract.CommitResult;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Isolated startup recovery and closed-backend reopen; no Activity or process restart. */
public final class RecoveryInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  @Override public void onStart() {
    Bundle report = new Bundle(); int status = Activity.RESULT_OK;
    File dir = new File(getTargetContext().getCacheDir(), "recovery-" + System.nanoTime());
    LibraryStore store = null;
    try {
      if (!dir.mkdirs()) throw new AssertionError("isolated directory unavailable");
      File legacy = new File(dir, "library.json");
      byte[] corrupt = "{invalid legacy snapshot".getBytes(StandardCharsets.UTF_8);
      Files.write(legacy.toPath(), corrupt);
      Context isolated = new ContextWrapper(getTargetContext()) {
        @Override public Context getApplicationContext() { return this; }
        @Override public File getFilesDir() { return dir; }
        @Override public File getDatabasePath(String name) { return new File(dir, name); }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
            SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
          return SQLiteDatabase.openOrCreateDatabase(new File(dir, name).getAbsolutePath(), factory, handler);
        }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
            SQLiteDatabase.CursorFactory factory) {
          return SQLiteDatabase.openOrCreateDatabase(new File(dir, name), factory);
        }
      };
      LibraryStore.resetInstanceForTest();
      store = LibraryStore.getInstance(isolated);
      CommitResult first = load(store);
      check(!first.isSuccess(), "corrupt legacy startup must report failure");
      check(java.util.Arrays.equals(corrupt, Files.readAllBytes(legacy.toPath())), "startup changed legacy bytes");
      long failedGeneration = store.currentGeneration();
      JSONObject invalid = new Library().snapshot().put("version", "99");
      File invalidFile = new File(dir, "invalid-import.json");
      Files.write(invalidFile.toPath(), invalid.toString().getBytes(StandardCharsets.UTF_8));
      AtomicReference<CommitResult> rejected = new AtomicReference<>();
      AtomicReference<Exception> rejectedError = new AtomicReference<>();
      CountDownLatch failed = new CountDownLatch(1);
      store.runRecoveryExclusive(null, backend -> ((SqliteLibraryStore) backend).importJson(invalidFile, failedGeneration), (value, error) -> {
        rejected.set(value); rejectedError.set(error); failed.countDown();
      });
      check(failed.await(10, TimeUnit.SECONDS), "failed restore callback missing");
      check(rejectedError.get() != null || (rejected.get() != null && !rejected.get().isSuccess()), "invalid restore accepted");
      check(!load(store).isSuccess(), "failed restore cleared initialization failure");
      check(java.util.Arrays.equals(corrupt, Files.readAllBytes(legacy.toPath())), "failed restore changed legacy bytes");
      JSONObject valid = new Library().snapshot().put("recovery_marker", "isolated-test");
      File validFile = new File(dir, "valid-import.json");
      Files.write(validFile.toPath(), valid.toString().getBytes(StandardCharsets.UTF_8));
      AtomicReference<CommitResult> restored = new AtomicReference<>();
      AtomicReference<Exception> restoreError = new AtomicReference<>();
      CountDownLatch done = new CountDownLatch(1);
      store.runRecoveryExclusive(null, backend -> ((SqliteLibraryStore) backend).importJson(validFile, failedGeneration), (value, error) -> {
        restored.set(value); restoreError.set(error); done.countDown();
      });
      check(done.await(10, TimeUnit.SECONDS), "successful restore callback missing");
      check(restoreError.get() == null && restored.get() != null && restored.get().isSuccess(), "valid recovery failed: " + restoreError.get());
      check(load(store).isSuccess(), "recovered store cannot load");
      long generation = store.currentGeneration();
      store.getBackend().close();
      LibraryStore.resetInstanceForTest();
      SqliteLibraryStore reopened = new SqliteLibraryStore(isolated, "library.db", legacy);
      try {
        reopened.initialize();
        check(reopened.getGeneration() == generation, "generation changed on backend reopen");
        check("isolated-test".equals(reopened.loadSnapshot().getRawDocument().optString("recovery_marker")), "recovered snapshot missing on backend reopen");
        check(!reopened.importJson(invalidFile, generation).isSuccess(), "invalid restore accepted after reopen");
        check(reopened.getGeneration() == generation, "failed restore changed generation");
        check("isolated-test".equals(reopened.loadSnapshot().getRawDocument().optString("recovery_marker")), "failed restore changed current snapshot");
      } finally { reopened.close(); }
      check(java.util.Arrays.equals(corrupt, Files.readAllBytes(legacy.toPath())), "legacy bytes changed");
      report.putString("result", "isolated startup failure, recovery callbacks, failed restore preservation and backend reopen passed; no process restart");
    } catch (Throwable e) { status = Activity.RESULT_CANCELED; report.putString("error", android.util.Log.getStackTraceString(e)); }
    finally { LibraryStore.resetInstanceForTest(); }
    finish(status, report);
  }
  private static CommitResult load(LibraryStore store) throws Exception {
    CountDownLatch done = new CountDownLatch(1); AtomicReference<CommitResult> result = new AtomicReference<>();
    store.loadLibrary(null, value -> { result.set(value); done.countDown(); });
    check(done.await(10, TimeUnit.SECONDS), "load callback missing"); return result.get();
  }
  private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
