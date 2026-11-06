package com.lexinote.nativeapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import com.lexinote.nativeapp.storage.contract.CommitResult;
import com.lexinote.nativeapp.storage.contract.LibraryCommand;
import com.lexinote.nativeapp.storage.contract.QueryRequest;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

/** Isolated SQLite regression; never opens or replaces the application database. */
public final class IncrementalReviewInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  @Override public void onStart() {
    Bundle report = new Bundle(); int outcome = Activity.RESULT_OK;
    File path = new File(getTargetContext().getCacheDir(), "sqlite-review-" + System.nanoTime() + ".db");
    SqliteLibraryStore store = null;
    try {
      Library lib = new Library(); String note = lib.createNotebook("测试", null);
      String word = lib.saveResult(new JSONObject("{word:'test',meanings:[{zh_definition:'测试'}]}"), new String[]{note}, new JSONObject());
      long now = System.currentTimeMillis();
      String plan = lib.reviewPlans().create("计划", note, 1, 1, new int[]{1,2,3,4,5,6,7}, "Asia/Shanghai", false, now);
      JSONObject unprepared = lib.snapshot();
      lib.reviewPlans().dailyQueue(plan, now);
      JSONObject source = lib.snapshot(); source.put("future_field", new JSONObject("{nested:[1,2,3],token_count:12,usage:{total_tokens:34}}"));
      store = new SqliteLibraryStore(getTargetContext(), path.getAbsolutePath()); store.initialize();
      store.restore(unprepared, store.getGeneration());
      long prepareGen = store.getGeneration(); long prepareRevision = store.getRevision();
      CommitResult prepared = store.execute(LibraryCommand.prepareDailyQueue(prepareGen, plan, now));
      check(prepared.isSuccess(), "queue preparation failed: " + prepared.getMessage());
      check(prepared.getUpdatedEntity().getJSONArray("days").getJSONObject(0).toString().equals(lib.snapshot().getJSONObject("review").getJSONArray("days").getJSONObject(0).toString()), "queue differs from domain");
      check(store.getRevision() == prepareRevision + 1, "queue revision not advanced");
      check(store.execute(LibraryCommand.prepareDailyQueue(prepareGen, plan, now)).getStatus() == CommitResult.Status.IDEMPOTENT_IGNORED && store.getRevision() == prepareRevision + 1, "existing queue was rewritten");
      store.restore(unprepared, prepareGen); prepareGen = store.getGeneration(); prepareRevision = store.getRevision();
      android.database.sqlite.SQLiteDatabase queueFault = android.database.sqlite.SQLiteDatabase.openDatabase(path.getAbsolutePath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE);
      queueFault.execSQL("CREATE TRIGGER fail_test_day BEFORE INSERT ON review_days BEGIN SELECT RAISE(ABORT, 'injected failure'); END"); queueFault.close();
      check(!store.execute(LibraryCommand.prepareDailyQueue(prepareGen, plan, now)).isSuccess(), "queue failure accepted");
      check(store.getRevision() == prepareRevision && store.loadSnapshot().getRawDocument().getJSONObject("review").getJSONArray("days").length() == 0, "failed queue partially committed");
      queueFault = android.database.sqlite.SQLiteDatabase.openDatabase(path.getAbsolutePath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE);
      queueFault.execSQL("DROP TRIGGER fail_test_day"); queueFault.close();
      store.restore(source, store.getGeneration()); long gen = store.getGeneration();
      long initialRevision = store.getRevision();
      check(store.execute(LibraryCommand.createNotebook(gen, "兼容路径", null, now)).isSuccess(), "ordinary command failed");
      check(store.getRevision() == initialRevision + 1, "ordinary command revision not persisted");
      check(!store.execute(LibraryCommand.createNotebook(gen, "", null, now)).isSuccess(), "invalid notebook accepted");
      check(store.getRevision() == initialRevision + 1, "failure changed revision");
      LibraryCommand cmd = LibraryCommand.recordReview(gen, "event-1", plan, word, "再次", 10, now);
      CommitResult result = store.execute(cmd);
      check(result.isSuccess(), "record failed: " + result.getMessage());
      JSONObject expected = lib.reviewPlans().record(plan, word, "再次", "event-1", 10, now);
      check(expected.toString().equals(result.getUpdatedEntity().getJSONObject("event").toString()), "event differs from domain");
      check(result.getUpdatedEntity().getJSONObject("state").toString().equals(lib.reviewPlans().state(plan, word).toString()), "state differs from domain");
      long revision = store.getRevision();
      check(store.execute(cmd).getStatus() == CommitResult.Status.IDEMPOTENT_IGNORED && store.getRevision() == revision, "duplicate changed revision");
      check(!store.execute(LibraryCommand.recordReview(gen, "event-1", plan, word, "简单", 10, now)).isSuccess(), "conflicting replay accepted");
      check(store.query(QueryRequest.reviewEvent("event-1")).getRawDocument().getJSONObject("event").getString("id").equals("event-1"), "event query failed");
      store.close(); store = new SqliteLibraryStore(getTargetContext(), path.getAbsolutePath()); store.initialize();
      check(store.getGeneration() == gen && store.getRevision() == revision, "version did not survive reopen");
      check(store.loadSnapshot().getRawDocument().getJSONObject("future_field").getJSONArray("nested").length() == 3, "unknown field lost");
      check(store.loadSnapshot().getRawDocument().getJSONObject("future_field").getInt("token_count") == 12 && store.loadSnapshot().getRawDocument().getJSONObject("future_field").getJSONObject("usage").getInt("total_tokens") == 34, "local usage fields lost");
      JSONObject beforeFailure = store.loadSnapshot().getRawDocument().getJSONObject("review");
      android.database.sqlite.SQLiteDatabase fault = android.database.sqlite.SQLiteDatabase.openDatabase(path.getAbsolutePath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE);
      fault.execSQL("CREATE TRIGGER fail_test_event BEFORE INSERT ON review_events BEGIN SELECT RAISE(ABORT, 'injected failure'); END"); fault.close();
      check(!store.execute(LibraryCommand.recordReview(gen, "event-fail", plan, word, "简单", 30, now)).isSuccess(), "injected write failure accepted");
      check(store.getRevision() == revision, "failed transaction changed revision");
      JSONObject afterFailure = store.loadSnapshot().getRawDocument().getJSONObject("review");
      check(beforeFailure.toString().equals(afterFailure.toString()), "failed transaction changed state or day");
      fault = android.database.sqlite.SQLiteDatabase.openDatabase(path.getAbsolutePath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE);
      fault.execSQL("DROP TRIGGER fail_test_event"); fault.close();
      java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
      store.exportJson(output);
      JSONObject exported = new JSONObject(output.toString("UTF-8"));
      Library.validate(exported);
      check(exported.getJSONObject("future_field").getJSONArray("nested").length() == 3, "stream export lost unknown fields");
      check(exported.getJSONObject("review").getJSONArray("events").length() == 1, "stream export lost event");
      check(store.rollback(gen).isSuccess() && store.getGeneration() != gen, "rollback failed");
      check(!store.execute(cmd).isSuccess(), "stale command accepted after rollback");
      File streamFile = new File(getTargetContext().getCacheDir(), "import-" + System.nanoTime() + ".json");
      exported.put("api_key", "must-not-persist");
      exported.put("apiKey", "must-not-persist-either");
      exported.put("providers", new JSONArray().put(new JSONObject().put("api_key", "must-not-persist-provider")));
      exported.getJSONArray("vocabularies").getJSONObject(0).put("secret", "must-not-persist");
      JSONObject legacySession = new JSONObject("{id:'legacy-session',input_text:'lookup',ai_messages:[{role:'user',content:'hello'}]}");
      exported.put("recent_queries", new org.json.JSONArray().put(legacySession));
      exported.put("conversations", new org.json.JSONArray().put(new JSONObject(legacySession.toString())));
      JSONObject expectedSession = new Library(exported).snapshot().getJSONArray("conversations").getJSONObject(0);
      try (java.io.FileOutputStream stream = new java.io.FileOutputStream(streamFile)) {
        stream.write(exported.toString().getBytes("UTF-8"));
      }
      long beforeImport = store.getGeneration();
      CommitResult imported = store.importJson(streamFile, beforeImport);
      check(imported.isSuccess(), "stream import rejected valid export: " + imported.getMessage());
      check(store.query(QueryRequest.reviewEvent("event-1")).getRawDocument().has("event"), "stream import lost event");
      JSONObject importedSnapshot = store.loadSnapshot().getRawDocument();
      check(!importedSnapshot.has("api_key") && !importedSnapshot.has("apiKey") && !importedSnapshot.has("providers") && !importedSnapshot.getJSONArray("vocabularies").getJSONObject(0).has("secret"), "secret persisted");
      check(importedSnapshot.has("future_field"), "non-secret unknown field lost");
      check(importedSnapshot.getJSONObject("future_field").getInt("token_count") == 12 && importedSnapshot.getJSONObject("future_field").getJSONObject("usage").getInt("total_tokens") == 34, "imported usage fields lost");
      check(importedSnapshot.getJSONArray("conversations").getJSONObject(0).toString().equals(expectedSession.toString()), "legacy session normalization differs");
      long importedGeneration = store.getGeneration();
      JSONObject saved = new JSONObject("{id:'saved-test',saved:true,input_text:'saved'}");
      check(store.execute(LibraryCommand.touchSession(importedGeneration, "saved-test", saved, false, now)).isSuccess(), "saved session failed");
      long sessionBase = store.getRevision();
      check(store.execute(LibraryCommand.touchSession(importedGeneration, "saved-test", saved, false, now, sessionBase)).isSuccess(), "current session revision rejected");
      check(store.execute(LibraryCommand.touchSession(importedGeneration, "saved-test", saved, false, now, sessionBase)).isConflict(), "stale session accepted");
      for (int i = 0; i < 6; i++) {
        JSONObject temporary = new JSONObject(); temporary.put("id", "temporary-" + i); temporary.put("ai_messages", new org.json.JSONArray().put(new JSONObject("{role:'user',content:'hello'}")));
        check(store.execute(LibraryCommand.touchSession(importedGeneration, temporary.optString("id"), temporary, false, now)).isSuccess(), "temporary session failed");
      }
      for (int i = 0; i < 12; i++) {
        JSONObject query = new JSONObject(); query.put("id", "query-" + i); query.put("created_at", java.time.Instant.ofEpochMilli(now + i * 1000).toString());
        check(store.execute(LibraryCommand.touchSession(importedGeneration, query.optString("id"), query, true, now)).isSuccess(), "query session failed");
      }
      JSONObject afterSessions = store.loadSnapshot().getRawDocument();
      check(afterSessions.getJSONArray("recent_queries").length() == 10, "recent limit changed");
      check(afterSessions.getJSONArray("recent_queries").getJSONObject(0).optString("id").equals("query-11") && afterSessions.getJSONArray("recent_queries").getJSONObject(9).optString("id").equals("query-2"), "recent ordering changed");
      org.json.JSONArray sessions = afterSessions.getJSONArray("conversations"); int temporaryCount = 0; boolean keptSaved = false;
      for (int i = 0; i < sessions.length(); i++) { JSONObject s = sessions.getJSONObject(i); if (Library.isTemporary(s)) temporaryCount++; if ("saved-test".equals(s.optString("id"))) keptSaved = true; }
      check(temporaryCount == 5 && keptSaved, "session retention changed");
      check(afterSessions.getJSONObject("review").getJSONArray("events").length() == 1, "session write changed review events");
      android.database.sqlite.SQLiteDatabase importFault = android.database.sqlite.SQLiteDatabase.openDatabase(path.getAbsolutePath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE);
      importFault.execSQL("CREATE TRIGGER fail_test_import BEFORE INSERT ON document_meta BEGIN SELECT RAISE(ABORT, 'injected failure'); END"); importFault.close();
      long revisionBeforeImportFault = store.getRevision();
      check(!store.importJson(streamFile, importedGeneration).isSuccess(), "import write failure accepted");
      check(store.getGeneration() == importedGeneration && store.getRevision() == revisionBeforeImportFault, "failed import changed active version");
      importFault = android.database.sqlite.SQLiteDatabase.openDatabase(path.getAbsolutePath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE);
      importFault.execSQL("DROP TRIGGER fail_test_import"); importFault.close();
      try (java.io.FileOutputStream stream = new java.io.FileOutputStream(streamFile)) { stream.write("{\"vocabularies\":[]}".getBytes("UTF-8")); }
      check(!store.importJson(streamFile, importedGeneration).isSuccess(), "invalid import accepted");
      check(store.getGeneration() == importedGeneration, "failed import changed active generation");
      check(store.rollback(importedGeneration).isSuccess(), "stream import lost rollback generation");
      streamFile.delete();
      long prior = now - 4L * 86400000L;
      Library mixed = new Library(); String mixedNote = mixed.createNotebook("混合队列", null);
      String a = mixed.saveResult(new JSONObject("{word:'a',meanings:[{zh_definition:'a'}]}"), new String[]{mixedNote}, new JSONObject());
      String b = mixed.saveResult(new JSONObject("{word:'b',meanings:[{zh_definition:'b'}]}"), new String[]{mixedNote}, new JSONObject());
      mixed.saveResult(new JSONObject("{word:'c',meanings:[{zh_definition:'c'}]}"), new String[]{mixedNote}, new JSONObject());
      String mixedPlan = mixed.reviewPlans().create("混合", mixedNote, 3, 3, new int[]{1,2,3,4,5,6,7}, "Asia/Shanghai", false, prior);
      mixed.reviewPlans().dailyQueue(mixedPlan, prior);
      mixed.reviewPlans().record(mixedPlan, a, "简单", "mixed-a", 2, prior);
      mixed.reviewPlans().record(mixedPlan, b, "困难", "mixed-b", 3, prior);
      JSONObject mixedBefore = mixed.snapshot();
      mixed.reviewPlans().dailyQueue(mixedPlan, now);
      JSONObject expectedMixed = mixed.snapshot().getJSONObject("review").getJSONArray("days").getJSONObject(1);
      store.restore(mixedBefore, store.getGeneration()); long mixedGeneration = store.getGeneration();
      CommitResult mixedResult = store.execute(LibraryCommand.prepareDailyQueue(mixedGeneration, mixedPlan, now));
      check(mixedResult.isSuccess() && expectedMixed.toString().equals(mixedResult.getUpdatedEntity().getJSONArray("days").getJSONObject(0).toString()), "mixed due/new queue differs from domain");
      long mixedRevision = store.getRevision();
      check(store.execute(LibraryCommand.prepareDailyQueue(mixedGeneration, mixedPlan, now)).getStatus() == CommitResult.Status.IDEMPOTENT_IGNORED && store.getRevision() == mixedRevision, "mixed queue retry changed version");
      Library batch = new Library(); String batchNote = batch.createNotebook("批量计划", null);
      batch.saveResult(new JSONObject("{word:'batch',meanings:[{zh_definition:'批量'}]}"), new String[]{batchNote}, new JSONObject());
      int currentWeekday = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of("Asia/Shanghai")).getDayOfWeek().getValue();
      int otherWeekday = currentWeekday % 7 + 1;
      String offPlan = batch.reviewPlans().create("休息日", batchNote, 1, 1, new int[]{otherWeekday}, "Asia/Shanghai", false, now);
      String onPlan = batch.reviewPlans().create("学习日", batchNote, 1, 1, new int[]{currentWeekday}, "Asia/Shanghai", false, now);
      JSONObject batchBefore = batch.snapshot();
      batch.reviewPlans().dailyQueue(offPlan, now); batch.reviewPlans().dailyQueue(onPlan, now);
      JSONArray expectedDays = batch.snapshot().getJSONObject("review").getJSONArray("days");
      store.restore(batchBefore, store.getGeneration()); long batchGeneration = store.getGeneration(); long batchRevision = store.getRevision();
      android.database.sqlite.SQLiteDatabase batchFault = android.database.sqlite.SQLiteDatabase.openDatabase(path.getAbsolutePath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE);
      batchFault.execSQL("CREATE TRIGGER fail_second_day BEFORE INSERT ON review_days WHEN NEW.ordinal=1 BEGIN SELECT RAISE(ABORT, 'injected failure'); END"); batchFault.close();
      check(!store.execute(LibraryCommand.prepareDailyQueue(batchGeneration, null, now)).isSuccess(), "batch queue failure accepted");
      check(store.getRevision() == batchRevision && store.loadSnapshot().getRawDocument().getJSONObject("review").getJSONArray("days").length() == 0, "batch queue partially committed");
      batchFault = android.database.sqlite.SQLiteDatabase.openDatabase(path.getAbsolutePath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE);
      batchFault.execSQL("DROP TRIGGER fail_second_day"); batchFault.close();
      CommitResult batchResult = store.execute(LibraryCommand.prepareDailyQueue(batchGeneration, null, now));
      check(batchResult.isSuccess() && expectedDays.toString().equals(batchResult.getUpdatedEntity().getJSONArray("days").toString()), "batch scheduled/off-day queues differ from domain");
      File legacyDir = new File(getTargetContext().getCacheDir(), "legacy-test-" + System.nanoTime());
      check(legacyDir.mkdir(), "cannot create legacy test directory");
      Repository legacy = new Repository(legacyDir);
      legacy.save(lib);
      File migratedPath = new File(legacyDir, "migration.db");
      SqliteLibraryStore migrated = new SqliteLibraryStore(getTargetContext(), migratedPath.getAbsolutePath(), new File(legacyDir, "library.json"));
      migrated.initialize();
      check(migrated.loadSnapshot().getRawDocument().getJSONArray("vocabularies").length() == 1, "legacy migration lost vocabulary");
      check(new File(legacyDir, "library.json").isFile(), "legacy source removed");
      migrated.close();
      for (String suffix : new String[]{"", "-journal", "-wal", "-shm"}) new File(migratedPath.getAbsolutePath() + suffix).delete();
      File sourceFile = new File(legacyDir, "library.json");
      LibraryDatabase stagingDb = new LibraryDatabase(getTargetContext(), migratedPath.getAbsolutePath());
      stagingDb.getWritableDatabase().execSQL("CREATE TRIGGER fail_activation BEFORE UPDATE OF value ON store_meta WHEN OLD.key='active' AND NEW.value<>0 BEGIN SELECT RAISE(ABORT, 'injected failure'); END");
      stagingDb.close();
      SqliteLibraryStore interrupted = new SqliteLibraryStore(getTargetContext(), migratedPath.getAbsolutePath(), sourceFile);
      boolean activationRejected = false;
      try { interrupted.initialize(); } catch (Exception expectedFailure) { activationRejected = true; }
      check(activationRejected && sourceFile.isFile(), "failed migration activation damaged source");
      boolean refusedEmptyExport = false;
      try { interrupted.exportJson(new java.io.ByteArrayOutputStream()); } catch (Exception expectedFailure) { refusedEmptyExport = true; }
      check(refusedEmptyExport, "failed migration exported an empty library"); interrupted.close();
      stagingDb = new LibraryDatabase(getTargetContext(), migratedPath.getAbsolutePath());
      stagingDb.getWritableDatabase().execSQL("DROP TRIGGER fail_activation"); stagingDb.close();
      interrupted = new SqliteLibraryStore(getTargetContext(), migratedPath.getAbsolutePath(), sourceFile);
      interrupted.initialize();
      check(interrupted.loadSnapshot().getRawDocument().getJSONArray("vocabularies").length() == 1, "migration retry after interrupted activation failed");
      interrupted.close();
      for (String suffix : new String[]{"", "-journal", "-wal", "-shm"}) new File(migratedPath.getAbsolutePath() + suffix).delete();
      try (java.io.FileOutputStream corrupt = new java.io.FileOutputStream(sourceFile)) { corrupt.write("invalid-json".getBytes("UTF-8")); }
      for (int attempt = 0; attempt < 2; attempt++) {
        SqliteLibraryStore invalid = new SqliteLibraryStore(getTargetContext(), migratedPath.getAbsolutePath(), sourceFile);
        boolean rejected = false;
        try { invalid.initialize(); } catch (Exception expectedFailure) { rejected = true; } finally { invalid.close(); }
        check(rejected, "corrupt legacy was replaced with empty data");
      }
      check(sourceFile.length() == "invalid-json".length(), "corrupt source changed");
      for (String suffix : new String[]{"", "-journal", "-wal", "-shm"}) new File(migratedPath.getAbsolutePath() + suffix).delete();
      sourceFile.delete(); legacyDir.delete();
      report.putString("stream", "\nPASS: SQLite migration, score parity, event replay, reopen and rollback\n");
    } catch (Throwable error) {
      outcome = Activity.RESULT_CANCELED; report.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(error));
    } finally {
      if (store != null) store.close();
      for (String suffix : new String[]{"", "-journal", "-wal", "-shm"}) new File(path.getAbsolutePath() + suffix).delete();
    }
    finish(outcome, report);
  }
  private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
