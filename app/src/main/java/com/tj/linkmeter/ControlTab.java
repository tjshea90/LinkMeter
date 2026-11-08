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
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Remote controls for the phone. Nothing here polls: a request is sent only when
 * a button is pressed (plus one status read when the tab opens).
 */
final class ControlTab extends Tab {
    private TextView status;
    private TextView result;
    private TextView version;
    private long phoneCode = -1;
    private String phoneName = "";
    private String phoneUpd = "";

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

        col.addView(Ui.button(act, "Restore everything (fix internet)", v -> restoreDialog(act)),
                Ui.lp(act, 10));
        col.addView(Ui.button(act, "Restart modem", v -> send("restart_modem", "Restarting the modem", 40000)),
                Ui.lp(act, 4));
        col.addView(Ui.text(act, "Use these if a band lock or network change leaves the phone without "
                + "internet. They work over the hotspot even when the phone has no mobile data.",
                12, Ui.DIM, false), Ui.lp(act, 4));

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

        col.addView(heading("Hotspot Wi-Fi band"), Ui.lp(act, 18));
        col.addView(row(
                Ui.button(act, "2.4 GHz", v -> send("hotspot_band_2", "Setting 2.4 GHz", 40000)),
                Ui.button(act, "5 GHz", v -> send("hotspot_band_5", "Setting 5 GHz", 40000)),
                Ui.button(act, "Auto", v -> send("hotspot_band_auto", "Setting auto", 40000))));
        col.addView(row(
                Ui.button(act, "Show current", v -> send("hotspot_info", "Reading", 40000)),
                Ui.button(act, "Restart hotspot", v -> confirm("Restart the hotspot?",
                        "The tablet loses its connection for about 10 seconds and reconnects when "
                                + "the hotspot is back. If it doesn't come back, turn the hotspot on "
                                + "at the phone.", "hotspot_restart"))));
        col.addView(Ui.text(act, "Pick a band, then restart the hotspot to use it. 2.4 GHz reaches "
                + "further and may cause less interference with the phone's mobile signal; 5 GHz is "
                + "faster. This is experimental on Samsung phones.", 12, Ui.DIM, false), Ui.lp(act, 4));

        col.addView(heading("Setup and diagnostics"), Ui.lp(act, 18));
        col.addView(row(
                Ui.button(act, "Prepare phone", v -> send("setup", "Preparing the phone", 40000)),
                Ui.button(act, "Probe phone", v -> probe())));

        col.addView(heading("Start Shizuku from the tablet (experimental)"), Ui.lp(act, 18));
        col.addView(row(
                Ui.button(act, "Set up network adb", v -> send("adb_tcp_setup", "Setting up", 40000)),
                Ui.button(act, "Start Shizuku", v -> startShizuku())));
        col.addView(Ui.text(act, "For after a phone restart, with no Wi-Fi network needed. Set up works "
                + "while Shizuku is running. Then Start Shizuku connects to the phone's network adb "
                + "and runs Shizuku's start script. The first time, the phone asks Allow USB debugging: "
                + "tap Always allow.", 12, Ui.DIM, false), Ui.lp(act, 4));

        col.addView(heading("App update"), Ui.lp(act, 18));
        version = Ui.text(act, "", 12, Ui.DIM, false);
        col.addView(version, Ui.lp(act, 4));
        col.addView(Ui.button(act, "Update phone from this tablet", v -> updatePhone()), Ui.lp(act, 6));
        col.addView(Ui.text(act, "Install the new version on this tablet first (open the downloaded "
                + "file). This sends the tablet's copy to the phone, which installs it and restarts "
                + "the app by itself. Needs Shizuku on the phone.", 12, Ui.DIM, false), Ui.lp(act, 4));
        paintVersion();

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
            phoneCode = json.optLong("vc", -1);
            phoneName = json.optString("vn", "");
            phoneUpd = json.optString("upd", "");
            paintVersion();
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

    private String ownVersion() {
        try {
            android.content.pm.PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            return pi.versionName + " (code " + pi.getLongVersionCode() + ")";
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private void paintVersion() {
        if (version == null) {
            return;
        }
        version.setText("Tablet app " + ownVersion() + "   ·   Phone app "
                + (phoneCode >= 0 ? phoneName + " (code " + phoneCode + ")"
                : "not read yet, or too old to update this way")
                + (phoneUpd.isEmpty() ? "" : "\nLast update on the phone: " + phoneUpd));
    }

    /** Sends this tablet's installed copy of the app to the phone, which installs it over itself. */
    private void updatePhone() {
        show("Sending the app to the phone…", true);
        new Thread(() -> {
            boolean ok = false;
            String text;
            try {
                String host = Net.phoneHost(act);
                if (host.isEmpty()) {
                    throw new IOException("The tablet is not on the phone's hotspot.");
                }
                File apk = new File(act.getApplicationInfo().sourceDir);
                try (FileInputStream in = new FileInputStream(apk)) {
                    Client.Resp r = Client.put(host, "/apk", Cfg.token(act), in, apk.length(), null);
                    text = r.body;
                    try {
                        JSONObject j = new JSONObject(r.body);
                        text = j.has("err") ? j.optString("err") : j.optString("out", r.body);
                    } catch (JSONException plain) {
                        // not JSON: show the text as it came
                    }
                    ok = r.code == 200;
                }
            } catch (IOException e) {
                text = "Couldn't update the phone: " + e.getMessage();
            }
            final boolean fOk = ok;
            final String fText = text;
            act.ui.post(() -> {
                show(fText, fOk);
                if (fOk && fText.contains("installing")) {
                    // The phone app restarts; look again once it is back.
                    act.ui.postDelayed(this::onShow, 15000);
                    act.ui.postDelayed(this::onShow, 30000);
                }
            });
        }, "linkmeter-update").start();
    }

    /** Connects to the phone's network adb and runs the Shizuku start script. */
    private void startShizuku() {
        show("Connecting to the phone's adb. If the phone asks Allow USB debugging, tap Always allow…", true);
        final android.content.Context app = act.getApplicationContext();
        new Thread(() -> {
            String msg;
            boolean ok = false;
            try {
                String host = Net.phoneHost(app);
                if (host.isEmpty()) {
                    throw new IOException("The tablet is not on the phone's hotspot.");
                }
                String out = AdbClient.shell(app, host, 5555,
                        "sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh", 90);
                msg = "Shizuku's start script said:\n" + out;
                ok = true;
            } catch (java.net.ConnectException e) {
                msg = "The phone is not listening for network adb on port 5555. Run Set up network adb "
                        + "(needs Shizuku running), restart the phone, or run adb tcpip 5555 once from a "
                        + "computer.";
            } catch (IOException e) {
                msg = "Couldn't start Shizuku: " + e.getMessage();
            }
            final boolean fOk = ok;
            final String fMsg = msg;
            act.ui.post(() -> {
                show(fMsg, fOk);
                act.ui.postDelayed(this::onShow, 6000); // read the phone's Shizuku state again
            });
        }, "linkmeter-adb").start();
    }

    /** Asks the phone to put everything back and shows its report. Also used by the Bands tab. */
    static void restoreDialog(final MainActivity act) {
        Toast.makeText(act, "Asking the phone to restore everything…", Toast.LENGTH_SHORT).show();
        Call.run(act, "POST", "/act?name=restore", 90000, (ok, text, json) ->
                new AlertDialog.Builder(act)
                        .setTitle(ok ? "Restore everything" : "Couldn't restore")
                        .setMessage(text)
                        .setPositiveButton("OK", null)
                        .show());
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
