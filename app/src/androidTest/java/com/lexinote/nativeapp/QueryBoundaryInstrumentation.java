package com.lexinote.nativeapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import com.lexinote.nativeapp.storage.LibraryStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Deterministic UI, actual client transports and SQLite checks. Run in its own process. */
public final class QueryBoundaryInstrumentation extends Instrumentation {
  private static final String MISS = "lexinotefixtureword";
  private static final String DIRECT = "lexinotedirectfixture";
  private static final String CHANGED = "lexinotechangedfixture";
  private static final String CORRECTED = "lexinotecorrectedfixture";
  private static final String PHRASE = "lexinote fixture phrase";
  private static volatile String agentMode = "confirm";
  private static volatile CountDownLatch agentEntered = new CountDownLatch(1), agentRelease = new CountDownLatch(1);
  private static volatile String searchMode = "";
  private static volatile String lastSearchQuery = "";
  private static volatile CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
  private static final AtomicInteger searchCalls = new AtomicInteger(), lookupCalls = new AtomicInteger();
  private MainActivity activity;
  private SqliteLibraryStore backend;

  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  @Override public void onStart() {
    Bundle report = new Bundle();
    boolean installed = false, passed = false;
    String stage = "setup";
    Context target = getTargetContext();
    String prefs = "boundary_provider_" + System.nanoTime();
    try {
      URL.setURLStreamHandlerFactory(protocol -> "https".equalsIgnoreCase(protocol)
          ? new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) throws IOException {
              if (!"tokendance.space".equalsIgnoreCase(url.getHost()))
                throw new IOException("Unexpected network destination: " + url.getHost());
              return new Fixture(url);
            }
          } : null);
      Context isolated = new ContextWrapper(target) {
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
          return target.getSharedPreferences(prefs, mode);
        }
      };
      SecureSettings secure = new SecureSettings(isolated);
      secure.saveProvider(secure.getProvider(), "fixture-key");
      File db = new File(target.getCacheDir(), "boundary_" + System.nanoTime() + ".db");
      backend = new SqliteLibraryStore(target, db.getAbsolutePath());
      backend.initialize();
      LibraryStore.setInstanceForTest(new LibraryStore(backend));
      installed = true;
      activity = (MainActivity) startActivitySync(new Intent(target, MainActivity.class)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      await(() -> activity.library != null && activity.query != null, "startup");
      runOnMainSync(() -> activity.secure = secure);

      stage = "dictionary_miss_waits_on_home";
      agentMode = "block_confirm";
      submit(MISS);
      require(agentEntered.await(10, TimeUnit.SECONDS), "validation request did not start");
      boolean[] stillHome = {false}, earlyConfirmation = {false};
      runOnMainSync(() -> {
        stillHome[0] = "tabs".equals(activity.screen) && activity.page == 0;
        earlyConfirmation[0] = has("确认查询");
      });
      require(stillHome[0], "single word opened assistant while validation was pending");
      require(!earlyConfirmation[0], "confirmation shown before validation completed");
      require(lookupCalls.get() == 0, "lookup started before validation completed");
      agentRelease.countDown();
      stage = "dictionary_miss_direct_lookup";
      await(() -> "result".equals(activity.screen) && result().optString("word").equals(MISS), "automatic AI lookup");
      require(lookupCalls.get() == 1, "validated miss did not invoke AiClient.lookup exactly once");
      JSONObject ai = persisted(MISS).getJSONObject("query_results");
      require(MISS.equals(ai.optString("word")) && marked(ai, "word"), "AI result/provenance not persisted");

      stage = "validated_lookup_result_direct";
      returnHome();
      agentMode = "direct";
      submit(DIRECT);
      await(() -> "result".equals(activity.screen) && DIRECT.equals(result().optString("word")), "direct model result");
      require(lookupCalls.get() == 1, "model result unnecessarily requested another lookup");
      require(DIRECT.equals(persisted(DIRECT).getJSONObject("query_results").optString("word")),
          "direct result was not persisted");

      stage = "changed_target_requires_confirmation";
      returnHome();
      agentMode = "changed";
      submit(CHANGED);
      await(() -> "assistant".equals(activity.screen) && activity.session != null
          && activity.session.optJSONObject("pending_lookup") != null && activity.active == null, "changed target confirmation");
      require(lookupCalls.get() == 1, "changed target was looked up without confirmation");
      runOnMainSync(() -> require(CORRECTED.equals(activity.session.optJSONObject("pending_lookup")
          .optJSONObject("lookup").optString("target")), "changed target proposal missing"));
      runOnMainSync(() -> activity.onBackPressed());
      clickRequired("确认查询");
      await(() -> "result".equals(activity.screen) && CORRECTED.equals(result().optString("word")), "confirmed corrected lookup");
      require(lookupCalls.get() == 2, "confirmed changed target did not invoke lookup");
      require(CORRECTED.equals(persisted(CHANGED).getJSONObject("query_results").optString("word")),
          "confirmed correction was not persisted");

      stage = "phrase_requires_confirmation";
      returnHome();
      agentMode = "phrase";
      submit(PHRASE);
      await(() -> "assistant".equals(activity.screen) && activity.session != null
          && activity.session.optJSONObject("pending_lookup") != null && activity.active == null, "phrase confirmation");
      require(lookupCalls.get() == 2, "phrase bypassed confirmation");
      require(persisted(PHRASE).optJSONObject("pending_lookup") != null, "phrase confirmation was not persisted");

      stage = "dictionary_hit";
      returnHome();
      submit("went");
      await(() -> "result".equals(activity.screen) && "ecdict".equals(result().optString("lookup_origin")), "dictionary hit");
      final String baseline = resultCopy().toString();
      assertDictionary(baseline);

      stage = "cancel_verification";
      searchMode = "block"; entered = new CountDownLatch(1); release = new CountDownLatch(1);
      clickRequired("联网核对");
      require(entered.await(10, TimeUnit.SECONDS), "search did not start");
      clickRequired("取消请求");
      release.countDown();
      await(() -> activity.active == null && has("联网核对"), "cancel retry control");
      assertDictionary(baseline);
      require(sessionVerification() == null, "cancelled verification committed a result");

      stage = "no_valid_evidence";
      searchMode = "empty";
      clickRequired("联网核对");
      await(() -> activity.active == null && activity.session.optJSONObject("web_verification") != null, "insufficient evidence");
      JSONObject verification = sessionVerification();
      require("insufficient".equals(verification.optString("status"))
          && verification.getJSONArray("sources").length() == 0 && !hasMain("加入可保存释义"),
          "invalid sources produced a supported or attachable result");
      assertDictionary(baseline);
      require("insufficient".equals(persisted("went").getJSONObject("web_verification").optString("status")),
          "insufficient result not persisted");

      stage = "timeout_retry";
      attemptFailure("timeout", baseline);
      stage = "http_failure_retry";
      attemptFailure("failure", baseline);
      stage = "retry_after_failure";
      int previous = searchCalls.get();
      searchMode = "valid";
      clickRequired("联网核对");
      await(() -> searchCalls.get() > previous && activity.active == null && has("联网核对")
          && activity.session.optJSONObject("web_verification") != null
          && "supported".equals(activity.session.optJSONObject("web_verification").optString("status")), "retry after failure");
      assertDictionary(baseline);
      JSONObject retried = persisted("went").getJSONObject("web_verification");
      require("supported".equals(retried.optString("status"))
          && "S1".equals(retried.getJSONArray("source_ids").optString(0)),
          "successful retry did not persist supported evidence");
      require(searchCalls.get() == 5, "unexpected search count: " + searchCalls.get());

      stage = "selected_dictionary_sense_search_query";
      String[] expectedQuery = {null};
      runOnMainSync(() -> {
        JSONObject entry = result();
        JSONArray meanings = entry.optJSONArray("meanings");
        JSONObject selected = null;
        for (int i = 0; meanings != null && i < meanings.length(); i++) {
          JSONObject candidate = meanings.optJSONObject(i);
          if (candidate != null && !candidate.optString("zh_definition").trim().isEmpty()) {
            selected = candidate;
            break;
          }
        }
        require(selected != null, "dictionary fixture has no Chinese sense");
        String gloss = selected.optString("zh_definition").trim();
        expectedQuery[0] = entry.optString("word") + " " + gloss + " dictionary";
        require(expectedQuery[0].codePointCount(0, expectedQuery[0].length()) <= 128,
            "selected fixture gloss exceeds search limit");
        JSONObject context = new JSONObject();
        Library.put(context, "selected_meaning_id", selected.optString("id"));
        Library.put(entry, "dictionary_context", context);
        Library.put(activity.session, "source_context", "I need to book a flight to Singapore for next Monday.");
        activity.render();
      });
      final int beforeSelectedSearch = searchCalls.get();
      searchMode = "empty";
      clickRequired("联网核对");
      await(() -> searchCalls.get() > beforeSelectedSearch && activity.active == null, "selected sense search");
      require(expectedQuery[0].equals(lastSearchQuery),
          "search query omitted selected dictionary sense: expected " + expectedQuery[0] + ", got " + lastSearchQuery);
      require(!lastSearchQuery.contains("I need to book a flight"), "search leaked the full user sentence");
      report.putString("status", "passed");
      report.putString("coverage", "validated dictionary miss -> automatic AiClient.lookup; direct result, changed target and phrase confirmation; cancellation, invalid evidence, timeout, HTTP failure and retry with SQLite preservation");
      passed = true;
    } catch (Throwable error) {
      report.putString("failed_stage", stage);
      report.putString("error", error.toString());
    } finally {
      release.countDown();
      agentRelease.countDown();
      if (activity != null) runOnMainSync(() -> { activity.cancel(); activity.session = null; activity.finish(); });
      if (installed) LibraryStore.resetInstanceForTest();
      target.deleteSharedPreferences(prefs);
    }
    finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, report);
  }

  private void returnHome() {
    runOnMainSync(() -> {
      activity.cancel();
      activity.session = null;
      activity.screen = "tabs";
      activity.page = 0;
      activity.query.context = "";
      activity.render();
    });
  }

  private void attemptFailure(String mode, String baseline) throws Exception {
    int previous = searchCalls.get();
    searchMode = mode;
    clickRequired("联网核对");
    await(() -> searchCalls.get() > previous && activity.active == null && has("联网核对"), mode);
    String[] failure = {""};
    runOnMainSync(() -> failure[0] = activity.requestFailureLocation);
    require(!failure[0].isEmpty(), mode + " did not report a failure");
    assertDictionary(baseline);
    JSONObject saved = persisted("went");
    require(baseline.equals(saved.getJSONObject("query_results").toString()), mode + " changed persisted dictionary data");
  }
  private void assertDictionary(String baseline) throws Exception {
    require(baseline.equals(resultCopy().toString()), "dictionary result changed");
    require(baseline.equals(persisted("went").getJSONObject("query_results").toString()), "SQLite dictionary result changed");
  }
  private JSONObject persisted(String input) throws Exception {
    DeviceAsync.drain(this, activity);
    JSONArray rows = backend.loadSnapshot().getRawDocument().getJSONArray("recent_queries");
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      if (input.equals(row.optString("input_text"))) return row;
    }
    throw new AssertionError("missing persisted history: " + input);
  }
  private JSONObject result() {
    JSONObject r = activity.session == null ? null : activity.session.optJSONObject("query_results");
    return r == null ? new JSONObject() : r;
  }
  private JSONObject resultCopy() {
    JSONObject[] value = {null};
    runOnMainSync(() -> value[0] = Library.copy(result()));
    return value[0];
  }
  private JSONObject sessionVerification() {
    JSONObject[] value = {null};
    runOnMainSync(() -> { JSONObject v = activity.session.optJSONObject("web_verification"); if (v != null) value[0] = Library.copy(v); });
    return value[0];
  }
  private void submit(String text) {
    runOnMainSync(() -> {
      EditText input = (EditText) FeatureInstrumentation.find(activity.root, v -> v instanceof EditText);
      require(input != null, "query input missing");
      input.setText(text);
      require(click("✦  智能查询"), "submit missing");
    });
  }
  private boolean has(String label) {
    return FeatureInstrumentation.find(activity.root, v -> v instanceof TextView
        && label.contentEquals(((TextView) v).getText()) && v.isShown()) != null;
  }
  private boolean hasMain(String label) { boolean[] value = {false}; runOnMainSync(() -> value[0] = has(label)); return value[0]; }
  private boolean click(String label) {
    View v = FeatureInstrumentation.find(activity.root, x -> x instanceof TextView
        && label.contentEquals(((TextView) x).getText()) && x.isShown());
    return v != null && v.performClick();
  }
  private void clickRequired(String label) { boolean[] ok = {false}; runOnMainSync(() -> ok[0] = click(label)); require(ok[0], "missing control: " + label); }
  private void await(BooleanSupplier condition, String label) {
    long end = SystemClock.elapsedRealtime() + 30000;
    while (SystemClock.elapsedRealtime() < end) {
      boolean[] done = {false}; runOnMainSync(() -> done[0] = condition.getAsBoolean());
      if (done[0]) return;
      SystemClock.sleep(50);
    }
    throw new AssertionError("timed out: " + label);
  }
  private static boolean marked(JSONObject object, String field) {
    JSONObject meta = object.optJSONObject("field_meta"), item = meta == null ? null : meta.optJSONObject(field);
    return item != null && item.optBoolean("is_ai_generated");
  }
  private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }

  private static final class Fixture extends HttpURLConnection {
    private final ByteArrayOutputStream request = new ByteArrayOutputStream();
    private final boolean search;
    private String body;
    Fixture(URL url) { super(url); search = url.getPath().endsWith("/tools/search"); }
    @Override public void connect() {}
    @Override public void disconnect() { }
    @Override public boolean usingProxy() { return false; }
    @Override public OutputStream getOutputStream() { return request; }
    @Override public String getContentType() { return "application/json"; }
    @Override public int getResponseCode() throws IOException {
      if (search) {
        try {
          lastSearchQuery = new JSONObject(new String(request.toByteArray(), StandardCharsets.UTF_8)).getString("query");
        } catch (Exception e) { throw new IOException("invalid search request body", e); }
        searchCalls.incrementAndGet();
        String mode = searchMode;
        if ("block".equals(mode)) {
          entered.countDown();
          try { if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("fixture gate timeout"); }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        }
        if ("timeout".equals(mode)) throw new SocketTimeoutException("fixture");
        if ("failure".equals(mode)) return 503;
        body = "valid".equals(mode)
            ? "{\"results\":[{\"title\":\"Dictionary\",\"url\":\"https://example.com/go\",\"snippet\":\"to move\"}]}"
            : "{\"results\":[{\"title\":\"invalid\",\"url\":\"javascript:bad\",\"snippet\":\"x\"}]}";
      } else {
        String payload = new String(request.toByteArray(), StandardCharsets.UTF_8);
        String answer;
        if (payload.contains("已明确确认的查询目标")) {
          lookupCalls.incrementAndGet();
          String target = "changed".equals(agentMode) ? CORRECTED : MISS;
          answer = "{\"word\":\"" + target + "\",\"type\":\"word\",\"meanings\":[{\"zh_definition\":\"测试释义\"}]}";
        } else if (payload.contains("根据搜索摘要核对词义")) {
          answer = "{\"status\":\"supported\",\"meaning\":\"移动\",\"source_ids\":[\"S1\"]}";
        } else if (payload.contains("生成一个简短标题")) answer = "测试会话";
        else {
          String mode = agentMode;
          if ("block_confirm".equals(mode)) {
            agentEntered.countDown();
            try { if (!agentRelease.await(10, TimeUnit.SECONDS)) throw new IOException("agent fixture gate timeout"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
          }
          String target = "direct".equals(mode) ? DIRECT : "changed".equals(mode) ? CORRECTED : "phrase".equals(mode) ? PHRASE : MISS;
          if ("direct".equals(mode))
            answer = "{\"kind\":\"lookup_result\",\"result\":{\"word\":\"" + target + "\",\"type\":\"word\",\"meanings\":[{\"zh_definition\":\"测试释义\"}]}}";
          else
            answer = "{\"kind\":\"confirm_lookup\",\"message\":\"确认查询\",\"lookup\":{\"target\":\"" + target + "\",\"type\":\"" + ("phrase".equals(mode) ? "phrase" : "word") + "\"}}";
        }
        try { body = new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("message", new JSONObject().put("content", answer)))).toString(); }
        catch (Exception e) { throw new IOException(e); }
      }
      return 200;
    }
    @Override public InputStream getInputStream() { return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)); }
  }
}
