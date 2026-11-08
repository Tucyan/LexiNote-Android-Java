package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Empirical Challenger Test Suite for Milestone 1.
 *
 * Verifies and stress-tests:
 * 1. Ionicons.ttf font file TrueType cmap & post tables against IconGlyphs mappings.
 * 2. Date presets and boundary stress (leap years, month boundaries, rollovers, exceptions).
 * 3. Session titles (empty, null, whitespace-only, 100+ chars, special characters, rename bounds).
 */
public class Challenger1VerificationTest {

  private static byte[] ttfBytes;

  @BeforeClass
  public static void loadFont() throws IOException {
    File[] candidates = new File[] {
      new File("app/src/main/assets/fonts/Ionicons.ttf"),
      new File("src/main/assets/fonts/Ionicons.ttf"),
      new File("../app/src/main/assets/fonts/Ionicons.ttf")
    };
    File fontFile = null;
    for (File f : candidates) {
      if (f.exists() && f.isFile()) {
        fontFile = f;
        break;
      }
    }
    assertNotNull("Ionicons.ttf must be located in assets", fontFile);
    assertTrue("Ionicons.ttf must be readable", fontFile.canRead());

    ttfBytes = new byte[(int) fontFile.length()];
    try (FileInputStream fis = new FileInputStream(fontFile)) {
      int read = 0;
      while (read < ttfBytes.length) {
        int r = fis.read(ttfBytes, read, ttfBytes.length - read);
        if (r < 0) break;
        read += r;
      }
    }
  }

  // =========================================================================
  // TTF Parser Helper Classes for Empirical TrueType Verification
  // =========================================================================

  private static class TrueTypeFont {
    final byte[] data;
    final Map<String, int[]> tables = new HashMap<>(); // tag -> [offset, length]

    TrueTypeFont(byte[] data) {
      this.data = data;
      int numTables = readUShort(4);
      for (int i = 0; i < numTables; i++) {
        int record = 12 + i * 16;
        String tag = new String(data, record, 4, StandardCharsets.US_ASCII);
        int offset = readInt(record + 8);
        int length = readInt(record + 12);
        tables.put(tag, new int[] {offset, length});
      }
    }

    int readUShort(int offset) {
      return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    short readShort(int offset) {
      return (short) readUShort(offset);
    }

    int readInt(int offset) {
      return ((data[offset] & 0xFF) << 24)
          | ((data[offset + 1] & 0xFF) << 16)
          | ((data[offset + 2] & 0xFF) << 8)
          | (data[offset + 3] & 0xFF);
    }

    int getGlyphId(int codepoint) {
      int[] cmapInfo = tables.get("cmap");
      assertNotNull("cmap table must exist in font", cmapInfo);
      int cmapOffset = cmapInfo[0];
      int numSubtables = readUShort(cmapOffset + 2);

      int subtableOffset = -1;
      for (int i = 0; i < numSubtables; i++) {
        int rec = cmapOffset + 4 + i * 8;
        int subOffset = cmapOffset + readInt(rec + 4);
        int format = readUShort(subOffset);
        if (format == 4) {
          subtableOffset = subOffset;
          break;
        }
      }
      assertTrue("Format 4 subtable must exist in cmap", subtableOffset > 0);

      int segCountX2 = readUShort(subtableOffset + 6);
      int segCount = segCountX2 / 2;
      int endCodeOffset = subtableOffset + 14;
      int startCodeOffset = endCodeOffset + 2 * segCount + 2; // skip reservedPad
      int idDeltaOffset = startCodeOffset + 2 * segCount;
      int idRangeOffsetOffset = idDeltaOffset + 2 * segCount;

      for (int s = 0; s < segCount; s++) {
        int endCode = readUShort(endCodeOffset + s * 2);
        if (codepoint <= endCode) {
          int startCode = readUShort(startCodeOffset + s * 2);
          if (codepoint < startCode) {
            return 0; // Not covered
          }
          int idRangeOffset = readUShort(idRangeOffsetOffset + s * 2);
          if (idRangeOffset == 0) {
            return (codepoint + readShort(idDeltaOffset + s * 2)) & 0xFFFF;
          } else {
            int glyphIdAddr = (idRangeOffsetOffset + s * 2) + idRangeOffset + 2 * (codepoint - startCode);
            int glyphId = readUShort(glyphIdAddr);
            if (glyphId != 0) {
              return (glyphId + readShort(idDeltaOffset + s * 2)) & 0xFFFF;
            }
            return 0;
          }
        }
      }
      return 0;
    }

    String getGlyphName(int glyphId) {
      int[] postInfo = tables.get("post");
      assertNotNull("post table must exist in font", postInfo);
      int postOffset = postInfo[0];
      int format = readInt(postOffset);
      assertEquals("post table must be format 2.0 (0x00020000)", 0x00020000, format);

      int numGlyphs = readUShort(postOffset + 32);
      assertTrue("glyphId must be within range", glyphId >= 0 && glyphId < numGlyphs);

      int glyphNameIndex = readUShort(postOffset + 34 + glyphId * 2);
      if (glyphNameIndex < 258) {
        return ".notdef_or_standard_" + glyphNameIndex;
      }

      int targetPascalIndex = glyphNameIndex - 258;
      int currentOffset = postOffset + 34 + numGlyphs * 2;
      int postEnd = postOffset + postInfo[1];

      int currentIndex = 0;
      while (currentOffset < postEnd) {
        int len = data[currentOffset] & 0xFF;
        if (currentIndex == targetPascalIndex) {
          return new String(data, currentOffset + 1, len, StandardCharsets.US_ASCII);
        }
        currentOffset += 1 + len;
        currentIndex++;
      }
      return null;
    }
  }

