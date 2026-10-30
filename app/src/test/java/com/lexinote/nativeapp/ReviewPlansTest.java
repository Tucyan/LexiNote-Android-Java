package com.lexinote.nativeapp;

import static org.junit.Assert.*;
import java.time.Instant;
import org.json.*;
import org.junit.Test;

public class ReviewPlansTest {
  private final long monday = Instant.parse("2026-10-26T02:00:00Z").toEpochMilli();
  private final int[] everyDay = {1, 2, 3, 4, 5, 6, 7};
  private Library library = new Library();
  private String note = library.createNotebook("单词", null);

  private String word(String content) throws Exception {
    return library.saveResult(new JSONObject("{word:'" + content
        + "',meanings:[{zh_definition:'释义'}]}"), new String[] {note}, new JSONObject());
  }
  private String plan(int fresh, int review) {
    return library.reviewPlans().create("计划", note, fresh, review, everyDay,
        "Asia/Shanghai", false, monday);
  }
  private void rejects(Runnable action) {
    try { action.run(); fail("invalid request accepted"); }
    catch (IllegalArgumentException expected) { }
  }
  private JSONObject patch(String text) throws Exception { return new JSONObject(text); }

  @Test public void emptyLibraryRequiresExplicitPlanAndCreationIsDefensive() {
    assertEquals(0, library.reviewPlans().list(false).length());
    String p = plan(20, 50);
    JSONObject view = library.reviewPlans().get(p);
    Library.put(view, "notebook_id", "bad");
    assertEquals(note, library.reviewPlans().get(p).optString("notebook_id"));
    rejects(() -> library.reviewPlans().create("bad", "missing", 1, 1, everyDay,
        "Asia/Shanghai", false, monday));
    rejects(() -> library.reviewPlans().create("bad", note, -1, 1, everyDay,
        "Asia/Shanghai", false, monday));
    rejects(() -> library.reviewPlans().create("bad", note, 0, 0, everyDay,
        "Asia/Shanghai", false, monday));
    rejects(() -> library.reviewPlans().create("bad", note, 1, 1, new int[] {0},
        "Asia/Shanghai", false, monday));
    rejects(() -> library.reviewPlans().create("bad", note, 1, 1, new int[] {1, 1},
        "Asia/Shanghai", false, monday));
    rejects(() -> library.reviewPlans().create("bad", note, 1, 1, new int[] {},
        "Asia/Shanghai", false, monday));
  }

  @Test public void bindingAndTimezoneCannotChangeAndUpdatesAreAtomic() throws Exception {
    String p = plan(1, 2);
    rejects(() -> library.reviewPlans().update(p, patchUnchecked("{notebook_id:'nb_default'}"), monday));
    rejects(() -> library.reviewPlans().update(p, patchUnchecked("{zone_id:'UTC'}"), monday));
    rejects(() -> library.reviewPlans().update(p, patchUnchecked("{name:'changed',daily_new_limit:-1}"), monday));
    assertEquals("计划", library.reviewPlans().get(p).optString("name"));
    library.reviewPlans().update(p, patch("{name:'改名',daily_new_limit:3,weekdays:[1,3,5]}"), monday);
    assertEquals(3, library.reviewPlans().get(p).optInt("daily_new_limit"));
    assertEquals(3, library.reviewPlans().get(p).optJSONArray("weekdays").length());
  }
  private JSONObject patchUnchecked(String text) {
    try { return patch(text); } catch (Exception e) { throw new AssertionError(e); }
  }

  @Test public void sameWordHasIndependentStateInTwoPlans() throws Exception {
    String v = word("apple"), p = plan(1, 1), other = plan(1, 1);
    library.reviewPlans().dailyQueue(p, monday);
    library.reviewPlans().record(p, v, "简单", "answer-1", 800, monday);
    assertEquals(0, library.find("vocabularies", v).optInt("review_count"));
    assertEquals(1, library.reviewPlans().dailyQueue(other, monday).optJSONArray("queue").length());
    assertEquals(1, library.reviewPlans().state(p, v).optInt("review_count"));
    assertNull(library.reviewPlans().state(other, v));
  }

