package com.lexinote.nativeapp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded TokenDance web search transport. Call search from a worker thread. */
public final class WebVerificationClient {
  private static final String ENDPOINT =
      "https://tokendance.space/gateway/deepseek/v1/tools/search";
  private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
  private static final int MAX_SOURCES = 3;
  static final int MAX_QUERY_CODE_POINTS = 128;
  private final String apiKey;
  private final ConnectionFactory connectionFactory;
  private volatile boolean cancelled;
  private volatile HttpURLConnection active;

  public WebVerificationClient(String tokenDanceKey) {
    this(tokenDanceKey, url -> (HttpURLConnection) url.openConnection());
  }

  WebVerificationClient(String tokenDanceKey, ConnectionFactory connectionFactory) {
    this.apiKey = tokenDanceKey == null ? "" : tokenDanceKey.trim();
    this.connectionFactory = connectionFactory;
  }

  /** Searches a word or phrase and returns at most three validated, deduplicated sources. */
  public List<Source> search(String query) throws IOException {
    checkCancelled();
    String normalizedQuery = query == null ? "" : query.trim();
    int codePoints = normalizedQuery.codePointCount(0, normalizedQuery.length());
    if (codePoints < 1 || codePoints > MAX_QUERY_CODE_POINTS)
      throw new IllegalArgumentException("查询词须为1到128个字符");
    if (apiKey.isEmpty()) throw new IOException("请先在设置中填写 TokenDance API Key");
    if (apiKey.indexOf('\r') >= 0 || apiKey.indexOf('\n') >= 0)
      throw new IllegalArgumentException("API Key 格式无效");

    byte[] bytes;
    try {
      bytes = new JSONObject()
          .put("query", normalizedQuery)
          .put("count", MAX_SOURCES)
          .put("need_content", false)
          .toString().getBytes(StandardCharsets.UTF_8);
    } catch (org.json.JSONException e) {
      throw new IOException("无法准备联网检索请求");
    }
    HttpURLConnection connection = null;
    try {
      checkCancelled();
      connection = connectionFactory.open(new URL(ENDPOINT));
      active = connection;
      checkCancelled();
      connection.setRequestMethod("POST");
      connection.setConnectTimeout(15000);
      connection.setReadTimeout(60000);
      connection.setInstanceFollowRedirects(false);
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      connection.setRequestProperty("Accept", "application/json");
      connection.setRequestProperty("Authorization", "Bearer " + apiKey);
      connection.setRequestProperty("X-App-URL", "app://lexinote");
      connection.setFixedLengthStreamingMode(bytes.length);
      try (OutputStream out = connection.getOutputStream()) {
        out.write(bytes);
      }
      int status = connection.getResponseCode();
      checkCancelled();
      if (status == 401 || status == 403)
        throw new IOException("联网检索鉴权失败，请检查 TokenDance API Key 或账户权限");
      if (status == 429) throw new IOException("联网检索额度不足或请求过于频繁，请稍后重试");
      if (status < 200 || status >= 300)
        throw new IOException("联网检索请求失败（HTTP " + status + "），请稍后重试");
      String response;
      try (InputStream in = connection.getInputStream()) {
        response = readBounded(in);
      }
      checkCancelled();
      return parseSources(response);
    } catch (SocketTimeoutException e) {
      checkCancelled();
      throw new IOException("联网检索响应超时，请手动重试");
    } catch (org.json.JSONException e) {
      throw new IOException("联网检索返回格式无效，请稍后重试");
    } finally {
      active = null;
      if (connection != null) connection.disconnect();
    }
  }

  /** Cancels the active request and permanently marks this client cancelled. */
  public void cancel() {
    cancelled = true;
    HttpURLConnection current = active;
    if (current != null) current.disconnect();
  }

  private List<Source> parseSources(String response) throws IOException, org.json.JSONException {
    JSONObject root = new JSONObject(response);
    JSONArray results = root.optJSONArray("results");
    List<Source> sources = new ArrayList<>();
    Set<String> seen = new HashSet<>(), equivalent = new HashSet<>();
    if (results == null) throw new IOException("联网检索返回格式无效，请稍后重试");
    for (int i = 0; i < results.length() && sources.size() < MAX_SOURCES; i++) {
      JSONObject result = results.optJSONObject(i);
      if (result == null) continue;
      String title = clean(result.optString("title"), 300);
      String url = result.optString("url").trim();
      if (url.length() > 2048) continue;
      String snippet = clean(result.optString("snippet"), 2000);
      if (snippet.isEmpty()) snippet = clean(result.optString("summary"), 2000);
      String key = canonicalUrl(url, false), alias = canonicalUrl(url, true);
      if (title.isEmpty() || snippet.isEmpty() || key == null || !seen.add(key)
          || !equivalent.add(alias + "\n" + title + "\n" + snippet)) continue;
      sources.add(new Source("S" + (sources.size() + 1), title, url, snippet));
    }
    return sources;
  }

  private static String canonicalUrl(String value, boolean ignoreLocale) {
    try {
      URI uri = new URI(value);
      String scheme = uri.getScheme();
      String host = uri.getHost();
      if (scheme == null || host == null || uri.getUserInfo() != null
          || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) return null;
      host = host.toLowerCase(Locale.ROOT);
      String path = uri.normalize().getRawPath();
      if (path == null) path = "";
      if (ignoreLocale) path = path.replaceFirst("(?i)^/(?:[a-z]{2,3}(?:-[a-z]{2})?)(?=/|$)", "");
      List<String> parameters = new ArrayList<>();
      String query = uri.getRawQuery();
      if (query != null)
        for (String parameter : query.split("&", -1)) {
          String name = parameter.split("=", 2)[0].toLowerCase(Locale.ROOT);
          if (!name.startsWith("utm_") && !name.equals("gclid") && !name.equals("fbclid"))
            parameters.add(parameter);
        }
      int port = uri.getPort();
      String portPart = (port == -1 || port == ("https".equalsIgnoreCase(scheme) ? 443 : 80))
          ? "" : ":" + port;
      return scheme.toLowerCase(Locale.ROOT) + "://" + host + portPart + path
          + (parameters.isEmpty() ? "" : "?" + String.join("&", parameters));
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String clean(String value, int maxLength) {
    if (value == null) return "";
    String input = value.substring(0, Math.min(value.length(), maxLength * 4));
    String clean = input.replaceAll("(?is)<[^<>]*>", " ").replaceAll("\\s+", " ").trim();
    return clean.substring(0, Math.min(clean.length(), maxLength));
  }

  private String readBounded(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int count;
    while ((count = in.read(buffer)) != -1) {
      checkCancelled();
      if (out.size() + count > MAX_RESPONSE_BYTES)
        throw new IOException("联网检索返回数据过大，请缩小查询范围");
      out.write(buffer, 0, count);
    }
    return out.toString(StandardCharsets.UTF_8.name());
  }

  private void checkCancelled() {
    if (cancelled || Thread.currentThread().isInterrupted())
      throw new java.util.concurrent.CancellationException("联网检索已取消");
  }

  @FunctionalInterface
  interface ConnectionFactory {
    HttpURLConnection open(URL url) throws IOException;
  }

  public static final class Source {
    public final String id;
    public final String title;
    public final String url;
    public final String snippet;

    private Source(String id, String title, String url, String snippet) {
      this.id = id;
      this.title = title;
      this.url = url;
      this.snippet = snippet;
    }
  }
}
