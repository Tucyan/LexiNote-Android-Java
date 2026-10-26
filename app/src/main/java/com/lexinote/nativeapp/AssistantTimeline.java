package com.lexinote.nativeapp;

import org.json.JSONArray;
import org.json.JSONObject;

/** Pure row-window and anchor math shared by the assistant timeline and JVM tests. */
final class AssistantTimeline {
  static final int LIMIT = 200;

  private AssistantTimeline() {}

  static int start(JSONArray messages) {
    int count = 0;
    for (int i = messages.length() - 1; i >= 0; i--) {
      if (visible(messages.optJSONObject(i)) && ++count == LIMIT) return i;
    }
    return 0;
  }

  static int count(JSONArray messages) {
    int count = 0;
    for (int i = 0; i < messages.length(); i++) if (visible(messages.optJSONObject(i))) count++;
    return Math.min(LIMIT, count);
  }

  static JSONObject at(JSONArray messages, int position) {
    int seen = 0;
    for (int i = start(messages); i < messages.length(); i++) {
      JSONObject item = messages.optJSONObject(i);
      if (!visible(item)) continue;
      if (seen++ == position) return item;
    }
    return null;
  }

  static int positionOf(JSONArray messages, String id) {
    if (id == null || id.isEmpty()) return -1;
    int position = 0;
    for (int i = start(messages); i < messages.length(); i++) {
      JSONObject item = messages.optJSONObject(i);
      if (!visible(item)) continue;
      if (id.equals(item.optString("id"))) return position;
      position++;
    }
    return -1;
  }

  static String id(JSONArray messages, int position) {
    JSONObject item = at(messages, position);
    return item == null ? "" : item.optString("id", "");
  }

  static long stableId(String id) {
    long hash = 0xcbf29ce484222325L;
    for (int i = 0; i < id.length(); i++) {
      hash ^= id.charAt(i);
      hash *= 0x100000001b3L;
    }
    return hash;
  }

  private static boolean visible(JSONObject item) {
    if (item == null) return false;
    String role = item.optString("role");
    return "user".equals(role) || "assistant".equals(role);
  }
}
