package com.lexinote.nativeapp.storage.contract;

import com.lexinote.nativeapp.Library;
import java.util.*;
import org.json.JSONObject;

/**
 * Immutable result of a storage commit delivered on the Android main thread.
 */
public final class CommitResult {

  public enum Status {
    SUCCESS,              // Persisted and committed
    IDEMPOTENT_IGNORED,   // Already applied (matching eventId)
    GENERATION_CONFLICT,  // Expected generation does not match active barrier
    VALIDATION_FAILED,    // Domain rule or schema violation
    ENTITY_NOT_FOUND,     // Referenced entity not found
    CAPACITY_EXCEEDED,    // Storage limit exceeded (e.g. 4MB JSON)
    IO_ERROR,             // Filesystem/disk failure
    DISPOSED              // UI token detached prior to delivery
  }

  private final Status status;
  private final long appliedGeneration;
  private final long appliedRevision;
  private final String message;
  private final JSONObject updatedEntity;
  private final List<String> affectedEntityIds;
  private final String eventId;
  private final Throwable cause;
  private final Library snapshot;

  private CommitResult(
      Status status,
      long appliedGeneration,
      long appliedRevision,
      String message,
      JSONObject updatedEntity,
      List<String> affectedEntityIds,
      String eventId,
      Throwable cause,
      Library snapshot) {
    this.status = Objects.requireNonNull(status, "status cannot be null");
    this.appliedGeneration = appliedGeneration;
    this.appliedRevision = appliedRevision;
    this.message = message == null ? "" : message;
    this.updatedEntity = updatedEntity == null ? null : Library.copy(updatedEntity);
    this.affectedEntityIds = affectedEntityIds == null
        ? Collections.emptyList()
        : Collections.unmodifiableList(new ArrayList<>(affectedEntityIds));
    this.eventId = eventId == null ? "" : eventId;
    this.cause = cause;
    this.snapshot = snapshot;
  }

  public boolean isSuccess() {
    return status == Status.SUCCESS || status == Status.IDEMPOTENT_IGNORED;
  }

  public boolean isConflict() {
    return status == Status.GENERATION_CONFLICT;
  }

  public Status getStatus() { return status; }
  public long getAppliedGeneration() { return appliedGeneration; }
  public long getAppliedRevision() { return appliedRevision; }
  public String getMessage() { return message; }
  public JSONObject getUpdatedEntity() {
    return updatedEntity == null ? null : Library.copy(updatedEntity);
  }
  public List<String> getAffectedEntityIds() { return affectedEntityIds; }
  public String getEventId() { return eventId; }
  public Throwable getCause() { return cause; }
  public Library getSnapshot() { return snapshot; }

  // --------------------------------------------------------------------------
  // Static Factory Methods
  // --------------------------------------------------------------------------

  public static CommitResult success(
      long generation,
      long revision,
      String eventId,
      JSONObject updatedEntity,
      List<String> affectedIds,
      String message,
      Library snapshot) {
    return new CommitResult(
        Status.SUCCESS, generation, revision, message, updatedEntity, affectedIds, eventId, null, snapshot);
  }

  public static CommitResult success(
      long generation,
      long revision,
      String eventId,
      JSONObject updatedEntity,
      List<String> affectedIds,
      String message) {
    return success(generation, revision, eventId, updatedEntity, affectedIds, message, null);
  }

  public static CommitResult idempotent(
      long generation,
      long revision,
      String eventId,
      JSONObject existingEntity,
      String message,
      Library snapshot) {
    return new CommitResult(
        Status.IDEMPOTENT_IGNORED, generation, revision, message, existingEntity, null, eventId, null, snapshot);
  }

  public static CommitResult idempotent(
      long generation,
      long revision,
      String eventId,
      JSONObject existingEntity,
      String message) {
    return idempotent(generation, revision, eventId, existingEntity, message, null);
  }

  public static CommitResult conflict(
      long expectedGen, long actualGen, String eventId, String message) {
    return new CommitResult(
        Status.GENERATION_CONFLICT,
        actualGen,
        0L,
        "Generation conflict: expected " + expectedGen + " but active is " + actualGen + ". " + message,
        null,
        null,
        eventId,
        null,
        null);
  }

  public static CommitResult failure(
      Status status, long generation, long revision, String eventId, String message, Throwable cause) {
    return new CommitResult(
        status, generation, revision, message, null, null, eventId, cause, null);
  }
}
