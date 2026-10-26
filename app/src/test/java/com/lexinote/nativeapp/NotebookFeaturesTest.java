package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import java.util.*;
import org.json.*;
import org.junit.Test;

public class NotebookFeaturesTest {
  private JSONObject result(String json) throws Exception {
    return new JSONObject(json);
  }

  @Test
  public void movingAndCopyingReferencesPreservesPresentationAndSharedEntity() throws Exception {
    Library lib = new Library();
    String a = lib.createNotebook("A", null),
        b = lib.createNotebook("B", null),
        c = lib.createNotebook("C", null);
    JSONObject display = new JSONObject("{fields:{example:false},meaning_ids:['draft']}");
    JSONObject r = result("{word:'move me',meanings:[{id:'draft',zh_definition:'移动'}]}");
    String vocabId = lib.saveResult(r, new String[] {a}, display);
    lib.batchReferences(a, new String[] {vocabId}, new String[] {b}, true);
    assertTrue(has(lib.find("notebooks", a).getJSONArray("vocabulary_ids"), vocabId));
    assertTrue(has(lib.find("notebooks", b).getJSONArray("vocabulary_ids"), vocabId));
    assertEquals(
        "移动",
        lib.find("vocabularies", vocabId)
            .getJSONArray("meanings")
            .getJSONObject(0)
            .getString("zh_definition"));
    lib.batchReferences(a, new String[] {vocabId}, new String[] {c}, false);
    assertFalse(has(lib.find("notebooks", a).getJSONArray("vocabulary_ids"), vocabId));
    assertTrue(has(lib.find("notebooks", c).getJSONArray("vocabulary_ids"), vocabId));
    assertEquals(
        false,
        lib.find("notebooks", c)
            .getJSONObject("vocabulary_display")
            .getJSONObject(vocabId)
            .getJSONObject("fields")
            .getBoolean("example"));
    Library.validate(lib.snapshot());
  }

  @Test
  public void removeReferenceDoesNotDeleteEntityButGlobalDeleteRemovesAllRefs() throws Exception {
    Library lib = new Library();
    String a = lib.createNotebook("A", null), b = lib.createNotebook("B", null);
    String id =
        lib.saveResult(
            result("{word:'shared',meanings:[{zh_definition:'共享'}]}"),
            new String[] {a, b},
            new JSONObject());
    lib.removeReferences(a, new String[] {id});
    assertNotNull(lib.find("vocabularies", id));
    assertTrue(has(lib.find("notebooks", b).getJSONArray("vocabulary_ids"), id));
    lib.deleteVocabularyEverywhere(new String[] {id});
    assertNull(lib.find("vocabularies", id));
    assertFalse(has(lib.find("notebooks", b).getJSONArray("vocabulary_ids"), id));
    Library.validate(lib.snapshot());
  }

  @Test
  public void globalViewCanCopyReferencesWithoutChangingGlobalMembership() throws Exception {
    Library lib = new Library();
    String destination = lib.createNotebook("Destination", null);
    String id =
        lib.saveResult(
            result("{word:'global copy',meanings:[{zh_definition:'全局'}]}"),
            new String[] {lib.settings().optString("default_notebook_id")},
            new JSONObject());
    JSONObject global = lib.find("notebooks", "nb_global_root");
    lib.batchReferences("nb_global_root", new String[] {id}, new String[] {destination}, true);
    assertTrue(has(global.getJSONArray("vocabulary_ids"), id));
    assertTrue(has(lib.find("notebooks", destination).getJSONArray("vocabulary_ids"), id));
    assertNotNull(lib.find("vocabularies", id));
    Library.validate(lib.snapshot());
  }

  @Test
  public void defaultNotebookCanLoseReferenceWithoutDeletingSharedVocabulary() throws Exception {
    Library lib = new Library();
    String defaultId = lib.settings().optString("default_notebook_id");
    String other = lib.createNotebook("Other", null);
    String id =
        lib.saveResult(
            result("{word:'default ref',meanings:[{zh_definition:'保留实体'}]}"),
            new String[] {defaultId, other},
            new JSONObject());
    lib.removeReferences(defaultId, new String[] {id});
    assertFalse(has(lib.find("notebooks", defaultId).getJSONArray("vocabulary_ids"), id));
    assertTrue(has(lib.find("notebooks", other).getJSONArray("vocabulary_ids"), id));
    assertNotNull(lib.find("vocabularies", id));
    Library.validate(lib.snapshot());
  }

  @Test
  public void copyingOverExistingReferenceDoesNotReplaceItsDisplay() throws Exception {
    Library lib = new Library();
    String source = lib.createNotebook("Source", null),
        target = lib.createNotebook("Target", null),
        other = lib.createNotebook("Other", null);
    String id =
        lib.saveResult(
            result("{word:'views',meanings:[{zh_definition:'源'}]}"),
            new String[] {source, other},
            new JSONObject());
    JSONObject sourceConfig = new JSONObject("{fields:{example:false},meaning_ids:[]}"),
        targetConfig = new JSONObject("{fields:{example:true},meaning_ids:['mine']}");
    JSONObject sourceNote = lib.find("notebooks", source),
        targetNote = lib.find("notebooks", target),
        otherNote = lib.find("notebooks", other);
    sourceNote.getJSONObject("vocabulary_display").put(id, sourceConfig);
    targetNote.getJSONArray("vocabulary_ids").put(id);
    targetNote.getJSONObject("vocabulary_display").put(id, targetConfig);
    JSONObject otherBefore =
        Library.copy(otherNote.getJSONObject("vocabulary_display").getJSONObject(id));
    lib.batchReferences(source, new String[] {id}, new String[] {target}, true);
    assertEquals(
        targetConfig.toString(),
        targetNote.getJSONObject("vocabulary_display").getJSONObject(id).toString());
    lib.batchReferences(source, new String[] {id}, new String[] {other}, true);
    assertEquals(
        otherBefore.toString(),
        otherNote.getJSONObject("vocabulary_display").getJSONObject(id).toString());
  }

