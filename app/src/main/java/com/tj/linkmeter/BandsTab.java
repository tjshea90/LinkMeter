package com.tj.linkmeter;

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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Cell bands: scan what the phone sees, and try bands one tap at a time. Tapping a band asks
 * the modem to use only that band. The status card shows what is locked and a countdown; the lock
 * undoes itself unless you press Keep. Nothing here polls: requests go out only on a tap, plus a
 * one-second clock on screen while a lock is counting down.
 */
final class BandsTab extends Tab {
    private static final int[] LTE = {1, 2, 3, 4, 5, 7, 8, 12, 13, 14, 17, 18, 19, 20, 25, 26, 28, 29,
        30, 38, 39, 40, 41, 42, 43, 46, 48, 66, 71};
    private static final int[] NR = {1, 2, 3, 5, 7, 8, 12, 14, 20, 25, 26, 28, 30, 38, 40, 41, 48, 66,
        70, 71, 77, 78, 79, 257, 258, 260, 261};
    private static final int PER_ROW = 5;

    private TextView statusText;
    private TextView nowText;
    private TextView cellsText;
    private TextView result;
    private Button keepBtn;
    private EditText manual;
    private final Map<String, Button> buttons = new HashMap<>();
    private final TreeSet<Integer> seenLte = new TreeSet<>();
    private final TreeSet<Integer> seenNr = new TreeSet<>();

    private String lockSpec = "";
    private boolean kept;
    private long revertAtMs;
    /** The next scan should say whether the phone obeyed the lock that was just asked for. */
    private boolean verifyNext;

    // "Find the best upload": the run itself lives on in this object when the tab is left and shown again
    private EditText lteList;
    private EditText nrList;
    private CheckBox fiveGOffBox;
    private CheckBox confirmBox;
    private Button sweepBtn;
    private Button stopBtn;
    private Button useBestBtn;
    private TextView sweepProgress;
    private TextView sweepTable;
    private BandSweep sweep;
    private List<BandSweep.Row> lastRows = new ArrayList<>();
    private int lastBest = -1;
    private String lastProgress = "";

    private final Runnable clock = new Runnable() {
        @Override
        public void run() {
            if (lockSpec.isEmpty() || kept) {
                return;
            }
            if (revertAtMs - SystemClock.elapsedRealtime() <= 0) {
                refreshStatus(); // the phone is undoing it now: ask what is really set
                return;
            }
            paintStatus();
            act.ui.postDelayed(this, 1000);
        }
    };

    BandsTab(MainActivity a) {
        super(a);
    }

    @Override
    String title() {
        return "Bands";
    }

