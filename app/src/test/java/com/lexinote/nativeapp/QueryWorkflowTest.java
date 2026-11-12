package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

public class QueryWorkflowTest {
  private JSONObject result(String word) throws Exception {
    return new JSONObject(
        "{\"word\":\""
            + word
            + "\",\"type\":\"word\",\"meanings\":[{\"id\":\"m1\",\"zh_definition\":\"有韧性的\",\"en_definition\":\"able"
            + " to recover\",\"example\":\"Stay resilient.\"}]}");
  }

  @Test
  public void autoRecordRequiresEnabledValidatedReadyResult() throws Exception {
    JSONObject settings = new JSONObject().put("auto_record_extra_info", true);
    JSONObject session =
        new JSONObject()
            .put("id", "s")
            .put("revision", 1)
            .put("session_state", "ready")
            .put("query_results", result("resilient"));
    assertTrue(QueryWorkflow.shouldAutoRecord(session, settings));
    assertFalse(QueryWorkflow.shouldAutoRecord(session, new JSONObject()));
    session.put("pending_lookup", new JSONObject().put("lookup", new JSONObject()));
    assertFalse(QueryWorkflow.shouldAutoRecord(session, settings));
    session.remove("pending_lookup");
    session.put("session_state", "awaiting_user");
    assertFalse(QueryWorkflow.shouldAutoRecord(session, settings));
    session.put("session_state", "ready");
    session.getJSONObject("query_results").put("is_from_local", true);
    assertFalse(QueryWorkflow.shouldAutoRecord(session, settings));
  }

  @Test
  public void autoRecordIsIdempotentAndIgnoresSavedResults() throws Exception {
    JSONObject settings = new JSONObject().put("auto_record_extra_info", true);
    JSONObject session =
        new JSONObject()
            .put("id", "s")
            .put("revision", 1)
            .put("session_state", "ready")
            .put("query_results", result("resilient"));
    session.put("auto_record_key", QueryWorkflow.resultKey(session));
    assertFalse(QueryWorkflow.shouldAutoRecord(session, settings));
    session.put("revision", 2);
    assertTrue(QueryWorkflow.shouldAutoRecord(session, settings));
    session.put("saved_content", new JSONObject().put("saved", true));
    assertFalse(QueryWorkflow.shouldAutoRecord(session, settings));
  }

  @Test
  public void supplementMergesWithoutMutatingOrReusingMeaningIds() throws Exception {
    JSONObject old = result("resilient").put("is_from_local", true);
    JSONObject supplement = result("resilient").put("raw_explanation", "more context");
    supplement
        .getJSONArray("meanings")
        .put(
            new JSONObject()
                .put("id", "m1")
                .put("zh_definition", "有弹性的")
                .put("en_definition", "returning to shape"));
    // Transport validates unique IDs; supply a valid model ID which collides with another old
    // result.
    supplement.getJSONArray("meanings").getJSONObject(0).put("id", "m2");
    JSONObject merged = QueryWorkflow.mergeSupplement(old, supplement);
    assertEquals(2, merged.getJSONArray("meanings").length());
    assertNotEquals(
        merged.getJSONArray("meanings").getJSONObject(0).getString("id"),
        merged.getJSONArray("meanings").getJSONObject(1).getString("id"));
    assertEquals("more context", merged.getString("raw_explanation"));
    assertEquals("ai", merged.getJSONArray("meanings").getJSONObject(1).optString("lookup_origin"));
    assertFalse(merged.getBoolean("is_from_local"));
    assertEquals(1, old.getJSONArray("meanings").length());
  }

