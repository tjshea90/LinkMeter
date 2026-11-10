package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/SwitchWordsTest.java). What the phone answers after it was asked to move one of its
 * switches from the tablet (hotspot on, off, restart; the screen; Standby), made from the one line its helper printed
 * (ShellMain: "OK ...", "ERR ...", or "UNSURE ..." when the phone was asked and does not report the hotspot's state).
 *
 * The rule this class exists for: the phone says "is on", "is off" or "was restarted" ONLY for a line that starts with OK.
 * Anything else that is not UNSURE means the switch did not move, and that is a failure: asked over a link that is still there,
 * the tablet gets it as an error (Actions.NotDone), never as "done" with a sad sentence. UNSURE is said in exactly those words:
 * asked, and not known.
 */
final class SwitchWords {
    private SwitchWords() {}

    private static final String UNSURE = "UNSURE";

    /** One answer. done false = the switch did not move: the answer is an error. text is always one or more whole sentences. */
    static final class Said {
        final boolean done;
        final String text;

        Said(boolean done, String text) {
            this.done = done;
            this.text = text;
        }
    }

    /**
     * The helper's verdict in what the shell printed: the last line that starts with OK, ERR or UNSURE (other lines can be
     * warnings of the phone's own tools), or the last line of all if there is none.
     */
    static String resultLine(String out) {
        String last = "";
        String[] lines = out.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i].trim();
            if (ok(l) || word(l, "ERR") || word(l, UNSURE)) {
                return l;
            }
            if (last.isEmpty()) {
                last = l;
            }
        }
        return last;
    }

    /** Does the line start with this verdict word (the word alone, or followed by a space)? "OKAY" is not "OK". */
    private static boolean word(String line, String verdict) {
        return line.equals(verdict) || line.startsWith(verdict + " ");
    }

    /** The helper says the phone did it. */
    static boolean ok(String line) {
        return word(line.trim(), "OK");
    }

    /** "OK the hotspot is on" -> "the hotspot is on"; "ERR no answer" -> "no answer". */
    static String afterWord(String line) {
        String t = line.trim();
        if (word(t, "OK") || word(t, "ERR") || word(t, UNSURE)) {
            int space = t.indexOf(' ');
            t = space < 0 ? "" : t.substring(space + 1).trim();
        }
        return t.isEmpty() ? "the phone gave no reason" : t;
    }

    /** Ends a sentence that was put together from the phone's own words, so two of them can stand next to each other. */
    static String dot(String sentence) {
        String t = sentence.trim();
        return t.endsWith(".") || t.endsWith("!") || t.endsWith("?") ? t : t + ".";
    }

    static Said hotspotOn(String line) {
        if (ok(line)) {
            return new Said(true, "The hotspot is on.");
        }
        if (word(line.trim(), UNSURE)) {
            return new Said(true, "The phone was asked to switch the hotspot on. It does not say whether it is on.");
        }
        return new Said(false, dot("The hotspot could not be switched on: " + afterWord(line)));
    }

    /**
     * The direct way of switching the hotspot off. When it failed, the old way (airplane mode for three seconds) is the next
     * step, but never over Bluetooth, because it would cut that link too: there the failure is the answer.
     */
    static Said hotspotOff(String line, boolean viaBluetooth) {
        if (ok(line)) {
            return new Said(true, "The hotspot is off.");
        }
        if (word(line.trim(), UNSURE)) {
            return new Said(true, "The phone was asked to switch the hotspot off. It does not say whether it is off.");
        }
        return new Said(false, viaBluetooth
                ? "The hotspot could not be switched off the direct way (" + afterWord(line) + "). The other way, airplane mode for "
                + "three seconds, is not used over Bluetooth because it would cut this link too."
                : dot("The hotspot could not be switched off the direct way: " + afterWord(line)));
    }

    /**
     * After the old way (airplane mode on for three seconds). direct: the line of the direct way that failed first.
     * off: TRUE the hotspot is off now, FALSE it is still on, null the phone does not say.
     */
    static Said hotspotOffByAirplane(String direct, Boolean off) {
        String why = "The direct way failed (" + afterWord(direct) + "), so airplane mode was switched on for three seconds.";
        if (off == null) {
            return new Said(true, why + " The phone does not say whether the hotspot is off.");
        }
        return off ? new Said(true, "The hotspot is off. " + why) : new Said(false, "The hotspot could not be switched off. " + why);
    }

    static Said hotspotRestart(String line) {
        if (ok(line)) {
            return new Said(true, "The hotspot was restarted.");
        }
        if (word(line.trim(), UNSURE)) {
            return new Said(true, "The phone was asked to restart the hotspot. It does not say whether the hotspot is on.");
        }
        return new Said(false, dot("The hotspot could not be restarted: " + afterWord(line)));
    }

    /** The checked Sleep. reached: the screen is off now; how: what was tried, in a few words. */
    static Said screenOff(boolean reached, String how) {
        return reached ? new Said(true, "The screen is off.") : new Said(false, "The screen did not stay off (" + how + ").");
    }

    /**
     * Standby is two switches: the hotspot off and the screen off. Both are always tried (a phone whose hotspot will not go off
     * still saves battery with its screen off, and the other way round). Only when BOTH moved is Standby done; if either did
     * not, the answer is an error that says what became of each, because a phone that is half on standby is not on standby.
     */
    static Said standby(Said hotspot, Said screen, boolean viaBluetooth) {
        if (hotspot.done && screen.done) {
            return new Said(true, hotspot.text + " " + screen.text + (viaBluetooth ? " To wake the phone: Hotspot on." : ""));
        }
        String lead = hotspot.done || screen.done ? "Standby is only half done. " : "Standby did not work. ";
        return new Said(false, lead + hotspot.text + " " + screen.text);
    }
}
