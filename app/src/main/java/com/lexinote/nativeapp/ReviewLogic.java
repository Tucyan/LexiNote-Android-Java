package com.lexinote.nativeapp;

import org.json.JSONObject;

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

  static final class ScheduleResult {
    final int nextCount;
    final int nextLevel;
    final String nextGrade;
    final long nextDue;
    final double nextEf;
    final int nextIntervalDays;
    final String intervalLabel;

    ScheduleResult(
        int nextCount,
        int nextLevel,
        String nextGrade,
        long nextDue,
        double nextEf,
        int nextIntervalDays,
        String intervalLabel) {
      this.nextCount = nextCount;
      this.nextLevel = nextLevel;
      this.nextGrade = nextGrade;
      this.nextDue = nextDue;
      this.nextEf = nextEf;
      this.nextIntervalDays = nextIntervalDays;
      this.intervalLabel = intervalLabel;
    }
  }

  static String formatInterval(int days) {
    if (days <= 0) return "<10分钟";
    if (days == 1) return "1天后";
    if (days < 30) return days + "天后";
    if (days < 365) {
      int months = Math.max(1, Math.round(days / 30f));
      return months + "个月后";
    }
    int years = Math.max(1, Math.round(days / 365f));
    return years + "年后";
  }

  static ScheduleResult calculateSchedule(JSONObject word, String grade, long now) {
    int count = (word != null) ? word.optInt("review_count", 0) : 0;
    Integer currentLevel = (word != null && word.has("review_mastery_level") && !word.isNull("review_mastery_level"))
        ? word.optInt("review_mastery_level") : null;
    String prevGrade = (word != null) ? word.optString("review_grade", "") : "";
    double ef = (word != null) ? word.optDouble("review_efactor", 2.5) : 2.5;
    if (Double.isNaN(ef) || ef < 1.3) ef = 2.5;

    int prevInterval = (word != null) ? word.optInt("review_interval_days", 0) : 0;
    if (prevInterval <= 0) {
      if (count == 1) prevInterval = 1;
      else if (count == 2) prevInterval = 3;
      else if (count >= 3) prevInterval = Math.max(4, count * 2);
    }

    int nextLvl = nextLevel(currentLevel, count, prevGrade, grade);
    int nextCnt = count + 1;
    double nextEf = ef;
    int nextDays;
    long nextDue;
    String label;

    if ("再次".equals(grade)) {
      nextEf = Math.max(1.3, ef - 0.2);
      nextDays = 0;
      nextDue = now;
      label = "<10分钟";
    } else if ("困难".equals(grade)) {
      nextEf = Math.max(1.3, ef - 0.15);
      if (count == 0) {
        nextDays = 1;
      } else if (count == 1) {
        nextDays = 2;
      } else {
        nextDays = Math.max(prevInterval + 1, (int) Math.round(prevInterval * 1.2));
      }
      nextDue = now + ((long) nextDays * 86400000L);
      label = formatInterval(nextDays);
    } else { // "简单"
      nextEf = Math.min(3.0, ef + 0.1);
      if (count == 0) {
        nextDays = 3;
      } else if (count == 1) {
        nextDays = 6;
      } else {
        nextDays = Math.max(prevInterval + 2, (int) Math.round(prevInterval * nextEf));
      }
      nextDue = now + ((long) nextDays * 86400000L);
      label = formatInterval(nextDays);
    }

    return new ScheduleResult(nextCnt, nextLvl, grade, nextDue, nextEf, nextDays, label);
  }

  static String previewInterval(JSONObject word, String grade, long now) {
    if (word == null) {
      if ("再次".equals(grade)) return "<10分钟";
      if ("困难".equals(grade)) return "1天后";
      return "3天后";
    }
    return calculateSchedule(word, grade, now).intervalLabel;
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
