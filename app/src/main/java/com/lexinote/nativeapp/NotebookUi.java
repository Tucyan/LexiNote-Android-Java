package com.lexinote.nativeapp;

import android.app.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;

final class NotebookUi {
  final MainActivity a;
  String selected = null, filter = "", typeFilter = "全部", tagFilter = "全部";
  boolean selecting = false;
  final LinkedHashSet<String> selectedVocabularyIds = new LinkedHashSet<>();

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
      if (!typeFilter.equals("全部")
          && !v.optString("type", "word").equals(typeFilter.equals("短语") ? "phrase" : "word"))
        continue;
      if (!tagFilter.equals("全部") && !contains(v.optJSONArray("tags"), tagFilter)) continue;
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
    // Keep category controls above the scrolling vocabulary list, as in the source app.
    LinearLayout controls = a.column();
    controls.setBackgroundColor(a.card);
    a.root.addView(controls, a.root.indexOfChild(a.scroll));
    a.body.setPadding(a.dp(14), 0, a.dp(14), a.dp(32));
    LinearLayout tools = a.row();
    tools.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8));
    EditText f = a.input("搜索词汇、含义或例句…", filter, false);
    f.setTextSize(13 * a.scale);
    f.setMinHeight(0);
    f.setPadding(a.dp(10), 0, a.dp(8), 0);
    f.setCompoundDrawables(a.new GlyphDrawable("search-outline", 16, a.subtle), null, null, null);
    f.setCompoundDrawablePadding(a.dp(6));
    tools.addView(f, new LinearLayout.LayoutParams(0, a.dp(36), 1));
    Button export = a.chip("导出", true, v -> exportMenu());
    export.setCompoundDrawables(
        a.new GlyphDrawable("share-outline", 16, a.accent), null, null, null);
    export.setCompoundDrawablePadding(a.dp(4));
    LinearLayout.LayoutParams exportLp = new LinearLayout.LayoutParams(-2, a.dp(36));
    exportLp.leftMargin = a.dp(8);
    tools.addView(export, exportLp);
    controls.addView(tools);
    f.setOnEditorActionListener(
        (v, action, event) -> {
          filter = f.getText().toString();
          a.render();
          return true;
        });
    LinearLayout cats = a.row();
    cats.setPadding(a.dp(14), a.dp(7), a.dp(14), a.dp(7));
    cats.addView(
        a.chip(
            "全部词汇 (" + a.library.array("vocabularies").length() + ")",
            selected == null,
            v -> {
              selected = null;
              a.render();
            }));
    JSONArray ns = a.library.array("notebooks");
    for (int i = 0; i < ns.length(); i++) {
      JSONObject n = ns.optJSONObject(i);
      if (Library.systemGlobal(n)) continue;
      cats.addView(
          a.chip(
              n.optString("name") + " (" + n.optJSONArray("vocabulary_ids").length() + ")",
              n.optString("id").equals(selected),
              v -> {
                selected = n.optString("id");
                a.render();
              }));
    }
    cats.addView(a.chip("＋ 新建分类", false, v -> create()));
    HorizontalScrollView catScroll = new HorizontalScrollView(a);
    catScroll.setHorizontalScrollBarEnabled(false);
    catScroll.addView(cats);
    controls.addView(catScroll);
    JSONObject selectedNote = selected == null ? null : a.library.find("notebooks", selected);
    LinearLayout active = a.row();
    active.setPadding(a.dp(14), a.dp(5), a.dp(14), a.dp(5));
    active.addView(
        a.icon(selectedNote == null ? "planet-outline" : "folder-outline", 20, a.accent));
    active.addView(
        a.text(" " + (selectedNote == null ? "全部词汇" : path(selected)), 15, true),
        new LinearLayout.LayoutParams(0, -2, 1));
    active.addView(
        a.chip(
            "信息",
            false,
            v ->
                new AlertDialog.Builder(a)
                    .setTitle("分类信息")
                    .setMessage(
                        (selectedNote == null ? "全部词汇" : path(selected))
                            + " · "
                            + visible().size()
                            + " 条词汇\n词汇实体可被多个笔记共享引用。")
                    .setPositiveButton("确定", null)
                    .show()));
    active.addView(a.chip("管理", false, v -> manage(selectedNote)));
    controls.addView(active);
    LinearLayout types = a.row();
    types.setPadding(a.dp(14), a.dp(5), a.dp(14), a.dp(5));
    types.setBackgroundColor(a.background);
    for (String t : new String[] {"全部", "单词", "短语"})
      types.addView(
          a.chip(
              t,
              typeFilter.equals(t),
              v -> {
                typeFilter = t;
                a.render();
              }));
    controls.addView(types);
    LinearLayout tags = a.row();
    tags.setPadding(a.dp(14), a.dp(4), a.dp(14), a.dp(4));
    LinkedHashSet<String> availableTags = new LinkedHashSet<>();
    JSONArray allVocab = a.library.array("vocabularies");
    for (int i = 0; i < allVocab.length(); i++) {
      JSONArray ts = allVocab.optJSONObject(i).optJSONArray("tags");
      for (int j = 0; ts != null && j < ts.length(); j++) availableTags.add(ts.optString(j));
    }
    tags.addView(
        a.chip(
            "标签: 全部",
            tagFilter.equals("全部"),
            view -> {
              tagFilter = "全部";
              a.render();
            }));
    for (String tag : availableTags)
      tags.addView(
          a.chip(
              "#" + tag,
              tagFilter.equals(tag),
              view -> {
                tagFilter = tag;
                a.render();
              }));
    HorizontalScrollView tagScroll = new HorizontalScrollView(a);
    tagScroll.setHorizontalScrollBarEnabled(false);
    tagScroll.addView(tags);
    controls.addView(tagScroll);
    if (selected != null) {
      JSONObject n = a.library.find("notebooks", selected);
      if (n == null) {
        selected = null;
        return;
      }
      String p = n.optString("parent_id", "");
      if (!p.isEmpty() && !p.equals("null"))
        a.body.addView(
            a.button(
                "↑ 上一级",
                v -> {
                  selected = p;
                  a.render();
                }));
    }
    List<JSONObject> rows = visible();
    pruneSelection(rows);
    LinearLayout selectionBar = a.row();
    selectionBar.setPadding(a.dp(14), a.dp(4), a.dp(14), a.dp(4));
    selectionBar.addView(
        a.chip(
            selecting ? "退出选择" : "批量选择",
            selecting,
            view -> {
              selecting = !selecting;
              selectedVocabularyIds.clear();
              a.render();
            }));
    if (selecting) {
      selectionBar.addView(
          a.chip(
              "全选当前筛选",
              false,
              view -> {
                for (JSONObject item : visible()) selectedVocabularyIds.add(item.optString("id"));
                a.render();
              }));
      selectionBar.addView(
          a.chip(
              "清空",
              false,
              view -> {
                selectedVocabularyIds.clear();
                a.render();
              }));
      selectionBar.addView(
          a.chip("操作 (" + selectedVocabularyIds.size() + ")", true, view -> batchMenu()));
    }
    controls.addView(selectionBar);
    if (selected != null) {
      JSONArray children =
          selectedNote == null ? null : selectedNote.optJSONArray("child_notebook_ids");
      if (children != null && children.length() > 0) {
        LinearLayout grid = a.row();
        for (int i = 0; i < children.length(); i++) {
          JSONObject child = a.library.find("notebooks", children.optString(i));
          if (child == null) continue;
          int count =
              child.optJSONArray("vocabulary_ids") == null
                  ? 0
                  : child.optJSONArray("vocabulary_ids").length();
          Button b =
              a.button(
                  "▣  " + child.optString("name") + "\n" + count + " 条",
                  v -> {
                    selected = child.optString("id");
                    a.render();
                  });
          grid.addView(b, new LinearLayout.LayoutParams(0, a.dp(72), 1));
          if (i % 2 == 1) {
            a.body.addView(grid);
            grid = a.row();
          }
        }
        if (grid.getChildCount() > 0) a.body.addView(grid);
      }
    }
    for (JSONObject v : rows) {
      JSONObject display = null;
      JSONObject n = selected == null ? null : a.library.find("notebooks", selected);
      if (n != null && n.optJSONObject("vocabulary_display") != null)
        display = n.optJSONObject("vocabulary_display").optJSONObject(v.optString("id"));
      renderVocabularyListCard(v, display);
    }
    if (rows.isEmpty()) {
      LinearLayout empty = a.column();
      empty.setGravity(Gravity.CENTER);
      empty.addView(a.icon("file-tray-outline", 48, a.subtle));
      empty.addView(a.gap(12));
      empty.addView(a.text("此分类暂无词汇记录", 16, true));
      empty.addView(a.gap(6));
      TextView hint = a.text("在「查询和记录」页面查询后，点击整理笔记即可保存到此处。", 13, false);
      hint.setGravity(Gravity.CENTER);
      hint.setPadding(a.dp(16), 0, a.dp(16), 0);
      empty.addView(hint);
      a.body.addView(empty, new LinearLayout.LayoutParams(-1, a.dp(260)));
    }
  }

  void renderVocabularyListCard(JSONObject vocabulary, JSONObject display) {
    LinearLayout card = a.card(null);
    card.setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(12));
    card.setOnClickListener(
        view -> {
          if (selecting) {
            toggleSelected(vocabulary.optString("id"));
            a.render();
          } else detail(vocabulary);
        });
    card.setOnLongClickListener(
        view -> {
          showVocabularyActions(vocabulary);
          return true;
        });

    LinearLayout top = a.row();
    top.setGravity(Gravity.CENTER_VERTICAL);
    if (selecting) {
      CheckBox selectedBox = new CheckBox(a);
      selectedBox.setChecked(selectedVocabularyIds.contains(vocabulary.optString("id")));
      selectedBox.setOnCheckedChangeListener(
          (button, checked) -> {
            toggleSelected(vocabulary.optString("id"));
            a.render();
          });
      top.addView(selectedBox);
    }
    TextView word = a.text(vocabulary.optString("content"), 16, true);
    top.addView(word, new LinearLayout.LayoutParams(0, -2, 1));

    String type = vocabulary.optString("type", "word").equals("phrase") ? "短语" : "单词";
    TextView typeBadge = a.text(type, 11, true);
    typeBadge.setTextColor(a.muted);
    typeBadge.setPadding(a.dp(8), a.dp(4), a.dp(8), a.dp(4));
    typeBadge.setBackground(a.shape(a.inputBg, 16, 0));
    LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(-2, -2);
    badgeLp.rightMargin = a.dp(6);
    top.addView(typeBadge, badgeLp);

    ImageButton edit = new ImageButton(a);
    edit.setImageResource(android.R.drawable.ic_menu_edit);
    edit.setColorFilter(a.accent);
    edit.setContentDescription("编辑词汇");
    edit.setBackground(a.shape(android.graphics.Color.TRANSPARENT, 8, 0));
    edit.setPadding(a.dp(7), a.dp(7), a.dp(7), a.dp(7));
    edit.setOnClickListener(view -> detail(vocabulary));
    top.addView(edit, new LinearLayout.LayoutParams(a.dp(32), a.dp(32)));
    card.addView(top);

    JSONArray tags = vocabulary.optJSONArray("tags");
    if (tags != null && tags.length() > 0) {
      LinearLayout tagRow = a.row();
      LinearLayout.LayoutParams tagRowLp = new LinearLayout.LayoutParams(-1, -2);
      tagRowLp.topMargin = a.dp(3);
      for (int i = 0; i < tags.length(); i++) {
        String tagValue = tags.optString(i);
        TextView tag = a.text("#" + tagValue, 11, true);
        tag.setTextColor(a.accent);
        tag.setPadding(a.dp(7), a.dp(3), a.dp(7), a.dp(3));
        tag.setBackground(a.shape((a.accent & 0x00FFFFFF) | 0x1A000000, 16, 0));
        tag.setOnClickListener(
            view -> {
              tagFilter = tagValue;
              a.render();
            });
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(-2, -2);
        tagLp.rightMargin = a.dp(5);
        tagRow.addView(tag, tagLp);
      }
      card.addView(tagRow, tagRowLp);
    }

    JSONArray meanings = vocabulary.optJSONArray("meanings");
    JSONObject fields = display == null ? null : display.optJSONObject("fields");
    JSONObject perMeaning = display == null ? null : display.optJSONObject("meaning_fields");
    JSONArray visibleIds = display == null ? null : display.optJSONArray("meaning_ids");
    int visibleCount = 0;
    int annotationCount = 0;
    for (int i = 0; meanings != null && i < meanings.length(); i++) {
      JSONObject meaning = meanings.optJSONObject(i);
      if (visibleIds != null && !contains(visibleIds, meaning.optString("id"))) continue;
      visibleCount++;
      JSONObject localFields =
          perMeaning == null ? null : perMeaning.optJSONObject(meaning.optString("id"));
      boolean showZh = displayField(fields, localFields, "zh_definition");
      boolean showEn = displayField(fields, localFields, "en_definition");
      boolean showExample = displayField(fields, localFields, "example");
      String definition = showZh ? meaning.optString("zh_definition").trim() : "";
      if (definition.isEmpty() && showEn) definition = meaning.optString("en_definition").trim();
      if (definition.isEmpty()) continue;

      LinearLayout meaningBlock = a.column();
      LinearLayout.LayoutParams meaningLp = new LinearLayout.LayoutParams(-1, -2);
      meaningLp.topMargin = a.dp(4);
      TextView definitionText = a.text("", 13, false);
      android.text.SpannableStringBuilder line = new android.text.SpannableStringBuilder();
      line.append(String.valueOf(i + 1)).append(". ");
      String pos = meaning.optString("part_of_speech").trim();
      if (!pos.isEmpty()) {
        int start = line.length();
        line.append(pos);
        line.setSpan(
            new android.text.style.ForegroundColorSpan(a.accent),
            start,
            line.length(),
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.setSpan(
            new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            start,
            line.length(),
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.append("  ");
      }
      line.append(definition);
      definitionText.setText(line);
      meaningBlock.addView(definitionText);
      String example = showExample ? meaning.optString("example").trim() : "";
      if (!example.isEmpty()) {
        TextView exampleText = a.text("例：" + example, 11, false);
        exampleText.setTextColor(a.muted);
        exampleText.setMaxLines(2);
        LinearLayout.LayoutParams exampleLp = new LinearLayout.LayoutParams(-1, -2);
        exampleLp.leftMargin = a.dp(16);
        meaningBlock.addView(exampleText, exampleLp);
      }
      JSONArray annotations = meaning.optJSONArray("annotations");
      if (annotations != null) annotationCount += annotations.length();
      card.addView(meaningBlock, meaningLp);
    }

    LinearLayout footer = a.row();
    LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(-1, -2);
    footerLp.topMargin = a.dp(5);
    footer.addView(a.text("共 " + visibleCount + " 条释义", 11, false));
    if (annotationCount > 0) {
      TextView annotations = a.text("  ·  " + annotationCount + " 条批注", 11, false);
      annotations.setTextColor(a.accent);
      footer.addView(annotations);
    }
    card.addView(footer, footerLp);
    a.passiveLabels(card);
  }

  boolean displayField(JSONObject fields, JSONObject localFields, String key) {
    return localFields != null
        ? localFields.optBoolean(key, fields == null || fields.optBoolean(key, true))
        : fields == null || fields.optBoolean(key, true);
  }

  void showVocabularyActions(JSONObject vocabulary) {
    String noteId = selected;
    String[] actions =
        noteId == null ? new String[] {"查看详情与管理"} : new String[] {"查看详情与管理", "从此笔记移除引用"};
    new AlertDialog.Builder(a)
        .setItems(
            actions,
            (dialog, which) -> {
              if (which == 0) {
                detail(vocabulary);
              } else if (noteId != null) {
                new AlertDialog.Builder(a)
                    .setMessage("仅从此笔记移除此词汇引用，词汇本身和其他笔记不受影响。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton(
                        "移除",
                        (confirmation, button) ->
                            a.change(
                                () -> {
                                  JSONObject note = a.library.find("notebooks", noteId);
                                  if (note != null)
                                    Library.removeValue(
                                        note.optJSONArray("vocabulary_ids"),
                                        vocabulary.optString("id"));
                                }))
                    .show();
              }
            })
        .show();
  }

  void exportMenu() {
    new AlertDialog.Builder(a)
        .setTitle("导出当前筛选")
        .setItems(
            new String[] {"Markdown", "JSON", "CSV"},
            (d, w) -> {
              List<JSONObject> rows = visible();
              if (w == 0) {
                JSONObject note = selected == null ? null : a.library.find("notebooks", selected);
                a.export(
                    "lexinote-filter.md",
                    "text/markdown",
                    NotebookExport.markdownForNotebook(rows, note));
              } else if (w == 1) {
                JSONArray arr = new JSONArray();
                for (JSONObject r : rows) arr.put(r);
                a.export("lexinote-filter.json", "application/json", arr.toString());
              } else a.export("lexinote-filter.csv", "text/csv", "\uFEFF" + a.library.csv(rows));
            })
        .show();
  }

  void toggleSelected(String id) {
    if (!selectedVocabularyIds.add(id)) selectedVocabularyIds.remove(id);
  }

  void pruneSelection(List<JSONObject> scope) {
    HashSet<String> visibleIds = new HashSet<>();
    for (JSONObject vocab : scope) visibleIds.add(vocab.optString("id"));
    NotebookSelection.pruneToScope(selectedVocabularyIds, visibleIds);
  }

  boolean selectionInCurrentScope() {
    HashSet<String> visibleIds = new HashSet<>();
    for (JSONObject vocab : visible()) visibleIds.add(vocab.optString("id"));
    return NotebookSelection.isWithinScope(selectedVocabularyIds, visibleIds);
  }

  void batchMenu() {
    if (selectedVocabularyIds.isEmpty()) {
      a.say("请先选择词汇");
      return;
    }
    if (!selectionInCurrentScope()) {
      a.say("筛选范围已变化，请重新选择词汇");
      selectedVocabularyIds.clear();
      a.render();
      return;
    }
    List<String> options = new ArrayList<>();
    if (selected != null) {
      options.add("移动到笔记");
      options.add("复制引用到笔记");
      options.add("从当前笔记移除引用");
    } else {
      options.add("复制引用到笔记");
    }
    options.add("全局删除词汇（所有笔记）");
    new AlertDialog.Builder(a)
        .setTitle("批量操作 · " + selectedVocabularyIds.size() + " 项")
        .setItems(
            options.toArray(new String[0]),
            (dialog, which) -> {
              String action = options.get(which);
              if (action.startsWith("移动") || action.startsWith("复制"))
                chooseBatchTargets(action.startsWith("复制"));
              else if (action.startsWith("从当前"))
                confirmBatch(
                    "仅移除这些词汇在当前笔记中的引用，实体和其他笔记保持不变。",
                    () -> a.library.removeReferences(selected, ids()));
              else
                confirmBatch(
                    "永久删除所选词汇实体，并从所有笔记中移除引用。此操作无法恢复。",
                    () -> a.library.deleteVocabularyEverywhere(ids()));
            })
        .show();
  }

  String[] ids() {
    if (!selectionInCurrentScope()) throw new IllegalStateException("所选词汇已不在当前筛选范围");
    return selectedVocabularyIds.toArray(new String[0]);
  }

  void confirmBatch(String message, Runnable action) {
    new AlertDialog.Builder(a)
        .setMessage(message)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "确认",
            (d, w) -> {
              if (a.change(action::run)) {
                selectedVocabularyIds.clear();
                selecting = false;
              }
            })
        .show();
  }

  void chooseBatchTargets(boolean copy) {
    List<JSONObject> targets = leaves();
    String[] names = new String[targets.size()];
    boolean[] checked = new boolean[targets.size()];
    for (int i = 0; i < targets.size(); i++) {
      names[i] = targets.get(i).optString("name");
      checked[i] = targets.get(i).optString("id").equals(selected);
    }
    new AlertDialog.Builder(a)
        .setTitle(copy ? "复制引用到目标笔记" : "移动到目标笔记")
        .setMultiChoiceItems(names, checked, (dlg, index, value) -> checked[index] = value)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "执行",
            (dlg, w) -> {
              List<String> targetIds = new ArrayList<>();
              for (int i = 0; i < checked.length; i++)
                if (checked[i]) targetIds.add(targets.get(i).optString("id"));
              if (targetIds.isEmpty()) {
                a.say("至少选择一个目标笔记");
                return;
              }
              String sourceId = selected == null ? "nb_global_root" : selected;
              confirmBatch(
                  copy ? "将共享词汇引用复制到所选笔记，并保留各笔记显示设置。" : "将引用移动到所选笔记，并保留显示设置。",
                  () ->
                      a.library.batchReferences(
                          sourceId, ids(), targetIds.toArray(new String[0]), copy));
            })
        .show();
  }

  void manage(JSONObject n) {
    List<String> options = new ArrayList<>();
    List<Runnable> actions = new ArrayList<>();
    if (n != null) {
      options.add("重命名");
      actions.add(() -> rename(n));
      options.add("删除笔记树");
      actions.add(
          () ->
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
                  .show());
      options.add("创建子分类");
      actions.add(() -> create());
    } else {
      options.add("创建分类");
      actions.add(() -> create());
    }
    options.add("离线添加词汇");
    actions.add(() -> manual());
    new AlertDialog.Builder(a)
        .setTitle("分类管理")
        .setItems(options.toArray(new String[0]), (d, w) -> actions.get(w).run())
        .show();
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

  void saveDefault(JSONObject result) {
    String id = a.library.settings().optString("default_notebook_id", "");
    JSONObject n = a.library.find("notebooks", id);
    if (n == null || Library.systemGlobal(n) || n.optJSONArray("child_notebook_ids").length() > 0) {
      a.say("请先在设置中选择默认末级笔记");
      return;
    }
    try {
      JSONObject fields = new JSONObject();
      for (String f :
          new String[] {
            "zh_definition",
            "en_definition",
            "phonetic",
            "example",
            "source",
            "source_images",
            "annotations",
            "remarks"
          }) Library.put(fields, f, true);
      JSONObject display = new JSONObject();
      Library.put(display, "fields", fields);
      String vocabId = a.library.saveResult(result, new String[] {id}, display);
      if (a.session != null && a.session.optJSONObject("query_results") == result) {
        JSONObject saved = new JSONObject();
        Library.put(saved, "saved", true);
        Library.put(saved, "vocabulary_id", vocabId);
        Library.put(saved, "notebook_ids", new JSONArray().put(id));
        Library.put(a.session, "saved_content", saved);
        a.library.touchSession(a.session, true);
      }
      a.persist();
      a.say("已保存到" + n.optString("name"));
      a.render();
    } catch (Exception e) {
      a.error(e);
    }
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
    box.addView(a.text("长按下方字段选中文字，可添加批注。", 12, false));
    JSONArray detailMeanings = v.optJSONArray("meanings");
    for (int i = 0; detailMeanings != null && i < detailMeanings.length(); i++) {
      JSONObject meaning = detailMeanings.optJSONObject(i);
      box.addView(a.text("释义 " + (i + 1) + "  " + meaning.optString("part_of_speech"), 16, true));
      if (!meaning.optString("phonetic").isEmpty())
        box.addView(a.text(meaning.optString("phonetic"), 14, false));
      String[] fields = {"zh_definition", "en_definition", "example", "source", "remarks"};
      String[] labels = {"中文释义", "英文释义", "例句", "来源", "备注"};
      for (int fieldIndex = 0; fieldIndex < fields.length; fieldIndex++) {
        String field = fields[fieldIndex];
        String value = meaning.optString(field);
        if (value.isEmpty()) continue;
        box.addView(a.text(labels[fieldIndex] + "：", 12, true));
        TextView selectable = a.text(value, 13, false);
        selectable.setTextIsSelectable(true);
        selectable.setCustomSelectionActionModeCallback(
            new ActionMode.Callback() {
              final int annotationActionId = View.generateViewId();

              @Override
              public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                menu.add(Menu.NONE, annotationActionId, Menu.NONE, "批注选中文字");
                return true;
              }

              @Override
              public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
              }

              @Override
              public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (!AnnotationActionIds.isAnnotationAction(item.getItemId(), annotationActionId))
                  return false;
                int start = selectable.getSelectionStart(), end = selectable.getSelectionEnd();
                String selectedText =
                    start >= 0 && end > start
                        ? selectable.getText().subSequence(start, end).toString()
                        : "";
                mode.finish();
                annotationDialog(meaning, field, selectedText, null);
                return true;
              }

              @Override
              public void onDestroyActionMode(ActionMode mode) {}
            });
        box.addView(selectable);
      }
      JSONArray images = meaning.optJSONArray("source_images");
      for (int j = 0; images != null && j < images.length(); j++) {
        ImageView image = new ImageView(a);
        image.setImageURI(android.net.Uri.parse(images.optString(j)));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(image, new LinearLayout.LayoutParams(-1, a.dp(140)));
      }
      JSONArray notes = meaning.optJSONArray("annotations");
      for (int j = 0; notes != null && j < notes.length(); j++) {
        JSONObject note = notes.optJSONObject(j);
        box.addView(
            a.button(
                "批注：" + note.optString("content"),
                view ->
                    annotationDialog(
                        meaning,
                        note.optString("target_field", "zh_definition"),
                        note.optString("target_text"),
                        note)));
      }
    }
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

  void annotationDialog(
      JSONObject meaning, String defaultField, String targetText, JSONObject existing) {
    String[] fields = {"zh_definition", "en_definition", "example", "source", "remarks"};
    String[] labels = {"中文释义", "英文释义", "例句", "来源", "备注"};
    int selectedField = 0;
    for (int i = 0; i < fields.length; i++) if (fields[i].equals(defaultField)) selectedField = i;
    Spinner spinner = new Spinner(a);
    spinner.setAdapter(
        new ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, labels));
    spinner.setSelection(selectedField);
    EditText target = a.input("目标字词 / 片段", targetText, false);
    EditText content = a.input("批注内容", existing == null ? "" : existing.optString("content"), true);
    LinearLayout box = a.column();
    box.addView(spinner);
    box.addView(target);
    box.addView(content);
    AlertDialog.Builder builder =
        new AlertDialog.Builder(a)
            .setTitle(existing == null ? "添加字段批注" : "编辑字段批注")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton(
                "保存",
                (d, w) ->
                    a.change(
                        () -> {
                          int index = spinner.getSelectedItemPosition();
                          if (existing == null)
                            Annotations.add(
                                meaning,
                                fields[index],
                                target.getText().toString(),
                                content.getText().toString());
                          else
                            Annotations.edit(
                                meaning,
                                existing.optString("id"),
                                fields[index],
                                target.getText().toString(),
                                content.getText().toString());
                        }));
    if (existing != null)
      builder.setNeutralButton(
          "删除", (d, w) -> a.change(() -> Annotations.remove(meaning, existing.optString("id"))));
    builder.show();
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
