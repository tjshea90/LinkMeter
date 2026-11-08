package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Offline test of BandSweep (the Bands tab's "find the best upload" run) against simulated phones on a virtual clock.
 * The most important check, in every story: when the run ends, however it ends, the phone has no band lock and 5G is as it
 * was before. Run by tools/test.sh. Prints "N checks, M failed".
 */
public final class BandSweepTest {
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

    /** A phone and a clock. Every call to the phone answers a little later; later() waits on the same clock. */
    static final class Sim implements BandSweep.Link {
        long now;
        long seq;
        final PriorityQueue<long[]> queue = new PriorityQueue<>((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        final List<Runnable> tasks = new ArrayList<>();
        // what the phone really is
        String lock = "";
        long lockSetAt = -1_000_000;
        boolean fiveGOff;
        boolean awake;
        int awakeCalls;
        int uploadsRunning;
        long uploadStartedAt = -1;
        boolean uploadWillFail;
        int maxUploads;
        // how this phone behaves
        boolean obeys = true;
        long moveDelay = 3000;
        long latency = 200;
        boolean rejectLocks;
        boolean loseSwitchReply; // the switch happens, the reply says it did not
        boolean switchFails; // the switch does not happen
        boolean netGetFails;
        boolean undoesLocks; // its safety net clears a lock soon after it is made
        boolean noServiceOnLock = false; // a locked band that is not there: no data
        int stopCalls;

        void at(long time, Runnable r) {
            tasks.add(r);
            queue.add(new long[] {time, seq++, tasks.size() - 1});
        }

        private <T> void answer(BandSweep.Done<T> d, boolean ok, String text, T value) {
            at(now + latency, () -> d.done(ok, text, value));
        }

        void run() {
            while (!queue.isEmpty()) {
                long[] e = queue.poll();
                now = Math.max(now, e[0]);
                tasks.get((int) e[2]).run();
                if (now > 3_600_000) {
                    throw new IllegalStateException("the run never ended");
                }
            }
        }

        RadioState radio() {
            RadioState s = new RadioState();
            boolean moved = obeys && now - lockSetAt >= moveDelay && !lock.isEmpty();
            boolean nr = !fiveGOff;
            if (moved && lock.startsWith("lte:")) {
                if (noServiceOnLock) {
                    return s; // nothing to be on
                }
                int b = Integer.parseInt(lock.substring(4).split(",")[0]);
                s.carriers.add(new RadioState.Carrier("LTE", b, 1000, 10000, true));
            } else {
                s.carriers.add(new RadioState.Carrier("LTE", 66, 66611, 20000, true));
                s.carriers.add(new RadioState.Carrier("LTE", 2, 900, 10000, false));
            }
            if (nr) {
                s.carriers.add(new RadioState.Carrier("NR", 77, 650016, 100000, false));
                s.display = "NR_ADVANCED";
            }
            return s;
        }

        double upload() {
            RadioState r = radio();
            if (r.carriers.isEmpty()) {
                return -1;
            }
            boolean nr = false;
            int primary = 0;
            for (RadioState.Carrier c : r.carriers) {
                nr |= c.nr();
                if (c.primary) {
                    primary = c.band;
                }
            }
            if (nr) {
                return 0.3; // the weak 5G UW
            }
            switch (primary) {
                case 13:
                    return 3.1;
                case 5:
                    return 6.2;
                case 66:
                    return 4.0;
                case 2:
                    return 2.5;
                default:
                    return 3.3;
            }
        }

        @Override
        public void fiveGOff(BandSweep.Done<Boolean> d) {
            if (netGetFails) {
                answer(d, false, "no answer", null);
            } else {
                answer(d, true, "Allowed network types: LTE (5G is " + (fiveGOff ? "off)" : "allowed)"), fiveGOff);
            }
        }

        @Override
        public void switchFiveG(boolean off, BandSweep.Done<Boolean> d) {
            at(now + latency, () -> {
                if (!switchFails) {
                    fiveGOff = off;
                }
                boolean ok = !switchFails && !loseSwitchReply;
                d.done(ok, ok ? (off ? "Forced to 4G." : "5G is allowed again.") : "the phone said no", ok ? Boolean.valueOf(off) : null);
            });
        }

        @Override
        public void setLock(String spec, BandSweep.Done<Void> d) {
            at(now + latency, () -> {
                if (rejectLocks && !spec.isEmpty()) {
                    d.done(false, "the modem rejected the band setting", null);
                    return;
                }
                lock = spec;
                lockSetAt = now;
                if (undoesLocks && !spec.isEmpty()) {
                    at(now + 8000, () -> lock = "");
                }
                d.done(true, "ok", null);
            });
        }

        @Override
        public void observe(BandSweep.Done<BandSweep.Observation> d) {
            at(now + latency, () -> d.done(true, "ok", new BandSweep.Observation(radio(), -101)));
        }

        @Override
        public void startUpload(BandSweep.Done<Void> d) {
            at(now + latency, () -> {
                uploadsRunning++;
                maxUploads = Math.max(maxUploads, uploadsRunning);
                uploadStartedAt = now;
                uploadWillFail = radio().carriers.isEmpty();
                at(now + 12000, () -> uploadsRunning = Math.max(0, uploadsRunning - 1));
                d.done(true, "ok", null);
            });
        }

        @Override
        public void uploadStatus(BandSweep.Done<BandSweep.Upload> d) {
            at(now + latency, () -> {
                if (now - uploadStartedAt < 12000) {
                    d.done(true, "ok", new BandSweep.Upload(3, 0, 0, 1.0, ""));
                } else if (uploadWillFail) {
                    d.done(true, "ok", new BandSweep.Upload(5, 0, 0, 0, "ping: no network"));
                } else {
                    d.done(true, "ok", new BandSweep.Upload(4, upload(), 60, 0, ""));
                }
            });
        }

        @Override
        public void stopUpload(BandSweep.Done<Void> d) {
            at(now + latency, () -> {
                stopCalls++;
                uploadsRunning = 0;
                d.done(true, "ok", null);
            });
        }

        @Override
        public void lockSpec(BandSweep.Done<String> d) {
            at(now + latency, () -> d.done(true, "ok", lock));
        }

        @Override
        public void later(long ms, Runnable r) {
            at(now + ms, r);
        }

        @Override
        public long now() {
            return now;
        }

        @Override
        public void awake(boolean on) {
            awake = on;
            awakeCalls++;
        }
    }

    static final class Rec implements BandSweep.Listener {
        int progress;
        int lastRows;
        int best = -1;
        boolean runningAtEnd = true;

        @Override
        public void progress(String text) {
            progress++;
        }

        @Override
        public void rows(List<BandSweep.Row> rows, int best, boolean running) {
            lastRows = rows.size();
            this.best = best;
            runningAtEnd = running;
        }
    }

    /** The invariants that must hold when any run is over. */
    static void over(String tag, Sim p, BandSweep s, Rec r, boolean originalFiveGOff) {
        check(!s.running(), tag + ": the run is over");
        check(p.lock.isEmpty(), tag + ": no band lock is left (" + p.lock + ")");
        check(p.fiveGOff == originalFiveGOff, tag + ": 5G is as it was (was " + (originalFiveGOff ? "off" : "on") + ", is " + (p.fiveGOff ? "off" : "on") + ")");
        check(!p.awake, tag + ": the tablet may sleep again");
        check(!r.runningAtEnd, tag + ": the table says it is over");
        check(p.maxUploads <= 1, tag + ": only one speed test at a time");
    }

    public static void main(String[] args) {
        // 1. a phone that obeys: B5 has the best upload, 5G UW is poor
        {
            Sim p = new Sim();
            Rec r = new Rec();
            BandSweep s = new BandSweep(p, r, SweepPlan.build(new int[] {13, 5, 66}, new int[0], true));
            s.start();
            p.run();
            over("obeys", p, s, r, false);
            check(s.rows().size() == 5, "obeys: five rows (as now, 5G off, three bands): " + s.rows().size());
            check(s.rows().get(0).up == 0.3 && s.rows().get(0).using.contains("5G UW"), "obeys: as it is now is the weak 5G UW: " + s.rows().get(0).line(false));
            BandSweep.Row best = s.rows().get(s.best());
            check(best.step.spec.equals("lte:5") && best.up == 6.2, "obeys: the best is B5: " + best.line(true));
            check(best.verdict.equals("obeyed"), "obeys: and the lock was obeyed");
            String sum = BandSweep.summary(s.rows());
            check(sum.contains("Every lock was obeyed") && sum.contains("Best: 4G band B5, 5G off") && sum.contains("20.7 times"),
                    "obeys: the summary says so: " + sum);
            check(s.rows().get(1).up == 4.0, "obeys: 5G off, no lock gives LTE B66 at 4.0: " + s.rows().get(1).up);
        }
        // 2. a phone that acknowledges locks and ignores them: every lock row says so
        {
            Sim p = new Sim();
            p.obeys = false;
            Rec r = new Rec();
            BandSweep s = new BandSweep(p, r, SweepPlan.build(new int[] {13, 5}, new int[0], true));
            s.start();
            p.run();
            over("ignores", p, s, r, false);
            int lies = 0;
            for (BandSweep.Row row : s.rows()) {
                if (row.step.spec.startsWith("lte:") && row.verdict.equals("NOT obeyed")) {
                    lies++;
                }
            }
            check(lies == 2, "ignores: both locks are reported as not obeyed (" + lies + ")");
            check(BandSweep.summary(s.rows()).contains("did not obey any of the band locks"), "ignores: the summary says the phone ignores locks: "
                    + BandSweep.summary(s.rows()));
        }
        // 3. the phone had 5G off before: a 5G step turns it on and the end puts it off again
        {
            Sim p = new Sim();
            p.fiveGOff = true;
            Rec r = new Rec();
            BandSweep s = new BandSweep(p, r, SweepPlan.build(new int[] {13}, new int[] {77}, true));
            s.start();
            p.run();
            over("5g was off", p, s, r, true);
        }
        // 4. a lock the modem rejects
        {
            Sim p = new Sim();
            p.rejectLocks = true;
            Rec r = new Rec();
            BandSweep s = new BandSweep(p, r, SweepPlan.build(new int[] {13, 5}, new int[0], false));
            s.start();
            p.run();
            over("rejects", p, s, r, false);
            check(s.rows().get(1).verdict.equals("rejected") && s.rows().get(2).verdict.equals("rejected"), "rejects: both rows say rejected");
            check(BandSweep.summary(s.rows()).contains("rejected every band lock"), "rejects: the summary says so");
        }
        // 5. a band that is not there: no data on it, and the run goes on
        {
            Sim p = new Sim();
            p.noServiceOnLock = true;
            Rec r = new Rec();
            BandSweep s = new BandSweep(p, r, SweepPlan.build(new int[] {13}, new int[0], true));
            s.start();
            p.run();
            over("no service", p, s, r, false);
            BandSweep.Row last = s.rows().get(s.rows().size() - 1);
            check(last.up < 0 && last.note.contains("no data"), "no service: the row says no data: " + last.line(false));
        }
        // 6. the phone's safety net undoes a lock: the run stops, and the phone is as it was
        {
            Sim p = new Sim();
            p.undoesLocks = true;
            Rec r = new Rec();
            BandSweep s = new BandSweep(p, r, SweepPlan.build(new int[] {13, 5, 66}, new int[0], true));
            s.start();
            p.run();
            over("safety net", p, s, r, false);
            check(s.rows().size() < 5, "safety net: the run stopped early (" + s.rows().size() + " rows)");
        }
        // 7. the reply to the 5G switch is lost, though it worked: the end still puts 5G back (it asks, it does not assume)
        {
            Sim p = new Sim();
            p.loseSwitchReply = true;
            Rec r = new Rec();
            BandSweep s = new BandSweep(p, r, SweepPlan.build(new int[] {13}, new int[0], true));
            s.start();
            p.run();
            over("lost switch reply", p, s, r, false);
        }
        // 8. the phone does not switch 5G at all: noted, and nothing is left changed
        {
            Sim p = new Sim();
            p.switchFails = true;
            Rec r = new Rec();
            BandSweep s = new BandSweep(p, r, SweepPlan.build(new int[] {13}, new int[0], true));
            s.start();
            p.run();
            over("switch fails", p, s, r, false);
        }
        // 9. the first question is not answered
        {
            Sim p = new Sim();
            p.netGetFails = true;
            Rec r = new Rec();
            BandSweep s = new BandSweep(p, r, SweepPlan.build(new int[] {5}, new int[0], true));
            s.start();
            p.run();
            over("net_get fails", p, s, r, false);
        }
        // 10. stopped at every moment of a run: always put back, and a test that was running is stopped
        for (long stopAt = 0; stopAt < 200000; stopAt += 2300) {
            Sim p = new Sim();
            Rec r = new Rec();
            final BandSweep s = new BandSweep(p, r, SweepPlan.build(new int[] {13, 5, 66}, new int[] {77}, true));
            final int[] rowsAtStop = {-1};
            p.at(stopAt, () -> {
                rowsAtStop[0] = s.rows().size();
                s.cancel();
            });
            s.start();
            p.run();
            over("stopped at " + stopAt, p, s, r, false);
            check(rowsAtStop[0] < 0 || s.rows().size() == rowsAtStop[0], "stopped at " + stopAt + ": no new step starts after Stop ("
                    + rowsAtStop[0] + " rows at the stop, " + s.rows().size() + " at the end)");
        }
        // 11. random phones, random stops
        for (int seed = 0; seed < 600; seed++) {
            Random rnd = new Random(seed);
            Sim p = new Sim();
            p.fiveGOff = rnd.nextInt(3) == 0;
            boolean original = p.fiveGOff;
            p.obeys = rnd.nextInt(3) != 0;
            p.moveDelay = rnd.nextInt(30000);
            p.latency = 50 + rnd.nextInt(2000);
            p.rejectLocks = rnd.nextInt(8) == 0;
            p.loseSwitchReply = rnd.nextInt(6) == 0;
            p.switchFails = rnd.nextInt(8) == 0;
            p.netGetFails = rnd.nextInt(8) == 0;
            p.undoesLocks = rnd.nextInt(8) == 0;
            p.noServiceOnLock = rnd.nextInt(6) == 0;
            Rec r = new Rec();
            int[] lte = new int[1 + rnd.nextInt(5)];
            for (int i = 0; i < lte.length; i++) {
                lte[i] = new int[] {13, 5, 66, 2, 4, 12}[rnd.nextInt(6)];
            }
            int[] nr = rnd.nextBoolean() ? new int[] {77} : new int[0];
            BandSweep s = new BandSweep(p, r, SweepPlan.build(lte, nr, rnd.nextBoolean()));
            if (rnd.nextInt(2) == 0) {
                p.at(rnd.nextInt(250000), s::cancel);
            }
            s.start();
            p.run();
            // when the phone loses the reply to a switch, or cannot be asked, what it started as may not be known
            boolean knowable = !p.netGetFails;
            if (knowable) {
                over("random " + seed, p, s, r, original);
            } else {
                check(!s.running() && p.lock.isEmpty() && !p.awake, "random " + seed + ": over, unlocked, awake released");
            }
        }
        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
