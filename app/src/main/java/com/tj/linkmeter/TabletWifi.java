package com.tj.linkmeter;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import java.io.IOException;
import java.util.function.BooleanSupplier;

/**
 * Tablet only. The tablet's own Wi-Fi switch, for {@link TabletWifiRules} (the Wi-Fi follows the phone's hotspot).
 *
 * Since Android 10 an app that targets Android 10 or newer cannot switch the Wi-Fi: {@code WifiManager.setWifiEnabled} answers
 * false for it, whatever permissions it holds. So {@link #set} tries every way there is, in this order, and reads the state back
 * after each, because "the call went through" proves nothing:
 *  1. the direct call (it works where Android allows it: an older Android, or an app installed as a system app);
 *  2. Shizuku, when it runs on THIS tablet and LinkMeter is allowed in it (the phone's way: {@code svc wifi enable});
 *  3. Android's own Wi-Fi switch, opened for one tap of the user. That is the usual outcome on this tablet, and it is said as what
 *     it is: the app asked, nothing was switched.
 * The state is only ever read through {@link #on}; a state Android does not report is "not known" and never acted on.
 */
final class TabletWifi {
    /** How the switch came out; the screen shows it. */
    interface Done {
        void done(TabletWifiRules.Way way);
    }

    /** How long a switch that was taken may need before the state shows it: this many looks, one every {@link #LOOK_MS}. */
    private static final int LOOKS = 24;
    private static final long LOOK_MS = 250L;

    /**
     * Tests only (tools/uipreview): true stands for an Android that refuses to switch the Wi-Fi, as Android 10 does for this app,
     * whatever the test's pretend Wi-Fi manager would do. Never set in the app.
     */
    static volatile boolean refusedForTest;

    private TabletWifi() {}

    /** The tablet's Wi-Fi: on (or coming on), off (or going off), or null when Android does not say. */
    static Boolean on(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            return wm == null ? null : TabletWifiRules.onFromState(wm.getWifiState());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The Wi-Fi network the tablet is on now, as the number Android gives it (it differs for every network and every new
     * connection), or {@link TabletWifiRules#NO_NETWORK}. No location permission is needed for this, unlike a network's name.
     */
    static long network(Context c) {
        Network n = Net.wifi(c);
        if (n == null) {
            return TabletWifiRules.NO_NETWORK;
        }
        try {
            return n.getNetworkHandle();
        } catch (RuntimeException e) {
            return TabletWifiRules.NO_NETWORK;
        }
    }

    /**
     * Switches the tablet's Wi-Fi on or off and says how it went (always on the UI thread). mayAsk: asked on the UI thread just
     * before Android's own switch would be opened; false (the user has left the screen) means it is not opened, because an app
     * that is out of sight may not open a screen on Android 10 and nobody would see it.
     */
    static void set(final MainActivity act, final boolean on, final BooleanSupplier mayAsk, final Done done) {
        final Context app = act.getApplicationContext();
        new Thread(() -> {
            final boolean did = already(app, on) || direct(app, on) || viaShizuku(app, on);
            act.ui.post(() -> {
                if (did) {
                    done.done(TabletWifiRules.Way.DONE);
                } else if (mayAsk.getAsBoolean() && openSwitch(act)) {
                    done.done(TabletWifiRules.Way.ASKED);
                } else {
                    done.done(TabletWifiRules.Way.FAILED);
                }
            });
        }, "linkmeter-wifi").start();
    }

    /** Is it as wanted already (it came on by itself while the phone was switching)? Then there is nothing to switch. */
    private static boolean already(Context app, boolean on) {
        Boolean now = on(app);
        return now != null && now == on;
    }

    /** The direct call. On Android 10 and newer this answers false for this app; it is kept for the builds where it does not. */
    @SuppressWarnings("deprecation")
    private static boolean direct(Context app, boolean on) {
        if (refusedForTest) {
            return false;
        }
        try {
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            return wm != null && wm.setWifiEnabled(on) && settled(app, on);
        } catch (RuntimeException e) {
            return false; // no permission, or the service is not there
        }
    }

    /** Through Shizuku, if it runs on this tablet and LinkMeter is allowed in it. Never asks for anything. */
    private static boolean viaShizuku(Context app, boolean on) {
        if (refusedForTest) {
            return false;
        }
        try {
            if (!Priv.ready()) {
                return false;
            }
            Priv.run("svc wifi " + (on ? "enable" : "disable") + " 2>&1 || cmd wifi set-wifi-enabled " + (on ? "enabled" : "disabled")
                    + " 2>&1", 15);
            return settled(app, on);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Did the state follow? Looks for a few seconds: a switch that was taken shows after a moment, one that was not never does. */
    private static boolean settled(Context app, boolean on) {
        for (int i = 0; i < LOOKS; i++) {
            if (already(app, on)) {
                return true;
            }
            try {
                Thread.sleep(LOOK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return already(app, on);
    }

    /**
     * Opens Android's own Wi-Fi switch: the quick panel with the switch in it, or the Wi-Fi settings where there is no panel.
     * False if neither could be opened. Only from the UI thread, and only while the app is in front.
     */
    static boolean openSwitch(Activity act) {
        try {
            act.startActivity(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY));
            return true;
        } catch (RuntimeException e) {
            try {
                act.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                return true;
            } catch (RuntimeException e2) {
                return false;
            }
        }
    }
}
