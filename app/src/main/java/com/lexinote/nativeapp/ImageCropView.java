package com.lexinote.nativeapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/** Native crop editor: drag to select, pinch to zoom, rotate without WebView/JavaScript. */
public final class ImageCropView extends View {
  private Bitmap bitmap;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final Matrix imageMatrix = new Matrix();
  private final RectF selection = new RectF();
  private float zoom = 1, panX, panY, startX, startY, lastX, lastY;
  private boolean pinching;
  private final ScaleGestureDetector detector;

  public ImageCropView(Context context, Bitmap source) {
    super(context);
    bitmap = source;
    setContentDescription("图片裁剪编辑器，单指框选，双指缩放移动");
    setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    detector =
        new ScaleGestureDetector(
            context,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
              @Override
              public boolean onScale(ScaleGestureDetector d) {
                float next = Math.max(1, Math.min(6, zoom * d.getScaleFactor()));
                float factor = next / zoom;
                panX =
                    d.getFocusX()
                        - getWidth() / 2f
                        - (d.getFocusX() - getWidth() / 2f - panX) * factor;
                panY =
                    d.getFocusY()
                        - getHeight() / 2f
                        - (d.getFocusY() - getHeight() / 2f - panY) * factor;
                zoom = next;
                selection.setEmpty();
                invalidate();
                return true;
              }
            });
  }

  private void matrix() {
    float fit =
        Math.min(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
    float scale = fit * zoom;
    imageMatrix.setScale(scale, scale);
    imageMatrix.postTranslate(
        (getWidth() - bitmap.getWidth() * scale) / 2 + panX,
        (getHeight() - bitmap.getHeight() * scale) / 2 + panY);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    canvas.drawColor(Color.rgb(20, 30, 36));
    matrix();
    paint.setStyle(Paint.Style.FILL);
    canvas.drawBitmap(bitmap, imageMatrix, paint);
    if (!selection.isEmpty()) {
      paint.setColor(0x380DCCB4);
      canvas.drawRect(selection, paint);
      paint.setColor(0xFF52E3CE);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(3 * getResources().getDisplayMetrics().density);
      canvas.drawRect(selection, paint);
    }
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    detector.onTouchEvent(event);
    if (event.getPointerCount() > 1) {
      float x = (event.getX(0) + event.getX(1)) / 2, y = (event.getY(0) + event.getY(1)) / 2;
      if (pinching && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
        panX += x - lastX;
        panY += y - lastY;
      }
      pinching = true;
      lastX = x;
      lastY = y;
      selection.setEmpty();
      invalidate();
      return true;
    }
    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
      pinching = false;
      startX = event.getX();
      startY = event.getY();
      selection.setEmpty();
    } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !pinching) {
      selection.set(
          Math.min(startX, event.getX()),
          Math.min(startY, event.getY()),
          Math.max(startX, event.getX()),
          Math.max(startY, event.getY()));
      invalidate();
    } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
      performClick();
    }
    return true;
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }

  public Bitmap crop() {
    if (selection.width() < 8 || selection.height() < 8)
      return bitmap.copy(Bitmap.Config.ARGB_8888, false);
    matrix();
    Matrix inverse = new Matrix();
    imageMatrix.invert(inverse);
    RectF bounds = new RectF(selection);
    inverse.mapRect(bounds);
    if (!bounds.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight()))
      throw new IllegalArgumentException("请在图片内框选区域");
    int left = Math.max(0, (int) Math.floor(bounds.left)),
        top = Math.max(0, (int) Math.floor(bounds.top));
    int right = Math.min(bitmap.getWidth(), (int) Math.ceil(bounds.right)),
        bottom = Math.min(bitmap.getHeight(), (int) Math.ceil(bounds.bottom));
    return Bitmap.createBitmap(
        bitmap, left, top, Math.max(1, right - left), Math.max(1, bottom - top));
  }

  public void rotate() {
    Matrix rotation = new Matrix();
    rotation.postRotate(90);
    bitmap =
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), rotation, true);
    reset();
  }

  public void reset() {
    zoom = 1;
    panX = panY = 0;
    selection.setEmpty();
    invalidate();
  }
}
