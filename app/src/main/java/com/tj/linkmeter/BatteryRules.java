package com.tj.linkmeter;

/**
 * What the headset's battery icon and words say, decided from the number the phone sent (tested offline in tools/test/FloatingLogicTest.java;
 * docs/FLOATING_PHONE.md). Pure: no Android, no colours (the drawing maps a {@link Band} to a colour). Nothing is claimed that the phone
 * did not say: a headset that does not report its battery shows a dash and says so, an old phone app says to update it.
 */
final class BatteryRules {
    private BatteryRules() {}

    /** How full, in words the drawing turns into a colour. */
    enum Band { UNKNOWN, CRITICAL, LOW, OK, GOOD }

    /** At or below this the headset is about to stop. */
    static final int CRITICAL_AT = 10;
    /** At or below this: charge it soon. */
    static final int LOW_AT = 25;
    /** At or below this the icon is not yet "good". */
    static final int OK_AT = 60;
    /** The number of segments the icon draws (each 20 %). */
    static final int SEGMENTS = 5;

    /** True for a real percentage (0 to 100). */
    static boolean known(int level) {
        return level >= 0 && level <= 100;
    }

    static Band band(int level) {
        if (!known(level)) {
            return Band.UNKNOWN;
        }
        if (level <= CRITICAL_AT) {
            return Band.CRITICAL;
        }
        if (level <= LOW_AT) {
            return Band.LOW;
        }
        if (level <= OK_AT) {
            return Band.OK;
        }
        return Band.GOOD;
    }

    /** The level to show when the phone and the tablet may both know it: the phone's when it is a real number, else the tablet's, else what the phone said (old phone / not reported). */
    static int best(int phone, int tablet) {
        return known(phone) ? phone : known(tablet) ? tablet : phone;
    }

    /** The fill of the icon, 0 to 1; 0 for "not known" (the icon shows an outline and a dash). */
    static float fraction(int level) {
        return known(level) ? level / 100f : 0f;
    }

    /** How many of the {@link #SEGMENTS} lamps are lit: 0 for nothing left or not known, otherwise at least 1 and 5 only for a full battery. */
    static int lit(int level) {
        if (!known(level) || level <= 0) {
            return 0;
        }
        return Math.max(1, Math.min(SEGMENTS, (level + 19) / 20));
    }

    /** The text beside the icon: "78%", or a dash. */
    static String percent(int level) {
        return known(level) ? level + "%" : "—";
    }

    /** One sentence for a screen reader and the lines of the Headset screen. {@code name} may be empty. */
    static String words(int level, String name) {
        String who = name == null || name.trim().isEmpty() ? "Headset" : name.trim();
        if (known(level)) {
            return who + " battery " + level + " percent" + (level <= CRITICAL_AT ? ": charge it now" : level <= LOW_AT ? ": charge it soon" : "");
        }
        if (level == Calls.BATTERY_OLD_PHONE) {
            return "Update LinkMeter on the phone to see the headset's battery.";
        }
        return who + " does not tell the phone its battery level. Some headsets only do it after a call or music starts.";
    }

    /** The short caption under the icon in the floating phone: empty when there is nothing to warn about. */
    static String caption(int level) {
        if (!known(level)) {
            return "";
        }
        if (level <= CRITICAL_AT) {
            return "Charge now";
        }
        if (level <= LOW_AT) {
            return "Low";
        }
        return "";
    }
}
