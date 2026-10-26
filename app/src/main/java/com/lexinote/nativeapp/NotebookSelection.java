package com.lexinote.nativeapp;

import java.util.*;

/** Shared scope checks for filtered notebook selections. */
public final class NotebookSelection {
  private NotebookSelection() {}

  public static <T> boolean isWithinScope(Collection<T> selected, Collection<T> visible) {
    return new HashSet<>(visible).containsAll(selected);
  }

  public static <T> void pruneToScope(Collection<T> selected, Collection<T> visible) {
    selected.retainAll(new HashSet<>(visible));
  }
}
