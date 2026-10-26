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
    assertFalse(merged.getBoolean("is_from_local"));
    assertEquals(1, old.getJSONArray("meanings").length());
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
}
