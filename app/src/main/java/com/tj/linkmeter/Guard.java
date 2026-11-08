package com.tj.linkmeter;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Safety net for radio changes (band lock, 4G/5G). After one, it watches mobile data for ten
 * minutes. If data has not worked for two minutes in a row it undoes the change by itself and
 * posts a notification, so a bad setting can never leave the phone without internet.
 * It is idle the rest of the time and uses no timers outside that window.
 */
final class Guard {
    private static final long STEP_MS = 20000L;
    private static final long WATCH_MS = 10L * 60 * 1000;
    private static final long BAD_LIMIT_MS = 2L * 60 * 1000;
    private static final Handler H = new Handler(Looper.getMainLooper());

    private static boolean running;
    private static long endAt;
    private static long badSince;

    private Guard() {}

    /** Call after any change that could affect mobile data. Safe to call again: it extends the window. */
    static synchronized void arm(Context c) {
        final Context app = c.getApplicationContext();
        endAt = SystemClock.elapsedRealtime() + WATCH_MS;
        badSince = 0;
        if (running) {
            return;
        }
        running = true;
        H.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!step(app)) {
                    H.postDelayed(this, STEP_MS);
                }
            }
        }, STEP_MS);
    }

    /** Returns true when watching is over. */
    private static synchronized boolean step(final Context app) {
        long now = SystemClock.elapsedRealtime();
        if (now > endAt) {
            running = false;
            return true;
        }
        if (dataWorks(app)) {
            badSince = 0;
            return false;
        }
        if (badSince == 0) {
            badSince = now;
            return false;
        }
        if (now - badSince >= BAD_LIMIT_MS) {
            running = false;
            new Thread(() -> {
                String report = Actions.restoreRadio(app);
                Notice.post(app, "LinkMeter undid a network change",
                        "Mobile data stopped working after a change, so the band lock and network "
                                + "types were put back.\n" + report);
            }, "linkmeter-guard").start();
            return true;
        }
        return false;
    }

    private static boolean dataWorks(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (RuntimeException e) {
            return true; // can't tell: don't undo anything
        }
    }
}
