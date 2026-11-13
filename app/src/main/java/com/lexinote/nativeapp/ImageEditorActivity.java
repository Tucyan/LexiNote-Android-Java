package com.lexinote.nativeapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Modern full-screen image markup and editing suite.
 * Supports pan/zoom, key region box selection, freehand pen markup with color & stroke widths,
 * arbitrary crop with preview mask, 90-degree rotations, and micro-angle tilt slider.
 */
public final class ImageEditorActivity extends Activity {
  static final int REQUEST_CODE = 103;
  private static final String ORIGINAL_URI = "originalImage";
  private static final String DERIVED_URI = "derivedImage";
  private static final String EDITOR_STATE = "editorState";
  private static final String ERROR = "imageError";
  private static final String ACTIVE_TAB = "activeTab";
  private static final String CROP_TO_SELECTION = "cropToSelection";

  private ImageCropView editor;
  private int activeTab = 1;

  static Intent editIntent(
      Activity caller, Uri original, ImageEditorState state, boolean cropToSelection) {
    return new Intent(caller, ImageEditorActivity.class)
        .putExtra(ORIGINAL_URI, original.toString())
        .putExtra(CROP_TO_SELECTION, cropToSelection)
        .putExtra(EDITOR_STATE, encodeState(state));
  }

  static ImageEditorResult readResult(Intent data) throws IOException, JSONException {
    String failure = data.getStringExtra(ERROR);
    if (failure != null) throw new IOException(failure);
    Uri original = Uri.parse(data.getStringExtra(ORIGINAL_URI));
    ImageEditorState state = decodeState(data.getByteArrayExtra(EDITOR_STATE));
    String derivedUri = data.getStringExtra(DERIVED_URI);
    Uri derived = derivedUri == null ? null : Uri.parse(derivedUri);
    return new ImageEditorResult(original, derived, derived == null ? null : state.selection, state);
  }

  private static byte[] encodeState(ImageEditorState state) {
    // UTF-8 keeps large ink paths compact in Activity arguments and results.
    return state.toJson().toString().getBytes(StandardCharsets.UTF_8);
  }

  private static ImageEditorState decodeState(byte[] bytes) throws JSONException {
    return bytes == null
        ? ImageEditorState.initial()
        : ImageEditorState.fromJson(new JSONObject(new String(bytes, StandardCharsets.UTF_8)));
  }

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    try {
      String uri = getIntent().getStringExtra(ORIGINAL_URI);
      if (uri == null) throw new IOException("请先选择图片");
      Bundle state = saved == null ? getIntent().getExtras() : saved;
      activeTab = state.getInt(ACTIVE_TAB, 1);
      // The former dialog built its controls with MainActivity's theme.
      Context controls = new ContextThemeWrapper(this, R.style.AppTheme);
      showEditor(controls, Uri.parse(uri), decodeState(state.getByteArray(EDITOR_STATE)), saved != null);
    } catch (Exception error) {
      setResult(RESULT_FIRST_USER, new Intent().putExtra(ERROR, errorMessage(error)));
      finish();
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    if (editor != null) state.putByteArray(EDITOR_STATE, encodeState(editor.state()));
    state.putInt(ACTIVE_TAB, activeTab);
  }

  @Override
  protected void onDestroy() {
    if (editor != null) editor.release();
    super.onDestroy();
  }

  private void complete(Uri original, Uri derived, ImageEditorState state) {
    Intent result = new Intent()
        .putExtra(ORIGINAL_URI, original.toString())
        .putExtra(EDITOR_STATE, encodeState(state));
    // The file URI is app-private. Return it as a string to avoid exposing a file URI to Android.
    if (derived != null) result.putExtra(DERIVED_URI, derived.toString());
    setResult(RESULT_OK, result);
    finish();
  }

  private static String errorMessage(Exception error) {
    return error.getMessage() == null ? error.toString() : error.getMessage();
  }

  private static int dp(Context activity, int dp) {
    return Math.round(dp * activity.getResources().getDisplayMetrics().density);
  }

