package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Comprehensive 4-Tier Opaque-Box Test Suite for Date Filtering and Quick Presets.
 *
 * Covers:
 * - Feature 9: Friendly date filtering with quick presets ("今天", "近7天", "近30天"),
 *   custom DatePickerDialog range validation, neutral "清除" button, and SessionDates matching engine.
 */
public class DateFilterTest {

  private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
  private static final ZoneId UTC = ZoneId.of("UTC");

  /**
   * Models the date preset generator from QueryUi.java:858-895.
   */
  public static class DatePreset {
    public final String start;
    public final String end;

    public DatePreset(String start, String end) {
      this.start = start;
      this.end = end;
    }

    public static DatePreset today(LocalDate anchor) {
      String date = anchor.toString();
      return new DatePreset(date, date);
    }

    public static DatePreset last7Days(LocalDate anchor) {
      return new DatePreset(anchor.minusDays(6).toString(), anchor.toString());
    }

    public static DatePreset last30Days(LocalDate anchor) {
      return new DatePreset(anchor.minusDays(29).toString(), anchor.toString());
    }
  }

  /**
   * Models the DateFilter dialog validation logic from QueryUi.java:500-506.
   */
  private boolean validateDateRange(String from, String to) {
    if (!from.isEmpty() && !SessionDates.validDate(from)) return false;
    if (!to.isEmpty() && !SessionDates.validDate(to)) return false;
    if (!from.isEmpty() && !to.isEmpty() && from.compareTo(to) > 0) return false;
    return true;
  }

  private JSONObject createSession(String id, String title, String createdAt, String lastUsedAt)
      throws Exception {
    return new JSONObject()
        .put("id", id)
        .put("title", title)
        .put("created_at", createdAt)
        .put("metadata", new JSONObject()
            .put("title", title)
            .put("created_at", createdAt)
            .put("last_used_at", lastUsedAt));
  }

  // =========================================================================
  // Tier 1: Feature Coverage (Representative Inputs)
  // =========================================================================

  @Test
  public void tier1_presetCalculationsSpanExactInclusiveDays() {
    LocalDate anchor = LocalDate.of(2026, 11, 8);

    // "今天": exactly 1 day
    DatePreset today = DatePreset.today(anchor);
    assertEquals("2026-11-08", today.start);
    assertEquals("2026-11-08", today.end);
    assertEquals(1, ChronoUnit.DAYS.between(LocalDate.parse(today.start), LocalDate.parse(today.end)) + 1);

    // "近7天": exactly 7 inclusive days (anchor - 6 to anchor)
    DatePreset last7 = DatePreset.last7Days(anchor);
    assertEquals("2026-11-02", last7.start);
    assertEquals("2026-11-08", last7.end);
    assertEquals(7, ChronoUnit.DAYS.between(LocalDate.parse(last7.start), LocalDate.parse(last7.end)) + 1);

    // "近30天": exactly 30 inclusive days (anchor - 29 to anchor)
    DatePreset last30 = DatePreset.last30Days(anchor);
    assertEquals("2026-10-10", last30.start);
    assertEquals("2026-11-08", last30.end);
    assertEquals(30, ChronoUnit.DAYS.between(LocalDate.parse(last30.start), LocalDate.parse(last30.end)) + 1);
  }

  @Test
  public void tier1_validIsoDateFormatRecognition() {
    assertTrue(SessionDates.validDate("2026-11-08"));
    assertTrue(SessionDates.validDate("2026-01-01"));
    assertTrue(SessionDates.validDate("2026-12-31"));
  }

  @Test
  public void tier1_sessionDatesMatchingWithinRange() throws Exception {
    JSONObject session = createSession("s1", "Test", "2026-11-05T08:00:00Z", "2026-11-05T08:00:00Z");

    assertTrue(
        "Session on Nov 5 matches range Nov 2 to Nov 8",
        SessionDates.matches(session, "last_used_at", "2026-11-02", "2026-11-08", UTC));
    assertFalse(
        "Session on Nov 5 does NOT match range Nov 6 to Nov 8",
        SessionDates.matches(session, "last_used_at", "2026-11-06", "2026-11-08", UTC));
  }

  @Test
  public void tier1_clearFilterMatchesAllSessions() throws Exception {
    JSONObject session = createSession("s1", "Test", "2026-11-05T08:00:00Z", "2026-11-05T08:00:00Z");
    assertTrue(
        "Empty start and empty end (cleared filter) must match all sessions",
        SessionDates.matches(session, "last_used_at", "", "", UTC));
  }

  // =========================================================================
  // Tier 2: Boundary & Corner Cases (Adversarial Probing)
  // =========================================================================

  @Test
  public void tier2_leapYearValidation() {
    // 2024 is a leap year -> Feb 29 is valid
    assertTrue("2024-02-29 must be valid", SessionDates.validDate("2024-02-29"));
    // 2026 is NOT a leap year -> Feb 29 is invalid
    assertFalse("2026-02-29 must be invalid", SessionDates.validDate("2026-02-29"));
  }

  @Test
  public void tier2_invertedDateRangeIsRejected() {
    assertFalse(
        "Inverted range (start > end) must be rejected by validator",
        validateDateRange("2026-11-08", "2026-11-01"));
    assertTrue(
        "Equal start and end (single day) must be valid",
        validateDateRange("2026-11-08", "2026-11-08"));
    assertTrue(
        "Valid range (start < end) must be accepted",
        validateDateRange("2026-11-01", "2026-11-08"));
  }

