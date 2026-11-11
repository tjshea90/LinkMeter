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

    /**
     * Saving and renaming a contact (from the tablet's Contacts screen) needs this as well. Texts do not, so it is NOT in
     * {@link #PERMS}: a phone without it is not "missing" anything for texts, and the tablet says so when it is the one thing lacking.
     */
    static final String WRITE_CONTACTS = Manifest.permission.WRITE_CONTACTS;

    /** What the phone's own question asks for: the texts permissions and the one for contacts (the same Android group: one question). */
    static final String[] ASK = {Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS};

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

    /** What LinkMeter may not do yet for lack of a permission ("read texts, send texts"); "" when everything is allowed. */
    static String missing(Context c) {
        StringBuilder missing = new StringBuilder();
        for (String p : PERMS) {
            if (!has(c, p)) {
                missing.append(missing.length() > 0 ? ", " : "").append(what(p));
            }
        }
        return missing.toString();
    }

    /** What is not allowed yet, in a sentence for the tablet (it says where to fix it); "" when everything is allowed. */
    static String note(Context c) {
        String missing = missing(c);
        if (missing.isEmpty()) {
            return "";
        }
        return "The phone has not allowed LinkMeter to " + missing + " yet. On the phone, open LinkMeter's Phone tab and tap \""
                + PhoneScreenRules.TEXTS_BUTTON + "\".";
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
        if (has(c, Manifest.permission.READ_CONTACTS) && !has(c, WRITE_CONTACTS)) {
            Priv.run("pm grant " + pkg + " " + WRITE_CONTACTS, 15); // so that the tablet can save a contact here; texts work without it
        }
        String left = note(c);
        return left.isEmpty() ? "Texts are allowed." : sb + left;
    }
}
