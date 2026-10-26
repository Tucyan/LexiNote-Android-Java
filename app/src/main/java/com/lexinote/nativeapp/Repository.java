package com.lexinote.nativeapp;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Atomic document snapshots; both active and pre-restore snapshots retain a last good copy. */
public final class Repository {
  private final AtomicFile file, previous;

  public Repository(Context c) {
    file = new AtomicFile(new File(c.getFilesDir(), "library.json"));
    previous = new AtomicFile(new File(c.getFilesDir(), "before-restore.json"));
  }

  public Library load() throws Exception {
    try (FileInputStream in = file.openRead()) {
      return new Library(new JSONObject(new String(read(in), StandardCharsets.UTF_8)));
    } catch (FileNotFoundException e) {
      if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists())
        return new Library();
      throw e;
    }
  }

  public void save(Library library) throws Exception {
    Library.validate(library.snapshot());
    write(file, library.snapshot());
  }

  private void write(AtomicFile target, JSONObject data) throws Exception {
    byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
    if (bytes.length > BackupArchive.MAX_JSON_BYTES)
      throw new IOException("笔记文本超过当前设备容量（最高4 MB），本次修改未写入");
    FileOutputStream out = null;
    try {
      out = target.startWrite();
      out.write(bytes);
      target.finishWrite(out);
    } catch (Exception e) {
      if (out != null) target.failWrite(out);
      throw e;
    }
  }

  public Library restore(JSONObject backup, Library current) throws Exception {
    Library.validate(backup);
    Library replacement = new Library(backup);
    // Provider credentials live in SecureSettings, outside these documents.
    write(previous, current.snapshot());
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
    try (FileInputStream in = previous.openRead()) {
      Library l = new Library(new JSONObject(new String(read(in), StandardCharsets.UTF_8)));
      save(l);
      return l;
    }
  }

  /** Preserve the exact unreadable bytes before the user explicitly restores a backup. */
  public void preserveCorruptData() throws IOException {
    File copy =
        new File(
            file.getBaseFile().getParentFile(),
            "library-corrupt-" + System.currentTimeMillis() + ".json");
    try (InputStream in = file.openRead();
        FileOutputStream out = new FileOutputStream(copy)) {
      byte[] bytes = new byte[8192];
      int count;
      while ((count = in.read(bytes)) != -1) out.write(bytes, 0, count);
      out.getFD().sync();
    }
  }
}
