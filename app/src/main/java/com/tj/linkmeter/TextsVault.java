package com.tj.linkmeter;

import android.app.KeyguardManager;
import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Tablet only: the Android side of keeping the texts private on this tablet. The key that seals the saved copy lives in the Android
 * Keystore (it is made there and cannot be read out, not even by this app; most tablets keep it in hardware), whether this tablet has
 * a screen lock that the Texts lock can ask for, and erasing everything that was saved.
 *
 * What this protects: the saved copy and the choices are unreadable to anything that copies the app's files (a backup, a file
 * extraction, another app with a root hole). What it does not protect: texts on a tablet that is unlocked and open at the Texts tab
 * (that is what the Texts lock is for), or the phone itself.
 */
final class TextsVault {
    static final String ALIAS = "linkmeter.texts.v1";
    private static final String PROVIDER = "AndroidKeyStore";
    /** Goes up whenever everything was erased, so a Texts tab that still holds the old key opens its files again. */
    private static final AtomicLong EPOCH = new AtomicLong();

    /** For the pictures made by tools/uipreview, which have no Android Keystore: the key to use instead (null: the real Keystore). */
    static SecretKey testKey;

    private TextsVault() {}

    static long epoch() {
        return EPOCH.get();
    }

    /** The key for the saved copy, made on first use. Null when this tablet's Keystore does not work: then nothing is saved. */
    static synchronized SecretKey key() {
        if (testKey != null) {
            return testKey;
        }
        try {
            KeyStore ks = KeyStore.getInstance(PROVIDER);
            ks.load(null);
            Key k = ks.getKey(ALIAS, null);
            if (k instanceof SecretKey) {
                return (SecretKey) k;
            }
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
            g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return g.generateKey();
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            return null;
        }
    }

    /** The folder with the saved copy and the tablet's choices. */
    static File dir(Context c) {
        return new File(c.getFilesDir(), "texts");
    }

    /** Does this tablet have a PIN, pattern, password or fingerprint lock that the Texts lock can ask for? */
    static boolean deviceSecure(Context c) {
        KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isDeviceSecure();
    }

    /** Pictures and files that were opened in another app are kept in the cache folder for a while; this removes the ones older than this. */
    static void sweepOpened(Context c, long maxAgeMs) {
        File[] all = new File(c.getCacheDir(), "texts-open").listFiles();
        if (all == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (File f : all) {
            if (f.isFile() && now - f.lastModified() > maxAgeMs) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /**
     * Erases everything saved from the phone's texts on this tablet: the saved copy, the choices (pins, archive, drafts, hidden
     * conversations), the pictures opened from texts, and the key. Used when the tablet pairs with a phone again, and by "Erase
     * everything saved". Never throws.
     */
    static synchronized void wipe(Context c) {
        deleteAll(dir(c));
        deleteAll(new File(c.getCacheDir(), "texts-open"));
        try {
            KeyStore ks = KeyStore.getInstance(PROVIDER);
            ks.load(null);
            if (ks.containsAlias(ALIAS)) {
                ks.deleteEntry(ALIAS);
            }
        } catch (GeneralSecurityException | IOException | RuntimeException ignored) {
            // the files are gone; a key left behind opens nothing
        }
        EPOCH.incrementAndGet();
    }

    private static void deleteAll(File dir) {
        File[] all = dir.listFiles();
        if (all != null) {
            for (File f : all) {
                if (f.isDirectory()) {
                    deleteAll(f);
                }
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }
}
