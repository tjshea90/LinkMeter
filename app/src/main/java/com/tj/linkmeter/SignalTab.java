package com.tj.linkmeter;

import android.app.AlertDialog;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The tablet's "Signal" tab: what the phone is on right now, 4G only or 5G (and a fair comparison of the two on the upload speed),
 * a helper for finding the best spot for the phone, and a few tools. It replaces the old Bands tab; versions up to v0.2.102 also had
 * band locks, a sweep, the 5G leg and dial codes here, which this phone's modem and dialer never obeyed.
 *
 * What it asks, and when: one scan and one question about the mode when the tab is shown (not while a comparison runs, not when the
 * last scan is under a minute old), once more a few seconds after a mode switch or the end of a comparison, and the signal once a
 * second only while "Best spot" is started and the tab is shown. Nothing else runs while the tab is hidden, except a comparison
 * that is already going (it is finite and puts the phone back); its progress is shown again when the tab comes back.
 */
final class SignalTab extends Tab {
    // ---- what the phone last said (kept when the views are rebuilt: a tab is rebuilt each time it is shown)
    private RadioState lastState;
    private int lastRsrp = Integer.MIN_VALUE;
    private String lastCells = "";
    private long lastScanAt = -1;
    private boolean noAnswer;
    private String noAnswerText = "";
    private SignalRules.Mode mode = SignalRules.Mode.UNKNOWN;

    // ---- what the tab is doing
    private boolean shown;
    private boolean scanning;
    /** A request that changes the phone is out: the buttons wait, so nothing is sent twice. */
    private boolean busy;
    private boolean questionOpen;
    /** Counts the times the tab was left, so that a rescan planned before that does not run after the tab came back later. */
    private int scanGen;

    // ---- the comparison (it lives on when the tab is left)
    private LeverRun run;
    private List<LeverRun.Row> rows = new ArrayList<>();
    private int best = -1;
    private String progressMsg = "";
    private String tableMsg = "";
    private int roundsIndex = 1;
    private String keepNote = "";
    /** The last table said the run was still going (so the next one that says it is over is the end of a run). */
    private boolean compareGoing;

    // ---- the best spot
    private final SignalHunt hunt = new SignalHunt();
    private final Beeper beeper = new Beeper();
    private boolean hunting;
    /** Each start gets a new number, so a loop from an earlier start ends when it sees that it is not the newest. */
    private int huntRun;
    private boolean beepOn = true;
    private String huntScore = "";
    private String huntLines = "";
    private String spotsMsg = "";

    // ---- the search for a network
    private SearchFollow follow;
    private int followGen;
    private long followSince;
    private String beforeSearch = "";
    private boolean afterSearchScan;

    // ---- the Phone info screen
    private boolean phoneInfoOpened;

    // ---- the last answer, kept across a rebuild
    private String resultWhat = "";
    private String resultText = "";
    private boolean resultOk = true;
    private boolean resultWorking;

    // ---- views
    private Ui.ResultBar bar;
    private TextView chip;
    private TextView status;
    private Ui.Fact fNetwork;
    private Ui.Fact fSignal;
    private TextView tCarriers;
    private TextView tCells;
    private Button bScan;
    private TextView modeChip;
    private Ui.Choice modeChoice;
    private Ui.Choice roundsChoice;
    private Button bStart;
    private Button bStop;
    private TextView tProgress;
    private TextView tTable;
    private Button bKeep;
    private TextView tKeepNote;
    private TextView tCompareNote;
    private Button bHunt;
    private Ui.Toggle beepToggle;
    private TextView tHuntScore;
    private TextView tHuntLines;
    private EditText labelField;
    private TextView tSpots;
    private Button bSearch;
    private Button bInfo;
    private Button bToScreen;
    private Button bSafety;
    private Button bRestore;

    SignalTab(MainActivity a) {
        super(a);
    }

    @Override
    String title() {
        return SIGNAL;
    }

    @Override
    Icons.Kind icon() {
        return Icons.Kind.BARS;
    }

