package com.lexinote.nativeapp;

import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Recycled assistant timeline and fixed composer. One instance belongs to one rendered activity
 * root.
 */
final class AssistantUi {
  private final MainActivity a;
  private final QueryUi q;
  private final String sessionId;
  private final ListView list;
  private final Adapter adapter;
  private final Button latest;
  private EditText composer;
  private String partial = "";

  private AssistantUi(MainActivity activity, QueryUi query) {
    a = activity;
    q = query;
    sessionId = a.session == null ? "" : a.session.optString("id");
    list = new ListView(a);
    list.setDivider(null);
    list.setBackgroundColor(a.background);
    list.setTranscriptMode(ListView.TRANSCRIPT_MODE_DISABLED);
    list.addHeaderView(makeHeader(), null, false);
    adapter = new Adapter();
    list.setAdapter(adapter);
    latest = a.button("回到最新消息", v -> scrollToLatest());
    latest.setVisibility(View.GONE);
    list.setOnScrollListener(
        new android.widget.AbsListView.OnScrollListener() {
          @Override
          public void onScrollStateChanged(android.widget.AbsListView view, int state) {}

          @Override
          public void onScroll(android.widget.AbsListView view, int first, int visible, int total) {
            if (total == 0) return;
            latest.setVisibility(isAtBottom() ? View.GONE : View.VISIBLE);
          }
        });
  }

