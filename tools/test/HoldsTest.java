package com.tj.linkmeter;

import java.util.Random;

/**
 * Offline test of Holds: the list of who keeps the phone awake. A pretend wake lock (one switch, one timer, exactly what Android
 * gives) is driven with what Holds answers, next to a plain list of who needs the processor until when. What must hold after
 * every step: the switch is on for as long as anybody still needs it, it is off as soon as nobody does, and it never stays on
 * longer than the longest need (so nothing can keep the phone awake for ever).
 * Run by tools/test.sh. Prints "N checks, M failed". ASCII only.
 */
public final class HoldsTest {
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

    /** The cases the audit named, by hand. */
    static void stories() {
        Object tablet = "tablet";
        Object stranger = "stranger";

        Holds h = new Holds();
        check(h.take(tablet, 1000, 120_000) == 120_000, "one holder: the switch is on for its whole time");
        check(h.drop(tablet, 3000) == 0, "one holder, done: the switch goes off");
        check(h.drop(tablet, 3001) == 0, "done twice: still off, nothing breaks");
        check(h.drop("nobody", 3002) == 0, "somebody who never held anything is done: off");

        // a short hold must not cut a long one short (a new connection is held for 5 s while a request runs for up to 120 s)
        h = new Holds();
        h.take(tablet, 0, 120_000);
        check(h.take(stranger, 1000, 5000) == 119_000, "a short hold beside a long one: the end stays where the long one needs it");
        check(h.drop(stranger, 2000) == 118_000, "the short one is done: the switch stays on for the long one");
        check(h.drop(tablet, 9000) == 0, "the long one is done too: off");

        // the other way round: a long hold that ends early must not leave the switch on for nothing
        h = new Holds();
        h.take(stranger, 0, 5000);
        check(h.take(tablet, 1000, 120_000) == 120_000, "a long hold beside a short one: on for the long one");
        check(h.drop(tablet, 2000) == 3000, "the long one is done early: only what the short one still needs is left");
        check(h.drop(stranger, 2500) == 0, "both done: off");

        // a holder has one hold: asking again moves its own end, both ways
        h = new Holds();
        h.take(tablet, 0, 5000);
        check(h.take(tablet, 100, 120_000) == 120_000, "the same holder asks for longer: its end moves out");
        check(h.take(tablet, 200, 5000) == 5000, "the same holder asks for shorter: its end moves in (it has one hold, not two)");
        check(h.drop(tablet, 300) == 0, "and one 'done' ends it");

        // a holder that never says it is done runs out by itself and is forgotten
        h = new Holds();
        h.take(stranger, 0, 5000);
        check(h.holders(4999) == 1 && h.holders(5000) == 0, "a hold runs out by itself at its end");
        h.take(stranger, 6000, 5000);
        check(h.take(tablet, 20_000, 3000) == 3000, "a hold that ran out long ago does not count any more");
        check(h.holders(20_000) == 1, "and it is forgotten, not kept in the list");

        // a hold of no time at all is still a hold, for a moment
        h = new Holds();
        check(h.take(tablet, 50, 0) >= 1 && h.take(stranger, 50, -7) >= 1, "a hold never answers 'off' to the one who just asked");
    }

    /** Random stories against a pretend wake lock and a plain list. */
    static void randomStories() {
        Random rnd = new Random(20261110);
        long[] lengths = {1, 5, 400, 5000, 90_000, 120_000};
        for (int story = 0; story < 400; story++) {
            Holds h = new Holds();
            Object[] who = {"a", "b", "c", "d"};
            long[] end = new long[who.length]; // the plain list: until when each one needs the processor (0 = does not)
            long now = 1_000_000 + rnd.nextInt(1000);
            long lockEnd = -1; // the pretend wake lock: on until this moment; -1 = off
            for (int step = 0; step < 120; step++) {
                int i = rnd.nextInt(who.length);
                int what = rnd.nextInt(10);
                String did;
                long answer = -1;
                if (what < 4) {
                    long ms = rnd.nextInt(4) == 0 ? 1 + rnd.nextInt(200_000) : lengths[rnd.nextInt(lengths.length)];
                    answer = h.take(who[i], now, ms);
                    end[i] = now + ms;
                    did = who[i] + " holds for " + ms;
                } else if (what < 7) {
                    answer = h.drop(who[i], now);
                    end[i] = 0;
                    did = who[i] + " is done";
                } else {
                    now += rnd.nextInt(3) == 0 ? rnd.nextInt(150_000) : rnd.nextInt(3000);
                    did = "time passes";
                }
                if (answer > 0) {
                    lockEnd = now + answer; // acquire(answer): one timer, the last call sets it
                } else if (answer == 0) {
                    lockEnd = -1; // release()
                }
                long longest = 0;
                int live = 0;
                for (long e : end) {
                    if (e > now) {
                        live++;
                        longest = Math.max(longest, e);
                    }
                }
                String at = " (story " + story + ", step " + step + ": " + did + ")";
                boolean on = lockEnd > now;
                check(live == 0 || (on && lockEnd >= longest), "awake for as long as anybody needs it" + at);
                check(live > 0 || !on, "off as soon as the last one is done" + at);
                check(!on || lockEnd <= Math.max(longest, now), "never on for longer than the longest need" + at);
                if (answer >= 0) {
                    check(answer == Math.max(0, longest - now), "the answer is the longest time anybody still needs: " + answer + at);
                }
                check(h.holders(now) == live, "the list holds exactly those who still need it: " + h.holders(now) + " for " + live + at);
            }
        }
    }

    public static void main(String[] a) {
        stories();
        randomStories();
        System.out.println(checks + " checks, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
