package com.lexinote.nativeapp;

import android.graphics.Color;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Recycled assistant timeline and fixed composer. One instance belongs to one rendered activity
 * root.
 */
final class AssistantUi {
  private final MainActivity a;
  private final QueryUi q;
  private final String sessionId;
  private final ListView list;
  private final Adapter adapter;
  private final View latest;
  private EditText composer;
  private String partial = "";
  private FrameLayout drawerOverlay;
  private View scrim;
  private DrawerPanelLayout drawerPanel;
  private EditText drawerSearchInput;
  private LinearLayout drawerList;
  private String drawerFilter = "";
  private boolean drawerOpen = false;
  private int drawerWidth;

  final class DrawerSwipeLayout extends FrameLayout {
    private float downX, downY;
    private boolean tracking;

    DrawerSwipeLayout(android.content.Context context) {
      super(context);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
      if (isDrawerOpen()) return false;
      switch (ev.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          downX = ev.getX();
          downY = ev.getY();
          tracking = downX < a.dp(80);
          break;
        case MotionEvent.ACTION_MOVE:
          if (tracking) {
            float dx = ev.getX() - downX;
            float dy = Math.abs(ev.getY() - downY);
            if (dx > a.dp(35) && dx > dy * 1.2f) {
              tracking = false;
              openDrawer();
              return true;
            }
          }
          break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
          tracking = false;
          break;
      }
      return super.onInterceptTouchEvent(ev);
    }
  }

  final class DrawerPanelLayout extends LinearLayout {
    private float panelDownX, panelDownY;

    DrawerPanelLayout(android.content.Context context) {
      super(context);
      setOrientation(VERTICAL);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
      switch (ev.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          panelDownX = ev.getX();
          panelDownY = ev.getY();
          break;
        case MotionEvent.ACTION_MOVE:
          float dx = ev.getX() - panelDownX;
          float dy = Math.abs(ev.getY() - panelDownY);
          if (dx < -a.dp(35) && Math.abs(dx) > dy * 1.2f) {
            closeDrawer();
            return true;
          }
          break;
      }
      return super.onInterceptTouchEvent(ev);
    }
  }

