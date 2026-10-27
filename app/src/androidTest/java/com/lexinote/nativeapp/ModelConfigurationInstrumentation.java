package com.lexinote.nativeapp;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;

/** Offline settings acceptance and opt-in live use. The supplied credential is never reported. */
public final class ModelConfigurationInstrumentation extends Instrumentation {
  MainActivity a;
  JSONObject before;
  Map<String, ?> originalPreferences;
  String credential;
  boolean live;
  final List<String> passed = new ArrayList<>();

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    live = args != null && "true".equals(args.getString("live"));
    credential = args == null ? "" : args.getString("credential", "");
    if (args != null) args.remove("credential");
    start();
  }

  @Override
  public void onStart() {
    int outcome = Activity.RESULT_OK;
    Bundle result = new Bundle();
    try {
      a =
          (MainActivity)
              startActivitySync(
                  new Intent(getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      waitForIdleSync();
      main(
          () -> {
            before = a.library.snapshot();
            originalPreferences =
                new HashMap<>(a.getSharedPreferences("provider_private", 0).getAll());
          });
      main(this::credentialIsolationAndMigration);
      passed.add(
          "legacy encrypted migration, independent provider credentials, partial-save compatibility"
              + " and destination guard");
      main(this::defaultProviderLayout);
      passed.add("TokenDance default editor and collapsed alternative providers");
      offlineEditor();
      passed.add(
          "draft discovery/probes, model picker, explicit save, stale cancellation and secret-free"
              + " recreation state");
      if (live) liveUse();
      result.putString(
          "stream",
          "\nPASS: "
              + passed.size()
              + " model configuration groups\n"
              + String.join("\n", passed)
              + "\n");
    } catch (Throwable e) {
      outcome = Activity.RESULT_CANCELED;
      String message = e.getClass().getSimpleName() + ": " + e.getMessage();
      if (credential != null && !credential.isEmpty())
        message = message.replace(credential, "[redacted]");
      result.putString("stream", "\nFAIL after " + passed.size() + " groups: " + message + "\n");
    } finally {
      if (a != null)
        main(
            () -> {
              a.cancel();
              a.titles.reset();
              a.settingsUi.models.cancel();
              a.settingsUi.models.clearDraft();
              a.session = null;
              a.query.assistantView = null;
              if (before != null) {
                a.library = new Library(before);
                a.repository.save(a.library);
              }
              if (originalPreferences != null)
                restorePreferences(
                    a.getSharedPreferences("provider_private", 0), originalPreferences);
              a.finish();
            });
      credential = null;
    }
    finish(outcome, result);
  }

  void defaultProviderLayout() throws Exception {
    ProviderSettingsUi ui = a.settingsUi.models;
    a.secure.selectProvider("tokendance");
    ui.clearDraft();
    ui.expanded = false;
    a.page = 4;
    a.screen = "tabs";
    a.render();
    check(button(a.root, "获取模型列表") != null, "TokenDance model controls are hidden by default");
    check(edit(a.root, "留空保留当前供应商密钥") != null, "TokenDance key control is hidden by default");
    check(button(a.root, "DeepSeek (Flash 多模态)") == null, "Alternative providers are visible by default");
    check(edit(a.root, "Base URL") == null, "Manual settings are visible by default");
    button(a.root, "高级设置 · 模型与手动配置").performClick();
    check(button(a.root, "DeepSeek (Flash 多模态)") != null, "Advanced providers cannot be opened");
    button(a.root, "DeepSeek (Flash 多模态)").performClick();
    check("deepseek".equals(a.secure.getProvider().optString("id")), "Provider selection failed");
    button(a.root, "收起高级设置").performClick();
    check("deepseek".equals(a.secure.getProvider().optString("id")), "Collapsing switched the active provider");
    check(button(a.root, "获取模型列表") == null, "Other provider settings remain expanded");
    button(a.root, "高级设置 · 模型与手动配置").performClick();
    check(edit(a.root, "Base URL") != null, "Other provider manual settings cannot be reopened");
  }

  void credentialIsolationAndMigration() throws Exception {
    ContextWrapper isolated =
        new ContextWrapper(a) {
          @Override
          public SharedPreferences getSharedPreferences(String name, int mode) {
            return super.getSharedPreferences("model_configuration_test_" + name, mode);
          }
        };
    SharedPreferences prefs = isolated.getSharedPreferences("provider_private", 0);
    prefs.edit().clear().commit();
    try {
      SecureSettings secure = new SecureSettings(isolated);
      check(secure.getProviders().length() == 5, "Preset count");
      secure.selectProvider("openai");
      JSONObject p = secure.getProvider();
      secure.saveProvider(p, "alpha-device-fixture");
      secure.selectProvider("deepseek");
      check(secure.getApiKey().isEmpty(), "Provider switch reused a key");
      secure.saveProvider(secure.getProvider(), "beta-device-fixture");
      secure.selectProvider("openai");
      check(secure.getApiKey().equals("alpha-device-fixture"), "Key isolation lost original");
      check(!prefs.getAll().toString().contains("alpha-device-fixture"), "Plaintext key stored");
      secure.saveProvider(new JSONObject().put("model_id", "partial-model"), null);
      check(
          secure.getProvider().optString("name").equals(p.optString("name")),
          "Partial save lost name");
      JSONObject moved = secure.getProvider().put("base_url", "https://changed.invalid/v1");
      check(secure.getApiKeyFor(moved, "").isEmpty(), "Draft leaked key to changed destination");
      boolean rejected = false;
      try {
        secure.saveProvider(moved, null);
      } catch (IllegalArgumentException expected) {
        rejected = true;
      }
      check(rejected, "Saving a changed destination silently reused key");
      String legacyJson = secure.getProvider().toString();
      JSONObject legacy = new JSONObject(legacyJson);
      legacy.remove("id");
      legacy.remove("name");
      String cipher = prefs.getString("key_openai", null), iv = prefs.getString("iv_openai", null);
      prefs
          .edit()
          .clear()
          .putString("provider", legacy.toString())
          .putString("key", cipher)
          .putString("iv", iv)
          .commit();
      SecureSettings migrated = new SecureSettings(isolated);
      migrated.saveProvider(new JSONObject().put("model_id", "migrated-update"), null);
      check(
          migrated.getProvider().optString("model_id").equals("migrated-update"),
          "Legacy model lost");
      check(migrated.getApiKey().equals("alpha-device-fixture"), "Legacy encrypted key lost");
      check(!prefs.contains("key") && !prefs.contains("provider"), "Legacy storage not migrated");
      String custom =
          migrated.addProvider(
              new JSONObject()
                  .put("name", "Temporary")
                  .put("base_url", "https://custom.invalid/v1")
                  .put("model_id", "chat"),
              "custom-fixture");
      migrated.selectProvider(custom);
      migrated.deleteProvider(custom);
      check(migrated.getApiKey(custom).isEmpty(), "Deleted provider retained key");
    } finally {
      prefs.edit().clear().commit();
    }
  }

  void offlineEditor() throws Exception {
    AtomicReference<JSONObject> tested = new AtomicReference<>();
    CountDownLatch gate = new CountDownLatch(1);
    main(
        () -> {
          a.secure.selectProvider("deepseek");
          ProviderSettingsUi ui = a.settingsUi.models;
          ui.clearDraft();
          ui.expanded = true;
          ui.clientFactory =
              (config, key) ->
                  new ModelsApi(config, key) {
                    @Override
                    public JSONArray fetchModels() throws Exception {
                      return new JSONArray()
                          .put(
                              new JSONObject()
                                  .put("id", "fixture-flash")
                                  .put("name", "Fixture Flash")
                                  .put("vision_known", true)
                                  .put("supports_vision", true)
                                  .put("reasoning_levels", new JSONArray().put("low").put("high")));
                    }

                    @Override
                    public JSONObject testConnection(boolean vision) {
                      tested.set(config);
                      try {
                        gate.await(5, TimeUnit.SECONDS);
                      } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                      }
                      return new JSONObject();
                    }
                  };
          a.page = 4;
          a.screen = "tabs";
          a.render();
          edit(a.root, "留空保留当前供应商密钥").setText("draft-only-fixture");
          edit(a.root, "模型 ID").setText("draft-model");
          button(a.root, "获取模型列表").performClick();
        });
    await(
        "catalog loaded",
        () -> a.settingsUi.models.active == null && a.settingsUi.models.catalog.length() == 1,
        10);
    main(
        () -> {
          check(
              !a.secure.getProvider().optString("model_id").equals("draft-model"),
              "Discovery implicitly saved draft");
          button(a.root, "draft-model").performClick();
        });
    waitForIdleSync();
    clickAccessible("Fixture Flash");
    main(
        () -> {
          check(
              a.settingsUi.models.draft.config().optString("model_id").equals("fixture-flash"),
              "Picker ignored selection");
          check(
              a.settingsUi.models.draft.config().optBoolean("supports_vision"),
              "Picker ignored capability");
          Bundle state = new Bundle();
          a.settingsUi.models.saveState(state);
          check(!state.toString().contains("draft-only-fixture"), "Key leaked into saved state");
          ProviderSettingsUi restored = new ProviderSettingsUi(a);
          restored.restoreState(state);
          check(
              restored.draft.config().optString("model_id").equals("fixture-flash")
                  && restored.draft.enteredKey().isEmpty(),
              "Nonsecret draft recreation failed");
          restored.close();
          button(a.root, "文本连接测试").performClick();
        });
    await("draft probe dispatched", () -> tested.get() != null, 10);
    main(
        () -> {
          check(
              tested.get().optString("model_id").equals("fixture-flash"),
              "Probe used saved model instead of draft");
          edit(a.root, "模型 ID").setText("newer-model");
          check(a.settingsUi.models.active == null, "Editing did not cancel stale probe");
        });
    gate.countDown();
    waitForIdleSync();
    main(
        () -> {
          AiClient existing = new AiClient(a.secure.getProvider(), "unused-fixture");
          a.active = existing;
          a.settingsUi.models.save();
          check(a.active == existing, "Saving settings silently cancelled another chat");
          a.active = null;
          check(
              a.secure.getProvider().optString("model_id").equals("newer-model"),
              "Explicit save failed");
          a.settingsUi.models.clientFactory = ModelsApi::new;
          a.settingsUi.models.clearDraft();
        });
  }

  void liveUse() throws Exception {
    check(credential != null && !credential.isEmpty(), "Live credential missing");
    main(
        () -> {
          a.library = new Library();
          a.session = null;
          a.secure.selectProvider("deepseek");
          a.settingsUi.models.clearDraft();
          a.settingsUi.models.expanded = true;
          a.page = 4;
          a.screen = "tabs";
          a.render();
          edit(a.root, "Base URL").setText("https://api.deepseek.com");
          edit(a.root, "模型 ID").setText("deepseek-flash");
          edit(a.root, "留空保留当前供应商密钥").setText(credential);
          a.settingsUi.models.draft.set("reasoning_effort", "off");
          a.settingsUi.models.draft.set("supports_vision", true);
          button(a.root, "获取模型列表").performClick();
        });
    await("live model discovery", () -> a.settingsUi.models.active == null, 70);
    main(
        () -> {
          ProviderSettingsUi ui = a.settingsUi.models;
          check(ui.catalog.length() > 0, "Live model discovery: " + ui.status);
          JSONObject selected = null;
          for (int i = 0; i < ui.catalog.length(); i++)
            if (ui.catalog.optJSONObject(i).optString("id").equals("deepseek-flash"))
              selected = ui.catalog.optJSONObject(i);
          check(selected != null, "Requested model not listed");
          ui.draft.selectModel(selected);
          a.render();
          button(a.root, "文本连接测试").performClick();
        });
    await("live text connection", () -> a.settingsUi.models.active == null, 90);
    main(
        () ->
            check(
                a.settingsUi.models.status.contains("文本探测成功"),
                "Live text probe: " + a.settingsUi.models.status));
    passed.add("LIVE DeepSeek catalog and current-draft text connection");
    main(() -> button(a.root, "图片识别测试").performClick());
    await("live vision connection", () -> a.settingsUi.models.active == null, 90);
    main(
        () -> {
          check(
              a.settingsUi.models.status.contains("视觉探测成功"),
              "Live vision probe: " + a.settingsUi.models.status);
          button(a.root, "保存服务配置").performClick();
          check(
              a.secure.getProvider().optString("model_id").equals("deepseek-flash"),
              "Live model not saved");
          check(a.secure.getApiKey().equals(credential), "Saved credential unusable");
        });
    passed.add("LIVE blind color-image probe and encrypted configuration save");
    FeatureInstrumentation.capture(this, a, "model-configuration");
    main(
        () -> {
          a.page = 0;
          a.screen = "tabs";
          a.query.homeMode = "text";
          a.query.draft = "resilient";
          a.render();
          button(a.root, "✦  智能查询").performClick();
        });
    await("live lookup", () -> a.active == null, 95);
    if (onMain(() -> a.query.pending() != null)) {
      main(
          () -> {
            a.screen = "tabs";
            a.page = 0;
            a.render();
            button(a.root, "确认查询").performClick();
          });
      await("confirmed live lookup", () -> a.active == null, 95);
    }
    main(
        () -> {
          JSONObject result = a.session.optJSONObject("query_results");
          check(result != null, "Live lookup failed: " + a.session.optString("last_error"));
          check(
              result.optString("word").equalsIgnoreCase("resilient")
                  && result.optJSONArray("meanings").length() > 0,
              "Live lookup returned no usable meanings");
        });
    FeatureInstrumentation.capture(this, a, "live-query");
    main(() -> button(a.root, "打开 AI 助手继续提问").performClick());
    main(
        () -> {
          edit(a.root, "继续提问…").setText("请用一小段中文解释 resilient 与 resistant 的用法差别，不要查词，只回答问题。");
          button(a.root, "发送").performClick();
        });
    await("live streamed follow-up", () -> a.active == null, 95);
    main(
        () -> {
          check(
              !a.session.has("last_error"),
              "Live stream failed: "
                  + a.session.optString("last_error")
                  + " at "
                  + a.requestFailureLocation);
          JSONArray messages = a.query.messages();
          JSONObject last = messages.optJSONObject(messages.length() - 1);
          check(
              last != null
                  && last.optString("role").equals("assistant")
                  && last.optString("content").length() > 20,
              "Live assistant answer missing");
          check(
              last.optJSONObject("action") != null
                  && last.optJSONObject("action").optString("kind").equals("answer"),
              "Live stream did not produce an answer action");
        });
    FeatureInstrumentation.capture(this, a, "live-assistant");
    passed.add("LIVE actual query page and streamed assistant follow-up");
  }

  void clickAccessible(String label) throws Exception {
    Thread.sleep(300);
    AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
    for (int i = 0; root == null && i < 30; i++) {
      Thread.sleep(100);
      root = getUiAutomation().getRootInActiveWindow();
    }
    check(root != null, "No accessible window");
    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
    check(!nodes.isEmpty(), "Missing accessible label: " + label);
    AccessibilityNodeInfo node = nodes.get(0);
    android.graphics.Rect bounds = new android.graphics.Rect();
    node.getBoundsInScreen(bounds);
    check(!bounds.isEmpty(), "Model entry has no visible bounds");
    long now = android.os.SystemClock.uptimeMillis();
    MotionEvent down =
        MotionEvent.obtain(
            now, now, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY(), 0);
    MotionEvent up =
        MotionEvent.obtain(
            now, now + 40, MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY(), 0);
    sendPointerSync(down);
    sendPointerSync(up);
    down.recycle();
    up.recycle();
    waitForIdleSync();
  }

  static EditText edit(View root, String hint) {
    if (root instanceof EditText && hint.contentEquals(((EditText) root).getHint()))
      return (EditText) root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        EditText found = edit(((ViewGroup) root).getChildAt(i), hint);
        if (found != null) return found;
      }
    return null;
  }

  static Button button(View root, String label) {
    if (root instanceof Button && label.contentEquals(((Button) root).getText()))
      return (Button) root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        Button found = button(((ViewGroup) root).getChildAt(i), label);
        if (found != null) return found;
      }
    return null;
  }

  interface Check {
    boolean ok() throws Exception;
  }

  interface Action {
    void run() throws Exception;
  }

  void main(Action action) {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Throwable e) {
            failure.set(e);
          }
        });
    if (failure.get() != null) throw new AssertionError(failure.get());
    waitForIdleSync();
  }

  boolean onMain(Check check) {
    boolean[] value = {false};
    main(() -> value[0] = check.ok());
    return value[0];
  }

  void await(String name, Check check, int seconds) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < end) {
      if (onMain(check)) return;
      Thread.sleep(100);
    }
    throw new AssertionError("Timed out waiting for " + name);
  }

  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  static void restorePreferences(SharedPreferences prefs, Map<String, ?> values) {
    SharedPreferences.Editor editor = prefs.edit().clear();
    for (Map.Entry<String, ?> entry : values.entrySet()) {
      Object value = entry.getValue();
      String name = entry.getKey();
      if (value instanceof String) editor.putString(name, (String) value);
      else if (value instanceof Boolean) editor.putBoolean(name, (Boolean) value);
      else if (value instanceof Integer) editor.putInt(name, (Integer) value);
      else if (value instanceof Long) editor.putLong(name, (Long) value);
      else if (value instanceof Float) editor.putFloat(name, (Float) value);
    }
    check(editor.commit(), "Cannot restore provider preferences");
  }
}
