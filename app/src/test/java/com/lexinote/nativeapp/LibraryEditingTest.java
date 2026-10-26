package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

public class LibraryEditingTest {
  @Test
  public void renameRejectsNormalizedDuplicate() throws Exception {
    Library l = new Library();
    String n = l.settings().getString("default_notebook_id");
    String a =
        l.saveResult(
            new JSONObject("{word:'alpha',type:'word',meanings:[{zh_definition:'一'}]}"),
            new String[] {n},
            new JSONObject());
    l.saveResult(
        new JSONObject("{word:'beta',type:'word',meanings:[{zh_definition:'二'}]}"),
        new String[] {n},
        new JSONObject());
    try {
      l.renameVocabulary(a, " BETA ");
      fail("duplicate rename accepted");
    } catch (IllegalArgumentException expected) {
    }
    assertEquals("alpha", l.find("vocabularies", a).getString("content"));
  }

  @Test
  public void lookupCollapsesWhitespaceAfterUnicodeNormalization() throws Exception {
    Library l = new Library();
    String n = l.settings().getString("default_notebook_id");
    String id =
        l.saveResult(
            new JSONObject("{word:'take off',type:'phrase',meanings:[{zh_definition:'起飞'}]}"),
            new String[] {n},
            new JSONObject());

    assertEquals(
        id,
        l.saveResult(
            new JSONObject("{word:' take  off ',type:'phrase',meanings:[{zh_definition:'起飞'}]}"),
            new String[] {n},
            new JSONObject()));
    assertEquals(
        id,
        l.saveResult(
            new JSONObject("{word:'ＴＡＫＥ　ＯＦＦ',type:'phrase',meanings:[{zh_definition:'起飞'}]}"),
            new String[] {n},
            new JSONObject()));
    assertEquals(1, l.array("vocabularies").length());
  }

  @Test
  public void refusesEmptyMeaningBeforeMutation() throws Exception {
    Library l = new Library();
    String n = l.settings().getString("default_notebook_id");
    try {
      l.saveResult(
          new JSONObject("{word:'empty',type:'word',meanings:[{}]}"),
          new String[] {n},
          new JSONObject());
      fail("empty definition accepted");
    } catch (IllegalArgumentException expected) {
    }
    assertEquals(0, l.array("vocabularies").length());
  }

  @Test
  public void removesObjectAnnotations() throws Exception {
    JSONArray a = new JSONArray("[{id:'a',content:'one'},{id:'b',content:'two'}]");
    Library.removeValue(a, "a");
    assertEquals(1, a.length());
    assertEquals("b", a.getJSONObject(0).getString("id"));
  }
}
