package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Offline test of LeverRun (the Bands tab's fair comparison of 5G off / 5G leg off / as it is) against simulated phones on a
 * virtual clock. The most important check, in every story: when the run ends, however it ends, 5G and the 5G leg are as they were
 * before and no speed test is left running. Also: every setting is measured once per round in a rotating order, a slow stretch of
 * the network (drift) does not fake a winner, a setting is only counted when the phone really dropped its 5G cell, and the verdict
 * words never claim more than the numbers show. Run by tools/test.sh. Prints "N checks, M failed".
 */
public final class LeverRunTest {
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

    /** A phone and a clock. Every call answers a little later; later() waits on the same clock. */
    static final class Sim implements LeverRun.Link {
        long now;
        long seq;
        final PriorityQueue<long[]> queue = new PriorityQueue<>((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        final List<Runnable> tasks = new ArrayList<>();
        // what the phone really is
        boolean fiveGOff;
        boolean legOff;
        boolean legOffNow;
        boolean nr = true;
        boolean nrCoverage = true;
        /** The phone also says its 5G label and 5G state (as a real phone does): "NR_ADVANCED"/"CONNECTED" with 5G, "NONE" without. */
        boolean labels;
        /** With 5G off the phone keeps listing its old 5G carriers (out-of-date list) while its label and state say there is none. */
        boolean staleList;
        /** Leave the 5G state out of what the phone says (some phones print no such line). */
        boolean noNrState;
        boolean legSupported = true;
        boolean legReadable = true;
        /** 0: "off" drops the 5G cell after 4 s; 1: only "off now" does; 2: nothing does. */
        int legMode;
        boolean observeBroken;
        int failFiveG;
        int failLeg;
        long[][] unreachable = new long[0][];
        // the speed model
        double base = 1.0;
        double fLte = 1.0;
        double fLegOff = 1.0;
        double fNormal = 1.0;
        double noise;
        /** Relative change of speed per millisecond (negative: slower and slower). */
        double drift;
        Random rnd = new Random(1);
        /** Seeds the random order of the run. */
        long orderSeed = 99;
        boolean uploadFail;
        boolean uploadError;
        boolean uploadWarn;
        int uploadsRunning;
        long uploadStartedAt = -1;
        double uploadValue;
        // what the run did
        int awakeOn;
        int awakeOff;
        int fiveGSwitches;
        int legSwitches;
        int offNowCalls;
        int uploadsStarted;
        long lastUploadDoneAt = -1;
        final List<String> states = new ArrayList<>();

        void after(long ms, Runnable r) {
            queue.add(new long[] {now + ms, seq++, tasks.size()});
            tasks.add(r);
        }

        boolean down() {
            for (long[] w : unreachable) {
                if (now >= w[0] && now < w[1]) {
                    return true;
                }
            }
            return false;
        }

        boolean nrExpected() {
            if (fiveGOff || !nrCoverage) {
                return false;
            }
            if (!legOff) {
                return true;
            }
            return legMode == 0 ? false : legMode == 1 ? !legOffNow : true;
        }

        /** Busy and quiet minutes: an oscillation of this size (relative) and period (ms), on top of the drift. */
        double wave;
        long wavePeriod = 60_000;

        double factor(long t) {
            return Math.max(0.05, 1.0 + drift * t + wave * Math.sin(2 * Math.PI * t / wavePeriod));
        }

        double rate() {
            double f = fiveGOff ? fLte : (legOff ? fLegOff : fNormal);
            double n = noise == 0 ? 1 : Math.exp(rnd.nextGaussian() * noise);
            return Math.max(0, base * f * factor(now) * n);
        }

        String stateName() {
            return fiveGOff ? "lte" : (legOff ? "legoff" : "normal");
        }

        void run() {
            int guard = 0;
            while (!queue.isEmpty() && guard++ < 3_000_000) {
                long[] e = queue.poll();
                now = e[0];
                tasks.get((int) e[2]).run();
            }
            check(guard < 3_000_000, "the run ended (no endless loop)");
        }

        @Override
        public void fiveGOff(BandSweep.Done<Boolean> d) {
            after(150, () -> {
                if (down()) {
                    d.done(false, "Couldn't reach the phone", null);
                } else {
                    d.done(true, "", fiveGOff);
                }
            });
        }

        @Override
        public void switchFiveG(boolean off, BandSweep.Done<Boolean> d) {
            fiveGSwitches++;
            after(300, () -> {
                if (down()) {
                    d.done(false, "Couldn't reach the phone", null);
                    return;
                }
                if (failFiveG > 0) {
                    failFiveG--;
                    d.done(false, "the phone did not take the setting", null);
                    return;
                }
                fiveGOff = off;
                after(off ? 2000 : 3000, () -> nr = nrExpected());
                d.done(true, "", off);
            });
        }

        @Override
        public void legOff(BandSweep.Done<Boolean> d) {
            after(150, () -> {
                if (down() || !legReadable) {
                    d.done(false, "Couldn't read it", null);
                } else {
                    d.done(true, "", legOff);
                }
            });
        }

        @Override
        public void switchLeg(String how, BandSweep.Done<Boolean> d) {
            legSwitches++;
            if (how.equals("off-now")) {
                offNowCalls++;
            }
            after(300, () -> {
                if (down()) {
                    d.done(false, "Couldn't reach the phone", null);
                    return;
                }
                if (!legSupported) {
                    d.done(false, "5G leg: this phone's modem says it cannot switch the 5G leg (not supported), so nothing was changed.", null);
                    return;
                }
                if (failLeg > 0) {
                    failLeg--;
                    d.done(false, "5G leg: the modem answered RADIO_ERROR, so it probably did not change.", null);
                    return;
                }
                legOff = how.startsWith("off");
                legOffNow = how.equals("off-now");
                after(!legOff ? 3000 : legOffNow ? 2000 : 4000, () -> nr = nrExpected());
                d.done(true, legOff ? "5G leg is off" : "5G leg is on", legOff);
            });
        }

        @Override
        public void observe(BandSweep.Done<BandSweep.Observation> d) {
            after(200, () -> {
                if (down() || observeBroken) {
                    d.done(false, "no carrier list", null);
                    return;
                }
                RadioState s = new RadioState();
                s.carriers.add(new RadioState.Carrier("LTE", 66, 66611, 20000, true));
                if (nr || (staleList && fiveGOff)) {
                    s.carriers.add(new RadioState.Carrier("NR", 77, 650000, 100000, false));
                }
                if (labels) {
                    s.display = nr ? "NR_ADVANCED" : "NONE";
                    s.nrState = noNrState ? "" : (nr ? "CONNECTED" : "NONE");
                }
                d.done(true, "", new BandSweep.Observation(s, -104));
            });
        }

        @Override
        public void startUpload(BandSweep.Done<Void> d) {
            after(200, () -> {
                if (down() || uploadFail) {
                    d.done(false, "the phone refused the test", null);
                    return;
                }
                uploadsRunning++;
                uploadsStarted++;
                uploadStartedAt = now;
                uploadValue = rate();
                states.add(stateName());
                d.done(true, "", null);
            });
        }

        @Override
        public void uploadStatus(BandSweep.Done<BandSweep.Upload> d) {
            after(150, () -> {
                if (down()) {
                    d.done(false, "Couldn't reach the phone", null);
                    return;
                }
                if (uploadStartedAt < 0) {
                    d.done(true, "", new BandSweep.Upload(0, 0, 0, 0, ""));
                } else if (now - uploadStartedAt >= 12000) {
                    if (uploadsRunning > 0) {
                        uploadsRunning--;
                    }
                    uploadStartedAt = -1;
                    lastUploadDoneAt = now;
                    if (uploadError) {
                        d.done(true, "", new BandSweep.Upload(5, -1, -1, 0, "no data"));
                    } else if (uploadWarn) {
                        d.done(true, "", new BandSweep.Upload(4, 0, 40, 0, "", "Upload could not be measured: stalled"));
                    } else {
                        d.done(true, "", new BandSweep.Upload(4, uploadValue, 40, uploadValue, ""));
                    }
                } else {
                    d.done(true, "", new BandSweep.Upload(2, -1, 40, uploadValue / 2, ""));
                }
            });
        }

        @Override
        public void stopUpload(BandSweep.Done<Void> d) {
            after(150, () -> {
                if (down()) {
                    d.done(false, "Couldn't reach the phone", null);
                    return;
                }
                if (uploadsRunning > 0) {
                    uploadsRunning--;
                }
                uploadStartedAt = -1;
                d.done(true, "", null);
            });
        }

        @Override
        public void later(long ms, Runnable r) {
            after(ms, r);
        }

        @Override
        public long now() {
            return now;
        }

        @Override
        public void awake(boolean on) {
            if (on) {
                awakeOn++;
            } else {
                awakeOff++;
            }
        }
    }

    static final class Rec implements LeverRun.Listener {
        String last = "";
        boolean lastRunning = true;
        int rowsCalls;
        List<LeverRun.Row> rows = new ArrayList<>();
        int best = -1;

        @Override
        public void progress(String text) {
            last = text;
        }

        @Override
        public void rows(List<LeverRun.Row> r, int b, boolean running) {
            rows = new ArrayList<>(r);
            best = b;
            lastRunning = running;
            rowsCalls++;
        }
    }

    static List<LeverRun.Setting> three() {
        return LeverRun.settings(true, true, false);
    }

    /** Runs a whole story. cancelAt < 0: never stopped. Returns the run; the phone and the listener are in the arguments. */
    static LeverRun go(Sim s, Rec rec, List<LeverRun.Setting> settings, int rounds, long cancelAt) {
        LeverRun r = new LeverRun(s, rec, settings, rounds, new Random(s.orderSeed));
        r.start();
        if (cancelAt >= 0) {
            s.after(cancelAt, r::cancel);
        }
        s.run();
        return r;
    }

    /** The invariants that hold after every story. */
    static void ended(Sim s, Rec rec, LeverRun r, boolean was5gOff, boolean wasLegOff, String ctx) {
        check(!r.running(), "the run is over" + ctx);
        check(!rec.lastRunning, "the last table says it is over" + ctx);
        check(s.fiveGOff == was5gOff, "5G is as it was (was off=" + was5gOff + ", is off=" + s.fiveGOff + ")" + ctx);
        check(s.legOff == wasLegOff, "the 5G leg is as it was (was off=" + wasLegOff + ", is off=" + s.legOff + ")" + ctx);
        check(s.uploadsRunning == 0, "no speed test is left running" + ctx);
        check(s.awakeOn == 1 && s.awakeOff == 1, "the tablet was kept awake once and let go once" + ctx);
        check(rec.last.startsWith("Done.") || rec.last.startsWith("Stopped."), "the closing message says how it ended: " + rec.last + ctx);
    }

    static LeverRun.Row row(double... v) {
        LeverRun.Row r = new LeverRun.Row(new LeverRun.Setting("x", null, null), new LeverRun.Target(false, Boolean.FALSE), v.length);
        System.arraycopy(v, 0, r.byRound, 0, v.length);
        return r;
    }

    public static void main(String[] args) {
        statistics();
        basicStory();
        driftDoesNotFakeAWinner();
        realEffectsAreFound();
        otherStartingStates();
        unsupportedLeg();
        stubbornFiveGCell();
        staleCarrierList();
        baselineIsNeverThrownAway();
        disobeyedSettingIsDropped();
        observeBroken();
        refusals();
        unreachablePhone();
        restoreWhileOutOfReach();
        stopAtEveryMoment();
        fuzz();
        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------ the numbers

    static void statistics() {
        check(row(1, 3, 2).median() == 2, "median of three");
        check(row(1, 3, 2, 4).median() == 2.5, "median of four");
        check(row().median() == -1, "median of nothing");
        check(row(Double.NaN, 5, Double.NaN).median() == 5 && row(Double.NaN, 5, Double.NaN).n() == 1, "missing rounds are left out");
        check(row(2, 9, 4).min() == 2 && row(2, 9, 4).max() == 9, "min and max");
        int[] pw = LeverRun.pairs(row(1, 2, 3), row(2, 1, 4));
        check(pw[0] == 3 && pw[1] == 2 && pw[2] == 1, "pairs, wins and losses");
        int[] tie = LeverRun.pairs(row(1, 2, 3), row(1, 2, 3));
        check(tie[0] == 3 && tie[1] == 0 && tie[2] == 0, "a tie is neither a win nor a loss");
        check(Math.abs(LeverRun.signP(5, 5) - 1.0 / 32) < 1e-12 && Math.abs(LeverRun.signP(5, 4) - 6.0 / 32) < 1e-12
                && Math.abs(LeverRun.signP(3, 3) - 0.125) < 1e-12 && LeverRun.signP(7, 0) == 1 && LeverRun.signP(7, 8) == 0
                && Math.abs(LeverRun.signP(9, 8) - 10.0 / 512) < 1e-12 && Math.abs(LeverRun.signP(1, 1) - 0.5) < 1e-12, "the luck chance is exact");
        double sum = 0;
        for (int k = 0; k <= 9; k++) {
            sum += LeverRun.signP(9, k) - LeverRun.signP(9, k + 1);
        }
        check(Math.abs(sum - 1.0) < 1e-12, "the chances of 0..9 wins add up to 1");
        check(LeverRun.pairs(row(1, Double.NaN, 3), row(2, 5, Double.NaN))[0] == 1, "a pair needs both numbers");

        LeverRun.Row base = row(1.0, 1.0, 1.0, 1.0, 1.0);
        check(LeverRun.verdict(base, row(1.5, 1.5, 1.5, 1.5, 1.5)) == LeverRun.Verdict.CLEAR, "1.5 times in every round is clear");
        check(LeverRun.verdict(base, row(1.5, 1.5, 1.5, 1.5, 0.9)) == LeverRun.Verdict.MAYBE, "1.5 times in 4 of 5 rounds is only 'maybe' (luck gives that 19% of the time)");
        check(LeverRun.verdict(base, row(1.5, 1.5, 1.5, 0.9, 0.9)) == LeverRun.Verdict.SAME, "better in only 3 of 5 rounds is the same");
        check(LeverRun.verdict(row(1, 1, 1), row(1.5, 1.5, 1.5)) == LeverRun.Verdict.MAYBE, "three rounds can only suggest, never prove");
        check(LeverRun.verdict(row(1, 1, 1, 1), row(1.5, 1.5, 1.5, 1.5)) == LeverRun.Verdict.MAYBE, "and four rounds too");
        check(LeverRun.verdict(row(1, 1, 1, 1, 1, 1), row(1.5, 1.5, 1.5, 1.5, 1.5, 1.5)) == LeverRun.Verdict.CLEAR, "six of six is clear");
        check(LeverRun.verdict(row(1, 1, 1, 1, 1, 1), row(1.5, 1.5, 1.5, 1.5, 1.5, 0.9)) == LeverRun.Verdict.MAYBE, "five of six is only 'maybe'");
        check(LeverRun.verdict(row(1, 1, 1, 1, 1, 1, 1, 1, 1), row(1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 1.5, 0.9)) == LeverRun.Verdict.CLEAR, "eight of nine is clear (2%)");
        check(LeverRun.verdict(row(1, 1, 1, 1, 1), row(1.5, 1.5, 1.5, 1.5, 1.0)) == LeverRun.Verdict.MAYBE, "a tie does not count as a win");
        check(LeverRun.verdict(base, row(1.29, 1.29, 1.29, 1.29, 1.29)) == LeverRun.Verdict.MAYBE, "1.29 times everywhere is only 'maybe'");
        check(LeverRun.verdict(base, row(1.1, 1.1, 1.1, 1.1, 1.1)) == LeverRun.Verdict.SAME, "1.1 times is the same");
        check(LeverRun.verdict(base, row(0.5, 0.5, 0.5, 0.5, 1.2)) == LeverRun.Verdict.WORSE, "half as fast is worse");
        check(LeverRun.verdict(base, row(1.5, 1.5)) == LeverRun.Verdict.UNKNOWN, "two rounds cannot decide");
        check(LeverRun.verdict(row(1, 1), row(5, 5)) == LeverRun.Verdict.UNKNOWN, "a baseline with two rounds cannot decide");
        LeverRun.Row gone = row(9, 9, 9, 9, 9);
        gone.unavailable = true;
        check(LeverRun.verdict(base, gone) == LeverRun.Verdict.UNKNOWN, "an impossible setting has no verdict");
        check(LeverRun.verdict(row(0, 0, 0), row(0, 0, 0)) == LeverRun.Verdict.SAME, "zero against zero is the same");
        check(LeverRun.verdict(row(0, 0, 0, 0, 0), row(0.3, 0.3, 0.3, 0.3, 0.3)) == LeverRun.Verdict.CLEAR, "something against nothing is clear");

        List<LeverRun.Row> rows = new ArrayList<>();
        rows.add(base);
        rows.add(row(1.4, 1.4, 1.4, 1.4, 1.4));
        rows.add(row(2.0, 2.0, 2.0, 2.0, 2.0));
        rows.add(row(0.5, 0.5, 0.5, 0.5, 0.5));
        check(LeverRun.bestOf(rows) == 2, "the best is the highest of the better ones");
        rows.set(2, row(1.0, 1.0, 1.0, 1.0, 1.0));
        check(LeverRun.bestOf(rows) == 1, "the next one when the top one is no better");
        rows.set(1, row(1.0, 1.0, 1.0, 1.0, 1.0));
        check(LeverRun.bestOf(rows) == -1, "no recommendation when nothing is better");
        check(LeverRun.bestOf(new ArrayList<LeverRun.Row>()) == -1 && LeverRun.bestOf(rows.subList(0, 1)) == -1, "nothing to compare");
        check(LeverRun.bestOf(Arrays.asList(row(5, 5, 5), row(1, 1, 1))) == -1, "the baseline is never recommended, even if it is the fastest");

        String s = LeverRun.summary(Arrays.asList(base, row(2, 2, 2, 2, 2)), 5);
        check(s.contains("Best:") && s.contains("clear difference") && s.contains("That spread is the noise"), "the summary of a clear winner: " + s);
        s = LeverRun.summary(Arrays.asList(base, row(1.0, 1.0, 1.0, 1.0, 1.0)), 5);
        check(s.contains("No setting was clearly better") && !s.contains("Best:"), "the summary of no difference: " + s);
        s = LeverRun.summary(Arrays.asList(base, row(0.3, 0.3, 0.3, 0.3, 0.3)), 5);
        check(s.contains("WORSE"), "the summary names a worse setting: " + s);
        s = LeverRun.summary(Arrays.asList(base, row(1.2, 1.2, 1.2, 1.2, 1.2)), 5);
        check(s.contains("only a possible difference"), "a maybe is called only possible: " + s);
        check(LeverRun.summary(new ArrayList<LeverRun.Row>(), 5).isEmpty(), "an empty table has no summary");
        check(LeverRun.summary(Arrays.asList(row(), row(1, 2, 3)), 5).contains("nothing can be compared"), "no baseline result yet");

        List<LeverRun.Setting> l = LeverRun.settings(true, true, true);
        check(l.size() == 4 && l.get(0).fiveG == null && l.get(0).leg == null, "all four settings, 'as it is now' first");
        check(LeverRun.settings(false, false, false).size() == 1, "only the baseline");
        check(l.get(1).fiveG == Boolean.FALSE && l.get(2).fiveG == Boolean.TRUE && l.get(2).leg == Boolean.FALSE, "what the settings mean");
        LeverRun.Row shown = row(1.5, 2.5);
        check(LeverRun.line(Arrays.asList(base, shown), 1, true).startsWith("★ ") && LeverRun.line(Arrays.asList(base, shown), 1, false).startsWith("  "), "the best row is starred");
        LeverRun.Row none = row();
        none.unavailable = true;
        check(LeverRun.line(Arrays.asList(base, none), 1, false).contains("not possible on this phone"), "an impossible row says so");
    }

    // ------------------------------------------------------------ stories

    /** The phone obeys, the 5G leg off is about twice as good: it is found, fairly, and everything is put back. */
    static void basicStory() {
        Sim s = new Sim();
        s.rnd = new Random(7);
        s.noise = 0.12;
        s.base = 0.8;
        s.fLte = 1.2;
        s.fLegOff = 2.5;
        Rec rec = new Rec();
        LeverRun r = go(s, rec, three(), 5, -1);
        ended(s, rec, r, false, false, " [basic]");
        check(s.uploadsStarted == 15, "3 settings x 5 rounds = 15 tests: " + s.uploadsStarted);
        check(rec.rows.size() == 3, "three rows");
        for (LeverRun.Row row : rec.rows) {
            check(row.n() == 5, "every setting has a number from every round: " + row.setting.label + " " + row.n());
            check(row.ineffective == 0 && row.unverified == 0, "nothing was left out: " + row.setting.label);
        }
        // fairness: each round has each setting once, in a changing order
        boolean once = true;
        boolean firstChanges = true;
        java.util.Set<String> orders = new java.util.HashSet<>();
        for (int round = 0; round < 5; round++) {
            List<String> in = s.states.subList(round * 3, round * 3 + 3);
            once &= in.contains("normal") && in.contains("lte") && in.contains("legoff");
            orders.add(in.toString());
            if (round > 0) {
                firstChanges &= !in.get(0).equals(s.states.get((round - 1) * 3));
            }
        }
        check(once, "every round measures every setting once: " + s.states);
        check(firstChanges, "a different setting is measured first from one round to the next: " + s.states);
        check(orders.size() >= 3, "the order is not the same every round: " + orders);
        check(LeverRun.verdict(rec.rows.get(0), rec.rows.get(2)) == LeverRun.Verdict.CLEAR, "the 5G leg off is clearly better");
        check(rec.best == 2 && r.best() == 2, "and it is the one recommended");
        check(r.summary().contains("Best:") && r.summary().contains("5G allowed, 5G leg off"), "the summary names it: " + r.summary());
        check(s.fiveGSwitches >= 4 && s.legSwitches >= 4, "the phone was switched between settings");
        check(s.legSwitches % 2 == 0, "the 5G leg ends as many times on as off: " + s.legSwitches);
    }

    /**
     * A network that gets slower and slower while the run goes on must not make the first-measured setting look best. Settings
     * that are exactly alike are compared over many random runs: a clear winner may hardly ever be declared. The same drift,
     * measured one setting after the other, WOULD fake a winner (this is computed here, so the story can fail).
     */
    static void driftDoesNotFakeAWinner() {
        int clear = 0;
        int any = 0;
        int runs = 60;
        for (int seed = 0; seed < runs; seed++) {
            Sim s = new Sim();
            s.rnd = new Random(1000 + seed);
            s.orderSeed = 3000 + seed;
            s.noise = 0.15;
            s.drift = -1.4e-6; // about 0.4 of the speed at the end of a 7 minute run
            Rec rec = new Rec();
            LeverRun r = go(s, rec, three(), 5, -1);
            ended(s, rec, r, false, false, " [drift " + seed + "]");
            for (int i = 1; i < rec.rows.size(); i++) {
                if (LeverRun.verdict(rec.rows.get(0), rec.rows.get(i)) == LeverRun.Verdict.CLEAR) {
                    clear++;
                }
            }
            if (rec.best >= 0) {
                any++;
            }
        }
        // the rule gives a clear verdict by luck at most 3.1% of the time per setting (5 of 5 rounds): 120 settings here
        System.out.println("  info: identical settings with a drifting network: " + clear + " clear verdicts in " + (2 * runs) + " settings, "
                + any + " of " + runs + " runs recommended something");
        check(clear <= 10, "identical settings are rarely called clearly different, drift or not: " + clear + " clear verdicts in " + (2 * runs) + " settings");
        check(any <= runs * 45 / 100, "and not often even 'possibly better': " + any + " of " + runs + " runs");
        // busy and quiet minutes, with a period close to the length of one round: a fixed order would always measure the same
        // setting in the same phase of the swing, and that setting would look best (or worst); the rotating order spreads it out
        Sim probe = new Sim();
        probe.rnd = new Random(77);
        go(probe, new Rec(), three(), 5, -1);
        long roundMs = probe.now / 5;
        int waveClear = 0;
        int waveAny = 0;
        for (int seed = 0; seed < runs; seed++) {
            Sim s = new Sim();
            s.rnd = new Random(5000 + seed);
            s.orderSeed = 7000 + seed;
            s.noise = 0.1;
            s.wave = 0.6;
            s.wavePeriod = roundMs;
            Rec rec = new Rec();
            LeverRun r = go(s, rec, three(), 5, -1);
            ended(s, rec, r, false, false, " [wave " + seed + "]");
            for (int i = 1; i < rec.rows.size(); i++) {
                if (LeverRun.verdict(rec.rows.get(0), rec.rows.get(i)) == LeverRun.Verdict.CLEAR) {
                    waveClear++;
                }
            }
            if (rec.best >= 0) {
                waveAny++;
            }
        }
        System.out.println("  info: identical settings with busy and quiet minutes: " + waveClear + " clear verdicts in " + (2 * runs) + " settings, "
                + waveAny + " of " + runs + " runs recommended something");
        check(waveClear <= 10, "busy and quiet minutes do not fake a clear winner either: " + waveClear + " clear verdicts in " + (2 * runs) + " settings");
        check(waveAny <= runs * 45 / 100, "nor often a possible one: " + waveAny + " of " + runs + " runs");
        // what a one-after-the-other design would see with the same drift: the first setting measured at the start, the last at the end
        Sim d = new Sim();
        d.drift = -1.4e-6;
        double first = (d.factor(0) + d.factor(150_000)) / 2;
        double last = (d.factor(300_000) + d.factor(450_000)) / 2;
        check(first / last > 1.5, "without rotation the drift alone would fake a " + String.format("%.1f", first / last) + " times winner");
    }

    /** The other side of the same rule: a real, consistent improvement is found, nearly always, and a real loss is called a loss. */
    static void realEffectsAreFound() {
        int found = 0;
        int wrong = 0;
        int runs = 40;
        for (int seed = 0; seed < runs; seed++) {
            Sim s = new Sim();
            s.rnd = new Random(8000 + seed);
            s.orderSeed = 9000 + seed;
            s.noise = 0.15;
            s.wave = 0.2;
            s.fLegOff = 1.8; // really better
            s.fLte = 0.5;    // really worse
            Rec rec = new Rec();
            LeverRun r = go(s, rec, three(), 5, -1);
            ended(s, rec, r, false, false, " [effect " + seed + "]");
            if (rec.best == 2) {
                found++;
            }
            if (LeverRun.verdict(rec.rows.get(0), rec.rows.get(1)) != LeverRun.Verdict.WORSE) {
                wrong++;
            }
        }
        System.out.println("  info: a real 1.8 times improvement was recommended in " + found + " of " + runs + " runs; a real halving was called worse in "
                + (runs - wrong) + " of " + runs);
        check(found >= runs * 9 / 10, "an improvement of 1.8 times is recommended nearly always: " + found + " of " + runs);
        check(wrong <= runs / 10, "a loss to half speed is called worse nearly always: " + (runs - wrong) + " of " + runs);
    }

    /** The phone does not start in the usual state: settings that mean the same are measured once, and the start state comes back. */
    static void otherStartingStates() {
        // 5G off at the start: "as it is now" IS "4G only"
        Sim s = new Sim();
        s.fiveGOff = true;
        s.nr = false;
        s.rnd = new Random(3);
        s.noise = 0.1;
        Rec rec = new Rec();
        LeverRun r = go(s, rec, LeverRun.settings(true, true, true), 3, -1);
        ended(s, rec, r, true, false, " [5G off at the start]");
        check(rec.rows.size() == 3, "'4G only' repeats 'as it is now' and is left out: " + rec.rows.size());
        check(r.summary().contains("is the same as a setting above"), "and the summary says so: " + r.summary());

        // the 5G leg already off at the start (and 5G allowed)
        s = new Sim();
        s.legOff = true;
        s.nr = false;
        s.rnd = new Random(4);
        s.noise = 0.1;
        rec = new Rec();
        r = go(s, rec, LeverRun.settings(true, true, true), 3, -1);
        ended(s, rec, r, false, true, " [leg off at the start]");
        check(rec.rows.size() == 3, "'leg off' repeats 'as it is now' and is left out: " + rec.rows.size());
        check(s.legOff, "the leg is switched off again at the end");

        // both off at the start
        s = new Sim();
        s.fiveGOff = true;
        s.legOff = true;
        s.nr = false;
        s.rnd = new Random(5);
        rec = new Rec();
        r = go(s, rec, LeverRun.settings(true, true, true), 3, -1);
        ended(s, rec, r, true, true, " [both off at the start]");

        // the leg cannot be read: assumed on, tracked by what the run did, and put back by trying
        s = new Sim();
        s.legReadable = false;
        s.rnd = new Random(6);
        rec = new Rec();
        r = go(s, rec, three(), 3, -1);
        ended(s, rec, r, false, false, " [leg unreadable]");

        // the leg cannot be read and it really IS off: "as it is now" must not be measured with the leg off while calling it normal,
        // so the run sets it on itself first (a known state), says so, and leaves it on (the normal state)
        s = new Sim();
        s.legReadable = false;
        s.legOff = true;
        s.nr = false;
        s.rnd = new Random(21);
        rec = new Rec();
        r = go(s, rec, LeverRun.settings(false, false, false), 3, -1);
        check(!r.running() && s.uploadsRunning == 0, "the run ended [leg unreadable and off]");
        check(s.states.size() == 3, "three tests: " + s.states);
        for (String st : s.states) {
            check(st.equals("normal"), "the baseline was measured in a known state (leg on), not with the leg really off: " + s.states);
        }
        check(s.legSwitches == 1 && !s.legOff, "the leg was set on once, and stays on: " + s.legSwitches);
        check(r.summary().contains("does not report whether the 5G leg is on"), "and the summary says so: " + r.summary());
    }

    /** A modem that cannot switch the 5G leg: that setting is dropped at the first try, the others finish. */
    static void unsupportedLeg() {
        Sim s = new Sim();
        s.legSupported = false;
        s.rnd = new Random(8);
        s.noise = 0.1;
        Rec rec = new Rec();
        LeverRun r = go(s, rec, three(), 4, -1);
        ended(s, rec, r, false, false, " [leg unsupported]");
        check(rec.rows.get(2).unavailable, "the leg row is marked impossible");
        check(s.legSwitches == 1, "and not tried again: " + s.legSwitches);
        check(rec.rows.get(0).n() == 4 && rec.rows.get(1).n() == 4, "the other settings were measured in every round");
        check(rec.rows.get(2).n() == 0, "no numbers for the impossible one");
        check(r.summary().contains("not possible on this phone's modem"), "the summary says so: " + r.summary());
        check(rec.best == -1 || rec.best == 1, "and never recommends the impossible one");
    }

    /** The 5G cell stays although the leg was switched off: "off now" is tried, and if even that fails the numbers are not counted. */
    static void stubbornFiveGCell() {
        Sim s = new Sim();
        s.legMode = 1; // only "off now" drops it
        s.rnd = new Random(9);
        s.noise = 0.1;
        s.fLegOff = 2.0;
        Rec rec = new Rec();
        LeverRun r = go(s, rec, three(), 4, -1);
        ended(s, rec, r, false, false, " [needs off now]");
        check(s.offNowCalls >= 1, "'off now' was asked for when the leg had just been switched off and the cell stayed: " + s.offNowCalls);
        check(rec.rows.get(2).n() == 4 && rec.rows.get(2).ineffective == 0, "and then they counted");

        s = new Sim();
        s.legMode = 2; // nothing drops it
        s.rnd = new Random(10);
        s.noise = 0.1;
        s.fLegOff = 3.0; // would look wonderful if it were counted
        rec = new Rec();
        r = go(s, rec, three(), 4, -1);
        ended(s, rec, r, false, false, " [never drops]");
        check(rec.rows.get(2).n() == 0, "measurements of a setting that did not take effect are not counted: " + rec.rows.get(2).n());
        check(rec.rows.get(2).ineffective == LeverRun.DROP_AFTER, "two tries, then it is left out of the other rounds: " + rec.rows.get(2).ineffective);
        check(rec.rows.get(2).unavailable && rec.rows.get(2).notObeyed, "and it is marked as not obeyed");
        check(rec.best != 2, "and it is never recommended");
        check(LeverRun.line(rec.rows, 2, false).contains("not counted"), "the table says so");
        check(LeverRun.line(rec.rows, 2, false).contains("this phone does not obey it"), "and that the phone does not obey it: " + LeverRun.line(rec.rows, 2, false));
        check(r.summary().contains("does not obey that setting"), "the summary says so too: " + r.summary());
        check(s.uploadsStarted == 8, "no speed test was wasted on it: " + s.uploadsStarted);

        s = new Sim();
        s.rnd = new Random(11);
        s.nrCoverage = false; // no 5G here at all: nothing to wait for
        s.nr = false;
        rec = new Rec();
        r = go(s, rec, three(), 3, -1);
        ended(s, rec, r, false, false, " [no 5G here]");
        check(rec.rows.get(2).n() == 3 && rec.rows.get(2).ineffective == 0, "with no 5G cell at all the setting is simply measured");
    }

    /**
     * The user's phone: after "4G only" the phone's own 5G label was gone but its carrier list still named the old 5G carriers
     * (B66 + n77 + n2). That list is out of date, not a 5G connection, so the measurements must count.
     */
    static void staleCarrierList() {
        // label and state both say "no 5G": believed over the list
        Sim s = new Sim();
        s.labels = true;
        s.staleList = true;
        s.rnd = new Random(31);
        s.noise = 0.1;
        s.fLte = 2.0;
        Rec rec = new Rec();
        LeverRun r = go(s, rec, three(), 4, -1);
        ended(s, rec, r, false, false, " [stale list, label and state]");
        LeverRun.Row lte = rec.rows.get(1);
        check(lte.n() == 4 && lte.ineffective == 0, "'4G only' was measured in every round, nothing thrown away: n=" + lte.n() + " ineffective=" + lte.ineffective);
        check(!lte.unavailable && !lte.notObeyed, "and it is not left out");
        check(lte.using.contains("old entries") && lte.using.contains("n77"), "the table names the old 5G entries it ignored: " + lte.using);
        check(!LeverRun.line(rec.rows, 1, false).contains("stayed on 5G"), "the table does not claim the phone stayed on 5G");
        check(lte.median() > 1.5 * rec.rows.get(0).median(), "and the real difference shows: 4G only " + lte.median() + " vs " + rec.rows.get(0).median());

        // the phone printed a 5G label but no 5G state: 5G was switched off and read back, so the label alone settles it
        s = new Sim();
        s.labels = true;
        s.noNrState = true;
        s.staleList = true;
        s.rnd = new Random(32);
        rec = new Rec();
        r = go(s, rec, three(), 3, -1);
        ended(s, rec, r, false, false, " [stale list, label only]");
        check(rec.rows.get(1).n() == 3 && rec.rows.get(1).ineffective == 0, "label only: still measured: n=" + rec.rows.get(1).n());
        check(rec.rows.get(1).using.contains("old entries"), "label only: the table names the old entries: " + rec.rows.get(1).using);

        // the phone says NOTHING about 5G besides its list: the list is believed (as before), so a 5G cell that stays is not counted
        s = new Sim();
        s.staleList = true; // no label, no state
        s.rnd = new Random(33);
        rec = new Rec();
        r = go(s, rec, three(), 4, -1);
        ended(s, rec, r, false, false, " [stale list, nothing else said]");
        check(rec.rows.get(1).n() == 0 && rec.rows.get(1).ineffective == LeverRun.DROP_AFTER,
                "with no label and no state the list is believed, as before: " + rec.rows.get(1).n() + "/" + rec.rows.get(1).ineffective);
        check(rec.rows.get(1).evidence.contains("label: not given") && rec.rows.get(1).evidence.contains("state: not given"),
                "and the table says what the phone did and did not say: " + rec.rows.get(1).evidence);

        // a REAL 5G connection (label and state say so) with 5G allowed but the leg "off": believed, the setting is not obeyed
        s = new Sim();
        s.labels = true;
        s.legMode = 2;
        s.rnd = new Random(34);
        rec = new Rec();
        r = go(s, rec, three(), 4, -1);
        ended(s, rec, r, false, false, " [real 5G, leg ignored]");
        check(rec.rows.get(2).n() == 0 && rec.rows.get(2).notObeyed, "a real 5G connection is not mistaken for a stale list");
        check(rec.rows.get(2).evidence.contains("NR_ADVANCED") && rec.rows.get(2).evidence.contains("CONNECTED"),
                "and the evidence names the label and the state: " + rec.rows.get(2).evidence);
    }

    /** "As it is now" is what everything is compared with: it is measured whatever the phone shows, and never asked to drop 5G. */
    static void baselineIsNeverThrownAway() {
        // the user's phone: its own setting says "5G leg off", yet it keeps its 5G connection
        Sim s = new Sim();
        s.labels = true;
        s.legOff = true;
        s.legMode = 2; // nothing drops the cell
        s.rnd = new Random(35);
        s.noise = 0.1;
        Rec rec = new Rec();
        LeverRun r = go(s, rec, LeverRun.settings(true, true, false), 4, -1);
        ended(s, rec, r, false, true, " [baseline with an ignored leg-off]");
        LeverRun.Row base = rec.rows.get(0);
        check(rec.rows.size() == 2, "'5G leg off' repeats the phone's own setting and is left out: " + rec.rows.size());
        check(base.n() == 4 && base.ineffective == 0, "the baseline was measured in every round: n=" + base.n() + " ineffective=" + base.ineffective);
        check(base.note.contains("measured as it is"), "and the table says the phone still uses 5G: " + base.note);
        check(base.note.indexOf("measured as it is") == base.note.lastIndexOf("measured as it is"), "once, not once per round: " + base.note);
        check(s.offNowCalls == 0, "the baseline was never asked to drop the 5G cell by force: " + s.offNowCalls);
        check(rec.rows.get(1).n() == 4, "the other setting was measured too, so there is something to compare");
        check(!r.summary().contains("nothing can be compared"), "a comparison exists: " + r.summary());

        // 5G off at the start (the phone's own setting) and the phone still shows 5G: the same
        s = new Sim();
        s.labels = true;
        s.fiveGOff = true;
        s.nr = true; // the cell stays although 5G is off
        s.rnd = new Random(36);
        rec = new Rec();
        r = go(s, rec, LeverRun.settings(false, true, false), 3, -1);
        ended(s, rec, r, true, false, " [baseline 5G off but a 5G cell]");
        check(rec.rows.get(0).n() == 3, "the baseline is measured: n=" + rec.rows.get(0).n());
    }

    /** A setting that is not obeyed twice in a row is dropped: it must not cost the whole run. */
    static void disobeyedSettingIsDropped() {
        Sim s = new Sim();
        s.labels = true;
        s.legMode = 2;
        s.rnd = new Random(37);
        s.noise = 0.1;
        Rec rec = new Rec();
        LeverRun r = go(s, rec, three(), 5, -1);
        ended(s, rec, r, false, false, " [leg never obeyed]");
        LeverRun.Row leg = rec.rows.get(2);
        check(leg.ineffective == 2 && leg.n() == 0 && leg.notObeyed, "two tries then dropped: " + leg.ineffective);
        check(rec.rows.get(0).n() == 5 && rec.rows.get(1).n() == 5, "the others were measured in all 5 rounds");
        check(s.uploadsStarted == 10, "the dropped setting cost no further speed tests: " + s.uploadsStarted);
        check(s.offNowCalls == LeverRun.DROP_AFTER, "'off now' was tried once per try, and there were only two tries: " + s.offNowCalls);

        // one bad try and then it takes: NOT dropped, and its number counts
        s = new Sim();
        s.legMode = 1; // only "off now" drops it, which works
        s.rnd = new Random(38);
        rec = new Rec();
        r = go(s, rec, three(), 4, -1);
        ended(s, rec, r, false, false, " [needs off now]");
        check(!rec.rows.get(2).unavailable && rec.rows.get(2).n() == 4, "a setting that works after 'off now' is kept");
    }

    static void observeBroken() {
        Sim s = new Sim();
        s.observeBroken = true;
        s.rnd = new Random(12);
        Rec rec = new Rec();
        LeverRun r = go(s, rec, three(), 3, -1);
        ended(s, rec, r, false, false, " [no carrier list]");
        for (LeverRun.Row row : rec.rows) {
            check(row.n() == 3 && row.unverified == 3, "measured anyway, and said it could not be checked: " + row.setting.label);
        }
        check(LeverRun.line(rec.rows, 1, false).contains("without being able to check"), "the table says so");
    }

    static void refusals() {
        // 5G cannot be switched off: that setting is skipped each time, with a note, and the rest goes on
        Sim s = new Sim();
        s.failFiveG = 1000;
        s.rnd = new Random(13);
        Rec rec = new Rec();
        LeverRun r = go(s, rec, three(), 3, -1);
        ended(s, rec, r, false, false, " [5G refused]");
        check(rec.rows.get(1).n() == 0 && rec.rows.get(1).note.contains("5G could not be switched off"), "the refused setting has a note and no numbers: " + rec.rows.get(1).note);
        check(rec.rows.get(0).n() == 3 && rec.rows.get(2).n() == 3, "the others were measured in every round");

        // the leg is refused with an error (not "cannot"): skipped with a note, not marked impossible
        s = new Sim();
        s.failLeg = 1000;
        s.rnd = new Random(14);
        rec = new Rec();
        r = go(s, rec, three(), 3, -1);
        ended(s, rec, r, false, false, " [leg refused]");
        check(!rec.rows.get(2).unavailable && rec.rows.get(2).note.contains("could not be switched"), "a refusal is not 'impossible': " + rec.rows.get(2).note);

        // the speed test will not start / ends in an error / ends with a warning and no number
        for (int mode = 0; mode < 3; mode++) {
            s = new Sim();
            s.uploadFail = mode == 0;
            s.uploadError = mode == 1;
            s.uploadWarn = mode == 2;
            s.rnd = new Random(15 + mode);
            rec = new Rec();
            r = go(s, rec, three(), 2, -1);
            ended(s, rec, r, false, false, " [speed test trouble " + mode + "]");
            for (LeverRun.Row row : rec.rows) {
                check(row.n() == 0, "no number is invented when the test gave none (mode " + mode + ")");
                check(!row.note.isEmpty(), "and a note says why (mode " + mode + ")");
            }
        }
    }


    /**
     * The phone is out of reach exactly when it must be put back: the run keeps asking, and in the end the phone is as it was. A probe
     * run finds an order that ends on the setting that changed the phone (5G off, or the 5G leg off) and when its last test finished;
     * the real run then loses the phone for 15 s from that moment.
     */
    static void restoreWhileOutOfReach() {
        boolean[] legCase = {false, true};
        for (boolean leg : legCase) {
            String wantLast = leg ? "legoff" : "lte";
            List<LeverRun.Setting> settings = LeverRun.settings(!leg, leg, false);
            boolean tried = false;
            for (long seed = 0; seed < 80 && !tried; seed++) {
                Sim p = new Sim();
                p.orderSeed = seed;
                p.rnd = new Random(31);
                go(p, new Rec(), settings, 2, -1);
                if (!p.states.get(p.states.size() - 1).equals(wantLast)) {
                    continue;
                }
                tried = true;
                Sim s = new Sim();
                s.orderSeed = seed;
                s.rnd = new Random(31);
                // 5G case: out of reach from the first question. Leg case: the 5G question (150 ms) still gets through, so it is the
                // 5G leg that is asked for, and put back, while the phone is out of reach
                long from = p.lastUploadDoneAt + (leg ? 200 : 100);
                s.unreachable = new long[][] {{from, from + 15_000}};
                Rec rec = new Rec();
                LeverRun r = go(s, rec, settings, 2, -1);
                String ctx = " [put back while out of reach: " + (leg ? "5G leg" : "5G") + ", order seed " + seed + "]";
                ended(s, rec, r, false, false, ctx);
                check(!rec.last.contains("may not be back"), "and it says it is back as it was: " + rec.last + ctx);
                check(leg ? s.legSwitches >= 3 : s.fiveGSwitches >= 3, "the run asked again until the phone answered" + ctx);
            }
            check(tried, "an order was found that ends on " + wantLast);
        }
    }

    static void unreachablePhone() {
        // out of reach for 30 s in the middle of a run: the run goes on and ends restored
        Sim s = new Sim();
        s.unreachable = new long[][] {{100_000, 130_000}};
        s.rnd = new Random(16);
        Rec rec = new Rec();
        LeverRun r = go(s, rec, three(), 3, -1);
        ended(s, rec, r, false, false, " [30 s out of reach]");
        // out of reach for a while exactly when the phone must be put back
        Sim probe = new Sim();
        probe.rnd = new Random(17);
        go(probe, new Rec(), three(), 2, -1);
        long total = probe.now;
        s = new Sim();
        s.rnd = new Random(17);
        s.unreachable = new long[][] {{total - 9_000, total + 12_000}};
        rec = new Rec();
        r = go(s, rec, three(), 2, -1);
        ended(s, rec, r, false, false, " [out of reach at the end]");
        check(!rec.last.contains("may not be back"), "and it says it is back as it was: " + rec.last);
        // never reachable again: the run ends anyway, and says the phone may not be back
        s = new Sim();
        s.rnd = new Random(18);
        s.legOff = false;
        s.unreachable = new long[][] {{60_000, Long.MAX_VALUE / 4}};
        rec = new Rec();
        r = go(s, rec, three(), 2, -1);
        check(!r.running() && !rec.lastRunning, "a phone that never comes back does not hang the run");
        check(rec.last.contains("may not be back") && rec.last.contains("Restore everything"), "and the closing message sends the user to Restore everything: " + rec.last);
        check(s.uploadsRunning <= 1, "(an upload that could not be stopped is the phone's own timeout)");
    }

    /** Stop pressed at every half second of a short run: always ends, always put back. */
    static void stopAtEveryMoment() {
        Sim probe = new Sim();
        probe.rnd = new Random(20);
        probe.fLegOff = 2;
        go(probe, new Rec(), three(), 2, -1);
        long total = probe.now;
        int n = 0;
        for (long at = 0; at <= total + 3000; at += 500) {
            Sim s = new Sim();
            s.rnd = new Random(20);
            s.fLegOff = 2;
            Rec rec = new Rec();
            LeverRun r = go(s, rec, three(), 2, at);
            ended(s, rec, r, false, false, " [stop at " + at + " ms]");
            n++;
        }
        check(n > 100, "many stop moments were tried: " + n);
        // stopped before the first answer, and stopped twice
        Sim s = new Sim();
        Rec rec = new Rec();
        LeverRun r = new LeverRun(s, rec, three(), 2, new Random(5));
        r.start();
        r.cancel();
        r.cancel();
        s.run();
        ended(s, rec, r, false, false, " [stop at once, twice]");
        // started twice: the second start does nothing
        s = new Sim();
        rec = new Rec();
        r = new LeverRun(s, rec, three(), 1, new Random(6));
        r.start();
        r.start();
        s.run();
        ended(s, rec, r, false, false, " [started twice]");
        check(s.uploadsStarted == 3, "a second start did not run it twice: " + s.uploadsStarted);
    }

    static void fuzz() {
        Random g = new Random(424242);
        int runs = 400;
        for (int i = 0; i < runs; i++) {
            Sim s = new Sim();
            s.rnd = new Random(g.nextLong());
            boolean o5 = g.nextInt(4) == 0;
            boolean oLeg = g.nextInt(4) == 0;
            s.fiveGOff = o5;
            s.legOff = oLeg;
            s.nr = !o5 && !oLeg;
            s.nrCoverage = g.nextInt(5) != 0;
            s.legSupported = g.nextInt(6) != 0;
            s.legReadable = g.nextInt(6) != 0;
            s.legMode = g.nextInt(3);
            s.observeBroken = g.nextInt(8) == 0;
            s.failFiveG = g.nextInt(3);
            s.failLeg = g.nextInt(3);
            s.uploadFail = g.nextInt(10) == 0;
            s.uploadError = g.nextInt(10) == 0;
            s.uploadWarn = g.nextInt(10) == 0;
            s.noise = g.nextDouble() * 0.3;
            s.drift = -g.nextDouble() * 1.5e-6;
            s.fLte = 0.5 + g.nextDouble() * 2;
            s.fLegOff = 0.5 + g.nextDouble() * 2;
            if (g.nextInt(3) == 0) {
                long from = 20_000 + g.nextInt(300_000);
                s.unreachable = new long[][] {{from, from + 3_000 + g.nextInt(12_000)}};
            }
            Rec rec = new Rec();
            long cancelAt = g.nextInt(3) == 0 ? g.nextInt(400_000) : -1;
            LeverRun r = go(s, rec, LeverRun.settings(g.nextBoolean(), g.nextBoolean(), g.nextBoolean()), 1 + g.nextInt(5), cancelAt);
            String ctx = " [fuzz " + i + " o5=" + o5 + " oLeg=" + oLeg + " legSupported=" + s.legSupported + " legReadable=" + s.legReadable
                    + " legMode=" + s.legMode + " failFiveG=" + s.failFiveG + " failLeg=" + s.failLeg + " down=" + java.util.Arrays.deepToString(s.unreachable)
                    + " cancelAt=" + cancelAt + " last=" + rec.last + "]";
            // a refusal that outlasts the retries cannot be undone by anyone; everything else must be back as it was
            boolean undoable = s.failFiveG == 0 && s.failLeg == 0;
            check(!r.running() && !rec.lastRunning, "the run ends" + ctx);
            check(s.uploadsRunning == 0, "no speed test left running" + ctx);
            check(s.awakeOn == 1 && s.awakeOff == 1, "awake balanced" + ctx);
            if (undoable || !rec.last.contains("may not be back")) {
                check(s.fiveGOff == o5, "5G as it was" + ctx);
                // a phone that cannot report the leg: the run sets it itself, and leaves it in the normal state (on) if it touched it
                check(s.legReadable ? s.legOff == oLeg : (s.legOff == oLeg || !s.legOff), "leg as it was (or on, if it could not be read)" + ctx);
            }
            for (LeverRun.Row row : rec.rows) {
                for (double v : row.samples()) {
                    check(v >= 0 && !Double.isNaN(v), "a number is a real number" + ctx);
                }
                check(row.n() <= r.rounds(), "no more numbers than rounds" + ctx);
            }
            check(rec.best < rec.rows.size(), "the recommended row exists" + ctx);
        }
    }
}
