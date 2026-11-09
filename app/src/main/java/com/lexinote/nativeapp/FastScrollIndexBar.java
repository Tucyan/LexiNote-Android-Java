package com.lexinote.nativeapp;

import android.content.Context;
import android.graphics.*;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;

final class FastScrollIndexBar extends View {
  interface OnIndexScrollListener {
    void onScrollTo(int targetIndex, String label);
  }

  static final int MODE_ALPHABET = 0;
  static final int MODE_TIMELINE = 1;
  static final int MODE_SLIDER = 2;

  private final MainActivity a;
  private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Rect bounds = new Rect();
  private final Handler handler = new Handler(Looper.getMainLooper());

  private int mode = MODE_ALPHABET;
  private List<JSONObject> rows = new ArrayList<>();
  private String sortOrder = "默认";
  private OnIndexScrollListener listener;

  // Alphabet mode data
  private String[] alphabetItems = new String[0];
  private int[] alphabetTargetIndices = new int[0];
  private boolean[] alphabetItemExists = new boolean[0];

  // Timeline mode data
  static class TimelineSection {
    String key;
    String displayLabel;
    String fullLabel;
    int startIndex;
    int count;
    float relativeY;
  }
  private final List<TimelineSection> timelineSections = new ArrayList<>();

  // State
  private int activeIndex = -1;
  private float scrollRatio = 0f;
  private boolean isDragging = false;

  // Floating Bubble View (added to contentContainer)
  private View bubbleView;
  private TextView bubbleMainText;
  private TextView bubbleSubText;
  private Runnable hideBubbleRunnable;

  FastScrollIndexBar(MainActivity a) {
    super(a);
    this.a = a;
    init();
  }

  private void init() {
    textPaint.setTextAlign(Paint.Align.CENTER);
    textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

    accentPaint.setStyle(Paint.Style.FILL);
    accentPaint.setColor(a.accent);

    trackPaint.setStyle(Paint.Style.STROKE);
    trackPaint.setStrokeWidth(a.dp(2));
    trackPaint.setColor(a.border);

    thumbPaint.setStyle(Paint.Style.FILL);
    thumbPaint.setColor(a.accent);

    hideBubbleRunnable = () -> {
      if (bubbleView != null) {
        bubbleView.animate().alpha(0f).setDuration(180).withEndAction(() -> {
          bubbleView.setVisibility(View.GONE);
          bubbleView.setAlpha(1f);
        }).start();
      }
    };
  }

  void attachBubble(FrameLayout container) {
    if (bubbleView != null && bubbleView.getParent() != null) {
      ((ViewGroup) bubbleView.getParent()).removeView(bubbleView);
    }

    LinearLayout bubble = new LinearLayout(a);
    bubble.setOrientation(LinearLayout.VERTICAL);
    bubble.setGravity(Gravity.CENTER);
    bubble.setBackground(a.shape(Color.parseColor("#EE1E293B"), 12, 0));
    bubble.setElevation(a.dp(8));
    bubble.setVisibility(View.GONE);

    bubbleMainText = new TextView(a);
    bubbleMainText.setTextColor(Color.WHITE);
    bubbleMainText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    bubbleMainText.setGravity(Gravity.CENTER);
    bubble.addView(bubbleMainText);

    bubbleSubText = new TextView(a);
    bubbleSubText.setTextColor(Color.parseColor("#94A3B8"));
    bubbleSubText.setTextSize(11 * a.scale);
    bubbleSubText.setGravity(Gravity.CENTER);
    bubble.addView(bubbleSubText);

    bubbleView = bubble;
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
    lp.rightMargin = a.dp(36);
    container.addView(bubbleView, lp);
  }

  void setData(List<JSONObject> items, String currentSortOrder, OnIndexScrollListener l) {
    this.rows = items != null ? items : new ArrayList<>();
    this.sortOrder = currentSortOrder != null ? currentSortOrder : "默认";
    this.listener = l;

    if (rows.size() < 6) {
      setVisibility(View.GONE);
      return;
    }
    setVisibility(View.VISIBLE);

    if ("A-Z".equals(sortOrder)) {
      mode = MODE_ALPHABET;
      initAlphabet(false);
    } else if ("Z-A".equals(sortOrder)) {
      mode = MODE_ALPHABET;
      initAlphabet(true);
    } else if ("最新".equals(sortOrder) || "最早".equals(sortOrder)) {
      mode = MODE_TIMELINE;
      initTimeline();
    } else {
      mode = MODE_SLIDER;
    }
    invalidate();
  }

