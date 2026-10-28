package com.lexinote.nativeapp;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import org.json.JSONObject;

/** Timestamp parsing, local-day filtering and display share the same timezone. */
final class SessionDates {
  private static final DateTimeFormatter DISPLAY =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm XXX", Locale.ROOT);
  private SessionDates() {}

  static Instant timestamp(JSONObject item, String field) {
    JSONObject metadata = item.optJSONObject("metadata");
    String fallback = field.equals("last_used_at") ? "updated_at" : "created_at";
    String[] candidates = {metadata == null ? "" : metadata.optString(field),
        item.optString(fallback), item.optString("created_at")};
    for (String value : candidates) {
      try { return Instant.parse(value); } catch (DateTimeParseException ignored) { }
    }
    return null;
  }

  static String display(JSONObject item, String field, ZoneId zone) {
    Instant instant = timestamp(item, field);
    return instant == null ? "—" : DISPLAY.format(instant.atZone(zone));
  }

  static boolean validDate(String value) {
    if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
    try { LocalDate.parse(value); return true; }
    catch (DateTimeParseException ignored) { return false; }
  }

  static boolean matches(JSONObject item, String field, String start, String end, ZoneId zone) {
    if (start.isEmpty() && end.isEmpty()) return true;
    if (!start.isEmpty() && !validDate(start) || !end.isEmpty() && !validDate(end)) return false;
    Instant instant = timestamp(item, field);
    if (instant == null) return false;
    LocalDate day = instant.atZone(zone).toLocalDate();
    return (start.isEmpty() || !day.isBefore(LocalDate.parse(start)))
        && (end.isEmpty() || !day.isAfter(LocalDate.parse(end)));
  }
}
