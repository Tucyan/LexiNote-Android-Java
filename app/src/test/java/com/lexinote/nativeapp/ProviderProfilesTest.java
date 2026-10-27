package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class ProviderProfilesTest {
  @Test
  public void exposesFiveSanitizedPresetsIncludingManualKeyGateway() throws Exception {
    var profiles = ProviderProfiles.defaults();
    assertEquals(5, profiles.length());
    JSONObject td = profiles.getJSONObject(0);
    assertEquals("tokendance", td.getString("id"));
    assertEquals("https://tokendance.space/gateway/v1", td.getString("base_url"));
    assertEquals("deepseek-v4.1-flash", td.getString("model_id"));
    assertFalse(td.has("api_key"));
    assertFalse(td.has("api_format"));
    assertEquals("off", td.getString("reasoning_effort"));
  }

  @Test
  public void sanitizesAndValidatesMetadata() throws Exception {
    JSONObject input =
        new JSONObject()
            .put("id", "mine")
            .put("name", "Custom")
            .put("base_url", "https://example.com/v1/")
            .put("model_id", " model-x ")
            .put("supports_vision", true)
            .put("reasoning_effort", "high")
            .put("is_custom", true)
            .put("api_key", "secret")
            .put("unknown", "drop");
    JSONObject safe = ProviderProfiles.sanitize(input, true);
    assertEquals("https://example.com/v1", safe.getString("base_url"));
    assertEquals("model-x", safe.getString("model_id"));
    assertTrue(safe.getBoolean("is_custom"));
    assertFalse(safe.has("api_key"));
    assertFalse(safe.has("unknown"));
    try {
      ProviderProfiles.sanitize(
          new JSONObject(input.toString()).put("reasoning_effort", "extreme"), true);
      fail("invalid effort accepted");
    } catch (IllegalArgumentException expected) {
    }
    try {
      ProviderProfiles.sanitize(
          new JSONObject(input.toString()).put("base_url", "file:///etc"), true);
      fail("invalid endpoint accepted");
    } catch (IllegalArgumentException expected) {
    }
    try {
      ProviderProfiles.sanitize(
          new JSONObject(input.toString()).put("base_url", "http://example.com/v1"), true);
      fail("insecure endpoint accepted");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void destinationComparisonIgnoresTrailingSlashAndCase() {
    assertEquals(
        ProviderProfiles.destinationKey("https://EXAMPLE.com/v1/"),
        ProviderProfiles.destinationKey("https://example.com/v1"));
    assertNotEquals(
        ProviderProfiles.destinationKey("https://example.com/v1"),
        ProviderProfiles.destinationKey("https://example.com/v2"));
    assertNotEquals(
        ProviderProfiles.destinationKey("https://example.com/a%2Fb"),
        ProviderProfiles.destinationKey("https://example.com/a/b"));
  }
}
