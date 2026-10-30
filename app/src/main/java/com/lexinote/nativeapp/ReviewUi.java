package com.lexinote.nativeapp;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.*;
import org.json.*;

final class ReviewUi {
  final MainActivity a;
  String phase = "dashboard"; // "dashboard", "active", "completed"
  final ArrayDeque<String> queue = new ArrayDeque<>();
  final List<String> initialBatchIds = new ArrayList<>();
  String notebook = null;
  boolean reverse = false, revealed = false;
  int batchSize = 10;
  ReviewLogic.Progress progressState = new ReviewLogic.Progress(0);

  static final class SessionSummary {
    int initialTotal = 0;
    int easyCount = 0;
    int hardCount = 0;
    int againCount = 0;

    void reset(int total) {
      initialTotal = total;
      easyCount = 0;
      hardCount = 0;
      againCount = 0;
    }
  }

  final SessionSummary sessionSummary = new SessionSummary();

  ReviewUi(MainActivity a) {
    this.a = a;
  }

  void show() {
    if ("active".equals(phase)) {
      renderActiveSession();
    } else if ("completed".equals(phase)) {
      renderCompletedSession();
    } else {
      phase = "dashboard";
      renderDashboard();
    }
  }

  Set<String> getNotebookVocabIdsRecursive(String nbId) {
    Set<String> set = new HashSet<>();
    JSONObject nb = a.library.find("notebooks", nbId);
    if (nb != null) {
      collectVocabIdsRecursive(nb, set);
    }
    return set;
  }

  void collectVocabIdsRecursive(JSONObject nb, Set<String> out) {
    if (nb == null) return;
    JSONArray direct = nb.optJSONArray("vocabulary_ids");
    for (int i = 0; direct != null && i < direct.length(); i++) {
      String vid = direct.optString(i);
      if (!vid.isEmpty()) out.add(vid);
    }
    JSONArray children = nb.optJSONArray("child_notebook_ids");
    for (int i = 0; children != null && i < children.length(); i++) {
      String cid = children.optString(i);
      JSONObject child = a.library.find("notebooks", cid);
      if (child != null) {
        collectVocabIdsRecursive(child, out);
      }
    }
  }

  List<JSONObject> getVocabPool() {
    JSONArray all = a.library.array("vocabularies");
    List<JSONObject> pool = new ArrayList<>();
    Set<String> filterIds = (notebook == null || "all".equals(notebook)) ? null : getNotebookVocabIdsRecursive(notebook);
    for (int i = 0; i < all.length(); i++) {
      JSONObject v = all.optJSONObject(i);
      if (v == null) continue;
      if (filterIds == null || filterIds.contains(v.optString("id"))) {
        pool.add(v);
      }
    }
    return pool;
  }

  void renderDashboard() {
    a.body.removeAllViews();
    a.body.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(32));

    List<JSONObject> currentPool = getVocabPool();
    long now = System.currentTimeMillis();
    int due = 0, mastered = 0;
    for (JSONObject v : currentPool) {
      if (v.optLong("review_due", 0) <= now) due++;
      Integer level = v.has("review_mastery_level") && !v.isNull("review_mastery_level")
          ? v.optInt("review_mastery_level") : null;
      if (ReviewLogic.mastered(level, v.optInt("review_count"), v.optString("review_grade", ""))) {
        mastered++;
      }
    }

    // 1. Statistics Dashboard Card
    LinearLayout stats = a.card(null);
    LinearLayout heading = a.row();
    LinearLayout iconCircle = a.column();
    iconCircle.setGravity(Gravity.CENTER);
    GradientDrawable circle = new GradientDrawable();
    circle.setShape(GradientDrawable.OVAL);
    circle.setColor((a.accent & 0x00FFFFFF) | 0x1A000000);
    iconCircle.setBackground(circle);
    iconCircle.addView(a.icon("repeat-outline", 22, a.accent));
    heading.addView(iconCircle, new LinearLayout.LayoutParams(a.dp(48), a.dp(48)));