  // =========================================================================
  // Challenge 1: IconGlyphs vs Ionicons.ttf Empirical Font Verification
  // =========================================================================

  @Test
  public void challenge1_verifyCloseCircleGlyphInFont() {
    TrueTypeFont font = new TrueTypeFont(ttfBytes);

    // Verify 62027 (0xF24B) -> "close-circle"
    int glyphId = font.getGlyphId(62027);
    assertTrue("Glyph ID for 62027 must be > 0", glyphId > 0);
    String glyphName = font.getGlyphName(glyphId);
    assertEquals("Glyph name in Ionicons.ttf for 62027 must be 'close-circle'", "close-circle", glyphName);

    // Verify IconGlyphs mapping
    assertEquals(Integer.valueOf(62027), IconGlyphs.VALUES.get("close-circle"));
  }

  @Test
  public void challenge1_verifyCloseCircleOutlineGlyphInFont() {
    TrueTypeFont font = new TrueTypeFont(ttfBytes);

    // Verify 62028 (0xF24C) -> "close-circle-outline"
    int glyphId = font.getGlyphId(62028);
    assertTrue("Glyph ID for 62028 must be > 0", glyphId > 0);
    String glyphName = font.getGlyphName(glyphId);
    assertEquals(
        "Glyph name in Ionicons.ttf for 62028 must be 'close-circle-outline'",
        "close-circle-outline",
        glyphName);

    // Verify IconGlyphs mapping
    assertEquals(Integer.valueOf(62028), IconGlyphs.VALUES.get("close-circle-outline"));
  }

  @Test
  public void challenge1_verifyCloseOutlineGlyphInFont() {
    TrueTypeFont font = new TrueTypeFont(ttfBytes);

    // Verify 62030 (0xF24E) -> "close-outline"
    int glyphId = font.getGlyphId(62030);
    assertTrue("Glyph ID for 62030 must be > 0", glyphId > 0);
    String glyphName = font.getGlyphName(glyphId);
    assertEquals("Glyph name in Ionicons.ttf for 62030 must be 'close-outline'", "close-outline", glyphName);

    // Verify IconGlyphs mapping matches 62030
    assertEquals(Integer.valueOf(62030), IconGlyphs.VALUES.get("close-outline"));

    // Verify that legacy 62065 was NOT close-outline (it was code-working!)
    int legacyGlyphId = font.getGlyphId(62065);
    String legacyGlyphName = font.getGlyphName(legacyGlyphId);
    assertEquals(
        "Codepoint 62065 in Ionicons.ttf was indeed 'code-working', proving the legacy bug",
        "code-working",
        legacyGlyphName);
    assertNotEquals("close-outline must NOT map to 62065", Integer.valueOf(62065), IconGlyphs.VALUES.get("close-outline"));
  }

