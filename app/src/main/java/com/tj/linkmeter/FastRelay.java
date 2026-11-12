package com.tj.linkmeter;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DnsResolver;
import android.net.Network;
import android.os.CancellationSignal;
import android.os.PowerManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Phone only. Fast Wi-Fi's relay (docs/FAST_WIFI.md) inside the always-on service: {@link FastRelayCore} with the phone's own pieces.
 * Only the paired tablets' keys open it ({@link FastProto#key}); DNS questions are answered by Android's resolver on the phone's
 * default network (its DNS servers, its cache, Private DNS); a partial wake lock is held only while relayed traffic flows (the core
 * says when it starts and when it has been quiet for half a minute), so the phone still sleeps when the tablet is idle.
 *
 * Battery: nothing runs while nobody uses it: the relay's thread sleeps in the kernel until a packet comes.
 */
final class FastRelay {
    private FastRelay() {}

    private static FastRelayCore core;
    private static PowerManager.WakeLock wake;
    private static volatile String problem = "";
    private static Set<String> keysFor;
    private static List<byte[]> keys = Collections.emptyList();

    /** Started with the phone's service (if the user allows Fast Wi-Fi). A port that cannot be had is said in the status. */
    static synchronized void start(Context c) {
        final Context app = c.getApplicationContext();
        if (!Cfg.fastAllowed(app)) {
            problem = "";
            return;
        }
        if (core != null && core.running()) {
            return;
        }
        FastRelayCore k = new FastRelayCore(FastProto.PORT, () -> keys(app), (q, answer) -> dns(app, q, answer), new FastRelayCore.Dialer() {
            @Override
            public InetSocketAddress target(int addr, int port) {
                byte[] b = new byte[4];
                Ip4.w32(b, 0, addr);
                try {
                    return new InetSocketAddress(InetAddress.getByAddress(b), port);
                } catch (UnknownHostException e) {
                    throw new IllegalStateException(e); // four bytes are always an address
                }
            }
        }, on -> busy(app, on));
        try {
            k.start();
            core = k;
            problem = "";
        } catch (IOException e) {
            core = null;
            problem = "Fast Wi-Fi could not open port " + FastProto.PORT + ": " + e.getMessage();
        }
    }

    static synchronized void stop() {
        if (core != null) {
            core.stop();
            core = null;
        }
        problem = "";
    }

    /** The user's switch on the phone's own screen. */
    static void setAllowed(Context c, boolean on) {
        Cfg.setFastAllowed(c, on);
        if (on) {
            start(c);
        } else {
            stop();
        }
    }

    static synchronized boolean running() {
        return core != null && core.running();
    }

    /** The status's "fast" part: what a tablet needs to know (and what the phone's screen shows). */
    static synchronized JSONObject status(Context c) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("v", FastProto.VERSION);
        o.put("port", FastProto.PORT);
        o.put("allowed", Cfg.fastAllowed(c));
        FastRelayCore k = core;
        boolean on = k != null && k.running();
        o.put("on", on);
        if (!problem.isEmpty()) {
            o.put("err", problem);
        }
        if (k != null) {
            o.put("conns", k.pairs);
            o.put("flows", k.flowCount);
            o.put("up", k.bytesUp);
            o.put("down", k.bytesDown);
            if (k.lastTabletAt > 0) {
                o.put("seen", Math.max(0, System.currentTimeMillis() - k.lastTabletAt));
            }
        }
        return o;
    }

    /** The paired tablets' keys, worked out again only when the set of paired tablets changes. */
    private static synchronized List<byte[]> keys(Context c) {
        Set<String> now = Cfg.trusted(c);
        if (!now.equals(keysFor)) {
            List<byte[]> k = new ArrayList<>();
            for (String s : now) {
                k.add(FastProto.key(s));
            }
            keys = Collections.unmodifiableList(k);
            keysFor = now;
        }
        return keys;
    }

    /** A DNS question from the tablet, asked through the phone's own resolver; a failure answers SERVFAIL so nobody waits. */
    private static void dns(Context c, byte[] q, FastRelayCore.Answer answer) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm == null ? null : cm.getActiveNetwork();
            DnsResolver.getInstance().rawQuery(n, q, DnsResolver.FLAG_EMPTY, Runnable::run, new CancellationSignal(),
                    new DnsResolver.Callback<byte[]>() {
                        @Override
                        public void onAnswer(byte[] reply, int rcode) {
                            answer.done(reply != null && reply.length >= 12 ? reply : FastProto.servfail(q));
                        }

                        @Override
                        public void onError(DnsResolver.DnsException e) {
                            answer.done(FastProto.servfail(q));
                        }
                    });
        } catch (RuntimeException e) {
            answer.done(FastProto.servfail(q));
        }
    }

    /** Relayed traffic began, still goes on, or has been quiet: the CPU stays awake only for the first two. */
    private static synchronized void busy(Context c, boolean on) {
        try {
            if (wake == null) {
                PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LinkMeter:fastwifi");
                wake.setReferenceCounted(false);
            }
            if (on) {
                wake.acquire(15 * 60 * 1000L); // the core says "still busy" every minute; this only ends a lock nobody renews
            } else if (wake.isHeld()) {
                wake.release();
            }
        } catch (RuntimeException ignored) {
            // no wake lock: the relay still works, more slowly while the screen is off
        }
    }
}
