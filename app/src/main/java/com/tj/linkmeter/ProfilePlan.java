package com.tj.linkmeter;

/**
 * Pure Java (no Android classes), so it is tested on a computer: tools/test.sh.
 *
 * The decisions behind RemoteProfile: given the phone's three screen settings (timeout, brightness mode,
 * brightness), what a remote session saved earlier, and what the user asked for now, which settings to
 * write. Index 0 is the screen timeout in ms, 1 the brightness mode (0 manual, 1 automatic), 2 the
 * brightness (0 to 255). A value is text, "null" meaning the setting is not set.
 */
final class ProfilePlan {
    static final int T = 0;
    static final int M = 1;
    static final int B = 2;

    private ProfilePlan() {}

    /** What to do when a session starts or its options change. */
    static final class Begin {
        /** The values from before the session (kept from the first start). */
        final String[] orig = new String[3];
        /** Settings the session is changing. */
        final boolean[] chg = new boolean[3];
        /** What the session set them to. */
        final String[] now = new String[3];
        /** Values to write now, in this order; null = write nothing for that setting. */
        final String[] writes = new String[3];

        boolean any() {
            return chg[T] || chg[M] || chg[B];
        }
    }

    static long num(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** What to put a setting back to: its original, except the battery saver's timeout while the saver is on. */
    static String restoreValue(int i, String orig, boolean ecoOn, String ecoTimeout) {
        return (i == T && ecoOn) ? ecoTimeout : orig;
    }

    /**
     * @param on whether a session was already running, with savedOrig, savedChg and savedAppl as it stored them
     * @param cur the settings as they are right now
     * @param keep keep the screen on at least this many ms (0 = leave the timeout alone)
     * @param level brightness 0 to 255 to use (-1 = leave brightness alone)
     */
    static Begin begin(boolean on, String[] savedOrig, boolean[] savedChg, String[] savedAppl, String[] cur,
            long keep, int level, boolean ecoOn, String ecoTimeout) {
        Begin p = new Begin();
        String[] appl = new String[3]; // what we set, or null when we do not manage that setting
        for (int i = 0; i < 3; i++) {
            p.orig[i] = on ? savedOrig[i] : cur[i];
            appl[i] = (on && savedChg[i]) ? savedAppl[i] : null;
        }
        // What we want, worked out from the originals, so asking again with the same options changes nothing.
        String[] want = new String[3];
        if (keep > 0 && num(p.orig[T]) < keep) {
            want[T] = String.valueOf(keep);
        }
        if (level >= 0) {
            if (!"0".equals(p.orig[M])) {
                want[M] = "0";
            }
            want[B] = String.valueOf(level);
        }
        for (int i = 0; i < 3; i++) {
            if (want[i] == null) {
                // not wanted (any more): put it back if we changed it and nobody else has touched it since
                if (appl[i] != null && cur[i].equals(appl[i])) {
                    p.writes[i] = restoreValue(i, p.orig[i], ecoOn, ecoTimeout);
                }
            } else if (appl[i] == null) { // take it over
                p.writes[i] = want[i];
                p.chg[i] = true;
                p.now[i] = want[i];
            } else if (!cur[i].equals(appl[i])) {
                // the user changed it during the session: keep their choice and stop managing it
                continue;
            } else if (!want[i].equals(appl[i])) { // our own options changed
                p.writes[i] = want[i];
                p.chg[i] = true;
                p.now[i] = want[i];
            } else {
                p.chg[i] = true;
                p.now[i] = appl[i];
            }
        }
        return p;
    }

    /**
     * What to write when the session ends: per setting the value to put back, or null to leave it (we did not
     * change it, or the user changed it since).
     */
    static String[] end(boolean[] savedChg, String[] savedAppl, String[] savedOrig, String[] cur, boolean ecoOn,
            String ecoTimeout) {
        String[] w = new String[3];
        for (int i = 0; i < 3; i++) {
            if (savedChg[i] && cur[i].equals(savedAppl[i])) {
                w[i] = restoreValue(i, savedOrig[i], ecoOn, ecoTimeout);
            }
        }
        return w;
    }
}
