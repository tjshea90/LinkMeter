package com.tj.linkmeter;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * No Android in here (tested in tools/test/TextsLogicTest.java). AES-GCM: the output is the 96-bit IV, then the ciphertext, then the
 * 128-bit tag. The IV is chosen by the cipher provider for every sealing (the Android Keystore insists on that: it makes a fresh
 * random one). The key can be an ordinary key or one that stays inside the Keystore: it is only ever used through {@link Cipher}.
 */
final class AesGcmSealer implements Sealer {
    private static final int IV = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;

    AesGcmSealer(SecretKey key) {
        this.key = key;
    }

    @Override
    public byte[] seal(byte[] plain, byte[] aad) throws IOException {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key);
            c.updateAAD(aad);
            byte[] ct = c.doFinal(plain);
            byte[] iv = c.getIV();
            if (iv == null || iv.length != IV) {
                throw new IOException("cannot seal: the cipher gave an unexpected IV");
            }
            byte[] out = new byte[IV + ct.length];
            System.arraycopy(iv, 0, out, 0, IV);
            System.arraycopy(ct, 0, out, IV, ct.length);
            return out;
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IOException("cannot seal: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] open(byte[] sealed, byte[] aad) throws IOException {
        if (sealed.length < IV + TAG_BITS / 8) {
            throw new IOException("too short to be sealed");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(sealed, 0, IV)));
            c.updateAAD(aad);
            return c.doFinal(sealed, IV, sealed.length - IV);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IOException("damaged, or sealed with another key", e);
        }
    }
}
