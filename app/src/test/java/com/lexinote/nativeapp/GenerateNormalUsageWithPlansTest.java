package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Generates and validates the normal-usage fixture with review plans and realistic study history.
 */
public class GenerateNormalUsageWithPlansTest {
  public static final String ZONE_ID = "Asia/Shanghai";

  @Test
  public void generateNormalUsageWithPlans() throws Exception {
    File repoRoot = findRepoRoot();
    File zipSource = new File(repoRoot, "docs/fixtures/normal-usage/lexinote-normal-usage-20261028.zip");
    assertTrue("source zip must exist at " + zipSource.getAbsolutePath(), zipSource.exists());

    byte[] zipBytes = Files.readAllBytes(zipSource.toPath());
    BackupArchive.Prepared prepared = BackupArchive.decode(zipBytes);
    assertNotNull(prepared.backup);

    Library lib = new Library(prepared.backup);

    // Create 4 review plans matching normal-usage thematic notebooks
    long baseTime = Instant.parse("2026-10-01T08:00:00Z").toEpochMilli();
    int[] everyDay = {1, 2, 3, 4, 5, 6, 7};
    int[] weekDays = {1, 2, 3, 4, 5};

    String planWork = lib.reviewPlans().create("工作沟通复习计划", "usage-work", 5, 15, everyDay, ZONE_ID, false, baseTime);
    String planPhrases = lib.reviewPlans().create("常用短语精练计划", "usage-phrases", 5, 10, everyDay, ZONE_ID, false, baseTime);
    String planReading = lib.reviewPlans().create("阅读表达进阶计划", "usage-reading", 4, 10, weekDays, ZONE_ID, false, baseTime);
    String planDaily = lib.reviewPlans().create("日常生活打卡计划", "usage-daily", 4, 10, everyDay, ZONE_ID, false, baseTime);

    List<String> planIds = Arrays.asList(planWork, planPhrases, planReading, planDaily);

    // Deterministic simulation over October 1 to October 27, 2026
    Random rng = new Random(20261028);
    int eventCounter = 0;
    LocalDate currentDate = LocalDate.of(2026, 10, 1);
    LocalDate endDate = LocalDate.of(2026, 10, 27);

    while (!currentDate.isAfter(endDate)) {
      long dayTimestamp = currentDate.atTime(9, 30).atZone(ZoneId.of(ZONE_ID)).toInstant().toEpochMilli();
      for (String planId : planIds) {
        JSONObject plan = lib.reviewPlans().get(planId);
        if (!lib.reviewPlans().isScheduled(plan, dayTimestamp)) {
          continue;
        }
        JSONObject dayView = lib.reviewPlans().dailyQueue(planId, dayTimestamp);
        JSONArray queue = dayView.optJSONArray("queue");
        if (queue == null || queue.length() == 0) continue;

        // In order to strictly satisfy ReviewPlanSchema:
        // Every task generated in dailyQueue that is completed must have matching events,
        // while all tasks attempted must be finished or retried until completion.
        // We simulate completing the tasks in the daily queue.
        for (int q = 0; q < queue.length(); q++) {
          JSONObject task = queue.getJSONObject(q);
          String vocabId = task.getString("vocab_id");
          double roll = rng.nextDouble();
          if (roll < 0.08) {
            // "再次" followed by retry with "简单"
            String eventId1 = String.format(Locale.ROOT, "evt-%s-%05d-a", planId.substring(0, 6), eventCounter++);
            lib.reviewPlans().record(planId, vocabId, "再次", eventId1, 2500 + rng.nextInt(1500), dayTimestamp + q * 10000L);
            String eventId2 = String.format(Locale.ROOT, "evt-%s-%05d-b", planId.substring(0, 6), eventCounter++);
            lib.reviewPlans().record(planId, vocabId, "简单", eventId2, 1200 + rng.nextInt(600), dayTimestamp + q * 10000L + 45000L);
          } else if (roll < 0.28) {
            String eventId = String.format(Locale.ROOT, "evt-%s-%05d", planId.substring(0, 6), eventCounter++);
            lib.reviewPlans().record(planId, vocabId, "困难", eventId, 2200 + rng.nextInt(1000), dayTimestamp + q * 10000L);
          } else {
            String eventId = String.format(Locale.ROOT, "evt-%s-%05d", planId.substring(0, 6), eventCounter++);
            lib.reviewPlans().record(planId, vocabId, "简单", eventId, 1000 + rng.nextInt(500), dayTimestamp + q * 10000L);
          }
        }
      }
      currentDate = currentDate.plusDays(1);
    }

    // On 2026-10-28 (the fixture export date), prepare the daily queue for all plans so today's queue is populated
    long exportTime = Instant.parse("2026-10-28T09:00:00Z").toEpochMilli();
    for (String planId : planIds) {
      JSONObject plan = lib.reviewPlans().get(planId);
      if (lib.reviewPlans().isScheduled(plan, exportTime)) {
        lib.reviewPlans().dailyQueue(planId, exportTime);
      }
    }

    JSONObject snapshot = lib.snapshot();
    ReviewPlanSchema.validate(snapshot);
    Library.validate(snapshot);

    JSONObject review = snapshot.getJSONObject("review");
    assertEquals(4, review.getJSONArray("plans").length());
    int statesCount = review.getJSONArray("states").length();
    int daysCount = review.getJSONArray("days").length();
    int eventsCount = review.getJSONArray("events").length();
    assertTrue("Has states: " + statesCount, statesCount > 0);
    assertTrue("Has days: " + daysCount, daysCount > 0);
    assertTrue("Has events: " + eventsCount, eventsCount > 0);

    // Encode to ZIP format 2.2
    byte[] newZipBytes = BackupArchive.encode(snapshot, uri -> new byte[0]);
    BackupArchive.Prepared verifyDecoded = BackupArchive.decode(newZipBytes);
    assertNotNull(verifyDecoded.backup);
    assertEquals(4, verifyDecoded.backup.getJSONObject("review").getJSONArray("plans").length());

    // Write to docs/fixtures/normal-usage/
    File targetDir = new File(repoRoot, "docs/fixtures/normal-usage");
    File outZip = new File(targetDir, "lexinote-normal-usage-with-plans-20261028.zip");
    File outZipAlias = new File(targetDir, "lexinote-normal-usage-20261028-with-plans.zip");
    File outJson = new File(targetDir, "lexinote-normal-usage-with-plans-20261028.json");

    Files.write(outZip.toPath(), newZipBytes);
    Files.write(outZipAlias.toPath(), newZipBytes);

    JSONObject jsonDocument = new JSONObject(snapshot.toString());
    jsonDocument.put("version", "1.1");
    Files.write(outJson.toPath(), jsonDocument.toString(2).getBytes(StandardCharsets.UTF_8));
    System.out.println("Generated normal-usage fixture with plans:");
    System.out.println("  Plans: " + review.getJSONArray("plans").length());
    System.out.println("  States: " + statesCount);
    System.out.println("  Days: " + daysCount);
    System.out.println("  Events: " + eventsCount);
    System.out.println("  Zip size: " + newZipBytes.length + " bytes");
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
}
