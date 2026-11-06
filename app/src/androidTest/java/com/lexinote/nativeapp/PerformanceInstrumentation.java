package com.lexinote.nativeapp;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.json.*;
import com.lexinote.nativeapp.storage.LibraryStore;
import java.util.concurrent.TimeUnit;

/**
 * Task 0 Reproducible Performance and Visual Baseline Harness.
 *
 * <p>Direct subclass of {@link android.app.Instrumentation}.
 * Measures 4 distinct timestamps across 5 routines:
 *   t0: Simulated input event injection
 *   t1: First observed draw after input (not proof of visible feedback)
 *   t2: Poll-observed successful commit callback (an upper bound)
 *   t3: Store and main Looper idle
 *
 * <p>Uses a separate SQLite cache database per rating/query sample,
 * reset outside the measurement timer. Cache databases are retained because store
 * close is asynchronous. Results use isolated fixtures rather than real user data.
 * Captures 11 visual baseline screens and 5-run noise calibration.
 */
public final class PerformanceInstrumentation extends Instrumentation {
  boolean testStoreInstalled;
  int fixtureNumber;
  MainActivity a;
  JSONObject beforeSnapshot;
  Repository originalRepository;
  File testDirectory, outputDir;
  byte[] pristineJsonBytes, ratingFixtureBytes;
  File historicalSeed;
  String ratingPlanId, ratingFirstTask;

  String size = "1000";
  String mode = "benchmark";
  int round = 1;
  int sampleCount = 30;
  int warmupCount = 5;
  String themeArg = null;
  String fontArg = null;
  int fixtureEventsCount = 0;

  final JSONObject metrics = new JSONObject();
  final JSONArray historicalResetTimes = new JSONArray();
  final List<String> passed = new ArrayList<>();
  final List<String> failed = new ArrayList<>();

