package com.lexinote.nativeapp;

/** Small platform-independent predicate for contextual selection menu routing. */
public final class AnnotationActionIds {
  private AnnotationActionIds() {}

  public static boolean isAnnotationAction(int clickedItemId, int generatedActionId) {
    return generatedActionId != 0 && clickedItemId == generatedActionId;
  }
}
