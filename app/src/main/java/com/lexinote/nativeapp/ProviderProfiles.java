package com.lexinote.nativeapp;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pure provider metadata helpers. Credentials are deliberately outside this class. */
public final class ProviderProfiles {
  private static final Set<String> EFFORTS =
      new HashSet<>(Arrays.asList("off", "low", "medium", "high", "max"));

  private ProviderProfiles() {}

  public static JSONArray defaults() {
    JSONArray profiles = new JSONArray();
    try {
      profiles.put(
          preset(
              "tokendance",
              "TokenDance (词元跳动)",
              "https://tokendance.space/gateway/v1",
              "deepseek-v4.1-flash",
              true));
      profiles.put(
          preset(
              "deepseek",
              "DeepSeek (Flash 多模态)",
              "https://api.deepseek.com",
              "deepseek-flash",
              true));
      profiles.put(
          preset(
              "openai", "OpenAI (GPT-4o mini)", "https://api.openai.com/v1", "gpt-4o-mini", true));
      profiles.put(
          preset(
              "siliconflow",
              "SiliconFlow (硅基流动)",
              "https://api.siliconflow.cn/v1",
              "deepseek-ai/DeepSeek-V3",
              false));
      profiles.put(
          preset(
              "moonshot",
              "Moonshot (月之暗面)",
              "https://api.moonshot.cn/v1",
              "moonshot-v1-8k",
              false));
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
    return profiles;
  }

  public static JSONObject preset(String id) {
    JSONArray all = defaults();
    for (int i = 0; i < all.length(); i++)
      if (id.equals(all.optJSONObject(i).optString("id"))) return all.optJSONObject(i);
    return null;
  }

  private static JSONObject preset(String id, String name, String url, String model, boolean vision)
      throws Exception {
    return new JSONObject()
        .put("id", id)
        .put("name", name)
        .put("base_url", url)
        .put("model_id", model)
        .put("supports_vision", vision)
        .put("reasoning_effort", "off")
        .put("is_custom", false);
  }

  public static JSONObject sanitize(JSONObject input, boolean custom) {
    if (input == null) throw new IllegalArgumentException("缺少模型配置");
    try {
      String id = input.optString("id", "").trim();
      String name = input.optString("name", "").trim();
      String model = input.optString("model_id", "").trim();
      String base = normalizeBase(input.optString("base_url", ""));
      if (id.isEmpty()) throw new IllegalArgumentException("缺少配置 ID");
      if (name.isEmpty()) throw new IllegalArgumentException("请填写服务商名称");
      if (model.isEmpty()) throw new IllegalArgumentException("请填写模型 ID");
      String effort = input.optString("reasoning_effort", "off").trim().toLowerCase(Locale.ROOT);
      if (!EFFORTS.contains(effort)) throw new IllegalArgumentException("推理强度无效");
      JSONObject safe =
          new JSONObject()
              .put("id", id)
              .put("name", name)
              .put("base_url", base)
              .put("model_id", model)
              .put("supports_vision", input.optBoolean("supports_vision", false))
              .put("reasoning_effort", effort)
              .put("is_custom", custom)
              .put("is_custom_model_id", input.optBoolean("is_custom_model_id", false))
              .put("is_custom_base_url", input.optBoolean("is_custom_base_url", false));
      JSONArray levels = input.optJSONArray("reasoning_levels");
      if (input.has("vision_known")) safe.put("vision_known", input.optBoolean("vision_known"));
      if (levels != null) {
        JSONArray clean = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < levels.length(); i++) {
          String level = levels.optString(i, "").trim().toLowerCase(Locale.ROOT);
          if (EFFORTS.contains(level) && seen.add(level)) clean.put(level);
        }
        safe.put("reasoning_levels", clean);
      }
      return safe;
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("模型配置无效", e);
    }
  }

  public static String normalizeBase(String value) {
    String base = value == null ? "" : value.trim();
    try {
      URI uri = URI.create(base);
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null) throw new IllegalArgumentException("请输入有效的 HTTPS 服务地址");
      String normalized = uri.normalize().toString();
      while (normalized.endsWith("/")
          && normalized.length() > (uri.getScheme() + "://" + uri.getAuthority()).length())
        normalized = normalized.substring(0, normalized.length() - 1);
      return normalized;
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("请输入有效的 HTTPS 服务地址", e);
    }
  }

  public static String destinationKey(String baseUrl) {
    URI uri = URI.create(normalizeBase(baseUrl));
    int port = uri.getPort();
    String authority =
        uri.getHost().toLowerCase(Locale.ROOT)
            + (port < 0
                    || (port == 443 && "https".equalsIgnoreCase(uri.getScheme()))
                    || (port == 80 && "http".equalsIgnoreCase(uri.getScheme()))
                ? ""
                : ":" + port);
    String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
    return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + authority + path;
  }
}
