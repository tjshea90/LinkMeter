package com.tj.linkmeter;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.SystemClock;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Tablet only. The tablet's end of the Bluetooth link (docs/REMOTE_LINK.md): calls the phone's Bluetooth service, keeps the one
 * connection for the next request, and closes it when nothing was asked for a while or the app leaves the screen. What is said
 * on the connection is {@link LinkClient}'s business (signed requests; the secret is never sent).
 *
 * A Bluetooth socket has no timeout of its own, so every wait here is ended by closing the socket from a timer.
 */
final class BtLink {
    /** A connection that was idle longer than this is not used again (the phone drops silent callers after 90 s). */
    private static final long REUSE_MS = 25_000L;
    private static final long CONNECT_MS = 14_000L;
    private static final long GREETING_MS = 6_000L;

    private static final Object LOCK = new Object();
    private static BluetoothSocket sock;
    private static LinkClient client;
    private static long lastUsed;
    private static ScheduledThreadPoolExecutor timer;
    private static ScheduledFuture<?> idleClose;

    private BtLink() {}

    /** May the tablet use the link? (A switch in the tablet's Settings; on by default.) */
    static boolean enabled(Context c) {
        return Cfg.getBool(c, "btUse", true);
    }

    static void setEnabled(Context c, boolean on) {
        Cfg.setBool(c, "btUse", on);
        if (!on) {
            close();
        }
    }

    /** Is there anything to try: the link is switched on here, this tablet has Bluetooth, and the phone's address is known? */
    static boolean ready(Context c) {
        return enabled(c) && BtServer.adapter(c) != null && BtServer.hasPermission(c) && BtAddr.valid(Cfg.btMac(c));
    }

    /** Why the link cannot be used, in words for the Settings card; "" when it can. */
    @SuppressLint("MissingPermission")
    static String problem(Context c) {
        if (!enabled(c)) {
            return "The Bluetooth link is switched off here.";
        }
        BluetoothAdapter a = BtServer.adapter(c);
        if (a == null) {
            return "This tablet has no Bluetooth.";
        }
        if (!BtServer.hasPermission(c)) {
            return "LinkMeter is not allowed to use Bluetooth on this tablet yet.";
        }
        if (!BtAddr.valid(Cfg.btMac(c))) {
            return "The phone's Bluetooth address is not known yet: press Set up while the tablet is on the phone's hotspot.";
        }
        try {
            if (!a.isEnabled()) {
                return "Bluetooth is switched off on this tablet (LinkMeter switches it on when it needs it).";
            }
        } catch (RuntimeException e) {
            return "Bluetooth cannot be used on this tablet: " + e.getMessage();
        }
        return "";
    }

