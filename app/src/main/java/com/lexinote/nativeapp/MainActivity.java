package com.lexinote.nativeapp;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import com.lexinote.nativeapp.storage.LibraryStore;
import com.lexinote.nativeapp.storage.JsonStoreBackend;
import com.lexinote.nativeapp.storage.contract.LibraryCommand;
import com.lexinote.nativeapp.storage.contract.CommitResult;

public final class MainActivity extends Activity {
  Library library;
  Repository repository;
  public LibraryStore store;
  public long dataRevision, uiEpoch;
  long libraryRevision, libraryGeneration;
  SecureSettings secure;
  LinearLayout root, body, nav, footer;
  ScrollView scroll;
  FrameLayout contentContainer;
  Typeface iconTypeface;
  int page = 0;
  String screen = "tabs";
  JSONObject session;
  JSONObject committed;
  JSONObject activeVocabulary;
  Uri image, originalImage, pendingCameraUri;
  ImageEditorState imageEditorState = ImageEditorState.initial();
  JSONObject imageRegion;
  ConversationTitles titles;
  int aiRequestToken = 0;
  AiClient active;
  String requestFailureLocation = "";
  final ExecutorService worker = Executors.newSingleThreadExecutor();
  final Handler handler = new Handler(Looper.getMainLooper());
  QueryUi query;
  NotebookUi notebooks;
  ReviewUi review;
  SettingsUi settingsUi;
  public PronunciationEngine pronunciationEngine;
  private android.speech.tts.TextToSpeech systemVoice;
  private boolean systemVoiceInitializing;
  private boolean systemVoiceReady;
  private int pronunciationRequest;
  private String pendingSystemSpeech;
  int background, foreground, muted, card, accent, inputBg, border, subtle;
  boolean isDark;
  float scale = 1;
  File pendingExport;
  boolean recoveryOnly;
  String loadFailure;
  Bundle pendingStartupState;
  int lastPage = -1;
  String lastScreen = "";
  int savedScrollX = 0;
  int savedScrollY = 0;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    pendingStartupState = state;
    repository = new Repository(this);
    secure = new SecureSettings(this);
    titles = new ConversationTitles(this);
    pronunciationEngine = new SherpaPronunciationEngine(this);
    initializeSystemVoice();
    store = LibraryStore.getInstance(this);
    store.bind(this);
    store.loadLibrary(this, result -> {
      if (!alive()) return;
      if (result.isSuccess()) { library = result.getSnapshot(); libraryRevision = result.getAppliedRevision(); libraryGeneration = result.getAppliedGeneration(); }
      else {
        library = new Library();
        recoveryOnly = true;
        loadFailure = result.getMessage();
      }
      initializeScreen(state);
    });
  }

  private void initializeScreen(Bundle state) {
    pendingStartupState = null;
    query = new QueryUi(this);
    notebooks = new NotebookUi(this);
    review = new ReviewUi(this);
    settingsUi = new SettingsUi(this);
    settingsUi.models.restoreState(state);
    if (state != null && !recoveryOnly) {
      page = Math.max(0, Math.min(4, state.getInt("page", 0)));
      if (page == 1) page = 0;
      screen = state.getString("screen", "tabs");
      String sessionId = state.getString("session");
      JSONObject saved = sessionId == null ? null : library.find("conversations", sessionId);
      session = saved == null ? null : Library.copy(saved);
      String activeVocabId = state.getString("activeVocabulary");
      if (activeVocabId != null && library != null) {
        activeVocabulary = library.find("vocabularies", activeVocabId);
      }
      if (screen.equals("vocabulary")) {
        if (activeVocabulary == null) screen = "tabs";
      } else if (session == null) screen = "tabs";
      query.draft = state.getString("draft", "");
      query.context = state.getString("context", "");
      query.follow = state.getString("follow", "");
      query.guessTarget = state.getString("guessTarget", "");
      query.guessContext = state.getString("guessContext", "");
      String uri = state.getString("image");
      image = uri == null ? null : Uri.parse(uri);
      String original = state.getString("originalImage", uri);
      originalImage = original == null ? null : Uri.parse(original);
      String camera = state.getString("pendingCamera");
      pendingCameraUri = camera == null ? null : Uri.parse(camera);
      try {
        imageEditorState =
            ImageEditorState.fromJson(new JSONObject(state.getString("imageEditorState", "{}")));
      } catch (Exception ignored) {
        imageEditorState = ImageEditorState.initial();
      }
      try {
        String region = state.getString("imageRegion");
        imageRegion = region == null ? null : new JSONObject(region);
      } catch (Exception ignored) {
        imageRegion = null;
      }
      query.homeMode = state.getString("homeMode", "text");
      query.guessMode = state.getString("guessMode", "image");
      query.organizing = state.getBoolean("organizing");
      if (notebooks != null) {
        notebooks.activeCollectionId = state.getString("notebook_active_collection", null);
      }
      if (review != null) {
        review.phase = state.getString("review_phase", "dashboard");
        review.notebook = state.getString("review_notebook", null);
        review.reverse = state.getBoolean("review_reverse", false);
        review.revealed = state.getBoolean("review_revealed", false);
        review.batchSize = state.getInt("review_batch_size", 10);
        ArrayList<String> tags = state.getStringArrayList("review_selected_tags");
        if (tags != null) {
          review.selectedTags.clear();
          review.selectedTags.addAll(tags);
        }
        review.activePlanId = state.getString("review_active_plan_id", null);
      }
    }
    if (state != null && state.getString("export") != null) {
      File candidate = new File(getCacheDir(), new File(state.getString("export")).getName());
      if (candidate.getName().startsWith("export-") && candidate.isFile())
        pendingExport = candidate;
    }
    if (Build.VERSION.SDK_INT >= 33)
      getOnBackInvokedDispatcher()
          .registerOnBackInvokedCallback(
              android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
    review.restoreState(state);
    render();

    Intent intent = getIntent();
    if (intent != null && intent.hasExtra("import_fixture_path")) {
      String path = intent.getStringExtra("import_fixture_path");
      intent.removeExtra("import_fixture_path");
      restoreFromFile(new File(path));
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (intent != null && intent.hasExtra("import_fixture_path")) {
      String path = intent.getStringExtra("import_fixture_path");
      intent.removeExtra("import_fixture_path");
      restoreFromFile(new File(path));
    }
  }

  void restoreFromFile(File file) {
    cancel();
    ProgressDialog progress = ProgressDialog.show(this, "", "正在恢复备份…", true, false);
    worker.execute(() -> {
      try (InputStream in = new FileInputStream(file)) {
        BackupStreamCodec.Prepared prepared = BackupStreamCodec.decode(in, getCacheDir());
        handler.post(() -> {
          progress.dismiss();
          if (!alive()) { prepared.close(); return; }
          applyRestore(prepared);
        });
      } catch (Exception e) {
        handler.post(() -> { progress.dismiss(); if (alive()) error(e); });
      }
    });
  }

  void palette() {
    String mode = library.settings().optString("theme_mode", "system");
    boolean dark =
        mode.equals("dark")
            || mode.equals("system")
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
    isDark = dark;
    background = Color.parseColor(dark ? "#0F172A" : "#F8FAFC");
    foreground = Color.parseColor(dark ? "#F8FAFC" : "#0F172A");
    muted = Color.parseColor(dark ? "#94A3B8" : "#475569");
    subtle = Color.parseColor(dark ? "#64748B" : "#94A3B8");
    card = Color.parseColor(dark ? "#1E293B" : "#FFFFFF");
    inputBg = Color.parseColor(dark ? "#0F172A" : "#F1F5F9");
    border = Color.parseColor(dark ? "#334155" : "#E2E8F0");
    String color = library.settings().optString("accent_color", "#10B981");
    accent = Color.parseColor(color.matches("#[0-9a-fA-F]{6}") ? color : "#10B981");
    String size = library.settings().optString("font_size", "medium");
    scale = size.equals("large") ? 1.15f : size.equals("small") ? .9f : 1;
    getWindow().setStatusBarColor(card);
    getWindow().setNavigationBarColor(card);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            dark
                ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
  }

  void render() {
    uiEpoch++;
    if (review != null) { if (page == 3 && screen.equals("tabs") && "active".equals(review.phase)) review.resumeTimer(); else review.pauseTimer(); }
    if (library == null) return;
    if (query != null && query.assistantView != null) {
      query.assistantView.saveAnchor();
      query.assistantView.destroy();
      query.assistantView = null;
    }
    palette();
    if (scroll != null && page == lastPage && Objects.equals(screen, lastScreen)) {
      savedScrollX = scroll.getScrollX();
      savedScrollY = scroll.getScrollY();
    } else {
      savedScrollX = 0;
      savedScrollY = 0;
    }
    lastPage = page;
    lastScreen = screen;
    if (root == null) {
      root = column();
      root.setBackgroundColor(card);
      root.setOnApplyWindowInsetsListener(
          (view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
              android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
              left = bars.left;
              top = bars.top;
              right = bars.right;
              bottom = bars.bottom;
              if (insets.isVisible(WindowInsets.Type.ime()))
                bottom = Math.max(bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
            } else {
              left = insets.getSystemWindowInsetLeft();
              top = insets.getSystemWindowInsetTop();
              right = insets.getSystemWindowInsetRight();
              bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, bottom);
            return insets;
          });
      setContentView(root);
      root.requestApplyInsets();
    } else {
      root.removeAllViews();
      root.setBackgroundColor(card);
    }
    if (recoveryOnly) {
      root.setPadding(dp(16), dp(60), dp(16), dp(16));
      root.addView(text("恢复本地数据", 22, true));
      root.addView(text("数据读取失败，原文件保留。可以选择有效备份恢复；确认恢复时会额外保留损坏文件副本。\n" + loadFailure, 16, false));
      Button restoreBtn = button("选择 ZIP / JSON 备份恢复", v -> restore());
      LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, dp(44));
      rLp.topMargin = dp(16);
      rLp.bottomMargin = dp(10);
      root.addView(restoreBtn, rLp);
      Button exitBtn = button("退出", v -> finish());
      LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(-1, dp(44));
      root.addView(exitBtn, eLp);
      return;
    }
    if (!(screen.equals("tabs") && (page == 2 || page == 3))) {
      renderHeader();
    }
    scroll = new ScrollView(this);
    scroll.setBackgroundColor(background);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    scroll.setFocusableInTouchMode(true);
    body = column();
    body.setTag("page-body");
    int padding = page == 0 || !screen.equals("tabs") ? 14 : 16;
    body.setPadding(dp(padding), dp(14), dp(padding), dp(32));
    body.setBackgroundColor(background);
    scroll.addView(body);
    contentContainer = new FrameLayout(this);
    contentContainer.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    root.addView(contentContainer, new LinearLayout.LayoutParams(-1, 0, 1));
    footer = column();
    footer.setTag("fixed-footer");
    footer.setBackground(shape(card, 0, 0));
    footer.setVisibility(View.GONE);
    if (screen.equals("assistant")) query.assistant();
    else if (screen.equals("result")) query.result();
    else if (screen.equals("vocabulary")) notebooks.renderVocabularyDetail();
    else
      switch (page) {
        case 0:
          query.home();
          break;
        case 1:
          query.guess();
          break;
        case 2:
          notebooks.show();
          break;
        case 3:
          review.show();
          break;
        default:
          settingsUi.show();
      }
    root.addView(footer, new LinearLayout.LayoutParams(-1, -2));
    if (screen.equals("tabs") && !(page == 3 && review != null && "active".equals(review.phase))) renderNavigation();
    else nav = null;
    if (savedScrollY > 0 || savedScrollX > 0) {
      final int restoreX = savedScrollX;
      final int restoreY = savedScrollY;
      scroll.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
        @Override
        public void onGlobalLayout() {
          scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
          scroll.scrollTo(restoreX, restoreY);
        }
      });
      scroll.post(() -> scroll.scrollTo(restoreX, restoreY));
    }
  }

  void renderHeader() {
    LinearLayout bar = row();
    bar.setTag("page-header");
    bar.setPadding(dp(16), 0, dp(16), 0);
    bar.setBackgroundColor(card);
    String title;
    if (!screen.equals("tabs")) {
      TextView back = icon("chevron-back", 24, foreground);
      back.setContentDescription("返回");
      back.setOnClickListener(
          v -> {
            screen = "tabs";
            render();
          });
      bar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(48)));
      JSONObject result = session == null ? null : session.optJSONObject("query_results");
      String sessionTitle = session != null ? query.title(session) : null;
      title =
          screen.equals("assistant")
              ? (sessionTitle != null && !sessionTitle.trim().isEmpty() ? sessionTitle : "AI 助手")
              : screen.equals("vocabulary")
                  ? (activeVocabulary != null ? activeVocabulary.optString("content", "词汇详情") : "词汇详情")
                  : result == null ? "查询信息" : result.optString("word", "查询信息");
    } else title = new String[] {"LexiNote · 查询和记录", "语境猜词辅助", "笔记管理与词库", "复习中心", "设置与数据管理"}[page];
    TextView heading = text(title, 17, true);
    heading.setSingleLine(true);
    heading.setEllipsize(TextUtils.TruncateAt.END);
    heading.setTag("page-title");
    boolean homeHeader = screen.equals("tabs") && page == 0;
    bar.addView(
        heading, new LinearLayout.LayoutParams(homeHeader ? -2 : 0, -2, homeHeader ? 0 : 1));
    if (screen.equals("vocabulary") && activeVocabulary != null) {
      Button editBtn = button("编辑", v -> notebooks.editWord(activeVocabulary));
      bar.addView(editBtn);
    }
    if (screen.equals("assistant")) {
      Button save =
          button(
              session != null && query.saved(session) ? "修改名称" : "保存会话",
              v -> query.saveConversation());
      save.setEnabled(
          session != null
              && session.optJSONArray("ai_messages") != null
              && session.optJSONArray("ai_messages").length() > 0);
      LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(-2, -2);
      saveLp.rightMargin = dp(8);
      bar.addView(save, saveLp);

      TextView drawerBtn = icon("chatbubble-ellipses-outline", 22, foreground);
      drawerBtn.setPadding(dp(6), dp(4), dp(4), dp(4));
      drawerBtn.setContentDescription("会话列表");
      drawerBtn.setOnClickListener(
          v -> {
            if (query.assistantView != null) query.assistantView.toggleDrawer();
          });
      bar.addView(drawerBtn);
    }
    if (screen.equals("tabs") && page == 0) {
      TextView version = text("v1.5", 11, true);
      version.setTextColor(accent);
      version.setPadding(dp(6), dp(3), dp(6), dp(3));
      version.setBackground(shape((accent & 0xFFFFFF) | 0x1A000000, 6, 0));
      LinearLayout.LayoutParams versionLp = new LinearLayout.LayoutParams(-2, -2);
      versionLp.leftMargin = dp(8);
      bar.addView(version, versionLp);
      bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
      LinearLayout recent = row();
      recent.setGravity(Gravity.CENTER);
      recent.addView(icon("time-outline", 17, foreground));
      TextView count = text(" 最近查询 (" + library.array("recent_queries").length() + ")", 12, false);
      count.setTextColor(foreground);
      count.setTextIsSelectable(false);
      recent.addView(count);
      recent.setContentDescription("最近查询");
      recent.setOnClickListener(v -> query.showRecent());
      bar.addView(recent, new LinearLayout.LayoutParams(-2, dp(48)));
    }
    root.addView(
        bar, new LinearLayout.LayoutParams(-1, dp(screen.equals("tabs") && page == 0 ? 52 : 56)));
    View line = new View(this);
    line.setBackgroundColor(border);
    root.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
  }

  void renderNavigation() {
    nav = row();
    nav.setTag("bottom-navigation");
    nav.setBackgroundColor(card);
    // "猜词辅助" is temporarily hidden per user instruction, bottom nav layout fills the vacant space
    String[] labels = {"查询记录", "笔记", "复习", "我的"};
    String[] glyphs = {"search", "book-outline", "repeat-outline", "settings-outline"};
    int[] targetPages = {0, 2, 3, 4};
    for (int i = 0; i < labels.length; i++) {
      final int targetPage = targetPages[i];
      Button b =
          button(
              labels[i],
              v -> {
                page = targetPage;
                render();
              });
      b.setTag("tab-" + i);
      b.setTextSize(11.5f * scale);
      b.setTextColor(page == targetPage ? accent : subtle);
      b.setMinWidth(0);
      b.setMinimumWidth(0);
      b.setPadding(0, dp(6), 0, dp(7));
      b.setCompoundDrawablePadding(dp(2));
      b.setCompoundDrawables(
          null, new GlyphDrawable(glyphs[i], 24, page == targetPage ? accent : subtle), null, null);
      b.setBackground(shape(Color.TRANSPARENT, 0, 0));
      nav.addView(b, new LinearLayout.LayoutParams(0, dp(62), 1));
    }
    View line = new View(this);
    line.setBackgroundColor(border);
    root.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    root.addView(nav, new LinearLayout.LayoutParams(-1, dp(62)));
  }

  int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  void passiveLabels(View view) {
    if (view instanceof TextView && !(view instanceof Button) && !view.hasOnClickListeners())
      ((TextView) view).setTextIsSelectable(false);
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) passiveLabels(group.getChildAt(i));
    }
  }

  TextView text(String value, int size, boolean bold) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextColor(bold ? foreground : muted);
    view.setTextSize(size * scale);
    if (bold) view.setTypeface(null, Typeface.BOLD);
    view.setTextIsSelectable(true);
    return view;
  }

  GradientDrawable shape(int color, int radius, int stroke) {
    GradientDrawable value = new GradientDrawable();
    value.setColor(color);
    value.setCornerRadius(dp(radius));
    if (stroke != 0) value.setStroke(dp(1), stroke);
    return value;
  }

  Button button(String label, View.OnClickListener click) {
    Button value = new Button(this);
    value.setText(label);
    value.setAllCaps(false);
    value.setTextSize(14 * scale);
    value.setTextColor(accent);
    value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    value.setMinHeight(dp(36));
    value.setMinimumHeight(dp(36));
    value.setMinWidth(0);
    value.setMinimumWidth(0);
    value.setElevation(0);
    value.setStateListAnimator(null);
    value.setBackgroundTintList(null);
    value.setBackground(
        new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf((accent & 0xFFFFFF) | 0x26000000),
            shape(Color.TRANSPARENT, 10, 0),
            null));
    value.setPadding(dp(10), dp(8), dp(10), dp(8));
    value.setOnClickListener(click);
    value.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
    return value;
  }

  Button primaryButton(String label, View.OnClickListener click) {
    Button value = button(label, click);
    value.setTextColor(Color.WHITE);
    value.setBackground(shape(accent, 10, 0));
    value.setMinHeight(dp(44));
    value.setMinimumHeight(dp(44));
    value.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(44)));
    return value;
  }

  Button outlineButton(String label, View.OnClickListener click) {
    Button value = button(label, click);
    value.setTextColor(foreground);
    value.setBackground(shape(Color.TRANSPARENT, 10, border));
    value.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(44)));
    return value;
  }

  Button chip(String label, boolean selected, View.OnClickListener click) {
    Button value = button(label, click);
    value.setTextSize(12 * scale);
    value.setTextColor(selected ? accent : muted);
    value.setBackground(
        shape(
            selected ? (accent & 0xFFFFFF) | 0x1A000000 : inputBg, 10, selected ? accent : border));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
    lp.rightMargin = dp(8);
    value.setLayoutParams(lp);
    return value;
  }

  EditText input(String hint, String value, boolean multiline) {
    EditText edit = new EditText(this);
    edit.setHint(hint);
    edit.setText(value);
    edit.setTextColor(foreground);
    edit.setHintTextColor(subtle);
    edit.setTextSize(14 * scale);
    edit.setSingleLine(!multiline);
    edit.setMinHeight(dp(44));
    if (multiline) {
      edit.setMinLines(2);
      edit.setGravity(Gravity.TOP);
    }
    edit.setPadding(dp(12), dp(9), dp(12), dp(9));
    edit.setBackground(shape(inputBg, 10, border));
    edit.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
    return edit;
  }

  LinearLayout card(String title) {
    LinearLayout value = column();
    value.setPadding(dp(14), dp(14), dp(14), dp(14));
    value.setBackground(shape(card, 16, border));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(14);
    value.setLayoutParams(lp);
    if (title != null) {
      TextView heading = text(title, 16, true);
      LinearLayout.LayoutParams h = new LinearLayout.LayoutParams(-1, -2);
      h.bottomMargin = dp(12);
      value.addView(heading, h);
    }
    body.addView(value);
    return value;
  }

  LinearLayout fixedFooter() {
    footer.setVisibility(View.VISIBLE);
    footer.setPadding(dp(14), dp(10), dp(14), dp(10));
    return footer;
  }

  View gap(int height) {
    View view = new View(this);
    view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height)));
    return view;
  }

  Typeface icons() {
    if (iconTypeface == null)
      iconTypeface = Typeface.createFromAsset(getAssets(), "fonts/Ionicons.ttf");
    return iconTypeface;
  }

  TextView icon(String name, int size, int color) {
    TextView value = new TextView(this);
    value.setTypeface(icons());
    value.setText(new String(Character.toChars(IconGlyphs.VALUES.getOrDefault(name, 0xF55F))));
    value.setTextSize(size);
    value.setTextColor(color);
    value.setGravity(Gravity.CENTER);
    value.setIncludeFontPadding(false);
    return value;
  }

  final class GlyphDrawable extends android.graphics.drawable.Drawable {
    final String glyph;
    final int size, color;

    GlyphDrawable(String name, int size, int color) {
      this.glyph = new String(Character.toChars(IconGlyphs.VALUES.getOrDefault(name, 0xF55F)));
      this.size = dp(size);
      this.color = color;
      setBounds(0, 0, this.size, this.size);
    }

    public void draw(android.graphics.Canvas canvas) {
      android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
      p.setTypeface(icons());
      p.setTextSize(size);
      p.setColor(color);
      p.setTextAlign(android.graphics.Paint.Align.CENTER);
      android.graphics.Paint.FontMetrics fm = p.getFontMetrics();
      canvas.drawText(glyph, size / 2f, (size - fm.ascent - fm.descent) / 2f, p);
    }

    public void setAlpha(int alpha) {}

    public void setColorFilter(android.graphics.ColorFilter filter) {}

    public int getOpacity() {
      return android.graphics.PixelFormat.TRANSLUCENT;
    }
  }

  void say(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }

  private void initializeSystemVoice() {
    systemVoiceInitializing = true;
    systemVoice = new android.speech.tts.TextToSpeech(this, status -> handler.post(() -> {
      if (!alive() || systemVoice == null) return;
      systemVoiceInitializing = false;
      systemVoiceReady = status == android.speech.tts.TextToSpeech.SUCCESS
          && systemVoice.setLanguage(java.util.Locale.US) >= android.speech.tts.TextToSpeech.LANG_AVAILABLE;
      if (systemVoiceReady) {
        systemVoice.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
          @Override public void onStart(String id) {}
          @Override public void onDone(String id) {}
          @Override public void onError(String id) {
            handler.post(() -> {
              if (alive() && Integer.toString(pronunciationRequest).equals(id))
                say("系统英语语音不可用，请检查设备的文字转语音设置");
            });
          }
        });
      }
      String pending = pendingSystemSpeech;
      pendingSystemSpeech = null;
      if (pending != null) speakWithSystemVoice(pending, pronunciationRequest);
    }));
  }

  private void speakWithSystemVoice(String word, int request) {
    if (!alive() || request != pronunciationRequest) return;
    if (systemVoiceInitializing) {
      pendingSystemSpeech = word;
      return;
    }
    if (!systemVoiceReady || systemVoice.speak(word, android.speech.tts.TextToSpeech.QUEUE_FLUSH,
        null, Integer.toString(request)) != android.speech.tts.TextToSpeech.SUCCESS)
      say("系统英语语音不可用，请检查设备的文字转语音设置");
  }

  public void pronounce(String word) {
    String clean = SherpaPronunciationEngine.cleanWord(word);
    if (clean.isEmpty()) return;
    int request = ++pronunciationRequest;
    pendingSystemSpeech = null;
    if (systemVoiceReady) systemVoice.stop();
    if (pronunciationEngine != null && pronunciationEngine.isReady()) {
      pronunciationEngine.pronounce(clean, null, null, error -> speakWithSystemVoice(clean, request));
    } else {
      speakWithSystemVoice(clean, request);
    }
  }

  void styleDialog(AlertDialog dialog) {
    if (dialog == null) return;
    android.view.Window w = dialog.getWindow();
    if (w != null) {
      android.graphics.drawable.GradientDrawable gd =
          new android.graphics.drawable.GradientDrawable();
      gd.setColor(card);
      gd.setCornerRadius(dp(18));
      gd.setStroke(dp(1), border);
      w.setBackgroundDrawable(
          new android.graphics.drawable.InsetDrawable(gd, dp(20), dp(20), dp(20), dp(20)));
      w.setElevation(dp(16));
    }
    Button pos = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
    if (pos != null) {
      pos.setTextColor(accent);
      pos.setTypeface(null, android.graphics.Typeface.BOLD);
    }
    Button neg = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
    if (neg != null) {
      neg.setTextColor(muted);
    }
    Button neu = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
    if (neu != null) {
      neu.setTextColor(muted);
    }
    TextView titleView = dialog.findViewById(android.R.id.title);
    if (titleView == null) {
      int alertTitleId = getResources().getIdentifier("alertTitle", "id", "android");
      if (alertTitleId > 0) titleView = dialog.findViewById(alertTitleId);
    }
    if (titleView != null) {
      titleView.setTextColor(foreground);
      titleView.setTypeface(null, android.graphics.Typeface.BOLD);
    }
    TextView messageView = dialog.findViewById(android.R.id.message);
    if (messageView != null) {
      messageView.setTextColor(foreground);
      messageView.setLineSpacing(dp(2), 1.15f);
    }
    ListView lv = dialog.getListView();
    if (lv != null) {
      lv.setDivider(new android.graphics.drawable.ColorDrawable(border));
      lv.setDividerHeight(dp(1));
    }
  }

  AlertDialog showDialog(AlertDialog.Builder builder) {
    AlertDialog dialog = builder.create();
    dialog.setOnShowListener(d -> styleDialog(dialog));
    dialog.show();
    styleDialog(dialog);
    return dialog;
  }

  AlertDialog showDialog(AlertDialog dialog) {
    dialog.setOnShowListener(d -> styleDialog(dialog));
    dialog.show();
    styleDialog(dialog);
    return dialog;
  }

  void styleDestructive(AlertDialog dialog) {
    if (dialog == null) return;
    Button pos = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
    if (pos != null) {
      pos.setTextColor(Color.parseColor("#EF4444"));
    }
  }

  LinearLayout dialogInput(View content) {
    LinearLayout box = column();
    box.setPadding(dp(22), dp(10), dp(22), dp(6));
    box.addView(content, new LinearLayout.LayoutParams(-1, -2));
    return box;
  }

  void error(Exception e) {
    if (!alive()) return;
    TokenDanceRecovery recovery = TokenDanceRecovery.find(e);
    if (recovery != null) {
      AlertDialog.Builder b =
          new AlertDialog.Builder(this)
              .setTitle("TokenDance 服务")
              .setMessage(recovery.getMessage())
              .setNegativeButton(
                  recovery.action == TokenDanceProtocol.RecoveryAction.API_KEY_QUOTA
                      ? "等待刷新"
                      : "稍后",
                  null)
              .setPositiveButton(
                  recovery.action == TokenDanceProtocol.RecoveryAction.TOP_UP_BALANCE
                      ? "去充值"
                      : "重新连接",
                  (dialog, which) ->
                      settingsUi.models.openTokenDance(
                          recovery.action == TokenDanceProtocol.RecoveryAction.TOP_UP_BALANCE
                              ? "recharge"
                              : "authorize"));
      showDialog(b);
      return;
    }
    AlertDialog.Builder b =
        new AlertDialog.Builder(this)
            .setTitle("操作未完成")
            .setMessage(e.getMessage() == null ? e.toString() : e.getMessage())
            .setPositiveButton("知道了", null);
    showDialog(b);
  }

  /** Compatibility entry point; use persistAsync when UI behavior depends on confirmation. */
  void persist() { persistAsync(null); }

  private static final class PendingSave {
    final Runnable mutation;
    final java.util.function.Consumer<Boolean> callback;
    PendingSave(Runnable mutation, java.util.function.Consumer<Boolean> callback) {
      this.mutation = mutation; this.callback = callback;
    }
  }
  private final ArrayDeque<PendingSave> pendingSaves = new ArrayDeque<>();
  private boolean savingSnapshot;

  void persistAsync(java.util.function.Consumer<Boolean> callback) {
    enqueueSave(null, callback);
  }

  private void enqueueSave(Runnable mutation, java.util.function.Consumer<Boolean> callback) {
    if (recoveryOnly) { if (callback != null) callback.accept(false); return; }
    if (mutation != null) dataRevision++;
    pendingSaves.addLast(new PendingSave(mutation, callback));
    runNextSave();
  }

  private void runNextSave() {
    if (savingSnapshot || pendingSaves.isEmpty()) return;
    final PendingSave job = pendingSaves.removeFirst();
    savingSnapshot = true;
    try {
      if (job.mutation != null) job.mutation.run();
      final JSONObject snapshot = library.snapshot();
      final String sessionId = session == null ? null : session.optString("id");
      store.dispatch(this, LibraryCommand.persistSnapshot(libraryGeneration, libraryRevision, snapshot, System.currentTimeMillis()), result -> {
        if (result.isSuccess()) {
          committed = snapshot; dataRevision++; libraryRevision = result.getAppliedRevision(); libraryGeneration = result.getAppliedGeneration();
          savingSnapshot = false;
          if (job.mutation != null) render();
          if (job.callback != null) job.callback.accept(true);
          runNextSave();
        } else failQueuedSave(job, new IOException("后台写入失败：" + result.getMessage()), sessionId);
      });
    } catch (Exception e) {
      String sessionId = session == null ? null : session.optString("id");
      failQueuedSave(job, new IOException("写入失败：" + e.getMessage(), e), sessionId);
    }
  }

  private void failQueuedSave(PendingSave job, Exception cause, String sessionId) {
    restoreAfterFailedSave(cause, sessionId, ignored -> {
      // Waiting mutations can refer to objects from the discarded view; reject rather than replay them.
      ArrayList<PendingSave> rejected = new ArrayList<>(pendingSaves);
      pendingSaves.clear();
      if (job.callback != null) job.callback.accept(false);
      for (PendingSave waiting : rejected) if (waiting.callback != null) waiting.callback.accept(false);
      savingSnapshot = false;
      runNextSave();
    });
  }

  void restoreAfterFailedSave(Exception cause, String sessionId, java.util.function.Consumer<Boolean> callback) {
    restoreAfterFailedSave(cause, sessionId, () -> true, callback);
  }

  void restoreAfterFailedSave(Exception cause, String sessionId,
      java.util.function.BooleanSupplier sameRequest, java.util.function.Consumer<Boolean> callback) {
    store.loadLibrary(this, loaded -> {
      boolean current = sameRequest.getAsBoolean();
      if (loaded.isSuccess() && loaded.getSnapshot() != null) {
        library = loaded.getSnapshot(); dataRevision++; libraryRevision = loaded.getAppliedRevision(); libraryGeneration = loaded.getAppliedGeneration();
        if (current) session = sessionId == null ? null : library.find("conversations", sessionId);
      } else {
        // The last committed state cannot be verified; stop further writes until recovery.
        recoveryOnly = true; loadFailure = "保存失败且无法重新读取已提交数据";
      }
      if (current) error(cause);
      if (current || recoveryOnly) render();
      if (callback != null) callback.accept(false);
    });
  }

  void changeAsync(Runnable action, java.util.function.Consumer<Boolean> callback) {
    enqueueSave(action, callback);
  }

  public void dispatch(LibraryCommand command, java.util.function.Consumer<CommitResult> callback) {
    if (store != null) {
      store.dispatch(this, command, result -> {
        if (result.isSuccess()) {
          dataRevision++; libraryRevision = result.getAppliedRevision(); libraryGeneration = result.getAppliedGeneration();
          if (result.getSnapshot() != null) this.library = result.getSnapshot();
        }
        if (callback != null) {
          callback.accept(result);
        }
      });
    }
  }

  void change(Runnable action) { changeAsync(action, null); }

  interface Work {
    JSONObject run(AiClient client) throws Exception;
  }

  interface Done {
    void accept(JSONObject result) throws Exception;
  }

  void request(Work work, Done done) {
    request(work, done, true);
  }

  void request(Work work, Done done, boolean modal) {
    request(work, done, modal, true, false);
  }

  /** The result handler commits its own session delta. */
  void requestSessionResult(Work work, Done done, boolean modal) {
    request(work, done, modal, false, false);
  }

  void requestQuietly(Work work, Done done) {
    request(work, done, false, false, true);
  }

  private void request(Work work, Done done, boolean modal, boolean persistResult, boolean quiet) {
    if (active != null) {
      say("正在请求，可取消后重新发送");
      return;
    }
    final int token = ++aiRequestToken;
    requestFailureLocation = "";
    try {
      active = new AiClient(secure.getProvider(), secure.getApiKey());
    } catch (Exception e) {
      rememberRequestFailure(e);
      if (!quiet) error(e);
      return;
    }
    AiClient c = active;
    final JSONObject requestSession = session;
    ProgressDialog progress = modal ? new ProgressDialog(this) : null;
    if (progress != null) {
      progress.setMessage("AI 正在思考…");
      progress.setCancelable(true);
      progress.setButton(DialogInterface.BUTTON_NEGATIVE, "取消", (d, w) -> cancel());
      progress.setOnCancelListener(d -> cancel());
      progress.show();
    } else if (!quiet) render();
    worker.execute(
        () -> {
          try {
            JSONObject value = work.run(c);
            handler.post(
                () -> {
                  if (progress != null) progress.dismiss();
                  if (token != aiRequestToken || !alive()) return;
                  active = null;
                  query.partialReply = "";
                  try {
                    done.accept(value);
                    if (persistResult)
                      persistAsync(saved -> { if (saved) { if (session != null) titles.schedule(session); render(); } });
                    else render();
                  } catch (Exception e) {
                    rememberRequestFailure(e);
                    error(e);
                  }
                });
          } catch (Exception e) {
            handler.post(
                () -> {
                  if (progress != null) progress.dismiss();
                  if (token != aiRequestToken || !alive()) return;
                  active = null;
                  query.partialReply = "";
                  rememberRequestFailure(e);
                  if (persistResult && session != null && session == requestSession) {
                    Library.put(session, "last_error", e.getMessage());
                    library.touchSession(session, true);
                    persist();
                  }
                  if (quiet) {
                    render();
                    return;
                  }
                  if (modal) error(e);
                  else {
                    say(e.getMessage() == null ? "请求失败，可重试" : e.getMessage());
                    render();
                    if (TokenDanceRecovery.find(e) != null) error(e);
                  }
                });
          }
        });
  }

  private void rememberRequestFailure(Exception error) {
    StackTraceElement[] trace = error.getStackTrace();
    requestFailureLocation = error.getClass().getSimpleName()
        + (trace.length == 0 ? "" : " @ " + trace[0]);
  }

  void cancel() {
    aiRequestToken++;
    if (active != null) active.cancel();
    active = null;
    if (query != null) query.partialReply = "";
  }

  String imageData() throws Exception {
    return image == null ? null : ImageTools.dataUrl(this, image);
  }

  void clearImage() {
    image = null;
    originalImage = null;
    imageEditorState = ImageEditorState.initial();
    imageRegion = null;
    invalidateDraft();
    if (screen.equals("assistant") && session != null) {
      query.storeImageSource(session);
      library.touchSession(session, false);
      persist();
    }
  }

  void chooseImage() {
    AlertDialog.Builder b =
        new AlertDialog.Builder(this)
            .setTitle("图片选项")
            .setItems(
                new String[] {"选择图片", "拍照（完整照片）", "编辑当前图片", "移除图片"},
                (dialog, which) -> {
                  if (which == 0) chooseImage(false);
                  else if (which == 1) chooseImage(true);
                  else if (which == 2) cropImage();
                  else {
                    clearImage();
                    render();
                  }
                })
            .setNegativeButton("取消", null);
    showDialog(b);
  }

  void chooseImage(boolean camera) {
    cancel();
    try {
      if (camera) {
        pendingCameraUri = FullResolutionCamera.createOutputUri(this);
        startActivityForResult(
            FullResolutionCamera.createCaptureIntent(this, pendingCameraUri), 102);
      } else {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, 101);
      }
    } catch (Exception error) {
      if (pendingCameraUri != null) FullResolutionCamera.cleanup(this, pendingCameraUri);
      pendingCameraUri = null;
      error(error);
    }
  }

  @Override
  protected void onActivityResult(int req, int result, Intent data) {
    super.onActivityResult(req, result, data);
    if (req == 70) {
      if (result == RESULT_OK) {
        titles.reset(); settingsUi.models.cancel(); settingsUi.models.clearDraft();
        screen = "tabs"; page = 4; render();
      }
      return;
    }
    if (req == 102) {
      Uri output = pendingCameraUri;
      pendingCameraUri = null;
      try {
        if (result == RESULT_OK && output != null) {
          acceptImage(FullResolutionCamera.persistCaptured(this, output));
        }
      } catch (Exception error) {
        error(error);
      } finally {
        if (output != null) FullResolutionCamera.cleanup(this, output);
      }
      return;
    }
    if (result != RESULT_OK || data == null) {
      if (req == 201 && pendingExport != null) {
        pendingExport.delete();
        pendingExport = null;
      }
      return;
    }
    try {
      if (req == 101) {
        Uri uri = data.getData();
        if (uri == null) return;
        acceptImage(ImageTools.persist(this, uri));
      } else if (req == 201) {
        final Uri destination = data.getData();
        final File source = pendingExport;
        pendingExport = null;
        worker.execute(
            () -> {
              try {
                if (source == null || !source.isFile() || destination == null)
                  throw new IOException("导出内容已失效，请重新导出");
                try (InputStream in = new FileInputStream(source);
                    OutputStream out = getContentResolver().openOutputStream(destination)) {
                  if (out == null) throw new IOException("无法打开目标文件");
                  byte[] buffer = new byte[8192];
                  int count;
                  while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                }
                handler.post(() -> say("文件已保存"));
              } catch (Exception e) {
                handler.post(() -> error(e));
              } finally {
                if (source != null) source.delete();
              }
            });
      } else if (req == 202) {
        prepareRestore(data.getData());
      }

    } catch (Exception e) {
      error(e);
    }
  }

  void invalidateDraft() {
    if (session != null && session.optJSONObject("pending_lookup") != null) {
      Library.put(session, "pending_lookup", JSONObject.NULL);
      Library.put(session, "input_dirty", true);
      Library.put(session, "revision", session.optInt("revision") + 1);
    }
  }

  void acceptImage(Uri source) {
    originalImage = image = source;
    imageEditorState = ImageEditorState.initial();
    imageRegion = null;
    invalidateDraft();
    if (screen.equals("assistant") && session != null) {
      query.storeImageSource(session);
      library.touchSession(session, false);
      persist();
    }
    render();
    cropImage();
  }

  void cropImage() {
    Uri source = originalImage == null ? image : originalImage;
    if (source == null) {
      say("请先选择图片");
      return;
    }
    ImageEditorDialog.show(
        this,
        source,
        imageEditorState,
        new ImageEditorDialog.Callback() {
          @Override
          public void onComplete(ImageEditorResult result) {
            originalImage = result.originalUri;
            image = result.effectiveUri == null ? result.originalUri : result.effectiveUri;
            imageEditorState = result.state;
            imageRegion = result.region;
            invalidateDraft();
            if (page == 1) {
              query.stage = 0;
              query.guessReply = null;
            }
            // Attachments changed inside an assistant remain part of that session's explicit
            // source.
            if (screen.equals("assistant") && session != null) {
              query.storeImageSource(session);
              library.touchSession(session, false);
              persist();
            }
            render();
          }

          @Override
          public void onError(Activity activity, Exception error) {
            MainActivity.this.error(error);
          }
        });
  }

  void export(String filename, String mime, String content) {
    export(filename, mime, content.getBytes(StandardCharsets.UTF_8));
  }

  void export(String filename, String mime, byte[] content) {
    worker.execute(
        () -> {
          try {
            File staged = File.createTempFile("export-", ".bin", getCacheDir());
            try (FileOutputStream out = new FileOutputStream(staged)) {
              out.write(content);
            }
            handler.post(
                () -> {
                  if (!alive()) {
                    staged.delete();
                    return;
                  }
                  pendingExport = staged;
                  Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                  i.addCategory(Intent.CATEGORY_OPENABLE);
                  i.setType(mime);
                  i.putExtra(Intent.EXTRA_TITLE, filename);
                  startActivityForResult(i, 201);
                });
          } catch (Exception e) {
            handler.post(() -> error(e));
          }
        });
  }

  private boolean alive() {
    return !isFinishing() && !isDestroyed();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    if (settingsUi == null) { if (pendingStartupState != null) state.putAll(pendingStartupState); super.onSaveInstanceState(state); return; }
    review.saveState(state);
    settingsUi.models.saveState(state);
    super.onSaveInstanceState(state);
    state.putInt("page", page);
    state.putString("screen", screen);
    if (session != null) state.putString("session", session.optString("id"));
    if (activeVocabulary != null) state.putString("activeVocabulary", activeVocabulary.optString("id"));
    if (notebooks != null && notebooks.activeCollectionId != null) {
      state.putString("notebook_active_collection", notebooks.activeCollectionId);
    }
    if (review != null) {
      state.putString("review_phase", review.phase);
      if (review.notebook != null) state.putString("review_notebook", review.notebook);
      state.putBoolean("review_reverse", review.reverse);
      state.putBoolean("review_revealed", review.revealed);
      state.putInt("review_batch_size", review.batchSize);
      if (!review.selectedTags.isEmpty()) {
        state.putStringArrayList("review_selected_tags", new ArrayList<>(review.selectedTags));
      }
      if (review.activePlanId != null) {
        state.putString("review_active_plan_id", review.activePlanId);
      }
    }
    if (query != null) {
      state.putString("draft", query.draft);
      state.putString("context", query.context);
      state.putString("follow", query.follow);
      state.putString("guessTarget", query.guessTarget);
      state.putString("guessContext", query.guessContext);
    }
    if (image != null) state.putString("image", image.toString());
    if (originalImage != null) state.putString("originalImage", originalImage.toString());
    if (pendingCameraUri != null) state.putString("pendingCamera", pendingCameraUri.toString());
    state.putString("imageEditorState", imageEditorState.toJson().toString());
    if (imageRegion != null) state.putString("imageRegion", imageRegion.toString());
    state.putString("homeMode", query.homeMode);
    state.putString("guessMode", query.guessMode);
    state.putBoolean("organizing", query.organizing);
    if (pendingExport != null) state.putString("export", pendingExport.getName());
  }

  byte[] readBounded(InputStream in) throws Exception {
    if (in == null) throw new IOException("无法打开所选文件");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] b = new byte[8192];
    int n;
    while ((n = in.read(b)) != -1) {
      if (out.size() + n > BackupArchive.MAX_BYTES) throw new IOException("文件超过当前设备安全容量（最高16 MB）");
      out.write(b, 0, n);
    }
    return out.toByteArray();
  }

  void backupZip() { prepareBackup(true); }

  void backupJson() { prepareBackup(false); }

  private void prepareBackup(boolean zip) {
    ProgressDialog progress = ProgressDialog.show(this, "", "正在整理备份和图片…", true, false);
    store.runExclusive(null, backend -> {
      File raw = File.createTempFile("backup-source-", ".json", getCacheDir());
      try (OutputStream out = new FileOutputStream(raw)) {
        if (backend instanceof SqliteLibraryStore) ((SqliteLibraryStore) backend).exportJson(out);
        else out.write(backend.loadSnapshot().getRawDocument().toString().getBytes(StandardCharsets.UTF_8));
        return raw;
      } catch (Exception e) { raw.delete(); throw e; }
    }, (raw, failure) -> {
      if (failure != null) { progress.dismiss(); if (alive()) error(failure); return; }
      if (!alive()) { raw.delete(); progress.dismiss(); return; }
      worker.execute(() -> {
        File staged = null;
        try {
          staged = File.createTempFile("export-", ".bin", getCacheDir());
          try (InputStream in = new FileInputStream(raw); OutputStream out = new FileOutputStream(staged)) {
            if (zip) BackupStreamCodec.encode(in, out, uri -> {
              if (uri.startsWith("data:image/")) {
                int comma = uri.indexOf(',');
                if (comma < 0) throw new IOException("图片数据无效");
                return new android.util.Base64InputStream(
                    new ByteArrayInputStream(uri.substring(comma + 1).getBytes(StandardCharsets.US_ASCII)), android.util.Base64.DEFAULT);
              }
              Uri value = Uri.parse(uri);
              if (!"file".equals(value.getScheme()) && !"content".equals(value.getScheme()))
                throw new IOException("无法打包外部图片，请先重新选择图片");
              InputStream imageStream = getContentResolver().openInputStream(value);
              if (imageStream == null) throw new IOException("无法读取图片");
              return imageStream;
            });
            else BackupStreamCodec.encodeJson(in, out);
          }
          final File completed = staged;
          handler.post(() -> {
            progress.dismiss();
            if (!alive()) { completed.delete(); return; }
            pendingExport = completed;
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(zip ? "application/zip" : "application/json");
            i.putExtra(Intent.EXTRA_TITLE, zip ? "lexinote-backup.zip" : "lexinote-backup.json");
            startActivityForResult(i, 201);
          });
        } catch (Exception e) {
          if (staged != null) staged.delete();
          handler.post(() -> { progress.dismiss(); if (alive()) error(e); });
        } finally { raw.delete(); }
      });
    });
  }

  void restore() {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.setType("*/*");
    i.putExtra(
        Intent.EXTRA_MIME_TYPES,
        new String[] {"application/json", "application/zip", "application/octet-stream"});
    i.addCategory(Intent.CATEGORY_OPENABLE);
    startActivityForResult(i, 202);
  }

  void prepareRestore(Uri uri) {
    cancel();
    ProgressDialog progress = ProgressDialog.show(this, "", "正在校验备份…", true, false);
    worker.execute(() -> {
      try (InputStream in = getContentResolver().openInputStream(uri)) {
        if (in == null) throw new IOException("无法打开所选文件");
        BackupStreamCodec.Prepared prepared = BackupStreamCodec.decode(in, getCacheDir());
        final int[] counts;
        try { counts = countBackupEntries(prepared.backupJson); }
        catch (Exception e) { prepared.close(); throw e; }
        handler.post(() -> {
          progress.dismiss();
          if (!alive()) { prepared.close(); return; }
          final boolean[] accepted = {false};
          AlertDialog.Builder builder = new AlertDialog.Builder(this)
              .setTitle("确认覆盖恢复")
              .setMessage("词汇 " + counts[0] + " · 笔记 " + counts[1] + " · 图片 " + prepared.assets.size()
                  + "\n将覆盖本机数据；供应商及密钥保留。旧 JSON 图片可能无法跨设备读取。")
              .setNegativeButton("取消", (d, w) -> prepared.close())
              .setOnCancelListener(d -> prepared.close())
              .setPositiveButton("恢复", (d, w) -> { accepted[0] = true; applyRestore(prepared); });
          AlertDialog dialog = showDialog(builder);
          dialog.setOnDismissListener(d -> { if (!accepted[0]) prepared.close(); });
        });
      } catch (Exception e) { handler.post(() -> { progress.dismiss(); if (alive()) error(e); }); }
    });
  }

  private int[] countBackupEntries(File source) throws Exception {
    int[] counts = new int[2];
    try (android.util.JsonReader reader = new android.util.JsonReader(new InputStreamReader(new FileInputStream(source), StandardCharsets.UTF_8))) {
      reader.beginObject();
      while (reader.hasNext()) {
        String name = reader.nextName();
        int index = name.equals("vocabularies") ? 0 : name.equals("notebooks") ? 1 : -1;
        if (index < 0) reader.skipValue();
        else { reader.beginArray(); while (reader.hasNext()) { reader.skipValue(); counts[index]++; } reader.endArray(); }
      }
      reader.endObject();
    }
    return counts;
  }

  void applyRestore(BackupStreamCodec.Prepared prepared) {
    cancel(); titles.reset();
    final long expectedGeneration = store.currentGeneration();
    ProgressDialog progress = ProgressDialog.show(this, "", "正在恢复…", true, false);
    worker.execute(() -> {
      File directory = new File(getFilesDir(), "restored/" + Library.id());
      File materialized = null;
      try {
        if (!directory.mkdirs()) throw new IOException("无法创建图片恢复目录");
        materialized = File.createTempFile("restore-", ".json", getCacheDir());
        try (OutputStream output = new FileOutputStream(materialized)) {
          prepared.materialize(output, (name, asset) -> {
            File dest = new File(directory, new File(name).getName());
            try (InputStream input = new FileInputStream(asset); FileOutputStream out = new FileOutputStream(dest)) {
              byte[] buffer = new byte[8192]; int n;
              while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
              out.getFD().sync();
            }
            return Uri.fromFile(dest).toString();
          });
        }
        prepared.close();
        File ready = materialized;
        store.runRecoveryExclusive(null, backend -> {
          try {
            if (backend instanceof SqliteLibraryStore)
              return ((SqliteLibraryStore) backend).importJson(ready, expectedGeneration);
            // Legacy backend still has its original bounded in-memory JSON contract.
            if (ready.length() > BackupArchive.MAX_JSON_BYTES) throw new IOException("当前后端尚不能安全恢复超过旧版容量的备份");
            byte[] bytes = java.nio.file.Files.readAllBytes(ready.toPath());
            JSONObject backup = new JSONObject(new String(bytes, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", ""));
            Library.validate(backup);
            if (recoveryOnly) repository.preserveCorruptData();
            return backend.restore(backup, expectedGeneration);
          } finally { ready.delete(); }
        }, (result, failure) -> {
          if (failure != null || result == null || !result.isSuccess()) {
            progress.dismiss(); deleteRestoredDirectory(directory);
            if (alive()) error(failure != null ? failure : new IOException(result == null ? "恢复失败" : result.getMessage()));
            return;
          }
          // The database commit has succeeded even if the subsequent display reload fails.
          store.loadLibrary(null, loaded -> {
            progress.dismiss();
            if (!alive()) return;
            if (!loaded.isSuccess() || loaded.getSnapshot() == null) {
              error(new IOException("恢复已提交，但重新读取失败，请重启应用：" + loaded.getMessage()));
              return;
            }
            library = loaded.getSnapshot(); dataRevision++; libraryRevision = loaded.getAppliedRevision(); libraryGeneration = loaded.getAppliedGeneration();
            titles.reset(); recoveryOnly = false; committed = null;
            session = null; image = originalImage = null;
            review.queue.clear(); notebooks.selected = null;
            render(); say("已恢复，可在设置回到恢复前");
          });
        });
      } catch (Exception e) {
        prepared.close(); if (materialized != null) materialized.delete(); deleteRestoredDirectory(directory);
        handler.post(() -> { progress.dismiss(); if (alive()) error(e); });
      }
    });
  }

  private static void deleteRestoredDirectory(File directory) {
    File[] files = directory.listFiles();
    if (files != null) for (File file : files) file.delete();
    directory.delete();
  }

  void rollbackLibrary() {
    cancel(); titles.reset();
    dispatch(LibraryCommand.rollbackLibrary(store.currentGeneration(), System.currentTimeMillis()), result -> {
      if (!result.isSuccess()) { error(new IOException(result.getMessage())); return; }
      session = null; activeVocabulary = null; committed = null;
      review.phase = "dashboard"; notebooks.selected = null;
      render(); say("已回退");
    });
  }

  @Override
  @android.annotation.SuppressLint("GestureBackNavigation")
  public void onBackPressed() {
    handleBack();
  }

  // API 33+ uses the system back dispatcher; older versions invoke onBackPressed.
  void handleBack() {
    if (query != null && query.assistantView != null && query.assistantView.isDrawerOpen()) {
      query.assistantView.closeDrawer();
      return;
    }
    if (!screen.equals("tabs")) {
      screen = "tabs";
      render();
    } else if (page == 2 && notebooks != null) {
      if (notebooks.selected != null || !notebooks.filter.isEmpty()) {
        notebooks.selected = null;
        notebooks.filter = "";
        render();
      } else if (notebooks.activeCollectionId != null) {
        notebooks.activeCollectionId = null;
        notebooks.filter = "";
        render();
      } else if (page != 0) {
        page = 0;
        render();
      } else finish();
    } else if (page == 3 && review != null) {
      if ("active".equals(review.phase)) {
        review.confirmExit();
      } else if ("completed".equals(review.phase)) {
        review.phase = "dashboard";
        render();
      } else if ("manage".equals(review.phase)) {
        review.phase = "dashboard";
        render();
      } else if (page != 0) {
        page = 0;
        render();
      } else finish();
    } else if (page != 0) {
      page = 0;
      render();
    } else finish();
  }

  @Override
  protected void onDestroy() {
    uiEpoch++;
    cancel();
    if (systemVoice != null) {
      systemVoice.shutdown();
      systemVoice = null;
    }
    if (pronunciationEngine != null) {
      pronunciationEngine.release();
    }
    if (query != null && query.assistantView != null) {
      query.assistantView.destroy();
    }
    if (notebooks != null) notebooks.close();
    if (settingsUi != null) settingsUi.models.close();
    if (titles != null) titles.close();
    if (store != null) {
      store.unbind(this);
    }
    worker.shutdownNow();
    super.onDestroy();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (review != null && page == 3 && "tabs".equals(screen) && "active".equals(review.phase)) review.resumeTimer();
  }

  @Override
  protected void onPause() {
    pronunciationRequest++;
    pendingSystemSpeech = null;
    if (systemVoiceReady) systemVoice.stop();
    if (pronunciationEngine != null) {
      pronunciationEngine.stop();
    }
    if (review != null) review.pauseTimer();
    if (query != null && query.assistantView != null && session != null) {
      query.assistantView.saveAnchor();
      library.touchSession(session, false);
      persist();
    }
    super.onPause();
  }
}
