package com.lexinote.nativeapp;

import android.app.*;
import android.text.InputType;
import android.widget.*;
import org.json.*;

final class SettingsUi {
  final MainActivity a;

  SettingsUi(MainActivity a) {
    this.a = a;
  }

  void show() {
    LinearLayout provider = a.card("OpenAI 兼容服务");
    JSONObject config = a.secure.getProvider();
    provider.addView(a.text("API Key 使用系统 Keystore 加密，仅保存在本机。备份不包含连接凭据。", 14, false));
    EditText name = a.input("供应商名称", config.optString("name"), false),
        base =
            a.input("Base URL，例如 https://api.example.com/v1", config.optString("base_url"), false),
        model = a.input("模型 ID", config.optString("model_id"), false),
        key = a.input("API Key（留空保留现有密钥）", "", false);
    key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    provider.addView(name);
    provider.addView(base);
    provider.addView(model);
    provider.addView(key);
    Switch vision = new Switch(a);
    vision.setText("模型支持视觉输入");
    vision.setTextColor(a.foreground);
    vision.setChecked(config.optBoolean("supports_vision"));
    provider.addView(vision);
    provider.addView(
        a.button(
            "保存服务配置",
            v -> {
              try {
                JSONObject p = new JSONObject();
                Library.put(p, "name", name.getText().toString());
                Library.put(p, "base_url", base.getText().toString().trim());
                Library.put(p, "model_id", model.getText().toString().trim());
                Library.put(p, "supports_vision", vision.isChecked());
                String k = key.getText().toString();
                a.secure.saveProvider(p, k.isEmpty() ? null : k);
                key.setText("");
                a.say("配置已保存");
              } catch (Exception e) {
                a.error(e);
              }
            }));
    provider.addView(
        a.button(
            "清除本机 API Key",
            v -> {
              try {
                a.secure.saveProvider(a.secure.getProvider(), "");
                a.say("密钥已清除");
              } catch (Exception e) {
                a.error(e);
              }
            }));
    LinearLayout appearance = a.card("外观与阅读");
    appearance.addView(
        a.button(
            "主题：" + a.library.settings().optString("theme_mode", "system"),
            v ->
                new AlertDialog.Builder(a)
                    .setItems(
                        new String[] {"跟随系统", "浅色", "深色"},
                        (d, w) ->
                            a.change(
                                () ->
                                    Library.put(
                                        a.library.settings(),
                                        "theme_mode",
                                        new String[] {"system", "light", "dark"}[w])))
                    .show()));
    appearance.addView(
        a.button(
            "字号：" + a.library.settings().optString("font_size", "medium"),
            v ->
                new AlertDialog.Builder(a)
                    .setItems(
                        new String[] {"小", "标准", "大"},
                        (d, w) ->
                            a.change(
                                () ->
                                    Library.put(
                                        a.library.settings(),
                                        "font_size",
                                        new String[] {"small", "medium", "large"}[w])))
                    .show()));
    LinearLayout backup = a.card("数据与备份");
    backup.addView(
        a.text(
            "ZIP 全量备份保留词汇、笔记树、显示配置、历史、会话、通用设置和图片，附完整性校验。兼容旧 Expo 2.0–2.2 ZIP 及 1.0 / 1.1 JSON；旧"
                + " JSON 图片路径可能无法跨设备读取。",
            14,
            false));
    backup.addView(a.button("保存完整 ZIP 备份（含图片）", v -> a.backupZip()));
    backup.addView(
        a.button(
            "保存全量 JSON 备份",
            v ->
                a.export(
                    "lexinote-backup.json", "application/json", a.library.backup().toString())));
    backup.addView(a.button("选择 ZIP / JSON 备份恢复", v -> a.restore()));
    backup.addView(
        a.button(
            "回到上次恢复前",
            v ->
                new AlertDialog.Builder(a)
                    .setMessage("覆盖当前数据，恢复到上次导入前？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton(
                        "恢复",
                        (d, w) -> {
                          try {
                            a.cancel();
                            a.library = a.repository.rollback();
                            a.committed = a.library.snapshot();
                            a.session = null;
                            a.render();
                            a.say("已回退");
                          } catch (Exception e) {
                            a.error(e);
                          }
                        })
                    .show()));
    LinearLayout about = a.card("LexiNote · Android Java");
    about.addView(
        a.text(
            "独立原生版本 · Java 17\n"
                + "本地数据保存在应用私有目录。卸载前请导出备份。\n"
                + "原生迁移首版：尚未完整迁移 AI 自动命名、复杂 Markdown 表格与批量管理。",
            14,
            false));
  }
}
