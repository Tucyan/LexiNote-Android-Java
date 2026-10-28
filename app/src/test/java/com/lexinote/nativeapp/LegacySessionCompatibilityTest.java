package com.lexinote.nativeapp;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class LegacySessionCompatibilityTest {
  JSONObject legacy(String id) throws Exception {
    return new JSONObject().put("id",id).put("title","旧版自定义标题")
        .put("input_text","input fallback").put("created_at","2026-10-27T01:00:00Z")
        .put("ai_messages",new JSONArray().put(new JSONObject().put("role","user").put("content","lookup")));
  }
  @Test public void normalizationPreservesSavedTitleAndMetadata() throws Exception {
    Library base=new Library(); JSONObject saved=legacy("saved").put("saved",true);
    base.array("conversations").put(saved);
    Library loaded=new Library(base.backup());
    JSONObject meta=loaded.find("conversations","saved").getJSONObject("metadata");
    assertEquals("saved",meta.optString("retention"));
    assertEquals("旧版自定义标题",meta.optString("title"));
    assertEquals("user",meta.optString("title_source"));
  }
  @Test public void oldHistoryWithUserMessageStillUsesQueryRetention() throws Exception {
    Library base=new Library(); JSONObject old=legacy("old");
    base.array("recent_queries").put(Library.copy(old)); base.array("conversations").put(old);
    Library loaded=new Library(base.backup());
    assertEquals("query",loaded.find("conversations","old").getJSONObject("metadata").optString("retention"));
    loaded.touchSession(loaded.newSession("budget"),true);
    assertNotNull(loaded.find("conversations","old"));
    loaded.delete("recent_queries","old");
    assertNull(loaded.find("conversations","old"));
  }
  @Test public void untypedConversationOutsideHistoryKeepsAssistantMessages() throws Exception {
    Library base=new Library(); JSONObject old=legacy("chat");
    old.getJSONArray("ai_messages").getJSONObject(0).put("role","assistant");
    base.array("conversations").put(old);
    Library loaded=new Library(base.backup());
    loaded.touchSession(loaded.newSession("budget"),true);
    assertNotNull(loaded.find("conversations","chat"));
    assertTrue(Library.isTemporary(loaded.find("conversations","chat")));
  }
}
