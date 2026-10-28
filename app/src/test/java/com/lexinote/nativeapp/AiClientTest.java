package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

/** In-memory HTTPS transport: no real provider, account, credential or paid request is used. */
public class AiClientTest {
  @Test
  public void structuredConversationKeepsJsonExamplesAndValidatedActionHistory() throws Exception {
    next = new FakeConnection(200, "{\"kind\":\"answer\",\"message\":\"ok\"}");
    JSONObject action = new JSONObject().put("kind", "answer").put("message", "earlier answer");
    JSONArray history =
        new JSONArray()
            .put(
                new JSONObject()
                    .put("role", "assistant")
                    .put("content", "earlier answer")
                    .put("action", action));
    new AiClient(provider(false), "test-only").agent(history, "usage question", null);
    JSONArray sent = new JSONObject(next.sent.toString("UTF-8")).getJSONArray("messages");
    assertTrue(
        sent.getJSONObject(0).getString("content").contains("{\"kind\":\"answer\",\"message\":"));
    assertEquals(
        "answer", new JSONObject(sent.getJSONObject(1).getString("content")).getString("kind"));
  }

  @Test
  public void deepSeekActionsRequestJsonButTitlesRemainText() throws Exception {
    JSONObject config =
        provider(false)
            .put("base_url", "https://api.deepseek.com")
            .put("model_id", "deepseek-flash");
    next = new FakeConnection(200, "{\"kind\":\"answer\",\"message\":\"ok\"}");
    new AiClient(config, "test-only").agent(new JSONArray(), "explain usage", null);
    assertEquals(
        "json_object",
        new JSONObject(next.sent.toString("UTF-8"))
            .getJSONObject("response_format")
            .getString("type"));
    next = new FakeConnection(200, "Usage discussion");
    new AiClient(config, "test-only").suggestTitle("question", "answer");
    assertFalse(new JSONObject(next.sent.toString("UTF-8")).has("response_format"));
  }

  private static FakeConnection next;
  private static int opened;

  @BeforeClass
  public static void interceptHttps() {
    URL.setURLStreamHandlerFactory(
        protocol ->
            "https".equals(protocol)
                ? new URLStreamHandler() {
                  @Override
                  protected URLConnection openConnection(URL url) {
                    opened++;
                    return next;
                  }
                }
                : null);
  }

  private JSONObject provider(boolean vision) throws Exception {
    return new JSONObject()
        .put("base_url", "https://provider.invalid/v1")
        .put("model_id", "test-model")
        .put("supports_vision", vision);
  }

  @Test
  public void missingCredentialsAndVisionFailBeforeOpeningConnection() throws Exception {
    int before = opened;
    try {
      new AiClient(provider(true), "").agent(new JSONArray(), "hello", null);
      fail();
    } catch (IllegalStateException expected) {
    }
    try {
      new AiClient(provider(false), "test-only")
          .agent(new JSONArray(), "hello", "data:image/png;base64,AA");
      fail();
    } catch (IllegalStateException expected) {
    }
    assertEquals(before, opened);
  }

  @Test
  public void preservesContextAndImageInRequestWithoutFollowingRedirects() throws Exception {
    next = new FakeConnection(200, "{\"kind\":\"answer\",\"message\":\"ok\"}");
    JSONArray history =
        new JSONArray()
            .put(new JSONObject().put("role", "user").put("content", "previous question"));
    JSONObject result =
        new AiClient(provider(true), "test-only")
            .agent(history, "new question", "data:image/png;base64,AA");
    assertEquals("answer", result.getString("kind"));
    JSONObject payload = new JSONObject(next.sent.toString("UTF-8"));
    JSONArray messages = payload.getJSONArray("messages");
    assertEquals("previous question", messages.getJSONObject(1).getString("content"));
    assertEquals(
        "data:image/png;base64,AA",
        messages
            .getJSONObject(2)
            .getJSONArray("content")
            .getJSONObject(1)
            .getJSONObject("image_url")
            .getString("url"));
    assertFalse(next.getInstanceFollowRedirects());
    assertEquals("Bearer test-only", next.getRequestProperty("Authorization"));
    assertTrue(next.disconnected);
  }

  @Test
  public void authErrorsExposeStatusGuidanceWithoutServerBody() throws Exception {
    next = new FakeConnection(401, "sensitive-server-content");
    try {
      new AiClient(provider(true), "test-only").agent(new JSONArray(), "hello", null);
      fail();
    } catch (java.io.IOException expected) {
      assertTrue(expected.getMessage().contains("鉴权"));
      assertFalse(expected.getMessage().contains("sensitive-server-content"));
    }
  }

