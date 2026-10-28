package com.lexinote.nativeapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Provider metadata is portable; API credentials are device-bound and never in backups. */
public final class SecureSettings {
  private static final String ALIAS = "lexinote.api.v1";
  private static final String PROVIDERS = "providers";
  private static final String ACTIVE = "active_provider_id";
  private final SharedPreferences preferences;

  public SecureSettings(Context context) {
    preferences = context.getSharedPreferences("provider_private", Context.MODE_PRIVATE);
  }

  public synchronized JSONArray getProviders() {
    migrateLegacy();
    String raw = preferences.getString(PROVIDERS, null);
    if (raw == null) return ProviderProfiles.defaults();
    try {
      JSONArray parsed = new JSONArray(raw), safe = new JSONArray();
      for (int i = 0; i < parsed.length(); i++)
        safe.put(
            ProviderProfiles.sanitize(
                parsed.getJSONObject(i), parsed.getJSONObject(i).optBoolean("is_custom", false)));
      return safe;
    } catch (Exception e) {
      throw new IllegalStateException("模型设置损坏，请重新配置", e);
    }
  }

  public synchronized JSONObject getProvider() {
    JSONArray providers = getProviders();
    String selected = preferences.getString(ACTIVE, "tokendance");
    for (int i = 0; i < providers.length(); i++) {
      JSONObject p = providers.optJSONObject(i);
      if (p != null && selected.equals(p.optString("id"))) return copy(p);
    }
    JSONObject first = providers.optJSONObject(0);
    if (first == null) throw new IllegalStateException("没有可用的模型配置");
    selectProvider(first.optString("id"));
    return copy(first);
  }

  public synchronized void selectProvider(String id) {
    JSONArray providers = getProviders();
    for (int i = 0; i < providers.length(); i++)
      if (id.equals(providers.optJSONObject(i).optString("id"))) {
        if (!preferences.edit().putString(ACTIVE, id).commit())
          throw new IllegalStateException("无法切换模型配置");
        return;
      }
    throw new IllegalArgumentException("找不到模型配置");
  }

  public synchronized String addProvider(JSONObject config, String apiKey) throws Exception {
    JSONObject input = copy(config);
    String id = input.optString("id", "").trim();
    if (id.isEmpty()) id = "custom_" + UUID.randomUUID();
    input.put("id", id);
    JSONObject safe = ProviderProfiles.sanitize(input, true);
    JSONArray all = getProviders();
    boolean replaced = false;
    JSONArray next = new JSONArray();
    for (int i = 0; i < all.length(); i++) {
      JSONObject existing = all.getJSONObject(i);
      if (id.equals(existing.optString("id"))) {
        if (!existing.optBoolean("is_custom", false))
          throw new IllegalArgumentException("预设服务商不能作为自定义配置更新");
        requireKeyForDestinationChange(existing, safe, id, apiKey);
        next.put(safe);
        replaced = true;
      } else next.put(existing);
    }
    if (!replaced) next.put(safe);
    SharedPreferences.Editor editor = preferences.edit().putString(PROVIDERS, next.toString());
    if (apiKey != null) {
      putKey(editor, id, apiKey);
      if (id.equals("tokendance")) editor.remove(keyName("tokendance_pending_payment")).remove(ivName("tokendance_pending_payment"));
    }
    if (!editor.commit()) throw new IllegalStateException("无法保存模型设置，请检查存储空间");
    return id;
  }

  public synchronized void deleteProvider(String id) {
    JSONArray all = getProviders(), next = new JSONArray();
    boolean found = false;
    for (int i = 0; i < all.length(); i++) {
      JSONObject p = all.optJSONObject(i);
      if (p != null && id.equals(p.optString("id"))) {
        if (!p.optBoolean("is_custom", false)) throw new IllegalArgumentException("预设服务商不能删除");
        found = true;
      } else if (p != null) next.put(p);
    }
    if (!found) throw new IllegalArgumentException("找不到自定义模型配置");
    if (next.length() == 0) throw new IllegalStateException("至少保留一个模型配置");
    SharedPreferences.Editor editor =
        preferences
            .edit()
            .putString(PROVIDERS, next.toString())
            .remove(keyName(id))
            .remove(ivName(id));
    if (id.equals(preferences.getString(ACTIVE, "tokendance")))
      editor.putString(ACTIVE, next.optJSONObject(0).optString("id"));
    if (!editor.commit()) throw new IllegalStateException("无法删除模型配置");
  }

