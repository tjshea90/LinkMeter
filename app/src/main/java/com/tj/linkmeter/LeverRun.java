package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Tablet side, with no Android in it, so it is tested on a computer against simulated phones (tools/test/LeverRunTest.java).
 *
 * Compares phone settings that the phone DOES obey (5G off = 4G only, and the 5G leg off while 5G stays allowed) against how the
 * phone is now, on the upload speed of its mobile data. Built for a bad place and a bad time of day, where an upload test gives
 * 0.1 to 1 Mbit/s and the network itself changes from minute to minute:
 *  - every setting is measured once in every ROUND, in a RANDOM order within the round (never the same setting first twice in
 *    a row), so a busy or quiet stretch of the network hits the settings by chance and not by position;
 *  - the verdict compares each setting with "as it is now" round by round. Because the order was random, "this setting makes no
 *    difference" means each round is a fair coin flip, so the chance of so many wins by luck is exact (a sign test): a CLEAR
 *    difference needs 1.3 times the median AND a luck chance of 5% or less (5 of 5 rounds, 6 of 6, 7 of 7, 8 of 9), a possible
 *    one 1.15 times and 20% or less (4 of 5). Three or four rounds can only suggest, never prove. Otherwise it says "no clear
 *    difference", with the spread of the baseline rounds as the noise;
 *  - a setting is only counted after the phone's carrier list shows it took effect (no 5G cell left when the setting says none),
 *    and "off" is tried again as "off now" once if the 5G cell stays;
 *  - however the run ends (done, stopped, a failure, a lost phone), 5G and the 5G leg are put back as they were, with retries.
 */
final class LeverRun {
    interface Listener {
        /** What the run is doing now. */
        void progress(String text);

        /** The table so far; best is the index of the row to recommend or -1. */
        void rows(List<Row> rows, int best, boolean running);
    }

    /** How the run talks to the phone and the clock. The real one is LeverLink; the test has a simulated phone. */
    interface Link {
        /** Is 5G switched off in the phone's allowed network types? ok = the phone answered in a way that could be read. */
        void fiveGOff(BandSweep.Done<Boolean> d);

        /** Switches 5G off (true) or on (false). ok = the phone says it is now as asked. */
        void switchFiveG(boolean off, BandSweep.Done<Boolean> d);

        /** Is the 5G leg switched off? ok = the phone answered in a way that could be read; value TRUE = off. */
        void legOff(BandSweep.Done<Boolean> d);

        /** how = "on", "off" or "off-now". ok = the modem accepted it (the text says why if not; NrLeg.cannot(text) = it never can). */
        void switchLeg(String how, BandSweep.Done<Boolean> d);

        void observe(BandSweep.Done<BandSweep.Observation> d);

        void startUpload(BandSweep.Done<Void> d);

        void uploadStatus(BandSweep.Done<BandSweep.Upload> d);

        void stopUpload(BandSweep.Done<Void> d);

        void later(long ms, Runnable r);

        long now();

        /** Keeps the tablet awake while a run is going on. */
        void awake(boolean on);
    }

    // ------------------------------------------------------------ what is compared

    /**
     * One thing to compare. fiveG: TRUE = 5G allowed, FALSE = 5G off (4G only), null = as it was when the run started.
     * leg: TRUE = the 5G leg may be added, FALSE = kept from being added, null = as it was (it only matters while 5G is allowed).
     */
    static final class Setting {
        final String label;
        final Boolean fiveG;
        final Boolean leg;

        Setting(String label, Boolean fiveG, Boolean leg) {
            this.label = label;
            this.fiveG = fiveG;
            this.leg = leg;
        }
    }

    /** The settings the tab offers. "As it is now" is always first: it is what everything else is compared with. */
    static List<Setting> settings(boolean fourGOnly, boolean legOff, boolean fiveGNormal) {
        List<Setting> l = new ArrayList<>();
        l.add(new Setting("As it is now", null, null));
        if (fourGOnly) {
            l.add(new Setting("4G only (5G off)", Boolean.FALSE, null));
        }
        if (legOff) {
            l.add(new Setting("5G allowed, 5G leg off", Boolean.TRUE, Boolean.FALSE));
        }
        if (fiveGNormal) {
            l.add(new Setting("5G on, normal", Boolean.TRUE, Boolean.TRUE));
        }
        return l;
    }

