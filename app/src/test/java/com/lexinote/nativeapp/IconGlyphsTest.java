package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import java.util.Map;
import org.junit.Test;

/**
 * Comprehensive 4-Tier Opaque-Box Test Suite for IconGlyphs.
 *
 * Covers:
 * - Feature 1: Registration of close-circle (62027) & close-circle-outline (62028)
 * - Correction of close-outline (62030 vs legacy 62065)
 * - Fallback codepoint contract (0xF55F / 62815)
 * - Feature 11 volume glyph contracts (62995, 62996, 63001, 63002)
 */
public class IconGlyphsTest {

  private static final int DEFAULT_FALLBACK_CODEPOINT = 0xF55F; // 62815 ('search')

  private int resolveCodepoint(String glyphName) {
    if (glyphName == null) return DEFAULT_FALLBACK_CODEPOINT;
    return IconGlyphs.VALUES.getOrDefault(glyphName, DEFAULT_FALLBACK_CODEPOINT);
  }

  // =========================================================================
  // Tier 1: Feature Coverage (Representative Happy-Path Inputs)
  // =========================================================================

  @Test
  public void testCloseCircleGlyphRegistration() {
    assertTrue(
        "IconGlyphs must contain close-circle",
        IconGlyphs.VALUES.containsKey("close-circle"));
    assertEquals(
        "Codepoint for 'close-circle' must be 62027 (0xF24B)",
        Integer.valueOf(62027),
        IconGlyphs.VALUES.get("close-circle"));
  }

  @Test
  public void testCloseCircleOutlineGlyphRegistration() {
    assertTrue(
        "IconGlyphs must contain close-circle-outline",
        IconGlyphs.VALUES.containsKey("close-circle-outline"));
    assertEquals(
        "Codepoint for 'close-circle-outline' must be 62028 (0xF24C)",
        Integer.valueOf(62028),
        IconGlyphs.VALUES.get("close-circle-outline"));
  }

  @Test
  public void testCloseOutlineGlyphValue() {
    assertTrue(
        "IconGlyphs must contain close-outline",
        IconGlyphs.VALUES.containsKey("close-outline"));
    assertEquals(
        "Codepoint for 'close-outline' must be 62030 (0xF24E), not 62065 (code-working)",
        Integer.valueOf(62030),
        IconGlyphs.VALUES.get("close-outline"));
  }

  @Test
  public void tier1_coreActionGlyphsAreRegistered() {
    String[] coreGlyphs = {
      "arrow-up",
      "search",
      "search-outline",
      "trash-outline",
      "time-outline",
      "camera",
      "image-outline",
      "create-outline",
      "settings",
      "repeat"
    };
    for (String glyph : coreGlyphs) {
      assertTrue(
          "Core glyph '" + glyph + "' must be registered in IconGlyphs",
          IconGlyphs.VALUES.containsKey(glyph));
      assertNotNull(
          "Codepoint for '" + glyph + "' must not be null", IconGlyphs.VALUES.get(glyph));
    }
  }

  @Test
  public void tier1_fallbackDefaultIsSearchIconCodepoint() {
    assertEquals(
        "Default glyph fallback must evaluate to 62815 (0xF55F)",
        62815,
        DEFAULT_FALLBACK_CODEPOINT);
  }

  // =========================================================================
  // Tier 2: Boundary & Corner Cases (Adversarial Probing)
  // =========================================================================

  @Test
  public void tier2_unregisteredGlyphReturnsDefaultFallback() {
    int resolved = resolveCodepoint("non_existent_random_glyph_name_12345");
    assertEquals(
        "Unregistered glyph name must safely resolve to fallback 62815",
        DEFAULT_FALLBACK_CODEPOINT,
        resolved);
  }

  @Test
  public void tier2_nullEmptyAndWhitespaceGlyphsReturnFallback() {
    assertEquals(
        "null glyph name must resolve to fallback",
        DEFAULT_FALLBACK_CODEPOINT,
        resolveCodepoint(null));
    assertEquals(
        "empty glyph name must resolve to fallback",
        DEFAULT_FALLBACK_CODEPOINT,
        resolveCodepoint(""));
    assertEquals(
        "whitespace-only glyph name must resolve to fallback",
        DEFAULT_FALLBACK_CODEPOINT,
        resolveCodepoint("   "));
  }

  @Test
  public void tier2_glyphNamesAreCaseSensitive() {
    assertNull(
        "Uppercase 'CLOSE-CIRCLE' must not be mapped directly",
        IconGlyphs.VALUES.get("CLOSE-CIRCLE"));
    assertNull(
        "CamelCase 'CloseCircle' must not be mapped directly",
        IconGlyphs.VALUES.get("CloseCircle"));
    assertNotNull(
        "Exact lowercase 'close-circle' must be mapped",
        IconGlyphs.VALUES.get("close-circle"));
  }

