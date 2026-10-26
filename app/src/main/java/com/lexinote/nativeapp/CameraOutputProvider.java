package com.lexinote.nativeapp;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Narrow URI-grant provider used only for full-resolution camera output files. */
public final class CameraOutputProvider extends ContentProvider {
  static final String AUTHORITY = "com.lexinote.nativeapp.camera";
  static final String PATH = "capture";

  static File file(ContextAccess access, Uri uri) throws FileNotFoundException {
    if (uri == null
        || !AUTHORITY.equals(uri.getAuthority())
        || uri.getPathSegments().size() != 2
        || !PATH.equals(uri.getPathSegments().get(0)))
      throw new FileNotFoundException("Invalid camera output URI");
    String name = uri.getLastPathSegment();
    if (!name.matches("[a-f0-9-]{36}\\.jpg"))
      throw new FileNotFoundException("Invalid camera file name");
    File root = new File(access.filesDir(), "camera");
    File target = new File(root, name);
    if (!target.getAbsolutePath().startsWith(root.getAbsolutePath() + File.separator))
      throw new FileNotFoundException("Invalid camera file path");
    return target;
  }

  interface ContextAccess {
    File filesDir();
  }

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    File target = file(() -> getContext().getFilesDir(), uri);
    File parent = target.getParentFile();
    if (!parent.isDirectory() && !parent.mkdirs())
      throw new FileNotFoundException("Cannot create camera directory");
    if (mode.equals("r"))
      return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY);
    if (mode.equals("w") || mode.equals("wt"))
      return ParcelFileDescriptor.open(
          target,
          ParcelFileDescriptor.MODE_WRITE_ONLY
              | ParcelFileDescriptor.MODE_CREATE
              | ParcelFileDescriptor.MODE_TRUNCATE);
    if (mode.equals("rw"))
      return ParcelFileDescriptor.open(
          target, ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE);
    throw new FileNotFoundException("Unsupported camera output mode");
  }

  @Override
  public String getType(Uri uri) {
    return "image/jpeg";
  }

  @Override
  public Cursor query(
      Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
    try {
      File file = file(() -> getContext().getFilesDir(), uri);
      String[] cols =
          projection == null
              ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
              : projection;
      MatrixCursor cursor = new MatrixCursor(cols);
      Object[] row = new Object[cols.length];
      for (int i = 0; i < cols.length; i++) {
        if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = file.getName();
        else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = file.length();
      }
      cursor.addRow(row);
      return cursor;
    } catch (FileNotFoundException invalid) {
      throw new IllegalArgumentException(invalid);
    }
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri uri, String selection, String[] args) {
    try {
      return file(() -> getContext().getFilesDir(), uri).delete() ? 1 : 0;
    } catch (FileNotFoundException invalid) {
      return 0;
    }
  }

  @Override
  public int update(Uri uri, ContentValues values, String selection, String[] args) {
    throw new UnsupportedOperationException();
  }
}
