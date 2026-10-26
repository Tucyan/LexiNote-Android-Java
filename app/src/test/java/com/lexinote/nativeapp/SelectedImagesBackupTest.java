package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

public class SelectedImagesBackupTest {
  @Test
  public void originalAndDerivedImagesTravelIndependentlyWithEditorState() throws Exception {
    Library library = new Library();
    JSONObject session = library.newSession("photo");
    session.put("input_images", new JSONArray().put("file:///original.jpg"));
    session.put("native_selected_images", new JSONArray().put("file:///selection.jpg"));
    session.put(
        "selected_regions",
        new JSONArray()
            .put(new JSONObject().put("x", 10).put("y", 20).put("width", 30).put("height", 40)));
    session.put(
        "image_editor_state",
        new JSONObject()
            .put("version", 2)
            .put("quarterTurns", 1)
            .put("inkStrokes", new JSONArray()));
    library.touchSession(session, false);
    byte[] zip =
        BackupArchive.encode(
            library.backup(),
            uri ->
                (uri.contains("selection") ? "selected bytes" : "original bytes")
                    .getBytes("UTF-8"));
    BackupArchive.Prepared prepared = BackupArchive.decode(zip);
    assertEquals(2, prepared.assets.size());
    JSONObject restored =
        BackupArchive.materialize(
            prepared, (name, bytes) -> "file:///restored/" + new String(bytes, "UTF-8"));
    JSONObject value = restored.getJSONArray("conversations").getJSONObject(0);
    assertTrue(value.getJSONArray("input_images").getString(0).endsWith("original bytes"));
    assertTrue(
        value.getJSONArray("native_selected_images").getString(0).endsWith("selected bytes"));
    assertEquals(1, value.getJSONObject("image_editor_state").getInt("quarterTurns"));
    assertEquals(30, value.getJSONArray("selected_regions").getJSONObject(0).getInt("width"));
  }
}
