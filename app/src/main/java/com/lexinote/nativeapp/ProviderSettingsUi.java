package com.lexinote.nativeapp;

import android.app.AlertDialog;
import android.text.*;
import android.text.method.PasswordTransformationMethod;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.json.*;

/** Native, draft-first provider editor. Network callbacks belong to a particular draft revision. */
final class ProviderSettingsUi {
  final MainActivity a;
  ProviderDraft draft;
  boolean expanded;
  boolean keyVisible;
  String status = "";
  JSONArray catalog = new JSONArray();
  String catalogDestination = "";
  ModelsApi active;
  long generation;
  final ExecutorService executor = Executors.newSingleThreadExecutor();
  java.util.function.BiFunction<JSONObject, String, ModelsApi> clientFactory = ModelsApi::new;

  ProviderSettingsUi(MainActivity activity) {
    a = activity;
  }

  void show() {
    JSONObject saved = a.secure.getProvider();
    if (draft == null || !draft.config().optString("id").equals(saved.optString("id")))
      draft = new ProviderDraft(saved);
    JSONObject config = draft.config();
    boolean tokenDance = "tokendance".equals(saved.optString("id"));
    boolean connected = tokenDance && TokenDanceCatalog.isGateway(saved.optString("base_url"));
    try { connected &= !a.secure.getApiKey("tokendance").isEmpty(); }
    catch (Exception e) { connected = false; }
    LinearLayout summary = a.card("AI 服务 · 词元跳动");
    summary.addView(a.text(connected ? "已连接，直接开始查词和图片解析"
        : "登录并确认授权后自动连接，无需复制 API Key。用量从你的词元跳动账户扣除。", 13, false));
    Button authBtn = a.primaryButton(connected ? "重新授权" : "连接 TokenDance", v -> openTokenDance("authorize"));
    LinearLayout.LayoutParams authLp = new LinearLayout.LayoutParams(-1, a.dp(44));
    authLp.topMargin = a.dp(12);
    authLp.bottomMargin = a.dp(10);
    summary.addView(authBtn, authLp);
    LinearLayout accountRow = a.row();
    LinearLayout.LayoutParams acctLp1 = new LinearLayout.LayoutParams(0, a.dp(44), 1);
    acctLp1.rightMargin = a.dp(10);
    accountRow.addView(a.outlineButton("账户与密钥", v -> openTokenDance("account")), acctLp1);
    accountRow.addView(a.outlineButton("充值", v -> openTokenDance("recharge")), new LinearLayout.LayoutParams(0, a.dp(44), 1));
    LinearLayout.LayoutParams accountRowLp = new LinearLayout.LayoutParams(-1, -2);
    accountRowLp.bottomMargin = a.dp(10);
    summary.addView(accountRow, accountRowLp);
    LinearLayout helpRow = a.row();
    LinearLayout.LayoutParams helpLp1 = new LinearLayout.LayoutParams(0, a.dp(40), 1);
    helpLp1.rightMargin = a.dp(10);
    helpRow.addView(a.button("调用记录", v -> openTokenDance("usage")), helpLp1);
    helpRow.addView(a.button("使用帮助", v -> openTokenDance("help")), new LinearLayout.LayoutParams(0, a.dp(40), 1));
    LinearLayout.LayoutParams helpRowLp = new LinearLayout.LayoutParams(-1, -2);
    helpRowLp.bottomMargin = a.dp(12);
    summary.addView(helpRow, helpRowLp);
    summary.addView(a.text("当前服务：" + saved.optString("name") + " · " + saved.optString("model_id"), 12, false));
    boolean fixed = tokenDance || TokenDanceCatalog.isGateway(config.optString("base_url"));
    if (fixed && (catalog.length() == 0 || !sameDestination(config,catalogDestination))) {
      catalog = TokenDanceCatalog.models(); catalogDestination = config.optString("base_url");
    }

    LinearLayout panel = a.card(tokenDance ? "TokenDance 模型配置" : "LLM 供应商与模型配置");

    // Current model info
    TextView modelInfo = a.text("当前使用模型：" + config.optString("model_id"), 13, true);
    modelInfo.setTextColor(a.foreground);
    panel.addView(modelInfo);

    // One-click connection test button
    Button oneClickTest =
        a.primaryButton(
            "一键测试服务连接",
            v -> run(config.optBoolean("supports_vision") ? "vision" : "text"));
    LinearLayout.LayoutParams oneClickLp = new LinearLayout.LayoutParams(-1, a.dp(44));
    oneClickLp.topMargin = a.dp(10);
    oneClickLp.bottomMargin = a.dp(10);
    panel.addView(oneClickTest, oneClickLp);

    if (active != null) {
      Button cancelBtn =
          a.outlineButton(
              "取消测试请求",
              v -> {
                cancel();
                status = "请求已取消";
                a.render();
              });
      LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(-1, a.dp(40));
      cancelLp.bottomMargin = a.dp(8);
      panel.addView(cancelBtn, cancelLp);
    }
    if (!status.isEmpty()) {
      TextView statusTv = a.text(status, 13, true);
      statusTv.setTextColor(
          status.contains("失败") || status.contains("无效")
              ? android.graphics.Color.parseColor("#EF4444")
              : a.accent);
      LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-1, -2);
      stLp.bottomMargin = a.dp(10);
      panel.addView(statusTv, stLp);
    }