  @Test
  public void tier2_openEndedOneSidedRangesAreSupported() throws Exception {
    JSONObject session = createSession("s1", "Test", "2026-11-05T12:00:00Z", "2026-11-05T12:00:00Z");

    // Only start specified (from Nov 1 onwards)
    assertTrue(SessionDates.matches(session, "last_used_at", "2026-11-01", "", UTC));
    assertFalse(SessionDates.matches(session, "last_used_at", "2026-11-10", "", UTC));

    // Only end specified (up to Nov 10)
    assertTrue(SessionDates.matches(session, "last_used_at", "", "2026-11-10", UTC));
    assertFalse(SessionDates.matches(session, "last_used_at", "", "2026-11-01", UTC));
  }

  @Test
  public void tier2_malformedDateFormatsFailSafely() {
    assertFalse(SessionDates.validDate("2026/11/08"));
    assertFalse(SessionDates.validDate("2026-1-1"));
    assertFalse(SessionDates.validDate("2026-11-32"));
    assertFalse(SessionDates.validDate("2026-13-01"));
    assertFalse(SessionDates.validDate(""));
    assertFalse(SessionDates.validDate(null));
    assertFalse(SessionDates.validDate("yesterday"));
  }

  // =========================================================================
  // Tier 3: Cross-Feature Combinations
  // =========================================================================

  @Test
  public void tier3_fieldSelectionCreatedVsLastUsedAt() throws Exception {
    // Created on Nov 1, last used on Nov 8
    JSONObject session = createSession("s1", "Title", "2026-11-01T10:00:00Z", "2026-11-08T10:00:00Z");

    // Filter by created_at
    assertTrue(SessionDates.matches(session, "created_at", "2026-11-01", "2026-11-01", UTC));
    assertFalse(SessionDates.matches(session, "created_at", "2026-11-08", "2026-11-08", UTC));

    // Filter by last_used_at
    assertTrue(SessionDates.matches(session, "last_used_at", "2026-11-08", "2026-11-08", UTC));
    assertFalse(SessionDates.matches(session, "last_used_at", "2026-11-01", "2026-11-01", UTC));
  }

  @Test
  public void tier3_timezoneAwarenessAcrossUtcMidnight() throws Exception {
    // 2026-10-27 23:00 UTC = 2026-10-28 07:00 in Shanghai (UTC+8)
    JSONObject session = createSession("s1", "Title", "2026-10-27T23:00:00Z", "2026-10-27T23:00:00Z");

    // In Shanghai timezone (+08:00): local day is 2026-10-28
    assertTrue(SessionDates.matches(session, "last_used_at", "2026-10-28", "2026-10-28", SHANGHAI));
    assertFalse(SessionDates.matches(session, "last_used_at", "2026-10-27", "2026-10-27", SHANGHAI));

    // In UTC timezone: local day is 2026-10-27
    assertTrue(SessionDates.matches(session, "last_used_at", "2026-10-27", "2026-10-27", UTC));
    assertFalse(SessionDates.matches(session, "last_used_at", "2026-10-28", "2026-10-28", UTC));
  }

  // =========================================================================
  // Tier 4: Real-World Application Scenarios
  // =========================================================================

  @Test
  public void tier4_endToEndDateFilterWorkflowAcrossMultiDaySessions() throws Exception {
    LocalDate anchor = LocalDate.of(2026, 11, 8);
    List<JSONObject> sessions = new ArrayList<>();

    sessions.add(createSession("s_today", "Today Session", "2026-11-08T03:00:00Z", "2026-11-08T03:00:00Z"));
    sessions.add(createSession("s_3d", "3 Days Ago Session", "2026-11-05T03:00:00Z", "2026-11-05T03:00:00Z"));
    sessions.add(createSession("s_10d", "10 Days Ago Session", "2026-10-29T03:00:00Z", "2026-10-29T03:00:00Z"));
    sessions.add(createSession("s_40d", "40 Days Ago Session", "2026-09-29T03:00:00Z", "2026-09-29T03:00:00Z"));

    // Scenario A: User clicks "今天" preset
    DatePreset presetToday = DatePreset.today(anchor);
    List<JSONObject> filteredToday = new ArrayList<>();
    for (JSONObject s : sessions) {
      if (SessionDates.matches(s, "last_used_at", presetToday.start, presetToday.end, UTC)) {
        filteredToday.add(s);
      }
    }
    assertEquals(1, filteredToday.size());
    assertEquals("s_today", filteredToday.get(0).getString("id"));

    // Scenario B: User clicks "近7天" preset
    DatePreset preset7 = DatePreset.last7Days(anchor);
    List<JSONObject> filtered7 = new ArrayList<>();
    for (JSONObject s : sessions) {
      if (SessionDates.matches(s, "last_used_at", preset7.start, preset7.end, UTC)) {
        filtered7.add(s);
      }
    }
    assertEquals(2, filtered7.size());
    assertEquals("s_today", filtered7.get(0).getString("id"));
    assertEquals("s_3d", filtered7.get(1).getString("id"));

    // Scenario C: User clicks "近30天" preset
    DatePreset preset30 = DatePreset.last30Days(anchor);
    List<JSONObject> filtered30 = new ArrayList<>();
    for (JSONObject s : sessions) {
      if (SessionDates.matches(s, "last_used_at", preset30.start, preset30.end, UTC)) {
        filtered30.add(s);
      }
    }
    assertEquals(3, filtered30.size());

    // Scenario D: User clicks neutral button "清除" (clears date range)
    List<JSONObject> cleared = new ArrayList<>();
    for (JSONObject s : sessions) {
      if (SessionDates.matches(s, "last_used_at", "", "", UTC)) {
        cleared.add(s);
      }
    }
    assertEquals(4, cleared.size()); // all sessions returned
  }
}
