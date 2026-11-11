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

  // Floating Bubble Views (added to contentContainer)
  static class WeChatBubbleView extends View {
    private final MainActivity a;
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private String letter = "";

    WeChatBubbleView(MainActivity a) {
      super(a);
      this.a = a;
      bubblePaint.setStyle(Paint.Style.FILL);
      bubblePaint.setColor(0xE6C8CBCF); // frosted silver-gray matching WeChat
      textPaint.setColor(Color.WHITE);
      textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
      textPaint.setTextAlign(Paint.Align.CENTER);
    }

    void setLetter(String letter) {
      this.letter = letter;
      invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      int w = getWidth();
      int h = getHeight();
      if (w <= 0 || h <= 0) return;

      float pw = a.dp(8); // pointer width pointing right
      float ph = a.dp(12); // pointer height
      float bodyW = w - pw;
      float r = bodyW / 2f; // rounded circular body

      path.reset();
      RectF bodyRect = new RectF(0, 0, bodyW, h);
      path.addRoundRect(bodyRect, r, r, Path.Direction.CW);

      Path pointer = new Path();
      pointer.moveTo(bodyW - a.dp(0.5f), h / 2f - ph / 2f);
      pointer.lineTo(w, h / 2f);
      pointer.lineTo(bodyW - a.dp(0.5f), h / 2f + ph / 2f);
      pointer.close();

      path.op(pointer, Path.Op.UNION);
      canvas.drawPath(path, bubblePaint);

      textPaint.setTextSize(h * 0.52f);
      Rect b = new Rect();
      textPaint.getTextBounds(letter, 0, letter.length(), b);
      float baseLine = h / 2f - b.exactCenterY();
      canvas.drawText(letter, bodyW / 2f, baseLine, textPaint);
    }
  }

  private WeChatBubbleView wechatBubble;
  private LinearLayout cardBubble;
  private TextView cardMainText;
  private TextView cardSubText;
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
      if (wechatBubble != null && wechatBubble.getVisibility() == View.VISIBLE) {
        wechatBubble.animate().alpha(0f).setDuration(160).withEndAction(() -> {
          wechatBubble.setVisibility(View.GONE);
          wechatBubble.setAlpha(1f);
        }).start();
      }
      if (cardBubble != null && cardBubble.getVisibility() == View.VISIBLE) {
        cardBubble.animate().alpha(0f).setDuration(160).withEndAction(() -> {
          cardBubble.setVisibility(View.GONE);
          cardBubble.setAlpha(1f);
        }).start();
      }
    };
  }

  void attachBubble(FrameLayout container) {
    if (wechatBubble != null && wechatBubble.getParent() != null) {
      ((ViewGroup) wechatBubble.getParent()).removeView(wechatBubble);
    }
    if (cardBubble != null && cardBubble.getParent() != null) {
      ((ViewGroup) cardBubble.getParent()).removeView(cardBubble);
    }

    wechatBubble = new WeChatBubbleView(a);
    wechatBubble.setVisibility(View.GONE);
    FrameLayout.LayoutParams wLp = new FrameLayout.LayoutParams(a.dp(60), a.dp(48), Gravity.TOP | Gravity.END);
    wLp.rightMargin = a.dp(24);
    container.addView(wechatBubble, wLp);

    cardBubble = new LinearLayout(a);
    cardBubble.setOrientation(LinearLayout.VERTICAL);
    cardBubble.setGravity(Gravity.CENTER);
    cardBubble.setBackground(a.shape(Color.parseColor("#EE1E293B"), 12, 0));
    cardBubble.setElevation(a.dp(8));
    cardBubble.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8));
    cardBubble.setVisibility(View.GONE);

    cardMainText = new TextView(a);
    cardMainText.setTextColor(Color.WHITE);
    cardMainText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    cardMainText.setTextSize(14 * a.scale);
    cardMainText.setGravity(Gravity.CENTER);
    cardBubble.addView(cardMainText);

    cardSubText = new TextView(a);
    cardSubText.setTextColor(Color.parseColor("#94A3B8"));
    cardSubText.setTextSize(11 * a.scale);
    cardSubText.setGravity(Gravity.CENTER);
    cardBubble.addView(cardSubText);

    FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
    cLp.rightMargin = a.dp(36);
    container.addView(cardBubble, cLp);
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

    // Populate fallback targets so touching ANY letter smoothly navigates to the nearest word
    for (int i = 0; i < alphabetItems.length; i++) {
      if (alphabetTargetIndices[i] == -1) {
        for (int j = i + 1; j < alphabetItems.length; j++) {
          if (alphabetTargetIndices[j] != -1) {
            alphabetTargetIndices[i] = alphabetTargetIndices[j];
            break;
          }
        }
        if (alphabetTargetIndices[i] == -1) {
          for (int j = i - 1; j >= 0; j--) {
            if (alphabetTargetIndices[j] != -1) {
              alphabetTargetIndices[i] = alphabetTargetIndices[j];
              break;
            }
          }
        }
        if (alphabetTargetIndices[i] == -1) alphabetTargetIndices[i] = 0;
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
      if (dateStr.length() >= 10) {
        String ymd = dateStr.substring(0, 10);
        key = ymd;
        try {
          String[] parts = ymd.split("-");
          if (parts.length >= 3) {
            shortLabel = parts[1] + "." + parts[2];
            fullLabel = parts[0] + "年" + Integer.parseInt(parts[1]) + "月" + Integer.parseInt(parts[2]) + "日";
          }
        } catch (Exception ignored) {
          shortLabel = ymd;
          fullLabel = ymd;
        }
      } else if (dateStr.length() >= 7) {
        String ym = dateStr.substring(0, 7);
        key = ym;
        try {
          String[] parts = ym.split("-");
          if (parts.length >= 2) {
            shortLabel = parts[0].substring(Math.max(0, parts[0].length() - 2)) + "." + parts[1];
            fullLabel = parts[0] + "年" + Integer.parseInt(parts[1]) + "月";
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
    float padTop = a.dp(6);
    float padBottom = a.dp(6);
    float usableH = Math.max(1f, h - padTop - padBottom);
    float itemH = usableH / count;
    textPaint.setTextSize(Math.min(a.dp(10.5f), itemH * 0.76f));

    for (int i = 0; i < count; i++) {
      float cy = padTop + (i + 0.5f) * itemH;
      if (i == activeIndex) {
        accentPaint.setColor(a.accent);
        float circleR = Math.min(w / 2f - a.dp(1.5f), itemH / 2f);
        canvas.drawCircle(w / 2f, cy, circleR, accentPaint);
        textPaint.setColor(Color.WHITE);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
      } else if (alphabetItemExists[i]) {
        textPaint.setColor(a.isDark ? 0xFFFFFFFF : 0xFF1E293B);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
      } else {
        // Clearly visible in solid medium slate gray (never ghosted or invisible)
        textPaint.setColor(a.isDark ? 0xFF94A3B8 : 0xFF64748B);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
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
      float lastDrawnY = -100f;
      for (int i = 0; i < count; i++) {
        TimelineSection sec = timelineSections.get(i);
        float ratio = (float) sec.startIndex / Math.max(1, rows.size() - 1);
        float cy = a.dp(16) + ratio * (h - a.dp(32));
        sec.relativeY = cy;

        if (cy - lastDrawnY > a.dp(24) || i == count - 1) {
          String label = sec.displayLabel;
          textPaint.setTextSize(a.dp(7.5f));
          textPaint.getTextBounds(label, 0, label.length(), bounds);
          float badgeW = bounds.width() + a.dp(8);
          float badgeH = a.dp(14);
          RectF badgeRect = new RectF(cx - badgeW / 2f, cy - badgeH / 2f, cx + badgeW / 2f, cy + badgeH / 2f);

          Paint pillBg = new Paint(Paint.ANTI_ALIAS_FLAG);
          pillBg.setStyle(Paint.Style.FILL);
          pillBg.setColor(a.isDark ? 0xDD334155 : 0xDD475569);
          canvas.drawRoundRect(badgeRect, a.dp(4), a.dp(4), pillBg);

          textPaint.setColor(Color.WHITE);
          textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
          float baseLine = cy - bounds.exactCenterY();
          canvas.drawText(label, cx, baseLine, textPaint);
          lastDrawnY = cy + a.dp(10);
        } else {
          thumbPaint.setColor(a.border);
          canvas.drawCircle(cx, cy, a.dp(2.5f), thumbPaint);
        }
      }
    }

    // Scrubber thumb (Photo gallery style pill with arrows)
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
      float padTop = a.dp(6);
      float usableH = Math.max(1f, h - a.dp(12));
      float itemH = usableH / count;
      int idx = Math.max(0, Math.min(count - 1, (int) ((y - padTop) / itemH)));
      if (idx != activeIndex) {
        activeIndex = idx;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        invalidate();

        String letter = alphabetItems[idx];
        showAlphabetBubble(letter, padTop + (idx + 0.5f) * itemH);

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
      JSONObject targetItem = rows.get(target);
      String dateStr = targetItem.optString("created_at", targetItem.optString("updated_at", ""));
      String main = "未知时间";
      if (dateStr.length() >= 10) {
        try {
          String[] parts = dateStr.substring(0, 10).split("-");
          main = parts[0] + "年" + Integer.parseInt(parts[1]) + "月" + Integer.parseInt(parts[2]) + "日";
        } catch (Exception e) {
          main = dateStr.substring(0, 10);
        }
      } else {
        for (int i = timelineSections.size() - 1; i >= 0; i--) {
          TimelineSection s = timelineSections.get(i);
          if (target >= s.startIndex) {
            main = s.fullLabel;
            break;
          }
        }
      }

      String word = targetItem.optString("content", "");
      String sub = (!word.isEmpty() ? word + " · " : "") + "第 " + (target + 1) + " / " + rows.size() + " 词";
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
    if (cardBubble != null) cardBubble.setVisibility(View.GONE);
    if (wechatBubble == null) return;
    wechatBubble.setVisibility(View.VISIBLE);
    wechatBubble.setAlpha(1f);
    wechatBubble.setLetter(letter);

    updateBubblePosition(wechatBubble, touchY, a.dp(48));
  }

  private void showTimelineBubble(String main, String sub, float touchY) {
    if (wechatBubble != null) wechatBubble.setVisibility(View.GONE);
    if (cardBubble == null) return;
    cardBubble.setVisibility(View.VISIBLE);
    cardBubble.setAlpha(1f);
    cardMainText.setText(main);
    cardSubText.setText(sub);

    updateBubblePosition(cardBubble, touchY, a.dp(52));
  }

  private void updateBubblePosition(View view, float touchY, int bubbleApproxHeight) {
    if (view == null) return;
    int containerH = getParent() instanceof ViewGroup ? ((ViewGroup) getParent()).getHeight() : getHeight();
    float targetY = getTop() + touchY - bubbleApproxHeight / 2f;
    targetY = Math.max(a.dp(8), Math.min(containerH - bubbleApproxHeight - a.dp(8), targetY));
    view.setTranslationY(targetY);
  }
}
