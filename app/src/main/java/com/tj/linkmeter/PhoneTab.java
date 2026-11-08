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
    private Button toggle;

    PhoneTab(MainActivity a) {
        super(a);
    }

    @Override
    String title() {
        return "Phone";
    }

    @Override
    View build() {
        LinearLayout col = Ui.column(act);
        col.addView(Ui.text(act, "LinkMeter · phone", 24, Ui.FG, true));
        state = Ui.text(act, "", 16, Ui.FG, false);
        col.addView(state, Ui.lp(act, 12));
        toggle = Ui.button(act, "", v -> act.toggleSender());
        col.addView(toggle, Ui.lp(act, 8));

        col.addView(Ui.button(act, "Pair tablet (open for 2 minutes)", v -> act.pairTablet()),
                Ui.lp(act, 8));
        col.addView(Ui.text(act, "Pair once. After that the tablet connects by itself, "
                + "with no numbers to type.", 13, Ui.DIM, false), Ui.lp(act, 4));
        col.addView(Ui.button(act, "Forget all tablets", v -> {
            Cfg.clearTrusted(act);
            Toast.makeText(act, "All tablets forgotten.", Toast.LENGTH_SHORT).show();
        }), Ui.lp(act, 8));

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

        CheckBox boot = new CheckBox(act);
        boot.setText("Start automatically when the phone boots");
        boot.setTextColor(Ui.FG);
        boot.setChecked(Cfg.autoStart(act));
        boot.setOnCheckedChangeListener((view, checked) -> Cfg.setAutoStart(act, checked));
        col.addView(boot, Ui.lp(act, 16));

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
        col.addView(Ui.button(act, "Switch role", v -> act.chooseRole("")), Ui.lp(act, 24));
        return Ui.scroll(act, col);
    }

    @Override
    void onShow() {
        refresh();
    }

    void refresh() {
        if (state == null) {
            return;
        }
        boolean on = SenderService.active;
        state.setText(on ? "Ready. The tablet can connect." : "Stopped.");
        toggle.setText(on ? "Stop" : "Start");
        priv.setText(Priv.stateText(Priv.state()));
        files.setText(Files.hasAccess(act) ? "All files access is allowed."
                : "All files access is not allowed yet (needed for the Files tab).");
    }
}
