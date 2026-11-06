package com.lexinote.nativeapp;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** Generation-scoped storage. JSON is stored per entity, never as a whole document. */
public final class LibraryDatabase extends SQLiteOpenHelper {
  public LibraryDatabase(Context context, String name) { super(context, name, null, 1); }

  @Override public void onConfigure(SQLiteDatabase db) {
    super.onConfigure(db);
    db.setForeignKeyConstraintsEnabled(true);
  }

  @Override public void onCreate(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE store_meta (key TEXT PRIMARY KEY, value INTEGER NOT NULL)");
    db.execSQL("INSERT INTO store_meta VALUES ('active',0),('previous',0),('next',1),('revision',0)");
    db.execSQL("CREATE TABLE document_meta (generation INTEGER NOT NULL, key TEXT NOT NULL, json TEXT NOT NULL, PRIMARY KEY(generation,key))");
    db.execSQL("CREATE TABLE entities (generation INTEGER NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, ordinal INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(generation,kind,id), UNIQUE(generation,kind,ordinal))");
    db.execSQL("CREATE TABLE review_plans (generation INTEGER NOT NULL, id TEXT NOT NULL, ordinal INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(generation,id), UNIQUE(generation,ordinal))");
    db.execSQL("CREATE TABLE review_states (generation INTEGER NOT NULL, plan_id TEXT NOT NULL, vocab_id TEXT NOT NULL, ordinal INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(generation,plan_id,vocab_id), UNIQUE(generation,ordinal))");
    db.execSQL("CREATE TABLE review_days (generation INTEGER NOT NULL, plan_id TEXT NOT NULL, date TEXT NOT NULL, ordinal INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(generation,plan_id,date), UNIQUE(generation,ordinal))");
    db.execSQL("CREATE TABLE review_events (generation INTEGER NOT NULL, id TEXT NOT NULL, ordinal INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(generation,id), UNIQUE(generation,ordinal))");
    ensureSessionIndexes(db);
  }

  static void ensureSessionIndexes(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE IF NOT EXISTS session_index (generation INTEGER NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, sort_time INTEGER NOT NULL, sort_id BLOB NOT NULL, retention TEXT NOT NULL, PRIMARY KEY(generation,kind,id))");
    db.execSQL("CREATE INDEX IF NOT EXISTS session_ordering ON session_index(generation,kind,sort_time DESC,sort_id DESC)");
    db.execSQL("CREATE TABLE IF NOT EXISTS session_order (generation INTEGER NOT NULL, kind TEXT NOT NULL, PRIMARY KEY(generation,kind))");
  }

  @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    throw new IllegalStateException("Unsupported library database upgrade");
  }
}
