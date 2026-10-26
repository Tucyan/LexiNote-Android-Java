package com.lexinote.nativeapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.util.Base64;
import androidx.exifinterface.media.ExifInterface;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

public final class ImageTools {
  private ImageTools() {}

  private static final int MAX_EDGE = 1600;

  public static Uri persist(Context context, Uri source) throws Exception {
    if (!"content".equals(source.getScheme()) && !"file".equals(source.getScheme()))
      throw new IOException("请选择本机图片");
    File directory = new File(context.getFilesDir(), "images");
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建图片目录");
    File file = new File(directory, UUID.randomUUID() + ".source");
    try (InputStream in = open(context, source);
        FileOutputStream out = new FileOutputStream(file)) {
      byte[] buffer = new byte[32 * 1024];
      int count;
      while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
      out.getFD().sync();
    } catch (Exception failure) {
      if (!file.delete()) file.deleteOnExit();
      throw failure;
    }
    return Uri.fromFile(file);
  }

  public static Uri saveBitmap(Context context, Bitmap bitmap) throws Exception {
    File directory = new File(context.getFilesDir(), "images");
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建图片目录");
    File file = new File(directory, UUID.randomUUID() + ".jpg");
    Bitmap small = bounded(bitmap);
    try (FileOutputStream out = new FileOutputStream(file)) {
      if (!small.compress(Bitmap.CompressFormat.JPEG, 88, out)) throw new IOException("图片保存失败");
      out.getFD().sync();
    } catch (Exception e) {
      if (!file.delete()) file.deleteOnExit();
      throw e;
    } finally {
      if (small != bitmap) small.recycle();
    }
    return Uri.fromFile(file);
  }

  public static String dataUrl(Context context, Uri source) throws Exception {
    Bitmap bitmap = decode(context, source);
    try {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) throw new IOException("图片编码失败");
      return "data:image/jpeg;base64,"
          + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
    } finally {
      bitmap.recycle();
    }
  }

  public static Bitmap decode(Context context, Uri source) throws Exception {
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    try (InputStream stream = open(context, source)) {
      BitmapFactory.decodeStream(stream, null, options);
    }
    if (options.outWidth <= 0
        || options.outHeight <= 0
        || options.outWidth > 100000
        || options.outHeight > 100000) throw new IOException("无法读取图片，请选择 JPEG 或 PNG 图片");
    int sample = 1;
    while (Math.max(options.outWidth, options.outHeight) / sample > MAX_EDGE) sample *= 2;
    options.inSampleSize = sample;
    options.inJustDecodeBounds = false;
    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
    Bitmap decoded;
    try (InputStream stream = open(context, source)) {
      decoded = BitmapFactory.decodeStream(stream, null, options);
    }
    if (decoded == null) throw new IOException("图片解码失败");
    Matrix matrix = new Matrix();
    try (InputStream stream = open(context, source)) {
      int orientation =
          new ExifInterface(stream)
              .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
      if (orientation == ExifInterface.ORIENTATION_ROTATE_90) matrix.postRotate(90);
      if (orientation == ExifInterface.ORIENTATION_ROTATE_180) matrix.postRotate(180);
      if (orientation == ExifInterface.ORIENTATION_ROTATE_270) matrix.postRotate(270);
      if (orientation == ExifInterface.ORIENTATION_FLIP_HORIZONTAL) matrix.postScale(-1, 1);
      if (orientation == ExifInterface.ORIENTATION_FLIP_VERTICAL) matrix.postScale(1, -1);
      if (orientation == ExifInterface.ORIENTATION_TRANSPOSE) {
        matrix.postRotate(90);
        matrix.postScale(-1, 1);
      }
      if (orientation == ExifInterface.ORIENTATION_TRANSVERSE) {
        matrix.postRotate(270);
        matrix.postScale(-1, 1);
      }
    } catch (IOException ignored) {
      /* Formats without EXIF are already upright. */
    }
    Bitmap oriented =
        matrix.isIdentity()
            ? decoded
            : Bitmap.createBitmap(
                decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
    if (oriented != decoded) decoded.recycle();
    Bitmap small = bounded(oriented);
    if (small != oriented) oriented.recycle();
    return small;
  }

  private static InputStream open(Context context, Uri source) throws IOException {
    if (!"content".equals(source.getScheme()) && !"file".equals(source.getScheme()))
      throw new IOException("请选择本机图片");
    InputStream stream = context.getContentResolver().openInputStream(source);
    if (stream == null) throw new IOException("无法打开图片");
    return stream;
  }

  private static Bitmap bounded(Bitmap source) {
    float ratio = Math.min(1f, MAX_EDGE / (float) Math.max(source.getWidth(), source.getHeight()));
    return ratio == 1f
        ? source
        : Bitmap.createScaledBitmap(
            source,
            Math.max(1, Math.round(source.getWidth() * ratio)),
            Math.max(1, Math.round(source.getHeight() * ratio)),
            true);
  }
}
