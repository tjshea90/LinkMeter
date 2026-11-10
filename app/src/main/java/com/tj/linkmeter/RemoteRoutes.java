package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/LinkProtocolTest.java). Two rules about the phone's two doors, the network (the
 * hotspot) and the Bluetooth link:
 *
 *  - which requests may come in over Bluetooth at all: only the small ones that ask a question or flip a switch. Files, texts,
 *    the screen's picture and touches, app updates and pairing need the hotspot and are refused on Bluetooth;
 *  - which actions take away the very link they arrived on, so that the phone must answer BEFORE it acts (the answer could
 *    not be delivered afterwards). Over Bluetooth far fewer do, so there the tablet gets the real result.
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
     * link. hotspotOn: the phone's own hotspot is on (so a request from the network came in through the hotspot, not through
     * a Wi-Fi network the phone has joined).
     */
    static boolean cutsLink(String action, boolean viaBluetooth, boolean hotspotOn) {
        switch (action) {
            case "poweroff":
            case "reboot":
            case "airplane_on":
                return true; // the phone, or every radio, goes away
            case "hotspot_off":
            case "hotspot_restart":
            case "radio_cycle": // the radio restart takes the hotspot down with it
            case "standby":
            case "hotspot_on": // a phone that is on somebody's Wi-Fi leaves it to become the hotspot
            case "boot_hotspot_on":
            case "wifi_on": // many phones cannot be a hotspot and a Wi-Fi client at once: the hotspot may stop
                return !viaBluetooth;
            case "wifi_off":
                return !viaBluetooth && !hotspotOn; // only when the request came through the Wi-Fi that is being switched off
            default:
                return false;
        }
    }
}
