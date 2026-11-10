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

    /** "address: 5C:F3:..." as Android's Bluetooth report prints the adapter's own address (the value may be a hidden one). */
    private static final Pattern OWN_LINE = Pattern.compile("(?i)address:\\s*(\\S+)");

    /** A real address: six pairs of hex digits, and not one of the fakes (all zero, all F, or Android's 02:00:00:00:00:00). */
    static boolean valid(String mac) {
        if (mac == null || !mac.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}")) {
            return false;
        }
        return !mac.equals("00:00:00:00:00:00") && !mac.equals("FF:FF:FF:FF:FF:FF") && !mac.equals("02:00:00:00:00:00");
    }

    /**
     * The phone's OWN Bluetooth address in what the shell printed, in capitals, or "" if the phone does not tell. Two places
     * count and no other: the answer of the setting (a line that is nothing but an address), and Android's Bluetooth report,
     * whose first "address:" line is the adapter's own. If that line hides the address (XX:XX:XX:XX:12:34), the search ends
     * there: a complete address further down belongs to ANOTHER device (a headset, a car), and the tablet would then call that
     * device instead of the phone.
     */
    static String own(String text) {
        if (text == null) {
            return "";
        }
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            String bare = line.toUpperCase(Locale.US);
            if (valid(bare)) {
                return bare; // the setting's answer
            }
            Matcher m = OWN_LINE.matcher(line);
            if (m.matches()) {
                String mac = m.group(1).toUpperCase(Locale.US);
                return valid(mac) ? mac : ""; // the adapter's own line: this or nothing
            }
        }
        return "";
    }
}
