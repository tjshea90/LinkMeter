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
 *
 * Two more rules live here because every request passes through this one place:
 *  - a status read that a screen makes by itself never switches the tablet's Bluetooth on; only something the user pressed may
 *    ({@link #statusRead});
 *  - over Bluetooth the phone carries an action out BEFORE it answers (over the hotspot it often answers first), so an action
 *    gets at least a minute there, whatever the caller allowed for the hotspot ({@link #bluetoothReadMs}).
 */
final class Reach {
    /** After the hotspot route failed to connect and Bluetooth worked, the hotspot route is left alone this long. */
    static final long SKIP_WIFI_MS = 8_000L;
    /** The least time an action gets over Bluetooth: there the phone does the work first and answers afterwards. */
    static final int ACT_MS_BLUETOOTH = 60_000;

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

        /**
         * pressed: the user pressed something for this request, so the tablet's Bluetooth may be switched on for it. Without
         * that, a tablet whose Bluetooth is off throws {@link BluetoothOff} and leaves it off.
         */
        Client.Resp call(String method, String target, int readMs, boolean pressed) throws IOException;
    }

    /** The tablet's own Bluetooth is switched off, and this request may not switch it on. Nothing was sent. */
    static final class BluetoothOff extends IOException {
        BluetoothOff() {
            super("Bluetooth is off on this tablet");
        }
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
        /** True if a route was really tried. False: there was nothing to try (no hotspot address, no usable Bluetooth link). */
        boolean asked;
        /** True if the phone was called over Bluetooth for this (whether or not it answered): such a call wakes the phone. */
        boolean btTried;
        /** True if Bluetooth was the way left, the tablet's Bluetooth is off, and this request may not switch it on. */
        boolean btOff;
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

    /**
     * Is this one of the questions a screen asks by itself, again and again, to show the phone's state? Such a read never
     * switches the tablet's Bluetooth on: the user may have switched it off on purpose, and nobody pressed anything. Every
     * other request is the result of a button.
     */
    static boolean statusRead(String method, String path) {
        return "GET".equals(method) && ("/s".equals(path) || "/sig".equals(path) || "/boot".equals(path)
                || "/screen/state".equals(path) || "/speed/status".equals(path));
    }

    /** How long the answer may take over Bluetooth: what the caller allowed, but at least a minute for an action. */
    static int bluetoothReadMs(String path, int readMs) {
        return "/act".equals(path) ? Math.max(readMs, ACT_MS_BLUETOOTH) : readMs;
    }

    /** One request as a screen makes it: a status read by itself is not a press, anything else is. */
    Result call(String method, String target, int readMs) {
        return call(method, target, readMs, !statusRead(method, pathOf(target)));
    }

    /** pressed: the user pressed something for this (see {@link Bluetooth#call}). */
    Result call(String method, String target, int readMs, boolean pressed) {
        Result res = new Result();
        boolean btOk = bt.ready() && RemoteRoutes.overBluetooth(method, pathOf(target));
        String wifiError = "";
        if (wifi.known() && !skipWifi(btOk)) {
            res.asked = true;
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
            String before = wifiError.isEmpty() ? (wifi.known() ? "" : "The tablet is not on the phone's hotspot, and ")
                    : wifiError + (wifiError.endsWith(")") || wifiError.endsWith(".") ? " And " : ". And ");
            if (!bluetooth(res, method, target, readMs, pressed)) {
                // Only "was tried": whether the request got through is for Bluetooth's own reason to say. A call that was not
                // answered did not, but a request that went out and got no answer in time may well have been carried out.
                res.error = before + (res.btOff ? res.error + "." : "Bluetooth was tried: " + res.error);
                res.error = Character.toUpperCase(res.error.charAt(0)) + res.error.substring(1);
            }
            return res;
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

    /**
     * One request over the Bluetooth link and nothing else: the live test of the link that the Home tab makes before it
     * switches the hotspot off. Always the result of a press. The error, if any, is Bluetooth's own reason.
     */
    Result bluetoothOnly(String method, String target, int readMs) {
        Result res = new Result();
        if (!bt.ready() || !RemoteRoutes.overBluetooth(method, pathOf(target))) {
            res.error = "the Bluetooth link is not set up";
            return res;
        }
        bluetooth(res, method, target, readMs, true);
        return res;
    }

    /** The Bluetooth half of a request. True: res holds the answer. False: res.error holds Bluetooth's own reason. */
    private boolean bluetooth(Result res, String method, String target, int readMs, boolean pressed) {
        try {
            res.resp = bt.call(method, target, bluetoothReadMs(pathOf(target), readMs), pressed);
            res.asked = true;
            res.btTried = true;
            res.via = BLUETOOTH;
            lastVia = BLUETOOTH;
            noteBt(true);
            return true;
        } catch (BluetoothOff e) {
            res.btOff = true; // nothing was sent, and nothing was switched on
            noteBt(false);
            res.error = e.getMessage();
            return false;
        } catch (IOException e) {
            res.asked = true;
            res.btTried = true;
            noteBt(false);
            res.error = e.getMessage();
            return false;
        }
    }
}
