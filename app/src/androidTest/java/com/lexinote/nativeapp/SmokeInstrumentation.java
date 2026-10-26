package com.lexinote.nativeapp;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Real-device smoke runner requiring no external test frameworks or model credentials. */
public final class SmokeInstrumentation extends Instrumentation {
  private final List<String> passed = new ArrayList<>();
  private MainActivity activity;
  private JSONObject before;

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
      activity =
          (MainActivity)
              startActivitySync(
                  new Intent(getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      waitForIdleSync();
      main(() -> before = activity.library.snapshot());
      main(this::testNavigation);
      record("five native tabs");
      main(this::testOfflineQueryAndSharedNotebooks);
      record("offline query, shared vocabulary references, result navigation");
      main(this::testConfirmationAndSessionImageIsolation);
      record("assistant proposals require hub confirmation and session images stay isolated");
      main(this::testReview);
      record("review answer and persisted grade");
      testCredentialEncryption();
      record("Android Keystore API key roundtrip");
      main(this::testCrop);
      record("native image crop coordinates and rotation");
      main(
          () -> {
            try {
              activity.repository.save(activity.library);
              Library loaded = activity.repository.load();
              check(loaded.local("native smoke") != null, "Persistence lost vocabulary");
              activity.repository.restore(new Library().backup(), activity.library);
              check(
                  activity.repository.rollback().local("native smoke") != null,
                  "Restore rollback lost vocabulary");
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      record("AtomicFile persistence and restore rollback");
      testCorruptDataRecovery();
      record("corrupt data retains a usable recovery entry and preserves raw bytes");
      result.putString(
          "stream",
          "\nPASS: " + passed.size() + " device smoke checks\n" + String.join("\n", passed) + "\n");
    } catch (Throwable e) {
      outcome = Activity.RESULT_CANCELED;
      result.putString(
          "stream",
          "\nFAIL after "
              + passed.size()
              + " checks: "
              + e
              + "\n"
              + android.util.Log.getStackTraceString(e));
    } finally {
      if (activity != null && before != null)
        main(
            () -> {
              try {
                activity.library = new Library(before);
                activity.repository.save(activity.library);
                activity.finish();
              } catch (Exception ignored) {
              }
            });
    }
    finish(outcome, result);
  }

  private void testNavigation() {
    String[] labels = {"查询", "猜词", "笔记", "复习", "设置"};
    for (int i = 0; i < labels.length; i++) {
      Button button = findButton(activity.nav, labels[i]);
      check(button != null, "Missing tab " + labels[i]);
      button.performClick();
      check(activity.page == i, "Tab did not navigate");
    }
  }

  private void testOfflineQueryAndSharedNotebooks() {
    try {
      activity.library = new Library();
      String a = activity.library.settings().getString("default_notebook_id");
      String b = activity.library.createNotebook("Smoke second notebook", null);
      JSONObject word =
          new JSONObject(
              "{\"word\":\"native"
                  + " smoke\",\"type\":\"phrase\",\"meanings\":[{\"id\":\"draft\",\"zh_definition\":\"原生离线测试\",\"en_definition\":\"native"
                  + " test\",\"example\":\"This is a native smoke test.\"}]}");
      String id = activity.library.saveResult(word, new String[] {a, b}, new JSONObject());
      check(
          id.equals(activity.library.saveResult(word, new String[] {b}, new JSONObject())),
          "Vocabulary duplicate");
      check(activity.library.array("vocabularies").length() == 1, "Entity duplicated");
      check(activity.persist(), "Fixture persistence failed");
      activity.screen = "tabs";
      activity.page = 0;
      activity.image = null;
      activity.query.draft = "native smoke";
      activity.render();
      activity.query.submit();
      check("result".equals(activity.screen), "Offline lookup did not open result");
      check(
          activity.session.getJSONObject("query_results").getBoolean("is_from_local"),
          "Lookup was not local");
      check(findText(activity.body, "原生离线测试"), "Result definition is not visible");
      activity.screen = "tabs";
      activity.page = 0;
      activity.render();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void testReview() {
    activity.page = 3;
    activity.render();
    Button start = findButton(activity.body, "开始 / 重新开始");
    check(start != null, "Review start missing");
    start.performClick();
    Button reveal = findButton(activity.body, "显示答案");
    check(reveal != null, "Review card missing");
    reveal.performClick();
    Button grade = findButton(activity.body, "简单");
    check(grade != null, "Review grade missing");
    grade.performClick();
    check(
        activity.library.local("native smoke").optInt("review_count") == 1, "Grade not persisted");
  }

  private void testConfirmationAndSessionImageIsolation() {
    try {
      JSONObject result = Library.copy(activity.session.getJSONObject("query_results"));
      JSONObject reply = new JSONObject().put("kind", "lookup_result").put("result", result);
      activity.screen = "assistant";
      activity.query.applyReply(reply, false);
      check(activity.query.pending() != null, "Assistant result bypassed confirmation");
      check("assistant".equals(activity.screen), "Assistant unexpectedly navigated to a result");
      activity.screen = "tabs";
      activity.page = 0;
      activity.query.lookup(activity.query.pending());
      check(
          activity.query.pending() == null,
          "Completed local lookup retained an executable proposal");
      activity.image = Uri.parse("file:///stale-session-image.jpg");
      activity.query.open(activity.session);
      check(activity.image == null, "A previous session image leaked into a text-only session");
      activity.screen = "tabs";
      activity.render();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void testCredentialEncryption() throws Exception {
    // Separate preference namespace avoids changing the app's configured provider or credential.
    android.content.Context isolated =
        new android.content.ContextWrapper(getTargetContext()) {
          @Override
          public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
            return super.getSharedPreferences("smoke_" + name, mode);
          }
        };
    SecureSettings secure = new SecureSettings(isolated);
    JSONObject provider =
        new JSONObject().put("base_url", "https://provider.invalid/v1").put("model_id", "test");
    secure.saveProvider(provider, "device-smoke-dummy-key");
    check("device-smoke-dummy-key".equals(secure.getApiKey()), "Credential roundtrip failed");
    String ciphertext = isolated.getSharedPreferences("provider_private", 0).getString("key", "");
    check(!ciphertext.contains("device-smoke-dummy-key"), "Plaintext credential found");
    secure.saveProvider(provider, "");
    check(secure.getApiKey().isEmpty(), "Credential clear failed");
  }

  private void testCrop() {
    Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
    ImageCropView crop = new ImageCropView(activity, bitmap);
    crop.layout(0, 0, 300, 300);
    long time = android.os.SystemClock.uptimeMillis();
    MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 30, 30, 0);
    MotionEvent move = MotionEvent.obtain(time, time + 30, MotionEvent.ACTION_MOVE, 210, 210, 0);
    MotionEvent up = MotionEvent.obtain(time, time + 60, MotionEvent.ACTION_UP, 210, 210, 0);
    crop.onTouchEvent(down);
    crop.onTouchEvent(move);
    crop.onTouchEvent(up);
    Bitmap selected = crop.crop();
    check(
        selected.getWidth() == 60 && selected.getHeight() == 60, "Crop mapped wrong bitmap bounds");
    selected.recycle();
    crop.rotate();
    Bitmap rotated = crop.crop();
    check(rotated.getWidth() == 100 && rotated.getHeight() == 100, "Rotation lost dimensions");
    rotated.recycle();
    down.recycle();
    move.recycle();
    up.recycle();
    bitmap.recycle();
  }

  private void testCorruptDataRecovery() {
    JSONObject[] valid = new JSONObject[1];
    main(
        () -> {
          valid[0] = activity.library.snapshot();
          try (java.io.FileOutputStream out =
              new java.io.FileOutputStream(
                  new java.io.File(activity.getFilesDir(), "library.json"))) {
            out.write("{broken-smoke-data".getBytes(java.nio.charset.StandardCharsets.UTF_8));
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
          activity.finish();
        });
    activity =
        (MainActivity)
            startActivitySync(
                new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    waitForIdleSync();
    main(
        () -> {
          check(activity.recoveryOnly, "Unreadable data did not enter recovery mode");
          check(
              findButton(activity.root, "选择 ZIP / JSON 备份恢复") != null,
              "Restore entry missing on corrupt data");
          check(!activity.persist(), "Recovery screen overwrote original with an empty library");
          try {
            activity.repository.preserveCorruptData();
            java.io.File[] copies =
                activity
                    .getFilesDir()
                    .listFiles((dir, name) -> name.startsWith("library-corrupt-"));
            check(copies != null && copies.length > 0, "Raw corrupt data was not preserved");
            activity.library = activity.repository.restore(valid[0], activity.library);
            activity.recoveryOnly = false;
            activity.committed = activity.library.snapshot();
            activity.render();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  private void main(Runnable action) {
    Throwable[] failure = new Throwable[1];
    runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Throwable e) {
            failure[0] = e;
          }
        });
    if (failure[0] != null) throw new RuntimeException(failure[0]);
    waitForIdleSync();
  }

  private void record(String name) {
    passed.add(name);
  }

  private static void check(boolean ok, String reason) {
    if (!ok) throw new AssertionError(reason);
  }

  private static Button findButton(View view, String label) {
    if (view instanceof Button && label.equals(((Button) view).getText().toString()))
      return (Button) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        Button result = findButton(((ViewGroup) view).getChildAt(i), label);
        if (result != null) return result;
      }
    return null;
  }

  private static boolean findText(View view, String text) {
    if (view instanceof TextView && ((TextView) view).getText().toString().contains(text))
      return true;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        if (findText(((ViewGroup) view).getChildAt(i), text)) return true;
    return false;
  }
}
