package com.lexinote.nativeapp;

import android.app.*;
import android.net.Uri;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;

final class QueryUi {
  final MainActivity a;
  String draft = "",
      context = "",
      follow = "",
      guessTarget = "",
      guessContext = "",
      sessionFilter = "";
  String homeMode = "text", guessMode = "image";
  String startDate = "", endDate = "", dateField = "created_at";
  int savedLimit = 10;
  boolean showDateFields = false;
  int stage = 0;
  JSONObject guessReply;
  AssistantUi assistantView;
  String partialReply = "";
  boolean organizing;
  final Set<String> editingMeanings = new HashSet<>();

  QueryUi(MainActivity a) {
    this.a = a;
  }

  void track(EditText e, java.util.function.Consumer<String> c) {
    e.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int count, int after) {}

          public void onTextChanged(CharSequence s, int st, int before, int count) {
            c.accept(s.toString());
          }

          public void afterTextChanged(Editable s) {}
        });
  }

  void home() {
    LinearLayout queryCard = a.card(null);
    queryCard.setTag("query-input-card");
    queryCard.setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(12));
    LinearLayout.LayoutParams queryLp = new LinearLayout.LayoutParams(-1, a.dp(312));
    queryLp.bottomMargin = a.dp(30);
    queryCard.setLayoutParams(queryLp);

    LinearLayout modes = a.row();
    modes.setPadding(a.dp(4), a.dp(3), a.dp(4), a.dp(3));
    android.graphics.drawable.GradientDrawable modeBg =
        new android.graphics.drawable.GradientDrawable();
    modeBg.setColor(a.inputBg);
    modeBg.setCornerRadius(a.dp(13));
    modes.setBackground(modeBg);
    String[] modeIds = {"text", "image", "both"};
    String[] modeLabels = {"文本查询", "图片查询", "图文+提问"};
    for (int i = 0; i < modeIds.length; i++) {
      String mode = modeIds[i];
      Button button =
          a.button(
              modeLabels[i],
              v -> {
                homeMode = mode;
                a.render();
              });
      button.setTextSize(12 * a.scale);
      String[] modeIcons = {"text-outline", "image-outline", "color-wand-outline"};
      button.setCompoundDrawables(
          a.new GlyphDrawable(modeIcons[i], 15, mode.equals(homeMode) ? a.accent : a.muted),
          null,
          null,
          null);
      button.setCompoundDrawablePadding(a.dp(3));
      button.setPadding(a.dp(8), 0, a.dp(6), 0);
      button.setMinHeight(a.dp(36));
      button.setMinimumHeight(a.dp(36));
      if (mode.equals(homeMode)) {
        android.graphics.drawable.GradientDrawable active =
            new android.graphics.drawable.GradientDrawable();
        active.setColor(a.card);
        active.setCornerRadius(a.dp(12));
        active.setStroke(a.dp(1), a.border);
        button.setBackground(active);
        button.setTextColor(a.accent);
      } else {
        button.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        button.setTextColor(a.muted);
      }
      modes.addView(button, new LinearLayout.LayoutParams(0, a.dp(36), 1));
    }
    queryCard.addView(modes);
    queryCard.addView(new View(a), new LinearLayout.LayoutParams(1, a.dp(8)));

    if (!homeMode.equals("image")) {
      String hint =
          homeMode.equals("both") ? "可输入目标词汇或问题" : "输入英文单词或短语，如 serendipity, break a leg...";
      EditText target = a.input(hint, draft, false);
      target.setCompoundDrawables(
          a
          .new GlyphDrawable(
              homeMode.equals("both") ? "color-wand-outline" : "search-outline", 17, a.accent),
          null,
          null,
          null);
      target.setCompoundDrawablePadding(a.dp(8));
      track(
          target,
          value -> {
            if (!draft.equals(value)) a.invalidateDraft();
            draft = value;
          });
      queryCard.addView(target, new LinearLayout.LayoutParams(-1, a.dp(42)));
    }

    if (homeMode.equals("text")) {
      LinearLayout contextBox = a.column();
      contextBox.setPadding(a.dp(8), a.dp(7), a.dp(8), a.dp(7));
      android.graphics.drawable.GradientDrawable contextBg =
          new android.graphics.drawable.GradientDrawable();
      contextBg.setColor(a.inputBg);
      contextBg.setCornerRadius(a.dp(12));
      contextBg.setStroke(a.dp(1), a.border);
      contextBox.setBackground(contextBg);
      TextView contextLabel = a.text("上下文例句 / 提问说明（选填）", 12, false);
      contextLabel.setTextColor(a.muted);
      contextBox.addView(contextLabel);
      EditText ctx = a.input("粘贴包含该词的原句段落或特定要求，帮助 AI 精准契合语境解析…", context, true);
      ctx.setTextSize(13 * a.scale);
      ctx.setBackgroundColor(android.graphics.Color.TRANSPARENT);
      ctx.setPadding(0, a.dp(4), 0, 0);
      track(
          ctx,
          value -> {
            if (!context.equals(value)) a.invalidateDraft();
            context = value;
          });
      LinearLayout.LayoutParams contextLp = new LinearLayout.LayoutParams(-1, 0, 1);
      contextLp.topMargin = a.dp(8);
      contextLp.bottomMargin = a.dp(8);
      queryCard.addView(contextBox, contextLp);
      contextBox.addView(ctx, new LinearLayout.LayoutParams(-1, 0, 1));
    } else if (homeMode.equals("image")) {
      if (a.image == null) {
        LinearLayout tiles = a.row();
        tiles.addView(
            imageTile("拍摄照片", "拍照识别书籍 / 屏幕", "camera-outline", () -> a.chooseImage(true)),
            new LinearLayout.LayoutParams(0, -1, 1));
        tiles.addView(
            imageTile("相册选取", "选择手机截图或照片", "images-outline", () -> a.chooseImage(false)),
            new LinearLayout.LayoutParams(0, -1, 1));
        ((LinearLayout.LayoutParams) tiles.getChildAt(1).getLayoutParams()).leftMargin = a.dp(8);
        LinearLayout.LayoutParams tilesLp = new LinearLayout.LayoutParams(-1, 0, 1);
        tilesLp.bottomMargin = a.dp(8);
        queryCard.addView(tiles, tilesLp);
      } else {
        ImageView preview = new ImageView(a);
        preview.setImageURI(a.image);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(-1, 0, 1);
        queryCard.addView(preview, previewLp);
        queryCard.addView(a.button("更换图片", v -> a.chooseImage(false)));
      }
    } else {
      LinearLayout both = a.row();
      if (a.image == null) {
        both.addView(
            imageTile("拍摄照片", "识别书页 / 屏幕", "camera-outline", () -> a.chooseImage(true)),
            new LinearLayout.LayoutParams(0, -1, 1));
        both.addView(
            imageTile("相册选取", "选择手机截图或照片", "images-outline", () -> a.chooseImage(false)),
            new LinearLayout.LayoutParams(0, -1, 1));
        ((LinearLayout.LayoutParams) both.getChildAt(1).getLayoutParams()).leftMargin = a.dp(8);
      } else {
        ImageView preview = new ImageView(a);
        preview.setImageURI(a.image);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        both.addView(preview, new LinearLayout.LayoutParams(0, a.dp(74), 1));
        both.addView(
            a.button(
                "移除",
                v -> {
                  a.clearImage();
                  a.render();
                }));
      }
      LinearLayout.LayoutParams bothLp = new LinearLayout.LayoutParams(-1, 0, 1);
      bothLp.topMargin = a.dp(8);
      bothLp.bottomMargin = a.dp(8);
      queryCard.addView(both, bothLp);
    }
    queryCard.addView(a.primaryButton("✦  智能查询", v -> submit()));

    if (pending() != null) {
      JSONObject pending = pending();
      LinearLayout p = a.card("待确认查询");
      p.addView(a.text(pending.optString("target") + " · " + pending.optString("type"), 20, true));
      p.addView(
          a.text(
              pending.optString("context_quote") + "\n" + pending.optString("user_requirement"),
              14,
              false));
      p.addView(a.button("确认查询", v -> lookup(pending)));
      p.addView(
          a.button(
              "修改目标",
              v -> {
                draft = pending.optString("target");
                Library.put(a.session, "pending_lookup", JSONObject.NULL);
                a.render();
              }));
    }
    renderSessionLists();
  }

  View imageTile(String title, String subtitle, String iconName, Runnable action) {
    LinearLayout tile = a.column();
    tile.setGravity(Gravity.CENTER);
    android.graphics.drawable.GradientDrawable background =
        new android.graphics.drawable.GradientDrawable();
    background.setColor(a.inputBg);
    background.setCornerRadius(a.dp(12));
    background.setStroke(a.dp(1), a.border);
    tile.setBackground(background);
    TextView tileIcon = a.icon(iconName, 22, a.accent);
    tileIcon.setBackground(a.shape((a.accent & 0xFFFFFF) | 0x1A000000, 22, 0));
    tile.addView(tileIcon, new LinearLayout.LayoutParams(a.dp(44), a.dp(44)));
    tile.addView(a.gap(8));
    TextView tileTitle = a.text(title, 13, true);
    tileTitle.setGravity(Gravity.CENTER);
    tile.addView(tileTitle);
    tile.addView(a.gap(4));
    TextView tileSubtitle = a.text(subtitle, 10, false);
    tileSubtitle.setGravity(Gravity.CENTER);
    tile.addView(tileSubtitle);
    tile.setOnClickListener(v -> action.run());
    a.passiveLabels(tile);
    return tile;
  }

  void renderSessionLists() {
    JSONArray conversations = a.library.array("conversations");
    List<JSONObject> savedItems = new ArrayList<>(), temporaryItems = new ArrayList<>();
    for (int i = conversations.length() - 1; i >= 0; i--) {
      JSONObject item = conversations.optJSONObject(i);
      if (item == null) continue;
      if (saved(item)) savedItems.add(item);
      else temporaryItems.add(item);
    }

    LinearLayout savedCard = a.card(null);
    TextView savedTitle = a.text("AI 会话 · 已保存", 17, true);
    LinearLayout.LayoutParams savedTitleLp = new LinearLayout.LayoutParams(-1, -2);
    savedTitleLp.bottomMargin = a.dp(12);
    savedCard.addView(savedTitle, savedTitleLp);
    LinearLayout searchRow = a.row();
    EditText filter = a.input("搜索会话名称", sessionFilter, false);
    searchRow.addView(filter, new LinearLayout.LayoutParams(0, a.dp(44), 1));
    TextView dateFilter = a.text("日期筛选", 14, false);
    dateFilter.setTextColor(a.accent);
    dateFilter.setTypeface(null, android.graphics.Typeface.NORMAL);
    dateFilter.setPadding(a.dp(8), 0, 0, 0);
    dateFilter.setOnClickListener(v -> showDateFilter());
    searchRow.addView(dateFilter);
    savedCard.addView(searchRow);
    track(
        filter,
        value -> {
          sessionFilter = value;
          savedLimit = 10;
        });
    filter.setOnEditorActionListener(
        (v, action, event) -> {
          sessionFilter = filter.getText().toString();
          savedLimit = 10;
          a.render();
          return true;
        });
    if (showDateFields) addDateFields(savedCard);

    int shown = 0, matched = 0;
    for (JSONObject item : savedItems) {
      if (!Library.normalized(title(item)).contains(Library.normalized(sessionFilter))
          || !matchesDate(item)) continue;
      matched++;
      if (shown++ < savedLimit) addConversationRow(savedCard, item);
    }
    if (matched == 0) {
      TextView empty =
          a.text(
              sessionFilter.isEmpty() && startDate.isEmpty() && endDate.isEmpty()
                  ? "在 AI 助手中点击“保存会话”，即可在这里继续交流。"
                  : "没有符合条件的会话。",
              13,
              false);
      empty.setPadding(0, a.dp(16), 0, a.dp(16));
      savedCard.addView(empty);
    }
    if (matched > savedLimit)
      savedCard.addView(
          a.button(
              "查看更多（共 " + matched + " 个）",
              v -> {
                savedLimit += 10;
                a.render();
              }));

    LinearLayout tempCard = a.card(null);
    LinearLayout tempHeader = a.row();
    tempHeader.addView(
        a.text("临时 AI 会话（" + temporaryItems.size() + "/5）", 17, true),
        new LinearLayout.LayoutParams(0, -2, 1));
    tempHeader.addView(
        a.button(
            "＋ 新建",
            v -> {
              a.cancel();
              a.clearImage();
              a.session = a.library.newSession("");
              a.screen = "assistant";
              a.render();
            }));
    tempCard.addView(tempHeader);
    TextView temporaryNote = a.text("自动命名，最多保留 5 个；保存后的会话不占名额。", 11, false);
    LinearLayout.LayoutParams temporaryNoteLp = new LinearLayout.LayoutParams(-1, -2);
    temporaryNoteLp.topMargin = a.dp(8);
    temporaryNoteLp.bottomMargin = a.dp(8);
    tempCard.addView(temporaryNote, temporaryNoteLp);
    if (temporaryItems.isEmpty()) {
      TextView empty = a.text("直接开始对话，无需先查词。", 13, false);
      empty.setTextColor(a.subtle);
      empty.setPadding(0, a.dp(10), 0, a.dp(10));
      tempCard.addView(empty);
    } else for (JSONObject item : temporaryItems) addConversationRow(tempCard, item);
  }

  void addConversationRow(LinearLayout parent, JSONObject item) {
    LinearLayout row = a.row();
    LinearLayout info = a.column();
    info.setPadding(a.dp(2), a.dp(8), a.dp(2), a.dp(8));
    info.addView(
        a.button(
            title(item),
            v -> {
              open(item);
              a.screen = "assistant";
              a.render();
            }));
    info.addView(a.text("最近使用 " + sessionDate(item, "last_used_at"), 11, false));
    info.addView(a.text("创建于 " + sessionDate(item, "created_at"), 10, false));
    row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(a.button("删除", v -> a.change(() -> deleteConversation(item))));
    parent.addView(row);
  }

  String sessionDate(JSONObject item, String field) {
    JSONObject metadata = item.optJSONObject("metadata");
    String value = metadata == null ? "" : metadata.optString(field);
    if (value.isEmpty())
      value = item.optString(field.equals("last_used_at") ? "updated_at" : "created_at");
    return value.isEmpty() ? "—" : value.replace('T', ' ').replace("Z", "");
  }

  boolean matchesDate(JSONObject item) {
    String date = sessionDate(item, dateField);
    if (date.length() < 10) return startDate.isEmpty() && endDate.isEmpty();
    String day = date.substring(0, 10);
    return (startDate.isEmpty() || day.compareTo(startDate) >= 0)
        && (endDate.isEmpty() || day.compareTo(endDate) <= 0);
  }

  void showDateFilter() {
    LinearLayout box = a.column();
    box.addView(a.text("日期范围（含首尾日期）", 14, true));
    RadioGroup group = new RadioGroup(a);
    int createdId = View.generateViewId();
    int usedId = View.generateViewId();
    RadioButton created = new RadioButton(a);
    created.setText("创建日期");
    created.setTextColor(a.foreground);
    created.setId(createdId);
    RadioButton used = new RadioButton(a);
    used.setText("最近使用日期");
    used.setTextColor(a.foreground);
    used.setId(usedId);
    group.addView(created);
    group.addView(used);
    group.check(dateField.equals("created_at") ? createdId : usedId);
    box.addView(group);
    EditText start = a.input("开始日期 YYYY-MM-DD", startDate, false);
    EditText end = a.input("结束日期 YYYY-MM-DD（含当天）", endDate, false);
    box.addView(start);
    box.addView(end);
    new AlertDialog.Builder(a)
        .setTitle("日期筛选")
        .setView(box)
        .setNeutralButton(
            "清除",
            (d, w) -> {
              startDate = "";
              endDate = "";
              showDateFields = false;
              a.render();
            })
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "应用",
            (d, w) -> {
              String from = start.getText().toString().trim(), to = end.getText().toString().trim();
              if ((!from.isEmpty() && !validDate(from))
                  || (!to.isEmpty() && !validDate(to))
                  || (!from.isEmpty() && !to.isEmpty() && from.compareTo(to) > 0)) {
                a.say("请输入有效日期范围（YYYY-MM-DD）");
                return;
              }
              dateField = group.getCheckedRadioButtonId() == usedId ? "last_used_at" : "created_at";
              startDate = from;
              endDate = to;
              showDateFields = !from.isEmpty() || !to.isEmpty();
              savedLimit = 10;
              a.render();
            })
        .show();
  }

  boolean validDate(String value) {
    if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
    java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
    format.setLenient(false);
    try {
      format.parse(value);
      return true;
    } catch (java.text.ParseException error) {
      return false;
    }
  }

  void addDateFields(LinearLayout parent) {
    parent.addView(
        a.text(
            (dateField.equals("created_at") ? "创建日期" : "最近使用")
                + " · "
                + (startDate.isEmpty() ? "不限" : startDate)
                + " 至 "
                + (endDate.isEmpty() ? "不限" : endDate),
            11,
            false));
    parent.addView(
        a.button(
            "清除日期筛选",
            v -> {
              startDate = "";
              endDate = "";
              showDateFields = false;
              a.render();
            }));
  }

  void showRecent() {
    JSONArray history = a.library.array("recent_queries");
    AlertDialog dialog =
        new AlertDialog.Builder(a).setTitle("最近查询").setPositiveButton("完成", null).create();
    LinearLayout box = a.column();
    for (int i = history.length() - 1; i >= 0; i--) {
      JSONObject s = history.optJSONObject(i);
      LinearLayout row = a.row();
      row.addView(
          a.button(
              s.optJSONObject("query_results") == null
                  ? title(s)
                  : s.optJSONObject("query_results").optString("word", title(s)),
              v -> {
                dialog.dismiss();
                open(s);
              }),
          new LinearLayout.LayoutParams(0, -2, 1));
      row.addView(
          a.button(
              "删除", v -> a.change(() -> a.library.delete("recent_queries", s.optString("id")))));
      box.addView(row);
    }
    if (history.length() == 0) box.addView(a.text("暂无最近查询", 14, false));
    if (history.length() > 0)
      box.addView(
          a.button(
              "清空最近查询",
              v ->
                  new AlertDialog.Builder(a)
                      .setMessage("清空最近查询？保存的会话和词汇保留。")
                      .setNegativeButton("取消", null)
                      .setPositiveButton(
                          "清空",
                          (d, w) ->
                              a.change(
                                  () -> {
                                    while (history.length() > 0) history.remove(0);
                                  }))
                      .show()));
    ScrollView scroll = new ScrollView(a);
    scroll.addView(box);
    dialog.setView(scroll);
    dialog.show();
  }

  String title(JSONObject s) {
    JSONObject meta = s.optJSONObject("metadata");
    return meta == null
        ? s.optString("title", s.optString("input_text", "会话"))
        : meta.optString("title", "会话");
  }

  boolean saved(JSONObject s) {
    return s.optBoolean("saved")
        || s.optJSONObject("metadata") != null
            && s.optJSONObject("metadata").optString("retention").equals("saved");
  }

  void image(LinearLayout c) {
    if (a.image == null) return;
    ImageView img = new ImageView(a);
    img.setImageURI(a.image);
    img.setScaleType(ImageView.ScaleType.FIT_CENTER);
    c.addView(img, new LinearLayout.LayoutParams(-1, a.dp(150)));
    c.addView(a.text("图片区域将连同文字一并发送给已配置的视觉模型", 12, false));
  }

  void submit() {
    if (a.active != null) {
      a.say("请等待当前请求或取消");
      return;
    }
    String queryText = homeMode.equals("image") ? "" : draft;
    String queryContext = homeMode.equals("image") ? "" : context;
    Uri queryImage = homeMode.equals("text") ? null : a.image;
    if (queryText.trim().isEmpty() && queryImage == null) {
      a.say("请输入内容或选择图片");
      return;
    }
    a.session = a.library.newSession(queryText.trim());
    a.image = queryImage;
    Library.put(a.session, "source_context", queryContext);
    if (queryImage != null) {
      storeImageSource(a.session);
      Library.put(a.session, "query_type", queryText.isEmpty() ? "image" : "both");
    } else {
      a.originalImage = null;
      a.imageEditorState = ImageEditorState.initial();
      a.imageRegion = null;
      Library.put(a.session, "query_type", "text");
    }
    JSONObject local = queryImage == null ? a.library.local(queryText) : null;
    if (local != null) {
      JSONObject r = Library.copy(local);
      Library.put(r, "word", local.optString("content"));
      Library.put(r, "is_from_local", true);
      Library.put(r, "local_vocabulary_id", local.optString("id"));
      finishResult(r);
      a.persist();
      a.render();
    } else send(queryText + (queryContext.isEmpty() ? "" : "\n语境/要求：" + queryContext), true);
  }

  JSONArray messages() {
    JSONArray m = a.session.optJSONArray("ai_messages");
    if (m == null) {
      m = new JSONArray();
      Library.put(a.session, "ai_messages", m);
    }
    return m;
  }

  void message(String role, String content, JSONObject action) {
    JSONObject m = new JSONObject();
    Library.put(m, "id", Library.id());
    Library.put(m, "role", role);
    Library.put(m, "content", content);
    Library.put(m, "created_at", Library.now());
    if (action != null) Library.put(m, "action", action);
    messages().put(m);
  }

  JSONObject pending() {
    if (a.session == null) return null;
    JSONObject p = a.session.optJSONObject("pending_lookup");
    if (p != null && p.has("revision") && p.optInt("revision") != a.session.optInt("revision"))
      return null;
    return p == null ? null : p.optJSONObject("lookup") == null ? p : p.optJSONObject("lookup");
  }

  JSONArray copyMessages() {
    JSONObject wrapper = new JSONObject();
    Library.put(wrapper, "messages", messages());
    return Library.copy(wrapper).optJSONArray("messages");
  }

  void send(String text) {
    send(text, false);
  }

  void send(String text, boolean fromHub) {
    sendInternal(text, fromHub, true);
  }

  void retryLast() {
    if (a.session == null || a.active != null) return;
    JSONArray history = messages();
    for (int i = history.length() - 1; i >= 0; i--) {
      JSONObject last = history.optJSONObject(i);
      if (last != null && last.optString("role").equals("user")) {
        sendInternal(
            last.optString("content"), a.session.optBoolean("last_request_from_hub"), false);
        return;
      }
    }
  }

  void sendInternal(String text, boolean fromHub, boolean append) {
    if (a.active != null) {
      a.say("请等待当前请求或取消");
      return;
    }
    if (text.trim().isEmpty() && a.image == null) return;
    if (a.session == null) a.session = a.library.newSession(text);
    final JSONObject target = a.session;
    final String explicitTarget = draft;
    final Uri requestImage = fromHub && homeMode.equals("text") ? null : a.image;
    final boolean hasImage = requestImage != null;
    JSONArray previous = copyMessages();
    if (!append
        && previous.length() > 0
        && previous.optJSONObject(previous.length() - 1).optString("role").equals("user"))
      previous.remove(previous.length() - 1);
    JSONObject resultContext = target.optJSONObject("query_results");
    if (resultContext != null) {
      JSONObject contextMessage = new JSONObject();
      Library.put(contextMessage, "role", "assistant");
      JSONObject contextAction = new JSONObject();
      Library.put(contextAction, "kind", "answer");
      Library.put(contextAction, "message", "当前词条完整上下文：" + resultContext.toString());
      Library.put(contextMessage, "content", contextAction.toString());
      previous.put(contextMessage);
    }
    if (append) message("user", text, null);
    Library.put(target, "last_request_from_hub", fromHub);
    target.remove("last_error");
    partialReply = "";
    Library.put(target, "pending_lookup", JSONObject.NULL);
    a.library.touchSession(target, true);
    if (!a.persist()) return;
    a.screen = "assistant";
    a.request(
        client ->
            client.agent(
                previous,
                text,
                requestImage == null ? null : ImageTools.dataUrl(a, requestImage),
                delta ->
                    a.handler.post(
                        () -> {
                          if (a.active != client || a.session != target) return;
                          partialReply += delta;
                          if (assistantView != null) assistantView.updatePartial(partialReply);
                        })),
        reply -> {
          if (a.session != target) return;
          applyReply(
              reply,
              reply.optJSONObject("result") != null
                  && AgentProtocol.canApplyDirectResult(
                      explicitTarget, reply.optJSONObject("result"), fromHub, hasImage));
        },
        false);
  }

  void applyReply(JSONObject original, boolean applyDirectly) {
    JSONObject reply = Library.copy(original);
    if (reply.optString("kind").equals("lookup_result") && !applyDirectly) {
      JSONObject result = reply.optJSONObject("result");
      JSONObject intent = new JSONObject();
      Library.put(intent, "target", result.optString("word"));
      Library.put(intent, "type", result.optString("type"));
      reply = new JSONObject();
      Library.put(reply, "kind", "confirm_lookup");
      Library.put(reply, "message", "将查询目标设为「" + result.optString("word") + "」？请返回首页确认。");
      Library.put(reply, "lookup", intent);
    }
    String kind = reply.optString("kind");
    if (kind.equals("lookup_result")) {
      JSONObject r = reply.optJSONObject("result");
      message("assistant", "已整理词条：" + r.optString("word"), reply);
      finishResult(r);
    } else {
      message("assistant", reply.optString("message"), reply);
      if (kind.equals("confirm_lookup")) {
        JSONObject proposal = new JSONObject();
        Library.put(proposal, "id", Library.id());
        Library.put(proposal, "revision", a.session.optInt("revision"));
        Library.put(
            proposal,
            "message_id",
            messages().optJSONObject(messages().length() - 1).optString("id"));
        Library.put(proposal, "lookup", reply.optJSONObject("lookup"));
        Library.put(a.session, "pending_lookup", proposal);
      }
      a.library.touchSession(a.session, true);
      a.screen = "assistant";
    }
  }

  void lookup(JSONObject intent) {
    if (a.session == null || pending() != intent || !a.screen.equals("tabs") || a.page != 0) {
      a.say("源输入已修改，请重新提交以确认目标");
      a.render();
      return;
    }
    final JSONObject target = a.session;
    String word = intent.optString("target");
    JSONObject local = a.library.local(word);
    if (local != null) {
      JSONObject r = Library.copy(local);
      Library.put(r, "word", local.optString("content"));
      Library.put(r, "is_from_local", true);
      Library.put(r, "local_vocabulary_id", local.optString("id"));
      finishResult(r);
      a.persist();
      a.render();
      return;
    }
    a.request(
        client ->
            client.lookup(
                word,
                intent.optString("context_quote") + "\n" + intent.optString("user_requirement"),
                a.imageData()),
        reply -> {
          if (a.session != target) return;
          Library.put(target, "pending_lookup", JSONObject.NULL);
          message("assistant", "已确认查询：" + word, null);
          finishResult(reply);
        });
  }

  void finishResult(JSONObject r) {
    JSONArray sessionImages = a.session.optJSONArray("input_images");
    if (sessionImages != null && sessionImages.length() > 0) {
      JSONArray ms = r.optJSONArray("meanings");
      if (ms != null)
        for (int i = 0; i < ms.length(); i++) {
          JSONObject meaning = ms.optJSONObject(i);
          JSONArray images = meaning.optJSONArray("source_images");
          if (images == null) {
            images = new JSONArray();
            Library.put(meaning, "source_images", images);
          }
          for (int j = 0; j < sessionImages.length(); j++) {
            String sourceImage = sessionImages.optString(j);
            if (!NotebookUi.contains(images, sourceImage)) images.put(sourceImage);
          }
        }
    }
    Library.put(a.session, "query_results", r);
    Library.put(a.session, "session_state", "ready");
    Library.put(a.session, "pending_lookup", JSONObject.NULL);
    a.session.remove("last_error");
    a.library.touchSession(a.session, true);
    a.screen = "result";
    organizing = false;
    editingMeanings.clear();
    autoRecord();
  }

  void open(JSONObject s) {
    if (assistantView != null) assistantView.saveAnchor();
    a.cancel();
    JSONObject existing = a.library.find("conversations", s.optString("id"));
    a.session = Library.copy(existing == null ? s : existing);
    draft = a.session.optString("input_text");
    context = a.session.optString("source_context");
    JSONArray images = a.session.optJSONArray("input_images");
    a.image = images != null && images.length() > 0 ? Uri.parse(images.optString(0)) : null;
    a.originalImage = a.image;
    JSONArray selected = a.session.optJSONArray("native_selected_images");
    if (selected != null && selected.length() > 0) a.image = Uri.parse(selected.optString(0));
    a.imageEditorState = ImageEditorState.fromJson(a.session.optJSONObject("image_editor_state"));
    JSONArray regions = a.session.optJSONArray("selected_regions");
    a.imageRegion = regions == null ? null : regions.optJSONObject(0);
    organizing = false;
    editingMeanings.clear();
    a.library.touchSession(a.session, false);
    a.persist();
    a.screen = a.session.optJSONObject("query_results") != null ? "result" : "assistant";
    a.render();
  }

  void assistant() {
    if (a.session == null) a.session = a.library.newSession("");
    assistantView = AssistantUi.show(a, this);
    if (!partialReply.isEmpty()) assistantView.updatePartial(partialReply);
  }

  void storeImageSource(JSONObject target) {
    if (a.image == null) {
      for (String key :
          new String[] {
            "input_images", "native_selected_images", "selected_regions", "image_editor_state"
          }) target.remove(key);
      return;
    }
    Uri original = a.originalImage == null ? a.image : a.originalImage;
    Library.put(target, "input_images", new JSONArray().put(original.toString()));
    if (!original.toString().equals(a.image.toString()))
      Library.put(target, "native_selected_images", new JSONArray().put(a.image.toString()));
    else target.remove("native_selected_images");
    Library.put(target, "image_editor_state", a.imageEditorState.toJson());
    if (a.imageRegion != null)
      Library.put(target, "selected_regions", new JSONArray().put(a.imageRegion));
    else target.remove("selected_regions");
  }

  void autoRecord() {
    if (!QueryWorkflow.shouldAutoRecord(a.session, a.library.settings())) return;
    JSONObject before = a.library.snapshot();
    try {
      String notebook = a.library.settings().optString("default_notebook_id", "nb_default");
      JSONObject display = new JSONObject();
      Library.put(display, "fields", QueryWorkflow.defaultFields(a.library.settings()));
      String id =
          a.library.saveResult(
              a.session.getJSONObject("query_results"), new String[] {notebook}, display);
      JSONObject saved = new JSONObject();
      Library.put(saved, "saved", true);
      Library.put(saved, "vocabulary_id", id);
      Library.put(saved, "notebook_ids", new JSONArray().put(notebook));
      Library.put(saved, "saved_at", Library.now());
      Library.put(a.session, "saved_content", saved);
      Library.put(a.session, "auto_record_key", QueryWorkflow.resultKey(a.session));
      a.session.remove("auto_record_error");
    } catch (Exception error) {
      a.library = new Library(before);
      Library.put(a.session, "auto_record_error", error.getMessage());
    }
    a.library.touchSession(a.session, true);
  }

  void updateResult(JSONObject updated) {
    if (a.session == null || a.active != null) {
      a.say("请先等待当前请求完成");
      return;
    }
    Library.put(a.session, "query_results", updated);
    a.library.touchSession(a.session, true);
    if (a.persist()) a.render();
  }

  void presentation(JSONObject meaning, boolean selected, String choice, boolean example) {
    try {
      updateResult(
          QueryWorkflow.presentation(
              a.session.getJSONObject("query_results"),
              meaning.optString("id"),
              selected,
              choice,
              example));
    } catch (Exception error) {
      a.error(error);
    }
  }

  void supplement() {
    if (a.session == null || a.active != null) return;
    final JSONObject target = a.session;
    final JSONObject original = Library.copy(target.optJSONObject("query_results"));
    final int revision = target.optInt("revision");
    a.request(
        client ->
            client.lookup(original.optString("word"), "请为此词汇提供更加丰富、深入的语境用法与高级搭配。保留原查询目标。", null),
        incoming -> {
          if (a.session != target || target.optInt("revision") != revision) return;
          Library.put(target, "query_results", QueryWorkflow.mergeSupplement(original, incoming));
          a.library.touchSession(target, true);
        });
  }

  void addMeaningControls(LinearLayout card, JSONObject meaning) {
    String id = meaning.optString("id");
    boolean selected = meaning.optBoolean("selected", true),
        example = meaning.optBoolean("save_example", true);
    String choice =
        meaning.optString(
            "definition_choice", meaning.optString("zh_definition").isEmpty() ? "en" : "zh");
    LinearLayout choices = a.row();
    CheckBox visible = new CheckBox(a);
    visible.setText("显示本义");
    visible.setTextColor(a.foreground);
    visible.setChecked(selected);
    visible.setOnCheckedChangeListener(
        (v, checked) -> presentation(meaning, checked, choice, example));
    choices.addView(visible);
    for (String language : new String[] {"zh", "en"}) {
      Button button =
          a.chip(
              language.equals("zh") ? "中文" : "英文",
              choice.equals(language),
              v -> presentation(meaning, selected, language, example));
      button.setEnabled(
          !meaning
              .optString(language.equals("zh") ? "zh_definition" : "en_definition")
              .trim()
              .isEmpty());
      choices.addView(button);
    }
    card.addView(choices);
    CheckBox examples = new CheckBox(a);
    examples.setText("显示例句");
    examples.setTextColor(a.foreground);
    examples.setChecked(example);
    examples.setOnCheckedChangeListener(
        (v, checked) -> presentation(meaning, selected, choice, checked));
    card.addView(examples);
    if (!editingMeanings.contains(id)) {
      card.addView(
          a.button(
              "就地编辑释义",
              v -> {
                editingMeanings.add(id);
                a.render();
              }));
      return;
    }
    String[] keys = {
      "part_of_speech", "zh_definition", "en_definition", "phonetic", "example", "source", "remarks"
    };
    String[] names = {"词性", "中文释义", "英文释义", "音标", "例句", "来源", "备注"};
    List<EditText> inputs = new ArrayList<>();
    for (int i = 0; i < keys.length; i++) {
      card.addView(a.text(names[i], 12, true));
      EditText input = a.input(names[i], meaning.optString(keys[i]), i != 0 && i != 3);
      inputs.add(input);
      card.addView(input);
    }
    LinearLayout actions = a.row();
    actions.addView(
        a.button(
            "取消编辑",
            v -> {
              editingMeanings.remove(id);
              a.render();
            }));
    actions.addView(
        a.button(
            "保存本条修改",
            v -> {
              try {
                JSONObject fields = new JSONObject();
                for (int i = 0; i < keys.length; i++)
                  fields.put(keys[i], inputs.get(i).getText().toString());
                JSONObject updated =
                    QueryWorkflow.editMeaning(a.session.getJSONObject("query_results"), id, fields);
                editingMeanings.remove(id);
                updateResult(updated);
              } catch (Exception error) {
                a.error(error);
              }
            }));
    card.addView(actions);
  }

  void deleteConversation(JSONObject s) {
    a.titles.remove(s.optString("id"));
    if (a.session != null && s.optString("id").equals(a.session.optString("id"))) {
      a.cancel();
      a.session = null;
    }
    a.library.delete("conversations", s.optString("id"));
  }

  void saveConversation() {
    EditText title = a.input("会话名称（最多40字）", title(a.session), false);
    new AlertDialog.Builder(a)
        .setTitle("保存 / 重命名")
        .setView(title)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "保存",
            (d, w) -> {
              String t = title.getText().toString().trim();
              if (t.isEmpty()) {
                a.say("名称不能为空");
                return;
              }
              Library.put(a.session, "title", t.substring(0, Math.min(40, t.length())));
              Library.put(a.session, "saved", true);
              JSONObject meta = a.session.optJSONObject("metadata");
              if (meta != null) {
                Library.put(meta, "title", t);
                Library.put(meta, "retention", "saved");
              }
              a.titles.manual(a.session);
              a.library.touchSession(a.session, false);
              a.persist();
              a.render();
            })
        .show();
  }

  void result() {
    JSONObject r = a.session == null ? null : a.session.optJSONObject("query_results");
    if (r == null) {
      a.body.addView(a.text("暂无词条", 18, true));
      return;
    }
    LinearLayout heading = a.card(null);
    heading.setPadding(a.dp(20), a.dp(16), a.dp(20), a.dp(16));
    LinearLayout wordRow = a.row();
    wordRow.addView(
        a.text(r.optString("word", r.optString("content")), 24, true),
        new LinearLayout.LayoutParams(0, -2, 1));
    TextView type = a.text(r.optString("type").equals("phrase") ? "短语" : "单词", 11, true);
    type.setPadding(a.dp(8), a.dp(4), a.dp(8), a.dp(4));
    type.setBackground(a.shape(a.inputBg, 12, 0));
    wordRow.addView(type);
    Button enrich = a.chip("AI 补充", false, v -> supplement());
    enrich.setEnabled(a.active == null);
    wordRow.addView(enrich);
    if (a.library.local(r.optString("word")) != null) {
      TextView saved = a.text("✓ 已在笔记中", 12, true);
      saved.setTextColor(a.accent);
      saved.setPadding(a.dp(8), a.dp(5), a.dp(8), a.dp(5));
      saved.setBackground(a.shape((a.accent & 0xFFFFFF) | 0x1A000000, 8, a.accent));
      LinearLayout.LayoutParams savedLp = new LinearLayout.LayoutParams(-2, -2);
      savedLp.leftMargin = a.dp(8);
      wordRow.addView(saved, savedLp);
    }
    heading.addView(wordRow);
    if (r.optBoolean("is_from_local")) {
      TextView local = a.text("本地词库记录", 11, true);
      local.setTextColor(a.accent);
      local.setPadding(a.dp(8), a.dp(3), a.dp(8), a.dp(3));
      local.setBackground(a.shape((a.accent & 0xFFFFFF) | 0x1A000000, 12, 0));
      heading.addView(local, new LinearLayout.LayoutParams(-2, -2));
    }
    heading.addView(a.gap(8));
    heading.addView(a.text(r.optString("phonetic"), 16, false));
    if (!r.optString("recognized_text").isEmpty())
      heading.addView(a.text("识别文字：" + r.optString("recognized_text"), 14, false));
    JSONArray ms = r.optJSONArray("meanings");
    if (a.session.has("auto_record_error"))
      a.body.addView(
          a.text("自动记录未完成：" + a.session.optString("auto_record_error") + "。可整理后手动保存。", 12, false));
    TextView section = a.text("释义与例句 (" + (ms == null ? 0 : ms.length()) + " 条)", 13, true);
    section.setTextColor(a.muted);
    LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(-1, -2);
    sectionLp.topMargin = a.dp(12);
    sectionLp.bottomMargin = a.dp(12);
    a.body.addView(section, sectionLp);
    if (ms != null)
      for (int i = 0; i < ms.length(); i++) {
        JSONObject m = ms.optJSONObject(i);
        LinearLayout c = a.card(null);
        LinearLayout meaningHeader = a.row();
        TextView number = a.text(String.valueOf(i + 1), 12, true);
        number.setGravity(Gravity.CENTER);
        number.setTextColor(-1);
        number.setBackground(a.shape(a.accent, 12, 0));
        meaningHeader.addView(number, new LinearLayout.LayoutParams(a.dp(24), a.dp(24)));
        TextView part = a.text(m.optString("part_of_speech", "释义 " + (i + 1)), 12, true);
        part.setTextColor(a.accent);
        part.setPadding(a.dp(8), a.dp(3), a.dp(8), a.dp(3));
        part.setBackground(a.shape((a.accent & 0xFFFFFF) | 0x1A000000, 12, 0));
        LinearLayout.LayoutParams partLp = new LinearLayout.LayoutParams(-2, -2);
        partLp.leftMargin = a.dp(8);
        meaningHeader.addView(part, partLp);
        c.addView(meaningHeader);
        if (organizing) addMeaningControls(c, m);
        for (String f : new String[] {"zh_definition", "en_definition"})
          if (!m.optString(f).isEmpty()) {
            LinearLayout definition = a.column();
            definition.setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10));
            definition.setBackground(a.shape(a.inputBg, 10, a.border));
            definition.addView(a.text(f.equals("zh_definition") ? "中文释义" : "英文释义", 11, true));
            TextView value = a.text(m.optString(f), 15, f.equals("zh_definition"));
            value.setLineSpacing(a.dp(3), 1);
            if (f.equals("en_definition"))
              value.setTypeface(null, android.graphics.Typeface.ITALIC);
            definition.addView(value);
            LinearLayout.LayoutParams definitionLp = new LinearLayout.LayoutParams(-1, -2);
            definitionLp.topMargin = a.dp(8);
            c.addView(definition, definitionLp);
          }
        if (!m.optString("example").isEmpty()) {
          LinearLayout example = a.row();
          example.setBackground(a.shape(a.inputBg, 5, 0));
          View stripe = new View(a);
          stripe.setBackgroundColor(a.accent);
          example.addView(stripe, new LinearLayout.LayoutParams(a.dp(3), -1));
          LinearLayout contents = a.column();
          contents.setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10));
          contents.addView(a.text("典型例句:", 11, true));
          TextView ex = a.text("\"" + m.optString("example") + "\"", 14, false);
          ex.setTypeface(null, android.graphics.Typeface.ITALIC);
          contents.addView(ex);
          example.addView(contents, new LinearLayout.LayoutParams(0, -2, 1));
          LinearLayout.LayoutParams exampleLp = new LinearLayout.LayoutParams(-1, -2);
          exampleLp.topMargin = a.dp(12);
          c.addView(example, exampleLp);
        }
        for (String f : new String[] {"source", "remarks"})
          if (!m.optString(f).isEmpty()) c.addView(a.text(m.optString(f), 13, false));
        JSONArray sourceImages = m.optJSONArray("source_images");
        if (sourceImages != null)
          for (int j = 0; j < sourceImages.length(); j++) {
            ImageView sourceImage = new ImageView(a);
            sourceImage.setImageURI(Uri.parse(sourceImages.optString(j)));
            sourceImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
            c.addView(sourceImage, new LinearLayout.LayoutParams(-1, a.dp(160)));
          }
        JSONArray annotations = m.optJSONArray("annotations");
        if (annotations != null)
          for (int j = 0; j < annotations.length(); j++)
            c.addView(a.text("批注：" + annotations.optJSONObject(j).optString("content"), 13, false));
      }
    if (!r.optString("raw_explanation").isEmpty())
      a.card("补充说明").addView(a.text(r.optString("raw_explanation"), 15, false));
    LinearLayout actions = a.fixedFooter();
    actions.addView(
        a.button(
            "打开 AI 助手继续提问",
            v -> {
              a.screen = "assistant";
              a.render();
            }));
    if (!organizing)
      actions.addView(
          a.primaryButton(
              "整理笔记",
              v -> {
                organizing = true;
                a.render();
              }));
    else {
      LinearLayout organizeHeader = a.row();
      organizeHeader.addView(
          a.text("整理模式 · 仅控制笔记显示", 12, true), new LinearLayout.LayoutParams(0, -2, 1));
      organizeHeader.addView(
          a.button(
              "退出整理",
              v -> {
                organizing = false;
                editingMeanings.clear();
                a.render();
              }));
      actions.addView(organizeHeader);
      LinearLayout organizeActions = a.row();
      organizeActions.addView(
          a.outlineButton("添加到默认笔记", v -> a.notebooks.saveDefault(r)),
          new LinearLayout.LayoutParams(0, a.dp(44), 1));
      organizeActions.addView(
          a.primaryButton("选择添加笔记", v -> a.notebooks.saveDialog(r)),
          new LinearLayout.LayoutParams(0, a.dp(44), 1));
      actions.addView(organizeActions);
    }
  }

  void guess() {
    LinearLayout principle = a.card(null);
    principle.setBackground(a.shape((a.accent & 0xFFFFFF) | 0x1A000000, 12, 0));
    TextView principleTitle = a.text("✦  猜词辅助核心原则", 14, true);
    principleTitle.setTextColor(a.accent);
    principle.addView(principleTitle);
    principle.addView(a.gap(4));
    principle.addView(
        a.text("不直接给出释义，而是通过语境线索、语义联想、词根词缀等 4 个阶段逐步引导，助你培养像母语者一样的语境解码能力。", 12, false));
    LinearLayout intro = a.column();
    a.body.addView(intro);
    LinearLayout modes = a.row();
    modes.setPadding(a.dp(3), a.dp(3), a.dp(3), a.dp(3));
    modes.setBackground(a.shape(a.inputBg, 10, 0));
    for (String mode : new String[] {"image", "text"}) {
      String label = mode.equals("image") ? "拍照 / 图片勾画" : "直接输入语境句";
      Button b =
          a.button(
              label,
              v -> {
                if (!guessMode.equals(mode)) {
                  a.cancel();
                  stage = 0;
                  guessReply = null;
                }
                guessMode = mode;
                a.render();
              });
      if (mode.equals(guessMode)) {
        b.setBackground(a.shape(a.card, 8, 0));
        b.setTextColor(a.accent);
      }
      b.setTextSize(13 * a.scale);
      modes.addView(b, new LinearLayout.LayoutParams(0, a.dp(36), 1));
    }
    intro.addView(modes);
    intro.addView(a.gap(12));
    if (guessMode.equals("image")) {
      if (a.image == null) {
        LinearLayout photo = a.column();
        photo.setGravity(Gravity.CENTER);
        photo.setBackground(a.shape(a.card, 12, a.border));
        photo.addView(a.icon("image-outline", 40, a.muted));
        photo.addView(a.gap(12));
        photo.addView(a.text("拍摄英文读物页面或截取屏幕文字", 13, false));
        photo.addView(a.gap(12));
        LinearLayout picks = a.row();
        picks.setGravity(Gravity.CENTER);
        picks.addView(
            a.outlineButton("拍照", v -> a.chooseImage(true)),
            new LinearLayout.LayoutParams(-2, a.dp(36)));
        picks.addView(
            a.outlineButton("从相册选", v -> a.chooseImage(false)),
            new LinearLayout.LayoutParams(-2, a.dp(36)));
        photo.addView(picks);
        ((TextView) photo.getChildAt(2)).setGravity(Gravity.CENTER);
        intro.addView(photo, new LinearLayout.LayoutParams(-1, a.dp(220)));
      } else {
        image(intro);
        intro.addView(a.button("更换图片", v -> a.chooseImage()));
      }
    } else {
      EditText target = a.input("目标词汇（选填）", guessTarget, false);
      track(
          target,
          s -> {
            if (!guessTarget.equals(s)) {
              stage = 0;
              guessReply = null;
            }
            guessTarget = s;
          });
      intro.addView(target);
      EditText ctx = a.input("包含目标词的原句或语境（必填）", guessContext, true);
      track(
          ctx,
          s -> {
            if (!guessContext.equals(s)) {
              stage = 0;
              guessReply = null;
            }
            guessContext = s;
          });
      intro.addView(ctx);
    }
    a.body.addView(a.gap(28));
    a.body.addView(
        a.primaryButton(
            stage == 0 ? "开启猜词推理" : stage < 4 ? "下一阶段提示" : "重新开始",
            v -> {
              if (guessMode.equals("image") && a.image == null
                  || guessMode.equals("text") && guessContext.trim().isEmpty()) {
                a.say(guessMode.equals("image") ? "请先选择图片" : "请输入包含目标词的语境句");
                return;
              }
              int next = stage >= 4 ? 1 : stage + 1;
              a.request(
                  client ->
                      client.guess(
                          guessTarget,
                          guessContext,
                          next,
                          guessMode.equals("image") ? a.imageData() : null),
                  reply -> {
                    guessReply = reply;
                    stage = next;
                  });
            }));
    if (guessReply != null) {
      LinearLayout hint = a.card("阶段 " + stage + " · " + guessReply.optString("stage_title"));
      TextView hintView = a.text(guessReply.optString("hint_content"), 17, false);
      MarkdownText.apply(hintView, guessReply.optString("hint_content"));
      hint.addView(hintView);
      if (stage == 4) {
        JSONObject finalData = guessReply.optJSONObject("final_data");
        if (finalData != null)
          for (String field :
              new String[] {"word", "phonetic", "zh_definition", "en_definition", "example"})
            if (!finalData.optString(field).isEmpty())
              hint.addView(a.text(finalData.optString(field), 16, false));
        hint.addView(
            a.button(
                "转入查询",
                v -> {
                  draft = guessReply.optString("target_word", guessTarget);
                  context = guessContext;
                  homeMode = "text";
                  a.invalidateDraft();
                  a.page = 0;
                  a.screen = "tabs";
                  a.render();
                }));
      }
    }
  }
}
