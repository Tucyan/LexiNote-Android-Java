package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class AgentProtocolTest {
  @Test
  public void directLookupRequiresExplicitHubTargetAndNoImage() throws Exception {
    JSONObject result = new JSONObject().put("word", "take off");
    assertTrue(AgentProtocol.canApplyDirectResult("take  off", result, true, false));
    assertFalse(AgentProtocol.canApplyDirectResult("take off", result, false, false));
    assertFalse(AgentProtocol.canApplyDirectResult("take off", result, true, true));
    assertFalse(AgentProtocol.canApplyDirectResult("take", result, true, false));
  }

  @Test
  public void normalizesUnicodeAndWhitespace() {
    assertEquals("hello world", AgentProtocol.normalize("  Ｈｅｌｌｏ   WORLD  "));
  }

  @Test
  public void acceptsBaseOrFullHttpsEndpoint() throws Exception {
    assertEquals(
        "https://example.com/v1/chat/completions",
        AgentProtocol.endpoint("https://example.com/v1/"));
    assertEquals(
        "https://example.com/v1/chat/completions",
        AgentProtocol.endpoint("https://example.com/v1/chat/completions"));
  }

  @Test
  public void rejectsCredentialBearingOrInsecureEndpoint() {
    for (String url :
        new String[] {
          "http://example.com",
          "https://key@example.com",
          "https://example.com/v1?key=secret",
          "file:///key"
        }) {
      try {
        AgentProtocol.endpoint(url);
        fail("Accepted unsafe endpoint");
      } catch (IllegalArgumentException expected) {
        assertFalse(expected.getMessage().contains("secret"));
      }
    }
  }

  @Test
  public void parsesFencedAnswerAndDeduplicatesFollowups() throws Exception {
    JSONObject reply =
        AgentProtocol.parse(
            "```json\n"
                + "{\"kind\":\"answer\",\"message\":\"Hello\",\"follow_up_questions\":[\"A\",\"a\",\"B\",\"C\",\"D\"]}\n"
                + "```");
    assertEquals(3, reply.getJSONArray("follow_up_questions").length());
  }

  @Test
  public void rejectsUnknownActionOrBlankTarget() throws Exception {
    for (String raw :
        new String[] {
          "{\"kind\":\"execute\",\"message\":\"run\"}",
          "{\"kind\":\"confirm_lookup\",\"message\":\"confirm\",\"lookup\":{\"target\":\""
              + " \",\"type\":\"word\"}}"
        }) {
      try {
        AgentProtocol.parse(raw);
        fail("Accepted malformed action");
      } catch (IllegalArgumentException expected) {
        assertNotNull(expected.getMessage());
      }
    }
  }

  @Test
  public void acceptsDefinitionsAndRejectsEmptyOrDuplicateMeanings() throws Exception {
    JSONObject result =
        new JSONObject(
            "{\"word\":\"resilient\",\"type\":\"word\",\"meanings\":[{\"zh_definition\":\"有韧性的\"}]}");
    JSONObject valid = AgentProtocol.validateResult(result);
    assertFalse(valid.getJSONArray("meanings").getJSONObject(0).getString("id").isEmpty());
    result.put(
        "meanings",
        new JSONArray(
            "[{\"id\":\"x\",\"zh_definition\":\"one\"},{\"id\":\"x\",\"zh_definition\":\"two\"}]"));
    try {
      AgentProtocol.validateResult(result);
      fail("Duplicate meaning accepted");
    } catch (IllegalArgumentException expected) {
    }
    result.put("meanings", new JSONArray("[{}]"));
    try {
      AgentProtocol.validateResult(result);
      fail("Empty meaning accepted");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void boundsResponsePayloads() {
    try {
      AgentProtocol.parse("x".repeat(1024 * 1024 + 1));
      fail("Unbounded reply accepted");
    } catch (Exception expected) {
      assertTrue(expected instanceof IllegalArgumentException);
    }
  }
}
