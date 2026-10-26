package com.lexinote.nativeapp;

import android.app.*;
import android.widget.*;
import java.util.*;
import org.json.*;

final class ReviewUi {
  final MainActivity a;
  final ArrayDeque<String> queue = new ArrayDeque<>();
  String notebook = null;
  boolean reverse = false, revealed = false;
  int completed = 0;

  ReviewUi(MainActivity a) {
    this.a = a;
  }

  void show() {
    LinearLayout options = a.card("复习设置");
    options.addView(a.text("再次：本轮重入队 · 困难：明天复习 · 简单：4天后复习", 14, false));
    options.addView(
        a.button("范围：" + (notebook == null ? "全部词库" : a.notebooks.path(notebook)), v -> choose()));
    Switch mode = new Switch(a);
    mode.setText("看义猜词");
    mode.setTextColor(a.foreground);
    mode.setChecked(reverse);
    mode.setOnCheckedChangeListener(
        (b, c) -> {
          reverse = c;
          revealed = false;
          a.render();
        });
    options.addView(mode);
    options.addView(
        a.button(
            "开始 / 重新开始",
            v -> {
              queue.clear();
              JSONArray vs = a.library.array("vocabularies");
              JSONObject n = notebook == null ? null : a.library.find("notebooks", notebook);
              long now = System.currentTimeMillis();
              for (int i = 0; i < vs.length(); i++) {
                JSONObject word = vs.optJSONObject(i);
                if (n != null
                    && !NotebookUi.contains(n.optJSONArray("vocabulary_ids"), word.optString("id")))
                  continue;
                if (word.optLong("review_due", 0) <= now) queue.add(word.optString("id"));
              }
              revealed = false;
              completed = 0;
              a.render();
            }));
    if (queue.isEmpty()) {
      a.card("准备好了吗？")
          .addView(
              a.text(
                  completed > 0 ? "本轮完成 " + completed + " 张卡片。评分已保存。" : "点击开始复习当前范围内到期的词汇。",
                  17,
                  false));
      return;
    }
    JSONObject word = a.library.find("vocabularies", queue.peek());
    if (word == null) {
      queue.remove();
      a.render();
      return;
    }
    LinearLayout card = a.card("剩余 " + queue.size() + " · 已复习 " + completed);
    JSONArray meanings = word.optJSONArray("meanings");
    JSONObject meaning =
        meanings == null || meanings.length() == 0 ? null : meanings.optJSONObject(0);
    if (meaning == null
        || meaning.optString("zh_definition").trim().isEmpty()
            && meaning.optString("en_definition").trim().isEmpty()) {
      card.addView(a.text("此词条暂无释义，暂时无法复习。", 17, false));
      card.addView(
          a.button(
              "跳过此词",
              v -> {
                queue.remove();
                revealed = false;
                a.render();
              }));
      return;
    }
    String definition = meaning.optString("zh_definition").trim();
    if (definition.isEmpty()) definition = meaning.optString("en_definition");
    card.addView(a.text(reverse ? definition : word.optString("content"), 28, true));
    if (!revealed)
      card.addView(
          a.button(
              "显示答案",
              v -> {
                revealed = true;
                a.render();
              }));
    else {
      card.addView(a.text(reverse ? word.optString("content") : definition, 22, true));
      card.addView(a.text(meaning.optString("example"), 16, false));
      LinearLayout row = a.row();
      for (String grade : new String[] {"再次", "困难", "简单"})
        row.addView(
            a.button(
                grade,
                v -> {
                  String id = queue.remove();
                  if (grade.equals("再次")) queue.add(id);
                  long interval =
                      grade.equals("再次") ? 0 : grade.equals("困难") ? 86400000L : 4 * 86400000L;
                  Library.put(word, "review_due", System.currentTimeMillis() + interval);
                  Library.put(word, "review_grade", grade);
                  Library.put(word, "review_count", word.optInt("review_count") + 1);
                  Library.put(word, "reviewed_at", Library.now());
                  completed++;
                  revealed = false;
                  a.persist();
                  a.render();
                }));
      card.addView(row);
    }
  }

  void choose() {
    List<JSONObject> ns = a.notebooks.leaves();
    String[] names = new String[ns.size() + 1];
    names[0] = "全部词库";
    for (int i = 0; i < ns.size(); i++) names[i + 1] = ns.get(i).optString("name");
    new AlertDialog.Builder(a)
        .setItems(
            names,
            (d, w) -> {
              notebook = w == 0 ? null : ns.get(w - 1).optString("id");
              queue.clear();
              a.render();
            })
        .show();
  }
}
