package com.tj.linkmeter;

import java.text.Normalizer;

/**
 * No Android in here (tested in tools/test/TypingFilterTest.java). The rule for everything the remote screen types on the phone:
 * it NEVER types the characters * and #.
 *
 * Why: every dialer "service code" that can do something to the phone starts or ends with them (for example the Samsung codes that
 * reset or erase it, and the carrier codes that change a SIM PIN), and the dialer runs such a code the moment the last character
 * arrives. A phone number, a message, a web address or a password hardly ever needs them, and they can still be typed on the phone
 * itself. The one place that turns typed characters into key presses on the phone ({@code ShellMain.typeText}) checks every
 * character with {@link #typable}, so no route into it can get around the rule; the phone's control channel and the tablet's keyboard
 * apply it earlier so that nothing is sent that would be dropped, and the tablet says why a character was left out.
 * Characters that merely LOOK like them (full-width, small, heavy, Arabic or mathematical stars and hash signs) are refused too,
 * so that no keyboard layout can turn one into the real thing.
 */
final class TypingFilter {
    private TypingFilter() {}

    /** Look-alikes that Unicode does not fold into * or # but that a keyboard map could conceivably treat as them. */
    private static final String LOOKALIKES = "\u2217\u204E\u2731\u2732\u2733\u2042\u066D\uFF0A\uFE61\uFF03\uFE5F\u266F\u2317";

    /** May this character be typed on the phone? Everything except * and # and their look-alikes. */
    static boolean typable(char c) {
        if (c == '*' || c == '#') {
            return false;
        }
        if (c < 0x80) {
            return true;
        }
        if (LOOKALIKES.indexOf(c) >= 0) {
            return false;
        }
        String n = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFKC);
        return n.indexOf('*') < 0 && n.indexOf('#') < 0;
    }

    /**
     * May this key code be part of a typed character? The star and pound keys and the numeric keypad's multiply key are the other
     * ways a keyboard map could produce * or #.
     */
    static boolean typableKey(int keyCode) {
        return keyCode != 17 && keyCode != 18 && keyCode != 155; // KEYCODE_STAR, KEYCODE_POUND, KEYCODE_NUMPAD_MULTIPLY
    }

    /** The text without the characters that may not be typed. */
    static String keep(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            if (typable(s.charAt(i))) {
                sb.append(s.charAt(i));
            }
        }
        return sb.toString();
    }

    /** Does the text hold a * or # (or a look-alike), which the tablet should tell the user about? */
    static boolean hasBlockedSymbol(String s) {
        return s != null && keep(s).length() != s.length();
    }

    /**
     * A control line "t " followed by 4 hex digits per character, as the phone receives it from the tablet: the same line without the
     * characters that may not be typed, or null if nothing is left or the line is not well formed.
     */
    static String filterLine(String line) {
        if (line == null || !line.startsWith("t ")) {
            return null;
        }
        String hex = line.substring(2);
        if (hex.isEmpty() || hex.length() % 4 != 0) {
            return null;
        }
        StringBuilder out = new StringBuilder("t ");
        for (int i = 0; i < hex.length(); i += 4) {
            int cp;
            try {
                cp = Integer.parseInt(hex.substring(i, i + 4), 16);
            } catch (NumberFormatException e) {
                return null;
            }
            if (typable((char) cp)) {
                out.append(hex, i, i + 4);
            }
        }
        return out.length() == 2 ? null : out.toString();
    }
}
