package com.tj.linkmeter;

import java.util.Random;

/**
 * No Android in here (tested in tools/test/BootNamesTest.java). The name and the password of the tablet's own hotspot, the one the phone
 * joins so that wireless debugging has a Wi-Fi network to run on.
 *
 * A Wi-Fi Direct group made by an app (Android 10 and later) may have a fixed name and password: the name must start with
 * "DIRECT-" and two more letters or digits, and the password is 8 to 63 ASCII characters. A fixed pair is what lets the phone save the
 * network once and join it by itself every time afterwards (a local-only hotspot gets a new random pair at every start).
 */
final class BootNames {
    private BootNames() {}

    /** Letters and digits that cannot be mixed up when read from a screen and typed on a phone (no 0 O 1 l I). */
    static final String ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int PASSWORD_LENGTH = 12;
    static final String DEFAULT_SSID = "DIRECT-LM-Tablet";

    /** Is this a name Android accepts for a Wi-Fi Direct group: "DIRECT-", two letters or digits, then anything printable, 32 bytes at most? */
    static boolean validSsid(String s) {
        if (s == null || s.length() < 9 || s.length() > 32 || !s.startsWith("DIRECT-")) {
            return false;
        }
        if (!isAlnum(s.charAt(7)) || !isAlnum(s.charAt(8))) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return false; // ASCII only, no spaces: it has to be typed on a phone
            }
        }
        return true;
    }

    /** Is this a password Android accepts for a WPA2 group: 8 to 63 printable ASCII characters? */
    static boolean validPassword(String p) {
        if (p == null || p.length() < 8 || p.length() > 63) {
            return false;
        }
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /** A fresh password from the readable alphabet. The caller keeps it (it is made once). */
    static String newPassword(Random r) {
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static boolean isAlnum(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
