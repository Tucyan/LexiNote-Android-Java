package com.lexinote.nativeapp.storage.contract;

import com.lexinote.nativeapp.Library;
import java.util.*;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Immutable business command submitted to LibraryStore.
 * UI never passes a mutable working copy or unmanaged reference.
 */
public final class LibraryCommand {

  public enum Type {
    RECORD_REVIEW,          // Record card rating (idempotent via eventId)
    PREPARE_DAILY_QUEUE,    // Explicit daily queue generation if absent
    EXTEND_DAILY_QUEUE,     // Extend daily queue with extra cards
    CREATE_REVIEW_PLAN,     // Create a new review plan
    UPDATE_REVIEW_PLAN,     // Update plan parameters (limits, weekdays)
    ARCHIVE_REVIEW_PLAN,    // Archive a review plan
    SAVE_QUERY_RESULT,      // Save dictionary search result to notebooks
    CREATE_NOTEBOOK,        // Create new notebook
    RENAME_NOTEBOOK,        // Rename notebook
    DELETE_NOTEBOOK,        // Delete notebook and children
    RENAME_VOCABULARY,      // Rename vocabulary text
    DELETE_VOCABULARIES,    // Delete vocabulary entities everywhere
    BATCH_REFERENCES,       // Batch copy/move vocabulary references
    REMOVE_REFERENCES,      // Remove vocabulary references from a notebook
    UPDATE_SETTINGS,        // Update settings key/value
    SAVE_SESSION,           // Upsert conversation or query session
    DELETE_SESSION,         // Delete conversation or query session
    PRUNE_SESSIONS,         // Clean up expired query sessions
    IMPORT_LIBRARY,         // Full backup restore (increments generation)
    ROLLBACK_LIBRARY,       // Rollback to pre-restore state (increments generation)
    PRESERVE_CORRUPT_DATA,  // Diagnostic copy of unreadable data
    BATCH_MUTATE,           // Composite atomic transaction
    MUTATE_CUSTOM           // Transition helper: safely runs mutation on worker thread
  }

  private final Type type;
  private final String entityId;
  private final JSONObject payload;
  private final long expectedGeneration;
  private final String eventId;
  private final long timestamp;
  private final Consumer<Library> customMutator;

  public LibraryCommand(
      Type type,
      String entityId,
      JSONObject payload,
      long expectedGeneration,
      String eventId,
      long timestamp) {
    this(type, entityId, payload, expectedGeneration, eventId, timestamp, null);
  }

  public LibraryCommand(
      Type type,
      String entityId,
      JSONObject payload,
      long expectedGeneration,
      String eventId,
      long timestamp,
      Consumer<Library> customMutator) {
    this.type = Objects.requireNonNull(type, "type cannot be null");
    this.entityId = entityId;
    this.payload = payload == null ? new JSONObject() : Library.copy(payload);
    this.expectedGeneration = expectedGeneration;
    this.eventId = eventId == null ? Library.id() : eventId;
    this.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
    this.customMutator = customMutator;
  }

  public Type getType() { return type; }
  public String getEntityId() { return entityId; }
  public JSONObject getPayload() { return Library.copy(payload); }
  public long getExpectedGeneration() { return expectedGeneration; }
  public String getEventId() { return eventId; }
  public long getTimestamp() { return timestamp; }
  public Consumer<Library> getCustomMutator() { return customMutator; }

  // --------------------------------------------------------------------------
  // Strongly-Typed Factory Methods
  // --------------------------------------------------------------------------

  public static LibraryCommand recordReview(
      long expectedGen,
      String eventId,
      String planId,
      String vocabId,
      String grade,
      long durationMs,
      long occurredAt) {
    JSONObject p = new JSONObject();
    Library.put(p, "plan_id", planId);
    Library.put(p, "vocab_id", vocabId);
    Library.put(p, "grade", grade);
    Library.put(p, "duration_ms", durationMs);
    Library.put(p, "occurred_at", occurredAt);
    return new LibraryCommand(Type.RECORD_REVIEW, planId, p, expectedGen, eventId, occurredAt);
  }

  public static LibraryCommand prepareDailyQueue(long expectedGen, String planId, long nowMs) {
    JSONObject p = new JSONObject();
    Library.put(p, "plan_id", planId);
    Library.put(p, "now_ms", nowMs);
    return new LibraryCommand(Type.PREPARE_DAILY_QUEUE, planId, p, expectedGen, Library.id(), nowMs);
  }

