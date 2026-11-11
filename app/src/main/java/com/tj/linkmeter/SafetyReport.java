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
        /**
         * Left by a version up to v0.2.102 (this version never sets either): LinkMeter's note that the 5G leg was switched off, and
         * its note that a band lock may be on. Restore everything and the safety net still clear them.
         */
        boolean legChanged;
        boolean lockFlag;
        Boolean airplaneOn;
        Boolean dataEnabled;
        /** "working", "not working" or "" (cannot tell). */
        String cellular = "";
        boolean radioBlip;
        boolean airplaneTimer;
        boolean menuRecent;
        boolean hotspotBandChanged;
        /** The battery saver's settings are in place (Eco.isOn). */
        boolean batterySaver;
        boolean guardWatching;
        /** "Start the phone after a restart": LinkMeter holds WRITE_SECURE_SETTINGS (given once through Shizuku). */
        boolean secureGranted;
        /** LinkMeter switched Android's 7-day timeout for wireless-debugging pairings off. */
        boolean pairingKept;
        /** Boot assist is on (the phone switches wireless debugging on by itself after a restart). */
        boolean bootAssist;
        /**
         * The phone's OWN switches, which the tablet can also move (hotspot, Wi-Fi) or listen at (the Bluetooth link). They are
         * information, never "changes still in place": Restore everything does not touch them. null / "" = the phone does not say,
         * and that is never printed as "off".
         */
        Boolean hotspotOn;
        Boolean wifiOn;
        /** The Bluetooth link in the listener's own words ("on, listening for the paired tablet"); "" = cannot tell. */
        String btLink = "";
    }

    /** What is said above the three switches, after the verdict: they are not changes, and Restore everything leaves them as they are. */
    static final String SWITCHES_NOTE = "The phone's own switches (the tablet can move them; Restore everything does not change them):";

    private static String onOff(Boolean on) {
        return on == null ? "cannot tell" : on.booleanValue() ? "on" : "off";
    }

    /** Things LinkMeter changed that are still in place, one line each. Empty when there are none. */
    static java.util.List<String> changes(Facts f) {
        java.util.List<String> l = new java.util.ArrayList<>();
        if (f.typesChanged) {
            l.add("the network types were changed" + (f.types >= 0 && (f.types & (1L << 19)) == 0 ? " (5G is off)" : ""));
        }
        if (f.legChanged) {
            l.add("the 5G leg was switched off by an older version of LinkMeter");
        }
        if (f.lockFlag) {
            l.add("a band lock may be on, left by an older version of LinkMeter");
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
        if (f.secureGranted) {
            l.add("LinkMeter holds the permission to change secure settings (it only uses it to switch wireless debugging on after a restart)");
        }
        if (f.pairingKept) {
            l.add("Android's 7-day timeout for wireless-debugging pairings is switched off");
        }
        if (f.bootAssist) {
            l.add("Boot assist is on: after a restart the phone switches wireless debugging on by itself and shows a notification that opens Shizuku");
        }
        if (f.batterySaver) {
            l.add("the battery saver is on: it turned off background scanning and the always-on display and shortened the screen timeout, "
                    + "and puts them back by itself (it never touches mobile data)");
        }
        return l;
    }

    /** Is one of the "start the phone after a restart" items in place? They are not network settings, and Restore everything leaves them alone. */
    static boolean bootChanged(Facts f) {
        return f.secureGranted || f.pairingKept || f.bootAssist;
    }

    /** Is anything in place that is NOT one of the boot items (so that Restore everything has something to put back)? */
    static boolean otherChanged(Facts f) {
        return f.typesChanged || f.legChanged || f.lockFlag || f.radioBlip || f.airplaneTimer || f.hotspotBandChanged || f.menuRecent
                || f.batterySaver;
    }

    /** Does the safety net have anything it could put back by itself? (The hotspot band and battery saver do not affect mobile data.) */
    static boolean radioChanged(Facts f) {
        return f.typesChanged || f.legChanged || f.lockFlag || f.radioBlip || f.airplaneTimer || f.menuRecent;
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
            if (otherChanged(f)) {
                sb.append("Restore everything puts it back (the Phone tab on the tablet, LinkMeter open on the phone itself, or the Restore button in the notification).");
                if (f.batterySaver) {
                    // Restore everything stores Off for the saver too (Eco.setAuto): left on Automatic, the tablet's next request would switch it on again
                    sb.append(" It also switches the battery saver's Automatic choice off. Switch it on again in the Phone tab.");
                }
            }
            if (bootChanged(f)) {
                sb.append(otherChanged(f) ? "\n" : "").append("The boot items stay until you undo them: on the tablet, Phone tab > Undo the setup (Restore everything leaves them alone).");
            }
        }
        // the phone's own switches come last, as information: the answer to "is my phone still changed?" is above
        StringBuilder out = new StringBuilder(sb.toString().trim());
        out.append("\n\n").append(SWITCHES_NOTE).append('\n');
        out.append("Hotspot: ").append(onOff(f.hotspotOn)).append('\n');
        out.append("Wi-Fi: ").append(onOff(f.wifiOn)).append('\n');
        out.append("Bluetooth link: ").append(f.btLink.isEmpty() ? "cannot tell" : f.btLink);
        return out.toString();
    }
}