    // Reasoning effort capsule segmented slider
    List<Gear> allGears =
        Arrays.asList(
            new Gear("极速响应", "关闭深度思考，毫秒级直接输出，适合快速查词", "off"),
            new Gear("微弱思考", "极简思维链，迅速核验基本词性与释义", "low"),
            new Gear("轻度推理", "短链推理，兼顾响应速度与基础语境准确度", "low"),
            new Gear("标准思考", "平衡推理深度与耗时，适合大多数日常查词与例句分析", "medium"),
            new Gear("深入推理", "深度剖析复杂语境、熟词生义与比喻隐喻", "high"),
            new Gear("强力思考", "多维推演分析长难句结构与近义词细微辨析", "high"),
            new Gear("极致推理", "调用最大思考算力预算，全方位极限深度辨析", "max"));
    JSONArray known = config.optJSONArray("reasoning_levels");
    List<Gear> gears = new ArrayList<>();
    for (Gear g : allGears) {
      if (!g.effort.equals("off") && known != null && !contains(known, g.effort)) continue;
      gears.add(g);
    }
    if (gears.isEmpty()) {
      gears.add(allGears.get(0));
    }

    int currentGearIndex = -1;
    if (config.has("reasoning_gear")) {
      int g = config.optInt("reasoning_gear", -1);
      if (g >= 0 && g < gears.size()) currentGearIndex = g;
    }
    if (currentGearIndex < 0) {
      String currentEffort = config.optString("reasoning_effort", "off");
      for (int i = 0; i < gears.size(); i++) {
        if (gears.get(i).effort.equals(currentEffort)) {
          currentGearIndex = i;
          break;
        }
      }
      if (currentGearIndex < 0) currentGearIndex = 0;
    }

    // Centered title with chevron, tapping opens explanatory guide
    TextView gearTitle = a.text(gears.get(currentGearIndex).chevronLabel, 16, true);
    gearTitle.setTextColor(a.foreground);
    gearTitle.setGravity(Gravity.CENTER);
    gearTitle.setPadding(0, a.dp(8), 0, a.dp(2));
    final List<Gear> fGears = gears;
    gearTitle.setOnClickListener(v -> showReasoningEffortGuide(fGears));
    panel.addView(gearTitle);

    // Custom modern capsule slider
    CapsuleSliderView slider = new CapsuleSliderView(a);
    slider.setColors(a.accent, a.isDark);
    slider.setNumSteps(gears.size());
    slider.setActiveStep(currentGearIndex);
    LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(-1, -2);
    sliderLp.topMargin = a.dp(2);
    sliderLp.bottomMargin = a.dp(2);
    panel.addView(slider, sliderLp);

    // Centered dynamic subtitle
    TextView gearSubtitle = a.text(gears.get(currentGearIndex).subtitle, 12, false);
    gearSubtitle.setTextColor(a.muted);
    gearSubtitle.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
    subLp.topMargin = a.dp(2);
    subLp.bottomMargin = a.dp(4);
    panel.addView(gearSubtitle, subLp);

    slider.setOnStepChanged(
        step -> {
          if (step >= 0 && step < fGears.size()) {
            Gear g = fGears.get(step);
            gearTitle.setText(g.chevronLabel);
            gearSubtitle.setText(g.subtitle);
          }
        });

