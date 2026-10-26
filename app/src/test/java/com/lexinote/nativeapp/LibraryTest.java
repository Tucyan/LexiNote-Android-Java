package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

public class LibraryTest {
  @Test
  public void sharedVocabularyAndUniformChildren() throws Exception {
    Library l = new Library();
    String a = l.createNotebook("A", null), b = l.createNotebook("B", null);
    JSONObject r = new JSONObject("{word:'hello',type:'word',meanings:[{zh_definition:'你好'}]}");
    String id = l.saveResult(r, new String[] {a, b}, new JSONObject());
    assertEquals(id, l.saveResult(r, new String[] {a}, new JSONObject()));
    assertEquals(1, l.array("vocabularies").length());
    try {
      l.createNotebook("bad", a);
      fail("mixed children accepted");
    } catch (IllegalArgumentException expected) {
    }
    l.deleteNotebook(a);
    assertNotNull(l.find("vocabularies", id));
  }

  @Test
  public void boundedHistoryAndTemporaryConversations() throws Exception {
    Library l = new Library();
    for (int i = 0; i < 12; i++) {
      JSONObject s = l.newSession("q" + i);
      l.touchSession(s, true);
    }
    assertEquals(10, l.array("recent_queries").length());
    assertEquals(5, l.array("conversations").length());
  }

  @Test
  public void restoreRejectsBrokenReferencesAndCycles() throws Exception {
    Library l = new Library();
    l.createNotebook("ordinary", null);
    JSONObject backup = l.backup();
    backup.getJSONArray("notebooks").getJSONObject(0).getJSONArray("vocabulary_ids").put("missing");
    try {
      Library.validate(backup);
      fail("broken ref accepted");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void backupRedactsSecrets() throws Exception {
    Library l = new Library();
    l.settings().put("api_key", "secret");
    assertFalse(l.backup().toString().contains("secret"));
  }
}
