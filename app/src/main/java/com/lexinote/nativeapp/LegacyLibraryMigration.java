package com.lexinote.nativeapp;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Converts a validated document to ordered, independently addressable rows. Caller owns transaction. */
public final class LegacyLibraryMigration {
  private static final String[] ENTITIES = {"vocabularies", "notebooks", "recent_queries", "conversations"};
  private static final String[] REVIEW = {"plans", "states", "days", "events"};
  private static final Set<String> ENTITY_KEYS = new HashSet<>(Arrays.asList(ENTITIES));
  private static final Set<String> REVIEW_KEYS = new HashSet<>(Arrays.asList(REVIEW));
  private LegacyLibraryMigration() {}

  public static void write(SQLiteDatabase db, long generation, JSONObject input) throws Exception {
    Library.validate(input);
    JSONObject doc = new Library(input).snapshot();
    Iterator<String> keys = doc.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if (ENTITY_KEYS.contains(key) || key.equals("review")) continue;
      putMeta(db, generation, "document/" + key, doc.opt(key));
    }
    JSONObject review = doc.optJSONObject("review");
    Iterator<String> reviewKeys = review.keys();
    while (reviewKeys.hasNext()) {
      String key = reviewKeys.next();
      if (!REVIEW_KEYS.contains(key)) putMeta(db, generation, "review/" + key, review.opt(key));
    }
    for (String kind : ENTITIES) {
      JSONArray array = doc.optJSONArray(kind);
      for (int i = 0; i < array.length(); i++) {
        JSONObject row = array.getJSONObject(i);
        ContentValues v = base(generation, i, row);
        v.put("kind", kind); v.put("id", row.getString("id"));
        db.insertOrThrow("entities", null, v);
        if (kind.equals("conversations") || kind.equals("recent_queries")) indexSession(db, generation, kind, row);
      }
    }
    for (String kind : REVIEW) {
      JSONArray array = review.getJSONArray(kind);
      for (int i = 0; i < array.length(); i++) {
        JSONObject row = array.getJSONObject(i);
        ContentValues v = base(generation, i, row);
        if (kind.equals("plans") || kind.equals("events")) v.put("id", row.getString("id"));
        else {
          v.put("plan_id", row.getString("plan_id"));
          String key = kind.equals("states") ? "vocab_id" : "date";
          v.put(key, row.getString(key));
        }
        db.insertOrThrow("review_" + kind, null, v);
      }
    }
  }
  static void indexSession(SQLiteDatabase db, long generation, String kind, JSONObject row) {
    String id = row.optString("id"); byte[] sortId = new byte[id.length() * 2];
    for (int i = 0; i < id.length(); i++) { char c = id.charAt(i); sortId[i * 2] = (byte)(c >>> 8); sortId[i * 2 + 1] = (byte)c; }
    ContentValues value = new ContentValues(); value.put("generation", generation); value.put("kind", kind); value.put("id", id);
    value.put("sort_time", Library.sessionTime(row, kind.equals("recent_queries"))); value.put("sort_id", sortId); value.put("retention", Library.retention(row));
    db.insertWithOnConflict("session_index", null, value, SQLiteDatabase.CONFLICT_REPLACE);
  }

  private static ContentValues base(long generation, int ordinal, JSONObject row) {
    ContentValues v = new ContentValues(); v.put("generation", generation);
    v.put("ordinal", ordinal); v.put("json", row.toString()); return v;
  }

  private static void putMeta(SQLiteDatabase db, long generation, String key, Object value) {
    ContentValues v = new ContentValues(); v.put("generation", generation); v.put("key", key);
    v.put("json", new JSONArray().put(value).toString()); db.insertOrThrow("document_meta", null, v);
  }

  public static JSONObject read(SQLiteDatabase db, long generation) throws Exception {
    JSONObject doc = new JSONObject(), review = new JSONObject();
    try (Cursor c = db.rawQuery("SELECT key,json FROM document_meta WHERE generation=?", new String[]{Long.toString(generation)})) {
      while (c.moveToNext()) {
        String key = c.getString(0); Object value = new JSONArray(c.getString(1)).get(0);
        if (key.startsWith("document/")) doc.put(key.substring(9), value);
        else if (key.startsWith("review/")) review.put(key.substring(7), value);
      }
    }
    for (String kind : ENTITIES) doc.put(kind, rows(db, "entities", generation, kind));
    for (String kind : REVIEW) review.put(kind, rows(db, "review_" + kind, generation, null));
    doc.put("review", review);
    return doc;
  }

  private static JSONArray rows(SQLiteDatabase db, String table, long generation, String kind) throws Exception {
    JSONArray result = new JSONArray();
    if (kind != null && (kind.equals("conversations") || kind.equals("recent_queries"))) {
      try (Cursor ordered = db.rawQuery("SELECT 1 FROM session_order WHERE generation=? AND kind=?", new String[]{Long.toString(generation), kind})) {
        if (ordered.moveToFirst()) {
          try (Cursor c = db.rawQuery("SELECT e.json FROM entities e JOIN session_index s ON s.generation=e.generation AND s.kind=e.kind AND s.id=e.id WHERE e.generation=? AND e.kind=? ORDER BY s.sort_time DESC,s.sort_id DESC", new String[]{Long.toString(generation), kind})) {
            while (c.moveToNext()) result.put(new JSONObject(c.getString(0)));
          }
          return result;
        }
      }
    }
    String where = kind == null ? "generation=?" : "generation=? AND kind=?";
    String[] args = kind == null ? new String[]{Long.toString(generation)} : new String[]{Long.toString(generation), kind};
    try (Cursor c = db.query(table, new String[]{"json"}, where, args, null, null, "ordinal")) {
      while (c.moveToNext()) result.put(new JSONObject(c.getString(0)));
    }
    return result;
  }
}
