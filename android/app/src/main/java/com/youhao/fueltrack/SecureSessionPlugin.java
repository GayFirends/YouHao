package com.youhao.fueltrack;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

@CapacitorPlugin(name = "SecureSession")
public class SecureSessionPlugin extends Plugin {
    private static final String KEY_ALIAS = "fuel-track-sync-passphrase";
    private static final String PREFS = "fuel-track-secure-session";
    private static final String VALUE_KEY = "encrypted-passphrase";

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return generator.generateKey();
    }

    private SharedPreferences preferences() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @PluginMethod
    public void set(PluginCall call) {
        String value = call.getString("value");
        if (value == null || value.isEmpty()) {
            call.reject("A non-empty value is required");
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            String stored = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP);
            preferences().edit().putString(VALUE_KEY, stored).apply();
            call.resolve();
        } catch (Exception error) {
            call.reject("Unable to protect the sync passphrase", error);
        }
    }

    @PluginMethod
    public void get(PluginCall call) {
        JSObject result = new JSObject();
        String stored = preferences().getString(VALUE_KEY, null);
        if (stored == null) {
            result.put("value", null);
            call.resolve(result);
            return;
        }
        try {
            String[] parts = stored.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            String value = new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
            result.put("value", value);
            call.resolve(result);
        } catch (Exception error) {
            preferences().edit().remove(VALUE_KEY).apply();
            try {
                KeyStore store = KeyStore.getInstance("AndroidKeyStore");
                store.load(null);
                if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS);
            } catch (Exception ignored) {
                // The encrypted value is already removed; a later save can retry key creation.
            }
            call.reject("Unable to restore the sync passphrase", error);
        }
    }

    @PluginMethod
    public void remove(PluginCall call) {
        preferences().edit().remove(VALUE_KEY).apply();
        call.resolve();
    }
}
