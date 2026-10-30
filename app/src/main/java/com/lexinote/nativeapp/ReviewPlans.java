package com.lexinote.nativeapp;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Plan-scoped review domain. No Android or view dependencies. Persist Library after commands. */
public final class ReviewPlans {
  private final Library library;
  private final JSONObject data;

  ReviewPlans(Library library, JSONObject document) {
    this.library = library;
    if (!document.has("review")) {
      JSONObject fresh = object("schema_version", 1);
      for (String key : new String[] {"plans", "states", "days", "events"})
        Library.put(fresh, key, new JSONArray());
      Library.put(document, "review", fresh);
    }
    data = document.optJSONObject("review");
  }

  static JSONObject object(Object... pairs) {
    JSONObject result = new JSONObject();
    for (int i = 0; i < pairs.length; i += 2)
      Library.put(result, (String) pairs[i], pairs[i + 1]);
    return result;
  }

  private JSONArray items(String key) { return data.optJSONArray(key); }

  private JSONObject plan(String id) {
    return require(find(items("plans"), "id", id), "复习计划不存在");
  }

  private JSONObject active(String id) {
    JSONObject p = plan(id);
    check(!p.optBoolean("archived"), "复习计划已归档");
    check(library.find("notebooks", p.optString("notebook_id")) != null, "绑定的笔记本不存在");
    return p;
  }

  public synchronized JSONObject get(String id) { return Library.copy(plan(id)); }

  public synchronized JSONArray list(boolean includeArchived) {
    JSONArray result = new JSONArray();
    for (int i = 0; i < items("plans").length(); i++) {
      JSONObject p = items("plans").optJSONObject(i);
      if (includeArchived || !p.optBoolean("archived")) result.put(Library.copy(p));
    }
    return result;
  }

  /** ISO weekdays 1=Monday..7=Sunday; limits 0..10000, at least one positive. */
  public synchronized String create(String name, String notebookId, int newLimit, int reviewLimit,
      int[] weekdays, String zoneId, boolean importLegacy, long now) {
    check(library.find("notebooks", notebookId) != null, "请选择已有笔记本");
    JSONArray days = new JSONArray();
    check(weekdays != null, "请选择计划频率");
    for (int weekday : weekdays) days.put(weekday);
    JSONObject p = object("id", Library.id(), "name", name, "notebook_id", notebookId,
        "daily_new_limit", newLimit, "daily_review_limit", reviewLimit, "weekdays", days,
        "zone_id", zoneId, "archived", false, "created_at", timestamp(now),
        "updated_at", timestamp(now), "legacy_imported", importLegacy);
    ReviewPlanSchema.validatePlan(p);
    if (importLegacy) {
      for (String vocabId : pool(p)) {
        JSONObject word = library.find("vocabularies", vocabId);
        if (word.optInt("review_count") <= 0) continue;
        JSONObject state = newState(p.optString("id"), vocabId);
        for (String key : new String[] {"review_count", "review_grade", "review_due",
            "review_mastery_level", "review_efactor", "review_interval_days", "reviewed_at"})
          if (word.has(key) && !word.isNull(key)) Library.put(state, key, word.opt(key));
        Library.put(state, "review_count", Math.max(0, state.optInt("review_count")));
        Library.put(state, "review_mastery_level", ReviewLogic.level(
            word.has("review_mastery_level") && !word.isNull("review_mastery_level")
                ? word.optInt("review_mastery_level") : null,
            state.optInt("review_count"), word.optString("review_grade")));
        double ef = state.optDouble("review_efactor", 2.5);
        Library.put(state, "review_efactor", Double.isFinite(ef) ? Math.max(1.3, Math.min(3, ef)) : 2.5);
        Library.put(state, "review_due", Math.max(0, state.optLong("review_due")));
        Library.put(state, "review_interval_days", Math.max(0, state.optInt("review_interval_days")));
        Library.put(state, "learned_at", timestamp(now));
        Library.put(state, "imported_at", timestamp(now));
        // Old timestamps/grades may be absent or malformed. Do not fabricate historical events.
        String grade = state.optString("review_grade");
        if (!Arrays.asList("再次", "困难", "简单").contains(grade)) Library.put(state, "review_grade", "");
        Library.put(state, "reviewed_at", validTimestamp(word.optString("reviewed_at"))
            ? word.optString("reviewed_at") : JSONObject.NULL);
        items("states").put(state);
      }
    }
    items("plans").put(p);
    return p.optString("id");
  }

