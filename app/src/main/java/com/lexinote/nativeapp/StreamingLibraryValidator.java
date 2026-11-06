package com.lexinote.nativeapp;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Validates staged data using bounded entity reads and disk-backed task counters. */
final class StreamingLibraryValidator {
  private final SQLiteDatabase db;
  private final String generation;
  StreamingLibraryValidator(SQLiteDatabase db, long generation) { this.db = db; this.generation = Long.toString(generation); }
  private static void check(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
  private static String text(JSONObject row, String key) {
    Object value = row.opt(key); check(value instanceof String && !((String)value).trim().isEmpty(), "文本字段无效: " + key); return (String)value;
  }
  private static long integer(JSONObject row, String key, long min, long max) {
    Object value = row.opt(key); check(value instanceof Integer || value instanceof Long, "整数字段无效: " + key);
    long n = ((Number)value).longValue(); check(n >= min && n <= max, "整数范围无效: " + key); return n;
  }
  private static void bool(JSONObject row, String key) { check(row.opt(key) instanceof Boolean, "布尔字段无效: " + key); }
  private static void time(JSONObject row, String key, boolean nullable) {
    if (nullable && row.has(key) && row.isNull(key)) return;
    check(ReviewPlans.validTimestamp(text(row, key)), "时间无效: " + key);
  }
  private static void date(JSONObject row, String key) {
    String d = text(row, key); try { check(LocalDate.parse(d).toString().equals(d), "日期无效"); }
    catch (RuntimeException error) { throw new IllegalArgumentException("日期无效", error); }
  }
  private static String kind(JSONObject row) { String kind = text(row, "kind"); check(kind.equals("new") || kind.equals("review"), "任务类型无效"); return kind; }
  private JSONObject row(String table, String where, String... args) throws Exception {
    try (Cursor c = db.query(table, new String[]{"json"}, where, args, null, null, null)) { return c.moveToFirst() ? new JSONObject(c.getString(0)) : null; }
  }
  private JSONObject entity(String kind, String id) throws Exception { return row("entities", "generation=? AND kind=? AND id=?", generation, kind, id); }
  private JSONObject plan(String id) throws Exception {
    JSONObject p = row("review_plans", "generation=? AND id=?", generation, id); check(p != null, "复习计划引用不存在"); return p;
  }
  private Cursor all(String table) { return db.query(table, new String[]{"json"}, "generation=?", new String[]{generation}, null, null, "ordinal"); }
  private Object meta(String key) throws Exception {
    try (Cursor c = db.query("document_meta", new String[]{"json"}, "generation=? AND key=?", new String[]{generation, key}, null, null, null)) {
      return c.moveToFirst() ? new JSONArray(c.getString(0)).get(0) : null;
    }
  }
  void validate() throws Exception {
    Object version = meta("document/version");
    check(version == null || version instanceof String && Arrays.asList("1.0", "1.1", "2.0", "2.1", "2.2").contains(version), "备份版本无效");
    check(meta("document/settings") instanceof JSONObject, "设置结构无效");
    Object schema = meta("review/schema_version"); check(schema instanceof Integer && ((Integer)schema) == 1, "复习版本无效");
    validateEntities(); validatePlans(); validateStates(); validateDaysAndEvents();
  }
  private void validateEntities() throws Exception {
    for (String kind : new String[]{"vocabularies", "recent_queries", "conversations"}) {
      try (Cursor c = db.query("entities", new String[]{"json"}, "generation=? AND kind=?", new String[]{generation, kind}, null, null, "ordinal")) {
        while (c.moveToNext()) {
          JSONObject sample = new JSONObject(); sample.put("version", "1.1");
          sample.put("vocabularies", kind.equals("vocabularies") ? new JSONArray().put(new JSONObject(c.getString(0))) : new JSONArray());
          sample.put("notebooks", new JSONArray());
          if (!kind.equals("vocabularies")) sample.put(kind, new JSONArray().put(new JSONObject(c.getString(0))));
          Library.validate(sample);
        }
      }
    }
    try (Cursor c = db.query("entities", new String[]{"json"}, "generation=? AND kind='notebooks'", new String[]{generation}, null, null, "ordinal")) {
      while (c.moveToNext()) {
        JSONObject n = new JSONObject(c.getString(0)); String id = n.optString("id"); check(!id.isEmpty(), "笔记ID无效");
        JSONArray children = n.optJSONArray("child_notebook_ids"), words = n.optJSONArray("vocabulary_ids");
        check(children != null && words != null && Arrays.asList("notebook", "vocabulary").contains(n.optString("children_type")), "笔记子项类型无效");
        check(!(children.length() > 0 && words.length() > 0) && (children.length() == 0 || n.optString("children_type").equals("notebook")) && (words.length() == 0 || n.optString("children_type").equals("vocabulary")), "笔记子项类型不一致");
        Set<String> direct = new HashSet<>();
        for (int i = 0; i < children.length(); i++) {
          String childId = children.optString(i); JSONObject child = entity("notebooks", childId);
          check(child != null && direct.add(childId) && id.equals(child.optString("parent_id")), "笔记引用无效");
        }
        direct.clear();
        for (int i = 0; !Library.systemGlobal(n) && i < words.length(); i++) {
          String word = words.optString(i); check(direct.add(word) && entity("vocabularies", word) != null, "词汇引用无效");
        }
        Set<String> visited = new HashSet<>(); JSONObject current = n;
        while (current != null) {
          String currentId = current.optString("id"); check(visited.add(currentId), "笔记存在循环");
          String parentId = current.optString("parent_id", "");
          if (parentId.isEmpty() || parentId.equals("null")) break;
          JSONObject parent = entity("notebooks", parentId); check(parent != null, "父笔记不存在");
          JSONArray list = parent.optJSONArray("child_notebook_ids"); boolean found = false;
          for (int i = 0; list != null && i < list.length(); i++) found |= currentId.equals(list.optString(i));
          check(found, "父子笔记关系不一致"); current = parent;
        }
        if (id.equals("nb_default") || id.equals("nb_global_root")) {
          check(n.optBoolean(id.equals("nb_default") ? "is_default" : "is_global_root") && children.length() == 0, "系统笔记结构无效");
        }
      }
    }
  }
  private void validatePlans() throws Exception {
    try (Cursor c = all("review_plans")) {
      while (c.moveToNext()) { JSONObject p = new JSONObject(c.getString(0)); ReviewPlanSchema.validatePlan(p);
        check(p.optBoolean("archived") || entity("notebooks", p.optString("notebook_id")) != null, "复习计划绑定的笔记本不存在"); }
    }
  }
  private void validateStates() throws Exception {
    try (Cursor c = all("review_states")) {
      while (c.moveToNext()) {
        JSONObject s = new JSONObject(c.getString(0)); plan(text(s, "plan_id")); text(s, "vocab_id");
        integer(s, "review_count", 0, Integer.MAX_VALUE); integer(s, "review_mastery_level", 0, 3);
        integer(s, "review_due", 0, Long.MAX_VALUE); integer(s, "review_interval_days", 0, Integer.MAX_VALUE);
        Object ef = s.opt("review_efactor"); check(ef instanceof Number && Double.isFinite(((Number)ef).doubleValue()) && ((Number)ef).doubleValue() >= 1.3 && ((Number)ef).doubleValue() <= 3, "复习难度因子无效");
        check(s.opt("review_grade") instanceof String && Arrays.asList("", "再次", "困难", "简单").contains(s.optString("review_grade")), "复习评分无效");
        time(s, "learned_at", true); time(s, "reviewed_at", true); if (s.has("imported_at")) time(s, "imported_at", false);
      }
    }
  }
  private void validateDaysAndEvents() throws Exception {
    db.execSQL("DROP TABLE IF EXISTS temp.import_tasks");
    db.execSQL("DROP TABLE IF EXISTS temp.import_state_counts");
    db.execSQL("CREATE TEMP TABLE import_tasks (plan TEXT NOT NULL, day TEXT NOT NULL, vocab TEXT NOT NULL, kind TEXT NOT NULL, expected_attempts INTEGER NOT NULL, expected_completed INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, completed INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(plan,day,vocab))");
    db.execSQL("CREATE TEMP TABLE import_state_counts (plan TEXT NOT NULL, vocab TEXT NOT NULL, attempts INTEGER NOT NULL, PRIMARY KEY(plan,vocab))");
    try {
      try (Cursor c = all("review_days")) {
        while (c.moveToNext()) {
          JSONObject day = new JSONObject(c.getString(0)); String planId = text(day, "plan_id"), date = text(day, "date");
          JSONObject p = plan(planId); date(day, "date"); time(day, "created_at", false);
          String zone = text(day, "zone_id"); check(zone.equals(p.optString("zone_id")), "每日任务时区不一致");
          check(date.equals(Instant.parse(day.optString("created_at")).atZone(ZoneId.of(zone)).toLocalDate().toString()), "每日任务日期不一致");
          bool(day, "scheduled"); long newLimit = integer(day, "daily_new_limit", 0, 10000), reviewLimit = integer(day, "daily_review_limit", 0, 10000);
          long newTarget = integer(day, "new_target", 0, newLimit), reviewTarget = integer(day, "review_target", 0, reviewLimit);
          JSONArray tasks = day.optJSONArray("tasks"); check(tasks != null, "任务集合无效"); int fresh = 0, repeat = 0;
          for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i); check(task != null, "任务格式无效");
            String vocab = text(task, "vocab_id"), taskKind = kind(task); bool(task, "completed");
            long attempts = integer(task, "attempts", 0, Integer.MAX_VALUE);
            if (taskKind.equals("new")) fresh++; else repeat++;
            ContentValues v = new ContentValues(); v.put("plan", planId); v.put("day", date); v.put("vocab", vocab); v.put("kind", taskKind);
            v.put("expected_attempts", attempts); v.put("expected_completed", task.optBoolean("completed") ? 1 : 0);
            db.insertOrThrow("import_tasks", null, v);
          }
          check(fresh == newTarget && repeat == reviewTarget && (day.optBoolean("scheduled") || tasks.length() == 0), "每日任务目标不一致");
        }
      }
      try (Cursor c = all("review_events")) {
        while (c.moveToNext()) {
          JSONObject event = new JSONObject(c.getString(0)); String id = text(event, "id"), planId = text(event, "plan_id"), vocab = text(event, "vocab_id");
          check(id.length() <= 128, "答题事件ID过长"); JSONObject p = plan(planId); date(event, "date"); time(event, "occurred_at", false);
          String eventKind = kind(event); bool(event, "is_retry"); bool(event, "completed"); integer(event, "duration_ms", 0, 86400000L);
          String grade = text(event, "grade"); check(Arrays.asList("再次", "困难", "简单").contains(grade) && event.optBoolean("completed") == !grade.equals("再次"), "答题评分或完成标志无效");
          String day = event.optString("date");
          check(day.equals(Instant.parse(event.optString("occurred_at")).atZone(ZoneId.of(p.optString("zone_id"))).toLocalDate().toString()), "答题日期不一致");
          JSONObject state = row("review_states", "generation=? AND plan_id=? AND vocab_id=?", generation, planId, vocab);
          check(state != null && (!event.optBoolean("completed") || !state.isNull("learned_at")), "事件缺少有效词汇进度");
          try (Cursor task = db.rawQuery("SELECT kind,attempts,completed FROM import_tasks WHERE plan=? AND day=? AND vocab=?", new String[]{planId, day, vocab})) {
            check(task.moveToFirst() && eventKind.equals(task.getString(0)), "答题事件缺少对应任务");
            int attempts = task.getInt(1); check(event.optBoolean("is_retry") == (attempts > 0) && task.getInt(2) == 0, "答题事件顺序无效");
          }
          db.execSQL("UPDATE import_tasks SET attempts=attempts+1,completed=? WHERE plan=? AND day=? AND vocab=?", new Object[]{event.optBoolean("completed") ? 1 : 0, planId, day, vocab});
          db.execSQL("INSERT OR IGNORE INTO import_state_counts(plan,vocab,attempts) VALUES(?,?,0)", new Object[]{planId, vocab});
          db.execSQL("UPDATE import_state_counts SET attempts=attempts+1 WHERE plan=? AND vocab=?", new Object[]{planId, vocab});
        }
      }
      try (Cursor c = db.rawQuery("SELECT 1 FROM import_tasks WHERE attempts<>expected_attempts OR completed<>expected_completed LIMIT 1", null)) {
        check(!c.moveToFirst(), "每日任务与答题事件不一致");
      }
      try (Cursor c = db.rawQuery("SELECT plan,vocab,attempts FROM import_state_counts", null)) {
        while (c.moveToNext()) {
          JSONObject state = row("review_states", "generation=? AND plan_id=? AND vocab_id=?", generation, c.getString(0), c.getString(1));
          check(state != null && state.optLong("review_count") >= c.getLong(2), "复习次数少于答题事件数");
        }
      }
    } finally { db.execSQL("DROP TABLE IF EXISTS temp.import_tasks"); db.execSQL("DROP TABLE IF EXISTS temp.import_state_counts"); }
  }
}