  private void initAlphabet(boolean reversed) {
    String[] standard = {"↑", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L",
        "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "#"};
    if (reversed) {
      alphabetItems = new String[standard.length];
      alphabetItems[0] = "↑";
      for (int i = 1; i <= 26; i++) {
        alphabetItems[i] = standard[27 - i];
      }
      alphabetItems[27] = "#";
    } else {
      alphabetItems = standard;
    }

    alphabetTargetIndices = new int[alphabetItems.length];
    alphabetItemExists = new boolean[alphabetItems.length];
    Arrays.fill(alphabetTargetIndices, -1);
    alphabetTargetIndices[0] = 0;
    alphabetItemExists[0] = true;

    for (int r = 0; r < rows.size(); r++) {
      JSONObject v = rows.get(r);
      String content = v.optString("content", "").trim();
      if (content.isEmpty()) continue;
      char c = Character.toUpperCase(content.charAt(0));
      if (c >= 'A' && c <= 'Z') {
        int idx = -1;
        for (int i = 1; i < alphabetItems.length - 1; i++) {
          if (alphabetItems[i].charAt(0) == c) {
            idx = i;
            break;
          }
        }
        if (idx != -1) {
          alphabetItemExists[idx] = true;
          if (alphabetTargetIndices[idx] == -1) alphabetTargetIndices[idx] = r;
        }
      } else {
        int hashIdx = alphabetItems.length - 1;
        alphabetItemExists[hashIdx] = true;
        if (alphabetTargetIndices[hashIdx] == -1) alphabetTargetIndices[hashIdx] = r;
      }
    }
  }

  private void initTimeline() {
    timelineSections.clear();
    Map<String, TimelineSection> map = new LinkedHashMap<>();
    for (int i = 0; i < rows.size(); i++) {
      JSONObject v = rows.get(i);
      String dateStr = v.optString("created_at", v.optString("updated_at", ""));
      String key = "其他";
      String shortLabel = "—";
      String fullLabel = "未知时间";
      if (dateStr.length() >= 7) {
        String ym = dateStr.substring(0, 7);
        key = ym;
        try {
          String[] parts = ym.split("-");
          if (parts.length >= 2) {
            shortLabel = "'" + parts[0].substring(Math.max(0, parts[0].length() - 2)) + "." + parts[1];
            fullLabel = parts[0] + "年" + parts[1] + "月";
          }
        } catch (Exception ignored) {
          shortLabel = ym;
          fullLabel = ym;
        }
      }
      TimelineSection sec = map.get(key);
      if (sec == null) {
        sec = new TimelineSection();
        sec.key = key;
        sec.displayLabel = shortLabel;
        sec.fullLabel = fullLabel;
        sec.startIndex = i;
        sec.count = 1;
        map.put(key, sec);
      } else {
        sec.count++;
      }
    }
    timelineSections.addAll(map.values());
  }

  void onScrollProgress(int scrollY, int maxScroll) {
    if (isDragging) return;
    if (maxScroll <= 0) scrollRatio = 0f;
    else scrollRatio = Math.max(0f, Math.min(1f, (float) scrollY / maxScroll));
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    int w = getWidth();
    int h = getHeight();
    if (w <= 0 || h <= 0) return;

    if (mode == MODE_ALPHABET) {
      drawAlphabetMode(canvas, w, h);
    } else if (mode == MODE_TIMELINE) {
      drawTimelineMode(canvas, w, h);
    } else {
      drawSliderMode(canvas, w, h);
    }
  }

  private void drawAlphabetMode(Canvas canvas, int w, int h) {
    int count = alphabetItems.length;
    if (count == 0) return;
    float itemH = (float) h / count;
    textPaint.setTextSize(Math.min(a.dp(10), itemH * 0.75f));

    for (int i = 0; i < count; i++) {
      float cy = (i + 0.5f) * itemH;
      if (i == activeIndex) {
        accentPaint.setColor(a.accent);
        canvas.drawCircle(w / 2f, cy, Math.min(w / 2f - a.dp(2), itemH / 2f - a.dp(1)), accentPaint);
        textPaint.setColor(Color.WHITE);
      } else if (alphabetItemExists[i]) {
        textPaint.setColor(a.isDark ? 0xFFCBD5E1 : 0xFF475569);
      } else {
        textPaint.setColor(a.isDark ? 0x3364748B : 0x3394A3B8);
      }

      String letter = alphabetItems[i];
      textPaint.getTextBounds(letter, 0, letter.length(), bounds);
      float baseLine = cy - bounds.exactCenterY();
      canvas.drawText(letter, w / 2f, baseLine, textPaint);
    }
  }

  private void drawTimelineMode(Canvas canvas, int w, int h) {
    float cx = w / 2f;
    canvas.drawLine(cx, a.dp(16), cx, h - a.dp(16), trackPaint);

    int count = timelineSections.size();
    if (count > 0) {
      textPaint.setTextSize(a.dp(8.5f));
      for (int i = 0; i < count; i++) {
        TimelineSection sec = timelineSections.get(i);
        float ratio = (float) sec.startIndex / Math.max(1, rows.size() - 1);
        float cy = a.dp(16) + ratio * (h - a.dp(32));
        sec.relativeY = cy;

        thumbPaint.setColor(a.border);
        canvas.drawCircle(cx, cy, a.dp(2.5f), thumbPaint);

        if (count <= 8 || i % Math.max(1, count / 7) == 0 || i == count - 1) {
          textPaint.setColor(a.isDark ? 0xFF94A3B8 : 0xFF64748B);
          textPaint.getTextBounds(sec.displayLabel, 0, sec.displayLabel.length(), bounds);
          canvas.drawText(sec.displayLabel, cx, cy - a.dp(4), textPaint);
        }
      }
    }

    // Scrubber thumb (Photo gallery style pill)
    float thumbY = a.dp(16) + scrollRatio * (h - a.dp(32));
    float thumbW = w - a.dp(4);
    float thumbH = a.dp(22);
    RectF thumbRect = new RectF(cx - thumbW / 2f, thumbY - thumbH / 2f, cx + thumbW / 2f, thumbY + thumbH / 2f);
    thumbPaint.setColor(a.accent);
    canvas.drawRoundRect(thumbRect, a.dp(6), a.dp(6), thumbPaint);

    // Indicator arrows
    textPaint.setTextSize(a.dp(7));
    textPaint.setColor(Color.WHITE);
    canvas.drawText("▲", cx, thumbY - a.dp(3), textPaint);
    canvas.drawText("▼", cx, thumbY + a.dp(8), textPaint);
  }

  private void drawSliderMode(Canvas canvas, int w, int h) {
    float cx = w / 2f;
    canvas.drawLine(cx, a.dp(12), cx, h - a.dp(12), trackPaint);

    float thumbY = a.dp(12) + scrollRatio * (h - a.dp(24));
    float thumbW = a.dp(8);
    float thumbH = a.dp(28);
    RectF thumbRect = new RectF(cx - thumbW / 2f, thumbY - thumbH / 2f, cx + thumbW / 2f, thumbY + thumbH / 2f);
    thumbPaint.setColor(a.accent);
    canvas.drawRoundRect(thumbRect, a.dp(4), a.dp(4), thumbPaint);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (rows.isEmpty()) return false;
    int action = event.getAction();
    float y = event.getY();

    switch (action) {
      case MotionEvent.ACTION_DOWN:
      case MotionEvent.ACTION_MOVE:
        isDragging = true;
        getParent().requestDisallowInterceptTouchEvent(true);
        handler.removeCallbacks(hideBubbleRunnable);
        handleTouch(y);
        return true;

      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        isDragging = false;
        activeIndex = -1;
        invalidate();
        handler.postDelayed(hideBubbleRunnable, 250);
        return true;
    }
    return super.onTouchEvent(event);
  }

  private void handleTouch(float y) {
    int h = getHeight();
    if (h <= 0) return;

    if (mode == MODE_ALPHABET) {
      int count = alphabetItems.length;
      if (count == 0) return;
      float itemH = (float) h / count;
      int idx = Math.max(0, Math.min(count - 1, (int) (y / itemH)));
      if (idx != activeIndex) {
        activeIndex = idx;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        invalidate();

        String letter = alphabetItems[idx];
        showAlphabetBubble(letter, (idx + 0.5f) * itemH);

        int target = alphabetTargetIndices[idx];
        if (target >= 0 && listener != null) {
          listener.onScrollTo(target, letter);
        }
      }
    } else if (mode == MODE_TIMELINE) {
      float clampedY = Math.max(a.dp(16), Math.min(h - a.dp(16), y));
      float ratio = (clampedY - a.dp(16)) / Math.max(1, (h - a.dp(32)));
      scrollRatio = ratio;
      invalidate();

      int target = Math.max(0, Math.min(rows.size() - 1, Math.round(ratio * (rows.size() - 1))));
      TimelineSection matchSec = null;
      for (int i = timelineSections.size() - 1; i >= 0; i--) {
        TimelineSection s = timelineSections.get(i);
        if (target >= s.startIndex) {
          matchSec = s;
          break;
        }
      }

      String main = matchSec != null ? matchSec.fullLabel : "时间节点";
      String sub = "第 " + (target + 1) + " / " + rows.size() + " 词";
      showTimelineBubble(main, sub, clampedY);

      if (listener != null) {
        listener.onScrollTo(target, main);
      }
    } else {
      float clampedY = Math.max(a.dp(12), Math.min(h - a.dp(12), y));
      float ratio = (clampedY - a.dp(12)) / Math.max(1, (h - a.dp(24)));
      scrollRatio = ratio;
      invalidate();

      int target = Math.max(0, Math.min(rows.size() - 1, Math.round(ratio * (rows.size() - 1))));
      String label = "第 " + (target + 1) + " / " + rows.size() + " 词";
      String sub = "生词优先".equals(sortOrder) ? ("掌握度排序 · " + target) : ("排序: " + sortOrder);
      showTimelineBubble(label, sub, clampedY);

      if (listener != null) {
        listener.onScrollTo(target, label);
      }
    }
  }

  private void showAlphabetBubble(String letter, float touchY) {
    if (bubbleView == null) return;
    bubbleView.setVisibility(View.VISIBLE);
    bubbleView.setAlpha(1f);

    bubbleView.setBackground(a.shape(Color.parseColor("#EE1E293B"), 26, 0));
    bubbleView.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8));
    bubbleMainText.setText(letter);
    bubbleMainText.setTextSize(24 * a.scale);
    bubbleSubText.setVisibility(View.GONE);

    updateBubblePosition(touchY, a.dp(52));
  }

  private void showTimelineBubble(String main, String sub, float touchY) {
    if (bubbleView == null) return;
    bubbleView.setVisibility(View.VISIBLE);
    bubbleView.setAlpha(1f);

    bubbleView.setBackground(a.shape(Color.parseColor("#EE1E293B"), 10, 0));
    bubbleView.setPadding(a.dp(12), a.dp(6), a.dp(12), a.dp(6));
    bubbleMainText.setText(main);
    bubbleMainText.setTextSize(13 * a.scale);
    bubbleSubText.setVisibility(View.VISIBLE);
    bubbleSubText.setText(sub);

    updateBubblePosition(touchY, a.dp(44));
  }

  private void updateBubblePosition(float touchY, int bubbleApproxHeight) {
    int containerH = getParent() instanceof ViewGroup ? ((ViewGroup) getParent()).getHeight() : getHeight();
    float targetY = touchY - bubbleApproxHeight / 2f;
    targetY = Math.max(a.dp(8), Math.min(containerH - bubbleApproxHeight - a.dp(8), targetY));
    bubbleView.setTranslationY(targetY);
  }
}
