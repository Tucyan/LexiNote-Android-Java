package com.lexinote.nativeapp;

/** Pure freshness/ownership guard for asynchronous session title suggestions. */
final class TitleSuggestionGuard {
  private TitleSuggestionGuard() {}

  static boolean mayApply(
      boolean exists,
      boolean manuallyNamed,
      String currentTitle,
      int capturedRevision,
      int currentRevision,
      long capturedEpoch,
      long currentEpoch) {
    return exists
        && !manuallyNamed
        && capturedRevision == currentRevision
        && capturedEpoch == currentEpoch
        && (currentTitle == null || currentTitle.trim().isEmpty());
  }

  static String clean(String suggestion) {
    if (suggestion == null) return "";
    String value = suggestion.trim().replaceAll("^[\\\"'“”‘’]+|[\\\"'“”‘’]+$", "");
    value = value.replaceAll("\\s+", " ");
    return value.length() > 48 ? value.substring(0, 48).trim() : value;
  }
}
