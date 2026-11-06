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
        || result.optBoolean("is_from_local") || "ecdict".equals(result.optString("lookup_origin"))
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
    JSONObject next = FieldProvenance.modelResult(AgentProtocol.validateResult(incoming));
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
          FieldProvenance.fillMissing(old, item, FieldProvenance.MEANING_TEXT);
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
    FieldProvenance.fillMissing(merged, next, "phonetic");
    if (!next.optString("raw_explanation").isEmpty()) {
      merged.put("raw_explanation", next.get("raw_explanation"));
      FieldProvenance.transfer(next, "raw_explanation", merged, "raw_explanation");
    }
    if (next.optJSONArray("follow_up_questions") != null) {
      merged.put("follow_up_questions", next.getJSONArray("follow_up_questions"));
      FieldProvenance.transfer(next, "follow_up_questions", merged, "follow_up_questions");
    }
    merged.put("is_from_local", false);
    return merged;
  }

  static JSONArray contextCandidates(JSONObject result) throws JSONException {
    JSONArray source = result.getJSONArray("meanings"), candidates = new JSONArray();
    for (int i = 0; i < source.length(); i++) {
      JSONObject meaning = source.getJSONObject(i);
      JSONObject candidate = new JSONObject().put("id", meaning.getString("id"));
      for (String field : new String[] {"part_of_speech", "zh_definition", "en_definition"}) {
        String value = meaning.optString(field);
        if (!value.isEmpty()) candidate.put(field, value.substring(0, Math.min(1000, value.length())));
      }
      candidates.put(candidate);
    }
    return candidates;
  }

  static JSONObject validateDictionaryContext(JSONObject result, JSONObject reply) throws Exception {
    String selected = boundedText(reply, "selected_meaning_id", 120);
    String explanation = boundedText(reply, "explanation", 1800);
    JSONObject context = new JSONObject();
    if (!selected.isEmpty()) {
      meaning(result, selected);
      context.put("selected_meaning_id", selected);
    }
    if (!explanation.isEmpty()) context.put("explanation", explanation);
    JSONObject additional = reply.optJSONObject("additional_meaning");
    if (selected.isEmpty() && additional != null) {
      JSONObject suggestion = new JSONObject().put("id", "ai-context-" + UUID.randomUUID());
      for (String field : FieldProvenance.MEANING_TEXT) {
        String value = boundedText(additional, field, 1800);
        if (!value.isEmpty()) suggestion.put(field, value);
      }
      if (!suggestion.optString("zh_definition").isEmpty() || !suggestion.optString("en_definition").isEmpty()) {
        FieldProvenance.modelFields(suggestion, FieldProvenance.MEANING_TEXT);
        suggestion.put("lookup_origin", "ai");
        context.put("additional_meaning", suggestion);
      }
    }
    if (context.length() == 0) throw new IllegalArgumentException("AI 未返回可用的语境解释");
    FieldProvenance.modelFields(context, "selected_meaning_id", "explanation");
    return context;
  }

  static JSONObject validateWebVerification(JSONArray evidence, JSONObject reply) throws JSONException {
    String status = boundedText(reply, "status", 20);
    if (!Arrays.asList("supported", "conflicting", "insufficient").contains(status))
      throw new IllegalArgumentException("联网核对结果格式无效");
    Set<String> allowed = new HashSet<>();
    for (int i = 0; i < evidence.length(); i++) allowed.add(evidence.getJSONObject(i).getString("id"));
    JSONArray citations = reply.optJSONArray("source_ids"), verified = new JSONArray();
    Set<String> seen = new HashSet<>();
    for (int i = 0; citations != null && i < citations.length(); i++) {
      String id = citations.getString(i);
      if (!allowed.contains(id)) throw new IllegalArgumentException("联网核对引用了未检索到的来源");
      if (seen.add(id)) verified.put(id);
    }
    if (!status.equals("insufficient") && verified.length() == 0)
      throw new IllegalArgumentException("联网核对结论缺少引用依据");
    JSONObject out = new JSONObject().put("status", status).put("sources", evidence)
        .put("source_ids", verified);
    String explanation = boundedText(reply, "reason", 1800);
    String definition = boundedText(reply, "meaning", 500);
    if (!status.equals("insufficient") && explanation.isEmpty() && definition.isEmpty())
      throw new IllegalArgumentException("联网核对没有可展示的结论");
    if (!explanation.isEmpty()) out.put("reason", explanation);
    if (!definition.isEmpty() && !status.equals("insufficient")) out.put("meaning", definition);
    FieldProvenance.modelFields(out, "meaning", "reason", "source_ids");
    return out;
  }

  static JSONObject meaningFromVerification(JSONObject verification) {
    String definition = verification.optString("meaning").trim();
    JSONArray sourceIds = verification.optJSONArray("source_ids");
    JSONArray fetched = verification.optJSONArray("sources");
    if (!"supported".equals(verification.optString("status")) || definition.isEmpty()
        || sourceIds == null || fetched == null) return null;
    JSONArray cited = new JSONArray();
    for (int i = 0; i < fetched.length(); i++) {
      JSONObject source = fetched.optJSONObject(i);
      if (source == null) continue;
      for (int j = 0; j < sourceIds.length(); j++)
        if (source.optString("id").equals(sourceIds.optString(j))) {
          cited.put(Library.copy(source));
          break;
        }
    }
    if (cited.length() == 0) return null;
    JSONObject meaning = new JSONObject();
    Library.put(meaning, "id", Library.id());
    Library.put(meaning, "zh_definition", definition);
    Library.put(meaning, "remarks", verification.optString("reason"));
    Library.put(meaning, "source", "联网核对");
    Library.put(meaning, "lookup_origin", "ai");
    Library.put(meaning, "web_sources", cited);
    FieldProvenance.markPresent(meaning, true, "zh_definition", "remarks");
    FieldProvenance.markPresent(meaning, false, "source");
    return meaning;
  }

  private static String boundedText(JSONObject object, String field, int limit) {
    Object value = object.opt(field);
    if (value == null || value == JSONObject.NULL) return "";
    if (!(value instanceof String) || ((String) value).length() > limit)
      throw new IllegalArgumentException("结果字段格式无效：" + field);
    return ((String) value).trim();
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
        FieldProvenance.edited(meaning, key);
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
