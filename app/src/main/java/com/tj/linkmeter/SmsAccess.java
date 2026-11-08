package com.tj.linkmeter;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import java.io.IOException;

/**
 * Phone only: may this app read and send texts and read the contacts, and how to get those permissions. A hand-installed app is
 * "restricted": Android will not let the user allow SMS permissions for it until restricted settings are allowed, and through Shizuku
 * that and the permissions themselves can be given in one go.
 */
final class SmsAccess {
    private SmsAccess() {}

    static final String[] PERMS = {Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS};

    static boolean has(Context c, String perm) {
        return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private static String what(String perm) {
        switch (perm) {
            case Manifest.permission.READ_SMS:
                return "read texts";
            case Manifest.permission.SEND_SMS:
                return "send texts";
            default:
                return "see contact names";
        }
    }

    /** What is not allowed yet, in a sentence for the tablet and the phone's screen; "" when everything is allowed. */
    static String note(Context c) {
        StringBuilder missing = new StringBuilder();
        for (String p : PERMS) {
            if (!has(c, p)) {
                missing.append(missing.length() > 0 ? ", " : "").append(what(p));
            }
        }
        if (missing.length() == 0) {
            return "";
        }
        return "The phone has not allowed LinkMeter to " + missing + " yet. On the phone, open LinkMeter's Phone tab and tap \"Allow texts access\".";
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        String l = (nl < 0 ? s : s.substring(0, nl)).trim();
        return l.length() > 160 ? l.substring(0, 160) : l;
    }

    /** Gives the app the permissions through Shizuku. Returns what happened, for a dialog on the phone. */
    static String grant(Context c) throws IOException {
        String pkg = c.getPackageName();
        StringBuilder sb = new StringBuilder();
        // an app installed by hand is "restricted" until this is allowed; phones without this setting just say so, which does not matter
        Priv.run("appops set " + pkg + " ACCESS_RESTRICTED_SETTINGS allow", 15);
        for (String p : PERMS) {
            if (!has(c, p)) {
                Priv.Result r = Priv.run("pm grant " + pkg + " " + p, 15);
                if (!r.ok() || !has(c, p)) {
                    sb.append(what(p)).append(": ").append(firstLine(r.out)).append('\n');
                }
            }
        }
        String left = note(c);
        return left.isEmpty() ? "Texts are allowed." : sb + left;
    }
}
