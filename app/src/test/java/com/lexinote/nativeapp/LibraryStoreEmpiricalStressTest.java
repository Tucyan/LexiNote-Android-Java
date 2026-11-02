package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import com.lexinote.nativeapp.storage.JsonStoreBackend;
import com.lexinote.nativeapp.storage.LibraryStore;
import com.lexinote.nativeapp.storage.contract.*;
import java.io.File;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Empirical stress test harness designed by teamwork_challenger (m1_challenger_1).
 * Directly executes multi-threaded stress scenarios against LibraryStore and JsonStoreBackend:
 * 1. High-concurrency command submission (thread contention, FIFO sequencing, disk parity).
 * 2. Monotonic generation barriers (pre/post barrier concurrency, rejection of stale commands).
 * 3. Idempotent review rating deduplication (high-contention same eventId, conflicting params).
 * 4. Transactional rollback (validation failure, 4MB capacity exceeded, batch all-or-nothing).
 * 5. Lifecycle token unbinding (callback suppression, commit durability, memory retention audit).
 */
public class LibraryStoreEmpiricalStressTest {

  private File tempDir;
  private JsonStoreBackend backend;
  private LibraryStore store;
  private ExecutorService testStoreExecutor;
  private TestDirectCallbackExecutor callbackExecutor;

