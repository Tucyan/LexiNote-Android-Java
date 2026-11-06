package com.lexinote.nativeapp;
import org.junit.Test;
import static org.junit.Assert.*;
public class BackupLimitsTest {
  @Test public void separateCompressedAndExtractedBudgetsAllowEightMiB() {
    BackupLimits limits = new BackupLimits();
    limits.addArchive(8L * 1024 * 1024);
    limits.addExtracted(8L * 1024 * 1024);
    assertThrows(IllegalArgumentException.class, () -> limits.addExtracted(9L * 1024 * 1024));
  }
}
