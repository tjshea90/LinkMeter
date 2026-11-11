package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Tablet side, with no Android in it, so it is tested on a computer against simulated phones (tools/test/LeverRunTest.java).
 *
 * Compares the two things this phone DOES obey: how it is now, and the other 5G state (5G off = 4G only, or 5G allowed), on the
 * upload speed of its mobile data. (Versions up to v0.2.102 also compared "the 5G leg off"; this modem answered NOT_SUPPORTED to
 * that, and band locks were never obeyed either, so a run now has exactly two settings.) Built for a bad place and a bad time of
 * day, where an upload test gives 0.1 to 1 Mbit/s and the network itself changes from minute to minute:
 *  - each setting is measured once in every ROUND, in a RANDOM order within the round (each round's order is a fair coin), so a busy
 *    or quiet stretch of the network hits the settings by chance and not by position;
 *  - the verdict compares each setting with "as it is now" round by round. Because the order was random, "this setting makes no
 *    difference" means each round is a fair coin flip, so the chance of so many wins by luck is exact (a sign test): a CLEAR
 *    difference needs 1.3 times the median AND a luck chance of 5% or less (5 of 5 rounds, 6 of 6, 7 of 7, 8 of 9), a possible
 *    one 1.15 times and 20% or less (4 of 5). Three or four rounds can only suggest, never prove. Otherwise it says "no clear
 *    difference", with the spread of the baseline rounds as the noise;
 *  - a setting is only counted after the phone's carrier list shows it took effect (no 5G cell left when the setting says none),
 *    and one that the phone does not obey twice is left out;
 *  - however the run ends (done, stopped, a failure, a lost phone), 5G is put back as it was, with retries.
 */
final class LeverRun {
    interface Listener {
        /** What the run is doing now. */
        void progress(String text);

        /** The table so far; best is the index of the row to recommend or -1. */
        void rows(List<Row> rows, int best, boolean running);
    }

    /** The answer to one question to the phone: ok = it could be read, text = the phone's words (or why not), value = what was read. */
    interface Done<T> {
        void done(boolean ok, String text, T value);
    }

    /** What the phone's radio was using at one moment. */
    static final class Observation {
        final RadioState state;
        final int rsrp;

        Observation(RadioState state, int rsrp) {
            this.state = state;
            this.rsrp = rsrp;
        }
    }

    /** The phone's speed test, as far as the run needs it. phase: 4 = done, 5 = error, others = still going or idle. */
    static final class Upload {
        final int phase;
        final double up;
        final double ping;
        final double live;
        final String error;
        /** Why a test that finished has no upload number ("" = none). The phone leaves the number at 0 then, which is not a measurement. */
        final String warn;

        Upload(int phase, double up, double ping, double live, String error) {
            this(phase, up, ping, live, error, "");
        }

        Upload(int phase, double up, double ping, double live, String error, String warn) {
            this.phase = phase;
            this.up = up;
            this.ping = ping;
            this.live = live;
            this.error = error;
            this.warn = warn == null ? "" : warn;
        }
    }

    /** How the run talks to the phone and the clock. The real one is LeverLink; the test has a simulated phone. */
    interface Link {
        /** Is 5G switched off in the phone's allowed network types? ok = the phone answered in a way that could be read. */
        void fiveGOff(Done<Boolean> d);

        /** Switches 5G off (true) or on (false). ok = the phone says it is now as asked. */
        void switchFiveG(boolean off, Done<Boolean> d);

        void observe(Done<Observation> d);

        void startUpload(Done<Void> d);

        void uploadStatus(Done<Upload> d);

        void stopUpload(Done<Void> d);

        void later(long ms, Runnable r);

        long now();

        /** Keeps the tablet awake while a run is going on. */
        void awake(boolean on);
    }

    // ------------------------------------------------------------ what is compared

    /** One thing to compare. fiveG: TRUE = 5G allowed, FALSE = 5G off (4G only), null = as it was when the run started. */
    static final class Setting {
        final String label;
        final Boolean fiveG;

        Setting(String label, Boolean fiveG) {
            this.label = label;
            this.fiveG = fiveG;
        }
    }

    /**
     * The settings the tab offers. "As it is now" is always first: it is what everything else is compared with. The tab always asks
     * for both of the others; the one that is the same as how the phone is now is dropped when the run starts, and the table says so.
     */
    static List<Setting> settings(boolean fourGOnly, boolean fiveGAllowed) {
        List<Setting> l = new ArrayList<>();
        l.add(new Setting("As it is now", null));
        if (fourGOnly) {
            l.add(new Setting("4G only (5G off)", Boolean.FALSE));
        }
        if (fiveGAllowed) {
            l.add(new Setting("5G allowed", Boolean.TRUE));
        }
        return l;
    }

    // ------------------------------------------------------------ results

    /** What one setting found. */
    static final class Row {
        final Setting setting;
        /** What the setting means for the phone, once "as it was" is known: 5G off (4G only) or not. */
        final boolean fiveGOff;
        /** Upload in Mbit/s per round; NaN = not measured in that round. */
        final double[] byRound;
        /** Measurements that did not count: the phone stayed on 5G although the setting says none. */
        int ineffective;
        /** Measurements made although the carriers could not be listed. */
        int unverified;
        /** The phone took the setting but kept 5G anyway, twice in a row: it is left out of the other rounds. */
        boolean notObeyed;
        /** What the phone said about 5G the last time a measurement was not counted (its list, its label, its state). */
        String evidence = "";
        String using = "";
        int rsrp = Integer.MIN_VALUE;
        String note = "";

        Row(Setting setting, boolean fiveGOff, int rounds) {
            this.setting = setting;
            this.fiveGOff = fiveGOff;
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
        if (p[0] < 3 || r.notObeyed) {
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
            sb.append(r.notObeyed ? "left out: this phone does not obey it" : "no upload result");
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
        sb.append(done >= rounds
                ? String.format(Locale.US, "Each setting was measured once in each of the %d rounds, in a random order so a slow stretch "
                        + "of the network does not favour one of them.", rounds)
                : String.format(Locale.US, "Each setting was measured once per round, %d of %d rounds so far, in a random order so a "
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
    /** The title of the tab that holds the Restore everything button, for the closing words (the tablet passes its Tab constant). */
    private final String tabName;
    private final List<Row> rows = new ArrayList<>();
    /** {row, round} in the order they are measured. */
    private final List<int[]> schedule = new ArrayList<>();
    private int pos = -1;
    private boolean running;
    private boolean cancelled;
    private boolean finishing;
    private boolean origFiveGOff;
    private boolean curFiveGOff;
    private String endNote = "";
    private String planNote = "";
    private Row row;
    private int round;

    private final java.util.Random rnd;

    LeverRun(Link link, Listener ui, List<Setting> settings, int rounds, String tabName) {
        this(link, ui, settings, rounds, tabName, new java.util.Random());
    }

    /** rnd: the source of the random order (the test passes a seeded one; neighbouring small seeds need scrambling, see the test). */
    LeverRun(Link link, Listener ui, List<Setting> settings, int rounds, String tabName, java.util.Random rnd) {
        this.rnd = rnd;
        this.link = link;
        this.ui = ui;
        this.wanted = settings;
        this.rounds = Math.max(1, Math.min(rounds, 9));
        this.tabName = tabName;
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
            plan();
            next();
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
        List<Boolean> seen = new ArrayList<>();
        for (Setting s : wanted) {
            boolean fiveGOff = s.fiveG == null ? origFiveGOff : !s.fiveG;
            if (seen.contains(fiveGOff)) {
                planNote = join(planNote, "\"" + s.label + "\" is the same as a setting above on this phone, so it was measured once.");
                continue;
            }
            seen.add(fiveGOff);
            rows.add(new Row(s, fiveGOff, rounds));
        }
        int n = rows.size();
        for (int r = 0; r < rounds; r++) {
            int[] perm = new int[n];
            for (int i = 0; i < n; i++) {
                perm[i] = i;
            }
            // a fresh shuffle every round: with two settings that is a fair coin for which one comes first, which is exactly what
            // the sign test in verdict() assumes
            for (int i = n - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int t = perm[i];
                perm[i] = perm[j];
                perm[j] = t;
            }
            for (int i = 0; i < n; i++) {
                schedule.add(new int[] {perm[i], r});
            }
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
            if (!rows.get(schedule.get(pos)[0]).notObeyed) {
                break;
            }
        }
        row = rows.get(schedule.get(pos)[0]);
        round = schedule.get(pos)[1];
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
        final boolean off = row.fiveGOff;
        if (off == curFiveGOff) {
            settle(1500);
            return;
        }
        say(off ? "switching 5G off" : "switching 5G on");
        link.switchFiveG(off, (ok, text, v) -> {
            if (cancelled) {
                finish();
                return;
            }
            if (!ok) {
                skip("5G could not be switched " + (off ? "off" : "on") + " (" + text + ")");
                return;
            }
            curFiveGOff = off;
            settle(10000);
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
            row.using = obs.state.usingText(row.fiveGOff);
            row.rsrp = obs.rsrp;
            // A 5G carrier in the phone's list counts only if the phone's own 5G label and state do not contradict it: the list can
            // be out of date for a while after 5G was switched off (that made this run call a phone that had gone to 4G "still on 5G").
            if (!row.fiveGOff || !obs.state.nrCarrierInUse(true)) {
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
                // whatever the phone shows (and never thrown away), and the table says so.
                if (!row.note.contains(KEPT_NR_NOTE)) {
                    row.note = join(row.note, KEPT_NR_NOTE);
                }
                measure();
                return;
            }
            row.ineffective++;
            row.evidence = obs.state.fiveGEvidence();
            if (row.n() == 0 && row.ineffective >= DROP_AFTER) {
                // the phone took the setting but kept 5G twice in a row: it will not obey, and every further round would only cost minutes
                row.notObeyed = true;
            }
            publish();
            next();
        });
    }

    /** "As it is now" changes nothing, so it is never judged by what the phone shows. */
    private static boolean isBaseline(Row r) {
        return r.setting.fiveG == null;
    }

    static final String KEPT_NR_NOTE = "5G is switched off but the phone still shows a 5G cell: measured as it is";

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
                    end();
                });
            } else {
                end();
            }
        });
    }

    private void end() {
        running = false;
        link.awake(false);
        publish();
        String head = cancelled ? "Stopped." : "Done.";
        if (!endNote.isEmpty()) {
            ui.progress(head + " The phone may not be back as it was: " + endNote + ". Press Restore everything on the " + tabName + " tab.");
        } else {
            ui.progress(head + " The phone is back as it was." + (cancelled ? "" : " Read the result below."));
        }
    }
}
