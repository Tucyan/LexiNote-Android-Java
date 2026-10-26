package com.lexinote.nativeapp;

import java.util.*;
import org.json.*;

/** Markdown export of complete vocabulary content with notebook presentation applied. */
public final class NotebookExport {
  private NotebookExport() {}

  public static String markdown(List<JSONObject> vocabularies, JSONObject display) {
    StringBuilder out = new StringBuilder("# LexiNote\n\n");
    for (JSONObject vocab : vocabularies) {
      out.append("## ").append(escape(vocab.optString("content"))).append("\n");
      JSONArray tags = vocab.optJSONArray("tags");
      if (tags != null && tags.length() > 0) {
        out.append("\n");
        for (int i = 0; i < tags.length(); i++)
          out.append(codeSpan("#" + tags.optString(i))).append(" ");
        out.append("\n");
      }
      JSONObject fields = display == null ? null : display.optJSONObject("fields");
      JSONArray meanings = vocab.optJSONArray("meanings"),
          ids = display == null ? null : display.optJSONArray("meaning_ids");
      for (int i = 0; meanings != null && i < meanings.length(); i++) {
        JSONObject m = meanings.optJSONObject(i);
        if (ids != null && !has(ids, m.optString("id"))) continue;
        JSONObject localFields =
            display == null || display.optJSONObject("meaning_fields") == null
                ? null
                : display.optJSONObject("meaning_fields").optJSONObject(m.optString("id"));
        JSONObject effectiveFields = effectiveFields(fields, localFields);
        out.append("\n- **").append(escape(m.optString("part_of_speech"))).append("**");
        append(out, effectiveFields, "zh_definition", "中文", m.optString("zh_definition"));
        append(out, effectiveFields, "en_definition", "English", m.optString("en_definition"));
        append(out, effectiveFields, "phonetic", "音标", m.optString("phonetic"));
        append(out, effectiveFields, "example", "例句", m.optString("example"));
        append(out, effectiveFields, "source", "来源", m.optString("source"));
        append(out, effectiveFields, "remarks", "备注", m.optString("remarks"));
        if (on(effectiveFields, "annotations")) {
          JSONArray annotations = m.optJSONArray("annotations");
          for (int j = 0; annotations != null && j < annotations.length(); j++) {
            JSONObject a = annotations.optJSONObject(j);
            String target = a.optString("target_text");
            out.append("\n  - 批注");
            if (!target.isEmpty()) out.append("（").append(escape(target)).append("）");
            out.append("：").append(escape(a.optString("content")));
          }
        }
        if (on(effectiveFields, "source_images")) {
          JSONArray images = m.optJSONArray("source_images");
          for (int j = 0; images != null && j < images.length(); j++)
            out.append("\n  - 图片：").append(escape(images.optString(j)));
        }
        out.append("\n");
      }
      out.append("\n");
    }
    return out.toString();
  }

  public static String markdownForNotebook(List<JSONObject> vocabularies, JSONObject notebook) {
    JSONObject configs = notebook == null ? null : notebook.optJSONObject("vocabulary_display");
    StringBuilder out = new StringBuilder();
    for (JSONObject vocabulary : vocabularies) {
      JSONObject config =
          configs == null ? null : configs.optJSONObject(vocabulary.optString("id"));
      out.append(markdown(Collections.singletonList(vocabulary), config));
    }
    return out.toString();
  }

  private static void append(
      StringBuilder out, JSONObject fields, String key, String label, String value) {
    if (on(fields, key) && !value.isEmpty())
      out.append("\n  - ").append(label).append("：").append(escape(value));
  }

  private static JSONObject effectiveFields(JSONObject fields, JSONObject local) {
    if (fields == null && local == null) return null;
    JSONObject result = fields == null ? new JSONObject() : Library.copy(fields);
    if (local != null) {
      Iterator<String> keys = local.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        Library.put(result, key, local.opt(key));
      }
    }
    return result;
  }

  private static boolean on(JSONObject fields, String key) {
    return fields == null || fields.optBoolean(key, true);
  }

  private static boolean has(JSONArray ids, String value) {
    for (int i = 0; i < ids.length(); i++) if (value.equals(ids.optString(i))) return true;
    return false;
  }

  private static String escape(String value) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\r') continue;
      if (c == '\n') {
        out.append("<br>");
        continue;
      }
      if ("\\`*_{}[]<>()#+-.!|".indexOf(c) >= 0) out.append('\\');
      out.append(c);
    }
    return out.toString();
  }

  private static String codeSpan(String value) {
    int longest = 0, run = 0;
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) == '`') {
        run++;
        longest = Math.max(longest, run);
      } else run = 0;
    }
    StringBuilder fence = new StringBuilder(longest + 1);
    for (int i = 0; i <= longest; i++) fence.append('`');
    String delimiter = fence.toString();
    String content = value;
    if (content.startsWith("`")
        || content.endsWith("`")
        || content.startsWith(" ")
        || content.endsWith(" ")) content = " " + content + " ";
    return delimiter + content + delimiter;
  }
}