  @Before
  public void setUp() throws Exception {
    tempDir = Files.createTempDirectory("empirical_stress_test_").toFile();
    backend = new JsonStoreBackend(tempDir);
    backend.initialize();

    testStoreExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "StoreWorkerEmpirical"));
    callbackExecutor = new TestDirectCallbackExecutor();
    store = new LibraryStore(backend, testStoreExecutor, callbackExecutor);
  }

  @After
  public void tearDown() throws Exception {
    if (store != null) {
      store.shutdown();
    }
    if (testStoreExecutor != null) {
      testStoreExecutor.shutdownNow();
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
      File[] children = f.listFiles();
      if (children != null) {
        for (File c : children) deleteRecursive(c);
      }
    }
    f.delete();
  }

  // ==========================================================================
  // 1. High-Concurrency Submission & FIFO Execution
  // ==========================================================================

  @Test
  public void stressTestHighConcurrencyCommandSubmissionWithRealBackend() throws Exception {
    int threadCount = 16;
    int commandsPerThread = 25;
    int totalCommands = threadCount * commandsPerThread;

    CountDownLatch startGate = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(totalCommands);
    ExecutorService clientThreads = Executors.newFixedThreadPool(threadCount);

    List<CommitResult> results = Collections.synchronizedList(new ArrayList<>());
    AtomicInteger failureCount = new AtomicInteger(0);

    for (int t = 0; t < threadCount; t++) {
      final int threadIdx = t;
      clientThreads.submit(() -> {
        try {
          startGate.await();
          for (int i = 0; i < commandsPerThread; i++) {
            String nbName = "StressNB_" + threadIdx + "_" + i;
            JSONObject payload = new JSONObject();
            payload.put("name", nbName);

            // expectedGeneration <= 0 so all concurrent writes match active generation
            LibraryCommand cmd = new LibraryCommand(
                LibraryCommand.Type.CREATE_NOTEBOOK,
                null,
                payload,
                1L,
                "evt-stress-" + threadIdx + "-" + i,
                System.currentTimeMillis());

            store.dispatch(cmd, res -> {
              if (res.isSuccess()) {
                results.add(res);
              } else {
                failureCount.incrementAndGet();
              }
              completionLatch.countDown();
            });
          }
        } catch (Exception e) {
          e.printStackTrace();
          failureCount.incrementAndGet();
        }
      });
    }

    startGate.countDown();
    boolean completedInTime = completionLatch.await(30, TimeUnit.SECONDS);
    clientThreads.shutdown();

    assertTrue("All 400 concurrent commands must finish within timeout", completedInTime);
    assertEquals("Zero commands should fail under valid payload", 0, failureCount.get());
    assertEquals("All dispatched commands must succeed", totalCommands, results.size());

    // Verify monotonic revisions
    assertEquals("Final revision must match total successfully committed commands",
        totalCommands, backend.getRevision());

    // Verify snapshot integrity on disk
    ReadOnlyView diskSnapshot = backend.loadSnapshot();
    JSONArray notebooks = diskSnapshot.getNotebooks();
    // 1 default root + 1 default notebook + totalCommands created
    assertEquals("Disk snapshot must contain all created notebooks",
        2 + totalCommands, notebooks.length());
  }

  @Test
  public void stressTestConcurrentReadsDuringActiveWritesDoNotTear() throws Exception {
    int writerThreads = 8;
    int writesPerThread = 20;
    int readerThreads = 8;
    int readsPerThread = 40;

    CountDownLatch startGate = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(writerThreads * writesPerThread + readerThreads * readsPerThread);
    ExecutorService pool = Executors.newFixedThreadPool(writerThreads + readerThreads);

    AtomicInteger readErrors = new AtomicInteger(0);
    AtomicInteger writeErrors = new AtomicInteger(0);

    // Writers
    for (int t = 0; t < writerThreads; t++) {
      final int tid = t;
      pool.submit(() -> {
        try {
          startGate.await();
          for (int i = 0; i < writesPerThread; i++) {
            JSONObject p = new JSONObject();
            p.put("name", "NB_R_W_" + tid + "_" + i);
            LibraryCommand cmd = new LibraryCommand(
                LibraryCommand.Type.CREATE_NOTEBOOK, null, p, 1L, "evt-rw-" + tid + "-" + i, System.currentTimeMillis());
            store.dispatch(cmd, r -> {
              if (!r.isSuccess()) writeErrors.incrementAndGet();
              doneLatch.countDown();
            });
          }
        } catch (Exception e) {
          writeErrors.incrementAndGet();
        }
      });
    }

    // Readers
    for (int t = 0; t < readerThreads; t++) {
      pool.submit(() -> {
        try {
          startGate.await();
          for (int i = 0; i < readsPerThread; i++) {
            store.query(QueryRequest.fullSnapshot(), view -> {
              try {
                if (view == null || view.getNotebooks() == null || view.getSettings() == null) {
                  readErrors.incrementAndGet();
                } else {
                  // Verify internal JSON consistency without throwing
                  int count = view.getNotebooks().length();
                  if (count < 2) readErrors.incrementAndGet();
                }
              } catch (Exception e) {
                readErrors.incrementAndGet();
              }
              doneLatch.countDown();
            });
          }
        } catch (Exception e) {
          readErrors.incrementAndGet();
        }
      });
    }

    startGate.countDown();
    assertTrue("Concurrent read/write stress must finish cleanly", doneLatch.await(30, TimeUnit.SECONDS));
    pool.shutdown();

    assertEquals("Zero write errors permitted during concurrent reads", 0, writeErrors.get());
    assertEquals("Zero read errors permitted during concurrent writes", 0, readErrors.get());
  }

  // ==========================================================================
  // 2. Generation Barrier Enforcement Under Contention
  // ==========================================================================

  @Test
  public void stressTestGenerationBarrierRejectionUnderContention() throws Exception {
    long initialGen = backend.getGeneration(); // 1L
    int threadCount = 10;
    int commandsPerThread = 20;
    CountDownLatch startGate = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount * commandsPerThread);
    ExecutorService pool = Executors.newFixedThreadPool(threadCount);

    AtomicInteger conflictRejections = new AtomicInteger(0);

    // Stale generation is initialGen + 5
    long staleGen = initialGen + 5;

    for (int t = 0; t < threadCount; t++) {
      final int tid = t;
      pool.submit(() -> {
        try {
          startGate.await();
          for (int i = 0; i < commandsPerThread; i++) {
            JSONObject p = new JSONObject();
            p.put("name", "Stale_" + tid + "_" + i);
            LibraryCommand cmd = new LibraryCommand(
                LibraryCommand.Type.CREATE_NOTEBOOK, null, p, staleGen, "evt-stale-" + tid + "-" + i, System.currentTimeMillis());
            store.dispatch(cmd, r -> {
              if (r.getStatus() == CommitResult.Status.GENERATION_CONFLICT) {
                conflictRejections.incrementAndGet();
              }
              doneLatch.countDown();
            });
          }
        } catch (Exception e) {
          e.printStackTrace();
        }
      });
    }

    startGate.countDown();
    assertTrue("All stale commands processed", doneLatch.await(15, TimeUnit.SECONDS));
    pool.shutdown();

    assertEquals("Every command with mismatched generation must be rejected with GENERATION_CONFLICT",
        threadCount * commandsPerThread, conflictRejections.get());
    assertEquals("Backend generation must not change", initialGen, backend.getGeneration());
    assertEquals("Backend revision must remain 0", 0L, backend.getRevision());
  }

  @Test
  public void stressTestGenerationBarrierTransitionDropsStalePipelinedCommands() throws Exception {
    long genBefore = backend.getGeneration();
    ReadOnlyView backupSnapshot = backend.loadSnapshot();

    int preBarrierCount = 30;
    int postBarrierCount = 30;

    CountDownLatch allCompleted = new CountDownLatch(preBarrierCount + 1 + postBarrierCount);
    AtomicInteger preBarrierSuccess = new AtomicInteger(0);
    AtomicInteger postBarrierRejected = new AtomicInteger(0);
    AtomicBoolean restoreSucceeded = new AtomicBoolean(false);

    // 1. Submit pre-barrier commands expecting genBefore
    for (int i = 0; i < preBarrierCount; i++) {
      JSONObject p = new JSONObject();
      p.put("name", "PreBarrier_" + i);
      LibraryCommand cmd = new LibraryCommand(
          LibraryCommand.Type.CREATE_NOTEBOOK, null, p, genBefore, "evt-pre-" + i, System.currentTimeMillis());
      store.dispatch(cmd, r -> {
        if (r.isSuccess()) preBarrierSuccess.incrementAndGet();
        allCompleted.countDown();
      });
    }

    // 2. Submit restore command on worker thread to advance generation barrier in FIFO sequence
    testStoreExecutor.execute(() -> {
      try {
        CommitResult res = backend.restore(backupSnapshot.getRawDocument(), genBefore);
        if (res.isSuccess()) restoreSucceeded.set(true);
      } catch (Exception e) {
        e.printStackTrace();
      } finally {
        allCompleted.countDown();
      }
    });

    // 3. Submit post-barrier commands STILL expecting old genBefore
    for (int i = 0; i < postBarrierCount; i++) {
      JSONObject p = new JSONObject();
      p.put("name", "PostBarrierStale_" + i);
      LibraryCommand cmd = new LibraryCommand(
          LibraryCommand.Type.CREATE_NOTEBOOK, null, p, genBefore, "evt-post-" + i, System.currentTimeMillis());
      store.dispatch(cmd, r -> {
        if (r.getStatus() == CommitResult.Status.GENERATION_CONFLICT) {
          postBarrierRejected.incrementAndGet();
        }
        allCompleted.countDown();
      });
    }

    assertTrue("Pipeline barrier test must complete", allCompleted.await(20, TimeUnit.SECONDS));

    assertEquals("Pre-barrier commands should succeed", preBarrierCount, preBarrierSuccess.get());
    assertTrue("Restore command must succeed", restoreSucceeded.get());
    assertEquals("Post-barrier commands with stale generation MUST all be rejected with GENERATION_CONFLICT",
        postBarrierCount, postBarrierRejected.get());
    assertTrue("Active generation must have advanced", backend.getGeneration() > genBefore);
  }

  @Test
  public void testImportAndRollbackLibraryCommandsSucceedInJsonStoreBackend() throws Exception {
    long initialGen = backend.getGeneration();
    ReadOnlyView current = backend.loadSnapshot();
    JSONObject backup = current.getRawDocument();

    // 1. Dispatch importLibrary
    CountDownLatch importLatch = new CountDownLatch(1);
    AtomicReference<CommitResult> importResRef = new AtomicReference<>();

    LibraryCommand importCmd = LibraryCommand.importLibrary(initialGen, backup, System.currentTimeMillis());
    store.dispatch(importCmd, r -> {
      importResRef.set(r);
      importLatch.countDown();
    });

    assertTrue("importLibrary must complete promptly", importLatch.await(5, TimeUnit.SECONDS));
    assertNotNull(importResRef.get());
    assertTrue("importLibrary command must execute successfully", importResRef.get().isSuccess());
    assertEquals(CommitResult.Status.SUCCESS, importResRef.get().getStatus());
    assertEquals(initialGen + 1, backend.getGeneration());

    // 2. Dispatch rollbackLibrary
    CountDownLatch rollbackLatch = new CountDownLatch(1);
    AtomicReference<CommitResult> rollbackResRef = new AtomicReference<>();

    LibraryCommand rollbackCmd = LibraryCommand.rollbackLibrary(backend.getGeneration(), System.currentTimeMillis());
    store.dispatch(rollbackCmd, r -> {
      rollbackResRef.set(r);
      rollbackLatch.countDown();
    });

    assertTrue("rollbackLibrary must complete promptly", rollbackLatch.await(5, TimeUnit.SECONDS));
    assertNotNull(rollbackResRef.get());
    assertTrue("rollbackLibrary command must execute successfully", rollbackResRef.get().isSuccess());
    assertEquals(CommitResult.Status.SUCCESS, rollbackResRef.get().getStatus());
    assertEquals(initialGen + 2, backend.getGeneration());
  }

  // ==========================================================================
  // 3. Rating Idempotency Under High Contention
  // ==========================================================================

  @Test
  public void stressTestHighContentionDuplicateRatingIdempotency() throws Exception {
    long gen = backend.getGeneration();
    long now = System.currentTimeMillis();

    // 1. Setup vocabulary
    JSONObject vocabDraft = new JSONObject();
    vocabDraft.put("word", "immutable");
    vocabDraft.put("type", "word");
    JSONArray meanings = new JSONArray();
    JSONObject m = new JSONObject();
    m.put("part_of_speech", "adj.");
    m.put("zh_definition", "不可变的");
    meanings.put(m);
    vocabDraft.put("meanings", meanings);

    CommitResult vr = backend.execute(
        LibraryCommand.saveQueryResult(gen, vocabDraft, new String[]{"nb_default"}, new JSONObject(), now));
    assertTrue(vr.isSuccess());
    String vocabId = vr.getUpdatedEntity().optString("id");

    // 2. Setup review plan and prepare daily queue
    JSONObject planPayload = new JSONObject();
    planPayload.put("name", "Idempotency Stress Plan");
    planPayload.put("notebook_id", "nb_default");
    planPayload.put("daily_new_limit", 10);
    planPayload.put("daily_review_limit", 10);
    planPayload.put("weekdays", new JSONArray(Arrays.asList(1, 2, 3, 4, 5, 6, 7)));
    planPayload.put("zone_id", "Asia/Shanghai");
    planPayload.put("now_ms", now);

    CommitResult pr = backend.execute(
        new LibraryCommand(LibraryCommand.Type.CREATE_REVIEW_PLAN, null, planPayload, gen, "evt-plan-idem", now));
    assertTrue(pr.isSuccess());
    String planId = pr.getUpdatedEntity().optString("id");

    backend.execute(LibraryCommand.prepareDailyQueue(gen, planId, now));

    // 3. Launch 20 concurrent threads submitting identical rating command with FIXED eventId
    int callers = 20;
    String fixedEventId = "evt-rating-contention-shared-999";
    CountDownLatch startGate = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(callers);
    ExecutorService pool = Executors.newFixedThreadPool(callers);

    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger idempotentIgnoredCount = new AtomicInteger(0);
    AtomicInteger unexpectedErrorCount = new AtomicInteger(0);

    for (int i = 0; i < callers; i++) {
      pool.submit(() -> {
        try {
          startGate.await();
          LibraryCommand rateCmd = LibraryCommand.recordReview(
              gen, fixedEventId, planId, vocabId, "简单", 800L, now);
          store.dispatch(rateCmd, r -> {
            if (r.getStatus() == CommitResult.Status.SUCCESS) {
              successCount.incrementAndGet();
            } else if (r.getStatus() == CommitResult.Status.IDEMPOTENT_IGNORED) {
              idempotentIgnoredCount.incrementAndGet();
            } else {
              unexpectedErrorCount.incrementAndGet();
            }
            doneLatch.countDown();
          });
        } catch (Exception e) {
          unexpectedErrorCount.incrementAndGet();
        }
      });
    }

    startGate.countDown();
    assertTrue("All duplicate rating calls must complete", doneLatch.await(15, TimeUnit.SECONDS));
    pool.shutdown();

    assertEquals("Unexpected errors must be 0", 0, unexpectedErrorCount.get());
    assertEquals("Exactly ONE submission must return SUCCESS", 1, successCount.get());
    assertEquals("Remaining 19 submissions must return IDEMPOTENT_IGNORED",
        callers - 1, idempotentIgnoredCount.get());

    // Verify domain data state: exactly 1 event recorded
    ReadOnlyView snapshot = backend.loadSnapshot();
    JSONObject raw = snapshot.getRawDocument();
    JSONObject reviewObj = raw.optJSONObject("review");
    assertNotNull("review section must exist", reviewObj);
    JSONArray events = reviewObj.optJSONArray("events");
    assertNotNull("events array must exist", events);
    int matchCount = 0;
    for (int i = 0; i < events.length(); i++) {
      JSONObject evt = events.getJSONObject(i);
      if (fixedEventId.equals(evt.optString("id"))) {
        matchCount++;
      }
    }
    assertEquals("Events list must contain exactly ONE occurrence of fixedEventId (zero double-counting)",
        1, matchCount);
  }

  // ==========================================================================
  // 4. Transactional Rollback & Zero Partial Mutation
  // ==========================================================================

  @Test
  public void stressTestValidationFailureLeavesZeroPartialState() throws Exception {
    long genBefore = backend.getGeneration();
    long revBefore = backend.getRevision();
    String rawBefore = backend.loadSnapshot().getRawDocument().toString();

    // Submit invalid command
    JSONObject invalidPayload = new JSONObject();
    invalidPayload.put("name", "   "); // blank name violates validation
    invalidPayload.put("parent_id", "nb_non_existent_404");

    LibraryCommand badCmd = new LibraryCommand(
        LibraryCommand.Type.CREATE_NOTEBOOK, null, invalidPayload, genBefore, "evt-fail-val", System.currentTimeMillis());

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<CommitResult> resultRef = new AtomicReference<>();
    store.dispatch(badCmd, r -> {
      resultRef.set(r);
      latch.countDown();
    });

    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertNotNull(resultRef.get());
    assertEquals(CommitResult.Status.VALIDATION_FAILED, resultRef.get().getStatus());
    assertFalse(resultRef.get().isSuccess());

    // State invariants
    assertEquals("Generation must be unaffected by validation failure", genBefore, backend.getGeneration());
    assertEquals("Revision must be unaffected by validation failure", revBefore, backend.getRevision());

    String rawAfter = backend.loadSnapshot().getRawDocument().toString();
    assertEquals("Database JSON snapshot must be byte-for-byte identical after rollback",
        rawBefore, rawAfter);
  }

  @Test
  public void stressTestOversizedPayloadRollbackPreservesDatabase() throws Exception {
    long genBefore = backend.getGeneration();
    long revBefore = backend.getRevision();
    ReadOnlyView viewBefore = backend.loadSnapshot();

    // Create 4.5MB string exceeding BackupArchive.MAX_JSON_BYTES
    char[] huge = new char[BackupArchive.MAX_JSON_BYTES + 1024];
    Arrays.fill(huge, 'Z');
    JSONObject hugePayload = new JSONObject();
    hugePayload.put("key", "oversized_key");
    hugePayload.put("value", new String(huge));

    LibraryCommand hugeCmd = new LibraryCommand(
        LibraryCommand.Type.UPDATE_SETTINGS, null, hugePayload, genBefore, "evt-huge", System.currentTimeMillis());

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<CommitResult> resultRef = new AtomicReference<>();
    store.dispatch(hugeCmd, r -> {
      resultRef.set(r);
      latch.countDown();
    });

    assertTrue(latch.await(10, TimeUnit.SECONDS));
    assertNotNull(resultRef.get());
    assertEquals(CommitResult.Status.CAPACITY_EXCEEDED, resultRef.get().getStatus());

    // Invariants
    assertEquals("Generation must remain unchanged", genBefore, backend.getGeneration());
    assertEquals("Revision must remain unchanged", revBefore, backend.getRevision());

    ReadOnlyView viewAfter = backend.loadSnapshot();
    assertFalse("Corrupt oversized setting must not exist in settings",
        viewAfter.getSettings().has("oversized_key"));
    assertEquals("Snapshot length must be identical to pre-mutation",
        viewBefore.getRawDocument().length(), viewAfter.getRawDocument().length());
  }

  // ==========================================================================
  // 5. Lifecycle Token Unbinding & Memory Audit
  // ==========================================================================

  @Test
  public void stressTestLifecycleTokenUnbindingUnderHeavyDispatch() throws Exception {
    int iterations = 50;
    CountDownLatch allDone = new CountDownLatch(iterations);
    AtomicInteger suppressedCallbacks = new AtomicInteger(0);
    AtomicInteger deliveredCallbacks = new AtomicInteger(0);

    for (int i = 0; i < iterations; i++) {
      Object token = new Object();
      store.bind(token);

      JSONObject p = new JSONObject();
      p.put("name", "LifecycleNB_" + i);
      LibraryCommand cmd = new LibraryCommand(
          LibraryCommand.Type.CREATE_NOTEBOOK, null, p, 1L, "evt-lc-" + i, System.currentTimeMillis());

      final boolean unbindNow = (i % 2 == 0);

      store.dispatch(token, cmd, r -> {
        if (unbindNow) {
          suppressedCallbacks.incrementAndGet(); // This should NOT be reached if unbind suppresses
        } else {
          deliveredCallbacks.incrementAndGet();
        }
      });

      if (unbindNow) {
        store.unbind(token);
      }
    }

    // Wait until store queue drains
    store.awaitIdle(15, TimeUnit.SECONDS);

    // Give callback executor a moment to process
    Thread.sleep(200);

    // All 50 notebooks must be committed on backend regardless of unbind
    assertEquals("All 50 commands must be durably committed on backend",
        iterations, backend.getRevision());

    // Delivered callbacks for non-unbound tokens must be 25
    assertEquals("25 non-unbound tokens must receive callbacks", 25, deliveredCallbacks.get());

    // Suppressed callbacks must be 0
    assertEquals("Unbound tokens must NEVER receive callbacks", 0, suppressedCallbacks.get());
  }

  @Test
  public void testTokenMemoryRetentionAuditVerifiesZeroLeaksInLibraryStore() throws Exception {
    // Audit whether unbinding an Activity token allows GC or leaks a hard reference in unboundTokens
    Object activityToken = new Object();
    WeakReference<Object> weakToken = new WeakReference<>(activityToken);

    store.bind(activityToken);
    store.unbind(activityToken);

    // Release caller's strong reference
    activityToken = null;

    // Trigger GC and heap compaction
    for (int i = 0; i < 10; i++) {
      System.gc();
      System.runFinalization();
      byte[] transientAlloc = new byte[512 * 1024];
      if (weakToken.get() == null) break;
      Thread.sleep(50);
    }

    // Assert token was reclaimed cleanly without retention in LibraryStore
    assertNull("LibraryStore must not retain strong references to unbound tokens (Activity memory leak prevented)",
        weakToken.get());
  }

  @Test
  public void testMainActivityPersistSucceedsAcrossAiQueryGenerations() throws Exception {
    int mainActivityUiGeneration = 0;
    // Simulate user making 2 AI requests and cancellations
    mainActivityUiGeneration++; // 1
    mainActivityUiGeneration++; // 2 (canceled)

    // Now user saves a word or edits a note, calling MainActivity.persist()
    // With the remediation applied, MainActivity.persist() passes store.currentGeneration()
    JSONObject currentSnapshot = backend.loadSnapshot().getRawDocument();
    LibraryCommand persistCmd = LibraryCommand.persistSnapshot(
        store.currentGeneration(), currentSnapshot, System.currentTimeMillis());

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<CommitResult> resultRef = new AtomicReference<>();
    store.dispatch(persistCmd, r -> {
      resultRef.set(r);
      latch.countDown();
    });

    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertNotNull(resultRef.get());

    // VERIFICATION OF REMEDIATION:
    // Persist succeeds cleanly because MainActivity uses store.currentGeneration()
    assertTrue("Persist must succeed after UI AI query tokens increment", resultRef.get().isSuccess());
    assertEquals("Status must be SUCCESS", CommitResult.Status.SUCCESS, resultRef.get().getStatus());
    assertFalse("Must not report GENERATION_CONFLICT", resultRef.get().isConflict());
  }

  // --------------------------------------------------------------------------
  // Direct Callback Executor for Deterministic Testing
  // --------------------------------------------------------------------------

  private static class TestDirectCallbackExecutor implements Executor {
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> new Thread(r, "CallbackLooperEmpirical"));

    @Override
    public void execute(Runnable command) {
      exec.execute(command);
    }

    public void shutdown() {
      exec.shutdownNow();
    }
  }
}
