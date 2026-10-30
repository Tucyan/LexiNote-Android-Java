package com.lexinote.nativeapp;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reject malformed review data before it can replace the user's library or enter a backup. */
final class ReviewPlanSchema {
  private ReviewPlanSchema() {}

  static void validatePlan(JSONObject plan) {
    text(plan, "id"); text(plan, "notebook_id");
    String name = text(plan, "name");
    check(name.length() <= 120, "计划名称过长");
    integer(plan, "daily_new_limit", 0, 10000);
    integer(plan, "daily_review_limit", 0, 10000);
    check(plan.optInt("daily_new_limit") + plan.optInt("daily_review_limit") > 0, "每日任务数量不能全为零");
    JSONArray days = array(plan, "weekdays");
    check(days.length() > 0 && days.length() <= 7, "请选择有效的星期");
    Set<Integer> unique = new HashSet<>();
    for (int i = 0; i < days.length(); i++) {
      Object day = days.opt(i);
      check(day instanceof Integer && (Integer) day >= 1 && (Integer) day <= 7
          && unique.add((Integer) day), "星期必须为不重复的1至7");
    }
    try { ZoneId.of(text(plan, "zone_id")); }
    catch (RuntimeException e) { throw new IllegalArgumentException("计划时区无效", e); }
    bool(plan, "archived"); bool(plan, "legacy_imported");
    time(plan, "created_at", false); time(plan, "updated_at", false);
  }

  static void validate(JSONObject document) {
    if (!document.has("review")) return; // Legacy JSON/ZIP: initialized without automatically creating plans.
    JSONObject review = document.optJSONObject("review");
    check(review != null, "复习数据格式无效");
    integer(review, "schema_version", 1, 1);
    JSONArray plans = array(review, "plans"), states = array(review, "states"),
        days = array(review, "days"), events = array(review, "events");
    Map<String, JSONObject> planMap = new HashMap<>();
    Set<String> notebookIds = new HashSet<>();
    JSONArray notebooks = document.optJSONArray("notebooks");
    for (int i = 0; notebooks != null && i < notebooks.length(); i++)
      if (notebooks.optJSONObject(i) != null) notebookIds.add(notebooks.optJSONObject(i).optString("id"));
    for (int i = 0; i < plans.length(); i++) {
      JSONObject p = element(plans, i);
      validatePlan(p);
      check(planMap.put(p.optString("id"), p) == null, "复习计划ID重复");
      check(p.optBoolean("archived") || notebookIds.contains(p.optString("notebook_id")), "复习计划绑定的笔记本不存在");
    }
    Map<List<String>, JSONObject> stateMap = new HashMap<>();
    for (int i = 0; i < states.length(); i++) {
      JSONObject s = element(states, i);
      reference(s, planMap);
      text(s, "vocab_id");
      check(stateMap.put(key(s, "plan_id", "vocab_id"), s) == null, "复习进度重复");
      integer(s, "review_count", 0, Integer.MAX_VALUE);
      integer(s, "review_mastery_level", 0, 3);
      integer(s, "review_due", 0, Long.MAX_VALUE);
      integer(s, "review_interval_days", 0, Integer.MAX_VALUE);
      Object ef = s.opt("review_efactor");
      check(ef instanceof Number && Double.isFinite(((Number) ef).doubleValue())
          && ((Number) ef).doubleValue() >= 1.3 && ((Number) ef).doubleValue() <= 3, "复习难度因子无效");
      check(s.opt("review_grade") instanceof String && Arrays.asList("", "再次", "困难", "简单")
          .contains(s.optString("review_grade")), "复习评分无效");
      time(s, "learned_at", true); time(s, "reviewed_at", true);
      if (s.has("imported_at")) time(s, "imported_at", false);
    }
    Map<List<String>, JSONObject> dayMap = new HashMap<>();
    Map<List<String>, JSONObject> taskMap = new HashMap<>();
    for (int i = 0; i < days.length(); i++) {
      JSONObject d = element(days, i);
      JSONObject p = reference(d, planMap);
      date(d, "date"); time(d, "created_at", false);
      check(p.optString("zone_id").equals(text(d, "zone_id")), "每日任务时区与计划不一致");
      check(d.optString("date").equals(Instant.parse(d.optString("created_at"))
          .atZone(ZoneId.of(d.optString("zone_id"))).toLocalDate().toString()), "每日任务日期与时区不一致");
      bool(d, "scheduled");
      integer(d, "daily_new_limit", 0, 10000); integer(d, "daily_review_limit", 0, 10000);
      integer(d, "new_target", 0, d.optInt("daily_new_limit"));
      integer(d, "review_target", 0, d.optInt("daily_review_limit"));
      check(dayMap.put(key(d, "plan_id", "date"), d) == null, "每日任务重复");
      JSONArray tasks = array(d, "tasks");
      int fresh = 0, repeat = 0;
      for (int j = 0; j < tasks.length(); j++) {
        JSONObject t = element(tasks, j);
        text(t, "vocab_id"); kind(t); bool(t, "completed"); integer(t, "attempts", 0, Integer.MAX_VALUE);
        check(taskMap.put(Arrays.asList(d.optString("plan_id"), d.optString("date"), t.optString("vocab_id")), t) == null,
            "每日词汇任务重复");
        if (t.optString("kind").equals("new")) fresh++; else repeat++;
      }
      check(fresh == d.optInt("new_target") && repeat == d.optInt("review_target"), "每日任务目标与词汇数不符");
      check(d.optBoolean("scheduled") || tasks.length() == 0, "非计划日包含任务");
    }
    Set<String> eventIds = new HashSet<>();
    Map<List<String>, Integer> attempts = new HashMap<>(), resolved = new HashMap<>();
    Map<List<String>, Integer> stateAttempts = new HashMap<>();
    for (int i = 0; i < events.length(); i++) {
      JSONObject e = element(events, i);
      JSONObject p = reference(e, planMap);
      check(eventIds.add(text(e, "id")) && e.optString("id").length() <= 128, "答题事件ID重复或过长");
      text(e, "vocab_id"); date(e, "date"); time(e, "occurred_at", false);
      kind(e); bool(e, "is_retry"); bool(e, "completed"); integer(e, "duration_ms", 0, 86400000L);
      String grade = text(e, "grade");
      check(Arrays.asList("再次", "困难", "简单").contains(grade), "答题评分无效");
      check(e.optBoolean("completed") == !grade.equals("再次"), "答题完成标志无效");
      check(e.optString("date").equals(Instant.parse(e.optString("occurred_at"))
          .atZone(ZoneId.of(p.optString("zone_id"))).toLocalDate().toString()), "答题日期与时区不一致");
      List<String> taskKey = Arrays.asList(e.optString("plan_id"), e.optString("date"), e.optString("vocab_id"));
      JSONObject task = taskMap.get(taskKey);
      check(task != null && task.optString("kind").equals(e.optString("kind")), "答题事件缺少对应任务");
      List<String> stateKey = key(e, "plan_id", "vocab_id");
      JSONObject state = stateMap.get(stateKey);
      check(state != null, "答题事件缺少词汇进度");
      check(!e.optBoolean("completed") || !state.isNull("learned_at"), "完成事件与学习状态不一致");
      stateAttempts.put(stateKey, stateAttempts.getOrDefault(stateKey, 0) + 1);
      int count = attempts.getOrDefault(taskKey, 0);
      check(e.optBoolean("is_retry") == (count > 0) && resolved.getOrDefault(taskKey, 0) == 0, "答题事件顺序无效");
      attempts.put(taskKey, count + 1);
      if (e.optBoolean("completed")) resolved.put(taskKey, 1);
    }
    for (Map.Entry<List<String>, JSONObject> entry : taskMap.entrySet()) {
      JSONObject task = entry.getValue();
      check(task.optInt("attempts") == attempts.getOrDefault(entry.getKey(), 0)
          && task.optBoolean("completed") == (resolved.getOrDefault(entry.getKey(), 0) == 1), "每日任务与答题事件不一致");
    }
    for (Map.Entry<List<String>, Integer> entry : stateAttempts.entrySet())
      check(stateMap.get(entry.getKey()).optInt("review_count") >= entry.getValue(), "复习次数少于答题事件数");
  }

