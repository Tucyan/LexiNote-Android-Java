package com.lexinote.nativeapp;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.JsonWriter;
import com.lexinote.nativeapp.storage.contract.*;
import com.lexinote.nativeapp.storage.JsonStoreBackend;
import java.io.File;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** SQLite library backend with generation-scoped migration and incremental transactions. */
public final class SqliteLibraryStore implements StoreBackend {
  private final LibraryDatabase helper;
  private final File legacyFile;
  private SQLiteDatabase db;
  public SqliteLibraryStore(Context context, String name) { this(context, name, null); }
  public SqliteLibraryStore(Context context, String name, File legacyFile) {
    helper = new LibraryDatabase(context, name); this.legacyFile = legacyFile;
  }

  @Override public synchronized void initialize() throws Exception {
    db = helper.getWritableDatabase();
    LibraryDatabase.ensureSessionIndexes(db);
    try (Cursor c = db.rawQuery("SELECT e.generation,e.kind,e.json FROM entities e LEFT JOIN session_index s ON s.generation=e.generation AND s.kind=e.kind AND s.id=e.id WHERE e.kind IN ('conversations','recent_queries') AND s.id IS NULL", null)) {
      while (c.moveToNext()) LegacyLibraryMigration.indexSession(db, c.getLong(0), c.getString(1), new JSONObject(c.getString(2)));
    }
    if (meta("active") == 0) initializeDataset();
    // Detect an incomplete or corrupted active dataset before returning it to callers.
    new StreamingLibraryValidator(db, meta("active")).validate();
  }
  private void initializeDataset() throws Exception {
    JSONObject source;
    JSONObject previous = null;
    if (legacyFile != null && (legacyFile.exists() || new File(legacyFile.getPath() + ".bak").exists())) {
      if (!legacyFile.getName().equals("library.json")) throw new IllegalArgumentException("Expected library.json");
      source = readLegacySnapshot(legacyFile);
      File before = new File(legacyFile.getParentFile(), "before-restore.json");
      File backup = new File(before.getPath() + ".bak");
      if (before.exists() || backup.exists()) previous = readLegacySnapshot(before);
    } else source = new Library().snapshot();
    Library.redact(source);
    if (previous != null) Library.redact(previous);
    long active = meta("next"), prior = previous == null ? 0 : active + 1;
    // Only active=0 reaches here. A crashed earlier attempt may have left staging rows.
    db.beginTransaction();
    try {
      for (String table : new String[]{"document_meta", "entities", "review_plans", "review_states", "review_days", "review_events", "session_index", "session_order"})
        db.delete(table, null, null);
      LegacyLibraryMigration.write(db, active, source);
      if (previous != null) LegacyLibraryMigration.write(db, prior, previous);
      db.setTransactionSuccessful();
    } finally { db.endTransaction(); }
    // Reopen verifies durable rows without exposing them through the active pointer.
    helper.close(); db = helper.getWritableDatabase();
    JSONObject staged = LegacyLibraryMigration.read(db, active);
    Library.validate(staged);
    if (!jsonEquals(new Library(source).snapshot(), staged)) throw new IllegalStateException("Migration verification failed");
    if (previous != null && !jsonEquals(new Library(previous).snapshot(), LegacyLibraryMigration.read(db, prior)))
      throw new IllegalStateException("Previous migration verification failed");
    db.beginTransaction();
    try {
      if (meta("active") != 0 || meta("next") != active) throw new IllegalStateException("Migration state changed");
      meta("active", active); meta("previous", prior); meta("next", previous == null ? active + 1 : prior + 1); meta("revision", 0);
      db.setTransactionSuccessful();
    } finally { db.endTransaction(); }
  }
  private static JSONObject readLegacySnapshot(File base) throws Exception {
    File backup = new File(base.getPath() + ".bak"); File source = backup.exists() ? backup : base;
    try (java.io.InputStream in = new java.io.FileInputStream(source);
         java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream()) {
      byte[] chunk = new byte[8192]; int count;
      while ((count = in.read(chunk)) != -1) {
        if (buffer.size() + count > BackupArchive.MAX_JSON_BYTES) throw new java.io.IOException("旧版数据超过容量限制");
        buffer.write(chunk, 0, count);
      }
      return new Library(new JSONObject(buffer.toString("UTF-8"))).snapshot();
    }
  }
  private long meta(String key) {
    try (Cursor c = db.rawQuery("SELECT value FROM store_meta WHERE key=?", new String[]{key})) {
      if (!c.moveToFirst()) throw new IllegalStateException("Missing metadata: " + key);
      return c.getLong(0);
    }
  }
  private void meta(String key, long value) {
    ContentValues v = new ContentValues(); v.put("value", value);
    if (db.update("store_meta", v, "key=?", new String[]{key}) != 1) throw new IllegalStateException(key);
  }
  @Override public synchronized long getGeneration() { return meta("active"); }
  @Override public synchronized long getRevision() { return meta("revision"); }
  @Override public synchronized ReadOnlyView loadSnapshot() throws Exception {
    require(getGeneration() != 0, "数据库尚未完成初始化");
    return new ReadOnlyView(LegacyLibraryMigration.read(db, getGeneration()), getGeneration(), getRevision());
  }
  /** Writes a consistent generation without assembling a whole-library document. */
  public synchronized void exportJson(OutputStream output) throws Exception {
    if (output == null) throw new IllegalArgumentException("output is null");
    db.beginTransactionNonExclusive();
    try {
      long generation = getGeneration();
      require(generation != 0, "数据库尚未完成初始化");
      JsonWriter writer = new JsonWriter(new java.io.BufferedWriter(
          new OutputStreamWriter(output, StandardCharsets.UTF_8), 32 * 1024));
      writer.beginObject();
      writeMeta(writer, generation, "document/");
      for (String kind : new String[]{"vocabularies", "notebooks", "recent_queries", "conversations"}) {
        writer.name(kind); writeRows(writer, generation, "entities", kind);
      }
      writer.name("review").beginObject();
      writeMeta(writer, generation, "review/");
      for (String kind : new String[]{"plans", "states", "days", "events"}) {
        writer.name(kind); writeRows(writer, generation, "review_" + kind, null);
      }
      writer.endObject(); writer.endObject(); writer.flush();
      db.setTransactionSuccessful();
    } finally { db.endTransaction(); }
  }
  /** Stages and validates a local JSON file before switching generations atomically. */
  public synchronized CommitResult importJson(File source, long expectedGeneration) {
    long active = getGeneration(), revision = getRevision();
    if (expectedGeneration > 0 && expectedGeneration != active)
      return CommitResult.conflict(expectedGeneration, active, "", "import");
    db.beginTransaction();
    try {
      long generation = meta("next");
      clearUnpublishedGeneration(generation);
      StreamingLibraryImport.stage(db, generation, source);
      new StreamingLibraryValidator(db, generation).validate();
      normalizeImportedRoots(generation);
      // Normalization can create references; recheck the actual dataset before publishing it.
      new StreamingLibraryValidator(db, generation).validate();
      meta("next", generation + 1); meta("previous", active); meta("active", generation); meta("revision", 0);
      for (String table : new String[]{"document_meta", "entities", "review_plans", "review_states", "review_days", "review_events", "session_index", "session_order"})
        db.delete(table, "generation<>? AND generation<>?", new String[]{Long.toString(generation), Long.toString(active)});
      db.setTransactionSuccessful();
      return CommitResult.success(generation, 0, "", null, Collections.emptyList(), "RESTORED");
    } catch (Exception error) {
      return CommitResult.failure(error instanceof IllegalArgumentException ? CommitResult.Status.VALIDATION_FAILED : CommitResult.Status.IO_ERROR, active, revision, "", error.getMessage(), error);
    } finally { db.endTransaction(); }
  }
  private void normalizeImportedRoots(long generation) throws Exception {
    String gen = Long.toString(generation);
    for (String[] spec : new String[][]{{"nb_default", "默认笔记", "is_default"}, {"nb_global_root", "全部词汇", "is_global_root"}}) {
      JSONObject note = row("entities", "generation=? AND kind='notebooks' AND id=?", gen, spec[0]);
      if (note == null) {
        note = new JSONObject(); note.put("id", spec[0]); note.put("name", spec[1]); note.put(spec[2], true);
        note.put("children_type", "vocabulary"); note.put("child_notebook_ids", new JSONArray()); note.put("vocabulary_ids", new JSONArray());
        note.put("vocabulary_display", new JSONObject()); note.put("created_at", Library.now()); note.put("updated_at", Library.now());
        ContentValues v = new ContentValues(); v.put("generation", generation); v.put("kind", "notebooks"); v.put("id", spec[0]);
        try (Cursor c = db.rawQuery("SELECT COALESCE(MAX(ordinal),-1)+1 FROM entities WHERE generation=? AND kind='notebooks'", new String[]{gen})) { c.moveToFirst(); v.put("ordinal", c.getLong(0)); }
        v.put("json", note.toString()); db.insertOrThrow("entities", null, v);
      }
    }
    JSONObject global = row("entities", "generation=? AND kind='notebooks' AND id='nb_global_root'", gen);
    JSONArray ids = new JSONArray();
    try (Cursor c = db.query("entities", new String[]{"id"}, "generation=? AND kind='vocabularies'", new String[]{gen}, null, null, "ordinal")) {
      while (c.moveToNext()) ids.put(c.getString(0));
    }
    global.put("vocabulary_ids", ids); ContentValues value = new ContentValues(); value.put("json", global.toString());
    db.update("entities", value, "generation=? AND kind='notebooks' AND id='nb_global_root'", new String[]{gen});
    JSONObject settings;
    try (Cursor c = db.query("document_meta", new String[]{"json"}, "generation=? AND key='document/settings'", new String[]{gen}, null, null, null)) {
      if (!c.moveToFirst()) throw new IllegalStateException("Missing settings"); settings = new JSONArray(c.getString(0)).getJSONObject(0);
    }
    JSONObject selected = row("entities", "generation=? AND kind='notebooks' AND id=?", gen, settings.optString("default_notebook_id"));
    if (selected == null || Library.systemGlobal(selected) || selected.optJSONArray("child_notebook_ids").length() > 0) {
      settings.put("default_notebook_id", "nb_default"); value = new ContentValues(); value.put("json", new JSONArray().put(settings).toString());
      db.update("document_meta", value, "generation=? AND key='document/settings'", new String[]{gen});
    }
    for (String kind : new String[]{"conversations", "recent_queries"}) {
      try (Cursor c = db.query("entities", new String[]{"id", "json"}, "generation=? AND kind=?", new String[]{gen, kind}, null, null, "ordinal")) {
        while (c.moveToNext()) {
          String id = c.getString(0); JSONObject session = new JSONObject(c.getString(1));
          boolean recent = row("entities", "generation=? AND kind='recent_queries' AND id=?", gen, id) != null;
          Library.normalizeSessionMetadata(session, recent);
          value = new ContentValues(); value.put("json", session.toString());
          db.update("entities", value, "generation=? AND kind=? AND id=?", new String[]{gen, kind, id});
          LegacyLibraryMigration.indexSession(db, generation, kind, session);
        }
      }
    }
  }
  private void writeMeta(JsonWriter writer, long generation, String prefix) throws Exception {
    try (Cursor c = db.query("document_meta", new String[]{"key", "json"}, "generation=?", new String[]{Long.toString(generation)}, null, null, "key")) {
      while (c.moveToNext()) {
        String key = c.getString(0);
        if (key.startsWith(prefix)) { writer.name(key.substring(prefix.length())); writeValue(writer, new JSONArray(c.getString(1)).get(0)); }
      }
    }
  }
  private void writeRows(JsonWriter writer, long generation, String table, String kind) throws Exception {
    if (kind != null && (kind.equals("conversations") || kind.equals("recent_queries"))) {
      try (Cursor ordered = db.rawQuery("SELECT 1 FROM session_order WHERE generation=? AND kind=?", new String[]{Long.toString(generation), kind})) {
        if (ordered.moveToFirst()) {
          writer.beginArray();
          try (Cursor c = db.rawQuery("SELECT e.json FROM entities e JOIN session_index s ON s.generation=e.generation AND s.kind=e.kind AND s.id=e.id WHERE e.generation=? AND e.kind=? ORDER BY s.sort_time DESC,s.sort_id DESC", new String[]{Long.toString(generation), kind})) {
            while (c.moveToNext()) writeValue(writer, new JSONObject(c.getString(0)));
          }
          writer.endArray(); return;
        }
      }
    }
    String where = kind == null ? "generation=?" : "generation=? AND kind=?";
    String[] args = kind == null ? new String[]{Long.toString(generation)} : new String[]{Long.toString(generation), kind};
    writer.beginArray();
    try (Cursor c = db.query(table, new String[]{"json"}, where, args, null, null, "ordinal")) {
      while (c.moveToNext()) writeValue(writer, new JSONObject(c.getString(0)));
    }
    writer.endArray();
  }
  private static void writeValue(JsonWriter writer, Object value) throws Exception {
    if (value == null || value == JSONObject.NULL) writer.nullValue();
    else if (value instanceof JSONObject) {
      JSONObject object = (JSONObject)value; writer.beginObject();
      java.util.Iterator<String> keys = object.keys();
      while (keys.hasNext()) { String key = keys.next(); writer.name(key); writeValue(writer, object.get(key)); }
      writer.endObject();
    } else if (value instanceof JSONArray) {
      JSONArray array = (JSONArray)value; writer.beginArray();
      for (int i = 0; i < array.length(); i++) writeValue(writer, array.get(i));
      writer.endArray();
    } else if (value instanceof Boolean) writer.value((Boolean)value);
    else if (value instanceof Number) writer.value((Number)value);
    else if (value instanceof String) writer.value((String)value);
    else throw new IllegalArgumentException("Unsupported JSON value");
  }
  @Override public synchronized ReadOnlyView query(QueryRequest request) throws Exception {
    require(getGeneration() != 0, "数据库尚未完成初始化");
    if (request != null && request.getType() == QueryRequest.Type.REVIEW_EVENT) {
      JSONObject result = new JSONObject();
      JSONObject event = row("review_events", "generation=? AND id=?", Long.toString(getGeneration()), request.getEntityId());
      if (event != null) result.put("event", event);
      return new ReadOnlyView(result, getGeneration(), getRevision());
    }
    if (request == null || request.getType() == QueryRequest.Type.FULL_SNAPSHOT) return loadSnapshot();
    JsonStoreBackend domain = new JsonStoreBackend(new Repository(new File(".")), new Library(LegacyLibraryMigration.read(db, getGeneration())));
    domain.initialize();
    return new ReadOnlyView(domain.query(request).getRawDocument(), getGeneration(), getRevision());
  }
  private void replace(JSONObject snapshot, boolean remember) throws Exception {
    snapshot = Library.copy(snapshot);
    Library.redact(snapshot);
    Library.validate(snapshot);
    db.beginTransaction();
    try {
      long old = meta("active"), generation = meta("next");
      clearUnpublishedGeneration(generation);
      LegacyLibraryMigration.write(db, generation, snapshot);
      JSONObject reread = LegacyLibraryMigration.read(db, generation);
      Library.validate(reread);
      // Compare normalized documents, including unknown fields and array order.
      if (!jsonEquals(new Library(snapshot).snapshot(), reread)) throw new IllegalStateException("Migration verification failed");
      meta("next", generation + 1); meta("active", generation);
      meta("previous", remember ? old : 0); meta("revision", 0);
      // There are no streaming readers in this backend yet; retain only active and rollback datasets.
      for (String table : new String[]{"document_meta", "entities", "review_plans", "review_states", "review_days", "review_events", "session_index", "session_order"})
        db.delete(table, "generation<>? AND generation<>?", new String[]{Long.toString(generation), Long.toString(remember ? old : 0)});
      db.setTransactionSuccessful();
    } finally { db.endTransaction(); }
  }
  private void clearUnpublishedGeneration(long generation) {
    if (generation == meta("active") || generation == meta("previous"))
      throw new IllegalStateException("Staging generation is currently protected");
    for (String table : new String[]{"document_meta", "entities", "review_plans", "review_states", "review_days", "review_events", "session_index", "session_order"})
      db.delete(table, "generation=?", new String[]{Long.toString(generation)});
  }
  private static boolean jsonEquals(Object a, Object b) throws Exception {
    if (a instanceof JSONObject && b instanceof JSONObject) {
      JSONObject x = (JSONObject)a, y = (JSONObject)b;
      if (x.length() != y.length()) return false;
      java.util.Iterator<String> keys = x.keys();
      while (keys.hasNext()) { String k = keys.next(); if (!y.has(k) || !jsonEquals(x.get(k), y.get(k))) return false; }
      return true;
    }
    if (a instanceof org.json.JSONArray && b instanceof org.json.JSONArray) {
      org.json.JSONArray x = (org.json.JSONArray)a, y = (org.json.JSONArray)b;
      if (x.length() != y.length()) return false;
      for (int i = 0; i < x.length(); i++) if (!jsonEquals(x.get(i), y.get(i))) return false;
      return true;
    }
    return a.equals(b);
  }
  @Override public synchronized CommitResult restore(JSONObject backup, long expected) throws Exception {
    if (expected > 0 && expected != getGeneration()) return CommitResult.conflict(expected, getGeneration(), "", "restore");
    replace(backup, true);
    return CommitResult.success(getGeneration(), getRevision(), "", null, Collections.emptyList(), "RESTORED", new Library(loadSnapshot().getRawDocument()));
  }
  @Override public synchronized CommitResult rollback(long expected) throws Exception {
    if (expected > 0 && expected != getGeneration()) return CommitResult.conflict(expected, getGeneration(), "", "rollback");
    long previous = meta("previous");
    if (previous == 0) return CommitResult.failure(CommitResult.Status.VALIDATION_FAILED, getGeneration(), getRevision(), "", "没有可回退的数据", null);
    replace(LegacyLibraryMigration.read(db, previous), true);
    return CommitResult.success(getGeneration(), getRevision(), "", null, Collections.emptyList(), "ROLLED_BACK", new Library(loadSnapshot().getRawDocument()));
  }
  @Override public synchronized void migrateFrom(File file) throws Exception {
    if (file == null || (!file.isFile() && !new File(file.getPath() + ".bak").isFile())) throw new java.io.FileNotFoundException(String.valueOf(file));
    if (!file.getName().equals("library.json")) throw new IllegalArgumentException("Expected library.json");
    replace(readLegacySnapshot(file), false);
  }
  @Override public synchronized CommitResult execute(LibraryCommand command) throws Exception {
    if (getGeneration() == 0 && command.getType() != LibraryCommand.Type.IMPORT_LIBRARY)
      return CommitResult.failure(CommitResult.Status.IO_ERROR, 0, getRevision(), command.getEventId(), "数据库尚未完成初始化", null);
    if (command.getExpectedGeneration() > 0 && command.getExpectedGeneration() != getGeneration())
      return CommitResult.conflict(command.getExpectedGeneration(), getGeneration(), command.getEventId(), "command");
    if (command.getType() == LibraryCommand.Type.IMPORT_LIBRARY) return restore(command.getPayload().getJSONObject("backup"), command.getExpectedGeneration());
    if (command.getType() == LibraryCommand.Type.ROLLBACK_LIBRARY) return rollback(command.getExpectedGeneration());
    if (command.getType() == LibraryCommand.Type.RECORD_REVIEW) return record(command);
    if (command.getType() == LibraryCommand.Type.PREPARE_DAILY_QUEUE) return prepareDailyQueues(command);
    if (command.getType() == LibraryCommand.Type.SAVE_SESSION) return touchSession(command);
    if (command.getType() == LibraryCommand.Type.PRESERVE_CORRUPT_DATA)
      return CommitResult.failure(CommitResult.Status.VALIDATION_FAILED, getGeneration(), getRevision(), command.getEventId(), "SQLite diagnostic copy is not implemented", null);
    return slowTransaction(Collections.singletonList(command));
  }

