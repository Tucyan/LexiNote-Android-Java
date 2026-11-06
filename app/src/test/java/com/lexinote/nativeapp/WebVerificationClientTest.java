package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class WebVerificationClientTest {
  static class Fake extends HttpURLConnection {
    final ByteArrayOutputStream sent = new ByteArrayOutputStream();
    final String body;
    final int status;
    boolean disconnected;

    Fake(URL url, String body, int status) {
      super(url);
      this.body = body;
      this.status = status;
    }

    @Override public void disconnect() { disconnected = true; }
    @Override public boolean usingProxy() { return false; }
    @Override public void connect() {}
    @Override public int getResponseCode() { return status; }
    @Override public InputStream getInputStream() {
      return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }
    @Override public OutputStream getOutputStream() { return sent; }
  }

  @Test public void sendsPinnedSearchRequestAndAssignsStableDeduplicatedIds() throws Exception {
    Fake[] fake = new Fake[1];
    WebVerificationClient client = new WebVerificationClient("test-key", url -> fake[0] = new Fake(url,
        "{\"results\":["
            + "{\"title\":\"River bank\",\"url\":\"https://example.org/en/bank?utm_source=one\",\"snippet\":\"beside a river\"},"
            + "{\"title\":\"River bank\",\"url\":\"https://example.org/bank#top\",\"summary\":\"beside a river\"},"
            + "{\"title\":\"Finance bank\",\"url\":\"https://finance.example.org/bank\",\"summary\":\"a financial institution\"}]}" , 200));

    List<WebVerificationClient.Source> sources = client.search("bank");

    assertEquals(2, sources.size());
    assertEquals("S1", sources.get(0).id);
    assertEquals("beside a river", sources.get(0).snippet);
    assertEquals("S2", sources.get(1).id);
    assertEquals("River bank", sources.get(0).title);
    assertEquals("https://example.org/en/bank?utm_source=one", sources.get(0).url);
    assertEquals("https://tokendance.space/gateway/deepseek/v1/tools/search", fake[0].getURL().toString());
    assertEquals(15000, fake[0].getConnectTimeout());
    assertEquals(60000, fake[0].getReadTimeout());
    assertFalse(fake[0].getInstanceFollowRedirects());
    assertEquals("Bearer test-key", fake[0].getRequestProperty("Authorization"));
    JSONObject payload = new JSONObject(fake[0].sent.toString(StandardCharsets.UTF_8.name()));
    assertEquals("bank", payload.getString("query"));
    assertEquals(3, payload.getInt("count"));
    assertFalse(payload.getBoolean("need_content"));
    assertTrue(fake[0].disconnected);
  }

  @Test public void independentHostsAndMeaningfulParametersRemainDistinct() throws Exception {
    WebVerificationClient client = new WebVerificationClient("test-key", url -> new Fake(url,
        "{\"results\":["
            + "{\"title\":\"term\",\"url\":\"https://bbc.co.uk/dictionary?q=bank\",\"snippet\":\"definition\"},"
            + "{\"title\":\"term\",\"url\":\"https://nhs.co.uk/dictionary?q=bank\",\"snippet\":\"definition\"},"
            + "{\"title\":\"term\",\"url\":\"https://bbc.co.uk/dictionary?q=river\",\"snippet\":\"definition\"}]}", 200));
    assertEquals(3, client.search("bank").size());
  }

  @Test public void cancellingWhileConnectionIsCreatedDoesNotSendRequest() throws Exception {
    WebVerificationClient[] client = new WebVerificationClient[1];
    Fake[] fake = new Fake[1];
    client[0] = new WebVerificationClient("test-key", url -> {
      fake[0] = new Fake(url, "{\"results\":[]}", 200);
      client[0].cancel();
      return fake[0];
    });
    try {
      client[0].search("bank");
      fail("cancelled search was sent");
    } catch (java.io.IOException | java.util.concurrent.CancellationException expected) {
      assertEquals(0, fake[0].sent.size());
      assertTrue(fake[0].disconnected);
    }
  }

  @Test public void validatesUnicodeQueryLengthAndNeverIncludesKeyInErrors() throws Exception {
    WebVerificationClient client = new WebVerificationClient("private-token", url -> new Fake(url, "{}", 401));
    try { client.search(" "); fail("blank query must fail"); } catch (IllegalArgumentException expected) {}
    try { client.search("😀".repeat(129)); fail("query over 128 code points must fail"); } catch (IllegalArgumentException expected) {}
    try { client.search("bank"); fail("401 must fail"); }
    catch (IOException expected) {
      assertFalse(expected.toString().contains("private-token"));
      assertTrue(expected.getMessage().contains("鉴权"));
    }
  }

  @Test public void ignoresMalformedAndNonHttpResults() throws Exception {
    WebVerificationClient client = new WebVerificationClient("test-key", url -> new Fake(url,
        "{\"results\":["
            + "{\"title\":\"ok\",\"url\":\"https://example.org/item\",\"snippet\":\"good\"},"
            + "{\"title\":\"bad\",\"url\":\"javascript:alert(1)\",\"snippet\":\"bad\"},"
            + "{\"title\":\"\",\"url\":\"https://example.org/no-title\",\"summary\":\"x\"},"
            + "{\"title\":\"no-summary\",\"url\":\"https://example.org/no-summary\"}]}" , 200));
    List<WebVerificationClient.Source> sources = client.search("valid");
    assertEquals(1, sources.size());
    assertEquals("S1", sources.get(0).id);
  }

  @Test public void cancellationPreventsOpeningTheConnection() throws Exception {
    WebVerificationClient client = new WebVerificationClient("test-key", url -> {
      fail("cancelled request must not open a connection");
      return null;
    });
    client.cancel();
    try { client.search("bank"); fail("cancelled request must stop"); }
    catch (java.util.concurrent.CancellationException expected) {}
  }
}
