package com.tj.linkmeter;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Remote controls for the phone. Nothing here polls: a request is sent only when
 * a button is pressed (plus one status read when the tab opens).
 */
final class ControlTab extends Tab {
    private TextView status;
    private TextView result;

    ControlTab(MainActivity a) {
        super(a);
    }

    @Override
    String title() {
        return "Control";
    }

    @Override
    View build() {
        LinearLayout col = Ui.column(act);
        col.addView(Ui.text(act, "Control the phone", 22, Ui.FG, true));
        status = Ui.text(act, "Checking the phone helper…", 13, Ui.DIM, false);
        col.addView(status, Ui.lp(act, 4));

        col.addView(heading("Mobile network"), Ui.lp(act, 18));
        col.addView(row(
                Ui.button(act, "Force 4G", v -> send("net_lte", "Switching to 4G", 40000)),
                Ui.button(act, "Allow 5G", v -> send("net_5g", "Allowing 5G", 40000)),
                Ui.button(act, "Read mode", v -> send("net_get", "Reading", 25000))));

        col.addView(heading("Battery saver (phone)"), Ui.lp(act, 18));
        col.addView(row(
                Ui.button(act, "Turn on", v -> send("eco_on", "Turning on", 40000)),
                Ui.button(act, "Turn off", v -> send("eco_off", "Turning off", 40000)),
                Ui.button(act, "Screen off", v -> send("screen_off", "Turning the screen off", 15000))));
        col.addView(Ui.text(act, "It switches on by itself when this tablet connects, and off 10 "
                + "minutes after the tablet is gone. It never touches mobile data or hotspot "
                + "speed.", 12, Ui.DIM, false), Ui.lp(act, 4));

        col.addView(heading("End the session"), Ui.lp(act, 18));
        col.addView(row(
                Ui.button(act, "Hotspot off", v -> confirm("Turn the hotspot off?",
                        "The tablet will lose its connection. To turn it back on, use the phone.",
                        "hotspot_off")),
                Ui.button(act, "Airplane on", v -> confirm("Turn airplane mode on?",
                        "The tablet will lose its connection and the phone will have no signal "
                                + "until airplane mode is turned off on the phone.", "airplane_on")),
                Ui.button(act, "Power off", v -> confirm("Power the phone off?",
                        "The phone shuts down. You will need to switch it on by hand.",
                        "poweroff"))));

        col.addView(heading("Setup and diagnostics"), Ui.lp(act, 18));
        col.addView(row(
                Ui.button(act, "Prepare phone", v -> send("setup", "Preparing the phone", 40000)),
                Ui.button(act, "Probe phone", v -> probe())));

        result = Ui.text(act, "", 12, Ui.FG, false);
        result.setTypeface(Typeface.MONOSPACE);
        result.setTextIsSelectable(true);
        result.setPadding(Ui.dp(act, 12), Ui.dp(act, 12), Ui.dp(act, 12), Ui.dp(act, 12));
        result.setBackgroundColor(Ui.CARD);
        col.addView(result, Ui.lp(act, 18));
        col.addView(Ui.button(act, "Copy result", v -> copy()), Ui.lp(act, 6));
        return Ui.scroll(act, col);
    }

    @Override
    void onShow() {
        Call.run(act, "GET", "/s", 4000, (ok, text, json) -> {
            if (status == null) {
                return;
            }
            if (!ok || json == null) {
                status.setText(text);
                status.setTextColor(Ui.AMBER);
                return;
            }
            int priv = json.optInt("priv", 0);
            String eco = json.optBoolean("eco", false) ? " Battery saver is on." : "";
            if (priv == Priv.READY) {
                status.setText("Phone helper is ready." + eco);
                status.setTextColor(Ui.GREEN);
            } else if (priv == Priv.NO_PERM) {
                status.setText("Shizuku runs on the phone but LinkMeter isn't allowed yet. Open "
                        + "LinkMeter on the phone and tap Allow in Shizuku.");
                status.setTextColor(Ui.AMBER);
            } else {
                status.setText("Shizuku isn't running on the phone, so these controls are "
                        + "unavailable. Start Shizuku on the phone first.");
                status.setTextColor(Ui.AMBER);
            }
        });
    }

    private TextView heading(String s) {
        return Ui.text(act, s, 16, Ui.FG, true);
    }

    private LinearLayout row(View... buttons) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View b : buttons) {
            r.addView(b, Ui.weight());
        }
        return r;
    }

    private void show(String s, boolean ok) {
        if (result != null) {
            result.setText(s);
            result.setTextColor(ok ? Ui.FG : Ui.AMBER);
        }
    }

    private void send(String name, String label, int readMs) {
        show(label + "…", true);
        Call.run(act, "POST", "/act?name=" + name, readMs, (ok, text, json) -> show(text, ok));
    }

    private void probe() {
        show("Asking the phone… this takes a few seconds.", true);
        Call.run(act, "GET", "/probe", 60000, (ok, text, json) -> show(text, ok));
    }

    private void confirm(String title, String msg, final String name) {
        new AlertDialog.Builder(act)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("Do it", (d, w) -> send(name, "Sending", 15000))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void copy() {
        if (result == null || result.getText().length() == 0) {
            return;
        }
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("LinkMeter", result.getText()));
            Toast.makeText(act, "Copied.", Toast.LENGTH_SHORT).show();
        }
    }
}
