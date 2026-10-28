package com.lexinote.nativeapp;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class SessionRetentionTest {
 @Test public void localQueriesDoNotEvictTemporaryAndHistoryUsesTime() throws Exception {
  Library l=new Library();
  for(int i=5;i>=1;i--){ JSONObject s=l.newSession("ai"+i); Library.put(s,"id","ai"+i); Library.promoteTemporary(s); Library.put(s.getJSONObject("metadata"),"last_used_at","2026-01-0"+i+"T00:00:00Z"); l.array("conversations").put(s); }
  for(int i=10;i>=1;i--){ JSONObject s=l.newSession("q"+i); Library.put(s,"id","q"+i); Library.put(s,"created_at",String.format("2026-01-%02dT00:00:00Z",i)); l.array("recent_queries").put(s); }
  JSONObject local=l.newSession("local"); l.touchSession(local,true);
  assertNotNull(l.find("conversations","ai1")); assertEquals(10,l.array("recent_queries").length()); assertNull(l.find("recent_queries","q1")); assertNotNull(l.find("recent_queries","q10"));
 }
 @Test public void temporaryLimitSavedLegacyAndStableTies() throws Exception {
  Library l=new Library();
  for(int i=6;i>=1;i--) { JSONObject s=l.newSession("ai"); Library.put(s,"id","t"+i); Library.promoteTemporary(s); Library.put(s.getJSONObject("metadata"),"last_used_at","2026-01-01T00:00:00Z"); l.array("conversations").put(s); }
  JSONObject saved=l.newSession("saved"); Library.put(saved,"saved",true); l.array("conversations").put(saved);
  JSONObject legacy=l.newSession("old"); Library.put(legacy,"id","legacy"); legacy.remove("metadata"); l.array("recent_queries").put(Library.copy(legacy)); l.array("conversations").put(legacy);
  JSONObject current=l.newSession("query"); l.touchSession(current,true);
  assertNull(l.find("conversations","t1")); assertNotNull(l.find("conversations","t6")); assertNotNull(l.find("conversations",saved.optString("id"))); assertNotNull(l.find("conversations","legacy"));
  Library restored=new Library(l.backup()); assertNotNull(restored.find("conversations","t6"));
  JSONArray invalid=new JSONArray(); JSONObject a=new JSONObject(), b=new JSONObject(); Library.put(a,"id","a"); Library.put(b,"id","b"); invalid.put(a); invalid.put(b);
  assertEquals("b",Library.sortedSessions(invalid,true).getJSONObject(0).getString("id"));
 }
 @Test public void legacyMislabelAndHistoryDeletion() throws Exception {
  Library l=new Library(); JSONObject old=l.newSession("old"); Library.put(old.getJSONObject("metadata"),"retention","temporary");
  assertFalse(Library.isTemporary(old)); l.touchSession(old,true); l.touchSession(old,false); assertNotNull(l.find("conversations",old.optString("id")));
  l.delete("recent_queries",old.optString("id")); assertNull(l.find("conversations",old.optString("id")));
  JSONObject active=l.newSession("active"); Library.promoteTemporary(active); assertTrue(Library.isTemporary(active)); l.touchSession(active,false); assertNotNull(l.find("conversations",active.optString("id")));
 }
 @Test public void normalizedMetadataSurvivesBackupAndReload() throws Exception {
  Library l=new Library(); JSONObject old=l.newSession("old"); Library.put(old.getJSONObject("metadata"),"retention","temporary"); Library.put(old.getJSONObject("metadata"),"custom","keep"); l.touchSession(old,true);
  Library restored=new Library(l.backup()); JSONObject loaded=restored.find("conversations",old.optString("id"));
  assertEquals("query",loaded.getJSONObject("metadata").getString("retention")); assertEquals("keep",loaded.getJSONObject("metadata").getString("custom"));
  JSONObject legacy=l.newSession("legacy"); legacy.remove("metadata"); l.touchSession(legacy,true);
  assertEquals("query",new Library(l.backup()).find("conversations",legacy.optString("id")).getJSONObject("metadata").getString("retention"));
 }
}
