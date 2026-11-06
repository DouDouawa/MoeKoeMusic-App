package cn.MoeKoe.Music.playback;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Iterator;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Playback configuration (API base URL and credentials) in app-private storage.
 *
 * <p>The payload is encrypted with an AndroidKeyStore key that never leaves the keystore, so a
 * backup of the preferences file does not leak a session token. If the keystore is unusable we
 * keep the values in memory only rather than falling back to plaintext on disk.
 */
public final class ConfigStore {

    private static final String TAG = "MoeConfig";
    private static final String PREFS = "moe_playback_config";
    private static final String PAYLOAD_KEY = "payload";
    private static final String KEY_ALIAS = "moe_playback_config_v1";
    private static final int GCM_TAG_BITS = 128;

    public static final long DEFAULT_CACHE_LIMIT = 500L * 1024L * 1024L;

    private final SharedPreferences prefs;
    private final JSONObject values = new JSONObject();
    private boolean persistent = true;

    public ConfigStore(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    /** Merges a patch so a cache-only update never drops the API/account fields. */
    public synchronized void apply(JSONObject patch) {
        if (patch == null) return;
        Iterator<String> keys = patch.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = patch.opt(key);
            if (value == null || value == JSONObject.NULL) continue;
            try {
                values.put(key, value);
            } catch (Exception error) {
                Log.w(TAG, "Ignoring invalid config value for " + key, error);
            }
        }
        save();
    }

    public synchronized boolean cacheEnabled() {
        return values.optBoolean("cacheEnabled", true);
    }

    public synchronized long cacheLimitBytes() {
        double limit = values.optDouble("cacheLimitBytes", DEFAULT_CACHE_LIMIT);
        if (!Double.isFinite(limit) || limit <= 0) return DEFAULT_CACHE_LIMIT;
        return Math.max(1024L * 1024L, (long) limit);
    }

    public synchronized String apiBaseUrl() {
        String value = values.optString("apiBaseUrl", "");
        return value == null ? "" : value.trim();
    }

    public synchronized String authorization() {
        String value = values.optString("authorization", "");
        return value == null ? "" : value;
    }

    public synchronized boolean authenticated() {
        return values.optBoolean("authenticated", false);
    }

    public synchronized String accountId() {
        String value = values.optString("accountId", "");
        return value == null ? "" : value.trim();
    }

    public synchronized String quality() {
        String value = values.optString("quality", "128");
        return value == null || value.trim().isEmpty() ? "128" : value.trim();
    }

    /** Config as seen by the plugin; never exposed to JS (it came from JS in the first place). */
    public synchronized JSONObject snapshot() {
        try {
            return new JSONObject(values.toString());
        } catch (Exception error) {
            return new JSONObject();
        }
    }

    private void load() {
        String stored = prefs.getString(PAYLOAD_KEY, null);
        if (stored == null || stored.isEmpty()) return;
        try {
            byte[] raw = Base64.decode(stored, Base64.NO_WRAP);
            int ivLength = 12;
            if (raw.length <= ivLength) throw new IllegalStateException("truncated config");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[ivLength];
            System.arraycopy(raw, 0, iv, 0, ivLength);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(raw, ivLength, raw.length - ivLength);
            JSONObject restored = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            Iterator<String> keys = restored.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                values.put(key, restored.opt(key));
            }
        } catch (Exception error) {
            Log.w(TAG, "Playback config could not be decrypted; starting unconfigured", error);
            prefs.edit().remove(PAYLOAD_KEY).apply();
        }
    }

    private void save() {
        if (!persistent) return;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] cipherText = cipher.doFinal(values.toString().getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(cipherText, 0, payload, iv.length, cipherText.length);
            prefs.edit().putString(PAYLOAD_KEY, Base64.encodeToString(payload, Base64.NO_WRAP)).apply();
        } catch (Exception error) {
            // Keep running with in-memory config instead of writing credentials in the clear.
            persistent = false;
            Log.w(TAG, "Keystore unavailable; playback config stays in memory for this session", error);
        }
    }

    private SecretKey key() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
