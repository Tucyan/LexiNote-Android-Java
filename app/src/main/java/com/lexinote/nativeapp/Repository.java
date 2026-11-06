package com.lexinote.nativeapp;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Atomic document snapshots; both active and pre-restore snapshots retain a last good copy. */
public class Repository {
  private final File baseFile, previousFile;
  private final AtomicFile file, previous;

  public Repository(File dir) {
    this.baseFile = new File(dir, "library.json");
    this.previousFile = new File(dir, "before-restore.json");
    AtomicFile af = null, ap = null;
    try {
      af = new AtomicFile(baseFile);
      ap = new AtomicFile(previousFile);
    } catch (Throwable ignored) {}
    this.file = af;
    this.previous = ap;
  }

  public Repository(Context c) {
    this(c.getFilesDir());
  }

  public File getFilesDir() {
    return baseFile.getParentFile();
  }

  public Library load() throws Exception {
    try (InputStream in = openStream(file, baseFile)) {
      return new Library(new JSONObject(new String(read(in), StandardCharsets.UTF_8)));
    } catch (FileNotFoundException e) {
      if (!baseFile.exists() && !new File(baseFile.getPath() + ".bak").exists())
        return new Library();
      throw e;
    }
  }

  private InputStream openStream(AtomicFile af, File fallback) throws IOException {
    if (af != null) {
      try {
        return af.openRead();
      } catch (Throwable t) {
        if (!isNotMocked(t)) {
          if (t instanceof IOException) throw (IOException) t;
          throw new IOException(t);
        }
      }
    }
    File bak = new File(fallback.getPath() + ".bak");
    if (bak.exists()) {
      fallback.delete();
      bak.renameTo(fallback);
    }
    return new FileInputStream(fallback);
  }

  public void save(Library library) throws Exception {
    JSONObject snapshot = library.snapshot();
    Library.validate(snapshot);
    write(file, baseFile, snapshot);
  }

  private void write(AtomicFile target, File fallback, JSONObject data) throws Exception {
    byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
    if (bytes.length > BackupArchive.MAX_JSON_BYTES)
      throw new IOException("笔记文本超过当前设备容量（最高4 MB），本次修改未写入");

    if (target != null) {
      try {
        FileOutputStream out = target.startWrite();
        out.write(bytes);
        target.finishWrite(out);
        return;
      } catch (Throwable t) {
        if (!isNotMocked(t)) {
          if (t instanceof Exception) throw (Exception) t;
          throw new RuntimeException(t);
        }
      }
    }

    // Atomic write fallback for host JVM (and when AtomicFile is not available)
    File bak = new File(fallback.getPath() + ".bak");
    File tmp = new File(fallback.getPath() + ".new");
    try (FileOutputStream out = new FileOutputStream(tmp)) {
      out.write(bytes);
      out.getFD().sync();
    }
    if (fallback.exists()) {
      if (!bak.exists()) {
        if (!fallback.renameTo(bak)) {
          throw new IOException("Failed to rename " + fallback + " to " + bak);
        }
      } else {
        fallback.delete();
      }
    }
    if (!tmp.renameTo(fallback)) {
      if (bak.exists()) bak.renameTo(fallback);
      throw new IOException("Failed to rename " + tmp + " to " + fallback);
    }
    bak.delete();
  }

  public Library restore(JSONObject backup, Library current) throws Exception {
    Library.validate(backup);
    Library replacement = new Library(backup);
    // Provider credentials live in SecureSettings, outside these documents.
    write(previous, previousFile, current.snapshot());
    save(replacement);
    return replacement;
  }

  private byte[] read(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] b = new byte[8192];
    int n;
    while ((n = in.read(b)) != -1) {
      if (out.size() + n > BackupArchive.MAX_JSON_BYTES)
        throw new IOException("笔记文本超过当前设备容量（最高4 MB）");
      out.write(b, 0, n);
    }
    return out.toByteArray();
  }

  public Library rollback() throws Exception {
    try (InputStream in = openStream(previous, previousFile)) {
      Library l = new Library(new JSONObject(new String(read(in), StandardCharsets.UTF_8)));
      save(l);
      return l;
    }
  }

  /** Preserve the exact unreadable bytes before the user explicitly restores a backup. */
  public void preserveCorruptData() throws IOException {
    File copy =
        new File(
            baseFile.getParentFile(),
            "library-corrupt-" + System.currentTimeMillis() + ".json");
    try (InputStream in = openStream(file, baseFile);
        FileOutputStream out = new FileOutputStream(copy)) {
      byte[] bytes = new byte[8192];
      int count;
      while ((count = in.read(bytes)) != -1) out.write(bytes, 0, count);
      out.getFD().sync();
    }
  }

  private static boolean isNotMocked(Throwable t) {
    if (t == null) return false;
    String msg = t.getMessage();
    return (t instanceof RuntimeException) && (msg != null && msg.contains("not mocked"));
  }
}
