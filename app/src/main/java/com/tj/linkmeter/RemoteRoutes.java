package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/LinkProtocolTest.java). The rules about the phone's two doors, the network (the
 * hotspot) and the Bluetooth link:
 *
 *  - which requests may come in over Bluetooth at all: only the small ones that ask a question or flip a switch. Files, texts,
 *    the screen's picture and touches, app updates and pairing need the hotspot and are refused on Bluetooth;
 *  - which actions take away the very link they arrived on, so that the phone must answer BEFORE it acts (the answer could
 *    not be delivered afterwards). Over Bluetooth far fewer do, so there the tablet gets the real result;
 *  - whether the hotspot is on, off, or not known, from what the phone can find out without Shizuku;
 *  - which screen requests are power switches and so do not wait for the phone's "see and control this screen" switch;
 *  - which Bluetooth connection gives way when one more arrives, and when the phone tries to listen again after a failure.
 */
final class RemoteRoutes {
    private RemoteRoutes() {}

    /** Every path the Bluetooth link serves. Anything else answers 404 without reaching a handler. */
    private static final String[] BLUETOOTH_PATHS = {
        "/s", "/sig", "/boot", "/act", "/probe", "/cells",
        "/screen/state", "/screen/on", "/screen/off",
        "/speed/start", "/speed/stop", "/speed/status",
    };

    static boolean overBluetooth(String method, String path) {
        if (!"GET".equals(method) && !"POST".equals(method)) {
            return false;
        }
        for (String p : BLUETOOTH_PATHS) {
            if (p.equals(path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Does carrying out this action take away the link it was asked over? viaBluetooth: the request came over the Bluetooth
     * link. hotspotOn: TRUE when the phone's own hotspot is on (so a request from the network came in through the hotspot, not
     * through a Wi-Fi network the phone has joined), FALSE when it is off, null when the phone cannot tell (see
     * {@link #hotspotOn}). Not known counts as "may be cut": answering first is the safe side, the answer still arrives.
     */
    static boolean cutsLink(String action, boolean viaBluetooth, Boolean hotspotOn) {
        return cutsLink(action, viaBluetooth, hotspotOn, false);
    }

    /**
     * The same, for a phone that may be in airplane mode (airplaneOn). "Hotspot on" then begins with ending airplane mode, and that
     * can restart Bluetooth, which may be what the request came over: so there it is answered first, like on the hotspot route, and
     * the tablet reads the result from the status. "Hotspot off" in its deep form (hotspot, location, airplane mode, screen) ends in
     * airplane mode, which can restart Bluetooth too: answered first on every route.
     */
    static boolean cutsLink(String action, boolean viaBluetooth, Boolean hotspotOn, boolean airplaneOn) {
        switch (action) {
            case "poweroff":
            case "reboot":
            case "airplane_on":
            case "hotspot_off_deep":
                return true; // the phone, or every radio, goes away
            case "hotspot_off":
            case "hotspot_restart":
            case "radio_cycle": // the radio restart takes the hotspot down with it
            case "standby":
            case "wifi_on": // many phones cannot be a hotspot and a Wi-Fi client at once: the hotspot may stop
            case "fast_direct_on": // Fast Wi-Fi's own network replaces the hotspot (docs/FAST_WIFI.md)
            case "fast_direct_off": // and the hotspot replaces it again
                return !viaBluetooth;
            case "hotspot_on": // a phone that is on somebody's Wi-Fi leaves it to become the hotspot
            case "boot_hotspot_on":
                return !viaBluetooth || airplaneOn;
            case "wifi_off":
                // only when the request came through the Wi-Fi that is being switched off; not known = it may have
                return !viaBluetooth && !Boolean.TRUE.equals(hotspotOn);
            default:
                return false;
        }
    }

    /**
     * Is the phone's own Wi-Fi hotspot on? TRUE, FALSE, or null when nothing can tell. Android has no public question for it, so
     * three sources are weighed, the surest first:
     *  - api: the Wi-Fi service's own answer (a hidden question; null when this Android does not let an app ask it);
     *  - announced: the hotspot state Android last announced (10 switching off, 11 off, 12 switching on, 13 on, 14 failed to
     *    start), or -1 when no announcement was heard. Only 13 is "on": a hotspot that is still coming up is not on yet;
     *  - hotspotInterface: a hotspot network interface with an address is up. That proves "on"; its absence proves nothing
     *    (the interface may have a name this app does not know).
     * "Not known" must never be reported as "off": the tablet would then offer to switch on a hotspot that is on.
     */
    static Boolean hotspotOn(Boolean api, int announced, boolean hotspotInterface) {
        if (api != null) {
            return api;
        }
        if (announced >= 10 && announced <= 14) {
            return Boolean.valueOf(announced == 13);
        }
        return hotspotInterface ? Boolean.TRUE : null;
    }

    /**
     * Is a hotspot in this state (the Wi-Fi service's numbers, as above) DOWN: nothing is running, so there is nothing to
     * switch off, and a start may be asked? 11 (off) and 14 (failed). After a start that failed Android leaves the state at 14
     * until the next start, and a hotspot that never came up is not on. Not 10 (still going off). And never anything else:
     * a state that is not known (-1) is not "down".
     */
    static boolean hotspotDown(int state) {
        return state == 11 || state == 14;
    }

    /**
     * The screen requests that are power switches: sleep, wake, and the question which of the two it is. They show nothing of
     * what is on the screen and move nothing on it, so the phone's switch "Let the tablet see and control this screen" does not
     * stop them (the Standby action switches the screen off as well). The picture, touches and typing stay behind that switch.
     */
    static boolean screenPower(String path) {
        return "/screen/state".equals(path) || "/screen/on".equals(path) || "/screen/off".equals(path);
    }

    /**
     * The Bluetooth door is full and one more caller has arrived: which open connection is closed to make room? proven[i] says
     * whether connection i (the oldest first) has sent a request signed by a paired tablet. One that has not proved itself goes
     * before one that has, so a stranger in range cannot push the paired tablet out by opening connections; among equals the
     * oldest goes.
     */
    static int givesWay(boolean[] proven) {
        for (int i = 0; i < proven.length; i++) {
            if (!proven[i]) {
                return i;
            }
        }
        return 0;
    }

    /** After a try to open the Bluetooth door, the next try by itself comes no sooner than this. */
    static final long LISTEN_AGAIN_MS = 30_000L;

    /**
     * Should the phone try by itself to listen on Bluetooth again? state: the door's state as the status reports it. Only the
     * states a new try can cure ("perm": the permission may have been given in Android's settings since; "error": the last try
     * failed; "off": Bluetooth may be back on), and only when the last such try is at least half a minute old. Never for
     * "disabled" (the user's own switch), "none" (no Bluetooth, or the service is not running) or "on".
     */
    static boolean listenAgain(String state, long msSinceLastTry) {
        return ("perm".equals(state) || "error".equals(state) || "off".equals(state)) && msSinceLastTry >= LISTEN_AGAIN_MS;
    }
}
