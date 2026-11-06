package com.lexinote.nativeapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import com.lexinote.nativeapp.storage.LibraryStore;
import java.io.DataInputStream;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs the real query, model, and search route with a one-time credential and isolated library. */
public final class TokenDanceFunctionalInstrumentation extends Instrumentation {
  private String socketName;
  private String sample;
  private String word;
  private String context;
  private MainActivity activity;

  @Override public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    socketName = arguments.getString("credential_socket", "");
    sample = arguments.getString("sample", "riverbank");
    start();
  }

  @Override public void onStart() {
    Bundle report = new Bundle();
    Context target = getTargetContext();
    String privateName = "functional_provider_" + System.nanoTime();
    boolean injected = false;
    boolean passed = false;
    String stage = "credential";
    try {
      String expectedSense = switch (sample) {
        case "riverbank" -> {
          word = "riverbank";
          context = "We sat on the riverbank and watched the water flow.";
          yield "河岸|河边|河堤|岸边";
        }
        case "book" -> {
          word = "book";
          context = "I need to book a flight to Singapore for next Monday.";
          yield "预订|预定|预约|订票|订座|订机票|订航班";
        }
        case "idiom" -> {
          word = "by and large";
          context = "By and large, the changes worked well.";
          yield "总体|总的|大体|基本|大致|总而言";
        }
        default -> throw new IllegalArgumentException("unknown test sample");
      };
      report.putString("word", word);
      report.putString("sentence", context);
      Context isolated = new ContextWrapper(target) {
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
          return target.getSharedPreferences(privateName, mode);
        }
      };
      SecureSettings secure = new SecureSettings(isolated);
      JSONObject provider = secure.getProvider().put("reasoning_effort", "off");
      TokenDanceCatalog.assertChatModel(provider);
      report.putString("model", provider.getString("model_id"));
      secure.saveProvider(provider, receiveCredential());

      stage = "startup";
      File database = new File(target.getCacheDir(), "functional_live_" + System.nanoTime() + ".db");
      SqliteLibraryStore backend = new SqliteLibraryStore(target, database.getAbsolutePath());
      backend.initialize();
      LibraryStore.setInstanceForTest(new LibraryStore(backend));
      injected = true;
      activity = (MainActivity) startActivitySync(new Intent(target, MainActivity.class)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      awaitMain(() -> activity.query != null && activity.library != null, "startup");

      stage = "android_gateway_reachability";
      report.putInt("gateway_models_http_status", probeGateway());
      stage = "submit_dictionary_query";
      boolean[] submitted = {false};
      runOnMainSync(() -> {
        activity.secure = secure;
        EditText wordInput = (EditText) FeatureInstrumentation.find(activity.root, view -> view instanceof EditText);
        EditText contextInput = (EditText) FeatureInstrumentation.find(activity.root, view -> view instanceof EditText
            && String.valueOf(((EditText) view).getHint()).contains("语境"));
        if (wordInput == null || contextInput == null || wordInput == contextInput) return;
        wordInput.setText(word);
        contextInput.setText(context);
        submitted[0] = click("✦  智能查询");
      });
      require(submitted[0], "query inputs or submit button missing");
      awaitMain(() -> "result".equals(activity.screen) && "ecdict".equals(result().optString("lookup_origin")),
          "offline dictionary");

      stage = "real_context_supplement";
      awaitMain(() -> activity.active == null && result().optJSONObject("dictionary_context") != null,
          "context supplement");
      JSONObject saved = recentSession(backend);
      JSONObject entry = saved.getJSONObject("query_results");
      require(context.equals(saved.optString("source_context")), "source context was not persisted");
      JSONObject explanation = entry.getJSONObject("dictionary_context");
      require(marked(explanation, "explanation"), "AI explanation provenance missing");
      require(!marked(entry, "word"), "dictionary headword provenance changed");
      report.putString("context_explanation", explanation.optString("explanation"));
      String selectedId = explanation.optString("selected_meaning_id");
      JSONArray meanings = entry.getJSONArray("meanings");
      for (int i = 0; i < meanings.length(); i++) {
        JSONObject meaning = meanings.getJSONObject(i);
        if (!selectedId.isEmpty() && selectedId.equals(meaning.optString("id"))) {
          report.putString("selected_sense", meaning.optString("zh_definition", meaning.optString("en_definition")));
          require(!marked(meaning, "zh_definition") && !marked(meaning, "en_definition"),
              "selected dictionary sense provenance changed");
        }
      }
      report.putString("dictionary_context", "live model result saved with field provenance");

      stage = "click_live_verification";
      boolean[] clicked = {false};
      runOnMainSync(() -> clicked[0] = click("联网核对"));
      require(clicked[0], "verification button missing");
      stage = "real_search_and_model";
      awaitMain(() -> activity.active == null && activity.session != null
          && activity.session.optJSONObject("web_verification") != null, "web verification");
      JSONObject verification = recentSession(backend).getJSONObject("web_verification");
      JSONArray sources = verification.getJSONArray("sources");
      JSONArray citations = verification.getJSONArray("source_ids");
      report.putString("verification_status", verification.optString("status"));
      report.putString("verification_meaning", verification.optString("meaning"));
      report.putInt("retrieved_sources", sources.length());
      report.putInt("validated_citations", citations.length());
      for (int i = 0; i < citations.length(); i++) {
        String id = citations.getString(i);
        for (int j = 0; j < sources.length(); j++) {
          JSONObject source = sources.getJSONObject(j);
          if (!id.equals(source.optString("id"))) continue;
          String snippet = source.optString("snippet").replaceAll("\\s+", " ");
          report.putString("cited_" + id, source.optString("title") + " | " +
              snippet.substring(0, Math.min(160, snippet.length())));
        }
      }
      require("supported".equals(verification.optString("status")), "search did not support the chosen example");
      require(sources.length() > 0 && citations.length() > 0, "no provider evidence and citations");
      require(marked(verification, "meaning") || marked(verification, "reason"), "AI verification provenance missing");
      require(java.util.regex.Pattern.compile(expectedSense).matcher(verification.optString("meaning")).find(),
          "verification did not express the expected contextual sense");
      report.putString("status", "passed");
      passed = true;
    } catch (Throwable error) {
      report.putString("failed_stage", stage);
      report.putString("error", error instanceof AssertionError ? error.getMessage() : error.getClass().getSimpleName());
    } finally {
      if (activity != null) runOnMainSync(() -> { activity.cancel(); activity.session = null; activity.finish(); });
      if (injected) LibraryStore.resetInstanceForTest();
      target.deleteSharedPreferences(privateName);
    }
    finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, report);
  }

  private String receiveCredential() throws Exception {
    if (!socketName.matches("lexinote-live-[a-f0-9]{32}"))
      throw new IllegalArgumentException("missing one-time credential socket");
    try (LocalSocket socket = new LocalSocket()) {
      socket.connect(new LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT));
      socket.setSoTimeout(5000);
      socket.getOutputStream().write((socketName + "\n").getBytes(StandardCharsets.US_ASCII));
      DataInputStream input = new DataInputStream(socket.getInputStream());
      int length = input.readUnsignedShort();
      require(length >= 16 && length <= 256, "invalid credential length");
      byte[] bytes = new byte[length];
      try {
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
      } finally {
        Arrays.fill(bytes, (byte) 0);
      }
    }
  }

  private static int probeGateway() throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(
        "https://tokendance.space/gateway/v1/models").openConnection();
    try {
      connection.setConnectTimeout(8000);
      connection.setReadTimeout(8000);
      connection.setInstanceFollowRedirects(false);
      return connection.getResponseCode();
    } finally {
      connection.disconnect();
    }
  }

  private JSONObject result() {
    JSONObject result = activity.session == null ? null : activity.session.optJSONObject("query_results");
    return result == null ? new JSONObject() : result;
  }

  private void awaitMain(BooleanSupplier check, String label) {
    long deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(50);
    while (SystemClock.elapsedRealtime() < deadline) {
      boolean[] done = {false};
      String[] failure = {""};
      runOnMainSync(() -> {
        done[0] = check.getAsBoolean();
        failure[0] = activity.requestFailureLocation;
      });
      if (done[0]) return;
      if (!failure[0].isEmpty()) throw new AssertionError(label + ": " + failure[0]);
      SystemClock.sleep(100);
    }
    throw new AssertionError(label + " did not complete");
  }

  private JSONObject recentSession(SqliteLibraryStore backend) throws Exception {
    DeviceAsync.drain(this, activity);
    JSONArray rows = backend.loadSnapshot().getRawDocument().getJSONArray("recent_queries");
    for (int i = 0; i < rows.length(); i++) {
      JSONObject session = rows.getJSONObject(i);
      if (word.equals(session.optString("input_text"))) return session;
    }
    throw new AssertionError("query was not persisted");
  }

  private boolean click(String text) {
    View view = FeatureInstrumentation.find(activity.root,
        item -> item instanceof TextView && text.contentEquals(((TextView) item).getText()) && item.isShown());
    return view != null && view.performClick();
  }

  private static boolean marked(JSONObject object, String field) {
    JSONObject all = object.optJSONObject("field_meta");
    JSONObject marker = all == null ? null : all.optJSONObject(field);
    return marker != null && marker.optBoolean("is_ai_generated");
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