    LinearLayout title = a.column();
    LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1);
    titleLp.leftMargin = a.dp(12);
    title.addView(a.text("复习背诵中心", 18, true));
    TextView subTv = a.text("基于艾宾浩斯记忆曲线，巩固你的生词语感", 12, false);
    subTv.setTextColor(a.muted);
    title.addView(subTv);
    heading.addView(title, titleLp);
    stats.addView(heading);

    LinearLayout metrics = a.row();
    metrics.setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8));
    GradientDrawable metricsBg = new GradientDrawable();
    metricsBg.setColor(a.inputBg);
    metricsBg.setCornerRadius(a.dp(12));
    metricsBg.setStroke(a.dp(1), a.border);
    metrics.setBackground(metricsBg);
    LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(-1, -2);
    mLp.topMargin = a.dp(14);
    metrics.setLayoutParams(mLp);

    addMetric(metrics, currentPool.size(), "当前生词", a.foreground);
    addMetricDivider(metrics);
    addMetric(metrics, mastered, "已熟练掌握", Color.parseColor("#10B981"));
    addMetricDivider(metrics);
    addMetric(metrics, due, "待复习强化", a.accent);
    stats.addView(metrics);

    if (currentPool.isEmpty()) {
      LinearLayout empty = a.card(null);
      empty.setGravity(Gravity.CENTER);
      empty.setPadding(a.dp(20), a.dp(30), a.dp(20), a.dp(30));
      TextView bookIcon = a.icon("book-outline", 48, a.subtle);
      empty.addView(bookIcon);
      TextView emptyTitle = a.text("生词本空空如也", 16, true);
      LinearLayout.LayoutParams titleMargin = new LinearLayout.LayoutParams(-1, -2);
      titleMargin.topMargin = a.dp(10);
      empty.addView(emptyTitle, titleMargin);
      TextView emptyDescription = a.text("复习需要基于已收录的生词。快去「查询和记录」或「语境猜词」查几个词加入笔记吧！", 13, false);
      emptyDescription.setGravity(Gravity.CENTER);
      emptyDescription.setTextColor(a.muted);
      LinearLayout.LayoutParams descriptionMargin = new LinearLayout.LayoutParams(-1, -2);
      descriptionMargin.topMargin = a.dp(10);
      empty.addView(emptyDescription, descriptionMargin);

      Button collect = a.primaryButton("前往查词并收录", v -> {
        a.page = 0;
        a.render();
      });
      LinearLayout.LayoutParams collectLp = new LinearLayout.LayoutParams(a.dp(220), a.dp(44));
      collectLp.gravity = Gravity.CENTER_HORIZONTAL;
      collectLp.topMargin = a.dp(18);
      empty.addView(collect, collectLp);

      if (notebook != null) {
        Button resetScope = a.button("返回全部复习范围", v -> {
          notebook = null;
          queue.clear();
          a.render();
        });
        resetScope.setTextColor(a.accent);
        empty.addView(resetScope);
      }
      return;
    }

    // 2. Review Configuration Card
    LinearLayout options = a.card(null);
    options.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(16));
    LinearLayout.LayoutParams optLp = new LinearLayout.LayoutParams(-1, -2);
    optLp.topMargin = a.dp(12);
    options.setLayoutParams(optLp);

    // Scope selection
    TextView scopeTitle = a.text("1. 选择复习范围", 14, true);
    options.addView(scopeTitle);
    LinearLayout scopes = a.row();
    scopes.setPadding(0, a.dp(8), 0, a.dp(8));
    scopes.addView(scopePill(
        "全部生词 (" + a.library.array("vocabularies").length() + ")",
        notebook == null || "all".equals(notebook),
        () -> {
          notebook = null;
          queue.clear();
          a.render();
        }));

    JSONArray allNb = a.library.array("notebooks");
    for (int i = 0; i < allNb.length(); i++) {
      JSONObject nb = allNb.optJSONObject(i);
      if (nb == null) continue;
      String id = nb.optString("id");
      if ("nb_global_root".equals(id)) continue;
      int count = getNotebookVocabIdsRecursive(id).size();
      if (count == 0) continue;
      String name = nb.optString("name", "笔记");
      boolean isSelected = id.equals(notebook);
      scopes.addView(scopePill(name + " (" + count + ")", isSelected, () -> {
        notebook = id;
        queue.clear();
        a.render();
      }));
    }

    HorizontalScrollView scopeScroll = new HorizontalScrollView(a);
    scopeScroll.setHorizontalScrollBarEnabled(false);
    scopeScroll.addView(scopes);
    options.addView(scopeScroll);

    // Mode selection
    TextView modeTitle = a.text("2. 复习模式", 14, true);
    LinearLayout.LayoutParams modeTitleLp = new LinearLayout.LayoutParams(-1, -2);
    modeTitleLp.topMargin = a.dp(12);
    options.addView(modeTitle, modeTitleLp);

    LinearLayout modeRow = a.row();
    LinearLayout.LayoutParams modeRowLp = new LinearLayout.LayoutParams(-1, a.dp(84));
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
    modeRow.addView(modeGap, new LinearLayout.LayoutParams(a.dp(10), 1));
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

    // Batch size selection
    TextView sizeTitle = a.text("3. 本组复习量", 14, true);
    LinearLayout.LayoutParams sizeTitleLp = new LinearLayout.LayoutParams(-1, -2);
    sizeTitleLp.topMargin = a.dp(14);
    options.addView(sizeTitle, sizeTitleLp);

    LinearLayout sizes = a.row();
    LinearLayout.LayoutParams sizesLp = new LinearLayout.LayoutParams(-1, a.dp(40));
    sizesLp.topMargin = a.dp(8);
    options.addView(sizes, sizesLp);
    for (int size : new int[] {10, 20, 30}) {
      sizes.addView(batchSizeButton(size), new LinearLayout.LayoutParams(0, a.dp(40), 1));
    }

    // 3. Start Button
    Button start = a.primaryButton("开启本组复习", v -> startReview());
    LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(-1, a.dp(52));
    startLp.topMargin = a.dp(16);
    start.setLayoutParams(startLp);
    start.setTextSize(16 * a.scale);
    a.body.addView(start);
  }

  void startReview() {
    List<JSONObject> pool = getVocabPool();
    if (pool.isEmpty()) {
      a.say("当前范围暂无生词，请先添加生词后再复习");
      return;
    }
    queue.clear();
    initialBatchIds.clear();
    long now = System.currentTimeMillis();

    List<JSONObject> dueWords = new ArrayList<>();
    List<JSONObject> otherWords = new ArrayList<>();
    for (JSONObject v : pool) {
      long d = v.optLong("review_due", 0);
      if (d <= now) dueWords.add(v);
      else otherWords.add(v);
    }

    Collections.sort(otherWords, (v1, v2) -> {
      int c1 = v1.optInt("review_count", 0);
      int c2 = v2.optInt("review_count", 0);
      if (c1 != c2) return Integer.compare(c1, c2);
      return Long.compare(v1.optLong("review_due", 0), v2.optLong("review_due", 0));
    });

    List<JSONObject> selectedList = new ArrayList<>();
    for (JSONObject v : dueWords) {
      if (selectedList.size() >= batchSize) break;
      selectedList.add(v);
    }
    for (JSONObject v : otherWords) {
      if (selectedList.size() >= batchSize) break;
      selectedList.add(v);
    }

    for (JSONObject v : selectedList) {
      String id = v.optString("id");
      queue.add(id);
      initialBatchIds.add(id);
    }

    revealed = false;
    progressState = new ReviewLogic.Progress(queue.size());
    sessionSummary.reset(queue.size());
    phase = "active";
    a.render();
  }

  void renderActiveSession() {
    a.body.removeAllViews();
    a.body.setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(24));

    if (queue.isEmpty()) {
      phase = "completed";
      a.render();
      return;
    }

    String wordId = queue.peek();
    JSONObject word = a.library.find("vocabularies", wordId);
    if (word == null) {
      queue.remove();
      progressState.skip();
      a.render();
      return;
    }

    // 1. Top Session Bar
    LinearLayout topBar = a.row();
    topBar.setGravity(Gravity.CENTER_VERTICAL);
    topBar.setPadding(0, 0, 0, a.dp(10));

    // Exit Button
    Button exitBtn = a.chip("‹ 退出", false, v -> confirmExit());
    exitBtn.setTextSize(11 * a.scale);
    exitBtn.setPadding(a.dp(8), 0, a.dp(8), 0);
    LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(-2, a.dp(28));
    eLp.rightMargin = a.dp(6);
    topBar.addView(exitBtn, eLp);

    // Progress container
    LinearLayout progressBox = a.column();
    LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(0, -2, 1);
    pbLp.leftMargin = a.dp(4);
    pbLp.rightMargin = a.dp(12);
    progressBox.setLayoutParams(pbLp);

    LinearLayout pLabels = a.row();
    int currentStep = Math.min(sessionSummary.initialTotal, sessionSummary.initialTotal - queue.size() + 1);
    int percent = Math.min(100, (int) Math.round(((double) (sessionSummary.initialTotal - queue.size()) / Math.max(1, sessionSummary.initialTotal)) * 100));
    TextView stepTv = a.text("第 " + currentStep + " / " + sessionSummary.initialTotal + " 词", 12, true);
    stepTv.setTextColor(a.foreground);
    pLabels.addView(stepTv);

    View pSpacer = new View(a);
    pLabels.addView(pSpacer, new LinearLayout.LayoutParams(0, 1, 1));

    TextView pctTv = a.text(percent + "%", 12, true);
    pctTv.setTextColor(a.accent);
    pLabels.addView(pctTv);
    progressBox.addView(pLabels);

    ProgressBar pb = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
    pb.setMax(Math.max(1, sessionSummary.initialTotal));
    pb.setProgress(sessionSummary.initialTotal - queue.size());
    LinearLayout.LayoutParams pbBarLp = new LinearLayout.LayoutParams(-1, a.dp(4));
    pbBarLp.topMargin = a.dp(4);
    progressBox.addView(pb, pbBarLp);
    topBar.addView(progressBox);

    // Face indicator
    TextView faceBadge = a.text(revealed ? "背面 · 释义解析" : "正面 · 尝试回忆", 11, true);
    faceBadge.setTextColor(revealed ? a.accent : a.muted);
    faceBadge.setPadding(a.dp(8), a.dp(3), a.dp(8), a.dp(3));
    faceBadge.setBackground(a.shape(revealed ? ((a.accent & 0x00FFFFFF) | 0x1A000000) : a.inputBg, 12, revealed ? a.accent : a.border));
    topBar.addView(faceBadge);
    a.body.addView(topBar);

    // 2. Center Flashcard
    LinearLayout card = a.card(null);
    card.setMinimumHeight(a.dp(380));
    card.setPadding(a.dp(18), a.dp(16), a.dp(18), a.dp(18));
    card.setClickable(true);
    card.setFocusable(false);
    card.setOnClickListener(v -> {
      revealed = !revealed;
      a.render();
    });

    JSONArray meanings = word.optJSONArray("meanings");
    JSONObject firstMeaning = (meanings != null && meanings.length() > 0) ? meanings.optJSONObject(0) : null;
    String wordContent = word.optString("content", "");
    String phonetic = word.optString("phonetic", "");
    if (phonetic.isEmpty() && firstMeaning != null) {
      phonetic = firstMeaning.optString("phonetic", "");
    }
    String typeLabel = word.optString("type", "word").equals("phrase") ? "短语" : "单词";

    if (!revealed) {
      // ===== FRONT SIDE =====
      LinearLayout cardTop = a.row();
      cardTop.setGravity(Gravity.CENTER_VERTICAL);
      TextView typeTv = a.text(typeLabel, 11, true);
      typeTv.setTextColor(a.accent);
      typeTv.setPadding(a.dp(7), a.dp(2), a.dp(7), a.dp(2));
      typeTv.setBackground(a.shape((a.accent & 0x00FFFFFF) | 0x1A000000, 10, 0));
      cardTop.addView(typeTv);

      View ctSpacer = new View(a);
      cardTop.addView(ctSpacer, new LinearLayout.LayoutParams(0, 1, 1));

      TextView flipHint = a.text("轻触卡片翻转 ↻", 11, false);
      flipHint.setTextColor(a.muted);
      cardTop.addView(flipHint);
      card.addView(cardTop);

      if (!reverse) {
        // 看词猜义: Front shows English word + Phonetic + Context sentence clue
        TextView wordTv = a.text(wordContent, 30, true);
        wordTv.setTextColor(a.foreground);
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(-1, -2);
        wLp.topMargin = a.dp(20);
        card.addView(wordTv, wLp);

        if (!phonetic.isEmpty()) {
          String pStr = phonetic.startsWith("/") ? phonetic : "/" + phonetic + "/";
          TextView phTv = a.text(pStr, 15, false);
          phTv.setTextColor(a.muted);
          LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(-1, -2);
          pLp.topMargin = a.dp(4);
          card.addView(phTv, pLp);
        }

        if (firstMeaning != null && !firstMeaning.optString("example").trim().isEmpty()) {
          LinearLayout egBox = a.column();
          egBox.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
          egBox.setBackground(a.shape(a.inputBg, 10, a.border));
          LinearLayout.LayoutParams egLp = new LinearLayout.LayoutParams(-1, -2);
          egLp.topMargin = a.dp(24);

          TextView egHdr = a.text("语境例句线索：", 11, true);
          egHdr.setTextColor(a.accent);
          egBox.addView(egHdr);

          TextView egText = a.text(firstMeaning.optString("example"), 14, false);
          egText.setTextColor(a.foreground);
          LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(-1, -2);
          etLp.topMargin = a.dp(4);
          egBox.addView(egText, etLp);
          card.addView(egBox, egLp);
        }
      } else {
        // 看义猜词: Front shows Chinese definition + Part of speech
        String zhDef = firstMeaning != null ? firstMeaning.optString("zh_definition") : "";
        if (zhDef.isEmpty() && firstMeaning != null) zhDef = firstMeaning.optString("en_definition");
        TextView zhTv = a.text(zhDef, 24, true);
        zhTv.setTextColor(a.foreground);
        LinearLayout.LayoutParams zLp = new LinearLayout.LayoutParams(-1, -2);
        zLp.topMargin = a.dp(24);
        card.addView(zhTv, zLp);

        if (firstMeaning != null && !firstMeaning.optString("part_of_speech").isEmpty()) {
          TextView posTv = a.text(firstMeaning.optString("part_of_speech"), 12, true);
          posTv.setTextColor(a.accent);
          posTv.setPadding(a.dp(8), a.dp(3), a.dp(8), a.dp(3));
          posTv.setBackground(a.shape(a.inputBg, 8, a.border));
          LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(-2, -2);
          pLp.topMargin = a.dp(12);
          card.addView(posTv, pLp);
        }
      }

      View bottomSpacer = new View(a);
      card.addView(bottomSpacer, new LinearLayout.LayoutParams(-1, 0, 1));

      TextView tapPrompt = a.text("点击卡片任意区域查看解析", 12, false);
      tapPrompt.setTextColor(a.muted);
      tapPrompt.setGravity(Gravity.CENTER);
      LinearLayout.LayoutParams tpLp = new LinearLayout.LayoutParams(-1, -2);
      tpLp.topMargin = a.dp(16);
      card.addView(tapPrompt, tpLp);

    } else {
      // ===== BACK SIDE =====
      LinearLayout backHeader = a.row();
      backHeader.setGravity(Gravity.CENTER_VERTICAL);
      TextView wordTv = a.text(wordContent, 22, true);
      wordTv.setTextColor(a.foreground);
      backHeader.addView(wordTv);

      if (!phonetic.isEmpty()) {
        String pStr = phonetic.startsWith("/") ? phonetic : "/" + phonetic + "/";
        TextView phTv = a.text(" " + pStr, 13, false);
        phTv.setTextColor(a.muted);
        backHeader.addView(phTv);
      }

      View bhSpacer = new View(a);
      backHeader.addView(bhSpacer, new LinearLayout.LayoutParams(0, 1, 1));

      TextView typeBadge = a.text(typeLabel, 10, true);
      typeBadge.setTextColor(a.accent);
      typeBadge.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
      typeBadge.setBackground(a.shape((a.accent & 0x00FFFFFF) | 0x1A000000, 8, 0));
      backHeader.addView(typeBadge);
      card.addView(backHeader);

      View div = new View(a);
      div.setBackgroundColor(a.border);
      LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(-1, a.dp(1));
      dLp.topMargin = a.dp(10);
      dLp.bottomMargin = a.dp(10);
      card.addView(div, dLp);

      // Meanings list
      if (meanings != null) {
        for (int i = 0; i < meanings.length(); i++) {
          JSONObject m = meanings.optJSONObject(i);
          if (m == null) continue;
          LinearLayout mBox = a.column();
          mBox.setPadding(0, a.dp(4), 0, a.dp(6));

          LinearLayout mHeader = a.row();
          mHeader.setGravity(Gravity.CENTER_VERTICAL);

          if (meanings.length() > 1) {
            TextView idxTv = a.text(String.valueOf(i + 1), 10, true);
            idxTv.setTextColor(a.accent);
            idxTv.setPadding(a.dp(5), a.dp(1), a.dp(5), a.dp(1));
            idxTv.setBackground(a.shape((a.accent & 0xFFFFFF) | 0x1A000000, 6, 0));
            mHeader.addView(idxTv);
          }

          String pos = m.optString("part_of_speech").trim();
          if (!pos.isEmpty()) {
            TextView posTv = a.text(pos, 11, true);
            posTv.setTextColor(a.accent);
            posTv.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
            posTv.setBackground(a.shape(a.inputBg, 6, a.border));
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(-2, -2);
            if (meanings.length() > 1) pLp.leftMargin = a.dp(6);
            mHeader.addView(posTv, pLp);
          }
          if (mHeader.getChildCount() > 0) mBox.addView(mHeader);

          String zh = m.optString("zh_definition").trim();
          if (!zh.isEmpty()) {
            TextView zhTv = a.text(zh, 15, true);
            zhTv.setTextColor(a.foreground);
            LinearLayout.LayoutParams zhLp = new LinearLayout.LayoutParams(-1, -2);
            zhLp.topMargin = a.dp(2);
            mBox.addView(zhTv, zhLp);
          }

          String en = m.optString("en_definition").trim();
          if (!en.isEmpty()) {
            TextView enTv = a.text(en, 13, false);
            enTv.setTextColor(a.muted);
            LinearLayout.LayoutParams enLp = new LinearLayout.LayoutParams(-1, -2);
            enLp.topMargin = a.dp(2);
            mBox.addView(enTv, enLp);
          }

          String eg = m.optString("example").trim();
          if (!eg.isEmpty()) {
            LinearLayout egBox = a.column();
            egBox.setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6));
            egBox.setBackground(a.shape(a.inputBg, 6, a.border));
            LinearLayout.LayoutParams egLp = new LinearLayout.LayoutParams(-1, -2);
            egLp.topMargin = a.dp(6);
            TextView egTv = a.text("例句：" + eg, 12, false);
            egTv.setTextColor(a.foreground);
            egBox.addView(egTv);
            mBox.addView(egBox, egLp);
          }

          JSONArray notes = m.optJSONArray("annotations");
          if (notes != null && notes.length() > 0) {
            for (int j = 0; j < notes.length(); j++) {
              JSONObject note = notes.optJSONObject(j);
              if (note == null) continue;
              LinearLayout noteBox = a.row();
              noteBox.setPadding(a.dp(8), a.dp(4), a.dp(8), a.dp(4));
              noteBox.setBackground(a.shape((a.accent & 0xFFFFFF) | 0x14000000, 6, 0));
              LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(-1, -2);
              nLp.topMargin = a.dp(4);
              String target = note.optString("target_text");
              String content = note.optString("content");
              TextView noteTv = a.text((target.isEmpty() ? "" : "「" + target + "」 ") + content, 12, false);
              noteTv.setTextColor(a.foreground);
              noteBox.addView(noteTv);
              mBox.addView(noteBox, nLp);
            }
          }
          card.addView(mBox);
        }
      }

      // Tags & Notebook chips
      JSONArray tags = word.optJSONArray("tags");
      if (tags != null && tags.length() > 0) {
        LinearLayout tagRow = a.row();
        tagRow.setPadding(0, a.dp(8), 0, 0);
        for (int i = 0; i < tags.length(); i++) {
          String t = tags.optString(i);
          if (NotebookUi.SYSTEM_STATUS_TAGS.contains(t)) continue;
          TextView tTv = a.text("#" + t, 11, true);
          tTv.setTextColor(a.accent);
          tTv.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
          tTv.setBackground(a.shape((a.accent & 0x00FFFFFF) | 0x1A000000, 10, 0));
          LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-2, -2);
          tLp.rightMargin = a.dp(4);
          tagRow.addView(tTv, tLp);
        }
        if (tagRow.getChildCount() > 0) card.addView(tagRow);
      }
    }

    // 3. Bottom Rating / Flip Action Bar
    LinearLayout footer = a.fixedFooter();
    footer.removeAllViews();
    footer.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(12));

    if (!revealed) {
      Button showAnswerBtn = a.primaryButton("查看答案 / 翻转卡片", v -> {
        revealed = true;
        a.render();
      });
      LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(-1, a.dp(50));
      showAnswerBtn.setLayoutParams(sbLp);
      showAnswerBtn.setTextSize(16 * a.scale);
      footer.addView(showAnswerBtn);
    } else {
      LinearLayout rateRow = a.row();
      rateRow.setGravity(Gravity.CENTER_VERTICAL);
      rateRow.setLayoutParams(new LinearLayout.LayoutParams(-1, a.dp(52)));

      // 再次 (Red)
      rateRow.addView(createRatingButton("再次", "<10分钟", Color.parseColor("#EF4444"), false, () -> {
        String id = queue.remove();
        queue.add(id); // Re-queue at tail for repeat review
        Library.put(word, "review_due", System.currentTimeMillis());
        Library.put(word, "review_mastery_level", ReviewLogic.nextLevel(
            word.has("review_mastery_level") && !word.isNull("review_mastery_level")
                ? word.optInt("review_mastery_level") : null,
            word.optInt("review_count"), word.optString("review_grade", ""), "再次"));
        Library.put(word, "review_grade", "再次");
        Library.put(word, "review_count", word.optInt("review_count") + 1);
        Library.put(word, "reviewed_at", Library.now());
        sessionSummary.againCount++;
        progressState.rate("再次");
        revealed = false;
        a.persist();
        a.render();
      }), new LinearLayout.LayoutParams(0, -1, 1));

      View gap1 = new View(a);
      rateRow.addView(gap1, new LinearLayout.LayoutParams(a.dp(8), 1));

      // 困难 (Amber)
      rateRow.addView(createRatingButton("困难", "1天后", Color.parseColor("#F59E0B"), false, () -> {
        queue.remove();
        long interval = 86400000L;
        Library.put(word, "review_due", System.currentTimeMillis() + interval);
        Library.put(word, "review_mastery_level", ReviewLogic.nextLevel(
            word.has("review_mastery_level") && !word.isNull("review_mastery_level")
                ? word.optInt("review_mastery_level") : null,
            word.optInt("review_count"), word.optString("review_grade", ""), "困难"));
        Library.put(word, "review_grade", "困难");
        Library.put(word, "review_count", word.optInt("review_count") + 1);
        Library.put(word, "reviewed_at", Library.now());
        sessionSummary.hardCount++;
        progressState.rate("困难");
        revealed = false;
        if (queue.isEmpty()) phase = "completed";
        a.persist();
        a.render();
      }), new LinearLayout.LayoutParams(0, -1, 1));

      View gap2 = new View(a);
      rateRow.addView(gap2, new LinearLayout.LayoutParams(a.dp(8), 1));

      // 简单 (Green)
      rateRow.addView(createRatingButton("简单", "4天后", Color.parseColor("#10B981"), true, () -> {
        queue.remove();
        long interval = 4 * 86400000L;
        Library.put(word, "review_due", System.currentTimeMillis() + interval);
        Library.put(word, "review_mastery_level", ReviewLogic.nextLevel(
            word.has("review_mastery_level") && !word.isNull("review_mastery_level")
                ? word.optInt("review_mastery_level") : null,
            word.optInt("review_count"), word.optString("review_grade", ""), "简单"));
        Library.put(word, "review_grade", "简单");
        Library.put(word, "review_count", word.optInt("review_count") + 1);
        Library.put(word, "reviewed_at", Library.now());
        sessionSummary.easyCount++;
        progressState.rate("简单");
        revealed = false;
        if (queue.isEmpty()) phase = "completed";
        a.persist();
        a.render();
      }), new LinearLayout.LayoutParams(0, -1, 1));

      footer.addView(rateRow);
    }
  }

  View createRatingButton(String label, String sub, int color, boolean solid, Runnable action) {
    LinearLayout btn = a.column();
    btn.setGravity(Gravity.CENTER);
    btn.setPadding(0, a.dp(6), 0, a.dp(6));
    btn.setClickable(true);
    btn.setFocusable(false);
    btn.setBackground(a.shape(solid ? color : ((color & 0x00FFFFFF) | 0x14000000), 12, color));
    btn.setOnClickListener(v -> action.run());

    TextView titleTv = a.text(label, 15, true);
    titleTv.setTextIsSelectable(false);
    titleTv.setFocusable(false);
    titleTv.setClickable(false);
    titleTv.setTextColor(solid ? Color.WHITE : color);
    titleTv.setGravity(Gravity.CENTER);
    btn.addView(titleTv);

    TextView subTv = a.text(sub, 10, false);
    subTv.setTextIsSelectable(false);
    subTv.setFocusable(false);
    subTv.setClickable(false);
    subTv.setTextColor(solid ? Color.parseColor("#CCFFFFFF") : color);
    subTv.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-2, -2);
    sLp.topMargin = a.dp(1);
    btn.addView(subTv, sLp);
    return btn;
  }

  void confirmExit() {
    new AlertDialog.Builder(a)
        .setTitle("退出复习")
        .setMessage("确定要提前结束当前这轮复习吗？已完成词汇的记录已安全保存。")
        .setNegativeButton("继续复习", null)
        .setPositiveButton("退出", (dialog, which) -> {
          queue.clear();
          revealed = false;
          phase = "dashboard";
          a.render();
        })
        .show();
  }

  void renderCompletedSession() {
    a.body.removeAllViews();
    a.body.setPadding(a.dp(20), a.dp(24), a.dp(20), a.dp(32));

    LinearLayout card = a.card(null);
    card.setGravity(Gravity.CENTER_HORIZONTAL);
    card.setPadding(a.dp(20), a.dp(28), a.dp(20), a.dp(28));

    // Trophy icon
    LinearLayout iconCircle = a.column();
    iconCircle.setGravity(Gravity.CENTER);
    GradientDrawable circle = new GradientDrawable();
    circle.setShape(GradientDrawable.OVAL);
    circle.setColor((a.accent & 0x00FFFFFF) | 0x1A000000);
    iconCircle.setBackground(circle);
    iconCircle.addView(a.icon("checkmark-circle-outline", 36, Color.parseColor("#10B981")));
    card.addView(iconCircle, new LinearLayout.LayoutParams(a.dp(72), a.dp(72)));

    TextView compTitle = a.text("太棒了！本轮复习完成", 20, true);
    compTitle.setTextColor(a.foreground);
    LinearLayout.LayoutParams ctLp = new LinearLayout.LayoutParams(-2, -2);
    ctLp.topMargin = a.dp(16);
    card.addView(compTitle, ctLp);

    TextView compSub = a.text("持之以恒，知识正在转化为你的长期语感记忆。", 13, false);
    compSub.setTextColor(a.muted);
    LinearLayout.LayoutParams csLp = new LinearLayout.LayoutParams(-2, -2);
    csLp.topMargin = a.dp(6);
    card.addView(compSub, csLp);

    // Stats Grid Box
    LinearLayout statsBox = a.row();
    statsBox.setPadding(a.dp(10), a.dp(12), a.dp(10), a.dp(12));
    GradientDrawable sBg = new GradientDrawable();
    sBg.setColor(a.inputBg);
    sBg.setCornerRadius(a.dp(12));
    sBg.setStroke(a.dp(1), a.border);
    statsBox.setBackground(sBg);
    LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(-1, -2);
    sbLp.topMargin = a.dp(20);
    statsBox.setLayoutParams(sbLp);

    addMetric(statsBox, sessionSummary.initialTotal, "复习总词数", a.foreground);
    addMetricDivider(statsBox);
    addMetric(statsBox, sessionSummary.easyCount, "轻松掌握", Color.parseColor("#10B981"));
    addMetricDivider(statsBox);
    addMetric(statsBox, sessionSummary.hardCount + sessionSummary.againCount, "需再强化", Color.parseColor("#F59E0B"));
    card.addView(statsBox);

    int masteredRatio = Math.round(((float) sessionSummary.easyCount / Math.max(1, sessionSummary.initialTotal)) * 100);
    LinearLayout ratioRow = a.row();
    ratioRow.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, -2);
    rLp.topMargin = a.dp(16);
    ratioRow.setLayoutParams(rLp);

    TextView rLabel = a.text("本轮轻松掌握率：", 13, false);
    rLabel.setTextColor(a.muted);
    ratioRow.addView(rLabel);

    TextView rVal = a.text(masteredRatio + "%", 16, true);
    rVal.setTextColor(a.accent);
    ratioRow.addView(rVal);
    card.addView(ratioRow);

    // Actions
    Button nextBatchBtn = a.primaryButton("再来一组（" + batchSize + "词）", v -> startReview());
    LinearLayout.LayoutParams nbLp = new LinearLayout.LayoutParams(-1, a.dp(48));
    nbLp.topMargin = a.dp(24);
    nextBatchBtn.setLayoutParams(nbLp);
    nextBatchBtn.setTextSize(15 * a.scale);
    card.addView(nextBatchBtn);

    Button backHomeBtn = a.button("返回复习中心", v -> {
      phase = "dashboard";
      a.render();
    });
    LinearLayout.LayoutParams bhLp = new LinearLayout.LayoutParams(-1, a.dp(44));
    bhLp.topMargin = a.dp(10);
    backHomeBtn.setLayoutParams(bhLp);
    backHomeBtn.setTextColor(a.accent);
    backHomeBtn.setBackground(a.shape(a.inputBg, 12, a.border));
    card.addView(backHomeBtn);
  }

  void addMetric(LinearLayout row, int value, String label, int color) {
    LinearLayout column = a.column();
    column.setGravity(Gravity.CENTER);
    TextView number = a.text(String.valueOf(value), 22, true);
    number.setGravity(Gravity.CENTER);
    number.setTextColor(color);
    column.addView(number);

    TextView caption = a.text(label, 11, false);
    caption.setGravity(Gravity.CENTER);
    caption.setTextColor(a.muted);
    column.addView(caption);
    row.addView(column, new LinearLayout.LayoutParams(0, a.dp(56), 1));
  }

  void addMetricDivider(LinearLayout row) {
    View divider = new View(a);
    divider.setBackgroundColor(a.border);
    row.addView(divider, new LinearLayout.LayoutParams(a.dp(1), a.dp(36)));
  }

  Button scopePill(String label, boolean selected, Runnable action) {
    Button button = a.button(label, v -> action.run());
    button.setTextSize(12.5f * a.scale);
    button.setTextColor(selected ? Color.WHITE : a.foreground);
    button.setPadding(a.dp(14), 0, a.dp(14), 0);
    button.setBackground(a.shape(selected ? a.accent : a.inputBg, 20, selected ? a.accent : a.border));
    button.setLayoutParams(new LinearLayout.LayoutParams(-2, a.dp(38)));
    return button;
  }

  LinearLayout modeCard(String icon, String title, String description, boolean selected, Runnable action) {
    LinearLayout card = a.column();
    card.setGravity(Gravity.CENTER);
    card.setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8));
    int tint = (a.accent & 0x00FFFFFF) | 0x1A000000;
    card.setBackground(a.shape(selected ? tint : a.inputBg, 14, selected ? a.accent : a.border));
    card.setOnClickListener(v -> action.run());

    int color = selected ? a.accent : a.muted;
    card.addView(a.icon(icon, 20, color));

    TextView heading = a.text(title, 14, true);
    heading.setTextIsSelectable(false);
    heading.setFocusable(false);
    heading.setClickable(false);
    heading.setTextColor(selected ? a.accent : a.foreground);
    heading.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(-1, -2);
    headingLp.topMargin = a.dp(4);
    card.addView(heading, headingLp);

    TextView caption = a.text(description, 11, false);
    caption.setTextIsSelectable(false);
    caption.setFocusable(false);
    caption.setClickable(false);
    caption.setTextColor(a.muted);
    caption.setGravity(Gravity.CENTER);
    card.addView(caption);
    return card;
  }

  Button batchSizeButton(int size) {
    boolean selected = (batchSize == size);
    Button button = a.button(size + " 词 / 组", v -> {
      batchSize = size;
      a.render();
    });
    button.setTextSize(13 * a.scale);
    button.setTextColor(selected ? a.accent : a.foreground);
    button.setBackground(a.shape(selected ? ((a.accent & 0x00FFFFFF) | 0x1A000000) : a.inputBg, 10, selected ? a.accent : a.border));
    return button;
  }
}
