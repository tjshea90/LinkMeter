package com.tj.linkmeter;

/**
 * Offline test of NrLeg (the words the phone and the tablet share about the 5G leg). Run by tools/test.sh.
 * Prints "N checks, M failed".
 *
 * What matters most: the comparison run decides what it is measuring from these words, so the text must NEVER say "5G leg is
 * off" unless the phone really reported the leg off, and never "5G leg is on" unless it reported it on, whatever the modem
 * answered (rejected, not supported, accepted but unchanged, an error, garbage).
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

        // ---- the words, for every combination
        String[] asks = {"on", "off", "off-now"};
        String[] results = {"SUCCESS", "NOT_SUPPORTED", "RADIO_NOT_AVAILABLE", "ERROR_INVALID_ARG", "RADIO_ERROR", "7", "", "n/a"};
        Boolean[] tri = {Boolean.TRUE, Boolean.FALSE, null};
        String[] errors = {"", "boom"};
        int combos = 0;
        for (String ask : asks) {
            for (String res : results) {
                for (Boolean en : tri) {
                    for (Boolean sup : tri) {
                        for (String err : errors) {
                            NrLeg.State s = st(en, sup, res, err);
                            String t = NrLeg.describeSet(ask, s);
                            String ctx = " [ask=" + ask + " result=" + res + " enabled=" + en + " supported=" + sup + " error=" + err + "]";
                            combos++;
                            check(t != null && !t.isEmpty(), "a set answer always has words" + ctx);
                            Boolean off = NrLeg.offFromText(t);
                            // I1/I2: the words never claim a state the phone did not report
                            if (Boolean.TRUE.equals(off)) {
                                check(Boolean.FALSE.equals(en) && err.isEmpty(), "never says 'off' unless the phone reported off" + ctx);
                            }
                            if (Boolean.FALSE.equals(off)) {
                                check(Boolean.TRUE.equals(en) && err.isEmpty(), "never says 'on' unless the phone reported on" + ctx);
                            }
                            // an error is never read as a state
                            if (!err.isEmpty()) {
                                check(off == null, "an error says nothing about the state" + ctx);
                                check(t.contains(err), "the error text is shown" + ctx);
                            }
                            // I3: accepted, and the phone reports what was asked -> the words say that state
                            if (err.isEmpty() && res.equals("SUCCESS") && en != null) {
                                boolean wantOff = ask.startsWith("off");
                                if (en == !wantOff) {
                                    check(Boolean.valueOf(wantOff).equals(off), "success + reported as asked -> the words say so" + ctx);
                                } else {
                                    check(off == null, "accepted but reported unchanged is never taken for the new state" + ctx);
                                    check(t.contains("still reports"), "and says it is still reported the old way" + ctx);
                                }
                            }
                            // I5: a modem that cannot do it says so in words the tablet recognizes
                            if (err.isEmpty() && res.equals("NOT_SUPPORTED")) {
                                check(NrLeg.cannot(t), "not supported is recognized" + ctx);
                                check(off == null, "not supported says nothing about the state" + ctx);
                            }
                            if (!res.equals("NOT_SUPPORTED") && !Boolean.FALSE.equals(sup)) {
                                check(!NrLeg.cannot(t), "'cannot' only when it really cannot" + ctx);
                            }
                            // the "off" hint appears only for plain "off", where waiting makes sense
                            if (err.isEmpty() && res.equals("SUCCESS") && en != null && ask.equals("off") && Boolean.TRUE.equals(en)) {
                                check(t.contains("off now"), "plain off that is still on points to 'off now'" + ctx);
                            }
                        }
                    }
                }
            }
        }
        check(combos == 3 * 8 * 3 * 3 * 2, "all combinations were tried");

        // ---- reading only
        for (Boolean en : tri) {
            for (Boolean sup : tri) {
                for (String err : errors) {
                    String t = NrLeg.describe(st(en, sup, "n/a", err));
                    Boolean off = NrLeg.offFromText(t);
                    String ctx = " [enabled=" + en + " supported=" + sup + " error=" + err + "]";
                    check(!t.isEmpty(), "a read always has words" + ctx);
                    if (!err.isEmpty()) {
                        check(off == null, "a read error says nothing about the state" + ctx);
                    } else if (en == null) {
                        check(off == null, "an unknown state stays unknown" + ctx);
                    } else {
                        check(Boolean.valueOf(!en).equals(off), "a read says exactly the reported state" + ctx);
                    }
                    check(NrLeg.cannot(t) == (err.isEmpty() && Boolean.FALSE.equals(sup)), "a read says 'cannot' only if the modem said so" + ctx);
                }
            }
        }

        // ---- the tablet's reading of arbitrary text
        check(NrLeg.offFromText(null) == null, "null text");
        check(NrLeg.offFromText("") == null, "empty text");
        check(NrLeg.offFromText("Failed (code 1): 5G leg is off") == Boolean.TRUE, "an off statement inside other text");
        check(NrLeg.offFromText("the leg is off") == null, "similar words are not enough");
        check(!NrLeg.cannot(null), "cannot(null)");

        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
