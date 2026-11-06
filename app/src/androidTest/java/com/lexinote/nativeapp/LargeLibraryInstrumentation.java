package com.lexinote.nativeapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Legal >8MiB library through SQLite, streaming ZIP and restore; all files are isolated. */
public final class LargeLibraryInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  @Override public void onStart() {
    Bundle result = new Bundle(); int code = Activity.RESULT_OK;
    SqliteLibraryStore store = null;
    try {
      File dir = new File(getTargetContext().getCacheDir(), "large-library-" + System.nanoTime());
      if (!dir.mkdirs()) throw new IOException("Cannot create isolated directory");
      File source = new File(dir, "source.json");
      JSONObject roots = new Library().snapshot();
      String definition = new String(new char[4096]).replace('\0', 'x');
      try (Writer out = new OutputStreamWriter(new FileOutputStream(source), StandardCharsets.UTF_8)) {
        out.write("{\"version\":\"1.1\",\"vocabularies\":[");
        for (int i=0;i<2400;i++) {
          if(i>0) out.write(',');
          JSONObject word = new JSONObject().put("id","large-"+i).put("content","entry "+i)
              .put("type","phrase").put("meanings",new JSONArray().put(new JSONObject()
                  .put("id","meaning-"+i).put("zh_definition",definition).put("annotations",new JSONArray())));
          out.write(word.toString());
        }
        out.write("],\"notebooks\":"); out.write(roots.getJSONArray("notebooks").toString());
        out.write(",\"settings\":{},\"future_field\":{\"value\":\"retained\"}}");
      }
      if(source.length()<=8L*1024*1024) throw new AssertionError("Fixture below required size");
      store = new SqliteLibraryStore(getTargetContext(),new File(dir,"library.db").getAbsolutePath());
      store.initialize();
      if(!store.importJson(source,store.getGeneration()).isSuccess()) throw new AssertionError("Large import rejected");
      File exported=new File(dir,"export.json"),zip=new File(dir,"backup.zip");
      try(OutputStream out=new FileOutputStream(exported)){store.exportJson(out);}
      try(InputStream in=new FileInputStream(exported);OutputStream out=new FileOutputStream(zip)){
        BackupStreamCodec.encode(in,out,uri->{throw new IOException("Unexpected image");});
      }
      BackupStreamCodec.Prepared prepared;
      try(InputStream in=new FileInputStream(zip)){prepared=BackupStreamCodec.decode(in,dir);}
      File materialized=new File(dir,"restore.json");
      try(OutputStream out=new FileOutputStream(materialized)){
        prepared.materialize(out,(name,asset)->{throw new IOException("Unexpected asset");});
      }
      if(!store.importJson(materialized,store.getGeneration()).isSuccess()) throw new AssertionError("Large round trip rejected");
      JSONObject actual=store.loadSnapshot().getRawDocument();
      if(actual.getJSONArray("vocabularies").length()!=2400 ||
          !"retained".equals(actual.getJSONObject("future_field").getString("value")))
        throw new AssertionError("Large round trip lost entities or unknown data");
      result.putString("stream","PASS: legal large SQLite/ZIP round trip; source_bytes="+source.length());
    } catch(Throwable error) { code=Activity.RESULT_CANCELED; result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(error)); }
    finally { if(store!=null)store.close(); }
    finish(code,result);
  }
}
