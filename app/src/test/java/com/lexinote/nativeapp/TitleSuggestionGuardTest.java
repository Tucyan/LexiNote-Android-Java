package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import org.junit.Test;

public class TitleSuggestionGuardTest {
  @Test
  public void blocksManualRenameDeletionRestoreAndNewerSessionRevision() {
    assertTrue(TitleSuggestionGuard.mayApply(true, false, "", 4, 4, 2, 2));
    assertFalse(TitleSuggestionGuard.mayApply(true, true, "", 4, 4, 2, 2));
    assertFalse(TitleSuggestionGuard.mayApply(false, false, "", 4, 4, 2, 2));
    assertFalse(TitleSuggestionGuard.mayApply(true, false, "", 4, 5, 2, 2));
    assertFalse(TitleSuggestionGuard.mayApply(true, false, "", 4, 4, 2, 3));
    assertFalse(TitleSuggestionGuard.mayApply(true, false, "已有标题", 4, 4, 2, 2));
  }

  @Test
  public void cleansAndBoundsSuggestion() {
    assertEquals("Hello world", TitleSuggestionGuard.clean("  \"Hello   world\" "));
    assertEquals(48, TitleSuggestionGuard.clean("x".repeat(80)).length());
  }
}
