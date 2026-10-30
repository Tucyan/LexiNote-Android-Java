package com.lexinote.nativeapp;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.json.*;

final class ReviewUi {
  final MainActivity a;
  String phase = "dashboard"; // "dashboard", "active", "completed"
  String activePlanId = null;
  boolean reverse = false, revealed = false;
  long cardStartTime = 0L;

  // Preserved fields for state compatibility
  String notebook = null;
  final Set<String> selectedTags = new HashSet<>();
  int batchSize = 10;
  final ArrayDeque<String> queue = new ArrayDeque<>();
  final List<String> initialBatchIds = new ArrayList<>();
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

  void renderDashboard() {
    a.body.removeAllViews();
    a.body.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(32));

    JSONArray activePlans = a.library.reviewPlans().list(false);

    // 1. Header Card
    LinearLayout header = a.card(null);
    LinearLayout heading = a.row();
    heading.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout iconCircle = a.column();
    iconCircle.setGravity(Gravity.CENTER);
    GradientDrawable circle = new GradientDrawable();
    circle.setShape(GradientDrawable.OVAL);
    circle.setColor((a.accent & 0x00FFFFFF) | 0x1A000000);
    iconCircle.setBackground(circle);
    iconCircle.addView(a.icon("calendar-outline", 24, a.accent));
    heading.addView(iconCircle, new LinearLayout.LayoutParams(a.dp(48), a.dp(48)));

