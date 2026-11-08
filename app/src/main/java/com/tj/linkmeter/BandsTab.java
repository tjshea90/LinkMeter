package com.tj.linkmeter;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Which cells the phone can see, and an experimental band lock. A request goes out only when
 * a button is pressed. A lock the phone cannot honour is reported as it is, and a lock that is
 * accepted undoes itself after two minutes unless it is confirmed.
 */
final class BandsTab extends Tab {
    private TextView cellsText;
    private TextView result;
    private LinearLayout quick;
    private EditText manual;

    BandsTab(MainActivity a) {
        super(a);
    }

    @Override
    String title() {
        return "Bands";
    }

    @Override
    View build() {
        LinearLayout col = Ui.column(act);
        col.addView(Ui.text(act, "Cell bands", 22, Ui.FG, true));
        col.addView(Ui.text(act, "Shows the cells the phone can see (★ is the one it is using) "
                + "and lets you ask the modem to stay on one band. This is experimental: the "
                + "modem may refuse, and a lock that works undoes itself after 2 minutes unless "
                + "you keep it.", 12, Ui.DIM, false), Ui.lp(act, 4));

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(Ui.button(act, "Scan cells", v -> scan()), Ui.weight());
        row.addView(Ui.button(act, "Read lock info", v -> info()), Ui.weight());
        col.addView(row, Ui.lp(act, 12));

        cellsText = Ui.text(act, "", 12, Ui.FG, false);
        cellsText.setTypeface(Typeface.MONOSPACE);
        cellsText.setTextIsSelectable(true);
        cellsText.setPadding(Ui.dp(act, 12), Ui.dp(act, 12), Ui.dp(act, 12), Ui.dp(act, 12));
        cellsText.setBackgroundColor(Ui.CARD);
        col.addView(cellsText, Ui.lp(act, 12));

        col.addView(Ui.text(act, "Lock to a band", 16, Ui.FG, true), Ui.lp(act, 18));
        quick = new LinearLayout(act);
        quick.setOrientation(LinearLayout.VERTICAL);
        col.addView(quick, Ui.lp(act, 4));
        quick.addView(Ui.text(act, "Scan first to get one button per LTE band the phone sees.",
                12, Ui.DIM, false));

        col.addView(Ui.text(act, "Or type it (for example lte:5 or lte:5,12 or lte:5;nr:71)",
                12, Ui.DIM, false), Ui.lp(act, 10));
        manual = new EditText(act);
        manual.setSingleLine(true);
        manual.setInputType(InputType.TYPE_CLASS_TEXT);
        manual.setTextColor(Ui.FG);
        manual.setHint("lte:5");
        manual.setHintTextColor(Ui.DIM);
        col.addView(manual);
        LinearLayout row2 = new LinearLayout(act);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(Ui.button(act, "Lock", v -> lock(manual.getText().toString().trim()
                .toLowerCase(Locale.US))), Ui.weight());
        row2.addView(Ui.button(act, "Clear lock", v -> lock("")), Ui.weight());
        col.addView(row2);
        col.addView(Ui.button(act, "Restore everything (fix internet)", v -> ControlTab.restoreDialog(act)),
                Ui.lp(act, 6));

        result = Ui.text(act, "", 12, Ui.FG, false);
        result.setTypeface(Typeface.MONOSPACE);
        result.setTextIsSelectable(true);
        col.addView(result, Ui.lp(act, 14));
        return Ui.scroll(act, col);
    }

    private void say(String s, boolean ok) {
        if (result != null) {
            result.setText(s);
            result.setTextColor(ok ? Ui.FG : Ui.AMBER);
        }
    }

    private void scan() {
        cellsText.setText("Scanning…");
        Call.run(act, "GET", "/cells", 35000, (ok, text, json) -> {
            if (cellsText == null) {
                return;
            }
            if (!ok || json == null) {
                cellsText.setText(text);
                return;
            }
            showCells(json);
        });
    }

    private void showCells(JSONObject json) {
        JSONArray cells = json.optJSONArray("cells");
        StringBuilder sb = new StringBuilder();
        TreeSet<Integer> lteSeen = new TreeSet<>();
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
                for (int k = 0; b != null && k < b.length(); k++) {
                    bs.append(bs.length() > 0 ? "/" : "").append("B").append(b.optInt(k));
                    if (c.optString("t").equals("LTE")) {
                        lteSeen.add(b.optInt(k));
                    }
                }
                sb.append(c.optInt("reg") == 1 ? "★ " : "  ")
                        .append(c.optString("t")).append(' ')
                        .append(bs.length() > 0 ? bs.toString() : "band ?");
                if (c.has("earfcn")) {
                    sb.append("  ch ").append(c.optInt("earfcn"));
                }
                if (c.has("pci")) {
                    sb.append("  pci ").append(c.optInt("pci"));
                }
                if (c.has("rsrp")) {
                    sb.append("  ").append(c.optInt("rsrp")).append(" dBm");
                }
                if (c.has("sinr")) {
                    sb.append("  sinr ").append(c.optInt("sinr"));
                }
                sb.append('\n');
            }
        }
        cellsText.setText(sb.toString().trim());
        quick.removeAllViews();
        if (lteSeen.isEmpty()) {
            quick.addView(Ui.text(act, "No LTE band names were found to lock to.", 12, Ui.DIM, false));
            return;
        }
        List<Integer> bands = new ArrayList<>(lteSeen);
        for (final int band : bands) {
            quick.addView(Ui.button(act, "Lock to LTE band " + band, v -> lock("lte:" + band)),
                    Ui.lp(act, 4));
        }
    }

    private void info() {
        say("Asking the phone…", true);
        Call.run(act, "GET", "/bandinfo", 50000, (ok, text, json) -> say(text, ok));
    }

    private void lock(final String spec) {
        if (!spec.matches("[a-z0-9,;:]*")) {
            say("That doesn't look right. Use something like lte:5 or lte:5,12.", false);
            return;
        }
        say(spec.isEmpty() ? "Clearing the lock…" : "Asking the modem to use " + spec + "…", true);
        Call.run(act, "POST", "/band?set=" + Http.enc(spec), 50000, (ok, text, json) -> {
            say(text, ok);
            if (ok && !spec.isEmpty()) {
                askKeep(spec);
            }
        });
    }

    /** The phone undoes the lock by itself in 2 minutes; this keeps it or undoes it now. */
    private void askKeep(String spec) {
        new AlertDialog.Builder(act)
                .setTitle("Locked to " + spec)
                .setMessage("Check that mobile data still works. If you do nothing, the phone goes "
                        + "back to normal in 2 minutes.")
                .setPositiveButton("Keep the lock", (d, w) ->
                        Call.run(act, "POST", "/band/keep", 5000, (ok, text, json) ->
                                say(ok ? "Lock kept." : text, ok)))
                .setNegativeButton("Undo now", (d, w) -> lock(""))
                .setCancelable(false)
                .show();
    }
}
