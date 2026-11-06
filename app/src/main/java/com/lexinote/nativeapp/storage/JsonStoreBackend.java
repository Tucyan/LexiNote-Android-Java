package com.lexinote.nativeapp.storage;

import com.lexinote.nativeapp.Library;
import com.lexinote.nativeapp.Repository;
import com.lexinote.nativeapp.ReviewPlans;
import com.lexinote.nativeapp.storage.contract.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Interim StoreBackend implementation wrapping Repository and Library.
 * Confined strictly to LibraryStore background worker thread.
 * Guarantees atomic rollback on persistence failure or validation errors.
 */
public class JsonStoreBackend implements StoreBackend {

  private final File workingDir;
  private Repository repository;
  private Library workingCopy;
  private long generation = 1L;
  private long revision = 0L;
  private JSONObject preRestoreBackup;
  private Exception initError;

  public JsonStoreBackend(File workingDir) {
    this.workingDir = Objects.requireNonNull(workingDir, "workingDir cannot be null");
  }

  public JsonStoreBackend(Repository repository, Library initialLibrary) {
    this.workingDir = null;
    this.repository = Objects.requireNonNull(repository, "repository cannot be null");
    this.workingCopy = initialLibrary != null ? initialLibrary : new Library();
  }

  @Override
  public synchronized void initialize() throws Exception {
    if (workingDir != null) {
      if (!workingDir.exists()) {
        workingDir.mkdirs();
      }
      this.repository = new Repository(workingDir);
      try {
        this.workingCopy = repository.load();
        this.initError = null;
      } catch (Exception e) {
        this.workingCopy = new Library();
        this.initError = e;
        throw e;
      }
    } else if (repository != null && workingCopy == null) {
      try {
        this.workingCopy = repository.load();
        this.initError = null;
      } catch (Exception e) {
        this.workingCopy = new Library();
        this.initError = e;
        throw e;
      }
    }
    this.generation = 1L;
    this.revision = 0L;
  }

  public synchronized Exception getInitError() {
    return initError;
  }

  public synchronized void clearInitError() {
    this.initError = null;
  }

  @Override
  public synchronized long getGeneration() {
    return generation;
  }

  @Override
  public synchronized long getRevision() {
    return revision;
  }

  public synchronized Library getWorkingLibrarySnapshot() {
    return workingCopy != null ? new Library(workingCopy.snapshot()) : new Library();
  }

  @Override
  public synchronized ReadOnlyView loadSnapshot() throws Exception {
    return new ReadOnlyView(workingCopy.snapshot(), generation, revision);
  }

  @Override
  public synchronized ReadOnlyView query(QueryRequest request) throws Exception {
    if (request == null || request.getType() == QueryRequest.Type.FULL_SNAPSHOT) {
      return loadSnapshot();
    }
    switch (request.getType()) {
      case VOCABULARY_BY_ID: {
        JSONObject v = workingCopy.find("vocabularies", request.getEntityId());
        JSONObject doc = new JSONObject();
        if (v != null) {
          JSONArray arr = new JSONArray();
          arr.put(Library.copy(v));
          Library.put(doc, "vocabularies", arr);
        }
        return new ReadOnlyView(doc, generation, revision);
      }
      case NOTEBOOK_BY_ID: {
        JSONObject nb = workingCopy.find("notebooks", request.getEntityId());
        JSONObject doc = new JSONObject();
        if (nb != null) {
          JSONArray arr = new JSONArray();
          arr.put(Library.copy(nb));
          Library.put(doc, "notebooks", arr);
        }
        return new ReadOnlyView(doc, generation, revision);
      }
      case SETTINGS: {
        JSONObject doc = new JSONObject();
        Library.put(doc, "settings", Library.copy(workingCopy.settings()));
        return new ReadOnlyView(doc, generation, revision);
      }
      case REVIEW_EVENT: {
        JSONObject doc = new JSONObject();
        JSONObject event = workingCopy.reviewPlans().findEvent(request.getEntityId());
        if (event != null) Library.put(doc, "event", event);
        return new ReadOnlyView(doc, generation, revision);
      }
      case DAILY_QUEUE: {
        long now = request.getParams().optLong("now_ms", System.currentTimeMillis());
        JSONObject day = workingCopy.reviewPlans().peekDailyQueue(request.getEntityId(), now);
        JSONObject doc = new JSONObject();
        if (day != null) {
          Library.put(doc, "daily_queue", day);
        }
        return new ReadOnlyView(doc, generation, revision);
      }
      default:
        return loadSnapshot();
    }
  }

