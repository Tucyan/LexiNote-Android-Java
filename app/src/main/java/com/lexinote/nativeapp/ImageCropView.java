package com.lexinote.nativeapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Native full-screen image canvas with reversible selection, ink strokes, rotation, fine angle
 * tilt, pan/zoom, and crop.
 */
public final class ImageCropView extends View {
  public interface OnStateChangeListener {
    void onStateChanged();
  }

  private final Bitmap source;
  private Bitmap displayed;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final Matrix imageMatrix = new Matrix();
  private final ImageEditHistory history;
  private final ScaleGestureDetector detector;
  private final List<float[]> gesturePoints = new ArrayList<>();
  private RectF gestureRect;
  private RectF cropRect;
  private float zoom = 1, panX, panY, startX, startY, lastX, lastY;
  private int fineAngle = 0;
  private int inkColor = 0xFFEF4444; // Default coral red
  private float inkWidth = 4f; // Default 4dp
  private boolean pinching, drawing, released;
  private OnStateChangeListener stateChangeListener;

  public ImageCropView(Context context, Bitmap source) {
    this(context, source, null);
  }

  public ImageCropView(Context context, Bitmap source, ImageEditorState saved) {
    super(context);
    this.source = source;
    history = new ImageEditHistory(saved == null ? ImageEditorState.initial() : saved);
    fineAngle = history.current().fineAngle;
    displayState(history.current());
    setContentDescription("图片编辑器，双指缩放拖动，自由框选或涂鸦");
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
                invalidate();
                notifyChange();
                return true;
              }
            });
  }

  public void setOnStateChangeListener(OnStateChangeListener listener) {
    this.stateChangeListener = listener;
  }

  private void notifyChange() {
    if (stateChangeListener != null) stateChangeListener.onStateChanged();
  }

  private void imageMatrix() {
    if (displayed == null || getWidth() == 0 || getHeight() == 0) return;
    float fit =
        Math.min(
            getWidth() / (float) displayed.getWidth(), getHeight() / (float) displayed.getHeight());
    float scale = fit * zoom;
    imageMatrix.reset();
    imageMatrix.setScale(scale, scale);
    float tx = (getWidth() - displayed.getWidth() * scale) / 2f + panX;
    float ty = (getHeight() - displayed.getHeight() * scale) / 2f + panY;
    imageMatrix.postTranslate(tx, ty);
    if (fineAngle != 0) {
      float cx = getWidth() / 2f + panX;
      float cy = getHeight() / 2f + panY;
      imageMatrix.postRotate(fineAngle, cx, cy);
    }
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (displayed == null) return;
    canvas.drawColor(0xFF0F172A);
    imageMatrix();
    canvas.drawBitmap(displayed, imageMatrix, paint);
    drawInk(
        canvas,
        history.current().inkStrokes,
        displayed.getWidth(),
        displayed.getHeight(),
        true);

    SelectedRegion selection = region();
    if (selection != null)
      drawSelection(canvas, mapRegion(selection), 2.5f * getResources().getDisplayMetrics().density);

    if (cropRect != null) {
      RectF screenCrop = mapRectF(cropRect);
      paint.setColor(0x99000000);
      paint.setStyle(Paint.Style.FILL);
      canvas.drawRect(0, 0, getWidth(), screenCrop.top, paint);
      canvas.drawRect(0, screenCrop.bottom, getWidth(), getHeight(), paint);
      canvas.drawRect(0, screenCrop.top, screenCrop.left, screenCrop.bottom, paint);
      canvas.drawRect(screenCrop.right, screenCrop.top, getWidth(), screenCrop.bottom, paint);

      paint.setColor(0xFFFFFFFF);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
      canvas.drawRect(screenCrop, paint);

      paint.setColor(0xFF10B981);
      paint.setStrokeWidth(3.5f * getResources().getDisplayMetrics().density);
      float cLen = 16f * getResources().getDisplayMetrics().density;
      canvas.drawLine(screenCrop.left, screenCrop.top, screenCrop.left + cLen, screenCrop.top, paint);
      canvas.drawLine(screenCrop.left, screenCrop.top, screenCrop.left, screenCrop.top + cLen, paint);
      canvas.drawLine(screenCrop.right, screenCrop.top, screenCrop.right - cLen, screenCrop.top, paint);
      canvas.drawLine(screenCrop.right, screenCrop.top, screenCrop.right, screenCrop.top + cLen, paint);
      canvas.drawLine(screenCrop.left, screenCrop.bottom, screenCrop.left + cLen, screenCrop.bottom, paint);
      canvas.drawLine(screenCrop.left, screenCrop.bottom, screenCrop.left, screenCrop.bottom - cLen, paint);
      canvas.drawLine(screenCrop.right, screenCrop.bottom, screenCrop.right - cLen, screenCrop.bottom, paint);
      canvas.drawLine(screenCrop.right, screenCrop.bottom, screenCrop.right, screenCrop.bottom - cLen, paint);
      paint.setStyle(Paint.Style.FILL);
    }

    if (drawing) drawGesture(canvas);
  }

  private RectF mapRectF(RectF normalized) {
    RectF v =
        new RectF(
            normalized.left * displayed.getWidth(),
            normalized.top * displayed.getHeight(),
            normalized.right * displayed.getWidth(),
            normalized.bottom * displayed.getHeight());
    imageMatrix.mapRect(v);
    return v;
  }

  private Path mapRegion(SelectedRegion r) {
    Path path = selectionPath(r);
    if (r.points.isEmpty()) {
      RectF bounds = new RectF();
      path.computeBounds(bounds, true);
      imageMatrix.mapRect(bounds);
      path.reset();
      path.addRect(bounds, Path.Direction.CW);
    } else path.transform(imageMatrix);
    return path;
  }

  private Path selectionPath(SelectedRegion r) {
    Path path = new Path();
    int width = displayed.getWidth(), height = displayed.getHeight();
    if (r.points.isEmpty()) {
      path.addRect((float) r.left * width, (float) r.top * height,
          (float) r.right * width, (float) r.bottom * height, Path.Direction.CW);
      return path;
    }
    for (int i = 0; i < r.points.size(); i++) {
      SelectedRegion.Point p = r.points.get(i);
      float x = (float) (p.x * width), y = (float) (p.y * height);
      if (i == 0) path.moveTo(x, y);
      else path.lineTo(x, y);
    }
    path.close();
    return path;
  }

  private void drawSelection(Canvas canvas, Path path, float strokeWidth) {
    paint.setColor(0x2810B981);
    paint.setStyle(Paint.Style.FILL);
    canvas.drawPath(path, paint);
    paint.setColor(0xFF10B981);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(strokeWidth);
    canvas.drawPath(path, paint);
    paint.setStyle(Paint.Style.FILL);
  }

  private void drawGesture(Canvas canvas) {
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);

    if (history.current().tool == ImageEditorState.Tool.CROP && gestureRect != null) {
      paint.setColor(0xFF10B981);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
      canvas.drawRect(gestureRect, paint);
      paint.setStyle(Paint.Style.FILL);
    } else if (history.current().tool == ImageEditorState.Tool.RECTANGLE && gestureRect != null) {
      paint.setColor(0x2810B981);
      paint.setStyle(Paint.Style.FILL);
      canvas.drawRect(gestureRect, paint);
      paint.setColor(0xFF10B981);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(2.5f * getResources().getDisplayMetrics().density);
      canvas.drawRect(gestureRect, paint);
      paint.setStyle(Paint.Style.FILL);
    } else if (!gesturePoints.isEmpty()) {
      paint.setColor(inkColor);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(inkWidth * getResources().getDisplayMetrics().density);
      Path p = new Path();
      p.moveTo(gesturePoints.get(0)[0], gesturePoints.get(0)[1]);
      for (int i = 1; i < gesturePoints.size(); i++)
        p.lineTo(gesturePoints.get(i)[0], gesturePoints.get(i)[1]);
      if (gesturePoints.size() == 1)
        canvas.drawCircle(
            gesturePoints.get(0)[0], gesturePoints.get(0)[1], paint.getStrokeWidth() / 2, paint);
      else canvas.drawPath(p, paint);
      paint.setStyle(Paint.Style.FILL);
    }
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    detector.onTouchEvent(event);
    int action = event.getActionMasked();
    if (event.getPointerCount() > 1) {
      if (action == MotionEvent.ACTION_MOVE) {
        float x = (event.getX(0) + event.getX(1)) / 2, y = (event.getY(0) + event.getY(1)) / 2;
        if (pinching && !detector.isInProgress()) {
          panX += x - lastX;
          panY += y - lastY;
        }
        lastX = x;
        lastY = y;
      }
      pinching = true;
      drawing = false;
      if (action == MotionEvent.ACTION_POINTER_UP) commitViewport();
      invalidate();
      return true;
    }
    if (action == MotionEvent.ACTION_DOWN) {
      if (pinching) return true;
      startX = event.getX();
      startY = event.getY();
      lastX = startX;
      lastY = startY;
      gestureRect = new RectF(startX, startY, startX, startY);
      gesturePoints.clear();
      addGesturePoint(startX, startY);
      drawing = true;
    } else if (action == MotionEvent.ACTION_MOVE && pinching) {
      commitViewport();
      pinching = false;
    } else if (action == MotionEvent.ACTION_MOVE && drawing) {
      ImageEditorState.Tool currentTool = history.current().tool;
      if (currentTool == ImageEditorState.Tool.PAN) {
        panX += event.getX() - lastX;
        panY += event.getY() - lastY;
        lastX = event.getX();
        lastY = event.getY();
      } else if (currentTool == ImageEditorState.Tool.RECTANGLE || currentTool == ImageEditorState.Tool.CROP) {
        gestureRect.set(
            Math.min(startX, event.getX()),
            Math.min(startY, event.getY()),
            Math.max(startX, event.getX()),
            Math.max(startY, event.getY()));
      } else {
        addGesturePoint(event.getX(), event.getY());
      }
      invalidate();
    } else if (action == MotionEvent.ACTION_UP) {
      if (pinching) {
        commitViewport();
        pinching = false;
        return true;
      }
      if (drawing) {
        ImageEditorState.Tool currentTool = history.current().tool;
        if (currentTool == ImageEditorState.Tool.PAN) {
          commitViewport();
        } else if (currentTool == ImageEditorState.Tool.CROP) {
          commitCropSelection();
        } else if (currentTool == ImageEditorState.Tool.RECTANGLE) {
          gestureRect.set(
              Math.min(startX, event.getX()),
              Math.min(startY, event.getY()),
              Math.max(startX, event.getX()),
              Math.max(startY, event.getY()));
          commitRectangle();
        } else {
          addGesturePoint(event.getX(), event.getY());
          if (currentTool == ImageEditorState.Tool.MARK) commitInk();
          else commitFreehand();
        }
        drawing = false;
        performClick();
        invalidate();
        notifyChange();
      }
    }
    return true;
  }

  private void addGesturePoint(float x, float y) {
    if (gesturePoints.size() < ImageEditorState.MAX_POINTS_PER_STROKE)
      gesturePoints.add(new float[] {x, y});
    else if (!gesturePoints.isEmpty())
      gesturePoints.set(gesturePoints.size() - 1, new float[] {x, y});
  }

  private float[] toImage(float x, float y) {
    float[] p = {x, y};
    Matrix inv = new Matrix();
    imageMatrix();
    if (imageMatrix.invert(inv)) inv.mapPoints(p);
    p[0] = Math.max(0, Math.min(displayed.getWidth(), p[0]));
    p[1] = Math.max(0, Math.min(displayed.getHeight(), p[1]));
    return p;
  }

  private List<SelectedRegion.Point> gestureImagePoints() {
    List<SelectedRegion.Point> points = new ArrayList<>();
    for (float[] screen : gesturePoints) {
      float[] p = toImage(screen[0], screen[1]);
      points.add(
          new SelectedRegion.Point(p[0] / displayed.getWidth(), p[1] / displayed.getHeight()));
    }
    return points;
  }

  private void commitRectangle() {
    float[] a = toImage(gestureRect.left, gestureRect.top),
        b = toImage(gestureRect.right, gestureRect.bottom);
    if (Math.abs(b[0] - a[0]) < 4 || Math.abs(b[1] - a[1]) < 4) return;
    push(
        history
            .current()
            .withSelection(
                SelectedRegion.rectangle(
                    a[0] / displayed.getWidth(),
                    a[1] / displayed.getHeight(),
                    b[0] / displayed.getWidth(),
                    b[1] / displayed.getHeight())));
  }

  private void commitCropSelection() {
    float[] a = toImage(gestureRect.left, gestureRect.top),
        b = toImage(gestureRect.right, gestureRect.bottom);
    if (Math.abs(b[0] - a[0]) < 8 || Math.abs(b[1] - a[1]) < 8) return;
    cropRect =
        new RectF(
            Math.min(a[0], b[0]) / displayed.getWidth(),
            Math.min(a[1], b[1]) / displayed.getHeight(),
            Math.max(a[0], b[0]) / displayed.getWidth(),
            Math.max(a[1], b[1]) / displayed.getHeight());
  }

  private void commitFreehand() {
    if (gesturePoints.size() < 3) return;
    try {
      push(history.current().withSelection(SelectedRegion.freehand(gestureImagePoints())));
    } catch (IllegalArgumentException ignored) {
    }
  }

  private void commitInk() {
    List<List<SelectedRegion.Point>> strokes = new ArrayList<>(history.current().inkStrokes);
    strokes.add(gestureImagePoints());
    push(history.current().withInkStrokes(strokes));
  }

  private void commitViewport() {
    push(history.current().withViewport(zoom, panX, panY));
  }

  private void push(ImageEditorState state) {
    history.push(state);
    notifyChange();
  }

  private void drawInk(
      Canvas canvas,
      List<List<SelectedRegion.Point>> strokes,
      int width,
      int height,
      boolean onView) {
    canvas.save();
    if (onView) canvas.concat(imageMatrix);
    else canvas.clipRect(0, 0, width, height);
    paint.setColor(inkColor);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    paint.setStrokeWidth(
        Math.max(
            onView ? inkWidth * getResources().getDisplayMetrics().density : inkWidth,
            width * .008f));
    for (List<SelectedRegion.Point> stroke : strokes) {
      if (stroke.isEmpty()) continue;
      Path path = new Path();
      SelectedRegion.Point first = stroke.get(0);
      float x = (float) (first.x * width),
          y = (float) (first.y * height);
      path.moveTo(x, y);
      for (int i = 1; i < stroke.size(); i++) {
        SelectedRegion.Point p = stroke.get(i);
        path.lineTo(
            (float) (p.x * width),
            (float) (p.y * height));
      }
      if (stroke.size() == 1) canvas.drawCircle(x, y, paint.getStrokeWidth() / 2, paint);
      else canvas.drawPath(path, paint);
    }
    paint.setStyle(Paint.Style.FILL);
    canvas.restore();
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }

  public Bitmap renderEditedImage() {
    return renderImage(false);
  }

  // Guess keeps its existing selection-only image input.
  Bitmap renderSelection() {
    return renderImage(true);
  }

  private Bitmap renderImage(boolean cropToSelection) {
    int width = displayed.getWidth(), height = displayed.getHeight();
    RectF bounds = new RectF(0, 0, width, height);
    Matrix rotation = new Matrix();
    rotation.setRotate(fineAngle);
    rotation.mapRect(bounds);
    SelectedRegion selection = region();
    if (cropToSelection && selection != null) {
      RectF selectedBounds = new RectF(
          bounds.left + (float) Math.floor(selection.left * bounds.width()),
          bounds.top + (float) Math.floor(selection.top * bounds.height()),
          bounds.left + (float) Math.ceil(selection.right * bounds.width()),
          bounds.top + (float) Math.ceil(selection.bottom * bounds.height()));
      bounds.set(selectedBounds);
    }
    Bitmap output = Bitmap.createBitmap(
        Math.round(bounds.width()), Math.round(bounds.height()), Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(output);
    canvas.translate(-bounds.left, -bounds.top);
    canvas.concat(rotation);
    canvas.drawBitmap(displayed, 0f, 0f, paint);
    drawInk(canvas, history.current().inkStrokes, width, height, false);
    if (selection != null && !cropToSelection)
      drawSelection(canvas, selectionPath(selection), Math.max(2f, width * .005f));
    return output;
  }

  public void applyCrop() {
    if (cropRect == null || displayed == null) return;
    SelectedRegion crop = SelectedRegion.rectangle(
        cropRect.left, cropRect.top, cropRect.right, cropRect.bottom);
    cropRect = null;
    push(history.current().withAppliedCrop(crop));
    displayState(history.current());
  }

  public void resetCrop() {
    cropRect = null;
    invalidate();
    notifyChange();
  }

  public boolean hasCrop() {
    return cropRect != null;
  }

  public void setInkColor(int color) {
    this.inkColor = color;
    invalidate();
  }

  public int getInkColor() {
    return inkColor;
  }

  public void setInkWidth(float widthDp) {
    this.inkWidth = widthDp;
    invalidate();
  }

  public float getInkWidth() {
    return inkWidth;
  }

  public void setFineAngle(int degrees) {
    this.fineAngle = Math.max(-45, Math.min(45, degrees));
    invalidate();
    notifyChange();
  }

  public void commitFineAngle() {
    push(history.current().withViewport(zoom, panX, panY).withFineAngle(fineAngle));
  }

  public int getFineAngle() {
    return fineAngle;
  }

  public void fit() {
    zoom = 1f;
    panX = 0f;
    panY = 0f;
    commitViewport();
    invalidate();
    notifyChange();
  }

  public void clearSelection() {
    push(history.current().withSelection(null));
    invalidate();
    notifyChange();
  }

  public void clearInk() {
    push(history.current().withInkStrokes(Collections.emptyList()));
    invalidate();
    notifyChange();
  }

  private void displayState(ImageEditorState state) {
    if (displayed != null && displayed != source && !displayed.isRecycled()) displayed.recycle();
    Bitmap base = source;
    SelectedRegion crop = state.appliedCrop;
    if (crop != null) {
      int width = source.getWidth(), height = source.getHeight();
      int left = Math.min(width - 1, (int) (crop.left * width));
      int top = Math.min(height - 1, (int) (crop.top * height));
      int right = Math.max(left + 1, (int) (crop.right * width));
      int bottom = Math.max(top + 1, (int) (crop.bottom * height));
      base = Bitmap.createBitmap(source, left, top, right - left, bottom - top);
    }
    if (state.quarterTurns == 0) displayed = base;
    else {
      Matrix rotation = new Matrix();
      rotation.setRotate(state.quarterTurns * 90);
      displayed = Bitmap.createBitmap(base, 0, 0, base.getWidth(), base.getHeight(), rotation, true);
      if (base != source && base != displayed) base.recycle();
    }
    zoom = (float) state.zoom;
    panX = (float) state.panX;
    panY = (float) state.panY;
    fineAngle = state.fineAngle;
    invalidate();
    notifyChange();
  }

  public ImageEditorState state() {
    return history.current().withViewport(zoom, panX, panY).withFineAngle(fineAngle);
  }

  public SelectedRegion region() {
    return history.current().selection;
  }

  public void setTool(ImageEditorState.Tool tool) {
    push(history.current().withViewport(zoom, panX, panY).withTool(tool));
    invalidate();
    notifyChange();
  }

  public ImageEditorState.Tool getTool() {
    return history.current().tool;
  }

  public void rotateClockwise() {
    push(
        history
            .current()
            .withViewport(zoom, panX, panY)
            .withRotation(history.current().quarterTurns + 1));
    displayState(history.current());
  }

  public void rotateCounterClockwise() {
    push(
        history
            .current()
            .withViewport(zoom, panX, panY)
            .withRotation(history.current().quarterTurns + 3));
    displayState(history.current());
  }

  public void rotate() {
    rotateClockwise();
  }

  public void undo() {
    displayState(history.undo());
  }

  public void redo() {
    displayState(history.redo());
  }

  public boolean canUndo() {
    return history.canUndo();
  }

  public boolean canRedo() {
    return history.canRedo();
  }

  public void reset() {
    cropRect = null;
    fineAngle = 0;
    displayState(history.reset());
  }

  public int getImageWidth() {
    return displayed != null ? displayed.getWidth() : (source != null ? source.getWidth() : 0);
  }

  public int getImageHeight() {
    return displayed != null ? displayed.getHeight() : (source != null ? source.getHeight() : 0);
  }

  public int getZoomPercent() {
    return Math.round(zoom * 100);
  }

  public void release() {
    if (released) return;
    released = true;
    if (displayed != null && displayed != source && !displayed.isRecycled()) displayed.recycle();
    if (source != null && !source.isRecycled()) source.recycle();
    displayed = null;
  }
}
