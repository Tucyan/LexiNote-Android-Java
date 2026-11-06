package com.lexinote.nativeapp;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.util.JsonReader;
import android.util.JsonToken;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Stages JSON one entity at a time. This does not validate or activate the dataset. */
final class StreamingLibraryImport {
  private static final Set<String> ENTITIES = new HashSet<>(Arrays.asList("vocabularies", "notebooks", "recent_queries", "conversations"));
  private static final Set<String> REVIEW = new HashSet<>(Arrays.asList("plans", "states", "days", "events"));
  private StreamingLibraryImport() {}

  static void stage(SQLiteDatabase db, long generation, File source) throws Exception {
    try (JsonReader reader = new JsonReader(new InputStreamReader(new FileInputStream(source), StandardCharsets.UTF_8))) {
      Set<String> seen = new HashSet<>(); reader.beginObject();
      while (reader.hasNext()) {
        String name = reader.nextName();
        if (!seen.add(name)) throw new IllegalArgumentException("重复的顶层字段: " + name);
        if (secret(name)) { reader.skipValue(); continue; }
        if (ENTITIES.contains(name)) rows(reader, db, generation, "entities", name);
        else if (name.equals("review")) review(reader, db, generation);
        else meta(db, generation, "document/" + name, value(reader, 0));
      }
      reader.endObject();
      if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("JSON 包含尾随内容");
      if (!seen.contains("vocabularies") || !seen.contains("notebooks")) throw new IllegalArgumentException("备份缺少词汇或笔记数组");
      // Absent optional collections from legacy documents are represented by empty row sets.
      if (!seen.contains("review")) {
        meta(db, generation, "review/schema_version", 1);
      }
      if (!seen.contains("settings")) meta(db, generation, "document/settings", new JSONObject());
    }
  }
  private static void review(JsonReader reader, SQLiteDatabase db, long generation) throws Exception {
    Set<String> seen = new HashSet<>(); reader.beginObject();
    while (reader.hasNext()) {
      String key = reader.nextName();
      if (!seen.add(key)) throw new IllegalArgumentException("重复的复习字段: " + key);
      if (secret(key)) { reader.skipValue(); continue; }
      if (REVIEW.contains(key)) rows(reader, db, generation, "review_" + key, null);
      else meta(db, generation, "review/" + key, value(reader, 0));
    }
    reader.endObject();
    if (!seen.contains("schema_version") || !seen.containsAll(REVIEW)) throw new IllegalArgumentException("复习集合不完整");
  }
  private static void rows(JsonReader reader, SQLiteDatabase db, long generation, String table, String kind) throws Exception {
    reader.beginArray(); int ordinal = 0;
    while (reader.hasNext()) {
      Object raw = value(reader, 0);
      if (!(raw instanceof JSONObject)) throw new IllegalArgumentException("集合元素必须为对象");
      JSONObject object = (JSONObject)raw; ContentValues row = new ContentValues();
      row.put("generation", generation); row.put("ordinal", ordinal++); row.put("json", object.toString());
      if (kind != null) {
        String entityId = object.optString("id");
        if (entityId.isEmpty()) throw new IllegalArgumentException("缺少实体标识: id");
        row.put("kind", kind); row.put("id", entityId);
      }
      else if (table.equals("review_plans") || table.equals("review_events")) row.put("id", id(object, "id"));
      else {
        row.put("plan_id", id(object, "plan_id")); String key = table.equals("review_states") ? "vocab_id" : "date";
        row.put(key, id(object, key));
      }
      db.insertOrThrow(table, null, row);
      if (kind != null && (kind.equals("conversations") || kind.equals("recent_queries"))) LegacyLibraryMigration.indexSession(db, generation, kind, object);
    }
    reader.endArray();
  }
  private static String id(JSONObject object, String key) {
    Object value = object.opt(key);
    if (!(value instanceof String) || ((String)value).isEmpty()) throw new IllegalArgumentException("缺少实体标识: " + key);
    return (String)value;
  }
  private static void meta(SQLiteDatabase db, long generation, String key, Object object) {
    ContentValues row = new ContentValues(); row.put("generation", generation); row.put("key", key);
    row.put("json", new JSONArray().put(object).toString()); db.insertOrThrow("document_meta", null, row);
  }
  private static Object value(JsonReader reader, int depth) throws Exception {
    if (depth > 128) throw new IllegalArgumentException("JSON 嵌套过深");
    switch (reader.peek()) {
      case BEGIN_OBJECT: {
        JSONObject result = new JSONObject(); Set<String> keys = new HashSet<>(); reader.beginObject();
        while (reader.hasNext()) { String key = reader.nextName(); if (!keys.add(key)) throw new IllegalArgumentException("重复字段: " + key); if (secret(key)) reader.skipValue(); else result.put(key, value(reader, depth + 1)); }
        reader.endObject(); return result;
      }
      case BEGIN_ARRAY: {
        JSONArray result = new JSONArray(); reader.beginArray();
        while (reader.hasNext()) result.put(value(reader, depth + 1));
        reader.endArray(); return result;
      }
      case STRING: return reader.nextString();
      case BOOLEAN: return reader.nextBoolean();
      case NULL: reader.nextNull(); return JSONObject.NULL;
      case NUMBER: {
        String number = reader.nextString();
        if (!number.contains(".") && !number.contains("e") && !number.contains("E")) {
          try { long n = Long.parseLong(number); if (n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE) return Integer.valueOf((int)n); return Long.valueOf(n); }
          catch (NumberFormatException ignored) { throw new IllegalArgumentException("整数超出范围"); }
        }
        double n = Double.parseDouble(number); if (!Double.isFinite(n)) throw new IllegalArgumentException("数字无效"); return n;
      }
      default: throw new IllegalArgumentException("无效 JSON 值");
    }
  }
  private static boolean secret(String key) {
    return Library.credentialField(key);
  }
}
