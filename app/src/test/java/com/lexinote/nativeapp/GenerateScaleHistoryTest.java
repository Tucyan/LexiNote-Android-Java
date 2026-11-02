package com.lexinote.nativeapp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Generates and validates the legitimate 3,000-item + 10,000-event fixture
 * using genuine domain methods (ReviewPlans.dailyQueue and ReviewPlans.record),
 * asserting schema compliance and recording baseline capacity rejection.
 */
public class GenerateScaleHistoryTest {
  public static final int TARGET_EVENTS = 10000;
  public static final String ZONE_ID = "Asia/Shanghai";

  @Test
  public void generateScale3000Events10000() throws Exception {
    File repoRoot = findRepoRoot();
    File baseFile = new File(repoRoot, "docs/fixtures/scale-usage/lexinote-scale-3000.json");
    if (!baseFile.exists()) {
      baseFile = new File(repoRoot, "app/src/androidTest/assets/scale-3000.json");
    }
    assertTrue("scale-3000.json must exist at " + baseFile.getAbsolutePath(), baseFile.exists());

    byte[] rawBytes = java.nio.file.Files.readAllBytes(baseFile.toPath());
    Library lib = new Library(new JSONObject(new String(rawBytes, StandardCharsets.UTF_8)));

    // Create 12 review plans matching ScaleInstrumentation / production setup
    long baseTime = Instant.parse("2026-06-01T08:00:00Z").toEpochMilli();
    int[] everyDay = {1, 2, 3, 4, 5, 6, 7};
    List<String> planIds = new ArrayList<>();
    planIds.add(lib.reviewPlans().create("工作英语强化总计划", "scale-heavy", 30, 80, everyDay, ZONE_ID, false, baseTime));
    for (int i = 0; i < 11; i++) {
      String noteId = String.format(Locale.ROOT, "scale-note-%02d", i);
      planIds.add(lib.reviewPlans().create("阅读精读计划 " + (i + 1), noteId, 15, 40, everyDay, ZONE_ID, false, baseTime));
    }

    Random rng = new Random(42); // deterministic seed
    int totalEvents = 0;
    LocalDate currentDate = LocalDate.of(2026, 6, 1);

    // Simulate days until target 10,000 events reached
    while (totalEvents < TARGET_EVENTS) {
      long dayTimestamp = currentDate.atTime(9, 0).atZone(ZoneId.of(ZONE_ID)).toInstant().toEpochMilli();
      for (String planId : planIds) {
        if (totalEvents >= TARGET_EVENTS) break;
        JSONObject dayView = lib.reviewPlans().dailyQueue(planId, dayTimestamp);
        JSONArray queue = dayView.optJSONArray("queue");
        if (queue == null || queue.length() == 0) continue;

        for (int q = 0; q < queue.length() && totalEvents < TARGET_EVENTS; q++) {
          JSONObject task = queue.getJSONObject(q);
          String vocabId = task.getString("vocab_id");

          double roll = rng.nextDouble();
          if (roll < 0.10) {
            // "再次" -> retry needed
            String eventId1 = String.format(Locale.ROOT, "evt-%s-%06d-a", planId.substring(0, Math.min(8, planId.length())), totalEvents);
            lib.reviewPlans().record(planId, vocabId, "再次", eventId1, 3000 + rng.nextInt(2000), dayTimestamp);
            totalEvents++;
            if (totalEvents >= TARGET_EVENTS) break;

            // Follow-up retry completed with "简单"
            String eventId2 = String.format(Locale.ROOT, "evt-%s-%06d-b", planId.substring(0, Math.min(8, planId.length())), totalEvents);
            lib.reviewPlans().record(planId, vocabId, "简单", eventId2, 1200 + rng.nextInt(800), dayTimestamp + 60000);
            totalEvents++;
          } else if (roll < 0.35) {
            // "困难"
            String eventId = String.format(Locale.ROOT, "evt-%s-%06d", planId.substring(0, Math.min(8, planId.length())), totalEvents);
            lib.reviewPlans().record(planId, vocabId, "困难", eventId, 2500 + rng.nextInt(1500), dayTimestamp);
            totalEvents++;
          } else {
            // "简单"
            String eventId = String.format(Locale.ROOT, "evt-%s-%06d", planId.substring(0, Math.min(8, planId.length())), totalEvents);
            lib.reviewPlans().record(planId, vocabId, "简单", eventId, 1000 + rng.nextInt(800), dayTimestamp);
            totalEvents++;
          }
        }
      }
      currentDate = currentDate.plusDays(1);
    }

    assertEquals(TARGET_EVENTS, totalEvents);

    // Verify strict schema validity
    JSONObject snapshot = lib.snapshot();
    ReviewPlanSchema.validate(snapshot);
    assertEquals(TARGET_EVENTS, snapshot.getJSONObject("review").getJSONArray("events").length());

    // Verify baseline capacity rejection: legacy Repository.save must reject > 4MB document
    File tempDir = new File(System.getProperty("java.io.tmpdir"), "scale-capacity-" + System.nanoTime());
    tempDir.mkdirs();
    try {
      Repository repo = new Repository(new DummyContext(tempDir));
      boolean rejected = false;
      try {
        repo.save(lib);
      } catch (IOException expected) {
        rejected = true;
        assertTrue(expected.getMessage().contains("最高4 MB") || expected.getMessage().contains("超过当前设备容量"));
      }
      assertTrue("Baseline Repository.save must reject 3000+10000 events library exceeding 4MB", rejected);
    } finally {
      deleteDir(tempDir);
    }

    // Export fixture to disk
    byte[] payload = snapshot.toString().getBytes(StandardCharsets.UTF_8);
    assertTrue("Payload must exceed 4MB", payload.length > BackupArchive.MAX_JSON_BYTES);

    File outJson = new File(repoRoot, "docs/fixtures/scale-usage/lexinote-scale-3000-events-10000.json");
    outJson.getParentFile().mkdirs();
    java.nio.file.Files.write(outJson.toPath(), payload);

    File assetJson = new File(repoRoot, "app/src/androidTest/assets/scale-3000-events-10000.json");
    assetJson.getParentFile().mkdirs();
    java.nio.file.Files.write(assetJson.toPath(), payload);

    System.out.println("Generated legitimate fixture: " + outJson.getAbsolutePath() + " (" + payload.length + " bytes, " + totalEvents + " events)");
  }

  private static File findRepoRoot() {
    File curr = new File(".").getAbsoluteFile();
    while (curr != null) {
      if (new File(curr, "settings.gradle").exists() && new File(curr, "app").exists()) {
        return curr;
      }
      curr = curr.getParentFile();
    }
    return new File(".").getAbsoluteFile();
  }

  private static void deleteDir(File dir) {
    File[] files = dir.listFiles();
    if (files != null) {
      for (File f : files) {
        if (f.isDirectory()) deleteDir(f);
        else f.delete();
      }
    }
    dir.delete();
  }

  private static class DummyContext extends android.content.ContextWrapper {
    private final File filesDir;
    DummyContext(File dir) {
      super(null);
      this.filesDir = dir;
    }
    @Override
    public File getFilesDir() {
      return filesDir;
    }
  }
}
