package com.lexinote.nativeapp;

import android.net.Uri;
import org.json.JSONObject;

/** Confirmed editor output; source remains intact and derived URI is independently optional. */
public final class ImageEditorResult {
  public final Uri originalUri;

  /** Null means use the untouched source URI as effective input. */
  public final Uri effectiveUri;

  public final JSONObject region;
  public final ImageEditorState state;

  ImageEditorResult(
      Uri originalUri, Uri effectiveUri, SelectedRegion region, ImageEditorState state) {
    this.originalUri = originalUri;
    this.effectiveUri = effectiveUri;
    this.region = region == null ? null : region.toJson();
    this.state = state;
  }
}