    /** What a setting means for the phone, once "as it was" is known. legOff null = does not matter (5G is off). */
    static final class Target {
        final boolean fiveGOff;
        final Boolean legOff;

        Target(boolean fiveGOff, Boolean legOff) {
            this.fiveGOff = fiveGOff;
            this.legOff = legOff;
        }

        boolean sameAs(Target o) {
            return fiveGOff == o.fiveGOff && (legOff == null ? o.legOff == null : legOff.equals(o.legOff));
        }

        /** The phone should show no 5G cell. */
        boolean expectNoNr() {
            return fiveGOff || Boolean.TRUE.equals(legOff);
        }
    }

    // ------------------------------------------------------------ results

    /** What one setting found. */
    static final class Row {
        final Setting setting;
        final Target target;
        /** Upload in Mbit/s per round; NaN = not measured in that round. */
        final double[] byRound;
        /** Measurements that did not count: the phone stayed on 5G although the setting says none. */
        int ineffective;
        /** Measurements made although the carriers could not be listed. */
        int unverified;
        /** The modem cannot do this setting at all (the 5G leg on a modem that cannot switch it), or does not obey it (notObeyed). */
        boolean unavailable;
        /** The phone took the setting but kept 5G anyway, twice in a row: it is left out of the other rounds. */
        boolean notObeyed;
        /** What the phone said about 5G the last time a measurement was not counted (its list, its label, its state). */
        String evidence = "";
        String using = "";
        int rsrp = Integer.MIN_VALUE;
        String note = "";

        Row(Setting setting, Target target, int rounds) {
            this.setting = setting;
            this.target = target;
            this.byRound = new double[rounds];
            Arrays.fill(byRound, Double.NaN);
        }

        int n() {
            int n = 0;
            for (double v : byRound) {
                if (!Double.isNaN(v)) {
                    n++;
                }
            }
            return n;
        }

        /** The measurements in round order. */
        List<Double> samples() {
            List<Double> l = new ArrayList<>();
            for (double v : byRound) {
                if (!Double.isNaN(v)) {
                    l.add(v);
                }
            }
            return l;
        }

        double median() {
            List<Double> l = samples();
            if (l.isEmpty()) {
                return -1;
            }
            java.util.Collections.sort(l);
            int m = l.size() / 2;
            return l.size() % 2 == 1 ? l.get(m) : (l.get(m - 1) + l.get(m)) / 2;
        }

        double min() {
            double lo = Double.MAX_VALUE;
            for (double v : samples()) {
                lo = Math.min(lo, v);
            }
            return lo == Double.MAX_VALUE ? -1 : lo;
        }

        double max() {
            double hi = -1;
            for (double v : samples()) {
                hi = Math.max(hi, v);
            }
            return hi;
        }
    }

    enum Verdict { CLEAR, MAYBE, SAME, WORSE, UNKNOWN }

    /** {rounds in which both settings have a number, rounds r beat base, rounds base beat r}. A tie is neither. */
    static int[] pairs(Row base, Row r) {
        int pairs = 0;
        int wins = 0;
        int losses = 0;
        for (int i = 0; i < base.byRound.length && i < r.byRound.length; i++) {
            if (!Double.isNaN(base.byRound[i]) && !Double.isNaN(r.byRound[i])) {
                pairs++;
                if (r.byRound[i] > base.byRound[i]) {
                    wins++;
                } else if (r.byRound[i] < base.byRound[i]) {
                    losses++;
                }
            }
        }
        return new int[] {pairs, wins, losses};
    }

    /** The chance of k or more wins in n rounds if the setting made no difference (each round a fair coin): P(X >= k), X ~ Binomial(n, 1/2). */
    static double signP(int n, int k) {
        if (k <= 0) {
            return 1;
        }
        if (k > n) {
            return 0;
        }
        long c = 1; // C(n, j)
        long sum = 0;
        for (int j = 0; j <= n; j++) {
            if (j >= k) {
                sum += c;
            }
            c = c * (n - j) / (j + 1);
        }
        return sum / Math.pow(2, n);
    }

