package com.lexinote.nativeapp;

import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.json.JSONObject;

/** Streaming archive staging. The caller must validate domain data before activating it. */
public final class BackupStreamCodec {
  private static final String PREFIX = "lexinote-asset:";
  public interface ImageOpener { InputStream open(String uri) throws Exception; }
  public interface AssetWriter { String write(String name, File asset) throws Exception; }
  private BackupStreamCodec() {}
  public static final class Prepared implements AutoCloseable {
    public final File backupJson;
    public final Map<String, File> assets;
    private Prepared(File json, Map<String, File> assets) { this.backupJson = json; this.assets = Collections.unmodifiableMap(assets); }
    @Override public void close() { deleteStaging(backupJson.getParentFile()); }
    /** Produces a local-reference document; caller validates it before making it active. */
    public void materialize(OutputStream output, AssetWriter writer) throws Exception {
      Map<String,String> mapping = new HashMap<>();
      for (Map.Entry<String,File> e : assets.entrySet()) mapping.put(PREFIX + e.getKey(), writer.write(e.getKey().substring(7), e.getValue()));
      try (InputStream in = new FileInputStream(backupJson)) { transform(in, output, mapping, false, null); }
    }
  }
  /** Writes a redacted 1.1 JSON backup while retaining original image references. */
  public static void encodeJson(InputStream json, OutputStream output) throws Exception {
    transform(json, new LimitedOutput(output, new BackupLimits(), false), new LinkedHashMap<>(), true, null);
  }
  public static void encode(InputStream json, OutputStream output, ImageOpener opener) throws Exception {
    BackupLimits limits = new BackupLimits();
    Map<String,String> mapping = new LinkedHashMap<>(); Map<String,String> sums = new LinkedHashMap<>();
    try (ZipOutputStream zip = new ZipOutputStream(new LimitedOutput(output, limits, true))) {
      zip.putNextEntry(new ZipEntry("backup.json"));
      CheckedEntry entry = new CheckedEntry(zip, limits);
      transform(json, entry, mapping, true, opener);
      entry.flush(); sums.put("backup.json", Long.toHexString(entry.crc.getValue())); zip.closeEntry();
      for (Map.Entry<String,String> image : mapping.entrySet()) {
        String path = image.getValue().substring(PREFIX.length()); zip.putNextEntry(new ZipEntry(path));
        CheckedEntry asset = new CheckedEntry(zip, limits);
        try (InputStream in = opener.open(image.getKey())) { if (in == null || copy(in, asset) == 0) throw new IOException("无法读取图片"); }
        asset.flush(); sums.put(path, Long.toHexString(asset.crc.getValue())); zip.closeEntry();
      }
      zip.putNextEntry(new ZipEntry("checksums.json"));
      CheckedEntry checksums = new CheckedEntry(zip, limits);
      checksums.write(new JSONObject(sums).toString().getBytes(StandardCharsets.UTF_8)); checksums.flush(); zip.closeEntry();
    }
  }
  /** Stages entries in a newly created child directory; does not activate or domain-validate data. */
  public static Prepared decode(InputStream source, File stagingParent) throws Exception {
    File dir = new File(stagingParent, "backup-" + UUID.randomUUID());
    if (!dir.mkdirs()) throw new IOException("无法创建备份暂存目录");
    BackupLimits limits = new BackupLimits(); Map<String,File> files = new LinkedHashMap<>(); Map<String,String> sums = new LinkedHashMap<>();
    try {
      BufferedInputStream buffered = new BufferedInputStream(new LimitedInput(source, limits)); buffered.mark(4);
      int a=buffered.read(), b=buffered.read(); buffered.reset();
      if (a != 'P' || b != 'K') {
        File json = new File(dir, "backup.json");
        try (OutputStream out = new LimitedOutput(new FileOutputStream(json), limits, false)) { copy(buffered, out); }
        validateReferences(json, Collections.emptyMap(), false);
        return new Prepared(json, new LinkedHashMap<>());
      }
      try (ZipInputStream zip = new ZipInputStream(buffered)) {
        ZipEntry ze; byte[] buf = new byte[8192];
        while ((ze = zip.getNextEntry()) != null) {
          String name = ze.getName();
          if (files.size() >= 2000 || files.containsKey(name) || ze.isDirectory() || !validPath(name)) throw new IOException("归档路径无效或重复");
          File file = new File(dir, name.replace('/', '_')); CRC32 crc = new CRC32();
          try (OutputStream out = new LimitedOutput(new FileOutputStream(file), limits, false)) {
            int n; while ((n = zip.read(buf)) != -1) { crc.update(buf,0,n); out.write(buf,0,n); }
          }
          files.put(name,file); sums.put(name,Long.toHexString(crc.getValue())); zip.closeEntry();
        }
      }
      File json=files.get("backup.json"), checks=files.get("checksums.json");
      if (json == null || checks == null || checks.length() > 256 * 1024) throw new IOException("归档缺少备份或校验文件");
      byte[] bytes = new byte[(int) checks.length()];
      try (DataInputStream in = new DataInputStream(new FileInputStream(checks))) { in.readFully(bytes); }
      JSONObject expected = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
      for (Map.Entry<String,String> e : sums.entrySet()) if (!e.getKey().equals("checksums.json") && !e.getValue().equals(expected.optString(e.getKey()))) throw new IOException("归档校验失败");
      files.remove("backup.json"); files.remove("checksums.json");
      validateReferences(json, files, true);
      return new Prepared(json, files);
    } catch (Exception e) { deleteStaging(dir); throw e; }
  }
  private static boolean validPath(String name) { return name.equals("backup.json") || name.equals("checksums.json") || name.matches("assets/\\d+\\.(png|jpe?g|webp|gif|heic)"); }
  private static void deleteStaging(File dir) { File[] files=dir.listFiles(); if (files!=null) for (File f:files) f.delete(); dir.delete(); }
  private static void validateReferences(File json, Map<String,File> assets, boolean zip) throws Exception {
    String version = "1.1";
    try (JsonReader r = new JsonReader(new InputStreamReader(new FileInputStream(json), StandardCharsets.UTF_8))) {
      r.beginObject();
      while (r.hasNext()) { String name = r.nextName(); if (name.equals("version")) version = r.nextString(); else r.skipValue(); }
      r.endObject();
      if (r.peek() != JsonToken.END_DOCUMENT) throw new IOException("JSON尾部有多余数据");
    }
    if (zip ? !Arrays.asList("2.0", "2.1", "2.2").contains(version) : !Arrays.asList("1.0", "1.1").contains(version))
      throw new IOException("备份版本无效");
    Map<String,String> references = new LinkedHashMap<>();
    try (InputStream in=new FileInputStream(json)) { transform(in, new NullOutput(), references, false, uri -> null); }
    for (String uri:references.keySet()) {
      if (zip && (!uri.startsWith(PREFIX) || !assets.containsKey(uri.substring(PREFIX.length())))) throw new IOException("备份缺少图片");
      if (!zip && uri.startsWith(PREFIX)) throw new IOException("备份缺少图片");
    }
  }
  private static void transform(InputStream input, OutputStream output, Map<String,String> mapping, boolean export, ImageOpener collect) throws Exception {
    JsonReader reader=new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    JsonWriter writer=new JsonWriter(new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), 32 * 1024));
    rewrite(reader,writer,mapping,export,collect,false,true); writer.flush();
    if (reader.peek()!=JsonToken.END_DOCUMENT) throw new IOException("JSON尾部有多余数据");
  }
  private static void rewrite(JsonReader r, JsonWriter w, Map<String,String> map, boolean export, ImageOpener collect, boolean images, boolean root) throws Exception {
    JsonToken token=r.peek();
    if (token==JsonToken.BEGIN_OBJECT) {
      r.beginObject(); w.beginObject();
      boolean hasVersion = false;
      while(r.hasNext()) {
        String name=r.nextName();
        if (export && Library.credentialField(name)) { r.skipValue(); continue; }
        if (root && name.equals("version") && export) { hasVersion = true; r.skipValue(); w.name(name).value(collect == null ? "1.1" : "2.2"); continue; }
        w.name(name); boolean imageField=name.equals("source_images") || name.equals("input_images") || name.equals("native_selected_images");
        if (imageField && r.peek()!=JsonToken.BEGIN_ARRAY && !(name.equals("source_images") && r.peek()==JsonToken.BOOLEAN)) throw new IOException("图片列表格式无效");
        rewrite(r,w,map,export,collect,imageField,false);
      }
      r.endObject();
      if (root && export && !hasVersion) w.name("version").value(collect == null ? "1.1" : "2.2");
      w.endObject();
    } else if(token==JsonToken.BEGIN_ARRAY) {
      r.beginArray(); w.beginArray(); while(r.hasNext()) { if (images && r.peek()!=JsonToken.STRING) throw new IOException("图片引用格式无效"); rewrite(r,w,map,export,collect,images,false); } r.endArray(); w.endArray();
    } else if(token==JsonToken.STRING) {
      String value=r.nextString();
      if(images) {
        if(export && collect != null) { if(!map.containsKey(value)) { if(map.size()>=1998) throw new IOException("图片数量超过限制"); String extension="jpg"; java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)\\.(png|jpe?g|webp|gif|heic)(?:[?#]|$)").matcher(value); if(m.find()) extension=m.group(1).toLowerCase(Locale.ROOT); map.put(value,PREFIX+"assets/"+map.size()+"."+extension); } value=map.get(value); }
        else if(collect!=null) map.put(value,value);
        else if(map.containsKey(value)) value=map.get(value);
      }
      w.value(value);
    } else if(token==JsonToken.NUMBER) { w.value(new java.math.BigDecimal(r.nextString())); }
    else if(token==JsonToken.BOOLEAN) w.value(r.nextBoolean());
    else if(token==JsonToken.NULL) { r.nextNull(); w.nullValue(); }
    else throw new IOException("JSON结构无效");
  }
  private static long copy(InputStream in, OutputStream out) throws IOException { byte[] b=new byte[8192]; long total=0; int n; while((n=in.read(b))!=-1) { out.write(b,0,n); total+=n; } return total; }
  private static final class NullOutput extends OutputStream { public void write(int b) {} public void write(byte[] b,int o,int n) {} }
  private static final class LimitedInput extends FilterInputStream {
    final BackupLimits limits; LimitedInput(InputStream in,BackupLimits l){super(in);limits=l;}
    public int read() throws IOException { int b=super.read(); if(b>=0) limits.addArchive(1); return b; }
    public int read(byte[] b,int o,int n) throws IOException { int count=in.read(b,o,n); if(count>0) limits.addArchive(count); return count; }
  }
  private static class LimitedOutput extends FilterOutputStream {
    final BackupLimits limits; final boolean archive; LimitedOutput(OutputStream out,BackupLimits l,boolean archive){super(out);limits=l;this.archive=archive;}
    public void write(int b) throws IOException { if(archive) limits.addArchive(1); else limits.addExtracted(1); out.write(b); }
    public void write(byte[] b,int o,int n) throws IOException { if(archive) limits.addArchive(n); else limits.addExtracted(n); out.write(b,o,n); }
  }
  private static final class CheckedEntry extends LimitedOutput {
    final CRC32 crc=new CRC32(); CheckedEntry(OutputStream out,BackupLimits l){super(out,l,false);}
    public void write(int b) throws IOException { super.write(b); crc.update(b); }
    public void write(byte[] b,int o,int n) throws IOException { super.write(b,o,n); crc.update(b,o,n); }
    public void close() throws IOException { flush(); }
  }
}