  public synchronized void saveProvider(JSONObject provider, String apiKey) throws Exception {
    JSONArray current = getProviders();
    String id = provider.optString("id", preferences.getString(ACTIVE, "tokendance"));
    if (id.trim().isEmpty()) id = preferences.getString(ACTIVE, "tokendance");
    JSONObject supplied = copy(provider).put("id", id);
    boolean custom = false, found = false;
    JSONObject existing = null;
    for (int i = 0; i < current.length(); i++)
      if (id.equals(current.getJSONObject(i).optString("id"))) {
        existing = current.getJSONObject(i);
        custom = existing.optBoolean("is_custom", false);
        found = true;
      }
    if (!found) throw new IllegalArgumentException("找不到模型配置");
    JSONObject input = copy(existing);
    if ((supplied.has("model_id")
            && !supplied.optString("model_id").equals(existing.optString("model_id")))
        || (supplied.has("base_url")
            && !ProviderProfiles.destinationKey(supplied.optString("base_url"))
                .equals(ProviderProfiles.destinationKey(existing.optString("base_url"))))) {
      input.remove("reasoning_levels");
      input.remove("vision_known");
    }
    for (String field :
        new String[] {
          "name",
          "base_url",
          "model_id",
          "supports_vision",
          "reasoning_effort",
          "reasoning_levels",
          "vision_known",
          "is_custom_model_id",
          "is_custom_base_url"
        }) if (supplied.has(field)) input.put(field, supplied.get(field));
    input.put("id", id);
    JSONObject safe = ProviderProfiles.sanitize(input, custom);
    requireKeyForDestinationChange(existing, safe, id, apiKey);
    JSONArray next = new JSONArray();
    for (int i = 0; i < current.length(); i++) {
      JSONObject p = current.getJSONObject(i);
      next.put(id.equals(p.optString("id")) ? safe : p);
    }
    SharedPreferences.Editor editor = preferences.edit().putString(PROVIDERS, next.toString());
    if (apiKey != null) {
      putKey(editor, id, apiKey);
      if (id.equals("tokendance")) editor.remove(keyName("tokendance_pending_payment")).remove(ivName("tokendance_pending_payment"));
    }
    if (!editor.commit()) throw new IllegalStateException("无法保存模型设置，请检查存储空间");
  }

  public synchronized String getApiKey(String id) throws Exception {
    migrateLegacy();
    return readKey(id);
  }

  public synchronized String getApiKey() throws Exception {
    return getApiKey(getProvider().optString("id"));
  }

  /** A draft can reuse a credential only when both identity and destination still match. */
  public synchronized String getApiKeyFor(JSONObject draft, String enteredKey) throws Exception {
    if (enteredKey != null && !enteredKey.trim().isEmpty()) return enteredKey.trim();
    JSONObject active = getProvider();
    String id = draft.optString("id", "");
    if (!id.equals(active.optString("id"))) return "";
    try {
      if (!ProviderProfiles.destinationKey(draft.optString("base_url"))
          .equals(ProviderProfiles.destinationKey(active.optString("base_url")))) return "";
    } catch (RuntimeException invalid) {
      return "";
    }
    return readKey(id);
  }

  /** Pending PKCE verifier and state share the device-bound encrypted store, never backups. */
  public synchronized void saveTokenDanceAuthorization(TokenDanceProtocol.Session session) throws Exception {
    SharedPreferences.Editor editor = preferences.edit();
    putKey(editor, "tokendance_pending_authorization", session.toJson());
    if (!editor.commit()) throw new IllegalStateException("无法保存授权会话");
  }

  public synchronized TokenDanceProtocol.Session pendingTokenDanceAuthorization() throws Exception {
    String raw = readKey("tokendance_pending_authorization");
    return raw.isEmpty() ? null : TokenDanceProtocol.Session.fromJson(raw);
  }

  public synchronized void clearTokenDanceAuthorization() {
    if (!preferences.edit().remove(keyName("tokendance_pending_authorization"))
        .remove(ivName("tokendance_pending_authorization")).commit())
      throw new IllegalStateException("无法清除授权会话");
  }

  /** One atomic write activates the official destination and saves the issued key. */
  public synchronized void connectTokenDance(String apiKey) throws Exception {
    if (apiKey == null || apiKey.trim().isEmpty()) throw new IllegalArgumentException("授权未返回有效密钥");
    JSONArray all = getProviders(), next = new JSONArray();
    for (int i = 0; i < all.length(); i++) {
      JSONObject provider = all.getJSONObject(i);
      if ("tokendance".equals(provider.optString("id"))) {
        provider = copy(provider).put("base_url", "https://tokendance.space/gateway/v1")
            .put("is_custom_base_url", false);
        try { TokenDanceCatalog.assertChatModel(provider); }
        catch (IllegalArgumentException invalid) {
          provider.put("model_id", "deepseek-v4.1-flash").put("is_custom_model_id", false);
        }
        if (TokenDanceCatalog.isKnown(provider.optString("model_id")))
          provider.put("supports_vision", true).put("vision_known", true);
      }
      next.put(provider);
    }
    SharedPreferences.Editor editor = preferences.edit().putString(PROVIDERS, next.toString())
        .putString(ACTIVE, "tokendance").remove(keyName("tokendance_pending_payment")).remove(ivName("tokendance_pending_payment"))
        .remove(keyName("tokendance_pending_authorization"))
        .remove(ivName("tokendance_pending_authorization"));
    putKey(editor, "tokendance", apiKey);
    if (!editor.commit()) throw new IllegalStateException("无法保存授权，请重新连接");
  }