    slider.setOnStepCommitted(
        step -> {
          if (step >= 0 && step < fGears.size()) {
            Gear g = fGears.get(step);
            draft.set("reasoning_gear", step);
            draft.set("reasoning_effort", g.effort);
            try {
              JSONObject cfg = draft.config();
              a.secure.saveProvider(cfg, null);
              draft = new ProviderDraft(a.secure.getProvider());
            } catch (Exception e) {
              edited();
            }
          }
        });

    TextView effortNote =
        a.text("按供应商保存，开启可能增加耗时和用量；实际支持的档位由模型决定。", 11, false);
    effortNote.setTextColor(a.subtle);
    effortNote.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
    noteLp.topMargin = a.dp(2);
    noteLp.bottomMargin = a.dp(12);
    panel.addView(effortNote, noteLp);

    // Toggle button for folding / expanding detailed config
    Button toggle =
        a.outlineButton(
            expanded ? "收起详细配置" : "高级设置 · 模型与手动配置",
            v -> {
              expanded = !expanded;
              a.render();
            });
    LinearLayout.LayoutParams toggleLayout = new LinearLayout.LayoutParams(-1, a.dp(42));
    toggleLayout.topMargin = a.dp(4);
    toggleLayout.bottomMargin = a.dp(expanded ? 12 : 4);
    panel.addView(toggle, toggleLayout);

