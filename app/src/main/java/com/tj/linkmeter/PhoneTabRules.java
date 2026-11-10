package com.tj.linkmeter;

/**
 * Pure Java (no Android classes), so it is tested on a computer: tools/test/PhoneTabRulesTest.java, and tools/mutate_link.py
 * ("phonetab:") breaks each rule on purpose.
 *
 * What the tablet's Phone tab says and offers, decided from what the phone's status says. A field the status leaves out (an older
 * phone app, or a phone that cannot tell) is "not known", never a guess: it never becomes "off", and never triggers a banner.
 */
final class PhoneTabRules {
    private PhoneTabRules() {}

    /** The card at the top of the tab: the first of these that is true, or none. */
    enum Banner { NONE, UNREACHABLE, AIRPLANE, SHIZUKU_OFF, SHIZUKU_NOT_ALLOWED, VERSION }

    /**
     * @param reachable the phone answered the last question
     * @param airplane the status's airplane flag (null = the phone did not say)
     * @param shizukuRunning null = the phone did not say
     * @param shizukuAllowed null = the phone did not say; only read when Shizuku runs
     * @param phoneCode the phone app's version code (-1 = not known)
     * @param ownCode this tablet app's version code (-1 = not known)
     */
    static Banner banner(boolean reachable, Boolean airplane, Boolean shizukuRunning, Boolean shizukuAllowed, long phoneCode, long ownCode) {
        if (!reachable) {
            return Banner.UNREACHABLE;
        }
        if (Boolean.TRUE.equals(airplane)) {
            return Banner.AIRPLANE;
        }
        if (Boolean.FALSE.equals(shizukuRunning)) {
            return Banner.SHIZUKU_OFF;
        }
        if (Boolean.TRUE.equals(shizukuRunning) && Boolean.FALSE.equals(shizukuAllowed)) {
            return Banner.SHIZUKU_NOT_ALLOWED;
        }
        // not-equal, not "older": a tablet older than the phone is worth saying too
        if (phoneCode >= 0 && ownCode >= 0 && phoneCode != ownCode) {
            return Banner.VERSION;
        }
        return Banner.NONE;
    }

    /**
     * The battery's present current as a sentence, from the status's cur (milliamps: negative while discharging, positive while
     * charging; null when the phone did not say, then there is no line at all).
     */
    static String currentWords(Integer cur) {
        if (cur == null) {
            return null;
        }
        int v = cur.intValue();
        if (v > 0) {
            return "The phone is charging at about " + v + " mA.";
        }
        if (v < 0) {
            return "The phone is using about " + (-v) + " mA.";
        }
        return "The phone's battery current is 0 mA.";
    }

    /** One of the phone's switches as a fact line: on, off, or not known. */
    static String switchFact(Boolean on) {
        return on == null ? "not known" : on.booleanValue() ? "on" : "off";
    }

    /**
     * Why "Update the phone" cannot be used right now, or "" when it can. The update sends the whole app, which only fits through
     * the hotspot (Bluetooth is for small requests).
     *
     * @param overBluetooth the last answer came over the Bluetooth link (the hotspot route did not connect)
     */
    static String updateBlocked(boolean reachable, boolean overBluetooth) {
        if (!reachable) {
            return "The phone cannot be reached.";
        }
        return overBluetooth ? "The update needs the hotspot: the app is too big to send over Bluetooth." : "";
    }

    /** Is a phone app that answers "unknown action" an older one? (Its words for an action it does not have.) */
    static boolean tooOld(String answer) {
        return answer != null && answer.contains("unknown action");
    }

    /** The version line: "0.2.141 (code 141)", or "not read yet" when the phone did not say. */
    static String versionText(String name, long code) {
        return code >= 0 ? (name == null || name.isEmpty() ? "" : name + " ") + "(code " + code + ")" : "not read yet";
    }
}