  @Override
  public synchronized CommitResult execute(LibraryCommand command) throws Exception {
    Objects.requireNonNull(command, "command cannot be null");

    // 1. Generation barrier check
    long expectedGen = command.getExpectedGeneration();
    if (expectedGen > 0 && expectedGen != generation) {
      return CommitResult.conflict(
          expectedGen, generation, command.getEventId(), "Expected generation mismatch");
    }

    if (command.getType() == LibraryCommand.Type.SAVE_SESSION) {
      JSONObject payload = command.getPayload();
      if (payload.has("base_revision") && payload.optLong("base_revision", -1) != revision)
        return CommitResult.failure(CommitResult.Status.GENERATION_CONFLICT, generation, revision,
            command.getEventId(), "会话快照版本已过期，请刷新后重试", null);
    }

    JSONObject capturedPayload = command.getPayload();
    if (capturedPayload.has("base_revision") && capturedPayload.optLong("base_revision") != revision)
      return CommitResult.conflict(command.getExpectedGeneration(), generation, command.getEventId(), "Stale snapshot revision");

    // 2. Pre-mutation working snapshot for atomic rollback
    JSONObject beforeSnapshot = workingCopy.snapshot();

    try {
      JSONObject p = command.getPayload();
      JSONObject updatedEntity = null;
      List<String> affectedIds = new ArrayList<>();
      boolean isIdempotentIgnored = false;

      switch (command.getType()) {
        case RECORD_REVIEW: {
          String planId = p.optString("plan_id");
          String vocabId = p.optString("vocab_id");
          String grade = p.optString("grade");
          long duration = p.optLong("duration_ms");
          long occurredAt = p.optLong("occurred_at", command.getTimestamp());
          String eventId = command.getEventId();

          // Check if event already exists
          JSONObject existing = workingCopy.reviewPlans().findEvent(eventId);

          if (existing != null) {
            // Verify identical parameters per ReviewPlans.record contract
            if (!planId.equals(existing.optString("plan_id"))
                || !vocabId.equals(existing.optString("vocab_id"))
                || !grade.equals(existing.optString("grade"))
                || duration != existing.optLong("duration_ms")
                || !java.time.Instant.ofEpochMilli(occurredAt).toString().equals(existing.optString("occurred_at"))) {
              throw new IllegalArgumentException("事件ID已用于其他答题");
            }
            isIdempotentIgnored = true;
            updatedEntity = existing;
          } else {
            updatedEntity = workingCopy.reviewPlans().record(
                planId, vocabId, grade, eventId, duration, occurredAt);
            affectedIds.add(vocabId);
          }
          break;
        }

        case PREPARE_DAILY_QUEUE: {
          String planId = p.optString("plan_id");
          long now = p.optLong("now_ms", command.getTimestamp());

          if (planId != null && !planId.isEmpty()) {
            boolean created = workingCopy.reviewPlans().prepareDailyQueue(planId, now);
            if (!created) {
              isIdempotentIgnored = true;
            } else {
              updatedEntity = workingCopy.reviewPlans().peekDailyQueue(planId, now);
            }
          } else {
            JSONArray plans = workingCopy.reviewPlans().list(false);
            boolean anyCreated = false;
            for (int i = 0; i < plans.length(); i++) {
              JSONObject plan = plans.optJSONObject(i);
              if (plan != null) {
                anyCreated |= workingCopy.reviewPlans().prepareDailyQueue(plan.optString("id"), now);
              }
            }
            if (!anyCreated) {
              isIdempotentIgnored = true;
            }
          }
          break;
        }

        case EXTEND_DAILY_QUEUE: {
          String planId = p.optString("plan_id");
          int extraNew = p.optInt("extra_new", 0);
          int extraReview = p.optInt("extra_review", 0);
          long now = p.optLong("now_ms", command.getTimestamp());
          updatedEntity = workingCopy.reviewPlans().extendDailyQueue(planId, extraNew, extraReview, now);
          break;
        }

        case CREATE_REVIEW_PLAN: {
          String name = p.optString("name");
          String notebookId = p.optString("notebook_id");
          int dailyNewLimit = p.optInt("daily_new_limit", 15);
          int dailyReviewLimit = p.optInt("daily_review_limit", 40);
          JSONArray weekdaysArr = p.optJSONArray("weekdays");
          int[] weekdays = new int[weekdaysArr != null ? weekdaysArr.length() : 0];
          for (int i = 0; i < weekdays.length; i++) {
            weekdays[i] = weekdaysArr.optInt(i);
          }
          String zoneId = p.optString("zone_id", "Asia/Shanghai");
          boolean importLegacy = p.optBoolean("import_legacy", false);
          long now = p.optLong("now_ms", command.getTimestamp());

          String planId = workingCopy.reviewPlans().create(
              name, notebookId, dailyNewLimit, dailyReviewLimit, weekdays, zoneId, importLegacy, now);
          updatedEntity = workingCopy.reviewPlans().get(planId);
          affectedIds.add(planId);
          break;
        }

        case UPDATE_REVIEW_PLAN: {
          String planId = command.getEntityId();
          JSONObject patch = p.optJSONObject("patch");
          long now = p.optLong("now_ms", command.getTimestamp());
          if (patch != null) {
            workingCopy.reviewPlans().update(planId, patch, now);
            updatedEntity = workingCopy.reviewPlans().get(planId);
          }
          affectedIds.add(planId);
          break;
        }

        case ARCHIVE_REVIEW_PLAN: {
          String planId = command.getEntityId();
          long now = p.optLong("now_ms", command.getTimestamp());
          workingCopy.reviewPlans().archive(planId, now);
          updatedEntity = workingCopy.reviewPlans().get(planId);
          affectedIds.add(planId);
          break;
        }

        case CREATE_NOTEBOOK: {
          String name = p.optString("name");
          String parentId = p.has("parent_id") && !p.isNull("parent_id") ? p.optString("parent_id") : null;
          if (parentId != null && parentId.isEmpty()) parentId = null;
          if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("笔记本名称不能为空");
          }
          if (parentId != null && workingCopy.find("notebooks", parentId) == null) {
            throw new IllegalArgumentException("父笔记本不存在: " + parentId);
          }
          String nbId = workingCopy.createNotebook(name.trim(), parentId);
          updatedEntity = workingCopy.find("notebooks", nbId);
          affectedIds.add(nbId);
          break;
        }

        case RENAME_NOTEBOOK: {
          String nbId = command.getEntityId();
          String name = p.optString("name");
          if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("笔记本名称不能为空");
          }
          JSONObject nb = workingCopy.find("notebooks", nbId);
          if (nb == null) {
            throw new IllegalArgumentException("笔记本不存在: " + nbId);
          }
          Library.put(nb, "name", name.trim());
          Library.put(nb, "updated_at", Library.now());
          updatedEntity = nb;
          affectedIds.add(nbId);
          break;
        }

        case DELETE_NOTEBOOK: {
          String nbId = command.getEntityId();
          workingCopy.deleteNotebook(nbId);
          affectedIds.add(nbId);
          break;
        }

        case RENAME_VOCABULARY: {
          String vocabId = command.getEntityId();
          String content = p.optString("content");
          workingCopy.renameVocabulary(vocabId, content);
          updatedEntity = workingCopy.find("vocabularies", vocabId);
          affectedIds.add(vocabId);
          break;
        }

        case DELETE_VOCABULARIES: {
          JSONArray idsArr = p.optJSONArray("ids");
          if (idsArr != null) {
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < idsArr.length(); i++) {
              ids.add(idsArr.optString(i));
            }
            workingCopy.deleteVocabularyEverywhere(ids.toArray(new String[0]));
            affectedIds.addAll(ids);
          }
          break;
        }

