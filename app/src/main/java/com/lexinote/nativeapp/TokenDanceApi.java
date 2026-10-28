package com.lexinote.nativeapp;

import org.json.JSONObject;
import org.json.JSONException;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Blocking network client; call on a worker thread. Never logs credentials. */
public final class TokenDanceApi {
 public interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }
 private static final int TIMEOUT=15000, MAX_BODY=2*1024*1024;
 private static final String PAYMENT="/portal/api/v1/payment/sessions";
 private final ConnectionFactory factory; private HttpURLConnection active; private boolean cancelled;
 public TokenDanceApi() { this(url -> (HttpURLConnection) url.openConnection()); }
 public TokenDanceApi(ConnectionFactory factory) { if(factory==null) throw new IllegalArgumentException("Missing factory"); this.factory=factory; }
 /** Cancels the current request; create a new client for subsequent requests. */
 public synchronized void cancel() { cancelled=true; if(active!=null) active.disconnect(); }
 public String exchange(String code, String verifier) throws IOException {
  if (!safeToken(code, 2048) || !safeToken(verifier, 128)) throw new IllegalArgumentException("Invalid authorization");
  try { JSONObject body=new JSONObject().put("code",code).put("code_verifier",verifier).put("code_challenge_method","S256");
   JSONObject response=request("/portal/api/v1/auth/keys", "POST", null, body); Object key=response.opt("key");
   if (!(key instanceof String)) throw new IOException("授权未返回有效密钥"); try { return validKey((String)key); } catch (IllegalArgumentException e) { throw new IOException("授权未返回有效密钥"); }
  } catch(JSONException e) { throw new IOException("Invalid authorization request"); }
 }
 public static final class PaymentSession {
  public final String id, status, alipayUrl; public final int amount; public final long expiredAt;
  PaymentSession(String id, int amount, String status, long expiry, String url) {this.id=id;this.amount=amount;this.status=status;this.expiredAt=expiry;this.alipayUrl=url;}
 }
 public PaymentSession createPayment(String key, int amount) throws IOException {
  if(amount<1 || amount>100000) throw new IllegalArgumentException("Invalid payment amount");
  try { return parseSession(request(PAYMENT,"POST",validKey(key),new JSONObject().put("amount",amount)), null, amount); }
  catch(JSONException e) { throw new IOException("Invalid payment request"); }
 }
 public PaymentSession getPaymentStatus(String key, String id) throws IOException {
  if(!validId(id)) throw new IllegalArgumentException("Invalid payment ID");
  return parseSession(request(PAYMENT+"/"+id,"GET",validKey(key),null), id, null);
 }
 private static boolean safeToken(String value, int limit) { return value!=null && !value.isEmpty() && value.length()<=limit && value.chars().allMatch(c -> c>=33 && c<=126); }
 private static String validKey(String key) { if(key==null || key.indexOf('\r')>=0 || key.indexOf('\n')>=0 || !safeToken(key.trim(), 4096)) throw new IllegalArgumentException("无效的密钥"); return key.trim(); }
 private static boolean validId(String id) { return id!=null && id.matches("[A-Za-z0-9_-]{1,128}"); }
 private static PaymentSession parseSession(JSONObject payload,String expectedId,Integer expectedAmount) throws IOException {
  JSONObject s=payload.optJSONObject("session"); if(s==null) throw new IOException("Invalid payment response");
  Object id=s.opt("id"), status=s.opt("status"), amount=s.opt("amount"), expiry=s.opt("expired_at"), pay=s.opt("alipay_url");
  if(!(id instanceof String) || !validId((String)id) || (expectedId!=null && !expectedId.equals(id)) || !(status instanceof String) || !java.util.Arrays.asList("pending", "paid", "failed", "closed", "refunded", "expired").contains(status) || !(amount instanceof Number) || !(expiry instanceof Number)) throw new IOException("Invalid payment response");
  double a=((Number)amount).doubleValue(), e=((Number)expiry).doubleValue();
  if(!Double.isFinite(a) || a!=Math.floor(a) || a<1 || a>100000 || (expectedAmount!=null && a!=expectedAmount) || !Double.isFinite(e) || e<=0 || e!=Math.floor(e) || e>Long.MAX_VALUE) throw new IOException("Invalid payment response");
  String url=null; if(pay!=null && pay!=JSONObject.NULL) { if(!(pay instanceof String)) throw new IOException("Invalid payment response"); url=(String)pay; TokenDanceProtocol.Page page=TokenDanceProtocol.classifyPageUrl(url); if(page!=TokenDanceProtocol.Page.PAYMENT && page!=TokenDanceProtocol.Page.EXTERNAL_HTTPS && page!=TokenDanceProtocol.Page.INTERNAL) throw new IOException("Invalid payment URL"); }
  return new PaymentSession((String)id,(int)a,(String)status,(long)e,url);
 }
 private synchronized void checkCancelled() throws IOException { if(cancelled) throw new IOException("请求已取消"); }
 private static final class ServiceException extends IOException { ServiceException(String message) { super(message); } }
 private JSONObject request(String path,String method,String key,JSONObject body) throws IOException {
  HttpURLConnection c=null;
  try {
   synchronized(this) { if(cancelled) throw new IOException("请求已取消"); if(active!=null) throw new IOException("Request already in progress"); c=factory.open(new URL(TokenDanceProtocol.ORIGIN+path)); active=c; }
   checkCancelled(); c.setConnectTimeout(TIMEOUT); c.setReadTimeout(TIMEOUT); c.setInstanceFollowRedirects(false); c.setRequestMethod(method); c.setRequestProperty("Content-Type","application/json"); if(key!=null) c.setRequestProperty("Authorization","Bearer "+key);
   checkCancelled(); if(body!=null) { c.setDoOutput(true); byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8); try(OutputStream out=c.getOutputStream()){ checkCancelled(); out.write(bytes); checkCancelled(); } }
   checkCancelled(); int status=c.getResponseCode(); checkCancelled(); if(status<200 || status>=300) throw new ServiceException("TokenDance 服务请求失败（HTTP "+status+"）");
   ByteArrayOutputStream bytes=new ByteArrayOutputStream(); try(InputStream in=c.getInputStream()) { byte[] buffer=new byte[8192]; int count; while((count=in.read(buffer))!=-1) { checkCancelled(); if(bytes.size()+count>MAX_BODY) throw new ServiceException("TokenDance 响应过大"); bytes.write(buffer,0,count); } }
   synchronized(this) { if(cancelled) throw new IOException("请求已取消"); }
   try { return new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8)); } catch(JSONException e) { throw new ServiceException("TokenDance 响应格式不正确"); }
  } catch (IOException e) { checkCancelled(); if(e instanceof ServiceException) throw e; if(e instanceof java.net.SocketTimeoutException) throw new IOException("TokenDance 请求超时，请稍后重试"); throw new IOException("TokenDance 网络请求失败，请检查网络后重试"); }
  finally { synchronized(this) { if(active==c) active=null; } if(c!=null) c.disconnect(); }
 }
}
