package com.tj.linkmeter;

/**
 * Offline test of PollPace: how often the Home tab asks the phone for its status. The rule that must never break: over Bluetooth
 * (where every question wakes the phone) it asks every 15 s only during the first minute after the user did something, then once
 * a minute, and stops after about five minutes; misses lengthen the wait; over the hotspot it never stops by itself. Time spent
 * on the hotspot is not counted: a screen that was open for an hour and has to ask over Bluetooth once does not stop on the spot.
 * Run by tools/test.sh. Prints "N checks, M failed". ASCII only.
 */
public final class PollPaceTest {
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

    static final long[] BASES = {2000L, 5000L, 15000L};
    static final long MIN = 60_000L;
    /** "Over Bluetooth all along": longer than anything the rule looks at, so only the time since the last press counts. */
    static final long ALL_ALONG = 24 * 60 * MIN;

    /** Over Bluetooth ever since the user last did something (the screen was opened while the hotspot was off, say). */
    static long bt(long base, long since, int misses) {
        return PollPace.next(base, true, since, ALL_ALONG, misses, false);
    }

    static long wifi(long base, long since, int misses) {
        return PollPace.next(base, false, since, 0, misses, false);
    }

    static void stated() {
        for (long base : BASES) {
            // over Bluetooth
            check(bt(base, 0, 0) == 15000, "Bluetooth, just pressed: 15 s (base " + base + ")");
            check(bt(base, MIN - 1, 0) == 15000, "Bluetooth, still the first minute: 15 s");
            check(bt(base, MIN, 0) == 60000, "Bluetooth, after the first minute: once a minute");
            check(bt(base, 5 * MIN - 1, 0) == 60000, "Bluetooth, just under five minutes: once a minute");
            check(bt(base, 5 * MIN, 0) == PollPace.STOP, "Bluetooth, five minutes: stops");
            check(bt(base, 60 * MIN, 0) == PollPace.STOP, "Bluetooth, an hour: still stopped");
            check(bt(base, 0, 1) == 30000, "Bluetooth, one miss: 30 s");
            check(bt(base, 0, 2) == 60000 && bt(base, 0, 9) == 60000, "Bluetooth, two or more misses: a minute");
            check(bt(base, 2 * MIN, 1) == 60000, "Bluetooth, a miss in the slow phase: still a minute");
            check(bt(base, 5 * MIN, 3) == PollPace.STOP, "Bluetooth, misses do not keep it going past five minutes");
            // over the hotspot (or with nothing to try)
            check(wifi(base, 0, 0) == base && wifi(base, 0, 2) == base, "hotspot: the chosen interval");
            check(wifi(base, 0, 3) == 15000 && wifi(base, 0, 5) == 15000, "hotspot, 3 misses: 15 s");
            check(wifi(base, 0, 6) == 30000 && wifi(base, 0, 9) == 30000, "hotspot, 6 misses: 30 s");
            check(wifi(base, 0, 10) == 60000 && wifi(base, 0, 500) == 60000, "hotspot, 10 misses: a minute");
            check(wifi(base, 60 * MIN, 0) == base, "hotspot: the time since a press does not matter");
            // Bluetooth comes into use on a screen nobody has touched for a long time (it was on the hotspot until now)
            check(PollPace.next(base, true, 60 * MIN, 0, 0, false) == 15000, "an hour on the hotspot, then the first question over Bluetooth: 15 s, not a stop");
            check(PollPace.next(base, true, 5 * MIN, 0, 1, false) == 30000, "the same when that first Bluetooth call failed: 30 s, not a stop");
            check(PollPace.next(base, true, 60 * MIN, MIN - 1, 0, false) == 15000, "the first minute over Bluetooth: 15 s");
            check(PollPace.next(base, true, 60 * MIN, MIN, 0, false) == 60000, "after a minute over Bluetooth: once a minute");
            check(PollPace.next(base, true, 60 * MIN, 5 * MIN - 1, 0, false) == 60000, "just under five minutes over Bluetooth: once a minute");
            check(PollPace.next(base, true, 60 * MIN, 5 * MIN, 0, false) == PollPace.STOP, "five minutes over Bluetooth with nobody at the screen: stops");
            check(PollPace.next(base, true, 0, 5 * MIN, 0, false) == 15000, "a press after a long time over Bluetooth: 15 s again");
            // looking for the result of a switch
            check(PollPace.next(base, true, 0, 0, 0, true) == FollowUp.EVERY_MS && PollPace.next(base, false, 0, 0, 4, true) == FollowUp.EVERY_MS
                    && PollPace.next(base, true, 60 * MIN, 60 * MIN, 9, true) == FollowUp.EVERY_MS, "following a switch: every 3 s on either route");
        }
        check(bt(120000L, 0, 0) == 120000L && wifi(120000L, 0, 12) == 120000L,
                "an interval longer than all of this is kept");
    }

