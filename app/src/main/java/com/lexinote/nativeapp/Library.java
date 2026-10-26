package com.lexinote.nativeapp;

import java.text.Normalizer;
import java.util.*;
import org.json.*;

/** JSON-preserving domain model, independent from Android. */
public final class Library {
  private JSONObject data;

  public static String id() {
    return UUID.randomUUID().toString();
  }

  public static String now() {
    return java.time.Instant.now().toString();
  }

  public static JSONObject copy(JSONObject v) {
    try {
      return new JSONObject(v.toString());
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
  }

  public static void put(JSONObject o, String k, Object v) {
    try {
      o.put(k, v);
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
  }

  public Library() {
    data = new JSONObject();
    put(data, "version", "1.1");
    for (String k : new String[] {"vocabularies", "notebooks", "recent_queries", "conversations"})
      put(data, k, new JSONArray());
    put(data, "settings", new JSONObject());
    normalizeSystemRoots();
  }

  public Library(JSONObject source) {
    validate(source);
    data = copy(source);
    for (String k : new String[] {"recent_queries", "conversations"})
      if (!data.has(k)) put(data, k, new JSONArray());
    if (!data.has("settings")) put(data, "settings", new JSONObject());
    normalizeSystemRoots();
  }

  public static boolean systemGlobal(JSONObject n) {
    return n != null
        && n.optString("id").equals("nb_global_root")
        && n.optBoolean("is_global_root");
  }

  private void normalizeSystemRoots() {
    for (String[] spec :
        new String[][] {
          {"nb_default", "默认笔记", "is_default"}, {"nb_global_root", "全部词汇", "is_global_root"}
        }) {
      JSONObject n = find("notebooks", spec[0]);
      if (n == null) {
        n = new JSONObject();
        put(n, "id", spec[0]);
        put(n, "name", spec[1]);
        put(n, spec[2], true);
        put(n, "children_type", "vocabulary");
        put(n, "child_notebook_ids", new JSONArray());
        put(n, "vocabulary_ids", new JSONArray());
        put(n, "vocabulary_display", new JSONObject());
        put(n, "created_at", now());
        put(n, "updated_at", now());
        array("notebooks").put(n);
      }
      if (!n.optBoolean(spec[2]) || n.optJSONArray("child_notebook_ids").length() > 0)
        throw new IllegalArgumentException("系统笔记结构无效");
    }
    JSONObject current = find("notebooks", settings().optString("default_notebook_id"));
    if (current == null
        || systemGlobal(current)
        || current.optJSONArray("child_notebook_ids").length() > 0)
      put(settings(), "default_notebook_id", "nb_default");
    rebuildGlobal();
  }

  private void rebuildGlobal() {
    JSONObject global = find("notebooks", "nb_global_root");
    if (global == null) return;
    JSONArray ids = new JSONArray();
    JSONArray vs = array("vocabularies");
    for (int i = 0; i < vs.length(); i++) ids.put(vs.optJSONObject(i).optString("id"));
    put(global, "vocabulary_ids", ids);
  }

  public JSONArray array(String k) {
    JSONArray a = data.optJSONArray(k);
    return a == null ? new JSONArray() : a;
  }

  public JSONObject settings() {
    return data.optJSONObject("settings");
  }

  public JSONObject snapshot() {
    return copy(data);
  }

  public JSONObject find(String key, String id) {
    JSONArray a = array(key);
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      if (o != null && id.equals(o.optString("id"))) return o;
    }
    return null;
  }

  public static String normalized(String s) {
    return Normalizer.normalize(s, Normalizer.Form.NFKC)
        .replaceAll("[\\s\\p{Z}]+", " ")
        .trim()
        .toLowerCase(Locale.ROOT);
  }

  public JSONObject local(String word) {
    JSONArray a = array("vocabularies");
    for (int i = 0; i < a.length(); i++) {
      JSONObject v = a.optJSONObject(i);
      if (normalized(word).equals(normalized(v.optString("content")))) return v;
    }
    return null;
  }

  public void renameVocabulary(String id, String content) {
    String word = content.trim();
    if (word.isEmpty()) throw new IllegalArgumentException("词汇不能为空");
    JSONObject current = find("vocabularies", id), existing = local(word);
    if (current == null) throw new IllegalArgumentException("词汇不存在");
    if (existing != null && !existing.optString("id").equals(id))
      throw new IllegalArgumentException("该词汇已存在，请编辑现有实体");
    put(current, "content", word);
    put(current, "updated_at", now());
  }

  public String createNotebook(String name, String parent) {
    if (name.trim().isEmpty()) throw new IllegalArgumentException("名称不能为空");
    JSONObject p = parent == null ? null : find("notebooks", parent);
    if (parent != null && p == null) throw new IllegalArgumentException("父笔记不存在");
    if (p != null && (p.optBoolean("is_default") || p.optBoolean("is_global_root")))
      throw new IllegalArgumentException("系统笔记不能添加子笔记");
    if (p != null && p.optJSONArray("vocabulary_ids").length() > 0)
      throw new IllegalArgumentException("该笔记已有词汇，不能混合子笔记");
    JSONObject n = new JSONObject();
    String id = id();
    put(n, "id", id);
    put(n, "name", name.trim());
    put(n, "parent_id", parent == null ? JSONObject.NULL : parent);
    put(n, "children_type", "vocabulary");
    put(n, "child_notebook_ids", new JSONArray());
    put(n, "vocabulary_ids", new JSONArray());
    put(n, "vocabulary_display", new JSONObject());
    put(n, "created_at", now());
    put(n, "updated_at", now());
    array("notebooks").put(n);
    if (p != null) {
      p.optJSONArray("child_notebook_ids").put(id);
      put(p, "children_type", "notebook");
    }
    return id;
  }

  public void deleteNotebook(String id) {
    JSONObject n = find("notebooks", id);
    if (n == null) return;
    if (n.optBoolean("is_default")
        || n.optBoolean("is_global_root")
        || id.equals(settings().optString("default_notebook_id")))
      throw new IllegalArgumentException("默认笔记和全局视图不能删除");
    JSONArray children = n.optJSONArray("child_notebook_ids");
    for (int i = children.length() - 1; i >= 0; i--) deleteNotebook(children.optString(i));
    for (int i = array("notebooks").length() - 1; i >= 0; i--) {
      JSONObject p = array("notebooks").optJSONObject(i);
      removeValue(p.optJSONArray("child_notebook_ids"), id);
      if (id.equals(p.optString("id"))) array("notebooks").remove(i);
    }
  }

  public static void removeValue(JSONArray a, String id) {
    if (a == null) return;
    for (int i = a.length() - 1; i >= 0; i--)
      if (id.equals(a.optString(i))
          || a.optJSONObject(i) != null && id.equals(a.optJSONObject(i).optString("id")))
        a.remove(i);
  }

  public String saveResult(JSONObject r, String[] notebooks, JSONObject display) {
    String word = r.optString("word", r.optString("content")).trim();
    if (word.isEmpty()
        || r.optJSONArray("meanings") == null
        || r.optJSONArray("meanings").length() == 0)
      throw new IllegalArgumentException("词汇与释义不能为空");
    JSONArray checking = r.optJSONArray("meanings");
    for (int i = 0; i < checking.length(); i++) {
      JSONObject m = checking.optJSONObject(i);
      if (m == null
          || m.optString("zh_definition").trim().isEmpty()
              && m.optString("en_definition").trim().isEmpty())
        throw new IllegalArgumentException("每条释义至少需要中文或英文说明");
    }
    for (String n : notebooks) {
      JSONObject nb = find("notebooks", n);
      if (nb == null || nb.optJSONArray("child_notebook_ids").length() > 0)
        throw new IllegalArgumentException("请选择可容纳词汇的末级笔记");
    }
    JSONObject v = local(word);
    if (v == null) {
      v = copy(r);
      put(v, "id", id());
      put(v, "content", word);
      put(v, "type", r.optString("type", word.contains(" ") ? "phrase" : "word"));
      put(v, "created_at", now());
      put(v, "meanings", new JSONArray());
      array("vocabularies").put(v);
    }
    JSONArray target = v.optJSONArray("meanings"), incoming = r.optJSONArray("meanings");
    for (int i = 0; i < incoming.length(); i++) {
      JSONObject m = copy(incoming.optJSONObject(i));
      boolean exists = false;
      for (int j = 0; j < target.length(); j++) {
        JSONObject old = target.optJSONObject(j);
        if (old.optString("part_of_speech").equals(m.optString("part_of_speech"))
            && old.optString("zh_definition").equals(m.optString("zh_definition"))
            && old.optString("en_definition").equals(m.optString("en_definition"))) {
          exists = true;
          mergeMeaning(old, m);
        }
      }
      if (!exists) {
        put(m, "id", id());
        put(m, "created_at", now());
        put(m, "updated_at", now());
        if (!m.has("annotations")) put(m, "annotations", new JSONArray());
        target.put(m);
      }
    }
    String phonetic = r.optString("phonetic").trim();
    if (v.optString("phonetic").trim().isEmpty() && !phonetic.isEmpty())
      put(v, "phonetic", phonetic);
    JSONArray incomingTags = r.optJSONArray("tags");
    if (incomingTags != null) {
      JSONArray tags = v.optJSONArray("tags");
      if (tags == null) {
        tags = new JSONArray();
        put(v, "tags", tags);
      }
      for (int i = 0; i < incomingTags.length(); i++) {
        String tag = incomingTags.optString(i);
        boolean exists = false;
        for (int j = 0; j < tags.length(); j++) if (tag.equals(tags.optString(j))) exists = true;
        if (!exists) tags.put(tag);
      }
    }
    put(v, "updated_at", now());
    for (String n : notebooks) {
      JSONObject nb = find("notebooks", n);
      removeValue(nb.optJSONArray("vocabulary_ids"), v.optString("id"));
      nb.optJSONArray("vocabulary_ids").put(v.optString("id"));
      if (nb.optJSONObject("vocabulary_display") == null)
        put(nb, "vocabulary_display", new JSONObject());
      JSONObject d = copy(display);
      if (!d.has("meaning_ids")) {
        JSONArray ids = new JSONArray();
        for (int i = 0; i < target.length(); i++) ids.put(target.optJSONObject(i).optString("id"));
        put(d, "meaning_ids", ids);
      }
      put(nb.optJSONObject("vocabulary_display"), v.optString("id"), d);
    }
    rebuildGlobal();
    return v.optString("id");
  }

  public JSONObject newSession(String text) {
    JSONObject s = new JSONObject();
    put(s, "id", id());
    put(s, "title", text.isEmpty() ? "新会话" : text.substring(0, Math.min(40, text.length())));
    put(s, "input_text", text);
    put(s, "created_at", now());
    put(s, "updated_at", now());
    put(s, "ai_messages", new JSONArray());
    put(s, "saved", false);
    put(s, "revision", 0);
    put(s, "query_type", "text");
    JSONObject metadata = new JSONObject();
    for (String key : new String[] {"title", "created_at", "updated_at"})
      put(metadata, key, s.opt(key));
    put(metadata, "title_source", "fallback");
    put(metadata, "title_revision", 0);
    put(metadata, "retention", "temporary");
    put(metadata, "origin", "query");
    put(metadata, "last_used_at", now());
    put(s, "metadata", metadata);
    return s;
  }

  public void touchSession(JSONObject s, boolean history) {
    put(s, "updated_at", now());
    if (s.optJSONObject("metadata") != null) {
      put(s.optJSONObject("metadata"), "updated_at", s.opt("updated_at"));
      put(s.optJSONObject("metadata"), "last_used_at", s.opt("updated_at"));
    }
    upsert("conversations", s);
    int temporary = 0;
    JSONArray a = array("conversations");
    for (int i = a.length() - 1; i >= 0; i--)
      if (!isSaved(a.optJSONObject(i))) {
        temporary++;
        if (temporary > 5) a.remove(i);
      }
    if (history) {
      upsert("recent_queries", copy(s));
      while (array("recent_queries").length() > 10) array("recent_queries").remove(0);
    }
  }

  public static boolean isSaved(JSONObject s) {
    return s.optBoolean("saved")
        || s.optJSONObject("metadata") != null
            && s.optJSONObject("metadata").optString("retention").equals("saved");
  }

  private static void mergeMeaning(JSONObject old, JSONObject incoming) {
    Iterator<String> keys = incoming.keys();
    while (keys.hasNext()) {
      String k = keys.next();
      Object value = incoming.opt(k);
      if (value instanceof JSONArray) {
        JSONArray target = old.optJSONArray(k);
        if (target == null) {
          put(old, k, value);
          continue;
        }
        JSONArray arr = (JSONArray) value;
        for (int i = 0; i < arr.length(); i++) {
          Object item = arr.opt(i);
          boolean found = false;
          for (int j = 0; j < target.length(); j++)
            if (target.opt(j).toString().equals(item.toString())) found = true;
          if (!found) target.put(item);
        }
      } else if (!k.equals("id")
          && !k.equals("created_at")
          && (old.optString(k).isEmpty() || old.isNull(k))) put(old, k, value);
    }
  }

  private void upsert(String key, JSONObject s) {
    JSONArray a = array(key);
    for (int i = a.length() - 1; i >= 0; i--)
      if (s.optString("id").equals(a.optJSONObject(i).optString("id"))) a.remove(i);
    a.put(copy(s));
  }

  public void delete(String key, String id) {
    JSONArray a = array(key);
    for (int i = a.length() - 1; i >= 0; i--)
      if (id.equals(a.optJSONObject(i).optString("id"))) a.remove(i);
  }

  public JSONObject backup() {
    JSONObject b = copy(data);
    put(b, "version", "1.1");
    put(b, "export_date", now());
    redact(b);
    return b;
  }

  private static void redact(Object v) {
    if (v instanceof JSONObject) {
      JSONObject o = (JSONObject) v;
      List<String> keys = new ArrayList<>();
      Iterator<String> it = o.keys();
      while (it.hasNext()) keys.add(it.next());
      for (String k : keys) {
        if (k.toLowerCase(Locale.ROOT).matches(".*(api.?key|secret|authorization|token).*")
            || k.equals("providers")) o.remove(k);
        else redact(o.opt(k));
      }
    } else if (v instanceof JSONArray) {
      JSONArray a = (JSONArray) v;
      for (int i = 0; i < a.length(); i++) redact(a.opt(i));
    }
  }

  public static void validate(JSONObject d) {
    String version = d.optString("version", "1.1");
    if (!Arrays.asList("1.0", "1.1", "2.0", "2.1", "2.2").contains(version))
      throw new IllegalArgumentException("不支持的备份版本");
    for (String key : new String[] {"recent_queries", "conversations"}) {
      if (d.has(key) && d.optJSONArray(key) == null) throw new IllegalArgumentException("会话集合结构无效");
      JSONArray sessions = d.optJSONArray(key);
      if (sessions != null) {
        ids(sessions);
        for (int i = 0; i < sessions.length(); i++) {
          JSONObject session = sessions.optJSONObject(i);
          if (session.has("ai_messages") && session.optJSONArray("ai_messages") == null)
            throw new IllegalArgumentException("会话消息格式无效");
          JSONArray messages = session.optJSONArray("ai_messages");
          if (messages != null)
            for (int j = 0; j < messages.length(); j++) {
              JSONObject m = messages.optJSONObject(j);
              if (m == null
                  || !Arrays.asList("user", "assistant", "system").contains(m.optString("role"))
                  || !(m.opt("content") instanceof String))
                throw new IllegalArgumentException("会话消息内容无效");
            }
          if (session.has("query_results") && !session.isNull("query_results")) {
            JSONObject result = session.optJSONObject("query_results");
            if (result == null || result.optJSONArray("meanings") == null)
              throw new IllegalArgumentException("会话词条无效");
            for (int j = 0; j < result.optJSONArray("meanings").length(); j++)
              if (result.optJSONArray("meanings").optJSONObject(j) == null)
                throw new IllegalArgumentException("会话释义无效");
          }
          if (session.has("metadata") && session.optJSONObject("metadata") == null)
            throw new IllegalArgumentException("会话元数据无效");
        }
      }
    }
    if (d.has("settings") && d.optJSONObject("settings") == null)
      throw new IllegalArgumentException("设置结构无效");
    JSONArray vs = d.optJSONArray("vocabularies"), ns = d.optJSONArray("notebooks");
    if (vs == null || ns == null) throw new IllegalArgumentException("备份缺少词汇或笔记数组");
    Set<String> vocab = ids(vs), notes = ids(ns);
    for (int i = 0; i < vs.length(); i++) {
      JSONObject v = vs.optJSONObject(i);
      if (v.optString("content").isEmpty() || v.optJSONArray("meanings") == null)
        throw new IllegalArgumentException("词汇结构无效");
      ids(v.optJSONArray("meanings"));
      JSONArray meanings = v.optJSONArray("meanings");
      for (int j = 0; j < meanings.length(); j++) {
        JSONObject m = meanings.optJSONObject(j);
        JSONArray annotations = m.optJSONArray("annotations");
        if (m.has("annotations") && annotations == null)
          throw new IllegalArgumentException("批注集合无效");
        if (annotations != null) {
          ids(annotations);
          for (int q = 0; q < annotations.length(); q++)
            if (!(annotations.optJSONObject(q).opt("content") instanceof String))
              throw new IllegalArgumentException("批注内容无效");
        }
      }
    }
    Map<String, JSONObject> map = new HashMap<>();
    for (int i = 0; i < ns.length(); i++)
      map.put(ns.optJSONObject(i).optString("id"), ns.optJSONObject(i));
    for (int i = 0; i < ns.length(); i++) {
      JSONObject n = ns.optJSONObject(i);
      JSONArray c = n.optJSONArray("child_notebook_ids"), v = n.optJSONArray("vocabulary_ids");
      if (c == null
          || v == null
          || !Arrays.asList("notebook", "vocabulary").contains(n.optString("children_type"))
          || c.length() > 0 && !n.optString("children_type").equals("notebook")
          || v.length() > 0 && !n.optString("children_type").equals("vocabulary")
          || c.length() > 0 && v.length() > 0) throw new IllegalArgumentException("笔记子项类型不一致");
      Set<String> direct = new HashSet<>();
      for (int j = 0; j < c.length(); j++) {
        String child = c.optString(j);
        if (!notes.contains(child)
            || !direct.add(child)
            || !n.optString("id").equals(map.get(child).optString("parent_id")))
          throw new IllegalArgumentException("笔记引用无效");
      }
      direct.clear();
      for (int j = 0; j < v.length(); j++)
        if (!systemGlobal(n) && (!vocab.contains(v.optString(j)) || !direct.add(v.optString(j))))
          throw new IllegalArgumentException("词汇引用无效");
      Set<String> visited = new HashSet<>();
      JSONObject p = n;
      while (p != null) {
        if (!visited.add(p.optString("id"))) throw new IllegalArgumentException("笔记存在循环");
        String parent = p.optString("parent_id", "");
        if (parent.isEmpty() || parent.equals("null")) break;
        JSONObject child = p;
        p = map.get(parent);
        if (p == null) throw new IllegalArgumentException("父笔记不存在");
        boolean listed = false;
        JSONArray parentChildren = p.optJSONArray("child_notebook_ids");
        if (parentChildren != null)
          for (int q = 0; q < parentChildren.length(); q++)
            if (child.optString("id").equals(parentChildren.optString(q))) listed = true;
        if (!listed) throw new IllegalArgumentException("父子笔记关系不一致");
      }
    }
  }

  private static Set<String> ids(JSONArray a) {
    Set<String> s = new HashSet<>();
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      if (o == null || o.optString("id").isEmpty() || !s.add(o.optString("id")))
        throw new IllegalArgumentException("实体ID缺失或重复");
    }
    return s;
  }

  public String csv(List<JSONObject> rows) {
    StringBuilder b =
        new StringBuilder("content,type,zh_definition,en_definition,example,tags\r\n");
    for (JSONObject v : rows) {
      JSONArray ms = v.optJSONArray("meanings");
      for (int i = 0; i < ms.length(); i++) {
        JSONObject m = ms.optJSONObject(i);
        for (String value :
            new String[] {
              v.optString("content"),
              v.optString("type"),
              m.optString("zh_definition"),
              m.optString("en_definition"),
              m.optString("example"),
              v.optString("tags")
            })
          b.append('"')
              .append((value.matches("^[=+@-].*") ? "'" : "") + value.replace("\"", "\"\""))
              .append("\",");
        b.setLength(b.length() - 1);
        b.append("\r\n");
      }
    }
    return b.toString();
  }
}
