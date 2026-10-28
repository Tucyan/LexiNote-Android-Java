package com.lexinote.nativeapp;

import java.net.URI;
import java.io.UnsupportedEncodingException;
import org.json.JSONObject;
import org.json.JSONException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** Pure protocol logic. Each Session accepts at most one authorization code. */
public final class TokenDanceProtocol {
 public static final String ORIGIN = "https://tokendance.space";
 public static final String NATIVE_CALLBACK_URL = "https://taskstream.xyz/lexinote/auth/callback";
 private static final long LIFETIME = 600000L;
 private TokenDanceProtocol() {}
 public enum Page { INTERNAL, EXTERNAL_HTTPS, CALLBACK, PAYMENT, BLOCKED }
 public enum RecoveryAction { TOP_UP_BALANCE, REAUTHORIZE_API_KEY, API_KEY_QUOTA }
 public static RecoveryAction readRecoveryAction(String baseUrl, String header) {
  if (baseUrl == null || !baseUrl.trim().replaceAll("/+$", "").equals(ORIGIN + "/gateway/v1") || header == null) return null;
  switch(header) { case "top_up_balance": return RecoveryAction.TOP_UP_BALANCE; case "reauthorize_api_key": return RecoveryAction.REAUTHORIZE_API_KEY; case "api_key_quota": return RecoveryAction.API_KEY_QUOTA; default: return null; }
 }
 public static Session begin(String callback, long now) {
  if (callback != null && (!isNativeCallback(callback) || URI.create(callback).getRawQuery()!=null)) throw new IllegalArgumentException("Invalid callback");
  SecureRandom random = new SecureRandom(); byte[] bytes = new byte[32]; random.nextBytes(bytes);
  StringBuilder v = new StringBuilder(); for (byte b : bytes) v.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
  byte[] state = new byte[32]; random.nextBytes(state);
  try { return new Session(v.toString(), Base64.getUrlEncoder().withoutPadding().encodeToString(state), callback, now); }
  catch (Exception e) { throw new IllegalStateException("Cannot initialize authorization", e); }
 }
 public static final class Session {
  public final String verifier, state, callback, challenge; public final long createdAt; private boolean consumed;
  private Session(String v, String s, String c, long t) throws Exception { verifier=v; state=s; callback=c; createdAt=t; challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.US_ASCII))); }
  /** Serialize only into encrypted storage; never log or include in backups. */
  public synchronized String toJson() { try { return new JSONObject().put("verifier",verifier).put("state",state).put("callback",callback == null ? JSONObject.NULL : callback).put("createdAt",createdAt).put("consumed",consumed).toString(); } catch(JSONException e) { throw new IllegalStateException("Cannot serialize authorization"); } }
  public static Session fromJson(String json) {
   try { JSONObject o=new JSONObject(json); String v=o.getString("verifier"), st=o.getString("state"); String cb=o.isNull("callback") ? null : o.getString("callback"); long t=o.getLong("createdAt");
    if(!v.matches("[0-9a-f]{64}") || !st.matches("[A-Za-z0-9_-]{43}") || t<0 || (cb!=null && (!isNativeCallback(cb) || new URI(cb).getRawQuery()!=null))) throw new IllegalArgumentException("Invalid stored authorization");
    Session session=new Session(v,st,cb,t); session.consumed=o.optBoolean("consumed",false); return session;
   } catch(Exception e) { throw new IllegalArgumentException("Invalid stored authorization"); }
  }
  private void check(long now) { if (consumed || now < createdAt || now - createdAt > LIFETIME) throw new IllegalArgumentException("Authorization expired or already used"); }
  public synchronized String readHeadlessCode(String input, long now) {
   check(now); if (callback != null || input == null) throw new IllegalArgumentException("Invalid authorization flow");
   String code=input.trim(); if (code.isEmpty() || code.length()>2048 || java.util.regex.Pattern.compile("\\s").matcher(code).find() || code.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) throw new IllegalArgumentException("Invalid authorization code");
   consumed=true; return code;
  }
  public synchronized String readCallback(String raw, long now) {
   check(now); if (callback == null || !isNativeCallback(raw)) throw new IllegalArgumentException("Invalid callback");
   try { URI uri = new URI(raw); Map<String,String> values = new HashMap<>();
    if (uri.getRawQuery() != null) for (String p : uri.getRawQuery().split("&", -1)) { String[] kv=p.split("=",2); String k=decode(kv[0]); String val=decode(kv.length==2?kv[1]:""); if ((k.equals("code") || k.equals("state")) && values.containsKey(k)) throw new IllegalArgumentException("Duplicate callback parameter"); values.put(k,val); }
    String code=values.get("code"); if (values.containsKey("error") || !state.equals(values.get("state")) || code == null || code.trim().isEmpty() || code.length()>2048 || java.util.regex.Pattern.compile("\\s").matcher(code).find()) throw new IllegalArgumentException("Invalid callback");
    consumed=true; return code;
   } catch (java.net.URISyntaxException e) { throw new IllegalArgumentException("Invalid callback"); }
  }
 }
 private static String decode(String s) { try { return URLDecoder.decode(s, "UTF-8"); } catch (UnsupportedEncodingException e) { throw new AssertionError(e); } }
 private static String encode(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (UnsupportedEncodingException e) { throw new AssertionError(e); } }
 public static String authorizationUrl(Session s) { String url=ORIGIN+"/auth?code_challenge="+encode(s.challenge)+"&code_challenge_method=S256&app_url="+encode("app://lexinote")+"&key_name=LexiNote"; return s.callback == null ? url : url+"&callback_url="+encode(s.callback+"?state="+encode(s.state)); }
 public static boolean isNativeCallback(String raw) { try { URI u=new URI(raw); return "https".equalsIgnoreCase(u.getScheme()) && "taskstream.xyz".equalsIgnoreCase(u.getHost()) && (u.getPort()==-1 || u.getPort()==443) && u.getUserInfo()==null && "/lexinote/auth/callback".equals(u.getRawPath()) && u.getFragment()==null; } catch(Exception e){return false;} }
 public static Page classifyPageUrl(String raw) { try { URI u=new URI(raw); String scheme=u.getScheme(), host=u.getHost(), path=u.getRawPath(); if (u.getUserInfo()!=null || scheme==null || host==null) return Page.BLOCKED; if (isNativeCallback(raw)) return Page.CALLBACK; if (scheme.equalsIgnoreCase("lexinote") && host.equalsIgnoreCase("tokendance") && "/callback".equals(path) && u.getPort()==-1) return Page.CALLBACK; if (scheme.equalsIgnoreCase("alipays") && host.equalsIgnoreCase("platformapi") && "/startapp".equals(path) && u.getPort()==-1) return Page.PAYMENT; if (scheme.equalsIgnoreCase("https")) return host.equalsIgnoreCase("tokendance.space") && (u.getPort()==-1 || u.getPort()==443) ? Page.INTERNAL : Page.EXTERNAL_HTTPS; return Page.BLOCKED; } catch(Exception e) {return Page.BLOCKED;} }
}
