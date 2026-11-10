package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/SignalRulesTest.java). What the tablet's Signal tab says and when it asks, decided from
 * what the phone said. A word the phone did not give is "not known", never a guess.
 */
final class SignalRules {
    private SignalRules() {}

    // ------------------------------------------------------------ the 4G or 5G switch

    enum Mode { FIVE_G_ALLOWED, FOUR_G_ONLY, UNKNOWN }

    /**
     * Which mode the phone's own words say, from its answer to net_get ("... (5G is allowed)" / "... (5G is off)"), net_lte ("Forced to
     * 4G ..." / "5G is already off ...") or net_5g ("5G is allowed again ..." / "5G is already allowed ..."). UNKNOWN for anything else,
     * and for words that say both.
     */
    static Mode modeOf(String text) {
        if (text == null) {
            return Mode.UNKNOWN;
        }
        boolean off = text.contains("5G is off") || text.contains("Forced to 4G") || text.contains("5G is already off");
        boolean on = text.contains("5G is allowed") || text.contains("allowed again") || text.contains("already allowed");
        return off == on ? Mode.UNKNOWN : off ? Mode.FOUR_G_ONLY : Mode.FIVE_G_ALLOWED;
    }

    // ------------------------------------------------------------ the chip over "Now using"

    enum Chip { NOT_SCANNED, NO_ANSWER, FIVE_G_UW, FIVE_G, FOUR_G, UNKNOWN }

    /**
     * What the phone is on, from its last scan (null = no scan yet). The phone's own label decides first; without one a listed 5G
     * carrier counts only when the phone's label and state do not contradict it (an out-of-date list is not 5G, see
     * {@link RadioState#nrCarrierInUse}).
     */
    static Chip chipOf(RadioState s) {
        if (s == null) {
            return Chip.NOT_SCANNED;
        }
        if ("NR_ADVANCED".equals(s.display)) {
            return Chip.FIVE_G_UW;
        }
        if ("NR_NSA".equals(s.display) || s.nrCarrierInUse(false)) {
            return Chip.FIVE_G;
        }
        for (RadioState.Carrier c : s.carriers) {
            if (!c.nr()) {
                return Chip.FOUR_G;
            }
        }
        return "LTE_CA".equals(s.display) || "LTE_ADVANCED_PRO".equals(s.display) ? Chip.FOUR_G : Chip.UNKNOWN;
    }

    static String chipText(Chip c) {
        switch (c) {
            case NOT_SCANNED:
                return "--";
            case NO_ANSWER:
                return "No answer";
            case FIVE_G_UW:
                return "5G UW";
            case FIVE_G:
                return "5G";
            case FOUR_G:
                return "4G";
            default:
                return "Not known";
        }
    }

    /** The "Network" line: the phone's label in words that mean something to the user. */
    static String networkWords(RadioState s) {
        if (s == null) {
            return "not scanned yet";
        }
        switch (chipOf(s)) {
            case FIVE_G_UW:
                return "5G UW (5G Ultra Wideband)";
            case FIVE_G:
                return "5G";
            case FOUR_G:
                String base = "LTE_ADVANCED_PRO".equals(s.display) ? "4G (LTE Advanced Pro, shown as 5G E)"
                        : "LTE_CA".equals(s.display) || s.ca ? "4G (LTE, carriers combined)" : "4G (LTE)";
                return s.staleNr(false) ? base + "; the phone's list still names old 5G cells" : base;
            default:
                return "not known";
        }
    }

    /** The signal of the registered cell: "-104 dBm", or "not known" when the phone did not say (Integer.MIN_VALUE). */
    static String dbm(int rsrp) {
        return rsrp == Integer.MIN_VALUE ? "not known" : rsrp + " dBm";
    }

    // ------------------------------------------------------------ when the tab asks by itself

    /** A scan younger than this is not repeated just because the tab was shown again. */
    static final long RESCAN_MS = 60_000L;
    /** One scan this long after a mode switch or the end of a comparison: the phone needs a few seconds to move. */
    static final long AFTER_CHANGE_MS = 8_000L;

    /**
     * Scan when the tab is shown? Not while a comparison runs (it is measuring, and its own checks look at the phone), and not
     * when the last scan is under a minute old. lastScanAt is on the same clock as now; a value below 0 means no scan yet.
     */
    static boolean scanOnShow(long now, long lastScanAt, boolean comparing) {
        return !comparing && (lastScanAt < 0 || now - lastScanAt >= RESCAN_MS || now < lastScanAt);
    }

    // ------------------------------------------------------------ the comparison

    /** Why a comparison cannot start now, or "" when it can. It measures the hotspot's speed, so Bluetooth is no way to run it. */
    static String comparisonBlocked(boolean reachable, boolean overBluetooth) {
        if (!reachable) {
            return "The phone cannot be reached, so there is nothing to measure.";
        }
        return overBluetooth ? "The comparison measures the speed of the hotspot, and the phone is reached over Bluetooth right now. "
                + "Switch the phone's hotspot on and join it with the tablet first." : "";
    }

    /** The button after a run that recommends a setting. */
    static String keepLabel(String settingLabel) {
        return "Keep: " + settingLabel;
    }

    /** What is said after Keep: how long the setting stays. The safety net puts 5G back by itself when mobile data is down for some minutes. */
    static String keepNote(boolean fourGOnly) {
        return fourGOnly
                ? "It stays 4G only until you switch it back here or press Restore everything. If mobile data is down for some minutes "
                + "the phone puts 5G back by itself."
                : "5G is allowed again, as it was when the comparison started.";
    }

    // ------------------------------------------------------------ searching for a network again

    /** The answer after a radio restart, with what the phone was on before and after (the "Now using" words of both scans). */
    static String searchWords(String before, String after) {
        boolean known = before != null && !before.isEmpty() && after != null && !after.isEmpty();
        String b = before == null || before.isEmpty() ? "not known" : before;
        String a = after == null || after.isEmpty() ? "not known" : after;
        return "The phone searched for a network again.\nBefore: " + b + "\nNow: " + a
                + (known && b.equals(a) ? "\nIt chose the same cells." : "");
    }
}