  // =========================================================================
  // Challenge 2: Date Presets & Boundary Stress Testing
  // =========================================================================

  @Test
  public void challenge2_leapYearDateValidationAndPresets() {
    // 2024 is a leap year (divisible by 4, not 100)
    assertTrue("2024-02-29 is a valid leap year date", SessionDates.validDate("2024-02-29"));
    // 2026 is NOT a leap year
    assertFalse("2026-02-29 is invalid in non-leap year", SessionDates.validDate("2026-02-29"));
    // Century leap year (divisible by 400)
    assertTrue("2000-02-29 is valid century leap year", SessionDates.validDate("2000-02-29"));
    // Century non-leap year (divisible by 100, not 400)
    assertFalse("1900-02-29 is invalid century non-leap year", SessionDates.validDate("1900-02-29"));
    assertFalse("2100-02-29 is invalid century non-leap year", SessionDates.validDate("2100-02-29"));

    // Presets generated on leap day: 2024-02-29
    LocalDate leapDay = LocalDate.of(2024, 2, 29);
    // "今天"
    assertEquals("2024-02-29", leapDay.toString());
    // "近7天" on leap day -> 2024-02-23 to 2024-02-29 (7 days)
    LocalDate sevenDays = leapDay.minusDays(6);
    assertEquals("2024-02-23", sevenDays.toString());
    assertEquals(6, ChronoUnit.DAYS.between(sevenDays, leapDay));
    // "近30天" on leap day -> 2024-01-31 to 2024-02-29 (30 days)
    LocalDate thirtyDays = leapDay.minusDays(29);
    assertEquals("2024-01-31", thirtyDays.toString());
    assertEquals(29, ChronoUnit.DAYS.between(thirtyDays, leapDay));
  }

  @Test
  public void challenge2_monthBoundaryTransitions() {
    // Scenario 1: March 1st in a non-leap year (2026-03-01)
    LocalDate march1NonLeap = LocalDate.of(2026, 3, 1);
    LocalDate sevenDaysBackNonLeap = march1NonLeap.minusDays(6);
    assertEquals("In 2026 (non-leap), 6 days before March 1 is Feb 23", "2026-02-23", sevenDaysBackNonLeap.toString());
    assertEquals(7, ChronoUnit.DAYS.between(sevenDaysBackNonLeap, march1NonLeap) + 1);

    LocalDate thirtyDaysBackNonLeap = march1NonLeap.minusDays(29);
    // 2026 Feb has 28 days -> 29 days back lands on Jan 31
    assertEquals("In 2026, 29 days before March 1 is Jan 31", "2026-01-31", thirtyDaysBackNonLeap.toString());
    assertEquals(30, ChronoUnit.DAYS.between(thirtyDaysBackNonLeap, march1NonLeap) + 1);

    // Scenario 2: March 1st in a leap year (2024-03-01)
    LocalDate march1Leap = LocalDate.of(2024, 3, 1);
    LocalDate sevenDaysBackLeap = march1Leap.minusDays(6);
    // 2024 Feb has 29 days -> 6 days before March 1 is Feb 24
    assertEquals("In 2024 (leap), 6 days before March 1 is Feb 24", "2024-02-24", sevenDaysBackLeap.toString());
    assertEquals(7, ChronoUnit.DAYS.between(sevenDaysBackLeap, march1Leap) + 1);

    LocalDate thirtyDaysBackLeap = march1Leap.minusDays(29);
    // 2024 Feb has 29 days -> 29 days back lands on Feb 1
    assertEquals("In 2024, 29 days before March 1 is Feb 01", "2024-02-01", thirtyDaysBackLeap.toString());
    assertEquals(30, ChronoUnit.DAYS.between(thirtyDaysBackLeap, march1Leap) + 1);

    // Scenario 3: May 1st (April has 30 days)
    LocalDate may1 = LocalDate.of(2026, 5, 1);
    LocalDate sevenDaysMay = may1.minusDays(6);
    assertEquals("2026-04-25", sevenDaysMay.toString());
  }

