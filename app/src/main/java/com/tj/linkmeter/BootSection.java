package com.tj.linkmeter;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Tablet only. The card at the top of the Control tab: "Start the phone after a restart" (see docs/BOOT_FLOW.md). One big button runs
 * the whole order (the tablet makes its own hotspot, the phone joins it, wireless debugging goes on, Shizuku is started on the phone,
 * the phone becomes the hotspot and the tablet joins it); the rest are the single steps by hand, the tablet hotspot's name and
 * password, and the one-time setup on the phone. The work is done by {@link BootDriver}; this only shows it.
 */
final class BootSection implements BootDriver.Listener {
    private final MainActivity act;
    private final BootDriver driver;
    private TextView headline;
    private TextView note;
    private TextView steps;
    private TextView phone;
    private TextView hotView;
    private TextView setupView;
    private Button startBtn;
    private Button cancelBtn;
    private boolean shown;
    private long lastPoll;

    BootSection(MainActivity a) {
        act = a;
        driver = BootDriver.get(a);
    }

    private static void mono(TextView t) {
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
    }

    void build(LinearLayout col) {
        col.addView(Ui.text(act, "Start the phone after a restart", 18, Ui.FG, true), Ui.lp(act, 18));
        col.addView(Ui.text(act, "When the phone restarts, Shizuku has to be started again. This does what you did by hand: the tablet makes "
                + "its own hotspot, the phone joins it, wireless debugging goes on, you start Shizuku on the phone, then the phone "
                + "becomes the hotspot and the tablet joins it. What Android does not let an app do stays with you, and the steps "
                + "below say exactly when.", 12, Ui.DIM, false), Ui.lp(act, 4));

        LinearLayout r = row();
        startBtn = Ui.button(act, "Start everything", v -> startPressed());
        cancelBtn = Ui.button(act, "Cancel", v -> driver.cancel());
        r.addView(startBtn, Ui.weight());
        r.addView(cancelBtn, Ui.weight());
        col.addView(r, Ui.lp(act, 8));
        headline = Ui.text(act, "", 14, Ui.FG, true);
        col.addView(headline, Ui.lp(act, 8));
        note = Ui.text(act, "", 12, Ui.AMBER, false);
        col.addView(note, Ui.lp(act, 2));
        steps = Ui.text(act, "", 12, Ui.FG, false);
        mono(steps);
        col.addView(steps, Ui.lp(act, 6));
        phone = Ui.text(act, "", 12, Ui.DIM, false);
        col.addView(phone, Ui.lp(act, 6));

        col.addView(Ui.text(act, "The tablet's hotspot", 15, Ui.FG, true), Ui.lp(act, 16));
        col.addView(Ui.text(act, "The tablet has no hotspot of its own, so LinkMeter makes one the phone can join (Wi-Fi Direct, with the "
                + "same name and password every time; the third try uses a local-only hotspot like NetShare's, with a new random "
                + "password each time). FIRST TIME ONLY: on the phone join this Wi-Fi once (Settings > Connections > Wi-Fi, pick the "
                + "name; if it is not listed use Add network and type the name and the password; stay connected even without internet). "
                + "After that the phone joins it by itself whenever the tablet makes it (the phone's Wi-Fi must be on). Android 10 "
                + "asks for the location permission before an app may make a hotspot; LinkMeter does not read your location.",
                12, Ui.DIM, false), Ui.lp(act, 2));
        LinearLayout h = row();
        h.addView(Ui.button(act, "Start hotspot", v -> act.requestLocation(() -> {
            driver.hotspot().start();
            paint();
        })), Ui.weight());
        h.addView(Ui.button(act, "Stop hotspot", v -> {
            driver.hotspot().stop();
            paint();
        }), Ui.weight());
        h.addView(Ui.button(act, "Wi-Fi switch", v -> openWifi()), Ui.weight());
        col.addView(h, Ui.lp(act, 6));
        hotView = Ui.text(act, "", 13, Ui.FG, false);
        mono(hotView);
        col.addView(hotView, Ui.lp(act, 6));
        LinearLayout h2 = row();
        h2.addView(Ui.button(act, "Copy password", v -> copyPassword()), Ui.weight());
        h2.addView(Ui.button(act, "Open NetShare", v -> openNetShare()), Ui.weight());
        col.addView(h2, Ui.lp(act, 4));

        col.addView(Ui.text(act, "One-time setup on the phone", 15, Ui.FG, true), Ui.lp(act, 16));
        col.addView(Ui.text(act, "Needs Shizuku running on the phone NOW, and the tablet on the phone's hotspot (the usual way). It gives LinkMeter "
                + "the permission to switch wireless debugging on by itself, and switches Boot assist on: after a restart the phone then "
                + "switches wireless debugging on as soon as it is on a Wi-Fi network, and shows a notification that opens Shizuku. "
                + "Undo the setup takes it all back. \"Keep the pairing\" stops Android from forgetting the wireless-debugging pairing "
                + "after 7 days (so you are not asked for the pairing number again).", 12, Ui.DIM, false), Ui.lp(act, 2));
        LinearLayout s1 = row();
        s1.addView(Ui.button(act, "One-time setup", v -> confirmSetup()), Ui.weight());
        s1.addView(Ui.button(act, "Undo the setup", v -> confirmUndo()), Ui.weight());
        col.addView(s1, Ui.lp(act, 6));
        LinearLayout s2 = row();
        s2.addView(Ui.button(act, "Keep the pairing", v -> confirmKeep(true)), Ui.weight());
        s2.addView(Ui.button(act, "Forget after 7 days", v -> setup("boot_keep_off", 30000)), Ui.weight());
        col.addView(s2, Ui.lp(act, 4));
        setupView = Ui.text(act, "", 12, Ui.FG, false);
        mono(setupView);
        col.addView(setupView, Ui.lp(act, 6));
        paint();
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    /** The tab is on screen: follow the driver, and look at the hotspot every second. */
    void onShow() {
        shown = true;
        driver.setListener(this);
        paint();
        act.ui.postDelayed(refresh, 1000);
    }

    void onHide() {
        shown = false;
        driver.setListener(null);
        act.ui.removeCallbacks(refresh);
    }

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (!shown) {
                return;
            }
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastPoll >= 5000) {
                lastPoll = now;
                driver.hotspot().poll();
            }
            paint();
            act.ui.postDelayed(this, 1000);
        }
    };

    @Override
    public void changed() {
        paint();
    }

    private void paint() {
        if (headline == null) {
            return;
        }
        boolean running = driver.running();
        startBtn.setEnabled(!running);
        cancelBtn.setEnabled(running);
        String hl = driver.headline();
        headline.setText(hl);
        note.setText(driver.note().isEmpty() ? driver.lastCommandResult() : driver.note());
        steps.setText(driver.active() ? driver.steps() : "");
        phone.setText(driver.phoneSummary());
        TabletHotspot t = driver.hotspot();
        StringBuilder sb = new StringBuilder();
        switch (t.state()) {
            case ON:
                sb.append("On (").append(t.mode()).append(")\n");
                if (!t.ssid().isEmpty()) {
                    sb.append("Name:     ").append(t.ssid()).append('\n').append("Password: ").append(t.password()).append('\n');
                }
                sb.append("Address:  ").append(t.ip().isEmpty() ? "?" : t.ip());
                if (!t.info().isEmpty() && t.ssid().isEmpty()) {
                    sb.append('\n').append(t.info());
                }
                break;
            case STARTING:
                sb.append("Starting…");
                break;
            case FAILED:
                sb.append("Could not start: ").append(t.info());
                break;
            default:
                sb.append("Off. Name when it is on: ").append(t.fixedName()).append("   Password: ").append(t.fixedPassword());
                break;
        }
        hotView.setText(sb.toString());
    }

    // ------------------------------------------------------------------------------------------- buttons

    private void startPressed() {
        if (driver.running()) {
            return;
        }
        if (!driver.hotspot().wifiOn()) {
            // Android does not let an app switch the Wi-Fi on (since Android 10): this is the one thing it asks you to do first
            Toast.makeText(act, "Switch the tablet's Wi-Fi on first, then press Start everything again.", Toast.LENGTH_LONG).show();
            openWifi();
            return;
        }
        act.requestLocation(driver::start);
    }

    private void openWifi() {
        try {
            act.startActivity(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY));
        } catch (RuntimeException e) {
            try {
                act.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            } catch (RuntimeException e2) {
                Toast.makeText(act, "Open Settings > Wi-Fi and switch it on.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openNetShare() {
        Intent i = TabletHotspot.netShareIntent(act);
        if (i == null) {
            Toast.makeText(act, "NetShare is not installed on this tablet.", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(act, "Start the hotspot in NetShare, then come back: I carry on when the phone shows up on it.", Toast.LENGTH_LONG).show();
        act.startActivity(i);
    }

    private void copyPassword() {
        String p = driver.hotspot().state() == BootFlow.Hotspot.ON && !driver.hotspot().password().isEmpty()
                ? driver.hotspot().password() : driver.hotspot().fixedPassword();
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("LinkMeter hotspot", p));
            Toast.makeText(act, "Password copied.", Toast.LENGTH_SHORT).show();
        }
    }

    private void setup(String action, int readMs) {
        setupView.setText("Asking the phone…");
        driver.normalPost(action, readMs, (ok, text) -> {
            if (setupView != null) {
                setupView.setText(text);
                setupView.setTextColor(ok ? Ui.FG : Ui.AMBER);
            }
        });
    }

    private void confirmSetup() {
        new AlertDialog.Builder(act)
                .setTitle("One-time setup")
                .setMessage("LinkMeter on the phone gets the permission to switch wireless debugging on by itself, and Boot assist is "
                        + "switched on. This changes a security-related permission: you can take it back with Undo the setup. "
                        + "Shizuku must be running on the phone now.")
                .setPositiveButton("Do it", (d, w) -> setup("boot_setup", 40000))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmUndo() {
        new AlertDialog.Builder(act)
                .setTitle("Undo the setup")
                .setMessage("Boot assist goes off, the phone gives the permission back, and the 7-day pairing timeout is put back as it was. "
                        + "The permission can only be taken back while Shizuku runs on the phone.")
                .setPositiveButton("Undo", (d, w) -> setup("boot_undo", 40000))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmKeep(final boolean keep) {
        new AlertDialog.Builder(act)
                .setTitle("Keep the pairing")
                .setMessage("Android forgets a wireless-debugging pairing that has not been used for 7 days. This switches that off, so the "
                        + "pairing stays until you remove it in Developer options. It is the same as the developer option \"Disable adb "
                        + "authorization timeout\". Needs Shizuku running on the phone now; undo it with Forget after 7 days.")
                .setPositiveButton("Do it", (d, w) -> setup(keep ? "boot_keep_on" : "boot_keep_off", 30000))
                .setNegativeButton("Cancel", null)
                .show();
    }
}