  private static GradientDrawable shape(int bgColor, int cornerRadiusDp, int strokeColor, int strokeWidthDp, Context activity) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(bgColor);
    d.setCornerRadius(dp(activity, cornerRadiusDp));
    if (strokeWidthDp > 0) d.setStroke(dp(activity, strokeWidthDp), strokeColor);
    return d;
  }

  private void showEditor(
      Context activity, Uri originalUri, ImageEditorState savedState, boolean recreating)
      throws Exception {
    Bitmap source = ImageTools.decode(activity, originalUri);
    final ImageCropView editor;
    try {
      editor = new ImageCropView(activity, source, savedState);
    } catch (RuntimeException error) {
      source.recycle();
      throw error;
    }
    this.editor = editor;

    Window window = getWindow();
    window.setBackgroundDrawable(new ColorDrawable(0xFF0F172A));
    window.setStatusBarColor(0xFF0F172A);
    window.setNavigationBarColor(0xFF0F172A);
    boolean cropToSelection = getIntent().getBooleanExtra(CROP_TO_SELECTION, false);

    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(0xFF0F172A);
    root.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));

    // -------------------------------------------------------------
    // TOP HEADER BAR
    // -------------------------------------------------------------
    LinearLayout header = new LinearLayout(activity);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setBackgroundColor(0xFF1E293B);
    header.setPadding(dp(activity, 12), dp(activity, 32), dp(activity, 12), dp(activity, 10));

    TextView cancelBtn = new TextView(activity);
    cancelBtn.setText("取消");
    cancelBtn.setTextColor(0xFF94A3B8);
    cancelBtn.setTextSize(13);
    cancelBtn.setGravity(Gravity.CENTER);
    cancelBtn.setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6));
    cancelBtn.setBackground(shape(0x1A64748B, 8, 0, 0, activity));
    cancelBtn.setOnClickListener(v -> finish());
    header.addView(cancelBtn);

    TextView keepOriginalBtn = new TextView(activity);
    keepOriginalBtn.setText("原图");
    keepOriginalBtn.setTextColor(0xFF94A3B8);
    keepOriginalBtn.setTextSize(13);
    keepOriginalBtn.setGravity(Gravity.CENTER);
    keepOriginalBtn.setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6));
    keepOriginalBtn.setBackground(shape(0x1A64748B, 8, 0, 0, activity));
    keepOriginalBtn.setOnClickListener(v -> complete(originalUri, null, editor.state()));
    LinearLayout.LayoutParams keepLp = new LinearLayout.LayoutParams(-2, -2);
    keepLp.leftMargin = dp(activity, 6);
    header.addView(keepOriginalBtn, keepLp);

    // Center title + stats pill
    LinearLayout titleCol = new LinearLayout(activity);
    titleCol.setOrientation(LinearLayout.VERTICAL);
    titleCol.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1);
    titleCol.setLayoutParams(titleLp);

    TextView titleText = new TextView(activity);
    titleText.setText("图片编辑与标注");
    titleText.setTextColor(0xFFF8FAFC);
    titleText.setTextSize(14);
    titleText.setTypeface(null, android.graphics.Typeface.BOLD);
    titleCol.addView(titleText);

    TextView statsPill = new TextView(activity);
    statsPill.setTextColor(0xFF94A3B8);
    statsPill.setTextSize(10);
    statsPill.setPadding(dp(activity, 8), dp(activity, 2), dp(activity, 8), dp(activity, 2));
    statsPill.setBackground(shape(0xFF0F172A, 4, 0xFF334155, 1, activity));
    LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(-2, -2);
    spLp.topMargin = dp(activity, 2);
    titleCol.addView(statsPill, spLp);
    header.addView(titleCol);

    // Top-right actions: Undo, Redo, Done
    TextView undoBtn = new TextView(activity);
    undoBtn.setText("↶");
    undoBtn.setTextSize(18);
    undoBtn.setTextColor(0xFFF8FAFC);
    undoBtn.setGravity(Gravity.CENTER);
    undoBtn.setBackground(shape(0x1A64748B, 8, 0, 0, activity));
    LinearLayout.LayoutParams undoLp = new LinearLayout.LayoutParams(dp(activity, 32), dp(activity, 32));
    undoLp.rightMargin = dp(activity, 6);
    undoBtn.setOnClickListener(v -> editor.undo());
    header.addView(undoBtn, undoLp);

    TextView redoBtn = new TextView(activity);
    redoBtn.setText("↷");
    redoBtn.setTextSize(18);
    redoBtn.setTextColor(0xFFF8FAFC);
    redoBtn.setGravity(Gravity.CENTER);
    redoBtn.setBackground(shape(0x1A64748B, 8, 0, 0, activity));
    LinearLayout.LayoutParams redoLp = new LinearLayout.LayoutParams(dp(activity, 32), dp(activity, 32));
    redoLp.rightMargin = dp(activity, 8);
    redoBtn.setOnClickListener(v -> editor.redo());
    header.addView(redoBtn, redoLp);

    Button doneBtn = new Button(activity);
    doneBtn.setText("完成");
    doneBtn.setTextColor(Color.WHITE);
    doneBtn.setTextSize(13);
    doneBtn.setTypeface(null, android.graphics.Typeface.BOLD);
    doneBtn.setBackground(shape(0xFF10B981, 16, 0, 0, activity));
    doneBtn.setPadding(dp(activity, 14), 0, dp(activity, 14), 0);
    doneBtn.setMinHeight(dp(activity, 32));
    doneBtn.setMinimumHeight(dp(activity, 32));
    doneBtn.setOnClickListener(v -> {
      try {
        ImageEditorState state = editor.state();
        Uri derived = null;
        if (state.appliedCrop != null
            || state.selection != null
            || state.quarterTurns != 0
            || state.fineAngle != 0
            || !state.inkStrokes.isEmpty()) {
          Bitmap output = cropToSelection ? editor.renderSelection() : editor.renderEditedImage();
          try {
            derived = ImageTools.saveBitmap(activity, output);
          } finally {
            output.recycle();
          }
        }
        complete(originalUri, derived, state);
      } catch (Exception error) {
        new AlertDialog.Builder(activity)
            .setTitle("操作未完成")
            .setMessage(errorMessage(error))
            .setPositiveButton("知道了", null)
            .show();
      }
    });
    LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(-2, -2);
    doneLp.leftMargin = dp(activity, 8);
    header.addView(doneBtn, doneLp);

    root.addView(header);

    // -------------------------------------------------------------
    // CENTER CANVAS (ImageCropView)
    // -------------------------------------------------------------
    FrameLayout canvasFrame = new FrameLayout(activity);
    canvasFrame.setBackgroundColor(0xFF0F172A);
    LinearLayout.LayoutParams canvasLp = new LinearLayout.LayoutParams(-1, 0, 1);
    canvasFrame.setLayoutParams(canvasLp);
    canvasFrame.addView(editor, new FrameLayout.LayoutParams(-1, -1));
    root.addView(canvasFrame);

    // -------------------------------------------------------------
    // CONTEXTUAL SUB-TOOLBAR (Changes depending on active tool)
    // -------------------------------------------------------------
    FrameLayout subToolbarFrame = new FrameLayout(activity);
    subToolbarFrame.setBackgroundColor(0xFF182234);
    subToolbarFrame.setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 12), dp(activity, 6));
    root.addView(subToolbarFrame, new LinearLayout.LayoutParams(-1, -2));

    // -------------------------------------------------------------
    // BOTTOM DOCK (Main Tool Tabs)
    // -------------------------------------------------------------
    LinearLayout dock = new LinearLayout(activity);
    dock.setOrientation(LinearLayout.HORIZONTAL);
    dock.setGravity(Gravity.CENTER_VERTICAL);
    dock.setBackgroundColor(0xFF1E293B);
    dock.setPadding(dp(activity, 8), dp(activity, 6), dp(activity, 8), dp(activity, 28));

    String[] tabLabels = {"移动", "框选", "划线", "裁剪", "旋转"};
    String[] tabIcons = {"🖐", "▢", "✏", "✂", "↻"};
    ImageEditorState.Tool[] tabTools = {
      ImageEditorState.Tool.PAN,
      ImageEditorState.Tool.RECTANGLE,
      ImageEditorState.Tool.MARK,
      ImageEditorState.Tool.CROP,
      null // Rotation tab (keeps current tool or PAN)
    };

    LinearLayout[] tabViews = new LinearLayout[tabLabels.length];

    Runnable updateStats = () -> {
      int w = editor.getImageWidth(), h = editor.getImageHeight();
      int z = editor.getZoomPercent();
      statsPill.setText(w + " × " + h + " · " + z + "%");
      undoBtn.setAlpha(editor.canUndo() ? 1f : 0.35f);
      redoBtn.setAlpha(editor.canRedo() ? 1f : 0.35f);
    };

    // State listener for dynamic updates
    editor.setOnStateChangeListener(updateStats::run);

    // Function to render contextual toolbar content
    Runnable renderSubToolbar = new Runnable() {
      @Override
      public void run() {
        subToolbarFrame.removeAllViews();
        int tab = activeTab;
        LinearLayout tray = new LinearLayout(activity);
        tray.setOrientation(LinearLayout.HORIZONTAL);
        tray.setGravity(Gravity.CENTER_VERTICAL);
        tray.setLayoutParams(new FrameLayout.LayoutParams(-1, -2));

        if (tab == 0) {
          // PAN MODE: Navigation info and Fit screen
          TextView hint = new TextView(activity);
          hint.setText("双指缩放 · 单指移动浏览");
          hint.setTextColor(0xFF94A3B8);
          hint.setTextSize(12);
          tray.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));

          Button fitBtn = new Button(activity);
          fitBtn.setText("适应屏幕");
          fitBtn.setTextSize(12);
          fitBtn.setTextColor(0xFFF8FAFC);
          fitBtn.setBackground(shape(0xFF334155, 8, 0, 0, activity));
          fitBtn.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
          fitBtn.setMinHeight(dp(activity, 32));
          fitBtn.setMinimumHeight(dp(activity, 32));
          fitBtn.setOnClickListener(v -> editor.fit());
          tray.addView(fitBtn);
        } else if (tab == 1) {
          // SELECT MODE (RECTANGLE): Highlight focus box
          TextView hint = new TextView(activity);
          hint.setText(cropToSelection ? "拖拽以框选重点单词或句子" : "拖动框选重点，保留整张图片");
          hint.setTextColor(0xFF94A3B8);
          hint.setTextSize(12);
          tray.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));

          Button clearBtn = new Button(activity);
          clearBtn.setText("清除框选");
          clearBtn.setTextSize(12);
          clearBtn.setTextColor(0xFFEF4444);
          clearBtn.setBackground(shape(0x20EF4444, 8, 0x40EF4444, 1, activity));
          clearBtn.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
          clearBtn.setMinHeight(dp(activity, 32));
          clearBtn.setMinimumHeight(dp(activity, 32));
          clearBtn.setOnClickListener(v -> editor.clearSelection());
          tray.addView(clearBtn);
        } else if (tab == 2) {
          // PEN MODE (MARK): Colors + widths
          int[] colors = {0xFFEF4444, 0xFFF59E0B, 0xFF10B981, 0xFF3B82F6, 0xFF8B5CF6, 0xFFFFFFFF};
          for (int c : colors) {
            View chip = new View(activity);
            boolean isSelected = editor.getInkColor() == c;
            chip.setBackground(shape(c, 12, isSelected ? 0xFFFFFFFF : 0x00000000, isSelected ? 2 : 0, activity));
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(dp(activity, 24), dp(activity, 24));
            chipLp.rightMargin = dp(activity, 8);
            chip.setOnClickListener(v -> {
              editor.setInkColor(c);
              run();
            });
            tray.addView(chip, chipLp);
          }

          View spacer = new View(activity);
          tray.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));

          // Width toggles: 2px, 4px, 8px
          float[] widths = {2f, 4f, 8f};
          String[] widthLabels = {"细", "中", "粗"};
          for (int i = 0; i < widths.length; i++) {
            final float w = widths[i];
            TextView wBtn = new TextView(activity);
            wBtn.setText(widthLabels[i]);
            wBtn.setTextSize(11);
            boolean isW = Math.abs(editor.getInkWidth() - w) < 0.5f;
            wBtn.setTextColor(isW ? 0xFF10B981 : 0xFF94A3B8);
            wBtn.setBackground(shape(isW ? 0x2810B981 : 0xFF334155, 6, isW ? 0xFF10B981 : 0, isW ? 1 : 0, activity));
            wBtn.setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 8), dp(activity, 4));
            LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(-2, -2);
            wLp.leftMargin = dp(activity, 4);
            wBtn.setOnClickListener(v -> {
              editor.setInkWidth(w);
              run();
            });
            tray.addView(wBtn, wLp);
          }

          TextView clearInkBtn = new TextView(activity);
          clearInkBtn.setText("清除");
          clearInkBtn.setTextSize(11);
          clearInkBtn.setTextColor(0xFFEF4444);
          clearInkBtn.setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 8), dp(activity, 4));
          clearInkBtn.setOnClickListener(v -> editor.clearInk());
          LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(-2, -2);
          clLp.leftMargin = dp(activity, 6);
          tray.addView(clearInkBtn, clLp);
        } else if (tab == 3) {
          // CROP MODE: crop instructions and apply button
          TextView hint = new TextView(activity);
          hint.setText("拖动选择保留区域");
          hint.setTextColor(0xFF94A3B8);
          hint.setTextSize(12);
          tray.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));

          Button resetCropBtn = new Button(activity);
          resetCropBtn.setText("重置");
          resetCropBtn.setTextSize(12);
          resetCropBtn.setTextColor(0xFF94A3B8);
          resetCropBtn.setBackground(shape(0xFF334155, 8, 0, 0, activity));
          resetCropBtn.setPadding(dp(activity, 10), 0, dp(activity, 10), 0);
          resetCropBtn.setMinHeight(dp(activity, 32));
          resetCropBtn.setMinimumHeight(dp(activity, 32));
          resetCropBtn.setOnClickListener(v -> editor.resetCrop());
          tray.addView(resetCropBtn);

          Button applyCropBtn = new Button(activity);
          applyCropBtn.setText("✂ 应用裁剪");
          applyCropBtn.setTextSize(12);
          applyCropBtn.setTextColor(Color.WHITE);
          applyCropBtn.setTypeface(null, android.graphics.Typeface.BOLD);
          applyCropBtn.setBackground(shape(0xFF10B981, 8, 0, 0, activity));
          applyCropBtn.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
          applyCropBtn.setMinHeight(dp(activity, 32));
          applyCropBtn.setMinimumHeight(dp(activity, 32));
          applyCropBtn.setOnClickListener(v -> editor.applyCrop());
          LinearLayout.LayoutParams applyLp = new LinearLayout.LayoutParams(-2, -2);
          applyLp.leftMargin = dp(activity, 8);
          tray.addView(applyCropBtn, applyLp);
        } else if (tab == 4) {
          // ROTATE MODE: 90-degree buttons + fine-tune slider
          tray.setOrientation(LinearLayout.VERTICAL);

          LinearLayout row1 = new LinearLayout(activity);
          row1.setOrientation(LinearLayout.HORIZONTAL);
          row1.setGravity(Gravity.CENTER_VERTICAL);
          row1.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

          TextView ccwBtn = new TextView(activity);
          ccwBtn.setText("↺ 逆时针90°");
          ccwBtn.setTextSize(12);
          ccwBtn.setTextColor(0xFFF8FAFC);
          ccwBtn.setGravity(Gravity.CENTER);
          ccwBtn.setBackground(shape(0xFF334155, 8, 0, 0, activity));
          ccwBtn.setPadding(0, dp(activity, 8), 0, dp(activity, 8));
          ccwBtn.setOnClickListener(v -> editor.rotateCounterClockwise());
          row1.addView(ccwBtn, new LinearLayout.LayoutParams(0, -2, 1));

          TextView cwBtn = new TextView(activity);
          cwBtn.setText("↻ 顺时针90°");
          cwBtn.setTextSize(12);
          cwBtn.setTextColor(0xFFF8FAFC);
          cwBtn.setGravity(Gravity.CENTER);
          cwBtn.setBackground(shape(0xFF334155, 8, 0, 0, activity));
          cwBtn.setPadding(0, dp(activity, 8), 0, dp(activity, 8));
          cwBtn.setOnClickListener(v -> editor.rotateClockwise());
          LinearLayout.LayoutParams cwLp = new LinearLayout.LayoutParams(0, -2, 1);
          cwLp.leftMargin = dp(activity, 8);
          row1.addView(cwBtn, cwLp);

          TextView fitRBtn = new TextView(activity);
          fitRBtn.setText("⛶ 适应");
          fitRBtn.setTextSize(12);
          fitRBtn.setTextColor(0xFFF8FAFC);
          fitRBtn.setGravity(Gravity.CENTER);
          fitRBtn.setBackground(shape(0xFF334155, 8, 0, 0, activity));
          fitRBtn.setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 14), dp(activity, 8));
          fitRBtn.setOnClickListener(v -> editor.fit());
          LinearLayout.LayoutParams fitLp = new LinearLayout.LayoutParams(-2, -2);
          fitLp.leftMargin = dp(activity, 8);
          row1.addView(fitRBtn, fitLp);

          tray.addView(row1);

          // Row 2: Fine tilt angle slider
          LinearLayout row2 = new LinearLayout(activity);
          row2.setOrientation(LinearLayout.HORIZONTAL);
          row2.setGravity(Gravity.CENTER_VERTICAL);
          LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(-1, -2);
          row2Lp.topMargin = dp(activity, 8);
          row2.setLayoutParams(row2Lp);

          TextView fineLabel = new TextView(activity);
          fineLabel.setText("微调");
          fineLabel.setTextColor(0xFF94A3B8);
          fineLabel.setTextSize(11);
          row2.addView(fineLabel);

          TextView minusBtn = new TextView(activity);
          minusBtn.setText("−");
          minusBtn.setTextSize(16);
          minusBtn.setTextColor(0xFFF8FAFC);
          minusBtn.setGravity(Gravity.CENTER);
          minusBtn.setBackground(shape(0xFF334155, 6, 0, 0, activity));
          LinearLayout.LayoutParams minLp = new LinearLayout.LayoutParams(dp(activity, 34), dp(activity, 30));
          minLp.leftMargin = dp(activity, 6);
          row2.addView(minusBtn, minLp);

          SeekBar seekBar = new SeekBar(activity);
          seekBar.setMax(90);
          seekBar.setProgress(editor.getFineAngle() + 45);
          LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(0, -2, 1);
          sbLp.leftMargin = dp(activity, 4);
          sbLp.rightMargin = dp(activity, 4);
          row2.addView(seekBar, sbLp);

          TextView plusBtn = new TextView(activity);
          plusBtn.setText("+");
          plusBtn.setTextSize(16);
          plusBtn.setTextColor(0xFFF8FAFC);
          plusBtn.setGravity(Gravity.CENTER);
          plusBtn.setBackground(shape(0xFF334155, 6, 0, 0, activity));
          LinearLayout.LayoutParams plusLp = new LinearLayout.LayoutParams(dp(activity, 34), dp(activity, 30));
          plusLp.rightMargin = dp(activity, 8);
          row2.addView(plusBtn, plusLp);

          TextView angleDegree = new TextView(activity);
          angleDegree.setText(editor.getFineAngle() + "°");
          angleDegree.setTextColor(0xFF10B981);
          angleDegree.setTextSize(13);
          angleDegree.setTypeface(null, android.graphics.Typeface.BOLD);
          angleDegree.setGravity(Gravity.CENTER);
          angleDegree.setMinWidth(dp(activity, 44));
          row2.addView(angleDegree);
          tray.addView(row2);

          minusBtn.setOnClickListener(v -> {
            int next = Math.max(-45, editor.getFineAngle() - 1);
            editor.setFineAngle(next);
            editor.commitFineAngle();
            seekBar.setProgress(next + 45);
            angleDegree.setText((next > 0 ? "+" : "") + next + "°");
          });

          plusBtn.setOnClickListener(v -> {
            int next = Math.min(45, editor.getFineAngle() + 1);
            editor.setFineAngle(next);
            editor.commitFineAngle();
            seekBar.setProgress(next + 45);
            angleDegree.setText((next > 0 ? "+" : "") + next + "°");
          });

          seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
              if (fromUser) {
                int angle = progress - 45;
                editor.setFineAngle(angle);
                angleDegree.setText((angle > 0 ? "+" : "") + angle + "°");
              }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
              editor.commitFineAngle();
            }
          });
        }

        subToolbarFrame.addView(tray);
      }
    };

    Runnable highlightTab = () -> {
      for (int j = 0; j < tabViews.length; j++) {
        boolean active = j == activeTab;
        tabViews[j].setBackground(shape(active ? 0x2610B981 : 0x00000000, 10, active ? 0xFF10B981 : 0, active ? 1 : 0, activity));
        TextView ico = (TextView) tabViews[j].getChildAt(0);
        ico.setTextColor(active ? 0xFF10B981 : 0xFF94A3B8);
        TextView lbl = (TextView) tabViews[j].getChildAt(1);
        lbl.setTextColor(active ? 0xFF10B981 : 0xFF94A3B8);
        lbl.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
      }
      renderSubToolbar.run();
    };

    // Create bottom dock tabs
    for (int i = 0; i < tabLabels.length; i++) {
      final int index = i;
      LinearLayout tabItem = new LinearLayout(activity);
      tabItem.setOrientation(LinearLayout.VERTICAL);
      tabItem.setGravity(Gravity.CENTER);
      tabItem.setPadding(0, dp(activity, 6), 0, dp(activity, 6));

      TextView iconView = new TextView(activity);
      iconView.setText(tabIcons[i]);
      iconView.setTextSize(16);
      iconView.setTextColor(0xFF94A3B8);
      iconView.setGravity(Gravity.CENTER);
      tabItem.addView(iconView);

      TextView labelView = new TextView(activity);
      labelView.setText(tabLabels[i]);
      labelView.setTextSize(11);
      labelView.setTextColor(0xFF94A3B8);
      labelView.setGravity(Gravity.CENTER);
      labelView.setTypeface(null, android.graphics.Typeface.NORMAL);
      LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(-2, -2);
      lblLp.topMargin = dp(activity, 2);
      tabItem.addView(labelView, lblLp);

      tabViews[i] = tabItem;

      tabItem.setOnClickListener(v -> {
        activeTab = index;
        if (tabTools[index] != null) editor.setTool(tabTools[index]);
        highlightTab.run();
      });
      dock.addView(tabItem, new LinearLayout.LayoutParams(0, -2, 1));
    }

    root.addView(dock);

    if (!recreating) editor.setTool(tabTools[activeTab]);
    highlightTab.run();
    updateStats.run();

    root.setOnApplyWindowInsetsListener((v, insets) -> {
      int top = dp(activity, 28);
      int bottom = dp(activity, 24);
      if (Build.VERSION.SDK_INT >= 30) {
        android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
        top = Math.max(bars.top, dp(activity, 28));
        bottom = Math.max(bars.bottom, dp(activity, 24));
      } else if (Build.VERSION.SDK_INT >= 20) {
        top = Math.max(insets.getSystemWindowInsetTop(), dp(activity, 28));
        bottom = Math.max(insets.getSystemWindowInsetBottom(), dp(activity, 24));
      }
      header.setPadding(dp(activity, 12), top + dp(activity, 4), dp(activity, 12), dp(activity, 8));
      dock.setPadding(dp(activity, 8), dp(activity, 6), dp(activity, 8), bottom + dp(activity, 4));
      return insets;
    });

    setContentView(root);
  }
}
