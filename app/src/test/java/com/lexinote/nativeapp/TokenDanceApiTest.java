package com.lexinote.nativeapp;
import org.junit.Test;
import static org.junit.Assert.*;
import java.net.*;
import java.io.*;

public class TokenDanceApiTest {
 static class Fake extends HttpURLConnection {
  final ByteArrayOutputStream sent = new ByteArrayOutputStream(); String body; boolean disconnected;
  Fake(URL u, String b) { super(u); body=b; }
  public void disconnect(){disconnected=true;} public boolean usingProxy(){return false;} public void connect(){}
  public int getResponseCode() throws IOException {return 200;} public InputStream getInputStream(){return new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  public OutputStream getOutputStream(){return sent;}
 }
 @Test public void exchangeUsesPinnedEndpointAndDoesNotFollowRedirects() throws Exception {
  final Fake[] f = new Fake[1]; TokenDanceApi api = new TokenDanceApi(url -> f[0] = new Fake(url,"{\"key\":\" key \"}"));
  assertEquals("key", api.exchange("code", "verifier"));
  assertEquals("https://tokendance.space/portal/api/v1/auth/keys", f[0].getURL().toString());
  assertEquals(15000, f[0].getConnectTimeout()); assertFalse(f[0].getInstanceFollowRedirects()); assertTrue(f[0].disconnected);
 }
 @Test public void errorsAreSafeAndCancellationPreventsOpening() throws Exception {
  TokenDanceApi api = new TokenDanceApi(url -> new Fake(url,"{}") { public int getResponseCode(){return 401;} });
  try { api.exchange("secret-code", "secret-verifier"); fail(); } catch(IOException e) { assertTrue(e.getMessage().contains("401")); assertFalse(e.toString().contains("secret")); }
  TokenDanceApi cancelled = new TokenDanceApi(url -> { fail("cancelled request opened"); return null; });
  cancelled.cancel(); try { cancelled.exchange("code","verifier"); fail(); } catch(IOException e) { assertTrue(e.getMessage().contains("取消")); }
 }
 @Test public void rejectsUnsafeReturnedKeys() throws Exception {
  TokenDanceApi newline = new TokenDanceApi(url -> new Fake(url,"{\"key\":\"abc\\nxyz\"}"));
  try { newline.exchange("code","verifier"); fail(); } catch(IOException expected) {}
  TokenDanceApi oversized = new TokenDanceApi(url -> new Fake(url,"{\"key\":\"" + "x".repeat(4097) + "\"}"));
  try { oversized.exchange("code","verifier"); fail(); } catch(IOException expected) {}
 }
 @Test public void reportsTimeoutAndBoundsResponse() throws Exception {
  TokenDanceApi timeout = new TokenDanceApi(url -> new Fake(url,"{}") { public int getResponseCode() throws java.net.SocketTimeoutException { throw new java.net.SocketTimeoutException("secret"); } });
  try { timeout.exchange("code","verifier"); fail(); } catch(IOException e) { assertTrue(e.getMessage().contains("超时")); assertFalse(e.toString().contains("secret")); }
  TokenDanceApi oversized = new TokenDanceApi(url -> new Fake(url,"x".repeat(2*1024*1024+1)));
  try { oversized.exchange("code","verifier"); fail(); } catch(IOException e) { assertTrue(e.getMessage().contains("过大")); }
 }
 @Test public void rejectsUnknownStatusAndUnsafeCredentials() throws Exception {
  TokenDanceApi api = new TokenDanceApi(url -> new Fake(url,"{\"session\":{\"id\":\"123e4567-e89b-12d3-a456-426614174000\",\"amount\":5,\"status\":\"unknown\",\"expired_at\":123}}"));
  try { api.createPayment("key",5); fail(); } catch(IOException expected) {}
  try { api.createPayment("key\r\nInjected: x",5); fail(); } catch(IllegalArgumentException expected) {}
  try { api.exchange("x".repeat(2049),"verifier"); fail(); } catch(IllegalArgumentException expected) {}
 }
 @Test public void validatesPaymentResponseAndAmount() throws Exception {
  TokenDanceApi api = new TokenDanceApi(url -> new Fake(url,"{\"session\":{\"id\":\"abc\",\"amount\":5,\"status\":\"pending\",\"expired_at\":123}}"));
  assertEquals("abc", api.createPayment("key",5).id);
  try { api.createPayment("key", 0); fail(); } catch (IllegalArgumentException expected) {}
  try { api.getPaymentStatus("key", "other"); fail(); } catch (IOException expected) {}
 }
}
