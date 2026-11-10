package com.tj.linkmeter;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;

/**
 * Phone only: the battery-saving profile for hotspot use.
 *
 * It only turns off things a hotspot does not need (background Wi-Fi and Bluetooth
 * scanning, the Always-on display), makes sure the hotspot's hardware offload is
 * enabled, and shortens the screen timeout. It never touches mobile data, the radio,
 * the CPU speed or Bluetooth itself (the tablet's remote link uses it), so the cell
 * link and hotspot speed are unaffected. Every value it changes is saved first and
 * put back afterwards.
 *
 * It starts by itself when the tablet connects (if Settings allows it) and ends
 * by itself 10 minutes after the tablet was last seen.
 */
final class Eco {
    /** namespace, key, value while saving battery. */
    private static final String[][] PROFILE = {
        {"global", "wifi_scan_always_enabled", "0"},
        {"global", "ble_scan_always_enabled", "0"},
        {"secure", "doze_always_on", "0"},
        {"global", "tether_offload_disabled", "0"},
        {"system", "screen_off_timeout", "15000"},
    };
    private static final int TIMEOUT = 4; // index of screen_off_timeout in PROFILE
    private static final long IDLE_MS = 10 * 60 * 1000L;
    private static final Handler H = new Handler(Looper.getMainLooper());

    private static volatile long lastSeen;
    private static volatile boolean suppressed;
    private static volatile boolean busy;
    private static boolean timerSet;

    private Eco() {}

    /** The short screen timeout the battery saver uses, in milliseconds (as text). */
    static String timeoutMs() {
        return PROFILE[TIMEOUT][2];
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("eco", Context.MODE_PRIVATE);
    }

    static boolean isOn(Context c) {
        return prefs(c).getBoolean("on", false);
    }

    /** After a manual "off" the profile stays off until it is switched on by hand. */
    static void setSuppressed(boolean s) {
        suppressed = s;
    }

    /** The service just started (possibly after a reboot with the profile still on). */
    static void onServiceStart(Context c) {
        lastSeen = SystemClock.elapsedRealtime();
        if (isOn(c)) {
            armIdleCheck(c.getApplicationContext());
        }
    }

    /** The tablet just talked to us. */
    static void touch(final Context c, boolean fromLocalNet) {
        lastSeen = SystemClock.elapsedRealtime();
        final Context app = c.getApplicationContext();
        armIdleCheck(app);
        if (fromLocalNet && Cfg.ecoAuto(app) && !suppressed && !isOn(app) && !busy
                && Priv.ready()) {
            busy = true;
            new Thread(() -> {
                try {
                    apply(app);
                } catch (IOException ignored) {
                    // try again on a later request
                } finally {
                    busy = false;
                }
            }, "linkmeter-eco").start();
        }
    }

    private static void armIdleCheck(final Context c) {
        synchronized (Eco.class) {
            if (timerSet) {
                return;
            }
            timerSet = true;
        }
        H.postDelayed(new Runnable() {
            @Override
            public void run() {
                long idle = SystemClock.elapsedRealtime() - lastSeen;
                if (idle < IDLE_MS) {
                    H.postDelayed(this, IDLE_MS - idle);
                    return;
                }
                synchronized (Eco.class) {
                    timerSet = false;
                }
                if (isOn(c)) {
                    new Thread(() -> {
                        try {
                            restore(c);
                        } catch (IOException ignored) {
                            // Shizuku is off right now; it is retried on the next start or request
                        }
                    }, "linkmeter-eco").start();
                }
            }
        }, IDLE_MS);
    }

    static synchronized String apply(Context c) throws IOException {
        if (isOn(c)) {
            return "Battery saver was already on.";
        }
        StringBuilder rd = new StringBuilder();
        for (int i = 0; i < PROFILE.length; i++) {
            rd.append("echo K").append(i).append("=$(settings get ")
                    .append(PROFILE[i][0]).append(' ').append(PROFILE[i][1]).append("); ");
        }
        rd.append("true");
        Priv.Result r = Priv.run(rd.toString(), 15);
        if (!r.ok()) {
            return "Couldn't read the phone's settings: " + r.out;
        }
        String[] orig = new String[PROFILE.length];
        for (String line : r.out.split("\n")) {
            String l = line.trim();
            int eq = l.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String k = l.substring(0, eq);
            String v = l.substring(eq + 1).trim();
            if (k.startsWith("K")) {
                try {
                    orig[Integer.parseInt(k.substring(1))] = v;
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
                    // not one of our lines
                }
            }
        }
        // A remote session may have raised the screen timeout: remember the value from before it, and leave
        // the timeout alone for now (the session puts it back to our value when it ends).
        boolean remoteTimeout = RemoteProfile.ownsTimeout(c);
        if (remoteTimeout) {
            orig[TIMEOUT] = RemoteProfile.originalTimeout(c);
        }
        // Save the originals before changing anything, so a crash half-way can still be undone.
        SharedPreferences.Editor e = prefs(c).edit();
        for (int i = 0; i < PROFILE.length; i++) {
            e.putString("o" + i, (orig[i] == null || orig[i].isEmpty()) ? "null" : orig[i]);
        }
        // Bluetooth is left alone since the remote link exists (it would cut the link, and an idle Bluetooth radio costs almost
        // nothing). "0" = restore() has no Bluetooth to switch back on; a profile applied by an older version still has "1".
        e.putString("bt", "0");
        e.putBoolean("on", true);
        e.commit();

        StringBuilder wr = new StringBuilder();
        for (int i = 0; i < PROFILE.length; i++) {
            if (i == TIMEOUT && remoteTimeout) {
                continue;
            }
            if (!PROFILE[i][2].equals(orig[i])) {
                wr.append("settings put ").append(PROFILE[i][0]).append(' ')
                        .append(PROFILE[i][1]).append(' ').append(PROFILE[i][2]).append("; ");
            }
        }
        wr.append("true");
        Priv.Result w = Priv.run(wr.toString(), 20);
        return w.ok() ? "Battery saver is on." : "Some changes failed: " + w.out;
    }

    static synchronized String restore(Context c) throws IOException {
        if (!isOn(c)) {
            return "Battery saver was off.";
        }
        SharedPreferences p = prefs(c);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PROFILE.length; i++) {
            String o = p.getString("o" + i, "null");
            if (o.equals("null") || o.isEmpty()) {
                sb.append("settings delete ").append(PROFILE[i][0]).append(' ')
                        .append(PROFILE[i][1]).append("; ");
            } else {
                sb.append("settings put ").append(PROFILE[i][0]).append(' ')
                        .append(PROFILE[i][1]).append(' ').append(o).append("; ");
            }
        }
        if ("1".equals(p.getString("bt", "0"))) {
            sb.append("svc bluetooth enable; ");
        }
        sb.append("true");
        Priv.Result r = Priv.run(sb.toString(), 20);
        if (r.ok()) {
            p.edit().putBoolean("on", false).commit();
            return "Battery saver is off. Settings are back as they were.";
        }
        return "Couldn't restore everything: " + r.out;
    }
}
