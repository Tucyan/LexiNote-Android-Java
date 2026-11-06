package com.lexinote.nativeapp;
/** Separate budgets for compressed input/output and expanded content. */
public final class BackupLimits {
  public static final long MAX_BYTES = 16L * 1024 * 1024;
  private long archive, extracted;
  public void addArchive(long count) { archive = checked(archive, count); }
  public void addExtracted(long count) { extracted = checked(extracted, count); }
  private static long checked(long current, long count) {
    if (count < 0 || count > MAX_BYTES - current) throw new IllegalArgumentException("备份超过16 MiB容量限制");
    return current + count;
  }
}
