package com.tj.linkmeter;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * No Android in here (tested in tools/test/LinkProtocolTest.java). Bluetooth addresses as text: finding the phone's own address in
 * what the shell printed, and telling a real address from the placeholders Android hands out instead of one.
 */
final class BtAddr {
    private BtAddr() {}

    private static final Pattern MAC = Pattern.compile("(?<![0-9A-Fa-f:])([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})(?![0-9A-Fa-f:])");

    /** A real address: six pairs of hex digits, and not one of the fakes (all zero, all F, or Android's 02:00:00:00:00:00). */
    static boolean valid(String mac) {
        if (mac == null || !mac.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}")) {
            return false;
        }
        return !mac.equals("00:00:00:00:00:00") && !mac.equals("FF:FF:FF:FF:FF:FF") && !mac.equals("02:00:00:00:00:00");
    }

    /** The first real address in a text, in capitals, or "" if there is none. Hidden ones (XX:XX:XX:XX:12:34) are not addresses. */
    static String find(String text) {
        if (text == null) {
            return "";
        }
        Matcher m = MAC.matcher(text);
        while (m.find()) {
            String mac = m.group(1).toUpperCase(Locale.US);
            if (valid(mac)) {
                return mac;
            }
        }
        return "";
    }
}
