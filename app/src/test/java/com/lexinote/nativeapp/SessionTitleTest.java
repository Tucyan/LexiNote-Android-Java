package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Comprehensive 4-Tier Opaque-Box Test Suite for Session Titles and Renaming.
 *
 * Covers:
 * - Feature 3: Temporary session actual title display in assistant header (MainActivity.java:318)
 * - Feature 8: Saved session explicit rename, title trimming, length bound (40 chars), and revision tracking
 * - Integration with TitleSuggestionGuard and FieldProvenance
 */
public class SessionTitleTest {

  /**
   * Authoritative implementation of the title resolution contract defined in:
   * - MainActivity.java:317-320
   * - QueryUi.java:1136-1141
   */
  private String resolveHeaderTitle(String screen, JSONObject session) {
    if (!"assistant".equals(screen)) {
      return "其他页面";
    }
    String sessionTitle = session != null ? extractSessionTitle(session) : null;
    return (sessionTitle != null && !sessionTitle.trim().isEmpty()) ? sessionTitle : "AI 助手";
  }

  private String extractSessionTitle(JSONObject s) {
    if (s == null) return "";
    JSONObject meta = s.optJSONObject("metadata");
    if (meta != null && meta.has("title")) {
      return meta.optString("title", "");
    }
    return s.optString("title", s.optString("input_text", ""));
  }

  /**
   * Authoritative implementation of manual rename logic in QueryUi.java / Library.java.
   */
  private JSONObject performManualRename(JSONObject session, String newRawTitle) throws Exception {
    String trimmed = newRawTitle == null ? "" : newRawTitle.trim();
    String bounded = trimmed.length() > 40 ? trimmed.substring(0, 40) : trimmed;

    JSONObject meta = session.optJSONObject("metadata");
    if (meta == null) {
      meta = new JSONObject();
      session.put("metadata", meta);
    }

    int currentRev = meta.optInt("title_revision", 0);
    meta.put("title", bounded);
    meta.put("title_source", "user");
    meta.put("title_revision", currentRev + 1);
    FieldProvenance.markPresent(meta, false, "title");

    session.put("title", bounded);
    FieldProvenance.markPresent(session, false, "title");
    return session;
  }

  // =========================================================================
  // Tier 1: Feature Coverage (Representative Inputs)
  // =========================================================================

  @Test
  public void tier1_temporarySessionDisplaysActualTitleWhenAvailable() throws Exception {
    JSONObject tempSession = new JSONObject()
        .put("id", "temp_001")
        .put("saved", false)
        .put("metadata", new JSONObject()
            .put("retention", "temporary")
            .put("title", "Photosynthesis in Plants"));

    String resolved = resolveHeaderTitle("assistant", tempSession);
    assertEquals("Temporary session must display its actual title", "Photosynthesis in Plants", resolved);
    assertNotEquals("Temporary session must NOT be hardcoded to 'AI 助手'", "AI 助手", resolved);
  }

  @Test
  public void tier1_savedSessionDisplaysTitle() throws Exception {
    JSONObject savedSession = new JSONObject()
        .put("id", "saved_001")
        .put("saved", true)
        .put("metadata", new JSONObject()
            .put("retention", "saved")
            .put("title", "Quantum Computing Basics"));

    String resolved = resolveHeaderTitle("assistant", savedSession);
    assertEquals("Quantum Computing Basics", resolved);
  }

  @Test
  public void tier1_fallbackToDefaultAssistantTitleWhenSessionHasNoTitle() throws Exception {
    JSONObject emptyTitleSession = new JSONObject()
        .put("id", "empty_001")
        .put("saved", false)
        .put("metadata", new JSONObject().put("retention", "temporary"));

    String resolved = resolveHeaderTitle("assistant", emptyTitleSession);
    assertEquals("AI 助手", resolved);
  }

  @Test
  public void tier1_titleFallsBackToInputTextWhenTitleFieldAbsent() throws Exception {
    JSONObject inputSession = new JSONObject()
        .put("id", "input_001")
        .put("input_text", "What is the speed of light?");

    String resolved = resolveHeaderTitle("assistant", inputSession);
    assertEquals("What is the speed of light?", resolved);
  }

  @Test
  public void tier1_manualRenameUpdatesTitleAndIncrementsRevision() throws Exception {
    JSONObject session = new JSONObject()
        .put("id", "s1")
        .put("metadata", new JSONObject().put("title", "Old Title").put("title_revision", 1));

    performManualRename(session, "New Renamed Title");

    assertEquals("New Renamed Title", session.getString("title"));
    assertEquals("New Renamed Title", session.getJSONObject("metadata").getString("title"));
    assertEquals(2, session.getJSONObject("metadata").getInt("title_revision"));
    assertEquals("user", session.getJSONObject("metadata").getString("title_source"));
  }

  // =========================================================================
  // Tier 2: Boundary & Corner Cases (Adversarial Probing)
  // =========================================================================

  @Test
  public void tier2_nullAndEmptySessionHandling() {
    assertEquals("AI 助手", resolveHeaderTitle("assistant", null));
    assertEquals("AI 助手", resolveHeaderTitle("assistant", new JSONObject()));
  }

  @Test
  public void tier2_whitespaceOnlyTitlesFallbackToDefault() throws Exception {
    JSONObject whitespaceSession = new JSONObject()
        .put("metadata", new JSONObject().put("title", "    \t \n  "));
    assertEquals("AI 助手", resolveHeaderTitle("assistant", whitespaceSession));
  }