  /** Only these configuration fields are mutable. Existing daily assignments stay unchanged. */
  public synchronized void update(String id, JSONObject patch, long now) {
    JSONObject current = active(id), replacement = Library.copy(current);
    check(patch != null, "计划更新不能为空");
    Iterator<String> keys = patch.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      check(Arrays.asList("name", "daily_new_limit", "daily_review_limit", "weekdays").contains(key),
          "不可修改字段: " + key);
      Library.put(replacement, key, patch.opt(key));
    }
    Library.put(replacement, "updated_at", timestamp(now));
    ReviewPlanSchema.validatePlan(replacement);
    // Copy only after every field has passed validation.
    for (String key : new String[] {"name", "daily_new_limit", "daily_review_limit", "weekdays", "updated_at"})
      Library.put(current, key, replacement.opt(key));
    // Detach the caller's JSONArray as well as its JSONObject.
    Library.put(current, "weekdays", Library.copy(replacement).optJSONArray("weekdays"));
  }

  public synchronized void archive(String id, long now) {
    JSONObject p = plan(id);
    Library.put(p, "archived", true);
    Library.put(p, "updated_at", timestamp(now));
  }

  private Set<String> pool(JSONObject p) {
    Set<String> result = new LinkedHashSet<>();
    collect(p.optString("notebook_id"), result, new HashSet<>());
    result.removeIf(id -> library.find("vocabularies", id) == null);
    return result;
  }

  private void collect(String noteId, Set<String> words, Set<String> visited) {
    if (!visited.add(noteId)) return;
    JSONObject note = library.find("notebooks", noteId);
    if (note == null) return;
    JSONArray ids = note.optJSONArray("vocabulary_ids"), children = note.optJSONArray("child_notebook_ids");
    for (int i = 0; ids != null && i < ids.length(); i++) words.add(ids.optString(i));
    for (int i = 0; children != null && i < children.length(); i++) collect(children.optString(i), words, visited);
  }

  synchronized void checkNotebookDeletion(String noteId) {
    Set<String> subtree = new HashSet<>();
    notebookTree(noteId, subtree);
    for (int i = 0; i < items("plans").length(); i++) {
      JSONObject p = items("plans").optJSONObject(i);
      check(p.optBoolean("archived") || !subtree.contains(p.optString("notebook_id")),
          "该笔记或子笔记绑定了复习计划，请先归档计划");
    }
  }

  private void notebookTree(String id, Set<String> result) {
    if (!result.add(id)) return;
    JSONObject n = library.find("notebooks", id);
    JSONArray children = n == null ? null : n.optJSONArray("child_notebook_ids");
    for (int i = 0; children != null && i < children.length(); i++) notebookTree(children.optString(i), result);
  }

  private JSONObject savedState(String planId, String vocabId) {
    return pair(items("states"), "plan_id", planId, "vocab_id", vocabId);
  }

  public synchronized JSONObject state(String planId, String vocabId) {
    plan(planId);
    JSONObject s = savedState(planId, vocabId);
    return s == null ? null : Library.copy(s);
  }

  private JSONObject newState(String planId, String vocabId) {
    return object("plan_id", planId, "vocab_id", vocabId, "review_count", 0,
        "review_mastery_level", 0, "review_grade", "", "review_due", 0L,
        "review_efactor", 2.5, "review_interval_days", 0, "learned_at", JSONObject.NULL,
        "reviewed_at", JSONObject.NULL);
  }

  private String date(JSONObject plan, long now) {
    return Instant.ofEpochMilli(now).atZone(ZoneId.of(plan.optString("zone_id"))).toLocalDate().toString();
  }

  private JSONObject savedDay(String planId, String day) {
    return pair(items("days"), "plan_id", planId, "date", day);
  }

  /** Initializes today's fixed task list if absent. Also a command: save the library afterwards. */
  public synchronized JSONObject dailyQueue(String id, long now) {
    JSONObject p = active(id);
    String localDate = date(p, now);
    JSONObject day = savedDay(id, localDate);
    Set<String> available = pool(p);
    if (day == null) {
      boolean scheduled = false;
      JSONArray weekdays = p.optJSONArray("weekdays");
      for (int i = 0; i < weekdays.length(); i++)
        if (weekdays.optInt(i) == LocalDate.parse(localDate).getDayOfWeek().getValue()) scheduled = true;
      List<String> fresh = new ArrayList<>(), due = new ArrayList<>();
      long tomorrow = LocalDate.parse(localDate).plusDays(1)
          .atStartOfDay(ZoneId.of(p.optString("zone_id"))).toInstant().toEpochMilli();
      if (scheduled) for (String vocabId : available) {
        JSONObject s = savedState(id, vocabId);
        if (s == null || s.isNull("learned_at")) fresh.add(vocabId);
        else if (s.optLong("review_due") < tomorrow) due.add(vocabId);
      }
      // Stable order independent of notebook reference order or hash iteration.
      Collections.sort(fresh);
      due.sort(Comparator.comparingLong((String v) -> savedState(id, v).optLong("review_due"))
          .thenComparing(v -> v));
      JSONArray tasks = new JSONArray();
      int newTarget = Math.min(p.optInt("daily_new_limit"), fresh.size());
      int reviewTarget = Math.min(p.optInt("daily_review_limit"), due.size());
      for (int i = 0; i < reviewTarget; i++) tasks.put(task(due.get(i), "review"));
      for (int i = 0; i < newTarget; i++) tasks.put(task(fresh.get(i), "new"));
      day = object("plan_id", id, "date", localDate, "zone_id", p.optString("zone_id"),
          "scheduled", scheduled, "daily_new_limit", p.optInt("daily_new_limit"),
          "daily_review_limit", p.optInt("daily_review_limit"), "new_target", newTarget,
          "review_target", reviewTarget, "created_at", timestamp(now), "tasks", tasks);
      items("days").put(day);
    }
    JSONObject view = Library.copy(day);
    JSONArray tasks = day.optJSONArray("tasks"), queue = new JSONArray();
    for (int i = 0; i < tasks.length(); i++) {
      JSONObject task = tasks.optJSONObject(i);
      if (!task.optBoolean("completed") && available.contains(task.optString("vocab_id")))
        queue.put(Library.copy(task));
    }
    view.remove("tasks");
    Library.put(view, "queue", queue);
    return view;
  }

  private JSONObject task(String id, String kind) {
    return object("vocab_id", id, "kind", kind, "completed", false, "attempts", 0);
  }

  /** eventId identifies ONE rating action. Retry the same id and payload on persistence failure. */
  public synchronized JSONObject record(String id, String vocabId, String grade, String eventId,
      long durationMillis, long now) {
    check(eventId != null && !eventId.trim().isEmpty() && eventId.length() <= 128, "答题事件ID无效");
    check(Arrays.asList("再次", "困难", "简单").contains(grade), "评分无效");
    check(durationMillis >= 0 && durationMillis <= 86400000L, "答题时长无效");
    JSONObject existing = find(items("events"), "id", eventId);
    if (existing != null) {
      check(id.equals(existing.optString("plan_id")) && vocabId.equals(existing.optString("vocab_id"))
          && grade.equals(existing.optString("grade")) && durationMillis == existing.optLong("duration_ms")
          && timestamp(now).equals(existing.optString("occurred_at")), "事件ID已用于其他答题");
      return Library.copy(existing);
    }
    JSONObject p = active(id);
    check(pool(p).contains(vocabId), "词汇已移出计划笔记本或已删除");
    String localDate = date(p, now);
    JSONObject day = require(savedDay(id, localDate), "请先获取当日复习任务");
    JSONObject task = require(find(day.optJSONArray("tasks"), "vocab_id", vocabId), "该词汇不在当日任务中");
    check(!task.optBoolean("completed"), "该词汇今日已完成");
    JSONObject old = savedState(id, vocabId), next = old == null ? newState(id, vocabId) : Library.copy(old);
    ReviewLogic.ScheduleResult schedule = ReviewLogic.calculateSchedule(next, grade, now);
    Library.put(next, "review_count", schedule.nextCount);
    Library.put(next, "review_mastery_level", schedule.nextLevel);
    Library.put(next, "review_grade", schedule.nextGrade);
    Library.put(next, "review_due", schedule.nextDue);
    Library.put(next, "review_efactor", schedule.nextEf);
    Library.put(next, "review_interval_days", schedule.nextIntervalDays);
    Library.put(next, "reviewed_at", timestamp(now));
    boolean completed = !grade.equals("再次");
    if (completed && next.isNull("learned_at")) Library.put(next, "learned_at", timestamp(now));
    JSONObject event = object("id", eventId, "plan_id", id, "vocab_id", vocabId,
        "date", localDate, "occurred_at", timestamp(now), "grade", grade,
        "kind", task.optString("kind"), "is_retry", task.optInt("attempts") > 0,
        "completed", completed, "duration_ms", durationMillis);
    // All validation/calculation precedes mutation; one Library snapshot commits the transaction.
    if (old != null) items("states").remove(indexOf(items("states"), old));
    items("states").put(next);
    Library.put(task, "attempts", task.optInt("attempts") + 1);
    Library.put(task, "completed", completed);
    if (!completed) {
      JSONArray tasks = day.optJSONArray("tasks");
      tasks.remove(indexOf(tasks, task));
      tasks.put(task); // Repeat after the other pending words.
    }
    items("events").put(event);
    return Library.copy(event);
  }

  private static final String[] COUNTERS = {"attempts", "new_completed", "review_completed",
      "again", "hard", "easy", "duration_ms"};

  /** Inclusive date range, zero-filled for heatmaps, up to 3660 days. Archived history is readable. */
  public synchronized JSONObject statistics(String id, String from, String through) {
    JSONObject p = plan(id);
    LocalDate start = LocalDate.parse(from), end = LocalDate.parse(through);
    check(!end.isBefore(start) && ChronoUnit.DAYS.between(start, end) < 3660, "统计日期范围无效（最多3660天）");
    Map<String, JSONObject> buckets = new LinkedHashMap<>();
    JSONObject totals = counters();
    for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
      JSONObject row = counters();
      Library.put(row, "date", d.toString());
      JSONObject day = savedDay(id, d.toString());
      Library.put(row, "scheduled", day == null ? JSONObject.NULL : day.optBoolean("scheduled"));
      Library.put(row, "new_target", day == null ? 0 : day.optInt("new_target"));
      Library.put(row, "review_target", day == null ? 0 : day.optInt("review_target"));
      buckets.put(d.toString(), row);
    }
    Set<String> distinct = new HashSet<>();
    Map<String, Set<String>> dailyDistinct = new HashMap<>();
    for (int i = 0; i < items("events").length(); i++) {
      JSONObject event = items("events").optJSONObject(i);
      JSONObject bucket = buckets.get(event.optString("date"));
      if (!id.equals(event.optString("plan_id")) || bucket == null) continue;
      accumulate(bucket, event); accumulate(totals, event);
      distinct.add(event.optString("vocab_id"));
      dailyDistinct.computeIfAbsent(event.optString("date"), key -> new HashSet<>()).add(event.optString("vocab_id"));
    }
    JSONArray days = new JSONArray();
    int activeDays = 0;
    for (Map.Entry<String, JSONObject> entry : buckets.entrySet()) {
      JSONObject row = entry.getValue();
      Library.put(row, "distinct_words", dailyDistinct.getOrDefault(entry.getKey(), Collections.emptySet()).size());
      if (row.optLong("attempts") > 0) activeDays++;
      days.put(row);
    }
    Library.put(totals, "distinct_words", distinct.size());
    return object("plan_id", id, "zone_id", p.optString("zone_id"), "from", from,
        "through", through, "active_days", activeDays, "totals", totals, "days", days);
  }

  private static JSONObject counters() {
    JSONObject result = new JSONObject();
    for (String key : COUNTERS) Library.put(result, key, 0L);
    return result;
  }

  private static void accumulate(JSONObject bucket, JSONObject event) {
    increment(bucket, "attempts", 1);
    String gradeKey = event.optString("grade").equals("再次") ? "again"
        : event.optString("grade").equals("困难") ? "hard" : "easy";
    increment(bucket, gradeKey, 1);
    increment(bucket, "duration_ms", event.optLong("duration_ms"));
    if (event.optBoolean("completed")) increment(bucket, event.optString("kind") + "_completed", 1);
  }

  private static void increment(JSONObject o, String key, long value) {
    Library.put(o, key, o.optLong(key) + value);
  }

  static JSONObject find(JSONArray items, String key, String value) {
    for (int i = 0; i < items.length(); i++) {
      JSONObject o = items.optJSONObject(i);
      if (Objects.equals(value, o.optString(key))) return o;
    }
    return null;
  }

  static JSONObject pair(JSONArray items, String k1, String v1, String k2, String v2) {
    for (int i = 0; i < items.length(); i++) {
      JSONObject o = items.optJSONObject(i);
      if (v1.equals(o.optString(k1)) && v2.equals(o.optString(k2))) return o;
    }
    return null;
  }

  private static int indexOf(JSONArray items, JSONObject object) {
    for (int i = 0; i < items.length(); i++) if (items.opt(i) == object) return i;
    throw new IllegalArgumentException("复习记录不存在");
  }

  static String timestamp(long millis) { return Instant.ofEpochMilli(millis).toString(); }
  static boolean validTimestamp(String value) {
    try { Instant.parse(value); return true; } catch (RuntimeException e) { return false; }
  }
  static void check(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }
  private static JSONObject require(JSONObject value, String message) {
    check(value != null, message); return value;
  }
}