  public synchronized void saveTokenDancePayment(JSONObject state) throws Exception {
    SharedPreferences.Editor editor=preferences.edit();
    putKey(editor,"tokendance_pending_payment",state.toString());
    if(!editor.commit()) throw new IllegalStateException("无法保存充值会话");
  }

  public synchronized JSONObject pendingTokenDancePayment() throws Exception {
    String raw=readKey("tokendance_pending_payment");
    return raw.isEmpty() ? null : new JSONObject(raw);
  }

  private void migrateLegacy() {
    if (preferences.contains(PROVIDERS)) return;
    if (!preferences.contains("provider")
        && !preferences.contains("key")
        && !preferences.contains("iv")) return;
    try {
      JSONObject legacy = new JSONObject(preferences.getString("provider", "{}"));
      if (!legacy.has("base_url")) legacy.put("base_url", "https://api.openai.com/v1");
      if (!legacy.has("model_id")) legacy.put("model_id", "gpt-4o-mini");
      if (legacy.optString("name").trim().isEmpty()) legacy.put("name", "兼容服务（已迁移）");
      if (!legacy.has("supports_vision")) legacy.put("supports_vision", true);
      JSONObject safe;
      String id = legacy.optString("id", "").trim();
      if (id.isEmpty()) {
        String base = legacy.optString("base_url", "https://api.openai.com/v1");
        String model = legacy.optString("model_id", "gpt-4o-mini");
        id = null;
        for (int i = 0; i < ProviderProfiles.defaults().length(); i++) {
          JSONObject preset = ProviderProfiles.defaults().getJSONObject(i);
          if (ProviderProfiles.destinationKey(base)
                  .equals(ProviderProfiles.destinationKey(preset.optString("base_url")))
              && model.equals(preset.optString("model_id"))) {
            id = preset.optString("id");
            break;
          }
        }
        if (id == null) id = "legacy_" + UUID.randomUUID();
      }
      legacy.put("id", id);
      boolean custom = legacy.optBoolean("is_custom", !isPreset(id));
      safe = ProviderProfiles.sanitize(legacy, custom);
      JSONArray all = ProviderProfiles.defaults();
      boolean matched = false;
      for (int i = 0; i < all.length(); i++)
        if (id.equals(all.getJSONObject(i).optString("id"))) {
          all.put(i, safe);
          matched = true;
        }
      if (!matched) all.put(safe);
      SharedPreferences.Editor editor =
          preferences.edit().putString(PROVIDERS, all.toString()).putString(ACTIVE, id);
      String oldCipher = preferences.getString("key", null),
          oldIv = preferences.getString("iv", null);
      if (oldCipher != null && oldIv != null)
        editor.putString(keyName(id), oldCipher).putString(ivName(id), oldIv);
      editor.remove("provider").remove("key").remove("iv");
      if (!editor.commit()) throw new IllegalStateException("无法迁移模型设置，请重试");
    } catch (Exception e) {
      throw new IllegalStateException("迁移模型设置失败", e);
    }
  }

  private boolean isPreset(String id) {
    return ProviderProfiles.preset(id) != null;
  }

  private String readKey(String id) throws Exception {
    String stored = preferences.getString(keyName(id), null);
    if (stored == null) return "";
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          key(),
          new GCMParameterSpec(
              128, Base64.decode(preferences.getString(ivName(id), ""), Base64.NO_WRAP)));
      return new String(
          cipher.doFinal(Base64.decode(stored, Base64.NO_WRAP)), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("本机密钥无法解密，请在设置中重新填写 API Key", e);
    }
  }

  private void putKey(SharedPreferences.Editor editor, String id, String value) throws Exception {
    if (value.trim().isEmpty()) {
      editor.remove(keyName(id)).remove(ivName(id));
      return;
    }
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    editor.putString(
        keyName(id),
        Base64.encodeToString(
            cipher.doFinal(value.trim().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP));
    editor.putString(ivName(id), Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
  }

  private String keyName(String id) {
    return "key_" + id;
  }

  private String ivName(String id) {
    return "iv_" + id;
  }

  private void requireKeyForDestinationChange(
      JSONObject previous, JSONObject next, String id, String apiKey) {
    if (apiKey == null
        && preferences.contains(keyName(id))
        && !ProviderProfiles.destinationKey(previous.optString("base_url"))
            .equals(ProviderProfiles.destinationKey(next.optString("base_url"))))
      throw new IllegalArgumentException("服务地址已更改，请重新填写 API Key 或清空密钥");
  }

  private JSONObject copy(JSONObject source) {
    try {
      return new JSONObject(source.toString());
    } catch (org.json.JSONException e) {
      throw new IllegalStateException("模型配置无效", e);
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
