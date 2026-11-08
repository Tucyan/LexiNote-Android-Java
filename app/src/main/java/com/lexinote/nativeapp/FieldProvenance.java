package com.lexinote.nativeapp;

import org.json.JSONArray;
import org.json.JSONObject;

/** Per-field provenance recorded by the app at the point where content enters the library. */
final class FieldProvenance {
  private static final String META = "field_meta";
  private static final String GENERATED = "is_ai_generated";
  static final String[] MEANING_TEXT = {
    "part_of_speech", "zh_definition", "en_definition", "phonetic", "example", "source", "remarks"
  };

  private FieldProvenance() {}

  static boolean isGenerated(JSONObject object, String field) {
    if (object == null || field == null) return false;
    JSONObject meta = metadata(object, field);
    return meta != null && meta.optBoolean(GENERATED, false);
  }

  static boolean hasAnyGenerated(JSONObject object, String... fields) {
    if (object == null) return false;
    for (String field : fields) {
      if (isGenerated(object, field)) return true;
    }
    return false;
  }

  static void markPresent(JSONObject object, boolean generated, String... fields) {
    if (object == null) return;
    for (String field : fields) {
      if (hasContent(object, field)) set(object, field, generated);
      else clear(object, field);
    }
  }

  static void edited(JSONObject object, String field) {
    JSONObject old = metadata(object, field);
    markPresent(object, old != null && old.optBoolean(GENERATED), field);
  }

  static void transfer(JSONObject source, String sourceField, JSONObject target, String targetField) {
    JSONObject meta = metadata(source, sourceField);
    Object generated = meta == null ? null : meta.opt(GENERATED);
    if (generated instanceof Boolean && hasContent(target, targetField))
      set(target, targetField, (Boolean) generated);
    else clear(target, targetField);
  }

  static void fillMissing(JSONObject target, JSONObject source, String... fields) {
    for (String field : fields) {
      if (hasContent(target, field) || !hasContent(source, field)) continue;
      Library.put(target, field, source.opt(field));
      transfer(source, field, target, field);
    }
  }

  static JSONObject modelResult(JSONObject result) {
    for (String field : new String[] {"lookup_origin", "dictionary_source", "dictionary_context",
        "is_from_local", "local_vocabulary_id"})
      result.remove(field);
    modelFields(result, "word", "type", "phonetic", "recognized_text", "raw_explanation",
        "follow_up_questions", "tags");
    JSONArray meanings = result.optJSONArray("meanings");
    for (int i = 0; meanings != null && i < meanings.length(); i++) {
      JSONObject meaning = meanings.optJSONObject(i);
      if (meaning == null) continue;
      meaning.remove("source_images");
      meaning.remove("annotations");
      meaning.remove("lookup_origin");
      modelFields(meaning, MEANING_TEXT);
    }
    return result;
  }

  static JSONObject modelReply(JSONObject reply) {
    modelFields(reply, "message", "follow_up_questions");
    JSONObject result = reply.optJSONObject("result");
    if (result != null) modelResult(result);
    modelFields(reply.optJSONObject("lookup"), "target", "type", "context_quote", "user_requirement");
    JSONArray choices = reply.optJSONArray("choices");
    for (int i = 0; choices != null && i < choices.length(); i++)
      modelFields(choices.optJSONObject(i), "label");
    return reply;
  }

  static void modelFields(JSONObject object, String... fields) {
    if (object == null) return;
    object.remove(META);
    markPresent(object, true, fields);
  }

  private static boolean hasContent(JSONObject object, String field) {
    Object value = object.opt(field);
    if (value instanceof String) return !((String) value).trim().isEmpty();
    if (value instanceof JSONArray) return ((JSONArray) value).length() > 0;
    return value != null && value != JSONObject.NULL;
  }

  private static JSONObject metadata(JSONObject object, String field) {
    JSONObject fields = object.optJSONObject(META);
    return fields == null ? null : fields.optJSONObject(field);
  }

  private static void set(JSONObject object, String field, boolean generated) {
    JSONObject fields = object.optJSONObject(META);
    if (fields == null) {
      fields = new JSONObject();
      Library.put(object, META, fields);
    }
    JSONObject meta = new JSONObject();
    Library.put(meta, GENERATED, generated);
    Library.put(fields, field, meta);
  }

  private static void clear(JSONObject object, String field) {
    JSONObject fields = object.optJSONObject(META);
    if (fields == null) return;
    fields.remove(field);
    if (fields.length() == 0) object.remove(META);
  }
}
