package com.lexinote.nativeapp;

import java.util.ArrayList;
import java.util.List;

/** Bounded immutable-state history for reversible editor actions. */
public final class ImageEditHistory {
  private static final int LIMIT = 80;
  private final List<ImageEditorState> states = new ArrayList<>();
  private int cursor;

  public ImageEditHistory(ImageEditorState initial) {
    states.add(initial == null ? ImageEditorState.initial() : initial);
  }

  public ImageEditorState current() {
    return states.get(cursor);
  }

  public ImageEditorState push(ImageEditorState state) {
    while (states.size() > cursor + 1) states.remove(states.size() - 1);
    if (!current().equals(state)) states.add(state);
    if (states.size() > LIMIT) states.remove(0);
    cursor = states.size() - 1;
    return current();
  }

  public ImageEditorState undo() {
    if (cursor > 0) cursor--;
    return current();
  }

  public ImageEditorState redo() {
    if (cursor + 1 < states.size()) cursor++;
    return current();
  }

  public boolean canUndo() {
    return cursor > 0;
  }

  public boolean canRedo() {
    return cursor + 1 < states.size();
  }

  public ImageEditorState reset() {
    states.clear();
    states.add(ImageEditorState.initial());
    cursor = 0;
    return current();
  }
}
