package com.tj.linkmeter;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.json.JSONObject;

/**
 * Phone only: lets the tablet update the phone's copy of this app. The tablet sends its own
 * installed copy over the hotspot, and the phone installs it over itself through Shizuku
 * (`pm install -r`). The file must be this same app, and Android itself refuses it unless it is
 * signed with the same key, so it can only ever be an update of LinkMeter.
 */
final class Updater {
    private Updater() {}

    private static File dir(Context c) {
        return c.getExternalFilesDir(null);
    }

    static long versionCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    static String versionName(Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return v == null ? "?" : v;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    /** Deletes a leftover update file. Called when the service starts. */
    static void cleanup(Context c) {
        File d = dir(c);
        if (d != null) {
            new File(d, "update.apk").delete();
        }
    }

    /** What the last install said ("Success" or the error), or empty if there was none. */
    static String lastResult(Context c) {
        File d = dir(c);
        if (d == null) {
            return "";
        }
        File log = new File(d, "install.log");
        if (!log.isFile() || log.length() > 4096) {
            return "";
        }
        try (InputStream in = new FileInputStream(log)) {
            byte[] b = new byte[(int) log.length()];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, "UTF-8").trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static void ok(OutputStream out, String text) throws IOException {
        Http.json(out, 200, "{\"ok\":true,\"out\":" + JSONObject.quote(text) + "}");
    }

    /** Handles POST /apk: the body is the new app file. */
    static void receive(Context c, Http.Req r, OutputStream out) throws IOException {
        if (!"POST".equals(r.method)) {
            Http.err(out, 400, "bad request");
            return;
        }
        if (!Priv.ready()) {
            Http.err(out, 409, "The phone can't install the update: " + Priv.stateText(Priv.state()) + ".");
            return;
        }
        File d = dir(c);
        if (d == null) {
            Http.err(out, 500, "The phone's storage is not available.");
            return;
        }
        if (r.length < 20000 || r.length > 40L * 1024 * 1024) {
            Http.err(out, 400, "That does not look like the app (size " + r.length + " bytes).");
            return;
        }
        File apk = new File(d, "update.apk");
        long left = r.length;
        try (OutputStream fo = new FileOutputStream(apk)) {
            byte[] buf = new byte[32 * 1024];
            while (left > 0) {
                int n = r.body.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) {
                    throw new IOException("the upload was cut short");
                }
                fo.write(buf, 0, n);
                left -= n;
            }
        } catch (IOException e) {
            apk.delete();
            throw e;
        }

        PackageInfo got = c.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (got == null || !c.getPackageName().equals(got.packageName)) {
            apk.delete();
            Http.err(out, 409, "That file is not the LinkMeter app.");
            return;
        }
        long have = versionCode(c);
        long want = got.getLongVersionCode();
        if (want < have) {
            apk.delete();
            Http.err(out, 409, "The tablet's copy (version code " + want + ") is older than the "
                    + "phone's (" + have + "). Install the newer version on the tablet first.");
            return;
        }
        if (want == have) {
            apk.delete();
            ok(out, "The phone already has this version (" + got.versionName + ").");
            return;
        }
        ok(out, "Sent " + (r.length / 1024) + " KB. The phone is installing version " + got.versionName
                + " now and the app restarts in a few seconds.");
        install(apk);
    }

    /** Installing replaces this very app, which kills it mid-command, so the result goes to a file. */
    private static void install(final File apk) {
        File log = new File(apk.getParentFile(), "install.log");
        // The installer hands its output channels to the system, and a file on shared storage was
        // refused ("Failed transaction"). So its output goes through a pipe, its input is empty, and
        // a second way (streaming the file in) is tried if the first one fails.
        final String tmp = "/data/local/tmp/lm-update.apk";
        final String cmd = "{ cp '" + apk.getAbsolutePath() + "' " + tmp
                + " && { pm install -r " + tmp + " < /dev/null 2>&1"
                + " || cat " + tmp + " | pm install -r -S " + apk.length() + " 2>&1; }"
                + "; rm -f " + tmp + "; } 2>&1 | cat > '" + log.getAbsolutePath() + "'";
        new Thread(() -> {
            try {
                Thread.sleep(800); // let the answer reach the tablet first
                Priv.run(cmd, 90);
            } catch (IOException | InterruptedException ignored) {
                // The app is usually killed by the install itself; nothing to report from here.
            }
        }, "linkmeter-update").start();
    }
}
