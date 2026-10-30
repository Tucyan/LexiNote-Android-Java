package com.lexinote.nativeapp;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Native portal shell. Website credentials stay inside WebView; there is no JS bridge. */
public final class TokenDanceActivity extends Activity {
  SecureSettings secure;
  TokenDanceProtocol.Session pending;
  TokenDanceApi.PaymentSession payment;
  static Supplier<TokenDanceApi> defaultClientFactory = TokenDanceApi::new;
  Supplier<TokenDanceApi> clientFactory = defaultClientFactory;
  final ExecutorService worker = Executors.newSingleThreadExecutor();
  final Handler handler = new Handler(Looper.getMainLooper());
  TokenDanceApi active;
  WebView web;
  LinearLayout content;
  TextView status;
  EditText codeInput;
  String mode, notice = "", paymentAmount = "10";
  boolean busy, foreground, manual;
  boolean creatingPayment, creationInterrupted;
  boolean externalPrompt, expiryChecked;
  int backgroundColor = Color.WHITE, foregroundColor = Color.rgb(15, 23, 42), accentColor = Color.rgb(16, 185, 129);
  int cardColor = Color.WHITE, mutedColor = Color.rgb(100, 116, 139), subtleColor = Color.rgb(148, 163, 184);
  int borderColor = Color.rgb(226, 232, 240), inputBgColor = Color.rgb(241, 245, 249), errorColor = Color.rgb(239, 68, 68);
  float fontScale = 1f;
  int generation, pollFailures;
  String pageHost = "tokendance.space";

  LinearLayout root;
  TextView titleText;
  TextView infoText;
  TextView hostText;
  LinearLayout autoRow;
  LinearLayout manualContainer;
  Button errorRetryBtn;
  ProgressBar progressBar;
  LinearLayout busyOverlay;
  ScrollView rechargeScroll;
  LinearLayout rechargeContent;

