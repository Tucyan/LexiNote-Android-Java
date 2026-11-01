package com.lexinote.nativeapp;
import static org.junit.Assert.*;
import java.time.Instant;
import org.json.*;
import org.junit.Test;
public class ReviewQueueAvailabilityTest {
 @Test public void availabilityIsReadOnlyAndSurvivesRestore() throws Exception {
  Library lib=new Library();
  String note=lib.createNotebook("工作阅读",null);
  lib.saveResult(new JSONObject("{word:'budget',meanings:[{zh_definition:'预算'}]}"),new String[]{note},new JSONObject());
  long now=Instant.parse("2026-11-01T02:00:00Z").toEpochMilli();
  String id=lib.reviewPlans().create("每日",note,20,50,new int[]{1,2,3,4,5,6,7},"Asia/Singapore",false,now);
  String before=lib.snapshot().toString();
  assertFalse(lib.reviewPlans().hasDailyQueue(id,now));
  assertEquals(before,lib.snapshot().toString());
  assertEquals(1,lib.reviewPlans().dailyQueue(id,now).getJSONArray("queue").length());
  assertTrue(lib.reviewPlans().hasDailyQueue(id,now));
  Library restored=new Library(lib.snapshot());
  assertTrue(restored.reviewPlans().hasDailyQueue(id,now));
  assertFalse(restored.reviewPlans().hasDailyQueue(id,now+86400000L));
 }
 @Test public void queuesReflectLiveNotebookMembership() throws Exception {
  Library lib=new Library();String note=lib.createNotebook("阅读",null);
  String first=lib.saveResult(new JSONObject("{word:'budget',meanings:[{zh_definition:'预算'}]}"),new String[]{note},new JSONObject());
  long now=Instant.parse("2026-11-01T02:00:00Z").toEpochMilli();
  String plan=lib.reviewPlans().create("每日",note,20,50,new int[]{1,2,3,4,5,6,7},"Asia/Singapore",false,now);
  assertEquals(1,lib.reviewPlans().dailyQueue(plan,now).getJSONArray("queue").length());
  lib.find("notebooks",note).put("vocabulary_ids",new JSONArray());
  assertEquals(0,lib.reviewPlans().dailyQueue(plan,now).getJSONArray("queue").length());
  lib.find("notebooks",note).put("vocabulary_ids",new JSONArray().put(first));
  assertEquals(1,lib.reviewPlans().dailyQueue(plan,now).getJSONArray("queue").length());
 }
}
