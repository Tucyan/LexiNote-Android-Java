package com.lexinote.nativeapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import com.lexinote.nativeapp.storage.LibraryStore;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Two invocations with the same probe: committed data across a real app process restart. */
public final class ProcessReopenInstrumentation extends Instrumentation {
  private Bundle args;
  private MainActivity activity;
  private boolean installed;
  @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); args = arguments; start(); }
  @Override public void onStart() {
    Bundle report = new Bundle(); int status = Activity.RESULT_OK;
    try {
      String mode = args.getString("mode", ""), probe = args.getString("probe", "");
      check(probe.matches("[A-Za-z0-9_-]{1,40}"), "invalid probe id");
      check(mode.equals("seed") || mode.equals("read"), "mode must be seed or read");
      File dir = new File(getTargetContext().getCacheDir(), "process-reopen-" + probe);
      File db = new File(dir, "library.db"), pidFile = new File(dir, "seed.pid");
      if (mode.equals("seed")) check(dir.mkdir(), "probe already exists or cannot be created");
      else check(dir.isDirectory() && db.isFile() && pidFile.isFile(), "seed files missing");
      int seedPid = mode.equals("read") ? Integer.parseInt(new String(Files.readAllBytes(pidFile.toPath()), StandardCharsets.UTF_8)) : Process.myPid();
      if (mode.equals("read")) check(seedPid != Process.myPid(), "read invocation is in seed process");
      SqliteLibraryStore backend = new SqliteLibraryStore(getTargetContext(), db.getAbsolutePath());
      backend.initialize();
      LibraryStore.setInstanceForTest(new LibraryStore(backend)); installed = true;
      activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      DeviceAsync.ready(this, activity);
      final String word = "reopenprobe";
      if (mode.equals("seed")) {
        DeviceAsync.change(this, activity, () -> {
          JSONObject meta = new JSONObject().put("zh_definition", new JSONObject().put("is_ai_generated", true));
          JSONObject meaning = new JSONObject().put("zh_definition", "进程重启测试释义").put("field_meta", meta);
          JSONObject draft = new JSONObject().put("word", word).put("type", "word").put("meanings", new JSONArray().put(meaning));
          activity.library.saveResult(draft, new String[]{"nb_default"}, new JSONObject());
        });
        DeviceAsync.drain(this, activity);
        verifySnapshot(backend.loadSnapshot().getRawDocument(), word);
        try (FileOutputStream out = new FileOutputStream(pidFile)) {
          out.write(Integer.toString(Process.myPid()).getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
        }
      } else {
        verifySnapshot(backend.loadSnapshot().getRawDocument(), word);
        runOnMainSync(() -> {
          check(!activity.recoveryOnly, "Activity entered recovery mode");
          JSONObject found = activity.library.local(word);
          check(found != null, "Activity did not load persisted vocabulary");
          EditText input = (EditText) FeatureInstrumentation.find(activity.root, v -> v instanceof EditText);
          check(input != null, "home query input missing"); input.setText(word);
          View button = FeatureInstrumentation.find(activity.root, v -> v instanceof TextView
              && "✦  智能查询".contentEquals(((TextView) v).getText()));
          check(button != null && button.performClick(), "home query button missing");
          JSONObject result = activity.session == null ? null : activity.session.optJSONObject("query_results");
          check(result != null && result.optBoolean("is_from_local") && word.equals(result.optString("word")), "Activity local query failed");
          check("进程重启测试释义".equals(result.optJSONArray("meanings").optJSONObject(0).optString("zh_definition")), "query meaning missing");
        });
        DeviceAsync.drain(this, activity);
      }
      report.putString("stream", "\nPASS mode=" + mode + " probe=" + probe + " seed_pid=" + seedPid + " current_pid=" + Process.myPid() + "\n");
    } catch (Throwable error) { status = Activity.RESULT_CANCELED; report.putString("stream", "\nFAIL " + android.util.Log.getStackTraceString(error)); }
    finally {
      if (activity != null) runOnMainSync(() -> activity.finish());
      if (installed) LibraryStore.resetInstanceForTest();
    }
    finish(status, report);
  }
  private static void verifySnapshot(JSONObject snapshot, String word) throws Exception {
    Library library = new Library(snapshot); JSONObject vocab = library.local(word);
    check(vocab != null, "persisted vocabulary missing");
    JSONObject meaning = vocab.getJSONArray("meanings").getJSONObject(0);
    check("进程重启测试释义".equals(meaning.optString("zh_definition")), "persisted meaning missing");
    check(meaning.getJSONObject("field_meta").getJSONObject("zh_definition").getBoolean("is_ai_generated"), "AI provenance missing");
    JSONArray ids = library.find("notebooks", "nb_default").getJSONArray("vocabulary_ids");
    boolean linked = false;
    for (int i = 0; i < ids.length(); i++) if (vocab.optString("id").equals(ids.optString(i))) linked = true;
    check(linked, "default notebook reference missing");
  }
  private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
