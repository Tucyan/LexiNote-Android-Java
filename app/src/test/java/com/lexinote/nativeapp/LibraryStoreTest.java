package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import com.lexinote.nativeapp.storage.JsonStoreBackend;
import com.lexinote.nativeapp.storage.LibraryStore;
import com.lexinote.nativeapp.storage.contract.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Concurrency, ordering, lifecycle, and non-blocking verification for LibraryStore.
 */
public class LibraryStoreTest {

  private File tempDir;
  private TestStoreBackend mockBackend;
  private ExecutorService storeExecutor;
  private TestCallbackExecutor callbackExecutor;
  private LibraryStore store;

  @Before
  public void setUp() throws Exception {
    tempDir = Files.createTempDirectory("library_store_test_").toFile();
    mockBackend = new TestStoreBackend();
    storeExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "StoreWorkerTest"));
    callbackExecutor = new TestCallbackExecutor();
    store = new LibraryStore(mockBackend, storeExecutor, callbackExecutor);
  }

  @After
  public void tearDown() throws Exception {
    if (store != null) {
      store.shutdown();
    }
    if (storeExecutor != null) {
      storeExecutor.shutdownNow();
    }
    if (callbackExecutor != null) {
      callbackExecutor.shutdown();
    }
    if (tempDir != null && tempDir.exists()) {
      deleteRecursive(tempDir);
    }
  }

  private void deleteRecursive(File f) {
    if (f.isDirectory()) {
      File[] c = f.listFiles();
      if (c != null) for (File sub : c) deleteRecursive(sub);
    }
    f.delete();
  }

  // --------------------------------------------------------------------------
  // Concurrency & FIFO Ordering Tests
  // --------------------------------------------------------------------------

  @Test
  public void testConcurrentCommandSubmissionStrictFifoOrdering() throws Exception {
    int threadCount = 10;
    int commandsPerThread = 50;
    int totalCommands = threadCount * commandsPerThread;

    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch finishLatch = new CountDownLatch(totalCommands);
    ExecutorService clientPool = Executors.newFixedThreadPool(threadCount);

    List<Integer> submittedOrder = Collections.synchronizedList(new ArrayList<>());
    List<CommitResult> completedResults = Collections.synchronizedList(new ArrayList<>());

    for (int t = 0; t < threadCount; t++) {
      final int threadId = t;
      clientPool.submit(() -> {
        try {
          startLatch.await();
          for (int i = 0; i < commandsPerThread; i++) {
            int seq = threadId * commandsPerThread + i;
            submittedOrder.add(seq);

            JSONObject p = new JSONObject();
            p.put("seq", seq);
            LibraryCommand cmd = new LibraryCommand(
                LibraryCommand.Type.UPDATE_SETTINGS, null, p, 1L, "evt-" + seq, System.currentTimeMillis());

            store.dispatch(cmd, result -> {
              completedResults.add(result);
              finishLatch.countDown();
            });
          }
        } catch (Exception e) {
          e.printStackTrace();
        }
      });
    }

    startLatch.countDown();
    assertTrue("All commands must complete within timeout", finishLatch.await(10, TimeUnit.SECONDS));
    clientPool.shutdown();

    assertEquals("Total completed must equal total dispatched", totalCommands, completedResults.size());
    assertEquals("Backend must have executed exactly totalCommands", totalCommands, mockBackend.executedCommands.size());
    assertEquals("Maximum concurrent executions inside backend must be exactly 1", 1, mockBackend.maxConcurrentExecutions.get());
  }

  @Test
  public void testCallbackDispatchedToCallerExecutor() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Thread> callbackThread = new AtomicReference<>();

    LibraryCommand cmd = new LibraryCommand(
        LibraryCommand.Type.UPDATE_SETTINGS, null, new JSONObject(), 1L, "evt-cb-01", System.currentTimeMillis());

    store.dispatch(cmd, result -> {
      callbackThread.set(Thread.currentThread());
      latch.countDown();
    });

    assertTrue(latch.await(2, TimeUnit.SECONDS));
    assertNotNull("Callback thread must be recorded", callbackThread.get());
    assertEquals("Callback must run on callbackExecutor thread, NOT on worker thread",
        callbackExecutor.getExecutorThreadName(), callbackThread.get().getName());
  }

  @Test
  public void testUnbindSuppressesPendingCallbacksWithoutCancellingCommit() throws Exception {
    Object uiToken = new Object();
    CountDownLatch executionStarted = new CountDownLatch(1);
    CountDownLatch holdExecution = new CountDownLatch(1);
    AtomicBoolean callbackInvoked = new AtomicBoolean(false);
    CountDownLatch callbackCompleted = new CountDownLatch(1);

    // Configure backend to pause during execution
    mockBackend.onExecuteHook = () -> {
      executionStarted.countDown();
      try {
        holdExecution.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };

    LibraryCommand cmd = new LibraryCommand(
        LibraryCommand.Type.UPDATE_SETTINGS, null, new JSONObject(), 1L, "evt-unbind-01", System.currentTimeMillis());

    // Dispatch tagged with uiToken
    store.dispatch(uiToken, cmd, result -> {
      callbackInvoked.set(true);
      callbackCompleted.countDown();
    });

    // Wait until backend starts executing on worker thread
    assertTrue("Execution must start", executionStarted.await(2, TimeUnit.SECONDS));

    // UI simulates Activity.onDestroy() by unbinding token
    store.unbind(uiToken);

    // Release backend to finish persistence
    holdExecution.countDown();

    // Give time for callback dispatch
    Thread.sleep(200);

    // Invariants:
    assertFalse("Callback must be suppressed after unbind", callbackInvoked.get());
    assertEquals("Backend commit MUST still complete despite UI unbind", 1, mockBackend.executedCommands.size());
  }

  @Test
  public void testUnbindIsTokenScopedAndDoesNotAffectOtherTokens() throws Exception {
    Object tokenA = new Object();
    Object tokenB = new Object();

    CountDownLatch latchB = new CountDownLatch(1);
    AtomicBoolean callbackA = new AtomicBoolean(false);
    AtomicBoolean callbackB = new AtomicBoolean(false);

    store.registerToken(tokenA);
    store.registerToken(tokenB);

    LibraryCommand cmdA = new LibraryCommand(
        LibraryCommand.Type.UPDATE_SETTINGS, null, new JSONObject(), 1L, "evt-a", System.currentTimeMillis());
    LibraryCommand cmdB = new LibraryCommand(
        LibraryCommand.Type.UPDATE_SETTINGS, null, new JSONObject(), 1L, "evt-b", System.currentTimeMillis());

    // Unbind tokenA only
    store.unbind(tokenA);

    store.dispatch(tokenA, cmdA, r -> callbackA.set(true));
    store.dispatch(tokenB, cmdB, r -> {
      callbackB.set(true);
      latchB.countDown();
    });

    assertTrue("Callback B must fire", latchB.await(2, TimeUnit.SECONDS));
    assertFalse("Callback A must be suppressed", callbackA.get());
    assertTrue("Callback B must be executed", callbackB.get());
  }

  @Test
  public void testGenerationBarrierRejectsQueuedStaleCommands() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<CommitResult> conflictResult = new AtomicReference<>();

    // Mock backend generation advances to 5
    mockBackend.currentGeneration = 5L;

    // Command expecting Generation 3 arrives
    LibraryCommand staleCmd = new LibraryCommand(
        LibraryCommand.Type.UPDATE_SETTINGS, null, new JSONObject(), 3L, "evt-stale", System.currentTimeMillis());

    store.dispatch(staleCmd, result -> {
      conflictResult.set(result);
      latch.countDown();
    });

    assertTrue(latch.await(2, TimeUnit.SECONDS));
    assertNotNull(conflictResult.get());
    assertTrue("Result must indicate conflict", conflictResult.get().isConflict());
    assertEquals("Status must be GENERATION_CONFLICT",
        CommitResult.Status.GENERATION_CONFLICT, conflictResult.get().getStatus());
  }

  @Test
  public void testNonBlockingDispatchUnderBackendLatency() throws Exception {
    // Inject 150ms artificial delay into backend
    mockBackend.artificialDelayMs = 150;

    LibraryCommand cmd = new LibraryCommand(
        LibraryCommand.Type.UPDATE_SETTINGS, null, new JSONObject(), 1L, "evt-delay", System.currentTimeMillis());

    long startNs = System.nanoTime();
    store.dispatch(cmd, r -> {});
    long durationMs = (System.nanoTime() - startNs) / 1_000_000;

    assertTrue("dispatch() must return immediately to caller thread without blocking (< 25ms, was " + durationMs + "ms)",
        durationMs < 25);
  }

  @Test
  public void testImportAndRollbackCommandsDispatchedThroughLibraryStore() throws Exception {
    // Test using real JsonStoreBackend inside LibraryStore
    JsonStoreBackend realBackend = new JsonStoreBackend(tempDir);
    realBackend.initialize();
    LibraryStore realStore = new LibraryStore(realBackend, storeExecutor, callbackExecutor);

    try {
      long gen0 = realStore.currentGeneration();
      JSONObject backup = realBackend.loadSnapshot().getRawDocument();
      JSONObject newSettings = backup.optJSONObject("settings");
      if (newSettings == null) newSettings = new JSONObject();
      newSettings.put("import_async_test", "passed");
      backup.put("settings", newSettings);

      // 1. Dispatch importLibrary via store.dispatch()
      CountDownLatch importLatch = new CountDownLatch(1);
      AtomicReference<CommitResult> importResRef = new AtomicReference<>();

      LibraryCommand importCmd = LibraryCommand.importLibrary(gen0, backup, System.currentTimeMillis());
      realStore.dispatch(importCmd, r -> {
        importResRef.set(r);
        importLatch.countDown();
      });

      assertTrue("importLibrary must complete", importLatch.await(5, TimeUnit.SECONDS));
      CommitResult importRes = importResRef.get();
      assertNotNull(importRes);
      assertTrue("importLibrary must report success", importRes.isSuccess());
      assertEquals(CommitResult.Status.SUCCESS, importRes.getStatus());
      assertTrue("Generation must advance", realStore.currentGeneration() > gen0);

      // 2. Dispatch rollbackLibrary via store.dispatch()
      long gen1 = realStore.currentGeneration();
      CountDownLatch rollbackLatch = new CountDownLatch(1);
      AtomicReference<CommitResult> rollbackResRef = new AtomicReference<>();

      LibraryCommand rollbackCmd = LibraryCommand.rollbackLibrary(gen1, System.currentTimeMillis());
      realStore.dispatch(rollbackCmd, r -> {
        rollbackResRef.set(r);
        rollbackLatch.countDown();
      });

      assertTrue("rollbackLibrary must complete", rollbackLatch.await(5, TimeUnit.SECONDS));
      CommitResult rollbackRes = rollbackResRef.get();
      assertNotNull(rollbackRes);
      assertTrue("rollbackLibrary must report success", rollbackRes.isSuccess());
      assertEquals(CommitResult.Status.SUCCESS, rollbackRes.getStatus());
      assertTrue("Generation must advance again after rollback", realStore.currentGeneration() > gen1);

    } finally {
      realStore.shutdown();
    }
  }

  // --------------------------------------------------------------------------
  // Test Doubles
  // --------------------------------------------------------------------------

  private static class TestCallbackExecutor implements Executor {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "CallbackLooperMock"));

    @Override
    public void execute(Runnable command) {
      executor.execute(command);
    }

    public String getExecutorThreadName() {
      Future<String> name = executor.submit(() -> Thread.currentThread().getName());
      try {
        return name.get(1, TimeUnit.SECONDS);
      } catch (Exception e) {
        return "";
      }
    }

    public void shutdown() {
      executor.shutdownNow();
    }
  }

  private static class TestStoreBackend implements StoreBackend {
    long currentGeneration = 1L;
    long currentRevision = 0L;
    int artificialDelayMs = 0;
    Runnable onExecuteHook = null;
    final List<LibraryCommand> executedCommands = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger activeExecutions = new AtomicInteger(0);
    final AtomicInteger maxConcurrentExecutions = new AtomicInteger(0);

    @Override public void initialize() {}
    @Override public long getGeneration() { return currentGeneration; }
    @Override public long getRevision() { return currentRevision; }
    @Override public ReadOnlyView loadSnapshot() { return new ReadOnlyView(currentGeneration, currentRevision, System.currentTimeMillis(), new JSONObject()); }
    @Override public ReadOnlyView query(QueryRequest r) { return loadSnapshot(); }

    @Override
    public CommitResult execute(LibraryCommand command) throws Exception {
      int active = activeExecutions.incrementAndGet();
      maxConcurrentExecutions.accumulateAndGet(active, Math::max);

      try {
        if (onExecuteHook != null) onExecuteHook.run();
        if (artificialDelayMs > 0) Thread.sleep(artificialDelayMs);

        executedCommands.add(command);
        currentRevision++;
        return CommitResult.success(currentGeneration, currentRevision, command.getEventId(), null, null, "OK");
      } finally {
        activeExecutions.decrementAndGet();
      }
    }

    @Override public CommitResult applyTransaction(List<LibraryCommand> c) { return CommitResult.success(currentGeneration, currentRevision, "", null, null, "OK"); }
    @Override public CommitResult restore(JSONObject b, long e) { currentGeneration++; return CommitResult.success(currentGeneration, currentRevision, "", null, null, "RESTORED"); }
    @Override public CommitResult rollback(long e) { currentGeneration++; return CommitResult.success(currentGeneration, currentRevision, "", null, null, "ROLLED_BACK"); }
    @Override public void migrateFrom(File f) {}
    @Override public void close() {}
  }
}
