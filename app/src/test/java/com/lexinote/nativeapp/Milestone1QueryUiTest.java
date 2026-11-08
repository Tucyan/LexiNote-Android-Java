package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.json.JSONObject;
import org.junit.Test;

public class Milestone1QueryUiTest {

  private String resolveAssistantTitle(JSONObject session) {
    if (session == null) return "AI 助手";
    JSONObject meta = session.optJSONObject("metadata");
    String title =
        meta == null
            ? session.optString("title", session.optString("input_text", ""))
            : meta.optString("title", "");
    return (title != null && !title.trim().isEmpty()) ? title : "AI 助手";
  }

  @Test
  public void testTemporarySessionTitleDisplayedInAssistant() throws Exception {
    JSONObject tempSession = new JSONObject();
    tempSession.put("id", "temp-123");
    tempSession.put("saved", false);
    tempSession.put("title", "Quantum Computing Query");
    JSONObject meta = new JSONObject();
    meta.put("title", "Quantum Computing Query");
    meta.put("retention", "temporary");
    tempSession.put("metadata", meta);

    String resolved = resolveAssistantTitle(tempSession);
    assertEquals("Quantum Computing Query", resolved);
  }

  @Test
  public void testEmptyTemporarySessionFallsBackToAiAssistant() throws Exception {
    JSONObject emptySession = new JSONObject();
    emptySession.put("id", "temp-empty");
    emptySession.put("saved", false);
    emptySession.put("title", "");
    JSONObject meta = new JSONObject();
    meta.put("title", "   ");
    meta.put("retention", "temporary");
    emptySession.put("metadata", meta);

    String resolved = resolveAssistantTitle(emptySession);
    assertEquals("AI 助手", resolved);

    assertEquals("AI 助手", resolveAssistantTitle(null));
  }

  @Test
  public void testSavedSessionTitleResolution() throws Exception {
    JSONObject savedSession = new JSONObject();
    savedSession.put("id", "saved-456");
    savedSession.put("saved", true);
    savedSession.put("title", "Saved Biology Vocab");
    JSONObject meta = new JSONObject();
    meta.put("title", "Saved Biology Vocab");
    meta.put("retention", "saved");
    savedSession.put("metadata", meta);

    assertEquals("Saved Biology Vocab", resolveAssistantTitle(savedSession));
  }

  @Test
  public void testDateFilterPresetCalculations() {
    LocalDate today = LocalDate.now();

    // "今天" preset
    String todayStart = today.toString();
    String todayEnd = today.toString();
    assertEquals(todayStart, todayEnd);
    assertTrue(SessionDates.validDate(todayStart));

    // "近7天" preset (7 inclusive calendar days: today - 6 through today)
    LocalDate sevenDaysStart = today.minusDays(6);
    assertEquals(6, ChronoUnit.DAYS.between(sevenDaysStart, today));
    assertTrue(SessionDates.validDate(sevenDaysStart.toString()));
    assertTrue(sevenDaysStart.toString().compareTo(todayEnd) <= 0);

    // "近30天" preset (30 inclusive calendar days: today - 29 through today)
    LocalDate thirtyDaysStart = today.minusDays(29);
    assertEquals(29, ChronoUnit.DAYS.between(thirtyDaysStart, today));
    assertTrue(SessionDates.validDate(thirtyDaysStart.toString()));
    assertTrue(thirtyDaysStart.toString().compareTo(todayEnd) <= 0);
  }

  @Test
  public void testDateFilterMatchingWithPresets() throws Exception {
    ZoneId zone = ZoneId.systemDefault();
    LocalDate today = LocalDate.now();

    JSONObject todayItem =
        new JSONObject()
            .put("created_at", today.atTime(12, 0).atZone(zone).toInstant().toString());
    JSONObject fiveDaysAgoItem =
        new JSONObject()
            .put("created_at", today.minusDays(5).atTime(10, 0).atZone(zone).toInstant().toString());
    JSONObject twentyDaysAgoItem =
        new JSONObject()
            .put("created_at", today.minusDays(20).atTime(8, 0).atZone(zone).toInstant().toString());
    JSONObject fortyDaysAgoItem =
        new JSONObject()
            .put("created_at", today.minusDays(40).atTime(14, 0).atZone(zone).toInstant().toString());

    // Filter by "今天"
    assertTrue(SessionDates.matches(todayItem, "created_at", today.toString(), today.toString(), zone));
    assertFalse(SessionDates.matches(fiveDaysAgoItem, "created_at", today.toString(), today.toString(), zone));

    // Filter by "近7天"
    String sevenDaysStart = today.minusDays(6).toString();
    assertTrue(SessionDates.matches(todayItem, "created_at", sevenDaysStart, today.toString(), zone));
    assertTrue(SessionDates.matches(fiveDaysAgoItem, "created_at", sevenDaysStart, today.toString(), zone));
    assertFalse(SessionDates.matches(twentyDaysAgoItem, "created_at", sevenDaysStart, today.toString(), zone));

    // Filter by "近30天"
    String thirtyDaysStart = today.minusDays(29).toString();
    assertTrue(SessionDates.matches(todayItem, "created_at", thirtyDaysStart, today.toString(), zone));
    assertTrue(SessionDates.matches(fiveDaysAgoItem, "created_at", thirtyDaysStart, today.toString(), zone));
    assertTrue(SessionDates.matches(twentyDaysAgoItem, "created_at", thirtyDaysStart, today.toString(), zone));
    assertFalse(SessionDates.matches(fortyDaysAgoItem, "created_at", thirtyDaysStart, today.toString(), zone));
  }
}
