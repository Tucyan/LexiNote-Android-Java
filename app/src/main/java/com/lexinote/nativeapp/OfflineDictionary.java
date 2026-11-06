package com.lexinote.nativeapp;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Read-only, independently packaged ECDICT. Call lookup on a background thread. */
public final class OfflineDictionary {
  private static final String DB = "ecdict-39cfb967-v1.db";
  private static final Object INSTALL_LOCK = new Object();
  private OfflineDictionary() {}

  public static JSONObject lookup(Context context, String query) throws IOException, JSONException {
    String key = query == null ? "" : query.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    if (key.isEmpty()) return null;
    File file = install(context.getApplicationContext());
    try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
      JSONObject result = find(db, key);
      if (result != null) return result;
      try (Cursor c = db.rawQuery("SELECT headword FROM variant WHERE key=?", new String[] {key})) {
        if (!c.moveToFirst()) return null;
        result = find(db, c.getString(0));
        if (result != null) result.put("requested_form", query.trim());
        return result;
      }
    }
  }

  private static File install(Context context) throws IOException {
    synchronized (INSTALL_LOCK) {
      File file = new File(context.getFilesDir(), DB);
      if (file.isFile()) return file;
      File temp = new File(context.getFilesDir(), DB + ".tmp");
      try {
        try (InputStream in = context.getAssets().open("ecdict.db"); FileOutputStream out = new FileOutputStream(temp)) {
          byte[] buffer = new byte[65536];
          int count;
          while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
          out.getFD().sync();
        }
        if (!temp.renameTo(file)) throw new IOException("Unable to install offline dictionary");
        return file;
      } finally {
        if (temp.exists() && !temp.delete()) temp.deleteOnExit();
      }
    }
  }

  private static JSONObject find(SQLiteDatabase db, String key) throws JSONException {
    try (Cursor c = db.rawQuery("SELECT word, phonetic, definition, translation FROM entry WHERE key=?", new String[] {key})) {
      return c.moveToFirst() ? toResult(c.getString(0), c.getString(1), c.getString(2), c.getString(3)) : null;
    }
  }

  static JSONObject toResult(String word, String phonetic, String english, String chinese) throws JSONException {
    JSONObject result = new JSONObject();
    result.put("word", word);
    result.put("type", word.contains(" ") ? "phrase" : "word");
    result.put("phonetic", phonetic);
    result.put("lookup_origin", "ecdict");
    result.put("dictionary_source", new JSONObject().put("name", "ECDICT").put("version", "39cfb9672c0906b63569e0453c2e6abfb9ab182a"));
    result.put("field_meta", phonetic == null || phonetic.trim().isEmpty()
        ? metadata("word", "type") : metadata("word", "type", "phonetic"));
    JSONArray meanings = new JSONArray();
    addMeanings(meanings, chinese, "zh_definition", "zh");
    addMeanings(meanings, english, "en_definition", "en");
    result.put("meanings", meanings);
    return result;
  }

  private static void addMeanings(JSONArray meanings, String text, String field, String language) throws JSONException {
    if (text == null || text.trim().isEmpty()) return;
    String[] lines = text.replace("\\n", "\n").split("\\r?\\n");
    int n = 0;
    StringBuilder overflow = new StringBuilder();
    for (String line : lines) {
      line = line.trim();
      if (line.isEmpty()) continue;
      if (n >= 14) { if (overflow.length() > 0) overflow.append('\n'); overflow.append(line); continue; }
      meanings.put(new JSONObject().put("id", language + "-" + n++).put(field, line).put("field_meta", metadata(field)));
    }
    if (overflow.length() > 0) meanings.put(new JSONObject().put("id", language + "-" + n).put(field, overflow.toString()).put("field_meta", metadata(field)));
  }

  private static JSONObject metadata(String... fields) throws JSONException {
    JSONObject meta = new JSONObject();
    for (String field : fields) meta.put(field, new JSONObject().put("is_ai_generated", false));
    return meta;
  }
}
