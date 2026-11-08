package com.tj.linkmeter;

import android.content.pm.PackageManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import rikka.shizuku.Shizuku;

/**
 * Phone only. Runs shell commands as the "shell" user through Shizuku, which is
 * what lets the app do things a normal app cannot (airplane mode, power off,
 * network type, system settings). Nothing here runs unless the tablet asks.
 */
final class Priv {
    static final int OFF = 0;      // Shizuku is not running
    static final int NO_PERM = 1;  // Shizuku is running, this app is not allowed yet
    static final int READY = 2;

    private static final Pattern RC = Pattern.compile("\\[rc=(\\d+)]\\s*$");

    private Priv() {}

    static final class Result {
        int rc = -1;
        String out = "";
        boolean timedOut;

        boolean ok() {
            return rc == 0 && !timedOut;
        }
    }

    static int state() {
        try {
            if (!Shizuku.pingBinder()) {
                return OFF;
            }
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    ? READY : NO_PERM;
        } catch (Throwable t) {
            return OFF;
        }
    }

    static boolean ready() {
        return state() == READY;
    }

    static String stateText(int s) {
        switch (s) {
            case READY:
                return "Shizuku is ready";
            case NO_PERM:
                return "Shizuku is running but LinkMeter is not allowed yet";
            default:
                return "Shizuku is not running";
        }
    }

    /** Shows Shizuku's own permission prompt. Must be called from the app screen. */
    static void requestPermission(int code) {
        try {
            Shizuku.requestPermission(code);
        } catch (Throwable ignored) {
            // Shizuku not running: the screen already says so.
        }
    }

    /** Runs one shell command line. Output includes errors. Blocks up to timeoutSec. */
    static Result run(String cmd, int timeoutSec) throws IOException {
        if (state() != READY) {
            throw new IOException(stateText(state()));
        }
        final Process p = start(new String[] {"sh", "-c", "(" + cmd + ") 2>&1; echo \"[rc=$?]\""});
        final ByteArrayOutputStream bo = new ByteArrayOutputStream();
        Thread t = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[2048];
                int r;
                while ((r = in.read(buf)) > 0) {
                    synchronized (bo) {
                        if (bo.size() < 200000) {
                            bo.write(buf, 0, r);
                        }
                    }
                }
            } catch (IOException ignored) {
                // process ended or was destroyed
            }
        }, "linkmeter-priv-read");
        t.start();
        try {
            t.join(timeoutSec * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Result res = new Result();
        if (t.isAlive()) {
            res.timedOut = true;
            try {
                p.destroy();
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
        String out;
        synchronized (bo) {
            out = bo.toString("UTF-8");
        }
        Matcher m = RC.matcher(out);
        if (m.find()) {
            try {
                res.rc = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                res.rc = -1;
            }
            out = out.substring(0, m.start());
        }
        res.out = out.trim();
        return res;
    }

    /** Shizuku keeps newProcess private in current versions, so it is called by reflection. */
    private static Process start(String[] cmd) throws IOException {
        try {
            Method m = Shizuku.class.getDeclaredMethod("newProcess",
                    String[].class, String[].class, String.class);
            m.setAccessible(true);
            return (Process) m.invoke(null, cmd, null, null);
        } catch (ReflectiveOperationException e) {
            throw new IOException("Shizuku could not start the command: " + e);
        }
    }
}
