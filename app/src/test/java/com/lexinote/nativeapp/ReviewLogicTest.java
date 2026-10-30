package com.lexinote.nativeapp;
import org.junit.Test;
import static org.junit.Assert.*;
public class ReviewLogicTest {
  @Test public void legacyAndRatings() {
    int mastered = 0;
    for (int i = 0; i < 48; i++) if (ReviewLogic.mastered(null, 3, "")) mastered++;
    assertEquals(48, mastered);
    assertEquals(1, ReviewLogic.nextLevel(null, 0, "", "简单"));
    int budget = ReviewLogic.nextLevel(null, 2, "简单", "再次");
    assertFalse(ReviewLogic.mastered(budget, 3, "再次"));
    assertEquals(48, mastered);
    assertEquals(0, ReviewLogic.nextLevel(0, 4, "再次", "再次"));
    assertFalse(ReviewLogic.mastered(null, 10, "困难"));
    assertFalse(ReviewLogic.mastered(null, 10, "再次"));
    assertFalse(ReviewLogic.mastered(null, 10, "unknown"));
    assertEquals(2, ReviewLogic.nextLevel(2, 2, "简单", "困难"));
    assertEquals(3, ReviewLogic.nextLevel(2, 2, "简单", "简单"));
    assertEquals(2, ReviewLogic.nextLevel(2, 2, "简单", "unknown"));
  }
  @Test public void progressCountsDistinctResolvedWords() {
    ReviewLogic.Progress p = new ReviewLogic.Progress(10);
    p.rate("再次"); p.rate("再次");
    assertEquals(10, p.total); assertEquals(0, p.completed);
    p.rate("简单"); assertEquals(1, p.completed);
    p.skip(); assertEquals(1, p.completed); assertEquals(1, p.skipped);
  }

  @Test public void testCalculateScheduleAndSpacedRepetition() {
    long now = 1700000000000L;
    org.json.JSONObject word = new org.json.JSONObject();

    // 1. New word test
    ReviewLogic.ScheduleResult rAgain = ReviewLogic.calculateSchedule(word, "再次", now);
    assertEquals(1, rAgain.nextCount);
    assertEquals(0, rAgain.nextLevel);
    assertEquals(0, rAgain.nextIntervalDays);
    assertEquals("<10分钟", rAgain.intervalLabel);
    assertEquals(now, rAgain.nextDue);

    ReviewLogic.ScheduleResult rHard = ReviewLogic.calculateSchedule(word, "困难", now);
    assertEquals(1, rHard.nextCount);
    assertEquals(0, rHard.nextLevel);
    assertEquals(1, rHard.nextIntervalDays);
    assertEquals("1天后", rHard.intervalLabel);
    assertEquals(now + 86400000L, rHard.nextDue);

    ReviewLogic.ScheduleResult rEasy = ReviewLogic.calculateSchedule(word, "简单", now);
    assertEquals(1, rEasy.nextCount);
    assertEquals(1, rEasy.nextLevel);
    assertEquals(3, rEasy.nextIntervalDays);
    assertEquals("3天后", rEasy.intervalLabel);
    assertEquals(now + 3 * 86400000L, rEasy.nextDue);

    // 2. Second repetition with 简单
    try {
      word.put("review_count", 1);
      word.put("review_mastery_level", 1);
      word.put("review_interval_days", 3);
      word.put("review_efactor", 2.6);
    } catch (Exception ignored) {}

    ReviewLogic.ScheduleResult rEasy2 = ReviewLogic.calculateSchedule(word, "简单", now);
    assertEquals(2, rEasy2.nextCount);
    assertEquals(2, rEasy2.nextLevel);
    assertEquals(6, rEasy2.nextIntervalDays);
    assertEquals("6天后", rEasy2.intervalLabel);

    // 3. Third repetition with 简单 -> moves to mastered (level 3) and extended interval
    try {
      word.put("review_count", 2);
      word.put("review_mastery_level", 2);
      word.put("review_interval_days", 6);
      word.put("review_efactor", 2.7);
    } catch (Exception ignored) {}

    ReviewLogic.ScheduleResult rEasy3 = ReviewLogic.calculateSchedule(word, "简单", now);
    assertEquals(3, rEasy3.nextCount);
    assertEquals(3, rEasy3.nextLevel);
    assertTrue(rEasy3.nextIntervalDays >= 16);
    assertTrue(ReviewLogic.mastered(rEasy3.nextLevel, rEasy3.nextCount, "简单"));
  }

  @Test public void testFormatInterval() {
    assertEquals("<10分钟", ReviewLogic.formatInterval(0));
    assertEquals("1天后", ReviewLogic.formatInterval(1));
    assertEquals("5天后", ReviewLogic.formatInterval(5));
    assertEquals("28天后", ReviewLogic.formatInterval(28));
    assertEquals("1个月后", ReviewLogic.formatInterval(30));
    assertEquals("2个月后", ReviewLogic.formatInterval(65));
    assertEquals("1年后", ReviewLogic.formatInterval(365));
    assertEquals("2年后", ReviewLogic.formatInterval(730));
  }
}
