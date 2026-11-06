package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import com.lexinote.nativeapp.storage.JsonStoreBackend;
import com.lexinote.nativeapp.storage.contract.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Backend-agnostic contract test suite verifying StoreBackend implementations:
 * 1. Initial state cleanliness & generation monotonicity
 * 2. Atomic writes & generation/revision incrementing
 * 3. Validation failure preserves exact previous state (no partial mutations)
 * 4. Stale generation rejection (generation barrier)
 * 5. Idempotent eventId deduplication for rating actions
 * 6. Conflicting eventId parameter rejection
 * 7. Transactional rollback to target generation
 * 8. Quota / capacity exceeded error propagation
 * 9. Read-only view immutability (zero mutable reference leakage)
 * 10. Atomic batch transaction (all-or-nothing)
 */
public abstract class StorageContractTest {

  protected File tempDir;
  protected StoreBackend backend;

  /** Factory method to be implemented by concrete backend test classes. */
  protected abstract StoreBackend createBackend(File workingDir) throws Exception;

  @Before
  public void setUp() throws Exception {
    tempDir = Files.createTempDirectory("storage_contract_test_").toFile();
    backend = createBackend(tempDir);
    backend.initialize();
  }

  @After
  public void tearDown() throws Exception {
    if (backend != null) {
      backend.close();
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

  // --------------------------------------------------------------------------
  // Contract Test Cases
  // --------------------------------------------------------------------------

  @Test
  public void testInitialStateIsCleanAndGenerationMonotonic() throws Exception {
    long gen = backend.getGeneration();
    long rev = backend.getRevision();
    assertTrue("Initial generation must be >= 1", gen >= 1);
    assertEquals("Initial revision must be 0", 0, rev);

    ReadOnlyView snapshot = backend.loadSnapshot();
    assertNotNull("Initial snapshot must not be null", snapshot);
    assertNotNull("Initial snapshot must contain settings", snapshot.getSettings());
    assertNotNull("Initial snapshot must contain default notebook", snapshot.getNotebook("nb_global_root"));
  }

  @Test
  public void testAtomicWriteSuccessIncrementsGenerationAndRevision() throws Exception {
    long initialGen = backend.getGeneration();
    long initialRev = backend.getRevision();

    // Create a new notebook
    JSONObject payload = new JSONObject();
    payload.put("name", "Test Notebook");

    LibraryCommand cmd = new LibraryCommand(
        LibraryCommand.Type.CREATE_NOTEBOOK,
        null,
        payload,
        initialGen,
        "evt-create-nb-01",
        System.currentTimeMillis());

    CommitResult result = backend.execute(cmd);

    assertTrue("CommitResult must indicate success", result.isSuccess());
    assertEquals("Status must be SUCCESS", CommitResult.Status.SUCCESS, result.getStatus());
    assertEquals("Applied generation must match current generation", initialGen, result.getAppliedGeneration());
    assertTrue("Revision must increment", result.getAppliedRevision() > initialRev);
    assertEquals("Backend revision must match commit result", result.getAppliedRevision(), backend.getRevision());

    // Verify entity was persisted
    ReadOnlyView updatedView = backend.loadSnapshot();
    assertNotNull("Updated notebook must exist in snapshot", result.getUpdatedEntity());
    String newId = result.getUpdatedEntity().optString("id");
    assertNotNull("Notebook ID must be in snapshot", updatedView.getNotebook(newId));
  }

  @Test
  public void testAtomicWriteValidationFailurePreservesExactPreviousState() throws Exception {
    long beforeGen = backend.getGeneration();
    long beforeRev = backend.getRevision();
    ReadOnlyView beforeView = backend.loadSnapshot();
    String beforeJson = beforeView.getRawDocument().toString();

    // Submit invalid command: missing required name and invalid parent
    JSONObject invalidPayload = new JSONObject();
    invalidPayload.put("parent_id", "non_existent_notebook_id_xyz");

    LibraryCommand badCmd = new LibraryCommand(
        LibraryCommand.Type.CREATE_NOTEBOOK,
        null,
        invalidPayload,
        beforeGen,
        "evt-bad-01",
        System.currentTimeMillis());

    CommitResult result = backend.execute(badCmd);

    assertFalse("Validation failure must not report success", result.isSuccess());
    assertEquals("Status must be VALIDATION_FAILED",
        CommitResult.Status.VALIDATION_FAILED, result.getStatus());
    assertEquals("Generation must not change on failure", beforeGen, backend.getGeneration());
    assertEquals("Revision must not change on failure", beforeRev, backend.getRevision());

    // Verify snapshot state is 100% unchanged
    ReadOnlyView afterView = backend.loadSnapshot();
    assertEquals("Document JSON must remain completely unchanged", beforeJson, afterView.getRawDocument().toString());
  }

  @Test
  public void testGenerationBarrierRejectsStaleExpectedGeneration() throws Exception {
    long currentGen = backend.getGeneration();

    // Submit command with stale expectedGeneration
    long staleGen = currentGen + 99L;
    JSONObject payload = new JSONObject();
    payload.put("name", "Stale Notebook");

    LibraryCommand staleCmd = new LibraryCommand(
        LibraryCommand.Type.CREATE_NOTEBOOK,
        null,
        payload,
        staleGen,
        "evt-stale-01",
        System.currentTimeMillis());

    CommitResult result = backend.execute(staleCmd);

    assertFalse("Stale generation command must be rejected", result.isSuccess());
    assertTrue("Result must indicate conflict", result.isConflict());
    assertEquals("Status must be GENERATION_CONFLICT", CommitResult.Status.GENERATION_CONFLICT, result.getStatus());
    assertEquals("Current backend generation must remain intact", currentGen, backend.getGeneration());
  }

  @Test
  public void testIdempotentEventIdHandlingForReviewRatings() throws Exception {
    long gen = backend.getGeneration();
    long now = System.currentTimeMillis();

    // 1. Add vocabulary to nb_default
    JSONObject vocabDraft = new JSONObject();
    vocabDraft.put("word", "ephemeral");
    vocabDraft.put("type", "word");
    JSONArray meanings = new JSONArray();
    JSONObject m = new JSONObject();
    m.put("part_of_speech", "adj.");
    m.put("zh_definition", "短暂的");
    meanings.put(m);
    vocabDraft.put("meanings", meanings);

    LibraryCommand addVocabCmd = LibraryCommand.saveQueryResult(
        gen, vocabDraft, new String[]{"nb_default"}, new JSONObject(), now);
    CommitResult vocabResult = backend.execute(addVocabCmd);
    assertTrue("Save vocabulary must succeed", vocabResult.isSuccess());
    assertNotNull(vocabResult.getUpdatedEntity());
    String vocabId = vocabResult.getUpdatedEntity().optString("id");

    // 2. Create review plan referencing nb_default
    JSONObject planPayload = new JSONObject();
    planPayload.put("name", "Daily Review");
    planPayload.put("notebook_id", "nb_default");
    planPayload.put("daily_new_limit", 10);
    planPayload.put("daily_review_limit", 20);
    planPayload.put("weekdays", new JSONArray(Arrays.asList(1, 2, 3, 4, 5, 6, 7)));
    planPayload.put("zone_id", "Asia/Shanghai");
    planPayload.put("now_ms", now);

    LibraryCommand createPlanCmd = new LibraryCommand(
        LibraryCommand.Type.CREATE_REVIEW_PLAN, null, planPayload, backend.getGeneration(), "evt-plan-01", now);
    CommitResult planResult = backend.execute(createPlanCmd);
    assertTrue("Plan creation must succeed", planResult.isSuccess());
    String planId = planResult.getUpdatedEntity().optString("id");

    // 3. Prepare daily queue
    LibraryCommand prepCmd = LibraryCommand.prepareDailyQueue(backend.getGeneration(), planId, now);
    CommitResult prepResult = backend.execute(prepCmd);
    assertTrue("Prepare daily queue must succeed", prepResult.isSuccess());

    // 4. Record rating with fixed eventId
    String eventId = "evt-rating-fixed-12345";
    LibraryCommand ratingCmd = LibraryCommand.recordReview(
        backend.getGeneration(), eventId, planId, vocabId, "简单", 1200L, now);

    CommitResult firstResult = backend.execute(ratingCmd);
    assertTrue("First rating must succeed", firstResult.isSuccess());
    long revAfterFirst = backend.getRevision();

    // 5. Submit DUPLICATE command with identical eventId and identical payload
    CommitResult duplicateResult = backend.execute(ratingCmd);

    assertTrue("Duplicate rating with identical eventId must be accepted as idempotent",
        duplicateResult.isSuccess());
    assertEquals("Status should be IDEMPOTENT_IGNORED",
        CommitResult.Status.IDEMPOTENT_IGNORED, duplicateResult.getStatus());
    assertEquals("Revision must not advance on idempotent duplicate",
        revAfterFirst, backend.getRevision());
  }

  @Test
  public void testConflictingEventIdParametersRejection() throws Exception {
    long gen = backend.getGeneration();
    String eventId = "evt-conflict-test-01";
    long now = System.currentTimeMillis();

    // 1. Add vocabulary
    JSONObject vocabDraft = new JSONObject();
    vocabDraft.put("word", "transient");
    vocabDraft.put("type", "word");
    JSONArray meanings = new JSONArray();
    JSONObject m = new JSONObject();
    m.put("part_of_speech", "adj.");
    m.put("zh_definition", "转瞬即逝的");
    meanings.put(m);
    vocabDraft.put("meanings", meanings);

    LibraryCommand addVocabCmd = LibraryCommand.saveQueryResult(
        gen, vocabDraft, new String[]{"nb_default"}, new JSONObject(), now);
    CommitResult vocabResult = backend.execute(addVocabCmd);
    assertTrue(vocabResult.isSuccess());
    String vocabId = vocabResult.getUpdatedEntity().optString("id");

    // 2. Create plan & prepare queue
    JSONObject planPayload = new JSONObject();
    planPayload.put("name", "Conflict Plan");
    planPayload.put("notebook_id", "nb_default");
    planPayload.put("daily_new_limit", 10);
    planPayload.put("daily_review_limit", 20);
    planPayload.put("weekdays", new JSONArray(Arrays.asList(1, 2, 3, 4, 5, 6, 7)));
    planPayload.put("zone_id", "Asia/Shanghai");
    planPayload.put("now_ms", now);

    LibraryCommand createPlanCmd = new LibraryCommand(
        LibraryCommand.Type.CREATE_REVIEW_PLAN, null, planPayload, backend.getGeneration(), "evt-plan-conflict", now);
    CommitResult planResult = backend.execute(createPlanCmd);
    assertTrue(planResult.isSuccess());
    String planId = planResult.getUpdatedEntity().optString("id");

    backend.execute(LibraryCommand.prepareDailyQueue(backend.getGeneration(), planId, now));

    // 3. Record rating
    LibraryCommand cmd1 = LibraryCommand.recordReview(backend.getGeneration(), eventId, planId, vocabId, "简单", 1000L, now);
    CommitResult res1 = backend.execute(cmd1);
    assertTrue("First rating must succeed", res1.isSuccess());

    // 4. Conflicting command: same eventId but different grade ("再次" vs "简单")
    LibraryCommand cmd2 = LibraryCommand.recordReview(
        backend.getGeneration(), eventId, planId, vocabId, "再次", 1000L, now);
    CommitResult res2 = backend.execute(cmd2);

    assertFalse("Conflicting parameters for existing eventId must be rejected", res2.isSuccess());
    assertEquals("Status must be VALIDATION_FAILED", CommitResult.Status.VALIDATION_FAILED, res2.getStatus());
    long revision = backend.getRevision();
    CommitResult differentTime = backend.execute(LibraryCommand.recordReview(
        backend.getGeneration(), eventId, planId, vocabId, "简单", 1000L, now + 1));
    assertEquals("Reused eventId with a different occurrence time must be rejected",
        CommitResult.Status.VALIDATION_FAILED, differentTime.getStatus());
    assertEquals("Conflicting event must not change revision", revision, backend.getRevision());
  }

  @Test
  public void testTransactionalRollbackToTargetGeneration() throws Exception {
    long initialGen = backend.getGeneration();

    // Create a backup snapshot
    ReadOnlyView initialView = backend.loadSnapshot();
    JSONObject backup = initialView.getRawDocument();

    // Add mutation
    JSONObject payload = new JSONObject();
    payload.put("name", "Temporary Notebook");
    LibraryCommand cmd = new LibraryCommand(
        LibraryCommand.Type.CREATE_NOTEBOOK, null, payload, initialGen, "evt-nb-temp", System.currentTimeMillis());
    assertTrue(backend.execute(cmd).isSuccess());

    // Restore backup -> creates new generation barrier
    CommitResult restoreResult = backend.restore(backup, backend.getGeneration());
    assertTrue("Restore must succeed", restoreResult.isSuccess());
    assertTrue("Generation must increment after restore barrier",
        backend.getGeneration() > initialGen);

    // Rollback to pre-restore snapshot
    CommitResult rollbackResult = backend.rollback(backend.getGeneration());
    assertTrue("Rollback must succeed", rollbackResult.isSuccess());
    assertTrue("Generation must advance again after rollback barrier",
        backend.getGeneration() > restoreResult.getAppliedGeneration());
  }

  @Test
  public void testReadOnlyViewImmutability() throws Exception {
    ReadOnlyView view = backend.loadSnapshot();
    JSONObject raw = view.getRawDocument();

    // Attempt to corrupt view
    raw.put("corrupted_key", "malicious_value");
    if (view.getSettings() != null) {
      view.getSettings().put("tampered", true);
    }

    // Load fresh view from backend
    ReadOnlyView freshView = backend.loadSnapshot();
    assertFalse("Backend must not be corrupted by mutations to returned view",
        freshView.getRawDocument().has("corrupted_key"));
    if (freshView.getSettings() != null) {
      assertFalse("Settings must not have tampered key", freshView.getSettings().has("tampered"));
    }
  }

  @Test
  public void testAtomicBatchTransactionAllOrNothing() throws Exception {
    long startGen = backend.getGeneration();
    long startRev = backend.getRevision();

    List<LibraryCommand> batch = new ArrayList<>();

    // Command 1: Valid notebook
    JSONObject p1 = new JSONObject();
    p1.put("name", "Batch Valid 1");
    batch.add(new LibraryCommand(LibraryCommand.Type.CREATE_NOTEBOOK, null, p1, startGen, "evt-b1", System.currentTimeMillis()));

    // Command 2: Malformed command that triggers validation failure
    JSONObject p2 = new JSONObject();
    p2.put("name", ""); // Empty name
    p2.put("parent_id", "nb_global_root");
    batch.add(new LibraryCommand(LibraryCommand.Type.CREATE_NOTEBOOK, null, p2, startGen, "evt-b2", System.currentTimeMillis()));

    CommitResult batchResult = backend.applyTransaction(batch);

    assertFalse("Batch containing invalid command must fail atomically", batchResult.isSuccess());
    assertEquals("Generation must remain unchanged after batch rollback", startGen, backend.getGeneration());
    assertEquals("Revision must remain unchanged after batch rollback", startRev, backend.getRevision());

    // Verify Command 1 was NOT applied
    ReadOnlyView snapshot = backend.loadSnapshot();
    boolean foundFirst = false;
    for (int i = 0; i < snapshot.getNotebooks().length(); i++) {
      if ("Batch Valid 1".equals(snapshot.getNotebooks().getJSONObject(i).optString("name"))) {
        foundFirst = true;
      }
    }
    assertFalse("First command in aborted batch must not be partially applied", foundFirst);
  }

  @Test
  public void testImportAndRollbackCommandsViaExecute() throws Exception {
    long initialGen = backend.getGeneration();

    // 1. Prepare backup payload with distinctive notebook
    ReadOnlyView current = backend.loadSnapshot();
    JSONObject backupJson = current.getRawDocument();
    JSONArray notebooks = backupJson.optJSONArray("notebooks");
    if (notebooks == null) {
      notebooks = new JSONArray();
      backupJson.put("notebooks", notebooks);
    }
    JSONObject importedNb = new JSONObject();
    importedNb.put("id", "nb_imported_special");
    importedNb.put("name", "Imported Special Notebook");
    importedNb.put("parent_id", JSONObject.NULL);
    importedNb.put("children_type", "vocabulary");
    importedNb.put("child_notebook_ids", new JSONArray());
    importedNb.put("vocabulary_ids", new JSONArray());
    importedNb.put("created_at", "2026-11-02T00:00:00Z");
    importedNb.put("updated_at", "2026-11-02T00:00:00Z");
    notebooks.put(importedNb);

    // 2. Submit IMPORT_LIBRARY command through backend.execute()
    LibraryCommand importCmd = LibraryCommand.importLibrary(
        initialGen, backupJson, System.currentTimeMillis());
    CommitResult importResult = backend.execute(importCmd);

    assertTrue("IMPORT_LIBRARY command must succeed: " + importResult.getStatus() + " - " + importResult.getMessage(), importResult.isSuccess());
    assertEquals("Status must be SUCCESS", CommitResult.Status.SUCCESS, importResult.getStatus());
    assertEquals("Generation must advance after import barrier", initialGen + 1, backend.getGeneration());
    assertEquals("Revision must reset to 0", 0L, backend.getRevision());

    // Verify imported entity exists
    ReadOnlyView postImportView = backend.loadSnapshot();
    assertNotNull("Imported notebook must exist in snapshot",
        postImportView.getNotebook("nb_imported_special"));

    // 3. Submit ROLLBACK_LIBRARY command through backend.execute()
    LibraryCommand rollbackCmd = LibraryCommand.rollbackLibrary(
        backend.getGeneration(), System.currentTimeMillis());
    CommitResult rollbackResult = backend.execute(rollbackCmd);

    assertTrue("ROLLBACK_LIBRARY command must succeed", rollbackResult.isSuccess());
    assertEquals("Status must be SUCCESS", CommitResult.Status.SUCCESS, rollbackResult.getStatus());
    assertEquals("Generation must advance again after rollback barrier",
        initialGen + 2, backend.getGeneration());
    assertEquals("Revision must reset to 0", 0L, backend.getRevision());

    // Verify rollback restored pre-import state
    ReadOnlyView postRollbackView = backend.loadSnapshot();
    assertNull("Imported notebook must be removed after rollback",
        postRollbackView.getNotebook("nb_imported_special"));
  }

  // --------------------------------------------------------------------------
  // Concrete Implementation for Phase A (JsonStoreBackend)
  // --------------------------------------------------------------------------

  public static class JsonStoreBackendContractTest extends StorageContractTest {
    @Override
    protected StoreBackend createBackend(File workingDir) throws Exception {
      return new JsonStoreBackend(workingDir);
    }

    @Test
    public void testJsonCapacityExceededErrorPropagation() throws Exception {
      long gen = backend.getGeneration();
      ReadOnlyView beforeView = backend.loadSnapshot();

      // Attempt to save an oversized payload exceeding BackupArchive.MAX_JSON_BYTES (4MB)
      JSONObject hugePayload = new JSONObject();
      hugePayload.put("key", "oversized_setting");
      char[] chars = new char[BackupArchive.MAX_JSON_BYTES + 1024];
      Arrays.fill(chars, 'A');
      hugePayload.put("value", new String(chars));

      LibraryCommand oversizedCmd = new LibraryCommand(
          LibraryCommand.Type.UPDATE_SETTINGS, null, hugePayload, gen, "evt-huge-01", System.currentTimeMillis());

      CommitResult result = backend.execute(oversizedCmd);

      assertFalse("Oversized payload must be rejected", result.isSuccess());
      assertEquals("Status must be CAPACITY_EXCEEDED",
          CommitResult.Status.CAPACITY_EXCEEDED, result.getStatus());
      assertNotNull("Error message must explain capacity limit", result.getMessage());

      // Existing file on disk must be undamaged
      ReadOnlyView afterView = backend.loadSnapshot();
      assertEquals("Snapshot must remain intact",
          beforeView.getRawDocument().length(), afterView.getRawDocument().length());
    }
  }
}
