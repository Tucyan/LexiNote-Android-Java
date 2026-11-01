package com.lexinote.nativeapp;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
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
    } else if ("manage".equals(phase)) {
      renderManagePlans();
    } else {
      phase = "dashboard";
      renderDashboard();
    }
  }

  static class SwipableLayout extends LinearLayout {
    private final MainActivity a;
    private float startX, startY;
    private long startTime;
    private boolean isHorizontalDrag;
    private final Runnable onSwipeLeft;
    private final Runnable onSwipeRight;

    SwipableLayout(MainActivity a, Runnable onSwipeLeft, Runnable onSwipeRight) {
      super(a);
      this.a = a;
      this.onSwipeLeft = onSwipeLeft;
      this.onSwipeRight = onSwipeRight;
      setOrientation(VERTICAL);
      setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
      switch (ev.getAction()) {
        case MotionEvent.ACTION_DOWN:
          startX = ev.getX();
          startY = ev.getY();
          startTime = System.currentTimeMillis();
          isHorizontalDrag = false;
          break;
        case MotionEvent.ACTION_MOVE:
          float dx = ev.getX() - startX;
          float dy = ev.getY() - startY;
          if (Math.abs(dx) > a.dp(14) && Math.abs(dx) > Math.abs(dy) * 1.3f) {
            isHorizontalDrag = true;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            return true;
          }
          break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
          if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
          break;
      }
      return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      switch (event.getAction()) {
        case MotionEvent.ACTION_DOWN:
          startX = event.getX();
          startY = event.getY();
          startTime = System.currentTimeMillis();
          isHorizontalDrag = false;
          return true;
        case MotionEvent.ACTION_MOVE:
          float dx = event.getX() - startX;
          float dy = event.getY() - startY;
          if (Math.abs(dx) > a.dp(14) && Math.abs(dx) > Math.abs(dy) * 1.3f) {
            isHorizontalDrag = true;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
          }
          break;
        case MotionEvent.ACTION_UP:
          if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
          if (isHorizontalDrag) {
            float totalDx = event.getX() - startX;
            float totalDy = event.getY() - startY;
            if (Math.abs(totalDx) > a.dp(36) && Math.abs(totalDx) > Math.abs(totalDy)) {
              if (totalDx < 0 && onSwipeLeft != null) {
                onSwipeLeft.run();
                return true;
              } else if (totalDx > 0 && onSwipeRight != null) {
                onSwipeRight.run();
                return true;
              }
            }
          }
          break;
        case MotionEvent.ACTION_CANCEL:
          if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
          break;
      }
      return true;
    }
  }

  static final class PlanSortInfo {
    final JSONObject plan;
    final int priority; // 1 = pending tasks, 2 = no tasks scheduled / rest, 3 = all done
    final int remaining;
    final int totalTarget;
    final boolean scheduled;

    PlanSortInfo(JSONObject plan, int priority, int remaining, int totalTarget, boolean scheduled) {
      this.plan = plan;
      this.priority = priority;
      this.remaining = remaining;
      this.totalTarget = totalTarget;
      this.scheduled = scheduled;
    }
  }

  Set<String> getDisabledPlanIds() {
    Set<String> set = new HashSet<>();
    JSONObject settings = a.library.settings();
    JSONArray arr = settings.optJSONArray("disabled_review_plans");
    if (arr != null) {
      for (int i = 0; i < arr.length(); i++) {
        String id = arr.optString(i, null);
        if (id != null && !id.trim().isEmpty()) set.add(id);
      }
    }
    return set;
  }

  boolean isPlanActive(String planId) {
    if (planId == null) return false;
    return !getDisabledPlanIds().contains(planId);
  }

  void setPlanActive(String planId, boolean active) {
    if (planId == null) return;
    a.change(() -> {
      JSONObject settings = a.library.settings();
      JSONArray arr = settings.optJSONArray("disabled_review_plans");
      Set<String> set = new LinkedHashSet<>();
      if (arr != null) {
        for (int i = 0; i < arr.length(); i++) {
          String id = arr.optString(i, null);
          if (id != null && !id.trim().isEmpty()) set.add(id);
        }
      }
      if (active) {
        set.remove(planId);
      } else {
        set.add(planId);
      }
      JSONArray newArr = new JSONArray();
      for (String id : set) newArr.put(id);
      Library.put(settings, "disabled_review_plans", newArr);
    });
  }

  List<PlanSortInfo> getSortedActivePlans(long nowMillis) {
    JSONArray all = a.library.reviewPlans().list(false);
    Set<String> disabled = getDisabledPlanIds();
    List<PlanSortInfo> list = new ArrayList<>();
    boolean generatedDailyQueues = false;
    for (int i = 0; i < all.length(); i++) {
      JSONObject p = all.optJSONObject(i);
      if (p == null) continue;
      String pid = p.optString("id");
      if (disabled.contains(pid)) continue;

      generatedDailyQueues |= !a.library.reviewPlans().hasDailyQueue(pid, nowMillis);
      JSONObject day = a.library.reviewPlans().dailyQueue(pid, nowMillis);
      boolean scheduled = day.optBoolean("scheduled", false);
      int newTarget = day.optInt("new_target", 0);
      int reviewTarget = day.optInt("review_target", 0);
      int totalTarget = newTarget + reviewTarget;
      JSONArray q = day.optJSONArray("queue");
      int remaining = q != null ? q.length() : 0;

      int priority;
      if (scheduled && totalTarget > 0 && remaining > 0) {
        priority = 1;
      } else if (!scheduled || totalTarget == 0) {
        priority = 2;
      } else {
        priority = 3;
      }
      list.add(new PlanSortInfo(p, priority, remaining, totalTarget, scheduled));
    }

    if (generatedDailyQueues && !a.persist()) return Collections.emptyList();

    Collections.sort(list, (o1, o2) -> {
      if (o1.priority != o2.priority) {
        return Integer.compare(o1.priority, o2.priority);
      }
      if (o1.priority == 1) {
        return Integer.compare(o2.remaining, o1.remaining);
      }
      return o1.plan.optString("name", "").compareTo(o2.plan.optString("name", ""));
    });
    return list;
  }

  String renderDots(int currentIdx, int total) {
    if (total <= 1) return "";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < total; i++) {
      sb.append(i == currentIdx ? "● " : "○ ");
    }
    return sb.toString().trim();
  }

  LinearLayout createCardLayout(String title) {
    LinearLayout value = a.column();
    value.setPadding(a.dp(14), a.dp(14), a.dp(14), a.dp(14));
    value.setBackground(a.shape(a.card, 16, a.border));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = a.dp(14);
    value.setLayoutParams(lp);
    if (title != null) {
      TextView heading = a.text(title, 16, true);
      LinearLayout.LayoutParams h = new LinearLayout.LayoutParams(-1, -2);
      h.bottomMargin = a.dp(12);
      value.addView(heading, h);
    }
    return value;
  }

  void renderDashboard() {
    a.body.removeAllViews();
    a.body.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(32));

    long now = System.currentTimeMillis();
    JSONArray allPlans = a.library.reviewPlans().list(false);

    // If completely no plans exist, render empty state guide
    if (allPlans.length() == 0) {
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
      if (a.library.reviewPlans().list(true).length() > 0) {
        Button manageArchived = a.chip("计划管理", false, v -> {
          phase = "manage";
          a.render();
        });
        LinearLayout.LayoutParams manageLp = new LinearLayout.LayoutParams(-2, a.dp(36));
        manageLp.topMargin = a.dp(12);
        manageLp.gravity = Gravity.CENTER_HORIZONTAL;
        emptyCard.addView(manageArchived, manageLp);
      }
      return;
    }

    List<PlanSortInfo> sortedActivePlans = getSortedActivePlans(now);

    // If all plans are paused/inactive
    if (sortedActivePlans.isEmpty()) {
      LinearLayout pausedCard = a.card(null);
      pausedCard.setGravity(Gravity.CENTER);
      pausedCard.setPadding(a.dp(20), a.dp(32), a.dp(20), a.dp(32));
      LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(-1, -2);
      pcLp.topMargin = a.dp(14);
      pausedCard.setLayoutParams(pcLp);

      pausedCard.addView(a.icon("pause-circle-outline", 52, a.subtle));

      TextView pausedTitle = a.text("所有复习计划均已暂停", 17, true);
      pausedTitle.setTextColor(a.foreground);
      LinearLayout.LayoutParams ptLp = new LinearLayout.LayoutParams(-2, -2);
      ptLp.topMargin = a.dp(14);
      pausedCard.addView(pausedTitle, ptLp);

      TextView pausedDesc = a.text("你已停用所有复习计划。可前往「计划管理」重新激活计划，或新建一个计划。", 13, false);
      pausedDesc.setGravity(Gravity.CENTER);
      pausedDesc.setTextColor(a.muted);
      LinearLayout.LayoutParams pdLp = new LinearLayout.LayoutParams(-1, -2);
      pdLp.topMargin = a.dp(8);
      pausedCard.addView(pausedDesc, pdLp);

      LinearLayout btnRow = a.row();
      btnRow.setGravity(Gravity.CENTER);
      LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(-1, -2);
      brLp.topMargin = a.dp(20);
      btnRow.setLayoutParams(brLp);

      Button gotoManageBtn = a.primaryButton("⚙ 前往计划管理", v -> {
        phase = "manage";
        a.render();
      });
      btnRow.addView(gotoManageBtn, new LinearLayout.LayoutParams(a.dp(160), a.dp(44)));

      View bGap = new View(a);
      btnRow.addView(bGap, new LinearLayout.LayoutParams(a.dp(12), 1));

      Button newPlanBtn = a.chip("＋ 新建计划", true, v -> showCreatePlanModal());
      btnRow.addView(newPlanBtn, new LinearLayout.LayoutParams(-2, a.dp(44)));

      pausedCard.addView(btnRow);
      return;
    }

    // Determine current plan index in sortedActivePlans
    int currentPlanIndex = -1;
    if (activePlanId != null) {
      for (int i = 0; i < sortedActivePlans.size(); i++) {
        if (activePlanId.equals(sortedActivePlans.get(i).plan.optString("id"))) {
          currentPlanIndex = i;
          break;
        }
      }
    }
    if (currentPlanIndex == -1) {
      currentPlanIndex = 0;
      activePlanId = sortedActivePlans.get(0).plan.optString("id");
    }

    final int currentIdx = currentPlanIndex;
    final int totalActive = sortedActivePlans.size();
    final PlanSortInfo currentSortInfo = sortedActivePlans.get(currentIdx);
    final JSONObject plan = currentSortInfo.plan;

    // 1. Header Card (Title, Manage Chip, Pager Controls, Switcher Pills)
    LinearLayout header = createCardLayout(null);
    a.body.addView(header);

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

    Button manageChip = a.chip("计划管理 ⚙", false, v -> {
      phase = "manage";
      a.render();
    });
    manageChip.setTextSize(11 * a.scale);
    heading.addView(manageChip);
    header.addView(heading);

    // Pager Row (if multiple active plans)
    if (totalActive > 1) {
      LinearLayout pagerRow = a.row();
      pagerRow.setGravity(Gravity.CENTER_VERTICAL);
      LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(-1, -2);
      prLp.topMargin = a.dp(10);
      pagerRow.setLayoutParams(prLp);

      Button prevBtn = a.chip("‹", false, v -> {
        int prev = (currentIdx - 1 + totalActive) % totalActive;
        activePlanId = sortedActivePlans.get(prev).plan.optString("id");
        a.render();
      });
      prevBtn.setTextSize(14 * a.scale);
      prevBtn.setPadding(a.dp(12), 0, a.dp(12), 0);
      pagerRow.addView(prevBtn, new LinearLayout.LayoutParams(-2, a.dp(28)));

      TextView dotTv = a.text(renderDots(currentIdx, totalActive) + "  " + (currentIdx + 1) + " / " + totalActive + " · 左右滑动切换", 11, true);
      dotTv.setTextColor(a.accent);
      dotTv.setGravity(Gravity.CENTER);
      LinearLayout.LayoutParams dtLp = new LinearLayout.LayoutParams(0, -2, 1);
      pagerRow.addView(dotTv, dtLp);

      Button nextBtn = a.chip("›", false, v -> {
        int next = (currentIdx + 1) % totalActive;
        activePlanId = sortedActivePlans.get(next).plan.optString("id");
        a.render();
      });
      nextBtn.setTextSize(14 * a.scale);
      nextBtn.setPadding(a.dp(12), 0, a.dp(12), 0);
      pagerRow.addView(nextBtn, new LinearLayout.LayoutParams(-2, a.dp(28)));

      header.addView(pagerRow);
    }

    // Plan Switcher Pills Bar
    LinearLayout switcherRow = a.row();
    switcherRow.setPadding(0, a.dp(8), 0, a.dp(4));

    for (int i = 0; i < sortedActivePlans.size(); i++) {
      PlanSortInfo psi = sortedActivePlans.get(i);
      JSONObject p = psi.plan;
      String pid = p.optString("id");
      String pname = p.optString("name", "计划");
      boolean isSelected = pid.equals(activePlanId);

      String pillLabel = pname;
      if (psi.priority == 1) {
        pillLabel += " (" + psi.remaining + ")";
      } else if (psi.priority == 2) {
        pillLabel += " (休)";
      } else {
        pillLabel += " (✓)";
      }

      switcherRow.addView(scopePill(pillLabel, isSelected, () -> {
        activePlanId = pid;
        a.render();
      }));
    }

    Button addPlanBtn = a.chip("+ 新建", false, v -> showCreatePlanModal());
    addPlanBtn.setLayoutParams(new LinearLayout.LayoutParams(-2, a.dp(34)));
    addPlanBtn.setPadding(a.dp(10), 0, a.dp(10), 0);
    switcherRow.addView(addPlanBtn);

    HorizontalScrollView switcherScroll = new HorizontalScrollView(a);
    switcherScroll.setHorizontalScrollBarEnabled(false);
    switcherScroll.addView(switcherRow);
    header.addView(switcherScroll);

    // 2. Swipable Container wrapping Plan details, Today's task, and 7-day stats
    SwipableLayout swipeContainer = new SwipableLayout(a,
        () -> {
          if (totalActive > 1) {
            int next = (currentIdx + 1) % totalActive;
            activePlanId = sortedActivePlans.get(next).plan.optString("id");
            a.render();
          }
        },
        () -> {
          if (totalActive > 1) {
            int prev = (currentIdx - 1 + totalActive) % totalActive;
            activePlanId = sortedActivePlans.get(prev).plan.optString("id");
            a.render();
          }
        });

    // Plan Details Box
    LinearLayout planDetailsBox = createCardLayout(null);
    planDetailsBox.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(16));
    LinearLayout.LayoutParams pdbLp = new LinearLayout.LayoutParams(-1, -2);
    pdbLp.topMargin = a.dp(12);
    planDetailsBox.setLayoutParams(pdbLp);

    LinearLayout pTitleRow = a.row();
    pTitleRow.setGravity(Gravity.CENTER_VERTICAL);

    if (currentSortInfo != null) {
      TextView stBadge;
      if (currentSortInfo.priority == 1) {
        stBadge = a.text("🟢 待学 " + currentSortInfo.remaining, 11, true);
        stBadge.setTextColor(Color.parseColor("#10B981"));
        stBadge.setBackground(a.shape(0x1A10B981, 8, 0));
      } else if (currentSortInfo.priority == 2) {
        stBadge = a.text("☕ 今日休息", 11, true);
        stBadge.setTextColor(Color.parseColor("#F59E0B"));
        stBadge.setBackground(a.shape(0x1AF59E0B, 8, 0));
      } else {
        stBadge = a.text("✅ 今日完成", 11, true);
        stBadge.setTextColor(a.accent);
        stBadge.setBackground(a.shape((a.accent & 0x00FFFFFF) | 0x1A000000, 8, 0));
      }
      stBadge.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
      LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(-2, -2);
      sbLp.rightMargin = a.dp(6);
      pTitleRow.addView(stBadge, sbLp);
    }

    TextView planNameTv = a.text(plan.optString("name"), 17, true);
    planNameTv.setTextColor(a.foreground);
    pTitleRow.addView(planNameTv);

    View ptSpacer = new View(a);
    pTitleRow.addView(ptSpacer, new LinearLayout.LayoutParams(0, 1, 1));

    Button editPlanBtn = a.chip("编辑", false, v -> showEditPlanModal(plan));
    editPlanBtn.setTextSize(11 * a.scale);
    pTitleRow.addView(editPlanBtn);

    View gapChip1 = new View(a);
    pTitleRow.addView(gapChip1, new LinearLayout.LayoutParams(a.dp(6), 1));

    Button managePlanBtn = a.chip("管理", false, v -> {
      phase = "manage";
      a.render();
    });
    managePlanBtn.setTextSize(11 * a.scale);
    pTitleRow.addView(managePlanBtn);

    View gapChip2 = new View(a);
    pTitleRow.addView(gapChip2, new LinearLayout.LayoutParams(a.dp(6), 1));

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

    // Badges Row
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
    swipeContainer.addView(planDetailsBox);

    // 4. Today's Task Card
    JSONObject day = a.library.reviewPlans().dailyQueue(activePlanId, now);
    // Newly generated daily tasks were persisted once while collecting active plans.

    LinearLayout todayCard = createCardLayout(null);
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

      Button restExtraBtn = a.primaryButton("⚡ 今天也学一组", v -> showExtraReviewModal());
      LinearLayout.LayoutParams reLp = new LinearLayout.LayoutParams(-1, a.dp(44));
      reLp.topMargin = a.dp(8);
      todayCard.addView(restExtraBtn, reLp);
    } else if (totalTarget == 0) {
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

      Button extraBtn = a.primaryButton("🔥 再来一组 / 额外复习", v -> showExtraReviewModal());
      LinearLayout.LayoutParams ebLp = new LinearLayout.LayoutParams(-1, a.dp(46));
      ebLp.topMargin = a.dp(14);
      doneCol.addView(extraBtn, ebLp);

      todayCard.addView(doneCol);
    } else {
      TextView todayTitle = a.text("今日学习任务", 16, true);
      todayCard.addView(todayTitle);

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

      Button startBtn = a.primaryButton("开启今日复习（剩余 " + remaining + " 词）", v -> startDailyReview());
      LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(-1, a.dp(50));
      sbLp.topMargin = a.dp(16);
      startBtn.setLayoutParams(sbLp);
      startBtn.setTextSize(16 * a.scale);
      todayCard.addView(startBtn);
    }
    swipeContainer.addView(todayCard);

    // 5. Past 7 Days Statistics Card
    try {
      LocalDate todayDate = LocalDate.now();
      LocalDate fromDate = todayDate.minusDays(6);
      JSONObject stats = a.library.reviewPlans().statistics(activePlanId, fromDate.toString(), todayDate.toString());
      JSONObject totals = stats.optJSONObject("totals");

      LinearLayout statsCard = createCardLayout(null);
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
      swipeContainer.addView(statsCard);
    } catch (Exception ignored) {}

    a.body.addView(swipeContainer);
  }

  void renderManagePlans() {
    a.body.removeAllViews();
    a.body.setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(32));

    long now = System.currentTimeMillis();
    JSONArray allPlans = a.library.reviewPlans().list(false);
    JSONArray archivedPlans = a.library.reviewPlans().list(true);
    Set<String> disabledIds = getDisabledPlanIds();

    // 1. Navigation Top Bar
    LinearLayout navRow = a.row();
    navRow.setGravity(Gravity.CENTER_VERTICAL);
    navRow.setPadding(0, a.dp(4), 0, a.dp(12));

    Button backBtn = a.chip("‹ 返回复习", true, v -> {
      phase = "dashboard";
      a.render();
    });
    backBtn.setTextSize(12 * a.scale);
    navRow.addView(backBtn);

    TextView titleTv = a.text(" 复习计划管理", 17, true);
    LinearLayout.LayoutParams ttLp = new LinearLayout.LayoutParams(0, -2, 1);
    ttLp.leftMargin = a.dp(8);
    navRow.addView(titleTv, ttLp);

    Button newPlanBtn = a.primaryButton("＋ 新建计划", v -> showCreatePlanModal());
    newPlanBtn.setTextSize(12 * a.scale);
    navRow.addView(newPlanBtn, new LinearLayout.LayoutParams(-2, a.dp(36)));
    a.body.addView(navRow);

    // Separate into active, inactive, archived
    List<PlanSortInfo> activeList = getSortedActivePlans(now);
    List<JSONObject> inactiveList = new ArrayList<>();
    List<JSONObject> archivedList = new ArrayList<>();

    for (int i = 0; i < allPlans.length(); i++) {
      JSONObject p = allPlans.optJSONObject(i);
      if (p == null) continue;
      String pid = p.optString("id");
      if (disabledIds.contains(pid)) {
        inactiveList.add(p);
      }
    }

    for (int i = 0; i < archivedPlans.length(); i++) {
      JSONObject p = archivedPlans.optJSONObject(i);
      if (p != null && p.optBoolean("archived", false)) {
        archivedList.add(p);
      }
    }

    // 2. Summary & Overview Card
    LinearLayout summaryCard = createCardLayout(null);
    summaryCard.setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(14));
    LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(-1, -2);
    scLp.bottomMargin = a.dp(14);
    summaryCard.setLayoutParams(scLp);

    LinearLayout sHeading = a.row();
    sHeading.setGravity(Gravity.CENTER_VERTICAL);
    sHeading.addView(a.icon("layers-outline", 18, a.accent));
    TextView sTitle = a.text(" 学习计划总览", 15, true);
    sHeading.addView(sTitle);
    summaryCard.addView(sHeading);

    TextView sInfo = a.text("共 " + allPlans.length() + " 个进行中计划 · "
        + activeList.size() + " 个已激活 · "
        + inactiveList.size() + " 个已暂停"
        + (archivedList.size() > 0 ? (" · " + archivedList.size() + " 个已归档") : ""), 12, false);
    sInfo.setTextColor(a.muted);
    LinearLayout.LayoutParams siLp = new LinearLayout.LayoutParams(-1, -2);
    siLp.topMargin = a.dp(4);
    summaryCard.addView(sInfo, siLp);

    TextView sTip = a.text("💡 激活状态说明：已激活的计划会呈现在复习首页，按「今日待学 > 休息日 > 已完成」排序，支持左右滑动切换；暂停的计划不参与日常复习。", 11, false);
    sTip.setTextColor(a.accent);
    sTip.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
    sTip.setBackground(a.shape((a.accent & 0x00FFFFFF) | 0x14000000, 8, 0));
    LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-1, -2);
    stLp.topMargin = a.dp(8);
    summaryCard.addView(sTip, stLp);
    a.body.addView(summaryCard);

    // If completely empty
    if (allPlans.length() == 0 && archivedList.isEmpty()) {
      LinearLayout emptyCard = createCardLayout(null);
      emptyCard.setGravity(Gravity.CENTER);
      emptyCard.setPadding(a.dp(20), a.dp(36), a.dp(20), a.dp(36));
      emptyCard.addView(a.icon("bookmarks-outline", 48, a.subtle));
      TextView et = a.text("暂无任何复习计划", 16, true);
      LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(-2, -2);
      etLp.topMargin = a.dp(12);
      emptyCard.addView(et, etLp);
      Button cBtn = a.primaryButton("新建第一个复习计划", v -> showCreatePlanModal());
      LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(a.dp(200), a.dp(44));
      cbLp.topMargin = a.dp(16);
      emptyCard.addView(cBtn, cbLp);
      a.body.addView(emptyCard);
      return;
    }

    // 3. Section: 已激活计划
    TextView activeSectionTitle = a.text("已激活计划 (" + activeList.size() + ")", 14, true);
    activeSectionTitle.setTextColor(a.foreground);
    activeSectionTitle.setPadding(0, a.dp(4), 0, a.dp(8));
    a.body.addView(activeSectionTitle);

    if (activeList.isEmpty()) {
      LinearLayout noActiveCard = createCardLayout(null);
      noActiveCard.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16));
      LinearLayout.LayoutParams naLp = new LinearLayout.LayoutParams(-1, -2);
      naLp.bottomMargin = a.dp(14);
      noActiveCard.setLayoutParams(naLp);
      TextView nat = a.text("暂无已激活的计划。请从下方「未激活计划」中激活，或新建计划。", 12, false);
      nat.setTextColor(a.muted);
      noActiveCard.addView(nat);
      a.body.addView(noActiveCard);
    } else {
      for (int i = 0; i < activeList.size(); i++) {
        PlanSortInfo psi = activeList.get(i);
        a.body.addView(createPlanManageCard(psi.plan, true, psi, i + 1));
      }
    }

    // 4. Section: 未激活 / 暂停计划
    if (!inactiveList.isEmpty()) {
      TextView inactSectionTitle = a.text("未激活 / 暂停计划 (" + inactiveList.size() + ")", 14, true);
      inactSectionTitle.setTextColor(a.muted);
      inactSectionTitle.setPadding(0, a.dp(12), 0, a.dp(8));
      a.body.addView(inactSectionTitle);

      for (JSONObject p : inactiveList) {
        a.body.addView(createPlanManageCard(p, false, null, 0));
      }
    }

    // 5. Section: 已归档计划
    if (!archivedList.isEmpty()) {
      TextView archSectionTitle = a.text("已归档计划 (" + archivedList.size() + ")", 14, true);
      archSectionTitle.setTextColor(a.muted);
      archSectionTitle.setPadding(0, a.dp(16), 0, a.dp(8));
      a.body.addView(archSectionTitle);

      for (JSONObject p : archivedList) {
        a.body.addView(createArchivedPlanCard(p));
      }
    }
  }

  View createPlanManageCard(JSONObject plan, boolean isActive, PlanSortInfo psi, int rank) {
    LinearLayout card = createCardLayout(null);
    card.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = a.dp(10);
    card.setLayoutParams(lp);

    String pid = plan.optString("id");
    boolean isCurrentActive = pid.equals(activePlanId);

    // Top Row: Rank/Status Badge + Plan Name + Current Indicator
    LinearLayout topRow = a.row();
    topRow.setGravity(Gravity.CENTER_VERTICAL);

    if (isActive && psi != null) {
      TextView badge;
      if (psi.priority == 1) {
        badge = a.text("🟢 待学 " + psi.remaining + " 词", 11, true);
        badge.setTextColor(Color.parseColor("#10B981"));
        badge.setBackground(a.shape(0x1A10B981, 8, 0));
      } else if (psi.priority == 2) {
        badge = a.text("☕ 今日休息", 11, true);
        badge.setTextColor(Color.parseColor("#F59E0B"));
        badge.setBackground(a.shape(0x1AF59E0B, 8, 0));
      } else {
        badge = a.text("✅ 今日已搞定", 11, true);
        badge.setTextColor(a.accent);
        badge.setBackground(a.shape((a.accent & 0x00FFFFFF) | 0x1A000000, 8, 0));
      }
      badge.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
      topRow.addView(badge);
    } else {
      TextView pauseBadge = a.text("⏸️ 暂停中", 11, true);
      pauseBadge.setTextColor(a.subtle);
      pauseBadge.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
      pauseBadge.setBackground(a.shape(a.inputBg, 8, a.border));
      topRow.addView(pauseBadge);
    }

    TextView nameTv = a.text(" " + plan.optString("name", "计划"), 16, true);
    nameTv.setTextColor(isActive ? a.foreground : a.muted);
    LinearLayout.LayoutParams ntLp = new LinearLayout.LayoutParams(0, -2, 1);
    ntLp.leftMargin = a.dp(6);
    topRow.addView(nameTv, ntLp);

    if (isCurrentActive) {
      TextView curTag = a.text("当前激活", 10, true);
      curTag.setTextColor(a.accent);
      curTag.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
      curTag.setBackground(a.shape((a.accent & 0x00FFFFFF) | 0x22000000, 6, a.accent));
      topRow.addView(curTag);
    }
    card.addView(topRow);

    // Notebook Info & Badges Row
    JSONObject nb = a.library.find("notebooks", plan.optString("notebook_id"));
    String nbName = nb != null ? nb.optString("name", "笔记本") : "已绑定笔记";

    LinearLayout metaRow = a.row();
    metaRow.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(-1, -2);
    metaLp.topMargin = a.dp(8);
    metaRow.setLayoutParams(metaLp);

    metaRow.addView(a.icon("folder-open-outline", 13, a.muted));
    TextView nbTv = a.text(" " + nbName, 12, false);
    nbTv.setTextColor(a.muted);
    metaRow.addView(nbTv);

    View metaSpacer = new View(a);
    metaRow.addView(metaSpacer, new LinearLayout.LayoutParams(a.dp(10), 1));

    String freqLabel = formatWeekdaysBadge(plan.optJSONArray("weekdays"));
    TextView freqTv = a.text(freqLabel, 11, false);
    freqTv.setTextColor(a.muted);
    freqTv.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
    freqTv.setBackground(a.shape(a.inputBg, 6, a.border));
    metaRow.addView(freqTv);

    View metaSpacer2 = new View(a);
    metaRow.addView(metaSpacer2, new LinearLayout.LayoutParams(a.dp(6), 1));

    String quotaLabel = "新词 " + plan.optInt("daily_new_limit") + " · 复习 " + plan.optInt("daily_review_limit");
    TextView quotaTv = a.text(quotaLabel, 11, false);
    quotaTv.setTextColor(a.muted);
    quotaTv.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
    quotaTv.setBackground(a.shape(a.inputBg, 6, a.border));
    metaRow.addView(quotaTv);

    card.addView(metaRow);

    // Actions Row
    LinearLayout actRow = a.row();
    actRow.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(-1, -2);
    actLp.topMargin = a.dp(12);
    actRow.setLayoutParams(actLp);

    if (isActive) {
      Button selectBtn = a.primaryButton("进入复习", v -> {
        activePlanId = pid;
        phase = "dashboard";
        a.render();
      });
      selectBtn.setTextSize(11 * a.scale);
      actRow.addView(selectBtn, new LinearLayout.LayoutParams(-2, a.dp(34)));

      View actGap = new View(a);
      actRow.addView(actGap, new LinearLayout.LayoutParams(a.dp(8), 1));

      Button toggleActiveBtn = a.chip("设为未激活", false, v -> {
        setPlanActive(pid, false);
        if (pid.equals(activePlanId)) {
          activePlanId = null;
        }
        a.render();
      });
      toggleActiveBtn.setTextSize(11 * a.scale);
      actRow.addView(toggleActiveBtn);
    } else {
      Button activateBtn = a.primaryButton("激活此计划", v -> {
        setPlanActive(pid, true);
        activePlanId = pid;
        a.render();
      });
      activateBtn.setTextSize(11 * a.scale);
      actRow.addView(activateBtn, new LinearLayout.LayoutParams(-2, a.dp(34)));
    }

    View actSpacer = new View(a);
    actRow.addView(actSpacer, new LinearLayout.LayoutParams(0, 1, 1));

    Button editBtn = a.chip("编辑", false, v -> showEditPlanModal(plan));
    editBtn.setTextSize(11 * a.scale);
    actRow.addView(editBtn);

    View actGap2 = new View(a);
    actRow.addView(actGap2, new LinearLayout.LayoutParams(a.dp(6), 1));

    Button archiveBtn = a.chip("归档", false, v -> confirmArchivePlan(pid));
    archiveBtn.setTextSize(11 * a.scale);
    actRow.addView(archiveBtn);

    card.addView(actRow);
    return card;
  }

  View createArchivedPlanCard(JSONObject plan) {
    LinearLayout card = createCardLayout(null);
    card.setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = a.dp(8);
    card.setLayoutParams(lp);

    LinearLayout row = a.row();
    row.setGravity(Gravity.CENTER_VERTICAL);

    TextView badge = a.text("📦 已归档", 11, false);
    badge.setTextColor(a.subtle);
    badge.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
    badge.setBackground(a.shape(a.inputBg, 6, a.border));
    row.addView(badge);

    TextView nameTv = a.text(" " + plan.optString("name", "计划"), 15, true);
    nameTv.setTextColor(a.muted);
    LinearLayout.LayoutParams ntLp = new LinearLayout.LayoutParams(0, -2, 1);
    ntLp.leftMargin = a.dp(6);
    row.addView(nameTv, ntLp);

    JSONObject nb = a.library.find("notebooks", plan.optString("notebook_id"));
    String nbName = nb != null ? nb.optString("name", "笔记本") : "笔记本";
    TextView nbTv = a.text(nbName, 11, false);
    nbTv.setTextColor(a.subtle);
    row.addView(nbTv);

    card.addView(row);
    return card;
  }

  void startDailyReview() {
    long now = System.currentTimeMillis();
    boolean generatedDailyQueue = !a.library.reviewPlans().hasDailyQueue(activePlanId, now);
    JSONObject day = a.library.reviewPlans().dailyQueue(activePlanId, now);
    if (generatedDailyQueue && !a.persist()) return;
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

    Button extraRoundBtn = a.primaryButton("🔥 再来一组（额外复习）", v -> showExtraReviewModal());
    LinearLayout.LayoutParams erLp = new LinearLayout.LayoutParams(-1, a.dp(48));
    erLp.topMargin = a.dp(20);
    extraRoundBtn.setLayoutParams(erLp);
    extraRoundBtn.setTextSize(15 * a.scale);
    card.addView(extraRoundBtn);

    // Back to dashboard button
    Button backHomeBtn = a.button("返回复习中心", v -> {
      phase = "dashboard";
      a.render();
    });
    LinearLayout.LayoutParams bhLp = new LinearLayout.LayoutParams(-1, a.dp(42));
    bhLp.topMargin = a.dp(8);
    backHomeBtn.setLayoutParams(bhLp);
    backHomeBtn.setTextSize(14 * a.scale);
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
    if (set.size() == 4 && set.contains(1) && set.contains(3) && set.contains(5) && set.contains(7)) {
      return "📅 隔天 (周一三五日)";
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

  View createWeekdaySelector(Set<Integer> selectedDays) {
    LinearLayout container = a.column();

    // 1. Presets Row
    LinearLayout presetsRow = a.row();
    presetsRow.setGravity(Gravity.CENTER_VERTICAL);
    String[] presetLabels = {"每天", "工作日", "周末", "隔天"};
    int[][] presetDays = {
      {1, 2, 3, 4, 5, 6, 7},
      {1, 2, 3, 4, 5},
      {6, 7},
      {1, 3, 5, 7}
    };
    List<Button> presetBtns = new ArrayList<>();
    List<Button> dayBtns = new ArrayList<>();
    TextView summaryTv = a.text("", 12, false);

    Runnable refresh = () -> {
      for (int i = 0; i < 7; i++) {
        int d = i + 1;
        Button db = dayBtns.get(i);
        boolean sel = selectedDays.contains(d);
        db.setTextColor(sel ? Color.WHITE : a.foreground);
        db.setBackground(a.shape(sel ? a.accent : a.inputBg, 8, sel ? a.accent : a.border));
        if (sel) db.setTypeface(null, android.graphics.Typeface.BOLD);
        else db.setTypeface(null, android.graphics.Typeface.NORMAL);
      }
      for (int i = 0; i < presetLabels.length; i++) {
        Button pb = presetBtns.get(i);
        int[] pDays = presetDays[i];
        boolean matches = selectedDays.size() == pDays.length;
        if (matches) {
          for (int d : pDays) {
            if (!selectedDays.contains(d)) { matches = false; break; }
          }
        }
        pb.setTextColor(matches ? Color.WHITE : a.foreground);
        pb.setBackground(a.shape(matches ? a.accent : a.inputBg, 8, matches ? a.accent : a.border));
      }
      if (selectedDays.isEmpty()) {
        summaryTv.setText("⚠️ 请至少选择一天学习频次");
        summaryTv.setTextColor(Color.parseColor("#EF4444"));
      } else {
        JSONArray arr = new JSONArray();
        for (int d : selectedDays) arr.put(d);
        summaryTv.setText("已选 " + selectedDays.size() + " 天 (" + formatWeekdaysBadge(arr).replace("📅 ", "").replace("💼 ", "").replace("🏖 ", "") + ")");
        summaryTv.setTextColor(a.muted);
      }
    };

    for (int i = 0; i < presetLabels.length; i++) {
      final int[] days = presetDays[i];
      Button pb = a.chip(presetLabels[i], false, null);
      pb.setTextSize(11.5f * a.scale);
      pb.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
      pb.setOnClickListener(v -> {
        selectedDays.clear();
        for (int d : days) selectedDays.add(d);
        refresh.run();
      });
      LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(0, a.dp(30), 1);
      if (i > 0) pbLp.leftMargin = a.dp(6);
      presetsRow.addView(pb, pbLp);
      presetBtns.add(pb);
    }
    LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(-1, -2);
    prLp.topMargin = a.dp(4);
    prLp.bottomMargin = a.dp(8);
    container.addView(presetsRow, prLp);

    // 2. 7 Day Buttons Row
    LinearLayout daysRow = a.row();
    String[] dayNames = {"一", "二", "三", "四", "五", "六", "日"};
    for (int d = 1; d <= 7; d++) {
      final int dayVal = d;
      Button db = a.button("周" + dayNames[d - 1], null);
      db.setTextSize(11.5f * a.scale);
      db.setPadding(0, 0, 0, 0);
      db.setOnClickListener(v -> {
        if (selectedDays.contains(dayVal)) {
          selectedDays.remove(dayVal);
        } else {
          selectedDays.add(dayVal);
        }
        refresh.run();
      });
      LinearLayout.LayoutParams dbLp = new LinearLayout.LayoutParams(0, a.dp(34), 1);
      if (d > 1) dbLp.leftMargin = a.dp(4);
      daysRow.addView(db, dbLp);
      dayBtns.add(db);
    }
    container.addView(daysRow);

    summaryTv.setTextColor(a.muted);
    LinearLayout.LayoutParams smLp = new LinearLayout.LayoutParams(-1, -2);
    smLp.topMargin = a.dp(6);
    smLp.bottomMargin = a.dp(10);
    container.addView(summaryTv, smLp);

    refresh.run();
    return container;
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
    layout.addView(a.text("学习频次（点击星期自由定制）", 13, true));
    final Set<Integer> selectedDays = new TreeSet<>(Arrays.asList(1, 2, 3, 4, 5, 6, 7));
    layout.addView(createWeekdaySelector(selectedDays));

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
          if (selectedDays.isEmpty()) {
            a.say("请至少选择一天学习频次");
            return;
          }
          String name = nameEt.getText().toString().trim();
          if (name.isEmpty()) name = "复习计划";
          int nbIdx = nbSpinner.getSelectedItemPosition();
          String nbId = (nbIdx >= 0 && nbIdx < nbIds.size()) ? nbIds.get(nbIdx) : nbIds.get(0);

          int newLimit = 15;
          try { newLimit = Integer.parseInt(newLimitEt.getText().toString().trim()); } catch (Exception ignored) {}
          int revLimit = 40;
          try { revLimit = Integer.parseInt(revLimitEt.getText().toString().trim()); } catch (Exception ignored) {}

          int[] weekdays = new int[selectedDays.size()];
          int sIdx = 0;
          for (int d : selectedDays) weekdays[sIdx++] = d;

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
    layout.addView(a.text("学习频次（点击星期自由定制）", 13, true));
    final Set<Integer> selectedDays = new TreeSet<>();
    JSONArray curWd = plan.optJSONArray("weekdays");
    if (curWd != null && curWd.length() > 0) {
      for (int i = 0; i < curWd.length(); i++) selectedDays.add(curWd.optInt(i));
    } else {
      selectedDays.addAll(Arrays.asList(1, 2, 3, 4, 5, 6, 7));
    }
    layout.addView(createWeekdaySelector(selectedDays));

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
          if (selectedDays.isEmpty()) {
            a.say("请至少选择一天学习频次");
            return;
          }
          String name = nameEt.getText().toString().trim();
          if (name.isEmpty()) name = plan.optString("name");

          int newLimit = plan.optInt("daily_new_limit", 15);
          try { newLimit = Integer.parseInt(newLimitEt.getText().toString().trim()); } catch (Exception ignored) {}
          int revLimit = plan.optInt("daily_review_limit", 40);
          try { revLimit = Integer.parseInt(revLimitEt.getText().toString().trim()); } catch (Exception ignored) {}

          final String finalName = name;
          final int finalNew = newLimit;
          final int finalRev = revLimit;
          final Set<Integer> finalDays = new TreeSet<>(selectedDays);

          boolean saved = a.change(() -> {
            JSONObject patch = new JSONObject();
            Library.put(patch, "name", finalName);
            Library.put(patch, "daily_new_limit", finalNew);
            Library.put(patch, "daily_review_limit", finalRev);
            JSONArray wdArr = new JSONArray();
            for (int w : finalDays) wdArr.put(w);
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

  void showExtraReviewModal() {
    if (activePlanId == null) return;
    long now = System.currentTimeMillis();
    JSONObject avail = a.library.reviewPlans().extraAvailable(activePlanId, now);
    int availTotal = avail.optInt("available_total");
    int availNew = avail.optInt("available_new");
    int availRev = avail.optInt("available_review");
    int dailyNew = avail.optInt("daily_new_limit", 15);
    int dailyRev = avail.optInt("daily_review_limit", 40);

    if (availTotal == 0) {
      new AlertDialog.Builder(a)
          .setTitle("暂无更多可用词汇")
          .setMessage("该笔记本中所有词汇今天已全部在复习列表中，没有更多词汇可供加练。\n\n你可以前往「笔记」模块添加更多新词后再来！")
          .setPositiveButton("知道了", null)
          .show();
      return;
    }

    LinearLayout layout = a.column();
    layout.setPadding(a.dp(20), a.dp(16), a.dp(20), a.dp(16));

    // Summary banner
    LinearLayout tipBox = a.column();
    tipBox.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
    tipBox.setBackground(a.shape((a.accent & 0x00FFFFFF) | 0x12000000, 10, 0));
    TextView tipTitle = a.text("趁热打铁，多记几个生词或强化记忆！", 13, true);
    tipTitle.setTextColor(a.accent);
    tipBox.addView(tipTitle);
    TextView tipDetail = a.text("词库尚有 " + availTotal + " 词可用（未学新词 " + availNew + " · 巩固复习 " + availRev + "）", 12, false);
    tipDetail.setTextColor(a.muted);
    LinearLayout.LayoutParams tdLp = new LinearLayout.LayoutParams(-1, -2);
    tdLp.topMargin = a.dp(3);
    tipBox.addView(tipDetail, tdLp);
    LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(-1, -2);
    tbLp.bottomMargin = a.dp(14);
    layout.addView(tipBox, tbLp);

    layout.addView(a.text("加练模式", 13, true));

    final int[] selectedMode = {0}; // default to 再来一天的量
    int oneDayTarget = Math.min(dailyNew + dailyRev, availTotal);
    int oneDayNew = Math.min(dailyNew, availNew);
    int oneDayRev = Math.min(dailyRev, availRev);

    String[] modeTitles = {
      "再来一天的量 (+" + oneDayTarget + " 词)",
      "微练习 (+5 词)",
      "标准一组 (+10 词)",
      "深度突破 (+20 词)",
      "自由指定数量"
    };
    String[] modeSubs = {
      "按计划配置配额：新词 " + oneDayNew + " + 复习 " + oneDayRev,
      "轻松加练，只需 2~3 分钟",
      "巩固记忆，推荐加练量",
      "强化记忆深度，巩固生词群",
      "自由输入本次想要加练的词汇数"
    };

    List<LinearLayout> optionCards = new ArrayList<>();
    LinearLayout optionsGroup = a.column();

    // Custom input container (visible when mode == 4)
    LinearLayout customRow = a.row();
    customRow.setGravity(Gravity.CENTER_VERTICAL);
    customRow.setVisibility(View.GONE);
    EditText customEt = a.input("输入词数", "10", false);
    customEt.setInputType(InputType.TYPE_CLASS_NUMBER);
    LinearLayout.LayoutParams ceLp = new LinearLayout.LayoutParams(0, a.dp(44), 1);
    customRow.addView(customEt, ceLp);
    TextView customUnit = a.text(" 词 (上限 " + availTotal + " 词)", 12, false);
    customUnit.setTextColor(a.muted);
    customRow.addView(customUnit);

    Runnable updateSelection = () -> {
      for (int i = 0; i < optionCards.size(); i++) {
        LinearLayout card = optionCards.get(i);
        boolean sel = selectedMode[0] == i;
        card.setBackground(a.shape(sel ? ((a.accent & 0x00FFFFFF) | 0x12000000) : a.inputBg, 10, sel ? a.accent : a.border));
        TextView tTitle = (TextView) card.getChildAt(0);
        tTitle.setTextColor(sel ? a.accent : a.foreground);
      }
      customRow.setVisibility(selectedMode[0] == 4 ? View.VISIBLE : View.GONE);
    };

    for (int i = 0; i < modeTitles.length; i++) {
      final int modeIdx = i;
      LinearLayout oCard = a.column();
      oCard.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
      oCard.setClickable(true);
      oCard.setOnClickListener(v -> {
        selectedMode[0] = modeIdx;
        updateSelection.run();
      });

      TextView oTitle = a.text(modeTitles[i], 14, true);
      oCard.addView(oTitle);

      TextView oSub = a.text(modeSubs[i], 11, false);
      oSub.setTextColor(a.muted);
      oCard.addView(oSub);

      LinearLayout.LayoutParams ocLp = new LinearLayout.LayoutParams(-1, -2);
      ocLp.topMargin = a.dp(6);
      optionsGroup.addView(oCard, ocLp);
      optionCards.add(oCard);
    }
    layout.addView(optionsGroup);

    LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(-1, -2);
    crLp.topMargin = a.dp(8);
    layout.addView(customRow, crLp);

    updateSelection.run();

    ScrollView sv = new ScrollView(a);
    sv.addView(layout);

    new AlertDialog.Builder(a)
        .setTitle("🔥 再来一组")
        .setView(sv)
        .setNegativeButton("取消", null)
        .setPositiveButton("开始加练", (dialog, which) -> {
          int count = 10;
          int addN = 0, addR = 0;
          if (selectedMode[0] == 0) {
            addN = oneDayNew;
            addR = oneDayRev;
            if (addN == 0 && addR == 0) {
              addR = Math.min(oneDayTarget, availRev);
              addN = Math.min(oneDayTarget - addR, availNew);
            }
          } else {
            if (selectedMode[0] == 1) count = 5;
            else if (selectedMode[0] == 2) count = 10;
            else if (selectedMode[0] == 3) count = 20;
            else {
              try { count = Integer.parseInt(customEt.getText().toString().trim()); } catch (Exception ignored) {}
            }
            count = Math.max(1, Math.min(count, availTotal));
            addR = Math.min(count, availRev);
            addN = Math.min(count - addR, availNew);
          }

          final int finalN = addN;
          final int finalR = addR;

          boolean saved = a.change(() -> {
            a.library.reviewPlans().extendDailyQueue(activePlanId, finalN, finalR, System.currentTimeMillis());
            phase = "active";
            revealed = false;
            cardStartTime = System.currentTimeMillis();
          });
          if (saved) {
            a.say("已加练 " + (finalN + finalR) + " 词，开始复习！");
          }
        })
        .show();
  }
}
