package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

public class LibraryIntegrityTest {
  @Test
  public void mergePreservesExistingAndIncomingImages() throws Exception {
    Library l = new Library();
    String n = l.array("notebooks").getJSONObject(0).getString("id");
    JSONObject first =
        new JSONObject(
            "{word:'run',type:'word',meanings:[{zh_definition:'跑',source_images:['file:a'],example:'I"
                + " run.'}]}");
    String id = l.saveResult(first, new String[] {n}, new JSONObject());
    JSONObject next =
        new JSONObject(
            "{word:'run',type:'word',meanings:[{zh_definition:'跑',source_images:['file:b'],remarks:'movement'}]}");
    l.saveResult(next, new String[] {n}, new JSONObject());
    JSONObject m = l.find("vocabularies", id).getJSONArray("meanings").getJSONObject(0);
    assertEquals(2, m.getJSONArray("source_images").length());
    assertEquals("I run.", m.getString("example"));
    assertEquals("movement", m.getString("remarks"));
  }

  @Test
  public void repeatedLookupFillsVocabularyFieldsAndMergesTags() throws Exception {
    Library l = new Library();
    String n = l.settings().optString("default_notebook_id");
    JSONObject first =
        new JSONObject(
            "{word:'hello',type:'word',tags:['existing'],meanings:[{zh_definition:'你好',source_images:['file:a'],annotations:[{id:'a',content:'note'}]}]}");
    String id = l.saveResult(first, new String[] {n}, new JSONObject());
    JSONObject next =
        new JSONObject(
            "{word:'hello',type:'word',phonetic:'/həˈləʊ/',tags:['new','existing'],meanings:[{zh_definition:'你好',source_images:['file:b']}]}");

    l.saveResult(next, new String[] {n}, new JSONObject());

    JSONObject vocabulary = l.find("vocabularies", id);
    assertEquals("/həˈləʊ/", vocabulary.getString("phonetic"));
    assertEquals(2, vocabulary.getJSONArray("tags").length());
    assertEquals("existing", vocabulary.getJSONArray("tags").getString(0));
    assertEquals("new", vocabulary.getJSONArray("tags").getString(1));
    JSONObject meaning = vocabulary.getJSONArray("meanings").getJSONObject(0);
    assertEquals(2, meaning.getJSONArray("source_images").length());
    assertEquals("note", meaning.getJSONArray("annotations").getJSONObject(0).getString("content"));
  }

  @Test
  public void savedLegacyConversationIsNotEvicted() throws Exception {
    Library l = new Library();
    JSONObject saved = l.newSession("saved");
    saved.put("metadata", new JSONObject("{retention:'saved',title:'saved'}"));
    l.touchSession(saved, false);
    for (int i = 0; i < 8; i++) l.touchSession(l.newSession("temp" + i), false);
    assertNotNull(l.find("conversations", saved.getString("id")));
    assertEquals(6, l.array("conversations").length());
  }

  @Test
  public void rejectsParentNotListingChild() throws Exception {
    Library l = new Library();
    String p = l.createNotebook("parent", null), c = l.createNotebook("child", p);
    l.find("notebooks", p).getJSONArray("child_notebook_ids").remove(0);
    try {
      Library.validate(l.snapshot());
      fail("orphan parent relation accepted");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void rejectsCycle() throws Exception {
    Library l = new Library();
    String p = l.createNotebook("p", null), c = l.createNotebook("c", p);
    JSONObject n = l.find("notebooks", p);
    n.put("parent_id", c);
    l.find("notebooks", c).getJSONArray("child_notebook_ids").put(p);
    try {
      Library.validate(l.snapshot());
      fail("cycle accepted");
    } catch (IllegalArgumentException expected) {
    }
  }
}
