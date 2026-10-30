package com.lexinote.nativeapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContextWrapper;
import android.os.Bundle;
import java.io.File;
import org.json.JSONObject;

/** Exercises real Android JSON and AtomicFile in an isolated test cache, without opening a screen. */
public final class ReviewPlansInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

  @Override public void onStart() {
    Bundle result = new Bundle();
    int outcome = Activity.RESULT_OK;
    File directory = new File(getTargetContext().getCacheDir(), "review-plans-" + System.nanoTime());
    try {
      if (!directory.mkdirs()) throw new IllegalStateException("Cannot create isolated test directory");
      Repository repository = new Repository(new ContextWrapper(getTargetContext()) {
        @Override public File getFilesDir() { return directory; }
      });
      Library library = repository.load();
      String note = library.createNotebook("设备测试", null);
      String word = library.saveResult(new JSONObject("{word:'device',meanings:[{zh_definition:'设备'}]}"),
          new String[] {note}, new JSONObject());
      long now = System.currentTimeMillis();
      String plan = library.reviewPlans().create("每日", note, 1, 1, new int[] {1,2,3,4,5,6,7},
          "Asia/Shanghai", false, now);
      library.reviewPlans().dailyQueue(plan, now);
      library.reviewPlans().record(plan, word, "再次", "device-again", 10, now);
      repository.save(library);
      library = repository.load();
      check(library.reviewPlans().dailyQueue(plan, now).optJSONArray("queue").length() == 1, "Pending task lost");
      library.reviewPlans().record(plan, word, "简单", "device-easy", 20, now);
      repository.save(library);
      library = repository.load();
      String date = library.reviewPlans().dailyQueue(plan, now).optString("date");
      JSONObject totals = library.reviewPlans().statistics(plan, date, date).optJSONObject("totals");
      check(totals.optInt("attempts") == 2 && totals.optInt("new_completed") == 1, "Statistics incorrect");
      check(library.find("vocabularies", word).optInt("review_count") == 0, "Global vocabulary was mutated");
      byte[] zip = BackupArchive.encode(library.backup(), uri -> new byte[0]);
      JSONObject roundtrip = BackupArchive.decode(zip).backup;
      Library.validate(roundtrip);
      repository.restore(new Library().backup(), library);
      check(repository.rollback().reviewPlans().state(plan, word).optInt("review_count") == 2, "Rollback lost review state");
      repository.restore(roundtrip, repository.load());
      check(repository.load().reviewPlans().dailyQueue(plan, now).optJSONArray("queue").length() == 0, "Restore lost completion");
      result.putString("stream", "\nPASS: review plan Android JSON, AtomicFile reload, retry, statistics, ZIP restore and rollback\n");
    } catch (Throwable e) {
      outcome = Activity.RESULT_CANCELED;
      result.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(e));
    } finally {
      // Only files created by Repository within this unique test directory.
      for (String name : new String[] {"library.json", "library.json.bak", "library.json.new",
          "before-restore.json", "before-restore.json.bak", "before-restore.json.new"}) {
        File file = new File(directory, name);
        if (file.exists() && !file.delete()) result.putString("cleanup", "Test cache file retained: " + name);
      }
      if (directory.exists() && !directory.delete()) result.putString("cleanup", "Test cache directory retained");
    }
    finish(outcome, result);
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
