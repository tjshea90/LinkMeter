package com.tj.linkmeter;

/**
 * Offline test of BootPlan (what the phone does by itself after a restart: switch wireless debugging on, post the Shizuku notification).
 * Every combination of the inputs is tried and the rules are checked on each. Run by tools/test.sh. Prints "N checks, M failed".
 */
public final class BootPlanTest {
    static int checks;
    static int failed;

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            failed++;
            if (failed <= 25) {
                System.out.println("  FAIL: " + msg);
            }
        }
    }

    static final long NOW = 100L * 60 * 1000;

    public static void main(String[] args) {
        exhaustive();
        stories();
        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static String show(BootPlan.In i) {
        return "assist=" + i.assist + " priv=" + i.priv + " wifi=" + i.wifi + " write=" + i.canWriteSecure + " wadb=" + i.wadbOn
                + " dev=" + i.devOptionsOn + " notified=" + i.notified + " notifiedAt=" + (i.notifiedAt == 0 ? "never" : (NOW - i.notifiedAt) + "ms ago")
                + " tries=" + i.wadbTries + " lastWadb=" + (i.lastWadbAt == 0 ? "never" : (NOW - i.lastWadbAt) + "ms ago")
                + " listening=" + (NOW - i.listenSince) + "ms";
    }

    static void exhaustive() {
        long[] notifiedAts = {0, NOW - 1000, NOW - BootPlan.REPOST_MS + 1, NOW - BootPlan.REPOST_MS, NOW - BootPlan.REPOST_MS - 1};
        long[] lastWadbs = {0, NOW - 1000, NOW - BootPlan.WADB_GAP_MS + 1, NOW - BootPlan.WADB_GAP_MS, NOW - BootPlan.WADB_GAP_MS - 1};
        long[] listens = {NOW - 1000, NOW - BootPlan.LISTEN_MS, NOW - BootPlan.LISTEN_MS - 1};
        int[] tries = {0, 1, BootPlan.MAX_WADB_TRIES - 1, BootPlan.MAX_WADB_TRIES, BootPlan.MAX_WADB_TRIES + 3};
        int cases = 0;
        for (int mask = 0; mask < 64; mask++) {
            for (int priv = 0; priv <= 2; priv++) {
                for (long na : notifiedAts) {
                    for (long lw : lastWadbs) {
                        for (long ls : listens) {
                            for (int tr : tries) {
                                for (int notified = 0; notified <= 1; notified++) {
                                    BootPlan.In i = new BootPlan.In();
                                    i.assist = (mask & 1) != 0;
                                    i.wifi = (mask & 2) != 0;
                                    i.canWriteSecure = (mask & 4) != 0;
                                    i.wadbOn = (mask & 8) != 0;
                                    i.devOptionsOn = (mask & 16) != 0;
                                    i.priv = priv;
                                    i.now = NOW;
                                    i.notifiedAt = na;
                                    i.lastWadbAt = lw;
                                    i.listenSince = ls;
                                    i.wadbTries = tr;
                                    i.notified = notified == 1;
                                    if ((mask & 32) != 0) {
                                        i.notified = !i.notified; // covers both values with the notifiedAt variants
                                    }
                                    cases++;
                                    rules(i, BootPlan.decide(i));
                                }
                            }
                        }
                    }
                }
            }
        }
        check(cases > 40000, "many combinations were tried: " + cases);
    }

    static void rules(BootPlan.In i, BootPlan.Out o) {
        String w = show(i) + " -> wadb=" + o.enableWadb + " dev=" + o.enableDevOptions + " post=" + o.post + " cancel=" + o.cancelNotice
                + " stop=" + o.stopListening + " (" + o.why + ")";
        boolean listening = NOW - i.listenSince <= BootPlan.LISTEN_MS;
        // never fights the user: nothing at all while the switch is off, and the old notification goes
        if (!i.assist) {
            check(!o.enableWadb && !o.enableDevOptions && o.post == BootPlan.Notice.NONE, "switch off: no action: " + w);
            check(o.cancelNotice == i.notified && o.stopListening, "switch off: the notification goes, no listening: " + w);
            return;
        }
        // nothing to do once Shizuku runs
        if (i.priv == BootPlan.PRIV_READY) {
            check(!o.enableWadb && !o.enableDevOptions && o.post == BootPlan.Notice.NONE, "Shizuku runs: no action: " + w);
            check(o.cancelNotice == i.notified && o.stopListening, "Shizuku runs: the notification goes, no listening: " + w);
            return;
        }
        // gives up after a long time
        if (!listening) {
            check(!o.enableWadb && !o.enableDevOptions && o.post == BootPlan.Notice.NONE && o.stopListening && !o.cancelNotice,
                    "listened too long: nothing more, the notification stays: " + w);
            return;
        }
        check(!o.stopListening, "still listening: " + w);
        check(!o.cancelNotice, "the notification stays while Shizuku is off: " + w);
        boolean repost = !i.notified && (i.notifiedAt == 0 || NOW - i.notifiedAt >= BootPlan.REPOST_MS);
        check((o.post != BootPlan.Notice.NONE) == repost, "the notification is posted exactly when it is not shown and was not posted lately: " + w);
        if (o.post != BootPlan.Notice.NONE) {
            check((o.post == BootPlan.Notice.NEEDS_ALLOW) == (i.priv == BootPlan.PRIV_NO_PERM), "the words match the Shizuku state: " + w);
        }
        boolean wadb = i.wifi && i.canWriteSecure && !i.wadbOn && i.wadbTries < BootPlan.MAX_WADB_TRIES
                && (i.lastWadbAt == 0 || NOW - i.lastWadbAt >= BootPlan.WADB_GAP_MS);
        check(o.enableWadb == wadb, "wireless debugging is switched on exactly when allowed and needed: " + w);
        check(o.enableDevOptions == (wadb && !i.devOptionsOn), "developer options only together with it and only if off: " + w);
        if (o.enableWadb) {
            check(i.assist && i.priv != BootPlan.PRIV_READY && i.wifi && i.canWriteSecure, "never without Wi-Fi, the permission, the switch: " + w);
        }
    }

    static void stories() {
        // a phone that has just restarted: switch on, Wi-Fi joined, permission held, Shizuku off
        BootPlan.In i = new BootPlan.In();
        i.assist = true;
        i.priv = BootPlan.PRIV_OFF;
        i.wifi = true;
        i.canWriteSecure = true;
        i.now = NOW;
        i.listenSince = NOW - 5000;
        BootPlan.Out o = BootPlan.decide(i);
        check(o.enableWadb && o.enableDevOptions && o.post == BootPlan.Notice.NOT_RUNNING, "a restarted phone on Wi-Fi: wireless debugging and the notification");
        // the second try comes no sooner than the gap
        i.lastWadbAt = NOW - 3000;
        i.wadbTries = 1;
        i.notified = true;
        i.notifiedAt = NOW - 3000;
        o = BootPlan.decide(i);
        check(!o.enableWadb && o.post == BootPlan.Notice.NONE, "too soon for a second try");
        i.now = NOW + BootPlan.WADB_GAP_MS;
        o = BootPlan.decide(i);
        check(o.enableWadb, "a second try after the gap");
        // after five tries it stops hammering
        i.wadbTries = BootPlan.MAX_WADB_TRIES;
        o = BootPlan.decide(i);
        check(!o.enableWadb, "no sixth try");
        // Shizuku is up: everything goes quiet
        i.priv = BootPlan.PRIV_READY;
        o = BootPlan.decide(i);
        check(!o.enableWadb && o.cancelNotice && o.stopListening, "Shizuku is up: the notification is taken back and listening stops");
        // Shizuku runs but LinkMeter is not allowed: different words
        i = new BootPlan.In();
        i.assist = true;
        i.priv = BootPlan.PRIV_NO_PERM;
        i.now = NOW;
        i.listenSince = NOW - 1000;
        o = BootPlan.decide(i);
        check(o.post == BootPlan.Notice.NEEDS_ALLOW && !o.enableWadb, "Shizuku runs, not allowed: ask for Allow, no wireless debugging");
    }
}
