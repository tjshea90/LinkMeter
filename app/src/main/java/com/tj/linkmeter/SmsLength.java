package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/TextsLogicTest.java). How many text messages a text will be: the phone's carrier counts a
 * text in the 7-bit GSM alphabet (160 characters, 153 in each part of a long one; some characters count twice) or, with any other
 * character (most emoji, accents outside the GSM set, other alphabets), in 16-bit characters (70, or 67 in each part). Shown while
 * writing so that nobody is surprised by three messages.
 */
final class SmsLength {
    private SmsLength() {}

    /** The basic GSM 03.38 alphabet (the escape position is not a character). */
    private static final String BASIC = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ"
            + "ÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§"
            + "¿abcdefghijklmnopqrstuvwxyzäöñüà";
    /** The extension table: each of these takes two places. */
    private static final String EXTENDED = "\f^{}\\[~]|€";

    /** What a text comes to. */
    static final class Count {
        /** Characters as the user sees them (a pair counts once). */
        final int chars;
        /** Places used (GSM) or 16-bit units used (otherwise). */
        final int units;
        final boolean unicode;
        /** Text messages it will be sent as. */
        final int parts;
        /** Places left in the last message. */
        final int left;

        Count(int chars, int units, boolean unicode, int parts, int left) {
            this.chars = chars;
            this.units = units;
            this.unicode = unicode;
            this.parts = parts;
            this.left = left;
        }
    }

    static Count of(String text) {
        if (text == null || text.isEmpty()) {
            return new Count(0, 0, false, 0, 160);
        }
        boolean gsm = true;
        int units = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (BASIC.indexOf(c) >= 0) {
                units++;
            } else if (EXTENDED.indexOf(c) >= 0) {
                units += 2;
            } else {
                gsm = false;
                break;
            }
        }
        int chars = text.codePointCount(0, text.length());
        if (gsm) {
            int parts = units <= 160 ? 1 : (units + 152) / 153;
            int cap = parts == 1 ? 160 : 153;
            return new Count(chars, units, false, parts, parts * cap - units);
        }
        int u = text.length();
        int parts = u <= 70 ? 1 : (u + 66) / 67;
        int cap = parts == 1 ? 70 : 67;
        return new Count(chars, u, true, parts, parts * cap - u);
    }
}