    /**
     * How r compares with base. CLEAR: median at least 1.3 times as high and better in so many rounds that luck alone would give
     * that in 5% of cases or fewer. MAYBE: at least 1.15 times and 20% or fewer. WORSE: at most 0.77 times and worse in so many
     * rounds that luck alone would give that in 20% or fewer. At least three rounds with both numbers are needed for any verdict.
     */
    static Verdict verdict(Row base, Row r) {
        int[] p = pairs(base, r);
        if (p[0] < 3 || r.unavailable) {
            return Verdict.UNKNOWN;
        }
        double mb = base.median();
        double mr = r.median();
        double ratio = mb > 0 ? mr / mb : (mr > 0 ? 99 : 1);
        double luckBetter = signP(p[0], p[1]);
        if (ratio >= 1.3 && luckBetter <= 0.05) {
            return Verdict.CLEAR;
        }
        if (ratio >= 1.15 && luckBetter <= 0.2) {
            return Verdict.MAYBE;
        }
        if (ratio <= 0.77 && signP(p[0], p[2]) <= 0.2) {
            return Verdict.WORSE;
        }
        return Verdict.SAME;
    }

    /** The row to recommend (clearly or possibly better than "as it is now", highest median), or -1. Row 0 is the baseline. */
    static int bestOf(List<Row> rows) {
        if (rows.size() < 2) {
            return -1;
        }
        int best = -1;
        for (int i = 1; i < rows.size(); i++) {
            Verdict v = verdict(rows.get(0), rows.get(i));
            if ((v == Verdict.CLEAR || v == Verdict.MAYBE) && (best < 0 || rows.get(i).median() > rows.get(best).median())) {
                best = i;
            }
        }
        return best;
    }

    static String line(List<Row> rows, int i, boolean best) {
        Row r = rows.get(i);
        StringBuilder sb = new StringBuilder(best ? "★ " : "  ").append(r.setting.label).append("\n    ");
        if (r.n() == 0) {
            sb.append(r.notObeyed ? "left out: this phone does not obey it" : r.unavailable ? "not possible on this phone" : "no upload result");
        } else {
            sb.append(String.format(Locale.US, "upload median %.2f Mbit/s", r.median()));
            sb.append(" (");
            List<Double> s = r.samples();
            for (int k = 0; k < s.size(); k++) {
                sb.append(k == 0 ? "" : ", ").append(String.format(Locale.US, "%.2f", s.get(k)));
            }
            sb.append(")");
            if (i > 0) {
                int[] p = pairs(rows.get(0), r);
                if (p[0] > 0) {
                    sb.append(String.format(Locale.US, "\n    better than \"%s\" in %d of %d rounds", rows.get(0).setting.label, p[1], p[0]));
                }
            }
        }
        if (!r.using.isEmpty()) {
            sb.append("\n    using ").append(r.using);
            if (r.rsrp != Integer.MIN_VALUE) {
                sb.append(", ").append(r.rsrp).append(" dBm");
            }
        }
        if (r.ineffective > 0) {
            sb.append("\n    ").append(r.ineffective).append(r.ineffective == 1 ? " measurement" : " measurements")
                    .append(" not counted: the phone stayed on 5G");
            if (!r.evidence.isEmpty()) {
                sb.append("\n    last reading: ").append(r.evidence);
            }
        }
        if (r.unverified > 0) {
            sb.append("\n    ").append(r.unverified).append(r.unverified == 1 ? " measurement" : " measurements")
                    .append(" made without being able to check the phone's cells");
        }
        if (!r.note.isEmpty()) {
            sb.append("\n    ").append(r.note);
        }
        return sb.toString();
    }

