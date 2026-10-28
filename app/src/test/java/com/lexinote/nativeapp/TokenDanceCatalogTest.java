package com.lexinote.nativeapp;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class TokenDanceCatalogTest {
  @Test public void catalogContainsExactAuditedModelsAndVisionMetadata() throws Exception {
    String[] ids = {"deepseek-v4.1-flash", "kimi-k2.5", "glm-4.6v", "seed-2.0-pro", "seed-2.0-mini", "glm-5v-turbo", "qwen3-vl-plus", "kimi-k2.6", "minimax-m3.1-flash", "qwen3.5-plus", "qwen3.5-flash", "qwen3.6-plus", "qwen3.7-plus", "step-3.7-flash", "kimi-k2.7-code", "seed-2.1-turbo", "minimax-m3", "seed-2.1-pro", "qwen3.8-max", "qwen3.5-35b-a3b", "glm-5.3-flash", "dots-3-note-preview", "qwen3.8-max-0902", "kimi-k3", "glm-5.3-flashx", "step-5-preview", "qwen3.8-flash", "mimo-v2.6-pro", "mimo-v2.6-flash", "deepseek-v4.1-flash-1017", "seed-2.0-lite", "seed-2.0-code", "seed-evolving", "mimo-v2.6-ultraspeed"};
    String[] names = {"DeepSeek: DeepSeek V4.1 Flash", "MoonshotAI: Kimi K2.5", "Z.ai: GLM 4.6V", "ByteDance Seed: Seed-2.0-Pro", "ByteDance Seed: Seed-2.0-Mini", "Z.ai: GLM 5V Turbo", "Qwen: Qwen3 VL Plus", "MoonshotAI: Kimi K2.6", "MiniMax: MiniMax M3.1 Flash", "Qwen: Qwen3.5 Plus", "Qwen: Qwen3.5 Flash", "Qwen: Qwen3.6 Plus", "Qwen: Qwen3.7 Plus", "StepFun: Step 3.7 Flash", "MoonshotAI: Kimi K2.7 Code", "ByteDance Seed: Seed-2.1-Turbo", "MiniMax: MiniMax M3", "ByteDance Seed: Seed-2.1-Pro", "Qwen: Qwen3.8 Max", "Qwen: Qwen3.5-35B-A3B", "Z.ai: GLM 5.3 Flash", "Dots Studio: Dots3-Note Preview（Free）", "Qwen: Qwen3.8-Max-0902", "MoonshotAI: Kimi K3", "GLM: GLM 5.3 FlashX", "StepFun: Step 5 Preview", "Qwen: Qwen3.8 Flash", "Xiaomi: Mimo-V2.6-Pro", "Xiaomi: Mimo-V2.6-Flash", "DeepSeek: DeepSeek V4.1 Flash 1017", "ByteDance Seed: Seed-2.0-Lite", "ByteDance Seed: Seed-2.0-Code", "ByteDance Seed: Seed-Evolving", "Xiaomi: MiMo-V2.6-Pro-UltraSpeed"};
    JSONArray list = TokenDanceCatalog.models();
    assertEquals(ids.length, list.length());
    for (int i = 0; i < ids.length; i++) {
      JSONObject item = list.getJSONObject(i);
      assertEquals(ids[i], item.getString("id"));
      assertEquals(names[i], item.getString("name"));
      assertTrue(item.getBoolean("vision_known"));
      assertTrue(item.getBoolean("supports_vision"));
      assertTrue(TokenDanceCatalog.isKnown(ids[i]));
    }
    list.getJSONObject(0).put("name", "mutated");
    assertEquals(names[0], TokenDanceCatalog.models().getJSONObject(0).getString("name"));
    assertFalse(TokenDanceCatalog.isKnown("KIMI-K2.5"));
  }

  @Test public void gatewayRequiresExactOriginAndPath() {
    assertTrue(TokenDanceCatalog.isGateway(" https://tokendance.space/gateway/v1/// "));
    assertTrue(TokenDanceCatalog.isGateway("https://TOKENDANCE.SPACE:443/gateway/v1?x=1"));
    assertFalse(TokenDanceCatalog.isGateway("http://tokendance.space/gateway/v1"));
    assertFalse(TokenDanceCatalog.isGateway("https://tokendance.space.evil/gateway/v1"));
    assertFalse(TokenDanceCatalog.isGateway("https://tokendance.space/gateway/v1/chat/completions"));
    assertFalse(TokenDanceCatalog.isGateway("invalid"));
  }

  @Test public void validationRequiresExplicitCustomModeAndRejectsKnownIncompatibleIds() throws Exception {
    TokenDanceCatalog.assertChatModel(provider("kimi-k2.5", false));
    rejected("new-vlm", false);
    TokenDanceCatalog.assertChatModel(provider("vendor/new-vlm:1", true));
    for (String id : new String[] {"deepseek-v4-pro", "MIMO-V2.5-TTS", "seedream-5.0-lite", "vendor/ocr-v1", "x video", "https://example.com/model", ""}) rejected(id, true);
    TokenDanceCatalog.assertChatModel(new JSONObject().put("id", "another").put("base_url", "https://other.invalid").put("model_id", "glm-ocr"));
    JSONObject gateway = provider("glm-ocr", true).put("id", "custom");
    try { TokenDanceCatalog.assertChatModel(gateway); fail("Gateway must enforce validation"); } catch (IllegalArgumentException expected) { }
  }
  private JSONObject provider(String model, boolean custom) throws Exception {
    return new JSONObject().put("id", "tokendance").put("base_url", "https://tokendance.space/gateway/v1").put("model_id", model).put("is_custom_model_id", custom);
  }
  private void rejected(String id, boolean custom) throws Exception {
    try { TokenDanceCatalog.assertChatModel(provider(id, custom)); fail("Accepted " + id); } catch (IllegalArgumentException expected) { }
  }
}
