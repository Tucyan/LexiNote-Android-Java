package com.lexinote.nativeapp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import java.util.function.Consumer;

/**
 * Modern capsule segmented slider with pill-shaped track, tick dots, accent fill,
 * and a smooth elevated circular thumb. Matches iOS/Material AI control standards.
 */
public class CapsuleSliderView extends View {
  private int numSteps = 7;
  private int activeStep = 0;
  private float currentX = -1f;

  private int accentColor = 0xFF2563EB;
  private int trackColorInactive = 0xFFE2E8F0;
  private int dotColorInactive = 0xFF94A3B8;
  private int dotColorActive = 0x80FFFFFF;
  private int thumbColor = 0xFFFFFFFF;
  private int thumbBorderColor = 0x1A000000;

  private final Paint inactiveTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint activeTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint thumbBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

  private final RectF trackRect = new RectF();
  private final Path trackPath = new Path();

  private float density = 1f;
  private float trackHeight;
  private float thumbRadius;
  private float dotRadius;

  private ValueAnimator snapAnimator;
  private Consumer<Integer> onStepChanged;
  private Consumer<Integer> onStepCommitted;

  public CapsuleSliderView(Context context) {
    super(context);
    init();
  }

  private void init() {
    setFocusable(true);
    density = getResources().getDisplayMetrics().density;
    trackHeight = dp(26f);
    thumbRadius = dp(16f);
    dotRadius = dp(2.5f);

    inactiveTrackPaint.setStyle(Paint.Style.FILL);
    activeTrackPaint.setStyle(Paint.Style.FILL);
    dotPaint.setStyle(Paint.Style.FILL);
    thumbPaint.setStyle(Paint.Style.FILL);
    thumbBorderPaint.setStyle(Paint.Style.STROKE);
    thumbBorderPaint.setStrokeWidth(dp(0.75f));
    shadowPaint.setStyle(Paint.Style.FILL);

    updateColors();
  }

  private float dp(float val) {
    return val * density;
  }

  public void setColors(int accent, boolean isDark) {
    this.accentColor = accent;
    this.trackColorInactive = isDark ? 0xFF334155 : 0xFFE2E8F0;
    this.dotColorInactive = isDark ? 0xFF64748B : 0xFF94A3B8;
    this.dotColorActive = 0x85FFFFFF;
    this.thumbColor = 0xFFFFFFFF;
    this.thumbBorderColor = isDark ? 0xFF475569 : 0x18000000;
    updateColors();
    invalidate();
  }

  private void updateColors() {
    inactiveTrackPaint.setColor(trackColorInactive);
    activeTrackPaint.setColor(accentColor);
    thumbPaint.setColor(thumbColor);
    thumbBorderPaint.setColor(thumbBorderColor);
  }

  public void setNumSteps(int steps) {
    this.numSteps = Math.max(2, steps);
    if (activeStep >= numSteps) activeStep = numSteps - 1;
    currentX = -1f;
    invalidate();
  }

  public int getNumSteps() {
    return numSteps;
  }

  public void setActiveStep(int step) {
    setActiveStep(step, false);
  }

  public void setActiveStep(int step, boolean animate) {
    this.activeStep = Math.max(0, Math.min(step, numSteps - 1));
    if (getWidth() > 0) {
      float targetX = getStepX(activeStep);
      if (animate) {
        snapTo(targetX, activeStep, false);
      } else {
        if (snapAnimator != null && snapAnimator.isRunning()) snapAnimator.cancel();
        currentX = targetX;
        invalidate();
      }
    } else {
      currentX = -1f;
    }
  }

  public int getActiveStep() {
    return activeStep;
  }

  public void setOnStepChanged(Consumer<Integer> listener) {
    this.onStepChanged = listener;
  }

  public void setOnStepCommitted(Consumer<Integer> listener) {
    this.onStepCommitted = listener;
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int desiredHeight = (int) (thumbRadius * 2 + dp(16f));
    int desiredWidth = (int) dp(240f);
    int width = resolveSize(desiredWidth, widthMeasureSpec);
    int height = resolveSize(desiredHeight, heightMeasureSpec);
    setMeasuredDimension(width, height);
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    float centerY = h / 2f;
    float trackRadius = trackHeight / 2f;
    // Track leaves padding on left and right for thumb clearance
    float padding = dp(4f);
    float trackLeft = padding;
    float trackRight = w - padding;
    float trackTop = centerY - trackRadius;
    float trackBottom = centerY + trackRadius;

    trackRect.set(trackLeft, trackTop, trackRight, trackBottom);
    trackPath.reset();
    trackPath.addRoundRect(trackRect, trackRadius, trackRadius, Path.Direction.CW);

    if (currentX < 0 || oldw == 0) {
      currentX = getStepX(activeStep);
    } else {
      currentX = getStepX(activeStep);
    }
  }

  private float getMinX() {
    return trackRect.left + (trackHeight / 2f);
  }

  private float getMaxX() {
    return trackRect.right - (trackHeight / 2f);
  }

  private float getStepX(int step) {
    float minX = getMinX();
    float maxX = getMaxX();
    if (numSteps <= 1) return minX;
    return minX + step * (maxX - minX) / (numSteps - 1);
  }

