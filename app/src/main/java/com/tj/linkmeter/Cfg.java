package com.tj.linkmeter;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

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

    // ------------------------------------------------------------ trust

    /**
     * This device's own secret. The tablet sends it with every request. The phone
     * keeps the secrets of the tablets it trusts. No numbers are ever typed.
     */
    static String token(Context c) {
        SharedPreferences p = sp(c);
        String t = p.getString("token", "");
        if (t.length() != 32) {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) {
                sb.append(String.format(Locale.US, "%02x", x));
            }
            t = sb.toString();
            p.edit().putString("token", t).apply();
        }
        return t;
    }

    /** Tablet: make a fresh secret. The phone must pair with it again. */
    static void resetToken(Context c) {
        sp(c).edit().remove("token").apply();
    }

    /** Phone: secrets of paired tablets. */
    static Set<String> trusted(Context c) {
        return new HashSet<>(sp(c).getStringSet("trusted", new HashSet<String>()));
    }

    static void addTrusted(Context c, String token) {
        Set<String> s = trusted(c);
        s.add(token);
        sp(c).edit().putStringSet("trusted", s).apply();
    }

    static void clearTrusted(Context c) {
        sp(c).edit().remove("trusted").remove("tabIp").remove("tabTok").apply();
    }

    /** Phone: the tablet that last talked to us, so the phone can browse the tablet's files. */
    static void setTablet(Context c, String ip, String token) {
        SharedPreferences p = sp(c);
        if (ip.equals(p.getString("tabIp", "")) && token.equals(p.getString("tabTok", ""))) {
            return;
        }
        p.edit().putString("tabIp", ip).putString("tabTok", token).apply();
    }

    static String tabletIp(Context c) {
        return sp(c).getString("tabIp", "");
    }

    static String tabletToken(Context c) {
        return sp(c).getString("tabTok", "");
    }

    // ------------------------------------------------------------ tablet options

    /** Tablet: phone address typed by hand. Empty means "use the hotspot gateway". */
    static String host(Context c) {
        return sp(c).getString("host", "");
    }

    static void setHost(Context c, String host) {
        sp(c).edit().putString("host", host).apply();
    }

    /** Tablet: how often to ask the phone while the screen is open. 5 s by default. */
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

    /** Tablet: also run the file server so the phone can browse this tablet. */
    static boolean shareFiles(Context c) {
        return sp(c).getBoolean("share", false);
    }

    static void setShareFiles(Context c, boolean on) {
        sp(c).edit().putBoolean("share", on).apply();
    }

    // ------------------------------------------------------------ phone options

    /** Phone: start the sender when the phone boots. */
    static boolean autoStart(Context c) {
        return sp(c).getBoolean("autostart", true);
    }

    static void setAutoStart(Context c, boolean on) {
        sp(c).edit().putBoolean("autostart", on).apply();
    }

    /** Phone: switch on the battery-saving profile by itself when the tablet connects. */
    /** Phone: may the paired tablet see and control the screen? On by default. */
    static boolean screenControl(Context c) {
        return sp(c).getBoolean("screenCtl", true);
    }

    static void setScreenControl(Context c, boolean on) {
        sp(c).edit().putBoolean("screenCtl", on).apply();
    }

    static boolean ecoAuto(Context c) {
        return sp(c).getBoolean("ecoAuto", true);
    }

    static void setEcoAuto(Context c, boolean on) {
        sp(c).edit().putBoolean("ecoAuto", on).apply();
    }
}
