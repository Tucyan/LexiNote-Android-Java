package com.lexinote.nativeapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Provider metadata is portable; API credentials are device-bound and never in backups. */
public final class SecureSettings {
  private static final String ALIAS = "lexinote.api.v1";
  private final SharedPreferences preferences;

  public SecureSettings(Context context) {
    preferences = context.getSharedPreferences("provider_private", Context.MODE_PRIVATE);
  }

  public JSONObject getProvider() {
    try {
      return new JSONObject(
          preferences.getString(
              "provider",
              "{\"name\":\"OpenAI"
                  + " compatible\",\"base_url\":\"https://api.openai.com/v1\",\"model_id\":\"gpt-4o-mini\",\"supports_vision\":true}"));
    } catch (Exception e) {
      throw new IllegalStateException("模型设置损坏，请重新配置", e);
    }
  }

  public synchronized void saveProvider(JSONObject provider, String apiKey) throws Exception {
    AgentProtocol.endpoint(provider.optString("base_url"));
    if (provider.optString("model_id").trim().isEmpty())
      throw new IllegalArgumentException("请填写模型 ID");
    JSONObject safe = new JSONObject();
    for (String field :
        new String[] {"name", "base_url", "model_id", "supports_vision", "reasoning_effort"})
      if (provider.has(field)) safe.put(field, provider.get(field));
    SharedPreferences.Editor editor = preferences.edit().putString("provider", safe.toString());
    if (apiKey != null) {
      if (apiKey.trim().isEmpty()) editor.remove("key").remove("iv");
      else {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        editor.putString(
            "key",
            Base64.encodeToString(
                cipher.doFinal(apiKey.trim().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP));
        editor.putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
      }
    }
    if (!editor.commit()) throw new IllegalStateException("无法保存模型设置，请检查存储空间");
  }

  public synchronized String getApiKey() throws Exception {
    String stored = preferences.getString("key", null);
    if (stored == null) return "";
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          key(),
          new GCMParameterSpec(
              128, Base64.decode(preferences.getString("iv", ""), Base64.NO_WRAP)));
      return new String(
          cipher.doFinal(Base64.decode(stored, Base64.NO_WRAP)), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("本机密钥无法解密，请在设置中重新填写 API Key");
    }
  }

  private SecretKey key() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
    KeyGenerator generator =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    generator.init(
        new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
    return generator.generateKey();
  }
}