  private int getNearestStep(float x) {
    float minX = getMinX();
    float maxX = getMaxX();
    if (numSteps <= 1 || maxX <= minX) return 0;
    float stepDist = (maxX - minX) / (numSteps - 1);
    int step = Math.round((x - minX) / stepDist);
    return Math.max(0, Math.min(step, numSteps - 1));
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (trackRect.width() <= 0) return;

    float centerY = getHeight() / 2f;
    float trackRadius = trackHeight / 2f;
    float minX = getMinX();
    float maxX = getMaxX();

    if (currentX < 0) {
      currentX = getStepX(activeStep);
    }
    float clampedX = Math.max(minX, Math.min(maxX, currentX));

    // 1. Draw inactive pill track
    canvas.drawRoundRect(trackRect, trackRadius, trackRadius, inactiveTrackPaint);

    // 2. Draw active track (accent color filled from left up to thumb)
    if (clampedX > trackRect.left) {
      canvas.save();
      canvas.clipPath(trackPath);
      canvas.drawRect(trackRect.left, trackRect.top, clampedX, trackRect.bottom, activeTrackPaint);
      canvas.restore();
    }

    // 3. Draw tick dots along the track
    for (int i = 0; i < numSteps; i++) {
      float sx = getStepX(i);
      // Skip dot if it is covered by the thumb
      if (Math.abs(sx - clampedX) < thumbRadius * 0.7f) {
        continue;
      }
      if (sx < clampedX) {
        dotPaint.setColor(dotColorActive);
      } else {
        dotPaint.setColor(dotColorInactive);
      }
      canvas.drawCircle(sx, centerY, dotRadius, dotPaint);
    }

    // 4. Draw Thumb
    // Soft shadow (dual layer for depth)
    shadowPaint.setColor(0x22000000);
    canvas.drawCircle(clampedX, centerY + dp(1.8f), thumbRadius + dp(0.6f), shadowPaint);
    shadowPaint.setColor(0x10000000);
    canvas.drawCircle(clampedX, centerY + dp(3.2f), thumbRadius + dp(1.4f), shadowPaint);

    // White thumb disk
    canvas.drawCircle(clampedX, centerY, thumbRadius, thumbPaint);

    // Subtle border
    canvas.drawCircle(clampedX, centerY, thumbRadius, thumbBorderPaint);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        if (snapAnimator != null && snapAnimator.isRunning()) {
          snapAnimator.cancel();
        }
        if (getParent() != null) {
          getParent().requestDisallowInterceptTouchEvent(true);
        }
        updateTouchPosition(event.getX(), false);
        return true;

      case MotionEvent.ACTION_MOVE:
        updateTouchPosition(event.getX(), false);
        return true;

      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        float finalX = event.getX();
        int finalStep = getNearestStep(finalX);
        float targetX = getStepX(finalStep);
        snapTo(targetX, finalStep, true);
        if (getParent() != null) {
          getParent().requestDisallowInterceptTouchEvent(false);
        }
        return true;
    }
    return super.onTouchEvent(event);
  }

  private void updateTouchPosition(float touchX, boolean snap) {
    float minX = getMinX();
    float maxX = getMaxX();
    currentX = Math.max(minX, Math.min(maxX, touchX));

    int step = getNearestStep(currentX);
    if (step != activeStep) {
      activeStep = step;
      try {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
      } catch (Exception ignored) {}
      if (onStepChanged != null) {
        onStepChanged.accept(step);
      }
    }
    invalidate();
  }

  private void snapTo(float targetX, int finalStep, boolean notifyCommit) {
    if (snapAnimator != null && snapAnimator.isRunning()) {
      snapAnimator.cancel();
    }
    snapAnimator = ValueAnimator.ofFloat(currentX, targetX);
    snapAnimator.setDuration(130);
    snapAnimator.setInterpolator(new DecelerateInterpolator());
    snapAnimator.addUpdateListener(anim -> {
      currentX = (float) anim.getAnimatedValue();
      invalidate();
    });
    snapAnimator.addListener(new AnimatorListenerAdapter() {
      @Override
      public void onAnimationEnd(Animator animation) {
        currentX = targetX;
        activeStep = finalStep;
        invalidate();
        if (notifyCommit && onStepCommitted != null) {
          onStepCommitted.accept(finalStep);
        }
      }
    });
    snapAnimator.start();
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_MINUS) {
      if (activeStep > 0) {
        setActiveStep(activeStep - 1, true);
        if (onStepChanged != null) onStepChanged.accept(activeStep);
        if (onStepCommitted != null) onStepCommitted.accept(activeStep);
        return true;
      }
    } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_PLUS || keyCode == KeyEvent.KEYCODE_EQUALS) {
      if (activeStep < numSteps - 1) {
        setActiveStep(activeStep + 1, true);
        if (onStepChanged != null) onStepChanged.accept(activeStep);
        if (onStepCommitted != null) onStepCommitted.accept(activeStep);
        return true;
      }
    }
    return super.onKeyDown(keyCode, event);
  }
}
