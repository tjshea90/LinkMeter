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
 * The part of the Bands tab for things this phone DOES obey (band locks it ignores): the 5G leg, a fair comparison of settings
 * on the upload speed, and a finder for the phone's hidden service menus. The views are thin; the logic is in NrLeg, LeverRun
 * and MenuScan (all tested on a computer). Like the band comparison, a run lives on in this object when the tab is left and shown
 * again, so every callback checks that its view still exists.
 */
final class LabSection {
    private final MainActivity act;

    // ---- the safety check
    private TextView safetyView;
    private String safetyMsg = "";

    // ---- the 5G leg
    private TextView legView;
    private String legMsg = "";

    // ---- the comparison
    private CheckBox fourGBox;
    private CheckBox legBox;
    private CheckBox normalBox;
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

    // ---- the hidden menus
    private TextView menuView;
    private LinearLayout screensBox;
    private String menuMsg = "";
    private List<String> screens = new ArrayList<>();

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
        col.addView(Ui.text(act, "What this phone does obey", 18, Ui.FG, true), Ui.lp(act, 16));
        col.addView(Ui.text(act, "This phone ignores band locks (a lock only hints at the modem's search). These are things it does "
                + "obey. None of them changes a band, and Restore everything undoes each of them.", 12, Ui.DIM, false), Ui.lp(act, 4));
        Button safe = Ui.button(act, "Safety check: is anything still changed?", x -> safetyCheck());
        col.addView(safe, Ui.lp(act, 6));
        safetyView = Ui.text(act, safetyMsg, 12, Ui.FG, false);
        mono(safetyView);
        col.addView(safetyView, Ui.lp(act, 4));
        buildLeg(col);
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

    // ------------------------------------------------------------ 1. the 5G leg

    private void buildLeg(LinearLayout col) {
        col.addView(Ui.text(act, "1. The 5G leg", 16, Ui.FG, true), Ui.lp(act, 14));
        col.addView(Ui.text(act, "On 5G UW the phone is on a 4G cell AND a 5G cell at once, and has to share its small upload power "
                + "between them. When the 5G cell is weak that can make the upload worse than on 4G alone. Switching the 5G leg off "
                + "keeps 5G allowed but lets the strong 4G cell carry everything. \"Off\" lets the modem drop the 5G cell when the "
                + "connection ends; \"Off now\" drops it at once (the data may blip for a second).", 12, Ui.DIM, false), Ui.lp(act, 4));
        LinearLayout r = row(act);
        r.addView(Ui.button(act, "Check", v -> leg("nrleg_get", "Asking the phone…", false)), Ui.weight());
        r.addView(Ui.button(act, "Off", v -> leg("nrleg_off", "Asking the modem to keep the 5G cell away…", true)), Ui.weight());
        r.addView(Ui.button(act, "Off now", v -> leg("nrleg_off_now", "Asking the modem to drop the 5G cell now…", true)), Ui.weight());
        r.addView(Ui.button(act, "On", v -> leg("nrleg_on", "Switching the 5G leg back on…", true)), Ui.weight());
        col.addView(r, Ui.lp(act, 6));
        legView = Ui.text(act, legMsg, 13, Ui.FG, false);
        mono(legView);
        col.addView(legView, Ui.lp(act, 6));
    }

    private void setLeg(String s) {
        legMsg = s;
        if (legView != null) {
            legView.setText(s);
        }
    }

    private void leg(final String action, String waiting, final boolean thenLook) {
        setLeg(waiting);
        Call.run(act, "POST", "/act?name=" + action, 40000, (ok, text, json) -> {
            setLeg(text);
            if (ok && thenLook) {
                // what the phone is really using a few seconds later: is the 5G cell gone?
                act.ui.postDelayed(() -> Call.run(act, "GET", "/cells", 35000, (ok2, text2, json2) -> {
                    if (ok2 && json2 != null) {
                        RadioState st = SweepLink.stateOf(json2);
                        boolean nr = false;
                        for (RadioState.Carrier c : st.carriers) {
                            nr |= c.nr();
                        }
                        setLeg(legMsg + "\nEight seconds later the phone is using: " + BandSweep.usingText(st)
                                + (nr ? "" : "  (no 5G cell)"));
                    }
                }), 8000);
            }
        });
    }

    // ------------------------------------------------------------ 2. the comparison

    private CheckBox box(String text, boolean on) {
        CheckBox b = new CheckBox(act);
        b.setText(text);
        b.setTextColor(Ui.FG);
        b.setChecked(on);
        return b;
    }

