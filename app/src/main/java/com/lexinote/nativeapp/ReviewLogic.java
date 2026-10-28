package com.lexinote.nativeapp;

final class ReviewLogic {
  private ReviewLogic() {}
  static int level(Integer saved, int count, String grade) {
    if (saved != null) return Math.max(0, Math.min(3, saved));
    if (grade == null || grade.isEmpty()) return count >= 3 ? 3 : 0;
    if (grade.equals("简单")) return count >= 3 ? 3 : 0;
    return 0;
  }
  static boolean mastered(Integer saved, int count, String grade) {
    return level(saved, count, grade) >= 3;
  }
  static int nextLevel(Integer saved, int count, String previousGrade, String grade) {
    int current = level(saved, count, previousGrade);
    if ("再次".equals(grade)) return 0;
    if ("简单".equals(grade)) return Math.min(3, current + 1);
    return current;
  }
  static final class Progress {
    final int total;
    int completed, skipped;
    Progress(int total) { this.total = total; }
    void rate(String grade) {
      if ("简单".equals(grade) || "困难".equals(grade)) completed++;
    }
    void skip() { skipped++; }
  }
}
