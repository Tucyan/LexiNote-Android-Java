package com.lexinote.nativeapp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Immutable, serializable history snapshot; all image coordinates are normalized. */
public final class ImageEditorState {
  public enum Tool {
    RECTANGLE,
    FREEHAND,
    MARK
  }

  public static final int MAX_STROKES = 128;
  public static final int MAX_POINTS_PER_STROKE = 2048;
  public static final int MAX_TOTAL_INK_POINTS = 8192;
  public final int quarterTurns;
  public final Tool tool;
  public final SelectedRegion selection;
  public final double zoom, panX, panY;

  /** Independent open polylines, each normalized against the current oriented image. */
  public final List<List<SelectedRegion.Point>> inkStrokes;

  public ImageEditorState(
      int quarterTurns,
      Tool tool,
      SelectedRegion selection,
      double zoom,
      double panX,
      double panY) {
    this(quarterTurns, tool, selection, zoom, panX, panY, Collections.emptyList());
  }

  public ImageEditorState(
      int quarterTurns,
      Tool tool,
      SelectedRegion selection,
      double zoom,
      double panX,
      double panY,
      List<List<SelectedRegion.Point>> inkStrokes) {
    this.quarterTurns = ((quarterTurns % 4) + 4) % 4;
    this.tool = Objects.requireNonNull(tool);
    this.selection = selection;
    this.zoom = Double.isFinite(zoom) ? Math.max(1, Math.min(6, zoom)) : 1;
    this.panX = Double.isFinite(panX) ? panX : 0;
    this.panY = Double.isFinite(panY) ? panY : 0;
    this.inkStrokes = immutableInk(inkStrokes);
  }

  private static List<List<SelectedRegion.Point>> immutableInk(
      List<List<SelectedRegion.Point>> strokes) {
    ArrayList<List<SelectedRegion.Point>> copy = new ArrayList<>();
    int total = 0;
    if (strokes != null)
      for (List<SelectedRegion.Point> stroke : strokes) {
        if (copy.size() >= MAX_STROKES || stroke == null || stroke.isEmpty()) break;
        int take =
            Math.min(stroke.size(), Math.min(MAX_POINTS_PER_STROKE, MAX_TOTAL_INK_POINTS - total));
        if (take <= 0) break;
        copy.add(Collections.unmodifiableList(new ArrayList<>(stroke.subList(0, take))));
        total += take;
      }
    return Collections.unmodifiableList(copy);
  }

  public static ImageEditorState initial() {
    return new ImageEditorState(0, Tool.RECTANGLE, null, 1, 0, 0);
  }

  public ImageEditorState withRotation(int value) {
    int turns = ((value % 4) + 4) % 4, delta = (turns - quarterTurns + 4) % 4;
    SelectedRegion rotated = selection;
    ArrayList<List<SelectedRegion.Point>> ink = new ArrayList<>();
    for (List<SelectedRegion.Point> stroke : inkStrokes) ink.add(rotatePoints(stroke, delta));
    if (rotated != null) {
      if (rotated.points.isEmpty()) {
        List<SelectedRegion.Point> corners = new ArrayList<>();
        corners.add(new SelectedRegion.Point(rotated.left, rotated.top));
        corners.add(new SelectedRegion.Point(rotated.right, rotated.top));
        corners.add(new SelectedRegion.Point(rotated.right, rotated.bottom));
        corners.add(new SelectedRegion.Point(rotated.left, rotated.bottom));
        List<SelectedRegion.Point> moved = rotatePoints(corners, delta);
        double l = 1, t = 1, r = 0, b = 0;
        for (SelectedRegion.Point p : moved) {
          l = Math.min(l, p.x);
          t = Math.min(t, p.y);
          r = Math.max(r, p.x);
          b = Math.max(b, p.y);
        }
        rotated = SelectedRegion.rectangle(l, t, r, b);
      } else
        rotated =
            rotated.kind == SelectedRegion.Kind.MARK
                ? SelectedRegion.mark(rotatePoints(rotated.points, delta))
                : SelectedRegion.freehand(rotatePoints(rotated.points, delta));
    }
    return new ImageEditorState(turns, tool, rotated, 1, 0, 0, ink);
  }

  private static List<SelectedRegion.Point> rotatePoints(
      List<SelectedRegion.Point> points, int turns) {
    ArrayList<SelectedRegion.Point> result = new ArrayList<>(points.size());
    for (SelectedRegion.Point point : points) {
      double x = point.x, y = point.y;
      for (int i = 0; i < turns; i++) {
        double nextX = 1 - y;
        y = x;
        x = nextX;
      }
      result.add(new SelectedRegion.Point(x, y));
    }
    return result;
  }

  public ImageEditorState withTool(Tool value) {
    return new ImageEditorState(quarterTurns, value, selection, zoom, panX, panY, inkStrokes);
  }

