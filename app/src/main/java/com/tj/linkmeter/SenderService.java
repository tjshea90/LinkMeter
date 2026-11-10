package com.tj.linkmeter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The always-on part. On the phone it answers the tablet; on the tablet it only
 * runs if file sharing is switched on.
 *
 * Battery notes: no wake lock, no Wi-Fi lock, no timers. The accept thread sleeps
 * in accept() until someone connects, and worker threads exist only while a
 * request is being served (the pool keeps none idle for more than 20 s).
 */
public class SenderService extends Service {
    /** Read by the activity to show running / stopped. */
    static volatile boolean active;
    /** Phone: while now is before this, a new tablet may pair itself. */
    static volatile long pairUntil;

    private static final String CHANNEL = "sender";
    private static final String ACTION_STOP = "com.tj.linkmeter.STOP";
    private static final String ACTION_RESTORE = "com.tj.linkmeter.RESTORE";

    private volatile boolean running;
    private volatile ServerSocket server;
    private ThreadPoolExecutor pool;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startInForeground();
        if (intent != null && ACTION_RESTORE.equals(intent.getAction())) {
            final Context app = getApplicationContext();
            new Thread(() -> Notice.post(app, "LinkMeter restore",
                    Actions.restoreAll(app)), "linkmeter-restore").start();
        }
        if (!running) {
            running = true;
            active = true;
            // Two of these are taken for as long as the tablet's Screen tab is open (video and touch), so there is room.
            pool = new ThreadPoolExecutor(0, 16, 20, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
            Thread t = new Thread(this::serve, "linkmeter-accept");
            t.start();
            if (Cfg.PHONE.equals(Cfg.mode(this))) {
                Eco.onServiceStart(getApplicationContext());
                Updater.cleanup(getApplicationContext());
                RemoteProfile.recover(getApplicationContext());
                Guard.resume(getApplicationContext()); // anything LinkMeter changed and left: watch it again, finish a cut-off radio-off
                BootAssist.start(getApplicationContext()); // after a restart: wireless debugging on and the Shizuku notification, if the user switched Boot assist on
                Radios.watch(getApplicationContext()); // hears the hotspot go on and off, for the status
                BtServer.start(getApplicationContext()); // the Bluetooth door: the tablet can reach the phone while the hotspot is off
            }
        }
        // Sticky: if Android ever stops it, it comes back by itself.
        return START_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "LinkMeter", NotificationManager.IMPORTANCE_MIN));
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(
                this, 1, new Intent(this, SenderService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent restore = PendingIntent.getService(
                this, 2, new Intent(this, SenderService.class).setAction(ACTION_RESTORE),
                PendingIntent.FLAG_IMMUTABLE);
        boolean phone = Cfg.PHONE.equals(Cfg.mode(this));
        Notification.Builder nb = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(phone ? "LinkMeter is ready for the tablet" : "LinkMeter is sharing files")
                .setContentText("Tap to open")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_launcher), "Stop", stop).build());
        if (phone) {
            // One tap from the notification shade puts the phone's network settings back.
            nb.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_launcher), "Restore", restore).build());
        }
        Notification n = nb.build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    private void serve() {
        ServerSocket ss;
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(Cfg.PORT), 8);
            server = ss;
        } catch (IOException e) {
            active = false;
            running = false;
            stopSelf(); // port busy or blocked: give up cleanly
            return;
        }
        while (running) {
            try {
                final Socket s = ss.accept();
                try {
                    pool.execute(() -> serveOne(s));
                } catch (RejectedExecutionException busy) {
                    refuseBusy(s); // every worker is busy: say so instead of just hanging up
                }
            } catch (IOException e) {
                if (!running) {
                    break;
                }
            }
        }
    }

    /**
     * Tells a caller that every worker is busy (it used to be dropped without a word, which looked like a dead phone).
     * Done on a thread of its own so the accept loop never waits. The caller is left to finish sending its request
     * first: closing a socket with unread data resets the connection, and the answer would be lost.
     */
    private static void refuseBusy(final Socket s) {
        Thread t = new Thread(() -> {
            try {
                s.setSoTimeout(1500);
                OutputStream out = new BufferedOutputStream(s.getOutputStream(), 1024);
                Http.err(out, 503, "The phone is busy with other requests. Try again in a moment.");
                out.flush();
                s.shutdownOutput();
                byte[] junk = new byte[512];
                while (s.getInputStream().read(junk) > 0) {
                    // the request that will not be served
                }
            } catch (IOException ignored) {
                // the caller is gone, or did not finish within a second and a half
            } finally {
                closeQuietly(s);
            }
        }, "linkmeter-busy");
        t.setDaemon(true);
        t.start();
    }

    private void serveOne(Socket s) {
        try {
            Http.Req r = Http.read(s);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 16 * 1024);
            try {
                Api.handle(getApplicationContext(), r, out);
            } catch (RuntimeException e) {
                Http.err(out, 500, String.valueOf(e));
            }
            out.flush();
        } catch (IOException | RuntimeException ignored) {
            // The other side went away or sent something odd. Nothing to do. (RuntimeException too: an exception that escapes a
            // worker thread would end the whole app, and what a stranger on the hotspot sends must never be able to do that.)
        } finally {
            closeQuietly(s);
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        active = false;
        ServerSocket ss = server;
        if (ss != null) {
            try {
                ss.close(); // wakes the accept thread so it can exit
            } catch (IOException ignored) {
                // already closed
            }
        }
        if (pool != null) {
            pool.shutdownNow();
        }
        BtServer.stop();
        Radios.unwatch(getApplicationContext());
        Eco.onServiceStop(getApplicationContext()); // no alarm and no screen receiver of the battery saver may outlive the service
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