  public static LibraryCommand extendDailyQueue(
      long expectedGen, String planId, int extraNew, int extraReview, long nowMs) {
    JSONObject p = new JSONObject();
    Library.put(p, "plan_id", planId);
    Library.put(p, "extra_new", extraNew);
    Library.put(p, "extra_review", extraReview);
    Library.put(p, "now_ms", nowMs);
    return new LibraryCommand(Type.EXTEND_DAILY_QUEUE, planId, p, expectedGen, Library.id(), nowMs);
  }

  public static LibraryCommand saveQueryResult(
      long expectedGen,
      JSONObject result,
      String[] notebookIds,
      JSONObject displayConfig,
      long nowMs) {
    JSONObject p = new JSONObject();
    Library.put(p, "result", Library.copy(result));
    JSONArray nbs = new JSONArray();
    if (notebookIds != null) {
      for (String id : notebookIds) nbs.put(id);
    }
    Library.put(p, "notebook_ids", nbs);
    Library.put(p, "display", displayConfig == null ? new JSONObject() : Library.copy(displayConfig));
    return new LibraryCommand(Type.SAVE_QUERY_RESULT, null, p, expectedGen, Library.id(), nowMs);
  }

  public static LibraryCommand createNotebook(
      long expectedGen, String name, String parentId, long nowMs) {
    JSONObject p = new JSONObject();
    Library.put(p, "name", name);
    Library.put(p, "parent_id", parentId);
    return new LibraryCommand(Type.CREATE_NOTEBOOK, null, p, expectedGen, Library.id(), nowMs);
  }

  public static LibraryCommand updateSettings(
      long expectedGen, String key, Object value, long nowMs) {
    JSONObject p = new JSONObject();
    Library.put(p, "key", key);
    Library.put(p, "value", value);
    return new LibraryCommand(Type.UPDATE_SETTINGS, null, p, expectedGen, Library.id(), nowMs);
  }

  public static LibraryCommand touchSession(
      long expectedGen, String sessionId, JSONObject sessionJson, boolean updateRecentQueries, long nowMs) {
    JSONObject p = new JSONObject();
    Library.put(p, "session_id", sessionId);
    Library.put(p, "session", sessionJson == null ? new JSONObject() : Library.copy(sessionJson));
    Library.put(p, "update_recent", updateRecentQueries);
    return new LibraryCommand(Type.SAVE_SESSION, sessionId, p, expectedGen, Library.id(), nowMs);
  }

  public static LibraryCommand importLibrary(long expectedGen, JSONObject backup, long nowMs) {
    JSONObject p = new JSONObject();
    Library.put(p, "backup", Library.copy(backup));
    return new LibraryCommand(Type.IMPORT_LIBRARY, null, p, expectedGen, Library.id(), nowMs);
  }

  public static LibraryCommand rollbackLibrary(long expectedGen, long nowMs) {
    return new LibraryCommand(Type.ROLLBACK_LIBRARY, null, new JSONObject(), expectedGen, Library.id(), nowMs);
  }

  public static LibraryCommand mutateCustom(
      long expectedGen, String eventId, Consumer<Library> mutator, long nowMs) {
    return new LibraryCommand(
        Type.MUTATE_CUSTOM, null, new JSONObject(), expectedGen, eventId, nowMs, mutator);
  }

  /**
   * Persists a whole-document snapshot guarded by an expected storage generation barrier.
   * @param expectedGen active storage generation (from StoreBackend.getGeneration() or LibraryStore.currentGeneration()).
   *                    Pass 0L to bypass generation barrier checks. NEVER pass UI request cancellation tokens.
   * @param snapshot the serialized Library snapshot
   * @param nowMs timestamp of the mutation
   */
  public static LibraryCommand persistSnapshot(long expectedGen, JSONObject snapshot, long nowMs) {
    JSONObject p = new JSONObject();
    Library.put(p, "snapshot", snapshot != null ? Library.copy(snapshot) : new JSONObject());
    return new LibraryCommand(Type.MUTATE_CUSTOM, null, p, expectedGen, Library.id(), nowMs);
  }

  /**
   * Convenience factory to persist snapshot without generation barrier check (expectedGen = 0L).
   */
  public static LibraryCommand persistSnapshot(JSONObject snapshot, long nowMs) {
    return persistSnapshot(0L, snapshot, nowMs);
  }
}

