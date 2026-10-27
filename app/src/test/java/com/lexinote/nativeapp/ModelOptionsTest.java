package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public class ModelOptionsTest {
  @Test
  public void vendorThinkingTogglesAndOffOmitsEffort() throws Exception {
    for (String model : new String[] {"deepseek-chat", "kimi-k2", "glm-4"}) {
      JSONObject off = ModelOptions.apply(new JSONObject(), provider(model, "off"), null);
      assertEquals("disabled", off.getJSONObject("thinking").getString("type"));
      assertFalse(off.has("reasoning_effort"));
      JSONObject on = ModelOptions.apply(new JSONObject(), provider(model, "high"), null);
      assertEquals("enabled", on.getJSONObject("thinking").getString("type"));
      assertEquals("high", on.getString("reasoning_effort"));
    }
  }

  @Test
  public void qwenUsesEnableThinkingAndGenericOffIsOmitted() throws Exception {
    assertFalse(
        ModelOptions.apply(new JSONObject(), provider("qwen-plus", "off"), null)
            .getBoolean("enable_thinking"));
    assertTrue(
        ModelOptions.apply(new JSONObject(), provider("qwen-plus", "low"), null)
            .getBoolean("enable_thinking"));
    assertFalse(
        ModelOptions.apply(new JSONObject(), provider("gpt-4o", "off"), null)
            .has("reasoning_effort"));
    assertEquals(
        "high",
        ModelOptions.apply(new JSONObject(), provider("custom", "high"), null)
            .getString("reasoning_effort"));
  }

  @Test
  public void effortMetadataRestrictsUnsupportedEffort() throws Exception {
    try {
      ModelOptions.apply(
          new JSONObject(),
          provider("deepseek-chat", "medium"),
          new org.json.JSONArray().put("low").put("high"));
      fail("unsupported effort must be rejected");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("支持"));
    }
  }

  private JSONObject provider(String model, String effort) throws Exception {
    return new JSONObject().put("model_id", model).put("reasoning_effort", effort);
  }
}
