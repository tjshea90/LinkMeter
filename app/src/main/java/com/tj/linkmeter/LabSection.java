package com.tj.linkmeter;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * The part of the Bands tab for things this phone DOES obey: a fair comparison of 4G only and 5G on the upload speed, the phone's
 * own Phone info screen, and a helper for finding the best spot. (Versions up to v0.2.102 also had the 5G leg here; this modem
 * never obeyed it.) The views are thin; the logic is in LeverRun (tested on a computer). Like the comparison, a run lives on in
 * this object when the tab is left and shown again, so every callback checks that its view still exists.
 */
final class LabSection {
    private final MainActivity act;

    // ---- the safety check
    private TextView safetyView;
    private String safetyMsg = "";

    // ---- the comparison
    private EditText roundsField;
    private Button startBtn;
    private Button stopBtn;
    private Button useBtn;
    private TextView progressView;
    private TextView tableView;
    private LeverRun run;
    private List<LeverRun.Row> rows = new ArrayList<>();
    private int best = -1;
    private String progressMsg = "";
    private String tableMsg = "";

    // ---- the Phone info screen
    private TextView menuView;
    private String menuMsg = "";

    // ---- the placement helper
    private final SignalHunt hunt = new SignalHunt();
    private final Beeper beeper = new Beeper();
    private boolean hunting;
    /** Each start gets a new number, so a loop from an earlier start ends when it sees that it is not the newest. */
    private int huntRun;
    private Button huntBtn;
    private TextView huntView;
    private TextView spotsView;
    private EditText labelField;
    private CheckBox beepBox;
    private String huntMsg = "";
    private String spotsMsg = "";

    LabSection(MainActivity act) {
        this.act = act;
    }

    /** The tab was left: no more questions to the phone and no more beeps. */
    void stop() {
        hunting = false;
        huntRun++;
        beeper.release();
        if (huntBtn != null) {
            huntBtn.setText("Start");
        }
    }

    void build(LinearLayout col) {
        Button safe = Ui.button(act, "Safety check: is anything still changed?", x -> safetyCheck());
        col.addView(safe, Ui.lp(act, 16));
        safetyView = Ui.text(act, safetyMsg, 12, Ui.FG, false);
        mono(safetyView);
        col.addView(safetyView, Ui.lp(act, 4));
        buildCompare(col);
        buildMenus(col);
        buildHunt(col);
    }

    private void safetyCheck() {
        safetyMsg = "Checking the phone…";
        if (safetyView != null) {
            safetyView.setText(safetyMsg);
        }
        Call.run(act, "POST", "/act?name=safety", 40000, (ok, text, json) -> {
            safetyMsg = text;
            if (safetyView != null) {
                safetyView.setText(text);
            }
        });
    }

    private static void mono(TextView t) {
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
    }

    private static LinearLayout row(MainActivity act) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    // ------------------------------------------------------------ 1. the comparison

    private CheckBox box(String text, boolean on) {
        CheckBox b = new CheckBox(act);
        b.setText(text);
        b.setTextColor(Ui.FG);
        b.setChecked(on);
        return b;
    }

    private void buildCompare(LinearLayout col) {
        col.addView(Ui.text(act, "1. Compare 4G only and 5G fairly", 16, Ui.FG, true), Ui.lp(act, 18));
        col.addView(Ui.text(act, "Measures the phone's upload on its mobile data with the phone as it is now and with the other 5G "
                + "state (4G only if 5G is on now, 5G allowed if it is off), again and again in rounds: each setting once per round, "
                + "in a random order, so a busy or quiet minute cannot favour one. Then it says whether one is clearly better, or "
                + "whether the difference is just noise. About 25 seconds per measurement (2 settings x 5 rounds is about 4 minutes). "
                + "Run it at the time of day when the upload is bad. At the end the phone is put back as it was.", 12, Ui.DIM, false),
                Ui.lp(act, 4));
        col.addView(Ui.text(act, "Rounds (3 to 9). 5 or more can show a clear difference; 3 or 4 can only suggest one.", 12, Ui.FG, false),
                Ui.lp(act, 8));
        roundsField = new EditText(act);
        roundsField.setSingleLine(true);
        roundsField.setInputType(InputType.TYPE_CLASS_NUMBER);
        roundsField.setTextColor(Ui.FG);
        roundsField.setText("5");
        col.addView(roundsField);
        LinearLayout r = row(act);
        startBtn = Ui.button(act, "Start", v -> startCompare());
        stopBtn = Ui.button(act, "Stop", v -> {
            if (run != null) {
                run.cancel();
            }
        });
        r.addView(startBtn, Ui.weight());
        r.addView(stopBtn, Ui.weight());
        col.addView(r, Ui.lp(act, 4));
        progressView = Ui.text(act, progressMsg, 13, Ui.FG, true);
        col.addView(progressView, Ui.lp(act, 6));
        tableView = Ui.text(act, tableMsg, 12, Ui.FG, false);
        mono(tableView);
        col.addView(tableView, Ui.lp(act, 4));
        useBtn = Ui.button(act, "Use the best setting", v -> useBest());
        col.addView(useBtn, Ui.lp(act, 4));
        paintButtons(run != null && run.running());
    }

