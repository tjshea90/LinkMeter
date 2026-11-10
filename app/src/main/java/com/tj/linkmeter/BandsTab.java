package com.tj.linkmeter;

import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Cell bands: scan what the phone is using, make it search again, and the things this phone DOES obey (see LabSection). Versions up
 * to v0.2.102 also had band locks and a "find the best upload" sweep here; this phone's modem never obeyed a lock, so they are gone
 * (track T5 replaces this tab by the Signal tab). Nothing here polls: requests go out only on a tap.
 */
final class BandsTab extends Tab {
    private TextView nowText;
    private TextView cellsText;
    private TextView result;

    /** The part for things the phone obeys; it keeps its runs when this tab is left and shown again. */
    private final LabSection lab;

    BandsTab(MainActivity a) {
        super(a);
        lab = new LabSection(a);
    }

    @Override
    String title() {
        return BANDS;
    }

    @Override
    Icons.Kind icon() {
        return Icons.Kind.BARS;
    }

    @Override
    View build() {
        LinearLayout col = Ui.column(act);
        col.addView(Ui.text(act, "Cell bands", 22, Ui.FG, true));
        col.addView(Ui.text(act, "What the phone is really using right now, a fair comparison of 4G only and 5G on the upload speed, "
                + "and a helper for finding the best spot for the phone. (This phone ignores band locks, so there are none here.)",
                12, Ui.DIM, false), Ui.lp(act, 4));

        nowText = Ui.text(act, "Now using: not scanned yet. Press \"Scan now\" (just below).", 13, Ui.DIM, false);
        nowText.setTypeface(Typeface.MONOSPACE);
        nowText.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10));
        nowText.setBackgroundColor(Ui.CARD);
        col.addView(nowText, Ui.lp(act, 10));
        col.addView(Ui.button(act, "Scan now", v -> {
            nowText.setText("Now using: scanning…");
            scan(true);
        }), Ui.lp(act, 4));

        result = Ui.text(act, "", 12, Ui.FG, false);
        result.setTypeface(Typeface.MONOSPACE);
        result.setTextIsSelectable(true);
        col.addView(result, Ui.lp(act, 6));

        lab.build(col); // what the phone does obey: a fair comparison, the Phone info screen, the best spot

        col.addView(Ui.button(act, "Make the phone search again", v -> searchAgain()), Ui.lp(act, 16));
        col.addView(Ui.text(act, "Switches the phone's radio off for a few seconds and on again, so it searches for a network from "
                + "scratch. Wait about 30 seconds, then press Scan now.", 12, Ui.DIM, false), Ui.lp(act, 2));

        cellsText = Ui.text(act, "", 12, Ui.FG, false);
        cellsText.setTypeface(Typeface.MONOSPACE);
        cellsText.setTextIsSelectable(true);
        cellsText.setPadding(Ui.dp(act, 12), Ui.dp(act, 8), Ui.dp(act, 12), Ui.dp(act, 8));
        cellsText.setBackgroundColor(Ui.CARD);
        cellsText.setVisibility(View.GONE); // nothing to show before the first scan
        col.addView(cellsText, Ui.lp(act, 8));

        col.addView(Ui.button(act, "Restore everything (fix internet)", v -> ControlTab.restoreDialog(act)),
                Ui.lp(act, 14));
        return Ui.scroll(act, col);
    }

    @Override
    void onHide() {
        lab.stop(); // the placement helper asks the phone once a second: not while this tab is out of sight
    }

    private void say(String s, boolean ok) {
        if (result != null) {
            result.setText(s);
            result.setTextColor(ok ? Ui.FG : Ui.AMBER);
        }
    }

    // ------------------------------------------------------------ actions

    /** Switches the phone's radio off for a few seconds and on again, so it searches for a network from scratch. */
    private void searchAgain() {
        say("Asking the phone to search again…", true);
        Call.run(act, "POST", "/act?name=radio_cycle", 10000, (ok, text, json) -> say(ok
                ? "The phone is restarting its radio. The link to it drops for about 30 seconds (the hotspot goes with the radio), "
                + "then press Scan now." : text, ok));
    }

    private void scan(boolean visible) {
        if (cellsText == null) {
            return;
        }
        if (visible) {
            showCellList("Scanning…");
        }
        Call.run(act, "GET", "/cells", 35000, (ok, text, json) -> {
            if (cellsText == null) {
                return;
            }
            if (!ok || json == null) {
                if (visible) {
                    showCellList(text);
                    if (nowText != null) {
                        nowText.setText("Now using: could not ask the phone. " + text);
                    }
                }
                return;
            }
            showCells(json);
        });
    }

    /** The list of cells under the buttons: hidden until there is something to say. */
    private void showCellList(String text) {
        cellsText.setText(text);
        cellsText.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showCells(JSONObject json) {
        JSONArray cells = json.optJSONArray("cells");
        StringBuilder sb = new StringBuilder();
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
        RadioState rs = LeverLink.stateOf(json);
        String carriersText = rs.describe();
        if (!rs.rawConfigs.isEmpty()) {
            sb.append("\n\nThe phone's carrier list is there but could not be read. Its text:\n").append(rs.rawConfigs);
        }
        showCellList(sb.toString().trim());
        if (nowText != null) {
            if (!rs.carriers.isEmpty()) {
                nowText.setText("Now using:\n" + carriersText);
            } else if (!now.isEmpty()) {
                nowText.setText("Now using: " + now + (rs.display.isEmpty() && !rs.ca ? "" : "\n" + carriersText));
            } else {
                nowText.setText("Now using: unknown" + (rs.display.isEmpty() && !rs.ca ? "" : "\n" + carriersText));
            }
        }
    }
}
