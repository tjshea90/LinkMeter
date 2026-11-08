package com.tj.linkmeter;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Phone only: measures the phone's own mobile-data speed. The tablet starts it and
 * watches the progress. It uses Cloudflare's public speed-test endpoints and sends
 * the traffic over the cellular network even if the phone is also on Wi-Fi.
 * Nothing runs unless a test is started, and it stops by itself after about 20 s.
 */
final class SpeedTest {
    static final int IDLE = 0;
    static final int PING = 1;
    static final int DOWN = 2;
    static final int UP = 3;
    static final int DONE = 4;
    static final int ERROR = 5;

    private static final String BASE = "https://speed.cloudflare.com/";
    private static final long PHASE_MS = 8000;
    private static final long WARMUP_MS = 1500;

    private static final Object LOCK = new Object();
    private static final AtomicLong BYTES = new AtomicLong();
    private static final ArrayList<Float> VALS = new ArrayList<>();
    private static final ArrayList<Integer> PHASES = new ArrayList<>();

    private static volatile int phase = IDLE;
    private static volatile boolean cancel;
    private static volatile double pingMs;
    private static volatile double downMbps;
    private static volatile double upMbps;
    private static volatile double live;
    private static volatile long usedBytes;
    private static volatile String error = "";
    private static volatile String via = "";

    private SpeedTest() {}

    static boolean running() {
        int p = phase;
        return p == PING || p == DOWN || p == UP;
    }

    static void start(Context c) {
        synchronized (LOCK) {
            if (running()) {
                return;
            }
            VALS.clear();
            PHASES.clear();
            pingMs = 0;
            downMbps = 0;
            upMbps = 0;
            live = 0;
            usedBytes = 0;
            error = "";
            via = "";
            cancel = false;
            phase = PING;
        }
        final Context app = c.getApplicationContext();
        new Thread(() -> runAll(app), "linkmeter-speed").start();
    }

    static void stop() {
        cancel = true;
    }

    private static void runAll(Context c) {
        try {
            Network net = cellular(c);
            String route = net != null ? "mobile network" : "default network";
            double p;
            try {
                p = ping(net);
            } catch (IOException first) {
                if (net == null) {
                    throw new IOException("ping: " + describe(first));
                }
                // The mobile-network handle did not work. On a hotspot phone the default network
                // is the mobile network anyway, so fall back to it and say so.
                net = null;
                route = "default network (the mobile-network handle failed: " + describe(first) + ")";
                try {
                    p = ping(null);
                } catch (IOException second) {
                    throw new IOException("ping: " + describe(second));
                }
            }
            pingMs = p;
            via = route;
            if (!cancel) {
                try {
                    downMbps = transfer(net, true);
                } catch (IOException e) {
                    throw new IOException("download: " + describe(e));
                }
            }
            if (!cancel) {
                try {
                    upMbps = transfer(net, false);
                } catch (IOException e) {
                    throw new IOException("upload: " + describe(e));
                }
            }
            phase = cancel ? IDLE : DONE;
        } catch (IOException e) {
            error = String.valueOf(e.getMessage());
            phase = ERROR;
        }
        live = 0;
    }

