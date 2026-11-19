package com.tj.linkmeter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.view.WindowManager;

/**
 * Tablet only: hosts the floating phone ({@link FloatingPhoneController}) as a foreground service that draws it with Android's "Display
 * over other apps" (docs/FLOATING_PHONE.md). Runs only while {@link FloatingPhone#wanted} says so. Where Android turns that permission
 * off (Android 10 Go) {@link FloatingPhoneAccess} hosts the same card instead.
 */
public final class FloatingPhoneService extends Service {
    static final String ACTION_SHOW = FloatingPhoneController.ACTION_SHOW;
    static final String ACTION_OFF = FloatingPhoneController.ACTION_OFF;
    private static final String CHANNEL = "floating_phone";
    private static final int NOTE_ID = 7;

    private static volatile boolean running;

    static boolean running() {
        return running;
    }

    private FloatingPhoneController controller;

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        startInForeground();
        controller = new FloatingPhoneController(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, this::stopSelf);
        controller.start();
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        startInForeground(); // Android wants this within seconds of every start
        String a = i == null ? null : i.getAction();
        if (ACTION_OFF.equals(a)) {
            controller.command(a);
            return START_NOT_STICKY;
        }
        if (ACTION_SHOW.equals(a)) {
            controller.command(a);
        } else {
            controller.refresh();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (controller != null) {
            controller.stop();
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (controller != null) {
            controller.configurationChanged();
        }
    }

    // ------------------------------------------------------------------ the notification Android wants for a service that stays

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Floating phone", NotificationManager.IMPORTANCE_MIN));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent show = PendingIntent.getService(this, 1, new Intent(this, FloatingPhoneService.class).setAction(ACTION_SHOW),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent off = PendingIntent.getService(this, 2, new Intent(this, FloatingPhoneService.class).setAction(ACTION_OFF),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Floating phone is ready")
                .setContentText("It pops up when the phone rings or a call is on")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_launcher), "Show phone", show).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_launcher), "Turn off", off).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTE_ID, n);
        }
    }
}