    private void paintButtons(boolean running) {
        if (startBtn == null) {
            return;
        }
        startBtn.setEnabled(!running);
        stopBtn.setEnabled(running);
        boolean can = !running && best >= 1 && best < rows.size();
        useBtn.setEnabled(can);
        useBtn.setText(can ? "Use the best: " + rows.get(best).setting.label : "Use the best setting");
    }

    private void startCompare() {
        if (run != null && run.running()) {
            return;
        }
        int rounds = 5;
        try {
            rounds = Integer.parseInt(roundsField.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            // keep 5
        }
        rounds = Math.max(3, Math.min(9, rounds));
        List<LeverRun.Setting> settings = LeverRun.settings(true, true); // the run drops the one that is how the phone is now
        rows = new ArrayList<>();
        best = -1;
        tableMsg = "";
        if (tableView != null) {
            tableView.setText("");
        }
        run = new LeverRun(new LeverLink(act), new LeverRun.Listener() {
            @Override
            public void progress(String text) {
                setProgress(text);
            }

            @Override
            public void rows(List<LeverRun.Row> r, int b, boolean running) {
                renderRows(r, b, running);
            }
        }, settings, rounds, Tab.BANDS);
        setProgress("Starting: " + rounds + " rounds.");
        run.start();
    }

    private void setProgress(String s) {
        progressMsg = s;
        if (progressView != null) {
            progressView.setText(s);
        }
    }

    private void renderRows(List<LeverRun.Row> r, int b, boolean running) {
        rows = new ArrayList<>(r);
        best = b;
        StringBuilder sb = new StringBuilder();
        if (!running && run != null) {
            String s = run.summary();
            if (!s.isEmpty()) {
                sb.append(s).append("\n\n");
            }
        }
        for (int i = 0; i < rows.size(); i++) {
            sb.append(LeverRun.line(rows, i, i == best)).append("\n\n");
        }
        tableMsg = sb.toString().trim();
        if (tableView != null) {
            tableView.setText(tableMsg);
        }
        paintButtons(running);
    }

    /**
     * Applies the winning setting for good: 5G on or off. The phone's safety net also undoes it by itself when mobile data is down
     * for some minutes (it watches whenever 5G was changed), and Restore everything undoes it at any time.
     */
    private void useBest() {
        if (best < 1 || best >= rows.size()) {
            return;
        }
        final LeverRun.Row chosen = rows.get(best);
        setProgress("Applying: " + chosen.setting.label + "…");
        Call.run(act, "POST", "/act?name=" + (chosen.fiveGOff ? "net_lte" : "net_5g"), 60000, (ok, text, json) ->
                setProgress(text + "\nIt stays like this until you switch it back or press Restore everything. If mobile data is "
                        + "down for a few minutes the phone puts 5G back by itself."));
    }

    // ------------------------------------------------------------ 2. the Phone info screen

    private void buildMenus(LinearLayout col) {
        col.addView(Ui.text(act, "2. Phone info screen", 16, Ui.FG, true), Ui.lp(act, 18));
        col.addView(Ui.text(act, "Opens Android's own Phone info screen on the phone. It shows the signal and the network in detail. "
                + "It opens on the phone's own screen: use the Screen tab to see it and tap it (unlock the phone first).", 12, Ui.DIM, false),
                Ui.lp(act, 4));
        col.addView(Ui.button(act, "Phone info screen", v -> confirmMenu("Open the Phone info screen?", () -> phoneInfo(0))),
                Ui.lp(act, 6));
        menuView = Ui.text(act, menuMsg, 12, Ui.FG, false);
        mono(menuView);
        col.addView(menuView, Ui.lp(act, 6));
    }

    /** The screen asks first, in plain words: it is made for factory and repair staff, and it has switches in it. */
    private void confirmMenu(String title, final Runnable go) {
        new AlertDialog.Builder(act)
                .setTitle(title)
                .setMessage("Service screens are made for the factory and for repair shops. Looking is safe. Only change band or network "
                        + "selection, and write down what it was before (usually \"all bands\" or \"auto\") so you can put it back. "
                        + "Never touch anything about IMEI, NVRAM, flashing, firmware, unlock, reset, or backup and restore. If mobile "
                        + "data stops working afterwards: set the band back to all bands or auto, press Restore everything (Phone tab on the tablet "
                        + "or LinkMeter open on the phone itself), or restart the phone.")
                .setPositiveButton("Open it", (d, w) -> go.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setMenu(String s) {
        menuMsg = s;
        if (menuView != null) {
            menuView.setText(s);
        }
    }

    /**
     * Opens Android's own Phone info screen BY NAME (menu_open: every phone app since v0.2.79 knows it, checks the name and wakes
     * the screen), trying the next place when the phone says it could not open this one. The older "usual screens" request would
     * open a technician menu first on a phone app that is not updated yet.
     */
    private void phoneInfo(final int i) {
        setMenu("Asking the phone…");
        final String comp = MenuScan.PHONE_INFO_SCREENS[i];
        Call.run(act, "POST", "/act?name=menu_open&arg=" + Http.enc(comp), 60000, (ok, text, json) -> {
            boolean opened = ok && text.startsWith("Opened");
            if (!opened && i + 1 < MenuScan.PHONE_INFO_SCREENS.length) {
                phoneInfo(i + 1);
                return;
            }
            setMenu(opened || !ok ? text : "This phone has no Phone info screen that LinkMeter could open. The phone said: " + text);
        });
    }

    // ------------------------------------------------------------ 3. the best spot

    private void buildHunt(LinearLayout col) {
        col.addView(Ui.text(act, "3. Find the best spot for the phone", 16, Ui.FG, true), Ui.lp(act, 18));
        col.addView(Ui.text(act, "Often the biggest gain of all: a few dB more signal at the phone is worth more than any setting. "
                + "Press Start, then hold or put the phone where you want to try (a window, higher up, outside on a sill, with its case "
                + "off, its back to the window), wait about 10 seconds for the average to settle, and press \"Mark this spot\". "
                + "The score looks at the 4G cell, because that is what the upload rides on. With Beep on, a higher tone means a "
                + "better spot, so you can move the phone without looking at the tablet. The phone must stay on the hotspot, and the "
                + "tablet within reach of it.", 12, Ui.DIM, false), Ui.lp(act, 4));
        LinearLayout r = row(act);
        huntBtn = Ui.button(act, hunting ? "Stop" : "Start", v -> toggleHunt());
        r.addView(huntBtn, Ui.weight());
        beepBox = box("Beep (higher = better)", true);
        r.addView(beepBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f));
        col.addView(r, Ui.lp(act, 6));
        huntView = Ui.text(act, huntMsg, 14, Ui.FG, false);
        mono(huntView);
        col.addView(huntView, Ui.lp(act, 6));
        LinearLayout r2 = row(act);
        labelField = new EditText(act);
        labelField.setSingleLine(true);
        labelField.setInputType(InputType.TYPE_CLASS_TEXT);
        labelField.setTextColor(Ui.FG);
        labelField.setHint("name of this spot");
        labelField.setHintTextColor(Ui.DIM);
        r2.addView(labelField, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f));
        r2.addView(Ui.button(act, "Mark this spot", v -> markSpot()), Ui.weight());
        col.addView(r2, Ui.lp(act, 4));
        spotsView = Ui.text(act, spotsMsg, 12, Ui.FG, false);
        mono(spotsView);
        col.addView(spotsView, Ui.lp(act, 4));
    }

    private void toggleHunt() {
        if (hunting) {
            stop();
            setHunt(huntMsg + "\n(stopped)");
            return;
        }
        hunting = true;
        final int me = ++huntRun;
        huntBtn.setText("Stop");
        setHunt("Asking the phone for its signal…");
        huntStep(me);
    }

    /** One reading, then the next one a second later; ends when stopped or when a newer start has taken over. */
    private void huntStep(final int me) {
        Call.run(act, "GET", "/sig", 3500, (ok, text, json) -> {
            if (!hunting || me != huntRun) {
                return;
            }
            long delay = 900;
            if (ok && json != null && !json.has("err")) {
                long now = SystemClock.elapsedRealtime();
                hunt.add(new SignalHunt.Sample(now,
                        SignalHunt.rsrp(json.optInt("lteRsrp", Integer.MAX_VALUE)),
                        SignalHunt.rsrq(json.optInt("lteRsrq", Integer.MAX_VALUE)),
                        SignalHunt.sinrRaw(json.optInt("lteSinr", Integer.MAX_VALUE)),
                        SignalHunt.cqi(json.optInt("lteCqi", Integer.MAX_VALUE)),
                        SignalHunt.rsrp(json.optInt("nrRsrp", Integer.MAX_VALUE)),
                        SignalHunt.nrSinr(json.optInt("nrSinr", Integer.MAX_VALUE))));
                paintHunt(now, json.optString("net", ""));
            } else {
                setHunt("The phone did not answer: " + (json != null && json.has("err") ? json.optString("err") : text)
                        + "\nIs the tablet on the phone's hotspot? Is the phone's app up to date?");
                delay = 2500;
            }
            act.ui.postDelayed(() -> huntStep(me), delay);
        });
    }

    private void paintHunt(long now, String net) {
        SignalHunt.Avg a = hunt.average(now, SignalHunt.WINDOW_MS);
        int score = SignalHunt.score(a);
        String trend = hunt.trend(now);
        String arrow = trend.equals("up") ? "↑" : trend.equals("down") ? "↓" : "→";
        StringBuilder sb = new StringBuilder();
        if (score < 0) {
            sb.append("No reading yet");
        } else {
            sb.append(String.format(java.util.Locale.US, "Score %d  %s  %s", score, SignalHunt.rating(score), arrow));
        }
        if (!net.isEmpty()) {
            sb.append("   (").append(net).append(")");
        }
        sb.append("\nLast 10 s: ").append(SignalHunt.describe(a));
        SignalHunt.Sample last = hunt.last();
        if (last != null) {
            sb.append("\nNow:       ").append(SignalHunt.describe(oneReading(last)));
        }
        if (hunt.bestScore() >= 0) {
            sb.append(String.format(java.util.Locale.US, "\nBest so far this session: %d, %d s ago", hunt.bestScore(),
                    Math.max(0, (now - hunt.bestAt()) / 1000)));
        }
        setHunt(sb.toString());
        if (beepBox != null && beepBox.isChecked()) {
            beeper.beep(SignalHunt.toneHz(score));
        }
    }

    private SignalHunt.Avg oneReading(SignalHunt.Sample s) {
        SignalHunt.Avg a = new SignalHunt.Avg();
        a.count = 1;
        a.lteRsrp = s.lteRsrp;
        a.lteRsrq = s.lteRsrq;
        a.lteSinrRaw = s.lteSinr;
        a.lteSinr = hunt.sinrKnown() && s.lteSinr != SignalHunt.NA ? Math.round(s.lteSinr / 10f) : SignalHunt.NA;
        a.lteCqi = s.lteCqi;
        a.nrRsrp = s.nrRsrp;
        a.nrSinr = s.nrSinr;
        return a;
    }

    private void setHunt(String s) {
        huntMsg = s;
        if (huntView != null) {
            huntView.setText(s);
        }
    }

    private void markSpot() {
        String label = labelField == null ? "" : labelField.getText().toString();
        SignalHunt.Spot sp = hunt.mark(label, SystemClock.elapsedRealtime());
        if (sp == null) {
            spotsMsg = "Nothing to mark yet: press Start and wait for a reading.";
        } else {
            StringBuilder sb = new StringBuilder("Marked spots (the average of the last 10 seconds at each):\n");
            List<SignalHunt.Spot> all = hunt.spots();
            int best = hunt.bestSpot();
            for (int i = 0; i < all.size(); i++) {
                sb.append(all.get(i).line(i == best)).append('\n');
            }
            spotsMsg = sb.toString().trim();
            if (labelField != null) {
                labelField.setText("");
            }
        }
        if (spotsView != null) {
            spotsView.setText(spotsMsg);
        }
    }
}