  public ImageEditorState withSelection(SelectedRegion value) {
    return new ImageEditorState(quarterTurns, tool, value, zoom, panX, panY, inkStrokes);
  }

  public ImageEditorState withViewport(double zoom, double panX, double panY) {
    return new ImageEditorState(quarterTurns, tool, selection, zoom, panX, panY, inkStrokes);
  }

  public ImageEditorState withInkStrokes(List<List<SelectedRegion.Point>> value) {
    return new ImageEditorState(quarterTurns, tool, selection, zoom, panX, panY, value);
  }

  public JSONObject toJson() {
    JSONObject out = new JSONObject();
    try {
      out.put("version", 2);
      out.put("quarterTurns", quarterTurns);
      out.put("tool", tool.name());
      out.put("zoom", zoom);
      out.put("panX", panX);
      out.put("panY", panY);
      out.put("selection", selection == null ? JSONObject.NULL : selection.toJson());
      JSONArray strokes = new JSONArray();
      for (List<SelectedRegion.Point> stroke : inkStrokes) {
        JSONArray points = new JSONArray();
        for (SelectedRegion.Point p : stroke)
          points.put(new JSONObject().put("x", p.x * 100).put("y", p.y * 100));
        strokes.put(points);
      }
      out.put("inkStrokes", strokes);
    } catch (JSONException impossible) {
      throw new IllegalStateException(impossible);
    }
    return out;
  }

  public static ImageEditorState fromJson(JSONObject json) {
    if (json == null) return initial();
    try {
      Tool tool;
      try {
        tool = Tool.valueOf(json.optString("tool", "RECTANGLE"));
      } catch (IllegalArgumentException bad) {
        tool = Tool.RECTANGLE;
      }
      ArrayList<List<SelectedRegion.Point>> strokes = new ArrayList<>();
      JSONArray raw = json.optJSONArray("inkStrokes");
      int total = 0;
      if (raw != null)
        for (int i = 0; i < raw.length() && i < MAX_STROKES; i++) {
          JSONArray stroke = raw.optJSONArray(i);
          if (stroke == null || stroke.length() == 0) continue;
          ArrayList<SelectedRegion.Point> points = new ArrayList<>();
          for (int j = 0;
              j < stroke.length() && j < MAX_POINTS_PER_STROKE && total < MAX_TOTAL_INK_POINTS;
              j++) {
            JSONObject p = stroke.optJSONObject(j);
            if (p == null) continue;
            points.add(
                new SelectedRegion.Point(
                    p.optDouble("x", Double.NaN) / 100, p.optDouble("y", Double.NaN) / 100));
            total++;
          }
          if (!points.isEmpty()) strokes.add(points);
        }
      return new ImageEditorState(
          json.optInt("quarterTurns", 0),
          tool,
          parseRegion(json.optJSONObject("selection")),
          json.optDouble("zoom", 1),
          json.optDouble("panX", 0),
          json.optDouble("panY", 0),
          strokes);
    } catch (RuntimeException invalid) {
      return initial();
    }
  }

  private static SelectedRegion parseRegion(JSONObject json) {
    if (json == null) return null;
    double x = json.optDouble("x", Double.NaN) / 100, y = json.optDouble("y", Double.NaN) / 100;
    double w = json.optDouble("width", Double.NaN) / 100,
        h = json.optDouble("height", Double.NaN) / 100;
    try {
      String kind = json.optString("kind", "rectangle");
      if (kind.equals("freehand") || kind.equals("mark")) {
        JSONArray array = json.optJSONArray("points");
        if (array == null || array.length() < 3 || array.length() > MAX_POINTS_PER_STROKE)
          return null;
        ArrayList<SelectedRegion.Point> points = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
          JSONObject p = array.optJSONObject(i);
          if (p == null) return null;
          points.add(
              new SelectedRegion.Point(
                  p.optDouble("x", Double.NaN) / 100, p.optDouble("y", Double.NaN) / 100));
        }
        return kind.equals("mark") ? SelectedRegion.mark(points) : SelectedRegion.freehand(points);
      }
      return SelectedRegion.rectangle(x, y, x + w, y + h);
    } catch (RuntimeException invalid) {
      return null;
    }
  }

  @Override
  public boolean equals(Object value) {
    if (!(value instanceof ImageEditorState)) return false;
    ImageEditorState s = (ImageEditorState) value;
    return quarterTurns == s.quarterTurns
        && tool == s.tool
        && Objects.equals(selection, s.selection)
        && Double.compare(zoom, s.zoom) == 0
        && Double.compare(panX, s.panX) == 0
        && Double.compare(panY, s.panY) == 0
        && inkStrokes.equals(s.inkStrokes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(quarterTurns, tool, selection, zoom, panX, panY, inkStrokes);
  }
}