  @Test public void dailyQuotasPersistAcrossRestartAndRepeatDoesNotComplete() throws Exception {
    word("a"); word("b"); word("c"); String p = plan(2, 1);
    JSONObject day = library.reviewPlans().dailyQueue(p, monday);
    String v = day.optJSONArray("queue").optJSONObject(0).optString("vocab_id");
    assertEquals(2, day.optJSONArray("queue").length());
    library.reviewPlans().record(p, v, "再次", "a1", 10, monday);
    assertEquals(0, library.reviewPlans().statistics(p, "2026-10-26", "2026-10-26")
        .optJSONObject("totals").optInt("new_completed"));
    library = new Library(library.snapshot());
    assertEquals(2, library.reviewPlans().dailyQueue(p, monday + 1000).optJSONArray("queue").length());
    library.reviewPlans().record(p, v, "困难", "a2", 20, monday + 1000);
    assertEquals(1, library.reviewPlans().dailyQueue(p, monday + 1000).optJSONArray("queue").length());
    library.reviewPlans().update(p, patch("{daily_new_limit:3}"), monday + 1000);
    assertEquals(2, library.reviewPlans().dailyQueue(p, monday + 1000).optInt("new_target"));
    JSONObject totals = library.reviewPlans().statistics(p, "2026-10-26", "2026-10-26").optJSONObject("totals");
    assertEquals(2, totals.optInt("attempts")); assertEquals(1, totals.optInt("new_completed"));
    assertEquals(30, totals.optLong("duration_ms")); assertEquals(1, totals.optInt("again"));
    assertEquals(1, totals.optInt("hard"));
  }

  @Test public void duplicateEventIsIdempotentAndConflictingReplayRejected() throws Exception {
    String v = word("a"), p = plan(1, 1);
    library.reviewPlans().dailyQueue(p, monday);
    JSONObject result = library.reviewPlans().record(p, v, "简单", "same", 10, monday);
    JSONObject again = library.reviewPlans().record(p, v, "简单", "same", 10, monday);
    assertEquals(result.toString(), again.toString());
    rejects(() -> library.reviewPlans().record(p, v, "困难", "same", 10, monday));
    assertEquals(1, library.reviewPlans().state(p, v).optInt("review_count"));
    rejects(() -> library.reviewPlans().record(p, v, "简单", "different", 10, monday));
  }

  @Test public void frequenciesUsePlanTimezoneAndOffDaysHaveNoTasks() throws Exception {
    word("a");
    String p = library.reviewPlans().create("周一", note, 1, 1, new int[] {1},
        "Asia/Shanghai", false, monday);
    long sundayUtcMondayLocal = Instant.parse("2026-10-25T16:01:00Z").toEpochMilli();
    JSONObject localMonday = library.reviewPlans().dailyQueue(p, sundayUtcMondayLocal);
    assertEquals("2026-10-26", localMonday.optString("date"));
    assertTrue(localMonday.optBoolean("scheduled"));
    JSONObject tuesday = library.reviewPlans().dailyQueue(p, monday + 86400000L);
    assertFalse(tuesday.optBoolean("scheduled"));
    assertEquals(0, tuesday.optJSONArray("queue").length());
  }

  @Test public void reviewQuotaOnlyContainsPreviouslyLearnedDueWords() throws Exception {
    String v = word("a"); word("b"); String p = plan(1, 1);
    String first = library.reviewPlans().dailyQueue(p, monday).optJSONArray("queue")
        .optJSONObject(0).optString("vocab_id");
    library.reviewPlans().record(p, first, "困难", "one", 0, monday);
    JSONObject next = library.reviewPlans().dailyQueue(p, monday + 86400000L);
    assertEquals(1, next.optInt("review_target"));
    assertEquals(1, next.optInt("new_target"));
    assertEquals("review", next.optJSONArray("queue").optJSONObject(0).optString("kind"));
    assertEquals(first, next.optJSONArray("queue").optJSONObject(0).optString("vocab_id"));
  }