        case BATCH_REFERENCES: {
          String sourceNbId = p.optString("source_notebook_id");
          JSONArray vIds = p.optJSONArray("vocab_ids");
          JSONArray targetNbIds = p.optJSONArray("target_notebook_ids");
          boolean copy = p.optBoolean("copy", true);

          List<String> vocabList = new ArrayList<>();
          if (vIds != null) {
            for (int i = 0; i < vIds.length(); i++) vocabList.add(vIds.optString(i));
          }
          List<String> targetList = new ArrayList<>();
          if (targetNbIds != null) {
            for (int i = 0; i < targetNbIds.length(); i++) targetList.add(targetNbIds.optString(i));
          }
          workingCopy.batchReferences(sourceNbId, vocabList.toArray(new String[0]), targetList.toArray(new String[0]), copy);
          affectedIds.addAll(vocabList);
          break;
        }

        case REMOVE_REFERENCES: {
          String nbId = p.optString("notebook_id");
          String vocabId = p.optString("vocab_id");
          workingCopy.removeReferences(nbId, new String[]{vocabId});
          affectedIds.add(vocabId);
          break;
        }

        case SAVE_QUERY_RESULT: {
          JSONObject result = p.optJSONObject("result");
          if (result == null && p.has("content")) {
            // direct vocabulary payload format
            result = p;
          }
          JSONArray nbArr = p.optJSONArray("notebook_ids");
          String[] nbIds = null;
          if (nbArr != null) {
            nbIds = new String[nbArr.length()];
            for (int i = 0; i < nbArr.length(); i++) nbIds[i] = nbArr.optString(i);
          }
          JSONObject display = p.optJSONObject("display");
          workingCopy.saveResult(result, nbIds, display);

          String word = result != null ? result.optString("word", result.optString("content")) : null;
          JSONObject savedVocab = word != null ? workingCopy.local(word) : null;
          if (savedVocab != null) {
            updatedEntity = savedVocab;
            affectedIds.add(savedVocab.optString("id"));
          }
          break;
        }