  @Test
  public void challenge2_endOfYearRollover() throws Exception {
    LocalDate newYear = LocalDate.of(2026, 1, 1);

    // "近7天" across year boundary
    LocalDate sevenDaysBack = newYear.minusDays(6);
    assertEquals("2025-12-26", sevenDaysBack.toString());
    assertEquals(7, ChronoUnit.DAYS.between(sevenDaysBack, newYear) + 1);

    // "近30天" across year boundary
    LocalDate thirtyDaysBack = newYear.minusDays(29);
    assertEquals("2025-12-03", thirtyDaysBack.toString());
    assertEquals(30, ChronoUnit.DAYS.between(thirtyDaysBack, newYear) + 1);

    // Test session matching across year rollover
    ZoneId utc = ZoneId.of("UTC");
    JSONObject newYearSession = new JSONObject().put("created_at", "2026-01-01T02:00:00Z");
    JSONObject newYearEveSession = new JSONObject().put("created_at", "2025-12-31T23:00:00Z");
    JSONObject christmasSession = new JSONObject().put("created_at", "2025-12-25T12:00:00Z");

    // Filter range: 2025-12-26 to 2026-01-01
    String start = sevenDaysBack.toString();
    String end = newYear.toString();

    assertTrue("New Year session matches range", SessionDates.matches(newYearSession, "created_at", start, end, utc));
    assertTrue("New Year's Eve session matches range", SessionDates.matches(newYearEveSession, "created_at", start, end, utc));
    assertFalse("Christmas session is before range start", SessionDates.matches(christmasSession, "created_at", start, end, utc));
  }

  @Test
  public void challenge2_dateParsingExceptionsAndMalformedInputs() throws Exception {
    // Malformed date strings must fail validation gracefully
    assertFalse(SessionDates.validDate(null));
    assertFalse(SessionDates.validDate(""));
    assertFalse(SessionDates.validDate("   "));
    assertFalse(SessionDates.validDate("2026-02-30")); // Feb 30 does not exist
    assertFalse(SessionDates.validDate("2026-04-31")); // April 31 does not exist
    assertFalse(SessionDates.validDate("2026-13-01")); // Month 13
    assertFalse(SessionDates.validDate("2026-00-01")); // Month 00
    assertFalse(SessionDates.validDate("2026-01-00")); // Day 00
    assertFalse(SessionDates.validDate("2026/01/01")); // Slashes instead of hyphens
    assertFalse(SessionDates.validDate("2026-1-1")); // Unpadded
    assertFalse(SessionDates.validDate("2026-01-01T00:00:00Z")); // Full ISO timestamp
    assertFalse(SessionDates.validDate("yesterday"));

    // Session with malformed created_at / last_used_at
    JSONObject corruptSession = new JSONObject().put("created_at", "not-a-valid-timestamp");
    assertNull(SessionDates.timestamp(corruptSession, "created_at"));
    assertFalse(SessionDates.matches(corruptSession, "created_at", "2026-01-01", "2026-12-31", ZoneId.of("UTC")));

    // Inverted ranges (start > end) must evaluate safely
    JSONObject validSession = new JSONObject().put("created_at", "2026-06-15T12:00:00Z");
    // When start > end, matches returns false because no date can be >= Nov 8 and <= Nov 1
    assertFalse(SessionDates.matches(validSession, "created_at", "2026-11-08", "2026-11-01", ZoneId.of("UTC")));
  }

  // =========================================================================
  // Challenge 3: Session Titles Stress Testing (Null, Whitespace, 100+ chars)
  // =========================================================================

  private String resolveAssistantHeaderTitle(JSONObject session) {
    String sessionTitle = session != null ? extractTitle(session) : null;
    return (sessionTitle != null && !sessionTitle.trim().isEmpty()) ? sessionTitle : "AI 助手";
  }

  private String extractTitle(JSONObject s) {
    if (s == null) return null;
    JSONObject meta = s.optJSONObject("metadata");
    return meta == null
        ? s.optString("title", s.optString("input_text", ""))
        : meta.optString("title", "");
  }

