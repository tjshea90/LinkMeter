package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/NrLegTest.java).
 *
 * The "5G leg" of 5G UW (the second, 5G cell beside the 4G one; TelephonyManager.setNrDualConnectivityState). Versions up to
 * v0.2.102 could ask the modem to keep it off. THIS phone's modem never obeyed ("NOT_SUPPORTED"), so the switch is gone and this
 * version never changes the leg. What is left here is small:
 *  - the clean-up of a note an older version left ("the leg was switched off"): reading ShellMain's answer to "nrdc-set on",
 *    the rule {@link #mayForget} and the words Restore everything prints ({@link #restoreLine});
 *  - the ONE sentence the four old actions (nrleg_get, nrleg_off, nrleg_off_now, nrleg_on) answer, {@link #REMOVED}, so a tablet
 *    that is not updated yet keeps working.
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

    /**
     * What the four old actions nrleg_get, nrleg_off, nrleg_off_now and nrleg_on answer now: ONE fixed sentence (audit D3). A tablet
     * up to v0.2.102 reads these words in its comparison, and an answer of "unknown action" would make it count the leg as
     * unreadable and lose its baseline in every run (measured: 40 of 40). It contains "cannot switch the 5G leg", which such a
     * tablet takes as "this phone can never do it" and goes on without the leg, and neither "5G leg is on" nor "5G leg is off",
     * which it would take as a reading. Pinned in NrLegTest.
     */
    static final String REMOVED = "5G leg: this version of LinkMeter on the phone cannot switch the 5G leg any more "
            + "(this phone's modem did not obey it), so nothing was changed.";

    /** ShellMain's words when this phone's telephony has no such call at all. */
    private static final String NO_CALL = "has no setNrDualConnectivityState";

    /**
     * May the stored flag "an older version switched the 5G leg off" be forgotten after the request "leg on" was answered with `s`?
     * Yes when the phone reports the leg on, when the modem accepted, and when the modem says it cannot do this at all or the phone
     * has no such call: then nothing was ever switched off, and (audit D1) the flag used to stay for ever on such a modem, keeping
     * the safety net awake and the Safety check worried. Any other answer (an error, the radio not available, no answer) may mean the
     * leg is still off, so the flag stays and the safety net tries again.
     */
    static boolean mayForget(State s) {
        if (!s.error.isEmpty()) {
            return s.error.contains(NO_CALL);
        }
        return Boolean.TRUE.equals(s.enabled) || s.result.equals("SUCCESS") || s.result.equals("NOT_SUPPORTED");
    }

    /** The line Restore everything prints after it asked for "leg on" because the stored flag said the leg had been switched off. */
    static String restoreLine(State s) {
        if (!mayForget(s)) {
            String why = !s.error.isEmpty() ? s.error
                    : s.result.equals("RADIO_NOT_AVAILABLE") ? "the radio is not available right now"
                    : "the modem answered " + (s.result.isEmpty() || s.result.equals("n/a") ? "nothing readable" : s.result);
            return "5G leg: could not be switched back on (" + why + ").";
        }
        if (s.error.isEmpty() && Boolean.TRUE.equals(s.enabled)) {
            return "5G leg: switched back on.";
        }
        if (s.error.isEmpty() && s.result.equals("SUCCESS")) {
            return "5G leg: the modem accepted the request to switch it back on.";
        }
        return "5G leg: this phone cannot switch it, so there was nothing to put back.";
    }
}
