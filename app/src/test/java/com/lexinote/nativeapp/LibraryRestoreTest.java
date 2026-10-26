package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

public class LibraryRestoreTest {
  private void rejected(JSONObject value) {
    try {
      Library.validate(value);
      fail("invalid backup accepted");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void rejectsMalformedSessionArrays() throws Exception {
    JSONObject b = new Library().backup();
    b.put("conversations", new JSONArray().put("wrong"));
    rejected(b);
  }

  @Test
  public void rejectsUnknownVersion() throws Exception {
    JSONObject b = new Library().backup();
    b.put("version", "99");
    rejected(b);
  }

  @Test
  public void rejectsChildrenTypeMismatch() throws Exception {
    JSONObject b = new Library().backup();
    b.getJSONArray("notebooks").getJSONObject(0).put("children_type", "alien");
    rejected(b);
  }

  @Test
  public void acceptsLegacyMeaningWithoutDefinitionsAndPreservesItsFields() throws Exception {
    Library l = new Library();
    JSONObject b = l.backup();
    JSONObject legacy =
        new JSONObject(
            "{id:'v',content:'x',type:'word',created_at:'2020-01-01T00:00:00Z',meanings:[{id:'m',created_at:'2020-01-01T00:00:00Z',source_images:['file:a'],annotations:[{id:'a',content:'legacy"
                + " note'}]}]}");
    b.getJSONArray("vocabularies").put(legacy);

    Library restored = new Library(b);
    JSONObject meaning =
        restored.find("vocabularies", "v").getJSONArray("meanings").getJSONObject(0);
    assertFalse(meaning.has("zh_definition"));
    assertFalse(meaning.has("en_definition"));
    assertEquals("file:a", meaning.getJSONArray("source_images").getString(0));
    assertEquals(
        "legacy note", meaning.getJSONArray("annotations").getJSONObject(0).getString("content"));
  }

  @Test
  public void acceptsLegacyVocabularyWithNoMeanings() throws Exception {
    JSONObject b = new Library().backup();
    b.getJSONArray("vocabularies")
        .put(new JSONObject("{id:'v',content:'x',type:'word',meanings:[]}"));

    Library restored = new Library(b);
    assertEquals(0, restored.find("vocabularies", "v").getJSONArray("meanings").length());
  }

  @Test
  public void stillRejectsMeaningWithoutAnId() throws Exception {
    JSONObject b = new Library().backup();
    b.getJSONArray("vocabularies")
        .put(new JSONObject("{id:'v',content:'x',type:'word',meanings:[{}]}"));
    rejected(b);
  }

  @Test
  public void domainProtectsDefaultNotebook() throws Exception {
    Library l = new Library();
    String id = l.settings().getString("default_notebook_id");
    try {
      l.deleteNotebook(id);
      fail("default deleted");
    } catch (IllegalArgumentException expected) {
    }
    assertNotNull(l.find("notebooks", id));
  }

  @Test
  public void snapshotsAreNotChangedByFutureConversationMutations() throws Exception {
    Library l = new Library();
    JSONObject s = l.newSession("a");
    l.touchSession(s, true);
    s.put("title", "changed");
    assertEquals("a", l.array("recent_queries").getJSONObject(0).getString("title"));
    assertEquals("a", l.array("conversations").getJSONObject(0).getString("title"));
  }
}
