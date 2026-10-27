package com.lexinote.nativeapp;

import java.util.Arrays;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** OpenAI-compatible provider-specific thinking options. */
public final class ModelOptions {
  private ModelOptions() {}

  /** Adds supported options to payload and returns the same object. */
  public static JSONObject apply(JSONObject payload, JSONObject provider) throws Exception {
    return apply(
        payload, provider, provider == null ? null : provider.optJSONArray("reasoning_levels"));
  }

  public static JSONObject apply(JSONObject payload, JSONObject provider, JSONArray supportedLevels)
      throws Exception {
    if (payload == null) throw new IllegalArgumentException("请求内容缺失");
    String model = provider == null ? "" : provider.optString("model_id").toLowerCase(Locale.ROOT);
    String effort =
        provider == null
            ? "off"
            : provider.optString("reasoning_effort", "off").trim().toLowerCase(Locale.ROOT);
    if (!Arrays.asList("off", "low", "medium", "high", "max").contains(effort)) effort = "off";
    if (!"off".equals(effort) && supportedLevels != null && supportedLevels.length() > 0) {
      boolean supported = false;
      for (int i = 0; i < supportedLevels.length(); i++)
        if (effort.equalsIgnoreCase(supportedLevels.optString(i))) supported = true;
      if (!supported) throw new IllegalArgumentException("所选模型不支持当前推理强度，请更改推理强度后重试");
    }
    if (model.matches(".*(deepseek|kimi|glm).*")) {
      payload.put(
          "thinking", new JSONObject().put("type", "off".equals(effort) ? "disabled" : "enabled"));
      if (!"off".equals(effort)) payload.put("reasoning_effort", effort);
    } else if (model.contains("qwen")) {
      payload.put("enable_thinking", !"off".equals(effort));
      if (!"off".equals(effort)) payload.put("reasoning_effort", effort);
    } else if (!"off".equals(effort)) payload.put("reasoning_effort", effort);
    return payload;
  }
}