    LinearLayout titleCol = a.column();
    LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1);
    titleLp.leftMargin = a.dp(12);
    titleCol.addView(a.text("复习背诵计划", 18, true));
    TextView subTv = a.text("基于艾宾浩斯记忆曲线，按每日配额科学背诵", 12, false);
    subTv.setTextColor(a.muted);
    titleCol.addView(subTv);
    heading.addView(titleCol, titleLp);
    header.addView(heading);

    // If no active plans exist, render empty state guide
    if (activePlans.length() == 0) {
      LinearLayout emptyCard = a.card(null);
      emptyCard.setGravity(Gravity.CENTER);
      emptyCard.setPadding(a.dp(20), a.dp(32), a.dp(20), a.dp(32));
      LinearLayout.LayoutParams ecLp = new LinearLayout.LayoutParams(-1, -2);
      ecLp.topMargin = a.dp(14);
      emptyCard.setLayoutParams(ecLp);

      emptyCard.addView(a.icon("bookmarks-outline", 52, a.subtle));

      TextView emptyTitle = a.text("暂无进行中的复习计划", 17, true);
      emptyTitle.setTextColor(a.foreground);
      LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(-2, -2);
      etLp.topMargin = a.dp(14);
      emptyCard.addView(emptyTitle, etLp);

      TextView emptyDesc = a.text("创建专属复习计划，绑定指定生词笔记本，设定每日新词配额与学习频次，让背单词更有节奏！", 13, false);
      emptyDesc.setGravity(Gravity.CENTER);
      emptyDesc.setTextColor(a.muted);
      LinearLayout.LayoutParams edLp = new LinearLayout.LayoutParams(-1, -2);
      edLp.topMargin = a.dp(8);
      emptyCard.addView(emptyDesc, edLp);

      Button createFirstBtn = a.primaryButton("新建第一个复习计划", v -> showCreatePlanModal());
      LinearLayout.LayoutParams cfLp = new LinearLayout.LayoutParams(a.dp(220), a.dp(46));
      cfLp.topMargin = a.dp(22);
      cfLp.gravity = Gravity.CENTER_HORIZONTAL;
      emptyCard.addView(createFirstBtn, cfLp);
      return;
    }

    // Ensure valid activePlanId
    boolean foundPlan = false;
    if (activePlanId != null) {
      for (int i = 0; i < activePlans.length(); i++) {
        JSONObject ap = activePlans.optJSONObject(i);
        if (ap != null && activePlanId.equals(ap.optString("id"))) {
          foundPlan = true;
          break;
        }
      }
    }
    if (!foundPlan) {
      activePlanId = activePlans.optJSONObject(0).optString("id");
    }

    JSONObject initialPlan = a.library.reviewPlans().get(activePlanId);
    if (initialPlan == null) {
      activePlanId = activePlans.optJSONObject(0).optString("id");
      initialPlan = a.library.reviewPlans().get(activePlanId);
    }
    final JSONObject plan = initialPlan;

    // 2. Plan Switcher Bar
    LinearLayout switcherRow = a.row();
    switcherRow.setPadding(0, a.dp(10), 0, a.dp(4));

    for (int i = 0; i < activePlans.length(); i++) {
      JSONObject p = activePlans.optJSONObject(i);
      if (p == null) continue;
      String pid = p.optString("id");
      String pname = p.optString("name", "计划");
      boolean isSelected = pid.equals(activePlanId);
      switcherRow.addView(scopePill(pname, isSelected, () -> {
        activePlanId = pid;
        a.render();
      }));
    }

    // Add "+ 新建计划" button chip
    Button addPlanBtn = a.chip("+ 新建计划", false, v -> showCreatePlanModal());
    addPlanBtn.setLayoutParams(new LinearLayout.LayoutParams(-2, a.dp(38)));
    addPlanBtn.setPadding(a.dp(12), 0, a.dp(12), 0);
    switcherRow.addView(addPlanBtn);

    HorizontalScrollView switcherScroll = new HorizontalScrollView(a);
    switcherScroll.setHorizontalScrollBarEnabled(false);
    switcherScroll.addView(switcherRow);
    header.addView(switcherScroll);

    // 3. Active Plan Details & Settings Box
    LinearLayout planDetailsBox = a.card(null);
    planDetailsBox.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(16));
    LinearLayout.LayoutParams pdbLp = new LinearLayout.LayoutParams(-1, -2);
    pdbLp.topMargin = a.dp(12);
    planDetailsBox.setLayoutParams(pdbLp);

    // Plan Title & Actions Row
    LinearLayout pTitleRow = a.row();
    pTitleRow.setGravity(Gravity.CENTER_VERTICAL);
    TextView planNameTv = a.text(plan.optString("name"), 17, true);
    planNameTv.setTextColor(a.foreground);
    pTitleRow.addView(planNameTv);

    View ptSpacer = new View(a);
    pTitleRow.addView(ptSpacer, new LinearLayout.LayoutParams(0, 1, 1));

    Button editPlanBtn = a.chip("编辑", false, v -> showEditPlanModal(plan));
    editPlanBtn.setTextSize(11 * a.scale);
    pTitleRow.addView(editPlanBtn);

    View gapChip = new View(a);
    pTitleRow.addView(gapChip, new LinearLayout.LayoutParams(a.dp(6), 1));

    Button archivePlanBtn = a.chip("归档", false, v -> confirmArchivePlan(activePlanId));
    archivePlanBtn.setTextSize(11 * a.scale);
    pTitleRow.addView(archivePlanBtn);
    planDetailsBox.addView(pTitleRow);

    // Bound Notebook Row
    JSONObject nb = a.library.find("notebooks", plan.optString("notebook_id"));
    String nbName = nb != null ? nb.optString("name", "笔记本") : "已绑定笔记";
    LinearLayout nbRow = a.row();
    nbRow.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams nbLp = new LinearLayout.LayoutParams(-1, -2);
    nbLp.topMargin = a.dp(8);
    nbRow.setLayoutParams(nbLp);
    nbRow.addView(a.icon("folder-open-outline", 14, a.muted));
    TextView nbTv = a.text(" 所属笔记本：" + nbName, 12, false);
    nbTv.setTextColor(a.muted);
    nbRow.addView(nbTv);
    planDetailsBox.addView(nbRow);

    // Badges Row (Frequency & Quota)
    LinearLayout badgesRow = a.row();
    badgesRow.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams bgLp = new LinearLayout.LayoutParams(-1, -2);
    bgLp.topMargin = a.dp(10);
    badgesRow.setLayoutParams(bgLp);

    String freqLabel = formatWeekdaysBadge(plan.optJSONArray("weekdays"));
    TextView freqBadge = a.text(freqLabel, 11, true);
    freqBadge.setTextColor(a.accent);
    freqBadge.setPadding(a.dp(8), a.dp(3), a.dp(8), a.dp(3));
    freqBadge.setBackground(a.shape((a.accent & 0x00FFFFFF) | 0x1A000000, 10, 0));
    badgesRow.addView(freqBadge);

    View bgGap = new View(a);
    badgesRow.addView(bgGap, new LinearLayout.LayoutParams(a.dp(6), 1));

    String quotaLabel = "每日新词 " + plan.optInt("daily_new_limit") + " · 复习 " + plan.optInt("daily_review_limit");
    TextView quotaBadge = a.text(quotaLabel, 11, false);
    quotaBadge.setTextColor(a.muted);
    quotaBadge.setPadding(a.dp(8), a.dp(3), a.dp(8), a.dp(3));
    quotaBadge.setBackground(a.shape(a.inputBg, 10, a.border));
    badgesRow.addView(quotaBadge);

    planDetailsBox.addView(badgesRow);

    // 4. Today's Task Card
    long now = System.currentTimeMillis();
    JSONObject day = a.library.reviewPlans().dailyQueue(activePlanId, now);
    a.persist(); // DailyQueue generates today's fixed task list if absent

    LinearLayout todayCard = a.card(null);
    todayCard.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(18));
    LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(-1, -2);
    tcLp.topMargin = a.dp(12);
    todayCard.setLayoutParams(tcLp);

    boolean scheduled = day.optBoolean("scheduled", false);
    int newTarget = day.optInt("new_target", 0);
    int reviewTarget = day.optInt("review_target", 0);
    int totalTarget = newTarget + reviewTarget;
    JSONArray queue = day.optJSONArray("queue");
    int remaining = queue != null ? queue.length() : 0;
    int completed = Math.max(0, totalTarget - remaining);

    if (!scheduled) {
      // Non-scheduled rest day
      LinearLayout restRow = a.row();
      restRow.setGravity(Gravity.CENTER_VERTICAL);
      restRow.addView(a.icon("cafe-outline", 36, a.accent));
      LinearLayout restCol = a.column();
      LinearLayout.LayoutParams rcLp = new LinearLayout.LayoutParams(0, -2, 1);
      rcLp.leftMargin = a.dp(12);
      restCol.addView(a.text("今日非计划学习日", 16, true));
      TextView restSub = a.text("根据计划频次设置，今天没有安排打卡任务。好好休息放松，让记忆沉淀！", 12, false);
      restSub.setTextColor(a.muted);
      restCol.addView(restSub);
      restRow.addView(restCol, rcLp);
      todayCard.addView(restRow);

      Button changeFreqBtn = a.button("调整计划学习频次", v -> showEditPlanModal(plan));
      changeFreqBtn.setTextColor(a.accent);
      LinearLayout.LayoutParams cfLp2 = new LinearLayout.LayoutParams(-1, a.dp(40));
      cfLp2.topMargin = a.dp(14);
      changeFreqBtn.setLayoutParams(cfLp2);
      todayCard.addView(changeFreqBtn);
    } else if (totalTarget == 0) {
      // No words available
      LinearLayout emptyWords = a.column();
      emptyWords.setGravity(Gravity.CENTER);
      emptyWords.setPadding(0, a.dp(12), 0, a.dp(12));
      emptyWords.addView(a.icon("file-tray-outline", 40, a.subtle));
      TextView ewTitle = a.text("当前笔记本暂无可用词汇", 15, true);
      LinearLayout.LayoutParams ewLp = new LinearLayout.LayoutParams(-2, -2);
      ewLp.topMargin = a.dp(8);
      emptyWords.addView(ewTitle, ewLp);
      TextView ewSub = a.text("可在笔记本中添加更多生词，或编辑计划绑定其他笔记本。", 12, false);
      ewSub.setTextColor(a.muted);
      emptyWords.addView(ewSub);
      todayCard.addView(emptyWords);
    } else if (remaining == 0) {
      // All tasks completed for today
      LinearLayout doneCol = a.column();
      doneCol.setGravity(Gravity.CENTER);
      doneCol.setPadding(0, a.dp(10), 0, a.dp(10));
      doneCol.addView(a.icon("checkmark-circle-outline", 46, Color.parseColor("#10B981")));

      TextView doneTitle = a.text("今日复习任务已全部搞定！", 17, true);
      doneTitle.setTextColor(a.foreground);
      LinearLayout.LayoutParams dtLp = new LinearLayout.LayoutParams(-2, -2);
      dtLp.topMargin = a.dp(10);
      doneCol.addView(doneTitle, dtLp);

      TextView doneSub = a.text("今天已完成 " + newTarget + " 个新词学习，巩固 " + reviewTarget + " 个到期复习词。明天继续保持！", 12, false);
      doneSub.setTextColor(a.muted);
      doneSub.setGravity(Gravity.CENTER);
      LinearLayout.LayoutParams dsLp = new LinearLayout.LayoutParams(-1, -2);
      dsLp.topMargin = a.dp(4);
      doneCol.addView(doneSub, dsLp);

      todayCard.addView(doneCol);
    } else {
      // Active tasks pending
      TextView todayTitle = a.text("今日学习任务", 16, true);
      todayCard.addView(todayTitle);

      // Metrics Row
      LinearLayout tmRow = a.row();
      tmRow.setPadding(a.dp(6), a.dp(8), a.dp(6), a.dp(8));
      GradientDrawable tmBg = new GradientDrawable();
      tmBg.setColor(a.inputBg);
      tmBg.setCornerRadius(a.dp(10));
      tmBg.setStroke(a.dp(1), a.border);
      tmRow.setBackground(tmBg);
      LinearLayout.LayoutParams tmLp = new LinearLayout.LayoutParams(-1, -2);
      tmLp.topMargin = a.dp(10);
      tmRow.setLayoutParams(tmLp);

      addMetric(tmRow, remaining, "待学习", a.accent);
      addMetricDivider(tmRow);
      addMetric(tmRow, newTarget, "新词配额", a.foreground);
      addMetricDivider(tmRow);
      addMetric(tmRow, reviewTarget, "复习配额", a.foreground);
      todayCard.addView(tmRow);

      // Progress bar
      int pct = (int) Math.round(((double) completed / Math.max(1, totalTarget)) * 100);
      LinearLayout pbRow = a.row();
      pbRow.setGravity(Gravity.CENTER_VERTICAL);
      LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(-1, -2);
      pbLp.topMargin = a.dp(12);
      pbRow.setLayoutParams(pbLp);

      ProgressBar pb = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
      pb.setMax(totalTarget);
      pb.setProgress(completed);
      pbRow.addView(pb, new LinearLayout.LayoutParams(0, a.dp(6), 1));

      TextView pctTv = a.text("  " + completed + " / " + totalTarget + " (" + pct + "%)", 11, true);
      pctTv.setTextColor(a.accent);
      pbRow.addView(pctTv);
      todayCard.addView(pbRow);

      // Mode Selector
      TextView modeTitle = a.text("复习模式", 13, true);
      LinearLayout.LayoutParams mtLp = new LinearLayout.LayoutParams(-1, -2);
      mtLp.topMargin = a.dp(14);
      todayCard.addView(modeTitle, mtLp);

      LinearLayout modeRow = a.row();
      LinearLayout.LayoutParams mrLp = new LinearLayout.LayoutParams(-1, a.dp(72));
      mrLp.topMargin = a.dp(6);
      todayCard.addView(modeRow, mrLp);

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
      View mGap = new View(a);
      modeRow.addView(mGap, new LinearLayout.LayoutParams(a.dp(8), 1));
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

      // Start Button
      Button startBtn = a.primaryButton("开启今日复习（剩余 " + remaining + " 词）", v -> startDailyReview());
      LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(-1, a.dp(50));
      sbLp.topMargin = a.dp(16);
      startBtn.setLayoutParams(sbLp);
      startBtn.setTextSize(16 * a.scale);
      todayCard.addView(startBtn);
    }

    // 5. Past 7 Days Statistics Card
    try {
      LocalDate todayDate = LocalDate.now();
      LocalDate fromDate = todayDate.minusDays(6);
      JSONObject stats = a.library.reviewPlans().statistics(activePlanId, fromDate.toString(), todayDate.toString());
      JSONObject totals = stats.optJSONObject("totals");

      LinearLayout statsCard = a.card(null);
      statsCard.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(16));
      LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(-1, -2);
      scLp.topMargin = a.dp(12);
      statsCard.setLayoutParams(scLp);

      TextView sTitle = a.text("近 7 天学习动态", 15, true);
      statsCard.addView(sTitle);

      LinearLayout sRow = a.row();
      sRow.setPadding(a.dp(6), a.dp(8), a.dp(6), a.dp(8));
      GradientDrawable sBg = new GradientDrawable();
      sBg.setColor(a.inputBg);
      sBg.setCornerRadius(a.dp(10));
      sBg.setStroke(a.dp(1), a.border);
      sRow.setBackground(sBg);
      LinearLayout.LayoutParams sRowLp = new LinearLayout.LayoutParams(-1, -2);
      sRowLp.topMargin = a.dp(10);
      sRow.setLayoutParams(sRowLp);

      addMetric(sRow, stats.optInt("active_days", 0), "打卡天数", a.accent);
      addMetricDivider(sRow);
      addMetric(sRow, totals != null ? totals.optInt("new_completed", 0) : 0, "新学词汇", Color.parseColor("#10B981"));
      addMetricDivider(sRow);
      addMetric(sRow, totals != null ? totals.optInt("review_completed", 0) : 0, "复习通过", Color.parseColor("#3B82F6"));
      addMetricDivider(sRow);
      addMetric(sRow, totals != null ? totals.optInt("attempts", 0) : 0, "累计答题", a.foreground);
      statsCard.addView(sRow);
    } catch (Exception ignored) {}
  }

  void startDailyReview() {
    long now = System.currentTimeMillis();
    JSONObject day = a.library.reviewPlans().dailyQueue(activePlanId, now);
    a.persist();
    JSONArray queue = day.optJSONArray("queue");
    if (queue == null || queue.length() == 0) {
      a.say("今日复习任务已完成！");
      return;
    }
    int total = day.optInt("new_target", 0) + day.optInt("review_target", 0);
    sessionSummary.reset(total);
    revealed = false;
    cardStartTime = System.currentTimeMillis();
    phase = "active";
    a.render();
  }

  void renderActiveSession() {
    a.body.removeAllViews();
    a.body.setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(24));

    long now = System.currentTimeMillis();
    JSONObject day = a.library.reviewPlans().dailyQueue(activePlanId, now);
    JSONArray queue = day.optJSONArray("queue");
    if (queue == null || queue.length() == 0) {
      phase = "completed";
      renderCompletedSession();
      return;
    }

    JSONObject task = queue.optJSONObject(0);
    String vocabId = task.optString("vocab_id");
    String kind = task.optString("kind", "review");
    int attempts = task.optInt("attempts", 0);

    JSONObject word = a.library.find("vocabularies", vocabId);
    if (word == null) {
      a.say("词汇不存在或已删除");
      phase = "dashboard";
      renderDashboard();
      return;
    }

    // State is strictly read from review plans domain state
    JSONObject state = a.library.reviewPlans().state(activePlanId, vocabId);

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
    int totalTarget = day.optInt("new_target") + day.optInt("review_target");
    int completed = Math.max(0, totalTarget - queue.length());
    LinearLayout progressBox = a.column();
    LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(0, -2, 1);
    pbLp.leftMargin = a.dp(4);
    pbLp.rightMargin = a.dp(10);
    progressBox.setLayoutParams(pbLp);

    LinearLayout pLabels = a.row();
    TextView stepTv = a.text("今日第 " + (completed + 1) + " / " + totalTarget + " 词", 12, true);
    stepTv.setTextColor(a.foreground);
    pLabels.addView(stepTv);

    View pSpacer = new View(a);
    pLabels.addView(pSpacer, new LinearLayout.LayoutParams(0, 1, 1));

    int percent = (int) Math.round(((double) completed / Math.max(1, totalTarget)) * 100);
    TextView pctTv = a.text(percent + "%", 12, true);
    pctTv.setTextColor(a.accent);
    pLabels.addView(pctTv);
    progressBox.addView(pLabels);

    ProgressBar pb = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
    pb.setMax(Math.max(1, totalTarget));
    pb.setProgress(completed);
    LinearLayout.LayoutParams pbBarLp = new LinearLayout.LayoutParams(-1, a.dp(4));
    pbBarLp.topMargin = a.dp(4);
    progressBox.addView(pb, pbBarLp);
    topBar.addView(progressBox);

    // Kind Badge (新词 vs 复习)
    boolean isNew = "new".equals(kind);
    TextView kindBadge = a.text(isNew ? "新词" : "复习", 11, true);
    kindBadge.setTextColor(isNew ? Color.parseColor("#3B82F6") : a.accent);
    kindBadge.setPadding(a.dp(7), a.dp(2), a.dp(7), a.dp(2));
    kindBadge.setBackground(a.shape((isNew ? 0x1A3B82F6 : ((a.accent & 0x00FFFFFF) | 0x1A000000)), 10, 0));
    LinearLayout.LayoutParams kbLp = new LinearLayout.LayoutParams(-2, -2);
    kbLp.rightMargin = a.dp(6);
    topBar.addView(kindBadge, kbLp);

    // Face indicator
    TextView faceBadge = a.text(revealed ? "背面" : "正面", 11, true);
    faceBadge.setTextColor(revealed ? a.accent : a.muted);
    faceBadge.setPadding(a.dp(8), a.dp(2), a.dp(8), a.dp(2));
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
        // 看词猜义
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
        // 看义猜词
        TextView promptTv = a.text("请回忆该释义对应的英文：", 13, true);
        promptTv.setTextColor(a.accent);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(-1, -2);
        pLp.topMargin = a.dp(16);
        card.addView(promptTv, pLp);

        if (firstMeaning != null) {
          String zh = firstMeaning.optString("zh_definition");
          if (!zh.isEmpty()) {
            TextView zhTv = a.text(zh, 22, true);
            zhTv.setTextColor(a.foreground);
            LinearLayout.LayoutParams zhLp = new LinearLayout.LayoutParams(-1, -2);
            zhLp.topMargin = a.dp(12);
            card.addView(zhTv, zhLp);
          }
        }
      }

      View bSpacer = new View(a);
      card.addView(bSpacer, new LinearLayout.LayoutParams(1, 0, 1));

      TextView bottomHint = a.text("点击卡片任意区域查看解析", 12, false);
      bottomHint.setTextColor(a.muted);
      bottomHint.setGravity(Gravity.CENTER);
      card.addView(bottomHint);
    } else {
      // ===== BACK SIDE =====
      LinearLayout cardHdr = a.row();
      cardHdr.setGravity(Gravity.CENTER_VERTICAL);
      TextView wordTv = a.text(wordContent, 26, true);
      wordTv.setTextColor(a.foreground);
      cardHdr.addView(wordTv);

      if (!phonetic.isEmpty()) {
        String pStr = phonetic.startsWith("/") ? phonetic : "/" + phonetic + "/";
        TextView phTv = a.text("  " + pStr, 14, false);
        phTv.setTextColor(a.muted);
        cardHdr.addView(phTv);
      }

      View hSpacer = new View(a);
      cardHdr.addView(hSpacer, new LinearLayout.LayoutParams(0, 1, 1));

      TextView typeTv = a.text(typeLabel, 11, true);
      typeTv.setTextColor(a.accent);
      typeTv.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
      typeTv.setBackground(a.shape((a.accent & 0x00FFFFFF) | 0x1A000000, 10, 0));
      cardHdr.addView(typeTv);
      card.addView(cardHdr);

      View div = new View(a);
      div.setBackgroundColor(a.border);
      LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(-1, a.dp(1));
      divLp.topMargin = a.dp(10);
      divLp.bottomMargin = a.dp(10);
      card.addView(div, divLp);

      // Meanings list
      if (meanings != null) {
        for (int i = 0; i < meanings.length(); i++) {
          JSONObject m = meanings.optJSONObject(i);
          if (m == null) continue;

          LinearLayout mBox = a.column();
          mBox.setPadding(0, a.dp(4), 0, a.dp(6));

          LinearLayout mHeader = a.row();
          mHeader.setGravity(Gravity.CENTER_VERTICAL);

          String pos = m.optString("part_of_speech").trim();
          if (!pos.isEmpty()) {
            TextView posTv = a.text(pos, 11, true);
            posTv.setTextColor(a.accent);
            posTv.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
            posTv.setBackground(a.shape(a.inputBg, 6, a.border));
            mHeader.addView(posTv);
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
          card.addView(mBox);
        }
      }

      // Tags
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

      // Dynamic intervals based on review plan state
      String againSub = ReviewLogic.previewInterval(state, "再次", now);
      String hardSub = ReviewLogic.previewInterval(state, "困难", now);
      String easySub = ReviewLogic.previewInterval(state, "简单", now);

      // 再次 (Red)
      rateRow.addView(createRatingButton("再次", againSub, Color.parseColor("#EF4444"), false, () -> {
        submitRating(vocabId, "再次");
      }), new LinearLayout.LayoutParams(0, -1, 1));

      View gap1 = new View(a);
      rateRow.addView(gap1, new LinearLayout.LayoutParams(a.dp(8), 1));

      // 困难 (Amber)
      rateRow.addView(createRatingButton("困难", hardSub, Color.parseColor("#F59E0B"), false, () -> {
        submitRating(vocabId, "困难");
      }), new LinearLayout.LayoutParams(0, -1, 1));

      View gap2 = new View(a);
      rateRow.addView(gap2, new LinearLayout.LayoutParams(a.dp(8), 1));

      // 简单 (Green)
      rateRow.addView(createRatingButton("简单", easySub, Color.parseColor("#10B981"), true, () -> {
        submitRating(vocabId, "简单");
      }), new LinearLayout.LayoutParams(0, -1, 1));

      footer.addView(rateRow);
    }
  }

  void submitRating(String vocabId, String grade) {
    long occurredAt = System.currentTimeMillis();
    long duration = Math.min(86400000L, Math.max(0, occurredAt - cardStartTime));
    String eventId = Library.id();

    revealed = false;
    cardStartTime = System.currentTimeMillis();
    if ("再次".equals(grade)) {
      sessionSummary.againCount++;
    } else if ("困难".equals(grade)) {
      sessionSummary.hardCount++;
    } else {
      sessionSummary.easyCount++;
    }

    boolean saved = a.change(() -> {
      a.library.reviewPlans().record(activePlanId, vocabId, grade, eventId, duration, occurredAt);
      JSONObject nextDay = a.library.reviewPlans().dailyQueue(activePlanId, occurredAt);
      JSONArray nextQueue = nextDay.optJSONArray("queue");
      if (nextQueue == null || nextQueue.length() == 0) {
        phase = "completed";
      }
    });

    if (!saved) {
      a.say("提交评分失败，请重试");
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
        .setTitle("退出今日复习")
        .setMessage("确定要提前结束当前答题吗？已提交评分的词汇已安全保存。")
        .setNegativeButton("继续背诵", null)
        .setPositiveButton("退出", (dialog, which) -> {
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

    // Celebration checkmark circle
    LinearLayout iconCircle = a.column();
    iconCircle.setGravity(Gravity.CENTER);
    GradientDrawable circle = new GradientDrawable();
    circle.setShape(GradientDrawable.OVAL);
    circle.setColor((a.accent & 0x00FFFFFF) | 0x1A000000);
    iconCircle.setBackground(circle);
    iconCircle.addView(a.icon("checkmark-circle-outline", 36, Color.parseColor("#10B981")));
    card.addView(iconCircle, new LinearLayout.LayoutParams(a.dp(72), a.dp(72)));

    TextView compTitle = a.text("太棒了！今日任务全部完成", 20, true);
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

    int totalCompleted = sessionSummary.easyCount + sessionSummary.hardCount;
    addMetric(statsBox, totalCompleted, "今日掌握", a.foreground);
    addMetricDivider(statsBox);
    addMetric(statsBox, sessionSummary.easyCount, "轻松通过", Color.parseColor("#10B981"));
    addMetricDivider(statsBox);
    addMetric(statsBox, sessionSummary.hardCount + sessionSummary.againCount, "需再强化", Color.parseColor("#F59E0B"));
    card.addView(statsBox);

    // Back to dashboard button
    Button backHomeBtn = a.primaryButton("返回复习中心", v -> {
      phase = "dashboard";
      a.render();
    });
    LinearLayout.LayoutParams bhLp = new LinearLayout.LayoutParams(-1, a.dp(48));
    bhLp.topMargin = a.dp(24);
    backHomeBtn.setLayoutParams(bhLp);
    backHomeBtn.setTextSize(15 * a.scale);
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

  String formatWeekdaysBadge(JSONArray weekdays) {
    if (weekdays == null || weekdays.length() == 0) return "每天";
    Set<Integer> set = new HashSet<>();
    for (int i = 0; i < weekdays.length(); i++) set.add(weekdays.optInt(i));
    if (set.size() == 7) return "📅 每天";
    if (set.size() == 5 && set.contains(1) && set.contains(2) && set.contains(3) && set.contains(4) && set.contains(5)) {
      return "💼 仅工作日 (周一至五)";
    }
    if (set.size() == 2 && set.contains(6) && set.contains(7)) {
      return "🏖 仅周末 (周六日)";
    }
    StringBuilder sb = new StringBuilder("📅 周");
    String[] ch = {"一", "二", "三", "四", "五", "六", "日"};
    List<Integer> list = new ArrayList<>(set);
    Collections.sort(list);
    for (int i = 0; i < list.size(); i++) {
      int d = list.get(i);
      if (d >= 1 && d <= 7) sb.append(ch[d - 1]);
    }
    return sb.toString();
  }

  void showCreatePlanModal() {
    JSONArray allNb = a.library.array("notebooks");
    List<String> nbNames = new ArrayList<>();
    List<String> nbIds = new ArrayList<>();
    for (int i = 0; i < allNb.length(); i++) {
      JSONObject n = allNb.optJSONObject(i);
      if (n == null || "nb_global_root".equals(n.optString("id"))) continue;
      nbNames.add(n.optString("name", "笔记"));
      nbIds.add(n.optString("id"));
    }

    if (nbIds.isEmpty()) {
      a.say("请先在笔记模块创建至少一个笔记本");
      return;
    }

    LinearLayout layout = a.column();
    layout.setPadding(a.dp(20), a.dp(16), a.dp(20), a.dp(16));

    // Name
    layout.addView(a.text("计划名称", 13, true));
    EditText nameEt = a.input("例如：日常高频词突破", nbNames.get(0) + "复习计划", false);
    LinearLayout.LayoutParams neLp = new LinearLayout.LayoutParams(-1, a.dp(44));
    neLp.topMargin = a.dp(4);
    neLp.bottomMargin = a.dp(12);
    layout.addView(nameEt, neLp);

    // Notebook Spinner
    layout.addView(a.text("选择绑定笔记本", 13, true));
    Spinner nbSpinner = new Spinner(a);
    ArrayAdapter<String> nbAdapter = new ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, nbNames);
    nbSpinner.setAdapter(nbAdapter);
    LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(-1, a.dp(44));
    spLp.topMargin = a.dp(4);
    spLp.bottomMargin = a.dp(12);
    layout.addView(nbSpinner, spLp);

    // Quotas row
    LinearLayout qRow = a.row();
    LinearLayout q1 = a.column();
    q1.addView(a.text("每日新词配额", 13, true));
    EditText newLimitEt = a.input("15", "15", false);
    newLimitEt.setInputType(InputType.TYPE_CLASS_NUMBER);
    q1.addView(newLimitEt, new LinearLayout.LayoutParams(-1, a.dp(44)));
    qRow.addView(q1, new LinearLayout.LayoutParams(0, -2, 1));

    View qGap = new View(a);
    qRow.addView(qGap, new LinearLayout.LayoutParams(a.dp(12), 1));

    LinearLayout q2 = a.column();
    q2.addView(a.text("每日复习上限", 13, true));
    EditText revLimitEt = a.input("40", "40", false);
    revLimitEt.setInputType(InputType.TYPE_CLASS_NUMBER);
    q2.addView(revLimitEt, new LinearLayout.LayoutParams(-1, a.dp(44)));
    qRow.addView(q2, new LinearLayout.LayoutParams(0, -2, 1));

    LinearLayout.LayoutParams qRowLp = new LinearLayout.LayoutParams(-1, -2);
    qRowLp.bottomMargin = a.dp(12);
    layout.addView(qRow, qRowLp);

    // Frequency Selector
    layout.addView(a.text("学习频次", 13, true));
    Spinner freqSpinner = new Spinner(a);
    String[] freqOptions = {"每天（周一至周日）", "仅工作日（周一至周五）", "仅周末（周六、周日）"};
    ArrayAdapter<String> freqAdapter = new ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, freqOptions);
    freqSpinner.setAdapter(freqAdapter);
    LinearLayout.LayoutParams fsLp = new LinearLayout.LayoutParams(-1, a.dp(44));
    fsLp.topMargin = a.dp(4);
    fsLp.bottomMargin = a.dp(12);
    layout.addView(freqSpinner, fsLp);

    // Legacy import checkbox
    CheckBox importCb = new CheckBox(a);
    importCb.setText("导入该笔记本中已有的复习进度");
    importCb.setChecked(true);
    importCb.setTextSize(12 * a.scale);
    layout.addView(importCb);

    ScrollView sv = new ScrollView(a);
    sv.addView(layout);

    new AlertDialog.Builder(a)
        .setTitle("新建复习计划")
        .setView(sv)
        .setNegativeButton("取消", null)
        .setPositiveButton("立即创建", (dialog, which) -> {
          String name = nameEt.getText().toString().trim();
          if (name.isEmpty()) name = "复习计划";
          int nbIdx = nbSpinner.getSelectedItemPosition();
          String nbId = (nbIdx >= 0 && nbIdx < nbIds.size()) ? nbIds.get(nbIdx) : nbIds.get(0);

          int newLimit = 15;
          try { newLimit = Integer.parseInt(newLimitEt.getText().toString().trim()); } catch (Exception ignored) {}
          int revLimit = 40;
          try { revLimit = Integer.parseInt(revLimitEt.getText().toString().trim()); } catch (Exception ignored) {}

          int[] weekdays = new int[] {1, 2, 3, 4, 5, 6, 7};
          if (freqSpinner.getSelectedItemPosition() == 1) {
            weekdays = new int[] {1, 2, 3, 4, 5};
          } else if (freqSpinner.getSelectedItemPosition() == 2) {
            weekdays = new int[] {6, 7};
          }

          boolean importLegacy = importCb.isChecked();
          final String finalName = name;
          final int finalNew = newLimit;
          final int finalRev = revLimit;
          final int[] finalWeekdays = weekdays;

          boolean saved = a.change(() -> {
            activePlanId = a.library.reviewPlans().create(
                finalName, nbId, finalNew, finalRev, finalWeekdays,
                ZoneId.systemDefault().getId(), importLegacy, System.currentTimeMillis());
          });
          if (saved) {
            a.say("复习计划创建成功！");
          }
        })
        .show();
  }

  void showEditPlanModal(JSONObject plan) {
    if (plan == null) return;
    String planId = plan.optString("id");

    LinearLayout layout = a.column();
    layout.setPadding(a.dp(20), a.dp(16), a.dp(20), a.dp(16));

    // Name
    layout.addView(a.text("计划名称", 13, true));
    EditText nameEt = a.input("计划名称", plan.optString("name"), false);
    LinearLayout.LayoutParams neLp = new LinearLayout.LayoutParams(-1, a.dp(44));
    neLp.topMargin = a.dp(4);
    neLp.bottomMargin = a.dp(12);
    layout.addView(nameEt, neLp);

    // Quotas row
    LinearLayout qRow = a.row();
    LinearLayout q1 = a.column();
    q1.addView(a.text("每日新词配额", 13, true));
    EditText newLimitEt = a.input("15", String.valueOf(plan.optInt("daily_new_limit", 15)), false);
    newLimitEt.setInputType(InputType.TYPE_CLASS_NUMBER);
    q1.addView(newLimitEt, new LinearLayout.LayoutParams(-1, a.dp(44)));
    qRow.addView(q1, new LinearLayout.LayoutParams(0, -2, 1));

    View qGap = new View(a);
    qRow.addView(qGap, new LinearLayout.LayoutParams(a.dp(12), 1));

    LinearLayout q2 = a.column();
    q2.addView(a.text("每日复习上限", 13, true));
    EditText revLimitEt = a.input("40", String.valueOf(plan.optInt("daily_review_limit", 40)), false);
    revLimitEt.setInputType(InputType.TYPE_CLASS_NUMBER);
    q2.addView(revLimitEt, new LinearLayout.LayoutParams(-1, a.dp(44)));
    qRow.addView(q2, new LinearLayout.LayoutParams(0, -2, 1));

    LinearLayout.LayoutParams qRowLp = new LinearLayout.LayoutParams(-1, -2);
    qRowLp.bottomMargin = a.dp(12);
    layout.addView(qRow, qRowLp);

    // Frequency Selector
    layout.addView(a.text("学习频次", 13, true));
    Spinner freqSpinner = new Spinner(a);
    String[] freqOptions = {"每天（周一至周日）", "仅工作日（周一至周五）", "仅周末（周六、周日）"};
    ArrayAdapter<String> freqAdapter = new ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, freqOptions);
    freqSpinner.setAdapter(freqAdapter);

    JSONArray curWd = plan.optJSONArray("weekdays");
    if (curWd != null) {
      if (curWd.length() == 5) freqSpinner.setSelection(1);
      else if (curWd.length() == 2) freqSpinner.setSelection(2);
      else freqSpinner.setSelection(0);
    }
    LinearLayout.LayoutParams fsLp = new LinearLayout.LayoutParams(-1, a.dp(44));
    fsLp.topMargin = a.dp(4);
    fsLp.bottomMargin = a.dp(12);
    layout.addView(freqSpinner, fsLp);

    // Notice: Notebook and Timezone are immutable as per domain rules
    TextView noteTv = a.text("注：绑定的笔记本与时区在计划创建后固定，如需调整可新建计划。", 11, false);
    noteTv.setTextColor(a.muted);
    layout.addView(noteTv);

    ScrollView sv = new ScrollView(a);
    sv.addView(layout);

    new AlertDialog.Builder(a)
        .setTitle("编辑复习计划")
        .setView(sv)
        .setNegativeButton("取消", null)
        .setPositiveButton("保存修改", (dialog, which) -> {
          String name = nameEt.getText().toString().trim();
          if (name.isEmpty()) name = plan.optString("name");

          int newLimit = plan.optInt("daily_new_limit", 15);
          try { newLimit = Integer.parseInt(newLimitEt.getText().toString().trim()); } catch (Exception ignored) {}
          int revLimit = plan.optInt("daily_review_limit", 40);
          try { revLimit = Integer.parseInt(revLimitEt.getText().toString().trim()); } catch (Exception ignored) {}

          int[] weekdays = new int[] {1, 2, 3, 4, 5, 6, 7};
          if (freqSpinner.getSelectedItemPosition() == 1) {
            weekdays = new int[] {1, 2, 3, 4, 5};
          } else if (freqSpinner.getSelectedItemPosition() == 2) {
            weekdays = new int[] {6, 7};
          }

          final String finalName = name;
          final int finalNew = newLimit;
          final int finalRev = revLimit;
          final int[] finalWeekdays = weekdays;

          boolean saved = a.change(() -> {
            JSONObject patch = new JSONObject();
            Library.put(patch, "name", finalName);
            Library.put(patch, "daily_new_limit", finalNew);
            Library.put(patch, "daily_review_limit", finalRev);
            JSONArray wdArr = new JSONArray();
            for (int w : finalWeekdays) wdArr.put(w);
            Library.put(patch, "weekdays", wdArr);
            a.library.reviewPlans().update(planId, patch, System.currentTimeMillis());
          });
          if (saved) {
            a.say("计划修改已保存！");
          }
        })
        .show();
  }

  void confirmArchivePlan(String planId) {
    new AlertDialog.Builder(a)
        .setTitle("归档复习计划")
        .setMessage("确定要归档此复习计划吗？归档后将不再生成每日复习任务，历史答题与打卡统计将完整保留。")
        .setNegativeButton("取消", null)
        .setPositiveButton("确认归档", (dialog, which) -> {
          boolean saved = a.change(() -> {
            a.library.reviewPlans().archive(planId, System.currentTimeMillis());
            activePlanId = null;
          });
          if (saved) {
            a.say("计划已成功归档");
          }
        })
        .show();
  }
}
