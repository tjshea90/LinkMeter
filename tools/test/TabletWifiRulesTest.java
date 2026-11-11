package com.tj.linkmeter;

import com.tj.linkmeter.TabletWifiRules.Verdict;
import com.tj.linkmeter.TabletWifiRules.Way;
import com.tj.linkmeter.TabletWifiRules.Wish;

/**
 * Tests TabletWifiRules: the tablet's own Wi-Fi follows the phone's hotspot (on with "Hotspot on", off with "Hotspot off" and
 * Standby) and is never touched for a switch that did not move, when it already is as wanted, or when it carries another
 * network. Not part of the app.
 */
public final class TabletWifiRulesTest {
    private static int checks;
    private static int fails;

    private TabletWifiRulesTest() {}

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

    private static final long NONE = TabletWifiRules.NO_NETWORK;
    private static final long HOTSPOT = 5;
    private static final long HOME = 7;

    public static void main(String[] args) {
        states();
        wishes();
        press();
        result();
        scenarios();
        everything();
        words();
        System.out.println(checks + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }

    /** WifiManager's five states: coming on is on, going off is off, anything else is not known. */
    private static void states() {
        eq(Boolean.FALSE, TabletWifiRules.onFromState(0), "disabling is off");
        eq(Boolean.FALSE, TabletWifiRules.onFromState(1), "disabled is off");
        eq(Boolean.TRUE, TabletWifiRules.onFromState(2), "enabling is on");
        eq(Boolean.TRUE, TabletWifiRules.onFromState(3), "enabled is on");
        eq(null, TabletWifiRules.onFromState(4), "unknown is not known");
        for (int s : new int[] {-1, 5, 6, 99, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            eq(null, TabletWifiRules.onFromState(s), "a state nobody defined is not known: " + s);
        }
    }

    private static void wishes() {
        eq(Wish.ON, TabletWifiRules.wishOf("hotspot_on"), "hotspot on wants Wi-Fi on");
        eq(Wish.OFF, TabletWifiRules.wishOf("hotspot_off"), "hotspot off wants Wi-Fi off");
        eq(Wish.OFF, TabletWifiRules.wishOf("standby"), "standby switches the hotspot off, so it wants Wi-Fi off");
        eq(Wish.OFF, TabletWifiRules.wishOf("hotspot_off_deep"), "the deep Hotspot off switches the hotspot off, so it wants Wi-Fi off");
        // every other name the tablet sends: the hotspot is not switched by them
        for (String a : new String[] {"hotspot_restart", "boot_hotspot_on", "wifi_on", "wifi_off", "airplane_on", "poweroff", "reboot",
            "eco_auto_on", "eco_auto_off", "eco_on", "eco_off", "radio_cycle", "", "unknown", "HOTSPOT_ON", " hotspot_on", "hotspot_on ",
            "hotspot", "standby2"}) {
            eq(Wish.NONE, TabletWifiRules.wishOf(a), "no wish for '" + a + "'");
        }
        eq(Wish.NONE, TabletWifiRules.wishOf(null), "no action, no wish");
    }

    private static void press() {
        // switched on in the settings, Wi-Fi off: Hotspot on wants it on
        eq(Wish.ON, TabletWifiRules.atPress(true, "hotspot_on", Boolean.FALSE), "hotspot on with Wi-Fi off");
        eq(Wish.NONE, TabletWifiRules.atPress(true, "hotspot_on", Boolean.TRUE), "hotspot on with Wi-Fi on already: nothing to do");
        eq(Wish.OFF, TabletWifiRules.atPress(true, "hotspot_off", Boolean.TRUE), "hotspot off with Wi-Fi on");
        eq(Wish.NONE, TabletWifiRules.atPress(true, "hotspot_off", Boolean.FALSE), "hotspot off with Wi-Fi off already");
        eq(Wish.OFF, TabletWifiRules.atPress(true, "standby", Boolean.TRUE), "standby with Wi-Fi on");
        eq(Wish.OFF, TabletWifiRules.atPress(true, "hotspot_off_deep", Boolean.TRUE), "the deep Hotspot off with Wi-Fi on");
        eq(Wish.NONE, TabletWifiRules.atPress(true, "hotspot_off_deep", Boolean.FALSE), "the deep Hotspot off with Wi-Fi off already");
        eq(Wish.NONE, TabletWifiRules.atPress(true, "standby", Boolean.FALSE), "standby with Wi-Fi off already");
        // the user's setting is the master switch
        for (String a : new String[] {"hotspot_on", "hotspot_off", "hotspot_off_deep", "standby"}) {
            for (Boolean w : new Boolean[] {Boolean.TRUE, Boolean.FALSE, null}) {
                eq(Wish.NONE, TabletWifiRules.atPress(false, a, w), "setting off: " + a + " with Wi-Fi " + w);
            }
        }
        // not known is never acted on
        for (String a : new String[] {"hotspot_on", "hotspot_off", "hotspot_off_deep", "standby"}) {
            eq(Wish.NONE, TabletWifiRules.atPress(true, a, null), "Wi-Fi state not known: " + a);
        }
        // other switches never touch it
        for (String a : new String[] {"hotspot_restart", "wifi_on", "airplane_on", "poweroff", "reboot", "screen"}) {
            for (Boolean w : new Boolean[] {Boolean.TRUE, Boolean.FALSE}) {
                eq(Wish.NONE, TabletWifiRules.atPress(true, a, w), "other switch " + a + " with Wi-Fi " + w);
            }
        }
    }

    private static void result() {
        // not settled: never
        for (Wish w : Wish.values()) {
            for (Boolean now : new Boolean[] {Boolean.TRUE, Boolean.FALSE, null}) {
                for (long atPress : new long[] {NONE, HOTSPOT}) {
                    for (long netNow : new long[] {NONE, HOTSPOT, HOME}) {
                        eq(Verdict.NOTHING, TabletWifiRules.atResult(w, false, now, atPress, netNow),
                                "the phone's switch did not settle: nothing (" + w + ", " + now + ")");
                    }
                }
            }
        }
        // no wish: never
        eq(Verdict.NOTHING, TabletWifiRules.atResult(Wish.NONE, true, Boolean.TRUE, HOTSPOT, NONE), "no wish");
        eq(Verdict.NOTHING, TabletWifiRules.atResult(Wish.NONE, true, Boolean.FALSE, NONE, NONE), "no wish, Wi-Fi off");
        // not known: never
        eq(Verdict.NOTHING, TabletWifiRules.atResult(Wish.ON, true, null, NONE, NONE), "on: state not known");
        eq(Verdict.NOTHING, TabletWifiRules.atResult(Wish.OFF, true, null, HOTSPOT, NONE), "off: state not known");
        // ON
        eq(Verdict.SWITCH_ON, TabletWifiRules.atResult(Wish.ON, true, Boolean.FALSE, NONE, NONE), "on: still off, so switch on");
        eq(Verdict.NOTHING, TabletWifiRules.atResult(Wish.ON, true, Boolean.TRUE, NONE, NONE), "on: came on by itself meanwhile");
        // OFF
        eq(Verdict.NOTHING, TabletWifiRules.atResult(Wish.OFF, true, Boolean.FALSE, HOTSPOT, NONE), "off: already off");
        eq(Verdict.SWITCH_OFF, TabletWifiRules.atResult(Wish.OFF, true, Boolean.TRUE, HOTSPOT, NONE), "off: the hotspot's network is gone");
        eq(Verdict.SWITCH_OFF, TabletWifiRules.atResult(Wish.OFF, true, Boolean.TRUE, HOTSPOT, HOTSPOT), "off: still the dying hotspot network");
        eq(Verdict.LEAVE_ON_OTHER_NETWORK, TabletWifiRules.atResult(Wish.OFF, true, Boolean.TRUE, HOTSPOT, HOME), "off: joined the home router");
    }

    /** The real situations, told as a story each. */
    private static void scenarios() {
        // The usual day. Morning: Wi-Fi off, hotspot off, the link over Bluetooth. "Hotspot on".
        Wish w = TabletWifiRules.atPress(true, "hotspot_on", Boolean.FALSE);
        eq(Wish.ON, w, "morning: Hotspot on wants Wi-Fi on");
        eq(Verdict.SWITCH_ON, TabletWifiRules.atResult(w, true, Boolean.FALSE, NONE, NONE), "morning: the phone said yes, Wi-Fi is still off: switch it on");
        // The phone refused (Shizuku not ready): the tablet's Wi-Fi is not touched.
        eq(Verdict.NOTHING, TabletWifiRules.atResult(w, false, Boolean.FALSE, NONE, NONE), "morning: the phone refused: Wi-Fi stays off");
        // Evening. On the hotspot (network 5), the phone answered over it. "Hotspot off".
        w = TabletWifiRules.atPress(true, "hotspot_off", Boolean.TRUE);
        eq(Wish.OFF, w, "evening: Hotspot off wants Wi-Fi off");
        eq(Verdict.SWITCH_OFF, TabletWifiRules.atResult(w, true, Boolean.TRUE, HOTSPOT, NONE), "evening: confirmed, the hotspot network is gone: Wi-Fi off");
        eq(Verdict.SWITCH_OFF, TabletWifiRules.atResult(w, true, Boolean.TRUE, HOTSPOT, HOTSPOT), "evening: confirmed, the dead network is not yet forgotten: Wi-Fi off");
        eq(Verdict.NOTHING, TabletWifiRules.atResult(w, false, Boolean.TRUE, HOTSPOT, HOTSPOT), "evening: the phone did not confirm: Wi-Fi stays on");
        // At home on the home router, the phone's hotspot also on and reached over Bluetooth: Hotspot off must not cut the router.
        w = TabletWifiRules.atPress(true, "hotspot_off", Boolean.TRUE);
        eq(Verdict.LEAVE_ON_OTHER_NETWORK, TabletWifiRules.atResult(w, true, Boolean.TRUE, NONE, HOME), "home: the tablet is on the router: Wi-Fi stays on");
        // Wi-Fi was switched on but never joined anything, the hotspot is switched off again: nothing to keep it for.
        eq(Verdict.SWITCH_OFF, TabletWifiRules.atResult(w, true, Boolean.TRUE, NONE, NONE), "never joined: Wi-Fi off");
        // The tablet fell onto the router right after the hotspot went away: the router is not the hotspot's business.
        eq(Verdict.LEAVE_ON_OTHER_NETWORK, TabletWifiRules.atResult(w, true, Boolean.TRUE, HOTSPOT, HOME), "fell onto the router: Wi-Fi stays on");
        // Standby is the same as Hotspot off.
        w = TabletWifiRules.atPress(true, "standby", Boolean.TRUE);
        eq(Verdict.SWITCH_OFF, TabletWifiRules.atResult(w, true, Boolean.TRUE, HOTSPOT, NONE), "standby: Wi-Fi off");
        // The setting is off: the tablet's Wi-Fi is never touched.
        eq(Wish.NONE, TabletWifiRules.atPress(false, "hotspot_on", Boolean.FALSE), "setting off: nothing");
    }

    /** Every combination of what could be true at the press and at the result, against rules written the other way round. */
    private static void everything() {
        String[] actions = {"hotspot_on", "hotspot_off", "hotspot_off_deep", "standby", "hotspot_restart", "wifi_on", "poweroff"};
        Boolean[] states = {Boolean.TRUE, Boolean.FALSE, null};
        long[] atPresses = {NONE, HOTSPOT};
        long[] nows = {NONE, HOTSPOT, HOME};
        int worlds = 0;
        for (boolean enabled : new boolean[] {true, false}) {
            for (String action : actions) {
                for (Boolean atPressState : states) {
                    for (boolean confirmed : new boolean[] {true, false}) {
                        for (Boolean nowState : states) {
                            for (long netAtPress : atPresses) {
                                for (long netNow : nows) {
                                    worlds++;
                                    Wish wish = TabletWifiRules.atPress(enabled, action, atPressState);
                                    Verdict v = TabletWifiRules.atResult(wish, confirmed, nowState, netAtPress, netNow);
                                    String where = "enabled=" + enabled + " " + action + " press:" + atPressState + " confirmed=" + confirmed
                                            + " now:" + nowState + " net " + netAtPress + "->" + netNow + " = " + v;
                                    // the Wi-Fi as it is after the verdict is carried out
                                    Boolean after = nowState;
                                    if (v == Verdict.SWITCH_ON) {
                                        after = Boolean.TRUE;
                                    } else if (v == Verdict.SWITCH_OFF) {
                                        after = Boolean.FALSE;
                                    }
                                    // 1. the setting off, or the phone's switch not settled: the tablet's Wi-Fi is left as it is
                                    if (!enabled || !confirmed) {
                                        eq(Verdict.NOTHING, v, "left alone: " + where);
                                    }
                                    // 2. only these three actions ever touch it
                                    if (!(action.equals("hotspot_on") || action.equals("hotspot_off") || action.equals("hotspot_off_deep") || action.equals("standby"))) {
                                        eq(Verdict.NOTHING, v, "other action: " + where);
                                    }
                                    // 3. a state that is not known is never acted on
                                    if (atPressState == null || nowState == null) {
                                        eq(Verdict.NOTHING, v, "not known: " + where);
                                    }
                                    // 4. it is only ever switched to something it is not
                                    if (v == Verdict.SWITCH_ON) {
                                        yes(nowState != null && !nowState, "switched on only when off: " + where);
                                        yes(action.equals("hotspot_on"), "switched on only for Hotspot on: " + where);
                                    }
                                    if (v == Verdict.SWITCH_OFF) {
                                        yes(nowState != null && nowState, "switched off only when on: " + where);
                                        yes(action.equals("hotspot_off") || action.equals("hotspot_off_deep") || action.equals("standby"), "switched off only for Hotspot off / Standby: " + where);
                                        // 5. never a Wi-Fi that carries a network that is not the hotspot's
                                        yes(netNow == NONE || (netAtPress != NONE && netNow == netAtPress), "off only when useless: " + where);
                                    }
                                    // 6. a settled Hotspot on with the setting on leaves Wi-Fi on; a settled off leaves it off unless it carries another network
                                    if (enabled && confirmed && action.equals("hotspot_on") && atPressState != null && !atPressState && nowState != null) {
                                        yes(Boolean.TRUE.equals(after), "Hotspot on ends with Wi-Fi on: " + where);
                                    }
                                    if (enabled && confirmed && (action.equals("hotspot_off") || action.equals("hotspot_off_deep") || action.equals("standby")) && atPressState != null
                                            && atPressState && nowState != null && nowState) {
                                        boolean other = netNow != NONE && !(netAtPress != NONE && netNow == netAtPress);
                                        yes(other ? Boolean.TRUE.equals(after) : Boolean.FALSE.equals(after), "Hotspot off ends with Wi-Fi off unless on another network: " + where);
                                        eq(other ? Verdict.LEAVE_ON_OTHER_NETWORK : Verdict.SWITCH_OFF, v, "verdict: " + where);
                                    }
                                    // 7. the other-network verdict only for an off wish
                                    if (v == Verdict.LEAVE_ON_OTHER_NETWORK) {
                                        yes(action.equals("hotspot_off") || action.equals("hotspot_off_deep") || action.equals("standby"), "left on only for an off wish: " + where);
                                        yes(netNow != NONE, "left on only on a network: " + where);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        yes(worlds == 2 * 7 * 3 * 2 * 3 * 2 * 3, "all the worlds were visited: " + worlds);
    }

    private static void words() {
        eq("", TabletWifiRules.words(Verdict.NOTHING, Way.DONE), "nothing to say");
        eq("", TabletWifiRules.words(Verdict.NOTHING, Way.FAILED), "nothing to say, whatever the way");
        eq("The tablet's Wi-Fi is on.", TabletWifiRules.words(Verdict.SWITCH_ON, Way.DONE), "on, done");
        eq("The tablet's Wi-Fi is off.", TabletWifiRules.words(Verdict.SWITCH_OFF, Way.DONE), "off, done");
        String askedOn = TabletWifiRules.words(Verdict.SWITCH_ON, Way.ASKED);
        String askedOff = TabletWifiRules.words(Verdict.SWITCH_OFF, Way.ASKED);
        String failedOn = TabletWifiRules.words(Verdict.SWITCH_ON, Way.FAILED);
        String failedOff = TabletWifiRules.words(Verdict.SWITCH_OFF, Way.FAILED);
        yes(askedOn.contains("Switch the tablet's Wi-Fi on in the panel"), "asked on names the panel: " + askedOn);
        yes(askedOff.contains("Switch the tablet's Wi-Fi off in the panel"), "asked off names the panel: " + askedOff);
        yes(failedOn.contains("could not switch the tablet's Wi-Fi on"), "failed on says so: " + failedOn);
        yes(failedOff.contains("could not switch the tablet's Wi-Fi off"), "failed off says so: " + failedOff);
        // the honest-words rule: only a switch that was seen to happen says "is on" / "is off"
        for (String s : new String[] {askedOn, askedOff, failedOn, failedOff}) {
            yes(!s.contains("Wi-Fi is on") && !s.contains("Wi-Fi is off"), "no claim without a read-back: " + s);
        }
        yes(askedOn.contains("Android does not let an app"), "says why one tap is needed: " + askedOn);
        eq("The tablet's Wi-Fi was left on: the tablet is on another network.", TabletWifiRules.words(Verdict.LEAVE_ON_OTHER_NETWORK, Way.DONE), "left on");
        eq(TabletWifiRules.words(Verdict.LEAVE_ON_OTHER_NETWORK, Way.DONE), TabletWifiRules.words(Verdict.LEAVE_ON_OTHER_NETWORK, Way.FAILED), "left on does not depend on the way");
        // plain words
        for (Verdict v : Verdict.values()) {
            for (Way way : Way.values()) {
                String s = TabletWifiRules.words(v, way);
                yes(!s.contains("!") && !s.contains(" - ") && !s.contains("  "), "plain words: " + s);
            }
        }
        // the line
        eq("The hotspot is on. The tablet's Wi-Fi is on.", TabletWifiRules.join("The hotspot is on.", "The tablet's Wi-Fi is on."), "join");
        eq("The hotspot is on.", TabletWifiRules.join("The hotspot is on.", ""), "nothing to add");
        eq("The tablet's Wi-Fi is on.", TabletWifiRules.join("", "The tablet's Wi-Fi is on."), "nothing to follow");
        eq("", TabletWifiRules.join("", ""), "nothing at all");
        // the colour
        yes(TabletWifiRules.good(true, Way.DONE), "good: done");
        yes(TabletWifiRules.good(true, Way.ASKED), "good: asked (the panel is not a fault)");
        yes(!TabletWifiRules.good(true, Way.FAILED), "not good: the Wi-Fi switch failed outright");
        yes(!TabletWifiRules.good(false, Way.DONE), "not good: the phone's switch was not ok");
        yes(!TabletWifiRules.good(false, Way.FAILED), "not good: both");
    }
}
