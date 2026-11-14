package com.tj.linkmeter;

/** Tests PhoneTabRules, what the Phone tab says and offers. Not part of the app. */
public final class PhoneTabRulesTest {
    private static int checks;
    private static int fails;

    private PhoneTabRulesTest() {}

    private static void eq(Object want, Object got, String what) {
        checks++;
        if (!String.valueOf(want).equals(String.valueOf(got))) {
            fails++;
            System.out.println("FAIL " + what + ": wanted " + want + " but got " + got);
        }
    }

    private static void yes(boolean ok, String what) {
        checks++;
        if (!ok) {
            fails++;
            System.out.println("FAIL " + what);
        }
    }

    public static void main(String[] args) {
        Boolean[] tri = {Boolean.TRUE, Boolean.FALSE, null};
        long[] codes = {-1, 100, 141};
        // every combination against the rule written another way round
        for (boolean reachable : new boolean[] {true, false}) {
            for (Boolean air : tri) {
                for (Boolean run : tri) {
                    for (Boolean allowed : tri) {
                        for (long phone : codes) {
                            for (long own : codes) {
                                PhoneTabRules.Banner want;
                                if (!reachable) {
                                    want = PhoneTabRules.Banner.UNREACHABLE;
                                } else if (air != null && air) {
                                    want = PhoneTabRules.Banner.AIRPLANE;
                                } else if (run != null && !run) {
                                    want = PhoneTabRules.Banner.SHIZUKU_OFF;
                                } else if (run != null && run && allowed != null && !allowed) {
                                    want = PhoneTabRules.Banner.SHIZUKU_NOT_ALLOWED;
                                } else if (phone >= 0 && own >= 0 && phone != own) {
                                    want = PhoneTabRules.Banner.VERSION;
                                } else {
                                    want = PhoneTabRules.Banner.NONE;
                                }
                                eq(want, PhoneTabRules.banner(reachable, air, run, allowed, phone, own),
                                        "banner reachable=" + reachable + " air=" + air + " shizuku=" + run + "/" + allowed + " phone=" + phone + " own=" + own);
                            }
                        }
                    }
                }
            }
        }
        // the cases that matter
        eq(PhoneTabRules.Banner.UNREACHABLE, PhoneTabRules.banner(false, Boolean.TRUE, Boolean.FALSE, null, 100, 141), "not reachable wins over everything");
        eq(PhoneTabRules.Banner.AIRPLANE, PhoneTabRules.banner(true, Boolean.TRUE, Boolean.FALSE, null, 100, 141), "airplane mode wins over Shizuku and the version");
        eq(PhoneTabRules.Banner.SHIZUKU_OFF, PhoneTabRules.banner(true, Boolean.FALSE, Boolean.FALSE, null, 100, 141), "Shizuku not running wins over the version");
        eq(PhoneTabRules.Banner.SHIZUKU_NOT_ALLOWED, PhoneTabRules.banner(true, null, Boolean.TRUE, Boolean.FALSE, 100, 141), "running but not allowed");
        eq(PhoneTabRules.Banner.NONE, PhoneTabRules.banner(true, null, Boolean.TRUE, Boolean.TRUE, 141, 141), "all well: no banner");
        eq(PhoneTabRules.Banner.VERSION, PhoneTabRules.banner(true, Boolean.FALSE, Boolean.TRUE, Boolean.TRUE, 140, 141), "the phone's app is older");
        eq(PhoneTabRules.Banner.VERSION, PhoneTabRules.banner(true, Boolean.FALSE, Boolean.TRUE, Boolean.TRUE, 142, 141), "the phone's app is newer: also said (not-equal)");
        eq(PhoneTabRules.Banner.NONE, PhoneTabRules.banner(true, null, null, null, -1, 141), "an older phone that says nothing: no banner, nothing is guessed");
        eq(PhoneTabRules.Banner.NONE, PhoneTabRules.banner(true, null, null, null, 141, -1), "a tablet that does not know its own version: no banner");

        // Android's own Battery saver: the choice and the words that keep it apart from LinkMeter's saver
        eq(-1, PhoneTabRules.androidSaverChoice(null), "an older phone app has no such option: nothing is selected");
        eq(0, PhoneTabRules.androidSaverChoice(Boolean.TRUE), "the option on: 'Also switch on'");
        eq(1, PhoneTabRules.androidSaverChoice(Boolean.FALSE), "the option off: 'Leave alone'");
        for (String t : new String[] {PhoneTabRules.SAVER_CARD_HINT, PhoneTabRules.ANDROID_SAVER_HINT, PhoneTabRules.ANDROID_SAVER_OLD_APP}) {
            yes(!t.contains("!") && !t.contains(" - ") && !t.contains("\u2014") && !t.contains("\u2013"), "plain words: " + t);
            yes(!t.contains("Eco") && !t.contains("Priv") && !t.contains("low_power"), "no class names or settings: " + t);
        }
        yes(PhoneTabRules.SAVER_CARD_HINT.contains("not Android's own Battery saver"), "the card says LinkMeter's saver is not Android's");
        yes(PhoneTabRules.SAVER_CARD_HINT.contains("leaves alone unless you ask"), "...and that Android's is left alone unless asked");
        yes(PhoneTabRules.ANDROID_SAVER_HINT.contains("Samsung: Power saving"), "the option's hint gives Samsung's name for it");
        yes(PhoneTabRules.ANDROID_SAVER_HINT.contains("Not tried on this phone yet"), "...says it is untried");
        yes(PhoneTabRules.ANDROID_SAVER_HINT.contains("Standby") && PhoneTabRules.ANDROID_SAVER_HINT.contains("hotspot goes off"), "...and names the two risks");
        eq("update the phone's app", PhoneTabRules.ANDROID_SAVER_OLD_APP, "an older phone app is told to update");

        // the battery current
        eq(null, PhoneTabRules.currentWords(null), "no cur: no line");
        eq("The phone is using about 312 mA.", PhoneTabRules.currentWords(-312), "discharging");
        eq("The phone is charging at about 1450 mA.", PhoneTabRules.currentWords(1450), "charging");
        eq("The phone's battery current is 0 mA.", PhoneTabRules.currentWords(0), "zero");
        eq("The phone is using about 1 mA.", PhoneTabRules.currentWords(-1), "one");
        for (int v : new int[] {-2000, -312, -1, 0, 1, 300, 5000}) {
            String s = PhoneTabRules.currentWords(v);
            yes(!s.contains("-") && !s.contains("!"), "plain words, no minus sign: " + s);
        }

        // switches
        eq("on", PhoneTabRules.switchFact(Boolean.TRUE), "on");
        eq("off", PhoneTabRules.switchFact(Boolean.FALSE), "off");
        eq("not known", PhoneTabRules.switchFact(null), "not known is never 'off'");

        // the update
        eq("", PhoneTabRules.updateBlocked(true, false), "over the hotspot the update can be sent");
        yes(PhoneTabRules.updateBlocked(true, true).contains("needs the hotspot"), "over Bluetooth it cannot, and says why");
        yes(PhoneTabRules.updateBlocked(false, false).contains("cannot be reached"), "no phone, no update");
        yes(!PhoneTabRules.updateBlocked(false, true).isEmpty(), "unreachable over Bluetooth is blocked too");

        // an older phone app
        yes(PhoneTabRules.tooOld("unknown action: eco_auto_on"), "the older phone's words");
        yes(!PhoneTabRules.tooOld("Shizuku is not running"), "another error is not 'too old'");
        yes(!PhoneTabRules.tooOld(null), "no answer is not 'too old'");

        // the version line
        eq("0.2.141 (code 141)", PhoneTabRules.versionText("0.2.141", 141), "name and code");
        eq("(code 141)", PhoneTabRules.versionText("", 141), "code only");
        eq("not read yet", PhoneTabRules.versionText("0.2.141", -1), "not known");

        System.out.println(checks + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
