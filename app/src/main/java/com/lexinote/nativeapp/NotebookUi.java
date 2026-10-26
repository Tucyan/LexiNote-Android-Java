package com.lexinote.nativeapp;

import android.app.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;

final class NotebookUi {
  final MainActivity a;
  String selected = null, filter = "";

  NotebookUi(MainActivity a) {
    this.a = a;
  }

  List<JSONObject> visible() {
    List<JSONObject> out = new ArrayList<>();
    JSONObject n = selected == null ? null : a.library.find("notebooks", selected);
    JSONArray refs = n == null ? null : n.optJSONArray("vocabulary_ids");
    JSONArray vs = a.library.array("vocabularies");
    for (int i = 0; i < vs.length(); i++) {
      JSONObject v = vs.optJSONObject(i);
      if (refs != null && !contains(refs, v.optString("id"))) continue;
      if (!Library.normalized(v.toString()).contains(Library.normalized(filter))) continue;
      out.add(v);
    }
    return out;
  }

  static boolean contains(JSONArray a, String id) {
    for (int i = 0; i < a.length(); i++) if (id.equals(a.optString(i))) return true;
    return false;
  }

  void show() {
    a.body.addView(a.button("+ 离线添加词汇", v -> manual()));
    LinearLayout scope = a.card(selected == null ? "全局词库" : path(selected));
    scope.addView(
        a.button(
            "全局词库（永久）",
            v -> {
              selected = null;
              a.render();
            }));
    JSONArray ns = a.library.array("notebooks");
    for (int i = 0; i < ns.length(); i++) {
      JSONObject n = ns.optJSONObject(i);
      if (Library.systemGlobal(n)) continue;
      String parent = n.optString("parent_id", "");
      if (parent.equals("null")) parent = "";
      if ((selected == null && parent.isEmpty()) || selected != null && parent.equals(selected))
        scope.addView(
            a.button(
                "▸ " + n.optString("name"),
                v -> {
                  selected = n.optString("id");
                  a.render();
                }));
    }
    if (selected != null) {
      JSONObject n = a.library.find("notebooks", selected);
      if (n == null) {
        selected = null;
        return;
      }
      scope.addView(
          a.button(
              "↑ 上一级",
              v -> {
                String p = n.optString("parent_id", "");
                selected = p.isEmpty() || p.equals("null") ? null : p;
                a.render();
              }));
      scope.addView(a.button("重命名", v -> rename(n)));
      scope.addView(
          a.button(
              "删除笔记树（保留词汇实体）",
              v ->
                  new AlertDialog.Builder(a)
                      .setMessage("删除当前笔记及其全部子笔记？词汇仍在全局词库。")
                      .setNegativeButton("取消", null)
                      .setPositiveButton(
                          "删除",
                          (d, w) ->
                              a.change(
                                  () -> {
                                    a.library.deleteNotebook(selected);
                                    selected = null;
                                  }))
                      .show()));
    }
    JSONObject selectedNote = selected == null ? null : a.library.find("notebooks", selected);
    if (selectedNote == null || !selectedNote.optBoolean("is_default"))
      scope.addView(a.button(selected == null ? "创建笔记树" : "创建子笔记", v -> create()));
    LinearLayout search = a.card("词汇筛选");
    EditText f = a.input("搜索词汇、释义、标签、类型", filter, false);
    search.addView(f);
    search.addView(
        a.button(
            "筛选",
            v -> {
              filter = f.getText().toString();
              a.render();
            }));
    List<JSONObject> rows = visible();
    search.addView(a.text(rows.size() + " 条词汇", 14, false));
    search.addView(
        a.button(
            "导出当前筛选 JSON",
            v -> {
              JSONArray arr = new JSONArray();
              for (JSONObject r : rows) arr.put(r);
              a.export("lexinote-filter.json", "application/json", arr.toString());
            }));
    search.addView(
        a.button(
            "导出当前筛选 CSV",
            v -> a.export("lexinote-filter.csv", "text/csv", "\uFEFF" + a.library.csv(rows))));
    for (JSONObject v : rows) {
      LinearLayout c = a.card(v.optString("content"));
      JSONObject display = null;
      JSONObject n = selected == null ? null : a.library.find("notebooks", selected);
      if (n != null && n.optJSONObject("vocabulary_display") != null)
        display = n.optJSONObject("vocabulary_display").optJSONObject(v.optString("id"));
      renderMeanings(c, v, display, false);
      c.addView(a.button("编辑 / 归属 / 显示配置", view -> detail(v)));
      if (selected != null)
        c.addView(
            a.button(
                "从此笔记移除引用",
                view ->
                    a.change(
                        () ->
                            Library.removeValue(
                                a.library
                                    .find("notebooks", selected)
                                    .optJSONArray("vocabulary_ids"),
                                v.optString("id")))));
    }
    if (rows.isEmpty()) a.card("还没有词汇").addView(a.text("先在查询中获取词条，再选择保存到笔记。", 15, false));
  }

