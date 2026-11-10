package com.tj.linkmeter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Phone only. The Bluetooth door (docs/REMOTE_LINK.md): while the phone's service runs, it listens on a Bluetooth serial socket, so
 * the paired tablet can still ask for the small things (status, hotspot on, Wi-Fi on or off, sleep, standby) when the hotspot is
 * OFF and the usual route does not exist. What a connection may do, and who may use it, is decided by {@link LinkServer},
 * {@link LinkAuth} and {@link RemoteRoutes}, which are tested on a computer; this class is only the Android part.
 *
 * Battery: a listening socket costs nothing by itself (Bluetooth must be on; an idle Bluetooth radio draws well under a
 * milliamp). There are no timers except while a connection is open. A connection holds a wake lock from the moment it is accepted
 * until its greeting is out, and then only while one of its requests is being answered.
 *
 * Threads: the door belongs to the service. {@link #start} and {@link #stop} are the service's; everybody else (the phone's own
 * screen, the setup) calls {@link #recheck}. None of the three blocks its caller: opening and closing the door is done on one
 * background thread, in the order asked, because Android's Bluetooth calls can take a while and the callers are the screen's
 * thread.
 */
final class BtServer {
    /** LinkMeter's own service number. The tablet asks the phone's Bluetooth for exactly this. */
    static final UUID SERVICE = UUID.fromString("f3b0a6c2-5d1e-4c7a-9b8f-2a6d4c1e7b90");
    private static final String NAME = "LinkMeter";
    /** At most this many connections at once; one more closes another (a stuck caller must not keep the tablet out). */
    private static final int MAX_OPEN = 2;
    /** A sleeping phone is kept awake this long after a connection arrives, so its greeting gets out (it is sent within a moment). */
    private static final long GREETING_AWAKE_MS = 5_000L;
    /** A sleeping phone must finish what it was asked: awake for at most this long per request, less when the answer is out sooner. */
    private static final long REQUEST_AWAKE_MS = 120_000L;

    /** One open connection. */
    private static final class Conn {
        final BluetoothSocket socket;
        /** A request signed by a paired tablet has arrived on it (a stranger's connection never gets this far). */
        volatile boolean proven;

        Conn(BluetoothSocket socket) {
            this.socket = socket;
        }
    }

    private static final Object LOCK = new Object();
    private static Context app;
    private static boolean wanted;
    private static BluetoothServerSocket server;
    private static BroadcastReceiver receiver;
    /** The open connections, the oldest first. */
    private static final List<Conn> OPEN = new ArrayList<>();
    private static ScheduledThreadPoolExecutor timer;
    private static int quickEnds;

    /** Opens and closes the door, one job after the other. No thread exists while there is nothing to do. */
    private static final ThreadPoolExecutor DOOR = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), r -> {
        Thread t = new Thread(r, "linkmeter-bt-door");
        t.setDaemon(true);
        return t;
    });

    static {
        DOOR.allowCoreThreadTimeOut(true);
    }

    /** For the status request and the phone's own screen: on, off (Bluetooth is off), perm, disabled, none, error. */
    private static volatile String state = "none";
    private static volatile String note = "";
    /** When a paired tablet last asked something over Bluetooth (clock time), 0 = not since the service started. */
    private static volatile long lastAt;
    /** When {@link #poke} last tried to listen again (time since the phone started), 0 = never. */
    private static long pokedAt;

    private BtServer() {}

    static boolean hasPermission(Context c) {
        return Build.VERSION.SDK_INT < 31
                || c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    static BluetoothAdapter adapter(Context c) {
        try {
            BluetoothManager bm = (BluetoothManager) c.getApplicationContext().getSystemService(Context.BLUETOOTH_SERVICE);
            return bm == null ? null : bm.getAdapter();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String state() {
        return state;
    }

    /** The state in plain words, for the phone's own screen and for the answer of the setup. */
    static String stateText() {
        switch (state) {
            case "on":
                return "on: this phone is listening for the paired tablet";
            case "off":
                return "Bluetooth is switched off on this phone";
            case "perm":
                return "LinkMeter is not allowed to use Bluetooth yet (the \"Nearby devices\" permission)";
            case "disabled":
                return "switched off in LinkMeter on this phone";
            case "error":
                return "could not start listening" + (note.isEmpty() ? "" : " (" + note + ")");
            default:
                return "this phone has no Bluetooth, or the service is not running";
        }
    }

    /** For the phone's own screen: when a paired tablet last asked something over Bluetooth, or "" if none has since the service started. */
    static String lastAskedText() {
        long at = lastAt;
        return at == 0 ? "" : "last asked over Bluetooth at " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(at));
    }

    private static volatile String cachedName = "";
    private static volatile long nameAt;

    /** This phone's Bluetooth name, as other devices see it, or "". Asked of Android at most every five minutes (it is in every status reply). */
    @SuppressLint("MissingPermission")
    static String name(Context c) {
        long now = SystemClock.elapsedRealtime();
        if (nameAt != 0 && now - nameAt < 5 * 60_000L) {
            return cachedName;
        }
        String n = "";
        try {
            BluetoothAdapter a = adapter(c);
            if (a != null && hasPermission(c)) {
                String got = a.getName();
                n = got == null ? "" : got;
            }
        } catch (RuntimeException e) {
            n = "";
        }
        cachedName = n;
        nameAt = now;
        return n;
    }

    /** Hands a job to the door's thread. A job that throws must not end the app: the door then says "error". */
    private static void door(final Runnable job) {
        DOOR.execute(() -> {
            try {
                job.run();
            } catch (RuntimeException e) {
                state = "error";
                note = String.valueOf(e.getMessage());
            }
        });
    }

    /**
     * The service has started: from now on listen whenever everything allows it. For the service only. Called from anywhere
     * else while the service is not running it does nothing, so no listener can be left behind that {@link #stop} never closes.
     */
    static void start(Context c) {
        final Context a = c.getApplicationContext();
        door(() -> {
            synchronized (LOCK) {
                if (!SenderService.active) {
                    return;
                }
                app = a;
                wanted = true;
                quickEnds = 0;
                registerLocked();
                listenLocked();
            }
        });
    }

    /** Bluetooth going off and coming back: stop listening, listen again. */
    private static void registerLocked() {
        if (receiver != null) {
            return;
        }
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                int st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                if (st == BluetoothAdapter.STATE_ON || st == BluetoothAdapter.STATE_OFF || st == BluetoothAdapter.STATE_TURNING_OFF) {
                    final boolean gone = st != BluetoothAdapter.STATE_ON;
                    // Android calls this on the screen's thread: the Bluetooth calls go to the door's
                    door(() -> {
                        synchronized (LOCK) {
                            if (gone) {
                                closeServerLocked();
                            }
                            listenLocked();
                        }
                    });
                }
            }
        };
        try {
            IntentFilter f = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED); // the system's own broadcasts still arrive
            } else {
                app.registerReceiver(receiver, f);
            }
        } catch (RuntimeException e) {
            receiver = null; // no state news: listening still starts now, and poke() tries again when the tablet asks for the status
        }
    }

    /** The service is stopping: no listener, no connections. */
    static void stop() {
        door(() -> {
            synchronized (LOCK) {
                wanted = false;
                if (receiver != null && app != null) {
                    try {
                        app.unregisterReceiver(receiver);
                    } catch (RuntimeException ignored) {
                        // was not registered
                    }
                }
                receiver = null;
                closeServerLocked();
                closeAllLocked();
                if (timer != null) {
                    timer.shutdownNow();
                    timer = null;
                }
                state = "none";
            }
        });
    }

    /**
     * Something the door depends on has changed (the switch on the phone's screen was moved, the permission was given): listen,
     * or stop listening and hang up, as things are now. Does nothing unless the service is running. Never blocks the caller.
     */
    static void recheck() {
        door(BtServer::listenNow);
    }

    private static void listenNow() {
        synchronized (LOCK) {
            listenLocked();
        }
    }

    /**
     * Called whenever the tablet asks for the status, which it does every few seconds while its app is open. If the door should
     * be open and is not (the permission was given in Android's settings since, the first try failed, Bluetooth came back
     * unnoticed), it is tried again: at most every half minute, and never on the caller's thread. So a phone that looks healthy
     * over the hotspot mends its Bluetooth door while the hotspot is still there to see it by.
     */
    static synchronized void poke() {
        long now = SystemClock.elapsedRealtime();
        if (RemoteRoutes.listenAgain(state, pokedAt == 0 ? Long.MAX_VALUE : now - pokedAt)) {
            pokedAt = now;
            recheck();
        }
    }

    @SuppressLint("MissingPermission")
    private static void listenLocked() {
        if (!wanted || app == null) {
            return;
        }
        if (!Cfg.btLink(app)) {
            // the user switched the link off on the phone: that also ends the connections that are open, or a tablet that is
            // connected would go on commanding the phone
            closeServerLocked();
            closeAllLocked();
            state = "disabled";
            return;
        }
        BluetoothAdapter a = adapter(app);
        if (a == null) {
            state = "none";
            return;
        }
        if (!hasPermission(app)) {
            state = "perm";
            return;
        }
        try {
            if (!a.isEnabled()) {
                closeServerLocked();
                state = "off";
                return;
            }
            if (server != null) {
                state = "on";
                return;
            }
            // "Insecure" = Android does not insist that the two devices were paired in its settings first. The connection is
            // still encrypted, and every request must be signed with the paired tablet's secret (LinkAuth).
            final BluetoothServerSocket ss = a.listenUsingInsecureRfcommWithServiceRecord(NAME, SERVICE);
            server = ss;
            state = "on";
            note = "";
            nameAt = 0; // the name may have been unreadable until now (no permission, Bluetooth off): ask again
            Thread t = new Thread(() -> acceptLoop(ss), "linkmeter-bt-accept");
            t.setDaemon(true);
            t.start();
        } catch (IOException | RuntimeException e) {
            state = "error";
            note = String.valueOf(e.getMessage());
        }
    }

    private static void closeServerLocked() {
        BluetoothServerSocket ss = server;
        server = null;
        if (ss != null) {
            try {
                ss.close(); // wakes the accept thread, which then ends
            } catch (IOException | RuntimeException ignored) {
                // already closed
            }
        }
    }

    /** Hangs up on everybody. Each connection's own thread then ends and lets go of its wake lock. */
    private static void closeAllLocked() {
        for (Conn c : OPEN) {
            closeQuietly(c.socket);
        }
        OPEN.clear();
    }

    private static void acceptLoop(BluetoothServerSocket ss) {
        long startedAt = SystemClock.elapsedRealtime();
        boolean any = false;
        while (true) {
            final BluetoothSocket s;
            try {
                s = ss.accept();
            } catch (IOException | RuntimeException e) {
                break; // closed: Bluetooth went off, or the service is stopping
            }
            if (s == null) {
                break;
            }
            any = true;
            final Conn conn = new Conn(s);
            final Context c = app; // set before this thread was started
            // The phone may have been asleep and only the Bluetooth chip woke it: stay awake until the greeting is out, or the
            // tablet waits for it in vain. serve() lets go as soon as it is; the hold runs out by itself anyway.
            Awake.linkBusy(c, conn, GREETING_AWAKE_MS);
            synchronized (LOCK) {
                if (!wanted) {
                    closeQuietly(s);
                    Awake.linkDone(conn);
                    break;
                }
                while (OPEN.size() >= MAX_OPEN) {
                    boolean[] proven = new boolean[OPEN.size()];
                    for (int i = 0; i < proven.length; i++) {
                        proven[i] = OPEN.get(i).proven;
                    }
                    closeQuietly(OPEN.remove(RemoteRoutes.givesWay(proven)).socket);
                }
                OPEN.add(conn);
            }
            Thread t = new Thread(() -> serve(c, conn), "linkmeter-bt");
            t.setDaemon(true);
            t.start();
        }
        synchronized (LOCK) {
            if (server == ss) {
                // the listener ended although nobody closed it: listen again if Bluetooth is still there, but never in a tight
                // loop (a listener that ends at once, three times in a row, is left alone until the next start or poke)
                server = null;
                quickEnds = !any && SystemClock.elapsedRealtime() - startedAt < 3000 ? quickEnds + 1 : 0;
                if (quickEnds >= 3) {
                    state = "error";
                    note = "the listener keeps ending";
                } else {
                    listenLocked();
                }
            }
        }
    }

    private static ScheduledThreadPoolExecutor timer() {
        synchronized (LOCK) {
            if (timer == null) {
                timer = new ScheduledThreadPoolExecutor(1);
                timer.setRemoveOnCancelPolicy(true);
                timer.setKeepAliveTime(30, TimeUnit.SECONDS);
                timer.allowCoreThreadTimeOut(true); // no thread at all while no connection is open
            }
            return timer;
        }
    }

    private static void serve(final Context c, final Conn conn) {
        final BluetoothSocket s = conn.socket;
        final ScheduledFuture<?>[] pending = new ScheduledFuture<?>[1];
        final Runnable hangUp = () -> closeQuietly(s);
        LinkServer.Watch watch = new LinkServer.Watch() {
            @Override
            public void waiting(long ms) {
                Awake.linkDone(conn); // the greeting, or the answer, is out: this connection needs the processor no longer
                synchronized (pending) {
                    if (pending[0] != null) {
                        pending[0].cancel(false);
                    }
                    try {
                        pending[0] = timer().schedule(hangUp, ms, TimeUnit.MILLISECONDS);
                    } catch (RuntimeException e) {
                        pending[0] = null; // the service is stopping; stop() closes the connection
                    }
                }
            }

            @Override
            public void working() {
                synchronized (pending) {
                    if (pending[0] != null) {
                        pending[0].cancel(false);
                        pending[0] = null;
                    }
                }
                Awake.linkBusy(c, conn, REQUEST_AWAKE_MS);
            }
        };
        try {
            LinkServer.serve(s.getInputStream(), s.getOutputStream(), LinkAuth.newNonce(), () -> Cfg.trusted(c), (r, out) -> {
                conn.proven = true; // only a request signed by a paired tablet gets here
                lastAt = System.currentTimeMillis();
                Api.handle(c, r, out);
            }, watch);
        } catch (IOException | RuntimeException ignored) {
            // the connection could not even be opened properly
        } finally {
            Awake.linkDone(conn);
            synchronized (pending) {
                if (pending[0] != null) {
                    pending[0].cancel(false);
                }
            }
            closeQuietly(s);
            synchronized (LOCK) {
                OPEN.remove(conn);
            }
        }
    }

    private static void closeQuietly(BluetoothSocket s) {
        try {
            s.close();
        } catch (IOException | RuntimeException ignored) {
            // already closed
        }
    }

    // ------------------------------------------------------------------------------------------- the one-time setup

    /**
     * Makes the link ready from the tablet, without touching the phone (needs Shizuku now): gives LinkMeter the Bluetooth
     * permission, switches Bluetooth on if it is off, reads this phone's Bluetooth address (an app may not read its own; the
     * shell may) so the tablet can be told where to call, and listens. Says in plain words what worked. If the phone is not
     * listening at the end, the whole report comes back as an error: the link was not made ready.
     */
    static String setup(Context c) throws IOException {
        if (!Cfg.btLink(c)) {
            // the user's own switch on the phone says no: give no permission, switch nothing on
            throw new IOException("The Bluetooth link is switched off on the phone, so nothing was changed. To use it, open LinkMeter "
                    + "on the phone and switch on \"Let the tablet reach this phone over Bluetooth\".");
        }
        if (adapter(c) == null) {
            throw new IOException("This phone has no Bluetooth.");
        }
        if (!Priv.ready()) {
            // every step below goes through Shizuku; say so in a whole sentence, the tablet shows these words as they are
            throw new IOException("The phone cannot get its Bluetooth side ready: " + Priv.stateText(Priv.state()) + " on the phone.");
        }
        StringBuilder sb = new StringBuilder();
        if (!hasPermission(c)) {
            Priv.run("pm grant " + c.getPackageName() + " " + Manifest.permission.BLUETOOTH_CONNECT, 20);
            nameAt = 0; // the phone's Bluetooth name could not be read without the permission: ask again, the tablet wants it next
            sb.append(hasPermission(c) ? "LinkMeter may now use Bluetooth on the phone.\n"
                    : "The phone did not give the Bluetooth permission. On the phone: Settings > Apps > LinkMeter > Permissions > "
                    + "Nearby devices > Allow.\n");
        }
        if (!enabled(c)) {
            Priv.run("svc bluetooth enable 2>&1 || cmd bluetooth_manager enable 2>&1", 20);
            for (int i = 0; i < 20 && !enabled(c); i++) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            sb.append(enabled(c) ? "Bluetooth was switched on.\n" : "Bluetooth could not be switched on: switch it on at the phone.\n");
        }
        // Never held in a shell variable: two plain commands, a few short lines each. Which of the lines is the phone's OWN
        // address is decided by BtAddr.own, not by the grep.
        Priv.Result r = Priv.run("settings get secure bluetooth_address 2>&1; dumpsys bluetooth_manager 2>/dev/null | grep -i -m 12 'address:'", 20);
        String mac = BtAddr.own(r.out);
        if (!mac.isEmpty()) {
            Cfg.setBtMac(c, mac);
            sb.append("The phone's Bluetooth address was read, so the tablet knows where to call.\n");
        } else if (Cfg.btMac(c).isEmpty()) {
            sb.append("The phone would not tell its Bluetooth address. Pair the phone and the tablet once in Android's own Bluetooth "
                    + "settings (on both: Settings > Bluetooth), then press \"Find the phone\" on the tablet.\n");
        }
        listenNow(); // on this thread, so the next line reports what is true now (does nothing unless the service is running)
        sb.append("Bluetooth link on the phone: ").append(stateText()).append('.');
        if (!"on".equals(state)) {
            throw new IOException(sb.toString());
        }
        return sb.toString();
    }

    @SuppressLint("MissingPermission")
    static boolean enabled(Context c) {
        try {
            BluetoothAdapter a = adapter(c);
            return a != null && a.isEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
