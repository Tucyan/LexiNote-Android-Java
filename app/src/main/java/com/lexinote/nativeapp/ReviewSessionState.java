package com.lexinote.nativeapp;

/** State for one in-flight rating and its visible-time clock. */
final class ReviewSessionState {
  static final class Rating {
    final String planId, vocabId, grade, eventId;
    final long occurredAt, durationMillis;
    Rating(String planId, String vocabId, String grade, String eventId, long occurredAt, long durationMillis) {
      this.planId = planId; this.vocabId = vocabId; this.grade = grade; this.eventId = eventId;
      this.occurredAt = occurredAt; this.durationMillis = durationMillis;
    }
  }
  private Rating pending;
  private boolean submitting;
  private long elapsed, resumedAt = -1;
  void resume(long now) { if (resumedAt < 0) resumedAt = now; }
  void pause(long now) { if (resumedAt >= 0) { elapsed += Math.max(0, now - resumedAt); resumedAt = -1; } }
  void resetClock(long now) { elapsed = 0; if (resumedAt >= 0) resumedAt = now; }
  boolean isSubmitting() { return submitting; }
  Rating pending() { return pending; }
  Rating begin(String plan, String vocab, String grade, String event, long occurredAt, long now) {
    if (submitting) return null;
    if (pending != null && (!pending.planId.equals(plan) || !pending.vocabId.equals(vocab) || !pending.grade.equals(grade))) return null;
    if (pending == null) pending = new Rating(plan, vocab, grade, event, occurredAt,
        Math.min(86400000L, elapsed + (resumedAt < 0 ? 0 : Math.max(0, now - resumedAt))));
    submitting = true;
    return pending;
  }
  void fail() { submitting = false; }
  void succeed(long now) { pending = null; submitting = false; resetClock(now); }
  void restorePending(Rating rating) { pending = rating; submitting = true; }
  boolean startRecoveryCheck(String plan, String vocab, String grade) {
    if (submitting || pending == null || !pending.planId.equals(plan)
        || !pending.vocabId.equals(vocab) || !pending.grade.equals(grade)) return false;
    submitting = true;
    return true;
  }
}