    /** "SocketTimeoutException: Read timed out": the class name matters when the message is vague. */
    private static String describe(Throwable e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    /** The mobile-data network, so the test measures it even if Wi-Fi is also connected. */
    private static Network cellular(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network active = cm.getActiveNetwork();
            NetworkCapabilities anc = active == null ? null : cm.getNetworkCapabilities(active);
            if (anc != null && anc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    && anc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return active;
            }
            // Otherwise any ordinary mobile network. Restricted ones (carrier tethering,
            // IMS, MMS) are not usable by an app and made the test time out.
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                        && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    return n;
                }
            }
        } catch (RuntimeException ignored) {
            // fall back to the default network
        }
        return null;
    }

    private static HttpURLConnection open(Network net, String url, boolean smallBuffers)
            throws IOException {
        URL u = new URL(url);
        HttpURLConnection h = (HttpURLConnection) (net != null ? net.openConnection(u) : u.openConnection());
        h.setUseCaches(false);
        h.setConnectTimeout(5000);
        h.setReadTimeout(10000);
        if (smallBuffers && h instanceof HttpsURLConnection) {
            ((HttpsURLConnection) h).setSSLSocketFactory(
                    new SmallBufferFactory((SSLSocketFactory) SSLSocketFactory.getDefault()));
        }
        return h;
    }

    /**
     * Gives upload sockets a small send buffer. With the default (several megabytes) the first
     * seconds of an upload only fill that buffer, which looks like a fast start and then a crash
     * to almost zero, and the oversized queue can also stall a weak uplink. With a small buffer a
     * write blocks until the data has really gone out, so the numbers are the real speed.
     */
    private static final class SmallBufferFactory extends SSLSocketFactory {
        private final SSLSocketFactory d;

        SmallBufferFactory(SSLSocketFactory delegate) {
            d = delegate;
        }

        private static Socket tune(Socket s) {
            try {
                s.setSendBufferSize(64 * 1024);
            } catch (IOException ignored) {
                // keep the default buffer
            }
            return s;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return d.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return d.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return tune(d.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return tune(d.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress local, int localPort)
                throws IOException {
            return tune(d.createSocket(host, port, local, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return tune(d.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress local, int localPort)
                throws IOException {
            return tune(d.createSocket(address, port, local, localPort));
        }
    }

    /** Lowest round trip over several tiny requests on one kept-open connection. */
    private static double ping(Network net) throws IOException {
        double best = Double.MAX_VALUE;
        byte[] buf = new byte[512];
        for (int i = 0; i < 6 && !cancel; i++) {
            long t0 = SystemClock.elapsedRealtimeNanos();
            HttpURLConnection h = open(net, BASE + "__down?bytes=0", false);
            int code = h.getResponseCode();
            if (code != 200) {
                h.disconnect();
                throw new IOException("speed server replied " + code);
            }
            try (InputStream in = h.getInputStream()) {
                while (in.read(buf) >= 0) {
                    // drain so the connection can be reused
                }
            }
            double ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6;
            if (i > 0 && ms < best) {
                best = ms;
            }
        }
        return best == Double.MAX_VALUE ? 0 : best;
    }

    private static double transfer(final Network net, final boolean down) throws IOException {
        phase = down ? DOWN : UP;
        BYTES.set(0);
        final int n = down ? 4 : 2;
        final long t0 = SystemClock.elapsedRealtime();
        final long end = t0 + PHASE_MS;
        final String[] fail = new String[1];
        Thread[] ts = new Thread[n];
        for (int i = 0; i < n; i++) {
            ts[i] = new Thread(() -> {
                try {
                    if (down) {
                        pumpDown(net, end);
                    } else {
                        pumpUp(net, end);
                    }
                } catch (IOException e) {
                    synchronized (fail) {
                        if (fail[0] == null) {
                            fail[0] = describe(e);
                        }
                    }
                }
            }, "linkmeter-speed-io");
            ts[i].start();
        }

        long lastT = t0;
        long lastB = 0;
        long warmT = -1;
        long warmB = 0;
        double ema = 0;
        while (!cancel && SystemClock.elapsedRealtime() < end) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            long now = SystemClock.elapsedRealtime();
            long b = BYTES.get();
            double inst = (b - lastB) * 8.0 / ((now - lastT) / 1000.0) / 1e6;
            ema = ema == 0 ? inst : ema * 0.6 + inst * 0.4;
            lastT = now;
            lastB = b;
            live = ema;
            synchronized (LOCK) {
                VALS.add((float) ema);
                PHASES.add(phase);
            }
            if (warmT < 0 && now - t0 >= WARMUP_MS) {
                warmT = now;
                warmB = b;
            }
        }
        for (Thread t : ts) {
            try {
                t.join(4000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        long endT = SystemClock.elapsedRealtime();
        long endB = BYTES.get();
        usedBytes += endB;
        if (endB == 0 && fail[0] != null) {
            throw new IOException(fail[0]);
        }
        if (warmT >= 0 && endT - warmT > 500) {
            return (endB - warmB) * 8.0 / ((endT - warmT) / 1000.0) / 1e6;
        }
        return endB * 8.0 / (Math.max(1, endT - t0) / 1000.0) / 1e6;
    }

    private static boolean over(long end) {
        return cancel || SystemClock.elapsedRealtime() >= end;
    }

    private static void pumpDown(Network net, long end) throws IOException {
        byte[] buf = new byte[64 * 1024];
        while (!over(end)) {
            HttpURLConnection h = open(net, BASE + "__down?bytes=26214400", false);
            try {
                int code = h.getResponseCode();
                if (code != 200) {
                    throw new IOException("speed server replied " + code);
                }
                InputStream in = h.getInputStream();
                int r;
                while ((r = in.read(buf)) >= 0) {
                    BYTES.addAndGet(r);
                    if (over(end)) {
                        break;
                    }
                }
            } finally {
                h.disconnect();
            }
        }
    }

    private static void pumpUp(Network net, long end) throws IOException {
        byte[] buf = new byte[64 * 1024];
        new Random().nextBytes(buf);
        final int total = 8 * 1024 * 1024;
        while (!over(end)) {
            HttpURLConnection h = open(net, BASE + "__up", true);
            try {
                h.setDoOutput(true);
                h.setRequestMethod("POST");
                h.setFixedLengthStreamingMode(total);
                OutputStream os = h.getOutputStream();
                int sent = 0;
                while (sent < total) {
                    int c = Math.min(buf.length, total - sent);
                    os.write(buf, 0, c);
                    sent += c;
                    BYTES.addAndGet(c);
                    if (over(end)) {
                        break;
                    }
                }
                if (sent >= total) {
                    os.close();
                    h.getResponseCode();
                }
            } catch (IOException e) {
                if (over(end)) {
                    return; // the deadline cut the request short: that is expected
                }
                throw e;
            } finally {
                h.disconnect();
            }
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** Progress for the tablet. Samples before index `from` are left out. */
    static String json(int from) {
        JSONObject o = new JSONObject();
        try {
            o.put("phase", phase);
            o.put("live", round1(live));
            o.put("ping", round1(pingMs));
            o.put("down", round1(downMbps));
            o.put("up", round1(upMbps));
            o.put("mb", Math.round(usedBytes / 1e6));
            o.put("via", via);
            if (!error.isEmpty()) {
                o.put("error", error);
            }
            JSONArray s = new JSONArray();
            JSONArray sp = new JSONArray();
            synchronized (LOCK) {
                for (int i = Math.max(0, from); i < VALS.size(); i++) {
                    s.put(round1(VALS.get(i)));
                    sp.put((int) PHASES.get(i));
                }
                o.put("n", VALS.size());
            }
            o.put("s", s);
            o.put("sp", sp);
        } catch (JSONException ignored) {
            // numbers only; cannot happen
        }
        return o.toString();
    }
}
