package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/NrLegTest.java).
 *
 * The "5G leg" of 5G UW. On Verizon the phone is connected to a 4G cell and, beside it, to a 5G cell (non-standalone, EN-DC).
 * In weak 5G the phone has to share its small uplink power between the two, and the 5G half is the weak one. Android can tell
 * the modem not to add that second leg (TelephonyManager.setNrDualConnectivityState): 5G stays allowed, but the strong 4G cell
 * carries everything. ShellMain talks to the modem and prints one line; this class reads that line and puts the answer into
 * words, so the phone and the tablet agree on the words (the tablet reads them back with {@link #offFromText}).
 *
 * ShellMain's line: "OK nrdc state=on|off|unknown supported=yes|no|unknown result=WORD|n/a", or "ERR why".
 */
final class NrLeg {
    private NrLeg() {}

    /** What the phone said. enabled / supported: null = the phone did not say. result: the modem's word for a set command. */
    static final class State {
        final Boolean enabled;
        final Boolean supported;
        final String result;
        /** The text after ERR, or "" if the answer was not an error. */
        final String error;

        State(Boolean enabled, Boolean supported, String result, String error) {
            this.enabled = enabled;
            this.supported = supported;
            this.result = result == null ? "" : result;
            this.error = error == null ? "" : error;
        }
    }

    /** Reads ShellMain's output (which may have other lines around it). Never throws; an unreadable answer is an error State. */
    static State parse(String out) {
        if (out == null) {
            return new State(null, null, "", "no answer");
        }
        String err = "";
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("OK nrdc")) {
                return new State(tri(word(t, "state="), "on", "off"), tri(word(t, "supported="), "yes", "no"),
                        word(t, "result="), "");
            }
            if (err.isEmpty() && t.startsWith("ERR ")) {
                err = t.substring(4).trim();
            }
        }
        return new State(null, null, "", err.isEmpty() ? "the phone gave no readable answer" : err);
    }

    private static String word(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) {
            return "";
        }
        int s = i + key.length();
        int e = s;
        while (e < line.length() && !Character.isWhitespace(line.charAt(e))) {
            e++;
        }
        return line.substring(s, e);
    }

    private static Boolean tri(String w, String yes, String no) {
        return w.equals(yes) ? Boolean.TRUE : w.equals(no) ? Boolean.FALSE : null;
    }

    /** The phone's words for the tablet when the leg was only read. */
    static String describe(State s) {
        if (!s.error.isEmpty()) {
            return "5G leg: could not be read (" + s.error + ").";
        }
        return stateWords(s) + support(s);
    }

    private static String stateWords(State s) {
        if (s.enabled == null) {
            return "5G leg: the phone did not say whether it is on.";
        }
        return s.enabled ? "5G leg is on (the phone may add a 5G cell beside the 4G one)."
                : "5G leg is off (the phone does not add a 5G cell beside the 4G one; 5G stays allowed).";
    }

    private static String support(State s) {
        return Boolean.FALSE.equals(s.supported) ? " The modem says it cannot switch the 5G leg." : "";
    }

    /** The phone's words after a set command. asked: "on", "off" or "off-now". */
    static String describeSet(String asked, State s) {
        if (!s.error.isEmpty()) {
            return "5G leg: could not be switched " + asked + " (" + s.error + ").";
        }
        String r = s.result;
        if (r.equals("NOT_SUPPORTED")) {
            return "5G leg: this phone's modem says it cannot switch the 5G leg (not supported), so nothing was changed.";
        }
        if (r.equals("RADIO_NOT_AVAILABLE")) {
            return "5G leg: the radio is not available right now; try again in a moment.";
        }
        if (!r.equals("SUCCESS")) {
            return "5G leg: the modem answered " + (r.isEmpty() || r.equals("n/a") ? "nothing readable" : r)
                    + ", so it probably did not change." + (s.enabled == null ? "" : " " + stateWords(s));
        }
        boolean wantOff = asked.startsWith("off");
        if (s.enabled == null) {
            return "5G leg: the modem accepted the request (" + asked + ") but the phone did not say what it is now.";
        }
        if (s.enabled == wantOff) {
            // accepted, yet still reported the old way: "off" waits until the current connection ends
            return "5G leg: the modem accepted \"" + asked + "\" but the phone still reports the leg "
                    + (s.enabled ? "ON" : "OFF") + "."
                    + (wantOff && asked.equals("off") ? " \"Off\" waits until the current connection ends: give it a minute, or use \"off now\"."
                    : "");
        }
        return stateWords(s);
    }

    /**
     * Reads the phone's words back on the tablet: TRUE = the leg is off, FALSE = on, null = unknown. Only the plain statement counts;
     * the sentence about a leg that is "still reported ON" must not be taken for "is on".
     */
    static Boolean offFromText(String text) {
        if (text == null) {
            return null;
        }
        if (text.contains("5G leg is off")) {
            return Boolean.TRUE;
        }
        if (text.contains("5G leg is on")) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * True when the phone's words after a set command say the modem ACCEPTED the request (whether or not the phone already reports
     * the new state: plain "off" may take a while). A refusal, a modem that cannot, an error or a text from somewhere else is false.
     */
    static boolean accepted(String text) {
        return text != null && (text.startsWith("5G leg is") || text.startsWith("5G leg: the modem accepted"));
    }

    /** True when the phone's words say the modem cannot do this at all. */
    static boolean cannot(String text) {
        return text != null && (text.contains("cannot switch the 5G leg") || text.contains("has no setNrDualConnectivityState"));
    }
}
