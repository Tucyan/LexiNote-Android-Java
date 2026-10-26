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
    final ByteArrayOutputStream sent = new ByteArrayOutputStream();
    final String answer;
    final int code;
    boolean disconnected;

    FakeConnection(int code, String content) throws Exception {
      super(new URL("https://provider.invalid"));
      this.code = code;
      answer =
          new JSONObject()
              .put(
                  "choices",
                  new JSONArray()
                      .put(
                          new JSONObject()
                              .put("message", new JSONObject().put("content", content))))
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
    public InputStream getInputStream() {
      return new ByteArrayInputStream(answer.getBytes(StandardCharsets.UTF_8));
    }
  }
}