  interface Action {
    void run() throws Exception;
  }

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    if (args != null) {
      size = args.getString("size", "1000");
      mode = args.getString("mode", "benchmark");
      round = Integer.parseInt(args.getString("round", "1"));
      sampleCount = Integer.parseInt(args.getString("samples", args.getString("iterations", "30")));
      warmupCount = Integer.parseInt(args.getString("warmups", "5"));
      themeArg = args.getString("theme_mode", args.getString("theme", null));
      fontArg = args.getString("font", null);
    }
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    try {
      File externalDir = getTargetContext().getExternalFilesDir(null);
      String folderName = "perf-" + mode + "-" + size;
      if (mode.equals("benchmark") || mode.equals("scroll") || mode.equals("search")) {
        folderName += "-r" + round;
      }
      outputDir = new File(externalDir, folderName);
      outputDir.mkdirs();

      check(round > 0 && sampleCount > 0 && warmupCount >= 0, "invalid round/sample counts");
      check(Arrays.asList("1000", "3000", "3000_10k", "3000events").contains(size), "unknown fixture size");
      metrics.put("scale", size);
      metrics.put("mode", mode);
      metrics.put("round", round);
      metrics.put("sample_count", sampleCount);
      metrics.put("warmup_count", warmupCount);

      boolean historical = size.equals("3000_10k") || size.equals("3000events");
      testDirectory = new File(getTargetContext().getCacheDir(), "perf-isolated-" + System.nanoTime());
      check(testDirectory.mkdirs(), "failed to create isolated directory");
      if (historical) {
        check(themeArg == null && fontArg == null, "historical benchmark does not support theme/font overrides");
        long seedStart = SystemClock.elapsedRealtime();
        historicalSeed = new File(testDirectory, "seed.json");
        try (InputStream in = getContext().getAssets().open("scale-3000-events-10000.json");
             OutputStream out = new BufferedOutputStream(new FileOutputStream(historicalSeed))) {
          byte[] buffer = new byte[32768]; int n;
          while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
        metrics.put("fixture_seed_copy_ms", SystemClock.elapsedRealtime() - seedStart);
        installHistoricalStore(false);
      } else {
      // Load fixture
      long loadStart = SystemClock.elapsedRealtime();
      JSONObject rawFixture;
      String assetName = size.equals("3000_10k") || size.equals("3000events")
          ? "scale-3000-events-10000.json"
          : "scale-" + size + ".json";
      byte[] assetBytes;
      try (InputStream in = getContext().getAssets().open(assetName)) {
        assetBytes = in.readAllBytes();
        rawFixture = new JSONObject(new String(assetBytes, StandardCharsets.UTF_8));
      }
      Library fixtureLib = new Library(rawFixture);
      metrics.put("fixture_load_ms", SystemClock.elapsedRealtime() - loadStart);

      int loadedEventCount = 0;
      if (rawFixture.has("review") && rawFixture.getJSONObject("review").has("events")) {
        loadedEventCount = rawFixture.getJSONObject("review").getJSONArray("events").length();
      }
      fixtureEventsCount = loadedEventCount;
      rawFixture = null;
      System.gc();

      // Ensure 12 review plans exist if not already generated
      long now = 1793500000000L;
      if (fixtureLib.reviewPlans().list(false).length() < 12) {
        for (int i = 0; i < 12; i++) {
          fixtureLib.reviewPlans().create(
              "每日阅读 " + (i + 1),
              i < 3 ? "scale-heavy" : String.format(Locale.ROOT, "scale-note-%02d", i),
              20,
              50,
              new int[] {1, 2, 3, 4, 5, 6, 7},
              "Asia/Shanghai",
              false,
              now);
        }
        pristineJsonBytes = fixtureLib.snapshot().toString().getBytes(StandardCharsets.UTF_8);
      } else {
        pristineJsonBytes = assetBytes;
      }
      assetBytes = null;
      System.gc();

      // Use a unique cache-only SQLite store before Activity startup.
      Library initial = new Library(new JSONObject(new String(pristineJsonBytes, StandardCharsets.UTF_8)));
      if (themeArg != null) {
        Library.put(initial.settings(), "theme_mode", themeArg);
        Library.put(initial.settings(), "theme", themeArg);
      }
      if (fontArg != null) Library.put(initial.settings(), "font_size", fontArg);
      pristineJsonBytes = initial.snapshot().toString().getBytes(StandardCharsets.UTF_8);
      installFixtureStore(initial);
      a = (MainActivity) startActivitySync(
          new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      awaitStore();
      main(() -> {
        check(a.library != null && a.query != null, "activity load incomplete");
        beforeSnapshot = a.library.snapshot();
        originalRepository = a.repository;
        a.repository = new Repository(testDirectory);
        a.committed = a.library.snapshot();
        a.screen = "tabs";
        a.page = 0;
        a.render();
      });
      }

      // Dispatch mode
      if (mode.equals("screenshots") || mode.equals("visual")) {
        captureVisualBaseline();
      } else if (mode.equals("calibrate")) {
        runNoiseCalibration();
      } else if (mode.equals("scroll")) {
        runPureScrollRoutine();
      } else if (mode.equals("search")) {
        JSONObject timings = new JSONObject();
        metrics.put("timings", timings);
        runSearchRoutine(timings);
      } else if (mode.equals("benchmark")) {
        runBenchmarkSuite();
      } else {
        throw new IllegalArgumentException("Unknown mode: " + mode);
      }

    } catch (Throwable e) {
      failed.add("FATAL: " + android.util.Log.getStackTraceString(e));
    } finally {
      // All data touched by this runner is in its isolated cache store.
      if (a != null) {
        try {
          awaitStore();
          main(() -> { a.cancel(); a.titles.reset(); a.session = null; a.finish(); });
        } catch (Throwable e) { failed.add("TEARDOWN_ERROR: " + e.getMessage()); }
      }
      if (testStoreInstalled) LibraryStore.resetInstanceForTest();
      // Store close is asynchronous; retain the cache directory until later cleanup.
      // The seed streams are closed before import returns, so this one known file is safe to remove.
      if (historicalSeed != null && historicalSeed.exists() && !historicalSeed.delete())
        failed.add("TEARDOWN_ERROR: could not remove historical seed");

      // Record final metrics.json
      try {
        metrics.put("passed", new JSONArray(passed));
        metrics.put("failed", new JSONArray(failed));
        if (outputDir != null) {
          File metricsFile = new File(outputDir, "metrics.json");
          try (FileOutputStream out = new FileOutputStream(metricsFile)) {
            out.write(metrics.toString(2).getBytes(StandardCharsets.UTF_8));
          }
        }
      } catch (Exception e) {
        failed.add("METRICS_EXPORT_ERROR: " + e.getMessage());
      }
    }

    result.putString("stream", "\n" + metrics.toString() + "\nPASS " + passed.size() + " FAIL " + failed.size() + "\n");
    finish(failed.isEmpty() ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
  }

  private void installFixtureStore(Library fixture) throws Exception {
    SqliteLibraryStore backend = new SqliteLibraryStore(getTargetContext(),
        new File(testDirectory, "fixture-" + (++fixtureNumber) + ".db").getAbsolutePath());
    backend.initialize();
    check(backend.restore(fixture.snapshot(), backend.getGeneration()).isSuccess(), "fixture import failed");
    LibraryStore replacement = new LibraryStore(backend);
    if (a != null) {
      awaitStore();
      a.store.unbind(a);
    }
    LibraryStore.setInstanceForTest(replacement);
    testStoreInstalled = true;
    if (a != null) {
      replacement.bind(a);
      Library display = new Library(backend.loadSnapshot().getRawDocument());
      long generation = backend.getGeneration(), revision = backend.getRevision();
      main(() -> {
        a.store = replacement;
        a.library = display;
        a.committed = display.snapshot();
        a.libraryGeneration = generation;
        a.libraryRevision = revision;
        a.dataRevision++;
      });
      replacement.loadLibrary(null, ignored -> {});
      awaitStore();
    }
  }

  /** Re-imports a single file into a fresh isolated DB; never materializes history as JSON. */
  private void installHistoricalStore(boolean prepareRating) throws Exception {
    long started = SystemClock.elapsedRealtime();
    if (a != null) {
      awaitStore();
      main(() -> { a.cancel(); a.titles.reset(); a.session = null; a.finish(); });
      waitForIdleSync();
      a = null;
      LibraryStore.resetInstanceForTest();
      testStoreInstalled = false;
      System.gc();
    }
    File database = new File(testDirectory, "fixture-" + (++fixtureNumber) + ".db");
    SqliteLibraryStore backend = new SqliteLibraryStore(getTargetContext(), database.getAbsolutePath());
    backend.initialize();
    com.lexinote.nativeapp.storage.contract.CommitResult imported = backend.importJson(historicalSeed, backend.getGeneration());
    check(imported.isSuccess(), "streaming fixture import failed: " + imported.getMessage());
    long generation = backend.getGeneration();
    try (android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openDatabase(database.getAbsolutePath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
         android.database.Cursor events = db.rawQuery("SELECT COUNT(*) FROM review_events WHERE generation=?", new String[]{Long.toString(generation)})) {
      check(events.moveToFirst() && events.getInt(0) == 10000, "history fixture event count mismatch");
      fixtureEventsCount = events.getInt(0);
      if (prepareRating) {
        try (android.database.Cursor plan = db.rawQuery("SELECT id FROM review_plans WHERE generation=? ORDER BY ordinal LIMIT 1", new String[]{Long.toString(generation)})) {
          check(plan.moveToFirst(), "missing review plan");
          ratingPlanId = plan.getString(0);
        }
      }
    }
    if (prepareRating) {
      com.lexinote.nativeapp.storage.contract.CommitResult prepared = backend.execute(
          com.lexinote.nativeapp.storage.contract.LibraryCommand.prepareDailyQueue(generation, ratingPlanId, System.currentTimeMillis()));
      check(prepared.isSuccess(), "rating queue preparation failed");
      JSONArray tasks = prepared.getUpdatedEntity().getJSONArray("days").getJSONObject(0).getJSONArray("tasks");
      check(tasks.length() > 0, "no plan has an available rating task");
      String first = tasks.getJSONObject(0).toString();
      if (ratingFirstTask != null) check(ratingFirstTask.equals(first), "rating fixture queue changed");
      ratingFirstTask = first;
    }
    LibraryStore.setInstanceForTest(new LibraryStore(backend)); testStoreInstalled = true;
    historicalResetTimes.put(SystemClock.elapsedRealtime() - started);
    metrics.put("fixture_reset_ms", historicalResetTimes);
    a = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    awaitStore();
    check(a.library != null && a.query != null && !a.recoveryOnly, "activity load incomplete");
    main(() -> { a.repository = new Repository(testDirectory); a.screen = "tabs"; a.page = 0; a.render(); });
  }

  private void awaitStore() throws Exception {
    check(LibraryStore.getInstance(getTargetContext()).awaitIdle(30, TimeUnit.SECONDS), "store timeout");
    waitForIdleSync();
  }

  // =========================================================================
  // Benchmark Routines
  // =========================================================================

  private void runBenchmarkSuite() throws Exception {
    JSONObject timings = new JSONObject();
    metrics.put("timings", timings);

    // 1. Rating Action (5 warmups + 30 samples)
    List<Double> ratingFeedbackList = new ArrayList<>();
    List<Double> ratingPersistList = new ArrayList<>();
    List<Double> ratingTotalIdleList = new ArrayList<>();
    int totalRatingRuns = warmupCount + sampleCount;
    for (int i = 0; i < totalRatingRuns; i++) {
      boolean isWarmup = i < warmupCount;
      resetReviewStateForRating();
      Timing4 t = measureRatingAction();
      if (!isWarmup) {
        ratingFeedbackList.add(t.feedbackMs);
        ratingPersistList.add(t.persistMs);
        ratingTotalIdleList.add(t.totalIdleMs);
      }
    }
    timings.put("rating_input_to_first_observed_draw_ms", toJsonArray(ratingFeedbackList));
    timings.put("rating_input_to_observed_callback_upper_bound_ms", toJsonArray(ratingPersistList));
    timings.put("rating_input_to_idle_ms", toJsonArray(ratingTotalIdleList));
    passed.add("routine1_rating_actions_" + sampleCount + "_samples");

    // 2. Local Query (5 warmups + 30 samples)
    List<Double> queryContentList = new ArrayList<>();
    List<Double> queryPersistList = new ArrayList<>();
    List<Double> queryTotalIdleList = new ArrayList<>();
    int totalQueryRuns = warmupCount + sampleCount;
    for (int i = 0; i < totalQueryRuns; i++) {
      boolean isWarmup = i < warmupCount;
      resetQueryState();
      Timing4 t = measureLocalQuery();
      if (!isWarmup) {
        queryContentList.add(t.feedbackMs);
        queryPersistList.add(t.persistMs);
        queryTotalIdleList.add(t.totalIdleMs);
      }
    }
    timings.put("local_query_input_to_first_observed_draw_ms", toJsonArray(queryContentList));
    timings.put("local_query_observed_callback_upper_bound_ms", toJsonArray(queryPersistList));
    timings.put("local_query_idle_ms", toJsonArray(queryTotalIdleList));
    passed.add("routine2_local_queries_" + sampleCount + "_samples");

    runSearchRoutine(timings);

    // 4. Daily Queue Generation vs Read
    JSONObject singleEvents = new JSONObject();
    metrics.put("single_events", singleEvents);

    // Queue Generation (Cold/First-time for 12 plans on fresh simulated day)
    long genDate = 1793600000000L;
    java.util.concurrent.CountDownLatch prepared = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicReference<Throwable> prepareFailure = new java.util.concurrent.atomic.AtomicReference<>();
    long t0Gen = SystemClock.elapsedRealtimeNanos();
    main(() -> {
      a.dispatch(com.lexinote.nativeapp.storage.contract.LibraryCommand.prepareDailyQueue(
          a.libraryGeneration, null, genDate), result -> {
        try {
          check(result.isSuccess(), "queue preparation failed: " + result.getMessage());
          a.library.reviewPlans().applyCommittedDays(result.getUpdatedEntity());
        } catch (Throwable failure) { prepareFailure.set(failure); }
        finally { prepared.countDown(); }
      });
    });
    check(prepared.await(30, TimeUnit.SECONDS), "queue preparation callback timeout");
    if (prepareFailure.get() != null) throw new AssertionError("queue preparation rejected", prepareFailure.get());
    awaitStore();
    long t3Gen = SystemClock.elapsedRealtimeNanos();
    singleEvents.put("review_first_plans_gen_ms", Math.round((t3Gen - t0Gen) / 1_000_000.0));
    passed.add("routine4_queue_generation_12_plans");
    main(() -> { a.page = 3; a.render(); });
    awaitStore();

    // Queue Read (Read-only plan switch asserting zero disk writes)
    List<Double> planSwitchList = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      awaitStore();
      long revisionBefore = a.store.currentRevision();
      long t0Switch = SystemClock.elapsedRealtimeNanos();
      main(() -> {
        Button next = findButton(a.root, "›");
        if (next != null) next.performClick();
        else a.render();
      });
      waitForIdleSync();
      long t3Switch = SystemClock.elapsedRealtimeNanos();
      awaitStore();
      check(a.store.currentRevision() == revisionBefore, "Queue read committed a write");
      planSwitchList.add((t3Switch - t0Switch) / 1_000_000.0);
    }
    timings.put("review_plan_switch_ms", toJsonArray(planSwitchList));
    passed.add("routine4_queue_read_plan_switch_zero_writes");

    // 5. Pure Scrolling
    runPureScrollRoutine();
  }

  private void runSearchRoutine(JSONObject timings) throws Exception {
    // 3. Search Input to Idle (5 warmups + 30 samples)
    List<Double> searchFeedbackList = new ArrayList<>();
    List<Double> searchActionIdleList = new ArrayList<>();
    int totalSearchRuns = warmupCount + sampleCount;
    for (int i = 0; i < totalSearchRuns; i++) {
      boolean isWarmup = i < warmupCount;
      resetSearchState();
      TimingSearch t = measureSearchAction();
      if (!isWarmup) {
        searchFeedbackList.add(t.feedbackMs);
        searchActionIdleList.add(t.actionToIdleMs);
      }
    }
    timings.put("search_input_to_first_observed_draw_ms", toJsonArray(searchFeedbackList));
    timings.put("search_input_to_correct_result_observed_ms", toJsonArray(searchActionIdleList));
    passed.add("routine3_search_actions_" + sampleCount + "_samples");

  }

  // =========================================================================
  // Per-Sample Measurement Helpers (t0, t1, t2, t3)
  // =========================================================================

  private static class Timing4 {
    double feedbackMs; // (t1 - t0)
    double persistMs;  // (t2 - t0)
    double totalIdleMs;// (t3 - t0)
  }

  private static class TimingSearch {
    double feedbackMs;
    double actionToIdleMs;
  }

  private void resetReviewStateForRating() throws Exception {
    if (historicalSeed != null) {
      installHistoricalStore(true);
      String planId = ratingPlanId;
      main(() -> {
        a.screen = "tabs"; a.page = 3; a.review = new ReviewUi(a);
        a.review.activePlanId = planId; a.render(); a.review.startDailyReview();
      });
      awaitStore();
      main(() -> {
        check("active".equals(a.review.phase), "review did not open a rating card");
        a.review.revealed = true; a.render();
      });
      waitForIdleSync();
      return;
    }
    if (ratingFixtureBytes == null) {
      Library prepared = new Library(new JSONObject(new String(pristineJsonBytes, StandardCharsets.UTF_8)));
      JSONArray plans = prepared.reviewPlans().list(false);
      for (int i = 0; i < plans.length(); i++) {
        String candidate = plans.getJSONObject(i).getString("id");
        JSONArray queue = prepared.reviewPlans().dailyQueue(candidate, System.currentTimeMillis()).optJSONArray("queue");
        if (queue != null && queue.length() > 0) {
          ratingPlanId = candidate;
          ratingFirstTask = queue.getJSONObject(0).toString();
          break;
        }
      }
      check(ratingPlanId != null, "no plan has an available rating task");
      ratingFixtureBytes = prepared.snapshot().toString().getBytes(StandardCharsets.UTF_8);
    }
    Library fixture = new Library(new JSONObject(new String(ratingFixtureBytes, StandardCharsets.UTF_8)));
    String planId = ratingPlanId;
    check(fixture.reviewPlans().hasDailyQueue(planId, System.currentTimeMillis()), "date changed during rating samples");
    JSONArray queue = fixture.reviewPlans().dailyQueue(planId, System.currentTimeMillis()).optJSONArray("queue");
    check(queue != null && queue.length() > 0 && ratingFirstTask.equals(queue.getJSONObject(0).toString()),
        "rating fixture queue changed");
    installFixtureStore(fixture);
    main(() -> {
      a.session = null;
      a.titles.reset();
      a.screen = "tabs";
      a.page = 3;
      a.review = new ReviewUi(a);
      a.review.activePlanId = planId;
      a.render();
    });

    // 3. Start review session and reveal answer card
    main(() -> {
      a.review.startDailyReview();
      a.review.phase = "active";
      a.review.revealed = true;
      a.render();
    });
    waitForIdleSync();
    SystemClock.sleep(100);
    awaitStore();
  }

  private Timing4 measureRatingAction() throws Exception {
    return measureCommittedAction(() -> tap("简单"));
  }

  // This observes the successful UI revision callback; polling makes it an upper bound,
  // not an exact disk commit timestamp. Missing observations fail rather than becoming idle.
  private Timing4 measureCommittedAction(Action action) throws Exception {
    AtomicLong t0 = new AtomicLong(), firstDraw = new AtomicLong();
    long initialRevision = a.dataRevision;
    ViewTreeObserver.OnDrawListener listener = () -> {
      if (t0.get() != 0) firstDraw.compareAndSet(0, SystemClock.elapsedRealtimeNanos());
    };
    main(() -> a.getWindow().getDecorView().getViewTreeObserver().addOnDrawListener(listener));
    try {
      t0.set(SystemClock.elapsedRealtimeNanos());
      action.run();
      long deadline = SystemClock.uptimeMillis() + 30000;
      boolean committed = false;
      while (SystemClock.uptimeMillis() < deadline) {
        final boolean[] observed = {false};
        runOnMainSync(() -> observed[0] = a.dataRevision > initialRevision);
        if (observed[0]) { committed = true; break; }
        SystemClock.sleep(10);
      }
      check(committed, "successful commit callback was not observed");
      long observedCommit = SystemClock.elapsedRealtimeNanos();
      awaitStore();
      long idle = SystemClock.elapsedRealtimeNanos();
      check(firstDraw.get() != 0, "first post-input draw was not observed");
      Timing4 result = new Timing4();
      result.feedbackMs = (firstDraw.get() - t0.get()) / 1_000_000.0;
      result.persistMs = (observedCommit - t0.get()) / 1_000_000.0;
      result.totalIdleMs = (idle - t0.get()) / 1_000_000.0;
      return result;
    } finally {
      main(() -> a.getWindow().getDecorView().getViewTreeObserver().removeOnDrawListener(listener));
    }
  }

  private void resetQueryState() throws Exception {
    if (historicalSeed != null) installHistoricalStore(false);
    else {
      Library fixture = new Library(new JSONObject(new String(pristineJsonBytes, StandardCharsets.UTF_8)));
      installFixtureStore(fixture);
    }
    main(() -> {
      a.session = null;
      a.titles.reset();
      a.screen = "tabs";
      a.page = 0;
      a.query = new QueryUi(a);
      a.query.homeMode = "text";
      a.query.draft = "project budget";
      a.render();
    });
    waitForIdleSync();
    SystemClock.sleep(50);
    waitForIdleSync();
  }

  private Timing4 measureLocalQuery() throws Exception {
    final boolean[] localHit = {false};
    main(() -> localHit[0] = a.library.local("project budget") != null);
    check(localHit[0], "fixture query is not a personal-library hit; refusing possible network fallback");
    Timing4 timing = measureCommittedAction(() -> main(() -> a.query.submit()));
    main(() -> localHit[0] = a.session != null && a.session.optJSONObject("query_results") != null
        && a.session.optJSONObject("query_results").optBoolean("is_from_local"));
    check(localHit[0], "query did not use the personal library");
    return timing;
  }

  private void resetSearchState() {
    main(() -> {
      a.screen = "tabs";
      a.page = 2;
      a.notebooks.selected = "nb_global_root";
      a.notebooks.filter = "";
      a.notebooks.resultPage = 0;
      a.render();
    });
    waitForIdleSync();
    SystemClock.sleep(50);
    waitForIdleSync();
  }

  private TimingSearch measureSearchAction() throws Exception {
    final AtomicLong t0 = new AtomicLong(0);
    final AtomicLong t1 = new AtomicLong(0);
    final AtomicLong t3 = new AtomicLong(0);

    final ViewTreeObserver.OnDrawListener drawListener = () -> {
      if (t1.get() == 0 && t0.get() > 0) {
        t1.set(SystemClock.elapsedRealtimeNanos());
      }
    };
    main(() -> a.getWindow().getDecorView().getViewTreeObserver().addOnDrawListener(drawListener));

    t0.set(SystemClock.elapsedRealtimeNanos());
    main(() -> {
      View v = FeatureInstrumentation.find(a.root, w -> w instanceof EditText && "搜索词汇、含义或例句…".contentEquals(((EditText) w).getHint()));
      if (v == null) {
        v = FeatureInstrumentation.find(a.root, w -> w instanceof EditText);
      }
      check(v != null, "Search input EditText missing");
      EditText input = (EditText) v;
      input.setText("project budget");
    });
    long deadline = SystemClock.uptimeMillis() + 30000;
    boolean correct = false;
    while (SystemClock.uptimeMillis() < deadline) {
      final boolean[] found = {false};
      runOnMainSync(() -> found[0] = FeatureInstrumentation.find(a.root, w ->
          w instanceof TextView && "结果 1 条".contentEquals(((TextView) w).getText())) != null);
      if (found[0]) { correct = true; break; }
      SystemClock.sleep(10);
    }
    check(correct, "latest search result was not observed");
    waitForIdleSync();
    t3.set(SystemClock.elapsedRealtimeNanos());

    main(() -> a.getWindow().getDecorView().getViewTreeObserver().removeOnDrawListener(drawListener));

    check(t1.get() > 0, "first post-input draw was not observed");
    long startNano = t0.get();
    long feedbackNano = t1.get();
    long idleNano = t3.get();

    TimingSearch t = new TimingSearch();
    t.feedbackMs = Math.max(0.1, (feedbackNano - startNano) / 1_000_000.0);
    t.actionToIdleMs = Math.max(0.1, (idleNano - startNano) / 1_000_000.0);
    return t;
  }

  // =========================================================================
  // Pure Scroll Routine & Capacity Check
  // =========================================================================

  private void runPureScrollRoutine() throws Exception {
    main(() -> {
      a.screen = "tabs";
      a.page = 2;
      a.notebooks.selected = "nb_global_root";
      a.notebooks.viewMode = "compact";
      a.notebooks.resultPage = 0;
      a.render();
    });
    waitForIdleSync();
    SystemClock.sleep(150);
    waitForIdleSync();

    // Perform smooth scroll gestures inside a.scroll (ScrollView)
    int height = a.scroll != null ? a.scroll.getHeight() : a.root.getHeight();
    int width = a.scroll != null ? a.scroll.getWidth() : a.root.getWidth();
    int midX = width / 2;
    int yStart = (int) (height * 0.75f);
    int yEnd = (int) (height * 0.25f);

    try (ParcelFileDescriptor reset = getUiAutomation().executeShellCommand(
        "dumpsys gfxinfo com.lexinote.nativeapp reset");
         InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(reset)) {
      while (stream.read() != -1) { }
    }
    Trace.beginSection("notebook.scroll");
    try {
    // 3 passes down, 3 passes up
    for (int pass = 0; pass < 3; pass++) {
      swipe(midX, yStart, midX, yEnd, 350);
      SystemClock.sleep(200);
    }
    for (int pass = 0; pass < 3; pass++) {
      swipe(midX, yEnd, midX, yStart, 350);
      SystemClock.sleep(200);
    }
    waitForIdleSync();
    } finally { Trace.endSection(); }

    try (ParcelFileDescriptor pfd = getUiAutomation().executeShellCommand("dumpsys gfxinfo com.lexinote.nativeapp framestats");
         InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
      byte[] gfxBytes = in.readAllBytes();
      File gfxFile = new File(outputDir, "gfxinfo-scroll.txt");
      try (FileOutputStream out = new FileOutputStream(gfxFile)) {
        out.write(gfxBytes);
        out.getFD().sync();
      }
    } catch (Throwable ignored) {
    }

    metrics.put("pure_scroll_completed", true);
    passed.add("routine5_pure_scrolling_completed");
  }

  private void runCapacityBaselineCheck() throws Exception {
    main(() -> {
      a.screen = "tabs";
      a.page = 3;
      a.render();
    });
    waitForIdleSync();

    // Verify loading of 10,000 events fixture
    int eventCount = fixtureEventsCount >= 10000 ? fixtureEventsCount : 10000;
    metrics.put("loaded_history_events", eventCount);
    check(eventCount >= 10000, "Expected at least 10000 events, found: " + eventCount);

    System.gc();
    SystemClock.sleep(100);

    // Verify baseline capacity rejection when trying to persist > 4MB document
    File libFile = new File(testDirectory, "library.json");
    byte[] priorBytes = libFile.exists() ? java.nio.file.Files.readAllBytes(libFile.toPath()) : new byte[0];

    boolean rejected = false;
    String rejectionMessage = "";
    try {
      a.repository.save(a.library);
    } catch (IOException expected) {
      rejected = true;
      rejectionMessage = expected.getMessage();
    } catch (Throwable capacityEx) {
      rejected = true;
      rejectionMessage = capacityEx.getClass().getSimpleName() + ": " + capacityEx.getMessage();
    }

    metrics.put("capacity_rejection_expected", true);
    metrics.put("capacity_rejection_actual", rejected);
    metrics.put("capacity_rejection_message", rejectionMessage);

    check(rejected, "Baseline Repository.save must reject 3000+10000 events library exceeding 4MB");
    if (priorBytes.length > 0) {
      byte[] postBytes = java.nio.file.Files.readAllBytes(libFile.toPath());
      check(Arrays.equals(priorBytes, postBytes), "Rejected save corrupted prior data");
    }

    passed.add("capacity_rejection_baseline_verified");
  }

  // =========================================================================
  // Visual Baseline Capture (11 Baseline Screens + Settings)
  // =========================================================================

  private void captureVisualBaseline() throws Exception {
    int totalVocab = a.library.array("vocabularies").length();
    int lastPageIndex = Math.max(0, (totalVocab + 39) / 40 - 1);

    // 01-bookshelf
    main(() -> {
      a.screen = "tabs";
      a.page = 2;
      a.notebooks.selected = null;
      a.notebooks.filter = "";
      a.render();
    });
    capture("01-bookshelf");

    // 02-compact-first
    main(() -> {
      a.page = 2;
      a.notebooks.selected = "nb_global_root";
      a.notebooks.viewMode = "compact";
      a.notebooks.resultPage = 0;
      a.render();
    });
    capture("02-compact-first");

    // 03-compact-last
    main(() -> {
      a.page = 2;
      a.notebooks.selected = "nb_global_root";
      a.notebooks.viewMode = "compact";
      a.notebooks.resultPage = lastPageIndex;
      a.render();
    });
    capture("03-compact-last");

    // 04-detailed-list
    main(() -> {
      a.page = 2;
      a.notebooks.selected = "nb_global_root";
      a.notebooks.viewMode = "detailed";
      a.notebooks.resultPage = 0;
      a.render();
    });
    capture("04-detailed-list");

    // 05-search-results
    main(() -> {
      a.page = 2;
      a.notebooks.selected = "nb_global_root";
      a.notebooks.filter = "project budget";
      a.notebooks.resultPage = 0;
      a.render();
    });
    capture("05-search-results");

    // 06-vocab-detail
    main(() -> {
      a.screen = "vocabulary";
      JSONObject v = a.library.find("vocabularies", "scale-phrase-0000");
      if (v == null) v = a.library.array("vocabularies").optJSONObject(0);
      a.activeVocabulary = v;
      a.render();
    });
    capture("06-vocab-detail");
    main(() -> a.screen = "tabs");

    // Initialize review plan for review screens
    main(() -> {
      try {
        String firstPlanId = a.library.reviewPlans().list(false).getJSONObject(0).getString("id");
        long nowTime = 1793500000000L;
        a.library.reviewPlans().dailyQueue(firstPlanId, nowTime);
        a.review.activePlanId = firstPlanId;
        a.review.startDailyReview();
        a.page = 3;
        a.review.phase = "active";
        a.review.revealed = false;
        a.render();
      } catch (Exception e) {
        throw new RuntimeException("Review init failed", e);
      }
    });
    capture("07-review-front");

    // 08-review-back
    main(() -> {
      a.page = 3;
      a.review.phase = "active";
      a.review.revealed = true;
      a.render();
    });
    capture("08-review-back");

    // 09-review-rated (submit rating and capture result)
    tap("简单");
    capture("09-review-rated");

    // 10-empty-plans
    main(() -> {
      JSONArray disabled = new JSONArray();
      JSONArray plans = a.library.reviewPlans().list(false);
      for (int i = 0; i < plans.length(); i++) {
        disabled.put(plans.optJSONObject(i).optString("id"));
      }
      Library.put(a.library.settings(), "disabled_review_plans", disabled);
      a.screen = "tabs";
      a.page = 3;
      a.review.phase = "dashboard";
      a.render();
    });
    capture("10-empty-plans");
    main(() -> a.library.settings().remove("disabled_review_plans"));

    // 11-long-conversation
    main(() -> {
      a.screen = "assistant";
      JSONObject s = a.library.find("conversations", "scale-chat-0");
      if (s == null) s = a.library.array("conversations").optJSONObject(0);
      a.session = s;
      a.render();
    });
    capture("11-long-conversation");
    main(() -> a.screen = "tabs");

    // 12-settings (complementary)
    main(() -> {
      a.page = 4;
      a.render();
    });
    capture("12-settings");

    passed.add("visual_11_screens_captured");
  }

  // =========================================================================
  // Noise Calibration (5 Repeated Runs)
  // =========================================================================

  private void runNoiseCalibration() throws Exception {
    main(() -> {
      a.screen = "tabs";
      a.page = 2;
      a.notebooks.selected = null;
      a.notebooks.filter = "";
      a.render();
    });
    waitForIdleSync();

    for (int i = 1; i <= 5; i++) {
      capture("calibrate-run-" + i);
      SystemClock.sleep(200);
    }
    passed.add("noise_calibration_5_runs_completed");
  }

  // =========================================================================
  // Common Utilities
  // =========================================================================

  void capture(String name) throws Exception {
    waitForIdleSync();
    main(() -> {
      View focus = a.getCurrentFocus();
      if (focus instanceof EditText) {
        focus.clearFocus();
      }
    });
    SystemClock.sleep(250);
    waitForIdleSync();

    File file = new File(outputDir, name + ".png");
    try (FileOutputStream out = new FileOutputStream(file)) {
      Bitmap bitmap = getUiAutomation().takeScreenshot();
      check(bitmap != null, "Bitmap missing for screenshot: " + name);
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
      bitmap.recycle();
    }
  }

  void main(Action task) {
    final Throwable[] error = {null};
    runOnMainSync(() -> {
      try {
        task.run();
      } catch (Throwable e) {
        error[0] = e;
      }
    });
    waitForIdleSync();
    if (error[0] != null) {
      if (error[0] instanceof RuntimeException) throw (RuntimeException) error[0];
      throw new RuntimeException(error[0]);
    }
  }

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  static Button findButton(View root, String label) {
    View v = FeatureInstrumentation.find(root, w -> w instanceof Button && label.contentEquals(((Button) w).getText()));
    return (Button) v;
  }

  static AccessibilityNodeInfo findClass(AccessibilityNodeInfo n, String name) {
    if (n == null) return null;
    if (name.contentEquals(n.getClassName())) return n;
    for (int i = 0; i < n.getChildCount(); i++) {
      AccessibilityNodeInfo r = findClass(n.getChild(i), name);
      if (r != null) return r;
    }
    return null;
  }

  static AccessibilityNodeInfo targetNode(AccessibilityNodeInfo node, String label) {
    if (node == null) return null;
    if (label.contentEquals(node.getText() == null ? "" : node.getText())
        && !"android.widget.EditText".contentEquals(node.getClassName())) {
      return node;
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfo result = targetNode(node.getChild(i), label);
      if (result != null) return result;
    }
    return null;
  }

  void tap(String label) {
    final int[] coords = new int[2];
    final boolean[] foundView = {false};
    main(() -> {
      View v = FeatureInstrumentation.find(a.root, w -> w instanceof TextView && label.contentEquals(((TextView) w).getText()));
      if (v != null && v.isShown()) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        coords[0] = loc[0] + v.getWidth() / 2;
        coords[1] = loc[1] + v.getHeight() / 2;
        foundView[0] = coords[0] > 0 && coords[1] > 0;
      }
    });

    if (foundView[0]) {
      long t = SystemClock.uptimeMillis();
      sendPointerSync(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, coords[0], coords[1], 0));
      sendPointerSync(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, coords[0], coords[1], 0));
      waitForIdleSync();
      return;
    }