  String path(String id) {
    JSONObject n = a.library.find("notebooks", id);
    return n == null ? "笔记" : n.optString("name");
  }

  void create() {
    EditText name = a.input("笔记名称", "", false);
    new AlertDialog.Builder(a)
        .setTitle("创建笔记")
        .setView(name)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "创建",
            (d, w) -> a.change(() -> a.library.createNotebook(name.getText().toString(), selected)))
        .show();
  }

  void rename(JSONObject n) {
    if (n.optBoolean("is_default") || n.optBoolean("is_global_root")) {
      a.say("系统笔记不能重命名");
      return;
    }
    EditText name = a.input("笔记名称", n.optString("name"), false);
    new AlertDialog.Builder(a)
        .setTitle("重命名")
        .setView(name)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "保存",
            (d, w) -> {
              if (name.getText().toString().trim().isEmpty()) {
                a.say("名称不能为空");
                return;
              }
              a.change(() -> Library.put(n, "name", name.getText().toString().trim()));
            })
        .show();
  }

  void manual() {
    LinearLayout box = a.column();
    EditText word = a.input("单词或短语", "", false),
        zh = a.input("中文释义", "", true),
        en = a.input("英文释义（可选）", "", true),
        example = a.input("例句（可选）", "", true);
    box.addView(word);
    box.addView(zh);
    box.addView(en);
    box.addView(example);
    new AlertDialog.Builder(a)
        .setTitle("离线添加词汇")
        .setView(box)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "选择笔记",
            (d, w) -> {
              JSONObject result = new JSONObject(), meaning = new JSONObject();
              Library.put(result, "word", word.getText().toString().trim());
              Library.put(
                  result,
                  "type",
                  word.getText().toString().trim().contains(" ") ? "phrase" : "word");
              Library.put(meaning, "zh_definition", zh.getText().toString());
              Library.put(meaning, "en_definition", en.getText().toString());
              Library.put(meaning, "example", example.getText().toString());
              Library.put(result, "meanings", new JSONArray().put(meaning));
              saveDialog(result);
            })
        .show();
  }

  List<JSONObject> leaves() {
    List<JSONObject> out = new ArrayList<>();
    JSONArray ns = a.library.array("notebooks");
    for (int i = 0; i < ns.length(); i++) {
      JSONObject n = ns.optJSONObject(i);
      if (!Library.systemGlobal(n) && n.optJSONArray("child_notebook_ids").length() == 0)
        out.add(n);
    }
    return out;
  }

  void saveDialog(JSONObject result) {
    List<JSONObject> leaves = leaves();
    if (leaves.isEmpty()) {
      a.say("请先创建一个末级笔记");
      return;
    }
    LinearLayout box = a.column();
    List<CheckBox> checks = new ArrayList<>();
    box.addView(a.text("保存全部释义。下面的字段选择仅控制这些笔记中的显示。", 14, false));
    for (JSONObject n : leaves) {
      CheckBox cb = new CheckBox(a);
      cb.setText(n.optString("name"));
      cb.setTextColor(a.foreground);
      cb.setChecked(
          n.optString("id").equals(a.library.settings().optString("default_notebook_id")));
      box.addView(cb);
      checks.add(cb);
    }
    String[] fields = {
      "zh_definition",
      "en_definition",
      "phonetic",
      "example",
      "source",
      "source_images",
      "annotations",
      "remarks"
    };
    String[] names = {"中文释义", "英文释义", "音标", "例句", "来源", "图片", "批注", "备注"};
    List<CheckBox> fs = new ArrayList<>();
    for (int i = 0; i < fields.length; i++) {
      CheckBox cb = new CheckBox(a);
      cb.setText(names[i]);
      cb.setChecked(true);
      cb.setTextColor(a.foreground);
      box.addView(cb);
      fs.add(cb);
    }
    ScrollView scroll = new ScrollView(a);
    scroll.addView(box);
    new AlertDialog.Builder(a)
        .setTitle("保存到多个笔记")
        .setView(scroll)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "保存",
            (d, w) -> {
              try {
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < checks.size(); i++)
                  if (checks.get(i).isChecked()) ids.add(leaves.get(i).optString("id"));
                if (ids.isEmpty()) {
                  a.say("至少选择一个笔记");
                  return;
                }
                JSONObject display = new JSONObject(), selection = new JSONObject();
                for (int i = 0; i < fields.length; i++)
                  Library.put(selection, fields[i], fs.get(i).isChecked());
                Library.put(display, "fields", selection);
                String id = a.library.saveResult(result, ids.toArray(new String[0]), display);
                if (a.session != null && a.session.optJSONObject("query_results") == result) {
                  JSONObject saved = new JSONObject();
                  Library.put(saved, "saved", true);
                  Library.put(saved, "vocabulary_id", id);
                  Library.put(saved, "notebook_ids", new JSONArray(ids));
                  Library.put(a.session, "saved_content", saved);
                  a.library.touchSession(a.session, true);
                }
                a.persist();
                a.say("已保存完整词汇，共享引用到 " + ids.size() + " 个笔记");
                a.render();
              } catch (Exception e) {
                a.error(e);
              }
            })
        .show();
  }

  void renderMeanings(LinearLayout box, JSONObject v, JSONObject display, boolean full) {
    JSONArray ms = v.optJSONArray("meanings");
    JSONObject fields = display == null ? null : display.optJSONObject("fields");
    JSONArray ids = display == null ? null : display.optJSONArray("meaning_ids");
    for (int i = 0; i < ms.length(); i++) {
      JSONObject m = ms.optJSONObject(i);
      if (!full && ids != null && !contains(ids, m.optString("id"))) continue;
      StringBuilder text = new StringBuilder(m.optString("part_of_speech"));
      for (String f :
          new String[] {
            "phonetic", "zh_definition", "en_definition", "example", "source", "remarks"
          })
        if ((full || fields == null || fields.optBoolean(f, true)) && !m.optString(f).isEmpty())
          text.append("\n").append(m.optString(f));
      box.addView(a.text(text.toString().trim(), 16, false));
      if (full || fields == null || fields.optBoolean("source_images", true)) {
        JSONArray images = m.optJSONArray("source_images");
        if (images != null)
          for (int j = 0; j < images.length(); j++) {
            ImageView image = new ImageView(a);
            image.setImageURI(android.net.Uri.parse(images.optString(j)));
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            box.addView(image, new LinearLayout.LayoutParams(-1, a.dp(140)));
          }
      }
      if (full || fields == null || fields.optBoolean("annotations", true)) {
        JSONArray notes = m.optJSONArray("annotations");
        if (notes != null)
          for (int j = 0; j < notes.length(); j++)
            box.addView(a.text("批注：" + notes.optJSONObject(j).optString("content"), 14, false));
      }
    }
    if (v.optJSONArray("tags") != null)
      box.addView(a.text("标签 " + v.optJSONArray("tags").toString(), 13, false));
  }

  void detail(JSONObject v) {
    LinearLayout box = a.column();
    EditText content = a.input("词汇", v.optString("content"), false);
    box.addView(content);
    EditText tags = a.input("标签，以逗号分隔", tags(v.optJSONArray("tags")), false);
    box.addView(tags);
    renderMeanings(box, v, null, true);
    box.addView(a.button("编辑释义 / 批注", view -> meanings(v)));
    box.addView(a.button("调整多个笔记归属", view -> membership(v)));
    box.addView(a.button("配置各笔记显示", view -> displayConfig(v)));
    ScrollView scroll = new ScrollView(a);
    scroll.addView(box);
    new AlertDialog.Builder(a)
        .setTitle("词库完整内容")
        .setView(scroll)
        .setNegativeButton("关闭", null)
        .setPositiveButton(
            "保存词汇 / 标签",
            (d, w) -> {
              if (content.getText().toString().trim().isEmpty()) {
                a.say("词汇不能为空");
                return;
              }
              a.change(
                  () -> {
                    a.library.renameVocabulary(v.optString("id"), content.getText().toString());
                    JSONArray arr = new JSONArray();
                    for (String t : tags.getText().toString().split("[,，]"))
                      if (!t.trim().isEmpty()) arr.put(t.trim());
                    Library.put(v, "tags", arr);
                    Library.put(v, "updated_at", Library.now());
                  });
            })
        .show();
  }

  String tags(JSONArray a) {
    if (a == null) return "";
    List<String> s = new ArrayList<>();
    for (int i = 0; i < a.length(); i++) s.add(a.optString(i));
    return String.join(", ", s);
  }

  void meanings(JSONObject v) {
    JSONArray ms = v.optJSONArray("meanings");
    String[] options = new String[ms.length() + 1];
    for (int i = 0; i < ms.length(); i++)
      options[i] =
          (i + 1)
              + " "
              + ms.optJSONObject(i)
                  .optString("zh_definition", ms.optJSONObject(i).optString("en_definition"));
    options[ms.length()] = "+ 添加释义";
    new AlertDialog.Builder(a)
        .setTitle("选择释义")
        .setItems(options, (d, w) -> editMeaning(v, w == ms.length() ? null : ms.optJSONObject(w)))
        .show();
  }

  void editMeaning(JSONObject vocab, JSONObject original) {
    JSONObject m = original == null ? new JSONObject() : original;
    LinearLayout box = a.column();
    String[] fields = {
      "part_of_speech", "zh_definition", "en_definition", "phonetic", "example", "source", "remarks"
    };
    String[] labels = {"词性", "中文释义", "英文释义", "音标", "例句", "来源", "备注"};
    List<EditText> inputs = new ArrayList<>();
    for (int i = 0; i < fields.length; i++) {
      box.addView(a.text(labels[i], 13, true));
      EditText e = a.input(labels[i], m.optString(fields[i]), i > 0);
      box.addView(e);
      inputs.add(e);
    }
    JSONArray annotations = m.optJSONArray("annotations");
    if (annotations != null)
      for (int i = 0; i < annotations.length(); i++) {
        JSONObject note = annotations.optJSONObject(i);
        box.addView(
            a.button(
                "删除批注：" + note.optString("content"),
                view -> a.change(() -> Library.removeValue(annotations, note.optString("id")))));
      }
    EditText annotation = a.input("添加一条批注（绑定当前释义）", "", true);
    box.addView(annotation);
    ScrollView scroll = new ScrollView(a);
    scroll.addView(box);
    new AlertDialog.Builder(a)
        .setTitle("编辑释义")
        .setView(scroll)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "保存",
            (d, w) ->
                a.change(
                    () -> {
                      if (inputs.get(1).getText().toString().trim().isEmpty()
                          && inputs.get(2).getText().toString().trim().isEmpty())
                        throw new IllegalArgumentException("至少填写中文或英文释义");
                      for (int i = 0; i < fields.length; i++)
                        Library.put(m, fields[i], inputs.get(i).getText().toString());
                      if (original == null) {
                        Library.put(m, "id", Library.id());
                        Library.put(m, "created_at", Library.now());
                        vocab.optJSONArray("meanings").put(m);
                      }
                      Library.put(m, "updated_at", Library.now());
                      if (!annotation.getText().toString().trim().isEmpty()) {
                        JSONArray notes = m.optJSONArray("annotations");
                        if (notes == null) {
                          notes = new JSONArray();
                          Library.put(m, "annotations", notes);
                        }
                        JSONObject note = new JSONObject();
                        Library.put(note, "id", Library.id());
                        Library.put(note, "meaning_id", m.optString("id"));
                        Library.put(note, "content", annotation.getText().toString());
                        Library.put(note, "created_at", Library.now());
                        Library.put(note, "updated_at", Library.now());
                        notes.put(note);
                      }
                    }))
        .show();
  }

  void membership(JSONObject v) {
    List<JSONObject> leaves = leaves();
    String[] names = new String[leaves.size()];
    boolean[] checked = new boolean[leaves.size()];
    for (int i = 0; i < names.length; i++) {
      names[i] = leaves.get(i).optString("name");
      checked[i] = contains(leaves.get(i).optJSONArray("vocabulary_ids"), v.optString("id"));
    }
    new AlertDialog.Builder(a)
        .setTitle("笔记归属（共享实体）")
        .setMultiChoiceItems(names, checked, (d, w, c) -> checked[w] = c)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "保存",
            (d, w) ->
                a.change(
                    () -> {
                      for (int i = 0; i < names.length; i++) {
                        JSONArray ids = leaves.get(i).optJSONArray("vocabulary_ids");
                        Library.removeValue(ids, v.optString("id"));
                        if (checked[i]) ids.put(v.optString("id"));
                      }
                    }))
        .show();
  }

  void displayConfig(JSONObject v) {
    List<JSONObject> refs = new ArrayList<>();
    for (JSONObject n : leaves())
      if (contains(n.optJSONArray("vocabulary_ids"), v.optString("id"))) refs.add(n);
    String[] names = new String[refs.size()];
    for (int i = 0; i < names.length; i++) names[i] = refs.get(i).optString("name");
    new AlertDialog.Builder(a)
        .setTitle("选择笔记的独立显示配置")
        .setItems(
            names,
            (d, w) -> {
              JSONObject n = refs.get(w);
              LinearLayout box = a.column();
              String[] fields = {
                "zh_definition",
                "en_definition",
                "phonetic",
                "example",
                "source",
                "source_images",
                "annotations",
                "remarks"
              };
              String[] labels = {"中文释义", "英文释义", "音标", "例句", "来源", "图片", "批注", "备注"};
              List<CheckBox> checks = new ArrayList<>();
              JSONObject displays = n.optJSONObject("vocabulary_display");
              JSONObject current =
                  displays == null ? null : displays.optJSONObject(v.optString("id"));
              JSONObject fs = current == null ? null : current.optJSONObject("fields");
              for (int i = 0; i < fields.length; i++) {
                CheckBox cb = new CheckBox(a);
                cb.setText(labels[i]);
                cb.setTextColor(a.foreground);
                cb.setChecked(fs == null || fs.optBoolean(fields[i], true));
                box.addView(cb);
                checks.add(cb);
              }
              List<CheckBox> meanings = new ArrayList<>();
              JSONArray ms = v.optJSONArray("meanings");
              for (int i = 0; i < ms.length(); i++) {
                JSONObject m = ms.optJSONObject(i);
                CheckBox cb = new CheckBox(a);
                cb.setText(
                    "释义 "
                        + (i + 1)
                        + " "
                        + m.optString("zh_definition", m.optString("en_definition")));
                cb.setTextColor(a.foreground);
                cb.setChecked(
                    current == null
                        || current.optJSONArray("meaning_ids") == null
                        || contains(current.optJSONArray("meaning_ids"), m.optString("id")));
                box.addView(cb);
                meanings.add(cb);
              }
              ScrollView scroll = new ScrollView(a);
              scroll.addView(box);
              new AlertDialog.Builder(a)
                  .setTitle(n.optString("name") + " · 显示内容")
                  .setView(scroll)
                  .setNegativeButton("取消", null)
                  .setPositiveButton(
                      "保存",
                      (dialog, which) ->
                          a.change(
                              () -> {
                                JSONObject config = new JSONObject(), newFields = new JSONObject();
                                for (int i = 0; i < fields.length; i++)
                                  Library.put(newFields, fields[i], checks.get(i).isChecked());
                                Library.put(config, "fields", newFields);
                                JSONArray ids = new JSONArray();
                                for (int i = 0; i < meanings.size(); i++)
                                  if (meanings.get(i).isChecked())
                                    ids.put(ms.optJSONObject(i).optString("id"));
                                Library.put(config, "meaning_ids", ids);
                                if (n.optJSONObject("vocabulary_display") == null)
                                  Library.put(n, "vocabulary_display", new JSONObject());
                                Library.put(
                                    n.optJSONObject("vocabulary_display"),
                                    v.optString("id"),
                                    config);
                              }))
                  .show();
            })
        .show();
  }
}
