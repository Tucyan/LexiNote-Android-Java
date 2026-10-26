package com.lexinote.nativeapp;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class MainActivity extends Activity {
  Library library;
  Repository repository;
  SecureSettings secure;
  LinearLayout root, body, nav;
  int page = 0;
  String screen = "tabs";
  JSONObject session;
  JSONObject committed;
  Uri image;
  int generation = 0;
  AiClient active;
  final ExecutorService worker = Executors.newSingleThreadExecutor();
  final Handler handler = new Handler(Looper.getMainLooper());
  QueryUi query;
  NotebookUi notebooks;
  ReviewUi review;
  SettingsUi settingsUi;
  int background, foreground, muted, card, accent;
  float scale = 1;
  File pendingExport;
  boolean recoveryOnly;
  String loadFailure;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    repository = new Repository(this);
    secure = new SecureSettings(this);
    try {
      library = repository.load();
      committed = library.snapshot();
    } catch (Exception e) {
      library = new Library();
      recoveryOnly = true;
      loadFailure = e.getMessage();
    }
    query = new QueryUi(this);
    notebooks = new NotebookUi(this);
    review = new ReviewUi(this);
    settingsUi = new SettingsUi(this);
    if (state != null && !recoveryOnly) {
      page = Math.max(0, Math.min(4, state.getInt("page", 0)));
      screen = state.getString("screen", "tabs");
      String sessionId = state.getString("session");
      JSONObject saved = sessionId == null ? null : library.find("conversations", sessionId);
      session = saved == null ? null : Library.copy(saved);
      if (session == null) screen = "tabs";
      query.draft = state.getString("draft", "");
      query.context = state.getString("context", "");
      query.follow = state.getString("follow", "");
      query.guessTarget = state.getString("guessTarget", "");
      query.guessContext = state.getString("guessContext", "");
      String uri = state.getString("image");
      image = uri == null ? null : Uri.parse(uri);
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
    render();
  }

  void palette() {
    String mode = library.settings().optString("theme_mode", "system");
    boolean dark =
        mode.equals("dark")
            || mode.equals("system")
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
    background = Color.parseColor(dark ? "#111F20" : "#F7F8F1");
    foreground = Color.parseColor(dark ? "#EDF6F2" : "#183C38");
    muted = Color.parseColor(dark ? "#A4BAB4" : "#657C74");
    card = Color.parseColor(dark ? "#203434" : "#FFFFFF");
    accent = Color.parseColor(dark ? "#80D8C0" : "#087E70");
    String f = library.settings().optString("font_size", "medium");
    scale = f.equals("large") ? 1.2f : f.equals("small") ? .9f : 1;
    getWindow().setStatusBarColor(background);
    getWindow().setNavigationBarColor(background);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            dark
                ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
  }

  void render() {
    if (library == null) return;
    palette();
    root = column();
    root.setBackgroundColor(background);
    root.setPadding(dp(16), dp(8), dp(16), 0);
    root.setOnApplyWindowInsetsListener(
        (view, insets) -> {
          int left, top, right, bottom;
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            left = bars.left;
            top = bars.top;
            right = bars.right;
            bottom = bars.bottom;
          } else {
            left = insets.getSystemWindowInsetLeft();
            top = insets.getSystemWindowInsetTop();
            right = insets.getSystemWindowInsetRight();
            bottom = insets.getSystemWindowInsetBottom();
          }
          view.setPadding(dp(16) + left, dp(8) + top, dp(16) + right, bottom);
          return insets;
        });
    root.requestApplyInsets();
    setContentView(root);
    if (recoveryOnly) {
      root.addView(text("恢复本地数据", 27, true));
      root.addView(text("数据读取失败，原文件保留。可以选择有效备份恢复；确认恢复时会额外保留损坏文件副本。\n" + loadFailure, 16, false));
      root.addView(button("选择 ZIP / JSON 备份恢复", v -> restore()));
      root.addView(button("退出", v -> finish()));
      return;
    }
    if (!screen.equals("tabs")) {
      LinearLayout bar = row();
      bar.addView(
          button(
              "‹ 返回",
              v -> {
                screen = "tabs";
                render();
              }));
      bar.addView(text(screen.equals("assistant") ? "AI 助手" : "词汇详情", 21, true));
      root.addView(bar);
    } else {
      root.addView(text(new String[] {"LexiNote", "猜词辅助", "我的笔记", "每日复习", "设置"}[page], 27, true));
      root.addView(
          text(
              new String[] {
                    "从一个词，连接更多理解", "先思考，再逐步揭晓答案", "完整词库 · 共享引用 · 树形整理", "让掌握的词汇留下来", "本地优先，按需连接 AI"
                  }
                  [page],
              13,
              false));
    }
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    body = column();
    body.setPadding(0, dp(12), 0, dp(20));
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    if (screen.equals("assistant")) query.assistant();
    else if (screen.equals("result")) query.result();
    else {
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
      nav = row();
      String[] labels = {"查询", "猜词", "笔记", "复习", "设置"};
      for (int i = 0; i < 5; i++) {
        final int tab = i;
        Button b =
            button(
                labels[i],
                v -> {
                  page = tab;
                  render();
                });
        b.setTextSize(12 * scale);
        b.setTextColor(i == page ? accent : muted);
        nav.addView(b, new LinearLayout.LayoutParams(0, dp(58), 1));
      }
      root.addView(nav);
    }
  }

  int dp(float v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    l.setBackgroundColor(background);
    return l;
  }

  LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  TextView text(String s, int size, boolean bold) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextColor(bold ? foreground : muted);
    t.setTextSize(size * scale);
    t.setPadding(0, dp(5), 0, dp(5));
    if (bold) t.setTypeface(null, Typeface.BOLD);
    t.setTextIsSelectable(true);
    return t;
  }

  Button button(String s, View.OnClickListener click) {
    Button b = new Button(this);
    b.setText(s);
    b.setAllCaps(false);
    b.setTextSize(14 * scale);
    b.setTextColor(accent);
    b.setMinHeight(dp(48));
    b.setMinimumHeight(dp(48));
    b.setElevation(0);
    b.setStateListAnimator(null);
    GradientDrawable shape = new GradientDrawable();
    shape.setCornerRadius(dp(12));
    shape.setColor((accent & 0x00FFFFFF) | 0x14000000);
    b.setBackgroundTintList(null);
    b.setBackground(
        new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf((accent & 0x00FFFFFF) | 0x33000000),
            shape,
            null));
    b.setPadding(dp(10), dp(8), dp(10), dp(8));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
    lp.setMargins(dp(3), dp(3), dp(3), dp(3));
    b.setLayoutParams(lp);
    b.setOnClickListener(click);
    return b;
  }

  EditText input(String hint, String value, boolean multiline) {
    EditText e = new EditText(this);
    e.setHint(hint);
    e.setText(value);
    e.setTextColor(foreground);
    e.setHintTextColor(muted);
    e.setTextSize(16 * scale);
    e.setSingleLine(!multiline);
    if (multiline) {
      e.setMinLines(2);
      e.setGravity(Gravity.TOP);
    }
    e.setPadding(dp(12), dp(10), dp(12), dp(10));
    GradientDrawable shape = new GradientDrawable();
    shape.setColor(card);
    shape.setCornerRadius(dp(12));
    shape.setStroke(dp(1), muted);
    e.setBackground(shape);
    e.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
    return e;
  }

  LinearLayout card(String title) {
    LinearLayout c = column();
    c.setPadding(dp(14), dp(12), dp(14), dp(12));
    GradientDrawable shape = new GradientDrawable();
    shape.setColor(card);
    shape.setCornerRadius(dp(16));
    c.setBackground(shape);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(12);
    c.setLayoutParams(lp);
    if (title != null) c.addView(text(title, 18, true));
    body.addView(c);
    return c;
  }

  void say(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }

  void error(Exception e) {
    if (!alive()) return;
    new AlertDialog.Builder(this)
        .setTitle("操作未完成")
        .setMessage(e.getMessage() == null ? e.toString() : e.getMessage())
        .setPositiveButton("知道了", null)
        .show();
  }

  boolean persist() {
    if (recoveryOnly) return false;
    try {
      repository.save(library);
      committed = library.snapshot();
      return true;
    } catch (Exception e) {
      String sessionId = session == null ? null : session.optString("id");
      if (committed != null) {
        library = new Library(committed);
        session = sessionId == null ? null : library.find("conversations", sessionId);
      }
      error(new IOException("写入失败，已恢复上次成功保存的数据：" + e.getMessage(), e));
      return false;
    }
  }

  void change(Runnable r) {
    JSONObject before = library.snapshot();
    try {
      r.run();
      persist();
      render();
    } catch (Exception e) {
      library = new Library(before);
      error(e);
      render();
    }
  }

  interface Work {
    JSONObject run(AiClient client) throws Exception;
  }

  interface Done {
    void accept(JSONObject result) throws Exception;
  }

  void request(Work work, Done done) {
    if (active != null) {
      say("正在请求，可取消后重新发送");
      return;
    }
    final int token = ++generation;
    try {
      active = new AiClient(secure.getProvider(), secure.getApiKey());
    } catch (Exception e) {
      error(e);
      return;
    }
    AiClient c = active;
    ProgressDialog progress = new ProgressDialog(this);
    progress.setMessage("AI 正在思考…");
    progress.setCancelable(true);
    progress.setButton(DialogInterface.BUTTON_NEGATIVE, "取消", (d, w) -> cancel());
    progress.setOnCancelListener(d -> cancel());
    progress.show();
    worker.execute(
        () -> {
          try {
            JSONObject value = work.run(c);
            handler.post(
                () -> {
                  progress.dismiss();
                  if (token != generation || !alive()) return;
                  active = null;
                  try {
                    done.accept(value);
                    persist();
                    render();
                  } catch (Exception e) {
                    error(e);
                  }
                });
          } catch (Exception e) {
            handler.post(
                () -> {
                  progress.dismiss();
                  if (token != generation || !alive()) return;
                  active = null;
                  if (session != null) {
                    Library.put(session, "last_error", e.getMessage());
                    library.touchSession(session, true);
                    persist();
                  }
                  error(e);
                });
          }
        });
  }

  void cancel() {
    generation++;
    if (active != null) active.cancel();
    active = null;
  }

  String imageData() throws Exception {
    return image == null ? null : ImageTools.dataUrl(this, image);
  }

  void chooseImage() {
    new AlertDialog.Builder(this)
        .setItems(
            new String[] {"选择图片", "拍照（系统相机缩略图）", "移除图片"},
            (d, w) -> {
              if (w == 0) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.setType("image/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(i, 101);
              } else if (w == 1) {
                try {
                  startActivityForResult(new Intent(MediaStore.ACTION_IMAGE_CAPTURE), 102);
                } catch (Exception e) {
                  error(e);
                }
              } else {
                image = null;
                invalidateDraft();
                render();
              }
            })
        .show();
  }

  @Override
  protected void onActivityResult(int req, int result, Intent data) {
    super.onActivityResult(req, result, data);
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
        image = ImageTools.persist(this, uri);
        cropImage();
      } else if (req == 102) {
        Bitmap bitmap = (Bitmap) data.getExtras().get("data");
        if (bitmap != null) {
          image = ImageTools.saveBitmap(this, bitmap);
          cropImage();
        }
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

  void cropImage() {
    invalidateDraft();
    if (page == 1) {
      query.stage = 0;
      query.guessReply = null;
    }
    try {
      Bitmap b = ImageTools.decode(this, image);
      ImageCropView crop = new ImageCropView(this, b);
      LinearLayout box = column();
      box.addView(crop, new LinearLayout.LayoutParams(-1, dp(300)));
      LinearLayout tools = row();
      tools.addView(button("旋转", v -> crop.rotate()));
      tools.addView(button("重置", v -> crop.reset()));
      box.addView(tools);
      new AlertDialog.Builder(this)
          .setTitle("选择图片区域")
          .setView(box)
          .setNegativeButton("保留原图", (d, w) -> render())
          .setPositiveButton(
              "使用裁剪",
              (d, w) -> {
                try {
                  image = ImageTools.saveBitmap(this, crop.crop());
                  render();
                } catch (Exception e) {
                  error(e);
                }
              })
          .show();
    } catch (Exception e) {
      error(e);
    }
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
    super.onSaveInstanceState(state);
    state.putInt("page", page);
    state.putString("screen", screen);
    if (session != null) state.putString("session", session.optString("id"));
    if (query != null) {
      state.putString("draft", query.draft);
      state.putString("context", query.context);
      state.putString("follow", query.follow);
      state.putString("guessTarget", query.guessTarget);
      state.putString("guessContext", query.guessContext);
    }
    if (image != null) state.putString("image", image.toString());
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

  void backupZip() {
    final JSONObject snapshot = library.backup();
    ProgressDialog p = ProgressDialog.show(this, "", "正在整理备份和图片…", true, false);
    worker.execute(
        () -> {
          try {
            byte[] bytes =
                BackupArchive.encode(
                    snapshot,
                    uri -> {
                      if (uri.startsWith("data:image/")) {
                        int comma = uri.indexOf(',');
                        if (comma < 0) throw new IOException("图片数据无效");
                        return android.util.Base64.decode(
                            uri.substring(comma + 1), android.util.Base64.DEFAULT);
                      }
                      Uri value = Uri.parse(uri);
                      if (!"file".equals(value.getScheme()) && !"content".equals(value.getScheme()))
                        throw new IOException("无法打包外部图片，请先重新选择图片：" + uri);
                      try (InputStream in = getContentResolver().openInputStream(value)) {
                        return readBounded(in);
                      }
                    });
            handler.post(
                () -> {
                  p.dismiss();
                  if (alive()) export("lexinote-backup.zip", "application/zip", bytes);
                });
          } catch (Exception e) {
            handler.post(
                () -> {
                  p.dismiss();
                  error(e);
                });
          }
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
    ProgressDialog p = ProgressDialog.show(this, "", "正在校验备份…", true, false);
    worker.execute(
        () -> {
          try (InputStream in = getContentResolver().openInputStream(uri)) {
            BackupArchive.Prepared prepared = BackupArchive.decode(readBounded(in));
            handler.post(
                () -> {
                  p.dismiss();
                  if (!alive()) return;
                  JSONObject b = prepared.backup;
                  new AlertDialog.Builder(this)
                      .setTitle("确认覆盖恢复")
                      .setMessage(
                          "词汇 "
                              + b.optJSONArray("vocabularies").length()
                              + " · 笔记 "
                              + b.optJSONArray("notebooks").length()
                              + " · 图片 "
                              + prepared.assets.size()
                              + "\n将覆盖本机数据；供应商及密钥保留。旧 JSON 图片可能无法跨设备读取。")
                      .setNegativeButton("取消", null)
                      .setPositiveButton("恢复", (d, w) -> applyRestore(prepared))
                      .show();
                });
          } catch (Exception e) {
            handler.post(
                () -> {
                  p.dismiss();
                  error(e);
                });
          }
        });
  }

  void applyRestore(BackupArchive.Prepared prepared) {
    cancel();
    final Library before = library;
    final int token = ++generation;
    ProgressDialog p = ProgressDialog.show(this, "", "正在恢复…", true, false);
    worker.execute(
        () -> {
          try {
            File directory = new File(getFilesDir(), "restored/" + Library.id());
            if (!directory.mkdirs()) throw new IOException("无法创建图片恢复目录");
            JSONObject backup =
                BackupArchive.materialize(
                    prepared,
                    (name, bytes) -> {
                      File dest = new File(directory, new File(name).getName());
                      try (FileOutputStream out = new FileOutputStream(dest)) {
                        out.write(bytes);
                        out.getFD().sync();
                      }
                      return Uri.fromFile(dest).toString();
                    });
            if (recoveryOnly) repository.preserveCorruptData();
            Library restored = repository.restore(backup, before);
            handler.post(
                () -> {
                  p.dismiss();
                  if (token != generation || !alive()) return;
                  library = restored;
                  recoveryOnly = false;
                  committed = library.snapshot();
                  session = null;
                  image = null;
                  review.queue.clear();
                  notebooks.selected = null;
                  render();
                  say("已恢复，可在设置回到恢复前");
                });
          } catch (Exception e) {
            handler.post(
                () -> {
                  p.dismiss();
                  error(e);
                });
          }
        });
  }

  @Override
  @android.annotation.SuppressLint("GestureBackNavigation")
  public void onBackPressed() {
    handleBack();
  }

  // API 33+ uses the system back dispatcher; older versions invoke onBackPressed.
  void handleBack() {
    if (!screen.equals("tabs")) {
      screen = "tabs";
      render();
    } else if (page != 0) {
      page = 0;
      render();
    } else finish();
  }

  @Override
  protected void onDestroy() {
    cancel();
    worker.shutdownNow();
    super.onDestroy();
  }
}