    static void everywhere() {
        for (long base : BASES) {
            for (int misses = 0; misses <= 14; misses++) {
                long prev = 0;
                boolean stopped = false;
                for (long since = 0; since <= 8 * MIN; since += 2500) {
                    long bt = bt(base, since, misses);
                    long wifi = wifi(base, since, misses);
                    check(wifi != PollPace.STOP && wifi >= base && wifi <= 60000, "the hotspot route never stops by itself and stays between the base and a minute: " + wifi);
                    if (bt == PollPace.STOP) {
                        stopped = true;
                        check(since >= 5 * MIN, "Bluetooth stops only after five minutes: " + since);
                    } else {
                        check(!stopped, "once stopped, more waiting does not start it again: " + since);
                        check(bt >= 15000 && bt >= base && bt <= 60000, "Bluetooth: never more often than every 15 s, never less often than once a minute: " + bt);
                        check(bt >= prev, "Bluetooth: more time since the last press never shortens the wait: " + prev + " -> " + bt);
                        check(since < MIN || bt == 60000, "Bluetooth after the first minute: once a minute: " + bt);
                        prev = bt;
                    }
                    if (misses > 0) {
                        long calm = bt(base, since, misses - 1);
                        check(calm == PollPace.STOP ? bt == PollPace.STOP : (bt == PollPace.STOP || bt >= calm), "Bluetooth: one more miss never shortens the wait");
                        check(wifi >= wifi(base, since, misses - 1), "hotspot: one more miss never shortens the wait");
                    }
                }
                check(stopped, "Bluetooth always stops in the end");
            }
        }
        // The two clocks: what counts is the shorter of "since the last press" and "since Bluetooth came into use". Either
        // one alone under five minutes keeps the questions going; both over it stop them.
        long[] times = {0, 1, 14_999, MIN - 1, MIN, 2 * MIN, 5 * MIN - 1, 5 * MIN, 5 * MIN + 1, 60 * MIN, ALL_ALONG};
        for (long base : BASES) {
            for (int misses = 0; misses <= 3; misses++) {
                for (long since : times) {
                    for (long onBt : times) {
                        long got = PollPace.next(base, true, since, onBt, misses, false);
                        long shorter = Math.min(since, onBt);
                        check(got == PollPace.next(base, true, shorter, shorter, misses, false),
                                "Bluetooth: only the shorter of the two times counts (" + since + ", " + onBt + ")");
                        check((got == PollPace.STOP) == (since >= 5 * MIN && onBt >= 5 * MIN),
                                "Bluetooth stops exactly when BOTH times have reached five minutes (" + since + ", " + onBt + "): " + got);
                        check(PollPace.next(base, false, since, onBt, misses, false) == wifi(base, 0, misses),
                                "hotspot: neither time matters");
                    }
                }
            }
        }
    }

    /** How many times is a phone on Standby woken when the Home tab is simply left open? */
    static void aLeftOpenScreen() {
        for (long base : BASES) {
            long t = 0;
            int questions = 1; // the one when the tab opens
            while (true) {
                long wait = bt(base, t, 0);
                if (wait == PollPace.STOP) {
                    break;
                }
                t += wait;
                questions++;
                if (questions > 1000) {
                    break;
                }
            }
            check(questions <= 10, "a screen left open asks at most ten times over Bluetooth, then leaves the phone alone (asked " + questions + ")");
            check(t >= 5 * MIN && t <= 6 * MIN, "and it stops after about five minutes (" + t / 1000 + " s)");
            // the phone does not answer at all (out of range): fewer still
            t = 0;
            questions = 1;
            int misses = 1;
            while (questions < 1000) {
                long wait = bt(base, t, misses);
                if (wait == PollPace.STOP) {
                    break;
                }
                t += wait + 14000; // each failed call takes up to 14 s itself
                questions++;
                misses++;
            }
            check(questions <= 6, "a phone out of range is called at most six times (called " + questions + ")");
        }
    }

    /**
     * The Home tab as HomeTab drives it: open for an hour on the hotspot, then the hotspot route fails for some questions and
     * Bluetooth carries them. (The screen is never touched: the time since the last press only grows.)
     */
    static void anHourOnTheHotspotFirst() {
        for (long base : BASES) {
            long opened = 0;
            long t = 60 * MIN;
            // one question has to go over Bluetooth, and is answered: the screen must ask again, soon
            long wait = PollPace.next(base, true, t - opened, 0, 0, false);
            check(wait != PollPace.STOP && wait <= 15000, "one question over Bluetooth after an hour on the hotspot: asks again within 15 s (" + wait + ")");
            // the hotspot stays away: from here on it is the left-open screen again, with its five minutes
            long btSince = t;
            int questions = 1;
            while (questions < 1000) {
                wait = PollPace.next(base, true, t - opened, t - btSince, 0, false);
                if (wait == PollPace.STOP) {
                    break;
                }
                t += wait;
                questions++;
            }
            check(questions <= 10, "the hotspot stays away: at most ten questions over Bluetooth (asked " + questions + ")");
            check(t - btSince >= 5 * MIN && t - btSince <= 6 * MIN, "and they stop about five minutes after Bluetooth came into use (" + (t - btSince) / 1000 + " s)");
        }
    }

    public static void main(String[] args) {
        stated();
        everywhere();
        aLeftOpenScreen();
        anHourOnTheHotspotFirst();
        System.out.println(checks + " checks, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
