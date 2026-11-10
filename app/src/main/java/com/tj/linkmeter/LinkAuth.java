package com.tj.linkmeter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * No Android in here (tested in tools/test/LinkProtocolTest.java). How a request proves, on the Bluetooth link, that it comes from
 * the paired tablet.
 *
 * Over the hotspot the tablet sends its secret with every request: only devices on the user's own password-protected Wi-Fi can see
 * it. A Bluetooth serial connection can be opened by ANY device in range, and a device that pretends to be the phone would be
 * handed the secret. So on Bluetooth the secret never travels. The phone starts every connection with a random number; the tablet
 * answers each request with a signature made from its secret, that number, the request's own number and the request itself. The
 * phone makes the same signature with each secret it trusts and compares. A listener learns nothing it could use again: a new
 * connection has a new random number, and inside one connection every request number is used once.
 */
final class LinkAuth {
    /** The first word of the phone's greeting. A different protocol would say something else here. */
    static final String MAGIC = "LM1";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private LinkAuth() {}

    /** A new random number for one connection, as 32 hex digits. */
    static String newNonce() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return hex(b);
    }

    /** The line the phone sends first on every connection. */
    static String greeting(String nonce) {
        return MAGIC + " " + nonce + "\n";
    }

    /** The random number in a greeting line, or null if the line is not a greeting of this protocol. */
    static String parseGreeting(String line) {
        if (line == null) {
            return null;
        }
        String t = line.trim();
        if (!t.startsWith(MAGIC + " ")) {
            return null;
        }
        String n = t.substring(MAGIC.length() + 1);
        return n.matches("[0-9a-f]{32}") ? n : null;
    }

    /** The signature of one request: 64 hex digits. The secret is the key and never part of what is sent. */
    static String sign(String secret, String nonce, long seq, String method, String target) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String msg = MAGIC + "\n" + nonce + "\n" + seq + "\n" + method + "\n" + target;
            return hex(mac.doFinal(msg.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("this device cannot make the signature: " + e);
        }
    }

    /**
     * Which of the trusted secrets made this signature? Returns that secret, or null if none did. Every secret is tried and
     * compared in constant time, so the time taken says nothing about how close a guess was.
     */
    static String match(Iterable<String> trusted, String nonce, long seq, String method, String target, String sig) {
        if (sig == null || sig.length() != 64 || seq < 0) {
            return null;
        }
        byte[] got = sig.getBytes(StandardCharsets.UTF_8);
        String found = null;
        for (String secret : trusted) {
            if (secret == null || secret.length() != 32) {
                continue;
            }
            byte[] want = sign(secret, nonce, seq, method, target).getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(want, got) && found == null) {
                found = secret;
            }
        }
        return found;
    }

    private static String hex(byte[] b) {
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[2 * i] = HEX[(b[i] >> 4) & 15];
            out[2 * i + 1] = HEX[b[i] & 15];
        }
        return new String(out);
    }
}
