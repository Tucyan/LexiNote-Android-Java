package com.lexinote.nativeapp;

import static org.junit.Assert.*;
import com.lexinote.nativeapp.storage.JsonStoreBackend;
import com.lexinote.nativeapp.storage.LibraryStore;
import com.lexinote.nativeapp.storage.contract.CommitResult;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

public class RecoveryStorageTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  @Test public void invalidRestoreDeliversFailureAndRetainsSnapshotAfterBackendReopen() throws Exception {
    File dir = temporary.newFolder("store");
    JsonStoreBackend backend = new JsonStoreBackend(dir);
    backend.initialize();
    JSONObject seed = new Library().snapshot().put("recovery_marker", "original");
    assertTrue(backend.restore(seed, backend.getGeneration()).isSuccess());
    long generation = backend.getGeneration();
    LibraryStore store = new LibraryStore(backend, Executors.newSingleThreadExecutor(), Runnable::run);
    try {
      JSONObject bad = new Library().snapshot().put("version", "99");
      CountDownLatch done = new CountDownLatch(1);
      AtomicReference<CommitResult> result = new AtomicReference<>();
      AtomicReference<Exception> failure = new AtomicReference<>();
      store.runRecoveryExclusive(null, b -> b.restore(bad, generation), (r, e) -> {
        result.set(r); failure.set(e); done.countDown();
      });
      assertTrue("restore failure callback was not delivered", done.await(5, TimeUnit.SECONDS));
      assertTrue("invalid restore must fail", failure.get() != null || (result.get() != null && !result.get().isSuccess()));
      assertEquals(generation, backend.getGeneration());
      assertEquals("original", backend.loadSnapshot().getRawDocument().getString("recovery_marker"));
      store.awaitIdle(5, TimeUnit.SECONDS);
    } finally { store.shutdown(); }
    JsonStoreBackend reopened = new JsonStoreBackend(dir);
    reopened.initialize();
    try { assertEquals("original", reopened.loadSnapshot().getRawDocument().getString("recovery_marker")); }
    finally { reopened.close(); }
  }
}
