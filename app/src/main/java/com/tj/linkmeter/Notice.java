package com.tj.linkmeter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

/** A normal notification for things the user must see, such as "LinkMeter restored your settings". */
final class Notice {
    private static final String CHANNEL = "alerts";

    private Notice() {}

    static void post(Context c, String title, String text) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "LinkMeter alerts", NotificationManager.IMPORTANCE_DEFAULT));
            Notification n = new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .build();
            nm.notify(2, n);
        } catch (RuntimeException ignored) {
            // notifications are not allowed: nothing else to do
        }
    }
}
