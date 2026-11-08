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

        Upload(int phase, double up, double ping, double live, String error) {
            this.phase = phase;
            this.up = up;
            this.ping = ping;
            this.live = live;
            this.error = error;
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
        /** Upload in Mbit/s, -1 if it could not be measured. */
        double up = -1;
        double ping = -1;
        int rsrp = Integer.MIN_VALUE;
        String note = "";

        Row(SweepPlan.Step step) {
            this.step = step;
        }

        String line(boolean best) {
            StringBuilder sb = new StringBuilder(best ? "★ " : "  ").append(step.label).append("\n    ");
            sb.append(up >= 0 ? String.format(Locale.US, "upload %.1f Mbit/s", up) : "no upload result");
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
    private boolean originalFiveGOff;
    private boolean fiveGOff;
    private Row row;

    BandSweep(Link link, Listener ui, List<SweepPlan.Step> steps) {
        this.link = link;
        this.ui = ui;
        this.steps = steps;
    }

    boolean running() {
        return running;
    }

    List<Row> rows() {
        return rows;
    }

    int best() {
        double[] up = new double[rows.size()];
        for (int i = 0; i < up.length; i++) {
            up[i] = rows.get(i).up;
        }
        return BandCheck.best(up);
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
        if (cancelled || ++index >= steps.size()) {
            finish();
            return;
        }
        SweepPlan.Step s = steps.get(index);
        row = new Row(s);
        rows.add(row);
        publish();
        say(s, "setting up");
        setFiveG(s);
    }

    private void say(SweepPlan.Step s, String what) {
        ui.progress("Step " + (index + 1) + " of " + steps.size() + ": " + s.label + " — " + what);
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
                row.verdict = "rejected";
                row.note = text;
                publish();
                next();
                return;
            }
            long wait = switched5g ? 10000 : (s.spec.isEmpty() ? 1500 : 4000); // the modem needs a few seconds to move
            final long t0 = link.now();
            link.later(wait, () -> poll(s, t0));
        });
    }

    /** Asks what the phone is on until it matches what was asked for, or gives up. */
    private void poll(final SweepPlan.Step s, final long t0) {
        if (cancelled) {
            finish();
            return;
        }
        say(s, "waiting for the phone to move");
        link.observe((ok, text, obs) -> {
            if (cancelled) {
                finish();
                return;
            }
            long waited = link.now() - t0;
            if (ok && obs != null) {
                RadioState rs = obs.state;
                BandCheck.Result v = BandCheck.verify(s.spec, rs.carriers);
                boolean nrStillOn = false;
                for (RadioState.Carrier c : rs.carriers) {
                    nrStillOn |= c.nr();
                }
                boolean fiveGWrong = Boolean.FALSE.equals(s.fiveG) && nrStillOn;
                row.using = usingText(rs);
                row.rsrp = obs.rsrp;
                boolean settled = v.verdict == BandCheck.Verdict.OBEYED && !fiveGWrong;
                if (settled || waited >= 22000 || (v.verdict == BandCheck.Verdict.UNKNOWN && waited >= 10000)) {
                    row.verdict = v.verdict == BandCheck.Verdict.OBEYED ? (s.spec.isEmpty() ? "" : "obeyed")
                            : v.verdict == BandCheck.Verdict.NOT_OBEYED ? "NOT obeyed" : "can't tell";
                    if (fiveGWrong) {
                        row.note = "5G was still on";
                    }
                    publish();
                    measure(s);
                    return;
                }
            } else if (waited >= 22000) {
                row.verdict = "can't tell";
                row.note = ok ? "" : text;
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
                finish();
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
                    row.up = u.up;
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
        link.setLock("", (ok, text, v) -> restoreFiveG());
    }

    /** Asks the phone (it may have changed even if a reply was lost) and switches 5G back only if it differs. */
    private void restoreFiveG() {
        link.fiveGOff((ok, text, off) -> {
            boolean now = ok ? Boolean.TRUE.equals(off) : fiveGOff;
            if (now != originalFiveGOff) {
                link.switchFiveG(originalFiveGOff, (a, t, o) -> end());
            } else {
                end();
            }
        });
    }

    private void end() {
        running = false;
        link.awake(false);
        publish();
        ui.progress(cancelled ? "Stopped. The phone is back as it was." : "Done. The phone is back as it was. Pick the best below.");
    }

    // ------------------------------------------------------------ words

    /** What the finished table says, in plain words: were locks obeyed at all, and how much better is the best one. */
    static String summary(List<Row> rows) {
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
                sb.append("The phone did not obey any of the band locks it acknowledged: it keeps its own bands, so locking "
                        + "bands from here will not change what it uses. The upload numbers below mostly show the noise "
                        + "of the connection.");
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
        double[] up = new double[rows.size()];
        for (int i = 0; i < up.length; i++) {
            up[i] = rows.get(i).up;
        }
        int best = BandCheck.best(up);
        if (best >= 0 && !rows.isEmpty() && rows.get(0).up > 0 && best != 0) {
            double times = rows.get(best).up / rows.get(0).up;
            if (times >= 1.3) {
                sb.append(sb.length() > 0 ? "\n" : "").append(String.format(Locale.US,
                        "Best: %s, upload %.1f Mbit/s: %.1f times what the phone chooses by itself (%.1f).",
                        rows.get(best).step.label, rows.get(best).up, times, rows.get(0).up));
            }
        }
        return sb.toString();
    }

    /** "B66 + B2 + n77 (5G UW)" */
    static String usingText(RadioState s) {
        StringBuilder sb = new StringBuilder();
        for (RadioState.Carrier c : s.carriers) {
            sb.append(sb.length() > 0 ? " + " : "").append(c.bandName());
        }
        if (sb.length() == 0) {
            sb.append("carriers not listed");
        }
        String label = s.display.equals("NR_ADVANCED") ? "5G UW" : s.display.equals("NR_NSA") ? "5G"
                : (s.display.equals("LTE_CA") || s.display.equals("LTE_ADVANCED_PRO")) ? "LTE+" : "";
        return label.isEmpty() ? sb.toString() : sb + " (" + label + ")";
    }
}
