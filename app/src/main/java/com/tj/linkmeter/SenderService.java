package com.tj.linkmeter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Phone side. A tiny server that answers "GET /s?c=CODE" with a JSON status.
 *
 * Battery notes: no wake lock, no Wi-Fi lock, no timers that wake the CPU. The
 * accept thread sleeps in accept() until the tablet asks. The idle check uses a
 * Handler, which only runs while the phone is awake anyway. If the tablet has
 * not asked for 2 hours the service stops itself.
 */
public class SenderService extends Service {
    /** Read by the activity to show running / stopped. */
    static volatile boolean active;

    private static final String CHANNEL = "sender";
    private static final String ACTION_STOP = "com.tj.linkmeter.STOP";
    private static final long IDLE_LIMIT_MS = 2L * 60 * 60 * 1000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile ServerSocket server;
    private volatile long lastRequest;
    private String code = "";

    private final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            long idle = SystemClock.elapsedRealtime() - lastRequest;
            if (idle >= IDLE_LIMIT_MS) {
                stopSelf();
            } else {
                main.postDelayed(this, IDLE_LIMIT_MS - idle);
            }
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startInForeground();
        if (!running) {
            running = true;
            active = true;
            code = Cfg.ensureCode(this);
            lastRequest = SystemClock.elapsedRealtime();
            Thread t = new Thread(this::serve, "linkmeter-accept");
            t.start();
            main.postDelayed(idleCheck, IDLE_LIMIT_MS);
        }
        // Not sticky: if Android kills it, it stays stopped instead of
        // restarting in the background and burning battery.
        return START_NOT_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "LinkMeter sender", NotificationManager.IMPORTANCE_MIN));
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(
                this, 1, new Intent(this, SenderService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("LinkMeter is sharing phone status")
                .setContentText("Stops by itself after 2 h with no tablet")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_launcher), "Stop", stop).build())
                .build();
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
            ss.bind(new InetSocketAddress(Cfg.PORT), 4);
            server = ss;
        } catch (IOException e) {
            main.post(() -> stopSelf()); // port busy or blocked: give up cleanly
            return;
        }
        while (running) {
            try (Socket s = ss.accept()) {
                handle(s);
            } catch (IOException e) {
                if (!running) {
                    break;
                }
            }
        }
    }

    /** One request at a time is plenty: each is a few hundred bytes. */
    private void handle(Socket s) throws IOException {
        s.setSoTimeout(1500);
        InputStream in = s.getInputStream();
        byte[] buf = new byte[256];
        int n = 0;
        while (n < buf.length) {
            int r = in.read(buf, n, buf.length - n);
            if (r < 0) {
                break;
            }
            n += r;
            if (hasNewline(buf, n)) {
                break;
            }
        }
        String text = new String(buf, 0, n, StandardCharsets.ISO_8859_1);
        String first = text.split("\r?\n", 2)[0];
        String want = "GET /s?c=";
        boolean ok = false;
        if (first.startsWith(want)) {
            int end = first.indexOf(' ', want.length());
            String tok = end > 0 ? first.substring(want.length(), end) : first.substring(want.length());
            ok = MessageDigest.isEqual(
                    tok.getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8));
        }
        OutputStream out = s.getOutputStream();
        if (!ok) {
            out.write("HTTP/1.0 403 Forbidden\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return;
        }
        lastRequest = SystemClock.elapsedRealtime();
        byte[] body = Status.json(this).getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.0 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                + body.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static boolean hasNewline(byte[] b, int n) {
        for (int i = 0; i < n; i++) {
            if (b[i] == '\n') {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onDestroy() {
        running = false;
        active = false;
        main.removeCallbacks(idleCheck);
        ServerSocket ss = server;
        if (ss != null) {
            try {
                ss.close(); // wakes the accept thread so it can exit
            } catch (IOException ignored) {
                // already closed
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
