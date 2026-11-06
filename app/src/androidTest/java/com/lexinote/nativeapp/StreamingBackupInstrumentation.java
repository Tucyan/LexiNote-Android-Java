package com.lexinote.nativeapp;
import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Isolated staging test; does not open application data. */
public final class StreamingBackupInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  @Override public void onStart() {
    Bundle report = new Bundle(); int result = Activity.RESULT_OK;
    try {
      StringBuilder manyValues = new StringBuilder("{\"version\":\"1.1\",\"data\":[");
      for (int i = 0; i < 16000; i++) { if (i > 0) manyValues.append(','); manyValues.append("\"x\""); }
      manyValues.append("]}");
      final int[] calls = {0}; final boolean[] closed = {false};
      OutputStream counted = new OutputStream() {
        @Override public void write(int value) { calls[0]++; }
        @Override public void write(byte[] data, int offset, int length) { calls[0]++; }
        @Override public void close() { closed[0] = true; }
      };
      BackupStreamCodec.encodeJson(new ByteArrayInputStream(manyValues.toString().getBytes(StandardCharsets.UTF_8)), counted);
      if (calls[0] > 64 || closed[0]) throw new AssertionError("JSON output must be buffered and leave caller stream open; calls=" + calls[0]);
      File dir = new File(getTargetContext().getCacheDir(), "stream-backup-" + System.nanoTime());
      if (!dir.mkdirs()) throw new IOException("cannot create isolated directory");
      rejectArchive(dir, false);
      rejectArchive(dir, true);
      File input = new File(dir, "source.json"), zip = new File(dir, "backup.zip");
      try (Writer w = new OutputStreamWriter(new FileOutputStream(input), StandardCharsets.UTF_8)) {
        w.write("{\"version\":\"1.1\",\"api_key\":\"do-not-export\",\"apiKey\":\"also-secret\",\"providers\":[{\"api_key\":\"provider-secret\"}],\"token_count\":12,\"usage\":{\"total_tokens\":34},\"input_images\":[\"content://sample.png\"],\"extra\":[");
        String chunk = new String(new char[1024]).replace('\0', 'x');
        for (int i=0;i<8193;i++) { if (i>0) w.write(','); w.write('"'); w.write(chunk); w.write('"'); }
        w.write("]}");
      }
      try (InputStream in=new FileInputStream(input); OutputStream out=new FileOutputStream(zip)) {
        BackupStreamCodec.encode(in, out, uri -> new ByteArrayInputStream(new byte[]{1,2,3}));
      }
      BackupStreamCodec.Prepared prepared;
      try (InputStream in=new FileInputStream(zip)) { prepared=BackupStreamCodec.decode(in,dir); }
      if (prepared.backupJson.length() <= 8L*1024*1024 || prepared.assets.size()!=1) throw new AssertionError("stream round trip missing text or image");
      File materialized = new File(dir,"local.json");
      try (OutputStream out=new FileOutputStream(materialized)) { prepared.materialize(out, (name, asset) -> "content://restored/"+name); }
      String prefix; try (Reader r=new InputStreamReader(new FileInputStream(materialized),StandardCharsets.UTF_8)) { char[] chars=new char[300]; int n=r.read(chars); prefix=new String(chars,0,n); }
      if (prefix.contains("do-not-export") || prefix.contains("also-secret") || prefix.contains("provider-secret") || !prefix.contains("content://restored/0.png") || !prefix.contains("\"token_count\":12") || !prefix.contains("\"total_tokens\":34")) throw new AssertionError("redaction, usage preservation or image mapping failed");
      // A separate real library exercises staging, unknown fields and failed-import rollback.
      SqliteLibraryStore store = new SqliteLibraryStore(getTargetContext(), new File(dir, "isolated.db").getAbsolutePath());
      try {
        store.initialize();
        org.json.JSONObject original;
        try (InputStream sample = getContext().getAssets().open("field-provenance.json")) {
          original = new Library(new org.json.JSONObject(
              new String(sample.readAllBytes(), StandardCharsets.UTF_8))).snapshot();
        }
        original.put("future_field", new org.json.JSONObject().put("nested", new org.json.JSONArray().put("kept")));
        File small = new File(dir, "small.json");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(small), StandardCharsets.UTF_8)) { w.write(original.toString()); }
        long before = store.getGeneration();
        com.lexinote.nativeapp.storage.contract.CommitResult imported = store.importJson(small, before);
        if (!imported.isSuccess()) throw new AssertionError("small import failed: " + imported.getMessage());
        long active = store.getGeneration();
        org.json.JSONObject read = store.loadSnapshot().getRawDocument();
        if (!"kept".equals(read.getJSONObject("future_field").getJSONArray("nested").getString(0))) throw new AssertionError("unknown field lost");
        File exported = new File(dir, "field-provenance-export.json");
        try (OutputStream out = new FileOutputStream(exported)) { store.exportJson(out); }
        File redacted = new File(dir, "field-provenance-backup.json");
        try (InputStream in = new FileInputStream(exported); OutputStream out = new FileOutputStream(redacted)) {
          BackupStreamCodec.encodeJson(in, out);
        }
        if (!store.importJson(redacted, active).isSuccess())
          throw new AssertionError("provenance backup reimport failed");
        active = store.getGeneration();
        org.json.JSONObject marks = store.loadSnapshot().getRawDocument()
            .getJSONArray("vocabularies").getJSONObject(0).getJSONArray("meanings")
            .getJSONObject(0).getJSONObject("field_meta");
        if (marks.getJSONObject("zh_definition").getBoolean("is_ai_generated")
            || !marks.getJSONObject("example").getBoolean("is_ai_generated"))
          throw new AssertionError("provenance changed during backup roundtrip");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(small), StandardCharsets.UTF_8)) {
          w.write("{\"version\":\"1.1\",\"vocabularies\":[{\"id\":\"bad\"}],\"notebooks\":[]}");
        }
        if (store.importJson(small, active).isSuccess() || store.getGeneration() != active) throw new AssertionError("invalid import changed active generation");
        if (!"kept".equals(store.loadSnapshot().getRawDocument().getJSONObject("future_field").getJSONArray("nested").getString(0))) throw new AssertionError("invalid import polluted active data");
        if (!store.rollback(active).isSuccess()) throw new AssertionError("failed import damaged previous generation");
      } finally { store.close(); }
      report.putString("result","streamed >8 MiB codec, asset mapping, redaction, checksum/missing-asset rejection, field provenance roundtrip and failed import rollback passed");
    } catch (Throwable e) { result=Activity.RESULT_CANCELED; report.putString("error",e.toString()); }
    finish(result,report);
  }

  private static void rejectArchive(File directory, boolean missingImage) throws Exception {
    byte[] json = (missingImage
        ? "{\"version\":\"2.2\",\"input_images\":[\"lexinote-asset:assets/0.png\"]}"
        : "{\"version\":\"2.2\"}").getBytes(StandardCharsets.UTF_8);
    java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(json);
    String expected = missingImage ? Long.toHexString(crc.getValue()) : "wrong-checksum";
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (java.util.zip.ZipOutputStream archive = new java.util.zip.ZipOutputStream(bytes)) {
      archive.putNextEntry(new java.util.zip.ZipEntry("backup.json")); archive.write(json); archive.closeEntry();
      archive.putNextEntry(new java.util.zip.ZipEntry("checksums.json"));
      archive.write(new org.json.JSONObject().put("backup.json", expected).toString().getBytes(StandardCharsets.UTF_8));
      archive.closeEntry();
    }
    boolean rejected = false;
    try (BackupStreamCodec.Prepared ignored = BackupStreamCodec.decode(new ByteArrayInputStream(bytes.toByteArray()), directory)) { }
    catch (IOException expectedFailure) { rejected = true; }
    if (!rejected) throw new AssertionError(missingImage ? "missing image accepted" : "wrong checksum accepted");
  }
}
