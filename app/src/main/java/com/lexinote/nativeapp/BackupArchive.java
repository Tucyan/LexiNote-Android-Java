package com.lexinote.nativeapp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Compatible with Expo backupFormat.ts ZIP v2.2, including CRC32 checksums and image URI remapping.
 */
public final class BackupArchive {
  // Budget each simultaneously-held ZIP/entry/JSON copy on small Android heaps.
  public static final int MAX_BYTES =
      (int) Math.min(16L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 16);
  public static final int MAX_JSON_BYTES = Math.min(4 * 1024 * 1024, MAX_BYTES / 2);
  private static final String PREFIX = "lexinote-asset:";

  private BackupArchive() {}

  public interface ImageReader {
    byte[] read(String uri) throws Exception;
  }

  public interface ImageWriter {
    String write(String name, byte[] bytes) throws Exception;
  }

  public static final class Prepared {
    public JSONObject backup;
    public Map<String, byte[]> assets = new LinkedHashMap<>();
  }

  public static byte[] encode(JSONObject backup, ImageReader reader) throws Exception {
    Library.validate(backup);
    JSONObject portable = Library.copy(backup);
    Map<String, byte[]> entries = new LinkedHashMap<>();
    Map<String, String> mapping = new LinkedHashMap<>();
    long total = 0;
    for (String uri : imageReferences(portable)) {
      check(mapping.size() < 1998, "图片数量超过限制");
      String extension = "jpg";
      java.util.regex.Matcher match =
          java.util.regex.Pattern.compile("(?i)\\.(png|jpe?g|webp|gif|heic)(?:[?#]|$)")
              .matcher(uri);
      if (match.find()) extension = match.group(1).toLowerCase(java.util.Locale.ROOT);
      String name = "assets/" + mapping.size() + "." + extension;
      byte[] image = reader.read(uri);
      check(image != null && image.length > 0, "无法读取图片，请重新选择图片后备份");
      total += image.length;
      check(total < MAX_BYTES, "图片总大小超过当前设备安全容量（最高16 MB）");
      entries.put(name, image);
      mapping.put(uri, PREFIX + name);
    }
    replaceImages(portable, mapping);
    portable.put("version", "2.2");
    entries.put("backup.json", portable.toString().getBytes(StandardCharsets.UTF_8));
    check(entries.get("backup.json").length <= MAX_JSON_BYTES, "笔记文本超过当前设备容量（最高4 MB）");
    total += entries.get("backup.json").length;
    check(total < MAX_BYTES, "备份超过当前设备安全容量（最高16 MB）");
    JSONObject sums = new JSONObject();
    for (Map.Entry<String, byte[]> entry : entries.entrySet())
      sums.put(entry.getKey(), crc(entry.getValue()));
    entries.put("checksums.json", sums.toString().getBytes(StandardCharsets.UTF_8));
    total += entries.get("checksums.json").length;
    check(total <= MAX_BYTES, "备份及校验文件超过当前设备安全容量（最高16 MB）");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
    check(bytes.size() <= MAX_BYTES, "备份文件超过当前设备安全容量（最高16 MB）");
    return bytes.toByteArray();
  }

