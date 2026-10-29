package com.lexinote.nativeapp;

import android.app.*;
import android.widget.*;
import org.json.*;

final class SettingsUi {
  final MainActivity a;
  final ProviderSettingsUi models;

  SettingsUi(MainActivity a) {
    this.a = a;
    models = new ProviderSettingsUi(a);
  }

  void show() {
    models.show();
    LinearLayout appearance = a.card("外观与阅读");
    JSONObject prefs = a.library.settings();
    appearance.addView(a.text("主题", 13, true));
    String theme = prefs.optString("theme_mode", "system");
    String[] tids = {"light", "dark", "system"}, tnames = {"浅色", "深色", "跟随系统"};
    LinearLayout themeRow = a.row();
    for (int i = 0; i < tids.length; i++) {
      final String id = tids[i];
      themeRow.addView(
          a.chip(
              tnames[i],
              theme.equals(id),
              v -> a.change(() -> Library.put(a.library.settings(), "theme_mode", id))),
          new LinearLayout.LayoutParams(0, a.dp(48), 1));
    }
    LinearLayout.LayoutParams themeRowLp = new LinearLayout.LayoutParams(-1, -2);
    themeRowLp.topMargin = a.dp(6);
    themeRowLp.bottomMargin = a.dp(14);
    themeRow.setLayoutParams(themeRowLp);
    appearance.addView(themeRow);
    appearance.addView(a.text("强调色", 13, true));
    String[] accents = {"#10B981", "#2563EB", "#6366F1", "#F59E0B", "#E11D48", "#8B5CF6"};
    String chosen = prefs.optString("accent_color", accents[0]);
    // Two rows keep all color choices reachable even at narrow viewport widths.
    LinearLayout colors = a.column();
    for (int i = 0; i < accents.length; i += 3) {
      LinearLayout colorRow = a.row();
      for (int j = i; j < Math.min(i + 3, accents.length); j++) {
        String color = accents[j];
        android.widget.FrameLayout target = new android.widget.FrameLayout(a);
        target.setContentDescription("强调色 " + color + (chosen.equalsIgnoreCase(color) ? "，已选择" : ""));
        target.setOnClickListener(v -> a.change(() -> Library.put(a.library.settings(), "accent_color", color)));
        TextView dot = a.text(chosen.equalsIgnoreCase(color) ? "✓" : "", 15, true);
        dot.setGravity(android.view.Gravity.CENTER);
        dot.setTextColor(LayoutStyle.onColor(android.graphics.Color.parseColor(color)));
        android.graphics.drawable.GradientDrawable swatch = new android.graphics.drawable.GradientDrawable();
        swatch.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        swatch.setColor(android.graphics.Color.parseColor(color));
        if (chosen.equalsIgnoreCase(color)) swatch.setStroke(a.dp(2), android.graphics.Color.WHITE);
        dot.setBackground(swatch);
        android.widget.FrameLayout.LayoutParams dotLp = new android.widget.FrameLayout.LayoutParams(a.dp(36), a.dp(36), android.view.Gravity.CENTER);
        target.addView(dot, dotLp);
        colorRow.addView(target, new LinearLayout.LayoutParams(a.dp(48), a.dp(48)));
      }
      colors.addView(colorRow);
    }
    LinearLayout.LayoutParams colorsLp = new LinearLayout.LayoutParams(-1, -2);
    colorsLp.topMargin = a.dp(8);
    colorsLp.bottomMargin = a.dp(14);
    colors.setLayoutParams(colorsLp);
    appearance.addView(colors);
    appearance.addView(a.text("字号", 13, true));
    String font = prefs.optString("font_size", "medium");
    String[] fids = {"small", "medium", "large"}, fnames = {"小", "标准", "大"};
    LinearLayout fonts = a.row();
    for (int i = 0; i < fids.length; i++) {
      final String id = fids[i];
      fonts.addView(
          a.chip(
              fnames[i],
              font.equals(id),
              v -> a.change(() -> Library.put(a.library.settings(), "font_size", id))),
          new LinearLayout.LayoutParams(0, a.dp(48), 1));
    }
    LinearLayout.LayoutParams fontsLp = new LinearLayout.LayoutParams(-1, -2);
    fontsLp.topMargin = a.dp(6);
    fontsLp.bottomMargin = a.dp(14);
    fonts.setLayoutParams(fontsLp);
    appearance.addView(fonts);
    appearance.addView(a.text("显示密度", 13, true));
    String density = prefs.optString("density", "comfortable");
    LinearLayout densities = a.row();
    for (String d : new String[] {"comfortable", "compact"})
      densities.addView(
          a.chip(
              d.equals("comfortable") ? "舒适" : "紧凑",
              density.equals(d),
              v -> a.change(() -> Library.put(a.library.settings(), "density", d))),
          new LinearLayout.LayoutParams(0, a.dp(48), 1));
    LinearLayout.LayoutParams densitiesLp = new LinearLayout.LayoutParams(-1, -2);
    densitiesLp.topMargin = a.dp(6);
    densities.setLayoutParams(densitiesLp);
    appearance.addView(densities);
    appearance.addView(a.text("舒适：卡片和内容间距更宽；紧凑：减少留白，触控区域保持不变。", 12, false));
    LinearLayout notebook = a.card("查询偏好");
    Switch auto = new Switch(a);
    auto.setText("查询结果自动记录到默认笔记");
    auto.setTextColor(a.foreground);
    auto.setChecked(prefs.optBoolean("auto_record_extra_info"));
    auto.setOnCheckedChangeListener(
        (button, checked) ->
            a.change(() -> Library.put(a.library.settings(), "auto_record_extra_info", checked)));
    notebook.addView(auto);
    notebook.addView(a.text("仅成功生成并确认的词条自动入库；聊天、失败和本地命中不会重复记录。", 12, false));
    notebook.addView(a.button("默认记录显示字段", v -> defaultFields()));
    String defaultId = prefs.optString("default_notebook_id", "");
    JSONObject defaultNote = a.library.find("notebooks", defaultId);
    notebook.addView(
        a.text(
            "默认保存分类：" + (defaultNote == null ? "未设置" : defaultNote.optString("name")), 14, false));
    notebook.addView(a.button("选择默认分类", v -> chooseDefaultNotebook()));
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
                            a.titles.reset();
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
            "独立原生版本 · Java 17\n" + "本地数据保存在应用私有目录。卸载前请导出备份。\n" + "原生 Java 版本，支持图文查询、流式答疑与共享笔记。",
            14,
            false));
    a.body.addView(a.text("LexiNote 原生 Android 版本 · Java", 13, false));
  }

  void chooseDefaultNotebook() {
    java.util.List<JSONObject> leaves = a.notebooks.leaves();
    String[] names = new String[leaves.size()];
    for (int i = 0; i < names.length; i++) names[i] = leaves.get(i).optString("name");
    new AlertDialog.Builder(a)
        .setTitle("默认保存分类")
        .setItems(
            names,
            (d, w) ->
                a.change(
                    () ->
                        Library.put(
                            a.library.settings(),
                            "default_notebook_id",
                            leaves.get(w).optString("id"))))
        .show();
  }

  void defaultFields() {
    try {
      JSONObject chosen = QueryWorkflow.defaultFields(a.library.settings());
      String[] labels = {"中文释义", "英文释义", "音标", "例句", "来源", "图片", "批注", "备注"};
      boolean[] values = new boolean[labels.length];
      for (int i = 0; i < values.length; i++)
        values[i] = chosen.optBoolean(QueryWorkflow.FIELDS[i]);
      new AlertDialog.Builder(a)
          .setTitle("默认笔记显示字段（完整数据仍保留）")
          .setMultiChoiceItems(labels, values, (dialog, index, checked) -> values[index] = checked)
          .setNegativeButton("取消", null)
          .setPositiveButton(
              "保存",
              (dialog, which) ->
                  a.change(
                      () -> {
                        JSONObject fields = new JSONObject();
                        for (int i = 0; i < values.length; i++)
                          Library.put(fields, QueryWorkflow.FIELDS[i], values[i]);
                        Library.put(a.library.settings(), "default_fields_to_save", fields);
                      }))
          .show();
    } catch (Exception error) {
      a.error(error);
    }
  }
}
