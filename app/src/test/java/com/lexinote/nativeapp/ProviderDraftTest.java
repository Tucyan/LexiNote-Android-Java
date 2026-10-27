package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

public class ProviderDraftTest {
  JSONObject provider() throws Exception {
    return new JSONObject()
        .put("id", "p")
        .put("model_id", "old")
        .put("base_url", "https://example.test/v1")
        .put("supports_vision", true)
        .put("reasoning_effort", "high");
  }

  @Test
  public void credentialsStayOutsidePortableDraftAndInvalidateOldCallbacks() throws Exception {
    ProviderDraft draft = new ProviderDraft(provider());
    long revision = draft.revision();
    draft.setKey("test-only-secret");
    assertEquals("test-only-secret", draft.enteredKey());
    assertFalse(draft.config().toString().contains("test-only-secret"));
    assertFalse(draft.matches("p", revision));
  }

  @Test
  public void revisionChangesOnlyWhenContentChanges() throws Exception {
    ProviderDraft draft = new ProviderDraft(provider());
    draft.set("model_id", "old");
    assertEquals(0, draft.revision());
    draft.set("model_id", "new");
    assertTrue(draft.matches("p", 1));
    assertFalse(draft.matches("other", 1));
  }

  @Test
  public void selectedCapabilityMetadataControlsVisionAndAllowedEffort() throws Exception {
    ProviderDraft draft = new ProviderDraft(provider());
    draft.selectModel(
        new JSONObject()
            .put("id", "text-model")
            .put("vision_known", true)
            .put("supports_vision", false)
            .put("reasoning_levels", new JSONArray().put("low")));
    assertFalse(draft.config().getBoolean("supports_vision"));
    assertEquals("off", draft.config().getString("reasoning_effort"));
    assertEquals("low", draft.config().getJSONArray("reasoning_levels").getString(0));
  }

  @Test
  public void changingDestinationOrManualModelDiscardsStaleCapabilities() throws Exception {
    ProviderDraft draft = new ProviderDraft(provider());
    draft.selectModel(
        new JSONObject()
            .put("id", "known")
            .put("vision_known", true)
            .put("supports_vision", true)
            .put("reasoning_levels", new JSONArray().put("high")));
    assertTrue(draft.config().has("reasoning_levels"));
    draft.set("base_url", "https://new.example/v1");
    assertFalse(draft.config().has("reasoning_levels"));
    assertFalse(draft.config().has("vision_known"));
  }

  @Test
  public void snapshotsCannotMutateTheDraft() throws Exception {
    ProviderDraft draft = new ProviderDraft(provider());
    draft.config().put("model_id", "changed");
    assertEquals("old", draft.config().getString("model_id"));
    assertTrue(draft.matches("p", 0));
  }
}
