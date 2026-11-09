package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/SafetyReportTest.java). Turns the facts the phone collected into the plain report the
 * "Safety check" button shows: is mobile data working, and is anything that LinkMeter changed still in place? It must never say
 * "nothing is changed" while something is, and never invent a change.
 */
final class SafetyReport {
    private SafetyReport() {}

    /** What the phone found out. null / -1 / "" = it could not tell. */
    static final class Facts {
        /** The allowed network types as a bit mask, -1 if unknown, and the phone's words for them ("LTE|LTE_CA|NR"). */
        long types = -1;
        String typesText = "";
        /** LinkMeter changed them and has not yet put them back. */
        boolean typesChanged;
        /** True = the 5G leg is allowed (normal), false = switched off, null = unknown. */
        Boolean legOn;
        boolean legChanged;
        /** The band lock now ("" = none) and whether it was kept; lockFlag = LinkMeter's own note that a lock may be on. */
        String lock = "";
        boolean lockKept;
        boolean lockFlag;
        Boolean airplaneOn;
        Boolean dataEnabled;
        /** "working", "not working" or "" (cannot tell). */
        String cellular = "";
        boolean radioBlip;
        boolean airplaneTimer;
        boolean menuRecent;
        boolean hotspotBandChanged;
        boolean batterySaver;
        boolean guardWatching;
    }

    /** Things LinkMeter changed that are still in place, one line each. Empty when there are none. */
    static java.util.List<String> changes(Facts f) {
        java.util.List<String> l = new java.util.ArrayList<>();
        if (f.typesChanged) {
            l.add("the network types were changed" + (f.types >= 0 && (f.types & (1L << 19)) == 0 ? " (5G is off)" : ""));
        }
        if (f.legChanged) {
            l.add("the 5G leg was switched off");
        }
        if (f.lockFlag || !f.lock.isEmpty()) {
            l.add("a band lock may be on" + (f.lock.isEmpty() ? "" : " (" + f.lock + (f.lockKept ? ", kept" : "") + ")"));
        }
        if (f.radioBlip) {
            l.add("a radio restart or the hotspot-off blip started and has not been confirmed as finished");
        }
        if (f.airplaneTimer) {
            l.add("airplane mode was started from the tablet and ends by itself");
        }
        if (f.hotspotBandChanged) {
            l.add("the hotspot's Wi-Fi band was changed");
        }
        if (f.menuRecent) {
            l.add("a service menu was opened in the last two hours (what is changed inside it is not tracked by LinkMeter)");
        }
        return l;
    }

    /** Does the safety net have anything it could put back by itself? (The hotspot band and battery saver do not affect mobile data.) */
    static boolean radioChanged(Facts f) {
        return f.typesChanged || f.legChanged || f.lockFlag || !f.lock.isEmpty() || f.radioBlip || f.airplaneTimer || f.menuRecent;
    }

    static String report(Facts f) {
        StringBuilder sb = new StringBuilder("Safety check\n");
        sb.append("Mobile data: ");
        if (Boolean.FALSE.equals(f.dataEnabled)) {
            sb.append("switched off on the phone (by you)");
        } else if (Boolean.TRUE.equals(f.airplaneOn)) {
            sb.append("airplane mode is on");
        } else if (f.cellular.equals("working")) {
            sb.append("working");
        } else if (f.cellular.equals("not working")) {
            sb.append("NOT working right now");
        } else {
            sb.append("cannot tell");
        }
        sb.append('\n');
        sb.append("Network types: ").append(f.types < 0 ? "cannot read" : f.typesText).append('\n');
        sb.append("5G leg: ").append(f.legOn == null ? "cannot read" : f.legOn ? "on (normal)" : "off").append('\n');
        sb.append("Band lock: ").append(f.lock.isEmpty() ? "none" : f.lock + (f.lockKept ? " (kept)" : "")).append('\n');
        java.util.List<String> ch = changes(f);
        if (ch.isEmpty()) {
            sb.append("\nLinkMeter has nothing changed on this phone.");
        } else {
            sb.append("\nStill changed by LinkMeter:\n");
            for (String c : ch) {
                sb.append(" - ").append(c).append('\n');
            }
            if (radioChanged(f)) {
                sb.append(f.guardWatching
                        ? "The safety net is watching: if mobile data stays down for 2 to 4 minutes it puts the radio settings back by itself.\n"
                        : "The safety net is not running right now; it starts again with the phone service.\n");
            }
            sb.append("Restore everything puts it all back (this app, LinkMeter > Phone, or the Restore button in the notification).");
        }
        if (f.batterySaver) {
            sb.append("\nBattery saver profile: on (it restores its own settings; it never touches mobile data).");
        }
        return sb.toString().trim();
    }
}
