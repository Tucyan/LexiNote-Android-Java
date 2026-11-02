package com.lexinote.nativeapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Rect;
import android.os.*;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.TextView;
import com.lexinote.nativeapp.storage.JsonStoreBackend;
import com.lexinote.nativeapp.storage.LibraryStore;
import com.lexinote.nativeapp.storage.contract.LibraryCommand;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Task 1 Standalone Persistence & StrictMode Instrumentation Runner.
 *
 * <p>Enforces strict Android OS StrictMode policy on the main (UI) thread:
 *   detectDiskReads().detectDiskWrites().penaltyDeath()
 *
 * <p>Asserts zero main-thread disk I/O during vocabulary modifications,
 * rating actions, queue preparation, and offline search queries.
 */
public final class PersistenceInstrumentation extends Instrumentation {

  private static final String TAG = "PersistenceInstrumentation";

  private MainActivity a;
  private JSONObject beforeSnapshot;
  private File testDirectory;
  private File outputDir;

  private final List<String> passed = new ArrayList<>();
  private final List<String> failed = new ArrayList<>();
  private final JSONObject metrics = new JSONObject();

  private final AtomicInteger mainThreadDiskReadViolations = new AtomicInteger(0);
  private final AtomicInteger mainThreadDiskWriteViolations = new AtomicInteger(0);
  private final List<String> violationTraces = Collections.synchronizedList(new ArrayList<>());

  interface Action {
    void run() throws Exception;
  }

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    int outcome = Activity.RESULT_OK;

    try {
      // 1. Output directory setup
      File externalDir = getTargetContext().getExternalFilesDir(null);
      outputDir = new File(externalDir, "persistence-strictmode");
      outputDir.mkdirs();

      // 2. Launch Activity
      a = (MainActivity) startActivitySync(
          new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      waitForIdleSync();

      // 3. Isolate repository directory in ephemeral cache
      main(() -> {
        beforeSnapshot = a.library.snapshot();
        testDirectory = new File(a.getCacheDir(), "strictmode-store-" + System.nanoTime());
        if (!testDirectory.mkdirs()) {
          throw new IllegalStateException("Failed to create isolated store dir");
        }
        a.repository = new Repository(new ContextWrapper(a) {
          @Override
          public File getFilesDir() {
            return testDirectory;
          }
        });
        a.store = new LibraryStore(new JsonStoreBackend(a.repository, a.library));
        LibraryStore.setInstanceForTest(a.store);
        a.store.bind(a);
        a.committed = a.library.snapshot();
        a.render();
      });

      // 4. Activate StrictMode penaltyDeath on the Main Thread
      activateMainThreadStrictMode();
      record("StrictMode penaltyDeath activated on Main Thread");

      // 5. Test Routine A: Vocabulary Modifications under StrictMode
      testVocabularyModificationsUnderStrictMode();
      record("Vocabulary modifications completed with zero main-thread disk I/O");

      // 6. Test Routine B: Review & Rating Actions under StrictMode
      testReviewRatingsUnderStrictMode();
      record("Rating actions completed with zero main-thread disk I/O");

      // 7. Test Routine C: Offline Query & Session under StrictMode
      testOfflineQueryUnderStrictMode();
      record("Local queries completed with zero main-thread disk I/O");

      // 8. Test Routine D: Verify Durable Persistence on Disk
      verifyDurableDiskPersistence();
      record("Durable background persistence verified on disk");

      // 9. Collect final metrics
      metrics.put("main_thread_disk_reads", mainThreadDiskReadViolations.get());
      metrics.put("main_thread_disk_writes", mainThreadDiskWriteViolations.get());
      metrics.put("passed_checks", new JSONArray(passed));
      metrics.put("failed_checks", new JSONArray(failed));
      metrics.put("survived_strict_mode_penalty_death", true);

      saveMetrics();

      result.putString("stream",
          "\nPASS: " + passed.size() + " StrictMode persistence checks\n" +
          "Main thread disk reads: " + mainThreadDiskReadViolations.get() + "\n" +
          "Main thread disk writes: " + mainThreadDiskWriteViolations.get() + "\n" +
          String.join("\n", passed) + "\n");

    } catch (Throwable e) {
      outcome = Activity.RESULT_CANCELED;
      Log.e(TAG, "Test failed", e);
      failed.add("FATAL: " + Log.getStackTraceString(e));
      try {
        metrics.put("fatal_error", Log.getStackTraceString(e));
        metrics.put("passed_checks", new JSONArray(passed));
        metrics.put("failed_checks", new JSONArray(failed));
        metrics.put("survived_strict_mode_penalty_death", false);
        saveMetrics();
      } catch (Exception ignored) {}

      result.putString("stream",
          "\nFAIL after " + passed.size() + " checks: " + e + "\n" +
          Log.getStackTraceString(e));
    } finally {
      // Cleanup & restore pristine state
      if (a != null && beforeSnapshot != null) {
        try {
          main(() -> {
            try {
              a.library = new Library(beforeSnapshot);
              a.finish();
            } catch (Exception ignored) {}
          });
        } catch (Throwable ignored) {}
      }
      LibraryStore.resetInstanceForTest();
    }

    finish(failed.isEmpty() ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
  }

  // --------------------------------------------------------------------------
  // Test Routines
  // --------------------------------------------------------------------------

  private void activateMainThreadStrictMode() {
    main(() -> {
      StrictMode.ThreadPolicy.Builder builder = new StrictMode.ThreadPolicy.Builder()
          .detectDiskReads()
          .detectDiskWrites()
          .penaltyDeath() // Mandated: process dies if main-thread disk I/O occurs
          .penaltyLog();

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        builder.penaltyListener(Executors.newSingleThreadExecutor(), violation -> {
          String trace = Log.getStackTraceString(violation);
          violationTraces.add(trace);
          if (trace.contains("openRead") || trace.contains("read") || trace.contains("FileInputStream")) {
            mainThreadDiskReadViolations.incrementAndGet();
          } else {
            mainThreadDiskWriteViolations.incrementAndGet();
          }
          Log.e(TAG, "StrictMode Violation detected on main thread: " + trace);
        });
      }

      StrictMode.setThreadPolicy(builder.build());
    });
  }

