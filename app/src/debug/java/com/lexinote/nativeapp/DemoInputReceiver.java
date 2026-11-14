package com.lexinote.nativeapp;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.util.Base64;
import java.nio.charset.StandardCharsets;

/** Debug-only controls for the shell driving the demo. */
public final class DemoInputReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context context, Intent intent) {
    if ("com.lexinote.nativeapp.DEMO_TOKENDANCE_STATUS".equals(intent.getAction())) {
      setResultCode(Activity.RESULT_CANCELED);
      if (!"com.lexinote.nativeapp.demo".equals(context.getPackageName())) return;
      try {
        SecureSettings settings = new SecureSettings(context);
        org.json.JSONObject provider = settings.getProvider();
        if ("tokendance".equals(provider.optString("id"))
            && TokenDanceCatalog.isGateway(provider.optString("base_url"))
            && !provider.optString("model_id").trim().isEmpty()
            && !settings.getApiKey("tokendance").trim().isEmpty()) {
          setResultCode(Activity.RESULT_OK);
        }
      } catch (Exception unavailable) {
        // Return only the status to the demo runner.
      }
      return;
    }
    if (!"com.lexinote.nativeapp.DEMO_INPUT".equals(intent.getAction())) return;
    String input = intent.getStringExtra("text64");
    if (input == null || input.length() > 4096) return;
    try {
      String value = new String(Base64.decode(input, Base64.DEFAULT), StandardCharsets.UTF_8);
      ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
      if (clipboard == null) return;
      clipboard.setPrimaryClip(ClipData.newPlainText("LexiNote demo", value));
      setResultCode(Activity.RESULT_OK);
    } catch (IllegalArgumentException invalid) {
      setResultCode(Activity.RESULT_CANCELED);
    }
  }
}
