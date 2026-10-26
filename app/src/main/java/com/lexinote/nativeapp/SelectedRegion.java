package com.lexinote.nativeapp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** A selection expressed in normalized coordinates of the oriented original image. */
public final class SelectedRegion {
  public enum Kind {
    RECTANGLE,
    FREEHAND,
    MARK
  }

  public static final class Point {
    public final double x, y;

    public Point(double x, double y) {
      this.x = clamp(x);
      this.y = clamp(y);
    }

    @Override
    public boolean equals(Object value) {
      if (!(value instanceof Point)) return false;
      Point p = (Point) value;
      return Double.compare(x, p.x) == 0 && Double.compare(y, p.y) == 0;
    }

    @Override
    public int hashCode() {
      return Objects.hash(x, y);
    }
  }

  public final Kind kind;
  public final double left, top, right, bottom;
  public final List<Point> points;

  private SelectedRegion(
      Kind kind, double left, double top, double right, double bottom, List<Point> points) {
    if (!(right > left) || !(bottom > top))
      throw new IllegalArgumentException("Selection must have positive area");
    this.kind = kind;
    this.left = left;
    this.top = top;
    this.right = right;
    this.bottom = bottom;
    this.points = Collections.unmodifiableList(new ArrayList<>(points));
  }

  public static SelectedRegion rectangle(double left, double top, double right, double bottom) {
    double l = clamp(Math.min(left, right)), r = clamp(Math.max(left, right));
    double t = clamp(Math.min(top, bottom)), b = clamp(Math.max(top, bottom));
    return new SelectedRegion(Kind.RECTANGLE, l, t, r, b, Collections.emptyList());
  }

  public static SelectedRegion freehand(List<Point> points) {
    return polygon(Kind.FREEHAND, points);
  }

  public static SelectedRegion mark(List<Point> points) {
    return polygon(Kind.MARK, points);
  }

  private static SelectedRegion polygon(Kind kind, List<Point> points) {
    if (points == null || points.size() < 3)
      throw new IllegalArgumentException("At least three points are required");
    double l = 1, t = 1, r = 0, b = 0;
    for (Point point : points) {
      l = Math.min(l, point.x);
      t = Math.min(t, point.y);
      r = Math.max(r, point.x);
      b = Math.max(b, point.y);
    }
    if (r - l < 1e-6 || b - t < 1e-6)
      throw new IllegalArgumentException("Selection must have positive area");
    return new SelectedRegion(kind, l, t, r, b, points);
  }

  public double width() {
    return right - left;
  }

  public double height() {
    return bottom - top;
  }

  public JSONObject toJson() {
    JSONObject out = new JSONObject();
    try {
      out.put("x", left * 100);
      out.put("y", top * 100);
      out.put("width", width() * 100);
      out.put("height", height() * 100);
      out.put("kind", kind.name().toLowerCase(java.util.Locale.ROOT));
      if (!points.isEmpty()) {
        JSONArray array = new JSONArray();
        for (Point point : points)
          array.put(new JSONObject().put("x", point.x * 100).put("y", point.y * 100));
        out.put("points", array);
      }
    } catch (JSONException impossible) {
      throw new IllegalStateException(impossible);
    }
    return out;
  }

  private static double clamp(double v) {
    if (!Double.isFinite(v)) throw new IllegalArgumentException("Coordinates must be finite");
    return Math.max(0, Math.min(1, v));
  }

  @Override
  public boolean equals(Object value) {
    if (!(value instanceof SelectedRegion)) return false;
    SelectedRegion r = (SelectedRegion) value;
    return kind == r.kind
        && Double.compare(left, r.left) == 0
        && Double.compare(top, r.top) == 0
        && Double.compare(right, r.right) == 0
        && Double.compare(bottom, r.bottom) == 0
        && points.equals(r.points);
  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, left, top, right, bottom, points);
  }
}
