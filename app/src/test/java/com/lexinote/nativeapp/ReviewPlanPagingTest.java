package com.lexinote.nativeapp;

import static org.junit.Assert.*;
import java.time.Instant;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class ReviewPlanPagingTest {
  private final long monday = Instant.parse("2026-10-26T02:00:00Z").toEpochMilli();
  private final int[] workDays = {1, 2, 3, 4, 5};
  private final int[] weekends = {6, 7};

  @Test
  public void testActivationSettingsPersistenceInLibrary() {
    Library lib = new Library();
    JSONObject settings = lib.settings();
    assertFalse(settings.has("disabled_review_plans"));

    // Disable plan1 and plan2
    JSONArray disabled = new JSONArray();
    disabled.put("plan_1");
    disabled.put("plan_2");
    Library.put(settings, "disabled_review_plans", disabled);

    // Verify snapshot retains settings
    Library copy = new Library(lib.snapshot());
    JSONArray retrieved = copy.settings().optJSONArray("disabled_review_plans");
    assertNotNull(retrieved);
    assertEquals(2, retrieved.length());
    assertEquals("plan_1", retrieved.optString(0));
    assertEquals("plan_2", retrieved.optString(1));
  }

  @Test
  public void testPlanSortingPriorityTiers() throws Exception {
    Library lib = new Library();
    String noteA = lib.createNotebook("生词本A", null);
    String noteB = lib.createNotebook("生词本B", null);
    String noteC = lib.createNotebook("生词本C", null);

    // Add words to noteA and noteC
    lib.saveResult(new JSONObject("{word:'apple',meanings:[{zh_definition:'苹果'}]}"), new String[] {noteA}, new JSONObject());
    lib.saveResult(new JSONObject("{word:'banana',meanings:[{zh_definition:'香蕉'}]}"), new String[] {noteC}, new JSONObject());

    // Plan A: Monday is scheduled, has pending task (apple) -> Priority 1
    String planA = lib.reviewPlans().create("待学计划A", noteA, 10, 20, workDays, "Asia/Shanghai", false, monday);

    // Plan B: Monday is NOT scheduled (weekends only) -> Priority 2
    String planB = lib.reviewPlans().create("休息日计划B", noteB, 10, 20, weekends, "Asia/Shanghai", false, monday);

    // Plan C: Monday is scheduled, word (banana) completed -> Priority 3
    String planC = lib.reviewPlans().create("已完成计划C", noteC, 10, 20, workDays, "Asia/Shanghai", false, monday);
    JSONObject dayC = lib.reviewPlans().dailyQueue(planC, monday);
    JSONArray queueC = dayC.optJSONArray("queue");
    assertNotNull(queueC);
    assertEquals(1, queueC.length());
    String wordC = queueC.getJSONObject(0).getString("vocab_id");
    // Complete word in Plan C
    lib.reviewPlans().record(planC, wordC, "简单", "evt_c1", 1000L, monday);

    // Now evaluate priority tiers
    int prioA = calculatePriority(lib, planA, monday);
    int prioB = calculatePriority(lib, planB, monday);
    int prioC = calculatePriority(lib, planC, monday);

    assertEquals("今日有安排且未完成的计划排在最前 (Priority 1)", 1, prioA);
    assertEquals("今日未安排复习的计划排在中间 (Priority 2)", 2, prioB);
    assertEquals("今日复习已全部完成的计划自动沉底 (Priority 3)", 3, prioC);

    List<Integer> priorities = Arrays.asList(prioB, prioC, prioA);
    Collections.sort(priorities);
    assertEquals(Arrays.asList(1, 2, 3), priorities);
  }

  @Test
  public void testRenderDotsIndicator() {
    assertEquals("", renderDots(0, 1));
    assertEquals("● ○", renderDots(0, 2));
    assertEquals("○ ●", renderDots(1, 2));
    assertEquals("● ○ ○", renderDots(0, 3));
    assertEquals("○ ● ○", renderDots(1, 3));
    assertEquals("○ ○ ●", renderDots(2, 3));
  }

  private int calculatePriority(Library library, String planId, long nowMillis) {
    JSONObject day = library.reviewPlans().dailyQueue(planId, nowMillis);
    boolean scheduled = day.optBoolean("scheduled", false);
    int newTarget = day.optInt("new_target", 0);
    int reviewTarget = day.optInt("review_target", 0);
    int totalTarget = newTarget + reviewTarget;
    JSONArray q = day.optJSONArray("queue");
    int remaining = q != null ? q.length() : 0;

    if (scheduled && totalTarget > 0 && remaining > 0) {
      return 1;
    } else if (!scheduled || totalTarget == 0) {
      return 2;
    } else {
      return 3;
    }
  }

  private String renderDots(int currentIdx, int total) {
    if (total <= 1) return "";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < total; i++) {
      sb.append(i == currentIdx ? "● " : "○ ");
    }
    return sb.toString().trim();
  }
}
