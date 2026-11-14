package com.lexinote.nativeapp;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import org.json.*;

/** Dedicated offline end-to-end checks for the selected feature migration. */
public final class FeatureInstrumentation extends Instrumentation {
  MainActivity a;
  JSONObject before;
  boolean imageEditorOnly;
  final List<String> checks = new ArrayList<>();

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    imageEditorOnly = args != null && "true".equals(args.getString("imageEditorOnly"));
    start();
  }

  @Override
  public void onStart() {
    Bundle report = new Bundle();
    int code = Activity.RESULT_OK;
    try {
      a =
          (MainActivity)
              startActivitySync(
                  new Intent(getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      DeviceAsync.ready(this, a);
      waitForIdleSync();
      if (imageEditorOnly) {
        imageEditorActivity();
        report.putString("stream", "\nPASS: image editor Activity, full-image marking and explicit crop\n");
      finish(code, report);
        return;
      }
      main(() -> before = a.library.snapshot());
      main(this::queryRecordingAndOrganization);
      capture(this, a, "query-organization");
      checks.add("automatic recording, in-place selection/editing and complete shared data");
      main(this::imageInkAndHistory);
      checks.add("native ink export, immutable source bitmap, undo/redo and rotation");
      imageEditorActivity();
      imageSessionBackup();
      checks.add(
          "full camera output with null result Intent and independent original/selection ZIP"
              + " retention");
      main(this::notebookBatchAndAnnotations);
      capture(this, a, "notebook-batch");
      checks.add(
          "batch references, per-notebook displays, filtered selection and annotation provenance");
      List<String> assistantFailures = AssistantDeviceChecks.run(this, a);
      check(
          assistantFailures.isEmpty(),
          "Assistant device failures: " + String.join("; ", assistantFailures));
      checks.add("200-row recycled timeline, persistent reading anchor and native Markdown tables");
      checks.add("live SSE preview and validated final action over fake HTTPS");
      checks.add("asynchronous AI naming and manual-title race protection");
      report.putString(
          "stream",
          "\nPASS: " + checks.size() + " feature checks\n" + String.join("\n", checks) + "\n");
    } catch (Throwable error) {
      code = Activity.RESULT_CANCELED;
      report.putString(
          "stream",
          "\nFAIL after "
              + checks.size()
              + " checks: "
              + android.util.Log.getStackTraceString(error));
    } finally {
      if (a != null && before != null)
        main(
            () -> {
              a.cancel();
              a.titles.reset();
              a.session = null;
              a.query.assistantView = null;
              a.library = new Library(before);
              a.repository.save(a.library);
              a.finish();
            });
    }
    finish(code, report);
  }

  JSONObject word(String text) throws Exception {
    return new JSONObject()
        .put("word", text)
        .put("type", "word")
        .put(
            "meanings",
            new JSONArray()
                .put(
                    new JSONObject()
                        .put("id", "m1")
                        .put("zh_definition", "有韧性的")
                        .put("en_definition", "able to recover")
                        .put("example", "Stay resilient.")));
  }

  void queryRecordingAndOrganization() throws Exception {
    a.library = new Library();
    a.session = a.library.newSession("resilient");
    Library.put(a.library.settings(), "auto_record_extra_info", true);
    a.query.finishResult(word("resilient"));
    check(
        a.library.array("vocabularies").length() == 1,
        "Auto record did not save a completed result");
    check(
        a.session.optJSONObject("saved_content").optBoolean("saved"),
        "Auto save lacks durable status");
    String savedId = a.library.local("resilient").getString("id");
    a.query.finishResult(a.session.getJSONObject("query_results"));
    check(a.library.array("vocabularies").length() == 1, "Auto record duplicated an entity");
    a.session = a.library.newSession("grammar");
    a.query.applyReply(
        new JSONObject().put("kind", "answer").put("message", "A grammar answer."), false);
    check(a.library.array("vocabularies").length() == 1, "Chat answer was automatically recorded");
    a.session = a.library.newSession("local confirmation");
    a.query.applyReply(
        new JSONObject()
            .put("kind", "confirm_lookup")
            .put("message", "Confirm target")
            .put("lookup", new JSONObject().put("target", "resilient").put("type", "word")),
        false);
    a.screen = "tabs";
    a.page = 0;
    a.query.lookup(a.query.pending());
    check(
        a.session.getJSONObject("query_results").optBoolean("is_from_local")
            && !a.session.has("auto_record_key"),
        "Confirmed local hit incorrectly triggered automatic recording");
    Library.put(a.library.settings(), "auto_record_extra_info", false);
    a.session = a.library.newSession("flexible");
    a.query.finishResult(word("flexible"));
    a.render();
    button(a.root, "整理笔记").performClick();
    check(a.query.organizing, "Organize action did not expose in-place controls");
    button(a.root, "英文").performClick();
    checkbox(a.root, "显示例句").setChecked(false);
    button(a.root, "添加到默认笔记").performClick();
    JSONObject saved = a.library.local("flexible");
    JSONObject meaning = saved.getJSONArray("meanings").getJSONObject(0);
    check(
        !meaning.optString("zh_definition").isEmpty() && !meaning.optString("example").isEmpty(),
        "Display choice deleted shared source fields");
    JSONObject display =
        a.library
            .find("notebooks", "nb_default")
            .getJSONObject("vocabulary_display")
            .getJSONObject(saved.getString("id"));
    JSONObject fields =
        display.getJSONObject("meaning_fields").getJSONObject(meaning.getString("id"));
    check(
        !fields.optBoolean("zh_definition", true) && fields.optBoolean("en_definition"),
        "Language choice not mapped to entity meaning IDs");
    check(!fields.optBoolean("example", true), "Example display toggle ignored");
    button(a.root, "就地编辑释义").performClick();
    edit(a.root, "英文释义").setText("quick to recover after difficulty");
    button(a.root, "保存本条修改").performClick();
    check(
        a.session
            .getJSONObject("query_results")
            .getJSONArray("meanings")
            .getJSONObject(0)
            .getString("en_definition")
            .startsWith("quick to recover"),
        "In-place meaning edit not saved to query");
    check(
        a.library.find("vocabularies", savedId) != null,
        "Editing another word changed an existing shared entity");
  }

  void imageInkAndHistory() throws Exception {
    Bitmap source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
    new Canvas(source).drawColor(Color.WHITE);
    ImageCropView view = new ImageCropView(a, source);
    view.layout(0, 0, 100, 100);
    view.setTool(ImageEditorState.Tool.MARK);
    gesture(view, 10, 50, 90, 50);
    check(view.state().inkStrokes.size() == 1, "Horizontal ink stroke was lost");
    Bitmap output = view.renderEditedImage();
    check(output != source && output.isMutable(), "Editor output aliases or cannot be drawn");
    check(output.getPixel(50, 50) != Color.WHITE, "Ink layer was not exported");
    check(source.getPixel(50, 50) == Color.WHITE, "Export changed the original bitmap");
    output.recycle();
    view.undo();
    check(view.state().inkStrokes.isEmpty(), "Undo retained ink");
    view.redo();
    check(view.state().inkStrokes.size() == 1, "Redo lost ink");
    view.rotate();
    check(view.state().quarterTurns == 1, "Rotation state missing");
    ImageEditorState restored = ImageEditorState.fromJson(view.state().toJson());
    check(
        restored.inkStrokes.size() == 1 && restored.quarterTurns == 1,
        "Image edit state lost across serialization");
    view.release();
  }

  void imageEditorActivity() throws Exception {
    String name = MainActivity.class.getPackage().getName() + ".ImageEditorActivity";
    ActivityMonitor monitor = addMonitor(name, null, false);
    try {
      main(this::cameraAndSourceRetention);
      capture(this, a, "image-editor");
      Activity editor = waitForMonitorWithTimeout(monitor, 3000);
      check(editor != null, "Camera image did not open the dedicated editor Activity");
      Uri original = a.originalImage;
      sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
      waitForIdleSync();
      check(original.equals(a.image), "Back changed the effective image");

      addMonitor(monitor);
      main(a::cropImage);
      Activity reopened = waitForMonitorWithTimeout(monitor, 3000);
      check(reopened != null, "Current image did not reopen in the editor Activity");
      main(() -> {
        View rotation = editorLabel(reopened.getWindow().getDecorView(), "旋转");
        check(rotation != null && ((View) rotation.getParent()).performClick(), "Rotation tab missing");
        clickEditorLabel(reopened, "↻ 顺时针90°");
      });
      addMonitor(monitor);
      main(reopened::recreate);
      Activity recreated = waitForMonitorWithTimeout(monitor, 3000);
      check(recreated != null && recreated != reopened, "Editor did not recreate");
      main(() -> clickEditorLabel(recreated, "完成"));
      waitForIdleSync();
      main(() -> check(original.equals(a.originalImage) && !original.equals(a.image)
          && a.imageEditorState.quarterTurns == 1, "Edited image or rotation state was lost"));

      addMonitor(monitor);
      main(a::cropImage);
      Activity useOriginal = waitForMonitorWithTimeout(monitor, 3000);
      check(useOriginal != null, "Edited image did not reopen");
      main(() -> clickEditorLabel(useOriginal, "原图"));
      waitForIdleSync();
      main(() -> check(original.equals(a.image), "Original action did not restore the source"));

      addMonitor(monitor);
      main(() -> a.onActivityResult(101, Activity.RESULT_OK, new Intent().setData(original)));
      Activity galleryEditor = waitForMonitorWithTimeout(monitor, 3000);
      check(galleryEditor != null, "Gallery result did not open the editor Activity");
      Uri galleryOriginal = a.originalImage;
      main(() -> clickEditorLabel(galleryEditor, "取消"));
      waitForIdleSync();
      main(() -> check(!original.equals(galleryOriginal) && galleryOriginal.equals(a.image)
          && a.imageRegion == null, "Cancel changed the new gallery image"));

      addMonitor(monitor);
      main(a::cropImage);
      Activity marking = waitForMonitorWithTimeout(monitor, 3000);
      check(marking != null, "Could not open the editor to mark the gallery image");
      waitForIdleSync();
      main(() -> {
        ImageCropView canvas = editorCanvas(marking.getWindow().getDecorView());
        check(canvas != null, "Editor canvas missing");
        dragCenter(canvas);
        check(canvas.region() != null, "Rectangle gesture did not create a mark");
        clickEditorLabel(marking, "完成");
      });
      waitForIdleSync();
      main(() -> {
        check(a.imageRegion != null && !galleryOriginal.equals(a.image), "Rectangle was not saved");
        Bitmap marked = ImageTools.decode(a, a.image);
        try {
          check(marked.getWidth() == 900 && marked.getHeight() == 600, "Rectangle cropped the image");
          check((marked.getPixel(450, 300) & 0xff) < 245, "Rectangle did not mark the image");
        } finally {
          marked.recycle();
        }
      });

      addMonitor(monitor);
      main(a::cropImage);
      Activity cropping = waitForMonitorWithTimeout(monitor, 3000);
      check(cropping != null, "Could not reopen the image to crop");
      waitForIdleSync();
      main(() -> {
        View cropTab = editorLabel(cropping.getWindow().getDecorView(), "裁剪");
        check(cropTab != null && ((View) cropTab.getParent()).performClick(), "Crop tab missing");
        ImageCropView canvas = editorCanvas(cropping.getWindow().getDecorView());
        check(canvas != null, "Crop canvas missing");
        dragCenter(canvas);
        check(canvas.hasCrop() && canvas.getImageWidth() == 900, "Crop preview changed the image");
        clickEditorLabel(cropping, "✂ 应用裁剪");
        int croppedWidth = canvas.getImageWidth();
        check(croppedWidth < 900, "Apply crop did not change the image");
        clickEditorLabel(cropping, "↶");
        check(canvas.getImageWidth() == 900 && canvas.region() != null, "Undo did not restore the mark");
        clickEditorLabel(cropping, "↷");
        check(canvas.getImageWidth() == croppedWidth, "Redo did not restore the crop");
      });
      addMonitor(monitor);
      main(cropping::recreate);
      Activity restoredCrop = waitForMonitorWithTimeout(monitor, 3000);
      check(restoredCrop != null && restoredCrop != cropping, "Crop editor did not recreate");
      main(() -> clickEditorLabel(restoredCrop, "完成"));
      waitForIdleSync();
      main(() -> {
        Bitmap cropped = ImageTools.decode(a, a.image);
        try {
          check(galleryOriginal.equals(a.originalImage) && a.imageRegion == null
              && cropped.getWidth() < 900 && cropped.getHeight() < 600, "Explicit crop was lost");
        } finally {
          cropped.recycle();
        }
      });
    } finally {
      removeMonitor(monitor);
    }
  }

  static void clickEditorLabel(Activity editor, String text) {
    View button = editorLabel(editor.getWindow().getDecorView(), text);
    check(button != null && button.performClick(), "Editor action missing: " + text);
  }

  void dragCenter(ImageCropView view) {
    float x = view.getWidth() / 2f, y = view.getHeight() / 2f;
    gesture(view, x - 100, y - 100, x + 100, y + 100);
  }

  static ImageCropView editorCanvas(View view) {
    if (view instanceof ImageCropView canvas) return canvas;
    if (view instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); i++) {
        ImageCropView match = editorCanvas(group.getChildAt(i));
        if (match != null) return match;
      }
    }
    return null;
  }

  static View editorLabel(View view, String text) {
    if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
    if (view instanceof ViewGroup group) {
      for (int i = 0; i < group.getChildCount(); i++) {
        View match = editorLabel(group.getChildAt(i), text);
        if (match != null) return match;
      }
    }
    return null;
  }

  void cameraAndSourceRetention() throws Exception {
    Uri output = FullResolutionCamera.createOutputUri(a);
    Intent capture = FullResolutionCamera.createCaptureIntent(a, output);
    check(
        output.equals(capture.getParcelableExtra(android.provider.MediaStore.EXTRA_OUTPUT)),
        "Camera has no full-file output URI");
    check(
        (capture.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0,
        "Camera cannot write output");
    Bitmap large = Bitmap.createBitmap(1800, 1200, Bitmap.Config.ARGB_8888);
    new Canvas(large).drawColor(Color.CYAN);
    try (OutputStream stream = a.getContentResolver().openOutputStream(output)) {
      check(stream != null, "Camera URI cannot be opened");
      large.compress(Bitmap.CompressFormat.PNG, 100, stream);
    }
    large.recycle();
    a.pendingCameraUri = output;
    a.onActivityResult(102, Activity.RESULT_OK, null);
    check(
        a.pendingCameraUri == null && a.originalImage != null,
        "Null camera result intent lost full image");
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    try (InputStream stream = a.getContentResolver().openInputStream(a.originalImage)) {
      BitmapFactory.decodeStream(stream, null, bounds);
    }
    check(
        bounds.outWidth == 1800 && bounds.outHeight == 1200,
        "Camera original was downsampled to preview");
  }

  void imageSessionBackup() throws Exception {
    DeviceAsync.save(this, a, () -> {
    a.imageEditorState =
        ImageEditorState.initial().withSelection(SelectedRegion.rectangle(.1, .2, .8, .9));
    a.imageRegion = a.imageEditorState.selection.toJson();
    Bitmap selected = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888);
    a.image = ImageTools.saveBitmap(a, selected);
    selected.recycle();
    a.session = a.library.newSession("photo");
    Library.promoteTemporary(a.session);
    a.query.storeImageSource(a.session);
    a.library.touchSession(a.session, false);
    }, true);
    main(() -> {
    String id = a.session.getString("id"),
        original = a.originalImage.toString(),
        derived = a.image.toString();
    a.query.open(a.session);
    check(
        original.equals(a.originalImage.toString()) && derived.equals(a.image.toString()),
        "Session reopen conflated original and selection");
    byte[] archive =
        BackupArchive.encode(
            a.library.backup(),
            uri -> {
              try (InputStream in = a.getContentResolver().openInputStream(Uri.parse(uri))) {
                return a.readBounded(in);
              }
            });
    BackupArchive.Prepared prepared = BackupArchive.decode(archive);
    check(prepared.assets.size() >= 2, "ZIP omitted original or derived image");
    check(
        prepared.backup.getJSONArray("conversations").length() > 0
            && a.library.find("conversations", id) != null,
        "Image session disappeared");
    a.screen = "assistant";
    a.clearImage();
    check(
        !a.library.find("conversations", id).has("input_images"),
        "Removed image still saved in conversation");
    a.query.open(a.library.find("conversations", id));
    check(a.image == null && a.originalImage == null, "Removed image returned after reopening");
    });
  }

  static void capture(Instrumentation instrumentation, MainActivity activity, String name)
      throws Exception {
    instrumentation.waitForIdleSync();
    // Let the submitted frame reach the compositor and transient Toasts disappear.
    android.os.SystemClock.sleep(2500);
    instrumentation.waitForIdleSync();
    Bitmap shot = instrumentation.getUiAutomation().takeScreenshot();
    check(shot != null, "Cannot capture " + name);
    File folder = new File(activity.getExternalFilesDir(null), "feature-verification");
    check(folder.isDirectory() || folder.mkdirs(), "Cannot create screenshot directory");
    try (OutputStream out = new FileOutputStream(new File(folder, name + ".png"))) {
      check(shot.compress(Bitmap.CompressFormat.PNG, 100, out), "Cannot save screenshot");
    } finally {
      shot.recycle();
    }
  }

  void notebookBatchAndAnnotations() throws Exception {
    String one = a.library.createNotebook("One", null), two = a.library.createNotebook("Two", null);
    String id = a.library.saveResult(word("durable"), new String[] {one}, new JSONObject());
    a.library.batchReferences(one, new String[] {id}, new String[] {two}, true);
    a.library.removeReferences(one, new String[] {id});
    check(a.library.find("vocabularies", id) != null, "Reference removal deleted shared entity");
    check(
        NotebookUi.contains(a.library.find("notebooks", two).getJSONArray("vocabulary_ids"), id),
        "Copy/removal lost another notebook reference");
    JSONObject meaning =
        a.library.find("vocabularies", id).getJSONArray("meanings").getJSONObject(0);
    JSONObject annotation = Annotations.add(meaning, "en_definition", "able to recover", "选中字段批注");
    check(
        annotation.optString("target_text").equals("able to recover")
            && annotation.optString("meaning_id").equals(meaning.getString("id")),
        "Annotation lost field provenance");
    a.notebooks.selected = two;
    a.notebooks.selecting = true;
    a.notebooks.selectedVocabularyIds.add(id);
    a.notebooks.filter = "not-present";
    a.screen = "tabs";
    a.page = 2;
    a.render();
    check(a.notebooks.selectedVocabularyIds.isEmpty(), "Hidden selection survived a filter change");
    String markdown =
        NotebookExport.markdownForNotebook(
            Collections.singletonList(a.library.find("vocabularies", id)),
            a.library.find("notebooks", two));
    check(
        markdown.contains("durable") && markdown.contains("选中字段批注"),
        "Markdown export lost selected content");
    a.notebooks.filter = "";
    a.notebooks.selecting = true;
    a.notebooks.selectedVocabularyIds.add(id);
    a.render();
  }

  void gesture(ImageCropView view, float x, float y, float endX, float endY) {
    long time = android.os.SystemClock.uptimeMillis();
    for (int action :
        new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP}) {
      MotionEvent event =
          MotionEvent.obtain(
              time,
              time + 20 * action,
              action,
              action == MotionEvent.ACTION_DOWN ? x : endX,
              action == MotionEvent.ACTION_DOWN ? y : endY,
              0);
      view.onTouchEvent(event);
      event.recycle();
    }
  }

  interface Action {
    void run() throws Exception;
  }

  void main(Action action) {
    final Throwable[] error = {null};
    runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Throwable e) {
            error[0] = e;
          }
        });
    waitForIdleSync();
    if (error[0] != null) throw new RuntimeException(error[0]);
  }

  static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  static Button button(View view, String text) {
    View found = find(view, v -> v instanceof Button && text.contentEquals(((Button) v).getText()));
    if (found == null) throw new AssertionError("Missing button: " + text);
    return (Button) found;
  }

  static CheckBox checkbox(View view, String text) {
    View found =
        find(view, v -> v instanceof CheckBox && text.contentEquals(((CheckBox) v).getText()));
    if (found == null) throw new AssertionError("Missing checkbox: " + text);
    return (CheckBox) found;
  }

  static EditText edit(View view, String hint) {
    View found =
        find(view, v -> v instanceof EditText && hint.contentEquals(((EditText) v).getHint()));
    if (found == null) throw new AssertionError("Missing input: " + hint);
    return (EditText) found;
  }

  interface Match {
    boolean accepts(View value);
  }

  static View find(View view, Match match) {
    if (match.accepts(view)) return view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        View found = find(((ViewGroup) view).getChildAt(i), match);
        if (found != null) return found;
      }
    return null;
  }
}