    /** What the table says, in plain words. */
    static String summary(List<Row> rows, int rounds) {
        if (rows.isEmpty()) {
            return "";
        }
        Row base = rows.get(0);
        StringBuilder sb = new StringBuilder();
        int done = 0;
        for (Row r : rows) {
            done = Math.max(done, r.n());
        }
        sb.append(String.format(Locale.US, "Each setting was measured once per round, %d of %d rounds so far, in a random order so a "
                + "slow stretch of the network does not favour one of them.", done, rounds));
        if (base.n() == 0) {
            return sb.append("\nThe phone's own setting has no result yet, so nothing can be compared.").toString();
        }
        sb.append(String.format(Locale.US, "\n\"%s\": median %.2f Mbit/s, between %.2f and %.2f in the rounds. That spread is the noise.",
                base.setting.label, base.median(), base.min(), base.max()));
        int best = bestOf(rows);
        if (best >= 0) {
            Row b = rows.get(best);
            int[] p = pairs(base, b);
            Verdict v = verdict(base, b);
            double ratio = base.median() > 0 ? b.median() / base.median() : 99;
            sb.append(String.format(Locale.US, "\nBest: \"%s\", median %.2f Mbit/s, %s times \"%s\", better in %d of %d rounds. ",
                    b.setting.label, b.median(), ratio >= 99 ? "many" : String.format(Locale.US, "%.1f", ratio), base.setting.label,
                    p[1], p[0]));
            sb.append(v == Verdict.CLEAR ? "That is a clear difference."
                    : "That is only a possible difference: run it again, ideally at the time of day that matters, before trusting it.");
        } else {
            sb.append("\nNo setting was clearly better than the phone's own choice here and now.");
        }
        for (int i = 1; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.notObeyed) {
                sb.append("\n\"").append(r.setting.label).append("\" was left out: the phone kept 5G although it was told not to, "
                        + "so there is no honest number for it (this phone does not obey that setting).");
            } else if (r.unavailable) {
                sb.append("\n\"").append(r.setting.label).append("\" is not possible on this phone's modem.");
            } else if (verdict(base, r) == Verdict.WORSE) {
                sb.append("\n\"").append(r.setting.label).append("\" was WORSE than \"").append(base.setting.label).append("\".");
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ the run

    /** A setting the phone took but did not obey (5G stayed) this many times in a row, with no number yet, is left out of the other rounds. */
    static final int DROP_AFTER = 2;

    private final Link link;
    private final Listener ui;
    private final List<Setting> wanted;
    private final int rounds;
    private final List<Row> rows = new ArrayList<>();
    /** {row, round} in the order they are measured. */
    private final List<int[]> schedule = new ArrayList<>();
    private int pos = -1;
    private boolean running;
    private boolean cancelled;
    private boolean finishing;
    private boolean origFiveGOff;
    private boolean origLegOff;
    private boolean legReadable;
    /**
     * The phone's modem says it cannot switch the 5G leg. Then the leg has no setting at all: the state the phone reports for it
     * ("off") is only the answer of a modem that has nothing to report, so it is not believed, and no setting is judged by it.
     */
    private boolean legUnsupported;
    /** The believed leg state is known to match the phone: it was read, or the run has set it itself. */
    private boolean legSynced;
    private boolean curFiveGOff;
    private boolean curLegOff;
    /** The 5G leg was switched at some point, so it must be looked at when the phone is put back. */
    private boolean legTouched;
    private String endNote = "";
    private String planNote = "";
    private Row row;
    private int round;
    private boolean escalated;

    private final java.util.Random rnd;

    LeverRun(Link link, Listener ui, List<Setting> settings, int rounds) {
        this(link, ui, settings, rounds, new java.util.Random());
    }

    /** rnd: the source of the random order (the test passes a seeded one). */
    LeverRun(Link link, Listener ui, List<Setting> settings, int rounds, java.util.Random rnd) {
        this.rnd = rnd;
        this.link = link;
        this.ui = ui;
        this.wanted = settings;
        this.rounds = Math.max(1, Math.min(rounds, 9));
    }

    boolean running() {
        return running;
    }

    List<Row> rows() {
        return rows;
    }

    int best() {
        return bestOf(rows);
    }

    int rounds() {
        return rounds;
    }

    String summary() {
        return summary(rows, rounds) + (planNote.isEmpty() ? "" : "\n" + planNote);
    }

    private static String join(String a, String b) {
        return a.isEmpty() ? b : a + "; " + b;
    }

    private void publish() {
        ui.rows(rows, best(), running);
    }

    void start() {
        if (running) {
            return;
        }
        running = true;
        cancelled = false;
        finishing = false;
        link.awake(true); // the tablet must not fall asleep half-way
        ui.progress("Reading how the phone is set…");
        link.fiveGOff((ok, text, off) -> {
            origFiveGOff = ok && Boolean.TRUE.equals(off);
            curFiveGOff = origFiveGOff;
            link.legOff((ok2, text2, off2) -> {
                legUnsupported = NrLeg.cannot(text2);
                legReadable = !legUnsupported && ok2 && off2 != null;
                legSynced = legReadable;
                origLegOff = legReadable && Boolean.TRUE.equals(off2);
                curLegOff = origLegOff;
                plan();
                next();
            });
        });
    }

    void cancel() {
        if (running && !finishing) {
            cancelled = true;
            ui.progress("Stopping after this step…");
        }
    }

    /** Works out what each setting means for this phone, drops repeats, and orders the measurements round by round. */
    private void plan() {
        List<Target> seen = new ArrayList<>();
        boolean asksForLeg = false;
        for (Setting s : wanted) {
            boolean fiveGOff = s.fiveG == null ? origFiveGOff : !s.fiveG;
            asksForLeg |= s.leg != null;
            if (legUnsupported && !fiveGOff && Boolean.FALSE.equals(s.leg)) {
                // "5G leg off" on a modem that says it cannot switch the leg: there is nothing to measure, and it is said so
                Row none = new Row(s, new Target(false, Boolean.TRUE), rounds);
                none.unavailable = true;
                none.note = "this phone's modem cannot switch the 5G leg";
                rows.add(none);
                continue;
            }
            // on a modem that cannot switch the leg the leg is no part of any setting (5G allowed is just 5G allowed)
            Boolean legOff = fiveGOff || legUnsupported ? null
                    : (s.leg == null ? Boolean.valueOf(origLegOff) : Boolean.valueOf(!s.leg));
            Target t = new Target(fiveGOff, legOff);
            boolean dup = false;
            for (Target o : seen) {
                dup |= o.sameAs(t);
            }
            if (dup) {
                planNote = join(planNote, "\"" + s.label + "\" is the same as a setting above on this phone, so it was measured once.");
                continue;
            }
            seen.add(t);
            rows.add(new Row(s, t, rounds));
        }
        if (legUnsupported && asksForLeg) {
            planNote = join(planNote, "This phone's modem says it cannot switch the 5G leg, so the leg settings are left out and 5G "
                    + "is measured as the phone has it.");
        }
        boolean usesLeg = false;
        for (Row r : rows) {
            usesLeg |= r.target.legOff != null;
        }
        if (!legReadable && !legUnsupported && usesLeg) {
            planNote = join(planNote, "This phone does not report whether the 5G leg is on, so the run sets it itself and leaves it on "
                    + "(the normal state) at the end.");
        }
        int n = rows.size();
        int prevFirst = -1;
        int prevLast = -1;
        for (int r = 0; r < rounds; r++) {
            int[] perm = new int[n];
            for (int i = 0; i < n; i++) {
                perm[i] = i;
            }
            for (int i = n - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int t = perm[i];
                perm[i] = perm[j];
                perm[j] = t;
            }
            if (n >= 3 && r > 0 && (perm[0] == prevFirst || perm[0] == prevLast)) {
                // not the setting that was first last time, and not the one that was just measured
                for (int i = 1; i < n; i++) {
                    if (perm[i] != prevFirst && perm[i] != prevLast) {
                        int t = perm[0];
                        perm[0] = perm[i];
                        perm[i] = t;
                        break;
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                schedule.add(new int[] {perm[i], r});
            }
            prevFirst = perm[0];
            prevLast = perm[n - 1];
        }
    }

    private void next() {
        if (cancelled) {
            finish();
            return;
        }
        while (true) {
            if (++pos >= schedule.size()) {
                finish();
                return;
            }
            if (!rows.get(schedule.get(pos)[0]).unavailable) {
                break;
            }
        }
        row = rows.get(schedule.get(pos)[0]);
        round = schedule.get(pos)[1];
        escalated = false;
        publish();
        say("setting up");
        applyFiveG();
    }

    private void say(String what) {
        ui.progress("Round " + (round + 1) + " of " + rounds + ": " + row.setting.label + " — " + what);
    }

    /** Leaves this measurement out (it could not be set up) and goes on. */
    private void skip(String why) {
        row.note = join(row.note, why);
        publish();
        next();
    }

    private void applyFiveG() {
        final Target t = row.target;
        if (t.fiveGOff == curFiveGOff) {
            applyLeg(false);
            return;
        }
        say(t.fiveGOff ? "switching 5G off" : "switching 5G on");
        link.switchFiveG(t.fiveGOff, (ok, text, v) -> {
            if (cancelled) {
                finish();
                return;
            }
            if (!ok) {
                skip("5G could not be switched " + (t.fiveGOff ? "off" : "on") + " (" + text + ")");
                return;
            }
            curFiveGOff = t.fiveGOff;
            applyLeg(true);
        });
    }

    private void applyLeg(final boolean switched5g) {
        final Boolean want = row.target.legOff;
        if (want == null || (want == curLegOff && legSynced)) {
            settle(switched5g ? 10000 : 1500);
            return;
        }
        say(want ? "switching the 5G leg off" : "switching the 5G leg on");
        legTouched = true;
        link.switchLeg(want ? "off" : "on", (ok, text, v) -> {
            if (cancelled) {
                finish();
                return;
            }
            if (!ok) {
                if (NrLeg.cannot(text)) {
                    // found out only now (the phone's reading did not say): from here on the leg is no part of any setting, and what the
                    // phone reported for it ("off") is not believed, so a note that was based on it is taken back
                    legUnsupported = true;
                    row.unavailable = true;
                    row.note = join(row.note, "this phone's modem cannot switch the 5G leg");
                    forgetKeptNrNotes();
                    publish();
                    next();
                } else {
                    skip("the 5G leg could not be switched (" + text + ")");
                }
                return;
            }
            curLegOff = want;
            legSynced = true;
            settle(switched5g ? 10000 : 6000);
        });
    }

    private void settle(long waitMs) {
        final long t0 = link.now();
        link.later(waitMs, () -> verify(t0));
    }

    /** Looks at the carriers until the setting has taken effect (no 5G cell when it says none), then measures. */
    private void verify(final long t0) {
        if (cancelled) {
            finish();
            return;
        }
        say("checking which cells the phone is on");
        link.observe((ok, text, obs) -> {
            if (cancelled) {
                finish();
                return;
            }
            if (!ok || obs == null || obs.state == null) {
                row.unverified++;
                measure();
                return;
            }
            row.using = BandSweep.usingText(obs.state, row.target.fiveGOff);
            row.rsrp = obs.rsrp;
            // A 5G carrier in the phone's list counts only if the phone's own 5G label and state do not contradict it: the list can
            // be out of date for a while after 5G was switched off (that made this run call a phone that had gone to 4G "still on 5G").
            if (!expectsNoNr(row) || !obs.state.nrCarrierInUse(row.target.fiveGOff)) {
                measure();
                return;
            }
            long waited = link.now() - t0;
            if (waited < 20000) {
                link.later(3000, () -> verify(t0));
                return;
            }
            if (isBaseline(row)) {
                // "As it is now" is what the phone does when it is left alone, and everything else is compared with it: it is measured
                // whatever the phone shows (and never asked to drop 5G), and the table says so.
                String said = row.target.fiveGOff ? KEPT_NR_NOTE_OFF : KEPT_NR_NOTE_LEG;
                if (!row.note.contains(said)) {
                    row.note = join(row.note, said);
                }
                measure();
                return;
            }
            if (Boolean.TRUE.equals(row.target.legOff) && !escalated) {
                // "off" lets the modem drop the 5G cell when the connection ends; "off now" drops it at once
                escalated = true;
                say("the 5G cell is still there: asking the modem to drop it now");
                link.switchLeg("off-now", (a, t, v) -> {
                    if (cancelled) {
                        finish();
                        return;
                    }
                    final long t1 = link.now();
                    link.later(6000, () -> verify(t1)); // up to 20 seconds in all after "off now", looking every 3 seconds
                });
                return;
            }
            row.ineffective++;
            row.evidence = obs.state.fiveGEvidence();
            if (row.n() == 0 && row.ineffective >= DROP_AFTER) {
                // the phone took the setting but kept 5G twice in a row: it will not obey, and every further round would only cost minutes
                row.unavailable = true;
                row.notObeyed = true;
            }
            publish();
            next();
        });
    }

    /** "As it is now" changes nothing, so it is never judged by what the phone shows. */
    private static boolean isBaseline(Row r) {
        return r.setting.fiveG == null && r.setting.leg == null;
    }

    static final String KEPT_NR_NOTE_OFF = "5G is switched off but the phone still shows a 5G cell: measured as it is";
    static final String KEPT_NR_NOTE_LEG = "the phone's 5G leg is switched off but it still uses 5G: measured as it is";

    /** Does this setting mean "the phone shows no 5G cell"? A leg setting means nothing on a modem that cannot switch the leg. */
    private boolean expectsNoNr(Row r) {
        return r.target.fiveGOff || (!legUnsupported && Boolean.TRUE.equals(r.target.legOff));
    }

    /** The modem turned out to have no 5G leg setting: a note that said the leg was off but still used is not true any more. */
    private void forgetKeptNrNotes() {
        for (Row r : rows) {
            if (r.note.contains(KEPT_NR_NOTE_LEG)) {
                String left = r.note.replace("; " + KEPT_NR_NOTE_LEG, "").replace(KEPT_NR_NOTE_LEG + "; ", "").replace(KEPT_NR_NOTE_LEG, "");
                r.note = left.trim();
            }
        }
    }

    /** The upload-only speed test on the phone's mobile data. */
    private void measure() {
        if (cancelled) {
            finish();
            return;
        }
        say("measuring upload");
        link.startUpload((ok, text, v) -> {
            if (cancelled) {
                link.stopUpload((a, b, c) -> finish()); // the test may have started: stop it before the phone is put back
                return;
            }
            if (!ok) {
                skip("the speed test would not start (" + text + ")");
                return;
            }
            watch(link.now());
        });
    }

    private void watch(final long t0) {
        link.later(1000, () -> link.uploadStatus((ok, text, u) -> {
            long waited = link.now() - t0;
            if (cancelled) {
                link.stopUpload((a, b, c) -> finish());
                return;
            }
            if (ok && u != null) {
                if (u.phase == 4) {
                    if (u.up <= 0 && !u.warn.isEmpty()) {
                        row.note = join(row.note, u.warn); // it measured nothing, which is not 0
                    } else {
                        row.byRound[round] = u.up;
                    }
                    publish();
                    next();
                    return;
                }
                if (u.phase == 5) {
                    row.note = join(row.note, "no data on this setting (" + u.error + ")");
                    publish();
                    next();
                    return;
                }
                say(String.format(Locale.US, "measuring upload (%.2f Mbit/s so far)", u.live));
            }
            if (waited > 45000) {
                link.stopUpload((a, b, c) -> skip("the test did not finish"));
                return;
            }
            watch(t0);
        }));
    }

    // ------------------------------------------------------------ putting the phone back

    private void finish() {
        if (!running || finishing) {
            return;
        }
        finishing = true;
        ui.progress("Putting the phone back as it was…");
        restoreFiveG(0);
    }

    /**
     * Asks the phone (it may have changed even if a reply was lost) and switches back only what differs. The phone may be busy or
     * out of reach for a moment after a switch, so a failed question or switch is tried again a few times.
     */
    private void restoreFiveG(final int attempt) {
        link.fiveGOff((ok, text, off) -> {
            if (!ok && attempt < 5) {
                ui.progress("Waiting for the phone to answer, to put 5G back as it was…");
                link.later(4000, () -> restoreFiveG(attempt + 1));
                return;
            }
            boolean now = ok ? Boolean.TRUE.equals(off) : curFiveGOff;
            if (now != origFiveGOff) {
                link.switchFiveG(origFiveGOff, (a, t, o) -> {
                    if (!a && attempt < 5) {
                        link.later(4000, () -> restoreFiveG(attempt + 1));
                        return;
                    }
                    if (!a) {
                        endNote = join(endNote, "5G could not be switched " + (origFiveGOff ? "off" : "on") + " again (" + t + ")");
                    }
                    restoreLeg(0);
                });
            } else {
                restoreLeg(0);
            }
        });
    }

    private void restoreLeg(final int attempt) {
        if (!legTouched) {
            end();
            return;
        }
        if (!legReadable) {
            setLegBack(curLegOff, attempt); // the phone cannot say; the run knows what it set last
            return;
        }
        link.legOff((ok, text, off) -> {
            if (!ok && attempt < 5) {
                ui.progress("Waiting for the phone to answer, to put the 5G leg back as it was…");
                link.later(4000, () -> restoreLeg(attempt + 1));
                return;
            }
            setLegBack(ok && off != null ? Boolean.TRUE.equals(off) : curLegOff, attempt);
        });
    }

    private void setLegBack(boolean now, final int attempt) {
        if (now == origLegOff) {
            end();
            return;
        }
        link.switchLeg(origLegOff ? "off" : "on", (a, t, o) -> {
            if (!a && attempt < 5) {
                link.later(4000, () -> restoreLeg(attempt + 1));
                return;
            }
            if (!a) {
                endNote = join(endNote, "the 5G leg could not be switched " + (origLegOff ? "off" : "on") + " again (" + t + ")");
            }
            end();
        });
    }

    private void end() {
        running = false;
        link.awake(false);
        publish();
        String head = cancelled ? "Stopped." : "Done.";
        if (!endNote.isEmpty()) {
            ui.progress(head + " The phone may not be back as it was: " + endNote + ". Press Restore everything on the Bands tab.");
        } else {
            ui.progress(head + " The phone is back as it was." + (cancelled ? "" : " Read the result below."));
        }
    }
}