  @Test
  public void supplementPreservesDictionaryFieldsAndMarksNewExample() throws Exception {
    JSONObject dictionary = result("resilient").put("lookup_origin", "ecdict");
    JSONObject original = dictionary.getJSONArray("meanings").getJSONObject(0);
    original.remove("example");
    original.put("field_meta", new JSONObject()
        .put("zh_definition", new JSONObject().put("is_ai_generated", false)));
    JSONObject model = result("resilient").put("phonetic", "model phonetic");
    dictionary.put("phonetic", "dictionary phonetic");

    JSONObject merged = QueryWorkflow.mergeSupplement(dictionary, model);

    assertEquals("dictionary phonetic", merged.getString("phonetic"));
    JSONObject meaning = merged.getJSONArray("meanings").getJSONObject(0);
    assertEquals("Stay resilient.", meaning.getString("example"));
    assertFalse(meaning.getJSONObject("field_meta").getJSONObject("zh_definition")
        .getBoolean("is_ai_generated"));
    assertTrue(meaning.getJSONObject("field_meta").getJSONObject("example")
        .getBoolean("is_ai_generated"));
  }

  @Test
  public void contextCanSelectExistingMeaningButCannotInventCandidateId() throws Exception {
    JSONObject word = result("resilient");
    JSONObject context = QueryWorkflow.validateDictionaryContext(word, new JSONObject()
        .put("selected_meaning_id", "m1").put("explanation", "Context supports this sense."));
    assertEquals("m1", context.getString("selected_meaning_id"));
    assertTrue(context.getJSONObject("field_meta").getJSONObject("explanation")
        .getBoolean("is_ai_generated"));
    assertFalse(word.has("field_meta"));
    try {
      QueryWorkflow.validateDictionaryContext(word, new JSONObject().put("selected_meaning_id", "m2"));
      fail("unknown dictionary sense accepted");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("释义"));
    }
  }

  @Test
  public void webConclusionUsesOnlyFetchedSourcesAndIsMarkedGenerated() throws Exception {
    JSONArray sources = new JSONArray().put(new JSONObject().put("id", "S1")
        .put("url", "https://example.com/dictionary"));
    JSONObject reply = new JSONObject().put("status", "supported").put("meaning", "河岸")
        .put("source_ids", new JSONArray().put("S1").put("S1"))
        .put("sources", new JSONArray().put(new JSONObject().put("id", "S2")));
    JSONObject verified = QueryWorkflow.validateWebVerification(sources, reply);
    assertEquals(1, verified.getJSONArray("source_ids").length());
    assertEquals("S1", verified.getJSONArray("sources").getJSONObject(0).getString("id"));
    assertTrue(verified.getJSONObject("field_meta").getJSONObject("meaning")
        .getBoolean("is_ai_generated"));
    JSONObject newMeaning = QueryWorkflow.meaningFromVerification(verified);
    Library library = new Library();
    String savedId = library.saveResult(new JSONObject().put("word", "bank").put("type", "word")
        .put("meanings", new JSONArray().put(newMeaning)), new String[] {"nb_default"}, new JSONObject());
    JSONObject saved = library.find("vocabularies", savedId).getJSONArray("meanings").getJSONObject(0);
    assertEquals("河岸", saved.getString("zh_definition"));
    assertEquals("S1", saved.getJSONArray("web_sources").getJSONObject(0).getString("id"));
    assertTrue(saved.getJSONObject("field_meta").getJSONObject("zh_definition")
        .getBoolean("is_ai_generated"));
    reply.put("source_ids", new JSONArray().put("S2"));
    try {
      QueryWorkflow.validateWebVerification(sources, reply);
      fail("unfetched citation accepted");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("来源"));
    }
  }

  @Test
  public void changedSupplementTargetIsRejected() throws Exception {
    try {
      QueryWorkflow.mergeSupplement(result("resilient"), result("resistant"));
      fail();
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("目标"));
    }
  }

  @Test
  public void editingPresentationNeverDeletesDefinitionsOrExample() throws Exception {
    JSONObject value = result("resilient");
    JSONObject changed = QueryWorkflow.presentation(value, "m1", false, "en", false);
    JSONObject m = changed.getJSONArray("meanings").getJSONObject(0);
    assertEquals("有韧性的", m.getString("zh_definition"));
    assertEquals("Stay resilient.", m.getString("example"));
    assertFalse(m.getBoolean("selected"));
    assertEquals("en", m.getString("definition_choice"));
    assertTrue(m.getBoolean("save_en"));
    assertFalse(m.getBoolean("save_zh"));
    assertFalse(m.getBoolean("save_example"));
    assertFalse(value.getJSONArray("meanings").getJSONObject(0).has("selected"));
  }

  @Test
  public void editPreservesProvenanceAndRejectsEmptyDefinitions() throws Exception {
    JSONObject original = result("resilient");
    original
        .getJSONArray("meanings")
        .getJSONObject(0)
        .put("source_images", new JSONArray().put("file:///original.jpg"));
    JSONObject edited =
        QueryWorkflow.editMeaning(
            original, "m1", new JSONObject().put("example", "A resilient child."));
    assertEquals(
        "A resilient child.",
        edited.getJSONArray("meanings").getJSONObject(0).getString("example"));
    assertEquals(
        "file:///original.jpg",
        edited
            .getJSONArray("meanings")
            .getJSONObject(0)
            .getJSONArray("source_images")
            .getString(0));
    try {
      QueryWorkflow.editMeaning(
          original, "m1", new JSONObject().put("zh_definition", "").put("en_definition", ""));
      fail();
    } catch (Exception expected) {
      assertTrue(expected.getMessage().contains("释义"));
    }
  }

  @Test
  public void provenanceIsGeneratedAndHasAnyGenerated() throws Exception {
    JSONObject meaning = new JSONObject();
    meaning.put("zh_definition", "苹果");
    meaning.put("example", "An apple a day.");
    FieldProvenance.markPresent(meaning, true, "zh_definition");
    FieldProvenance.markPresent(meaning, false, "example");

    assertTrue(FieldProvenance.isGenerated(meaning, "zh_definition"));
    assertFalse(FieldProvenance.isGenerated(meaning, "example"));
    assertFalse(FieldProvenance.isGenerated(meaning, "en_definition"));
    assertFalse(FieldProvenance.isGenerated(null, "zh_definition"));
    assertFalse(FieldProvenance.isGenerated(meaning, null));

    assertTrue(FieldProvenance.hasAnyGenerated(meaning, "zh_definition", "example"));
    assertFalse(FieldProvenance.hasAnyGenerated(meaning, "example", "en_definition"));
    assertFalse(FieldProvenance.hasAnyGenerated(null, "zh_definition"));
  }
  @Test
  public void automaticSupplementRequiresOptInAndDictionaryResult() throws Exception {
    JSONObject dictionary = result("resilient").put("lookup_origin", "ecdict");
    JSONObject settings = new JSONObject();
    assertFalse(QueryWorkflow.shouldAutoSupplement(dictionary, settings));
    settings.put("auto_ai_supplement", true);
    assertTrue(QueryWorkflow.shouldAutoSupplement(dictionary, settings));
    assertFalse(QueryWorkflow.shouldAutoSupplement(result("resilient"), settings));
  }

  @Test
  public void organizeFieldsAreIndependentAndAnnotationsKeepTheirTarget() throws Exception {
    JSONObject original = result("resilient");
    JSONObject hidden = QueryWorkflow.organizeField(original, "m1", "en_definition", false);
    JSONObject shown = QueryWorkflow.organizeField(hidden, "m1", "en_definition", true);
    JSONObject annotated = QueryWorkflow.annotateMeaning(shown, "m1", "en_definition", "我的批注");
    JSONObject meaning = annotated.getJSONArray("meanings").getJSONObject(0);
    assertTrue(meaning.optBoolean("save_zh", true));
    assertTrue(meaning.getBoolean("save_en"));
    assertFalse(hidden.getJSONArray("meanings").getJSONObject(0).getBoolean("save_en"));
    assertEquals("able to recover", meaning.getJSONArray("annotations").getJSONObject(0).getString("target_text"));
    assertFalse(original.getJSONArray("meanings").getJSONObject(0).has("annotations"));
  }

  @Test
  public void editingAndDeletingAnAnnotationOnlyChangesItsOwnEntry() throws Exception {
    JSONObject r = QueryWorkflow.annotateMeaning(result("resilient"), "m1", "zh_definition", "First");
    r = QueryWorkflow.annotateMeaning(r, "m1", "example", "Second");
    JSONArray before = r.getJSONArray("meanings").getJSONObject(0).getJSONArray("annotations");
    String id = before.getJSONObject(0).getString("id");
    JSONObject updated = QueryWorkflow.changeAnnotation(r, "m1", id, "en_definition", "Revised");
    JSONArray edited = updated.getJSONArray("meanings").getJSONObject(0).getJSONArray("annotations");
    assertEquals("Revised", edited.getJSONObject(0).getString("content"));
    assertEquals("able to recover", edited.getJSONObject(0).getString("target_text"));
    JSONObject deleted = QueryWorkflow.changeAnnotation(updated, "m1", id, null, null);
    JSONArray remaining = deleted.getJSONArray("meanings").getJSONObject(0).getJSONArray("annotations");
    assertEquals(1, remaining.length());
    assertEquals("Second", remaining.getJSONObject(0).getString("content"));
    assertEquals("First", before.getJSONObject(0).getString("content"));
  }

  @Test
  public void dictionaryAlignmentKeepsOriginalTextAndUnmatchedSenses() throws Exception {
    JSONObject original = OfflineDictionary.toResult("bank", "bæŋk",
        "n. sloping land\\nn. financial institution", "n. 银行\\nn. 河岸\\nn. 沙洲");
    JSONObject reply = new JSONObject("{\"groups\":["
        + "{\"ids\":[\"zh-0\",\"en-1\"],\"zh_definition\":\"错误改写\",\"example\":\"The bank opens at nine.\"},"
        + "{\"ids\":[\"zh-1\",\"en-0\"]}]}");
    JSONObject aligned = QueryWorkflow.alignDictionary(original, reply);
    JSONArray cards = aligned.getJSONArray("meanings");
    assertEquals(3, cards.length());
    JSONObject bank = cards.getJSONObject(0);
    assertEquals("n. 银行", bank.getString("zh_definition"));
    assertEquals("n. financial institution", bank.getString("en_definition"));
    assertFalse(FieldProvenance.isGenerated(bank, "zh_definition"));
    assertFalse(FieldProvenance.isGenerated(bank, "en_definition"));
    assertTrue(FieldProvenance.isGenerated(bank, "example"));
    assertEquals("n. sloping land", cards.getJSONObject(1).getString("en_definition"));
    assertEquals("n. 沙洲", cards.getJSONObject(2).getString("zh_definition"));
    assertEquals(5, original.getJSONArray("meanings").length());
  }

  @Test
  public void dictionaryAlignmentLabelsMissingTranslationsAndRejectsReusedOrUnknownIds() throws Exception {
    JSONObject original = OfflineDictionary.toResult("look up", "", "", "查阅");
    JSONObject valid = new JSONObject("{\"groups\":[{\"ids\":[\"zh-0\"],"
        + "\"en_definition\":\"To consult a source.\"}]}");
    JSONObject meaning = QueryWorkflow.alignDictionary(original, valid)
        .getJSONArray("meanings").getJSONObject(0);
    assertTrue(FieldProvenance.isGenerated(meaning, "en_definition"));
    assertFalse(FieldProvenance.isGenerated(meaning, "zh_definition"));
    for (String ids : new String[] {"[\"zh-0\",\"zh-0\"]", "[\"missing\"]"}) {
      JSONObject invalid = new JSONObject("{\"groups\":[{\"ids\":" + ids + "}]}");
      assertThrows(IllegalArgumentException.class, () -> QueryWorkflow.alignDictionary(original, invalid));
    }
  }

}
