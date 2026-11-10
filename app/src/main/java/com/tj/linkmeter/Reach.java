package com.tj.linkmeter;

import java.io.IOException;

/**
 * No Android in here (tested in tools/test/ReachTest.java). The tablet's choice of route for one small request to the phone
 * (docs/REMOTE_LINK.md): the hotspot first; Bluetooth when the hotspot route could not even CONNECT, the link is set up and the
 * request is one of the small ones that may travel there.
 *
 * The rule that matters: a request is never sent a second time unless it is certain that the first try did not arrive. Only a
 * failed connect is that certain ({@link Client.ConnectFailed}). A request that connected and then got no answer may have been
 * carried out, so it is reported as failed and NOT repeated over Bluetooth.
 */
final class Reach {
    /** After the hotspot route failed to connect and Bluetooth worked, the hotspot route is left alone this long. */
    static final long SKIP_WIFI_MS = 8_000L;

    static final String WIFI = "wifi";
    static final String BLUETOOTH = "bt";

    /** The hotspot route. */
    interface Wifi {
        /** Is there an address to try at all (the tablet is on a Wi-Fi network, or an address was typed)? */
        boolean known();

        Client.Resp call(String method, String target, int readMs) throws IOException;
    }

    /** The Bluetooth link. */
    interface Bluetooth {
        /** Set up and switched on in the tablet's settings (whether the phone answers is found out by calling). */
        boolean ready();

        Client.Resp call(String method, String target, int readMs) throws IOException;
    }

    interface Clock {
        long now();
    }

    static final class Result {
        /** The phone's answer, or null if it could not be asked. */
        Client.Resp resp;
        /** {@link #WIFI} or {@link #BLUETOOTH}: the route that carried the answer. "" if there is none. */
        String via = "";
        /** Why there is no answer, in words for the user. "" if there is one. */
        String error = "";
        /** True if the hotspot route was tried and no connection could be made (the phone's hotspot is probably off). */
        boolean wifiDown;
    }

    private final Wifi wifi;
    private final Bluetooth bt;
    private final Clock clock;
    private long wifiFailedAt = -1;
    private boolean btWorked;
    /** The route of the last answer, for a line on the screen. */
    private volatile String lastVia = "";

    Reach(Wifi wifi, Bluetooth bt, Clock clock) {
        this.wifi = wifi;
        this.bt = bt;
        this.clock = clock;
    }

    String lastVia() {
        return lastVia;
    }

    private static String pathOf(String target) {
        int q = target.indexOf('?');
        return q < 0 ? target : target.substring(0, q);
    }

    private synchronized boolean skipWifi(boolean btOk) {
        long now = clock.now();
        return btOk && btWorked && wifiFailedAt >= 0 && now >= wifiFailedAt && now - wifiFailedAt < SKIP_WIFI_MS;
    }

    private synchronized void noteWifi(boolean connected) {
        wifiFailedAt = connected ? -1 : clock.now(); // a hotspot route that connects ends any skipping at once
    }

    private synchronized void noteBt(boolean worked) {
        btWorked = worked;
    }

    Result call(String method, String target, int readMs) {
        Result res = new Result();
        boolean btOk = bt.ready() && RemoteRoutes.overBluetooth(method, pathOf(target));
        String wifiError = "";
        if (wifi.known() && !skipWifi(btOk)) {
            try {
                res.resp = wifi.call(method, target, readMs);
                res.via = WIFI;
                lastVia = WIFI;
                noteWifi(true);
                return res;
            } catch (Client.ConnectFailed e) {
                // nothing was sent: the other route may be tried
                noteWifi(false);
                res.wifiDown = true;
                wifiError = "Couldn't reach the phone: " + e.getMessage();
            } catch (IOException e) {
                // the request may have arrived: say so, and do not send it again another way
                noteWifi(true);
                res.error = "Couldn't reach the phone: " + e.getMessage();
                return res;
            }
        } else if (wifi.known()) {
            res.wifiDown = true; // not tried again so soon after it failed
        }
        if (btOk) {
            try {
                res.resp = bt.call(method, target, readMs);
                res.via = BLUETOOTH;
                lastVia = BLUETOOTH;
                noteBt(true);
                return res;
            } catch (IOException e) {
                noteBt(false);
                res.error = (wifiError.isEmpty() ? (wifi.known() ? "" : "The tablet is not on the phone's hotspot, and ")
                        : wifiError + " And ") + "Bluetooth did not get through either: " + e.getMessage();
                res.error = Character.toUpperCase(res.error.charAt(0)) + res.error.substring(1);
                return res;
            }
        }
        if (!wifiError.isEmpty()) {
            res.error = wifiError;
        } else if (!wifi.known()) {
            res.error = "The tablet is not on the phone's hotspot.";
        } else {
            res.error = "Couldn't reach the phone.";
        }
        return res;
    }
}
