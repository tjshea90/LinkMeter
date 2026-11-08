package com.tj.linkmeter;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.SecureRandom;
import java.util.Locale;

/** Everything the app remembers lives here. Tiny on purpose. */
final class Cfg {
    static final int PORT = 8765;
    static final String PHONE = "phone";
    static final String TABLET = "tablet";

    private Cfg() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    static String mode(Context c) {
        return sp(c).getString("mode", "");
    }

    static void setMode(Context c, String mode) {
        sp(c).edit().putString("mode", mode).apply();
    }

    /** 4-digit pairing code. The phone makes one; the tablet has it typed in. */
    static String code(Context c) {
        return sp(c).getString("code", "");
    }

    static void setCode(Context c, String code) {
        sp(c).edit().putString("code", code).apply();
    }

    /** Phone side: make a code the first time, then keep it. */
    static String ensureCode(Context c) {
        String code = code(c);
        if (code.length() != 4) {
            code = String.format(Locale.US, "%04d", new SecureRandom().nextInt(10000));
            setCode(c, code);
        }
        return code;
    }

    /** Tablet side: phone address typed by hand. Empty means "use the hotspot gateway". */
    static String host(Context c) {
        return sp(c).getString("host", "");
    }

    static void setHost(Context c, String host) {
        sp(c).edit().putString("host", host).apply();
    }

    /** Tablet side: how often to ask the phone. 5 s by default. */
    static long intervalMs(Context c) {
        return sp(c).getLong("interval", 5000L);
    }

    static void setIntervalMs(Context c, long ms) {
        sp(c).edit().putLong("interval", ms).apply();
    }

    static boolean keepOn(Context c) {
        return sp(c).getBoolean("keepOn", false);
    }

    static void setKeepOn(Context c, boolean on) {
        sp(c).edit().putBoolean("keepOn", on).apply();
    }
}
