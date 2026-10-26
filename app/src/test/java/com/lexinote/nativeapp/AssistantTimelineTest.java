package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class AssistantTimelineTest {
  @Test
  public void latestWindowFiltersSystemRowsAndRestoresByStableMessageId() throws Exception {
    JSONArray all = new JSONArray();
    for (int i = 0; i < 240; i++) {
      all.put(new JSONObject().put("id", "m" + i).put("role", i % 11 == 0 ? "system" : "user"));
    }
    assertEquals(200, AssistantTimeline.count(all));
    String anchor = AssistantTimeline.id(all, 91);
    assertEquals(91, AssistantTimeline.positionOf(all, anchor));
    assertEquals("m" + anchor.substring(1), AssistantTimeline.at(all, 91).getString("id"));
    assertEquals(AssistantTimeline.stableId(anchor), AssistantTimeline.stableId(anchor));
    assertEquals(-1, AssistantTimeline.positionOf(all, "old-pruned-id"));
  }

  @Test
  public void addingRowsBeforeAnAnchorDoesNotChangeItsRestoredIndex() throws Exception {
    JSONArray before = new JSONArray();
    for (int i = 0; i < 200; i++)
      before.put(new JSONObject().put("id", "id" + i).put("role", "assistant"));
    String anchor = AssistantTimeline.id(before, 80);
    before.put(new JSONObject().put("id", "id200").put("role", "user"));
    assertEquals(79, AssistantTimeline.positionOf(before, anchor));
  }
}