    @Override
    View build() {
        buttons.clear();
        LinearLayout col = Ui.column(act);
        col.addView(Ui.text(act, "Cell bands", 22, Ui.FG, true));
        col.addView(Ui.text(act, "Tap a band to ask the modem to use only that band. \"The modem accepted it\" only "
                + "means the modem acknowledged the request: many phones do that and then ignore it, so this tab "
                + "checks which carriers the phone really uses afterwards. A lock undoes itself after 2 minutes "
                + "unless you press Keep, and Restore everything always puts the phone back.", 12, Ui.DIM, false),
                Ui.lp(act, 4));

        statusText = Ui.text(act, "", 15, Ui.FG, true);
        statusText.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 4));
        statusText.setBackgroundColor(Ui.CARD);
        col.addView(statusText, Ui.lp(act, 10));
        nowText = Ui.text(act, "Now using: scan to see", 13, Ui.DIM, false);
        nowText.setTypeface(Typeface.MONOSPACE);
        nowText.setPadding(Ui.dp(act, 12), 0, Ui.dp(act, 12), Ui.dp(act, 10));
        nowText.setBackgroundColor(Ui.CARD);
        col.addView(nowText);

        LinearLayout keepRow = new LinearLayout(act);
        keepRow.setOrientation(LinearLayout.HORIZONTAL);
        keepBtn = Ui.button(act, "Keep this lock", v -> keepLock());
        keepRow.addView(keepBtn, Ui.weight());
        keepRow.addView(Ui.button(act, "Clear lock", v -> lock("")), Ui.weight());
        col.addView(keepRow, Ui.lp(act, 4));

        result = Ui.text(act, "", 12, Ui.FG, false);
        result.setTypeface(Typeface.MONOSPACE);
        result.setTextIsSelectable(true);
        col.addView(result, Ui.lp(act, 6));

        buildSweepSection(col);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(Ui.button(act, "Scan cells", v -> scan(true)), Ui.weight());
        row.addView(Ui.button(act, "Read lock info", v -> info()), Ui.weight());
        col.addView(row, Ui.lp(act, 8));

        cellsText = Ui.text(act, "", 12, Ui.FG, false);
        cellsText.setTypeface(Typeface.MONOSPACE);
        cellsText.setTextIsSelectable(true);
        cellsText.setPadding(Ui.dp(act, 12), Ui.dp(act, 8), Ui.dp(act, 12), Ui.dp(act, 8));
        cellsText.setBackgroundColor(Ui.CARD);
        col.addView(cellsText, Ui.lp(act, 8));

        col.addView(Ui.text(act, "4G (LTE) bands: tap to try", 16, Ui.FG, true), Ui.lp(act, 16));
        grid(col, "lte", "B", LTE);
        col.addView(Ui.text(act, "5G bands: tap to try", 16, Ui.FG, true), Ui.lp(act, 14));
        grid(col, "nr", "n", NR);
        col.addView(Ui.text(act, "Blue = seen by your phone in the last scan. Green = locked now.",
                12, Ui.DIM, false), Ui.lp(act, 6));

        col.addView(Ui.text(act, "Or type a combination (for example lte:5,12 or lte:5;nr:71)",
                12, Ui.DIM, false), Ui.lp(act, 14));
        manual = new EditText(act);
        manual.setSingleLine(true);
        manual.setInputType(InputType.TYPE_CLASS_TEXT);
        manual.setTextColor(Ui.FG);
        manual.setHint("lte:5,12");
        manual.setHintTextColor(Ui.DIM);
        col.addView(manual);
        col.addView(Ui.button(act, "Lock to this", v -> lock(manual.getText().toString().trim()
                .toLowerCase(Locale.US))), Ui.lp(act, 4));

        col.addView(Ui.button(act, "Restore everything (fix internet)", v -> ControlTab.restoreDialog(act)),
                Ui.lp(act, 14));
        paintStatus();
        return Ui.scroll(act, col);
    }

    @Override
    void onShow() {
        refreshStatus();
    }

    @Override
    void onHide() {
        act.ui.removeCallbacks(clock);
    }

    /** A grid of band buttons, a few per row. Each tries that band alone. */
    private void grid(LinearLayout col, String net, String label, int[] bands) {
        LinearLayout row = null;
        for (int i = 0; i < bands.length; i++) {
            if (i % PER_ROW == 0) {
                row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                col.addView(row, Ui.lp(act, 0));
            }
            final String spec = net + ":" + bands[i];
            Button b = Ui.button(act, label + bands[i], v -> lock(spec));
            b.setTextSize(13);
            b.setPadding(0, b.getPaddingTop(), 0, b.getPaddingBottom());
            buttons.put(spec, b);
            row.addView(b, Ui.weight());
        }
        // pad the last row so its buttons keep the same width
        int rest = bands.length % PER_ROW;
        if (rest != 0 && row != null) {
            for (int i = rest; i < PER_ROW; i++) {
                row.addView(new View(act), Ui.weight());
            }
        }
    }

    // ------------------------------------------------------------ find the best upload

    private EditText bandField(String hint, String text) {
        EditText e = new EditText(act);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setTextColor(Ui.FG);
        e.setHint(hint);
        e.setHintTextColor(Ui.DIM);
        e.setText(text);
        return e;
    }

    private void buildSweepSection(LinearLayout col) {
        col.addView(Ui.text(act, "Find the best upload", 18, Ui.FG, true), Ui.lp(act, 16));
        col.addView(Ui.text(act, "Tries the settings below one after another. For each it asks the modem, waits until the "
                + "phone has really moved (checked against the carriers it is using), then measures the phone's upload on "
                + "its mobile data for about 10 seconds. One test is noisy, so the best three are then measured once more "
                + "and the best of those wins by its average. At the end the phone is put back as it was, and you pick the "
                + "best. About half a minute per setting. Leave the phone where you will use it.", 12, Ui.DIM, false),
                Ui.lp(act, 4));
        col.addView(Ui.text(act, "4G bands to try (numbers, separated by commas)", 12, Ui.FG, false), Ui.lp(act, 10));
        lteList = bandField("13, 5, 66, 2, 4", "13, 5, 66, 2, 4");
        col.addView(lteList);
        col.addView(Ui.text(act, "5G bands to try (leave empty to skip 5G, for example 77 is the C-band)", 12, Ui.FG,
                false), Ui.lp(act, 6));
        nrList = bandField("77", "");
        col.addView(nrList);
        fiveGOffBox = new CheckBox(act);
        fiveGOffBox.setText("Also try 4G with 5G switched off (switched back on at the end)");
        fiveGOffBox.setTextColor(Ui.FG);
        fiveGOffBox.setChecked(true);
        col.addView(fiveGOffBox, Ui.lp(act, 6));
        confirmBox = new CheckBox(act);
        confirmBox.setText("Measure the best three again (more reliable, about 1½ minutes longer)");
        confirmBox.setTextColor(Ui.FG);
        confirmBox.setChecked(true);
        col.addView(confirmBox, Ui.lp(act, 6));
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        sweepBtn = Ui.button(act, "Start", v -> startSweep());
        stopBtn = Ui.button(act, "Stop", v -> stopSweep());
        row.addView(sweepBtn, Ui.weight());
        row.addView(stopBtn, Ui.weight());
        col.addView(row, Ui.lp(act, 4));
        sweepProgress = Ui.text(act, lastProgress, 13, Ui.FG, true);
        col.addView(sweepProgress, Ui.lp(act, 6));
        sweepTable = Ui.text(act, "", 12, Ui.FG, false);
        sweepTable.setTypeface(Typeface.MONOSPACE);
        sweepTable.setTextIsSelectable(true);
        col.addView(sweepTable, Ui.lp(act, 4));
        useBestBtn = Ui.button(act, "Use the best setting", v -> useBest());
        col.addView(useBestBtn, Ui.lp(act, 4));
        renderRows(lastRows, lastBest, sweep != null && sweep.running());
    }

    private void startSweep() {
        if (sweep != null && sweep.running()) {
            return;
        }
        int[] lte = SweepPlan.parseBands(lteList.getText().toString(), 10);
        int[] nr = SweepPlan.parseBands(nrList.getText().toString(), 6);
        List<SweepPlan.Step> plan = SweepPlan.build(lte, nr, fiveGOffBox.isChecked());
        lastRows = new ArrayList<>();
        lastBest = -1;
        sweep = new BandSweep(new SweepLink(act), new BandSweep.Listener() {
            @Override
            public void progress(String text) {
                lastProgress = text;
                if (sweepProgress != null) {
                    sweepProgress.setText(text);
                }
            }

            @Override
            public void rows(List<BandSweep.Row> rows, int best, boolean running) {
                renderRows(rows, best, running);
            }
        }, plan, confirmBox.isChecked());
        say("Starting a comparison of " + plan.size() + " settings. Any band lock is cleared first.", true);
        sweep.start();
    }

    private void stopSweep() {
        if (sweep != null) {
            sweep.cancel();
        }
    }

    private void renderRows(List<BandSweep.Row> rows, int best, boolean running) {
        lastRows = rows == null ? new ArrayList<BandSweep.Row>() : new ArrayList<>(rows);
        lastBest = best;
        if (sweepTable == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        String summary = running ? "" : BandSweep.summary(lastRows);
        if (!summary.isEmpty()) {
            sb.append(summary).append("\n\n");
        }
        for (int i = 0; i < lastRows.size(); i++) {
            sb.append(lastRows.get(i).line(i == best)).append("\n\n");
        }
        sweepTable.setText(sb.toString().trim());
        sweepBtn.setEnabled(!running);
        stopBtn.setEnabled(running);
        boolean can = !running && best >= 0 && best < lastRows.size();
        useBestBtn.setEnabled(can);
        useBestBtn.setText(can ? "Use the best: " + lastRows.get(best).step.label : "Use the best setting");
    }

    /** Applies the winning setting for good: 5G on or off as it needs, the band lock, and Keep. */
    private void useBest() {
        if (lastBest < 0 || lastBest >= lastRows.size()) {
            return;
        }
        final SweepPlan.Step s = lastRows.get(lastBest).step;
        say("Applying: " + s.label + "…", true);
        if (s.fiveG == null) {
            applyStep(s);
            return;
        }
        Call.run(act, "POST", "/act?name=" + (s.fiveG ? "net_5g" : "net_lte"), 60000, (ok, text, json) -> {
            say(text, ok);
            applyStep(s);
        });
    }

    private void applyStep(SweepPlan.Step s) {
        if (s.spec.isEmpty()) {
            lock("");
        } else {
            lock(s.spec, true);
        }
    }

    // ------------------------------------------------------------ what is locked

    /** "lte:5" becomes "LTE band 5"; "lte:5,12;nr:71" becomes "LTE bands 5, 12 + 5G band n71". */
    private static String pretty(String spec) {
        StringBuilder sb = new StringBuilder();
        for (String part : spec.split(";")) {
            String[] kv = part.split(":");
            if (kv.length != 2) {
                continue;
            }
            boolean nr = kv[0].equals("nr");
            String[] bs = kv[1].split(",");
            sb.append(sb.length() > 0 ? " + " : "").append(nr ? "5G band" : "LTE band")
                    .append(bs.length > 1 ? "s " : " ");
            for (int i = 0; i < bs.length; i++) {
                sb.append(i > 0 ? ", " : "").append(nr ? "n" : "").append(bs[i]);
            }
        }
        return sb.toString();
    }

    private void applyStatus(String lock, boolean isKept, int leftSeconds) {
        lockSpec = lock;
        kept = isKept;
        revertAtMs = SystemClock.elapsedRealtime() + leftSeconds * 1000L;
        paintStatus();
        act.ui.removeCallbacks(clock);
        if (!lock.isEmpty() && !isKept) {
            act.ui.postDelayed(clock, 1000);
        }
    }

    private void paintStatus() {
        if (statusText == null) {
            return;
        }
        if (lockSpec.isEmpty()) {
            statusText.setText("No band lock. The phone chooses its own bands.");
            statusText.setTextColor(Ui.FG);
            keepBtn.setEnabled(false);
        } else if (kept) {
            statusText.setText("Locked to " + pretty(lockSpec) + " (kept)");
            statusText.setTextColor(Ui.GREEN);
            keepBtn.setEnabled(false);
        } else {
            long left = Math.max(0, (revertAtMs - SystemClock.elapsedRealtime()) / 1000);
            statusText.setText(String.format(Locale.US, "Locked to %s. Undoes itself in %d:%02d "
                    + "unless you press Keep.", pretty(lockSpec), left / 60, left % 60));
            statusText.setTextColor(Ui.AMBER);
            keepBtn.setEnabled(true);
        }
        for (Map.Entry<String, Button> e : buttons.entrySet()) {
            String spec = e.getKey();
            int band = Integer.parseInt(spec.substring(spec.indexOf(':') + 1));
            boolean seen = spec.startsWith("nr") ? seenNr.contains(band) : seenLte.contains(band);
            e.getValue().setTextColor(spec.equals(lockSpec) ? Ui.GREEN : (seen ? Ui.ACCENT : 0xFFFFFFFF));
        }
    }

    private void refreshStatus() {
        Call.run(act, "GET", "/band/status", 8000, (ok, text, json) -> {
            if (ok && json != null) {
                applyStatus(json.optString("lock", ""), json.optBoolean("kept", false),
                        json.optInt("left", 0));
            }
        });
    }

    private void say(String s, boolean ok) {
        if (result != null) {
            result.setText(s);
            result.setTextColor(ok ? Ui.FG : Ui.AMBER);
        }
    }

    // ------------------------------------------------------------ actions

    private void lock(final String spec) {
        lock(spec, false);
    }

    private void lock(final String spec, final boolean keepAfter) {
        if (!spec.matches("[a-z0-9,;:]*")) {
            say("That doesn't look right. Use something like lte:5 or lte:5,12.", false);
            return;
        }
        say(spec.isEmpty() ? "Clearing the lock…" : "Asking the modem for " + pretty(spec) + "…", true);
        Call.run(act, "POST", "/band?set=" + Http.enc(spec), 50000, (ok, text, json) -> {
            say(ok ? (spec.isEmpty() ? "Lock cleared." : "The modem acknowledged the request for " + pretty(spec)
                    + ". That does not prove the phone moved: checking which carriers it is really on…") : text, ok);
            if (ok && json != null) {
                applyStatus(json.optString("lock", ""), json.optBoolean("kept", false),
                        json.optInt("left", 0));
                if (!spec.isEmpty()) {
                    // the modem needs a few seconds to move; then show what it is really on, and whether it obeyed
                    verifyNext = true;
                    act.ui.postDelayed(() -> scan(false), 6000);
                    if (keepAfter) {
                        keepLock();
                    }
                }
            } else {
                refreshStatus();
            }
        });
    }

    private void keepLock() {
        Call.run(act, "POST", "/band/keep", 8000, (ok, text, json) -> {
            say(ok ? "Lock kept." : text, ok);
            refreshStatus();
        });
    }

    private void info() {
        say("Asking the phone…", true);
        Call.run(act, "GET", "/bandinfo", 50000, (ok, text, json) -> say(text, ok));
    }

    private void scan(boolean visible) {
        if (cellsText == null) {
            return;
        }
        if (visible) {
            cellsText.setText("Scanning…");
        }
        Call.run(act, "GET", "/cells", 35000, (ok, text, json) -> {
            if (cellsText == null) {
                return;
            }
            if (!ok || json == null) {
                if (visible) {
                    cellsText.setText(text);
                }
                return;
            }
            showCells(json);
        });
    }

    private void showCells(JSONObject json) {
        JSONArray cells = json.optJSONArray("cells");
        StringBuilder sb = new StringBuilder();
        seenLte.clear();
        seenNr.clear();
        String now = "";
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
                    if (nr) {
                        seenNr.add(b.optInt(k));
                    } else if (c.optString("t").equals("LTE")) {
                        seenLte.add(b.optInt(k));
                    }
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
                if (c.optInt("reg") == 1 && now.isEmpty()) {
                    now = c.optString("t") + " " + (bs.length() > 0 ? bs.toString() : "band ?")
                            + (c.has("rsrp") ? " · " + c.optInt("rsrp") + " dBm" : "")
                            + (c.has("sinr") ? " · sinr " + c.optInt("sinr") : "");
                }
            }
        }
        // The list above is the cell the phone is registered on. What the radio is really USING is the list of carriers:
        // with carrier aggregation or 5G there are several at once.
        RadioState rs = SweepLink.stateOf(json);
        for (RadioState.Carrier c : rs.carriers) {
            if (c.band > 0) {
                (c.nr() ? seenNr : seenLte).add(c.band);
            }
        }
        String carriersText = BandCheck.describe(rs);
        if (!rs.rawConfigs.isEmpty()) {
            sb.append("\n\nThe phone's carrier list is there but could not be read. Its text:\n").append(rs.rawConfigs);
        }
        cellsText.setText(sb.toString().trim());
        if (nowText != null) {
            if (!rs.carriers.isEmpty()) {
                nowText.setText("Now using:\n" + carriersText);
            } else if (!now.isEmpty()) {
                nowText.setText("Now using: " + now + (rs.display.isEmpty() && !rs.ca ? "" : "\n" + carriersText));
            } else {
                nowText.setText("Now using: unknown" + (rs.display.isEmpty() && !rs.ca ? "" : "\n" + carriersText));
            }
        }
        if (verifyNext || !lockSpec.isEmpty()) {
            boolean after = verifyNext;
            verifyNext = false;
            if (!lockSpec.isEmpty() && (after || (result != null && result.getText().length() == 0))) {
                BandCheck.Result v = BandCheck.verify(lockSpec, rs.carriers);
                say((v.verdict == BandCheck.Verdict.OBEYED ? "✓ " : v.verdict == BandCheck.Verdict.NOT_OBEYED ? "✗ " : "? ")
                        + v.text, v.verdict != BandCheck.Verdict.NOT_OBEYED);
            }
        }
        paintStatus();
    }
}