  @Test
  public void challenge3_nullAndEmptySessionTitleHandling() throws Exception {
    // 1. null session
    assertEquals("AI 助手", resolveAssistantHeaderTitle(null));

    // 2. empty JSONObject
    assertEquals("AI 助手", resolveAssistantHeaderTitle(new JSONObject()));

    // 3. title is empty string
    JSONObject emptyTitle = new JSONObject().put("title", "");
    assertEquals("AI 助手", resolveAssistantHeaderTitle(emptyTitle));

    // 4. metadata title is empty string
    JSONObject metaEmpty = new JSONObject().put("metadata", new JSONObject().put("title", ""));
    assertEquals("AI 助手", resolveAssistantHeaderTitle(metaEmpty));

    // 5. title is whitespace-only (spaces, tabs, newlines)
    JSONObject wsSession = new JSONObject().put("title", "   \t\r\n   ");
    assertEquals("Whitespace title must fall back to 'AI 助手'", "AI 助手", resolveAssistantHeaderTitle(wsSession));

    JSONObject wsMetaSession = new JSONObject()
        .put("metadata", new JSONObject().put("title", "  \n  "));
    assertEquals("Whitespace metadata title must fall back to 'AI 助手'", "AI 助手", resolveAssistantHeaderTitle(wsMetaSession));
  }

  @Test
  public void challenge3_fallbackToInputTextWhenTitleAbsent() throws Exception {
    // If title is absent, falls back to input_text
    JSONObject inputOnly = new JSONObject().put("input_text", "What causes aurora borealis?");
    assertEquals("What causes aurora borealis?", resolveAssistantHeaderTitle(inputOnly));

    // If input_text is also whitespace, falls back to AI 助手
    JSONObject wsInput = new JSONObject().put("input_text", "    ");
    assertEquals("AI 助手", resolveAssistantHeaderTitle(wsInput));
  }

  @Test
  public void challenge3_veryLongTitlesStressTest() throws Exception {
    // Construct 100+ character title (e.g. 150 chars)
    StringBuilder sb150 = new StringBuilder();
    for (int i = 0; i < 15; i++) {
      sb150.append("TitleWord").append(i).append(" ");
    }
    String longTitle = sb150.toString().trim();
    assertTrue("Title length must be >= 100", longTitle.length() >= 100);

    JSONObject longSession = new JSONObject()
        .put("title", longTitle)
        .put("metadata", new JSONObject().put("title", longTitle));

    String resolved = resolveAssistantHeaderTitle(longSession);
    assertEquals("Resolved title must match the full session title", longTitle, resolved);

    // Verify manual rename capping contract (Math.min(40, t.length())) from QueryUi.java:2001
    String t = longTitle.trim();
    String bounded = t.substring(0, Math.min(40, t.length()));
    assertEquals(40, bounded.length());
    assertEquals(longTitle.substring(0, 40), bounded);

    // Construct 1000 character extreme stress title
    StringBuilder sb1000 = new StringBuilder();
    while (sb1000.length() < 1000) {
      sb1000.append("ExtremeLongTitleQuery_");
    }
    String extremeTitle = sb1000.toString();
    JSONObject extremeSession = new JSONObject().put("title", extremeTitle);
    String extremeResolved = resolveAssistantHeaderTitle(extremeSession);
    assertEquals(extremeTitle, extremeResolved);
    assertEquals(40, extremeResolved.substring(0, Math.min(40, extremeResolved.length())).length());
  }

  @Test
  public void challenge3_specialCharactersAndEmojisInTitle() throws Exception {
    // Emojis, math symbols, and multilingual text
    String complexTitle = "⚛️ Quantum Physics & 🧬 Genetics · 2026 【高频词汇】";
    JSONObject session = new JSONObject().put("title", complexTitle);
    assertEquals(complexTitle, resolveAssistantHeaderTitle(session));

    // Potential injection strings (XSS / SQL-like)
    String injectionTitle = "<script>alert('hack')</script>'; DROP TABLE sessions; --";
    JSONObject xssSession = new JSONObject().put("title", injectionTitle);
    assertEquals(injectionTitle, resolveAssistantHeaderTitle(xssSession));
  }
}