  @Test public void legacyImportCopiesStateWithoutInventingHistory() throws Exception {
    String v = word("a"); JSONObject old = library.find("vocabularies", v);
    Library.put(old, "review_count", 4); Library.put(old, "review_due", monday);
    Library.put(old, "review_grade", "简单");
    String p = library.reviewPlans().create("迁移", note, 2, 2, everyDay,
        "Asia/Shanghai", true, monday);
    assertEquals(4, library.reviewPlans().state(p, v).optInt("review_count"));
    assertEquals(1, library.reviewPlans().dailyQueue(p, monday).optInt("review_target"));
    assertEquals(0, library.reviewPlans().statistics(p, "2026-10-26", "2026-10-26")
        .optJSONObject("totals").optInt("attempts"));
    Library restored = new Library(library.backup());
    assertEquals(4, restored.reviewPlans().state(p, v).optInt("review_count"));
    assertEquals(1, restored.reviewPlans().list(false).length());
  }

  @Test public void subtreeBindingDeduplicatesAndActivePlansProtectDeletion() throws Exception {
    String v = word("a"), parent = library.createNotebook("父", null);
    String child = library.createNotebook("子", parent);
    Library.put(library.find("notebooks", child), "vocabulary_ids", new JSONArray().put(v));
    String p = library.reviewPlans().create("子树", parent, 5, 5, everyDay, "UTC", false, monday);
    assertEquals(1, library.reviewPlans().dailyQueue(p, monday).optInt("new_target"));
    rejects(() -> library.deleteNotebook(parent));
    assertNotNull(library.find("notebooks", child));
    library.reviewPlans().archive(p, monday);
    assertEquals(0, library.reviewPlans().list(false).length());
    library.deleteNotebook(parent);
    Library.validate(library.snapshot());
    rejects(() -> library.reviewPlans().dailyQueue(p, monday));
  }

  @Test public void removedWordsAreNotAnsweredButTheirStatisticsRemain() throws Exception {
    String v = word("a"), p = plan(1, 1);
    library.reviewPlans().dailyQueue(p, monday);
    library.reviewPlans().record(p, v, "简单", "a", 20, monday);
    library.deleteVocabularyEverywhere(new String[] {v});
    Library.validate(library.snapshot());
    assertEquals(1, library.reviewPlans().statistics(p, "2026-10-26", "2026-10-26")
        .optJSONObject("totals").optInt("new_completed"));
    assertEquals(0, library.reviewPlans().dailyQueue(p, monday).optJSONArray("queue").length());
  }

  @Test public void badCommandsAndCorruptBackupsAreRejected() throws Exception {
    String v = word("a"), p = plan(1, 1);
    rejects(() -> library.reviewPlans().record(p, v, "unknown", "a", 0, monday));
    rejects(() -> library.reviewPlans().record(p, v, "简单", "a", -1, monday));
    rejects(() -> library.reviewPlans().record(p, v, "简单", "a", 0, monday));
    JSONObject backup = library.snapshot();
    Library.put(backup.optJSONObject("review"), "schema_version", 999);
    rejects(() -> new Library(backup));
    JSONObject corrupt = library.snapshot();
    corrupt.optJSONObject("review").optJSONArray("plans").optJSONObject(0)
        .put("daily_new_limit", "2");
    rejects(() -> new Library(corrupt));
    rejects(() -> library.reviewPlans().statistics(p, "2026-10-27", "2026-10-26"));
  }

  @Test public void heatmapFillsEmptyDatesAndZipPreservesReviewDocument() throws Exception {
    String v = word("a"), p = plan(1, 1);
    library.reviewPlans().dailyQueue(p, monday);
    library.reviewPlans().record(p, v, "简单", "a", 30, monday);
    JSONObject stats = library.reviewPlans().statistics(p, "2026-10-25", "2026-10-27");
    assertEquals(3, stats.optJSONArray("days").length());
    assertEquals(0, stats.optJSONArray("days").optJSONObject(0).optInt("attempts"));
    assertEquals(1, stats.optInt("active_days"));
    byte[] zip = BackupArchive.encode(library.backup(), uri -> new byte[0]);
    Library restored = new Library(BackupArchive.decode(zip).backup);
    assertEquals(1, restored.reviewPlans().statistics(p, "2026-10-26", "2026-10-26")
        .optJSONObject("totals").optInt("new_completed"));
  }