    if (expanded) {
      View div = new View(a);
      div.setBackgroundColor(a.border);
      LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(-1, a.dp(1));
      divLp.topMargin = a.dp(8);
      divLp.bottomMargin = a.dp(12);
      panel.addView(div, divLp);

      panel.addView(a.text("选择预设供应商", 12, true));
      panel.addView(a.gap(6));
      JSONArray profiles = a.secure.getProviders();
      for (int i = 0; i < profiles.length(); i += 2) {
        LinearLayout row = a.row();
        for (int j = i; j < Math.min(i + 2, profiles.length()); j++) {
          JSONObject profile = profiles.optJSONObject(j);
          String id = profile.optString("id");
          Button chip =
              a.chip(profile.optString("name"), id.equals(saved.optString("id")), v -> selectProvider(id));
          chip.setSingleLine(false);
          LinearLayout.LayoutParams chipLayout = new LinearLayout.LayoutParams(0, a.dp(44), 1);
          chipLayout.rightMargin = a.dp(6);
          row.addView(chip, chipLayout);
        }
        if (i + 1 >= profiles.length()) row.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
        panel.addView(row);
        panel.addView(a.gap(6));
      }
      panel.addView(a.button("＋ 新增兼容服务", v -> confirmDiscard(this::addProvider)));
      panel.addView(a.gap(6));

      panel.addView(a.text("模型选择", 12, true));
      panel.addView(
          a.outlineButton(
              config.optString("model_id"),
              v -> {
                if (catalog.length() > 0 && sameDestination(draft.config(), catalogDestination))
                  chooseModel();
                else run("models");
              }));
      if (!fixed) panel.addView(a.button("获取模型列表", v -> run("models")));
      else
        panel.addView(
            a.text(
                "内置 34 个已核对的图像理解模型（2026-10-22），离线可选；新模型可通过高级配置手动填写。",
                12,
                false));
      TextView visionNotice =
          a.text(
              config.optBoolean("supports_vision")
                  ? "当前模型已开启视觉输入，可通过图片测试核对能力。"
                  : "当前模型未开启视觉输入；图片查询需要支持视觉的模型。",
              12,
              false);
      visionNotice.setTextColor(config.optBoolean("supports_vision") ? a.accent : a.muted);
      visionNotice.setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8));
      visionNotice.setBackground(a.shape((a.accent & 0xFFFFFF) | 0x1A000000, 8, 0));
      panel.addView(visionNotice);
      panel.addView(a.gap(12));

      panel.addView(a.text("API Key（安全加密存储）", 12, true));
      EditText key = a.input("留空保留当前供应商密钥", draft.enteredKey(), false);
      key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
      key.setTransformationMethod(keyVisible ? null : PasswordTransformationMethod.getInstance());
      key.setSaveEnabled(false);
      key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
      watch(key, value -> {
        draft.setKey(value);
        edited();
      });
      LinearLayout keyRow = a.row();
      keyRow.addView(key, new LinearLayout.LayoutParams(0, a.dp(44), 1));
      Button reveal =
          a.outlineButton(
              keyVisible ? "隐藏" : "显示",
              v -> {
                keyVisible = !keyVisible;
                key.setTransformationMethod(
                    keyVisible ? null : PasswordTransformationMethod.getInstance());
                key.setSelection(key.getText().length());
                ((Button) v).setText(keyVisible ? "隐藏" : "显示");
              });
      keyRow.addView(reveal, new LinearLayout.LayoutParams(a.dp(64), a.dp(44)));
      panel.addView(keyRow);
      panel.addView(a.gap(8));

      LinearLayout actions = a.row();
      LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, a.dp(44), 1);
      left.rightMargin = a.dp(10);
      actions.addView(a.primaryButton("保存服务配置", v -> save()), left);
      actions.addView(
          a.outlineButton("文本连接测试", v -> run("text")),
          new LinearLayout.LayoutParams(0, a.dp(44), 1));
      LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, -2);
      actionsLp.topMargin = a.dp(10);
      actionsLp.bottomMargin = a.dp(10);
      panel.addView(actions, actionsLp);

      Button visionBtn = a.outlineButton("图片识别测试", v -> run("vision"));
      LinearLayout.LayoutParams visionLp = new LinearLayout.LayoutParams(-1, a.dp(44));
      visionLp.bottomMargin = a.dp(10);
      panel.addView(visionBtn, visionLp);

      panel.addView(
          a.text(
              "测试使用当前填写的密钥、地址和模型，发送少量请求，可能产生费用；图片测试使用内置色块图。",
              12,
              false));

      panel.addView(a.gap(12));
      panel.addView(a.text("手动配置（修改后保存生效）", 12, true));
      addInput(panel, "供应商名称", "name", config.optString("name"));
      panel.addView(a.gap(6));
      addInput(panel, "Base URL", "base_url", config.optString("base_url"));
      panel.addView(a.gap(6));
      addInput(panel, "模型 ID", "model_id", config.optString("model_id"));
      Switch vision = new Switch(a);
      vision.setText("多模态视觉识图能力 (Vision)");
      vision.setTextColor(a.foreground);
      vision.setChecked(config.optBoolean("supports_vision"));
      vision.setOnCheckedChangeListener(
          (button, checked) -> {
            draft.set("supports_vision", checked);
            edited();
          });
      panel.addView(vision);
      panel.addView(
          a.text(
              config.optBoolean("vision_known")
                  ? "模型列表已提供输入能力；也可通过图片测试核对。"
                  : "输入能力尚未由模型列表确认；请核对供应商说明或进行图片测试。",
              12,
              false));

      Button manualSave = a.outlineButton("保存服务配置", v -> save());
      LinearLayout.LayoutParams manualSaveLp = new LinearLayout.LayoutParams(-1, a.dp(44));
      manualSaveLp.topMargin = a.dp(10);
      manualSaveLp.bottomMargin = a.dp(10);
      panel.addView(manualSave, manualSaveLp);

      Button clearKeyBtn =
          a.button(
              "清除当前供应商密钥",
              v -> {
                AlertDialog.Builder b =
                    new AlertDialog.Builder(a)
                        .setMessage("清除该供应商在本机保存的 API Key？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton(
                            "清除",
                            (dialog, which) -> {
                              try {
                                cancel();
                                a.secure.saveProvider(a.secure.getProvider(), "");
                                draft.setKey("");
                                status = "当前供应商密钥已清除";
                                a.render();
                              } catch (Exception e) {
                                a.error(e);
                              }
                            });
                AlertDialog d = a.showDialog(b);
                a.styleDestructive(d);
              });
      LinearLayout.LayoutParams clearKeyLp = new LinearLayout.LayoutParams(-1, a.dp(40));
      clearKeyLp.bottomMargin = a.dp(8);
      panel.addView(clearKeyBtn, clearKeyLp);

      if (ProviderProfiles.preset(saved.optString("id")) != null)
        panel.addView(
            a.button(
                "恢复当前预设参数",
                v -> {
                  confirmDiscard(
                      () -> {
                        cancel();
                        draft = new ProviderDraft(ProviderProfiles.preset(saved.optString("id")));
                        status = "已恢复预设草稿，保存后生效；密钥保持独立。";
                        a.render();
                      });
                }));
      else
        panel.addView(
            a.button(
                "删除当前兼容服务",
                v -> {
                  AlertDialog.Builder b =
                      new AlertDialog.Builder(a)
                          .setMessage("删除该服务配置及其本机密钥？")
                          .setNegativeButton("取消", null)
                          .setPositiveButton(
                              "删除",
                              (dialog, which) -> {
                                try {
                                  cancel();
                                  a.titles.reset();
                                  a.secure.deleteProvider(saved.optString("id"));
                                  clearDraft();
                                  a.render();
                                } catch (Exception e) {
                                  a.error(e);
                                }
                              });
                  AlertDialog d = a.showDialog(b);
                  a.styleDestructive(d);
                }));
    }
  }

  void addInput(LinearLayout panel, String hint, String field, String value) {
    panel.addView(a.text(hint, 12, true));
    EditText input = a.input(hint, value, false);
    input.setSaveEnabled(false);
    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    watch(
        input,
        changed -> {
          draft.set(field, changed);
          if (field.equals("model_id")) draft.set("is_custom_model_id", true);
          if (field.equals("base_url")) draft.set("is_custom_base_url", true);
          edited();
        });
    panel.addView(input);
  }

  static void watch(EditText input, Consumer<String> consumer) {
    input.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

          public void onTextChanged(CharSequence text, int start, int before, int count) {
            consumer.accept(text.toString());
          }

          public void afterTextChanged(Editable text) {}
        });
  }

  void edited() {
    if (active != null) {
      cancel();
      status = "配置已修改，请重新测试";
    } else status = "";
  }

  void selectProvider(String id) {
    if (id.equals(a.secure.getProvider().optString("id"))) return;
    Runnable select = () -> {
      try {
        cancel();
        a.titles.reset();
        a.secure.selectProvider(id);
        clearDraft();
        a.render();
      } catch (Exception e) { a.error(e); }
    };
    confirmDiscard(select);
  }

  void confirmDiscard(Runnable action) {
    boolean dirty = draft != null && (!draft.enteredKey().isEmpty()
        || !draft.config().toString().equals(a.secure.getProvider().toString()));
    if (dirty) {
      AlertDialog.Builder b =
          new AlertDialog.Builder(a)
              .setMessage("此操作将放弃当前未保存的配置，继续吗？")
              .setNegativeButton("取消", null)
              .setPositiveButton("继续", (dialog, which) -> action.run());
      a.showDialog(b);
    } else action.run();
  }

  void addProvider() {
    LinearLayout box = a.column();
    box.setPadding(a.dp(22), a.dp(10), a.dp(22), a.dp(6));
    EditText name = a.input("服务名称", "", false),
        base = a.input("HTTPS Base URL", "", false),
        model = a.input("模型 ID", "", false);
    box.addView(name);
    box.addView(base);
    box.addView(model);
    AlertDialog.Builder b =
        new AlertDialog.Builder(a)
            .setTitle("新增兼容服务")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建", null);
    AlertDialog dialog = a.showDialog(b);
    Button createBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
    if (createBtn != null) {
      createBtn.setOnClickListener(
          v -> {
            try {
              JSONObject config =
                  new JSONObject()
                      .put("name", name.getText().toString())
                      .put("base_url", base.getText().toString())
                      .put("model_id", model.getText().toString())
                      .put("supports_vision", false)
                      .put("reasoning_effort", "off");
              cancel();
              String id = a.secure.addProvider(config, null);
              a.titles.reset();
              a.secure.selectProvider(id);
              clearDraft();
              dialog.dismiss();
              a.render();
            } catch (Exception e) {
              a.error(e);
            }
          });
    }
  }

  void save() {
    try {
      JSONObject config = draft.config();
      TokenDanceCatalog.assertChatModel(config);
      String entered = draft.enteredKey();
      if (entered.trim().isEmpty()) a.secure.getApiKeyFor(config, entered);
      cancel();
      a.titles.reset();
      a.secure.saveProvider(config, entered.trim().isEmpty() ? null : entered);
      draft = new ProviderDraft(a.secure.getProvider());
      status = "配置已保存";
      a.render();
    } catch (Exception e) {
      a.error(e);
    }
  }

  void run(String operation) {
    if (active != null) {
      a.say("正在测试，可取消后重新开始");
      return;
    }
    try {
      JSONObject config = draft.config();
      AgentProtocol.endpoint(config.optString("base_url"));
      if (!operation.equals("models")) TokenDanceCatalog.assertChatModel(config);
      String key = a.secure.getApiKeyFor(config, draft.enteredKey());
      if (key.trim().isEmpty()
          && !(operation.equals("models") && ModelsApi.isPublicCatalog(config)))
        throw new IllegalArgumentException("请填写当前供应商的 API Key");
      if (!operation.equals("models") && config.optString("model_id").trim().isEmpty())
        throw new IllegalArgumentException("请填写模型 ID");
      if (operation.equals("vision") && !config.optBoolean("supports_vision"))
        throw new IllegalArgumentException("请先开启视觉输入，再测试该模型是否支持图片");
      ModelsApi client = clientFactory.apply(config, key);
      active = client;
      final long token = ++generation, revision = draft.revision();
      String id = config.optString("id");
      status = operation.equals("models") ? "正在获取模型列表…" : "正在测试，请稍候…";
      a.render();
      executor.execute(
          () -> {
            try {
              JSONArray models = operation.equals("models") ? client.fetchModels() : null;
              JSONObject result =
                  models == null ? client.testConnection(operation.equals("vision")) : null;
              a.handler.post(
                  () -> {
                    if (!current(token, id, revision)) return;
                    active = null;
                    if (models != null) {
                      catalog = models;
                      catalogDestination = config.optString("base_url");
                      status =
                          models.length() == 0
                              ? "接口未提供可用对话模型，可手动填写模型 ID。"
                              : "已获取 " + models.length() + " 个模型，请选择或保留手动填写的 ID。";
                    } else
                      status =
                          result.optString("message") + "（" + result.optLong("latency_ms") + " ms）";
                    a.render();
                    if (result != null && !result.optString("recovery_action").isEmpty()) {
                      TokenDanceRecovery recovery = TokenDanceRecovery.from(config.optString("base_url"),result.optString("recovery_action"));
                      if (recovery != null) a.error(recovery);
                    }
                  });
            } catch (Exception e) {
              a.handler.post(
                  () -> {
                    if (!current(token, id, revision)) return;
                    active = null;
                    String message = e.getMessage() == null ? "连接失败，请检查服务配置" : e.getMessage();
                    status = key.isEmpty() ? message : message.replace(key, "[已隐藏]");
                    a.render();
                  });
            }
          });
    } catch (Exception e) {
      status = e.getMessage() == null ? "配置无效" : e.getMessage();
      a.render();
      if (TokenDanceRecovery.find(e) != null) a.error(e);
    }
  }

  boolean current(long token, String id, long revision) {
    return !a.isFinishing()
        && !a.isDestroyed()
        && token == generation
        && draft != null
        && draft.matches(id, revision);
  }

  void chooseModel() {
    if (!sameDestination(draft.config(), catalogDestination)) return;
    final long revision = draft.revision();
    final String id = draft.config().optString("id");
    LinearLayout box = a.column();
    EditText search = a.input("搜索模型名称或 ID", "", false);
    box.addView(search);
    ListView list = new ListView(a);
    box.addView(list, new LinearLayout.LayoutParams(-1, a.dp(320)));
    List<JSONObject> shown = new ArrayList<>();
    Consumer<String> filter =
        term -> {
          shown.clear();
          List<String> labels = new ArrayList<>();
          for (int i = 0; i < catalog.length(); i++) {
            JSONObject model = catalog.optJSONObject(i);
            String label = model.optString("name") + "\n" + model.optString("id");
            if (!label.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT))) continue;
            shown.add(model);
            labels.add(
                label
                    + (model.optBoolean("vision_known")
                        ? (model.optBoolean("supports_vision") ? " · 图文" : " · 文本")
                        : " · 能力未知"));
          }
          list.setAdapter(new ArrayAdapter<>(a, android.R.layout.simple_list_item_1, labels));
        };
    filter.accept("");
    watch(search, filter);
    AlertDialog dialog =
        new AlertDialog.Builder(a)
            .setTitle("选择模型")
            .setView(box)
            .setNegativeButton("取消", null)
            .create();
    list.setOnItemClickListener(
        (parent, view, position, rowId) -> {
          if (draft == null || !draft.matches(id, revision)) {
            dialog.dismiss();
            return;
          }
          cancel();
          draft.selectModel(shown.get(position));
          status = "已选择模型，保存后用于查询；也可先测试。";
          dialog.dismiss();
          a.render();
        });
    a.showDialog(dialog);
  }

  static boolean contains(JSONArray values, String value) {
    for (int i = 0; i < values.length(); i++) if (value.equals(values.optString(i))) return true;
    return false;
  }

  static boolean sameDestination(JSONObject config, String destination) {
    try {
      return AgentProtocol.endpoint(config.optString("base_url"))
          .equals(AgentProtocol.endpoint(destination));
    } catch (Exception e) {
      return false;
    }
  }

  void openTokenDance(String mode) {
    confirmDiscard(() -> a.startActivityForResult(new android.content.Intent(a,TokenDanceActivity.class).putExtra("mode",mode),70));
  }

  void clearDraft() {
    keyVisible = false;
    if (draft != null) draft.setKey("");
    draft = null;
    catalog = new JSONArray();
    catalogDestination = "";
    status = "";
  }

  void cancel() {
    generation++;
    if (active != null) active.cancel();
    active = null;
  }

  void close() {
    cancel();
    if (draft != null) draft.setKey("");
    executor.shutdownNow();
  }

  void saveState(android.os.Bundle state) {
    state.putBoolean("provider_editor_expanded", expanded);
    if (draft != null) state.putString("provider_editor_draft", draft.config().toString());
  }

  void restoreState(android.os.Bundle state) {
    if (state == null) return;
    expanded = state.getBoolean("provider_editor_expanded", false);
    String raw = state.getString("provider_editor_draft");
    if (raw == null) return;
    try {
      JSONObject restored = new JSONObject(raw);
      if (restored.optString("id").equals(a.secure.getProvider().optString("id")))
        draft = new ProviderDraft(restored);
    } catch (JSONException ignored) {
      draft = null;
    }
  }

  private void showReasoningEffortGuide(List<Gear> gears) {
    ScrollView scroll = new ScrollView(a);
    LinearLayout content = a.column();
    content.setPadding(a.dp(20), a.dp(16), a.dp(20), a.dp(16));
    scroll.addView(content);

    TextView title = a.text("推理强度档位说明", 18, true);
    title.setTextColor(a.foreground);
    content.addView(title);
    content.addView(a.gap(10));

    TextView intro =
        a.text(
            "推理强度决定模型在给出最终回答前进行的思维链（Reasoning / Thinking）深度。档位越高，思考越充分、分析越细致，但响应耗时和 token 消耗也会相应增加。",
            13,
            false);
    intro.setTextColor(a.muted);
    content.addView(intro);
    content.addView(a.gap(14));

    for (int i = 0; i < gears.size(); i++) {
      Gear g = gears.get(i);
      LinearLayout item = a.column();
      item.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
      item.setBackground(a.shape(a.isDark ? 0xFF1E293B : 0xFFF1F5F9, 10, a.border));
      LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(-1, -2);
      itemLp.bottomMargin = a.dp(8);
      item.setLayoutParams(itemLp);

      LinearLayout header = a.row();
      header.setGravity(Gravity.CENTER_VERTICAL);
      TextView name = a.text(g.label, 14, true);
      name.setTextColor(a.foreground);
      header.addView(name, new LinearLayout.LayoutParams(0, -2, 1));

      TextView badge = a.text("档位 " + (i + 1), 11, true);
      badge.setTextColor(a.accent);
      badge.setPadding(a.dp(8), a.dp(2), a.dp(8), a.dp(2));
      badge.setBackground(a.shape((a.accent & 0xFFFFFF) | 0x1A000000, 6, a.accent));
      header.addView(badge);
      item.addView(header);

      TextView desc = a.text(g.subtitle, 12, false);
      desc.setTextColor(a.muted);
      LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(-1, -2);
      descLp.topMargin = a.dp(4);
      item.addView(desc, descLp);

      content.addView(item);
    }

    AlertDialog.Builder b =
        new AlertDialog.Builder(a)
            .setView(scroll)
            .setPositiveButton("我知道了", null);
    a.showDialog(b);
  }

  static final class Gear {
    final String label;
    final String chevronLabel;
    final String subtitle;
    final String effort;

    Gear(String label, String subtitle, String effort) {
      this.label = label;
      this.chevronLabel = label + "  ›";
      this.subtitle = subtitle;
      this.effort = effort;
    }
  }
}