    private static ScheduledThreadPoolExecutor timer() {
        synchronized (LOCK) {
            if (timer == null) {
                timer = new ScheduledThreadPoolExecutor(1);
                timer.setRemoveOnCancelPolicy(true);
                timer.setKeepAliveTime(20, TimeUnit.SECONDS);
                timer.allowCoreThreadTimeOut(true); // no thread while the link is not in use
            }
            return timer;
        }
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException | RuntimeException ignored) {
                // already closed
            }
        }
    }

    /** Closes the connection now (the app left the screen, or the link was switched off). */
    static void close() {
        BluetoothSocket s;
        synchronized (LOCK) {
            s = sock;
            sock = null;
            client = null;
            if (idleClose != null) {
                idleClose.cancel(false);
                idleClose = null;
            }
        }
        closeQuietly(s);
    }

    /** Switches the tablet's Bluetooth on if it is off (Android 10 lets an app do that) and waits for it. */
    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private static void ensureOn(BluetoothAdapter a) throws IOException {
        if (a.isEnabled()) {
            return;
        }
        boolean asked;
        try {
            asked = a.enable();
        } catch (RuntimeException e) {
            asked = false;
        }
        if (!asked) {
            throw new IOException("Bluetooth is off on this tablet. Switch it on (swipe down from the top of the screen).");
        }
        long end = SystemClock.elapsedRealtime() + 9000;
        while (!a.isEnabled()) {
            if (SystemClock.elapsedRealtime() > end) {
                throw new IOException("Bluetooth did not switch on in time on this tablet. Try again.");
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        }
    }

    /** Opens a connection to the device with this address and reads the phone's greeting. The caller holds no lock on the result yet. */
    @SuppressLint("MissingPermission")
    private static Object[] open(Context c, String mac) throws IOException {
        BluetoothAdapter a = BtServer.adapter(c);
        if (a == null) {
            throw new IOException("This tablet has no Bluetooth.");
        }
        if (!BtServer.hasPermission(c)) {
            throw new IOException("LinkMeter is not allowed to use Bluetooth on this tablet.");
        }
        final BluetoothSocket s;
        try {
            ensureOn(a);
            BluetoothDevice d = a.getRemoteDevice(mac);
            try {
                a.cancelDiscovery(); // a search for devices in progress makes connecting slow or fail
            } catch (RuntimeException ignored) {
                // not searching, or not allowed to say: connect anyway
            }
            // "Insecure" = no pairing in Android's settings is demanded. The link is still encrypted and every request is signed.
            s = d.createInsecureRfcommSocketToServiceRecord(BtServer.SERVICE);
        } catch (RuntimeException e) {
            throw new IOException("Bluetooth cannot be used: " + e.getMessage());
        }
        ScheduledFuture<?> guard = timer().schedule(() -> closeQuietly(s), CONNECT_MS, TimeUnit.MILLISECONDS);
        try {
            s.connect();
        } catch (IOException | RuntimeException e) {
            closeQuietly(s);
            throw new IOException("the phone did not answer the Bluetooth call (is it in range, with Bluetooth on and LinkMeter running?)");
        } finally {
            guard.cancel(false);
        }
        guard = timer().schedule(() -> closeQuietly(s), GREETING_MS, TimeUnit.MILLISECONDS);
        try {
            LinkClient lc = new LinkClient(s.getInputStream(), s.getOutputStream(), Cfg.token(c));
            return new Object[] {s, lc};
        } catch (IOException | RuntimeException e) {
            closeQuietly(s);
            throw new IOException("a device answered the Bluetooth call, but not as LinkMeter on the phone (" + e.getMessage() + ")");
        } finally {
            guard.cancel(false);
        }
    }

    /**
     * One request to the phone over Bluetooth. Calls are made one after another (there is one connection). A request that is only a
     * question (GET) is asked once more on a fresh connection if an old connection turned out to be dead; anything else is sent once.
     */
    static Client.Resp call(Context c, String method, String target, int readMs) throws IOException {
        synchronized (BtLink.class) {
            return callLocked(c.getApplicationContext(), method, target, readMs);
        }
    }

    private static Client.Resp callLocked(Context c, String method, String target, int readMs) throws IOException {
        String mac = Cfg.btMac(c);
        if (!BtAddr.valid(mac)) {
            throw new IOException("the phone's Bluetooth address is not known yet (Settings > Bluetooth link > Set up)");
        }
        for (int attempt = 0; ; attempt++) {
            BluetoothSocket s;
            LinkClient lc;
            boolean fresh;
            synchronized (LOCK) {
                if (idleClose != null) {
                    idleClose.cancel(false);
                    idleClose = null;
                }
                if (sock != null && SystemClock.elapsedRealtime() - lastUsed > REUSE_MS) {
                    closeQuietly(sock);
                    sock = null;
                    client = null;
                }
                s = sock;
                lc = client;
            }
            fresh = s == null;
            if (fresh) {
                Object[] o = open(c, mac); // nothing was sent if this throws
                s = (BluetoothSocket) o[0];
                lc = (LinkClient) o[1];
                synchronized (LOCK) {
                    sock = s;
                    client = lc;
                }
            }
            final BluetoothSocket fs = s;
            ScheduledFuture<?> guard = timer().schedule(() -> closeQuietly(fs), readMs + 3000L, TimeUnit.MILLISECONDS);
            try {
                Client.Resp r = lc.call(method, target);
                synchronized (LOCK) {
                    lastUsed = SystemClock.elapsedRealtime();
                    // do not hold the phone's radio in a connection nobody uses
                    idleClose = timer().schedule(BtLink::close, REUSE_MS + 5000, TimeUnit.MILLISECONDS);
                }
                return r;
            } catch (IOException | RuntimeException e) {
                synchronized (LOCK) {
                    if (sock == s) {
                        sock = null;
                        client = null;
                    }
                }
                closeQuietly(s);
                if (!fresh && attempt == 0 && "GET".equals(method)) {
                    continue; // an old connection that had died: a question can safely be asked again
                }
                throw new IOException(fresh ? "the phone did not answer over Bluetooth in time"
                        : "the Bluetooth connection was lost while asking (the phone may or may not have done it: look at the status)");
            } finally {
                guard.cancel(false);
            }
        }
    }

    /**
     * For a phone that would not tell its address: tries every device this tablet is paired with (Android's Bluetooth settings)
     * and keeps the one that answers as LinkMeter and accepts this tablet's signature. Returns what happened, in words.
     */
    @SuppressLint("MissingPermission")
    static String findAmongPaired(Context c, String preferName) {
        Context app = c.getApplicationContext();
        BluetoothAdapter a = BtServer.adapter(app);
        if (a == null) {
            return "This tablet has no Bluetooth.";
        }
        if (!BtServer.hasPermission(app)) {
            return "LinkMeter is not allowed to use Bluetooth on this tablet.";
        }
        List<BluetoothDevice> order = new ArrayList<>();
        try {
            ensureOn(a);
            Set<BluetoothDevice> bonded = a.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    String n = d.getName();
                    if (n != null && !preferName.isEmpty() && n.equals(preferName)) {
                        order.add(0, d); // the phone told its Bluetooth name over the hotspot: try that one first
                    } else {
                        order.add(d);
                    }
                }
            }
        } catch (IOException e) {
            return e.getMessage();
        } catch (RuntimeException e) {
            return "Bluetooth cannot be used: " + e.getMessage();
        }
        if (order.isEmpty()) {
            return "This tablet is not paired with any Bluetooth device. Pair it with the phone first: on both devices open "
                    + "Settings > Bluetooth, and tap the other device's name.";
        }
        close();
        StringBuilder tried = new StringBuilder();
        synchronized (BtLink.class) {
            for (BluetoothDevice d : order) {
                String mac = d.getAddress();
                String label = d.getName() == null ? mac : d.getName();
                try {
                    Object[] o = open(app, mac);
                    BluetoothSocket s = (BluetoothSocket) o[0];
                    try {
                        ScheduledFuture<?> guard = timer().schedule(() -> closeQuietly(s), 8000, TimeUnit.MILLISECONDS);
                        try {
                            Client.Resp r = ((LinkClient) o[1]).call("GET", "/s");
                            if (r.code == 200) {
                                Cfg.setBtMac(app, mac);
                                return "Found the phone: " + label + ". The Bluetooth link is ready.";
                            }
                            tried.append(label).append(r.code == 401 ? " (LinkMeter is there, but this tablet is not paired with it in LinkMeter); "
                                    : " (answered " + r.code + "); ");
                        } finally {
                            guard.cancel(false);
                        }
                    } finally {
                        closeQuietly(s);
                    }
                } catch (IOException e) {
                    tried.append(label).append(" (no LinkMeter); ");
                }
            }
        }
        return "None of the paired devices answered as LinkMeter on the phone. Tried: " + tried.toString().trim()
                + " Is LinkMeter running on the phone, with Bluetooth on?";
    }
}