    // ------------------------------------------------------------------------------------------------ the screen

    @Override
    View build() {
        LinearLayout col = Ui.column(act);
        bar = new Ui.ResultBar(act);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.title(act, SIGNAL), Ui.weight());
        chip = Ui.chip(act, "--", Ui.GREY);
        head.addView(chip);
        col.addView(head);
        status = Ui.text(act, "", 13, Ui.DIM, false);
        col.addView(status, Ui.lp(act, 2));

        // ---- 1. Now using
        LinearLayout now = Ui.card(act, "Now using", "What the phone's radio is on at this moment. The tab looks when you open it.");
        fNetwork = new Ui.Fact(act, "Network");
        now.addView(fNetwork.view, Ui.lp(act, 8));
        fSignal = new Ui.Fact(act, "Signal of the 4G cell");
        now.addView(fSignal.view, Ui.lp(act, 4));
        tCarriers = Ui.body(act, "");
        now.addView(tCarriers, Ui.lp(act, 6));
        tCells = Ui.mono(act, "");
        now.addView(tCells, Ui.lp(act, 4));
        bScan = Ui.primary(act, "Scan", v -> scan(true));
        now.addView(bScan, Ui.lp(act, 10));
        col.addView(now, Ui.lp(act, 12));

        // ---- 2. 4G or 5G, and the comparison
        LinearLayout sw = Ui.card(act);
        LinearLayout swHead = new LinearLayout(act);
        swHead.setOrientation(LinearLayout.HORIZONTAL);
        swHead.setGravity(Gravity.CENTER_VERTICAL);
        swHead.addView(Ui.heading(act, "4G or 5G"), Ui.weight());
        modeChip = Ui.chip(act, "Not known", Ui.GREY);
        swHead.addView(modeChip);
        sw.addView(swHead);
        sw.addView(Ui.hint(act, "5G was faster in the evening measurement (11.0 against 6.5 Mbit/s upload), so 4G only is not "
                + "always better. 4G only also saves the phone's battery. Switch back at any time."), Ui.lp(act, 2));
        modeChoice = new Ui.Choice(act, new String[] {"5G allowed", "4G only"}, 0, i -> switchMode(i == 1));
        sw.addView(modeChoice.view, Ui.lp(act, 8));
        sw.addView(Ui.divider(act));
        sw.addView(Ui.heading(act, "Compare the two"), Ui.lp(act, 6));
        sw.addView(Ui.hint(act, "Measures the upload on mobile data again and again, in rounds and in random order, so a busy minute "
                + "cannot favour one. The phone is put back as it was. About 5 minutes for 5 rounds; uses mobile data."), Ui.lp(act, 2));
        roundsChoice = new Ui.Choice(act, new String[] {"3 rounds", "5 rounds", "7 rounds"}, roundsIndex, i -> roundsIndex = i);
        sw.addView(roundsChoice.view, Ui.lp(act, 8));
        bStart = Ui.primary(act, "Start", v -> startCompare());
        bStop = Ui.button(act, "Stop", v -> {
            if (run != null) {
                run.cancel();
            }
        });
        sw.addView(Ui.row(act, bStart, bStop), Ui.lp(act, 6));
        tCompareNote = Ui.hint(act, "");
        sw.addView(tCompareNote, Ui.lp(act, 4));
        tProgress = Ui.text(act, "", 13, Ui.FG, true);
        sw.addView(tProgress, Ui.lp(act, 6));
        tTable = Ui.mono(act, "");
        sw.addView(tTable, Ui.lp(act, 4));
        bKeep = Ui.button(act, "Keep", v -> keepBest());
        sw.addView(bKeep, Ui.lp(act, 6));
        tKeepNote = Ui.hint(act, "");
        sw.addView(tKeepNote, Ui.lp(act, 2));
        col.addView(sw, Ui.lp(act, 12));

