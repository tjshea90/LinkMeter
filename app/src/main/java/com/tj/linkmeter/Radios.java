package com.tj.linkmeter;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * Phone only: the state of the phone's switches, read the cheap way (no Shizuku, no new process), because the tablet asks for
 * them with every status request: is the hotspot on, is Wi-Fi on, is the screen on, is airplane mode on.
 */
final class Radios {
    private Radios() {}

    /** How the last hotspot answer was found ("api", "broadcast" or "interfaces"), for the Probe report. */
    static volatile String hotspotHow = "";

    /**
     * Is the phone's own Wi-Fi hotspot on? Android has no public question for it, so three ways are tried in turn: the Wi-Fi
     * manager's own (hidden) answer, the last "hotspot state changed" broadcast Android keeps, and as the last resort a hotspot
     * network interface with an address.
     */
    static boolean hotspotOn(Context c) {
        Context app = c.getApplicationContext();
        try {
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            Object on = WifiManager.class.getMethod("isWifiApEnabled").invoke(wm);
            if (on instanceof Boolean) {
                hotspotHow = "api";
                return (Boolean) on;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // hidden on this Android version: try the next way
        }
        try {
            Intent last = app.registerReceiver(null, new IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED"));
            int st = last == null ? -1 : last.getIntExtra("wifi_state", -1);
            if (st >= 10 && st <= 14) {
                hotspotHow = "broadcast";
                return st == 13 || st == 12; // enabled, or being enabled
            }
        } catch (RuntimeException ignored) {
            // not available: try the next way
        }
        hotspotHow = "interfaces";
        return Boot.hotspotOn(app);
    }

    static boolean wifiOn(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            return wm != null && wm.isWifiEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean screenOn(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean airplane(Context c) {
        try {
            return Settings.Global.getInt(c.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Waits (up to ms) for the hotspot to be on, or off; returns whether it ended up as wanted. */
    static boolean waitHotspot(Context c, boolean wantOn, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (hotspotOn(c) != wantOn) {
            if (System.currentTimeMillis() >= end) {
                return false;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return hotspotOn(c) == wantOn;
            }
        }
        return true;
    }

    /** Waits (up to ms) for the phone's Wi-Fi switch to be on, or off; returns whether it ended up as wanted. */
    static boolean waitWifi(Context c, boolean wantOn, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (wifiOn(c) != wantOn) {
            if (System.currentTimeMillis() >= end) {
                return false;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return wifiOn(c) == wantOn;
            }
        }
        return true;
    }
}
