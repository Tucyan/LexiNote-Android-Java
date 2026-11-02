package com.lexinote.nativeapp.storage.contract;

import com.lexinote.nativeapp.Library;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Immutable read-only view of storage data.
 * Protects internal JSON structures from external UI modification.
 */
public final class ReadOnlyView {

  private final long generation;
  private final long revision;
  private final long timestamp;
  private final JSONObject rawDocument;

  public ReadOnlyView(long generation, long revision, long timestamp, JSONObject rawDocument) {
    this.generation = generation;
    this.revision = revision;
    this.timestamp = timestamp;
    this.rawDocument = rawDocument == null ? new JSONObject() : Library.copy(rawDocument);
  }

  public ReadOnlyView(JSONObject rawDocument, long generation, long revision) {
    this(generation, revision, System.currentTimeMillis(), rawDocument);
  }

  public long getGeneration() { return generation; }
  public long getRevision() { return revision; }
  public long getTimestamp() { return timestamp; }

  public JSONObject getRawDocument() {
    return Library.copy(rawDocument);
  }

  public JSONObject getSettings() {
    JSONObject settings = rawDocument.optJSONObject("settings");
    return settings == null ? null : Library.copy(settings);
  }

  public JSONArray getNotebooks() {
    JSONArray nbs = rawDocument.optJSONArray("notebooks");
    if (nbs == null) return new JSONArray();
    try {
      return new JSONArray(nbs.toString());
    } catch (Exception e) {
      return new JSONArray();
    }
  }

  public JSONArray getVocabularies() {
    JSONArray vocabs = rawDocument.optJSONArray("vocabularies");
    if (vocabs == null) return new JSONArray();
    try {
      return new JSONArray(vocabs.toString());
    } catch (Exception e) {
      return new JSONArray();
    }
  }

  public JSONObject getNotebook(String id) {
    if (id == null) return null;
    JSONArray nbs = rawDocument.optJSONArray("notebooks");
    if (nbs == null) return null;
    for (int i = 0; i < nbs.length(); i++) {
      JSONObject nb = nbs.optJSONObject(i);
      if (nb != null && id.equals(nb.optString("id"))) {
        return Library.copy(nb);
      }
    }
    return null;
  }

  public JSONObject getVocabulary(String id) {
    if (id == null) return null;
    JSONArray vocabs = rawDocument.optJSONArray("vocabularies");
    if (vocabs == null) return null;
    for (int i = 0; i < vocabs.length(); i++) {
      JSONObject v = vocabs.optJSONObject(i);
      if (v != null && id.equals(v.optString("id"))) {
        return Library.copy(v);
      }
    }
    return null;
  }
}