  @Test
  public void explicitQuerySaveOverwritesTargetPresentationButLeavesOtherNotebookUnchanged()
      throws Exception {
    Library lib = new Library();
    String target = lib.createNotebook("Target", null), other = lib.createNotebook("Other", null);
    String id =
        lib.saveResult(
            result("{word:'explicit',meanings:[{id:'draft',zh_definition:'词义'}]}"),
            new String[] {target, other},
            new JSONObject("{fields:{example:true}}"));
    JSONObject otherDisplay =
        Library.copy(
            lib.find("notebooks", other).getJSONObject("vocabulary_display").getJSONObject(id));
    lib.saveResult(
        result(
            "{word:'explicit',meanings:[{id:'draft-2',zh_definition:'词义',selected:true,save_example:false}]}"),
        new String[] {target},
        new JSONObject("{fields:{example:true}}"));
    String actual =
        lib.find("vocabularies", id).getJSONArray("meanings").getJSONObject(0).getString("id");
    assertFalse(
        lib.find("notebooks", target)
            .getJSONObject("vocabulary_display")
            .getJSONObject(id)
            .getJSONObject("meaning_fields")
            .getJSONObject(actual)
            .getBoolean("example"));
    assertEquals(
        otherDisplay.toString(),
        lib.find("notebooks", other)
            .getJSONObject("vocabulary_display")
            .getJSONObject(id)
            .toString());
  }

  @Test
  public void queryDraftSelectionsMapToEntityMeaningIdsWithoutDroppingSource() throws Exception {
    Library lib = new Library();
    String notebook = lib.settings().optString("default_notebook_id");
    JSONObject r =
        result(
            "{word:'drafts',meanings:[{id:'m1',zh_definition:'甲',en_definition:'one',example:'one"
                + " ex',selected:true,save_zh:true,save_en:false,save_example:false},{id:'m2',zh_definition:'乙',selected:false}]}");
    String id = lib.saveResult(r, new String[] {notebook}, new JSONObject());
    JSONObject v = lib.find("vocabularies", id),
        config =
            lib.find("notebooks", notebook).getJSONObject("vocabulary_display").getJSONObject(id);
    assertEquals(1, config.getJSONArray("meaning_ids").length());
    String actual = config.getJSONArray("meaning_ids").getString(0);
    assertNotEquals("m1", actual);
    assertEquals(actual, v.getJSONArray("meanings").getJSONObject(0).getString("id"));
    assertEquals("乙", v.getJSONArray("meanings").getJSONObject(1).getString("zh_definition"));
    assertFalse(config.getJSONObject("meaning_fields").getJSONObject(actual).getBoolean("example"));
  }

  @Test
  public void markdownHonorsDisplayAndEscapesContent() throws Exception {
    JSONObject v =
        result(
            "{content:'a*b#',tags:['a`b'],meanings:[{id:'m',part_of_speech:'n',zh_definition:'[x] >"
                + " ! |',en_definition:'hidden',example:'line\\n"
                + "next',annotations:[{target_text:'x*y',content:'note'}]}]}");
    JSONObject display =
        result(
            "{fields:{zh_definition:true,en_definition:true,example:true,annotations:true},meaning_fields:{m:{en_definition:false}},meaning_ids:['m']}");
    String markdown = NotebookExport.markdown(Collections.singletonList(v), display);
    assertTrue(markdown.contains("a\\*b\\#"));
    assertTrue(markdown.contains("\\[x\\] \\> \\! \\|"));
    assertFalse(markdown.contains("hidden"));
    assertTrue(markdown.contains("x\\*y"));
    assertTrue(markdown.contains("``#a`b``"));
  }

  @Test
  public void filteredSelectionIsPrunedAndCannotEscapeVisibleScope() {
    LinkedHashSet<String> selected = new LinkedHashSet<>(Arrays.asList("visible", "hidden"));
    assertFalse(NotebookSelection.isWithinScope(selected, Arrays.asList("visible")));
    NotebookSelection.pruneToScope(selected, Arrays.asList("visible"));
    assertEquals(Collections.singleton("visible"), selected);
    assertTrue(NotebookSelection.isWithinScope(selected, Arrays.asList("visible")));
  }

  @Test
  public void annotationContextActionOnlyHandlesItsGeneratedMenuId() {
    assertTrue(AnnotationActionIds.isAnnotationAction(7241, 7241));
    assertFalse(AnnotationActionIds.isAnnotationAction(1, 7241));
    assertFalse(AnnotationActionIds.isAnnotationAction(0, 0));
  }

  @Test
  public void annotationRetainsFieldTextAndMeaningProvenanceOnEdit() throws Exception {
    JSONObject m = result("{id:'meaning-1',annotations:[]}");
    JSONObject a = Annotations.add(m, "example", "take off", "separable phrasal verb");
    String id = a.getString("id");
    Annotations.edit(m, id, "en_definition", "to leave", "updated");
    a = m.getJSONArray("annotations").getJSONObject(0);
    assertEquals("meaning-1", a.getString("meaning_id"));
    assertEquals("en_definition", a.getString("target_field"));
    assertEquals("to leave", a.getString("target_text"));
    assertEquals("updated", a.getString("content"));
    Annotations.remove(m, id);
    assertEquals(0, m.getJSONArray("annotations").length());
  }

  private static boolean has(JSONArray ids, String id) {
    for (int i = 0; i < ids.length(); i++) if (id.equals(ids.optString(i))) return true;
    return false;
  }
}
