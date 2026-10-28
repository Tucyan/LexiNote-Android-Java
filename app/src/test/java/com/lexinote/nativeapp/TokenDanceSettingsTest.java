package com.lexinote.nativeapp;

import org.junit.Test;
import static org.junit.Assert.*;
import org.json.JSONObject;

public class TokenDanceSettingsTest {
  @Test public void explicitCustomModelAndAddressSurvivePortableConfiguration() throws Exception {
    JSONObject provider = ProviderProfiles.preset("tokendance");
    provider.put("model_id", "future-vision-model");
    provider.put("is_custom_model_id", true);
    provider.put("is_custom_base_url", true);
    JSONObject saved = ProviderProfiles.sanitize(provider, false);
    assertTrue("Custom model consent must survive save/reload", saved.optBoolean("is_custom_model_id"));
    assertTrue(saved.optBoolean("is_custom_base_url"));
  }
}