  private static List<String> key(JSONObject o, String first, String second) {
    return Arrays.asList(o.optString(first), o.optString(second));
  }
  private static void kind(JSONObject o) {
    check(Arrays.asList("new", "review").contains(text(o, "kind")), "学习任务类型无效");
  }
  private static JSONObject reference(JSONObject o, Map<String, JSONObject> plans) {
    JSONObject result = plans.get(text(o, "plan_id"));
    check(result != null, "复习计划引用不存在"); return result;
  }
  private static JSONObject element(JSONArray array, int index) {
    JSONObject result = array.optJSONObject(index);
    check(result != null, "复习集合元素格式无效"); return result;
  }
  private static JSONArray array(JSONObject o, String key) {
    JSONArray result = o.optJSONArray(key);
    check(result != null, "复习集合格式无效: " + key); return result;
  }
  private static String text(JSONObject o, String key) {
    Object result = o.opt(key);
    check(result instanceof String && !((String) result).trim().isEmpty(), "复习文本字段无效: " + key);
    return (String) result;
  }
  private static void bool(JSONObject o, String key) {
    check(o.opt(key) instanceof Boolean, "复习布尔字段无效: " + key);
  }
  private static void integer(JSONObject o, String key, long min, long max) {
    Object value = o.opt(key);
    check((value instanceof Integer || value instanceof Long)
        && ((Number) value).longValue() >= min && ((Number) value).longValue() <= max, "复习整数无效: " + key);
  }
  private static void time(JSONObject o, String key, boolean nullable) {
    if (nullable && o.has(key) && o.isNull(key)) return;
    check(ReviewPlans.validTimestamp(text(o, key)), "复习时间字段无效: " + key);
  }
  private static void date(JSONObject o, String key) {
    String value = text(o, key);
    try { check(LocalDate.parse(value).toString().equals(value), "日期须为YYYY-MM-DD"); }
    catch (RuntimeException e) { throw new IllegalArgumentException("复习日期字段无效: " + key, e); }
  }
  private static void check(boolean condition, String message) { ReviewPlans.check(condition, message); }
}
