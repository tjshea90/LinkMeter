package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/BootPlanTest.java). What the PHONE does by itself after it restarts, with no tablet to ask:
 * switch wireless debugging on (so Shizuku can be started) and post the notification that opens Shizuku. It runs only while the user's
 * "Boot assist" switch is on and only while Shizuku is not running, and it never acts more often than the limits below.
 */
final class BootPlan {
    private BootPlan() {}

    static final int PRIV_OFF = 0;     // same numbers as Priv.OFF / NO_PERM / READY
    static final int PRIV_NO_PERM = 1;
    static final int PRIV_READY = 2;

    /** Wireless debugging is switched on at most this often, and at most {@link #MAX_WADB_TRIES} times per Wi-Fi connection. */
    static final long WADB_GAP_MS = 10_000L;
    static final int MAX_WADB_TRIES = 5;
    /** The notification is posted again (after the user swiped it away) no sooner than this. */
    static final long REPOST_MS = 5L * 60 * 1000;
    /** Boot assist stops listening for Wi-Fi after this long (the phone has been up that long without Shizuku: the user is not doing it now). */
    static final long LISTEN_MS = 20L * 60 * 1000;

    enum Notice { NONE, NOT_RUNNING, NEEDS_ALLOW }

    static final class In {
        boolean assist;
        int priv;
        boolean wifi;
        boolean canWriteSecure;
        boolean wadbOn;
        boolean devOptionsOn;
        long now;
        long listenSince;
        long lastWadbAt;
        int wadbTries;
        boolean notified;
        long notifiedAt;
    }

    static final class Out {
        boolean enableDevOptions;
        boolean enableWadb;
        Notice post = Notice.NONE;
        boolean cancelNotice;
        boolean stopListening;
        String why = "";
    }

    static Out decide(In i) {
        Out o = new Out();
        if (!i.assist) {
            o.cancelNotice = i.notified;
            o.stopListening = true;
            o.why = "Boot assist is off";
            return o;
        }
        if (i.priv == PRIV_READY) {
            o.cancelNotice = i.notified;
            o.stopListening = true;
            o.why = "Shizuku is running";
            return o;
        }
        if (i.now - i.listenSince > LISTEN_MS) {
            o.stopListening = true;
            o.why = "Shizuku has not been started for a long time: Boot assist stops waiting";
            return o;
        }
        if (!i.notified && (i.notifiedAt == 0 || i.now - i.notifiedAt >= REPOST_MS)) {
            o.post = i.priv == PRIV_NO_PERM ? Notice.NEEDS_ALLOW : Notice.NOT_RUNNING;
        }
        if (i.wifi && i.canWriteSecure && !i.wadbOn && i.wadbTries < MAX_WADB_TRIES
                && (i.lastWadbAt == 0 || i.now - i.lastWadbAt >= WADB_GAP_MS)) {
            o.enableWadb = true;
            o.enableDevOptions = !i.devOptionsOn;
            o.why = "Wi-Fi is up and wireless debugging is off";
        } else if (!o.enableWadb && o.post == Notice.NONE) {
            o.why = "nothing to do now";
        }
        return o;
    }
}
