package com.tj.linkmeter;

/**
 * Offline test of NrLeg (what is left of the 5G leg on the phone). Run by tools/test.sh. Prints "N checks, M failed".
 *
 * What matters most: (1) the stored note "an older version switched the 5G leg off" is forgotten whenever nothing can have been
 * switched off (audit D1: on this modem it stayed for ever), and kept whenever the leg may still be off; (2) the one sentence the
 * four old actions answer keeps a tablet that is not updated yet working (audit D3): the old comparison reads these words.
 */
public final class NrLegTest {
    static int checks;
    static int failed;

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            failed++;
            System.out.println("  FAIL: " + msg);
        }
    }

    static NrLeg.State st(Boolean enabled, Boolean supported, String result, String error) {
        return new NrLeg.State(enabled, supported, result, error);
    }

    // How a tablet up to v0.2.102 read the phone's words: copies of that version's NrLeg.offFromText, accepted and cannot, so the
    // contract of the fixed sentence stays pinned after the current helpers are gone.
    static Boolean oldOffFromText(String text) {
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

    static boolean oldAccepted(String text) {
        return text != null && (text.startsWith("5G leg is") || text.startsWith("5G leg: the modem accepted"));
    }

    static boolean oldCannot(String text) {
        return text != null && (text.contains("cannot switch the 5G leg") || text.contains("has no setNrDualConnectivityState"));
    }

    public static void main(String[] a) {
        // ---- reading ShellMain's line
        {
            NrLeg.State s = NrLeg.parse("OK nrdc state=off supported=yes result=SUCCESS");
            check(Boolean.FALSE.equals(s.enabled) && Boolean.TRUE.equals(s.supported) && s.result.equals("SUCCESS") && s.error.isEmpty(),
                    "a plain answer is read");
            s = NrLeg.parse("OK nrdc state=on supported=no result=n/a");
            check(Boolean.TRUE.equals(s.enabled) && Boolean.FALSE.equals(s.supported) && s.result.equals("n/a"), "on, not supported");
            s = NrLeg.parse("OK nrdc state=unknown supported=unknown result=NOT_SUPPORTED");
            check(s.enabled == null && s.supported == null && s.result.equals("NOT_SUPPORTED"), "unknown stays unknown, not false");
            s = NrLeg.parse("WARNING: linker: something\nOK nrdc state=off supported=yes result=SUCCESS\n[rc=0]\n");
            check(Boolean.FALSE.equals(s.enabled) && s.error.isEmpty(), "noise lines around the answer are ignored");
            s = NrLeg.parse("ERR java.lang.IllegalStateException: Telephony service is null");
            check(s.enabled == null && s.error.equals("java.lang.IllegalStateException: Telephony service is null"), "an error is kept");
            s = NrLeg.parse("something else entirely");
            check(s.enabled == null && !s.error.isEmpty(), "an unreadable answer is an error, not 'on'");
            s = NrLeg.parse("");
            check(s.enabled == null && !s.error.isEmpty(), "an empty answer is an error");
            s = NrLeg.parse(null);
            check(s.enabled == null && !s.error.isEmpty(), "null is an error");
            s = NrLeg.parse("OK nrdc state=off");
            check(Boolean.FALSE.equals(s.enabled) && s.supported == null && s.result.isEmpty(), "missing fields are unknown");
            s = NrLeg.parse("ERR first\nOK nrdc state=on supported=yes result=SUCCESS");
            check(Boolean.TRUE.equals(s.enabled), "an OK line wins over an earlier ERR line");
            s = NrLeg.parse("OK nrdc state=ON supported=YES result=SUCCESS");
            check(s.enabled == null, "capital letters are not guessed at (ShellMain only prints lower case)");
        }

        // ---- D3: the one sentence the four old actions answer (nrleg_get, nrleg_off, nrleg_off_now, nrleg_on)
        {
            String t = NrLeg.REMOVED;
            check(t.contains("cannot switch the 5G leg"), "it says the phone cannot switch the leg: an old tablet takes that as 'never possible'");
            check(!t.contains("5G leg is on") && !t.contains("5G leg is off"), "and gives no reading an old tablet could take for a state");
            check(oldCannot(t), "an old tablet recognizes it as 'cannot'");
            check(oldOffFromText(t) == null, "an old tablet reads no state from it");
            check(!oldAccepted(t), "an old tablet does not take it for an accepted request");
            check(!t.startsWith("5G leg is") && !t.startsWith("5G leg: the modem accepted"), "it does not start like an accepted answer");
            check(t.startsWith("5G leg:") && !t.contains("\n") && !t.contains("unknown action"), "one line of plain words, not an error");
        }

        // ---- D1: may the stored 'an older version switched the 5G leg off' flag be forgotten after 'leg on'?
        String noCall = "this phone has no setNrDualConnectivityState(int)";
        String[] answers = {"SUCCESS", "NOT_SUPPORTED", "RADIO_NOT_AVAILABLE", "ERROR_INVALID_ARG", "RADIO_ERROR", "7", "", "n/a"};
        String[] errs = {"", "boom", noCall, "java.lang.IllegalStateException: Telephony service is null"};
        Boolean[] tri = {Boolean.TRUE, Boolean.FALSE, null};
        int states = 0;
        for (String res : answers) {
            for (Boolean en : tri) {
                for (Boolean sup : tri) {
                    for (String err : errs) {
                        NrLeg.State s = st(en, sup, res, err);
                        boolean forget = NrLeg.mayForget(s);
                        String line = NrLeg.restoreLine(s);
                        String ctx = " [result=" + res + " enabled=" + en + " supported=" + sup + " error=" + err + "]";
                        states++;
                        if (!err.isEmpty() && !err.equals(noCall)) {
                            check(!forget, "an error that may mean the leg is still off keeps the flag" + ctx);
                        }
                        if (err.equals(noCall)) {
                            check(forget, "a phone with no such call never switched anything off: forget" + ctx);
                        }
                        if (err.isEmpty() && (res.equals("SUCCESS") || res.equals("NOT_SUPPORTED"))) {
                            check(forget, "the modem accepted, or cannot do it at all: forget" + ctx);
                        }
                        if (err.isEmpty() && Boolean.TRUE.equals(en)) {
                            check(forget, "the phone reports the leg on: forget" + ctx);
                        }
                        if (err.isEmpty() && !Boolean.TRUE.equals(en) && !res.equals("SUCCESS") && !res.equals("NOT_SUPPORTED")) {
                            check(!forget, "any other answer keeps the flag" + ctx);
                        }
                        // the line Restore everything prints never claims more than was checked
                        check(!line.isEmpty() && !line.contains("\n"), "one line of words" + ctx);
                        check(line.startsWith("5G leg:"), "the line says what it is about" + ctx);
                        check(line.startsWith("5G leg: switched back on.") == (err.isEmpty() && Boolean.TRUE.equals(en)),
                                "'switched back on' only when the phone reported the leg on" + ctx);
                        check(line.contains("could not be switched back on") == !forget, "'could not' exactly when the flag stays" + ctx);
                        if (!err.isEmpty() && !err.equals(noCall)) {
                            check(line.contains(err), "a kept flag shows the error" + ctx);
                        }
                        // this version never says what the leg IS from a word it did not read
                        check(!line.contains("5G leg is on") && !line.contains("5G leg is off"), "no reading in the line" + ctx);
                    }
                }
            }
        }
        check(states == 8 * 3 * 3 * 4, "all 288 states were tried");
        // the same, from the lines ShellMain really prints
        check(NrLeg.mayForget(NrLeg.parse("OK nrdc state=off supported=no result=NOT_SUPPORTED")),
                "the user's modem (NOT_SUPPORTED, reads 'off' because it has nothing to report): the flag is forgotten");
        check(NrLeg.restoreLine(NrLeg.parse("OK nrdc state=off supported=no result=NOT_SUPPORTED")).contains("nothing to put back"),
                "and Restore everything says nothing was changed");
        check(NrLeg.mayForget(NrLeg.parse("ERR " + noCall)), "a phone without the call: forgotten");
        check(NrLeg.mayForget(NrLeg.parse("OK nrdc state=on supported=yes result=SUCCESS")), "accepted and on: forgotten");
        check(NrLeg.mayForget(NrLeg.parse("OK nrdc state=off supported=yes result=SUCCESS")),
                "accepted although the read still says off (that read means nothing on some modems): forgotten");
        check(!NrLeg.mayForget(NrLeg.parse("OK nrdc state=off supported=yes result=RADIO_NOT_AVAILABLE")), "radio not available: the flag stays");
        check(NrLeg.restoreLine(NrLeg.parse("OK nrdc state=off supported=yes result=RADIO_NOT_AVAILABLE")).contains("radio is not available"),
                "and the line says why");
        check(!NrLeg.mayForget(NrLeg.parse("ERR java.lang.SecurityException: not allowed")), "a refusal keeps the flag");
        check(!NrLeg.mayForget(NrLeg.parse("")) && !NrLeg.mayForget(NrLeg.parse(null)) && !NrLeg.mayForget(NrLeg.parse("garbage")),
                "no readable answer keeps the flag");

        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
