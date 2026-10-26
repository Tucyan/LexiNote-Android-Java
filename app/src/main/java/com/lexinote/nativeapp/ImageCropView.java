package com.lexinote.nativeapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** Image canvas with reversible selection, open ink strokes, rotation and pinch pan/zoom. */
public final class ImageCropView extends View {
  private final Bitmap source;
  private Bitmap displayed;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final Matrix imageMatrix = new Matrix();
  private final ImageEditHistory history;
  private final ScaleGestureDetector detector;
  private final List<float[]> gesturePoints = new ArrayList<>();
  private RectF gestureRect;
  private float zoom = 1, panX, panY, startX, startY, lastX, lastY;
  private boolean pinching, drawing, released;

  public ImageCropView(Context context, Bitmap source) {
    this(context, source, null);
  }

  public ImageCropView(Context context, Bitmap source, ImageEditorState saved) {
    super(context);
    this.source = source;
    history = new ImageEditHistory(saved == null ? ImageEditorState.initial() : saved);
    displayState(history.current());
    setContentDescription("图片编辑器，拖动框选，双指缩放移动");
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
                return true;
              }
            });
  }

  private void imageMatrix() {
    if (displayed == null || getWidth() == 0 || getHeight() == 0) return;
    float fit =
        Math.min(
            getWidth() / (float) displayed.getWidth(), getHeight() / (float) displayed.getHeight());
    float scale = fit * zoom;
    imageMatrix.setScale(scale, scale);
    imageMatrix.postTranslate(
        (getWidth() - displayed.getWidth() * scale) / 2 + panX,
        (getHeight() - displayed.getHeight() * scale) / 2 + panY);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (displayed == null) return;
    canvas.drawColor(Color.rgb(20, 30, 36));
    imageMatrix();
    canvas.drawBitmap(displayed, imageMatrix, paint);
    drawInk(
        canvas,
        history.current().inkStrokes,
        0,
        0,
        displayed.getWidth(),
        displayed.getHeight(),
        true);
    SelectedRegion r = history.current().selection;
    if (r != null) {
      paint.setColor(0x380DCCB4);
      paint.setStyle(Paint.Style.FILL);
      if (r.points.isEmpty()) canvas.drawRect(mapRect(r), paint);
      else canvas.drawPath(mapRegion(r), paint);
      paint.setColor(0xFF52E3CE);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(3 * getResources().getDisplayMetrics().density);
      canvas.drawPath(mapRegion(r), paint);
      paint.setStyle(Paint.Style.FILL);
    }
    if (drawing) drawGesture(canvas);
  }

  private RectF mapRect(SelectedRegion r) {
    RectF v =
        new RectF(
            (float) r.left * displayed.getWidth(),
            (float) r.top * displayed.getHeight(),
            (float) r.right * displayed.getWidth(),
            (float) r.bottom * displayed.getHeight());
    imageMatrix.mapRect(v);
    return v;
  }

  private Path mapRegion(SelectedRegion r) {
    Path path = new Path();
    if (r.points.isEmpty()) {
      path.addRect(mapRect(r), Path.Direction.CW);
      return path;
    }
    float[] xy = new float[2];
    for (int i = 0; i < r.points.size(); i++) {
      SelectedRegion.Point p = r.points.get(i);
      xy[0] = (float) p.x * displayed.getWidth();
      xy[1] = (float) p.y * displayed.getHeight();
      imageMatrix.mapPoints(xy);
      if (i == 0) path.moveTo(xy[0], xy[1]);
      else path.lineTo(xy[0], xy[1]);
    }
    path.close();
    return path;
  }

  private void drawGesture(Canvas canvas) {
    paint.setColor(0xE652E3CE);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    paint.setStrokeWidth(
        Math.max(
            3 * getResources().getDisplayMetrics().density,
            displayed.getWidth() * .012f * displayScale()));
    if (history.current().tool == ImageEditorState.Tool.RECTANGLE && gestureRect != null)
      canvas.drawRect(gestureRect, paint);
    else if (!gesturePoints.isEmpty()) {
      Path p = new Path();
      p.moveTo(gesturePoints.get(0)[0], gesturePoints.get(0)[1]);
      for (int i = 1; i < gesturePoints.size(); i++)
        p.lineTo(gesturePoints.get(i)[0], gesturePoints.get(i)[1]);
      if (gesturePoints.size() == 1)
        canvas.drawCircle(
            gesturePoints.get(0)[0], gesturePoints.get(0)[1], paint.getStrokeWidth() / 2, paint);
      else canvas.drawPath(p, paint);
    }
    paint.setStyle(Paint.Style.FILL);
  }

  private float displayScale() {
    float[] v = new float[9];
    imageMatrix.getValues(v);
    return v[Matrix.MSCALE_X];
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
      if (pinching) {
        return true;
      }
      drawing = true;
      startX = event.getX();
      startY = event.getY();
      gestureRect = new RectF(startX, startY, startX, startY);
      gesturePoints.clear();
      addGesturePoint(startX, startY);
    } else if (action == MotionEvent.ACTION_MOVE && pinching) {
      commitViewport();
      pinching = false;
    } else if (action == MotionEvent.ACTION_MOVE && drawing) {
      if (history.current().tool == ImageEditorState.Tool.RECTANGLE)
        gestureRect.set(
            Math.min(startX, event.getX()),
            Math.min(startY, event.getY()),
            Math.max(startX, event.getX()),
            Math.max(startY, event.getY()));
      else addGesturePoint(event.getX(), event.getY());
      invalidate();
    } else if (action == MotionEvent.ACTION_UP) {
      if (pinching) {
        commitViewport();
        pinching = false;
        return true;
      }
      if (drawing) {
        if (history.current().tool == ImageEditorState.Tool.RECTANGLE) {
          gestureRect.set(
              Math.min(startX, event.getX()),
              Math.min(startY, event.getY()),
              Math.max(startX, event.getX()),
              Math.max(startY, event.getY()));
          commitRectangle();
        } else {
          addGesturePoint(event.getX(), event.getY());
          if (history.current().tool == ImageEditorState.Tool.MARK) commitInk();
          else commitFreehand();
        }
        drawing = false;
        performClick();
        invalidate();
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
  }

  private void drawInk(
      Canvas canvas,
      List<List<SelectedRegion.Point>> strokes,
      float originX,
      float originY,
      int width,
      int height,
      boolean onView) {
    canvas.save();
    if (onView) canvas.concat(imageMatrix);
    else canvas.clipRect(0, 0, width, height);
    paint.setColor(0xFFFF4D8D);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    paint.setStrokeWidth(
        Math.max(onView ? 2 * getResources().getDisplayMetrics().density : 2, width * .012f));
    for (List<SelectedRegion.Point> stroke : strokes) {
      if (stroke.isEmpty()) continue;
      Path path = new Path();
      SelectedRegion.Point first = stroke.get(0);
      float x = (float) (first.x * displayed.getWidth() - originX),
          y = (float) (first.y * displayed.getHeight() - originY);
      path.moveTo(x, y);
      for (int i = 1; i < stroke.size(); i++) {
        SelectedRegion.Point p = stroke.get(i);
        path.lineTo(
            (float) (p.x * displayed.getWidth() - originX),
            (float) (p.y * displayed.getHeight() - originY));
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

  public Bitmap crop() {
    SelectedRegion r = history.current().selection;
    int l = 0, t = 0, rr = displayed.getWidth(), bb = displayed.getHeight();
    if (r != null) {
      l = Math.max(0, (int) Math.floor(r.left * displayed.getWidth()));
      t = Math.max(0, (int) Math.floor(r.top * displayed.getHeight()));
      rr = Math.min(displayed.getWidth(), (int) Math.ceil(r.right * displayed.getWidth()));
      bb = Math.min(displayed.getHeight(), (int) Math.ceil(r.bottom * displayed.getHeight()));
    }
    int width = Math.max(1, rr - l), height = Math.max(1, bb - t);
    Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(output);
    canvas.drawBitmap(displayed, new Rect(l, t, rr, bb), new Rect(0, 0, width, height), paint);
    drawInk(canvas, history.current().inkStrokes, l, t, width, height, false);
    return output;
  }

  private void displayState(ImageEditorState state) {
    if (displayed != null && displayed != source && !displayed.isRecycled()) displayed.recycle();
    if (state.quarterTurns == 0) displayed = source;
    else {
      Matrix m = new Matrix();
      m.postRotate(state.quarterTurns * 90);
      displayed = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), m, true);
    }
    zoom = (float) state.zoom;
    panX = (float) state.panX;
    panY = (float) state.panY;
    invalidate();
  }

  public ImageEditorState state() {
    return history.current().withViewport(zoom, panX, panY);
  }

  public SelectedRegion region() {
    return history.current().selection;
  }

  public void setTool(ImageEditorState.Tool tool) {
    push(history.current().withViewport(zoom, panX, panY).withTool(tool));
    invalidate();
  }

  public void rotate() {
    push(
        history
            .current()
            .withViewport(zoom, panX, panY)
            .withRotation(history.current().quarterTurns + 1));
    displayState(history.current());
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
    displayState(history.reset());
  }

  /**
   * Releases the editor-owned decoded source and any rotated display bitmap. Safe to call
   * repeatedly.
   */
  public void release() {
    if (released) return;
    released = true;
    if (displayed != null && displayed != source && !displayed.isRecycled()) displayed.recycle();
    if (source != null && !source.isRecycled()) source.recycle();
    displayed = null;
  }
}
