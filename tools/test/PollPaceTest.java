package com.tj.linkmeter;

/**
 * Offline test of PollPace: how often the Home tab asks the phone for its status. The rule that must never break: over Bluetooth
 * (where every question wakes the phone) it asks every 15 s only during the first minute after the user did something, then once
 * a minute, and stops after about five minutes; misses lengthen the wait; over the hotspot it never stops by itself.
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

    static void stated() {
        for (long base : BASES) {
            // over Bluetooth
            check(PollPace.next(base, true, 0, 0, false) == 15000, "Bluetooth, just pressed: 15 s (base " + base + ")");
            check(PollPace.next(base, true, MIN - 1, 0, false) == 15000, "Bluetooth, still the first minute: 15 s");
            check(PollPace.next(base, true, MIN, 0, false) == 60000, "Bluetooth, after the first minute: once a minute");
            check(PollPace.next(base, true, 5 * MIN - 1, 0, false) == 60000, "Bluetooth, just under five minutes: once a minute");
            check(PollPace.next(base, true, 5 * MIN, 0, false) == PollPace.STOP, "Bluetooth, five minutes: stops");
            check(PollPace.next(base, true, 60 * MIN, 0, false) == PollPace.STOP, "Bluetooth, an hour: still stopped");
            check(PollPace.next(base, true, 0, 1, false) == 30000, "Bluetooth, one miss: 30 s");
            check(PollPace.next(base, true, 0, 2, false) == 60000 && PollPace.next(base, true, 0, 9, false) == 60000, "Bluetooth, two or more misses: a minute");
            check(PollPace.next(base, true, 2 * MIN, 1, false) == 60000, "Bluetooth, a miss in the slow phase: still a minute");
            check(PollPace.next(base, true, 5 * MIN, 3, false) == PollPace.STOP, "Bluetooth, misses do not keep it going past five minutes");
            // over the hotspot (or with nothing to try)
            check(PollPace.next(base, false, 0, 0, false) == base && PollPace.next(base, false, 0, 2, false) == base, "hotspot: the chosen interval");
            check(PollPace.next(base, false, 0, 3, false) == 15000 && PollPace.next(base, false, 0, 5, false) == 15000, "hotspot, 3 misses: 15 s");
            check(PollPace.next(base, false, 0, 6, false) == 30000 && PollPace.next(base, false, 0, 9, false) == 30000, "hotspot, 6 misses: 30 s");
            check(PollPace.next(base, false, 0, 10, false) == 60000 && PollPace.next(base, false, 0, 500, false) == 60000, "hotspot, 10 misses: a minute");
            check(PollPace.next(base, false, 60 * MIN, 0, false) == base, "hotspot: the time since a press does not matter");
            // looking for the result of a switch
            check(PollPace.next(base, true, 0, 0, true) == FollowUp.EVERY_MS && PollPace.next(base, false, 0, 4, true) == FollowUp.EVERY_MS
                    && PollPace.next(base, true, 60 * MIN, 9, true) == FollowUp.EVERY_MS, "following a switch: every 3 s on either route");
        }
        check(PollPace.next(120000L, true, 0, 0, false) == 120000L && PollPace.next(120000L, false, 0, 12, false) == 120000L,
                "an interval longer than all of this is kept");
    }

    static void everywhere() {
        for (long base : BASES) {
            for (int misses = 0; misses <= 14; misses++) {
                long prev = 0;
                boolean stopped = false;
                for (long since = 0; since <= 8 * MIN; since += 2500) {
                    long bt = PollPace.next(base, true, since, misses, false);
                    long wifi = PollPace.next(base, false, since, misses, false);
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
                        long calm = PollPace.next(base, true, since, misses - 1, false);
                        check(calm == PollPace.STOP ? bt == PollPace.STOP : (bt == PollPace.STOP || bt >= calm), "Bluetooth: one more miss never shortens the wait");
                        check(wifi >= PollPace.next(base, false, since, misses - 1, false), "hotspot: one more miss never shortens the wait");
                    }
                }
                check(stopped, "Bluetooth always stops in the end");
            }
        }
    }

    /** How many times is a phone on Standby woken when the Home tab is simply left open? */
    static void aLeftOpenScreen() {
        for (long base : BASES) {
            long t = 0;
            int questions = 1; // the one when the tab opens
            while (true) {
                long wait = PollPace.next(base, true, t, 0, false);
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
                long wait = PollPace.next(base, true, t, misses, false);
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

    public static void main(String[] args) {
        stated();
        everywhere();
        aLeftOpenScreen();
        System.out.println(checks + " checks, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
