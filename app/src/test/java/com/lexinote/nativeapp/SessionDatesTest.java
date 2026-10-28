package com.lexinote.nativeapp;
import java.time.ZoneId;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class SessionDatesTest {
  JSONObject item(String timestamp) throws Exception {
    return new JSONObject().put("metadata", new JSONObject().put("last_used_at", timestamp));
  }
  @Test public void localDayAndDisplayedTimeAgreeAcrossUtcMidnight() throws Exception {
    JSONObject s=item("2026-10-27T23:00:00Z"); ZoneId sg=ZoneId.of("Asia/Singapore");
    assertTrue(SessionDates.matches(s,"last_used_at","2026-10-28","2026-10-28",sg));
    assertFalse(SessionDates.matches(s,"last_used_at","2026-10-27","2026-10-27",sg));
    assertEquals("2026-10-28 07:00 +08:00",SessionDates.display(s,"last_used_at",sg));
  }
  @Test public void localDaysIncludeDstTransitionAndEndOfDay() throws Exception {
    ZoneId ny=ZoneId.of("America/New_York");
    assertTrue(SessionDates.matches(item("2026-11-02T04:59:59.999Z"),"last_used_at","2026-11-01","2026-11-01",ny));
    assertFalse(SessionDates.matches(item("2026-11-02T05:00:00Z"),"last_used_at","2026-11-01","2026-11-01",ny));
    assertTrue(SessionDates.matches(item("2026-10-28T07:00:00+08:00"),"last_used_at","2026-10-28","2026-10-28",ZoneId.of("Asia/Singapore")));
  }
  @Test public void invalidAndLegacyValuesFailSafely() throws Exception {
    ZoneId utc=ZoneId.of("UTC"); JSONObject s=item("bad").put("updated_at","2026-10-28T02:00:00Z");
    assertTrue(SessionDates.matches(s,"last_used_at","2026-10-28","",utc));
    s.remove("updated_at");
    assertFalse(SessionDates.matches(s,"last_used_at","2026-10-28","",utc));
    assertTrue(SessionDates.matches(s,"last_used_at","","",utc));
    assertEquals("—",SessionDates.display(s,"last_used_at",utc));
    assertTrue(SessionDates.validDate("2024-02-29"));
    assertFalse(SessionDates.validDate("2026-02-29"));
    assertFalse(SessionDates.validDate("2026-1-01"));
    assertFalse(SessionDates.matches(item("2026-10-28T02:00:00Z"),"last_used_at","2026-10-30","2026-10-28",utc));
  }
}