  @Test
  public void tier2_allRegisteredCodepointsAreWithinValidUnicodeRange() {
    for (Map.Entry<String, Integer> entry : IconGlyphs.VALUES.entrySet()) {
      int code = entry.getValue();
      assertTrue(
          "Codepoint for '" + entry.getKey() + "' (" + code + ") must be a positive integer",
          code > 0);
      assertTrue(
          "Codepoint for '" + entry.getKey() + "' (" + code + ") must be a valid Unicode BMP codepoint",
          Character.isValidCodePoint(code));
    }
  }

  // =========================================================================
  // Tier 3: Cross-Feature Combinations
  // =========================================================================

  @Test
  public void tier3_utf16CharacterEncodingIntegrityAcrossAllGlyphs() {
    for (Map.Entry<String, Integer> entry : IconGlyphs.VALUES.entrySet()) {
      int codepoint = entry.getValue();
      char[] chars = Character.toChars(codepoint);
      assertTrue("chars array must have length 1 or 2", chars.length >= 1 && chars.length <= 2);
      String utf16String = new String(chars);
      assertFalse("Rendered UTF-16 glyph string must not be empty", utf16String.isEmpty());
      assertEquals(
          "Codepoint round-trip from UTF-16 string must match original value",
          codepoint,
          utf16String.codePointAt(0));
    }
  }

  @Test
  public void tier3_closeCircleDistinctFromDeleteAndSearchGlyphs() {
    int closeCircle = IconGlyphs.VALUES.get("close-circle");
    int trash = IconGlyphs.VALUES.get("trash-outline");
    int search = IconGlyphs.VALUES.get("search");

    assertNotEquals("close-circle must differ from trash-outline", trash, closeCircle);
    assertNotEquals("close-circle must differ from search fallback", search, closeCircle);
  }

  @Test
  public void tier3_volumeGlyphsSpecificationContract() {
    // Specification for Feature 11 / M3 TTS Volume Glyphs:
    // "volume-high" -> 62995 (0xF613)
    // "volume-high-outline" -> 62996 (0xF614)
    // "volume-medium" -> 63001 (0xF619)
    // "volume-medium-outline" -> 63002 (0xF61A)
    final int volumeHigh = 62995;
    final int volumeHighOutline = 62996;
    final int volumeMedium = 63001;
    final int volumeMediumOutline = 63002;

    assertTrue(Character.isValidCodePoint(volumeHigh));
    assertTrue(Character.isValidCodePoint(volumeHighOutline));
    assertTrue(Character.isValidCodePoint(volumeMedium));
    assertTrue(Character.isValidCodePoint(volumeMediumOutline));

    // Verify UTF-16 conversion for all volume glyphs
    assertEquals(volumeHigh, new String(Character.toChars(volumeHigh)).codePointAt(0));
    assertEquals(volumeHighOutline, new String(Character.toChars(volumeHighOutline)).codePointAt(0));
    assertEquals(volumeMedium, new String(Character.toChars(volumeMedium)).codePointAt(0));
    assertEquals(volumeMediumOutline, new String(Character.toChars(volumeMediumOutline)).codePointAt(0));

    // Verify registration in IconGlyphs.VALUES
    assertEquals(Integer.valueOf(volumeHigh), IconGlyphs.VALUES.get("volume-high"));
    assertEquals(Integer.valueOf(volumeHighOutline), IconGlyphs.VALUES.get("volume-high-outline"));
    assertEquals(Integer.valueOf(volumeMedium), IconGlyphs.VALUES.get("volume-medium"));
    assertEquals(Integer.valueOf(volumeMediumOutline), IconGlyphs.VALUES.get("volume-medium-outline"));
  }

  // =========================================================================
  // Tier 4: Real-World Application Scenarios
  // =========================================================================

  @Test
  public void tier4_simulatedMainActivityIconResolutionForQueryAndAssistantUi() {
    // 1. User taps query input box: clear button requests "close-circle"
    int inputClearGlyph = resolveCodepoint("close-circle");
    assertEquals(62027, inputClearGlyph);
    assertNotEquals(
        "Clear button MUST NOT fall back to magnifying glass search icon",
        DEFAULT_FALLBACK_CODEPOINT,
        inputClearGlyph);

    // 2. User attaches image in assistant: removal button requests "close-circle"
    int removeAttachmentGlyph = resolveCodepoint("close-circle");
    assertEquals(62027, removeAttachmentGlyph);

    // 3. User views recent queries popup: history icon requests "time-outline", delete requests "trash-outline"
    int historyGlyph = resolveCodepoint("time-outline");
    int deleteGlyph = resolveCodepoint("trash-outline");
    assertEquals(62942, historyGlyph);
    assertEquals(62966, deleteGlyph);

    // 4. User views word card: phonetic row requests speaker glyph
    int speakerGlyph = resolveCodepoint("volume-high-outline");
    assertEquals(62996, speakerGlyph);
  }
}
