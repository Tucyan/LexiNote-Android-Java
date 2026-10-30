package com.lexinote.nativeapp;

import org.json.*;

/** In-memory settings draft. Credentials never enter its portable JSON snapshot. */
final class ProviderDraft {
  private final JSONObject value;
  private String enteredKey = "";
  private long revision;

  ProviderDraft(JSONObject provider) {
    value = Library.copy(provider);
  }

  JSONObject config() {
    return Library.copy(value);
  }

  long revision() {
    return revision;
  }

  String enteredKey() {
    return enteredKey;
  }

  void setKey(String key) {
    String next = key == null ? "" : key;
    if (!enteredKey.equals(next)) {
      enteredKey = next;
      revision++;
    }
  }

  void set(String field, Object content) {
    if (java.util.Objects.equals(value.opt(field), content)) return;
    Library.put(value, field, content);
    if (field.equals("base_url") || field.equals("model_id")) {
      value.remove("reasoning_levels");
      value.remove("vision_known");
    }
    revision++;
  }

  void selectModel(JSONObject model) {
    set("model_id", model.optString("id"));
    set("is_custom_model_id", false);
    set("vision_known", model.optBoolean("vision_known"));
    set("supports_vision", model.optBoolean("vision_known") && model.optBoolean("supports_vision"));
    JSONArray levels = model.optJSONArray("reasoning_levels");
    if (levels != null) {
      JSONArray copy = new JSONArray();
      for (int i = 0; i < levels.length(); i++) copy.put(levels.optString(i));
      Library.put(value, "reasoning_levels", copy);
      boolean allowed = value.optString("reasoning_effort", "off").equals("off");
      for (int i = 0; i < levels.length(); i++)
        if (levels.optString(i).equals(value.optString("reasoning_effort"))) allowed = true;
      if (!allowed) {
        set("reasoning_effort", "off");
        value.remove("reasoning_gear");
      }
    } else value.remove("reasoning_levels");
    revision++;
  }

  boolean matches(String id, long captured) {
    return value.optString("id").equals(id) && captured == revision;
  }
}
