package com.lexinote.nativeapp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Immutable, serializable history snapshot; all image coordinates are normalized. */
public final class ImageEditorState {
  public enum Tool {
    PAN,
    RECTANGLE,
    FREEHAND,
    MARK,
    CROP
  }

  public static final int MAX_STROKES = 128;
  public static final int MAX_POINTS_PER_STROKE = 2048;
  public static final int MAX_TOTAL_INK_POINTS = 8192;
  public final int quarterTurns;
  public final Tool tool;
  public final SelectedRegion selection;
  // The applied crop is expressed in the original image's normalized coordinates.
  final SelectedRegion appliedCrop;
  public final double zoom, panX, panY;
  public final int fineAngle;

  /** Independent open polylines, each normalized against the current oriented image. */
  public final List<List<SelectedRegion.Point>> inkStrokes;

  public ImageEditorState(
      int quarterTurns,
      Tool tool,
      SelectedRegion selection,
      double zoom,
      double panX,
      double panY) {
    this(quarterTurns, tool, selection, zoom, panX, panY, Collections.emptyList(), 0);
  }

  public ImageEditorState(
      int quarterTurns,
      Tool tool,
      SelectedRegion selection,
      double zoom,
      double panX,
      double panY,
      List<List<SelectedRegion.Point>> inkStrokes) {
    this(quarterTurns, tool, selection, zoom, panX, panY, inkStrokes, 0);
  }

  public ImageEditorState(
      int quarterTurns,
      Tool tool,
      SelectedRegion selection,
      double zoom,
      double panX,
      double panY,
      List<List<SelectedRegion.Point>> inkStrokes,
      int fineAngle) {
    this(quarterTurns, tool, selection, zoom, panX, panY, inkStrokes, fineAngle, null);
  }

  private ImageEditorState(
      int quarterTurns, Tool tool, SelectedRegion selection, double zoom, double panX,
      double panY, List<List<SelectedRegion.Point>> inkStrokes, int fineAngle,
      SelectedRegion appliedCrop) {
    this.quarterTurns = ((quarterTurns % 4) + 4) % 4;
    this.tool = Objects.requireNonNull(tool);
    this.selection = selection;
    this.appliedCrop = appliedCrop;
    this.zoom = Double.isFinite(zoom) ? Math.max(1, Math.min(6, zoom)) : 1;
    this.panX = Double.isFinite(panX) ? panX : 0;
    this.panY = Double.isFinite(panY) ? panY : 0;
    this.inkStrokes = immutableInk(inkStrokes);
    this.fineAngle = Math.max(-45, Math.min(45, fineAngle));
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
        rotated = rotateRectangle(rotated, delta);
      } else
        rotated =
            rotated.kind == SelectedRegion.Kind.MARK
                ? SelectedRegion.mark(rotatePoints(rotated.points, delta))
                : SelectedRegion.freehand(rotatePoints(rotated.points, delta));
    }
    return new ImageEditorState(turns, tool, rotated, 1, 0, 0, ink, fineAngle, appliedCrop);
  }

  private static SelectedRegion rotateRectangle(SelectedRegion region, int turns) {
    List<SelectedRegion.Point> corners = rotatePoints(Arrays.asList(
        new SelectedRegion.Point(region.left, region.top),
        new SelectedRegion.Point(region.right, region.bottom)), turns);
    SelectedRegion.Point a = corners.get(0), b = corners.get(1);
    return SelectedRegion.rectangle(a.x, a.y, b.x, b.y);
  }

  ImageEditorState withAppliedCrop(SelectedRegion visibleCrop) {
    SelectedRegion local = rotateRectangle(visibleCrop, (4 - quarterTurns) % 4);
    if (appliedCrop != null) {
      local = SelectedRegion.rectangle(
          appliedCrop.left + local.left * appliedCrop.width(),
          appliedCrop.top + local.top * appliedCrop.height(),
          appliedCrop.left + local.right * appliedCrop.width(),
          appliedCrop.top + local.bottom * appliedCrop.height());
    }
    return new ImageEditorState(quarterTurns, tool, null, 1, 0, 0,
        Collections.emptyList(), 0, local);
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
    return new ImageEditorState(quarterTurns, value, selection, zoom, panX, panY, inkStrokes, fineAngle, appliedCrop);
  }

  public ImageEditorState withSelection(SelectedRegion value) {
    return new ImageEditorState(quarterTurns, tool, value, zoom, panX, panY, inkStrokes, fineAngle, appliedCrop);
  }

  public ImageEditorState withViewport(double zoom, double panX, double panY) {
    return new ImageEditorState(quarterTurns, tool, selection, zoom, panX, panY, inkStrokes, fineAngle, appliedCrop);
  }

  public ImageEditorState withInkStrokes(List<List<SelectedRegion.Point>> value) {
    return new ImageEditorState(quarterTurns, tool, selection, zoom, panX, panY, value, fineAngle, appliedCrop);
  }

  public ImageEditorState withFineAngle(int value) {
    return new ImageEditorState(quarterTurns, tool, selection, zoom, panX, panY, inkStrokes, value, appliedCrop);
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
      out.put("fineAngle", fineAngle);
      out.put("selection", selection == null ? JSONObject.NULL : selection.toJson());
      if (appliedCrop != null) out.put("appliedCrop", appliedCrop.toJson());
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
          strokes,
          json.optInt("fineAngle", 0),
          parseRegion(json.optJSONObject("appliedCrop")));
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
        && Objects.equals(appliedCrop, s.appliedCrop)
        && Double.compare(zoom, s.zoom) == 0
        && Double.compare(panX, s.panX) == 0
        && Double.compare(panY, s.panY) == 0
        && fineAngle == s.fineAngle
        && inkStrokes.equals(s.inkStrokes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(quarterTurns, tool, selection, zoom, panX, panY, fineAngle, inkStrokes, appliedCrop);
  }
}
