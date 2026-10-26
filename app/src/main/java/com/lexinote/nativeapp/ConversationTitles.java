package com.lexinote.nativeapp;

import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Owns cancellable background titles, independently of the active chat request. */
final class ConversationTitles {
  final MainActivity a;
  final ExecutorService executor = Executors.newSingleThreadExecutor();
  final Map<String, AiClient> pending = new HashMap<>();
  long epoch;

  ConversationTitles(MainActivity activity) {
    a = activity;
  }

  void schedule(JSONObject session) {
    if (session == null || a.isFinishing() || a.isDestroyed()) return;
    String id = session.optString("id");
    JSONObject meta = session.optJSONObject("metadata");
    if (meta == null
        || !meta.optString("title_source", "fallback").equals("fallback")
        || pending.containsKey(id)) return;
    JSONArray messages = session.optJSONArray("ai_messages");
    String user = "", assistant = "";
    for (int i = 0; messages != null && i < messages.length(); i++) {
      JSONObject m = messages.optJSONObject(i);
      if (m == null) continue;
      if (user.isEmpty() && m.optString("role").equals("user")) user = m.optString("content");
      if (!user.isEmpty() && m.optString("role").equals("assistant")) {
        assistant = m.optString("content");
        break;
      }
    }
    if (user.isEmpty() || assistant.isEmpty()) return;
    final AiClient client;
    try {
      String key = a.secure.getApiKey();
      if (key.isEmpty()) return;
      client = new AiClient(a.secure.getProvider(), key);
    } catch (Exception unavailable) {
      return;
    }
    final int revision = meta.optInt("title_revision");
    final long capturedEpoch = epoch;
    final String firstUser = user, firstAssistant = assistant;
    pending.put(id, client);
    executor.execute(
        () -> {
          String candidate;
          try {
            candidate = client.suggestTitle(firstUser, firstAssistant);
          } catch (Exception ignored) {
            candidate = "";
          }
          final String title = candidate.length() > 40 ? candidate.substring(0, 40) : candidate;
          a.handler.post(
              () -> {
                if (pending.get(id) != client) return;
                pending.remove(id);
                if (a.isDestroyed() || a.isFinishing() || title.isEmpty()) return;
                JSONObject current = a.library.find("conversations", id);
                JSONObject currentMeta = current == null ? null : current.optJSONObject("metadata");
                boolean fallback =
                    currentMeta != null
                        && currentMeta.optString("title_source", "fallback").equals("fallback");
                if (currentMeta == null
                    || !TitleSuggestionGuard.mayApply(
                        current != null,
                        !fallback,
                        fallback ? "" : currentMeta.optString("title"),
                        revision,
                        currentMeta.optInt("title_revision"),
                        capturedEpoch,
                        epoch)) return;
                Library.put(currentMeta, "title", title);
                Library.put(currentMeta, "title_source", "ai");
                Library.put(currentMeta, "title_revision", revision + 1);
                Library.put(current, "title", title);
                a.library.touchSession(current, false);
                if (a.session != null && id.equals(a.session.optString("id"))) {
                  Library.put(a.session, "title", title);
                  Library.put(a.session, "metadata", Library.copy(currentMeta));
                }
                if (a.persist()) {
                  // A title update must not rebuild a composer or reset a reading position.
                  android.widget.TextView heading = a.root.findViewWithTag("page-title");
                  if (heading != null
                      && a.screen.equals("assistant")
                      && a.session != null
                      && id.equals(a.session.optString("id"))) heading.setText(title);
                  else if (a.screen.equals("tabs") && a.page == 0) a.render();
                }
              });
        });
  }

  void manual(JSONObject session) {
    if (session == null) return;
    AiClient client = pending.remove(session.optString("id"));
    if (client != null) client.cancel();
    JSONObject meta = session.optJSONObject("metadata");
    if (meta != null) {
      Library.put(meta, "title_source", "user");
      Library.put(meta, "title_revision", meta.optInt("title_revision") + 1);
    }
  }

  void remove(String id) {
    AiClient c = pending.remove(id);
    if (c != null) c.cancel();
  }

  void reset() {
    epoch++;
    for (AiClient c : pending.values()) c.cancel();
    pending.clear();
  }

  void close() {
    reset();
    executor.shutdownNow();
  }
}