    private void buildCompare(LinearLayout col) {
        col.addView(Ui.text(act, "2. Compare settings fairly", 16, Ui.FG, true), Ui.lp(act, 18));
        col.addView(Ui.text(act, "Measures the phone's upload on its mobile data under each setting you tick, again and again in rounds: "
                + "every setting once per round, in a random order, so a busy or quiet minute cannot favour one. Then it says whether "
                + "one is clearly better than how the phone is now, or whether the differences are just noise. About 25 seconds per "
                + "measurement (3 settings x 5 rounds is about 7 minutes). Run it at the time of day when the upload is bad. At the end "
                + "the phone is put back as it was.", 12, Ui.DIM, false), Ui.lp(act, 4));
        fourGBox = box("4G only (5G off)", true);
        legBox = box("5G allowed, 5G leg off", true);
        normalBox = box("5G on, normal (tick this if the phone starts with 5G off or the leg off)", false);
        col.addView(fourGBox, Ui.lp(act, 6));
        col.addView(legBox, Ui.lp(act, 0));
        col.addView(normalBox, Ui.lp(act, 0));
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
        List<LeverRun.Setting> settings = LeverRun.settings(fourGBox.isChecked(), legBox.isChecked(), normalBox.isChecked());
        if (settings.size() < 2) {
            setProgress("Tick at least one setting to compare with how the phone is now.");
            return;
        }
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
        }, settings, rounds);
        setProgress("Starting: " + settings.size() + " settings, " + rounds + " rounds.");
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

    /** Applies the winning setting for good: 5G on or off, then the 5G leg. It stays until Restore everything (or the leg is switched on). */
    private void useBest() {
        if (best < 1 || best >= rows.size()) {
            return;
        }
        final LeverRun.Target t = rows.get(best).target;
        setProgress("Applying: " + rows.get(best).setting.label + "…");
        Call.run(act, "POST", "/act?name=" + (t.fiveGOff ? "net_lte" : "net_5g"), 60000, (ok, text, json) -> {
            if (t.legOff == null) {
                setProgress(text + "\nIt stays like this until you press Restore everything.");
                return;
            }
            Call.run(act, "POST", "/act?name=" + (t.legOff ? "nrleg_off_now" : "nrleg_on"), 40000, (ok2, text2, json2) ->
                    setProgress(text + "\n" + text2 + "\nIt stays like this until you press Restore everything."));
        });
    }

    // ------------------------------------------------------------ 3. the hidden menus

    private void buildMenus(LinearLayout col) {
        col.addView(Ui.text(act, "3. Hidden service menus", 16, Ui.FG, true), Ui.lp(act, 18));
        col.addView(Ui.text(act, "Some phones hide a menu that can choose bands (Samsung's *#2263#, MediaTek's engineering menu). "
                + "Verizon usually removes them, but this looks at what THIS phone really has: apps, the dialer codes it knows and "
                + "the screens it can open. The menu opens on the phone's own screen: use the Screen tab to see it and tap it "
                + "(unlock the phone first).", 12, Ui.DIM, false), Ui.lp(act, 4));
        LinearLayout r1 = row(act);
        r1.addView(Ui.button(act, "Look for service menus", v -> findMenus()), Ui.weight());
        r1.addView(Ui.button(act, "Try the usual ones", v -> confirmMenu("Open a service menu?", () -> menuCall("menu_usual", ""))),
                Ui.weight());
        col.addView(r1, Ui.lp(act, 6));
        col.addView(Ui.text(act, "Codes this app will dial (it dials nothing else: some service codes erase the phone). Verizon phones "
                + "usually ignore them: the dialer then answers \"Connection problem or invalid MMI code\", which is harmless "
                + "(these codes only ask the network a question) but opens nothing. The two buttons above open the screens "
                + "directly and work better:", 12, Ui.DIM, false), Ui.lp(act, 8));
        for (String[] c : MenuScan.SAFE_CODES) {
            col.addView(codeButton(c[0], c[1]), Ui.lp(act, 2));
        }
        menuView = Ui.text(act, menuMsg, 12, Ui.FG, false);
        mono(menuView);
        col.addView(menuView, Ui.lp(act, 6));
        screensBox = new LinearLayout(act);
        screensBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(screensBox, Ui.lp(act, 2));
        paintScreens();
    }

    private Button codeButton(final String code, String what) {
        Button b = Ui.button(act, code + "   " + what, v -> confirmMenu("Dial " + code + "?", () -> menuCall("menu_dial", code)));
        b.setTextSize(12);
        return b;
    }

    /** Every menu action asks first, in plain words: the menus are made for factory and repair staff. */
    private void confirmMenu(String title, final Runnable go) {
        new AlertDialog.Builder(act)
                .setTitle(title)
                .setMessage("Service menus are made for the factory and for repair shops. Looking is safe. Only change band or network "
                        + "selection, and write down what it was before (usually \"all bands\" or \"auto\") so you can put it back. "
                        + "Never touch anything about IMEI, NVRAM, flashing, firmware, unlock, reset, or backup and restore. If mobile "
                        + "data stops working afterwards: set the band back to all bands or auto, press Restore everything (Bands tab "
                        + "or LinkMeter > Phone on the phone), or restart the phone.")
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

    private void menuCall(String action, String arg) {
        setMenu("Asking the phone…");
        String target = "/act?name=" + action + (arg.isEmpty() ? "" : "&arg=" + Http.enc(arg));
        Call.run(act, "POST", target, 60000, (ok, text, json) -> setMenu(text));
    }

    private void findMenus() {
        setMenu("Looking through the phone's apps and dialer codes (this can take half a minute)…");
        Call.run(act, "POST", "/act?name=menu_find", 120000, (ok, text, json) -> {
            if (!ok) {
                setMenu(text);
                return;
            }
            MenuScan.Reply reply = MenuScan.parseReply(text);
            setMenu(MenuScan.summary(reply));
            screens = reply.activities;
            paintScreens();
        });
    }

    private void paintScreens() {
        if (screensBox == null) {
            return;
        }
        screensBox.removeAllViews();
        if (screens.isEmpty()) {
            return;
        }
        screensBox.addView(Ui.text(act, "Screens this phone can open (tap to open on the phone):", 12, Ui.DIM, false));
        for (int i = 0; i < screens.size() && i < 12; i++) {
            final String comp = screens.get(i);
            Button b = Ui.button(act, comp.length() > 46 ? comp.substring(0, 20) + "…" + comp.substring(comp.length() - 24) : comp,
                    v -> confirmMenu("Open this screen?", () -> menuCall("menu_open", comp)));
            b.setTextSize(11);
            screensBox.addView(b, Ui.lp(act, 2));
        }
    }

    // ------------------------------------------------------------ 4. the best spot

    private void buildHunt(LinearLayout col) {
        col.addView(Ui.text(act, "4. Find the best spot for the phone", 16, Ui.FG, true), Ui.lp(act, 18));
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