        case UPDATE_SETTINGS: {
          String key = p.optString("key");
          Object value = p.opt("value");
          if (key != null && !key.isEmpty()) {
            workingCopy.settings().put(key, value);
          }
          break;
        }

        case SAVE_SESSION: {
          String sessionId = p.optString("session_id");
          JSONObject sessionJson = p.optJSONObject("session");
          boolean updateRecent = p.optBoolean("update_recent", false);
          if (sessionJson != null) {
            workingCopy.touchSession(sessionJson, updateRecent);
            updatedEntity = sessionJson;
          }
          if (sessionId != null) affectedIds.add(sessionId);
          break;
        }

        case DELETE_SESSION: {
          String sessionId = command.getEntityId();
          workingCopy.delete("conversations", sessionId);
          affectedIds.add(sessionId);
          break;
        }

        case PRUNE_SESSIONS: {
          workingCopy.pruneQuerySessions();
          break;
        }

        case MUTATE_CUSTOM: {
          if (p.has("snapshot")) {
            workingCopy = new Library(p.optJSONObject("snapshot"));
          } else if (command.getCustomMutator() != null) {
            command.getCustomMutator().accept(workingCopy);
          }
          break;
        }

        case IMPORT_LIBRARY: {
          JSONObject backup = p.optJSONObject("backup");
          if (backup == null) {
            throw new IllegalArgumentException("Backup JSON payload cannot be null");
          }
          return restore(backup, command.getExpectedGeneration(), command.getEventId());
        }

        case ROLLBACK_LIBRARY: {
          return rollback(command.getExpectedGeneration(), command.getEventId());
        }

        case PRESERVE_CORRUPT_DATA: {
          repository.preserveCorruptData();
          return CommitResult.success(
              generation,
              revision,
              command.getEventId(),
              null,
              Collections.emptyList(),
              "CORRUPT_DATA_PRESERVED",
              new Library(workingCopy.snapshot()));
        }