  public static Prepared decode(byte[] bytes) throws Exception {
    check(bytes.length <= MAX_BYTES && bytes.length > 0, "文件为空或超过当前设备安全容量（最高16 MB）");
    Prepared prepared = new Prepared();
    if (bytes.length < 2 || bytes[0] != 0x50 || bytes[1] != 0x4b) {
      check(bytes.length <= MAX_JSON_BYTES, "JSON超过当前设备容量（最高4 MB）");
      prepared.backup =
          new JSONObject(new String(bytes, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", ""));
      Library.validate(prepared.backup);
      check(!prepared.backup.optString("version").startsWith("2."), "含图备份须选择完整ZIP文件");
      for (String uri : imageReferences(prepared.backup))
        check(!uri.startsWith(PREFIX), "备份缺少图片文件");
      return prepared;
    }
    Map<String, byte[]> entries = new LinkedHashMap<>();
    int total = 0;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry;
      byte[] buffer = new byte[8192];
      while ((entry = zip.getNextEntry()) != null) {
        String name = entry.getName();
        check(entries.size() < 2000 && !entries.containsKey(name), "归档文件重复或过多");
        check(
            name.equals("backup.json")
                || name.equals("checksums.json")
                || name.matches("assets/\\d+\\.(png|jpe?g|webp|gif|heic)"),
            "归档包含无效路径");
        check(!entry.isDirectory(), "归档不能包含目录条目");
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int count;
        while ((count = zip.read(buffer)) != -1) {
          total += count;
          check(total <= MAX_BYTES, "解压数据超过当前设备安全容量（最高16 MB）");
          if (name.equals("backup.json") || name.equals("checksums.json"))
            check(data.size() + count <= MAX_JSON_BYTES, "归档文本超过当前设备容量（最高4 MB）");
          data.write(buffer, 0, count);
        }
        entries.put(name, data.toByteArray());
        zip.closeEntry();
      }
    }
    check(
        entries.containsKey("backup.json") && entries.containsKey("checksums.json"), "归档缺少备份或校验文件");
    JSONObject sums =
        new JSONObject(new String(entries.get("checksums.json"), StandardCharsets.UTF_8));
    for (Map.Entry<String, byte[]> entry : entries.entrySet())
      if (!entry.getKey().equals("checksums.json"))
        check(crc(entry.getValue()).equals(sums.optString(entry.getKey())), "归档校验失败，文件可能已损坏");
    prepared.backup =
        new JSONObject(new String(entries.get("backup.json"), StandardCharsets.UTF_8));
    Library.validate(prepared.backup);
    check(
        java.util.Arrays.asList("2.0", "2.1", "2.2").contains(prepared.backup.optString("version")),
        "ZIP备份版本无效");
    for (String uri : imageReferences(prepared.backup)) {
      check(uri.startsWith(PREFIX), "含图备份的图片未完整打包");
      String name = uri.substring(PREFIX.length());
      check(
          name.matches("assets/\\d+\\.(png|jpe?g|webp|gif|heic)") && entries.containsKey(name),
          "归档缺少图片");
      prepared.assets.put(name, entries.get(name));
    }
    return prepared;
  }

  public static JSONObject materialize(Prepared prepared, ImageWriter writer) throws Exception {
    JSONObject backup = Library.copy(prepared.backup);
    Map<String, String> mapping = new LinkedHashMap<>();
    for (Map.Entry<String, byte[]> asset : prepared.assets.entrySet())
      mapping.put(
          PREFIX + asset.getKey(),
          writer.write(asset.getKey().substring("assets/".length()), asset.getValue()));
    replaceImages(backup, mapping);
    return backup;
  }

  private static Set<String> imageReferences(JSONObject backup) throws Exception {
    Set<String> references = new LinkedHashSet<>();
    visit(
        backup,
        array -> {
          for (int i = 0; i < array.length(); i++) {
            check(array.get(i) instanceof String, "图片引用格式无效");
            references.add(array.getString(i));
          }
        });
    return references;
  }

  private static void replaceImages(JSONObject backup, Map<String, String> mapping)
      throws Exception {
    visit(
        backup,
        array -> {
          for (int i = 0; i < array.length(); i++) {
            String replacement = mapping.get(array.getString(i));
            if (replacement != null) array.put(i, replacement);
          }
        });
  }

  private interface ImageArray {
    void accept(JSONArray array) throws Exception;
  }

  private static void visit(Object value, ImageArray consumer) throws Exception {
    if (value instanceof JSONObject) {
      JSONObject object = (JSONObject) value;
      Iterator<String> keys = object.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        Object child = object.get(key);
        if (key.equals("source_images") || key.equals("input_images")) {
          check(child instanceof JSONArray, "图片列表格式无效");
          consumer.accept((JSONArray) child);
        } else visit(child, consumer);
      }
    } else if (value instanceof JSONArray) {
      JSONArray array = (JSONArray) value;
      for (int i = 0; i < array.length(); i++) visit(array.get(i), consumer);
    }
  }

  private static String crc(byte[] bytes) {
    CRC32 sum = new CRC32();
    sum.update(bytes);
    return Long.toHexString(sum.getValue());
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }
}
