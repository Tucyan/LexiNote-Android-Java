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
  int stage = 0;
  JSONObject guessReply;

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
    LinearLayout c = a.card("今天想理解什么？");
    EditText input = a.input("输入单词、短语或问题", draft, true);
    track(
        input,
        s -> {
          if (!draft.equals(s)) a.invalidateDraft();
          draft = s;
        });
    c.addView(input);
    EditText ctx = a.input("可选：原句或查询要求", context, true);
    track(
        ctx,
        s -> {
          if (!context.equals(s)) a.invalidateDraft();
          context = s;
        });
    c.addView(ctx);
    image(c);
    LinearLayout row = a.row();
    row.addView(a.button("图片 / 相机", v -> a.chooseImage()));
    row.addView(a.button("查询", v -> submit()));
    row.addView(
        a.button(
            "新建助手",
            v -> {
              a.cancel();
              a.image = null;
              a.session = a.library.newSession("");
              a.screen = "assistant";
              a.render();
            }));
    c.addView(row);
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
    LinearLayout recent = a.card("最近查询 · 最多 10 条");
    JSONArray history = a.library.array("recent_queries");
    if (history.length() == 0) recent.addView(a.text("提交后可在这里继续查询", 14, false));
    for (int i = history.length() - 1; i >= 0; i--) {
      JSONObject s = history.optJSONObject(i);
      LinearLayout rowH = a.row();
      rowH.addView(a.button(title(s), v -> open(s)), new LinearLayout.LayoutParams(0, -2, 1));
      rowH.addView(
          a.button(
              "删除", v -> a.change(() -> a.library.delete("recent_queries", s.optString("id")))));
      recent.addView(rowH);
    }
    if (history.length() > 0)
      recent.addView(
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
    LinearLayout conversations = a.card("保存和临时会话");
    EditText filter = a.input("按会话名称筛选", sessionFilter, false);
    conversations.addView(filter);
    conversations.addView(
        a.button(
            "筛选",
            v -> {
              sessionFilter = filter.getText().toString();
              a.render();
            }));
    JSONArray list = a.library.array("conversations");
    for (int i = list.length() - 1; i >= 0; i--) {
      JSONObject s = list.optJSONObject(i);
      if (!Library.normalized(title(s)).contains(Library.normalized(sessionFilter))) continue;
      LinearLayout rr = a.row();
      rr.addView(
          a.button(
              (saved(s) ? "★ " : "◷ ") + title(s),
              v -> {
                open(s);
                a.screen = "assistant";
                a.render();
              }),
          new LinearLayout.LayoutParams(0, -2, 1));
      rr.addView(a.button("删除", v -> a.change(() -> deleteConversation(s))));
      conversations.addView(rr);
    }
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
    if (draft.trim().isEmpty() && a.image == null) {
      a.say("请输入内容或选择图片");
      return;
    }
    a.session = a.library.newSession(draft.trim());
    Library.put(a.session, "source_context", context);
    if (a.image != null) {
      Library.put(a.session, "input_images", new JSONArray().put(a.image.toString()));
      Library.put(a.session, "query_type", draft.isEmpty() ? "image" : "both");
    } else Library.put(a.session, "query_type", "text");
    JSONObject local = a.image == null ? a.library.local(draft) : null;
    if (local != null) {
      JSONObject r = Library.copy(local);
      Library.put(r, "word", local.optString("content"));
      Library.put(r, "is_from_local", true);
      Library.put(r, "local_vocabulary_id", local.optString("id"));
      finishResult(r);
      a.persist();
      a.render();
    } else send(draft + (context.isEmpty() ? "" : "\n语境/要求：" + context), true);
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
    if (a.active != null) {
      a.say("请等待当前请求或取消");
      return;
    }
    if (text.trim().isEmpty() && a.image == null) return;
    if (a.session == null) a.session = a.library.newSession(text);
    final JSONObject target = a.session;
    final String explicitTarget = draft;
    final boolean hasImage = a.image != null;
    JSONArray previous = copyMessages();
    JSONObject resultContext = target.optJSONObject("query_results");
    if (resultContext != null) {
      JSONObject contextMessage = new JSONObject();
      Library.put(contextMessage, "role", "assistant");
      Library.put(contextMessage, "content", "当前词条完整上下文：" + resultContext.toString());
      previous.put(contextMessage);
    }
    message("user", text, null);
    Library.put(target, "pending_lookup", JSONObject.NULL);
    a.library.touchSession(target, true);
    if (!a.persist()) return;
    a.request(
        client -> client.agent(previous, text, a.imageData()),
        reply -> {
          if (a.session != target) return;
          applyReply(
              reply,
              reply.optJSONObject("result") != null
                  && AgentProtocol.canApplyDirectResult(
                      explicitTarget, reply.optJSONObject("result"), fromHub, hasImage));
        });
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
    if (a.image != null) {
      JSONArray ms = r.optJSONArray("meanings");
      if (ms != null)
        for (int i = 0; i < ms.length(); i++) {
          JSONObject meaning = ms.optJSONObject(i);
          JSONArray images = meaning.optJSONArray("source_images");
          if (images == null) {
            images = new JSONArray();
            Library.put(meaning, "source_images", images);
          }
          if (!NotebookUi.contains(images, a.image.toString())) images.put(a.image.toString());
        }
    }
    Library.put(a.session, "query_results", r);
    Library.put(a.session, "session_state", "ready");
    Library.put(a.session, "pending_lookup", JSONObject.NULL);
    a.session.remove("last_error");
    a.library.touchSession(a.session, true);
    a.screen = "result";
  }

  void open(JSONObject s) {
    a.cancel();
    JSONObject existing = a.library.find("conversations", s.optString("id"));
    a.session = Library.copy(existing == null ? s : existing);
    draft = a.session.optString("input_text");
    context = a.session.optString("source_context");
    JSONArray images = a.session.optJSONArray("input_images");
    a.image = images != null && images.length() > 0 ? Uri.parse(images.optString(0)) : null;
    a.library.touchSession(a.session, false);
    a.persist();
    a.screen = a.session.optJSONObject("query_results") != null ? "result" : "assistant";
    a.render();
  }

  void assistant() {
    if (a.session == null) a.session = a.library.newSession("");
    LinearLayout header = a.card(title(a.session));
    header.addView(a.button(saved(a.session) ? "重命名会话" : "保存会话（与保存词汇独立）", v -> saveConversation()));
    if (a.session.optJSONObject("query_results") != null)
      header.addView(
          a.button(
              "查看词条",
              v -> {
                a.screen = "result";
                a.render();
              }));
    JSONArray m = messages();
    if (m.length() == 0) header.addView(a.text("可以查词、翻译、问语法，也可以先上传图片。", 15, false));
    for (int i = 0; i < m.length(); i++) {
      JSONObject msg = m.optJSONObject(i);
      LinearLayout card = a.card(msg.optString("role").equals("user") ? "我" : "LexiNote AI");
      TextView messageView = a.text(msg.optString("content"), 16, false);
      MarkdownText.apply(messageView, msg.optString("content"));
      card.addView(messageView);
      JSONObject action = msg.optJSONObject("action");
      if (action == null) continue;
      JSONArray choices = action.optJSONArray("choices");
      if (choices != null)
        for (int j = 0; j < choices.length(); j++) {
          JSONObject choice = choices.optJSONObject(j);
          String label = choice == null ? choices.optString(j) : choice.optString("label");
          card.addView(
              a.button(
                  label,
                  v -> {
                    follow = label;
                    a.render();
                  }));
        }
      JSONArray questions = action.optJSONArray("follow_up_questions");
      if (questions != null)
        for (int j = 0; j < questions.length(); j++) {
          String q = questions.optString(j);
          card.addView(
              a.button(
                  q,
                  v -> {
                    follow = q;
                    a.render();
                  }));
        }
    }
    JSONObject pending = pending();
    if (pending != null) {
      LinearLayout confirm = a.card("确认查询 · " + pending.optString("target"));
      confirm.addView(
          a.text(
              pending.optString("context_quote") + "\n" + pending.optString("user_requirement"),
              14,
              false));
      confirm.addView(
          a.button(
              "返回首页确认",
              v -> {
                a.screen = "tabs";
                a.page = 0;
                a.render();
              }));
    }
    LinearLayout send = a.card("继续交流");
    image(send);
    EditText f = a.input("输入追问，候选只填入不自动发送", follow, true);
    track(f, s -> follow = s);
    send.addView(f);
    send.addView(a.button("图片", v -> a.chooseImage()));
    send.addView(
        a.button(
            "发送",
            v -> {
              String value = follow;
              follow = "";
              send(value);
            }));
    if (a.session.has("last_error"))
      send.addView(a.text("上次请求：" + a.session.optString("last_error") + "。可重新发送。", 13, false));
  }

  void deleteConversation(JSONObject s) {
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
    LinearLayout heading = a.card(r.optString("word", r.optString("content")));
    heading.addView(
        a.text(
            r.optString("phonetic") + (r.optBoolean("is_from_local") ? " · 本地词库" : ""), 16, false));
    if (!r.optString("recognized_text").isEmpty())
      heading.addView(a.text("识别文字：" + r.optString("recognized_text"), 14, false));
    JSONArray ms = r.optJSONArray("meanings");
    if (ms != null)
      for (int i = 0; i < ms.length(); i++) {
        JSONObject m = ms.optJSONObject(i);
        LinearLayout c = a.card(m.optString("part_of_speech", "释义 " + (i + 1)));
        for (String f :
            new String[] {"zh_definition", "en_definition", "example", "source", "remarks"})
          if (!m.optString(f).isEmpty()) c.addView(a.text(m.optString(f), 16, false));
      }
    if (!r.optString("raw_explanation").isEmpty())
      a.card("补充说明").addView(a.text(r.optString("raw_explanation"), 15, false));
    LinearLayout actions = a.card("整理笔记");
    actions.addView(a.button("选择笔记并保存完整词汇", v -> a.notebooks.saveDialog(r)));
    actions.addView(
        a.button(
            "询问 AI / 补充释义",
            v -> {
              a.screen = "assistant";
              a.render();
            }));
  }

  void guess() {
    LinearLayout c = a.card("四阶段启发");
    EditText target = a.input("目标词汇", guessTarget, false);
    track(
        target,
        s -> {
          if (!guessTarget.equals(s)) {
            stage = 0;
            guessReply = null;
          }
          guessTarget = s;
        });
    c.addView(target);
    EditText ctx = a.input("包含目标词的原句或语境", guessContext, true);
    track(
        ctx,
        s -> {
          if (!guessContext.equals(s)) {
            stage = 0;
            guessReply = null;
          }
          guessContext = s;
        });
    c.addView(ctx);
    image(c);
    c.addView(a.button("图片 / 相机", v -> a.chooseImage()));
    c.addView(a.text("1 语境线索 → 2 结构提示 → 3 推理提示 → 4 最终解析", 14, false));
    c.addView(
        a.button(
            stage == 0 ? "获取第一条提示" : stage < 4 ? "下一阶段提示" : "重新开始",
            v -> {
              if (guessTarget.trim().isEmpty() && a.image == null) {
                a.say("请输入目标或选择图片");
                return;
              }
              int next = stage >= 4 ? 1 : stage + 1;
              a.request(
                  client -> client.guess(guessTarget, guessContext, next, a.imageData()),
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
                "转入查询并保存",
                v -> {
                  draft = guessReply.optString("target_word", guessTarget);
                  context = guessContext;
                  a.page = 0;
                  a.screen = "tabs";
                  a.render();
                }));
      }
    }
  }
}
