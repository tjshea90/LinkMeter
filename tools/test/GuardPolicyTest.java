package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Offline test of GuardPolicy (when the phone undoes LinkMeter's changes by itself because mobile data is down), on a virtual
 * clock. Run by tools/test.sh. Prints "N checks, M failed".
 *
 * Both directions matter: it must NEVER undo anything while data works, while the user chose to have no data, or when nothing is
 * changed (that would fight the user), and it MUST undo within a few minutes when data is down and something is changed, even
 * hours after the change, and it must not hammer the phone when an undo cannot work.
 */
public final class GuardPolicyTest {
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

    static final long S = 1000;
    static final long MIN = 60 * S;

    interface DataAt {
        GuardPolicy.Data at(long t);
    }

    /** Runs the policy at its own cadence from time `from` to `to`; returns the times at which it said RESTORE. A restore clears `pending` if clears. */
    static List<Long> run(GuardPolicy p, long from, long to, DataAt data, boolean[] pending, boolean clears, long[] stoppedAt) {
        List<Long> restores = new ArrayList<>();
        long t = from;
        while (t <= to) {
            GuardPolicy.Decision d = p.step(t, pending[0], data.at(t));
            if (d.act == GuardPolicy.Act.STOP) {
                stoppedAt[0] = t;
                return restores;
            }
            if (d.act == GuardPolicy.Act.RESTORE) {
                restores.add(t);
                if (clears) {
                    pending[0] = false;
                }
            }
            check(d.nextMs > 0, "a next look is always scheduled when not stopped");
            t += d.nextMs;
        }
        stoppedAt[0] = -1;
        return restores;
    }

    static DataAt always(final GuardPolicy.Data d) {
        return t -> d;
    }

    public static void main(String[] args) {
        // ---- nothing changed: nothing to guard
        {
            GuardPolicy p = new GuardPolicy();
            check(!p.armed(), "a new policy is not armed");
            check(p.step(0, false, GuardPolicy.Data.BAD).act == GuardPolicy.Act.STOP, "nothing changed: stop, even with no data");
            p.arm(0);
            check(p.step(5 * S, false, GuardPolicy.Data.BAD).act == GuardPolicy.Act.STOP, "armed but nothing pending any more: stop");
            check(p.armed(), "arm() is remembered");
        }

        // ---- data works: never undone, however long
        {
            GuardPolicy p = new GuardPolicy();
            p.arm(0);
            long[] st = new long[1];
            List<Long> r = run(p, 0, 6 * 60 * MIN, always(GuardPolicy.Data.GOOD), new boolean[] {true}, true, st);
            check(r.isEmpty(), "six hours of working data: never undone");
            check(st[0] == -1, "and it keeps watching while something is still changed");
        }

        // ---- data down in the first ten minutes: undone after two minutes
        {
            GuardPolicy p = new GuardPolicy();
            p.arm(0);
            long[] st = new long[1];
            List<Long> r = run(p, 0, 30 * MIN, t -> t >= 30 * S ? GuardPolicy.Data.BAD : GuardPolicy.Data.GOOD, new boolean[] {true}, true, st);
            check(r.size() == 1, "one undo: " + r);
            // the first bad look is at 40 s (the policy looks every 20 s), so the undo is due 120 s later
            check(!r.isEmpty() && r.get(0) >= 160 * S && r.get(0) <= 180 * S, "after two minutes of no data: " + r);
            check(st[0] >= 0, "and then it stops, because nothing is changed any more: " + st[0]);
        }

        // ---- data goes down hours later: still undone, after four minutes
        {
            GuardPolicy p = new GuardPolicy();
            p.arm(0);
            long[] st = new long[1];
            final long down = 3 * 60 * MIN;
            List<Long> r = run(p, 0, down + 20 * MIN, t -> t >= down ? GuardPolicy.Data.BAD : GuardPolicy.Data.GOOD, new boolean[] {true}, true, st);
            check(r.size() == 1, "one undo: " + r);
            check(!r.isEmpty() && r.get(0) - down >= 4 * MIN && r.get(0) - down <= 4 * MIN + 2 * MIN, "four minutes (plus one look) after the data went: " + (r.isEmpty() ? "none" : (r.get(0) - down) / S + " s"));
        }

        // ---- the count must be continuous
        {
            GuardPolicy p = new GuardPolicy();
            p.arm(0);
            long[] st = new long[1];
            // 100 s bad, 20 s good, repeated: never 120 s in a row
            List<Long> r = run(p, 0, 9 * MIN, t -> (t % (120 * S)) < 100 * S ? GuardPolicy.Data.BAD : GuardPolicy.Data.GOOD, new boolean[] {true}, true, st);
            check(r.isEmpty(), "a signal that keeps coming back is not undone: " + r);
        }

        // ---- "cannot tell" never counts as bad, and breaks a run of bad
        {
            GuardPolicy p = new GuardPolicy();
            p.arm(0);
            long[] st = new long[1];
            List<Long> r = run(p, 0, 5 * 60 * MIN, always(GuardPolicy.Data.UNKNOWN), new boolean[] {true}, true, st);
            check(r.isEmpty(), "hours of 'cannot tell' (the user turned data off, airplane mode, no answer): never undone");
            p = new GuardPolicy();
            p.arm(0);
            r = run(p, 0, 9 * MIN, t -> (t % (100 * S)) < 80 * S ? GuardPolicy.Data.BAD : GuardPolicy.Data.UNKNOWN, new boolean[] {true}, true, st);
            check(r.isEmpty(), "a 'cannot tell' reading starts the count again");
        }

        // ---- an undo that does not work: wait five minutes, then try again, never faster
        {
            GuardPolicy p = new GuardPolicy();
            p.arm(0);
            long[] st = new long[1];
            List<Long> r = run(p, 0, 45 * MIN, always(GuardPolicy.Data.BAD), new boolean[] {true}, false, st);
            check(r.size() >= 3, "it keeps trying: " + r);
            for (int i = 1; i < r.size(); i++) {
                check(r.get(i) - r.get(i - 1) >= 5 * MIN, "never twice within five minutes: " + r.get(i - 1) / S + " s then " + r.get(i) / S + " s");
            }
            // one try per five minutes at most: the first at about 2:40, then one every five minutes -> at most 9 in 45 minutes
            check(r.size() <= 9, "and not hammering: " + r.size() + " tries in 45 minutes");
        }

        // ---- the user restores by hand
        {
            GuardPolicy p = new GuardPolicy();
            p.arm(0);
            check(p.step(30 * S, true, GuardPolicy.Data.BAD).act == GuardPolicy.Act.WATCH, "watching");
            check(p.step(50 * S, false, GuardPolicy.Data.BAD).act == GuardPolicy.Act.STOP, "restored by hand: stop at the next look");
        }

        // ---- a new change starts the count again
        {
            GuardPolicy p = new GuardPolicy();
            p.arm(0);
            p.step(20 * S, true, GuardPolicy.Data.BAD);
            p.step(100 * S, true, GuardPolicy.Data.BAD);
            p.arm(110 * S); // another change
            check(p.step(120 * S, true, GuardPolicy.Data.BAD).act == GuardPolicy.Act.WATCH, "the old count is forgotten");
            check(p.step(130 * S, true, GuardPolicy.Data.BAD).act == GuardPolicy.Act.WATCH, "and two minutes are counted from the new change");
            check(p.step(240 * S, true, GuardPolicy.Data.BAD).act == GuardPolicy.Act.RESTORE, "then it undoes");
        }

        // ---- the cadence
        {
            GuardPolicy p = new GuardPolicy();
            p.arm(0);
            check(p.step(0, true, GuardPolicy.Data.GOOD).nextMs == 20 * S, "close watch at first: every 20 seconds");
            check(p.step(9 * MIN, true, GuardPolicy.Data.GOOD).nextMs == 20 * S, "still close at nine minutes");
            check(p.step(11 * MIN, true, GuardPolicy.Data.GOOD).nextMs == 60 * S, "then once a minute");
        }

        // ---- fuzz: safe and live, on random stories
        Random g = new Random(2024);
        for (int run = 0; run < 400; run++) {
            GuardPolicy p = new GuardPolicy();
            long t = 0;
            p.arm(0);
            long continuousBadFrom = -1;
            long lastRestore = -1;
            boolean pending = true;
            int restores = 0;
            for (int i = 0; i < 300; i++) {
                t += 1000L * (3 + g.nextInt(70));
                if (g.nextInt(40) == 0) {
                    p.arm(t);
                    continuousBadFrom = -1;
                }
                if (g.nextInt(60) == 0) {
                    pending = !pending;
                }
                GuardPolicy.Data d = GuardPolicy.Data.values()[g.nextInt(3) == 0 ? g.nextInt(3) : (continuousBadFrom >= 0 || g.nextInt(4) == 0 ? 1 : 0)];
                GuardPolicy.Decision dec = p.step(t, pending, d);
                if (!pending) {
                    continuousBadFrom = -1;
                } else if (d != GuardPolicy.Data.BAD) {
                    continuousBadFrom = -1;
                } else if (continuousBadFrom < 0) {
                    continuousBadFrom = t;
                }
                if (dec.act == GuardPolicy.Act.RESTORE) {
                    restores++;
                    check(pending, "SAFE: never undoes when nothing is changed [run " + run + "]");
                    check(d == GuardPolicy.Data.BAD, "SAFE: never undoes unless data is bad right now [run " + run + "]");
                    check(continuousBadFrom >= 0 && t - continuousBadFrom >= GuardPolicy.FAST_BAD_MS,
                            "SAFE: never undoes before two continuous minutes of no data [run " + run + "]");
                    check(lastRestore < 0 || t - lastRestore >= GuardPolicy.RETRY_AFTER_RESTORE_MS, "SAFE: never twice within five minutes [run " + run + "]");
                    lastRestore = t;
                    continuousBadFrom = -1;
                }
                if (dec.act == GuardPolicy.Act.STOP) {
                    check(!pending, "never stops while something is still changed [run " + run + "]");
                }
            }
        }

        // ---- liveness: data down for good and something changed -> undone soon, whenever the change was made
        for (long armedAt = 0; armedAt <= 6 * 60 * MIN; armedAt += 17 * MIN) {
            GuardPolicy p = new GuardPolicy();
            p.arm(0);
            long[] st = new long[1];
            final long down = armedAt;
            List<Long> r = run(p, 0, down + 12 * MIN, t -> t >= down ? GuardPolicy.Data.BAD : GuardPolicy.Data.GOOD, new boolean[] {true}, true, st);
            check(r.size() == 1 && r.get(0) - down <= 4 * MIN + 2 * MIN, "LIVE: undone within six minutes when the data went at " + down / MIN + " min: " + r);
        }

        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
