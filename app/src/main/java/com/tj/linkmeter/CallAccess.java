package com.tj.linkmeter;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import java.io.IOException;

/**
 * Phone only: may this app place, answer and end calls, see who rings, read the call log and use Bluetooth devices, and how to get
 * those permissions (docs/PHONE_CALLS.md). Like {@link SmsAccess}: a hand-installed app is "restricted" until that is allowed, and
 * through Shizuku that and the permissions themselves can be given in one go.
 */
final class CallAccess {
    private CallAccess() {}

    /** Placing a call. */
    static final String CALL = Manifest.permission.CALL_PHONE;
    /** Answering and ending a call. */
    static final String ANSWER = Manifest.permission.ANSWER_PHONE_CALLS;
    /** Seeing that the phone rings or is on a call (the manifest already holds it for the signal readings). */
    static final String STATE = Manifest.permission.READ_PHONE_STATE;
    /** The call log, and the number of a ringing call. */
    static final String LOG = Manifest.permission.READ_CALL_LOG;
    /** Finding and pairing a headset (Android 12 and newer). */
    static final String SCAN = "android.permission.BLUETOOTH_SCAN";
    /** Talking to a paired headset (Android 12 and newer). */
    static final String BT = Manifest.permission.BLUETOOTH_CONNECT;

    /** What the calls need, in the order the phone screen names them. */
    static final String[] PERMS = {CALL, ANSWER, STATE, LOG};

    /** What the phone's own question asks for (the Bluetooth ones only exist on Android 12 and newer). */
    static String[] ask() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[] {CALL, ANSWER, STATE, LOG, BT, SCAN};
        }
        return PERMS;
    }

    static boolean has(Context c, String perm) {
        return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    /** May LinkMeter use Bluetooth devices (find, pair, read the state of the headset)? Always, below Android 12. */
    static boolean bluetooth(Context c) {
        return Build.VERSION.SDK_INT < 31 || (has(c, BT) && has(c, SCAN));
    }

    private static String what(String perm) {
        switch (perm) {
            case CALL:
                return "place calls";
            case ANSWER:
                return "answer or end calls";
            case STATE:
                return "see that the phone rings";
            default:
                return "read the call list";
        }
    }

    /** What LinkMeter may not do yet for lack of a permission ("place calls, answer or end calls"); "" when everything is allowed. */
    static String missing(Context c) {
        StringBuilder missing = new StringBuilder();
        for (String p : PERMS) {
            if (!has(c, p)) {
                missing.append(missing.length() > 0 ? ", " : "").append(what(p));
            }
        }
        if (!bluetooth(c)) {
            missing.append(missing.length() > 0 ? ", " : "").append("use the headset");
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
                + PhoneScreenRules.CALLS_BUTTON + "\".";
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
        Priv.run("appops set " + pkg + " ACCESS_RESTRICTED_SETTINGS allow", 15);
        for (String p : ask()) {
            if (!has(c, p)) {
                Priv.Result r = Priv.run("pm grant " + pkg + " " + p, 15);
                if (!r.ok() || !has(c, p)) {
                    sb.append(p.substring(p.lastIndexOf('.') + 1)).append(": ").append(firstLine(r.out)).append('\n');
                }
            }
        }
        String left = note(c);
        return left.isEmpty() ? "Calls are allowed." : sb + left;
    }
}
