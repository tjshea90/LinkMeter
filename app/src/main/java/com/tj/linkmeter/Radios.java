package com.tj.linkmeter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * Phone only: the state of the phone's switches, read the cheap way (no Shizuku, no new process), because the tablet asks for
 * them with every status request: is the hotspot on, is Wi-Fi on, is the screen on, is airplane mode on.
 */
final class Radios {
    private Radios() {}

    /** Android's announcement "the hotspot's state changed" (not in the public SDK; the number travels as "wifi_state"). */
    private static final String AP_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED";
    /** The hotspot state Android last announced while {@link #watch} was listening (10 to 14), or -1 if it has announced none. */
    private static volatile int announced = -1;
    private static BroadcastReceiver watcher;

    /**
     * The service has started: remember what Android announces about the hotspot from now on. Up to Android 12 the last
     * announcement can be read back at any time; since then it can only be heard as it happens (and only by an app that may
     * see the Wi-Fi state, which this one may). Costs nothing between changes.
     */
    static synchronized void watch(Context c) {
        if (watcher != null) {
            return;
        }
        Context app = c.getApplicationContext();
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                int st = i.getIntExtra("wifi_state", -1);
                if (st >= 10 && st <= 14) {
                    announced = st;
                }
            }
        };
        try {
            IntentFilter f = new IntentFilter(AP_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED); // the system's own broadcasts still arrive
            } else {
                app.registerReceiver(r, f);
            }
            watcher = r;
        } catch (RuntimeException e) {
            watcher = null; // no announcements: the other two ways are still asked
        }
    }

    /** The service is stopping: what was heard is forgotten, because changes from now on would be missed. */
    static synchronized void unwatch(Context c) {
        if (watcher != null) {
            try {
                c.getApplicationContext().unregisterReceiver(watcher);
            } catch (RuntimeException ignored) {
                // was not registered
            }
            watcher = null;
        }
        announced = -1;
    }

    /**
     * Is the phone's own Wi-Fi hotspot on? TRUE, FALSE, or null when this phone does not say: "not known" is never passed off
     * as "off". Android has no public question for it, so three ways are asked and {@link RemoteRoutes#hotspotOn} weighs them:
     * the Wi-Fi manager's own (hidden) answer, the last "hotspot state changed" announcement, and a hotspot network interface
     * with an address.
     */
    static Boolean hotspot(Context c) {
        Context app = c.getApplicationContext();
        Boolean api = null;
        try {
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            Object on = WifiManager.class.getMethod("isWifiApEnabled").invoke(wm);
            if (on instanceof Boolean) {
                api = (Boolean) on;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // hidden on this Android version: the other ways are asked
        }
        int heard = announced;
        if (heard < 0) {
            try {
                Intent last = app.registerReceiver(null, new IntentFilter(AP_CHANGED)); // Android 12 and older keep the last one
                heard = last == null ? -1 : last.getIntExtra("wifi_state", -1);
            } catch (RuntimeException ignored) {
                // not available
            }
        }
        return RemoteRoutes.hotspotOn(api, heard, Boot.hotspotOn(app));
    }

    /** Is the phone's Wi-Fi switched on? null when the phone does not say (the Safety check never prints that as "off"). */
    static Boolean wifi(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            return wm == null ? null : Boolean.valueOf(wm.isWifiEnabled());
        } catch (RuntimeException e) {
            return null;
        }
    }

    static boolean wifiOn(Context c) {
        return Boolean.TRUE.equals(wifi(c));
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

    /**
     * Waits (up to ms) for the hotspot to be on, or off. TRUE: it is as wanted. FALSE: it is not. null: this phone does not
     * say which (see {@link #hotspot}).
     */
    static Boolean waitHotspot(Context c, boolean wantOn, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (true) {
            Boolean on = hotspot(c);
            if (on != null && on == wantOn) {
                return Boolean.TRUE;
            }
            if (System.currentTimeMillis() >= end) {
                return on == null ? null : Boolean.FALSE;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return on == null ? null : Boolean.FALSE;
            }
        }
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
