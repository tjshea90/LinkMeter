package com.tj.linkmeter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.provider.Settings;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Phone only. "Start the phone after a restart" (see docs/BOOT_FLOW.md): what the phone says about itself to the tablet, switching
 * wireless debugging on by itself, the notification that opens Shizuku, and the one-time setup that makes the first part possible.
 *
 * Nothing here touches the radio, the network type, bands or mobile data. What it changes, and how each is undone:
 *  - WRITE_SECURE_SETTINGS, given once through Shizuku (`pm grant`); taken back by {@link #undo} (`pm revoke`).
 *  - Settings.Global adb_wifi_enabled / development_settings_enabled, only while Shizuku is not running; Android itself switches
 *    wireless debugging off again when the Wi-Fi goes.
 *  - Settings.Global adb_allowed_connection_time = 0 (Android forgets unused adb authorizations after 7 days otherwise), only if the user
 *    asks; the old value is saved and put back by {@link #setPairingKept} or {@link #undo}.
 *  - the Boot assist switch ({@link #setAssist}).
 */
final class Boot {
    static final String PERM = "android.permission.WRITE_SECURE_SETTINGS";
    static final String SHIZUKU = "moe.shizuku.privileged.api";
    private static final String KEY_WADB = "adb_wifi_enabled";
    private static final String KEY_DEV = "development_settings_enabled";
    private static final String KEY_TIMEOUT = "adb_allowed_connection_time";
    private static final String PREF_ASSIST = "bootAssist";
    private static final String PREF_OLD_TIMEOUT = "adbTimeoutOld";
    private static final String NO_VALUE = "-";
    private static final String CHANNEL = "boot";
    private static final int NOTICE_ID = 77;

    /** How the last "start the phone's hotspot" went (the phone answers the tablet first and acts a moment later). */
    static volatile String hotspotResult = "";

    private Boot() {}

    // ------------------------------------------------------------------------------------------- facts

    static boolean canWriteSecure(Context c) {
        return c.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean wadbOn(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), KEY_WADB, 0) == 1;
    }

    static boolean devOptionsOn(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), KEY_DEV, 0) == 1;
    }

    static boolean assist(Context c) {
        return Cfg.getBool(c, PREF_ASSIST, false);
    }

    /** Is Android's 7-day timeout for wireless-debugging pairings switched off (by this app)? */
    static boolean pairingKept(Context c) {
        try {
            String v = Settings.Global.getString(c.getContentResolver(), KEY_TIMEOUT);
            return v != null && v.trim().equals("0") && !Cfg.getStr(c, PREF_OLD_TIMEOUT, "").isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean wifiUp(Context c) {
        return Net.wifi(c) != null;
    }

    /** The phone's address on the Wi-Fi network it has joined, or "" if it has joined none. */
    static String wifiIp(Context c) {
        try {
            Network n = Net.wifi(c);
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
            if (lp != null) {
                for (LinkAddress a : lp.getLinkAddresses()) {
                    if (a.getAddress() instanceof Inet4Address) {
                        return a.getAddress().getHostAddress();
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // no network information
        }
        return "";
    }

    /** Best guess whether the phone's own Wi-Fi hotspot is on: a hotspot interface with a private address. false if it cannot tell. */
    static boolean hotspotOn(Context c) {
        return !hotspotAddress(c).isEmpty();
    }

    /**
     * The phone's own address on its hotspot (the one a tablet types when it cannot find the phone), or "" when no hotspot interface
     * with a private address is up, or when this phone does not say. Lists the network interfaces: not for the screen's thread.
     */
    static String hotspotAddress(Context c) {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String n = ni.getName();
                boolean hotspotName = n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap")
                        || (n.startsWith("wlan") && !n.equals("wlan0"));
                if (hotspotName && ni.isUp()) {
                    for (java.net.InetAddress a : Collections.list(ni.getInetAddresses())) {
                        if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                            return a.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception | LinkageError ignored) {
            // cannot tell
        }
        return "";
    }

    /** What the tablet is told at GET /boot. */
    static String statusJson(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("priv", Priv.state());
            o.put("wifi", wifiUp(c));
            o.put("ip", wifiIp(c));
            o.put("wadb", wadbOn(c));
            o.put("dev", devOptionsOn(c));
            o.put("write", canWriteSecure(c));
            o.put("hotspot", hotspotOn(c));
            o.put("assist", assist(c));
            o.put("keep", pairingKept(c));
            o.put("note", hotspotResult);
            o.put("vc", Updater.versionCode(c));
        } catch (JSONException e) {
            return "{\"err\":\"could not write the status\"}";
        }
        return o.toString();
    }

    // ------------------------------------------------------------------------------------------- wireless debugging

    /**
     * Switches wireless debugging on (and developer options, which it needs). Needs the one-time permission, not Shizuku. Android may then
     * ask "Allow wireless debugging on this network?": that dialog is Android's and only a tap can answer it.
     */
    static String enableWadb(Context c) {
        if (!canWriteSecure(c)) {
            return "ERR LinkMeter may not change this setting yet: run the one-time setup first (Phone tab > After a phone restart > Tablet hotspot and one-time setup > One-time setup).";
        }
        try {
            ContentResolver cr = c.getContentResolver();
            if (!devOptionsOn(c) && !Settings.Global.putInt(cr, KEY_DEV, 1)) {
                return "ERR Android did not accept developer options.";
            }
            if (wadbOn(c)) {
                return "OK wireless debugging is already on";
            }
            return Settings.Global.putInt(cr, KEY_WADB, 1)
                    ? "OK wireless debugging is on" + (wifiUp(c) ? "" : " (it needs a Wi-Fi network: Android switches it off again until the phone is on one)")
                    : "ERR Android did not switch wireless debugging on.";
        } catch (RuntimeException e) {
            return "ERR " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------------------------------- the Shizuku notification

    /** The intent that opens the Shizuku app, or Android's developer options if Shizuku is not installed. */
    static Intent shizukuIntent(Context c) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(SHIZUKU);
        if (i == null) {
            i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    /** One notification, one tap to open Shizuku (Android does not let an app open another app's screen by itself). */
    static void notifyShizuku(Context c, BootPlan.Notice kind) {
        try {
            boolean allow = kind == BootPlan.Notice.NEEDS_ALLOW;
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Start Shizuku after a restart", NotificationManager.IMPORTANCE_HIGH));
            Intent main = allow ? new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) : shizukuIntent(c);
            PendingIntent open = PendingIntent.getActivity(c, 10, main, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Intent dev = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent devPi = PendingIntent.getActivity(c, 11, dev, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            String text = allow
                    ? "Shizuku is running. Open LinkMeter and tap Allow in Shizuku's question."
                    : "Tap to open Shizuku, then tap Start. If Android asks, allow wireless debugging on this network. If Shizuku asks to pair: "
                    + "Developer options > Wireless debugging > Pair device with pairing code, then type the number into Shizuku's notification.";
            Notification n = new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(allow ? "Allow LinkMeter in Shizuku" : "Shizuku isn't running")
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_launcher), "Developer options", devPi).build())
                    .build();
            nm.notify(NOTICE_ID, n);
        } catch (RuntimeException ignored) {
            // notifications are not allowed: the tablet's card still tells the user what to do
        }
    }

    static void cancelNotice(Context c) {
        try {
            c.getSystemService(NotificationManager.class).cancel(NOTICE_ID);
        } catch (RuntimeException ignored) {
            // nothing to cancel
        }
    }

    // ------------------------------------------------------------------------------------------- setting up and undoing

    static void setAssist(Context c, boolean on) {
        Cfg.setBool(c, PREF_ASSIST, on);
        if (on) {
            BootAssist.start(c);
        } else {
            BootAssist.stop();
            cancelNotice(c);
        }
    }

    /**
     * The one-time setup (needs Shizuku to be running now): gives LinkMeter the permission to switch wireless debugging on, and switches
     * Boot assist on. Reports in plain words.
     */
    static String setup(Context c) throws IOException {
        StringBuilder sb = new StringBuilder();
        if (canWriteSecure(c)) {
            sb.append("LinkMeter already has the permission.\n");
        } else {
            Priv.Result r = Priv.run("pm grant " + c.getPackageName() + " " + PERM, 20);
            if (canWriteSecure(c)) {
                sb.append("The permission was given.\n");
            } else {
                return "The phone did not give the permission:\n" + r.out
                        + "\nNothing was changed. Without it LinkMeter cannot switch wireless debugging on by itself; "
                        + "you can still do it by hand after a restart.";
            }
        }
        setAssist(c, true);
        sb.append("Boot assist is on: after a restart, while Shizuku is not running, the phone switches wireless debugging on by itself "
                + "(once it is on a Wi-Fi network) and shows a notification that opens Shizuku.\n"
                + "Undo it any time with Undo the setup.");
        return sb.toString();
    }

    /** Takes everything back: the permission, Boot assist, the pairing-timeout change. Works without Shizuku except for the permission. */
    static String undo(Context c) {
        StringBuilder sb = new StringBuilder();
        setAssist(c, false);
        sb.append("Boot assist is off.\n");
        String t = setPairingKept(c, false);
        sb.append(t).append('\n');
        if (!canWriteSecure(c)) {
            sb.append("LinkMeter does not hold the permission.");
        } else {
            try {
                Priv.run("pm revoke " + c.getPackageName() + " " + PERM, 20);
                sb.append(canWriteSecure(c)
                        ? "The permission could not be taken back (it stays until LinkMeter is uninstalled or you run: pm revoke "
                        + c.getPackageName() + " " + PERM + ")."
                        : "The permission was taken back.");
            } catch (IOException e) {
                sb.append("The permission could not be taken back now (").append(e.getMessage())
                        .append("). Run Undo the setup again when Shizuku is running.");
            }
        }
        return sb.toString().trim();
    }

    /**
     * Switches Android's 7-day timeout for unused adb authorizations off (so a wireless-debugging pairing is not forgotten between two
     * rare restarts), or puts the old value back. Needs Shizuku to write the setting.
     */
    static String setPairingKept(Context c, boolean keep) {
        try {
            if (keep) {
                if (pairingKept(c)) {
                    return "The 7-day timeout is already off.";
                }
                String old = Settings.Global.getString(c.getContentResolver(), KEY_TIMEOUT);
                Cfg.setStr(c, PREF_OLD_TIMEOUT, old == null ? NO_VALUE : old);
                Priv.Result r = Priv.run("settings put global " + KEY_TIMEOUT + " 0", 15);
                if (pairingKept(c)) {
                    return "Android no longer forgets the wireless-debugging pairing after 7 days.";
                }
                Cfg.setStr(c, PREF_OLD_TIMEOUT, "");
                return "The phone did not take the change: " + r.out;
            }
            String old = Cfg.getStr(c, PREF_OLD_TIMEOUT, "");
            if (old.isEmpty()) {
                return "The 7-day timeout was not changed by LinkMeter.";
            }
            String cmd = old.equals(NO_VALUE) ? "settings delete global " + KEY_TIMEOUT : "settings put global " + KEY_TIMEOUT + " " + old;
            if (!old.equals(NO_VALUE) && !old.matches("[0-9]{1,15}")) {
                Cfg.setStr(c, PREF_OLD_TIMEOUT, "");
                return "The old value was not a number, so it was left alone.";
            }
            Priv.run(cmd, 15);
            Cfg.setStr(c, PREF_OLD_TIMEOUT, "");
            return "The 7-day timeout is back as it was.";
        } catch (IOException e) {
            return "Needs Shizuku to be running: " + e.getMessage();
        } catch (RuntimeException e) {
            return "ERR " + e.getMessage();
        }
    }
}
