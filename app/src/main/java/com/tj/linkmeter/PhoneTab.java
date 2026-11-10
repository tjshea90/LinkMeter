package com.tj.linkmeter;

import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** The phone's own screen: start or stop, pair a tablet, and the setup checks. */
final class PhoneTab extends Tab {
    private TextView state;
    private TextView priv;
    private TextView files;
    private TextView seen;
    private TextView version;
    private TextView phonePerm;
    private TextView textsInfo;
    private Button toggle;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            act.ui.postDelayed(this, 2000);
        }
    };

    PhoneTab(MainActivity a) {
        super(a);
    }

    @Override
    String title() {
        return "Phone";
    }

    @Override
    Icons.Kind icon() {
        return Icons.Kind.PHONE;
    }

    @Override
    View build() {
        LinearLayout col = Ui.column(act);
        col.addView(Ui.text(act, "LinkMeter · phone", 24, Ui.FG, true));

        // Works on the phone alone: no tablet and no internet needed.
        col.addView(Ui.text(act, "If something breaks", 16, Ui.RED, true), Ui.lp(act, 12));
        col.addView(Ui.button(act, "Restore everything (fix internet)", v -> restore()), Ui.lp(act, 4));
        col.addView(Ui.button(act, "Restart modem", v -> restartModem()), Ui.lp(act, 4));
        col.addView(Ui.button(act, "Open mobile network settings", v -> act.openNetworkSettings()),
                Ui.lp(act, 4));
        col.addView(Ui.text(act, "Restore puts back the band lock, network types, hotspot band, "
                + "battery saver, airplane mode and mobile data. It also runs from the Restore "
                + "button on LinkMeter's notification. Without Shizuku, follow these steps:\n"
                + Actions.MANUAL, 12, Ui.DIM, false), Ui.lp(act, 4));

        state = Ui.text(act, "", 16, Ui.FG, false);
        col.addView(state, Ui.lp(act, 12));
        toggle = Ui.button(act, "", v -> act.toggleSender());
        col.addView(toggle, Ui.lp(act, 8));

        col.addView(Ui.button(act, "Pair tablet (open for 2 minutes)", v -> act.pairTablet()),
                Ui.lp(act, 8));
        col.addView(Ui.text(act, "Pair once. After that the tablet connects by itself, "
                + "with no numbers to type.", 13, Ui.DIM, false), Ui.lp(act, 4));
        seen = Ui.text(act, "", 12, Ui.DIM, false);
        col.addView(seen, Ui.lp(act, 8));
        col.addView(Ui.button(act, "Forget all tablets", v -> {
            Cfg.clearTrusted(act);
            Toast.makeText(act, "All tablets forgotten.", Toast.LENGTH_SHORT).show();
        }), Ui.lp(act, 8));

        col.addView(Ui.text(act, "Phone permission (for the signal meter)", 16, Ui.FG, true), Ui.lp(act, 20));
        phonePerm = Ui.text(act, "", 13, Ui.DIM, false);
        col.addView(phonePerm, Ui.lp(act, 2));
        col.addView(Ui.button(act, "Allow phone permission", v -> act.requestPhonePermission()), Ui.lp(act, 6));

        col.addView(Ui.text(act, "Phone helper (Shizuku)", 16, Ui.FG, true), Ui.lp(act, 20));
        priv = Ui.text(act, "", 13, Ui.DIM, false);
        col.addView(priv, Ui.lp(act, 2));
        col.addView(Ui.button(act, "Allow LinkMeter in Shizuku", v -> Priv.requestPermission(2)),
                Ui.lp(act, 6));
        col.addView(Ui.text(act, "Needed for the tablet's Control tab: airplane mode, hotspot off, "
                + "power off, 4G toggle, battery saver. Start Shizuku first (it stops when the "
                + "phone reboots). The tablet's Prepare phone button then sets up the rest.",
                12, Ui.DIM, false), Ui.lp(act, 4));

        col.addView(Ui.text(act, "Files", 16, Ui.FG, true), Ui.lp(act, 20));
        files = Ui.text(act, "", 13, Ui.DIM, false);
        col.addView(files, Ui.lp(act, 2));
        col.addView(Ui.button(act, "Allow all files access", v -> act.fixStorageAccess()),
                Ui.lp(act, 6));

        col.addView(Ui.text(act, "Texts", 16, Ui.FG, true), Ui.lp(act, 20));
        CheckBox texts = new CheckBox(act);
        texts.setText("Let the tablet use my texts");
        texts.setTextColor(Ui.FG);
        texts.setChecked(Cfg.textsOn(act));
        texts.setOnCheckedChangeListener((view, checked) -> {
            Cfg.setTextsOn(act, checked);
            if (checked && !SmsAccess.note(act).isEmpty()) {
                allowTexts();
            }
            refresh();
        });
        col.addView(texts, Ui.lp(act, 4));
        textsInfo = Ui.text(act, "", 13, Ui.DIM, false);
        col.addView(textsInfo, Ui.lp(act, 2));
        col.addView(Ui.button(act, "Allow texts access", v -> allowTexts()), Ui.lp(act, 6));
        col.addView(Ui.text(act, "Off until you switch it on. When on, only the tablet you paired can read your texts and send texts "
                + "and pictures through this phone. Chats that use RCS (\"chat features\" in Google Messages) are kept inside that app "
                + "and cannot be seen here; turn chat features off there to see those conversations as plain texts.", 12, Ui.DIM, false),
                Ui.lp(act, 4));

        CheckBox boot = new CheckBox(act);
        boot.setText("Start automatically when the phone boots");
        boot.setTextColor(Ui.FG);
        boot.setChecked(Cfg.autoStart(act));
        boot.setOnCheckedChangeListener((view, checked) -> Cfg.setAutoStart(act, checked));
        col.addView(boot, Ui.lp(act, 16));

        CheckBox screenCtl = new CheckBox(act);
        screenCtl.setText("Let the tablet see and control this screen");
        screenCtl.setTextColor(Ui.FG);
        screenCtl.setChecked(Cfg.screenControl(act));
        screenCtl.setOnCheckedChangeListener((view, checked) -> Cfg.setScreenControl(act, checked));
        col.addView(screenCtl, Ui.lp(act, 4));

        CheckBox eco = new CheckBox(act);
        eco.setText("Switch battery saver on by itself when the tablet connects");
        eco.setTextColor(Ui.FG);
        eco.setChecked(Cfg.ecoAuto(act));
        eco.setOnCheckedChangeListener((view, checked) -> Cfg.setEcoAuto(act, checked));
        col.addView(eco, Ui.lp(act, 4));

        col.addView(Ui.button(act, "Battery settings for this app", v -> act.openBatterySettings()),
                Ui.lp(act, 12));
        col.addView(Ui.text(act, "Tip: set this app to Unrestricted battery use so Android never "
                + "stops it while the screen is off.", 13, Ui.DIM, false), Ui.lp(act, 4));
        version = Ui.text(act, "", 12, Ui.DIM, false);
        col.addView(version, Ui.lp(act, 20));
        col.addView(Ui.button(act, "Switch role", v -> act.chooseRole("")), Ui.lp(act, 12));
        return Ui.scroll(act, col);
    }

    @Override
    void onShow() {
        act.ui.removeCallbacks(tick);
        tick.run();
    }

    @Override
    void onHide() {
        act.ui.removeCallbacks(tick);
    }

    private void showResult(String title, String text) {
        new android.app.AlertDialog.Builder(act).setTitle(title).setMessage(text)
                .setPositiveButton("OK", null).show();
    }

    private void restore() {
        Toast.makeText(act, "Restoring…", Toast.LENGTH_SHORT).show();
        final android.content.Context app = act.getApplicationContext();
        new Thread(() -> {
            final String r = Actions.restoreAll(app);
            act.ui.post(() -> showResult("Restore everything", r));
        }, "linkmeter-restore").start();
    }

    /** Gets the texts permissions: through Shizuku if it is ready, otherwise through Android's own permission prompt. */
    private void allowTexts() {
        if (Priv.ready()) {
            final android.content.Context app = act.getApplicationContext();
            Toast.makeText(act, "Allowing texts access…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String r;
                try {
                    r = SmsAccess.grant(app);
                } catch (java.io.IOException e) {
                    r = "Couldn't allow texts access: " + e.getMessage();
                }
                final String fr = r;
                act.ui.post(() -> {
                    showResult("Texts access", fr);
                    refresh();
                });
            }, "linkmeter-sms-grant").start();
        } else {
            act.requestPermissions(SmsAccess.PERMS, 7);
            Toast.makeText(act, "If Android says the setting is restricted, start Shizuku and tap this again, or allow restricted "
                    + "settings in this app's info page.", Toast.LENGTH_LONG).show();
        }
    }

    private void restartModem() {
        final android.content.Context app = act.getApplicationContext();
        Toast.makeText(act, "Restarting the modem…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String r;
            try {
                r = Actions.run(app, "restart_modem");
            } catch (java.io.IOException e) {
                r = "Couldn't restart the modem: " + e.getMessage();
            }
            final String fr = r;
            act.ui.post(() -> showResult("Restart modem", fr));
        }, "linkmeter-modem").start();
    }

    void refresh() {
        if (state == null) {
            return;
        }
        boolean on = SenderService.active;
        state.setText(on ? "Ready. The tablet can connect." : "Stopped.");
        toggle.setText(on ? "Stop" : "Start");
        priv.setText(Priv.stateText(Priv.state()));
        if (Api.hits == 0) {
            seen.setText("Nothing has reached the phone yet from the tablet.");
        } else {
            seen.setText("Requests received: " + Api.hits + ". Last: " + Api.lastNote + " at "
                    + java.text.DateFormat.getTimeInstance().format(new java.util.Date(Api.lastAt)));
        }
        phonePerm.setText(act.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
                ? "Allowed. The tablet can read the phone's signal."
                : "Not allowed. The signal is read through Shizuku instead; allow it here for the "
                        + "most reliable reading.");
        String last = Updater.lastResult(act);
        version.setText("App version " + Updater.versionName(act) + " (code " + Updater.versionCode(act)
                + ")" + (last.isEmpty() ? "" : ". Last update from the tablet: " + last));
        String missing = SmsAccess.note(act);
        textsInfo.setText(!Cfg.textsOn(act) ? "Off. The tablet cannot see your texts."
                : missing.isEmpty() ? "On. The paired tablet can read your texts and send texts and pictures through this phone."
                : "On, but not ready. " + missing);
        files.setText(Files.hasAccess(act) ? "All files access is allowed."
                : "All files access is not allowed yet (needed for the Files tab).");
    }
}
