package com.lexinote.nativeapp;

import static org.junit.Assert.*;
import org.json.*;
import org.junit.Test;

public class NotebookSearchIndexTest {
  @Test public void projectionKeepsFieldsSeparateAndMatchesOriginal() throws Exception {
    JSONObject v = new JSONObject("{id:'1',content:'ＡＢＣ',tags:['study'],meanings:[{zh_definition:'hello',en_definition:'world',example:'two　 words',annotations:[{content:'note',target_text:'fragment'}]}]}");
    NotebookSearchIndex index = new NotebookSearchIndex();
    for (String query : new String[]{"abc", "STUDY", "hello", "world", "two words", "note", "fragment", "helloworld", "", "missing"})
      assertEquals(query, NotebookSearch.matches(v, query), index.matches(v, NotebookSearch.norm(query)));
    assertFalse(index.matches(v, NotebookSearch.norm("helloworld")));
  }

  @Test public void snapshotIsIndependentOfLaterEdits() throws Exception {
    JSONObject v = new JSONObject("{id:'1',content:'before'}");
    NotebookSearchIndex index = new NotebookSearchIndex();
    NotebookSearchIndex.Projection snapshot = index.snapshot(v);
    v.put("content", "after");
    assertTrue(snapshot.matches("before"));
    assertFalse(snapshot.matches("after"));
    assertTrue(index.snapshot(v).matches("after"));
  }

  @Test public void changedNestedFieldsAndReplacedEntitiesInvalidateProjection() throws Exception {
    JSONObject v = new JSONObject("{id:'1',content:'first',tags:['old'],meanings:[{annotations:[{content:'before'}]}]}");
    NotebookSearchIndex index = new NotebookSearchIndex();
    assertTrue(index.matches(v, "before"));
    v.getJSONArray("meanings").getJSONObject(0).getJSONArray("annotations").getJSONObject(0).put("content", "after");
    assertFalse(index.matches(v, "before"));
    assertTrue(index.matches(v, "after"));
    v.getJSONArray("tags").put(0, "new-tag");
    assertFalse(index.matches(v, "old"));
    assertTrue(index.matches(v, "new-tag"));
    JSONObject replacement = new JSONObject("{id:'1',content:'replacement'}");
    assertFalse(index.matches(replacement, "after"));
    assertTrue(index.matches(replacement, "replacement"));
  }
}
