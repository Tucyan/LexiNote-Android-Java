package com.lexinote.nativeapp;

import java.net.URI;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class AgentProtocol {
  private AgentProtocol() {}

  public static String normalize(String text) {
    return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
        .trim()
        .replaceAll("\\s+", " ")
        .toLowerCase(Locale.ROOT);
  }

  public static boolean canApplyDirectResult(
      String input, JSONObject result, boolean fromQueryHub, boolean hasImage) {
    return fromQueryHub
        && !hasImage
        && normalize(input).equals(normalize(result.optString("word")))
        && input.trim().matches("[a-zA-Z][a-zA-Z'’\\-]*(?:\\s+[a-zA-Z][a-zA-Z'’\\-]*){0,7}");
  }

  public static String endpoint(String base) {
    try {
      URI uri = new URI(base.trim());
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null) throw new IllegalArgumentException();
      String value = uri.toString().replaceAll("/+$", "");
      return value.endsWith("/chat/completions") ? value : value + "/chat/completions";
    } catch (Exception e) {
      throw new IllegalArgumentException("接口地址须为不含用户名、密码或查询参数的 HTTPS 地址");
    }
  }

  static JSONObject json(String text) throws JSONException {
    if (text == null || text.length() > 1024 * 1024) throw invalid("模型回复过大或为空");
    String clean =
        text.trim().replaceFirst("(?i)^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
    return new JSONObject(clean);
  }

  public static JSONObject parse(String raw) throws JSONException {
    JSONObject reply = json(raw);
    String kind = reply.optString("kind");
    if ("lookup_result".equals(kind)) {
      reply.put("result", validateResult(reply.getJSONObject("result")));
      return reply;
    }
    if (!java.util.Arrays.asList("clarify", "confirm_lookup", "answer", "request_input")
        .contains(kind)) throw invalid("模型返回了未知动作");
    reply.put("message", text(reply, "message", 24000, true));
    if ("confirm_lookup".equals(kind)) {
      JSONObject lookup = reply.getJSONObject("lookup");
      String target = text(lookup, "target", 200, true);
      if (target.contains("\n")
          || target.contains("\r")
          || !java.util.Arrays.asList("word", "phrase").contains(lookup.optString("type")))
        throw invalid("查询目标须为单词或短语");
      lookup.put("target", target);
    }
    JSONArray choices = reply.optJSONArray("choices");
    if (choices != null) {
      if (choices.length() > 8) throw invalid("澄清选项过多");
      Set<String> ids = new HashSet<>();
      for (int i = 0; i < choices.length(); i++) {
        JSONObject choice = choices.getJSONObject(i);
        if (!ids.add(text(choice, "id", 100, true))) throw invalid("澄清选项重复");
        text(choice, "label", 200, true);
      }
    }
    reply.put("follow_up_questions", suggestions(reply.optJSONArray("follow_up_questions")));
    return reply;
  }

  public static JSONObject validateResult(JSONObject input) throws JSONException {
    JSONObject result = new JSONObject(input.toString());
    result.put("word", text(result, "word", 200, true));
    if (!java.util.Arrays.asList("word", "phrase").contains(result.optString("type"))
        || result.optBoolean("is_invalid")) throw invalid("尚未获得有效词汇，请先确认查询目标");
    JSONArray meanings = result.optJSONArray("meanings");
    if (meanings == null || meanings.length() == 0 || meanings.length() > 30)
      throw invalid("释义数量无效");
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < meanings.length(); i++) {
      JSONObject meaning = meanings.getJSONObject(i);
      String zh = text(meaning, "zh_definition", 12000, false);
      String en = text(meaning, "en_definition", 12000, false);
      if (zh.isEmpty() && en.isEmpty()) throw invalid("释义须至少包含中文或英文解释");
      String id = meaning.optString("id").trim();
      if (id.isEmpty()) id = "meaning-" + i;
      if (!ids.add(id)) throw invalid("释义 ID 重复");
      meaning.put("id", id);
      for (String key : new String[] {"part_of_speech", "example", "source", "remarks", "phonetic"})
        text(meaning, key, 12000, false);
    }
    result.put("follow_up_questions", suggestions(result.optJSONArray("follow_up_questions")));
    return result;
  }

  static JSONArray suggestions(JSONArray source) throws JSONException {
    JSONArray out = new JSONArray();
    Set<String> seen = new HashSet<>();
    if (source != null)
      for (int i = 0; i < source.length() && out.length() < 3; i++) {
        Object value = source.get(i);
        if (!(value instanceof String)) continue;
        String item = ((String) value).trim();
        if (!item.isEmpty()
            && item.codePointCount(0, item.length()) <= 40
            && seen.add(normalize(item))) out.put(item);
      }
    return out;
  }

  private static String text(JSONObject object, String key, int max, boolean required) {
    Object value = object.opt(key);
    if ((value == null || value == JSONObject.NULL) && !required) return "";
    if (!(value instanceof String)
        || ((String) value).length() > max
        || (required && ((String) value).trim().isEmpty())) throw invalid("模型字段格式无效：" + key);
    return ((String) value).trim();
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