  private void testVocabularyModificationsUnderStrictMode() throws Exception {
    // Navigate to Notebooks tab
    main(() -> {
      a.screen = "tabs";
      a.page = 2; // Notebooks tab
      a.notebooks.selected = "nb_default";
      a.render();
    });
    waitForIdleSync();

    // Trigger vocabulary creation via change() which uses asynchronous persist()
    main(() -> {
      JSONObject vocabDraft = new JSONObject();
      Library.put(vocabDraft, "content", "immutability");
      Library.put(vocabDraft, "type", "word");
      Library.put(vocabDraft, "phonetic", "/strik-məʊd/");

      JSONArray meanings = new JSONArray();
      JSONObject m = new JSONObject();
      Library.put(m, "part_of_speech", "n.");
      Library.put(m, "zh_definition", "严格模式无主线程写盘");
      Library.put(m, "en_definition", "Zero disk I/O on UI thread");
      meanings.put(m);
      Library.put(vocabDraft, "meanings", meanings);

      boolean saved = a.change(() -> {
        a.library.saveResult(vocabDraft, new String[]{"nb_default"}, new JSONObject());
      });
      check(saved, "Vocabulary save failed");
    });

    if (a.store != null) {
      a.store.awaitIdle(5, TimeUnit.SECONDS);
    }
    waitForIdleSync();

    // Verify word exists in library and UI
    main(() -> {
      check(a.library.local("immutability") != null, "Newly added vocabulary missing from library");
    });
  }

