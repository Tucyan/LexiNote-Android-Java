package com.lexinote.nativeapp;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class BackupArchiveTest {
  @Test
  public void sourceImageDisplayFlagsAreNotImageReferences() throws Exception {
    Library library = new Library();
    library.settings().put("default_fields_to_save", new JSONObject().put("source_images", true));
    library.saveResult(
        new JSONObject(
            "{\"word\":\"photo\",\"type\":\"word\",\"meanings\":[{\"id\":\"m\",\"zh_definition\":\"照片\",\"source_images\":[\"file:///image.jpg\"]}]}"),
        new String[] {"nb_default"},
        new JSONObject().put("fields", new JSONObject().put("source_images", false)));
    BackupArchive.Prepared prepared =
        BackupArchive.decode(BackupArchive.encode(library.backup(), uri -> new byte[] {1}));
    assertEquals(1, prepared.assets.size());
    assertTrue(
        prepared
            .backup
            .getJSONObject("settings")
            .getJSONObject("default_fields_to_save")
            .getBoolean("source_images"));
    assertFalse(
        new Library(prepared.backup)
            .find("notebooks", "nb_default")
            .getJSONObject("vocabulary_display")
            .getJSONObject(library.local("photo").getString("id"))
            .getJSONObject("fields")
            .getBoolean("source_images"));
  }

  private JSONObject fixture() throws Exception {
    Library library = new Library();
    JSONObject word =
        new JSONObject(
            "{\"word\":\"test\",\"type\":\"word\",\"meanings\":[{\"id\":\"m\",\"zh_definition\":\"测试\",\"source_images\":[\"file:///image.jpg\"]}]}");
    library.saveResult(
        word,
        new String[] {library.array("notebooks").getJSONObject(0).getString("id")},
        new JSONObject());
    return library.backup();
  }

  @Test
  public void imageArchiveSurvivesAnotherDevicePath() throws Exception {
    byte[] photo = new byte[] {1, 2, 3, 4};
    BackupArchive.Prepared prepared =
        BackupArchive.decode(BackupArchive.encode(fixture(), uri -> photo));
    assertEquals(1, prepared.assets.size());
    JSONObject materialized =
        BackupArchive.materialize(
            prepared,
            (name, bytes) -> {
              assertArrayEquals(photo, bytes);
              return "file:///new-device/" + name;
            });
    assertTrue(
        materialized
            .getJSONArray("vocabularies")
            .getJSONObject(0)
            .getJSONArray("meanings")
            .getJSONObject(0)
            .getJSONArray("source_images")
            .getString(0)
            .startsWith("file:///new-device/"));
  }

  @Test
  public void brokenChecksumFailsBeforeMaterializing() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("backup.json"));
      zip.write(fixture().toString().getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("checksums.json"));
      zip.write("{}".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    try {
      BackupArchive.decode(bytes.toByteArray());
      fail("Corrupt archive accepted");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void rejectsZipTraversal() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("../outside"));
      zip.write(1);
      zip.closeEntry();
    }
    try {
      BackupArchive.decode(bytes.toByteArray());
      fail("Traversal accepted");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void refusesMissingImageAndAcceptsLegacyJson() throws Exception {
    try {
      BackupArchive.encode(fixture(), uri -> new byte[0]);
      fail("Missing image accepted");
    } catch (IllegalArgumentException expected) {
    }
    assertEquals(
        0,
        BackupArchive.decode(fixture().toString().getBytes(StandardCharsets.UTF_8)).assets.size());
  }

  @Test
  public void deduplicatesSharedImagesIncludingHistory() throws Exception {
    JSONObject backup = fixture();
    JSONObject session =
        new JSONObject()
            .put("id", "history")
            .put("created_at", Library.now())
            .put("query_type", "image")
            .put("ai_messages", new JSONArray())
            .put("input_images", new JSONArray().put("file:///image.jpg"));
    backup.getJSONArray("recent_queries").put(session);
    int[] reads = {0};
    BackupArchive.decode(
        BackupArchive.encode(
            backup,
            uri -> {
              reads[0]++;
              return new byte[] {1};
            }));
    assertEquals(1, reads[0]);
  }

  @Test
  public void importsActualArchiveProducedByExpoSourceEncoder() throws Exception {
    byte[] bytes;
    try (java.io.InputStream in = getClass().getResourceAsStream("/expo-2.2.zip")) {
      assertNotNull("Missing committed Expo export fixture", in);
      bytes = in.readAllBytes();
    }
    BackupArchive.Prepared prepared = BackupArchive.decode(bytes);
    assertEquals(1, prepared.assets.size());
    Library imported =
        new Library(BackupArchive.materialize(prepared, (name, image) -> "file:///native/" + name));
    assertEquals(
        "有韧性的",
        imported
            .local("resilient")
            .getJSONArray("meanings")
            .getJSONObject(0)
            .getString("zh_definition"));
    assertEquals(
        "旧版批注",
        imported
            .local("resilient")
            .getJSONArray("meanings")
            .getJSONObject(0)
            .getJSONArray("annotations")
            .getJSONObject(0)
            .getString("content"));
    assertTrue(Library.isSaved(imported.find("conversations", "original-c")));
    assertNotNull(imported.find("notebooks", "nb_default"));
    assertEquals("dark", imported.settings().getString("theme_mode"));
  }

  @Test
  public void rejectsOversizeInputBeforeJsonParsing() throws Exception {
    try {
      BackupArchive.decode(new byte[BackupArchive.MAX_BYTES + 1]);
      fail("Oversize input accepted");
    } catch (IllegalArgumentException expected) {
    }
  }
}
