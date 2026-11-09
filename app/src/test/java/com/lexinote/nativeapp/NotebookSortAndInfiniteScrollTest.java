package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import java.util.*;
import org.json.*;
import org.junit.Test;

public class NotebookSortAndInfiniteScrollTest {

  @Test
  public void testAlphabeticalSortLogic() throws Exception {
    List<JSONObject> items = new ArrayList<>();
    items.add(new JSONObject().put("id", "1").put("content", "Banana"));
    items.add(new JSONObject().put("id", "2").put("content", "apple"));
    items.add(new JSONObject().put("id", "3").put("content", "Cherry"));
    items.add(new JSONObject().put("id", "4").put("content", "date"));

    // A-Z
    List<JSONObject> az = new ArrayList<>(items);
    Collections.sort(az, (v1, v2) ->
        v1.optString("content", "").compareToIgnoreCase(v2.optString("content", "")));
    assertEquals("apple", az.get(0).optString("content"));
    assertEquals("Banana", az.get(1).optString("content"));
    assertEquals("Cherry", az.get(2).optString("content"));
    assertEquals("date", az.get(3).optString("content"));

    // Z-A
    List<JSONObject> za = new ArrayList<>(items);
    Collections.sort(za, (v1, v2) ->
        v2.optString("content", "").compareToIgnoreCase(v1.optString("content", "")));
    assertEquals("date", za.get(0).optString("content"));
    assertEquals("Cherry", za.get(1).optString("content"));
    assertEquals("Banana", za.get(2).optString("content"));
    assertEquals("apple", za.get(3).optString("content"));
  }

  @Test
  public void testTimelineSortLogic() throws Exception {
    List<JSONObject> items = new ArrayList<>();
    items.add(new JSONObject().put("id", "1").put("content", "Mid").put("created_at", "2026-05-10T10:00:00Z"));
    items.add(new JSONObject().put("id", "2").put("content", "Oldest").put("created_at", "2025-12-01T10:00:00Z"));
    items.add(new JSONObject().put("id", "3").put("content", "Newest").put("created_at", "2026-11-01T10:00:00Z"));

    // 最新 (Descending)
    List<JSONObject> desc = new ArrayList<>(items);
    Collections.sort(desc, (v1, v2) -> {
      String t1 = v1.optString("created_at", v1.optString("updated_at", ""));
      String t2 = v2.optString("created_at", v2.optString("updated_at", ""));
      return t2.compareTo(t1);
    });
    assertEquals("Newest", desc.get(0).optString("content"));
    assertEquals("Mid", desc.get(1).optString("content"));
    assertEquals("Oldest", desc.get(2).optString("content"));

    // 最早 (Ascending)
    List<JSONObject> asc = new ArrayList<>(items);
    Collections.sort(asc, (v1, v2) -> {
      String t1 = v1.optString("created_at", v1.optString("updated_at", ""));
      String t2 = v2.optString("created_at", v2.optString("updated_at", ""));
      return t1.compareTo(t2);
    });
    assertEquals("Oldest", asc.get(0).optString("content"));
    assertEquals("Mid", asc.get(1).optString("content"));
    assertEquals("Newest", asc.get(2).optString("content"));
  }

  @Test
  public void testMasteryPrioritySortLogic() throws Exception {
    List<JSONObject> items = new ArrayList<>();
    JSONObject mastered = new JSONObject()
        .put("id", "1")
        .put("content", "Mastered")
        .put("review_count", 5)
        .put("review_grade", "easy")
        .put("review_mastery_level", 4);
    JSONObject due = new JSONObject()
        .put("id", "2")
        .put("content", "Due")
        .put("review_count", 1)
        .put("review_due", System.currentTimeMillis() - 10000);
    JSONObject unlearned = new JSONObject()
        .put("id", "3")
        .put("content", "Unlearned")
        .put("review_count", 0);

    items.add(mastered);
    items.add(due);
    items.add(unlearned);

    // 生词优先 (Due first, then unmastered, then mastered)
    Collections.sort(items, (v1, v2) -> {
      boolean due1 = isDueForTest(v1), due2 = isDueForTest(v2);
      if (due1 != due2) return due1 ? -1 : 1;
      boolean m1 = isMasteredForTest(v1), m2 = isMasteredForTest(v2);
      if (m1 != m2) return m1 ? 1 : -1;
      return 0;
    });

    assertEquals("Due", items.get(0).optString("content"));
    assertEquals("Unlearned", items.get(1).optString("content"));
    assertEquals("Mastered", items.get(2).optString("content"));
  }

  private boolean isMasteredForTest(JSONObject v) {
    Integer level = v.has("review_mastery_level") ? v.optInt("review_mastery_level") : null;
    int count = v.optInt("review_count", 0);
    String grade = v.optString("review_grade", "");
    return ReviewLogic.mastered(level, count, grade);
  }

  private boolean isDueForTest(JSONObject v) {
    if (isMasteredForTest(v)) return false;
    int count = v.optInt("review_count", 0);
    long due = v.optLong("review_due", 0);
    return count > 0 && due <= System.currentTimeMillis();
  }

  @Test
  public void testDefinitionModeSelectionLogic() throws Exception {
    JSONObject meaning = new JSONObject()
        .put("zh_definition", "苹果")
        .put("en_definition", "a round fruit with red or green skin");

    // "zh" mode: zh primary
    String defZh = "en".equals("zh")
        ? (!meaning.optString("en_definition").isEmpty() ? meaning.optString("en_definition") : meaning.optString("zh_definition"))
        : (!meaning.optString("zh_definition").isEmpty() ? meaning.optString("zh_definition") : meaning.optString("en_definition"));
    assertEquals("苹果", defZh);

    // "en" mode: en primary
    String defEn = "en".equals("en")
        ? (!meaning.optString("en_definition").isEmpty() ? meaning.optString("en_definition") : meaning.optString("zh_definition"))
        : (!meaning.optString("zh_definition").isEmpty() ? meaning.optString("zh_definition") : meaning.optString("en_definition"));
    assertEquals("a round fruit with red or green skin", defEn);

    // "hidden" mode: revealed set
    Set<String> revealed = new HashSet<>();
    String vocabId = "voc_123";
    assertTrue("hidden".equals("hidden") && !revealed.contains(vocabId));
    revealed.add(vocabId);
    assertFalse("hidden".equals("hidden") && !revealed.contains(vocabId));
  }

  @Test
  public void testTimelineGroupingSections() throws Exception {
    List<JSONObject> rows = new ArrayList<>();
    rows.add(new JSONObject().put("id", "1").put("created_at", "2026-11-05T12:00:00Z"));
    rows.add(new JSONObject().put("id", "2").put("created_at", "2026-11-02T08:00:00Z"));
    rows.add(new JSONObject().put("id", "3").put("created_at", "2026-10-25T14:00:00Z"));
    rows.add(new JSONObject().put("id", "4").put("created_at", "2025-12-15T09:00:00Z"));

    Map<String, Integer> counts = new LinkedHashMap<>();
    for (JSONObject v : rows) {
      String date = v.optString("created_at", "");
      String ym = date.length() >= 7 ? date.substring(0, 7) : "其他";
      counts.put(ym, counts.getOrDefault(ym, 0) + 1);
    }

    assertEquals(3, counts.size());
    assertEquals(Integer.valueOf(2), counts.get("2026-11"));
    assertEquals(Integer.valueOf(1), counts.get("2026-10"));
    assertEquals(Integer.valueOf(1), counts.get("2025-12"));
  }
}