  final Runnable poll = this::checkPayment;
  final Runnable loadTimeout = () -> {
    if (!isFinishing() && mode.equals("authorize") && !busy) {
      notice = "页面加载耗时较长，可点击刷新重试，或改用系统浏览器授权。";
      updateControlCard();
    }
  };

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    secure = new SecureSettings(this);
    palette();
    String requested = getIntent().getStringExtra("mode");
    if (state != null) requested = state.getString("mode", requested);
    mode = java.util.Arrays.asList("authorize", "account", "usage", "help", "recharge").contains(requested) ? requested : "account";
    manual = state != null && state.getBoolean("manual");
    if (mode.equals("authorize")) {
      try {
        pending = secure.pendingTokenDanceAuthorization();
        if (pending == null || System.currentTimeMillis() - pending.createdAt >= 600000L)
          newAuthorization(false);
        else manual = pending.callback == null;
      } catch (Exception e) { notice = "无法恢复授权，请重新授权。"; }
    }
    if (state != null && mode.equals("recharge") && state.getString("payment_id") != null) {
      payment = new TokenDanceApi.PaymentSession(state.getString("payment_id"), state.getInt("payment_amount"),
          state.getString("payment_status", "pending"), state.getLong("payment_expiry"), null);
    }
    if (mode.equals("recharge")) restorePayment();
    if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::leave);

    setupViews();
    handleCallbackIntent(getIntent());
  }

  @Override protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    handleCallbackIntent(intent);
  }

  void handleCallbackIntent(Intent intent) {
    if (intent == null || intent.getData() == null) return;
    String raw = intent.getData().toString();
    if (mode.equals("authorize") && (TokenDanceProtocol.isNativeCallback(raw) || TokenDanceProtocol.classifyPageUrl(raw) == TokenDanceProtocol.Page.CALLBACK)) {
      finishAuthorization(raw, false);
    }
  }

  void palette() {
    try {
      org.json.JSONObject settings = new Repository(this).load().settings();
      String theme = settings.optString("theme_mode", "system");
      boolean dark = theme.equals("dark") || (theme.equals("system") &&
          (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
      backgroundColor = Color.parseColor(dark ? "#0F172A" : "#F8FAFC");
      foregroundColor = Color.parseColor(dark ? "#F8FAFC" : "#0F172A");
      cardColor = Color.parseColor(dark ? "#1E293B" : "#FFFFFF");
      mutedColor = Color.parseColor(dark ? "#94A3B8" : "#475569");
      subtleColor = Color.parseColor(dark ? "#64748B" : "#94A3B8");
      borderColor = Color.parseColor(dark ? "#334155" : "#E2E8F0");
      inputBgColor = Color.parseColor(dark ? "#0F172A" : "#F1F5F9");
      errorColor = Color.parseColor(dark ? "#F87171" : "#EF4444");

      String accent = settings.optString("accent_color", "#10B981");
      if (accent.matches("#[0-9a-fA-F]{6}")) accentColor = Color.parseColor(accent);

      String size = settings.optString("font_size", "medium");
      fontScale = size.equals("large") ? 1.15f : size.equals("small") ? 0.9f : 1.0f;

      getWindow().setStatusBarColor(cardColor);
      getWindow().setNavigationBarColor(cardColor);
      getWindow().getDecorView().setSystemUiVisibility(
          dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    } catch (Exception ignored) { }
  }

  int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }

  TextView text(String s, float size, boolean bold) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextColor(bold ? foregroundColor : mutedColor);
    t.setTextSize(size * fontScale);
    if (bold) t.setTypeface(null, Typeface.BOLD);
    return t;
  }

  GradientDrawable shape(int color, float radiusDp, int strokeColor) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radiusDp));
    if (strokeColor != 0) d.setStroke(dp(1), strokeColor);
    return d;
  }

  Button textButton(String label, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextColor(accentColor);
    b.setTextSize(14 * fontScale);
    b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    b.setMinHeight(dp(36));
    b.setMinimumHeight(dp(36));
    b.setElevation(0);
    b.setStateListAnimator(null);
    b.setBackground(new RippleDrawable(
        ColorStateList.valueOf((accentColor & 0xFFFFFF) | 0x26000000),
        shape(Color.TRANSPARENT, 8, 0), null));
    b.setPadding(dp(10), dp(6), dp(10), dp(6));
    b.setOnClickListener(v -> action.run());
    b.setEnabled(!busy);
    return b;
  }

  Button outlineButton(String label, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextColor(foregroundColor);
    b.setTextSize(13 * fontScale);
    b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    b.setMinHeight(dp(38));
    b.setMinimumHeight(dp(38));
    b.setElevation(0);
    b.setStateListAnimator(null);
    b.setBackground(new RippleDrawable(
        ColorStateList.valueOf((accentColor & 0xFFFFFF) | 0x26000000),
        shape(Color.TRANSPARENT, 10, borderColor), null));
    b.setPadding(dp(12), dp(6), dp(12), dp(6));
    b.setOnClickListener(v -> action.run());
    b.setEnabled(!busy);
    return b;
  }

  Button primaryButton(String label, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextColor(Color.WHITE);
    b.setTextSize(13.5f * fontScale);
    b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    b.setMinHeight(dp(40));
    b.setMinimumHeight(dp(40));
    b.setElevation(0);
    b.setStateListAnimator(null);
    b.setBackground(new RippleDrawable(
        ColorStateList.valueOf(0x33FFFFFF),
        shape(accentColor, 10, 0), null));
    b.setPadding(dp(14), dp(8), dp(14), dp(8));
    b.setOnClickListener(v -> action.run());
    b.setEnabled(!busy);
    return b;
  }

  Button button(String label, Runnable action) {
    return outlineButton(label, action);
  }

  void show() {
    palette();
    destroyWeb();
    setupViews();
  }

  void setupViews() {
    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(backgroundColor);
    root.setOnApplyWindowInsetsListener((v, insets) -> {
      if (Build.VERSION.SDK_INT >= 30) {
        android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
      } else {
        v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
            insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
      }
      return insets;
    });
    setContentView(root);
    root.requestApplyInsets();

    // 1. Clean Modern Navigation Header (52dp height)
    LinearLayout bar = new LinearLayout(this);
    bar.setOrientation(LinearLayout.HORIZONTAL);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setBackgroundColor(cardColor);
    bar.setPadding(dp(8), dp(6), dp(12), dp(6));

    Button back = textButton("← 返回", this::leave);
    bar.addView(back);

    String title = mode.equals("authorize") ? "连接 TokenDance" : mode.equals("recharge") ? "账户充值"
        : mode.equals("usage") ? "调用记录" : mode.equals("help") ? "使用帮助" : "账户与密钥";
    titleText = text(title, 17, true);
    titleText.setPadding(dp(6), 0, dp(6), 0);
    titleText.setSingleLine(true);
    bar.addView(titleText, new LinearLayout.LayoutParams(0, -2, 1));

    Button refresh = textButton("刷新", () -> {
      if (mode.equals("authorize")) restart(manual);
      else if (web != null) web.reload();
    });
    bar.addView(refresh);

    root.addView(bar);

    View barDivider = new View(this);
    barDivider.setBackgroundColor(borderColor);
    root.addView(barDivider, new LinearLayout.LayoutParams(-1, dp(1)));

    // 2. Recharge Mode
    if (mode.equals("recharge")) {
      rechargeScroll = new ScrollView(this);
      rechargeContent = new LinearLayout(this);
      rechargeContent.setOrientation(LinearLayout.VERTICAL);
      rechargeScroll.addView(rechargeContent);
      root.addView(rechargeScroll, new LinearLayout.LayoutParams(-1, 0, 1));
      showRecharge();
      return;
    }

    // 3. Web & Authorization Control Card
    LinearLayout controlCard = new LinearLayout(this);
    controlCard.setOrientation(LinearLayout.VERTICAL);
    controlCard.setBackgroundColor(cardColor);
    controlCard.setPadding(dp(16), dp(10), dp(16), dp(12));

    status = text(notice, 12.5f, false);
    status.setTextColor(notice.contains("失败") || notice.contains("超时") || notice.contains("无效") ? errorColor : mutedColor);
    status.setVisibility(notice.isEmpty() ? View.GONE : View.VISIBLE);
    controlCard.addView(status);

    infoText = text(manual ? "登录并确认授权后，复制页面显示的一次性授权码在下方粘贴连接。" : "登录并确认授权后自动连接。", 13, false);
    infoText.setTextColor(subtleColor);
    controlCard.addView(infoText);

    hostText = text("当前网站：" + pageHost, 11, false);
    hostText.setTextColor(mutedColor);
    hostText.setPadding(0, dp(3), 0, dp(8));
    controlCard.addView(hostText);

    if (mode.equals("authorize")) {
      autoRow = new LinearLayout(this);
      autoRow.setOrientation(LinearLayout.HORIZONTAL);
      autoRow.setGravity(Gravity.CENTER_VERTICAL);
      Button openBrowserBtn = outlineButton("系统浏览器打开", this::openBrowser);
      Button switchManualBtn = outlineButton("改用一次性授权码", () -> switchToManual(true));
      LinearLayout.LayoutParams obLp = new LinearLayout.LayoutParams(0, dp(38), 1);
      obLp.rightMargin = dp(8);
      autoRow.addView(openBrowserBtn, obLp);
      autoRow.addView(switchManualBtn, new LinearLayout.LayoutParams(0, dp(38), 1));
      controlCard.addView(autoRow);

      manualContainer = new LinearLayout(this);
      manualContainer.setOrientation(LinearLayout.VERTICAL);
      manualContainer.setVisibility(manual ? View.VISIBLE : View.GONE);

      codeInput = new EditText(this);
      codeInput.setHint("粘贴一次性授权码（10 分钟内有效）");
      codeInput.setSingleLine(true);
      codeInput.setTextColor(foregroundColor);
      codeInput.setHintTextColor(mutedColor);
      codeInput.setTextSize(13 * fontScale);
      codeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
      codeInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
      codeInput.setSaveEnabled(false);
      codeInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
      codeInput.setBackground(shape(inputBgColor, 10, borderColor));
      codeInput.setPadding(dp(12), dp(9), dp(12), dp(9));
      manualContainer.addView(codeInput, new LinearLayout.LayoutParams(-1, dp(42)));

      LinearLayout manualBtnRow = new LinearLayout(this);
      manualBtnRow.setOrientation(LinearLayout.HORIZONTAL);
      manualBtnRow.setGravity(Gravity.CENTER_VERTICAL);
      LinearLayout.LayoutParams mbrLp = new LinearLayout.LayoutParams(-1, -2);
      mbrLp.topMargin = dp(8);
      manualBtnRow.setLayoutParams(mbrLp);

      Button pasteBtn = outlineButton("粘贴授权码", this::pasteCode);
      Button finishBtn = primaryButton("完成连接", () -> finishAuthorization(codeInput.getText().toString(), true));
      LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(0, dp(40), 1);
      pbLp.rightMargin = dp(8);
      manualBtnRow.addView(pasteBtn, pbLp);
      manualBtnRow.addView(finishBtn, new LinearLayout.LayoutParams(0, dp(40), 1));
      manualContainer.addView(manualBtnRow);

      Button switchAutoBtn = textButton("改用自动回调连接", () -> switchToManual(false));
      LinearLayout.LayoutParams sabLp = new LinearLayout.LayoutParams(-2, -2);
      sabLp.gravity = Gravity.CENTER_HORIZONTAL;
      sabLp.topMargin = dp(4);
      manualContainer.addView(switchAutoBtn, sabLp);

      controlCard.addView(manualContainer);

      errorRetryBtn = outlineButton("重新发起授权", () -> restart(false));
      LinearLayout.LayoutParams erbLp = new LinearLayout.LayoutParams(-1, dp(38));
      erbLp.topMargin = dp(8);
      errorRetryBtn.setLayoutParams(erbLp);
      errorRetryBtn.setVisibility(View.GONE);
      controlCard.addView(errorRetryBtn);

    } else {
      Button openBrowserBtn = outlineButton("系统浏览器打开", () -> openExternal(portalUrl()));
      controlCard.addView(openBrowserBtn, new LinearLayout.LayoutParams(-1, dp(38)));
    }

    root.addView(controlCard);

    View cardDivider = new View(this);
    cardDivider.setBackgroundColor(borderColor);
    root.addView(cardDivider, new LinearLayout.LayoutParams(-1, dp(1)));

    // 4. Horizontal Slim Progress Bar (2.5dp)
    progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progressBar.setMax(100);
    progressBar.setProgress(0);
    progressBar.setVisibility(View.VISIBLE);
    if (Build.VERSION.SDK_INT >= 21) {
      progressBar.setProgressTintList(ColorStateList.valueOf(accentColor));
    }
    root.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(2.5f)));

    // 5. Content Frame (WebView + Busy Overlay)
    FrameLayout contentFrame = new FrameLayout(this);
    root.addView(contentFrame, new LinearLayout.LayoutParams(-1, 0, 1));

    // WebView
    web = new WebView(this);
    web.setBackgroundColor(backgroundColor);
    initWebView();
    contentFrame.addView(web, new FrameLayout.LayoutParams(-1, -1));

    // Busy Overlay
    busyOverlay = new LinearLayout(this);
    busyOverlay.setOrientation(LinearLayout.VERTICAL);
    busyOverlay.setGravity(Gravity.CENTER);
    busyOverlay.setBackgroundColor(backgroundColor);
    busyOverlay.setVisibility(busy ? View.VISIBLE : View.GONE);

    ProgressBar spinner = new ProgressBar(this);
    if (Build.VERSION.SDK_INT >= 21) {
      spinner.setIndeterminateTintList(ColorStateList.valueOf(accentColor));
    }
    busyOverlay.addView(spinner);

    TextView busyTitle = text("正在安全保存授权…", 15, true);
    busyTitle.setPadding(0, dp(12), 0, dp(4));
    busyOverlay.addView(busyTitle);

    TextView busySubtitle = text("已收到授权，正在交换密钥并连接服务…", 12, false);
    busySubtitle.setTextColor(mutedColor);
    busyOverlay.addView(busySubtitle);

    contentFrame.addView(busyOverlay, new FrameLayout.LayoutParams(-1, -1));

    updateControlCard();

    // Load initial URL
    if (mode.equals("authorize")) {
      if (pending != null) {
        web.loadUrl(TokenDanceProtocol.authorizationUrl(pending));
      }
    } else {
      web.loadUrl(portalUrl());
    }
  }

  void initWebView() {
    WebSettings settings = web.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setDatabaseEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    settings.setJavaScriptCanOpenWindowsAutomatically(true);
    settings.setSupportMultipleWindows(false);
    web.setSaveEnabled(false);

    CookieManager cookieManager = CookieManager.getInstance();
    cookieManager.setAcceptCookie(true);
    if (Build.VERSION.SDK_INT >= 21) {
      cookieManager.setAcceptThirdPartyCookies(web, true);
    }

    web.setWebChromeClient(new WebChromeClient() {
      @Override public void onProgressChanged(WebView view, int newProgress) {
        if (progressBar != null) {
          if (newProgress < 100 && !busy) {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setProgress(newProgress);
          } else {
            progressBar.setVisibility(View.GONE);
          }
        }
      }
    });

    web.setWebViewClient(new WebViewClient() {
      @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        if (request == null || request.getUrl() == null) return false;
        return navigate(request.getUrl().toString());
      }
      @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return navigate(url);
      }
      @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
        handler.removeCallbacks(loadTimeout);
        if (mode.equals("authorize") && (TokenDanceProtocol.isNativeCallback(url) || TokenDanceProtocol.classifyPageUrl(url) == TokenDanceProtocol.Page.CALLBACK)) {
          view.stopLoading();
          finishAuthorization(url, false);
          return;
        }
        if (url != null) {
          try {
            Uri parsed = Uri.parse(url);
            if ("https".equalsIgnoreCase(parsed.getScheme()) && parsed.getHost() != null) {
              pageHost = parsed.getHost();
              updateHostText();
            }
          } catch (Exception ignored) { }
        }
        if (mode.equals("authorize") && !busy) {
          handler.postDelayed(loadTimeout, 25000);
        }
      }
      @Override public void onPageFinished(WebView view, String url) {
        handler.removeCallbacks(loadTimeout);
        if (!busy && url != null && !TokenDanceProtocol.isNativeCallback(url)) {
          try {
            Uri parsed = Uri.parse(url);
            if ("https".equalsIgnoreCase(parsed.getScheme()) && parsed.getHost() != null) {
              pageHost = parsed.getHost();
              updateHostText();
            }
          } catch (Exception ignored) { }
        }
      }
      @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        if (request != null && request.isForMainFrame() && !busy) {
          handler.removeCallbacks(loadTimeout);
          notice = "页面加载失败，可刷新重试，或改用系统浏览器。";
          updateControlCard();
        }
      }
      @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
        if (request != null && request.isForMainFrame() && !busy) {
          if (response != null && response.getStatusCode() >= 400) {
            handler.removeCallbacks(loadTimeout);
            notice = "页面暂时不可用 (" + response.getStatusCode() + ")，请稍后重试。";
            updateControlCard();
          }
        }
      }
    });
  }

  void updateHostText() {
    if (hostText != null) {
      hostText.setText("当前网站：" + pageHost);
    }
  }

  void updateControlCard() {
    if (status != null) {
      status.setText(notice);
      status.setTextColor(notice.contains("失败") || notice.contains("超时") || notice.contains("无效") ? errorColor : mutedColor);
      status.setVisibility(notice.isEmpty() ? View.GONE : View.VISIBLE);
    }
    if (infoText != null) {
      infoText.setText(manual ? "登录并确认授权后，复制页面显示的一次性授权码在下方粘贴连接。" : "登录并确认授权后自动连接。");
    }
    if (hostText != null) {
      hostText.setText("当前网站：" + pageHost);
    }
    if (autoRow != null) {
      autoRow.setVisibility(manual ? View.GONE : View.VISIBLE);
    }
    if (manualContainer != null) {
      manualContainer.setVisibility(manual ? View.VISIBLE : View.GONE);
    }
    if (errorRetryBtn != null) {
      boolean hasError = notice.contains("失败") || notice.contains("超时") || notice.contains("重试");
      errorRetryBtn.setVisibility(hasError ? View.VISIBLE : View.GONE);
    }
    if (busyOverlay != null) {
      busyOverlay.setVisibility(busy ? View.VISIBLE : View.GONE);
    }
  }

  void switchToManual(boolean isManual) {
    if (busy) return;
    manual = isManual;
    if (manual) {
      notice = "已切换为一次性授权码方式。若已在页面授权，请复制授权码并在下方粘贴。";
    } else {
      notice = "";
      restart(false);
      return;
    }
    updateControlCard();
  }

  void pasteCode() {
    try {
      ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
      if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
        CharSequence text = cm.getPrimaryClip().getItemAt(0).getText();
        if (text != null && text.length() > 0) {
          String val = text.toString().trim();
          codeInput.setText(val);
          codeInput.setSelection(val.length());
          return;
        }
      }
      Toast.makeText(this, "剪贴板为空，请先复制授权码", Toast.LENGTH_SHORT).show();
    } catch (Exception e) {
      fail("无法读取剪贴板，请长按输入框粘贴。");
    }
  }

  String portalUrl() {
    return TokenDanceProtocol.ORIGIN + (mode.equals("usage") ? "/activity/requests" : mode.equals("help") ? "/docs/quickstart" : "/keys");
  }

  boolean navigate(String url) {
    if (url == null || "about:blank".equals(url)) return false;

    // Real in-app callback interception
    if (mode.equals("authorize") && (TokenDanceProtocol.isNativeCallback(url) || TokenDanceProtocol.classifyPageUrl(url) == TokenDanceProtocol.Page.CALLBACK)) {
      finishAuthorization(url, false);
      return true;
    }

    TokenDanceProtocol.Page page = TokenDanceProtocol.classifyPageUrl(url);
    if (page == TokenDanceProtocol.Page.CALLBACK) {
      if (mode.equals("authorize")) finishAuthorization(url, false);
      return true;
    }
    if (page == TokenDanceProtocol.Page.PAYMENT) {
      confirmPayment(url);
      return true;
    }
    if (page == TokenDanceProtocol.Page.INTERNAL || page == TokenDanceProtocol.Page.EXTERNAL_HTTPS) {
      // Allow internal TokenDance pages and external HTTPS login redirects (e.g. watcha.cn) in WebView!
      return false;
    }

    offerExternal(url);
    return true;
  }

  void offerExternal(String url) {
    if (externalPrompt || isFinishing() || isDestroyed()) return;
    externalPrompt = true;
    new AlertDialog.Builder(this)
        .setTitle("系统浏览器继续")
        .setMessage("此页面将跳转到：" + Uri.parse(url).getHost() + "。是否使用系统浏览器打开？")
        .setNegativeButton("取消", null)
        .setPositiveButton("打开浏览器", (d, w) -> {
          if (mode.equals("authorize")) openBrowser();
          else openExternal(url);
        })
        .setOnDismissListener(d -> externalPrompt = false)
        .show();
  }

  void newAuthorization(boolean headless) throws Exception {
    pending = TokenDanceProtocol.begin(headless ? null : TokenDanceProtocol.NATIVE_CALLBACK_URL, System.currentTimeMillis());
    secure.saveTokenDanceAuthorization(pending);
    manual = headless;
  }

  void restart(boolean headless) {
    if (busy) return;
    try {
      newAuthorization(headless);
      notice = "";
      manual = headless;
      updateControlCard();
      if (web != null && pending != null) {
        web.loadUrl(TokenDanceProtocol.authorizationUrl(pending));
      }
    } catch (Exception e) {
      fail("无法发起授权，请重试。");
    }
  }

  void openBrowser() {
    if (busy) return;
    try {
      if (pending == null) newAuthorization(false);
      notice = "已在系统浏览器打开授权页面。完成授权后将自动返回；若未自动返回，可复制页面授权码在此粘贴。";
      String url = TokenDanceProtocol.authorizationUrl(pending);
      updateControlCard();
      openExternal(url);
    } catch (Exception e) {
      fail("无法打开浏览器，请重试。");
    }
  }

  void openExternal(String url) {
    TokenDanceProtocol.Page page = TokenDanceProtocol.classifyPageUrl(url);
    if (page != TokenDanceProtocol.Page.INTERNAL && page != TokenDanceProtocol.Page.EXTERNAL_HTTPS && page != TokenDanceProtocol.Page.PAYMENT) return;
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (ActivityNotFoundException e) {
      fail("没有可打开此链接的应用，请检查浏览器或支付宝安装状态。");
    }
  }

  void finishAuthorization(String value, boolean headless) {
    if (busy || pending == null) return;
    try {
      String code = headless
          ? pending.readHeadlessCode(value, System.currentTimeMillis())
          : pending.readCallback(value, System.currentTimeMillis());
      if (codeInput != null) codeInput.setText("");
      String verifier = pending.verifier;
      notice = "";
      run(client -> client.exchange(code, verifier), result -> {
        secure.connectTokenDance((String) result);
        pending = null;
        setResult(RESULT_OK);
        Toast.makeText(this, "已连接 TokenDance，可以开始查询了", Toast.LENGTH_LONG).show();
        finish();
      }, "授权未完成，请重新授权后重试。");
    } catch (Exception e) {
      fail("授权码无效、已过期或与当前流程不匹配，请重新授权。");
      if (!headless) {
        manual = true;
        notice = "自动授权未完成，已切换为一次性授权码方式。请重新确认授权并粘贴新授权码。";
        updateControlCard();
      }
    }
  }

  boolean connected() {
    try {
      return "tokendance".equals(secure.getProvider().optString("id"))
          && TokenDanceCatalog.isGateway(secure.getProvider().optString("base_url")) && !secure.getApiKey().isEmpty();
    } catch (Exception e) {
      return false;
    }
  }

  void showRecharge() {
    if (rechargeContent == null) return;
    rechargeContent.removeAllViews();

    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setBackground(shape(cardColor, 14, borderColor));
    card.setPadding(dp(16), dp(16), dp(16), dp(16));
    LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-1, -2);
    cLp.setMargins(dp(16), dp(16), dp(16), dp(16));
    card.setLayoutParams(cLp);

    TextView title = text("为你的词元跳动账户充值", 18, true);
    card.addView(title);

    TextView sub = text("金额和支付由你确认，到账后可返回继续查询。", 13, false);
    sub.setTextColor(mutedColor);
    sub.setPadding(0, dp(4), 0, dp(8));
    card.addView(sub);

    status = text(creationInterrupted ? "上次创建充值会话的过程被中断，可能已经建单。请先检查账户，确认后再重新创建。" : notice, 13, false);
    status.setTextColor(creationInterrupted || notice.contains("失败") ? errorColor : subtleColor);
    status.setVisibility((creationInterrupted || !notice.isEmpty()) ? View.VISIBLE : View.GONE);
    card.addView(status);

    if (!connected()) {
      Button connectBtn = primaryButton("先连接 TokenDance", () -> {
        mode = "authorize";
        show();
      });
      LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(-1, dp(42));
      cbLp.topMargin = dp(12);
      card.addView(connectBtn, cbLp);
      rechargeContent.addView(card);
      return;
    }

    TextView amtLabel = text("充值金额（元）", 12, true);
    amtLabel.setPadding(0, dp(12), 0, dp(4));
    card.addView(amtLabel);

    EditText amount = new EditText(this);
    amount.setHint("充值金额（整数元）");
    amount.setText(payment == null ? paymentAmount : String.valueOf(payment.amount));
    amount.setTextColor(foregroundColor);
    amount.setHintTextColor(mutedColor);
    amount.setInputType(InputType.TYPE_CLASS_NUMBER);
    amount.setEnabled(!busy && (payment == null || !payment.status.equals("pending")));
    amount.setBackground(shape(inputBgColor, 10, borderColor));
    amount.setPadding(dp(12), dp(10), dp(12), dp(10));
    card.addView(amount);

    if (payment == null || !payment.status.equals("pending")) {
      Button createBtn = primaryButton("创建充值会话", () -> {
        String value = amount.getText().toString().trim();
        if (!value.matches("[0-9]{1,6}") || Integer.parseInt(value) < 1 || Integer.parseInt(value) > 100000) {
          fail("请输入 1 至 100000 元之间的整数金额。");
          return;
        }
        paymentAmount = value;
        Runnable create = () -> createPayment(Integer.parseInt(value));
        if (creationInterrupted) {
          new AlertDialog.Builder(this)
              .setMessage("确认已经检查账户，并重新创建充值会话？")
              .setNegativeButton("取消", null)
              .setPositiveButton("重新创建", (d, w) -> create.run())
              .show();
        } else {
          create.run();
        }
      });
      LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(-1, dp(42));
      cbLp.topMargin = dp(14);
      card.addView(createBtn, cbLp);
    }

    if (payment != null) {
      if (payment.status.equals("pending") && payment.alipayUrl != null && payment.expiredAt > System.currentTimeMillis() / 1000L) {
        Button payBtn = primaryButton("支付宝支付 ¥" + payment.amount, () -> confirmPayment(payment.alipayUrl));
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(-1, dp(42));
        pbLp.topMargin = dp(10);
        card.addView(payBtn, pbLp);
      }
      Button checkBtn = outlineButton("检查到账状态", this::checkPayment);
      LinearLayout.LayoutParams ckbLp = new LinearLayout.LayoutParams(-1, dp(40));
      ckbLp.topMargin = dp(10);
      card.addView(checkBtn, ckbLp);

      if (payment.status.equals("pending") && payment.expiredAt > System.currentTimeMillis() / 1000L) {
        TextView expTip = text("充值会话将在到期后停止检查。返回应用时会自动确认到账。", 11.5f, false);
        expTip.setTextColor(mutedColor);
        expTip.setPadding(0, dp(8), 0, 0);
        card.addView(expTip);
      }
    }

    rechargeContent.addView(card);
  }

  void createPayment(int amount) {
    try {
      secure.saveTokenDancePayment(new org.json.JSONObject().put("creating", true).put("amount", amount));
      creatingPayment = true;
      creationInterrupted = false;
      String key = secure.getApiKey();
      run(client -> client.createPayment(key, amount), result -> {
        payment = (TokenDanceApi.PaymentSession) result;
        savePayment();
        creatingPayment = false;
        pollFailures = 0;
        updatePaymentNotice();
        showRecharge();
        schedulePoll(3000);
      }, "充值会话创建未确认，请先检查账户后再重试。");
    } catch (Exception e) {
      creatingPayment = false;
      fail("无法创建充值会话，请检查服务连接。");
    }
  }

  void restorePayment() {
    try {
      org.json.JSONObject data = secure.pendingTokenDancePayment();
      if (data == null) return;
      creationInterrupted = data.optBoolean("creating");
      if (creationInterrupted) return;
      payment = new TokenDanceApi.PaymentSession(data.getString("id"), data.getInt("amount"), data.getString("status"),
          data.getLong("expiry"), data.isNull("url") ? null : data.getString("url"));
      updatePaymentNotice();
    } catch (Exception e) {
      payment = null;
      creationInterrupted = true;
      notice = "无法恢复上次充值会话，请先检查账户。";
    }
  }

  void savePayment() throws Exception {
    secure.saveTokenDancePayment(new org.json.JSONObject().put("id", payment.id).put("amount", payment.amount)
        .put("status", payment.status).put("expiry", payment.expiredAt)
        .put("url", payment.alipayUrl == null ? org.json.JSONObject.NULL : payment.alipayUrl));
  }

  void confirmPayment(String url) {
    if (mode.equals("recharge") && payment != null && (!payment.status.equals("pending") || payment.expiredAt <= System.currentTimeMillis() / 1000L)) {
      fail("充值会话已结束或过期，请检查到账状态。");
      return;
    }
    if (TokenDanceProtocol.classifyPageUrl(url) != TokenDanceProtocol.Page.PAYMENT) {
      fail("服务未返回可用的支付宝链接，请在电脑端扫码或稍后重试。");
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("打开支付宝")
        .setMessage("前往支付宝确认金额并完成支付？")
        .setNegativeButton("取消", null)
        .setPositiveButton("打开支付宝", (d, w) -> openExternal(url))
        .show();
  }

  void updatePaymentNotice() {
    if (payment == null) return;
    notice = payment.status.equals("paid") ? "充值已到账，可以返回继续查询。"
        : payment.status.equals("pending") && payment.expiredAt <= System.currentTimeMillis() / 1000L ? "支付入口已到期，实际到账状态尚待服务端确认。请检查到账状态后再操作。"
        : payment.status.equals("pending") ? payment.alipayUrl == null ? "暂无支付链接，可检查到账状态或稍后重新创建。" : "点击支付宝支付，返回后自动确认到账。"
        : payment.status.equals("expired") ? "充值会话已过期，请重新创建。" : "本次充值未完成，状态：" + payment.status;
  }

  void schedulePoll(long delay) {
    handler.removeCallbacks(poll);
    if (foreground && mode.equals("recharge") && payment != null && payment.status.equals("pending") && pollFailures < 3
        && (!expiryChecked || payment.expiredAt > System.currentTimeMillis() / 1000L))
      handler.postDelayed(poll, delay);
  }

  void checkPayment() {
    if (busy || payment == null || !mode.equals("recharge") || !foreground) return;
    expiryChecked = payment.expiredAt <= System.currentTimeMillis() / 1000L;
    final String id = payment.id;
    try {
      String key = secure.getApiKey();
      run(client -> client.getPaymentStatus(key, id), result -> {
        TokenDanceApi.PaymentSession next = (TokenDanceApi.PaymentSession) result;
        if (payment == null || !id.equals(payment.id) || next.amount != payment.amount)
          throw new IllegalStateException("充值会话不匹配");
        String link = next.status.equals("pending") ? (next.alipayUrl == null ? payment.alipayUrl : next.alipayUrl) : null;
        payment = new TokenDanceApi.PaymentSession(next.id, next.amount, next.status, next.expiredAt, link);
        savePayment();
        pollFailures = 0;
        updatePaymentNotice();
        showRecharge();
        schedulePoll(3000);
      }, "暂时无法确认到账，可点检查到账状态重试。");
    } catch (Exception e) {
      fail("无法读取 TokenDance 密钥，请重新连接。");
    }
  }

  interface Request { Object call(TokenDanceApi client) throws Exception; }
  interface Complete { void accept(Object result) throws Exception; }

  void run(Request request, Complete completed, String failure) {
    if (busy) return;
    busy = true;
    TokenDanceApi client = clientFactory.get();
    active = client;
    final int token = ++generation;
    if (mode.equals("recharge")) showRecharge();
    else updateControlCard();

    worker.execute(() -> {
      try {
        Object result = request.call(client);
        handler.post(() -> {
          if (isFinishing() || isDestroyed() || token != generation) return;
          active = null;
          busy = false;
          try {
            completed.accept(result);
          } catch (Exception e) {
            if (creatingPayment) { creatingPayment = false; creationInterrupted = true; }
            fail(failure);
          }
        });
      } catch (Exception error) {
        handler.post(() -> {
          if (isFinishing() || isDestroyed() || token != generation) return;
          active = null;
          busy = false;
          pollFailures++;
          if (creatingPayment) { creatingPayment = false; creationInterrupted = true; }
          fail(failure);
          if (mode.equals("recharge")) schedulePoll(10000);
        });
      }
    });
  }

  void fail(String message) {
    notice = message;
    if (mode.equals("recharge")) showRecharge();
    else updateControlCard();
  }

  void destroyWeb() {
    handler.removeCallbacks(loadTimeout);
    if (web != null) {
      web.stopLoading();
      web.setWebViewClient(new WebViewClient());
      web.destroy();
      web = null;
    }
  }

  void leave() {
    if (busy) {
      new AlertDialog.Builder(this)
          .setMessage("离开会取消当前请求，之后可以重新操作。")
          .setNegativeButton("继续等待", null)
          .setPositiveButton("离开", (d, w) -> finish())
          .show();
    } else if (web != null && web.canGoBack()) {
      web.goBack();
    } else {
      finish();
    }
  }

  @Override @android.annotation.SuppressLint("GestureBackNavigation")
  public void onBackPressed() {
    leave();
  }

  @Override protected void onResume() {
    super.onResume();
    foreground = true;
    expiryChecked = false;
    schedulePoll(100);
  }

  @Override protected void onPause() {
    foreground = false;
    handler.removeCallbacks(poll);
    super.onPause();
  }

  @Override protected void onSaveInstanceState(Bundle state) {
    state.putString("mode", mode);
    state.putBoolean("manual", manual);
    if (payment != null) {
      state.putString("payment_id", payment.id);
      state.putInt("payment_amount", payment.amount);
      state.putString("payment_status", payment.status);
      state.putLong("payment_expiry", payment.expiredAt);
    }
    if (codeInput != null) codeInput.setText("");
    super.onSaveInstanceState(state);
  }

  @Override protected void onDestroy() {
    generation++;
    if (active != null) active.cancel();
    handler.removeCallbacksAndMessages(null);
    destroyWeb();
    worker.shutdownNow();
    super.onDestroy();
  }
}