        // ---- 3. Best spot
        LinearLayout spot = Ui.card(act, "Best spot for the phone", "Press Start, then carry the phone around: a window, higher up, case "
                + "off. The tablet beeps higher where the 4G signal is better, and \"Mark\" keeps the spot.");
        bHunt = Ui.primary(act, hunting ? "Stop" : "Start", v -> toggleHunt());
        beepToggle = new Ui.Toggle(act, "Beep", "A higher tone means a better spot.", beepOn, (b, on) -> beepOn = on);
        spot.addView(Ui.row(act, bHunt), Ui.lp(act, 8));
        spot.addView(beepToggle.view, Ui.lp(act, 4));
        tHuntScore = Ui.text(act, huntScore, 22, Ui.FG, true);
        spot.addView(tHuntScore, Ui.lp(act, 8));
        tHuntLines = Ui.mono(act, huntLines);
        spot.addView(tHuntLines, Ui.lp(act, 2));
        labelField = new EditText(act);
        labelField.setSingleLine(true);
        labelField.setInputType(InputType.TYPE_CLASS_TEXT);
        labelField.setTextColor(Ui.FG);
        labelField.setHint("name of this spot");
        labelField.setHintTextColor(Ui.DIM);
        LinearLayout markRow = new LinearLayout(act);
        markRow.setOrientation(LinearLayout.HORIZONTAL);
        markRow.setGravity(Gravity.CENTER_VERTICAL);
        markRow.addView(labelField, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f));
        markRow.addView(Ui.button(act, "Mark", v -> markSpot()), Ui.weight());
        spot.addView(markRow, Ui.lp(act, 6));
        tSpots = Ui.mono(act, spotsMsg);
        spot.addView(tSpots, Ui.lp(act, 4));
        col.addView(spot, Ui.lp(act, 12));

        // ---- 4. Tools
        LinearLayout tools = Ui.card(act, "Tools", null);
        bSearch = Ui.quiet(act, "Search for a network again", v -> askSearch());
        tools.addView(bSearch, Ui.lp(act, 8));
        tools.addView(Ui.hint(act, "Switches the phone's radio off for a few seconds and on again. The hotspot, and this link, drop for "
                + "about a minute."), Ui.lp(act, 2));
        bInfo = Ui.quiet(act, "Phone info screen", v -> askPhoneInfo());
        tools.addView(bInfo, Ui.lp(act, 10));
        tools.addView(Ui.hint(act, "Opens Android's own Phone info screen on the phone. Look at it in the Screen tab."), Ui.lp(act, 2));
        bToScreen = Ui.button(act, "Open the Screen tab", v -> act.openTab(SCREEN));
        bToScreen.setVisibility(phoneInfoOpened ? View.VISIBLE : View.GONE);
        tools.addView(bToScreen, Ui.lp(act, 4));
        bSafety = Ui.quiet(act, "Safety check", v -> send("safety", "Safety check", 40000));
        tools.addView(bSafety, Ui.lp(act, 10));
        tools.addView(Ui.hint(act, "Shows what LinkMeter has changed on the phone and not put back."), Ui.lp(act, 2));
        bRestore = Ui.quiet(act, "Restore everything", v -> ControlTab.restoreDialog(act));
        tools.addView(bRestore, Ui.lp(act, 10));
        tools.addView(Ui.hint(act, "Puts the phone back as it was: network types, hotspot band, battery saver."), Ui.lp(act, 2));
        col.addView(tools, Ui.lp(act, 12));

        View root = Ui.withResultBar(act, col, bar);
        paintAll();
        return root;
    }

    @Override
    void onShow() {
        shown = true;
        paintAll();
        boolean comparing = run != null && run.running();
        if (SignalRules.scanOnShow(SystemClock.elapsedRealtime(), lastScanAt, comparing)) {
            scan(false);
            readMode();
        }
    }

    @Override
    void onHide() {
        shown = false;
        scanGen++;
        followGen++; // a look at the search that is on its way ends here
        stopHunt();
        if (follow != null) {
            follow = null;
            busy = false;
            setResult(resultWhat, "Sent. LinkMeter stopped looking for the end of the restart when this tab was left. Scan to see where "
                    + "the phone is now.", true);
        }
    }

    // ------------------------------------------------------------------------------------------------ painting

    private int chipColor(SignalRules.Chip c) {
        switch (c) {
            case FIVE_G_UW:
            case FIVE_G:
                return Ui.GREEN;
            case FOUR_G:
                return Ui.ACCENT;
            case NO_ANSWER:
                return Ui.AMBER;
            default:
                return Ui.GREY;
        }
    }

    private void paintAll() {
        if (chip == null) {
            return;
        }
        paintNow();
        paintMode();
        paintCompare();
        paintHunt();
        paintButtons();
        if (bar != null) {
            if (resultWorking) {
                bar.working(resultWhat);
            } else if (!resultWhat.isEmpty() || !resultText.isEmpty()) {
                bar.working(resultWhat);
                bar.show(resultText, resultOk);
            }
        }
    }

    private void paintNow() {
        SignalRules.Chip c = noAnswer ? SignalRules.Chip.NO_ANSWER : SignalRules.chipOf(lastState);
        Ui.paintChip(chip, SignalRules.chipText(c), chipColor(c));
        if (noAnswer) {
            status.setText("The phone did not answer: " + noAnswerText + (lastState == null ? "" : "\nThe values below are from the last scan."));
            status.setTextColor(Ui.AMBER);
        } else {
            status.setText(lastState == null ? (scanning ? "Looking at what the phone is on…" : "Not scanned yet.")
                    : "Scanned " + ago() + ".");
            status.setTextColor(Ui.DIM);
        }
        fNetwork.set(SignalRules.networkWords(lastState), lastState == null ? Ui.DIM : Ui.FG);
        fSignal.set(SignalRules.dbm(lastRsrp), lastRsrp == Integer.MIN_VALUE ? Ui.DIM : Ui.FG);
        if (lastState == null) {
            tCarriers.setText("");
            tCells.setText("");
        } else {
            tCarriers.setText("Carriers: " + lastState.usingText());
            String d = lastState.describe();
            if (!lastState.display.isEmpty() && d.indexOf('\n') >= 0) {
                d = d.substring(d.indexOf('\n') + 1); // the first line says what the Network line says
            }
            tCells.setText(d);
        }
        bScan.setText(scanning ? "Scanning…" : "Scan");
    }

    private String ago() {
        long s = Math.max(0, (SystemClock.elapsedRealtime() - lastScanAt) / 1000);
        return s < 5 ? "just now" : s < 90 ? s + " seconds ago" : (s / 60) + " minutes ago";
    }

    private void paintMode() {
        switch (mode) {
            case FIVE_G_ALLOWED:
                Ui.paintChip(modeChip, "5G allowed", Ui.GREEN);
                modeChoice.select(0);
                break;
            case FOUR_G_ONLY:
                Ui.paintChip(modeChip, "4G only", Ui.AMBER);
                modeChoice.select(1);
                break;
            default:
                Ui.paintChip(modeChip, "Not known", Ui.GREY);
                modeChoice.select(-1);
                break;
        }
    }

    private void paintCompare() {
        boolean running = run != null && run.running();
        tProgress.setText(progressMsg);
        tTable.setText(tableMsg);
        tTable.setVisibility(tableMsg.isEmpty() ? View.GONE : View.VISIBLE);
        tProgress.setVisibility(progressMsg.isEmpty() ? View.GONE : View.VISIBLE);
        boolean can = !running && best >= 1 && best < rows.size();
        bKeep.setVisibility(can ? View.VISIBLE : View.GONE);
        if (can) {
            bKeep.setText(SignalRules.keepLabel(rows.get(best).setting.label));
        }
        tKeepNote.setText(keepNote);
        tKeepNote.setVisibility(keepNote.isEmpty() ? View.GONE : View.VISIBLE);
        String blocked = running ? "" : SignalRules.comparisonBlocked(!noAnswer, "bt".equals(Call.lastVia(act)));
        tCompareNote.setText(blocked);
        tCompareNote.setVisibility(blocked.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void paintButtons() {
        boolean running = run != null && run.running();
        boolean can = !busy && !running;
        bStart.setEnabled(can && SignalRules.comparisonBlocked(!noAnswer, "bt".equals(Call.lastVia(act))).isEmpty());
        bStop.setEnabled(running);
        bKeep.setEnabled(can);
        modeChoice.setEnabled(can);
        roundsChoice.setEnabled(!running);
        bScan.setEnabled(!scanning && !running);
        bSearch.setEnabled(can && follow == null);
        bInfo.setEnabled(!busy);
        bSafety.setEnabled(!busy);
        bRestore.setEnabled(!busy);
    }

    // ------------------------------------------------------------------------------------------------ the last answer

    private void working(String what) {
        resultWhat = what;
        resultText = "";
        resultWorking = true;
        if (bar != null) {
            bar.working(what);
        }
    }

    private void setResult(String what, String text, boolean ok) {
        resultWhat = what;
        resultText = text == null ? "" : text;
        resultOk = ok;
        resultWorking = false;
        if (bar != null) {
            bar.working(what);
            bar.show(resultText, ok);
        }
    }

    // ------------------------------------------------------------------------------------------------ scanning and the mode

    /** manual: the user pressed Scan, so the cell list goes into the strip's Details too. */
    private void scan(final boolean manual) {
        if (scanning) {
            return;
        }
        scanning = true;
        if (shown) {
            paintNow();
            paintButtons();
        }
        Call.run(act, "GET", "/cells", 35000, (ok, text, json) -> {
            scanning = false;
            lastScanAt = SystemClock.elapsedRealtime();
            if (!ok || json == null) {
                noAnswer = true;
                noAnswerText = text;
            } else {
                noAnswer = false;
                noAnswerText = "";
                lastState = LeverLink.stateOf(json);
                lastRsrp = LeverLink.rsrpOf(json);
                lastCells = cellList(json);
                if (manual) {
                    setResult("Scan", lastCells, true);
                }
            }
            if (afterSearchScan && ok) {
                afterSearchScan = false;
                setResult("Search for a network again", SignalRules.searchWords(beforeSearch, lastState == null ? "" : lastState.usingText()), true);
            }
            if (shown) {
                paintAll();
            }
        });
    }

    private void readMode() {
        Call.run(act, "POST", "/act?name=net_get", 30000, (ok, text, json) -> {
            if (ok) {
                mode = SignalRules.modeOf(text);
            }
            if (shown) {
                paintMode();
            }
        });
    }

    /** One scan and one question about the mode a few seconds after something changed the phone's radio (the phone needs time to move). */
    private void rescanSoon() {
        final int gen = scanGen;
        act.ui.postDelayed(() -> {
            if (shown && gen == scanGen && !(run != null && run.running())) {
                scan(false);
                readMode();
            }
        }, SignalRules.AFTER_CHANGE_MS);
    }

    /** The phone's cells as lines, the one it is registered on starred. */
    private static String cellList(JSONObject json) {
        JSONArray cells = json.optJSONArray("cells");
        StringBuilder sb = new StringBuilder();
        if (cells == null || cells.length() == 0) {
            sb.append("No cells were listed.\n").append(json.optString("raw", ""));
        } else {
            for (int i = 0; i < cells.length(); i++) {
                JSONObject c = cells.optJSONObject(i);
                if (c == null) {
                    continue;
                }
                JSONArray b = c.optJSONArray("bands");
                StringBuilder bs = new StringBuilder();
                boolean nr = c.optString("t").equals("NR");
                for (int k = 0; b != null && k < b.length(); k++) {
                    bs.append(bs.length() > 0 ? "/" : "").append(nr ? "n" : "B").append(b.optInt(k));
                }
                StringBuilder line = new StringBuilder();
                line.append(c.optInt("reg") == 1 ? "★ " : "  ").append(c.optString("t")).append(' ')
                        .append(bs.length() > 0 ? bs.toString() : "band ?");
                if (c.has("earfcn")) {
                    line.append("  ch ").append(c.optInt("earfcn"));
                }
                if (c.has("rsrp")) {
                    line.append("  ").append(c.optInt("rsrp")).append(" dBm");
                }
                if (c.has("sinr")) {
                    line.append("  sinr ").append(c.optInt("sinr"));
                }
                sb.append(line).append('\n');
            }
        }
        String raw = json.optString("cfgRaw", "");
        if (!raw.isEmpty()) {
            sb.append("\nThe phone's carrier list is there but could not be read. Its text:\n").append(raw);
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------------------------------------ switching 5G

    private void switchMode(final boolean fourGOnly) {
        if (busy || (run != null && run.running())) {
            paintMode();
            return;
        }
        busy = true;
        final String label = fourGOnly ? "4G only" : "5G allowed";
        working("Switching to " + label);
        paintButtons();
        Call.run(act, "POST", "/act?name=" + (fourGOnly ? "net_lte" : "net_5g"), 60000, (ok, text, json) -> {
            busy = false;
            SignalRules.Mode m = SignalRules.modeOf(text);
            if (ok && m != SignalRules.Mode.UNKNOWN) {
                mode = m;
            } else {
                readMode(); // say what the phone is really set to, not what was asked
            }
            setResult(label, text, ok && m != SignalRules.Mode.UNKNOWN);
            if (shown) {
                paintAll();
            }
            rescanSoon();
        });
    }

    // ------------------------------------------------------------------------------------------------ the comparison

    private void startCompare() {
        if (run != null && run.running()) {
            return;
        }
        String blocked = SignalRules.comparisonBlocked(!noAnswer, "bt".equals(Call.lastVia(act)));
        if (!blocked.isEmpty()) {
            setResult("Compare the two", blocked, false);
            return;
        }
        int rounds = roundsIndex == 0 ? 3 : roundsIndex == 2 ? 7 : 5;
        rows = new ArrayList<>();
        best = -1;
        tableMsg = "";
        keepNote = "";
        run = new LeverRun(new LeverLink(act), new LeverRun.Listener() {
            @Override
            public void progress(String text) {
                progressMsg = text;
                if (tProgress != null && shown) {
                    tProgress.setText(text);
                    tProgress.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void rows(List<LeverRun.Row> r, int b, boolean running) {
                renderRows(r, b, running);
            }
        }, LeverRun.settings(true, true), rounds, SIGNAL);
        progressMsg = "Starting: " + rounds + " rounds.";
        if (shown) {
            paintAll();
        }
        run.start();
    }

    private void renderRows(List<LeverRun.Row> r, int b, boolean running) {
        boolean ended = compareGoing && !running;
        compareGoing = running;
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
        if (shown) {
            paintAll();
        }
        if (ended) {
            rescanSoon(); // the run put the phone back: look at what it is on, and which mode it is set to
        }
    }

    /** Applies the winning setting for good: 5G on or off. */
    private void keepBest() {
        if (busy || best < 1 || best >= rows.size()) {
            return;
        }
        final LeverRun.Row chosen = rows.get(best);
        busy = true;
        working("Keeping " + chosen.setting.label);
        paintButtons();
        Call.run(act, "POST", "/act?name=" + (chosen.fiveGOff ? "net_lte" : "net_5g"), 60000, (ok, text, json) -> {
            busy = false;
            SignalRules.Mode m = SignalRules.modeOf(text);
            if (ok && m != SignalRules.Mode.UNKNOWN) {
                mode = m;
            }
            keepNote = ok ? SignalRules.keepNote(chosen.fiveGOff) : "";
            setResult("Keep " + chosen.setting.label, text, ok);
            if (shown) {
                paintAll();
            }
            rescanSoon();
        });
    }

    // ------------------------------------------------------------------------------------------------ the best spot

    private void toggleHunt() {
        if (hunting) {
            stopHunt();
            huntScore = huntScore.isEmpty() ? "" : huntScore + " (stopped)";
            paintHunt();
            return;
        }
        hunting = true;
        final int me = ++huntRun;
        huntScore = "Asking the phone for its signal…";
        huntLines = "";
        paintHunt();
        huntStep(me);
    }

    private void stopHunt() {
        hunting = false;
        huntRun++;
        beeper.release();
    }

    /** One reading, then the next one a second later; ends when stopped, when the tab is left or when a newer start has taken over. */
    private void huntStep(final int me) {
        Call.run(act, "GET", "/sig", 3500, (ok, text, json) -> {
            if (!hunting || me != huntRun || !shown) {
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
                readHunt(now, json.optString("net", ""));
            } else {
                huntScore = "The phone did not answer";
                huntLines = (json != null && json.has("err") ? json.optString("err") : text)
                        + "\nIs the phone's app up to date, and the phone in reach of the tablet?";
                paintHunt();
                delay = 2500;
            }
            act.ui.postDelayed(() -> huntStep(me), delay);
        });
    }

    private void readHunt(long now, String net) {
        SignalHunt.Avg a = hunt.average(now, SignalHunt.WINDOW_MS);
        int score = SignalHunt.score(a);
        String trend = hunt.trend(now);
        String arrow = trend.equals("up") ? "↑" : trend.equals("down") ? "↓" : "→";
        huntScore = score < 0 ? "No reading yet" : String.format(Locale.US, "%d  %s  %s", score, SignalHunt.rating(score), arrow);
        StringBuilder sb = new StringBuilder();
        if (!net.isEmpty()) {
            sb.append("On ").append(net).append('\n');
        }
        sb.append("Last 10 s: ").append(SignalHunt.describe(a));
        SignalHunt.Sample last = hunt.last();
        if (last != null) {
            sb.append("\nNow:       ").append(SignalHunt.describe(oneReading(last)));
        }
        if (hunt.bestScore() >= 0) {
            sb.append(String.format(Locale.US, "\nBest so far: %d, %d s ago", hunt.bestScore(), Math.max(0, (now - hunt.bestAt()) / 1000)));
        }
        huntLines = sb.toString();
        paintHunt();
        if (beepOn) {
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

    private void paintHunt() {
        if (tHuntScore == null) {
            return;
        }
        bHunt.setText(hunting ? "Stop" : "Start");
        tHuntScore.setText(huntScore);
        tHuntScore.setVisibility(huntScore.isEmpty() ? View.GONE : View.VISIBLE);
        tHuntLines.setText(huntLines);
        tHuntLines.setVisibility(huntLines.isEmpty() ? View.GONE : View.VISIBLE);
        tSpots.setText(spotsMsg);
        tSpots.setVisibility(spotsMsg.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void markSpot() {
        String label = labelField == null ? "" : labelField.getText().toString();
        SignalHunt.Spot sp = hunt.mark(label, SystemClock.elapsedRealtime());
        if (sp == null) {
            spotsMsg = "Nothing to mark yet: press Start and wait for a reading.";
        } else {
            StringBuilder sb = new StringBuilder("Marked spots (the average of the last 10 seconds at each):\n");
            List<SignalHunt.Spot> all = hunt.spots();
            int bestSpot = hunt.bestSpot();
            for (int i = 0; i < all.size(); i++) {
                sb.append(all.get(i).line(i == bestSpot)).append('\n');
            }
            spotsMsg = sb.toString().trim();
            if (labelField != null) {
                labelField.setText("");
            }
        }
        paintHunt();
    }

    // ------------------------------------------------------------------------------------------------ the tools

    private void confirm(String title, String message, String go, final Runnable then) {
        if (questionOpen) {
            return;
        }
        questionOpen = true;
        new AlertDialog.Builder(act)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(go, (d, w) -> then.run())
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> questionOpen = false)
                .show();
    }

    private void send(final String action, final String label, int readMs) {
        if (busy) {
            return;
        }
        busy = true;
        working(label);
        paintButtons();
        Call.run(act, "POST", "/act?name=" + action, readMs, (ok, text, json) -> {
            busy = false;
            setResult(label, text, ok);
            if (shown) {
                paintButtons();
            }
        });
    }

    // ---- searching for a network again

    private void askSearch() {
        confirm("Search for a network again?", "The phone switches its radio off for a few seconds and on again. Its hotspot, and the link "
                + "to this tablet, drop for about a minute, and mobile data is gone meanwhile. The tablet looks at the phone until it "
                + "says the restart is over, then scans.", "Search again", this::startSearch);
    }

    private void startSearch() {
        if (busy || follow != null) {
            return;
        }
        busy = true;
        beforeSearch = lastState == null ? "" : lastState.usingText();
        working("Search for a network again");
        paintButtons();
        // the phone answers first and restarts its radio a moment later; over Bluetooth it finishes before it answers, so the wait is long
        Call.run(act, "POST", "/act?name=radio_cycle", 60000, (ok, text, json) -> {
            if (!ok) {
                busy = false;
                setResult("Search for a network again", text, false);
                if (shown) {
                    paintButtons();
                }
                return;
            }
            if (json != null && json.optBoolean("later", false) && shown) {
                follow = new SearchFollow();
                followSince = SystemClock.elapsedRealtime();
                final int gen = ++followGen;
                setResult("Search for a network again", "Sent. The phone is restarting its radio; the link drops for a while. Looking at "
                        + "how it went…", true);
                act.ui.postDelayed(() -> followSearch(gen), SearchFollow.EVERY_MS);
                return;
            }
            busy = false;
            afterSearchScan = true;
            setResult("Search for a network again", "Done. Looking at what the phone is on now…", true);
            scan(false);
            if (shown) {
                paintButtons();
            }
        });
    }

    private void followSearch(final int gen) {
        if (gen != followGen || follow == null || !shown) {
            return;
        }
        Call.run(act, "POST", "/act?name=radio_cycle_state", 6000, (ok, text, json) -> {
            if (gen != followGen || follow == null) {
                return;
            }
            SearchFollow.Outcome o = follow.look(ok, text);
            if (o == null && SystemClock.elapsedRealtime() - followSince >= SearchFollow.GIVE_UP_MS) {
                o = follow.giveUp();
            }
            if (o == null) {
                act.ui.postDelayed(() -> followSearch(gen), SearchFollow.EVERY_MS);
                return;
            }
            follow = null;
            busy = false;
            if (o.ok) {
                afterSearchScan = true;
                setResult("Search for a network again", "Done. Looking at what the phone is on now…", true);
                scan(false);
                readMode();
            } else {
                setResult("Search for a network again", o.text, false);
            }
            if (shown) {
                paintButtons();
            }
        });
    }

    // ---- the Phone info screen

    private void askPhoneInfo() {
        confirm("Open the Phone info screen?", "This opens Android's own Phone info screen on the phone. It has real switches in it: do not "
                + "touch the ones that turn the radio off or change the preferred network type. Looking is safe. Open the Screen tab to see "
                + "it (unlock the phone first).", "Open it", () -> phoneInfo(0));
    }

    /**
     * Opens Android's own Phone info screen BY NAME (menu_open: every phone app since v0.2.79 knows it, checks the name and wakes
     * the screen), trying the next place when the phone says it could not open this one.
     */
    private void phoneInfo(final int i) {
        if (i == 0) {
            if (busy) {
                return;
            }
            busy = true;
            working("Phone info screen");
            paintButtons();
        }
        Call.run(act, "POST", "/act?name=menu_open&arg=" + Http.enc(MenuScan.PHONE_INFO_SCREENS[i]), 60000, (ok, text, json) -> {
            boolean opened = ok && text.startsWith("Opened");
            if (!opened && i + 1 < MenuScan.PHONE_INFO_SCREENS.length) {
                phoneInfo(i + 1);
                return;
            }
            busy = false;
            phoneInfoOpened = opened;
            setResult("Phone info screen", opened || !ok ? text
                    : "This phone has no Phone info screen that LinkMeter could open. The phone said: " + text, opened);
            if (shown) {
                bToScreen.setVisibility(opened ? View.VISIBLE : View.GONE);
                paintButtons();
            }
        });
    }
}
