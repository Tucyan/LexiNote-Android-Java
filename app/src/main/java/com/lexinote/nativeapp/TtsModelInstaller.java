package com.lexinote.nativeapp;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Utility responsible for safely extracting offline neural TTS model assets
 * from the APK into internal storage on first run.
 */
public final class TtsModelInstaller {

  public static final String MODEL_NAME = "model.onnx";
  public static final String TOKENS_NAME = "tokens.txt";
  public static final String ESPEAK_DATA_ZIP = "espeak-ng-data.zip";
  public static final String ESPEAK_DATA_DIR = "espeak-ng-data";

  private static final Object INSTALL_LOCK = new Object();

  private TtsModelInstaller() {}

  public static File getTtsDir(Context context) {
    File dir = new File(context.getFilesDir(), "tts");
    if (!dir.exists()) {
      dir.mkdirs();
    }
    return dir;
  }

  public static File getModelFile(Context context) throws IOException {
    install(context);
    return new File(getTtsDir(context), MODEL_NAME);
  }

  public static File getTokensFile(Context context) throws IOException {
    install(context);
    return new File(getTtsDir(context), TOKENS_NAME);
  }

  public static File getEspeakDataDir(Context context) throws IOException {
    install(context);
    File dir = new File(getTtsDir(context), ESPEAK_DATA_DIR);
    return dir.isDirectory() ? dir : null;
  }

  public static boolean isInstalled(Context context) {
    File ttsDir = new File(context.getFilesDir(), "tts");
    File model = new File(ttsDir, MODEL_NAME);
    File tokens = new File(ttsDir, TOKENS_NAME);
    return model.isFile() && model.length() > 0 && tokens.isFile() && tokens.length() > 0;
  }

  public static void install(Context context) throws IOException {
    synchronized (INSTALL_LOCK) {
      File ttsDir = getTtsDir(context);
      File modelFile = new File(ttsDir, MODEL_NAME);
      File tokensFile = new File(ttsDir, TOKENS_NAME);
      File espeakDir = new File(ttsDir, ESPEAK_DATA_DIR);

      boolean modelReady = modelFile.isFile() && modelFile.length() > 0;
      boolean tokensReady = tokensFile.isFile() && tokensFile.length() > 0;
      boolean espeakReady = espeakDir.isDirectory() && espeakDir.list() != null && espeakDir.list().length > 0;

      if (modelReady && tokensReady && espeakReady) {
        return;
      }

      if (!modelReady) {
        copyAsset(context, "tts/" + MODEL_NAME, modelFile);
      }
      if (!tokensReady) {
        copyAsset(context, "tts/" + TOKENS_NAME, tokensFile);
      }
      if (!espeakReady) {
        unzipAssetIfPresent(context, "tts/" + ESPEAK_DATA_ZIP, espeakDir);
      }
    }
  }

  private static void copyAsset(Context context, String assetPath, File destFile) throws IOException {
    File parent = destFile.getParentFile();
    if (parent != null && !parent.exists()) {
      parent.mkdirs();
    }
    File temp = new File(parent, destFile.getName() + ".tmp");
    try {
      try (InputStream in = context.getAssets().open(assetPath);
           FileOutputStream out = new FileOutputStream(temp)) {
        byte[] buffer = new byte[65536];
        int read;
        while ((read = in.read(buffer)) != -1) {
          out.write(buffer, 0, read);
        }
        out.getFD().sync();
      }
      if (!temp.renameTo(destFile)) {
        if (destFile.exists() && destFile.delete() && temp.renameTo(destFile)) {
          return;
        }
        throw new IOException("Unable to rename temporary file to " + destFile.getAbsolutePath());
      }
    } finally {
      if (temp.exists() && !temp.delete()) {
        temp.deleteOnExit();
      }
    }
  }

  private static void unzipAssetIfPresent(Context context, String assetZipPath, File targetDir) {
    try (InputStream assetIn = context.getAssets().open(assetZipPath);
         ZipInputStream zis = new ZipInputStream(assetIn)) {
      if (!targetDir.exists()) {
        targetDir.mkdirs();
      }
      byte[] buffer = new byte[65536];
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        File file = new File(targetDir, entry.getName());
        // Guard against Zip Slip vulnerability
        String canonicalTarget = targetDir.getCanonicalPath();
        String canonicalDest = file.getCanonicalPath();
        if (!canonicalDest.startsWith(canonicalTarget + File.separator) && !canonicalDest.equals(canonicalTarget)) {
          throw new SecurityException("Zip entry outside target directory: " + entry.getName());
        }

        if (entry.isDirectory()) {
          file.mkdirs();
        } else {
          File p = file.getParentFile();
          if (p != null && !p.exists()) {
            p.mkdirs();
          }
          try (FileOutputStream fos = new FileOutputStream(file)) {
            int len;
            while ((len = zis.read(buffer)) > 0) {
              fos.write(buffer, 0, len);
            }
            fos.getFD().sync();
          }
        }
        zis.closeEntry();
      }
    } catch (IOException ignored) {
      // If optional zip asset is missing or error occurs, continue gracefully
    }
  }
}
