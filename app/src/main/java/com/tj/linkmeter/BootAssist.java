package com.tj.linkmeter;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.HandlerThread;
import rikka.shizuku.Shizuku;

/**
 * Phone only. What the phone does by itself after a restart while "Boot assist" is on (the decisions are in {@link BootPlan}, which is
 * tested offline): when it joins a Wi-Fi network and Shizuku is not running, switch wireless debugging on and show the notification that
 * opens Shizuku. Event driven: a Wi-Fi callback and Shizuku's "binder received" listener wake it, and the only timer is the short
 * re-check after wireless debugging was switched on. It stops listening once Shizuku runs, when Boot assist is switched off, when the
 * service stops, or 20 minutes after it started (the phone has been up that long without Shizuku: nobody is doing this now).
 */
final class BootAssist {
    private static final Object LOCK = new Object();
    private static final long RECHECK_MS = 12_000L;

    private static Context app;
    private static HandlerThread thread;
    private static Handler handler;
    private static ConnectivityManager.NetworkCallback callback;
    private static Shizuku.OnBinderReceivedListener binderListener;
    private static long since;
    private static int tries;
    private static long lastWadbAt;
    private static boolean notified;
    private static long notifiedAt;

    private BootAssist() {}

    /** Called when the phone service starts and when Boot assist is switched on. Does nothing if it is off or already listening. */
    static void start(Context c) {
        synchronized (LOCK) {
            if (!Boot.assist(c)) {
                stopLocked();
                return;
            }
            if (handler != null) {
                return;
            }
            app = c.getApplicationContext();
            since = System.currentTimeMillis();
            tries = 0;
            lastWadbAt = 0;
            notified = false;
            notifiedAt = 0;
            thread = new HandlerThread("linkmeter-bootassist");
            thread.start();
            handler = new Handler(thread.getLooper());
            try {
                callback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network n) {
                        synchronized (LOCK) {
                            tries = 0; // a new Wi-Fi connection: Android has switched wireless debugging off, so it may be switched on again
                            lastWadbAt = 0;
                        }
                        schedule(1500);
                    }
                };
                app.getSystemService(ConnectivityManager.class).registerNetworkCallback(
                        new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback, handler);
            } catch (RuntimeException e) {
                callback = null; // no Wi-Fi events: the first look below still happens
            }
            try {
                binderListener = new Shizuku.OnBinderReceivedListener() {
                    @Override
                    public void onBinderReceived() {
                        schedule(500);
                    }
                };
                Shizuku.addBinderReceivedListener(binderListener);
            } catch (Throwable t) {
                binderListener = null; // Shizuku's classes are not usable here: Wi-Fi events and the first look still work
            }
            schedule(0);
        }
    }

    static void stop() {
        synchronized (LOCK) {
            stopLocked();
        }
    }

    private static void stopLocked() {
        Handler h = handler;
        handler = null;
        if (h != null) {
            h.removeCallbacksAndMessages(null);
        }
        if (callback != null && app != null) {
            try {
                app.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(callback);
            } catch (RuntimeException ignored) {
                // was not registered
            }
        }
        callback = null;
        if (binderListener != null) {
            try {
                Shizuku.removeBinderReceivedListener(binderListener);
            } catch (Throwable ignored) {
                // nothing to remove
            }
        }
        binderListener = null;
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }

    private static void schedule(long delayMs) {
        Handler h;
        synchronized (LOCK) {
            h = handler;
        }
        if (h != null) {
            h.removeCallbacks(EVALUATE);
            h.postDelayed(EVALUATE, delayMs);
        }
    }

    private static final Runnable EVALUATE = new Runnable() {
        @Override
        public void run() {
            evaluate();
        }
    };

    private static void evaluate() {
        final Context c;
        BootPlan.In in = new BootPlan.In();
        synchronized (LOCK) {
            if (handler == null || app == null) {
                return;
            }
            c = app;
            in.listenSince = since;
            in.wadbTries = tries;
            in.lastWadbAt = lastWadbAt;
            in.notified = notified;
            in.notifiedAt = notifiedAt;
        }
        in.assist = Boot.assist(c);
        in.priv = Priv.state(); // the same numbers as BootPlan.PRIV_*
        in.wifi = Boot.wifiUp(c);
        in.canWriteSecure = Boot.canWriteSecure(c);
        in.wadbOn = Boot.wadbOn(c);
        in.devOptionsOn = Boot.devOptionsOn(c);
        in.now = System.currentTimeMillis();
        BootPlan.Out out = BootPlan.decide(in);
        if (out.cancelNotice) {
            Boot.cancelNotice(c);
            synchronized (LOCK) {
                notified = false;
            }
        }
        if (out.post != BootPlan.Notice.NONE) {
            Boot.notifyShizuku(c, out.post);
            synchronized (LOCK) {
                notified = true;
                notifiedAt = in.now;
            }
        }
        if (out.enableWadb) {
            synchronized (LOCK) {
                tries++;
                lastWadbAt = in.now;
            }
            Boot.enableWadb(c);
            schedule(RECHECK_MS); // did it take? (Android may have been waiting for the Wi-Fi to settle)
        }
        if (out.stopListening) {
            stop();
        }
    }
}
