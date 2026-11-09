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
