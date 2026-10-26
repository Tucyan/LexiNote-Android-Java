package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

public class LibrarySystemRootsTest {
  @Test
  public void restoreRepairsRealGlobalIndex() throws Exception {
    Library l = new Library();
    JSONObject b = l.backup();
    JSONObject global =
        new JSONObject(
            "{id:'nb_global_root',name:'全部词汇',is_global_root:true,children_type:'vocabulary',child_notebook_ids:[],vocabulary_ids:['stale']}");
    for (int i = b.getJSONArray("notebooks").length() - 1; i >= 0; i--)
      if (b.getJSONArray("notebooks").getJSONObject(i).getString("id").equals("nb_global_root"))
        b.getJSONArray("notebooks").remove(i);
    b.getJSONArray("notebooks").put(global);
    Library restored = new Library(b);
    assertEquals(
        0, restored.find("notebooks", "nb_global_root").getJSONArray("vocabulary_ids").length());
  }

  @Test
  public void createsSystemRootsAndRepairsDefaultSetting() throws Exception {
    JSONObject b =
        new JSONObject(
            "{version:'1.1',vocabularies:[],notebooks:[],settings:{default_notebook_id:'missing'}}");
    Library l = new Library(b);
    assertNotNull(l.find("notebooks", "nb_default"));
    assertNotNull(l.find("notebooks", "nb_global_root"));
    assertEquals("nb_default", l.settings().getString("default_notebook_id"));
  }

  @Test
  public void fakeGlobalCannotBypassReferenceValidation() throws Exception {
    JSONObject b = new Library().backup();
    b.getJSONArray("notebooks")
        .put(
            new JSONObject(
                "{id:'fake',name:'fake',is_global_root:true,children_type:'vocabulary',child_notebook_ids:[],vocabulary_ids:['stale']}"));
    try {
      new Library(b);
      fail("fake global bypassed checks");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void systemRootsCannotHaveChildNotebooks() throws Exception {
    Library l = new Library();
    try {
      l.createNotebook("bad", l.settings().getString("default_notebook_id"));
      fail("system default accepted subnotebook");
    } catch (IllegalArgumentException expected) {
    }
  }
}