        default:
          throw new UnsupportedOperationException("Unsupported command type: " + command.getType());
      }

      // If already idempotent, skip disk write and revision bump
      if (isIdempotentIgnored) {
        return CommitResult.idempotent(
            generation, revision, command.getEventId(), updatedEntity, "Idempotent commit ignored",
            new Library(workingCopy.snapshot()));
      }

      // 3. Persist working copy to disk
      repository.save(workingCopy);
      revision++;

      return CommitResult.success(
          generation,
          revision,
          command.getEventId(),
          updatedEntity,
          affectedIds,
          "Committed successfully",
          new Library(workingCopy.snapshot()));

    } catch (IllegalArgumentException e) {
      workingCopy = new Library(beforeSnapshot);
      return CommitResult.failure(
          CommitResult.Status.VALIDATION_FAILED,
          generation,
          revision,
          command.getEventId(),
          e.getMessage(),
          e);
    } catch (IOException e) {
      workingCopy = new Library(beforeSnapshot);
      boolean isCapacity = e.getMessage() != null
          && (e.getMessage().contains("最高4 MB") || e.getMessage().contains("超过当前设备容量"));
      return CommitResult.failure(
          isCapacity ? CommitResult.Status.CAPACITY_EXCEEDED : CommitResult.Status.IO_ERROR,
          generation,
          revision,
          command.getEventId(),
          e.getMessage(),
          e);
    } catch (Exception e) {
      workingCopy = new Library(beforeSnapshot);
      return CommitResult.failure(
          CommitResult.Status.IO_ERROR,
          generation,
          revision,
          command.getEventId(),
          e.getMessage(),
          e);
    }
  }

  @Override
  public synchronized CommitResult applyTransaction(List<LibraryCommand> commands) throws Exception {
    Objects.requireNonNull(commands, "commands cannot be null");
    if (commands.isEmpty()) {
      return CommitResult.success(generation, revision, Library.id(), null, null, "Empty transaction");
    }

    // Verify all expected generations first
    for (LibraryCommand cmd : commands) {
      if (cmd.getExpectedGeneration() > 0 && cmd.getExpectedGeneration() != generation) {
        return CommitResult.conflict(
            cmd.getExpectedGeneration(), generation, cmd.getEventId(), "Transaction aborted: generation mismatch");
      }
    }

    long startRevision = revision;
    JSONObject transactionStartSnapshot = workingCopy.snapshot();
    List<String> allAffected = new ArrayList<>();
    JSONObject lastUpdated = null;

    try {
      for (LibraryCommand cmd : commands) {
        CommitResult res = execute(cmd);
        if (!res.isSuccess()) {
          // Abort and rollback entire transaction
          workingCopy = new Library(transactionStartSnapshot);
          repository.save(workingCopy);
          revision = startRevision;
          return res;
        }
        if (res.getUpdatedEntity() != null) lastUpdated = res.getUpdatedEntity();
        allAffected.addAll(res.getAffectedEntityIds());
      }
      return CommitResult.success(
          generation, revision, Library.id(), lastUpdated, allAffected, "Transaction applied",
          new Library(workingCopy.snapshot()));
    } catch (Exception e) {
      workingCopy = new Library(transactionStartSnapshot);
      revision = startRevision;
      try {
        repository.save(workingCopy);
      } catch (Exception ignored) {}
      return CommitResult.failure(
          CommitResult.Status.IO_ERROR, generation, revision, Library.id(), e.getMessage(), e);
    }
  }

  public synchronized CommitResult restore(JSONObject backup, long expectedGeneration, String eventId) throws Exception {
    String evtId = eventId != null ? eventId : Library.id();
    if (expectedGeneration > 0 && expectedGeneration != generation) {
      return CommitResult.conflict(expectedGeneration, generation, evtId, "Expected generation mismatch on restore");
    }

    preRestoreBackup = workingCopy.snapshot();
    workingCopy = repository.restore(backup, workingCopy);
    this.initError = null;
    generation++;
    revision = 0L;

    return CommitResult.success(
        generation, revision, evtId, null, Collections.emptyList(), "RESTORED", new Library(workingCopy.snapshot()));
  }

  @Override
  public synchronized CommitResult restore(JSONObject backup, long expectedGeneration) throws Exception {
    return restore(backup, expectedGeneration, Library.id());
  }

  public synchronized CommitResult rollback(long expectedGeneration, String eventId) throws Exception {
    String evtId = eventId != null ? eventId : Library.id();
    if (expectedGeneration > 0 && expectedGeneration != generation) {
      return CommitResult.conflict(expectedGeneration, generation, evtId, "Expected generation mismatch on rollback");
    }

    if (preRestoreBackup != null) {
      workingCopy = new Library(preRestoreBackup);
      repository.save(workingCopy);
    } else {
      workingCopy = repository.rollback();
    }
    generation++;
    revision = 0L;

    return CommitResult.success(
        generation, revision, evtId, null, Collections.emptyList(), "ROLLED_BACK", new Library(workingCopy.snapshot()));
  }

  @Override
  public synchronized CommitResult rollback(long expectedGeneration) throws Exception {
    return rollback(expectedGeneration, Library.id());
  }

  @Override
  public synchronized void migrateFrom(File legacyFile) throws Exception {
    // Migration SPI placeholder for Task 3
  }

  @Override
  public synchronized void close() {
    // No-op for JsonStoreBackend
  }
}
