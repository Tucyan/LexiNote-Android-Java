package com.lexinote.nativeapp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Derived search fields. Callers supply a normalized query; each field remains separate. */
final class NotebookSearchIndex {
  private static final String[] MEANING_FIELDS = {
      "part_of_speech", "phonetic", "zh_definition", "en_definition", "example", "source", "remarks"
  };
  private static final int MAX_ENTRIES = 4096;
  private final Map<String, Projection> entries = new HashMap<>();

  boolean matches(JSONObject vocabulary, String normalizedQuery) {
    if (normalizedQuery.isEmpty()) return true;
    return snapshot(vocabulary).matches(normalizedQuery);
  }

  Projection snapshot(JSONObject vocabulary) {
    List<String> fields = fields(vocabulary);
    String id = vocabulary.optString("id", "");
    Projection previous = entries.get(id);
    if (previous == null || !previous.source.equals(fields)) {
      previous = new Projection(fields);
      if (!id.isEmpty()) {
        if (entries.size() >= MAX_ENTRIES && !entries.containsKey(id)) entries.clear();
        entries.put(id, previous);
      }
    }
    return previous;
  }

  private static List<String> fields(JSONObject vocabulary) {
    List<String> result = new ArrayList<>();
    result.add(vocabulary.optString("content"));
    JSONArray tags = vocabulary.optJSONArray("tags");
    for (int i = 0; tags != null && i < tags.length(); i++) result.add(tags.optString(i));
    JSONArray meanings = vocabulary.optJSONArray("meanings");
    for (int i = 0; meanings != null && i < meanings.length(); i++) {
      JSONObject meaning = meanings.optJSONObject(i);
      if (meaning == null) continue;
      for (String field : MEANING_FIELDS) result.add(meaning.optString(field));
      JSONArray annotations = meaning.optJSONArray("annotations");
      for (int j = 0; annotations != null && j < annotations.length(); j++) {
        JSONObject note = annotations.optJSONObject(j);
        if (note != null) {
          result.add(note.optString("content"));
          result.add(note.optString("target_text"));
        }
      }
    }
    return result;
  }

  static final class Projection {
    private final List<String> source;
    private final List<String> normalized = new ArrayList<>();
    Projection(List<String> source) {
      this.source = source;
      for (String value : source) normalized.add(NotebookSearch.norm(value));
    }
    boolean matches(String query) {
      if (query.isEmpty()) return true;
      for (String field : normalized) if (field.contains(query)) return true;
      return false;
    }
  }
}
