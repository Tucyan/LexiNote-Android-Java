package com.lexinote.nativeapp;
import org.junit.Test;
import static org.junit.Assert.*;

public class TokenDanceProtocolTest {
 @Test public void pkceAndSingleUseHeadless() throws Exception {
  TokenDanceProtocol.Session s = TokenDanceProtocol.begin(null, 1000);
  assertEquals(64, s.verifier.length()); assertEquals(43, s.challenge.length());
  assertTrue(TokenDanceProtocol.authorizationUrl(s).contains("code_challenge_method=S256"));
  assertEquals("abc", s.readHeadlessCode(" abc ", 1001));
  try { s.readHeadlessCode("abc", 1002); fail(); } catch (IllegalArgumentException expected) {}
 }
 @Test public void callbackRequiresExactAddressStateAndSingleCode() throws Exception {
  TokenDanceProtocol.Session s = TokenDanceProtocol.begin(TokenDanceProtocol.NATIVE_CALLBACK_URL, 1000);
  String u = TokenDanceProtocol.NATIVE_CALLBACK_URL + "?state=" + s.state;
  try { s.readCallback(u + "&code=a&code=b", 1001); fail(); } catch (IllegalArgumentException expected) {}
  try { s.readCallback(u + "&code=a", 601001); fail(); } catch (IllegalArgumentException expected) {}
  assertEquals("a", s.readCallback(u + "&code=a", 1001));
 }
 @Test public void secureStorageRoundTripRetainsSingleUseState() {
  TokenDanceProtocol.Session s=TokenDanceProtocol.begin(null,1000);
  TokenDanceProtocol.Session restored=TokenDanceProtocol.Session.fromJson(s.toJson());
  assertEquals(s.challenge,restored.challenge); assertEquals(s.state,restored.state);
  restored.readHeadlessCode("abc",1001);
  TokenDanceProtocol.Session consumed=TokenDanceProtocol.Session.fromJson(restored.toJson());
  try { consumed.readHeadlessCode("abc",1002); fail(); } catch(IllegalArgumentException expected) {}
 }
 @Test public void rejectsUnsafeLinks() {
  assertEquals(TokenDanceProtocol.Page.PAYMENT, TokenDanceProtocol.classifyPageUrl("alipays://platformapi/startapp?id=1"));
  assertEquals(TokenDanceProtocol.Page.BLOCKED, TokenDanceProtocol.classifyPageUrl("alipays://evil/startapp"));
  assertEquals(TokenDanceProtocol.Page.EXTERNAL_HTTPS, TokenDanceProtocol.classifyPageUrl("https://tokendance.space.evil.test/"));
  assertNull(TokenDanceProtocol.readRecoveryAction("https://other.example/gateway/v1", "top_up_balance"));
 }
}
