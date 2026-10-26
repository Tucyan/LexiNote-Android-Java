package com.lexinote.nativeapp;

import org.json.*;

/** CRUD helpers preserving annotation provenance on a meaning. */
public final class Annotations {
  private Annotations() {}

  private static final String[] FIELDS = {
    "zh_definition", "en_definition", "example", "source", "remarks"
  };

  public static JSONObject add(
      JSONObject meaning, String field, String targetText, String content) {
    boolean valid = false;
    for (String candidate : FIELDS) if (candidate.equals(field)) valid = true;
    if (!valid) throw new IllegalArgumentException("批注字段无效");
    if (content == null || content.trim().isEmpty()) throw new IllegalArgumentException("批注内容不能为空");
    JSONArray annotations = meaning.optJSONArray("annotations");
    if (annotations == null) {
      annotations = new JSONArray();
      Library.put(meaning, "annotations", annotations);
    }
    JSONObject annotation = new JSONObject();
    Library.put(annotation, "id", Library.id());
    Library.put(annotation, "meaning_id", meaning.optString("id"));
    Library.put(annotation, "target_field", field);
    if (targetText != null && !targetText.trim().isEmpty())
      Library.put(annotation, "target_text", targetText.trim());
    Library.put(annotation, "content", content.trim());
    Library.put(annotation, "created_at", Library.now());
    Library.put(annotation, "updated_at", Library.now());
    annotations.put(annotation);
    return annotation;
  }

  public static void edit(
      JSONObject meaning, String id, String field, String targetText, String content) {
    JSONArray annotations = meaning.optJSONArray("annotations");
    if (annotations == null) throw new IllegalArgumentException("批注不存在");
    JSONObject found = null;
    for (int i = 0; i < annotations.length(); i++)
      if (id.equals(annotations.optJSONObject(i).optString("id")))
        found = annotations.optJSONObject(i);
    if (found == null) throw new IllegalArgumentException("批注不存在");
    if (content == null || content.trim().isEmpty()) throw new IllegalArgumentException("批注内容不能为空");
    boolean valid = false;
    for (String candidate : FIELDS) if (candidate.equals(field)) valid = true;
    if (!valid) throw new IllegalArgumentException("批注字段无效");
    Library.put(found, "target_field", field);
    if (targetText != null && !targetText.trim().isEmpty())
      Library.put(found, "target_text", targetText.trim());
    else found.remove("target_text");
    Library.put(found, "content", content.trim());
    Library.put(found, "updated_at", Library.now());
  }

  public static void remove(JSONObject meaning, String id) {
    Library.removeValue(meaning.optJSONArray("annotations"), id);
  }
}
