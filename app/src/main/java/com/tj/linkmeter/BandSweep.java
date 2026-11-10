package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tablet side, with no Android in it so it is tested on a computer against simulated phones (tools/test/BandSweepTest.java).
 *
 * Tries one band setting after another and measures the phone's upload speed on each, so "which band gives me the best upload
 * here?" gets an answer from measurements instead of guesses. For every step: set it (and switch 5G on or off if the step needs
 * that), wait for the phone to really move (checked against the carriers the phone says it is using, because "the modem accepted
 * it" proves nothing), then run an upload-only speed test on the phone's mobile data. At the end, or when stopped, or when
 * anything goes wrong, the band lock is cleared and 5G is put back as it really was (the phone is asked, not assumed).
 *
 * Safety: each lock also undoes itself on the phone after two minutes (Cells), and "Restore everything" always works. If the
 * phone's own safety net undoes a lock during the run (it lost all data), the run stops.
 */
final class BandSweep {
    interface Listener {
        /** What the run is doing now. */
        void progress(String text);

        /** The table so far; best is the index of the best row or -1. */
        void rows(List<Row> rows, int best, boolean running);
    }

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

    /** How the run talks to the phone and the clock. The real one is SweepLink; the test has a simulated phone. */
    interface Link {
        /** Is 5G switched off in the phone's allowed network types? ok = the phone answered in a way that could be read. */
        void fiveGOff(Done<Boolean> d);

        /** Switches 5G off (true) or on (false). ok = the phone says it is now as asked; value = whether 5G is off now. */
        void switchFiveG(boolean off, Done<Boolean> d);

        /** Locks to spec ("lte:13") or clears the lock (""). ok = the modem acknowledged it (which proves nothing). */
        void setLock(String spec, Done<Void> d);

        void observe(Done<Observation> d);

        void startUpload(Done<Void> d);

        void uploadStatus(Done<Upload> d);

        void stopUpload(Done<Void> d);

        /** The lock the phone has right now ("" = none). */
        void lockSpec(Done<String> d);

        /**
         * Asks the phone to search for a network again (its radio is switched off and on). A band lock tells the modem which bands to
         * SEARCH, so a phone that is already connected may keep its band until it searches again. The restart cuts the hotspot, so
         * the phone answers FIRST and restarts a moment later: ok = it took the request, nothing more (see researchState).
         */
        void research(Done<Void> d);

        /**
         * How that restart went: "running", "done" (the radio was switched off and on) or "failed: why". ok = false when the phone
         * cannot be reached, which is what happens while its radio restarts (the hotspot is down).
         */
        void researchState(Done<String> d);

        void later(long ms, Runnable r);

        long now();

        /** Keeps the tablet awake while a run is going on. */
        void awake(boolean on);
    }

    /** What one step found. */
    static final class Row {
        final SweepPlan.Step step;
        /** "obeyed", "NOT obeyed", "can't tell", "rejected", or "". */
        String verdict = "";
        /** The bands the phone was on, in words. */
        String using = "";
        /** Upload in Mbit/s, -1 if it could not be measured. With more than one check: their average. */
        double up = -1;
        /** Every upload measured for this setting, in Mbit/s. */
        final List<Double> samples = new ArrayList<>();
        /** One of the settings that were measured a second time (only these can be the best once that has happened). */
        boolean finalist;
        double ping = -1;
        int rsrp = Integer.MIN_VALUE;
        String note = "";

        Row(SweepPlan.Step step) {
            this.step = step;
        }

        /** Adds a measurement; up becomes the average of all of them. */
        void add(double mbit) {
            samples.add(mbit);
            double sum = 0;
            for (double v : samples) {
                sum += v;
            }
            up = sum / samples.size();
        }

        /** Does this row measure the setting it names? A lock that was not obeyed just measured the phone's own choice again. */
        boolean effective() {
            return step.spec.isEmpty() || !verdict.equals("NOT obeyed");
        }

        String line(boolean best) {
            StringBuilder sb = new StringBuilder(best ? "★ " : "  ").append(step.label).append("\n    ");
            sb.append(up >= 0 ? String.format(Locale.US, "upload %.1f Mbit/s", up) : "no upload result");
            if (samples.size() > 1) {
                sb.append(" (");
                for (int i = 0; i < samples.size(); i++) {
                    sb.append(i == 0 ? "" : i == samples.size() - 1 ? " and " : ", ").append(String.format(Locale.US, "%.1f", samples.get(i)));
                }
                sb.append(")");
            }
            if (ping >= 0) {
                sb.append(String.format(Locale.US, ", ping %.0f ms", ping));
            }
            if (rsrp != Integer.MIN_VALUE) {
                sb.append(", ").append(rsrp).append(" dBm");
            }
            sb.append("\n    ");
            if (!verdict.isEmpty()) {
                sb.append(verdict).append(using.isEmpty() ? "" : ": ");
            }
            sb.append(using);
            if (!note.isEmpty()) {
                sb.append("\n    ").append(note);
            }
            return sb.toString();
        }
    }

    private final Link link;
    private final Listener ui;
    private final List<SweepPlan.Step> steps;
    private final List<Row> rows = new ArrayList<>();
    private int index = -1;
    private boolean running;
    private boolean cancelled;
    private boolean finishing;
    /** After the main pass, measure the best three settings once more. */
    private final boolean confirm;
    /** After a lock, make the phone search again, because that is when the modem applies a band restriction. */
    private final boolean research;
    /** A band lock is set on the phone right now. */
    private boolean lockApplied;
    /** The phone has listed its carriers at least once (so an empty list later means "no service yet", not "cannot list"). */
    private boolean listedBefore;
    /** The last look found no carriers on a phone that had listed them before. */
    private boolean noService;
    /** How long poll() waits for the phone to settle on this step. */
    private long waitLimit = 22000;
    private int ignoredLocks;
    private int obeyedLocks;
    private int skippedLocks;
    /** Times the phone said it had searched again, and times that did not work or was not confirmed. */
    private int searchesConfirmed;
    private int searchesFailed;
    /** Something that went wrong while the phone was being put back, for the closing message. */
    private String endNote = "";
    private boolean confirming;
    private final List<Integer> queue = new ArrayList<>();
    private int queuePos = -1;
    private boolean originalFiveGOff;
    private boolean fiveGOff;
    private Row row;

    BandSweep(Link link, Listener ui, List<SweepPlan.Step> steps) {
        this(link, ui, steps, false);
    }

    /** confirm: after all the steps, the best three are measured once more and only those can be the best. */
    BandSweep(Link link, Listener ui, List<SweepPlan.Step> steps, boolean confirm) {
        this(link, ui, steps, confirm, false);
    }

    /** research: after each lock the phone is made to search again (its radio off and on) before it is checked and measured. */
    BandSweep(Link link, Listener ui, List<SweepPlan.Step> steps, boolean confirm, boolean research) {
        this.link = link;
        this.ui = ui;
        this.steps = steps;
        this.confirm = confirm;
        this.research = research;
    }

    /** Two locks in a row were not obeyed and none was: the phone ignores locks, another one would only repeat the same test. */
    private boolean locksIgnored() {
        return ignoredLocks >= 2 && obeyedLocks == 0;
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

    /**
     * The best row: the highest upload, among the settings that were measured twice if a second pass was made. A lock that was
     * not obeyed is never the best: it only measured the phone's own choice again, and "using" it would change nothing.
     */
    static int bestOf(List<Row> rows) {
        boolean anyFinalist = false;
        for (Row r : rows) {
            anyFinalist |= r.finalist;
        }
        double[] up = new double[rows.size()];
        for (int i = 0; i < up.length; i++) {
            Row r = rows.get(i);
            up[i] = !r.effective() || (anyFinalist && !r.finalist) ? -1 : r.up;
        }
        return BandCheck.best(up);
    }

    private static String join(String a, String b) {
        return a.isEmpty() ? b : a + "; " + b;
    }

    private void publish() {
        ui.rows(rows, best(), running);
    }

    // ------------------------------------------------------------ the run

    void start() {
        if (running) {
            return;
        }
        running = true;
        cancelled = false;
        finishing = false;
        link.awake(true); // the tablet must not fall asleep half-way
        ui.progress("Reading how 5G is set…");
        link.fiveGOff((ok, text, off) -> {
            originalFiveGOff = ok && Boolean.TRUE.equals(off);
            fiveGOff = originalFiveGOff;
            next();
        });
    }

    void cancel() {
        if (running && !finishing) {
            cancelled = true;
            ui.progress("Stopping after this step…");
        }
    }

    private void next() {
        if (cancelled) {
            finish();
            return;
        }
        if (confirming) {
            if (++queuePos >= queue.size()) {
                finish();
                return;
            }
            row = rows.get(queue.get(queuePos));
            publish();
            say(row.step, "setting up");
            setFiveG(row.step);
            return;
        }
        SweepPlan.Step s;
        while (true) {
            if (++index >= steps.size()) {
                if (startConfirming()) {
                    next();
                } else {
                    finish();
                }
                return;
            }
            s = steps.get(index);
            if (!s.spec.isEmpty() && locksIgnored()) {
                if (++skippedLocks == 1) {
                    ui.progress("The phone ignores band locks: skipping the rest of them…");
                }
                continue;
            }
            break;
        }
        row = new Row(s);
        rows.add(row);
        publish();
        say(s, "setting up");
        setFiveG(s);
    }

    /**
     * One 8-second upload test is noisy (one phone gave 2.9 to 7.5 Mbit/s on different bands), so the three best settings are
     * measured once more. Only those can then be the best, by their average. They are visited in plan order so 5G is switched
     * as little as possible. False if there is nothing to compare (fewer than two settings gave a number).
     */
    private boolean startConfirming() {
        if (!confirm) {
            return false;
        }
        List<Integer> measured = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).up >= 0 && rows.get(i).effective()) {
                measured.add(i);
            }
        }
        if (measured.size() < 2) {
            return false;
        }
        measured.sort((a, b) -> Double.compare(rows.get(b).up, rows.get(a).up)); // best first; a tie keeps the plan order
        List<Integer> top = new ArrayList<>(measured.subList(0, Math.min(3, measured.size())));
        java.util.Collections.sort(top);
        for (int i : top) {
            rows.get(i).finalist = true;
        }
        queue.addAll(top);
        confirming = true;
        return true;
    }

    private void say(SweepPlan.Step s, String what) {
        ui.progress((confirming ? "Checking again " + (queuePos + 1) + " of " + queue.size()
                : "Step " + (index + 1) + " of " + steps.size()) + ": " + s.label + " — " + what);
    }

    /** Switches 5G on or off if this step needs it, then goes on to the lock. */
    private void setFiveG(final SweepPlan.Step s) {
        if (s.fiveG == null || s.fiveG == !fiveGOff) {
            lock(s, false);
            return;
        }
        final boolean wantOff = !s.fiveG;
        say(s, wantOff ? "switching 5G off" : "switching 5G on");
        link.switchFiveG(wantOff, (ok, text, off) -> {
            if (cancelled) {
                finish();
                return;
            }
            if (ok) {
                fiveGOff = wantOff;
            } else {
                row.note = "5G could not be switched " + (wantOff ? "off" : "on") + ": " + text;
            }
            lock(s, true);
        });
    }

    private void lock(final SweepPlan.Step s, final boolean switched5g) {
        if (cancelled) {
            finish();
            return;
        }
        say(s, s.spec.isEmpty() ? "clearing the band lock" : "asking for " + s.spec);
        link.setLock(s.spec, (ok, text, v) -> {
            if (cancelled) {
                finish();
                return;
            }
            if (!ok) {
                if (confirming) {
                    row.note = join(row.note, "second check: the modem refused the lock: " + text);
                } else {
                    row.verdict = "rejected";
                    row.note = text;
                }
                publish();
                next();
                return;
            }
            boolean wasLocked = lockApplied;
            lockApplied = !s.spec.isEmpty();
            if (research && (lockApplied || wasLocked)) {
                // the modem applies a band restriction when it searches: make it search (this also puts the phone back on its own bands after a lock)
                say(s, "making the phone search again (the link to it drops for a while)");
                searchAgain(row, () -> {
                    waitLimit = 70000; // service is gone for a while, then comes back
                    final long t1 = link.now();
                    link.later(3000, () -> poll(s, t1));
                });
                return;
            }
            waitLimit = 22000;
            long wait = switched5g ? 10000 : (s.spec.isEmpty() ? 1500 : 4000); // the modem needs a few seconds to move
            final long t0 = link.now();
            link.later(wait, () -> poll(s, t0));
        });
    }

    /**
     * Makes the phone search for a network again and waits until it SAYS it has. The restart cuts the hotspot, so the reply to the
     * request cannot be waited for (that was the "connection abort" that looked like a failure): the phone answers first, restarts
     * a moment later, and is asked how it went once the link is back. noteTo: the row that gets a note if it did not work; null at
     * the end of the run (the note goes to the closing message).
     */
    private void searchAgain(final Row noteTo, final Runnable whenDone) {
        link.research((ok, text, v) -> {
            if (cancelled && !finishing) {
                finish();
                return;
            }
            if (!ok) {
                searchFailed(noteTo, "could not make the phone search again: " + text);
                whenDone.run();
                return;
            }
            awaitSearch(noteTo, link.now(), whenDone);
        });
    }

    private void awaitSearch(final Row noteTo, final long t0, final Runnable whenDone) {
        String waiting = "waiting for the phone's radio to restart (" + (link.now() - t0) / 1000 + " s)";
        if (noteTo != null) {
            say(noteTo.step, waiting);
        } else {
            ui.progress("Making the phone search again: " + waiting);
        }
        link.later(3000, () -> link.researchState((ok, text, state) -> {
            if (cancelled && !finishing) {
                finish();
                return;
            }
            if (ok && "done".equals(state)) {
                searchesConfirmed++;
                whenDone.run();
            } else if (ok && state != null && state.startsWith("failed")) {
                searchFailed(noteTo, "the phone could not search again: " + state.replaceFirst("^failed:?\\s*", ""));
                whenDone.run();
            } else if (link.now() - t0 > 90000) {
                searchFailed(noteTo, "the phone did not say that it had searched again (no answer for 90 seconds)");
                whenDone.run();
            } else {
                awaitSearch(noteTo, t0, whenDone);
            }
        }));
    }

    private void searchFailed(Row noteTo, String why) {
        searchesFailed++;
        if (noteTo != null) {
            noteTo.note = join(noteTo.note, why);
        } else {
            endNote = join(endNote, why);
        }
    }

    /** Asks what the phone is on until it matches what was asked for, or gives up. */
    private void poll(final SweepPlan.Step s, final long t0) {
        if (cancelled) {
            finish();
            return;
        }
        say(s, noService ? "waiting for the phone to find service" : "waiting for the phone to move");
        link.observe((ok, text, obs) -> {
            if (cancelled) {
                finish();
                return;
            }
            long waited = link.now() - t0;
            if (ok && obs != null) {
                RadioState rs = obs.state;
                if (!rs.carriers.isEmpty()) {
                    listedBefore = true;
                }
                // a phone that has listed its carriers and now lists none has no service yet: that is waited out, not "cannot tell"
                noService = rs.carriers.isEmpty() && listedBefore;
                BandCheck.Result v = BandCheck.verify(s.spec, rs.carriers);
                // the phone's carrier list can still name 5G carriers for a while after 5G was switched off: its own label and state decide
                boolean nrStillOn = rs.nrCarrierInUse(Boolean.FALSE.equals(s.fiveG));
                boolean fiveGWrong = Boolean.FALSE.equals(s.fiveG) && nrStillOn;
                if (!confirming) {
                    row.using = usingText(rs, Boolean.FALSE.equals(s.fiveG));
                    row.rsrp = obs.rsrp;
                }
                boolean settled = v.verdict == BandCheck.Verdict.OBEYED && !fiveGWrong;
                if (settled || waited >= waitLimit || (v.verdict == BandCheck.Verdict.UNKNOWN && !listedBefore && waited >= 10000)) {
                    String verdict = v.verdict == BandCheck.Verdict.OBEYED ? (s.spec.isEmpty() ? "" : "obeyed")
                            : v.verdict == BandCheck.Verdict.NOT_OBEYED ? "NOT obeyed" : "can't tell";
                    if (confirming) {
                        // the first check's verdict stays; a second check that differs is said so
                        if (!verdict.equals(row.verdict) || fiveGWrong) {
                            row.note = join(row.note, "second check: " + (verdict.isEmpty() ? "no lock" : verdict)
                                    + (fiveGWrong ? ", 5G was still on" : ""));
                        }
                    } else {
                        row.verdict = verdict;
                        if (!s.spec.isEmpty()) {
                            if (verdict.equals("NOT obeyed")) {
                                ignoredLocks++;
                            } else if (verdict.equals("obeyed")) {
                                obeyedLocks++;
                            }
                        }
                        if (fiveGWrong) {
                            row.note = "5G was still on";
                        }
                    }
                    publish();
                    measure(s);
                    return;
                }
            } else if (waited >= waitLimit) {
                if (confirming) {
                    row.note = join(row.note, "second check: the phone could not be read");
                } else {
                    row.verdict = "can't tell";
                    row.note = ok ? "" : text;
                }
                publish();
                measure(s);
                return;
            }
            link.later(2500, () -> poll(s, t0));
        });
    }

    /** The upload-only speed test on the phone's mobile data. */
    private void measure(final SweepPlan.Step s) {
        if (cancelled) {
            finish();
            return;
        }
        say(s, "measuring upload");
        link.startUpload((ok, text, v) -> {
            if (cancelled) {
                // Stop was pressed while the request was on its way: the test may have started, so it is stopped before the phone is put back
                link.stopUpload((a, b, c) -> finish());
                return;
            }
            if (!ok) {
                row.note = (row.note.isEmpty() ? "" : row.note + "; ") + "the speed test would not start: " + text;
                publish();
                afterStep(s);
                return;
            }
            watch(s, link.now());
        });
    }

    private void watch(final SweepPlan.Step s, final long t0) {
        link.later(1000, () -> link.uploadStatus((ok, text, u) -> {
            long waited = link.now() - t0;
            if (cancelled) {
                link.stopUpload((a, b, c) -> finish());
                return;
            }
            if (ok && u != null) {
                if (u.phase == 4) { // done
                    // a test that finished with a warning and no speed did not measure 0: it measured nothing
                    boolean unmeasured = u.up <= 0 && !u.warn.isEmpty();
                    if (unmeasured) {
                        row.note = join(row.note, u.warn);
                    } else {
                        row.add(u.up);
                    }
                    row.ping = u.ping;
                    publish();
                    afterStep(s);
                    return;
                }
                if (u.phase == 5) { // error
                    row.note = (row.note.isEmpty() ? "" : row.note + "; ") + "no data on this setting: " + u.error;
                    publish();
                    afterStep(s);
                    return;
                }
                say(s, String.format(Locale.US, "measuring upload (%.1f Mbit/s so far)", u.live));
            }
            if (waited > 45000) {
                link.stopUpload((a, b, c) -> {
                    row.note = (row.note.isEmpty() ? "" : row.note + "; ") + "the test did not finish";
                    publish();
                    afterStep(s);
                });
                return;
            }
            watch(s, t0);
        }));
    }

    /** Before the next step: the lock must still be there. The phone undoes it by itself when it loses all data. */
    private void afterStep(final SweepPlan.Step s) {
        if (s.spec.isEmpty() || cancelled) {
            next();
            return;
        }
        link.lockSpec((ok, text, lock) -> {
            if (ok && lock != null && !lock.equals(s.spec)) {
                row.note = (row.note.isEmpty() ? "" : row.note + "; ") + "the phone undid the lock (its safety net: no data)";
                publish();
                cancelled = true; // the phone has put itself back: stop here
                finish();
                return;
            }
            next();
        });
    }

    /** Puts the phone back: no band lock, and 5G as it really was before the run. */
    private void finish() {
        if (!running || finishing) {
            return;
        }
        finishing = true;
        ui.progress("Putting the phone back as it was…");
        clearLock(0);
    }

    /**
     * Clears the band lock. The phone may not be reachable right now (a stop during its radio restart: the hotspot is down with
     * the radio), so a failed request is tried again for a while before it is given up.
     */
    private void clearLock(final int attempt) {
        link.setLock("", (ok, text, v) -> {
            if (!ok && attempt < 8) {
                ui.progress("Waiting for the phone to answer, to clear the band lock…");
                link.later(5000, () -> clearLock(attempt + 1));
                return;
            }
            if (!ok) {
                endNote = join(endNote, "the band lock could not be cleared (" + text + ")");
            }
            if (research && lockApplied) {
                lockApplied = false;
                ui.progress("Making the phone search again, so it is back on its own bands…");
                searchAgain(null, this::restoreFiveG);
            } else {
                restoreFiveG();
            }
        });
    }

    private void restoreFiveG() {
        restoreFiveG(0);
    }

    /**
     * Asks the phone (it may have changed even if a reply was lost) and switches 5G back only if it differs. The phone may still be
     * reconnecting after its radio restart, so a failed question or switch is tried again a few times before it is given up.
     */
    private void restoreFiveG(final int attempt) {
        link.fiveGOff((ok, text, off) -> {
            if (!ok && attempt < 5) {
                ui.progress("Waiting for the phone to answer, to put 5G back as it was…");
                link.later(4000, () -> restoreFiveG(attempt + 1));
                return;
            }
            boolean now = ok ? Boolean.TRUE.equals(off) : fiveGOff;
            if (now != originalFiveGOff) {
                link.switchFiveG(originalFiveGOff, (a, t, o) -> {
                    if (!a && attempt < 5) {
                        link.later(4000, () -> restoreFiveG(attempt + 1));
                        return;
                    }
                    if (!a) {
                        endNote = join(endNote, "5G could not be switched " + (originalFiveGOff ? "off" : "on") + " again (" + t + ")");
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
            ui.progress(head + " The phone may not be back as it was: " + endNote + ". Check the Phone tab: Restore everything puts it back.");
        } else {
            ui.progress(head + " The phone is back as it was." + (cancelled ? "" : " Pick the best below."));
        }
    }

    // ------------------------------------------------------------ words

    /** What the finished table says, in plain words: were locks obeyed at all, and how much better is the best one. */
    static String summary(List<Row> rows) {
        return summary(rows, false, 0);
    }

    /**
     * This run's summary. "Searched again" counts only searches the phone confirmed; if searching was asked for and none could be
     * confirmed, the summary says so instead of claiming that locks were tried on a fresh search.
     */
    String summary() {
        return summary(rows, research && searchesConfirmed > 0, skippedLocks, research && searchesConfirmed == 0 && searchesFailed > 0);
    }

    private static boolean sameSituation(Row a, Row b) {
        return !a.using.isEmpty() && !a.using.startsWith("carriers not listed") && a.using.equals(b.using);
    }

    static String summary(List<Row> rows, boolean searchedAgain, int skippedLocks) {
        return summary(rows, searchedAgain, skippedLocks, false);
    }

    /** searchedAgain: the phone confirmed it searched again after a lock. searchFailed: it was asked to, and never confirmed. */
    static String summary(List<Row> rows, boolean searchedAgain, int skippedLocks, boolean searchFailed) {
        int locks = 0;
        int obeyed = 0;
        int notObeyed = 0;
        int cantTell = 0;
        int rejected = 0;
        for (Row r : rows) {
            if (r.step.spec.isEmpty()) {
                continue;
            }
            locks++;
            if (r.verdict.equals("obeyed")) {
                obeyed++;
            } else if (r.verdict.equals("NOT obeyed")) {
                notObeyed++;
            } else if (r.verdict.equals("rejected")) {
                rejected++;
            } else {
                cantTell++;
            }
        }
        StringBuilder sb = new StringBuilder();
        if (locks > 0) {
            if (rejected == locks) {
                sb.append("The modem rejected every band lock: this phone does not take band locks from here.");
            } else if (notObeyed == locks - rejected && notObeyed > 0 && obeyed == 0) {
                if (searchedAgain) {
                    sb.append("The phone did not obey any band lock, even after searching again: it keeps its own bands, so "
                            + "locking bands from here will not change what it uses. Only 5G on or off does.");
                } else if (searchFailed) {
                    sb.append("The phone did not obey any band lock, but it never confirmed that it searched for a network again "
                            + "after one (see the notes below), so this does not yet prove that locks cannot work. Press \"Make the "
                            + "phone search again\" on its own, wait for the phone to come back, then Scan cells.");
                } else {
                    sb.append("The phone did not obey any of the band locks it acknowledged. A lock may only apply when the phone "
                            + "searches for a network again: tick \"Make the phone search again after each lock\" and run it "
                            + "again. If it still stays on its own bands, band locks cannot change what this phone uses; only "
                            + "5G on or off does.");
                }
                // the rows that were not obeyed are all the same connection: how far apart they are is the noise
                double lo = Double.MAX_VALUE;
                double hi = -1;
                int n = 0;
                for (Row r : rows) {
                    if (!r.effective()) {
                        for (double v : r.samples) {
                            lo = Math.min(lo, v);
                            hi = Math.max(hi, v);
                            n++;
                        }
                    }
                }
                if (n >= 2) {
                    sb.append(String.format(Locale.US, "\nThe %d tests of locks that were not obeyed are the same connection "
                            + "measured again and again: %.1f to %.1f Mbit/s. That spread is the noise.", n, lo, hi));
                }
            } else if (obeyed == locks) {
                sb.append("Every lock was obeyed, so each number is for the band named.");
            } else if (obeyed > 0) {
                sb.append("Some locks were obeyed (").append(obeyed).append(" of ").append(locks)
                        .append("), some were not: trust the rows that say \"obeyed\".");
            } else if (cantTell == locks - rejected) {
                sb.append("Whether the phone obeyed the locks could not be checked (it does not list its carriers). If the "
                        + "upload numbers are all about the same, the locks are probably doing nothing.");
            }
        }
        if (skippedLocks > 0) {
            sb.append(sb.length() > 0 ? "\n" : "").append("Skipped ").append(skippedLocks).append(skippedLocks == 1 ? " more band lock"
                    : " more band locks").append(": the phone had ignored the first ones.");
        }
        int best = bestOf(rows);
        if (best >= 0 && !rows.isEmpty() && rows.get(0).up > 0 && best != 0 && !sameSituation(rows.get(best), rows.get(0))) {
            double times = rows.get(best).up / rows.get(0).up;
            if (times >= 1.3) {
                int n = rows.get(best).samples.size();
                sb.append(sb.length() > 0 ? "\n" : "").append(String.format(Locale.US,
                        "Best: %s, upload %.1f Mbit/s%s: %.1f times what the phone chooses by itself (%.1f).",
                        rows.get(best).step.label, rows.get(best).up, n > 1 ? " (average of " + n + " checks)" : "", times, rows.get(0).up));
            }
        }
        return sb.toString();
    }

    /** "B66 + B2 + n77 (5G UW)" */
    static String usingText(RadioState s) {
        return usingText(s, false);
    }

    /**
     * The carriers in use, in words. 5G carriers that the phone's own label and state say are not in use (an out-of-date list, see
     * RadioState.fiveGInUse) are left out and named at the end, so the text does not claim 5G for a phone that is on 4G.
     * fiveGOff: 5G was switched off by the caller and read back.
     */
    static String usingText(RadioState s, boolean fiveGOff) {
        boolean hasLte = false;
        for (RadioState.Carrier c : s.carriers) {
            hasLte |= !c.nr();
        }
        boolean stale = hasLte && s.staleNr(fiveGOff);
        StringBuilder sb = new StringBuilder();
        StringBuilder old = new StringBuilder();
        for (RadioState.Carrier c : s.carriers) {
            if (stale && c.nr()) {
                old.append(old.length() > 0 ? " + " : "").append(c.bandName());
                continue;
            }
            sb.append(sb.length() > 0 ? " + " : "").append(c.bandName());
        }
        if (sb.length() == 0) {
            sb.append("carriers not listed");
        }
        String label = s.display.equals("NR_ADVANCED") ? "5G UW" : s.display.equals("NR_NSA") ? "5G"
                : (s.display.equals("LTE_CA") || s.display.equals("LTE_ADVANCED_PRO")) ? "LTE+" : "";
        String text = label.isEmpty() ? sb.toString() : sb + " (" + label + ")";
        return old.length() == 0 ? text : text + " [the phone still lists " + old + " but says 5G is not in use: old entries]";
    }
}
