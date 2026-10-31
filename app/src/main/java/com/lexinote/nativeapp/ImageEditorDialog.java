package com.lexinote.nativeapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

/** Native editor entry point. Confirmation returns source and derived image independently. */
public final class ImageEditorDialog {
  private ImageEditorDialog() {}

  @FunctionalInterface
  public interface Callback {
    void onComplete(ImageEditorResult result);

    default void onError(Activity activity, Exception error) {
      Toast.makeText(
              activity,
              error.getMessage() == null ? "图片处理失败" : error.getMessage(),
              Toast.LENGTH_LONG)
          .show();
    }
  }

  public static void show(
      Activity activity, Uri originalUri, ImageEditorState savedState, Callback callback) {
    ImageCropView[] owned = new ImageCropView[1];
    try {
      Bitmap source = ImageTools.decode(activity, originalUri);
      ImageCropView editor = new ImageCropView(activity, source, savedState);
      owned[0] = editor;
      LinearLayout box = new LinearLayout(activity);
      box.setOrientation(LinearLayout.VERTICAL);
      box.addView(
          editor,
          new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              activity.getResources().getDisplayMetrics().heightPixels / 2));
      LinearLayout tools = new LinearLayout(activity);
      tools.setOrientation(LinearLayout.VERTICAL);
      LinearLayout first = new LinearLayout(activity), second = new LinearLayout(activity);
      first.addView(
          activityButton(activity, "框选", () -> editor.setTool(ImageEditorState.Tool.RECTANGLE)));
      first.addView(
          activityButton(activity, "圈选", () -> editor.setTool(ImageEditorState.Tool.FREEHAND)));
      first.addView(
          activityButton(activity, "标记", () -> editor.setTool(ImageEditorState.Tool.MARK)));
      first.addView(activityButton(activity, "旋转", editor::rotate));
      second.addView(activityButton(activity, "撤销", editor::undo));
      second.addView(activityButton(activity, "重做", editor::redo));
      second.addView(activityButton(activity, "重置", editor::reset));
      tools.addView(first);
      tools.addView(second);
      box.addView(tools);
      AlertDialog dialog =
          new AlertDialog.Builder(activity)
              .setTitle("图片选择与标注")
              .setView(box)
              .setNegativeButton("取消", (d, w) -> {})
              .setNeutralButton(
                  "保留原图",
                  (d, w) ->
                      callback.onComplete(
                          new ImageEditorResult(originalUri, null, null, editor.state())))
              .setPositiveButton(
                  "使用当前图",
                  (d, w) -> {
                    try {
                      ImageEditorState state = editor.state();
                      Uri derived = null;
                      if (editor.region() != null
                          || state.quarterTurns != 0
                          || !state.inkStrokes.isEmpty()) {
                        Bitmap output = editor.crop();
                        try {
                          derived = ImageTools.saveBitmap(activity, output);
                        } finally {
                          output.recycle();
                        }
                      }
                      callback.onComplete(
                          new ImageEditorResult(originalUri, derived, editor.region(), state));
                    } catch (Exception e) {
                      callback.onError(activity, e);
                    }
                  })
              .create();
      dialog.setOnDismissListener(ignored -> editor.release());
      if (activity instanceof MainActivity) {
        ((MainActivity) activity).showDialog(dialog);
      } else {
        dialog.show();
      }
    } catch (Exception e) {
      if (owned[0] != null) owned[0].release();
      callback.onError(activity, e);
    }
  }

  private interface Action {
    void run();
  }

  private static android.widget.Button activityButton(Activity a, String text, Action action) {
    android.widget.Button b = new android.widget.Button(a);
    b.setText(text);
    b.setOnClickListener(v -> action.run());
    return b;
  }
}
