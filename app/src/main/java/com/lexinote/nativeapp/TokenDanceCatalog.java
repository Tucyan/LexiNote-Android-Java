package com.lexinote.nativeapp;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Offline snapshot of the audited TokenDance chat and vision catalog. */
public final class TokenDanceCatalog {
  private TokenDanceCatalog() {}
  private static final String[][] MODELS = {
    {"deepseek-v4.1-flash", "DeepSeek: DeepSeek V4.1 Flash"},
    {"kimi-k2.5", "MoonshotAI: Kimi K2.5"},
    {"glm-4.6v", "Z.ai: GLM 4.6V"},
    {"seed-2.0-pro", "ByteDance Seed: Seed-2.0-Pro"},
    {"seed-2.0-mini", "ByteDance Seed: Seed-2.0-Mini"},
    {"glm-5v-turbo", "Z.ai: GLM 5V Turbo"},
    {"qwen3-vl-plus", "Qwen: Qwen3 VL Plus"},
    {"kimi-k2.6", "MoonshotAI: Kimi K2.6"},
    {"minimax-m3.1-flash", "MiniMax: MiniMax M3.1 Flash"},
    {"qwen3.5-plus", "Qwen: Qwen3.5 Plus"},
    {"qwen3.5-flash", "Qwen: Qwen3.5 Flash"},
    {"qwen3.6-plus", "Qwen: Qwen3.6 Plus"},
    {"qwen3.7-plus", "Qwen: Qwen3.7 Plus"},
    {"step-3.7-flash", "StepFun: Step 3.7 Flash"},
    {"kimi-k2.7-code", "MoonshotAI: Kimi K2.7 Code"},
    {"seed-2.1-turbo", "ByteDance Seed: Seed-2.1-Turbo"},
    {"minimax-m3", "MiniMax: MiniMax M3"},
    {"seed-2.1-pro", "ByteDance Seed: Seed-2.1-Pro"},
    {"qwen3.8-max", "Qwen: Qwen3.8 Max"},
    {"qwen3.5-35b-a3b", "Qwen: Qwen3.5-35B-A3B"},
    {"glm-5.3-flash", "Z.ai: GLM 5.3 Flash"},
    {"dots-3-note-preview", "Dots Studio: Dots3-Note Preview（Free）"},
    {"qwen3.8-max-0902", "Qwen: Qwen3.8-Max-0902"},
    {"kimi-k3", "MoonshotAI: Kimi K3"},
    {"glm-5.3-flashx", "GLM: GLM 5.3 FlashX"},
    {"step-5-preview", "StepFun: Step 5 Preview"},
    {"qwen3.8-flash", "Qwen: Qwen3.8 Flash"},
    {"mimo-v2.6-pro", "Xiaomi: Mimo-V2.6-Pro"},
    {"mimo-v2.6-flash", "Xiaomi: Mimo-V2.6-Flash"},
    {"deepseek-v4.1-flash-1017", "DeepSeek: DeepSeek V4.1 Flash 1017"},
    {"seed-2.0-lite", "ByteDance Seed: Seed-2.0-Lite"},
    {"seed-2.0-code", "ByteDance Seed: Seed-2.0-Code"},
    {"seed-evolving", "ByteDance Seed: Seed-Evolving"},
    {"mimo-v2.6-ultraspeed", "Xiaomi: MiMo-V2.6-Pro-UltraSpeed"},
  };
  private static final Set<String> INCOMPATIBLE = new HashSet<>(Arrays.asList(
    "minimax-h3-max",
    "seedream-5.0-lite",
    "ateve-jev-v1",
    "mimo-v2.5",
    "qwen-image-2.1-pro",
    "glm-ocr",
    "qwen-text-embedding-v4",
    "deepseek-fetch",
    "qwen-image-2.1-turbo",
    "deepseek-search",
    "happyhorse-1.0-video-edit",
    "happyhorse-1.0-i2v",
    "happyhorse-1.0-t2v",
    "happyhorse-1.0-r2v",
    "deepseek-v4-pro",
    "mimo-v2.5-pro",
    "seedream-5.0-flash",
    "seedance-2.0",
    "seedance-2.0-fast",
    "mimo-v2.5-tts",
    "mimo-v2.5-tts-voicedesign",
    "mimo-v2.5-tts-voiceclone",
    "qwen3.7-max",
    "minimax-speech-2.8-hd",
    "minimax-speech-2.8-turbo",
    "bocha-web-search",
    "unifuncs-web-reader",
    "unifuncs-web-search",
    "happyhorse-1.1-r2v",
    "wan3.0-video",
    "seedance-2.0-mini",
    "deepseek-v4-flash",
    "kling-3.0",
    "happyhorse-1.1-t2v",
    "seedream-5.0-pro",
    "happyhorse-1.1-i2v",
    "deepseek-v4-flash-0731",
    "seedance-2.5",
    "wan3.0-video-prime",
    "kling-3.0-turbo",
    "seed-tts-2.0",
    "qwen3.7-text-embedding",
    "kling-3.0-omni",
    "qwen3.7-text-rerank",
    "qwen3.5-ocr",
    "deepseek-v4-pro-0813",
    "minimax-h3",
    "bocha-jev-v1",
    "qwen-audio-3.1-asr-flash",
    "qwen-audio-3.0-asr-flash",
    "yc-song-4.0",
    "qwen-audio-3.1-asr-flash-message",
    "yc-song-3.5"
  ));
  private static final Pattern VALID_ID = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,255}$");
  private static final Pattern BAD_ID = Pattern.compile("seedance|seedream|happyhorse|kling|(?:^|[/_.-])(?:tts|asr|ocr|embedding|rerank|image-generation|video)(?:$|[/_.-])", Pattern.CASE_INSENSITIVE);

  public static JSONArray models() {
    JSONArray result = new JSONArray();
    try {
      for (String[] model : MODELS) result.put(new JSONObject().put("id", model[0]).put("name", model[1]).put("vision_known", true).put("supports_vision", true));
    } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    return result;
  }

  public static boolean isKnown(String id) {
    for (String[] model : MODELS) if (model[0].equals(id)) return true;
    return false;
  }

  public static boolean isGateway(String baseUrl) {
    if (baseUrl == null) return false;
    try {
      URI uri = new URI(baseUrl.trim());
      return "https".equalsIgnoreCase(uri.getScheme())
          && "tokendance.space".equalsIgnoreCase(uri.getHost())
          && (uri.getPort() == -1 || uri.getPort() == 443)
          && uri.getRawPath() != null
          && "/gateway/v1".equals(uri.getRawPath().replaceAll("/+$", ""));
    } catch (Exception invalid) { return false; }
  }

  public static void assertChatModel(JSONObject provider) {
    if (provider == null || (!"tokendance".equals(provider.optString("id")) && !isGateway(provider.optString("base_url")))) return;
    String id = provider.optString("model_id", "");
    boolean invalid = !VALID_ID.matcher(id).matches()
        || id.toLowerCase(Locale.ROOT).contains("http://") || id.toLowerCase(Locale.ROOT).contains("https://")
        || INCOMPATIBLE.contains(id.toLowerCase(Locale.ROOT)) || BAD_ID.matcher(id).find()
        || (!isKnown(id) && !provider.optBoolean("is_custom_model_id", false));
    if (invalid) throw new IllegalArgumentException("TokenDance 模型不适用于图文对话，请从固定列表选择，或填写合法的自定义 Model ID。自定义入口仅用于兼容对话模型，不支持已知的纯文本、视频、绘图、音频、OCR 或已下线模型。");
  }
}
