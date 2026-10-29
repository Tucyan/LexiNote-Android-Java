package com.lexinote.nativeapp;

import android.app.*;
import android.view.View;
import android.widget.*;
import java.util.*;
import org.json.*;

final class ReviewUi {
  final MainActivity a;
  final ArrayDeque<String> queue = new ArrayDeque<>();
  String notebook = null;
  boolean reverse = false, revealed = false;
  int completed = 0, batchSize = 10;
  ReviewLogic.Progress progressState = new ReviewLogic.Progress(0);

  ReviewUi(MainActivity a) {
    this.a = a;
  }

  void show() {
    JSONArray all = a.library.array("vocabularies");
    long now = System.currentTimeMillis();
    List<JSONObject> currentPool = new ArrayList<>();
    for (int i = 0; i < all.length(); i++) {
      JSONObject v = all.optJSONObject(i);
      JSONObject selectedNote = notebook == null ? null : a.library.find("notebooks", notebook);
      if (selectedNote == null
          || NotebookUi.contains(selectedNote.optJSONArray("vocabulary_ids"), v.optString("id")))
        currentPool.add(v);
    }
    if (queue.isEmpty()) {
      int due = 0, mastered = 0;
      for (JSONObject v : currentPool) {
        if (v.optLong("review_due", 0) <= now) due++;
        if (ReviewLogic.mastered(v.has("review_mastery_level") && !v.isNull("review_mastery_level")
            ? v.optInt("review_mastery_level") : null, v.optInt("review_count"),
            v.optString("review_grade", ""))) mastered++;
      }
      LinearLayout stats = a.card(null);
      LinearLayout heading = a.row();
      LinearLayout iconCircle = a.column();
      iconCircle.setGravity(android.view.Gravity.CENTER);
      android.graphics.drawable.GradientDrawable circle =
          new android.graphics.drawable.GradientDrawable();
      circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
      circle.setColor((a.accent & 0x00FFFFFF) | 0x1A000000);
      iconCircle.setBackground(circle);
      iconCircle.addView(a.icon("repeat-outline", 20, a.accent));
      heading.addView(iconCircle, new LinearLayout.LayoutParams(a.dp(48), a.dp(48)));
      LinearLayout title = a.column();
      LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1);
      titleLp.leftMargin = a.dp(10);
      title.addView(a.text("复习背诵中心", 17, true));
      title.addView(a.text("基于间隔复习，巩固你的生词语感", 12, false));
      heading.addView(title, titleLp);
      stats.addView(heading);
      LinearLayout metrics = a.row();
      metrics.setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8));
      android.graphics.drawable.GradientDrawable metricsBg =
          new android.graphics.drawable.GradientDrawable();
      metricsBg.setColor(a.inputBg);
      metricsBg.setCornerRadius(a.dp(12));
      metricsBg.setStroke(a.dp(1), a.border);
      metrics.setBackground(metricsBg);
      addMetric(metrics, currentPool.size(), "当前范围生词", a.foreground);
      addMetricDivider(metrics);
      addMetric(metrics, mastered, "已熟练掌握", android.graphics.Color.parseColor("#10B981"));
      addMetricDivider(metrics);
      addMetric(metrics, due, "待复习强化", a.accent);
      stats.addView(metrics);
    }
    if (currentPool.isEmpty()) {
      LinearLayout empty = a.card(null);
      empty.setGravity(android.view.Gravity.CENTER);
      empty.setPadding(a.dp(20), a.dp(20), a.dp(20), a.dp(20));
      TextView bookIcon = a.icon("book-outline", 48, a.subtle);
      empty.addView(bookIcon);
      TextView emptyTitle = a.text("生词本空空如也", 16, true);
      LinearLayout.LayoutParams titleMargin = new LinearLayout.LayoutParams(-1, -2);
      titleMargin.topMargin = a.dp(10);
      empty.addView(emptyTitle, titleMargin);
      TextView emptyDescription = a.text("复习需要基于已收录的生词。快去「查询和记录」或「语境猜词」查几个词加入笔记吧！", 13, false);
      emptyDescription.setGravity(android.view.Gravity.CENTER);
      LinearLayout.LayoutParams descriptionMargin = new LinearLayout.LayoutParams(-1, -2);
      descriptionMargin.topMargin = a.dp(10);
      empty.addView(emptyDescription, descriptionMargin);
      empty.setMinimumHeight(a.dp(270));
      Button collect =
          a.primaryButton(
              "前往查词并收录",
              v -> {
                a.page = 0;
                a.render();
              });
      LinearLayout.LayoutParams collectLp = new LinearLayout.LayoutParams(a.dp(240), a.dp(44));
      collectLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
      collectLp.topMargin = a.dp(14);
      empty.addView(collect, collectLp);
      if (notebook != null) {
        empty.addView(
            a.button(
                "返回全部复习范围",
                v -> {
                  notebook = null;
                  queue.clear();
                  a.render();
                }));
      }
      return;
    }
    if (queue.isEmpty()) {
      LinearLayout options = a.card(null);
      TextView scopeTitle = a.text("1. 选择复习范围", 14, true);
      options.addView(scopeTitle);
      LinearLayout scopes = a.row();
      scopes.setPadding(0, a.dp(8), 0, a.dp(8));
      scopes.addView(
          scopePill(
              "全部生词 (" + all.length() + ")",
              notebook == null,
              () -> {
                notebook = null;
                queue.clear();
                a.render();
              }));
      for (JSONObject note : a.notebooks.leaves())
        scopes.addView(
            scopePill(
                note.optString("name") + " (" + vocabCount(note) + ")",
                note.optString("id").equals(notebook),
                () -> {
                  notebook = note.optString("id");
                  queue.clear();
                  a.render();
                }));
      HorizontalScrollView scopeScroll = new HorizontalScrollView(a);
      scopeScroll.setHorizontalScrollBarEnabled(false);
      scopeScroll.addView(scopes);
      options.addView(scopeScroll);
      TextView modeTitle = a.text("2. 复习模式", 14, true);
      LinearLayout.LayoutParams modeTitleLp = new LinearLayout.LayoutParams(-1, -2);
      modeTitleLp.topMargin = a.dp(12);
      options.addView(modeTitle, modeTitleLp);
      LinearLayout modeRow = a.row();
      LinearLayout.LayoutParams modeRowLp = new LinearLayout.LayoutParams(-1, a.dp(90));
      modeRowLp.topMargin = a.dp(8);
      options.addView(modeRow, modeRowLp);
      modeRow.addView(
          modeCard(
              "file-tray-outline",
              "看词猜义",
              "看词想意，翻转评级",
              !reverse,
              () -> {
                reverse = false;
                revealed = false;
                a.render();
              }),
          new LinearLayout.LayoutParams(0, -1, 1));
      View modeGap = new View(a);
      modeRow.addView(modeGap, new LinearLayout.LayoutParams(a.dp(12), 1));
      modeRow.addView(
          modeCard(
              "help-buoy-outline",
              "看义猜词",
              "看中文释义回忆拼写",
              reverse,
              () -> {
                reverse = true;
                revealed = false;
                a.render();
              }),
          new LinearLayout.LayoutParams(0, -1, 1));
      TextView sizeTitle = a.text("3. 本组复习量", 14, true);
      LinearLayout.LayoutParams sizeTitleLp = new LinearLayout.LayoutParams(-1, -2);
      sizeTitleLp.topMargin = a.dp(14);
      options.addView(sizeTitle, sizeTitleLp);
      LinearLayout sizes = a.row();
      LinearLayout.LayoutParams sizesLp = new LinearLayout.LayoutParams(-1, a.dp(40));
      sizesLp.topMargin = a.dp(8);
      options.addView(sizes, sizesLp);
      for (int size : new int[] {10, 20, 50})
        sizes.addView(batchSizeButton(size), new LinearLayout.LayoutParams(0, a.dp(40), 1));
      Button start =
          a.primaryButton(
              "开启本组复习",
              v -> {
                queue.clear();
                JSONArray vs = a.library.array("vocabularies");
                JSONObject n = notebook == null ? null : a.library.find("notebooks", notebook);
                long reviewStartedAt = System.currentTimeMillis();
                for (int i = 0; i < vs.length() && queue.size() < batchSize; i++) {
                  JSONObject word = vs.optJSONObject(i);
                  if (n != null
                      && !NotebookUi.contains(
                          n.optJSONArray("vocabulary_ids"), word.optString("id"))) continue;
                  if (word.optLong("review_due", 0) <= reviewStartedAt
                      && !queue.contains(word.optString("id")))
                    queue.add(word.optString("id"));
                }
                revealed = false;
                completed = 0;
                progressState = new ReviewLogic.Progress(queue.size());
                a.render();
              });
      LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(-1, a.dp(56));
      startLp.topMargin = a.dp(6);
      start.setLayoutParams(startLp);
      start.setTextSize(16 * a.scale);
      // Keep the primary action outside the options card, as in the reference screen.
      a.body.addView(start);
    }
    if (queue.isEmpty()) {
      int dueCount = 0;
      for (JSONObject v : currentPool) if (v.optLong("review_due", 0) <= now) dueCount++;
      if (dueCount == 0 || completed > 0 || progressState.skipped > 0) {
        LinearLayout empty = a.column();
        empty.setGravity(android.view.Gravity.CENTER);
        empty.addView(a.icon("checkmark-circle-outline", 40, a.accent));
        empty.addView(
            a.text((completed > 0 || progressState.skipped > 0) ? "本轮完成 " + completed + " 张卡片。" + (progressState.skipped > 0 ? "跳过 " + progressState.skipped + " 张。" : "") + "评分已保存。" : "当前范围暂无待复习词汇。", 16, true));
        a.body.addView(empty, new LinearLayout.LayoutParams(-1, a.dp(180)));
      }
      return;
    }
    JSONObject word = a.library.find("vocabularies", queue.peek());
    if (word == null) {
      queue.remove();
      progressState.skip();
      a.render();
      return;
    }
    LinearLayout card = a.card(null);
    card.setMinimumHeight(a.dp(360));
    int total = progressState.total;
    card.addView(a.text("已完成 " + completed + "/" + total + " 张", 13, false));
    card.addView(a.text("剩余 " + queue.size() + " · 跳过 " + progressState.skipped, 12, false));
    ProgressBar progress = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
    progress.setMax(Math.max(1, total));
    progress.setProgress(completed + progressState.skipped);
    card.addView(progress, new LinearLayout.LayoutParams(-1, a.dp(5)));
    JSONArray meanings = word.optJSONArray("meanings");
    JSONObject meaning =
        meanings == null || meanings.length() == 0 ? null : meanings.optJSONObject(0);
    if (meaning == null
        || meaning.optString("zh_definition").trim().isEmpty()
            && meaning.optString("en_definition").trim().isEmpty()) {
      card.addView(a.text("此词条暂无释义，暂时无法复习。", 17, false));
      LinearLayout footer = a.fixedFooter();
      footer.addView(
          a.button(
              "跳过此词",
              v -> {
                queue.remove();
                progressState.skip();
                revealed = false;
                a.render();
              }));
      return;
    }
    String definition = meaning.optString("zh_definition").trim();
    if (definition.isEmpty()) definition = meaning.optString("en_definition");
    card.addView(a.text(reverse ? definition : word.optString("content"), 28, true));
    LinearLayout footer = a.fixedFooter();
    if (!revealed)
      footer.addView(
          a.primaryButton(
              "显示答案",
              v -> {
                revealed = true;
                a.render();
              }));
    else {
      card.addView(a.text(reverse ? word.optString("content") : definition, 22, true));
      card.addView(a.text(meaning.optString("example"), 16, false));
      LinearLayout row = footer;
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
                  Library.put(word, "review_mastery_level", ReviewLogic.nextLevel(
                      word.has("review_mastery_level") && !word.isNull("review_mastery_level")
                          ? word.optInt("review_mastery_level") : null,
                      word.optInt("review_count"), word.optString("review_grade", ""), grade));
                  Library.put(word, "review_grade", grade);
                  Library.put(word, "review_count", word.optInt("review_count") + 1);
                  Library.put(word, "reviewed_at", Library.now());
                  progressState.rate(grade);
                  completed = progressState.completed;
                  revealed = false;
                  a.persist();
                  a.render();
                }));
      row.setOrientation(LinearLayout.HORIZONTAL);
    }
  }

  void addMetric(LinearLayout row, int value, String label, int color) {
    LinearLayout column = a.column();
    column.setGravity(android.view.Gravity.CENTER);
    TextView number = a.text(String.valueOf(value), 24, true);
    number.setGravity(android.view.Gravity.CENTER);
    number.setTextColor(color);
    column.addView(number);
    TextView caption = a.text(label, 11, false);
    caption.setGravity(android.view.Gravity.CENTER);
    caption.setTextColor(a.muted);
    column.addView(caption);
    row.addView(column, new LinearLayout.LayoutParams(0, a.dp(66), 1));
  }

  Button scopePill(String label, boolean selected, Runnable action) {
    Button button = a.button(label, v -> action.run());
    button.setTextSize(12.5f * a.scale);
    button.setTextColor(selected ? android.graphics.Color.WHITE : a.foreground);
    button.setPadding(a.dp(14), 0, a.dp(14), 0);
    button.setBackground(
        a.shape(selected ? a.accent : a.inputBg, 24, selected ? a.accent : a.border));
    button.setLayoutParams(new LinearLayout.LayoutParams(-2, a.dp(40)));
    return button;
  }

  int vocabCount(JSONObject note) {
    JSONArray ids = note.optJSONArray("vocabulary_ids");
    return ids == null ? 0 : ids.length();
  }

  LinearLayout modeCard(
      String icon, String title, String description, boolean selected, Runnable action) {
    LinearLayout card = a.column();
    card.setGravity(android.view.Gravity.CENTER);
    card.setPadding(a.dp(6), a.dp(6), a.dp(6), a.dp(6));
    int tint = (a.accent & 0x00FFFFFF) | 0x1A000000;
    card.setBackground(a.shape(selected ? tint : a.inputBg, 14, selected ? a.accent : a.border));
    card.setOnClickListener(v -> action.run());
    int color = selected ? a.accent : a.muted;
    card.addView(a.icon(icon, 20, color));
    TextView heading = a.text(title, 14, true);
    heading.setTextColor(selected ? a.accent : a.foreground);
    heading.setGravity(android.view.Gravity.CENTER);
    LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(-1, -2);
    headingLp.topMargin = a.dp(2);
    card.addView(heading, headingLp);
    TextView caption = a.text(description, 11, false);
    caption.setTextColor(a.muted);
    caption.setGravity(android.view.Gravity.CENTER);
    card.addView(caption);
    a.passiveLabels(card);
    return card;
  }

  Button batchSizeButton(int size) {
    boolean selected = batchSize == size;
    Button button =
        a.button(
            size + " 词 / 组",
            v -> {
              batchSize = size;
              a.render();
            });
    button.setTextSize(13 * a.scale);
    button.setTextColor(selected ? a.accent : a.foreground);
    button.setBackground(
        a.shape(
            selected ? (a.accent & 0x00FFFFFF) | 0x1A000000 : a.inputBg,
            10,
            selected ? a.accent : a.border));
    return button;
  }

  void addMetricDivider(LinearLayout row) {
    View divider = new View(a);
    divider.setBackgroundColor(a.border);
    row.addView(divider, new LinearLayout.LayoutParams(a.dp(1), a.dp(42)));
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
