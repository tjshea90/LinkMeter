package com.tj.linkmeter;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Remembers how the phone was before LinkMeter first changed something, so "Restore everything"
 * can put it back exactly. Cleared again once the restore has worked.
 */
final class Saved {
    private Saved() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("restore", Context.MODE_PRIVATE);
    }

    /** The allowed network types before the first change. Only the first call is kept. */
    static void rememberTypes(Context c, long mask) {
        SharedPreferences p = sp(c);
        if (!p.contains("nt")) {
            p.edit().putLong("nt", mask).apply();
        }
    }

    static long types(Context c) {
        return sp(c).getLong("nt", -1);
    }

    static void clearTypes(Context c) {
        sp(c).edit().remove("nt").apply();
    }

    /**
     * LinkMeter has switched the 5G leg (NR dual connectivity) off. Written BEFORE the change, so a session that is cut off in
     * the middle is still undone: Restore everything (and the safety net) switch the leg back on while this is set.
     */
    static void rememberNrLegChanged(Context c) {
        sp(c).edit().putBoolean("nrleg", true).apply();
    }

    static boolean nrLegChanged(Context c) {
        return sp(c).getBoolean("nrleg", false);
    }

    static void clearNrLeg(Context c) {
        sp(c).edit().remove("nrleg").apply();
    }

    // ---- the other things the safety net must be able to undo, even after the app was killed or the phone restarted

    /** A band lock is (or may be) on. Written BEFORE the lock is asked for, cleared when it is gone. */
    static void rememberLock(Context c) {
        sp(c).edit().putBoolean("lock", true).apply();
    }

    static boolean lockOn(Context c) {
        return sp(c).getBoolean("lock", false);
    }

    static void clearLock(Context c) {
        sp(c).edit().remove("lock").apply();
    }

    /**
     * A deliberate short radio-off (the radio restart, the airplane-mode blip behind "Hotspot off") started at this wall-clock time.
     * Written BEFORE the radio goes off and cleared once it is confirmed back on: if the app dies in between, the next start finds
     * it and switches the radio back on. 0 = none.
     */
    static void rememberBlip(Context c, long at) {
        sp(c).edit().putLong("blip", at).apply();
    }

    static long blip(Context c) {
        return sp(c).getLong("blip", 0);
    }

    static void clearBlip(Context c) {
        sp(c).edit().remove("blip").apply();
    }

    /** Airplane mode was turned on from the tablet and ends by itself at this wall-clock time (the tablet cannot end it: the link is down). 0 = none. */
    static void rememberAirplaneUntil(Context c, long at) {
        sp(c).edit().putLong("airUntil", at).apply();
    }

    static long airplaneUntil(Context c) {
        return sp(c).getLong("airUntil", 0);
    }

    static void clearAirplaneUntil(Context c) {
        sp(c).edit().remove("airUntil").apply();
    }

    /** How long after opening a service menu the safety net keeps watching. */
    static final long MENU_WATCH_MS = 2L * 60 * 60 * 1000;

    /** A service menu (or the dialer with a service code) was opened at this wall-clock time: what is done inside it is not seen by LinkMeter. */
    static void rememberMenuAt(Context c, long at) {
        sp(c).edit().putLong("menuAt", at).apply();
    }

    static void clearMenu(Context c) {
        sp(c).edit().remove("menuAt").apply();
    }

    static boolean menuRecent(Context c) {
        long at = sp(c).getLong("menuAt", 0);
        long now = System.currentTimeMillis();
        return at > 0 && now >= at && now - at < MENU_WATCH_MS;
    }

    /** Is anything LinkMeter changed still in place that could stop mobile data? Then the safety net keeps watching. */
    static boolean pendingRadio(Context c) {
        return types(c) > 0 || nrLegChanged(c) || lockOn(c) || blip(c) > 0 || airplaneUntil(c) > 0 || menuRecent(c);
    }

    /** The hotspot band before the first change: 1 = 2.4 GHz, 2 = 5 GHz, 3 = either. */
    static void rememberHotspotBand(Context c, int band) {
        SharedPreferences p = sp(c);
        if (!p.contains("hb") && band > 0) {
            p.edit().putInt("hb", band).apply();
        }
    }

    static int hotspotBand(Context c) {
        return sp(c).getInt("hb", -1);
    }

    static void clearHotspotBand(Context c) {
        sp(c).edit().remove("hb").apply();
    }
}
