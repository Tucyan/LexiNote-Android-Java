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
}
