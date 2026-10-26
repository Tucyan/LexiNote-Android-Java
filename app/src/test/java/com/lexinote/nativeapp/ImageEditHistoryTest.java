package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ImageEditHistoryTest {
  @Test
  public void undoRedoRestoreEveryEditAndBranchDropsRedo() {
    ImageEditorState initial = ImageEditorState.initial();
    ImageEditHistory history = new ImageEditHistory(initial);
    ImageEditorState rotated = initial.withRotation(1);
    ImageEditorState selected = rotated.withSelection(SelectedRegion.rectangle(.1, .2, .8, .9));
    history.push(rotated);
    history.push(selected);
    assertEquals(selected, history.current());
    assertEquals(rotated, history.undo());
    ImageEditorState marked =
        rotated.withSelection(
            SelectedRegion.freehand(
                Arrays.asList(
                    new SelectedRegion.Point(.2, .3),
                    new SelectedRegion.Point(.6, .4),
                    new SelectedRegion.Point(.3, .8))));
    history.push(marked);
    assertEquals(marked, history.redo());
    assertFalse(history.canRedo());
    assertEquals(marked, history.current());
  }

  @Test
  public void regionClampsAndComputesBounds() {
    SelectedRegion region = SelectedRegion.rectangle(-.2, .1, 1.4, .7);
    assertEquals(0, region.left, 0);
    assertEquals(1, region.right, 0);
    assertEquals(.6, region.height(), .00001);
  }

  @Test
  public void invalidRegionRejectedAndResetStateIsInitial() {
    try {
      SelectedRegion.rectangle(.5, .5, .5, .7);
      fail("zero-width rectangle must be rejected");
    } catch (IllegalArgumentException expected) {
    }
    ImageEditHistory history = new ImageEditHistory(ImageEditorState.initial().withRotation(2));
    history.reset();
    assertEquals(ImageEditorState.initial(), history.current());
  }

  @Test
  public void stateJsonRoundTripsSelectionAndUsesExpoPercentSchema() throws Exception {
    SelectedRegion region =
        SelectedRegion.freehand(
            Arrays.asList(
                new SelectedRegion.Point(.2, .3),
                new SelectedRegion.Point(.8, .4),
                new SelectedRegion.Point(.4, .9)));
    ImageEditorState state =
        new ImageEditorState(3, ImageEditorState.Tool.FREEHAND, region, 2.25, 11, -7);
    ImageEditorState restored = ImageEditorState.fromJson(state.toJson());
    assertEquals(state, restored);
    assertEquals(20, restored.selection.toJson().getDouble("x"), .00001);
    assertEquals(60, restored.selection.toJson().getDouble("width"), .00001);
  }

  @Test
  public void rotationTransformsRectangleAndPolygonCoordinates() {
    SelectedRegion rectangle = SelectedRegion.rectangle(.1, .2, .5, .5);
    ImageEditorState rectState =
        ImageEditorState.initial().withSelection(rectangle).withRotation(1);
    assertEquals(.5, rectState.selection.left, .00001);
    assertEquals(.1, rectState.selection.top, .00001);
    assertEquals(.8, rectState.selection.right, .00001);
    assertEquals(.5, rectState.selection.bottom, .00001);
    SelectedRegion polygon =
        SelectedRegion.freehand(
            Arrays.asList(
                new SelectedRegion.Point(.1, .2),
                new SelectedRegion.Point(.5, .2),
                new SelectedRegion.Point(.3, .8)));
    ImageEditorState polygonState =
        ImageEditorState.initial().withSelection(polygon).withRotation(1);
    assertEquals(.8, polygonState.selection.points.get(0).x, .00001);
    assertEquals(.1, polygonState.selection.points.get(0).y, .00001);
    assertEquals(.2, polygonState.selection.points.get(2).x, .00001);
    assertEquals(.3, polygonState.selection.points.get(2).y, .00001);
  }

  @Test
  public void openInkStrokesAllowSinglePointAndUndoAsIndependentLayer() {
    List<SelectedRegion.Point> stroke = Arrays.asList(new SelectedRegion.Point(.2, .7));
    ImageEditorState marked =
        ImageEditorState.initial()
            .withTool(ImageEditorState.Tool.MARK)
            .withInkStrokes(Arrays.asList(stroke));
    ImageEditHistory history = new ImageEditHistory(ImageEditorState.initial());
    history.push(marked);
    assertEquals(1, history.current().inkStrokes.size());
    assertNull(history.current().selection);
    assertEquals(ImageEditorState.initial(), history.undo());
    assertEquals(marked, ImageEditorState.fromJson(marked.toJson()));
  }

  @Test
  public void viewportSnapshotIsRestoredByUndoAndRedo() {
    ImageEditHistory history = new ImageEditHistory(ImageEditorState.initial());
    ImageEditorState zoomed = history.current().withViewport(3, 16, -9);
    history.push(zoomed);
    history.push(zoomed.withSelection(SelectedRegion.rectangle(.2, .2, .6, .7)));
    assertEquals(zoomed, history.undo());
    assertEquals(zoomed.withSelection(SelectedRegion.rectangle(.2, .2, .6, .7)), history.redo());
  }
}
