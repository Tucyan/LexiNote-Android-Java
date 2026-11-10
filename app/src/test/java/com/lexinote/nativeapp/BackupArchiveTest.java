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

  @Test
  public void normalUsageZipFixtureImportsSuccessfullyInNewVersion() throws Exception {
    java.io.File zipFile = new java.io.File("docs/fixtures/normal-usage/lexinote-normal-usage-20261028.zip");
    if (!zipFile.exists()) zipFile = new java.io.File("../docs/fixtures/normal-usage/lexinote-normal-usage-20261028.zip");
    assertTrue("Zip fixture exists", zipFile.exists());
    byte[] bytes = java.nio.file.Files.readAllBytes(zipFile.toPath());
    BackupArchive.Prepared prepared = BackupArchive.decode(bytes);
    assertNotNull(prepared);
    assertNotNull(prepared.backup);
    Library library = new Library(prepared.backup);
    assertEquals(120, library.array("vocabularies").length());
    assertEquals(8, library.array("notebooks").length());
    assertEquals(10, library.array("recent_queries").length());
    assertEquals(13, library.array("conversations").length());
  }

  @Test
  public void normalUsageJsonFixtureImportsSuccessfullyInNewVersion() throws Exception {
    java.io.File jsonFile = new java.io.File("docs/fixtures/normal-usage/lexinote-normal-usage-20261028.json");
    if (!jsonFile.exists()) jsonFile = new java.io.File("../docs/fixtures/normal-usage/lexinote-normal-usage-20261028.json");
    assertTrue("Json fixture exists", jsonFile.exists());
    byte[] bytes = java.nio.file.Files.readAllBytes(jsonFile.toPath());
    BackupArchive.Prepared prepared = BackupArchive.decode(bytes);
    assertNotNull(prepared);
    assertNotNull(prepared.backup);
    Library library = new Library(prepared.backup);
    assertEquals(120, library.array("vocabularies").length());
    assertEquals(8, library.array("notebooks").length());
  }

  @Test
  public void normalUsageWithPlansZipFixtureImportsSuccessfullyInNewVersion() throws Exception {
    java.io.File zipFile = new java.io.File("docs/fixtures/normal-usage/lexinote-normal-usage-with-plans-20261028.zip");
    if (!zipFile.exists()) zipFile = new java.io.File("../docs/fixtures/normal-usage/lexinote-normal-usage-with-plans-20261028.zip");
    assertTrue("Zip with plans fixture exists", zipFile.exists());
    byte[] bytes = java.nio.file.Files.readAllBytes(zipFile.toPath());
    BackupArchive.Prepared prepared = BackupArchive.decode(bytes);
    assertNotNull(prepared);
    assertNotNull(prepared.backup);
    Library library = new Library(prepared.backup);
    assertEquals(120, library.array("vocabularies").length());
    assertEquals(8, library.array("notebooks").length());
    assertEquals(10, library.array("recent_queries").length());
    assertEquals(13, library.array("conversations").length());
    assertNotNull(prepared.backup.optJSONObject("review"));
    assertEquals(4, prepared.backup.getJSONObject("review").getJSONArray("plans").length());
    assertEquals(4, library.reviewPlans().list(false).length());
  }

  @Test
  public void normalUsageWithPlansJsonFixtureImportsSuccessfullyInNewVersion() throws Exception {
    java.io.File jsonFile = new java.io.File("docs/fixtures/normal-usage/lexinote-normal-usage-with-plans-20261028.json");
    if (!jsonFile.exists()) jsonFile = new java.io.File("../docs/fixtures/normal-usage/lexinote-normal-usage-with-plans-20261028.json");
    assertTrue("Json with plans fixture exists", jsonFile.exists());
    byte[] bytes = java.nio.file.Files.readAllBytes(jsonFile.toPath());
    BackupArchive.Prepared prepared = BackupArchive.decode(bytes);
    assertNotNull(prepared);
    assertNotNull(prepared.backup);
    Library library = new Library(prepared.backup);
    assertEquals(120, library.array("vocabularies").length());
    assertEquals(8, library.array("notebooks").length());
    assertNotNull(prepared.backup.optJSONObject("review"));
    assertEquals(4, prepared.backup.getJSONObject("review").getJSONArray("plans").length());
  }
}
