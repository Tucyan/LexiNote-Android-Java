package com.lexinote.nativeapp;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.UUID;

/** Prepares ACTION_IMAGE_CAPTURE output without relying on the thumbnail result extra. */
public final class FullResolutionCamera {
  private FullResolutionCamera() {}

  public static Uri createOutputUri(Context context) throws Exception {
    File directory = new File(context.getFilesDir(), "camera");
    if (!directory.isDirectory() && !directory.mkdirs())
      throw new java.io.IOException("无法创建相机图片目录");
    File file = new File(directory, UUID.randomUUID() + ".jpg");
    if (!file.createNewFile()) throw new java.io.IOException("无法创建相机图片文件");
    return new Uri.Builder()
        .scheme("content")
        .authority(CameraOutputProvider.AUTHORITY)
        .appendPath(CameraOutputProvider.PATH)
        .appendPath(file.getName())
        .build();
  }

  public static Intent createCaptureIntent(Context context, Uri output) {
    Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
    intent.putExtra(MediaStore.EXTRA_OUTPUT, output);
    intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
    intent.setClipData(ClipData.newUri(context.getContentResolver(), "camera-output", output));
    return intent;
  }

  public static Uri persistCaptured(Context context, Uri cameraOutput) throws Exception {
    File source = CameraOutputProvider.file(context::getFilesDir, cameraOutput);
    if (!source.isFile() || source.length() == 0) throw new FileNotFoundException("相机没有写入完整图片");
    Uri saved = ImageTools.persist(context, cameraOutput);
    cleanup(context, cameraOutput);
    return saved;
  }

  /** Deletes an abandoned capture after cancellation or failed activity result. */
  public static void cleanup(Context context, Uri cameraOutput) {
    try {
      CameraOutputProvider.file(context::getFilesDir, cameraOutput).delete();
    } catch (FileNotFoundException ignored) {
    }
  }
}
