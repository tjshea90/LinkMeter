package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/TabletWifiRulesTest.java). The tablet's own Wi-Fi follows the phone's hotspot: when the
 * hotspot is switched ON from the tablet the tablet's Wi-Fi is wanted on (it has to join the hotspot), and when the hotspot is
 * switched OFF (or the phone put on Standby) the tablet's Wi-Fi is wanted off (there is nothing to join, and it would only go on
 * searching).
 *
 * Why this is not just "switch it": since Android 10 an app that targets Android 10 or newer cannot switch the Wi-Fi at all
 * (WifiManager.setWifiEnabled does nothing for it). What the app can do is try every way there is, read the state back, and when
 * none works open Android's own Wi-Fi switch for one tap ({@link TabletWifi}). The words here never say more than was seen.
 *
 * The rules that matter:
 *  - nothing happens unless the switch on the PHONE was settled (the phone said so, or its status showed it): the tablet's Wi-Fi
 *    is never touched for a switch that failed;
 *  - Wi-Fi is switched ON only when it is off, and OFF only when it is on, so the screen never "switches" what already is so;
 *  - Wi-Fi is switched OFF only when it is useless: the tablet is on no Wi-Fi network, or still on the very network the phone
 *    answered on at the press (the phone's hotspot, going away). A tablet that is on ANOTHER network (the home router) keeps its
 *    Wi-Fi: that network was never the hotspot's business;
 *  - a Wi-Fi state that Android does not report is "not known", and not known is never acted on.
 */
final class TabletWifiRules {
    /** "No Wi-Fi network" for a network handle ({@link android.net.Network#getNetworkHandle()} is never negative). */
    static final long NO_NETWORK = -1L;

    /** What a switch of the phone asks of the tablet's Wi-Fi, decided when the button is pressed. */
    enum Wish { NONE, ON, OFF }

    /** What to do, decided when the phone's switch is settled. */
    enum Verdict { NOTHING, SWITCH_ON, SWITCH_OFF, LEAVE_ON_OTHER_NETWORK }

    /** How the switch of the tablet's Wi-Fi came out. */
    enum Way {
        /** Android switched it (the state read back shows it). */
        DONE,
        /** Android does not let the app do it: its own Wi-Fi switch was opened for the user's tap. */
        ASKED,
        /** It could not be done and nothing could be opened for the user either. */
        FAILED
    }

    private TabletWifiRules() {}

    // WifiManager.WIFI_STATE_*: written out here so that this class needs no Android
    static final int STATE_DISABLING = 0;
    static final int STATE_DISABLED = 1;
    static final int STATE_ENABLING = 2;
    static final int STATE_ENABLED = 3;

    /**
     * On, off, or null when Android does not say. Coming on counts as on and going off as off: the switch has been taken, which
     * is all that "did it switch" can ask for (the network follows a few seconds later).
     */
    static Boolean onFromState(int wifiState) {
        switch (wifiState) {
            case STATE_ENABLING:
            case STATE_ENABLED:
                return Boolean.TRUE;
            case STATE_DISABLING:
            case STATE_DISABLED:
                return Boolean.FALSE;
            default: // WIFI_STATE_UNKNOWN (4), or anything a vendor adds
                return null;
        }
    }

    /** What this action of the phone asks of the tablet's Wi-Fi, if it is switched at all. */
    static Wish wishOf(String action) {
        if (action == null) {
            return Wish.NONE;
        }
        switch (action) {
            case "hotspot_on":
                return Wish.ON;
            case "hotspot_off":
            case "hotspot_off_deep": // the hotspot, then location, airplane mode and the screen
            case "standby": // the hotspot goes off with the screen
                return Wish.OFF;
            default: // the screen, the phone's own Wi-Fi, a restart of the hotspot, power: the hotspot stays or comes back by itself
                return Wish.NONE;
        }
    }

    /**
     * At the press: is the tablet's Wi-Fi to follow this switch? enabled: the user's setting. wifiOn: the tablet's Wi-Fi now, null
     * when Android does not say. The answer is only a wish: {@link #atResult} decides once the phone's switch is settled.
     */
    static Wish atPress(boolean enabled, String action, Boolean wifiOn) {
        if (!enabled || wifiOn == null) {
            return Wish.NONE;
        }
        Wish w = wishOf(action);
        if (w == Wish.ON && !wifiOn) {
            return Wish.ON;
        }
        if (w == Wish.OFF && wifiOn) {
            return Wish.OFF;
        }
        return Wish.NONE;
    }

    /**
     * When the phone's switch is settled. confirmed: the phone said it moved, or its status showed it (a "Sent" that nothing
     * confirmed, a timeout, a screen that was left, are not that). wifiOnNow: the tablet's Wi-Fi now (null: not known).
     * networkAtPress: the Wi-Fi network the tablet was on at the press IF the phone then answered over it, so it was the phone's
     * hotspot; {@link #NO_NETWORK} if the phone answered another way (Bluetooth) or the tablet was on no network.
     * networkNow: the network the tablet is on now, {@link #NO_NETWORK} if none.
     */
    static Verdict atResult(Wish wish, boolean confirmed, Boolean wifiOnNow, long networkAtPress, long networkNow) {
        if (!confirmed || wish == Wish.NONE || wifiOnNow == null) {
            return Verdict.NOTHING;
        }
        if (wish == Wish.ON) {
            return wifiOnNow ? Verdict.NOTHING : Verdict.SWITCH_ON; // it may have come on by itself meanwhile
        }
        if (!wifiOnNow) {
            return Verdict.NOTHING; // it is already off
        }
        boolean noNetwork = networkNow == NO_NETWORK;
        boolean hotspotsNetwork = networkAtPress != NO_NETWORK && networkNow == networkAtPress;
        return noNetwork || hotspotsNetwork ? Verdict.SWITCH_OFF : Verdict.LEAVE_ON_OTHER_NETWORK;
    }

    /** The sentence for the result line: what became of the tablet's Wi-Fi. "" when there is nothing to say. */
    static String words(Verdict v, Way way) {
        switch (v) {
            case LEAVE_ON_OTHER_NETWORK:
                return "The tablet's Wi-Fi was left on: the tablet is on another network.";
            case SWITCH_ON:
            case SWITCH_OFF:
                String state = v == Verdict.SWITCH_ON ? "on" : "off";
                if (way == Way.DONE) {
                    return "The tablet's Wi-Fi is " + state + ".";
                }
                if (way == Way.ASKED) {
                    return "Switch the tablet's Wi-Fi " + state + " in the panel that opened: Android does not let an app do it "
                            + "by itself.";
                }
                return "LinkMeter could not switch the tablet's Wi-Fi " + state + ". Do it in Android's quick settings.";
            default:
                return "";
        }
    }

    /** The phone's result and the Wi-Fi sentence in one line. */
    static String join(String phoneSaid, String wifiSaid) {
        if (wifiSaid.isEmpty()) {
            return phoneSaid;
        }
        if (phoneSaid.isEmpty()) {
            return wifiSaid;
        }
        return phoneSaid + " " + wifiSaid;
    }

    /** Is the line a good one (not shown in the warning colour)? The phone's result stands, unless the Wi-Fi switch failed outright. */
    static boolean good(boolean phoneOk, Way way) {
        return phoneOk && way != Way.FAILED;
    }
}