  private AssistantUi(MainActivity activity, QueryUi query) {
    a = activity;
    q = query;
    sessionId = a.session == null ? "" : a.session.optString("id");
    list = new ListView(a);
    list.setDivider(null);
    list.setBackgroundColor(a.background);
    list.setTranscriptMode(ListView.TRANSCRIPT_MODE_DISABLED);
    list.addHeaderView(makeHeader(), null, false);
    adapter = new Adapter();
    list.setAdapter(adapter);

    FrameLayout latestBtn = new FrameLayout(a);
    latestBtn.setContentDescription("回到最新消息");
    android.graphics.drawable.GradientDrawable latestBg =
        new android.graphics.drawable.GradientDrawable();
    latestBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
    latestBg.setColor(a.card);
    latestBg.setStroke(a.dp(1), a.border);
    latestBtn.setBackground(latestBg);
    latestBtn.setElevation(a.dp(6));
    latestBtn.setClickable(true);
    latestBtn.setFocusable(true);

    View arrow =
        new View(a) {
          final android.graphics.Paint p =
              new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
          final android.graphics.Path path = new android.graphics.Path();

          @Override
          protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            p.setColor(a.foreground);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(a.dp(2f));
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setStrokeJoin(android.graphics.Paint.Join.ROUND);

            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float top = h * 0.32f;
            float bottom = h * 0.68f;
            float arm = w * 0.18f;

            path.reset();
            path.moveTo(cx, top);
            path.lineTo(cx, bottom);
            path.moveTo(cx - arm, bottom - arm);
            path.lineTo(cx, bottom);
            path.lineTo(cx + arm, bottom - arm);

            canvas.drawPath(path, p);
          }
        };
    latestBtn.addView(arrow, new FrameLayout.LayoutParams(-1, -1));
    latestBtn.setOnClickListener(
        v -> {
          v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
          scrollToLatest();
        });
    latest = latestBtn;
    latest.setVisibility(View.GONE);
    list.setOnScrollListener(
        new android.widget.AbsListView.OnScrollListener() {
          @Override
          public void onScrollStateChanged(android.widget.AbsListView view, int state) {}

          @Override
          public void onScroll(android.widget.AbsListView view, int first, int visible, int total) {
            if (total == 0) return;
            latest.setVisibility(isAtBottom() ? View.GONE : View.VISIBLE);
          }
        });
  }

  static AssistantUi show(MainActivity a, QueryUi q) {
    AssistantUi ui = new AssistantUi(a, q);
    int index = a.root.indexOfChild(a.scroll);
    if (index < 0) index = Math.max(0, a.root.getChildCount() - 1);
    a.scroll.setVisibility(View.GONE);
    DrawerSwipeLayout timeline = ui.new DrawerSwipeLayout(a);
    timeline.addView(ui.list, new FrameLayout.LayoutParams(-1, -1));
    FrameLayout.LayoutParams latestLp =
        new FrameLayout.LayoutParams(a.dp(40), a.dp(40), Gravity.BOTTOM | Gravity.RIGHT);
    latestLp.setMargins(0, 0, a.dp(16), a.dp(16));
    timeline.addView(ui.latest, latestLp);
    ui.buildDrawer(timeline);
    a.root.addView(timeline, index, new LinearLayout.LayoutParams(-1, 0, 1));
    ui.composer();
    if (android.os.Build.VERSION.SDK_INT >= 29) {
      timeline.post(
          () -> {
            List<android.graphics.Rect> rects = new ArrayList<>();
            rects.add(new android.graphics.Rect(0, 0, a.dp(48), timeline.getHeight()));
            timeline.setSystemGestureExclusionRects(rects);
          });
    }
    ui.list.post(ui::restoreAnchor);
    return ui;
  }

  private void buildDrawer(ViewGroup container) {
    drawerWidth =
        Math.min(a.dp(310), (int) (a.getResources().getDisplayMetrics().widthPixels * 0.85f));
    drawerOverlay = new FrameLayout(a);
    drawerOverlay.setVisibility(View.GONE);

    // Semi-transparent scrim
    scrim = new View(a);
    scrim.setBackgroundColor(0x66000000);
    scrim.setOnClickListener(v -> closeDrawer());
    drawerOverlay.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

    // Drawer panel
    drawerPanel = new DrawerPanelLayout(a);
    drawerPanel.setBackground(a.shape(a.card, 0, 0));
    drawerPanel.setElevation(a.dp(12));

    // Panel Header
    LinearLayout panelHeader = a.row();
    panelHeader.setPadding(a.dp(14), a.dp(14), a.dp(14), a.dp(8));
    TextView title = a.text("AI 会话", 16, true);
    panelHeader.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

    Button newBtn =
        a.button(
            "＋ 新建",
            v -> {
              closeDrawer();
              a.cancel();
              a.clearImage();
              a.session = a.library.newSession("");
              a.screen = "assistant";
              a.render();
            });
    newBtn.setTextSize(12 * a.scale);
    panelHeader.addView(newBtn);

    TextView closeIcon = a.text("✕", 18, false);
    closeIcon.setTextColor(a.muted);
    closeIcon.setPadding(a.dp(8), a.dp(4), a.dp(4), a.dp(4));
    closeIcon.setContentDescription("关闭");
    closeIcon.setOnClickListener(v -> closeDrawer());
    panelHeader.addView(closeIcon);
    drawerPanel.addView(panelHeader);

    // Search box
    LinearLayout searchWrapper = a.column();
    searchWrapper.setPadding(a.dp(14), 0, a.dp(14), a.dp(8));
    drawerSearchInput = a.input("搜索会话…", drawerFilter, false);
    drawerSearchInput.setTextSize(13 * a.scale);
    drawerSearchInput.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
    q.track(
        drawerSearchInput,
        val -> {
          drawerFilter = val;
          renderDrawerItems();
        });
    searchWrapper.addView(drawerSearchInput);
    drawerPanel.addView(searchWrapper);

    // List in a ScrollView
    ScrollView scroll = new ScrollView(a);
    scroll.setFillViewport(true);
    drawerList = a.column();
    drawerList.setPadding(a.dp(10), 0, a.dp(10), a.dp(20));
    scroll.addView(drawerList);
    drawerPanel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

    FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(drawerWidth, -1, Gravity.LEFT);
    drawerOverlay.addView(drawerPanel, panelLp);

    container.addView(drawerOverlay, new FrameLayout.LayoutParams(-1, -1));
  }

  boolean isDrawerOpen() {
    return drawerOpen;
  }

  void openDrawer() {
    if (drawerOverlay == null || drawerOpen) return;
    drawerOpen = true;
    InputMethodManager imm =
        (InputMethodManager) a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
    if (imm != null && a.getCurrentFocus() != null) {
      imm.hideSoftInputFromWindow(a.getCurrentFocus().getWindowToken(), 0);
    }
    if (composer != null) composer.clearFocus();
    renderDrawerItems();
    drawerOverlay.setVisibility(View.VISIBLE);
    drawerPanel.setTranslationX(-drawerWidth);
    drawerPanel.animate().translationX(0).setDuration(220).start();
    scrim.setAlpha(0f);
    scrim.animate().alpha(1f).setDuration(220).start();
  }

  void closeDrawer() {
    if (drawerOverlay == null || !drawerOpen) return;
    drawerOpen = false;
    drawerPanel.animate().translationX(-drawerWidth).setDuration(180).start();
    scrim
        .animate()
        .alpha(0f)
        .setDuration(180)
        .withEndAction(
            () -> {
              if (!drawerOpen) drawerOverlay.setVisibility(View.GONE);
            })
        .start();
  }

  void toggleDrawer() {
    if (drawerOpen) closeDrawer();
    else openDrawer();
  }

  void renderDrawerItems() {
    if (drawerList == null) return;
    drawerList.removeAllViews();
    JSONArray conversations = Library.sortedSessions(a.library.array("conversations"), true);
    List<JSONObject> items = new ArrayList<>();
    String currentId = a.session != null ? a.session.optString("id") : "";

    for (int i = 0; i < conversations.length(); i++) {
      JSONObject item = conversations.optJSONObject(i);
      if (item == null) continue;
      if (q.saved(item) || Library.isTemporary(item)) {
        String itemTitle = q.title(item);
        if (drawerFilter.isEmpty()
            || Library.normalized(itemTitle).contains(Library.normalized(drawerFilter))) {
          items.add(item);
        }
      }
    }

    if (items.isEmpty()) {
      TextView empty =
          a.text(drawerFilter.isEmpty() ? "暂无会话" : "没有符合条件的会话", 13, false);
      empty.setGravity(Gravity.CENTER);
      empty.setPadding(0, a.dp(30), 0, a.dp(30));
      drawerList.addView(empty);
      return;
    }

    for (JSONObject item : items) {
      boolean isCurrent = item.optString("id").equals(currentId);
      boolean isSaved = q.saved(item);

      LinearLayout row = a.row();
      row.setPadding(a.dp(10), a.dp(10), a.dp(6), a.dp(10));
      row.setBackground(
          a.shape(
              isCurrent ? (a.accent & 0xFFFFFF) | 0x18000000 : Color.TRANSPARENT,
              10,
              isCurrent ? a.accent : 0));

      LinearLayout info = a.column();
      TextView itemTitle = a.text(q.title(item), 14, isCurrent);
      if (isCurrent) itemTitle.setTextColor(a.accent);
      itemTitle.setSingleLine(true);
      itemTitle.setEllipsize(TextUtils.TruncateAt.END);
      info.addView(itemTitle);

      LinearLayout metaRow = a.row();
      metaRow.setPadding(0, a.dp(3), 0, 0);

      TextView badge = a.text(isSaved ? "已保存" : "临时", 10, false);
      badge.setTextColor(isSaved ? a.accent : a.muted);
      badge.setBackground(
          a.shape(
              isSaved
                  ? (a.accent & 0xFFFFFF) | 0x20000000
                  : (a.muted & 0xFFFFFF) | 0x1A000000,
              4,
              0));
      badge.setPadding(a.dp(4), a.dp(1), a.dp(4), a.dp(1));
      metaRow.addView(badge);

      TextView date = a.text(" · " + q.sessionDate(item, "last_used_at"), 11, false);
      date.setTextColor(a.subtle);
      date.setSingleLine(true);
      metaRow.addView(date);

      info.addView(metaRow);

      row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

      info.setOnClickListener(
          v -> {
            closeDrawer();
            if (!isCurrent) {
              q.open(item);
              a.screen = "assistant";
              a.render();
            }
          });
      a.passiveLabels(info);

      TextView deleteIcon = a.icon("trash-outline", 18, a.muted);
      deleteIcon.setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8));
      deleteIcon.setContentDescription("删除" + q.title(item));
      deleteIcon.setOnClickListener(
          v -> {
            q.confirmDeleteConversation(
                item,
                () -> {
                  if (isCurrent) {
                    closeDrawer();
                    a.session = null;
                    a.screen = "tabs";
                    a.page = 0;
                    a.render();
                  } else {
                    renderDrawerItems();
                  }
                });
          });
      row.addView(deleteIcon);

      drawerList.addView(row);
      drawerList.addView(a.gap(4));
    }
  }

  void updatePartial(String safeText) {
    boolean followTail = isAtBottom();
    partial = safeText == null ? "" : safeText;
    adapter.notifyDataSetChanged();
    afterRowsChanged(followTail);
  }

  void refresh() {
    boolean followTail = isAtBottom();
    adapter.notifyDataSetChanged();
    afterRowsChanged(followTail);
  }

  void saveAnchor() {
    if (a.session == null || sessionId.isEmpty() || !sessionId.equals(a.session.optString("id")))
      return;
    Library.put(a.session, "assistant_anchor_at_bottom", isAtBottom());
    int rawPosition = list.getFirstVisiblePosition() - list.getHeaderViewsCount();
    if (adapter.getCount() == 0) {
      Library.put(a.session, "assistant_anchor_id", "");
      Library.put(a.session, "assistant_anchor_offset", 0);
      return;
    }
    int listPosition = Math.max(0, rawPosition);
    View first = list.getChildAt(rawPosition < 0 && list.getChildCount() > 1 ? 1 : 0);
    String id = AssistantTimeline.id(q.messages(), listPosition);
    Library.put(a.session, "assistant_anchor_id", id);
    Library.put(a.session, "assistant_anchor_offset", first == null ? 0 : first.getTop());
  }

  private void restoreAnchor() {
    if (a.session == null || !sessionId.equals(a.session.optString("id"))) return;
    if (a.session.optBoolean("assistant_anchor_at_bottom", true)) {
      scrollToLatest();
      return;
    }
    String id = a.session.optString("assistant_anchor_id", "");
    int position = AssistantTimeline.positionOf(q.messages(), id);
    if (position < 0) {
      scrollToLatest();
      return;
    }
    list.setSelectionFromTop(
        position + list.getHeaderViewsCount(), a.session.optInt("assistant_anchor_offset", 0));
  }

  private void afterRowsChanged(boolean followTail) {
    if (followTail) list.post(this::scrollToLatest);
    else latest.setVisibility(View.VISIBLE);
  }

  private boolean isAtBottom() {
    int count = list.getCount();
    if (count == 0) return true;
    if (list.getLastVisiblePosition() < count - 1 || list.getChildCount() == 0) return false;
    View last = list.getChildAt(list.getChildCount() - 1);
    return last.getBottom() <= list.getHeight() - list.getPaddingBottom();
  }

  private void scrollToLatest() {
    if (list.getCount() > 0) list.setSelection(list.getCount() - 1);
    latest.setVisibility(View.GONE);
  }

  private View makeHeader() {
    LinearLayout column = a.column();
    column.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8));
    Uri source = a.image;
    if (source == null && a.session != null) {
      JSONArray images = a.session.optJSONArray("input_images");
      if (images != null && images.length() > 0) source = Uri.parse(images.optString(0));
    }
    if (source != null) {
      LinearLayout imageBlock = a.column();
      TextView sourceLabel = a.text("来源图片 · 点击重新裁剪或选择区域", 12, false);
      sourceLabel.setTextColor(a.muted);
      imageBlock.addView(sourceLabel);
      ImageView preview = new ImageView(a);
      preview.setImageURI(source);
      preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
      imageBlock.addView(preview, new LinearLayout.LayoutParams(-1, a.dp(72)));
      a.passiveLabels(imageBlock);
      imageBlock.setOnClickListener(v -> a.cropImage());
      column.addView(imageBlock);
    }
    String recognized =
        a.session == null
            ? ""
            : a.session.optString("recognized_text", a.session.optString("source_context", ""));
    if (!recognized.isEmpty()) {
      TextView context = a.text("识别上下文：" + recognized, 12, false);
      context.setTextColor(a.muted);
      column.addView(context);
    }
    if (q.messages().length() == 0 && source == null && recognized.isEmpty()) {
      LinearLayout welcomeCard = a.column();
      welcomeCard.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16));
      welcomeCard.setBackground(a.shape(a.card, 16, a.border));

      LinearLayout titleRow = a.row();
      titleRow.setGravity(Gravity.CENTER_VERTICAL);
      TextView badge = a.text("✦ AI 英语助手", 12, true);
      badge.setTextColor(a.accent);
      titleRow.addView(badge);
      welcomeCard.addView(titleRow);

      TextView desc =
          a.text(
              "欢迎使用 LexiNote 英语助手！你可以向我提问任何英语学习问题，包括单词/短语用法辨析、地道表达、例句生成和语法答疑。",
              13,
              false);
      desc.setTextColor(a.muted);
      desc.setLineSpacing(a.dp(3), 1.2f);
      LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(-1, -2);
      descLp.topMargin = a.dp(8);
      descLp.bottomMargin = a.dp(14);
      welcomeCard.addView(desc, descLp);

      welcomeCard.addView(a.text("试试这样问：", 12, true));

      String[] starters = {
        "Hello 和 Hi 用法有什么区别？",
        "如何用英语地道地表达“加油”？",
        "Explain the difference between 'affect' and 'effect'"
      };
      for (String starter : starters) {
        LinearLayout chip = a.row();
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(a.shape(a.inputBg, 10, a.border));
        chip.setPadding(a.dp(12), a.dp(9), a.dp(10), a.dp(9));
        chip.setClickable(true);
        chip.setFocusable(true);

        TextView chipText = a.text(starter, 13, false);
        chipText.setTextColor(a.foreground);
        chip.addView(chipText, new LinearLayout.LayoutParams(0, -2, 1));

        TextView arrow = a.text("›", 16, true);
        arrow.setTextColor(a.muted);
        chip.addView(arrow);

        chip.setOnClickListener(
            v -> {
              v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
              sendDirectly(starter);
            });

        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-1, -2);
        sLp.topMargin = a.dp(6);
        welcomeCard.addView(chip, sLp);
      }

      LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
      cardLp.topMargin = a.dp(6);
      cardLp.bottomMargin = a.dp(8);
      column.addView(welcomeCard, cardLp);
    }
    return column;
  }

  private void addQuestions(LinearLayout parent, JSONArray questions) {
    if (questions == null || questions.length() == 0) return;
    int count = Math.min(questions.length(), 3);
    if (count == 0) return;

    LinearLayout box = a.column();
    box.setPadding(0, a.dp(10), 0, a.dp(2));

    LinearLayout header = a.row();
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(0, 0, 0, a.dp(6));
    TextView sparkle = a.text("✦ 追问建议", 11, true);
    sparkle.setTextColor(a.accent);
    header.addView(sparkle);
    box.addView(header);

    for (int i = 0; i < count; i++) {
      JSONObject choice = questions.optJSONObject(i);
      String label = choice == null ? questions.optString(i) : choice.optString("label");
      if (label == null || label.trim().isEmpty()) continue;
      String cleanLabel = label.trim();

      LinearLayout row = a.row();
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setBackground(a.shape(a.inputBg, 10, a.border));
      row.setPadding(a.dp(12), a.dp(9), a.dp(10), a.dp(9));
      row.setClickable(true);
      row.setFocusable(true);

      TextView text = a.text(cleanLabel, 13, false);
      text.setTextColor(a.foreground);
      text.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
      row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));

      TextView arrow = a.text("›", 16, true);
      arrow.setTextColor(a.muted);
      arrow.setPadding(a.dp(6), 0, 0, 0);
      row.addView(arrow);

      row.setOnClickListener(
          v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            sendDirectly(cleanLabel);
          });

      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
      lp.bottomMargin = a.dp(6);
      box.addView(row, lp);
    }
    parent.addView(box);
  }

  private void sendDirectly(String text) {
    if (text == null || text.trim().isEmpty()) return;
    if (a.active != null) return;
    q.follow = "";
    if (composer != null) composer.setText("");
    q.send(text.trim());
    a.render();
  }

  private void composer() {
    LinearLayout footer = a.fixedFooter();
    footer.setBackground(a.shape(a.card, 0, a.border));
    LinearLayout column = a.column();

    if (a.image != null) {
      LinearLayout imgPreviewBar = a.row();
      imgPreviewBar.setGravity(Gravity.CENTER_VERTICAL);
      imgPreviewBar.setPadding(0, 0, 0, a.dp(8));

      ImageView thumb = new ImageView(a);
      thumb.setImageURI(a.image);
      thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
      thumb.setBackground(a.shape(a.card, 6, a.border));
      thumb.setClipToOutline(true);
      imgPreviewBar.addView(thumb, new LinearLayout.LayoutParams(a.dp(36), a.dp(36)));

      TextView imgLabel = a.text("已附带图片 · 点击重新裁剪", 11, false);
      imgLabel.setTextColor(a.muted);
      imgLabel.setPadding(a.dp(8), 0, a.dp(8), 0);
      imgLabel.setOnClickListener(v -> a.cropImage());
      imgPreviewBar.addView(imgLabel, new LinearLayout.LayoutParams(0, -2, 1));

      TextView removeImg = a.icon("close-circle", 18, a.muted);
      removeImg.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(4));
      removeImg.setOnClickListener(
          v -> {
            a.clearImage();
            a.render();
          });
      imgPreviewBar.addView(removeImg);
      column.addView(imgPreviewBar);
    }

    if (a.session != null && a.session.has("last_error")) {
      TextView error = a.text("上次请求失败：" + a.session.optString("last_error"), 12, false);
      error.setTextColor(a.muted);
      column.addView(error);
      column.addView(a.outlineButton("重试上一条", v -> q.retryLast()));
    }

    LinearLayout row = a.row();
    row.setGravity(Gravity.CENTER_VERTICAL);

    TextView photoBtn = a.icon("camera-outline", 22, a.muted);
    photoBtn.setPadding(a.dp(4), a.dp(6), a.dp(8), a.dp(6));
    photoBtn.setOnClickListener(
        v -> {
          v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
          a.chooseImage();
        });
    row.addView(photoBtn);

    composer = a.input("继续提问…", q.follow, true);
    composer.setMinHeight(a.dp(40));
    composer.setMaxHeight(a.dp(100));
    composer.setTextSize(14 * a.scale);
    composer.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
    composer.setBackground(a.shape(a.inputBg, 18, a.border));
    row.addView(composer, new LinearLayout.LayoutParams(0, -2, 1));

    if (a.active != null) {
      Button cancelBtn =
          a.button(
              "取消",
              v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                a.cancel();
                if (a.session != null) {
                  Library.put(a.session, "last_error", "请求已取消");
                  a.library.touchSession(a.session, true);
                  a.persist();
                }
                a.render();
              });
      cancelBtn.setTextColor(Color.parseColor("#EF4444"));
      cancelBtn.setPadding(a.dp(8), 0, a.dp(4), 0);
      row.addView(cancelBtn);
    } else {
      FrameLayout sendBtn = new FrameLayout(a);
      sendBtn.setContentDescription("发送");
      android.graphics.drawable.GradientDrawable sendBg =
          new android.graphics.drawable.GradientDrawable();
      sendBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
      sendBg.setColor(a.accent);
      sendBtn.setBackground(sendBg);
      sendBtn.setClickable(true);
      sendBtn.setFocusable(true);

      TextView sendIcon = a.icon("arrow-up", 18, Color.WHITE);
      sendIcon.setGravity(Gravity.CENTER);
      sendBtn.addView(sendIcon, new FrameLayout.LayoutParams(-1, -1));

      sendBtn.setOnClickListener(
          v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            String text = composer.getText().toString();
            q.follow = "";
            q.send(text);
            a.render();
          });
      LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(a.dp(36), a.dp(36));
      sendLp.leftMargin = a.dp(8);
      row.addView(sendBtn, sendLp);
    }
    column.addView(row);
    q.track(composer, value -> q.follow = value);
    footer.addView(column);
  }

  private final class Adapter extends BaseAdapter {
    JSONArray messages() {
      return q.messages();
    }

    @Override
    public int getCount() {
      boolean hasThinkingOrPartial = !partial.isEmpty() || a.active != null;
      return AssistantTimeline.count(messages()) + (hasThinkingOrPartial ? 1 : 0);
    }

    @Override
    public JSONObject getItem(int position) {
      return AssistantTimeline.at(messages(), position);
    }

    @Override
    public long getItemId(int position) {
      JSONObject message = getItem(position);
      String id =
          message == null ? "partial:" + sessionId : message.optString("id", "row:" + position);
      return AssistantTimeline.stableId(id);
    }

    @Override
    public boolean hasStableIds() {
      return true;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
      LinearLayout line =
          convertView instanceof LinearLayout ? (LinearLayout) convertView : a.row();
      line.removeAllViews();
      line.setPadding(a.dp(14), a.dp(6), a.dp(14), a.dp(6));
      JSONObject message =
          position < AssistantTimeline.count(messages()) ? getItem(position) : null;
      boolean mine = message != null && "user".equals(message.optString("role"));
      String content = message == null ? partial : message.optString("content");

      LinearLayout bubble = a.column();
      if (mine) {
        bubble.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10));
        bubble.setBackground(a.shape(a.accent, 16, 0));
      } else {
        bubble.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12));
        bubble.setBackground(a.shape(a.card, 16, a.border));

        LinearLayout assistantHdr = a.row();
        assistantHdr.setGravity(Gravity.CENTER_VERTICAL);
        assistantHdr.setPadding(0, 0, 0, a.dp(6));
        TextView aiTag = a.text("✦ AI 助手", 11, true);
        aiTag.setTextColor(a.accent);
        assistantHdr.addView(aiTag, new LinearLayout.LayoutParams(0, -2, 1));

        if (!content.isEmpty()) {
          TextView copyBtn = a.icon("document-text-outline", 14, a.subtle);
          copyBtn.setPadding(a.dp(6), a.dp(2), a.dp(2), a.dp(2));
          copyBtn.setOnClickListener(
              v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                android.content.ClipboardManager cb =
                    (android.content.ClipboardManager)
                        a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                if (cb != null) {
                  cb.setPrimaryClip(android.content.ClipData.newPlainText("ai_reply", content));
                  a.say("已复制内容");
                }
              });
          assistantHdr.addView(copyBtn);
        }
        bubble.addView(assistantHdr);
      }

      if (message == null && content.isEmpty()) {
        LinearLayout thinkingRow = a.row();
        thinkingRow.setGravity(Gravity.CENTER_VERTICAL);
        ProgressBar spinner = new ProgressBar(a, null, android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminate(true);
        thinkingRow.addView(spinner, new LinearLayout.LayoutParams(a.dp(18), a.dp(18)));

        TextView thinkingText = a.text("  ✦ AI 正在思考中…", 13, false);
        thinkingText.setTextColor(a.muted);
        thinkingRow.addView(thinkingText);
        bubble.addView(thinkingRow);
      } else {
        MarkdownView body = new MarkdownView(a, mine ? -1 : a.foreground, a.border);
        body.setMarkdown(content);
        bubble.addView(body);
      }

      if (message != null && isLatestAssistantMessage(position)) suggestions(bubble, message);
      if (message != null) confirmation(bubble, message);

      line.setGravity(mine ? Gravity.RIGHT : Gravity.LEFT);
      LinearLayout.LayoutParams bubbleLp = new LinearLayout.LayoutParams(mine ? -2 : -1, -2);
      if (mine) {
        bubbleLp.leftMargin = a.dp(44);
      } else {
        bubbleLp.rightMargin = a.dp(20);
      }
      line.addView(bubble, bubbleLp);
      return line;
    }

    private boolean isLatestAssistantMessage(int position) {
      if (!partial.isEmpty()) return false;
      JSONArray msgs = messages();
      int count = AssistantTimeline.count(msgs);
      if (count == 0 || position != count - 1) return false;
      JSONObject m = AssistantTimeline.at(msgs, position);
      return m != null && !"user".equals(m.optString("role"));
    }

    private void suggestions(LinearLayout bubble, JSONObject message) {
      JSONObject action = message.optJSONObject("action");
      if (action != null) {
        addQuestions(bubble, action.optJSONArray("choices"));
        addQuestions(bubble, action.optJSONArray("follow_up_questions"));
      }
      JSONObject result = message.optJSONObject("result");
      if (result != null) addQuestions(bubble, result.optJSONArray("follow_up_questions"));
      JSONObject queryResult = message.optJSONObject("query_result");
      if (queryResult != null)
        addQuestions(bubble, queryResult.optJSONArray("follow_up_questions"));
    }

    private void confirmation(LinearLayout bubble, JSONObject message) {
      JSONObject action = message.optJSONObject("action");
      if (action == null || !"confirm_lookup".equals(action.optString("kind")) || a.session == null)
        return;
      JSONObject proposal = a.session.optJSONObject("pending_lookup");
      LinearLayout card = a.column();
      card.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
      card.setBackground(a.shape(a.card, 10, a.border));
      JSONObject lookup = action.optJSONObject("lookup");
      String targetWord =
          lookup == null
              ? (proposal == null ? "" : proposal.optString("target"))
              : lookup.optString("target", proposal == null ? "" : proposal.optString("target"));
      card.addView(a.text("确认查询 · " + targetWord, 13, true));
      boolean matchesProposal =
          proposal != null && message.optString("id").equals(proposal.optString("message_id"));
      boolean valid =
          matchesProposal
              && q.pending() != null
              && proposal.optInt("revision", -1) == a.session.optInt("revision");
      if (valid) {
        String contextQuote = proposal.optString("context_quote", "");
        String userReq = proposal.optString("user_requirement", "");
        if (!contextQuote.isEmpty() || !userReq.isEmpty()) {
          String detail =
              (contextQuote.isEmpty() ? "" : "原句：" + contextQuote + "\n")
                  + (userReq.isEmpty() ? "" : "要求：" + userReq);
          TextView detailView = a.text(detail.trim(), 12, false);
          detailView.setTextColor(a.muted);
          detailView.setPadding(0, a.dp(4), 0, a.dp(6));
          card.addView(detailView);
        }

        LinearLayout btnRow = a.row();
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        Button confirmBtn =
            a.primaryButton(
                "确认查询",
                v -> {
                  v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                  q.lookup(proposal);
                });
        btnRow.addView(confirmBtn, new LinearLayout.LayoutParams(0, -2, 1));

        Button editBtn =
            a.button(
                "修改目标",
                v -> {
                  v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                  if (composer != null) {
                    composer.setText("我想修改查询目标为：" + targetWord);
                    composer.requestFocus();
                    composer.setSelection(composer.getText().length());
                  }
                });
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(0, -2, 1);
        editLp.leftMargin = a.dp(8);
        btnRow.addView(editBtn, editLp);
        card.addView(btnRow);
      } else {
        TextView stale = a.text("此确认已失效，请重新提交问题。", 12, false);
        stale.setTextColor(a.muted);
        card.addView(stale);
      }
      bubble.addView(card);
    }
  }
}
