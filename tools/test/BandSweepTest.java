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
        boolean uploadWarns; // the speed test finishes, says it could not measure the upload, and leaves the number at 0
        boolean uploadZero; // the speed test finishes and really measured 0.0
        double[] script; // the k-th speed test (0, 1, 2, ...) measures script[k] (the last one repeats), instead of what the band gives
        final java.util.Set<Integer> warnOn = new java.util.HashSet<>(); // the k-th speed test cannot measure the upload
        int started; // speed tests started so far
        double sample = Double.NaN;
        boolean sampleWarns;
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
        int strayAtRestore; // the band lock was cleared while a speed test was still running
        boolean researchApplies; // a lock only takes effect when the phone searches again (research())
        boolean researchFails; // the request to search again fails
        long radioDownUntil = -1; // no service until then (the phone is searching)
        String appliedSpec = ""; // the restriction the modem is really using, for a phone that applies on a search
        int researches;
        long searchMs = 12000; // how long the phone has no service while it searches

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

        /** The restriction the phone is really under: what it was asked, once it has moved (or searched again). */
        String effectiveLock() {
            if (!obeys) {
                return "";
            }
            if (researchApplies) {
                return appliedSpec;
            }
            return now - lockSetAt >= moveDelay ? lock : "";
        }

        RadioState radio() {
            RadioState s = new RadioState();
            if (now < radioDownUntil) {
                return s; // searching: no service
            }
            String eff = effectiveLock();
            boolean moved = !eff.isEmpty();
            boolean nr = !fiveGOff;
            if (moved && eff.startsWith("lte:")) {
                if (noServiceOnLock) {
                    return s; // nothing to be on
                }
                int b = Integer.parseInt(eff.substring(4).split(",")[0]);
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
                if (spec.isEmpty() && uploadsRunning > 0) {
                    strayAtRestore++;
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
                sample = script == null ? Double.NaN : script[Math.min(started, script.length - 1)];
                sampleWarns = warnOn.contains(started);
                started++;
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
                } else if (uploadWarns || sampleWarns) {
                    d.done(true, "ok", new BandSweep.Upload(4, 0, 60, 0, "", "Upload could not be measured: SocketTimeoutException: timeout"));
                } else if (uploadZero) {
                    d.done(true, "ok", new BandSweep.Upload(4, 0.0, 60, 0, "", ""));
                } else {
                    d.done(true, "ok", new BandSweep.Upload(4, Double.isNaN(sample) ? upload() : sample, 60, 0, ""));
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
        public void research(BandSweep.Done<Void> d) {
            at(now + latency, () -> {
                researches++;
                if (researchFails) {
                    d.done(false, "the phone would not do it", null);
                    return;
                }
                radioDownUntil = now + searchMs; // switching the radio off and on and finding a network takes a while
                final long until = radioDownUntil;
                at(until, () -> appliedSpec = lock); // the search ends under whatever restriction is in force by then
                d.done(true, "OK", null);
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
        final List<String> texts = new ArrayList<>();
        int lastRows;
        int best = -1;
        boolean runningAtEnd = true;

        @Override
        public void progress(String text) {
            progress++;
            texts.add(text);
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
        check(p.strayAtRestore == 0, tag + ": no speed test is left running when the phone is put back (" + p.strayAtRestore + ")");
        check(!p.researchApplies || p.appliedSpec.isEmpty(), tag + ": the phone is back on its own bands (" + p.appliedSpec + ")");
        int finalists = 0;
        for (BandSweep.Row row : s.rows()) {
            check(row.samples.size() <= 2, tag + ": no setting is measured more than twice (" + row.samples.size() + ")");
            finalists += row.finalist ? 1 : 0;
            if (row.samples.size() > 1) {
                check(row.finalist, tag + ": only a checked setting has two measurements");
            }
            if (!row.samples.isEmpty()) {
                double sum = 0;
                for (double v : row.samples) {
                    sum += v;
                }
                check(Math.abs(row.up - sum / row.samples.size()) < 1e-9, tag + ": the number shown is the average of the measurements");
            }
        }
        check(finalists <= 3, tag + ": at most three settings are measured again (" + finalists + ")");
    }

    static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-9;
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
        // 5b. the speed test could not measure the upload (it says so and leaves 0): that is "no result" with the reason, never "0.0 Mbit/s",
        // and a row with no result is never called the best
        {
            Sim p = new Sim();
            p.uploadWarns = true;
            Rec r = new Rec();
            BandSweep s = new BandSweep(p, r, SweepPlan.build(new int[] {13}, new int[0], true));
            s.start();
            p.run();
            over("upload warns", p, s, r, false);
            check(s.rows().size() == 3, "upload warns: three rows: " + s.rows().size());
            for (BandSweep.Row row : s.rows()) {
                check(row.up < 0 && row.note.contains("could not be measured") && row.line(false).contains("no upload result"),
                        "upload warns: no number, and the reason is shown: " + row.line(false));
            }
            check(s.best() < 0, "upload warns: nothing is called best when nothing was measured: " + s.best());
            check(!BandSweep.summary(s.rows()).contains("Best:"), "upload warns: the summary names no best");
        }
        // 5c. a measured speed of zero (what a very weak connection really gives) is a result, not a failure: it is shown as 0.0
        {
            Sim p = new Sim();
            p.uploadZero = true;
            Rec r = new Rec();
            BandSweep s = new BandSweep(p, r, SweepPlan.build(new int[] {5}, new int[0], false));
            s.start();
            p.run();
            over("measured zero", p, s, r, false);
            check(s.rows().size() == 2, "measured zero: two rows: " + s.rows().size());
            for (BandSweep.Row row : s.rows()) {
                check(row.up == 0 && row.line(false).contains("upload 0.0 Mbit/s") && row.note.isEmpty(),
                        "measured zero: shown as a number, not as a failure: " + row.line(false));
            }
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
        // 12. the second pass: B5 wins the first pass by luck, B66 wins on average
        {
            Sim p = new Sim();
            // plan: as it is now, 5G off, B13, B5, B66 (five measurements); the best three are measured again in plan order: 5G off, B5, B66
            p.script = new double[] {0.3, 4.0, 3.1, 6.2, 4.0, 4.2, 2.0, 7.0};
            Rec r = new Rec();
            BandSweep s = new BandSweep(p, r, SweepPlan.build(new int[] {13, 5, 66}, new int[0], true), true);
            s.start();
            p.run();
            over("second pass", p, s, r, false);
            check(s.rows().size() == 5, "second pass: still five rows: " + s.rows().size());
            check(p.started == 8, "second pass: eight speed tests in all (5 + 3): " + p.started);
            check(s.rows().get(1).finalist && s.rows().get(3).finalist && s.rows().get(4).finalist
                    && !s.rows().get(0).finalist && !s.rows().get(2).finalist, "second pass: the three best first-pass settings are the ones checked");
            check(s.rows().get(0).samples.size() == 1 && s.rows().get(2).samples.size() == 1, "second pass: the others are not measured again");
            check(near(s.rows().get(3).up, 4.1) && near(s.rows().get(4).up, 5.5) && near(s.rows().get(1).up, 4.1),
                    "second pass: the averages are 4.1, 4.1 and 5.5: " + s.rows().get(1).up + ", " + s.rows().get(3).up + ", " + s.rows().get(4).up);
            check(s.best() == 4, "second pass: B66 is the best by average although B5 won the first pass: " + s.best());
            String line = s.rows().get(4).line(true);
            check(line.contains("upload 5.5 Mbit/s (4.0 and 7.0)"), "second pass: the row shows both numbers: " + line);
            String sum = BandSweep.summary(s.rows());
            check(sum.contains("Best: 4G band B66, 5G off, upload 5.5 Mbit/s (average of 2 checks)"), "second pass: the summary says how it was found: " + sum);
            boolean first = false;
            boolean last = false;
            for (String t : r.texts) {
                first |= t.startsWith("Checking again 1 of 3: ");
                last |= t.startsWith("Checking again 3 of 3: ");
            }
            check(first && last, "second pass: the progress line says which check it is on");
        }
        // 13. a setting that was not checked cannot be the best, even if its single number is higher than every average
        {
            Sim p = new Sim();
            p.script = new double[] {0.3, 4.0, 3.9, 4.2, 4.1, 1.0, 1.0, 1.0}; // B13 (3.9) just misses the three; the three then average 2.5, 2.6, 2.55
            Rec r = new Rec();
            BandSweep s = new BandSweep(p, r, SweepPlan.build(new int[] {13, 5, 66}, new int[0], true), true);
            s.start();
            p.run();
            over("unchecked", p, s, r, false);
            check(!s.rows().get(2).finalist && near(s.rows().get(2).up, 3.9), "unchecked: B13 was not measured again and keeps 3.9");
            check(s.best() == 3 && near(s.rows().get(3).up, 2.6), "unchecked: the best is the best of the checked ones (B5, 2.6), not B13's 3.9: " + s.best());
        }
        // 14. a second check that cannot be measured keeps the first number, and says so
        {
            Sim p = new Sim();
            p.script = new double[] {0.3, 4.0, 3.1, 6.2, 4.0, 4.2, 2.0, 7.0};
            p.warnOn.add(6); // the second check of B5
            Rec r = new Rec();
            BandSweep s = new BandSweep(p, r, SweepPlan.build(new int[] {13, 5, 66}, new int[0], true), true);
            s.start();
            p.run();
            over("failed check", p, s, r, false);
            BandSweep.Row b5 = s.rows().get(3);
            check(b5.samples.size() == 1 && near(b5.up, 6.2) && b5.note.contains("could not be measured") && b5.finalist,
                    "failed check: B5 keeps its first number and the note says why: " + b5.line(false));
            check(s.best() == 3, "failed check: B5 (6.2) still beats B66's average (5.5): " + s.best());
        }
        // 15. nothing to compare (one setting only): no second pass
        {
            Sim p = new Sim();
            Rec r = new Rec();
            BandSweep s = new BandSweep(p, r, SweepPlan.build(new int[0], new int[0], false), true);
            s.start();
            p.run();
            over("nothing to compare", p, s, r, false);
            boolean again = false;
            for (String t : r.texts) {
                again |= t.startsWith("Checking again");
            }
            check(!again && p.started == s.rows().size(), "nothing to compare: no second pass (" + p.started + " tests, " + s.rows().size() + " rows)");
        }
        // 16. stopped at every moment of a run with a second pass: always put back, nothing new starts after the stop
        for (long stopAt = 0; stopAt < 400000; stopAt += 2900) {
            Sim p = new Sim();
            p.script = new double[] {0.3, 4.0, 3.1, 6.2, 4.0, 4.2, 2.0, 7.0};
            Rec r = new Rec();
            final BandSweep s = new BandSweep(p, r, SweepPlan.build(new int[] {13, 5, 66}, new int[] {77}, true), true);
            final int[] startedAtStop = {-1};
            final int[] rowsAtStop = {-1};
            p.at(stopAt, () -> {
                startedAtStop[0] = p.started;
                rowsAtStop[0] = s.rows().size();
                s.cancel();
            });
            s.start();
            p.run();
            over("second pass stopped at " + stopAt, p, s, r, false);
            check(rowsAtStop[0] < 0 || s.rows().size() == rowsAtStop[0], "second pass stopped at " + stopAt + ": no new row after the stop");
            // a test that was already on its way when Stop was pressed may still be started; no second one after it
            check(startedAtStop[0] < 0 || p.started <= startedAtStop[0] + 1, "second pass stopped at " + stopAt + ": no new speed test after the stop ("
                    + startedAtStop[0] + " at the stop, " + p.started + " at the end)");
        }
        // 17. random phones with a second pass
        for (int seed = 1000; seed < 1600; seed++) {
            Random rnd = new Random(seed);
            Sim p = new Sim();
            p.fiveGOff = rnd.nextInt(3) == 0;
            boolean original = p.fiveGOff;
            p.obeys = rnd.nextInt(3) != 0;
            p.moveDelay = rnd.nextInt(30000);
            p.latency = 50 + rnd.nextInt(2000);
            p.rejectLocks = rnd.nextInt(10) == 0;
            p.loseSwitchReply = rnd.nextInt(6) == 0;
            p.switchFails = rnd.nextInt(8) == 0;
            p.undoesLocks = rnd.nextInt(10) == 0;
            p.noServiceOnLock = rnd.nextInt(8) == 0;
            double[] script = new double[12];
            for (int i = 0; i < script.length; i++) {
                script[i] = rnd.nextInt(10) == 0 ? 0.0 : rnd.nextInt(80) / 10.0;
            }
            p.script = script;
            for (int i = 0; i < 12; i++) {
                if (rnd.nextInt(8) == 0) {
                    p.warnOn.add(i);
                }
            }
            Rec r = new Rec();
            int[] lte = new int[1 + rnd.nextInt(5)];
            for (int i = 0; i < lte.length; i++) {
                lte[i] = new int[] {13, 5, 66, 2, 4, 12}[rnd.nextInt(6)];
            }
            int[] nr = rnd.nextBoolean() ? new int[] {77} : new int[0];
            BandSweep s = new BandSweep(p, r, SweepPlan.build(lte, nr, rnd.nextBoolean()), true);
            if (rnd.nextInt(2) == 0) {
                p.at(rnd.nextInt(400000), s::cancel);
            }
            s.start();
            p.run();
            over("random second pass " + seed, p, s, r, original);
        }
        // 18. a phone that applies a lock only when it searches again: without searching every lock is "not obeyed" (and the rest are
        // skipped once that is plain); with searching every lock is obeyed
        {
            Sim p = new Sim();
            p.researchApplies = true;
            Rec r = new Rec();
            BandSweep s = new BandSweep(p, r, SweepPlan.build(new int[] {13, 5, 66}, new int[0], true), false, false);
            s.start();
            p.run();
            over("no search", p, s, r, false);
            check(s.rows().size() == 4 && p.researches == 0, "no search: B13 and B5 were not obeyed, B66 was skipped: " + s.rows().size() + " rows");
            check(s.rows().get(2).verdict.equals("NOT obeyed") && s.rows().get(3).verdict.equals("NOT obeyed"), "no search: both are not obeyed");
            String sum = s.summary();
            check(sum.contains("searches for a network again") && sum.contains("Skipped 1 more band lock"), "no search: the summary says what to try: " + sum);

            Sim q = new Sim();
            q.researchApplies = true;
            Rec r2 = new Rec();
            BandSweep s2 = new BandSweep(q, r2, SweepPlan.build(new int[] {13, 5, 66}, new int[0], true), false, true);
            s2.start();
            q.run();
            over("searching", q, s2, r2, false);
            check(s2.rows().size() == 5, "searching: all five settings were measured: " + s2.rows().size());
            for (int i = 2; i < 5; i++) {
                check(s2.rows().get(i).verdict.equals("obeyed"), "searching: lock " + i + " was obeyed: " + s2.rows().get(i).line(false));
            }
            check(s2.rows().get(s2.best()).step.spec.equals("lte:5"), "searching: B5 is the best: " + s2.best());
            check(q.researches >= 4, "searching: one search per lock and one to put the phone back (" + q.researches + ")");
            check(s2.summary().contains("Every lock was obeyed"), "searching: the summary: " + s2.summary());
        }
        // 18b. a slow search (service is back after 45 s): the run waits for it instead of measuring a dead connection
        {
            Sim p = new Sim();
            p.researchApplies = true;
            p.searchMs = 45000;
            Rec r = new Rec();
            BandSweep s = new BandSweep(p, r, SweepPlan.build(new int[] {13, 5}, new int[0], true), false, true);
            s.start();
            p.run();
            over("slow search", p, s, r, false);
            check(s.rows().size() == 4, "slow search: all four settings were measured: " + s.rows().size());
            boolean sawService = false;
            for (String t : r.texts) {
                sawService |= t.contains("waiting for the phone to find service");
            }
            check(sawService, "slow search: the progress line says it is waiting for service");
            for (int i = 2; i < 4; i++) {
                BandSweep.Row row = s.rows().get(i);
                check(row.verdict.equals("obeyed") && row.up > 0, "slow search: lock " + i + " obeyed and measured on a live connection: " + row.line(false));
            }
        }
        // 19. a phone that ignores locks even after searching: the rest are skipped, and a lock that was not obeyed is never "the best"
        {
            Sim p = new Sim();
            p.obeys = false;
            p.script = new double[] {1.0, 1.4, 9.0, 8.0}; // the not-obeyed rows happen to measure the most
            Rec r = new Rec();
            BandSweep s = new BandSweep(p, r, SweepPlan.build(new int[] {13, 5, 66, 2}, new int[0], true), false, true);
            s.start();
            p.run();
            over("ignores after search", p, s, r, false);
            check(s.rows().size() == 4, "ignores after search: now, 5G off, and two locks, the rest skipped: " + s.rows().size());
            check(s.best() >= 0 && s.best() <= 1, "ignores after search: the best is one of the settings that really differ, not a lock that did nothing: " + s.best());
            String sum = s.summary();
            check(sum.contains("even after searching again") && sum.contains("spread is the noise") && sum.contains("Skipped 2 more band locks"),
                    "ignores after search: the summary: " + sum);
            check(!sum.contains("Best: 4G band"), "ignores after search: no lock is named best: " + sum);
        }
        // 20. asking the phone to search again fails: noted, the run goes on and ends clean
        {
            Sim p = new Sim();
            p.researchFails = true;
            Rec r = new Rec();
            BandSweep s = new BandSweep(p, r, SweepPlan.build(new int[] {13}, new int[0], true), false, true);
            s.start();
            p.run();
            over("search fails", p, s, r, false);
            check(s.rows().get(s.rows().size() - 1).note.contains("could not make the phone search again"), "search fails: the row says so");
        }
        // 21. stopped at every moment of a run that searches again: always put back, nothing new starts after the stop
        for (long stopAt = 0; stopAt < 500000; stopAt += 3700) {
            Sim p = new Sim();
            p.researchApplies = true;
            Rec r = new Rec();
            final BandSweep s = new BandSweep(p, r, SweepPlan.build(new int[] {13, 5, 66}, new int[] {77}, true), true, true);
            final int[] rowsAtStop = {-1};
            p.at(stopAt, () -> {
                rowsAtStop[0] = s.rows().size();
                s.cancel();
            });
            s.start();
            p.run();
            over("search, stopped at " + stopAt, p, s, r, false);
            check(rowsAtStop[0] < 0 || s.rows().size() == rowsAtStop[0], "search, stopped at " + stopAt + ": no new row after the stop");
        }
        // 22. random phones that search again
        for (int seed = 2000; seed < 2500; seed++) {
            Random rnd = new Random(seed);
            Sim p = new Sim();
            p.fiveGOff = rnd.nextInt(3) == 0;
            boolean original = p.fiveGOff;
            p.obeys = rnd.nextInt(3) != 0;
            p.researchApplies = rnd.nextBoolean();
            p.researchFails = rnd.nextInt(8) == 0;
            p.latency = 50 + rnd.nextInt(2000);
            p.rejectLocks = rnd.nextInt(10) == 0;
            p.loseSwitchReply = rnd.nextInt(6) == 0;
            p.switchFails = rnd.nextInt(8) == 0;
            p.undoesLocks = rnd.nextInt(10) == 0;
            p.noServiceOnLock = rnd.nextInt(8) == 0;
            double[] script = new double[12];
            for (int i = 0; i < script.length; i++) {
                script[i] = rnd.nextInt(10) == 0 ? 0.0 : rnd.nextInt(80) / 10.0;
            }
            p.script = script;
            Rec r = new Rec();
            int[] lte = new int[1 + rnd.nextInt(5)];
            for (int i = 0; i < lte.length; i++) {
                lte[i] = new int[] {13, 5, 66, 2, 4, 12}[rnd.nextInt(6)];
            }
            int[] nr = rnd.nextBoolean() ? new int[] {77} : new int[0];
            BandSweep s = new BandSweep(p, r, SweepPlan.build(lte, nr, rnd.nextBoolean()), rnd.nextBoolean(), true);
            if (rnd.nextInt(2) == 0) {
                p.at(rnd.nextInt(500000), s::cancel);
            }
            s.start();
            p.run();
            over("random search " + seed, p, s, r, original);
        }
        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
