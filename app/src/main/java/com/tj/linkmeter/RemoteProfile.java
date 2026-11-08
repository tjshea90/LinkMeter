package com.tj.linkmeter;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;

/**
 * Phone only: while the tablet's Screen tab is in use, keep the phone's screen on for at least a while
 * before it times out, and (if asked) turn its brightness down to the lowest, because nobody is looking at
 * the phone itself. The picture on the tablet is unaffected: it is taken from the screen contents, not
 * from the panel's brightness. When the tablet leaves, everything is put back.
 *
 * Safety, because these are the user's own settings:
 * - the originals are saved before anything is changed, so a crash half-way can still be undone;
 * - a setting is only put back if it still has the value we gave it (if the user changed it by hand in the
 *   meantime, that choice is kept);
 * - if the tablet disappears (no request for a minute and no stream open) the phone restores by itself;
 * - after a restart of the service anything left over is restored as well;
 * - the battery saver (Eco) also shortens the screen timeout. While a remote session owns the timeout, Eco
 *   leaves it alone and remembers the original, and on the way out the timeout goes back to Eco's value if
 *   Eco is on. Both use the Eco class lock, so they never run at the same time.
 */
final class RemoteProfile {
    private static final String[] KEYS = {"screen_off_timeout", "screen_brightness_mode", "screen_brightness"};
    private static final int T = 0; // screen_off_timeout (ms)
    private static final int M = 1; // screen_brightness_mode (0 manual, 1 automatic)
    private static final int B = 2; // screen_brightness (0 to 255)
    /** No request from the tablet and no stream open for this long: the tablet is gone. */
    private static final long LEASE_MS = 60 * 1000L;
    private static final long CHECK_MS = 15 * 1000L;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static volatile long lastSeen;
    private static volatile Context appCtx;

    private static final Runnable CHECK = new Runnable() {
        @Override
        public void run() {
            final Context app = appCtx;
            if (app == null || !isOn(app)) {
                return; // nothing to watch any more
            }
            long idle = SystemClock.elapsedRealtime() - lastSeen;
            if (Screen.openStreams() == 0 && idle > LEASE_MS) {
                new Thread(() -> {
                    try {
                        end(app);
                    } catch (IOException ignored) {
                        // Shizuku is off right now; the next check tries again
                    }
                }, "linkmeter-remote-end").start();
            }
            H.postDelayed(this, CHECK_MS);
        }
    };