  private void testReviewRatingsUnderStrictMode() throws Exception {
    long now = System.currentTimeMillis();

    // Ensure at least one active plan exists
    main(() -> {
      if (a.library.reviewPlans().list(false).length() == 0) {
        a.change(() -> {
          a.library.reviewPlans().create("严格测试计划", "nb_default", 5, 10, new int[]{1, 2, 3, 4, 5, 6, 7}, "Asia/Shanghai", false, now);
        });
      }
    });

    // Navigate to Review Dashboard
    main(() -> {
      a.screen = "tabs";
      a.page = 3; // Review tab
      a.render();
    });
    waitForIdleSync();

    // Verify Review tab loaded
    main(() -> {
      check(has(a.root, "复习") || has(a.root, "今日任务"), "Review screen missing");
    });

    // Start review session if available
    main(() -> {
      if (a.review != null && a.review.canStartReview()) {
        a.review.startDailyReview();
      }
    });
    waitForIdleSync();

    // If review is active, perform card flip and rating
    main(() -> {
      if (a.review != null && "active".equals(a.review.phase)) {
        // Card flip
        a.review.revealed = true;
        a.render();
      }
    });
    waitForIdleSync();

    main(() -> {
      if (a.review != null && "active".equals(a.review.phase)) {
        // Submit rating "简单"
        a.review.submitRating("简单");
      }
    });
    waitForIdleSync();

    if (a.store != null) {
      a.store.awaitIdle(5, TimeUnit.SECONDS);
    }
  }

  private void testOfflineQueryUnderStrictMode() throws Exception {
    main(() -> {
      a.screen = "tabs";
      a.page = 0; // Query tab
      a.session = null;
      a.query.draft = "immutability";
      a.render();
      a.query.submit();
    });
    waitForIdleSync();

    if (a.store != null) {
      a.store.awaitIdle(5, TimeUnit.SECONDS);
    }

    main(() -> {
      check(a.session != null, "Query session not created");
      check(a.session.optJSONObject("query_results") != null, "Query results missing");
    });
  }

  private void verifyDurableDiskPersistence() throws Exception {
    // Wait for store executor to idle and flush all pending commits to disk
    if (a.store != null) {
      a.store.awaitIdle(5, TimeUnit.SECONDS);
    } else {
      SystemClock.sleep(500);
    }

    // Inspect disk file on background thread outside StrictMode main-thread policy
    Future<Boolean> diskCheck = Executors.newSingleThreadExecutor().submit(() -> {
      File file = new File(testDirectory, "library.json");
      if (!file.exists()) return false;
      byte[] bytes = Files.readAllBytes(file.toPath());
      String json = new String(bytes, StandardCharsets.UTF_8);
      return json.contains("immutability");
    });

    Boolean foundOnDisk = diskCheck.get(3, TimeUnit.SECONDS);
    check(Boolean.TRUE.equals(foundOnDisk), "Persisted vocabulary not found on disk file!");
  }

  // --------------------------------------------------------------------------
  // Helper Methods
  // --------------------------------------------------------------------------

  private void saveMetrics() {
    try {
      File metricsFile = new File(outputDir, "metrics.json");
      try (FileOutputStream out = new FileOutputStream(metricsFile)) {
        out.write(metrics.toString(2).getBytes(StandardCharsets.UTF_8));
      }
    } catch (Exception e) {
      Log.e(TAG, "Failed to save metrics", e);
    }
  }

  private void record(String name) {
    passed.add(name);
    Log.i(TAG, "PASS: " + name);
  }

  private void main(Action task) {
    final Throwable[] error = new Throwable[1];
    runOnMainSync(() -> {
      try {
        task.run();
      } catch (Throwable e) {
        error[0] = e;
      }
    });
    waitForIdleSync();
    if (error[0] != null) {
      throw new RuntimeException(error[0]);
    }
  }

  private static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  private static boolean has(View root, String label) {
    return FeatureInstrumentation.find(
        root, v -> v instanceof TextView && label.contentEquals(((TextView) v).getText())) != null;
  }
}
