package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class ModelsApiTest {
  @Test
  public void cancellationWhileRegisteringConnectionDoesNotSendProbeBody() throws Exception {
    TestConnection connection = new TestConnection(200, envelope("LEXINOTE TEXT OK"));
    ModelsApi[] holder = new ModelsApi[1];
    holder[0] =
        new ModelsApi(
            new JSONObject()
                .put("base_url", "https://provider.invalid")
                .put("model_id", "test-model"),
            "test-key",
            url -> {
              holder[0].cancel();
              return connection;
            });
    assertFalse(holder[0].testConnection(false).getBoolean("success"));
    assertEquals("Cancelled probe sent request bytes", 0, connection.sent.size());
  }

  @Test
  public void catalogNormalizesModalitiesProtocolAndChatCandidates() throws Exception {
    String body =
        "{\"data\":["
            + "{\"id\":\"vision\",\"name\":\"Vision\",\"supported_protocols\":[\"openai:chat-completions\"],\"architecture\":{\"input_modalities\":[\"text\",\"image\"],\"output_modalities\":[\"text\"]},\"metadata\":{\"effort\":{\"supported_levels\":[\"low\",\"high\"]}}},"
            + "{\"id\":\"chat-unknown\",\"supported_protocols\":[\"openai:chat-completions\"]},"
            + "{\"id\":\"embed-v2\",\"supported_protocols\":[\"openai:chat-completions\"]},"
            + "{\"id\":\"vision\",\"supported_protocols\":[\"openai:chat-completions\"]}]}";
    TestConnection connection = new TestConnection(200, body);
    ModelsApi api = api(connection, "https://provider.invalid/v1/chat/completions");
    JSONArray models = api.fetchModels();
    assertEquals(2, models.length());
    assertEquals("chat-unknown", models.getJSONObject(0).getString("id"));
    JSONObject vision = models.getJSONObject(1);
    assertTrue(vision.getBoolean("vision_known"));
    assertTrue(vision.getBoolean("supports_vision"));
    assertEquals("low", vision.getJSONArray("reasoning_levels").getString(0));
    assertEquals("https://provider.invalid/v1/models", connection.url.toString());
  }

  @Test
  public void catalogReadsTopLevelProviderModalitiesAndEffortMetadata() throws Exception {
    String body =
        "{\"data\":[{\"id\":\"deepseek-flash\",\"name\":\"DeepSeek Flash\","
            + "\"input_modalities\":[\"text\",\"image\"],\"output_modalities\":[\"text\"],"
            + "\"effort\":{\"supported_levels\":[\"low\",\"high\",\"max\"]}}]}";
    TestConnection connection = new TestConnection(200, body);
    JSONArray models = api(connection, "https://provider.invalid/v1").fetchModels();
    JSONObject model = models.getJSONObject(0);
    assertTrue(model.getBoolean("vision_known"));
    assertTrue(model.getBoolean("supports_vision"));
    assertEquals("max", model.getJSONArray("reasoning_levels").getString(2));
  }

  @Test
  public void textProbeChecksExpectedPhraseAndSanitizesFailures() throws Exception {
    TestConnection good = new TestConnection(200, envelope("LEXINOTE TEXT OK"));
    JSONObject result = api(good, "https://provider.invalid").testConnection(false);
    assertTrue(result.getBoolean("success"));
    assertTrue(
        new JSONObject(good.sent.toString("UTF-8"))
            .getJSONArray("messages")
            .getJSONObject(0)
            .getString("content")
            .contains("LEXINOTE TEXT OK"));
    TestConnection bad = new TestConnection(401, "secret error");
    JSONObject failed = api(bad, "https://provider.invalid").testConnection(false);
    assertFalse(failed.getBoolean("success"));
    assertFalse(failed.getString("message").contains("secret"));
  }

  @Test
  public void visionProbeSendsImageAndRequiresCorrectColor() throws Exception {
    TestConnection good = new TestConnection(200, envelope("RED"));
    JSONObject result = api(good, "https://provider.invalid/v1").testConnection(true);
    assertTrue(result.getBoolean("success"));
    JSONObject payload = new JSONObject(good.sent.toString("UTF-8"));
    JSONArray content = payload.getJSONArray("messages").getJSONObject(0).getJSONArray("content");
    String prompt = content.getJSONObject(0).getString("text");
    assertFalse(
        "prompt must not disclose expected color",
        prompt.toLowerCase(java.util.Locale.ROOT).contains("red"));
    JSONObject image = content.getJSONObject(1);
    assertTrue(
        image.getJSONObject("image_url").getString("url").startsWith("data:image/png;base64,"));
    assertTrue(
        "probe image should have enough pixels for robust inspection",
        image.getJSONObject("image_url").getString("url").length() > 100);
    TestConnection wrong = new TestConnection(200, envelope("BLUE"));
    JSONObject failed = api(wrong, "https://provider.invalid/v1").testConnection(true);
    assertFalse(failed.getBoolean("success"));
    assertTrue(failed.getString("message").contains("视觉"));
  }

  @Test
  public void endpointResolverHandlesRootV1AndChatEndpoint() throws Exception {
    for (String[] pair :
        new String[][] {
          {"https://provider.invalid", "https://provider.invalid/models"},
          {"https://provider.invalid/v1/", "https://provider.invalid/v1/models"},
          {"https://provider.invalid/v1/chat/completions", "https://provider.invalid/v1/models"}
        }) {
      TestConnection connection = new TestConnection(200, "{\"data\":[]}");
      api(connection, pair[0]).fetchModels();
      assertEquals(pair[1], connection.url.toString());
    }
  }

  @Test
  public void publicTokenDanceCatalogCanLoadWithoutCredential() throws Exception {
    TestConnection connection = new TestConnection(200, "{\"data\":[]}");
    ModelsApi api =
        new ModelsApi(
            new JSONObject().put("base_url", "https://tokendance.space/gateway/v1"),
            "",
            url -> {
              connection.url = url;
              return connection;
            });
    assertEquals(0, api.fetchModels().length());
    assertEquals("app://lexinote", connection.getRequestProperty("X-App-URL"));
    assertNull(connection.getRequestProperty("Authorization"));
  }

  private ModelsApi api(TestConnection connection, String base) throws Exception {
    return new ModelsApi(
        new JSONObject().put("base_url", base).put("model_id", "test-model"),
        "test-key",
        url -> {
          connection.url = url;
          return connection;
        });
  }

  private String envelope(String answer) throws Exception {
    return new JSONObject()
        .put(
            "choices",
            new JSONArray()
                .put(new JSONObject().put("message", new JSONObject().put("content", answer))))
        .toString();
  }

  private static final class TestConnection extends HttpURLConnection {
    final int code;
    final String body;
    final ByteArrayOutputStream sent = new ByteArrayOutputStream();
    URL url;

    TestConnection(int code, String body) throws Exception {
      super(new URL("https://provider.invalid"));
      this.code = code;
      this.body = body;
    }

    @Override
    public void connect() {}

    @Override
    public void disconnect() {}

    @Override
    public boolean usingProxy() {
      return false;
    }

    @Override
    public OutputStream getOutputStream() {
      return sent;
    }

    @Override
    public int getResponseCode() {
      return code;
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
  }
}