  private JSONObject row(String table, String where, String... args) throws Exception {
    try (Cursor c = db.query(table, new String[]{"json"}, where, args, null, null, null)) {
      return c.moveToFirst() ? new JSONObject(c.getString(0)) : null;
    }
  }
  private void collectPool(String generation, String notebookId, Set<String> visited, Set<String> words) throws Exception {
    if (!visited.add(notebookId)) return;
    JSONObject note = row("entities", "generation=? AND kind='notebooks' AND id=?", generation, notebookId);
    if (note == null) return;
    JSONArray ids = note.optJSONArray("vocabulary_ids"), children = note.optJSONArray("child_notebook_ids");
    for (int i = 0; ids != null && i < ids.length(); i++) words.add(ids.optString(i));
    for (int i = 0; children != null && i < children.length(); i++) collectPool(generation, children.optString(i), visited, words);
  }
  private CommitResult prepareDailyQueues(LibraryCommand command) {
    long gen = getGeneration(), revision = getRevision(); String generation = Long.toString(gen);
    JSONObject payload = command.getPayload(); String requested = payload.optString("plan_id");
    long now = payload.optLong("now_ms", command.getTimestamp());
    db.beginTransaction();
    try {
      JSONArray plans = new JSONArray(), days = new JSONArray();
      if (!requested.isEmpty()) {
        JSONObject plan = row("review_plans", "generation=? AND id=?", generation, requested);
        require(plan != null, "复习计划不存在"); plans.put(plan);
      } else {
        try (Cursor c = db.query("review_plans", new String[]{"json"}, "generation=?", new String[]{generation}, null, null, "ordinal")) {
          while (c.moveToNext()) { JSONObject plan = new JSONObject(c.getString(0)); if (!plan.optBoolean("archived")) plans.put(plan); }
        }
      }
      db.execSQL("CREATE TEMP TABLE IF NOT EXISTS queue_pool (id TEXT PRIMARY KEY)");
      boolean changed = false;
      for (int index = 0; index < plans.length(); index++) {
        JSONObject plan = plans.getJSONObject(index); String planId = plan.optString("id"), notebookId = plan.optString("notebook_id");
        require(!plan.optBoolean("archived"), "复习计划已归档");
        require(row("entities", "generation=? AND kind='notebooks' AND id=?", generation, notebookId) != null, "绑定的笔记本不存在");
        ZoneId zone = ZoneId.of(plan.optString("zone_id"));
        java.time.LocalDate localDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate(); String date = localDate.toString();
        JSONObject day = row("review_days", "generation=? AND plan_id=? AND date=?", generation, planId, date);
        if (day == null) {
          boolean scheduled = false; JSONArray weekdays = plan.optJSONArray("weekdays");
          for (int i = 0; weekdays != null && i < weekdays.length(); i++) if (weekdays.optInt(i) == localDate.getDayOfWeek().getValue()) scheduled = true;
          java.util.ArrayList<String> fresh = new java.util.ArrayList<>();
          java.util.HashMap<String, Long> due = new java.util.HashMap<>();
          if (scheduled) {
            Set<String> words = new java.util.LinkedHashSet<>(); collectPool(generation, notebookId, new HashSet<>(), words);
            db.delete("queue_pool", null, null);
            try (android.database.sqlite.SQLiteStatement insert = db.compileStatement("INSERT OR IGNORE INTO queue_pool(id) VALUES(?)")) {
              for (String id : words) { insert.bindString(1, id); insert.executeInsert(); }
            }
            long tomorrow = localDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            try (Cursor c = db.rawQuery("SELECT e.id,s.json FROM queue_pool q JOIN entities e ON e.generation=? AND e.kind='vocabularies' AND e.id=q.id LEFT JOIN review_states s ON s.generation=e.generation AND s.plan_id=? AND s.vocab_id=e.id", new String[]{generation, planId})) {
              while (c.moveToNext()) {
                String id = c.getString(0); JSONObject state = c.isNull(1) ? null : new JSONObject(c.getString(1));
                if (state == null || state.isNull("learned_at")) fresh.add(id);
                else if (state.optLong("review_due") < tomorrow) due.put(id, state.optLong("review_due"));
              }
            }
          }
          Collections.sort(fresh); java.util.ArrayList<String> review = new java.util.ArrayList<>(due.keySet());
          review.sort(java.util.Comparator.comparingLong((String id) -> due.get(id)).thenComparing(id -> id));
          int newTarget = Math.min(plan.optInt("daily_new_limit"), fresh.size()), reviewTarget = Math.min(plan.optInt("daily_review_limit"), review.size());
          JSONArray tasks = new JSONArray();
          for (int i = 0; i < reviewTarget; i++) tasks.put(ReviewPlans.object("vocab_id", review.get(i), "kind", "review", "completed", false, "attempts", 0));
          for (int i = 0; i < newTarget; i++) tasks.put(ReviewPlans.object("vocab_id", fresh.get(i), "kind", "new", "completed", false, "attempts", 0));
          day = ReviewPlans.object("plan_id", planId, "date", date, "zone_id", plan.optString("zone_id"), "scheduled", scheduled,
              "daily_new_limit", plan.optInt("daily_new_limit"), "daily_review_limit", plan.optInt("daily_review_limit"),
              "new_target", newTarget, "review_target", reviewTarget, "created_at", ReviewPlans.timestamp(now), "tasks", tasks);
          ContentValues value = new ContentValues(); value.put("generation", gen); value.put("plan_id", planId); value.put("date", date);
          value.put("ordinal", nextOrdinal("review_days", gen)); value.put("json", day.toString()); db.insertOrThrow("review_days", null, value);
          changed = true;
        }
        days.put(day);
      }
      db.delete("queue_pool", null, null);
      JSONObject result = new JSONObject(); result.put("days", days);
      if (!changed) { db.setTransactionSuccessful(); return CommitResult.idempotent(gen, revision, command.getEventId(), result, "Daily queue already prepared"); }
      meta("revision", revision + 1); db.setTransactionSuccessful();
      return CommitResult.success(gen, revision + 1, command.getEventId(), result, Collections.emptyList(), "Daily queues prepared");
    } catch (Exception error) {
      return CommitResult.failure(error instanceof IllegalArgumentException ? CommitResult.Status.VALIDATION_FAILED : CommitResult.Status.IO_ERROR, gen, revision, command.getEventId(), error.getMessage(), error);
    } finally { db.endTransaction(); }
  }
  private void putSession(long generation, String kind, JSONObject session) {
    String gen = Long.toString(generation), id = session.optString("id");
    long ordinal;
    try (Cursor c = db.rawQuery("SELECT COALESCE(MAX(ordinal),-1)+1 FROM entities WHERE generation=? AND kind=?", new String[]{gen, kind})) { c.moveToFirst(); ordinal = c.getLong(0); }
    db.delete("entities", "generation=? AND kind=? AND id=?", new String[]{gen, kind, id});
    ContentValues v = new ContentValues(); v.put("generation", generation); v.put("kind", kind); v.put("id", id); v.put("ordinal", ordinal); v.put("json", session.toString());
    db.insertOrThrow("entities", null, v); LegacyLibraryMigration.indexSession(db, generation, kind, session);
  }
  private void deleteSession(long generation, String kind, String id) {
    String[] args = {Long.toString(generation), kind, id};
    db.delete("entities", "generation=? AND kind=? AND id=?", args);
    db.delete("session_index", "generation=? AND kind=? AND id=?", args);
  }
  private CommitResult touchSession(LibraryCommand command) {
    long gen = getGeneration(), revision = getRevision(); String g = Long.toString(gen);
    JSONObject payload = command.getPayload();
    if (payload.has("base_revision") && payload.optLong("base_revision", -1) != revision)
      return CommitResult.failure(CommitResult.Status.GENERATION_CONFLICT, gen, revision, command.getEventId(), "会话快照版本已过期，请刷新后重试", null);
    JSONObject input = payload.optJSONObject("session");
    if (input == null) return CommitResult.failure(CommitResult.Status.VALIDATION_FAILED, gen, revision, command.getEventId(), "会话不能为空", null);
    db.beginTransaction();
    try {
      JSONObject session = Library.copy(input); Library.redact(session);
      String id = session.optString("id"); require(!id.isEmpty(), "会话ID不能为空");
      Library.normalizeRetention(session); session.put("updated_at", Library.now());
      JSONObject metadata = session.getJSONObject("metadata");
      metadata.put("updated_at", session.get("updated_at")); metadata.put("last_used_at", session.get("updated_at"));
      JSONObject sample = new JSONObject(); sample.put("vocabularies", new JSONArray()); sample.put("notebooks", new JSONArray());
      sample.put("conversations", new JSONArray().put(session)); Library.validate(sample);
      putSession(gen, "conversations", session);
      if (payload.optBoolean("update_recent", false)) putSession(gen, "recent_queries", session);
      for (String kind : new String[]{"conversations", "recent_queries"}) {
        ContentValues v = new ContentValues(); v.put("generation", gen); v.put("kind", kind);
        db.insertWithOnConflict("session_order", null, v, SQLiteDatabase.CONFLICT_IGNORE);
      }
      java.util.ArrayList<String> oldRecent = new java.util.ArrayList<>();
      try (Cursor c = db.rawQuery("SELECT id FROM session_index WHERE generation=? AND kind='recent_queries' ORDER BY sort_time DESC,sort_id DESC LIMIT -1 OFFSET 10", new String[]{g})) {
        while (c.moveToNext()) oldRecent.add(c.getString(0));
      }
      for (String old : oldRecent) deleteSession(gen, "recent_queries", old);
      java.util.ArrayList<String> removed = new java.util.ArrayList<>();
      try (Cursor c = db.rawQuery("SELECT id FROM session_index WHERE generation=? AND kind='conversations' AND retention='temporary' ORDER BY sort_time DESC,sort_id DESC LIMIT -1 OFFSET 5", new String[]{g})) {
        while (c.moveToNext()) removed.add(c.getString(0));
      }
      try (Cursor c = db.rawQuery("SELECT s.id FROM session_index s WHERE s.generation=? AND s.kind='conversations' AND s.retention='query' AND NOT EXISTS(SELECT 1 FROM session_index r WHERE r.generation=s.generation AND r.kind='recent_queries' AND r.id=s.id)", new String[]{g})) {
        while (c.moveToNext()) removed.add(c.getString(0));
      }
      for (String old : removed) deleteSession(gen, "conversations", old);
      JSONArray recent = new JSONArray(), removedIds = new JSONArray();
      try (Cursor c = db.rawQuery("SELECT e.json FROM entities e JOIN session_index s ON s.generation=e.generation AND s.kind=e.kind AND s.id=e.id WHERE e.generation=? AND e.kind='recent_queries' ORDER BY s.sort_time DESC,s.sort_id DESC", new String[]{g})) {
        while (c.moveToNext()) recent.put(new JSONObject(c.getString(0)));
      }
      for (String old : removed) removedIds.put(old);
      JSONObject result = new JSONObject(); result.put("session", session); result.put("recent_queries", recent); result.put("removed_conversation_ids", removedIds);
      meta("revision", revision + 1); db.setTransactionSuccessful();
      return CommitResult.success(gen, revision + 1, command.getEventId(), result, Collections.singletonList(id), "Session saved");
    } catch (Exception error) {
      return CommitResult.failure(error instanceof IllegalArgumentException ? CommitResult.Status.VALIDATION_FAILED : CommitResult.Status.IO_ERROR, gen, revision, command.getEventId(), error.getMessage(), error);
    } finally { db.endTransaction(); }
  }
  private boolean contains(String noteId, String vocab, Set<String> seen, String gen) throws Exception {
    if (!seen.add(noteId)) return false;
    JSONObject note = row("entities", "generation=? AND kind='notebooks' AND id=?", gen, noteId);
    if (note == null) return false;
    JSONArray ids = note.optJSONArray("vocabulary_ids"), children = note.optJSONArray("child_notebook_ids");
    for (int i = 0; ids != null && i < ids.length(); i++) if (vocab.equals(ids.optString(i))) return true;
    for (int i = 0; children != null && i < children.length(); i++) if (contains(children.optString(i), vocab, seen, gen)) return true;
    return false;
  }
  private long nextOrdinal(String table, long generation) {
    try (Cursor c = db.rawQuery("SELECT COALESCE(MAX(ordinal),-1)+1 FROM " + table + " WHERE generation=?", new String[]{Long.toString(generation)})) {
      c.moveToFirst(); return c.getLong(0);
    }
  }
  private static void require(boolean test, String message) { if (!test) throw new IllegalArgumentException(message); }
  private CommitResult record(LibraryCommand command) {
    long gen = getGeneration(), revision = getRevision(); String g = Long.toString(gen);
    JSONObject p = command.getPayload(); String planId = p.optString("plan_id"), vocab = p.optString("vocab_id");
    String grade = p.optString("grade"), eventId = command.getEventId();
    long duration = p.optLong("duration_ms"), now = p.optLong("occurred_at", command.getTimestamp());
    db.beginTransaction();
    try {
      require(eventId != null && !eventId.trim().isEmpty() && eventId.length() <= 128, "答题事件ID无效");
      require(grade.equals("再次") || grade.equals("困难") || grade.equals("简单"), "评分无效");
      require(duration >= 0 && duration <= 86400000L, "答题时长无效");
      JSONObject existing = row("review_events", "generation=? AND id=?", g, eventId);
      if (existing != null) {
        require(planId.equals(existing.optString("plan_id")) && vocab.equals(existing.optString("vocab_id")) && grade.equals(existing.optString("grade")) && duration == existing.optLong("duration_ms") && Instant.ofEpochMilli(now).toString().equals(existing.optString("occurred_at")), "事件ID已用于其他答题");
        JSONObject result = new JSONObject(); result.put("event", existing);
        result.put("state", row("review_states", "generation=? AND plan_id=? AND vocab_id=?", g, planId, vocab));
        result.put("day", row("review_days", "generation=? AND plan_id=? AND date=?", g, planId, existing.optString("date")));
        db.setTransactionSuccessful();
        return CommitResult.idempotent(gen, revision, eventId, result, "Already recorded");
      }
      JSONObject plan = row("review_plans", "generation=? AND id=?", g, planId);
      require(plan != null && !plan.optBoolean("archived"), "复习计划不存在或已归档");
      require(row("entities", "generation=? AND kind='vocabularies' AND id=?", g, vocab) != null && contains(plan.optString("notebook_id"), vocab, new HashSet<>(), g), "词汇已移出计划笔记本或已删除");
      String date = Instant.ofEpochMilli(now).atZone(ZoneId.of(plan.optString("zone_id"))).toLocalDate().toString();
      JSONObject day = row("review_days", "generation=? AND plan_id=? AND date=?", g, planId, date);
      require(day != null, "请先获取当日复习任务");
      JSONArray tasks = day.optJSONArray("tasks"); JSONObject task = null; int taskIndex = -1;
      for (int i = 0; tasks != null && i < tasks.length(); i++) if (vocab.equals(tasks.getJSONObject(i).optString("vocab_id"))) { task = tasks.getJSONObject(i); taskIndex = i; break; }
      require(task != null && !task.optBoolean("completed"), "该词汇不在当日任务中或已完成");
      JSONObject state = row("review_states", "generation=? AND plan_id=? AND vocab_id=?", g, planId, vocab);
      if (state == null) state = ReviewPlans.object("plan_id", planId, "vocab_id", vocab, "review_count", 0, "review_mastery_level", 0, "review_grade", "", "review_due", 0L, "review_efactor", 2.5, "review_interval_days", 0, "learned_at", JSONObject.NULL, "reviewed_at", JSONObject.NULL);
      ReviewLogic.ScheduleResult schedule = ReviewLogic.calculateSchedule(state, grade, now);
      state.put("review_count", schedule.nextCount); state.put("review_mastery_level", schedule.nextLevel);
      state.put("review_grade", schedule.nextGrade); state.put("review_due", schedule.nextDue);
      state.put("review_efactor", schedule.nextEf); state.put("review_interval_days", schedule.nextIntervalDays);
      String occurred = Instant.ofEpochMilli(now).toString(); state.put("reviewed_at", occurred);
      boolean completed = !grade.equals("再次");
      if (completed && state.isNull("learned_at")) state.put("learned_at", occurred);
      JSONObject event = ReviewPlans.object("id", eventId, "plan_id", planId, "vocab_id", vocab, "date", date, "occurred_at", occurred, "grade", grade, "kind", task.optString("kind"), "is_retry", task.optInt("attempts") > 0, "completed", completed, "duration_ms", duration);
      task.put("attempts", task.optInt("attempts") + 1); task.put("completed", completed);
      if (!completed) { tasks.remove(taskIndex); tasks.put(task); }
      long ordinal = nextOrdinal("review_states", gen);
      db.delete("review_states", "generation=? AND plan_id=? AND vocab_id=?", new String[]{g, planId, vocab});
      ContentValues sv = new ContentValues(); sv.put("generation", gen); sv.put("plan_id", planId); sv.put("vocab_id", vocab); sv.put("ordinal", ordinal); sv.put("json", state.toString()); db.insertOrThrow("review_states", null, sv);
      ContentValues dv = new ContentValues(); dv.put("json", day.toString()); require(db.update("review_days", dv, "generation=? AND plan_id=? AND date=?", new String[]{g, planId, date}) == 1, "每日任务不存在");
      ContentValues ev = new ContentValues(); ev.put("generation", gen); ev.put("id", eventId); ev.put("ordinal", nextOrdinal("review_events", gen)); ev.put("json", event.toString()); db.insertOrThrow("review_events", null, ev);
      meta("revision", revision + 1);
      JSONObject result = new JSONObject(); result.put("event", event); result.put("state", state); result.put("day", day);
      db.setTransactionSuccessful();
      return CommitResult.success(gen, revision + 1, eventId, result, Collections.singletonList(vocab), "Recorded");
    } catch (Exception ex) {
      return CommitResult.failure(ex instanceof IllegalArgumentException ? CommitResult.Status.VALIDATION_FAILED : CommitResult.Status.IO_ERROR, gen, revision, eventId, ex.getMessage(), ex);
    } finally { db.endTransaction(); }
  }
  @Override public synchronized CommitResult applyTransaction(List<LibraryCommand> commands) throws Exception {
    if (commands.size() == 1) return execute(commands.get(0));
    for (LibraryCommand command : commands) {
      if (command.getExpectedGeneration() > 0 && command.getExpectedGeneration() != getGeneration())
        return CommitResult.conflict(command.getExpectedGeneration(), getGeneration(), command.getEventId(), "transaction");
      if (command.getType() == LibraryCommand.Type.IMPORT_LIBRARY || command.getType() == LibraryCommand.Type.ROLLBACK_LIBRARY || command.getType() == LibraryCommand.Type.PRESERVE_CORRUPT_DATA)
        return CommitResult.failure(CommitResult.Status.VALIDATION_FAILED, getGeneration(), getRevision(), command.getEventId(), "Restore and diagnostic commands cannot be batched", null);
    }
    return slowTransaction(commands);
  }
  /** Transitional compatibility for operations which do not yet expose affected rows. */
  private CommitResult slowTransaction(List<LibraryCommand> commands) throws Exception {
    long gen = getGeneration(), rev = getRevision();
    if (commands.isEmpty()) return CommitResult.success(gen, rev, "", null, Collections.emptyList(), "Empty transaction");
    for (LibraryCommand command : commands) {
      JSONObject payload = command.getPayload();
      if ((payload.has("snapshot") || command.getType() == LibraryCommand.Type.SAVE_SESSION) && payload.has("base_revision") && payload.optLong("base_revision", -1) != rev)
        return CommitResult.failure(CommitResult.Status.GENERATION_CONFLICT, gen, rev, command.getEventId(), "快照版本已过期，请刷新后重试", null);
    }
    Library current = new Library(LegacyLibraryMigration.read(db, gen));
    Repository memory = new Repository(new File(".")) {
      @Override public void save(Library lib) { Library.validate(lib.snapshot()); }
    };
    JsonStoreBackend domain = new JsonStoreBackend(memory, current); domain.initialize();
    java.util.ArrayList<LibraryCommand> copies = new java.util.ArrayList<>();
    for (LibraryCommand c : commands) {
      JSONObject payload = c.getPayload(); payload.remove("base_revision");
      copies.add(new LibraryCommand(c.getType(), c.getEntityId(), payload, 0, c.getEventId(), c.getTimestamp(), c.getCustomMutator()));
    }
    CommitResult result = copies.size() == 1 ? domain.execute(copies.get(0)) : domain.applyTransaction(copies);
    if (!result.isSuccess()) return CommitResult.failure(result.getStatus(), gen, rev, result.getEventId(), result.getMessage(), result.getCause());
    if (result.getStatus() == CommitResult.Status.IDEMPOTENT_IGNORED)
      return CommitResult.idempotent(gen, rev, result.getEventId(), result.getUpdatedEntity(), result.getMessage(), current);
    Library snapshot = result.getSnapshot();
    if (snapshot == null) return CommitResult.failure(CommitResult.Status.IO_ERROR, gen, rev, result.getEventId(), "Missing domain snapshot", null);
    JSONObject sanitized = snapshot.snapshot(); Library.redact(sanitized); snapshot = new Library(sanitized);
    db.beginTransaction();
    try {
      for (String table : new String[]{"document_meta", "entities", "review_plans", "review_states", "review_days", "review_events", "session_index", "session_order"})
        db.delete(table, "generation=?", new String[]{Long.toString(gen)});
      LegacyLibraryMigration.write(db, gen, snapshot.snapshot());
      meta("revision", rev + 1); db.setTransactionSuccessful();
    } finally { db.endTransaction(); }
    return CommitResult.success(gen, rev + 1, result.getEventId(), result.getUpdatedEntity(), result.getAffectedEntityIds(), result.getMessage(), snapshot);
  }
  @Override public synchronized void close() { helper.close(); db = null; }
}
