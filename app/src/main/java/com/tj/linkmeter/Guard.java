package com.tj.linkmeter;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The phone's safety net for everything LinkMeter changes on its radio: network types, a band lock (even a "kept" one), the 5G leg,
 * a radio that was switched off for a moment, airplane mode started from the tablet. The rules are in {@link GuardPolicy} (tested
 * on a computer): in short, if mobile data is down for two minutes (the first ten minutes after a change) or four minutes (later)
 * while any of that is still in place, the phone puts it back by itself, with no tablet and no internet, and says so in a
 * notification. It judges the CELLULAR network, not whatever network happens to be active (a working Wi-Fi must not hide a dead
 * mobile connection), and it never fights the user: airplane mode or mobile data switched off by hand count as "cannot tell".
 * It watches only while something is still changed, a quick look every 20 s at first and once a minute later, so it costs nothing
 * the rest of the time. {@link #resume} re-arms it when the service starts, so a restart of the app or the phone does not lose it.
 */
final class Guard {
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final GuardPolicy POLICY = new GuardPolicy();
    private static final AtomicBoolean RECOVERING = new AtomicBoolean();
    private static Context appCtx;
    private static boolean running;

    private Guard() {}

    /** Call after any change that could affect mobile data. Safe to call again: it starts the close watch over. */
    static synchronized void arm(Context c) {
        appCtx = c.getApplicationContext();
        POLICY.arm(SystemClock.elapsedRealtime());
        start();
    }

    /** The service has started: if anything LinkMeter changed is still in place, watch closely again. */
    static synchronized void resume(Context c) {
        appCtx = c.getApplicationContext();
        if (Saved.pendingRadio(appCtx)) {
            POLICY.arm(SystemClock.elapsedRealtime());
            start();
        }
    }

    private static void start() {
        if (running) {
            return;
        }
        running = true;
        H.postDelayed(TICK, GuardPolicy.FAST_STEP_MS);
    }

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            step();
        }
    };

    private static synchronized void step() {
        final Context app = appCtx;
        if (app == null) {
            running = false;
            return;
        }
        // a radio that was switched off for a moment and never came back, airplane mode that was meant to end: finish that first
        if (Actions.timersDue(app) && RECOVERING.compareAndSet(false, true)) {
            new Thread(() -> {
                try {
                    Actions.recoverTimers(app);
                } finally {
                    RECOVERING.set(false);
                }
            }, "linkmeter-recover").start();
        }
        GuardPolicy.Decision d = POLICY.step(SystemClock.elapsedRealtime(), Saved.pendingRadio(app), dataState(app));
        if (d.act == GuardPolicy.Act.STOP) {
            running = false;
            return;
        }
        if (d.act == GuardPolicy.Act.RESTORE) {
            new Thread(() -> {
                String report = Actions.restoreRadio(app);
                Notice.post(app, "LinkMeter undid a network change",
                        "Mobile data stopped working while something LinkMeter had changed was still in place, so it was put back.\n"
                                + report);
            }, "linkmeter-guard").start();
        }
        H.postDelayed(TICK, d.nextMs);
    }

    /**
     * GOOD: a cellular network with working internet. BAD: none. UNKNOWN: cannot tell, or the user chose it (airplane mode, mobile
     * data switched off, no answer from Android): never counted against LinkMeter's changes.
     */
    private static GuardPolicy.Data dataState(Context c) {
        try {
            if (Settings.Global.getInt(c.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1) {
                return GuardPolicy.Data.UNKNOWN;
            }
            TelephonyManager tm = c.getSystemService(TelephonyManager.class);
            if (tm != null && !tm.isDataEnabled()) {
                return GuardPolicy.Data.UNKNOWN;
            }
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    return GuardPolicy.Data.GOOD;
                }
            }
            return GuardPolicy.Data.BAD;
        } catch (RuntimeException e) {
            return GuardPolicy.Data.UNKNOWN; // can't tell: don't undo anything
        }
    }
}