  @Test
  public void tokenDanceRecoveryRoutesOnlyOfficialGatewayErrors() throws Exception {
    next = new FakeConnection(402, "secret-body"); next.recoveryHeader = "top_up_balance";
    JSONObject config = provider(false).put("base_url", "https://tokendance.space/gateway/v1").put("model_id", "deepseek-v4.1-flash");
    try { new AiClient(config,"test-only").agent(new JSONArray(),"hello",null); fail(); }
    catch (TokenDanceRecovery expected) { assertEquals(TokenDanceProtocol.RecoveryAction.TOP_UP_BALANCE,expected.action); }
    next = new FakeConnection(402, "secret-body"); next.recoveryHeader = "top_up_balance";
    try { new AiClient(provider(false),"test-only").agent(new JSONArray(),"hello",null); fail(); }
    catch (java.io.IOException expected) { assertFalse(expected instanceof TokenDanceRecovery); assertFalse(expected.toString().contains("secret-body")); }
  }

  @Test
  public void tokenDanceGatewaySendsApplicationHeader() throws Exception {
    next = new FakeConnection(200, "{\"kind\":\"answer\",\"message\":\"ok\"}");
    JSONObject provider = provider(false).put("base_url", "https://tokendance.space/gateway/v1").put("model_id", "deepseek-v4.1-flash");
    new AiClient(provider, "test-only").agent(new JSONArray(), "hello", null);
    assertEquals("app://lexinote", next.getRequestProperty("X-App-URL"));
  }

  @Test
  public void streamingAgentReturnsValidatedFinalActionAndOnlyMessagePreview() throws Exception {
    String finalJson = "{\"kind\":\"answer\",\"message\":\"safe preview\"}";
    next =
        new FakeConnection(
            200, stream(finalJson, "stop", true), "text/event-stream; charset=utf-8");
    StringBuilder preview = new StringBuilder();
    JSONObject reply =
        new AiClient(provider(true), "test-only")
            .agent(new JSONArray(), "question", null, preview::append);
    assertEquals("answer", reply.getString("kind"));
    assertEquals("safe preview", reply.getString("message"));
    assertEquals("safe preview", preview.toString());
    assertTrue(new JSONObject(next.sent.toString("UTF-8")).optBoolean("stream"));
    assertTrue(next.disconnected);
  }

  @Test
  public void streamingLengthOrMissingTerminalMarkerNeverReturnsAnAnswer() throws Exception {
    String answer = "{\"kind\":\"answer\",\"message\":\"looks complete\"}";
    for (String wire : new String[] {stream(answer, "length", true), stream(answer, null, false)}) {
      next = new FakeConnection(200, wire, "text/event-stream");
      try {
        new AiClient(provider(true), "test-only")
            .agent(new JSONArray(), "question", null, part -> {});
        fail("truncated stream must not produce a reply");
      } catch (java.io.IOException expected) {
        assertTrue(expected.getMessage().contains("结束") || expected.getMessage().contains("截断"));
      }
    }
  }

  private static String stream(String content, String finishReason, boolean done) throws Exception {
    JSONObject delta = new JSONObject().put("content", content);
    JSONObject choice = new JSONObject().put("delta", delta);
    if (finishReason == null) choice.put("finish_reason", JSONObject.NULL);
    else choice.put("finish_reason", finishReason);
    String wire = "data: " + new JSONObject().put("choices", new JSONArray().put(choice)) + "\n\n";
    if (done) wire += "data: [DONE]\n\n";
    return wire;
  }

  @Test
  public void cancellationPreventsNetworkAndTargetSubstitutionIsRejected() throws Exception {
    int before = opened;
    AiClient cancelled = new AiClient(provider(true), "test-only");
    cancelled.cancel();
    try {
      cancelled.agent(new JSONArray(), "hello", null);
      fail();
    } catch (CancellationException expected) {
    }
    assertEquals(before, opened);
    next =
        new FakeConnection(
            200,
            "{\"word\":\"other\",\"type\":\"word\",\"meanings\":[{\"zh_definition\":\"另一个\"}]}");
    try {
      new AiClient(provider(true), "test-only").lookup("target", "context", null);
      fail();
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("目标"));
    }
  }

  private static final class FakeConnection extends HttpURLConnection {
    String recoveryHeader;
    @Override public String getHeaderField(String name) { return name.equals("TokenDance-Recovery-Action") ? recoveryHeader : null; }
    final ByteArrayOutputStream sent = new ByteArrayOutputStream();
    final String answer;
    final int code;
    final String contentType;
    boolean disconnected;

    FakeConnection(int code, String content) throws Exception {
      this(code, envelope(content), "application/json");
    }

    FakeConnection(int code, String response, String contentType) throws Exception {
      super(new URL("https://provider.invalid"));
      this.code = code;
      this.answer = response;
      this.contentType = contentType;
    }

    private static String envelope(String content) throws Exception {
      return new JSONObject()
          .put(
              "choices",
              new JSONArray()
                  .put(new JSONObject().put("message", new JSONObject().put("content", content))))
          .toString();
    }

    @Override
    public void connect() {}

    @Override
    public void disconnect() {
      disconnected = true;
    }

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
    public String getContentType() {
      return contentType;
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(answer.getBytes(StandardCharsets.UTF_8));
    }
  }
}
