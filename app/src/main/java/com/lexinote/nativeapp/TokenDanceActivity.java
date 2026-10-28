package com.lexinote.nativeapp;

import android.app.*;
import android.content.*;
import android.graphics.Color;
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
  int backgroundColor=Color.WHITE, foregroundColor=Color.rgb(15,23,42), accentColor=Color.rgb(16,185,129);
  float fontScale=1f;
  int generation, pollFailures;
  final Runnable poll = this::checkPayment;
  final Runnable loadTimeout = () -> {
    if (!isFinishing() && mode.equals("authorize") && !busy) {
      notice = "授权页面加载超时，可重新加载，或改用一次性授权码。";
      if (status != null) status.setText(notice);
    }
  };

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    secure = new SecureSettings(this);
    try {
      org.json.JSONObject settings=new Repository(this).load().settings();
      String theme=settings.optString("theme_mode","system");
      boolean dark=theme.equals("dark") || (theme.equals("system") &&
          (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES);
      backgroundColor=Color.parseColor(dark?"#0F172A":"#FFFFFF");
      foregroundColor=Color.parseColor(dark?"#F8FAFC":"#0F172A");
      String accent=settings.optString("accent_color","#10B981");
      if(accent.matches("#[0-9a-fA-F]{6}")) accentColor=Color.parseColor(accent);
      String size=settings.optString("font_size","medium"); fontScale=size.equals("large")?1.15f:size.equals("small")?.9f:1;
      getWindow().getDecorView().setSystemUiVisibility(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
      getWindow().setStatusBarColor(backgroundColor); getWindow().setNavigationBarColor(backgroundColor);
    } catch(Exception ignored) { }
    String requested = getIntent().getStringExtra("mode");
    if (state != null) requested = state.getString("mode",requested);
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
        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::leave);
    show();
  }

  int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
  TextView text(String s, int size) {
    TextView t = new TextView(this); t.setText(s); t.setTextColor(foregroundColor); t.setTextSize(size*fontScale);
    t.setPadding(0, dp(6), 0, dp(6)); return t;
  }
  Button button(String label, Runnable action) {
    Button b = new Button(this); b.setText(label); b.setAllCaps(false);
    b.setTextColor(accentColor); b.setTextSize(13*fontScale); b.setMinHeight(dp(44));
    b.setOnClickListener(v -> action.run()); b.setEnabled(!busy); return b;
  }
  void rowButtons(LinearLayout parent, String left, Runnable l, String right, Runnable r) {
    LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
    row.addView(button(left,l), new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(button(right,r), new LinearLayout.LayoutParams(0, -2, 1)); parent.addView(row);
  }

  void show() {
    destroyWeb();
    LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(backgroundColor);
    root.setOnApplyWindowInsetsListener((v, insets) -> {
      if (Build.VERSION.SDK_INT >= 30) {
        android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
        v.setPadding(bars.left,bars.top,bars.right,bars.bottom);
      } else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
      return insets;
    });
    setContentView(root); root.requestApplyInsets();
    LinearLayout bar = new LinearLayout(this); bar.setPadding(dp(10),0,dp(10),0);
    bar.addView(button("返回", this::leave));
    String title = mode.equals("authorize") ? "连接 TokenDance" : mode.equals("recharge") ? "账户充值"
        : mode.equals("usage") ? "调用记录" : mode.equals("help") ? "使用帮助" : "账户与密钥";
    bar.addView(text(title,18)); root.addView(bar);
    ScrollView controls = new ScrollView(this);
    content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(16),0,dp(16),dp(12));
    controls.addView(content);
    if (mode.equals("recharge")) {
      root.addView(controls, new LinearLayout.LayoutParams(-1,0,1)); showRecharge(); return;
    }
    root.addView(controls, new LinearLayout.LayoutParams(-1,-2));
    status = text(notice,13); content.addView(status);
    if (mode.equals("authorize")) {
      content.addView(text(manual ? "登录并确认授权后，粘贴页面提供的一次性授权码（10 分钟内有效）。" : "登录并确认授权后自动连接。",14));
      rowButtons(content,"重新授权",() -> restart(false),"改用一次性授权码",() -> restart(true));
      content.addView(button("系统浏览器打开", this::openBrowser));
      if (manual) {
        codeInput = new EditText(this); codeInput.setHint("一次性授权码"); codeInput.setSingleLine(true);
        codeInput.setTextColor(foregroundColor); codeInput.setHintTextColor(Color.GRAY);
        codeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        codeInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
        codeInput.setSaveEnabled(false); codeInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        content.addView(codeInput); content.addView(button("完成连接", () -> finishAuthorization(codeInput.getText().toString(),true)));
      }
      if (busy) { content.addView(text("正在安全保存授权…",14)); return; }
      if (pending == null) return;
    } else content.addView(button("系统浏览器打开", () -> openExternal(portalUrl())));
    content.addView(button("重新加载页面", () -> { if(web!=null) web.reload(); }));
    web = new WebView(this);
    WebSettings settings = web.getSettings();
    settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
    settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    settings.setJavaScriptCanOpenWindowsAutomatically(false); settings.setSupportMultipleWindows(false);
    web.setSaveEnabled(false);
    web.setWebViewClient(new WebViewClient() {
      @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        if(request.isForMainFrame() && TokenDanceProtocol.classifyPageUrl(request.getUrl().toString())==TokenDanceProtocol.Page.EXTERNAL_HTTPS) {
          handler.post(() -> offerExternal(request.getUrl().toString()));
          return new WebResourceResponse("text/plain","UTF-8",new java.io.ByteArrayInputStream(
              "Please continue in the system browser.".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        return null;
      }
      @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        if (!request.isForMainFrame()) {
          TokenDanceProtocol.Page page=TokenDanceProtocol.classifyPageUrl(request.getUrl().toString());
          return page!=TokenDanceProtocol.Page.INTERNAL && page!=TokenDanceProtocol.Page.EXTERNAL_HTTPS;
        }
        return navigate(request.getUrl().toString());
      }
      @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return navigate(url); }
      @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
        handler.removeCallbacks(loadTimeout);
        if (TokenDanceProtocol.isNativeCallback(url) && mode.equals("authorize")) {
          view.stopLoading(); finishAuthorization(url,false); return;
        }
        if(TokenDanceProtocol.classifyPageUrl(url)==TokenDanceProtocol.Page.EXTERNAL_HTTPS) {
          view.stopLoading(); offerExternal(url); return;
        }
        try { status.setText("正在加载：" + Uri.parse(url).getHost()); } catch(Exception ignored) { }
        if(mode.equals("authorize")) handler.postDelayed(loadTimeout,20000);
      }
      @Override public void onPageFinished(WebView view, String url) {
        handler.removeCallbacks(loadTimeout);
        if (!busy && !TokenDanceProtocol.isNativeCallback(url)) {
          String host = Uri.parse(url).getHost();
          status.setText(notice.isEmpty() ? "当前网站：" + (host == null ? "tokendance.space" : host) : notice);
        }
      }
      @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        if (request.isForMainFrame()) { handler.removeCallbacks(loadTimeout); status.setText("页面加载失败，可重新加载或使用系统浏览器。"); }
      }
    });
    root.addView(web, new LinearLayout.LayoutParams(-1,0,1));
    web.loadUrl(mode.equals("authorize") ? TokenDanceProtocol.authorizationUrl(pending) : portalUrl());
  }

  String portalUrl() { return TokenDanceProtocol.ORIGIN + (mode.equals("usage") ? "/activity/requests" : mode.equals("help") ? "/docs/quickstart" : "/keys"); }
  boolean navigate(String url) {
    if ("about:blank".equals(url)) return false;
    TokenDanceProtocol.Page page = TokenDanceProtocol.classifyPageUrl(url);
    if (page == TokenDanceProtocol.Page.CALLBACK) {
      if(mode.equals("authorize") && TokenDanceProtocol.isNativeCallback(url)) finishAuthorization(url,false);
      return true;
    }
    if (page == TokenDanceProtocol.Page.PAYMENT) { confirmPayment(url); return true; }
    if (page == TokenDanceProtocol.Page.INTERNAL) return false;
    if (page == TokenDanceProtocol.Page.EXTERNAL_HTTPS) {
      offerExternal(url);
      return true;
    }
    notice = "此链接无法在应用中打开，请使用系统浏览器。"; if(status!=null) status.setText(notice); return true;
  }
  void offerExternal(String url) {
    if(externalPrompt || isFinishing() || isDestroyed()) return;
    externalPrompt=true;
    new AlertDialog.Builder(this).setTitle("系统浏览器继续")
        .setMessage("此页面将跳转到："+Uri.parse(url).getHost()+"。授权可在系统浏览器继续，完成后返回粘贴一次性授权码。")
        .setNegativeButton("取消",null).setPositiveButton("打开浏览器",(d,w) -> {
          if(mode.equals("authorize")) openBrowser(); else openExternal(url);
        }).setOnDismissListener(d -> externalPrompt=false).show();
  }
  void newAuthorization(boolean headless) throws Exception {
    pending = TokenDanceProtocol.begin(headless ? null : TokenDanceProtocol.NATIVE_CALLBACK_URL,System.currentTimeMillis());
    secure.saveTokenDanceAuthorization(pending); manual = headless;
  }
  void restart(boolean headless) {
    if (busy) return;
    try { newAuthorization(headless); notice=""; show(); } catch(Exception e) { fail("无法发起授权，请重试。"); }
  }
  void openBrowser() {
    if (busy) return;
    try {
      // The public HTTPS App Link currently belongs to the original Expo package.
      // Use a new headless PKCE flow in an external browser; never pretend it can return here.
      if (pending == null || pending.callback != null) newAuthorization(true);
      notice="请在系统浏览器确认授权，然后返回粘贴一次性授权码。";
      String url=TokenDanceProtocol.authorizationUrl(pending); show(); openExternal(url);
    } catch(Exception e) { fail("无法打开浏览器，请重试。"); }
  }
  void openExternal(String url) {
    TokenDanceProtocol.Page page=TokenDanceProtocol.classifyPageUrl(url);
    if(page!=TokenDanceProtocol.Page.INTERNAL && page!=TokenDanceProtocol.Page.EXTERNAL_HTTPS && page!=TokenDanceProtocol.Page.PAYMENT) return;
    try { startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))); }
    catch(ActivityNotFoundException e) { fail("没有可打开此链接的应用，请检查浏览器或支付宝安装状态。"); }
  }
  void finishAuthorization(String value, boolean headless) {
    if(busy || pending==null) return;
    try {
      String code = headless ? pending.readHeadlessCode(value,System.currentTimeMillis()) : pending.readCallback(value,System.currentTimeMillis());
      if(codeInput!=null) codeInput.setText("");
      String verifier=pending.verifier;
      run(client -> client.exchange(code,verifier), result -> {
        secure.connectTokenDance((String)result);
        pending=null;
        setResult(RESULT_OK); Toast.makeText(this,"已连接 TokenDance，可以开始查询了",Toast.LENGTH_LONG).show(); finish();
      }, "授权未完成，请重新授权后重试。");
    } catch(Exception e) { fail("授权码无效、已过期或与当前流程不匹配，请重新授权。"); }
  }

  boolean connected() {
    try { return "tokendance".equals(secure.getProvider().optString("id"))
        && TokenDanceCatalog.isGateway(secure.getProvider().optString("base_url")) && !secure.getApiKey().isEmpty(); }
    catch(Exception e) { return false; }
  }
  void showRecharge() {
    content.addView(text("为你的词元跳动账户充值",20));
    content.addView(text("金额和支付由你确认，到账后可返回继续查询。",14));
    status=text(creationInterrupted ? "上次创建充值会话的过程被中断，可能已经建单。请先检查账户，确认后再重新创建。" : notice,14); content.addView(status);
    if(!connected()) {
      content.addView(button("先连接 TokenDance",() -> { mode="authorize"; restart(false); })); return;
    }
    EditText amount=new EditText(this); amount.setHint("充值金额（整数元）"); amount.setText(payment==null ? paymentAmount : String.valueOf(payment.amount));
    amount.setTextColor(foregroundColor); amount.setHintTextColor(Color.GRAY);
    amount.setInputType(InputType.TYPE_CLASS_NUMBER); amount.setEnabled(!busy && (payment==null || !payment.status.equals("pending")));
    content.addView(amount);
    if(payment==null || !payment.status.equals("pending")) {
      content.addView(button("创建充值会话",() -> {
        String value=amount.getText().toString().trim();
        if(!value.matches("[0-9]{1,6}") || Integer.parseInt(value)<1 || Integer.parseInt(value)>100000) { fail("请输入 1 至 100000 元之间的整数金额。"); return; }
        paymentAmount=value;
        Runnable create=() -> createPayment(Integer.parseInt(value));
        if(creationInterrupted) new AlertDialog.Builder(this).setMessage("确认已经检查账户，并重新创建充值会话？")
            .setNegativeButton("取消",null).setPositiveButton("重新创建",(d,w) -> create.run()).show();
        else create.run();
      }));
    }
    if(payment!=null) {
      if(payment.status.equals("pending") && payment.alipayUrl!=null && payment.expiredAt>System.currentTimeMillis()/1000L)
        content.addView(button("支付宝支付 ¥"+payment.amount,() -> confirmPayment(payment.alipayUrl)));
      content.addView(button("检查到账状态",this::checkPayment));
      if(payment.status.equals("pending") && payment.expiredAt>System.currentTimeMillis()/1000L)
        content.addView(text("充值会话将在到期后停止检查。返回应用时会自动确认到账。",12));
    }
  }
  void createPayment(int amount) {
    try {
      secure.saveTokenDancePayment(new org.json.JSONObject().put("creating",true).put("amount",amount));
      creatingPayment=true; creationInterrupted=false;
      String key=secure.getApiKey();
      run(client -> client.createPayment(key,amount),result -> {
        payment=(TokenDanceApi.PaymentSession)result; savePayment();
        creatingPayment=false; pollFailures=0; updatePaymentNotice(); show(); schedulePoll(3000);
      },"充值会话创建未确认，请先检查账户后再重试。");
    } catch(Exception e) { creatingPayment=false; fail("无法创建充值会话，请检查服务连接。"); }
  }
  void restorePayment() {
    try {
      org.json.JSONObject data=secure.pendingTokenDancePayment();
      if(data==null) return;
      creationInterrupted=data.optBoolean("creating");
      if(creationInterrupted) return;
      payment=new TokenDanceApi.PaymentSession(data.getString("id"),data.getInt("amount"),data.getString("status"),
          data.getLong("expiry"),data.isNull("url") ? null : data.getString("url"));
      updatePaymentNotice();
    } catch(Exception e) { payment=null; creationInterrupted=true; notice="无法恢复上次充值会话，请先检查账户。"; }
  }
  void savePayment() throws Exception {
    secure.saveTokenDancePayment(new org.json.JSONObject().put("id",payment.id).put("amount",payment.amount)
        .put("status",payment.status).put("expiry",payment.expiredAt).put("url",payment.alipayUrl==null ? org.json.JSONObject.NULL : payment.alipayUrl));
  }
  void confirmPayment(String url) {
    if(mode.equals("recharge") && payment!=null && (!payment.status.equals("pending") || payment.expiredAt<=System.currentTimeMillis()/1000L)) {
      fail("充值会话已结束或过期，请检查到账状态。"); return;
    }
    if(TokenDanceProtocol.classifyPageUrl(url)!=TokenDanceProtocol.Page.PAYMENT) { fail("服务未返回可用的支付宝链接，请在电脑端扫码或稍后重试。"); return; }
    new AlertDialog.Builder(this).setTitle("打开支付宝").setMessage("前往支付宝确认金额并完成支付？")
        .setNegativeButton("取消",null).setPositiveButton("打开支付宝",(d,w) -> openExternal(url)).show();
  }
  void updatePaymentNotice() {
    if(payment==null) return;
    notice=payment.status.equals("paid") ? "充值已到账，可以返回继续查询。"
        : payment.status.equals("pending") && payment.expiredAt<=System.currentTimeMillis()/1000L ? "支付入口已到期，实际到账状态尚待服务端确认。请检查到账状态后再操作。"
        : payment.status.equals("pending") ? payment.alipayUrl==null ? "暂无支付链接，可检查到账状态或稍后重新创建。" : "点击支付宝支付，返回后自动确认到账。"
        : payment.status.equals("expired") ? "充值会话已过期，请重新创建。" : "本次充值未完成，状态："+payment.status;
  }
  void schedulePoll(long delay) {
    handler.removeCallbacks(poll);
    if(foreground && mode.equals("recharge") && payment!=null && payment.status.equals("pending") && pollFailures<3
        && (!expiryChecked || payment.expiredAt>System.currentTimeMillis()/1000L))
      handler.postDelayed(poll,delay);
  }
  void checkPayment() {
    if(busy || payment==null || !mode.equals("recharge") || !foreground) return;
    // The user may have paid before expiry and returned later. Only the service can
    // decide paid/expired; after local expiry stop automatic polling after one check.
    expiryChecked=payment.expiredAt<=System.currentTimeMillis()/1000L;
    final String id=payment.id;
    try { String key=secure.getApiKey(); run(client -> client.getPaymentStatus(key,id), result -> {
      TokenDanceApi.PaymentSession next=(TokenDanceApi.PaymentSession)result;
      if(payment==null || !id.equals(payment.id) || next.amount!=payment.amount) throw new IllegalStateException("充值会话不匹配");
      String link=next.status.equals("pending") ? (next.alipayUrl==null ? payment.alipayUrl : next.alipayUrl) : null;
      payment=new TokenDanceApi.PaymentSession(next.id,next.amount,next.status,next.expiredAt,link);
      savePayment(); pollFailures=0; updatePaymentNotice(); show(); schedulePoll(3000);
    },"暂时无法确认到账，可点检查到账状态重试。"); } catch(Exception e) { fail("无法读取 TokenDance 密钥，请重新连接。"); }
  }

  interface Request { Object call(TokenDanceApi client) throws Exception; }
  interface Complete { void accept(Object result) throws Exception; }
  void run(Request request, Complete completed, String failure) {
    if(busy) return;
    busy=true; TokenDanceApi client=clientFactory.get(); active=client; final int token=++generation; show();
    worker.execute(() -> {
      try { Object result=request.call(client); handler.post(() -> {
        if(isFinishing() || isDestroyed() || token!=generation) return;
        active=null; busy=false;
        try { completed.accept(result); } catch(Exception e) {
          if(creatingPayment) { creatingPayment=false; creationInterrupted=true; }
          fail(failure);
        }
      }); } catch(Exception error) { handler.post(() -> {
        if(isFinishing() || isDestroyed() || token!=generation) return;
        active=null; busy=false; pollFailures++;
        if(creatingPayment) { creatingPayment=false; creationInterrupted=true; }
        fail(failure); if(mode.equals("recharge")) schedulePoll(10000);
      }); }
    });
  }
  void fail(String message) { notice=message; show(); }
  void destroyWeb() {
    handler.removeCallbacks(loadTimeout);
    if(web!=null) { web.stopLoading(); web.setWebViewClient(new WebViewClient()); web.destroy(); web=null; }
  }
  void leave() {
    if(busy) new AlertDialog.Builder(this).setMessage("离开会取消当前请求，之后可以重新操作。")
        .setNegativeButton("继续等待",null).setPositiveButton("离开",(d,w)->finish()).show();
    else if(web!=null && web.canGoBack()) web.goBack(); else finish();
  }
  // API 33+ uses the native dispatcher registered in onCreate; this covers older devices.
  @Override @android.annotation.SuppressLint("GestureBackNavigation")
  public void onBackPressed() { leave(); }
  @Override protected void onResume() { super.onResume(); foreground=true; expiryChecked=false; schedulePoll(100); }
  @Override protected void onPause() { foreground=false; handler.removeCallbacks(poll); super.onPause(); }
  @Override protected void onSaveInstanceState(Bundle state) {
    state.putString("mode",mode);
    state.putBoolean("manual",manual);
    if(payment!=null) { state.putString("payment_id",payment.id); state.putInt("payment_amount",payment.amount);
      state.putString("payment_status",payment.status); state.putLong("payment_expiry",payment.expiredAt); }
    // Never save code, verifier, callback URL or payment URL in instance state.
    if(codeInput!=null) codeInput.setText("");
    super.onSaveInstanceState(state);
  }
  @Override protected void onDestroy() {
    generation++; if(active!=null) active.cancel(); handler.removeCallbacksAndMessages(null); destroyWeb(); worker.shutdownNow(); super.onDestroy();
  }
}
