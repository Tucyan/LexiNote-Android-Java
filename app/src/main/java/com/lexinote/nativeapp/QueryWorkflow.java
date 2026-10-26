package com.lexinote.nativeapp;

import java.util.*;
import org.json.*;

/** Query transitions, independent from Android views and network callbacks. */
final class QueryWorkflow {
  static final String[] FIELDS = {
    "zh_definition",
    "en_definition",
    "phonetic",
    "example",
    "source",
    "source_images",
    "annotations",
    "remarks"
  };

  static boolean shouldAutoRecord(JSONObject s, JSONObject settings) {
    if (s == null
        || !settings.optBoolean("auto_record_extra_info")
        || !s.optString("session_state").equals("ready")
        || s.optJSONObject("pending_lookup") != null) return false;
    JSONObject result = s.optJSONObject("query_results"), saved = s.optJSONObject("saved_content");
    if (result == null
        || result.optBoolean("is_from_local")
        || saved != null && saved.optBoolean("saved")
        || resultKey(s).equals(s.optString("auto_record_key"))) return false;
    try {
      AgentProtocol.validateResult(result);
      return true;
    } catch (Exception invalid) {
      return false;
    }
  }

  static String resultKey(JSONObject s) {
    JSONObject result = s.optJSONObject("query_results");
    return s.optString("id")
        + ":"
        + s.optInt("revision")
        + ":"
        + (result == null ? "" : AgentProtocol.normalize(result.optString("word")));
  }

  static JSONObject defaultFields(JSONObject settings) throws JSONException {
    JSONObject configured = settings.optJSONObject("default_fields_to_save"),
        fields = new JSONObject();
    for (String key : FIELDS)
      fields.put(
          key,
          configured == null
              ? !key.equals("annotations")
              : configured.optBoolean(key, !key.equals("annotations")));
    return fields;
  }

  static JSONObject mergeSupplement(JSONObject current, JSONObject incoming) throws Exception {
    JSONObject next = AgentProtocol.validateResult(incoming);
    if (!AgentProtocol.normalize(current.optString("word"))
        .equals(AgentProtocol.normalize(next.optString("word"))))
      throw new IllegalArgumentException("AI 补充更换了查询目标，请重新查询");
    JSONObject merged = new JSONObject(current.toString());
    JSONArray meanings = merged.getJSONArray("meanings"),
        additional = next.getJSONArray("meanings");
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < meanings.length(); i++) ids.add(meanings.getJSONObject(i).optString("id"));
    for (int i = 0; i < additional.length(); i++) {
      JSONObject item = additional.getJSONObject(i);
      boolean duplicate = false;
      for (int j = 0; j < meanings.length(); j++) {
        JSONObject old = meanings.getJSONObject(j);
        if (AgentProtocol.normalize(old.optString("zh_definition"))
                .equals(AgentProtocol.normalize(item.optString("zh_definition")))
            && AgentProtocol.normalize(old.optString("en_definition"))
                .equals(AgentProtocol.normalize(item.optString("en_definition")))) {
          duplicate = true;
          break;
        }
      }
      if (!duplicate) {
        JSONObject copy = new JSONObject(item.toString());
        String id = copy.optString("id");
        if (id.isEmpty() || ids.contains(id)) id = UUID.randomUUID().toString();
        copy.put("id", id);
        ids.add(id);
        meanings.put(copy);
      }
    }
    for (String field : new String[] {"raw_explanation", "phonetic"})
      if (!next.optString(field).isEmpty()) merged.put(field, next.get(field));
    if (next.optJSONArray("follow_up_questions") != null)
      merged.put("follow_up_questions", next.getJSONArray("follow_up_questions"));
    merged.put("is_from_local", false);
    return merged;
  }

  static JSONObject presentation(
      JSONObject r, String id, boolean selected, String choice, boolean example) throws Exception {
    if (!choice.equals("zh") && !choice.equals("en"))
      throw new IllegalArgumentException("请选择中文或英文释义");
    JSONObject copy = new JSONObject(r.toString()), meaning = meaning(copy, id);
    if (meaning.optString(choice.equals("zh") ? "zh_definition" : "en_definition").trim().isEmpty())
      throw new IllegalArgumentException("此释义没有所选语言的内容");
    meaning.put("selected", selected);
    meaning.put("definition_choice", choice);
    meaning.put("save_zh", choice.equals("zh"));
    meaning.put("save_en", choice.equals("en"));
    meaning.put("save_example", example);
    return copy;
  }

  static JSONObject editMeaning(JSONObject r, String id, JSONObject fields) throws Exception {
    JSONObject copy = new JSONObject(r.toString()), meaning = meaning(copy, id);
    for (String key :
        new String[] {
          "part_of_speech",
          "phonetic",
          "zh_definition",
          "en_definition",
          "example",
          "source",
          "remarks"
        })
      if (fields.has(key)) {
        Object value = fields.get(key);
        if (!(value instanceof String) || ((String) value).length() > 12000)
          throw new IllegalArgumentException("释义字段须为不超过12000字的文本");
        meaning.put(key, value);
      }
    if (meaning.optString("zh_definition").trim().isEmpty()
        && meaning.optString("en_definition").trim().isEmpty())
      throw new IllegalArgumentException("释义至少保留中文或英文说明");
    return copy;
  }

  private static JSONObject meaning(JSONObject r, String id) throws Exception {
    JSONArray meanings = r.getJSONArray("meanings");
    for (int i = 0; i < meanings.length(); i++)
      if (id.equals(meanings.getJSONObject(i).optString("id"))) return meanings.getJSONObject(i);
    throw new IllegalArgumentException("释义已删除，请重新打开词条");
  }
}