  @Test public void laterTodayDueWordIsIncludedWhenOpeningInTheMorning() throws Exception {
    String v = word("a"), p = plan(1, 1);
    library.reviewPlans().dailyQueue(p, monday);
    library.reviewPlans().record(p, v, "困难", "first", 0, monday);
    long nextMorning = monday + 86400000L - 3600000L;
    JSONObject queue = library.reviewPlans().dailyQueue(p, nextMorning);
    assertEquals(1, queue.optInt("review_target"));
    assertEquals(v, queue.optJSONArray("queue").optJSONObject(0).optString("vocab_id"));
  }

  @Test public void extremeLegacyCountersCannotProduceUnrestorableProgress() throws Exception {
    String v = word("a"); JSONObject old = library.find("vocabularies", v);
    Library.put(old, "review_count", Integer.MAX_VALUE);
    Library.put(old, "review_interval_days", Integer.MAX_VALUE);
    Library.put(old, "review_due", monday);
    String p = library.reviewPlans().create("边界", note, 1, 1, everyDay, "UTC", true, monday);
    library.reviewPlans().dailyQueue(p, monday);
    library.reviewPlans().record(p, v, "简单", "max", 0, monday);
    assertEquals(Integer.MAX_VALUE, library.reviewPlans().state(p, v).optInt("review_count"));
    assertEquals(Integer.MAX_VALUE, library.reviewPlans().state(p, v).optInt("review_interval_days"));
    Library.validate(library.snapshot());
    new Library(library.backup());
  }

  @Test public void restoreRejectsStateThatContradictsCompletedEvents() throws Exception {
    String v = word("a"), p = plan(1, 1);
    library.reviewPlans().dailyQueue(p, monday);
    library.reviewPlans().record(p, v, "简单", "done", 0, monday);
    JSONObject backup = library.snapshot();
    backup.optJSONObject("review").optJSONArray("states").optJSONObject(0).put("learned_at", JSONObject.NULL);
    rejects(() -> new Library(backup));
    JSONObject badCount = library.snapshot();
    badCount.optJSONObject("review").optJSONArray("states").optJSONObject(0).put("review_count", 0);
    rejects(() -> new Library(badCount));
  }

  @Test public void extendDailyQueueAppendsTasksAndValidates() throws Exception {
    String v1 = word("apple"), v2 = word("banana"), v3 = word("cherry"), v4 = word("date");
    String p = plan(1, 1);
    JSONObject q1 = library.reviewPlans().dailyQueue(p, monday);
    assertEquals(1, q1.optJSONArray("queue").length());
    String firstVocab = q1.optJSONArray("queue").optJSONObject(0).optString("vocab_id");
    assertNotNull(firstVocab);

    // Rate firstVocab as 简单
    library.reviewPlans().record(p, firstVocab, "简单", "ev1", 1000, monday);
    JSONObject afterRecord = library.reviewPlans().dailyQueue(p, monday);
    assertEquals(0, afterRecord.optJSONArray("queue").length());

    // Check extra available
    JSONObject avail = library.reviewPlans().extraAvailable(p, monday);
    assertEquals(3, avail.optInt("available_new"));
    assertEquals(0, avail.optInt("available_review"));
    assertEquals(3, avail.optInt("available_total"));

    // Extend queue by 2 fresh words
    JSONObject extended = library.reviewPlans().extendDailyQueue(p, 2, 0, monday);
    assertEquals(2, extended.optJSONArray("queue").length());
    assertEquals(3, extended.optInt("new_target"));

    // Validate schema
    Library.validate(library.snapshot());

    // Complete remaining extended words
    String next1 = extended.optJSONArray("queue").optJSONObject(0).optString("vocab_id");
    String next2 = extended.optJSONArray("queue").optJSONObject(1).optString("vocab_id");
    library.reviewPlans().record(p, next1, "简单", "ev2", 1000, monday);
    library.reviewPlans().record(p, next2, "简单", "ev3", 1000, monday);

    JSONObject finish = library.reviewPlans().dailyQueue(p, monday);
    assertEquals(0, finish.optJSONArray("queue").length());
    assertEquals(3, finish.optInt("new_target"));

    Library.validate(library.snapshot());
    new Library(library.backup());
  }
}
