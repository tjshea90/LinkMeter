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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Phone only. The Bluetooth door (docs/REMOTE_LINK.md): while the phone's service runs, it listens on a Bluetooth serial socket, so
 * the paired tablet can still ask for the small things (status, hotspot on, Wi-Fi on or off, sleep, standby) when the hotspot is
 * OFF and the usual route does not exist. What a connection may do, and who may use it, is decided by {@link LinkServer},
 * {@link LinkAuth} and {@link RemoteRoutes}, which are tested on a computer; this class is only the Android part.
 *
 * Battery: a listening socket costs nothing by itself (Bluetooth must be on; an idle Bluetooth radio draws well under a
 * milliamp). There are no timers except while a connection is open. A request holds a wake lock only until it is answered.
 */
final class BtServer {
    /** LinkMeter's own service number. The tablet asks the phone's Bluetooth for exactly this. */
    static final UUID SERVICE = UUID.fromString("f3b0a6c2-5d1e-4c7a-9b8f-2a6d4c1e7b90");
    private static final String NAME = "LinkMeter";
    /** At most this many connections at once; one more closes the oldest (a stuck caller must not keep the tablet out). */
    private static final int MAX_OPEN = 2;

    private static final Object LOCK = new Object();
    private static Context app;
    private static boolean wanted;
    private static BluetoothServerSocket server;
    private static BroadcastReceiver receiver;
    private static final List<BluetoothSocket> OPEN = new ArrayList<>();
    private static ScheduledThreadPoolExecutor timer;
    private static int quickEnds;

    /** For the status request and the phone's own screen: on, off (Bluetooth is off), perm, disabled, none, error. */
    private static volatile String state = "none";
    private static volatile String note = "";
    static volatile int served;
    static volatile long lastAt;

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
                return "ready: the paired tablet can reach this phone over Bluetooth";
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

    private static volatile String cachedName = "";
    private static volatile long nameAt;

    /** This phone's Bluetooth name, as other devices see it, or "". Asked of Android at most every five minutes (it is in every status reply). */
    @SuppressLint("MissingPermission")
    static String name(Context c) {
        long now = android.os.SystemClock.elapsedRealtime();
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

    /** The service started, or something changed (the permission was given, the switch was moved): listen if everything allows it. */
    static void start(Context c) {
        synchronized (LOCK) {
            app = c.getApplicationContext();
            wanted = true;
            quickEnds = 0;
            if (receiver == null) {
                receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ctx, Intent i) {
                        int st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                        if (st == BluetoothAdapter.STATE_ON || st == BluetoothAdapter.STATE_OFF
                                || st == BluetoothAdapter.STATE_TURNING_OFF) {
                            synchronized (LOCK) {
                                if (st != BluetoothAdapter.STATE_ON) {
                                    closeServerLocked();
                                }
                                listenLocked();
                            }
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
                    receiver = null; // no state news: listening still starts below, and again at the next service start
                }
            }
            listenLocked();
        }
    }

    /** The service is stopping: no listener, no connections. */
    static void stop() {
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
            for (BluetoothSocket s : new ArrayList<>(OPEN)) {
                closeQuietly(s);
            }
            OPEN.clear();
            if (timer != null) {
                timer.shutdownNow();
                timer = null;
            }
            state = "none";
        }
    }

    @SuppressLint("MissingPermission")
    private static void listenLocked() {
        if (!wanted || app == null) {
            return;
        }
        if (!Cfg.btLink(app)) {
            closeServerLocked();
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

    private static void acceptLoop(BluetoothServerSocket ss) {
        long startedAt = android.os.SystemClock.elapsedRealtime();
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
            synchronized (LOCK) {
                if (!wanted) {
                    closeQuietly(s);
                    break;
                }
                while (OPEN.size() >= MAX_OPEN) {
                    closeQuietly(OPEN.remove(0));
                }
                OPEN.add(s);
            }
            Thread t = new Thread(() -> serve(s), "linkmeter-bt");
            t.setDaemon(true);
            t.start();
        }
        synchronized (LOCK) {
            if (server == ss) {
                // the listener ended although nobody closed it: listen again if Bluetooth is still there, but never in a tight
                // loop (a listener that ends at once, three times in a row, is left alone until the next start)
                server = null;
                quickEnds = !any && android.os.SystemClock.elapsedRealtime() - startedAt < 3000 ? quickEnds + 1 : 0;
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

    private static void serve(final BluetoothSocket s) {
        final Context c = app;
        final ScheduledFuture<?>[] pending = new ScheduledFuture<?>[1];
        final Runnable hangUp = () -> closeQuietly(s);
        LinkServer.Watch watch = new LinkServer.Watch() {
            @Override
            public void waiting(long ms) {
                Awake.linkDone();
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
                Awake.linkBusy(c, 120_000L); // a sleeping phone must finish what it was asked; released when the answer is out
            }
        };
        try {
            LinkServer.serve(s.getInputStream(), s.getOutputStream(), LinkAuth.newNonce(), () -> Cfg.trusted(c), (r, out) -> {
                served++;
                lastAt = System.currentTimeMillis();
                Api.handle(c, r, out);
            }, watch);
        } catch (IOException | RuntimeException ignored) {
            // the connection could not even be opened properly
        } finally {
            Awake.linkDone();
            synchronized (pending) {
                if (pending[0] != null) {
                    pending[0].cancel(false);
                }
            }
            closeQuietly(s);
            synchronized (LOCK) {
                OPEN.remove(s);
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
     * shell may) so the tablet can be told where to call, and starts listening. Says in plain words what worked.
     */
    static String setup(Context c) throws IOException {
        StringBuilder sb = new StringBuilder();
        if (adapter(c) == null) {
            return "This phone has no Bluetooth.";
        }
        if (!hasPermission(c)) {
            Priv.run("pm grant " + c.getPackageName() + " " + Manifest.permission.BLUETOOTH_CONNECT, 20);
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
        // Never held in a shell variable: two plain commands, a few short lines each.
        Priv.Result r = Priv.run("settings get secure bluetooth_address 2>&1; dumpsys bluetooth_manager 2>/dev/null | grep -i -m 4 'address'", 20);
        String mac = BtAddr.find(r.out);
        if (!mac.isEmpty()) {
            Cfg.setBtMac(c, mac);
            sb.append("The phone's Bluetooth address was read, so the tablet knows where to call.\n");
        } else if (Cfg.btMac(c).isEmpty()) {
            sb.append("The phone would not tell its Bluetooth address. Pair the phone and the tablet once in Android's own Bluetooth "
                    + "settings (on both: Settings > Bluetooth), then press \"Find the phone\" on the tablet.\n");
        }
        start(c);
        sb.append("Bluetooth link on the phone: ").append(stateText()).append('.');
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
