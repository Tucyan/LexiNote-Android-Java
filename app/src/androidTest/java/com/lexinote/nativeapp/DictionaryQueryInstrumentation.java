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
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises the actual home-query route against an isolated personal store and the bundled database. */
public final class DictionaryQueryInstrumentation extends Instrumentation {
  private MainActivity activity;
  private boolean installed;
  private boolean captureUi;
  private Throwable failure;

  @Override public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    captureUi = "true".equals(arguments.getString("capture_ui"));
    start();
  }

  @Override public void onStart() {
    Bundle result = new Bundle();
    Context target = getTargetContext();
    String prefs = "dictionary_test_provider_" + System.nanoTime();
    try {
      SecureSettings noCredential = new SecureSettings(new ContextWrapper(target) {
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
          return target.getSharedPreferences(prefs, mode);
        }
      });
      if (!noCredential.getApiKey().isEmpty()) throw new AssertionError("expected an empty test API key");
      File dir = new File(getTargetContext().getCacheDir(), "dictionary-test-" + System.nanoTime());
      if (!dir.mkdirs()) throw new AssertionError("isolated directory unavailable");
      SqliteLibraryStore backend = new SqliteLibraryStore(getTargetContext(), new File(dir, "library.db").getAbsolutePath());
      backend.initialize();
      LibraryStore.setInstanceForTest(new LibraryStore(backend));
      installed = true;
      activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      if (!LibraryStore.getInstance(getTargetContext()).awaitIdle(30, TimeUnit.SECONDS))
        throw new AssertionError("initial store load timed out");
      waitForIdleSync();

      CountDownLatch savedSeed = new CountDownLatch(1);
      boolean[] seedOk = {false};
      runOnMainSync(() -> {
        if (activity.library == null || activity.query == null) throw new AssertionError("activity did not load");
        activity.secure = noCredential;
        JSONObject personal = new JSONObject();
        Library.put(personal, "word", "bank");
        Library.put(personal, "type", "word");
        JSONObject meaning = new JSONObject();
        Library.put(meaning, "zh_definition", "个人笔记专属释义");
        Library.put(personal, "meanings", new JSONArray().put(meaning));
        activity.library.saveResult(personal, new String[] {"nb_default"}, new JSONObject());
        Library.put(activity.library.settings(), "auto_record_extra_info", true);
        activity.persistAsync(ok -> { seedOk[0] = ok; savedSeed.countDown(); });
      });
      if (!savedSeed.await(30, TimeUnit.SECONDS) || !seedOk[0]) throw new AssertionError("seed did not commit");

      submit("went");
      waitForResult("ecdict", "内置词库 · ECDICT");
      awaitStore();
      JSONObject persisted = recentResult(backend, "went");
      if (!"ecdict".equals(persisted.optString("lookup_origin"))
          || !"go".equals(persisted.optString("word"))
          || !"went".equals(persisted.optString("requested_form")))
        throw new AssertionError("dictionary variant was not persisted: " + persisted);
      assertFalseMeta(persisted, "word");
      JSONArray definitions = persisted.optJSONArray("meanings");
      if (definitions == null || definitions.length() == 0) throw new AssertionError("dictionary definitions missing");
      JSONObject definition = definitions.getJSONObject(0);
      assertFalseMeta(definition, definition.has("zh_definition") ? "zh_definition" : "en_definition");
      if (backend.loadSnapshot().getRawDocument().getJSONArray("vocabularies").length() != 1)
        throw new AssertionError("dictionary lookup automatically saved vocabulary");
      File database = new File(getTargetContext().getFilesDir(), "ecdict-39cfb967-v1.db");
      if (!database.isFile() || database.length() != 34422784L)
        throw new AssertionError("bundled dictionary database was not installed");

      // The network contract is tested separately. Use a fixed response to exercise UI and storage.
      showVerifiedMeaning();
      awaitStore();
      JSONArray enriched = recentResult(backend, "went").getJSONArray("meanings");
      JSONObject added = enriched.getJSONObject(enriched.length() - 1);
      if (!"移动".equals(added.optString("zh_definition"))
          || !added.getJSONObject("field_meta").getJSONObject("zh_definition").getBoolean("is_ai_generated")
          || !"S1".equals(added.getJSONArray("web_sources").getJSONObject(0).optString("id")))
        throw new AssertionError("the attached meaning or its source was not persisted");

      // Save through the real result-page controls, then inspect the persisted SQLite snapshot.
      if (!clickLabelOnMain("整理笔记")) throw new AssertionError("organize action missing");
      if (!clickLabelOnMain("添加到默认笔记")) throw new AssertionError("save-to-default action missing");
      awaitStore();
      JSONObject savedSnapshot = backend.loadSnapshot().getRawDocument();
      JSONArray vocabularies = savedSnapshot.getJSONArray("vocabularies");
      JSONObject savedVocabulary = null;
      for (int i = 0; i < vocabularies.length(); i++) {
        JSONObject candidate = vocabularies.getJSONObject(i);
        if ("go".equals(candidate.optString("content"))) savedVocabulary = candidate;
      }
      if (savedVocabulary == null) throw new AssertionError("UI save did not persist vocabulary");
      JSONArray defaultVocabularyIds = null;
      JSONArray notebooks = savedSnapshot.getJSONArray("notebooks");
      for (int i = 0; i < notebooks.length(); i++) {
        JSONObject notebook = notebooks.getJSONObject(i);
        if ("nb_default".equals(notebook.optString("id")))
          defaultVocabularyIds = notebook.optJSONArray("vocabulary_ids");
      }
      if (!contains(defaultVocabularyIds, savedVocabulary.optString("id")))
        throw new AssertionError("UI save did not link vocabulary to default notebook");
      JSONArray savedMeanings = savedVocabulary.getJSONArray("meanings");
      JSONObject savedMeaning = null;
      for (int i = 0; i < savedMeanings.length(); i++) {
        JSONObject candidate = savedMeanings.getJSONObject(i);
        if ("移动".equals(candidate.optString("zh_definition"))) savedMeaning = candidate;
      }
      if (savedMeaning == null) throw new AssertionError("attached meaning missing from saved vocabulary");
      JSONObject meaningMeta = savedMeaning.getJSONObject("field_meta").getJSONObject("zh_definition");
      JSONArray savedSources = savedMeaning.getJSONArray("web_sources");
      if (!meaningMeta.optBoolean("is_ai_generated") || savedSources.length() != 1
          || !"S1".equals(savedSources.getJSONObject(0).optString("id")))
        throw new AssertionError("saved meaning lost generated provenance or evidence: " + savedMeaning);

      openHome();
      submit("bank");
      waitForResult("personal", "个人笔记");
      awaitStore();
      JSONObject local = recentResult(backend, "bank");
      if (!local.optBoolean("is_from_local") || "ecdict".equals(local.optString("lookup_origin"))
          || !"个人笔记专属释义".equals(local.getJSONArray("meanings").getJSONObject(0).optString("zh_definition")))
        throw new AssertionError("personal entry did not win: " + local);
      if (backend.loadSnapshot().getRawDocument().getJSONArray("vocabularies").length() != 2)
        throw new AssertionError("personal query unexpectedly mutated the saved vocabularies");
      openHome();
      submit("by and large", "By and large, the changes worked well.");
      waitForResult("ecdict", "内置词库 · ECDICT");
      awaitStore();
      JSONObject phrase = recentResult(backend, "by and large");
      if (!"phrase".equals(phrase.optString("type")) || phrase.has("dictionary_context"))
        throw new AssertionError("the phrase did not stay usable without AI");
      if (captureUi) FeatureInstrumentation.capture(this, activity, "dictionary-offline-phrase");
      if (!clickLabelOnMain("整理笔记")) throw new AssertionError("offline organize action missing");
      if (captureUi) FeatureInstrumentation.capture(this, activity, "dictionary-offline-organize");
      if (!clickLabelOnMain("添加到默认笔记")) throw new AssertionError("offline save action missing");
      awaitStore();
      JSONObject offlineSaved = backend.loadSnapshot().getRawDocument();
      JSONArray savedRows = offlineSaved.getJSONArray("vocabularies");
      JSONObject savedPhrase = null;
      for (int i = 0; i < savedRows.length(); i++) {
        JSONObject row = savedRows.getJSONObject(i);
        if ("by and large".equals(row.optString("content"))) savedPhrase = row;
      }
      if (savedPhrase == null) throw new AssertionError("offline phrase did not persist");
      assertFalseMeta(savedPhrase, "content");
      JSONObject savedDefinition = savedPhrase.getJSONArray("meanings").getJSONObject(0);
      assertFalseMeta(savedDefinition, "zh_definition");
      JSONArray savedNotebooks = offlineSaved.getJSONArray("notebooks");
      boolean linked = false;
      for (int i = 0; i < savedNotebooks.length(); i++) {
        JSONObject row = savedNotebooks.getJSONObject(i);
        if ("nb_default".equals(row.optString("id"))) {
          linked = contains(row.optJSONArray("vocabulary_ids"), savedPhrase.optString("id"));
        }
      }
      if (!linked) throw new AssertionError("offline phrase missing from default notebook");
      result.putString("no_credential", "contextual ECDICT phrase saved to default notebook with dictionary provenance");
      result.putString("status", "passed");
      result.putString("verification_ui", "fixed response, expandable source, generated meaning saved to default notebook with provenance and evidence");
    } catch (Throwable error) {
      failure = error;
      result.putString("error", error.toString());
    } finally {
      if (activity != null) runOnMainSync(() -> { activity.session = null; activity.finish(); });
      if (installed) LibraryStore.resetInstanceForTest();
      target.deleteSharedPreferences(prefs);
    }
    finish(failure == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
  }

  private void showVerifiedMeaning() throws Exception {
    runOnMainSync(() -> {
      try {
        JSONObject source = new JSONObject().put("id", "S1").put("title", "Sample reference")
            .put("url", "https://example.com/go").put("snippet", "to move");
        JSONObject reply = new JSONObject().put("status", "supported").put("meaning", "移动")
            .put("source_ids", new JSONArray().put("S1"));
        JSONObject verification = QueryWorkflow.validateWebVerification(new JSONArray().put(source), reply)
            .put("word", "go");
        Library.put(activity.session, "web_verification", verification);
        activity.render();
        if (!clickLabel("查看引用")) throw new AssertionError("evidence control missing");
        View visibleSource = FeatureInstrumentation.find(activity.root, v -> v instanceof TextView
            && "S1 · Sample reference".contentEquals(((TextView) v).getText()) && v.isShown());
        if (visibleSource == null) throw new AssertionError("evidence did not expand");
      } catch (org.json.JSONException malformed) {
        throw new AssertionError(malformed);
      }
    });
    if (captureUi) FeatureInstrumentation.capture(this, activity, "dictionary-expanded-evidence");
    if (!clickLabelOnMain("加入可保存释义")) throw new AssertionError("attach meaning control missing");
  }

  private boolean clickLabel(String text) {
    View button = FeatureInstrumentation.find(activity.root, v -> v instanceof TextView
        && text.contentEquals(((TextView) v).getText()) && v.isShown());
    return button != null && button.performClick();
  }

  private boolean clickLabelOnMain(String text) {
    boolean[] clicked = {false};
    runOnMainSync(() -> clicked[0] = clickLabel(text));
    return clicked[0];
  }

  private void openHome() {
    runOnMainSync(() -> {
      activity.page = 0;
      activity.screen = "tabs";
      activity.query.context = "";
      activity.render();
    });
  }

  private void submit(String word) {
    submit(word, "");
  }

  private void submit(String word, String context) {
    runOnMainSync(() -> {
      EditText input = (EditText) FeatureInstrumentation.find(activity.root, v -> v instanceof EditText);
      if (input == null) throw new AssertionError("query input missing");
      input.setText(word);
      EditText contextInput = (EditText) FeatureInstrumentation.find(activity.root, v -> v instanceof EditText
          && String.valueOf(((EditText) v).getHint()).contains("语境"));
      if (contextInput == null || contextInput == input) throw new AssertionError("context input missing");
      contextInput.setText(context);
      View button = FeatureInstrumentation.find(activity.root, v -> v instanceof TextView
          && "✦  智能查询".contentEquals(((TextView) v).getText()));
      if (button == null || !button.performClick()) throw new AssertionError("query button missing");
    });
  }

  private void waitForResult(String origin, String badge) {
    for (int i = 0; i < 600; i++) {
      SystemClock.sleep(50);
      boolean[] found = {false};
      runOnMainSync(() -> {
        JSONObject item = activity.session == null ? null : activity.session.optJSONObject("query_results");
        View label = FeatureInstrumentation.find(activity.root, v -> v instanceof TextView
            && badge.contentEquals(((TextView) v).getText()) && v.isShown());
        found[0] = "result".equals(activity.screen) && item != null && label != null
            && ("personal".equals(origin) ? item.optBoolean("is_from_local") : origin.equals(item.optString("lookup_origin")));
      });
      if (found[0]) return;
    }
    throw new AssertionError("query result or visible badge timed out: " + origin);
  }

  private void awaitStore() throws Exception {
    if (!LibraryStore.getInstance(getTargetContext()).awaitIdle(30, TimeUnit.SECONDS))
      throw new AssertionError("history persistence timed out");
    waitForIdleSync();
  }

  private JSONObject recentResult(SqliteLibraryStore backend, String input) throws Exception {
    JSONArray recent = backend.loadSnapshot().getRawDocument().getJSONArray("recent_queries");
    for (int i = 0; i < recent.length(); i++) {
      JSONObject row = recent.getJSONObject(i);
      if (input.equals(row.optString("input_text"))) return row.getJSONObject("query_results");
    }
    throw new AssertionError("history missing after store acknowledgement: " + input);
  }

  private boolean contains(JSONArray values, String value) {
    if (values == null) return false;
    for (int i = 0; i < values.length(); i++) if (value.equals(values.optString(i))) return true;
    return false;
  }

  private void assertFalseMeta(JSONObject object, String field) throws Exception {
    JSONObject value = object.getJSONObject("field_meta").getJSONObject(field);
    if (!value.has("is_ai_generated") || value.getBoolean("is_ai_generated"))
      throw new AssertionError("dictionary provenance missing: " + field);
  }
}
