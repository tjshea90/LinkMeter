package com.tj.linkmeter;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Tablet only. The card "After a phone restart" in the Phone tab (see docs/BOOT_FLOW.md and docs/BOOT_GUIDE.md). One big button runs
 * the whole order (the tablet makes its own hotspot, the phone joins it, wireless debugging goes on, Shizuku is started on the phone,
 * the phone becomes the hotspot and the tablet joins it). Behind a quiet button are the single steps by hand, the tablet hotspot's
 * name and password, and the one-time setup on the phone. The work is done by {@link BootDriver}; this only shows it.
 *
 * What is shown is what is true: while nothing runs, the line at the top says what the phone itself last said about Shizuku (read
 * once with GET /boot, when the card is shown and when its details are opened); the steps are shown only while a run is on or has
 * just ended. Nothing here asks the phone on a timer.
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
    private Button detailsBtn;
    private Button netShareBtn;
    private LinearLayout details;
    private Ui.Toggle keepToggle;
    private CompoundButton.OnCheckedChangeListener keepListener;
    private boolean detailsOpen;
    private boolean shown;
    private long lastPoll;
    /** The phone's Shizuku state from its last /boot answer (Priv.OFF, NO_PERM, READY), or -1 while it has not answered. */
    private int priv = -1;
    /** The phone's own word on whether the 7-day pairing timeout is off; null while not known. */
    private Boolean keepOn;

    BootSection(MainActivity a) {
        act = a;
        driver = BootDriver.get(a);
    }

    private static void mono(TextView t) {
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
    }

    void build(LinearLayout card) {
        card.addView(Ui.heading(act, "After a phone restart"));
        card.addView(Ui.hint(act, "When the phone restarts, its helper Shizuku stops and the switches do not work. Unlock the phone "
                + "once, then press Start everything."), Ui.lp(act, 2));
        // the steps come first and the line that says what is going on or what to do now after them: the flow's own sentences
        // refer to "the steps above"
        steps = Ui.text(act, "", 12, Ui.FG, false);
        mono(steps);
        card.addView(steps, Ui.lp(act, 8));
        headline = Ui.text(act, "", 14, Ui.FG, true);
        card.addView(headline, Ui.lp(act, 8));
        note = Ui.hint(act, "");
        note.setTextColor(Ui.AMBER);
        card.addView(note, Ui.lp(act, 2));
        startBtn = Ui.primary(act, "Start everything", v -> startPressed());
        cancelBtn = Ui.button(act, "Cancel", v -> driver.cancel());
        card.addView(Ui.row(act, startBtn, cancelBtn), Ui.lp(act, 8));
        phone = Ui.hint(act, "");
        card.addView(phone, Ui.lp(act, 4));
        detailsBtn = Ui.quiet(act, "Tablet hotspot and one-time setup", v -> toggleDetails());
        card.addView(detailsBtn, Ui.lp(act, 10));

        details = new LinearLayout(act);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setVisibility(View.GONE);

        details.addView(Ui.heading(act, "The tablet's hotspot"), Ui.lp(act, 12));
        details.addView(Ui.hint(act, "The tablet makes a hotspot the phone can join, with the same name and password every time. "
                + "The first time, join it once on the phone and stay connected even without internet."), Ui.lp(act, 2));
        hotView = Ui.text(act, "", 13, Ui.FG, false);
        mono(hotView);
        details.addView(hotView, Ui.lp(act, 6));
        details.addView(Ui.row(act,
                Ui.button(act, "Start hotspot", v -> act.requestLocation(() -> {
                    driver.hotspot().start();
                    paint();
                })),
                Ui.button(act, "Stop hotspot", v -> {
                    driver.hotspot().stop();
                    paint();
                })), Ui.lp(act, 6));
        details.addView(Ui.row(act,
                Ui.button(act, "Tablet Wi-Fi", v -> openWifi()),
                Ui.button(act, "Copy password", v -> copyPassword())), Ui.lp(act, 2));
        netShareBtn = Ui.button(act, "Open NetShare", v -> openNetShare());
        netShareBtn.setVisibility(TabletHotspot.netShareIntent(act) == null ? View.GONE : View.VISIBLE); // only when it is installed
        details.addView(netShareBtn, Ui.lp(act, 2));
        details.addView(Ui.divider(act));

        details.addView(Ui.heading(act, "One-time setup on the phone"), Ui.lp(act, 6));
        details.addView(Ui.hint(act, "Lets LinkMeter switch wireless debugging on by itself after a restart. Needs Shizuku running on "
                + "the phone now, and the tablet on the phone's hotspot."), Ui.lp(act, 2));
        details.addView(Ui.row(act,
                Ui.button(act, "One-time setup", v -> confirmSetup()),
                Ui.button(act, "Undo the setup", v -> confirmUndo())), Ui.lp(act, 6));
        keepListener = (b, on) -> {
            if (on) {
                confirmKeep();
            } else {
                setup("boot_keep_off", 30000); // taking the pairing timeout back needs no question
            }
        };
        keepToggle = new Ui.Toggle(act, "Keep the pairing longer than 7 days",
                "Android forgets a wireless-debugging pairing after 7 days. This keeps it.", false, keepListener);
        details.addView(keepToggle.view, Ui.lp(act, 6));
        setupView = Ui.text(act, "", 12, Ui.FG, false);
        mono(setupView);
        details.addView(setupView, Ui.lp(act, 6));
        card.addView(details);
        paint();
    }

    private void toggleDetails() {
        if (detailsOpen) {
            detailsOpen = false;
            details.setVisibility(View.GONE);
            detailsBtn.setText("Tablet hotspot and one-time setup");
        } else {
            openDetails();
        }
    }

    private void openDetails() {
        detailsOpen = true;
        details.setVisibility(View.VISIBLE);
        detailsBtn.setText("Hide the details");
        readPhone();
    }

    /** One status read of the phone's /boot: its Shizuku state and its 7-day pairing setting. Never while the tab is hidden. */
    private void readPhone() {
        if (!shown) {
            return;
        }
        Call.run(act, "GET", "/boot", 4000, (ok, text, json) -> {
            if (!shown || headline == null || !ok || json == null) {
                return;
            }
            priv = json.has("priv") ? json.optInt("priv", -1) : -1;
            keepOn = json.has("keep") ? Boolean.valueOf(json.optBoolean("keep")) : null;
            paint();
        });
    }

    /** The tab is on screen: follow the driver, and look at the hotspot (every second while something is going on, else every five). */
    void onShow() {
        shown = true;
        driver.setListener(this);
        paint();
        readPhone();
        act.ui.removeCallbacks(refresh); // never a second loop beside one that is already running
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
            // A boot run, or a hotspot of this tablet that is on, starting or failed, changes what is shown from second to second.
            // Otherwise nothing moves between the driver's own announcements (changed()), and every five seconds is plenty.
            boolean busy = driver.active() || driver.hotspot().state() != BootFlow.Hotspot.OFF;
            act.ui.postDelayed(this, busy ? 1000 : 5000);
        }
    };

    @Override
    public void changed() {
        paint();
    }

    /** The line at the top: while a run is on or has just ended, the run's own words; otherwise what the phone itself last said. */
    private String fact() {
        if (driver.active()) {
            return driver.headline();
        }
        if (priv == Priv.READY) {
            return "Shizuku is running on the phone: nothing to do.";
        }
        if (priv == Priv.OFF) {
            return "Shizuku is not running on the phone.";
        }
        if (priv == Priv.NO_PERM) {
            return "Shizuku is running, but LinkMeter is not allowed in it yet. Open LinkMeter on the phone and tap Allow.";
        }
        return driver.headline();
    }

    private void paint() {
        if (headline == null) {
            return;
        }
        boolean running = driver.running();
        boolean active = driver.active();
        startBtn.setEnabled(!running);
        cancelBtn.setVisibility(running ? View.VISIBLE : View.GONE);
        headline.setText(fact());
        String n = driver.note().isEmpty() ? driver.lastCommandResult() : driver.note();
        note.setText(n);
        note.setVisibility(n.isEmpty() ? View.GONE : View.VISIBLE);
        steps.setText(active ? driver.steps() : "");
        steps.setVisibility(active ? View.VISIBLE : View.GONE);
        phone.setText(active ? driver.phoneSummary() : "");
        phone.setVisibility(active ? View.VISIBLE : View.GONE);
        if (running && !detailsOpen) {
            openDetails(); // a run is on: the hotspot and what it needs from the phone are what the user is looking for
        }
        if (keepOn != null) {
            keepToggle.show(keepOn.booleanValue(), keepListener);
        }
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
        if (!TabletWifi.openSwitch(act)) {
            Toast.makeText(act, "Open Settings > Wi-Fi and switch it on.", Toast.LENGTH_LONG).show();
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
            readPhone(); // what the phone says now (also puts the pairing switch where the phone has it)
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

    /** The switch was turned on: ask first, and put it back where the phone has it if the answer is no. */
    private void confirmKeep() {
        new AlertDialog.Builder(act)
                .setTitle("Keep the pairing")
                .setMessage("Android forgets a wireless-debugging pairing that has not been used for 7 days. This switches that off, so the "
                        + "pairing stays until you remove it in Developer options. It is the same as the developer option \"Disable adb "
                        + "authorization timeout\". Needs Shizuku running on the phone now; switch it off here to undo it.")
                .setPositiveButton("Do it", (d, w) -> setup("boot_keep_on", 30000))
                .setNegativeButton("Cancel", (d, w) -> keepToggle.show(Boolean.TRUE.equals(keepOn), keepListener))
                .setOnCancelListener(d -> keepToggle.show(Boolean.TRUE.equals(keepOn), keepListener))
                .show();
    }
}