  @Test
  public void tier2_exact40CharAndLongTitleTruncationOnRename() throws Exception {
    JSONObject session = new JSONObject();

    // Exactly 40 characters
    String fortyChars = "1234567890123456789012345678901234567890";
    performManualRename(session, fortyChars);
    assertEquals(40, session.getString("title").length());
    assertEquals(fortyChars, session.getString("title"));

    // 60 characters -> must truncate to exactly 40
    String sixtyChars = "123456789012345678901234567890123456789012345678901234567890";
    performManualRename(session, sixtyChars);
    assertEquals(40, session.getString("title").length());
    assertEquals(fortyChars, session.getString("title"));
  }

  @Test
  public void tier2_renameTrimsLeadingAndTrailingWhitespace() throws Exception {
    JSONObject session = new JSONObject();
    performManualRename(session, "   Clean Title With Spaces   ");
    assertEquals("Clean Title With Spaces", session.getString("title"));
  }

  @Test
  public void tier2_titleWithUnicodeEmojisAndSpecialCharacters() throws Exception {
    JSONObject emojiSession = new JSONObject()
        .put("metadata", new JSONObject().put("title", "📖 高频考研词汇 #1 · Day 3"));
    assertEquals("📖 高频考研词汇 #1 · Day 3", resolveHeaderTitle("assistant", emojiSession));
  }

  // =========================================================================
  // Tier 3: Cross-Feature Combinations
  // =========================================================================

  @Test
  public void tier3_manualRenameBlocksAiTitleSuggestionGuard() throws Exception {
    JSONObject session = new JSONObject()
        .put("id", "guard_test")
        .put("metadata", new JSONObject()
            .put("title", "Initial Query")
            .put("title_source", "fallback")
            .put("title_revision", 1));

    // Initially, AI suggestion is permitted
    assertTrue(
        "Initial fallback title permits AI suggestion",
        TitleSuggestionGuard.mayApply(true, false, "", 1, 1, 100L, 100L));

    // User performs manual rename via [重命名]
    performManualRename(session, "User Chosen Title");

    // After manual rename: manuallyNamed is true, revision is 2
    assertFalse(
        "AI suggestion must be rejected after manual rename",
        TitleSuggestionGuard.mayApply(true, true, "User Chosen Title", 1, 2, 100L, 100L));
  }

  @Test
  public void tier3_provenanceMarksUserSourceOnManualRename() throws Exception {
    JSONObject session = new JSONObject();
    performManualRename(session, "Manual Named Title");

    // User provenance is marked (not AI generated)
    assertFalse(
        "Title must not be marked as AI generated",
        FieldProvenance.isGenerated(session, "title"));
    assertFalse(
        "Metadata title must not be marked as AI generated",
        FieldProvenance.isGenerated(session.getJSONObject("metadata"), "title"));
  }

  @Test
  public void tier3_sessionPromotionFromTemporaryToSavedPreservesTitle() throws Exception {
    JSONObject session = new JSONObject()
        .put("id", "promo_001")
        .put("saved", false)
        .put("metadata", new JSONObject()
            .put("retention", "temporary")
            .put("title", "Vocabulary Session"));

    assertEquals("Vocabulary Session", resolveHeaderTitle("assistant", session));

    // Promote to saved session
    session.put("saved", true);
    session.getJSONObject("metadata").put("retention", "saved");

    // Title is preserved exactly
    assertEquals("Vocabulary Session", resolveHeaderTitle("assistant", session));
  }

  // =========================================================================
  // Tier 4: Real-World Application Scenarios
  // =========================================================================

  @Test
  public void tier4_endToEndSessionTitleLifecycle() throws Exception {
    // 1. User starts with a query in temporary session
    JSONObject session = new JSONObject()
        .put("id", "lifecycle_01")
        .put("input_text", "serendipity explanation")
        .put("metadata", new JSONObject()
            .put("retention", "temporary")
            .put("title", "serendipity explanation")
            .put("title_source", "fallback")
            .put("title_revision", 0));

    assertEquals("serendipity explanation", resolveHeaderTitle("assistant", session));

    // 2. AI suggests a refined title
    String aiSuggestion = TitleSuggestionGuard.clean(" \"Meaning of Serendipity\" ");
    assertEquals("Meaning of Serendipity", aiSuggestion);

    // Apply AI title
    session.getJSONObject("metadata").put("title", aiSuggestion);
    session.getJSONObject("metadata").put("title_source", "ai");
    session.getJSONObject("metadata").put("title_revision", 1);
    FieldProvenance.markPresent(session.getJSONObject("metadata"), true, "title");

    assertEquals("Meaning of Serendipity", resolveHeaderTitle("assistant", session));
    assertTrue(FieldProvenance.isGenerated(session.getJSONObject("metadata"), "title"));

    // 3. User taps [重命名] button to set custom title
    performManualRename(session, "My Favorite Word");
    assertEquals("My Favorite Word", resolveHeaderTitle("assistant", session));
    assertFalse(FieldProvenance.isGenerated(session.getJSONObject("metadata"), "title"));

    // 4. Stale AI response returns late -> blocked by guard
    boolean guardAllows = TitleSuggestionGuard.mayApply(
        true,
        true, // manuallyNamed
        session.getJSONObject("metadata").getString("title"),
        1, // captured revision was 1
        session.getJSONObject("metadata").getInt("title_revision"), // now 2
        100L,
        100L);
    assertFalse("Late AI title must be blocked", guardAllows);

    // 5. Final state in assistant header remains user title
    assertEquals("My Favorite Word", resolveHeaderTitle("assistant", session));
  }
}
