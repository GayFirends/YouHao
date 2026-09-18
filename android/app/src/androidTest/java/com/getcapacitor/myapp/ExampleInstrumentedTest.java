package com.youhao.fueltrack;

import static org.junit.Assert.*;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.InputStream;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class ExampleInstrumentedTest {

    @Test
    public void usesExpectedApplicationIdAndBundlesOfflineShell() throws Exception {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("com.youhao.fueltrack", appContext.getPackageName());
        try (InputStream index = appContext.getAssets().open("public/index.html");
             InputStream manifest = appContext.getAssets().open("public/manifest.webmanifest");
             InputStream worker = appContext.getAssets().open("public/sw.js")) {
            assertTrue(index.available() > 0);
            assertTrue(manifest.available() > 0);
            assertTrue(worker.available() > 0);
        }
    }

    @Test
    public void androidKeystoreSupportsPassphraseRoundTrip() throws Exception {
        String alias = "fuel-track-instrumentation-test";
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(alias)) store.deleteEntry(alias);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        SecretKey key = generator.generateKey();
        Cipher encrypt = Cipher.getInstance("AES/GCM/NoPadding");
        encrypt.init(Cipher.ENCRYPT_MODE, key);
        byte[] ciphertext = encrypt.doFinal("test-passphrase".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Cipher decrypt = Cipher.getInstance("AES/GCM/NoPadding");
        decrypt.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, encrypt.getIV()));
        assertEquals("test-passphrase", new String(decrypt.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8));
        store.deleteEntry(alias);
    }
}