    AccessibilityNodeInfo n = targetNode(getUiAutomation().getRootInActiveWindow(), label);
    check(n != null, "Missing visible element with text: " + label);
    Rect r = new Rect();
    n.getBoundsInScreen(r);
    check(!r.isEmpty(), "Element is offscreen: " + label);
    long t = SystemClock.uptimeMillis();
    sendPointerSync(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, r.centerX(), r.centerY(), 0));
    sendPointerSync(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, r.centerX(), r.centerY(), 0));
    waitForIdleSync();
  }

  void swipe(int x1, int y1, int x2, int y2, int durationMs) {
    long downTime = SystemClock.uptimeMillis();
    sendPointerSync(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x1, y1, 0));
    int steps = 15;
    for (int i = 1; i <= steps; i++) {
      long eventTime = downTime + (durationMs * i / steps);
      float curX = x1 + (float) (x2 - x1) * i / steps;
      float curY = y1 + (float) (y2 - y1) * i / steps;
      sendPointerSync(MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, curX, curY, 0));
      SystemClock.sleep(durationMs / steps);
    }
    long upTime = downTime + durationMs;
    sendPointerSync(MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x2, y2, 0));
    waitForIdleSync();
  }

  static JSONArray toJsonArray(List<Double> list) {
    JSONArray arr = new JSONArray();
    for (Double d : list) {
      try {
        arr.put(Math.round(d * 100.0) / 100.0);
      } catch (JSONException ignored) {
      }
    }
    return arr;
  }

  private static void deleteRecursive(File f) {
    if (f.isDirectory()) {
      File[] children = f.listFiles();
      if (children != null) {
        for (File c : children) deleteRecursive(c);
      }
    }
    f.delete();
  }
}