  static AssistantUi show(MainActivity a, QueryUi q) {
    AssistantUi ui = new AssistantUi(a, q);
    int index = a.root.indexOfChild(a.scroll);
    if (index < 0) index = Math.max(0, a.root.getChildCount() - 1);
    a.scroll.setVisibility(View.GONE);
    FrameLayout timeline = new FrameLayout(a);
    timeline.addView(ui.list, new FrameLayout.LayoutParams(-1, -1));
    FrameLayout.LayoutParams latestLp =
        new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.RIGHT);
    latestLp.setMargins(0, 0, a.dp(16), a.dp(12));
    timeline.addView(ui.latest, latestLp);
    a.root.addView(timeline, index, new LinearLayout.LayoutParams(-1, 0, 1));
    ui.composer();
    ui.list.post(ui::restoreAnchor);
    return ui;
  }

  void updatePartial(String safeText) {
    boolean followTail = isAtBottom();
    partial = safeText == null ? "" : safeText;
    adapter.notifyDataSetChanged();
    afterRowsChanged(followTail);
  }

  void refresh() {
    boolean followTail = isAtBottom();
    adapter.notifyDataSetChanged();
    afterRowsChanged(followTail);
  }

  void saveAnchor() {
    if (a.session == null || sessionId.isEmpty() || !sessionId.equals(a.session.optString("id")))
      return;
    Library.put(a.session, "assistant_anchor_at_bottom", isAtBottom());
    int rawPosition = list.getFirstVisiblePosition() - list.getHeaderViewsCount();
    if (adapter.getCount() == 0) {
      Library.put(a.session, "assistant_anchor_id", "");
      Library.put(a.session, "assistant_anchor_offset", 0);
      return;
    }
    int listPosition = Math.max(0, rawPosition);
    View first = list.getChildAt(rawPosition < 0 && list.getChildCount() > 1 ? 1 : 0);
    String id = AssistantTimeline.id(q.messages(), listPosition);
    Library.put(a.session, "assistant_anchor_id", id);
    Library.put(a.session, "assistant_anchor_offset", first == null ? 0 : first.getTop());
  }

  private void restoreAnchor() {
    if (a.session == null || !sessionId.equals(a.session.optString("id"))) return;
    if (a.session.optBoolean("assistant_anchor_at_bottom", true)) {
      scrollToLatest();
      return;
    }
    String id = a.session.optString("assistant_anchor_id", "");
    int position = AssistantTimeline.positionOf(q.messages(), id);
    if (position < 0) {
      scrollToLatest();
      return;
    }
    list.setSelectionFromTop(
        position + list.getHeaderViewsCount(), a.session.optInt("assistant_anchor_offset", 0));
  }

  private void afterRowsChanged(boolean followTail) {
    if (followTail) list.post(this::scrollToLatest);
    else latest.setVisibility(View.VISIBLE);
  }

  private boolean isAtBottom() {
    int count = list.getCount();
    if (count == 0) return true;
    if (list.getLastVisiblePosition() < count - 1 || list.getChildCount() == 0) return false;
    View last = list.getChildAt(list.getChildCount() - 1);
    return last.getBottom() <= list.getHeight() - list.getPaddingBottom();
  }

  private void scrollToLatest() {
    if (list.getCount() > 0) list.setSelection(list.getCount() - 1);
    latest.setVisibility(View.GONE);
  }

  private View makeHeader() {
    LinearLayout column = a.column();
    column.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8));
    Uri source = a.image;
    if (source == null && a.session != null) {
      JSONArray images = a.session.optJSONArray("input_images");
      if (images != null && images.length() > 0) source = Uri.parse(images.optString(0));
    }
    if (source != null) {
      LinearLayout imageBlock = a.column();
      TextView sourceLabel = a.text("来源图片 · 点击重新裁剪或选择区域", 12, false);
      sourceLabel.setTextColor(a.muted);
      imageBlock.addView(sourceLabel);
      ImageView preview = new ImageView(a);
      preview.setImageURI(source);
      preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
      imageBlock.addView(preview, new LinearLayout.LayoutParams(-1, a.dp(72)));
      a.passiveLabels(imageBlock);
      imageBlock.setOnClickListener(v -> a.cropImage());
      column.addView(imageBlock);
    }
    String recognized =
        a.session == null
            ? ""
            : a.session.optString("recognized_text", a.session.optString("source_context", ""));
    if (!recognized.isEmpty()) {
      TextView context = a.text("识别上下文：" + recognized, 12, false);
      context.setTextColor(a.muted);
      column.addView(context);
    }
    return column;
  }

  private void addQuestions(LinearLayout parent, JSONArray questions) {
    if (questions == null) return;
    for (int i = 0; i < Math.min(questions.length(), 3); i++) {
      JSONObject choice = questions.optJSONObject(i);
      String label = choice == null ? questions.optString(i) : choice.optString("label");
      parent.addView(a.outlineButton(label, v -> fillComposer(label)));
    }
  }

  private void fillComposer(String text) {
    q.follow = text;
    if (composer != null) composer.setText(text);
  }

  private void composer() {
    LinearLayout footer = a.fixedFooter();
    footer.setBackground(a.shape(a.card, 0, a.border));
    LinearLayout column = a.column();
    if (a.session != null && a.session.has("last_error")) {
      TextView error = a.text("上次请求失败：" + a.session.optString("last_error"), 12, false);
      error.setTextColor(a.muted);
      column.addView(error);
      column.addView(a.outlineButton("重试上一条", v -> q.retryLast()));
    }
    LinearLayout row = a.row();
    composer = a.input("继续提问…", q.follow, true);
    composer.setMinHeight(a.dp(48));
    composer.setOnLongClickListener(
        v -> {
          a.chooseImage();
          return true;
        });
    row.addView(composer, new LinearLayout.LayoutParams(0, a.dp(48), 1));
    Button send =
        a.button(
            a.active != null ? "取消" : "发送",
            v -> {
              if (a.active != null) {
                a.cancel();
                if (a.session != null) {
                  Library.put(a.session, "last_error", "请求已取消");
                  a.library.touchSession(a.session, true);
                  a.persist();
                }
                a.render();
                return;
              }
              String text = composer.getText().toString();
              q.follow = "";
              q.send(text);
              a.render();
            });
    row.addView(send);
    column.addView(row);
    q.track(composer, value -> q.follow = value);
    footer.addView(column);
  }

  private final class Adapter extends BaseAdapter {
    JSONArray messages() {
      return q.messages();
    }

    @Override
    public int getCount() {
      return AssistantTimeline.count(messages()) + (partial.isEmpty() ? 0 : 1);
    }

    @Override
    public JSONObject getItem(int position) {
      return AssistantTimeline.at(messages(), position);
    }

    @Override
    public long getItemId(int position) {
      JSONObject message = getItem(position);
      String id =
          message == null ? "partial:" + sessionId : message.optString("id", "row:" + position);
      return AssistantTimeline.stableId(id);
    }

    @Override
    public boolean hasStableIds() {
      return true;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
      LinearLayout line =
          convertView instanceof LinearLayout ? (LinearLayout) convertView : a.row();
      line.removeAllViews();
      line.setPadding(a.dp(14), a.dp(5), a.dp(14), a.dp(5));
      JSONObject message =
          position < AssistantTimeline.count(messages()) ? getItem(position) : null;
      boolean mine = message != null && "user".equals(message.optString("role"));
      String content = message == null ? partial : message.optString("content");
      LinearLayout bubble = a.column();
      bubble.setPadding(a.dp(13), a.dp(11), a.dp(13), a.dp(11));
      bubble.setBackground(a.shape(mine ? a.accent : a.inputBg, 15, mine ? 0 : a.border));
      MarkdownView body = new MarkdownView(a, mine ? -1 : a.foreground, a.border);
      body.setMarkdown(content);
      bubble.addView(body);
      if (message != null) suggestions(bubble, message);
      if (message != null) confirmation(bubble, message);
      line.setGravity(mine ? Gravity.RIGHT : Gravity.LEFT);
      line.addView(bubble, new LinearLayout.LayoutParams(mine ? -2 : -1, -2));
      return line;
    }

    private void suggestions(LinearLayout bubble, JSONObject message) {
      JSONObject action = message.optJSONObject("action");
      if (action != null) {
        addQuestions(bubble, action.optJSONArray("choices"));
        addQuestions(bubble, action.optJSONArray("follow_up_questions"));
      }
      JSONObject result = message.optJSONObject("result");
      if (result != null) addQuestions(bubble, result.optJSONArray("follow_up_questions"));
      JSONObject queryResult = message.optJSONObject("query_result");
      if (queryResult != null)
        addQuestions(bubble, queryResult.optJSONArray("follow_up_questions"));
    }

    private void confirmation(LinearLayout bubble, JSONObject message) {
      JSONObject action = message.optJSONObject("action");
      if (action == null || !"confirm_lookup".equals(action.optString("kind")) || a.session == null)
        return;
      JSONObject proposal = a.session.optJSONObject("pending_lookup");
      LinearLayout card = a.column();
      card.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
      card.setBackground(a.shape(a.card, 10, a.border));
      JSONObject lookup = action.optJSONObject("lookup");
      card.addView(
          a.text("确认查询 · " + (lookup == null ? "" : lookup.optString("target")), 13, true));
      boolean matchesProposal =
          proposal != null && message.optString("id").equals(proposal.optString("message_id"));
      boolean valid =
          matchesProposal
              && q.pending() != null
              && proposal.optInt("revision", -1) == a.session.optInt("revision");
      if (valid) {
        card.addView(
            a.button(
                "返回首页确认",
                v -> {
                  a.screen = "tabs";
                  a.page = 0;
                  a.render();
                }));
      } else {
        TextView stale = a.text("此确认已失效，请重新提交问题。", 12, false);
        stale.setTextColor(a.muted);
        card.addView(stale);
      }
      bubble.addView(card);
    }
  }
}