    private RemoteProfile() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("remoteprofile", Context.MODE_PRIVATE);
    }

    static boolean isOn(Context c) {
        return prefs(c).getBoolean("on", false);
    }

    /** The tablet is there: any request counts, and so does an open stream. */
    static void seen() {
        lastSeen = SystemClock.elapsedRealtime();
    }

    /** True while a remote session has changed the screen timeout, so the battery saver must not touch it. */
    static boolean ownsTimeout(Context c) {
        SharedPreferences p = prefs(c);
        return p.getBoolean("on", false) && p.getBoolean("c" + T, false);
    }

    /** The screen timeout from before the remote session changed it (the text "null" if it was not set). */
    static String originalTimeout(Context c) {
        return prefs(c).getString("o" + T, "null");
    }

    /** The service just started: if a session was left half-way (reboot, crash), end it soon. */
    static void recover(Context c) {
        Context app = c.getApplicationContext();
        if (isOn(app)) {
            arm(app);
        }
    }

    private static void arm(Context app) {
        appCtx = app;
        H.removeCallbacks(CHECK);
        H.postDelayed(CHECK, CHECK_MS);
    }

    // ------------------------------------------------------------ reading and writing the settings

    private static long num(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The three settings, read in one shell call. A setting that is not set reads as "null". */
    private static String[] read() throws IOException {
        Priv.Result r = Priv.run("echo T=$(settings get system screen_off_timeout); "
                + "echo M=$(settings get system screen_brightness_mode); "
                + "echo B=$(settings get system screen_brightness)", 15);
        if (!r.ok()) {
            throw new IOException("Couldn't read the phone's settings: " + r.out);
        }
        String[] v = {"null", "null", "null"};
        for (String line : r.out.split("\n")) {
            String l = line.trim();
            if (l.length() < 3 || l.charAt(1) != '=') {
                continue;
            }
            String val = l.substring(2).trim();
            if (val.isEmpty()) {
                val = "null";
            }
            switch (l.charAt(0)) {
                case 'T':
                    v[T] = val;
                    break;
                case 'M':
                    v[M] = val;
                    break;
                case 'B':
                    v[B] = val;
                    break;
                default:
                    break;
            }
        }
        return v;
    }

    /** One shell command that sets a setting, or deletes it if it was not set before. */
    private static String put(int i, String v) {
        if (v.equals("null")) {
            return "settings delete system " + KEYS[i] + "; ";
        }
        if (!v.matches("\\d{1,10}")) {
            throw new IllegalArgumentException("bad value for " + KEYS[i]);
        }
        return "settings put system " + KEYS[i] + " " + v + "; ";
    }

    private static String timeoutText(String ms) {
        long v = num(ms);
        if (v < 0) {
            return "not set";
        }
        return v >= 60000 && v % 60000 == 0 ? (v / 60000) + " min" : (v / 1000) + " s";
    }

    private static String brightText(String level, String mode) {
        long v = num(level);
        String s = v < 0 ? "not set" : (v == 0 ? "the lowest" : v + " of 255");
        return "1".equals(mode) ? s + ", automatic" : s;
    }

    // ------------------------------------------------------------ begin, end, status

    /**
     * Starts (or updates) a session. keepMs: make sure the screen stays on at least this long (0 = leave the
     * timeout alone). dim: brightness 0 to 255 to use meanwhile (-1 = leave brightness alone). Returns what it did.
     * The decisions are in ProfilePlan, which is tested on a computer.
     */
    static String begin(Context c, long keepMs, int dim) throws IOException {
        final Context app = c.getApplicationContext();
        final long keep = Math.max(0, Math.min(keepMs, 3600000L));
        final int level = dim < 0 ? -1 : Math.min(dim, 255);
        synchronized (Eco.class) {
            SharedPreferences p = prefs(app);
            boolean on = p.getBoolean("on", false);
            String[] cur = read();
            String[] savedOrig = new String[3];
            boolean[] savedChg = new boolean[3];
            String[] savedAppl = new String[3];
            for (int i = 0; i < 3; i++) {
                savedOrig[i] = p.getString("o" + i, cur[i]);
                savedChg[i] = p.getBoolean("c" + i, false);
                savedAppl[i] = p.getString("a" + i, "null");
            }
            ProfilePlan.Begin plan = ProfilePlan.begin(on, savedOrig, savedChg, savedAppl, cur, keep, level,
                    Eco.isOn(app), Eco.timeoutMs());
            // Save first (originals and what we are about to set), then change.
            SharedPreferences.Editor e = p.edit();
            for (int i = 0; i < 3; i++) {
                e.putString("o" + i, plan.orig[i]);
                e.putBoolean("c" + i, plan.chg[i]);
                e.putString("a" + i, plan.chg[i] ? plan.now[i] : "null");
            }
            e.putBoolean("on", plan.any());
            e.commit();
            StringBuilder wr = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                if (plan.writes[i] != null) {
                    wr.append(put(i, plan.writes[i]));
                }
            }
            String[] now = plan.now;
            if (wr.length() > 0) {
                Priv.Result w = Priv.run(wr.append("true").toString(), 20);
                if (!w.ok()) {
                    return "Some of the phone's screen settings could not be changed: " + w.out;
                }
                String[] back = read(); // the phone may round or limit a value: remember what it kept
                SharedPreferences.Editor e2 = p.edit();
                now = plan.now.clone();
                for (int i = 0; i < 3; i++) {
                    if (plan.chg[i]) {
                        now[i] = back[i];
                        e2.putString("a" + i, back[i]);
                    }
                }
                e2.commit();
            }
            seen();
            if (plan.any()) {
                arm(app);
            } else {
                H.removeCallbacks(CHECK);
            }
            StringBuilder said = new StringBuilder();
            if (plan.chg[T]) {
                said.append("The phone's screen stays on for at least ").append(timeoutText(now[T]))
                        .append(" (it was ").append(timeoutText(plan.orig[T])).append("). ");
            } else if (keep > 0) {
                said.append("Screen timeout is already ").append(timeoutText(cur[T])).append(" or more, left as it is. ");
            }
            if (plan.chg[B]) {
                said.append("Brightness is ").append(brightText(now[B], "0")).append(" while you use the remote screen (it was ")
                        .append(brightText(plan.orig[B], plan.orig[M])).append("). ");
            }
            if (said.length() == 0) {
                said.append("No phone settings needed changing.");
            }
            return said.toString().trim();
        }
    }

    /** Ends the session and puts the settings back. Safe to call when no session is on. */
    static String end(Context c) throws IOException {
        final Context app = c.getApplicationContext();
        synchronized (Eco.class) {
            SharedPreferences p = prefs(app);
            if (!p.getBoolean("on", false)) {
                H.removeCallbacks(CHECK);
                return "No remote session was changing the phone's screen settings.";
            }
            String[] cur = read();
            boolean[] savedChg = new boolean[3];
            String[] savedAppl = new String[3];
            String[] savedOrig = new String[3];
            for (int i = 0; i < 3; i++) {
                savedChg[i] = p.getBoolean("c" + i, false);
                savedAppl[i] = p.getString("a" + i, "null");
                savedOrig[i] = p.getString("o" + i, "null");
            }
            String[] w = ProfilePlan.end(savedChg, savedAppl, savedOrig, cur, Eco.isOn(app), Eco.timeoutMs());
            StringBuilder wr = new StringBuilder();
            StringBuilder said = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                if (w[i] != null) {
                    wr.append(put(i, w[i]));
                    if (i == T) {
                        said.append("Screen timeout back to ").append(timeoutText(w[i])).append(". ");
                    } else if (i == B) {
                        said.append("Brightness back to ").append(brightText(w[i], savedOrig[M])).append(". ");
                    }
                } else if (savedChg[i] && (i == T || i == B)) {
                    said.append(i == T ? "Screen timeout" : "Brightness").append(" left as you changed it. ");
                }
            }
            if (wr.length() > 0) {
                Priv.Result r = Priv.run(wr.append("true").toString(), 20);
                if (!r.ok()) {
                    return "Couldn't put the phone's screen settings back: " + r.out;
                }
            }
            SharedPreferences.Editor e = p.edit();
            e.putBoolean("on", false);
            for (int i = 0; i < 3; i++) {
                e.putBoolean("c" + i, false);
            }
            e.commit();
            H.removeCallbacks(CHECK);
            return said.length() == 0 ? "The phone's screen settings are as they were." : said.toString().trim();
        }
    }

    static String status(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("on", false)) {
            return "No remote session is changing the phone's screen settings.";
        }
        StringBuilder sb = new StringBuilder("A remote session is changing the phone's screen settings: ");
        if (p.getBoolean("c" + T, false)) {
            sb.append("screen timeout ").append(timeoutText(p.getString("a" + T, "null")))
                    .append(" (was ").append(timeoutText(p.getString("o" + T, "null"))).append("); ");
        }
        if (p.getBoolean("c" + B, false)) {
            sb.append("brightness ").append(brightText(p.getString("a" + B, "null"), "0"))
                    .append(" (was ").append(brightText(p.getString("o" + B, "null"), p.getString("o" + M, "0"))).append("); ");
        }
        return sb.append("they are put back when the tablet leaves.").toString();
    }
}
