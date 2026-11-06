package com.lexinote.nativeapp.storage.contract;

import com.lexinote.nativeapp.Library;
import java.util.Objects;
import org.json.JSONObject;

/**
 * Structured query specification for StoreBackend reads.
 */
public final class QueryRequest {

  public enum Type {
    FULL_SNAPSHOT,
    VOCABULARY_BY_ID,
    NOTEBOOK_BY_ID,
    DAILY_QUEUE,
    REVIEW_EVENT,
    PLAN_STATISTICS,
    SETTINGS,
    SESSIONS
  }

  private final Type type;
  private final String entityId;
  private final JSONObject params;

  public QueryRequest(Type type, String entityId, JSONObject params) {
    this.type = Objects.requireNonNull(type, "type cannot be null");
    this.entityId = entityId;
    this.params = params == null ? new JSONObject() : Library.copy(params);
  }

  public Type getType() { return type; }
  public String getEntityId() { return entityId; }
  public JSONObject getParams() { return Library.copy(params); }

  public static QueryRequest fullSnapshot() {
    return new QueryRequest(Type.FULL_SNAPSHOT, null, null);
  }

  public static QueryRequest vocabulary(String id) {
    return new QueryRequest(Type.VOCABULARY_BY_ID, id, null);
  }

  public static QueryRequest notebook(String id) {
    return new QueryRequest(Type.NOTEBOOK_BY_ID, id, null);
  }

  public static QueryRequest settings() {
    return new QueryRequest(Type.SETTINGS, null, null);
  }

  public static QueryRequest reviewEvent(String eventId) {
    return new QueryRequest(Type.REVIEW_EVENT, eventId, null);
  }

  public static QueryRequest dailyQueue(String planId, long nowMs) {
    JSONObject p = new JSONObject();
    Library.put(p, "now_ms", nowMs);
    return new QueryRequest(Type.DAILY_QUEUE, planId, p);
  }
}
