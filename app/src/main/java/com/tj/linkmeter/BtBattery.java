package com.tj.linkmeter;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Bluetooth headset's battery level, as far as it can be read (docs/FLOATING_PHONE.md). No Android in it (tests: tools/test/FloatingLogicTest.java).
 * Android keeps the level a headset reports (the hands-free profile's battery indicator, or the standard battery service) and lets the
 * Bluetooth settings read it; an ordinary app has three ways to the same number and {@link HeadsetLink} tries them in this order: the hidden
 * {@code BluetoothDevice.getBatteryLevel()} in its own process, the system's BATTERY_LEVEL_CHANGED broadcast, and the same hidden call as the
 * shell user ({@code ShellMain bt-battery}), whose answer here is a number or, when the call is missing on this Android, a look through
 * {@code dumpsys bluetooth_manager} for the device's battery line ({@link #fromDump}). Nothing here guesses: a number that is not 0 to 100
 * is "not known".
 */
final class BtBattery {
    private BtBattery() {}

    private static final Pattern MAC = Pattern.compile("([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})|((?:[0-9A-Fa-fXx]{2}:){4}[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2})");
    // "battery level: 80", "mBatteryLevel=80", "Battery: 80%", "battery level (percent) 80"; never "battery saver", "battery low"
    private static final Pattern LEVEL = Pattern.compile("(?i)batt(?:ery)?[ _]?(?:level|percent(?:age)?)?[^0-9a-zA-Z\\n]{0,14}(\\d{1,3})\\s*%?");

    /** A level Android gave: 0 to 100 is real; anything else (-1 unknown, -100 Bluetooth off, 101+) is "not known" ({@link Calls#BATTERY_UNKNOWN}). */
    static int clean(int level) {
        return level >= 0 && level <= 100 ? level : Calls.BATTERY_UNKNOWN;
    }

    /** The words after "OK " in what {@code ShellMain bt-battery} printed, or "not known": "OK 78" is 78; "OK -1", "ERR ..." and rubbish are not known. */
    static int fromShell(String said) {
        if (said == null) {
            return Calls.BATTERY_UNKNOWN;
        }
        String t = said.trim();
        int nl = t.lastIndexOf('\n');
        if (nl >= 0) {
            t = t.substring(nl + 1).trim(); // the shell may say something first; the answer is the last line
        }
        if (!t.startsWith("OK ")) {
            return Calls.BATTERY_UNKNOWN;
        }
        try {
            return clean(Integer.parseInt(t.substring(3).trim()));
        } catch (NumberFormatException e) {
            return Calls.BATTERY_UNKNOWN;
        }
    }

    private static boolean sameDevice(String seen, String addr) {
        String a = addr.toUpperCase(Locale.US);
        String s = seen.toUpperCase(Locale.US);
        if (s.equals(a)) {
            return true;
        }
        // Android may hide the first four bytes of an address in a dump ("xx:xx:xx:xx:ee:ff"): the last two are then all there is
        return s.startsWith("XX:XX:XX:XX:") && a.endsWith(s.substring(11));
    }

    /**
     * The battery level the Bluetooth dump gives for the device {@code addr}: the number on the first battery line after the device's address
     * and before another device's address. {@link Calls#BATTERY_UNKNOWN} when no such line exists. A dump that is huge is looked at only to a
     * bounded size by the caller.
     */
    static int fromDump(String dump, String addr) {
        if (dump == null || addr == null || addr.isEmpty()) {
            return Calls.BATTERY_UNKNOWN;
        }
        boolean mine = false;
        for (String line : dump.split("\n")) {
            Matcher m = MAC.matcher(line);
            while (m.find()) {
                mine = sameDevice(m.group(), addr); // the last address on a line is the device the lines after it are about
            }
            if (!mine) {
                continue;
            }
            Matcher l = LEVEL.matcher(line);
            if (l.find()) {
                int v;
                try {
                    v = Integer.parseInt(l.group(1));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (v >= 0 && v <= 100) {
                    return v;
                }
            }
        }
        return Calls.BATTERY_UNKNOWN;
    }
}
