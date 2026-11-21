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
                } else if (mayAsk.getAsBoolean()) {
                    // one of Android's own screens, for one tap: and it is checked that a screen really came in front (field report
                    // 2026-11-21: "Switch Wi-Fi on" did nothing, and nothing said so)
                    showSwitch(act, shown -> done.done(shown ? TabletWifiRules.Way.ASKED : TabletWifiRules.Way.FAILED));
                } else {
                    done.done(TabletWifiRules.Way.FAILED);
                }
            });
        }, "linkmeter-wifi").start();
    }

    /** Tells whether one of Android's Wi-Fi screens really came in front. */
    interface Shown {
        void shown(boolean ok);
    }

    /** The screens Android has for its Wi-Fi switch, in the order they are tried. */
    static final int OPEN_INTERNET_PANEL = 0;
    static final int OPEN_WIFI_PANEL = 1;
    static final int OPEN_WIFI_SETTINGS = 2;
    static final int OPEN_WAYS = 3;
    /**
     * How long a screen that was started may take to come in front before the next one is tried. The first is a small Settings panel
     * that has to start Android's Settings app on a 2 GB tablet: it can take a second or two, and starting the next screen on top of a
     * slow one would only stack two.
     */
    static final long SHOW_FIRST_MS = 2500L;
    static final long SHOW_NEXT_MS = 1800L;

    /**
     * Tests only (tools/uipreview): a pretend Android never pauses the activity when a screen is started, so every screen that was
     * accepted counts as shown at once, with nothing waited for. Never set in the app.
     */
    static volatile boolean assumeShownForTest;

    /**
     * Opens Android's own Wi-Fi switch for one tap and makes sure something came up: the Internet panel, else the Wi-Fi panel, else
     * the full Wi-Fi settings. A screen that Android accepted but that did not come in front (the app was not paused within the time
     * given) is followed by the next one. Reports (on the UI thread) whether any screen showed.
     */
    static void showSwitch(final MainActivity act, final Shown result) {
        tryWay(act, 0, act.pauseCount(), result);
    }

    private static void tryWay(final MainActivity act, int way, final int pausesBefore, final Shown result) {
        while (way < OPEN_WAYS && !openSwitch(act, way)) {
            way++;
        }
        if (way >= OPEN_WAYS) {
            result.shown(false);
            return;
        }
        if (assumeShownForTest) {
            result.shown(true);
            return;
        }
        final int started = way;
        act.ui.postDelayed(() -> {
            if (act.pauseCount() != pausesBefore || !act.inFront()) {
                result.shown(true); // the app was covered (or the user has already gone): a screen is up
            } else {
                tryWay(act, started + 1, pausesBefore, result);
            }
        }, started == 0 ? SHOW_FIRST_MS : SHOW_NEXT_MS);
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
        for (int way = OPEN_INTERNET_PANEL; way < OPEN_WAYS; way++) {
            if (way != OPEN_WIFI_PANEL && openSwitch(act, way)) {
                return true;
            }
        }
        return false;
    }

    /** Starts one of the screens ({@link #OPEN_INTERNET_PANEL} ...). False if Android has no such screen or refuses it. */
    static boolean openSwitch(Activity act, int way) {
        String action = way == OPEN_INTERNET_PANEL ? Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                : way == OPEN_WIFI_PANEL ? Settings.Panel.ACTION_WIFI : Settings.ACTION_WIFI_SETTINGS;
        try {
            act.startActivity(new Intent(action));
            return true;
        } catch (RuntimeException e) {
            return false; // no such screen on this Android (ActivityNotFoundException), or it is not allowed
        }
    }
}
