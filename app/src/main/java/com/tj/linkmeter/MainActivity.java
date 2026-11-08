package com.tj.linkmeter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/**
 * One activity, built in code (no layout files, no libraries).
 *
 * Phone role: a short control screen (start, pair, options).
 * Tablet role: tabs. Only the tab on screen does any work, and only while the
 * app is in front.
 */
public class MainActivity extends Activity {
    private static final int REQ_PERMS = 1;

    /** Shared with the tabs. */
    final Handler ui = new Handler(Looper.getMainLooper());

    private boolean resumed;

    // Tablet shell
    private FrameLayout content;
    private Tab[] tabs;
    private Button[] bar;
    private Tab cur;
    private int index;

    // Phone screen
    private TextView pState;
    private TextView pPriv;
    private Button pToggle;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        afterShow();
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (cur != null) {
            cur.onHide();
        }
        super.onPause();
    }

    private void afterShow() {
        applyKeepOn();
        if (cur != null) {
            cur.onShow();
        } else if (Cfg.PHONE.equals(Cfg.mode(this))) {
            refreshPhone();
        }
    }

    void applyKeepOn() {
        if (Cfg.TABLET.equals(Cfg.mode(this)) && Cfg.keepOn(this)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ---------------------------------------------------------------- screens

    private void render() {
        if (cur != null) {
            cur.onHide();
        }
        cur = null;
        tabs = null;
        bar = null;
        content = null;
        pState = null;
        pPriv = null;
        pToggle = null;

        String mode = Cfg.mode(this);
        View root;
        if (Cfg.TABLET.equals(mode)) {
            root = buildTablet();
        } else if (Cfg.PHONE.equals(mode)) {
            root = buildPhone();
        } else {
            root = buildChooser();
        }
        setContentView(root);
        if (cur != null && resumed) {
            // select() already ran in buildTablet() before the view was attached
            cur.onShow();
        }
        if (resumed) {
            applyKeepOn();
            if (cur == null && Cfg.PHONE.equals(mode)) {
                refreshPhone();
            }
        }
    }

    void chooseRole(String role) {
        Cfg.setMode(this, role);
        render();
    }

    private View buildChooser() {
        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, "LinkMeter", 28, Ui.FG, true));
        col.addView(Ui.text(this, "See and control the phone from the tablet while the phone is "
                + "your hotspot. Install this app on both, then pick this device's role.",
                15, Ui.DIM, false), Ui.lp(this, 8));
        col.addView(Ui.button(this, "This is the PHONE", v -> chooseRole(Cfg.PHONE)), Ui.lp(this, 24));
        col.addView(Ui.button(this, "This is the TABLET", v -> chooseRole(Cfg.TABLET)), Ui.lp(this, 8));
        return framed(Ui.scroll(this, col));
    }

    private View framed(View v) {
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(Ui.BG);
        f.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return f;
    }

    // ---------------------------------------------------------------- tablet shell

    private View buildTablet() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout barRow = new LinearLayout(this);
        barRow.setOrientation(LinearLayout.HORIZONTAL);
        barRow.setBackgroundColor(Ui.CARD);

        tabs = new Tab[] {new HomeTab(this), new SpeedTab(this), new ControlTab(this),
                new SettingsTab(this)};
        bar = new Button[tabs.length];
        for (int i = 0; i < tabs.length; i++) {
            final int idx = i;
            Button b = Ui.button(this, tabs[i].title(), v -> select(idx));
            b.setTextSize(13);
            barRow.addView(b, Ui.weight());
            bar[i] = b;
        }
        root.addView(barRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (index >= tabs.length) {
            index = 0;
        }
        // Not resumed yet from this method's point of view: show the tab's views only.
        showTab(index, false);
        return root;
    }

    private void select(int i) {
        showTab(i, true);
    }

    private void showTab(int i, boolean start) {
        if (cur != null) {
            cur.onHide();
        }
        index = i;
        cur = tabs[i];
        content.removeAllViews();
        content.addView(cur.build());
        for (int j = 0; j < bar.length; j++) {
            bar[j].setAlpha(j == i ? 1f : 0.55f);
        }
        if (start && resumed) {
            cur.onShow();
        }
    }

    // ---------------------------------------------------------------- phone screen

    private View buildPhone() {
        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, "LinkMeter · phone", 24, Ui.FG, true));
        pState = Ui.text(this, "", 16, Ui.FG, false);
        col.addView(pState, Ui.lp(this, 12));
        pToggle = Ui.button(this, "", v -> toggleSender());
        col.addView(pToggle, Ui.lp(this, 8));

        col.addView(Ui.button(this, "Pair tablet (open for 2 minutes)", v -> pairTablet()),
                Ui.lp(this, 8));
        col.addView(Ui.text(this, "Pair once. After that the tablet connects by itself, "
                + "with no numbers to type.", 13, Ui.DIM, false), Ui.lp(this, 4));
        col.addView(Ui.button(this, "Forget all tablets", v -> {
            Cfg.clearTrusted(this);
            Toast.makeText(this, "All tablets forgotten.", Toast.LENGTH_SHORT).show();
        }), Ui.lp(this, 8));

        col.addView(Ui.text(this, "Phone helper (Shizuku)", 16, Ui.FG, true), Ui.lp(this, 20));
        pPriv = Ui.text(this, "", 13, Ui.DIM, false);
        col.addView(pPriv, Ui.lp(this, 2));
        col.addView(Ui.button(this, "Allow LinkMeter in Shizuku", v -> Priv.requestPermission(2)),
                Ui.lp(this, 6));
        col.addView(Ui.text(this, "Needed for the tablet's Control tab: airplane mode, hotspot off, "
                + "power off, 4G toggle, battery saver. Start Shizuku first (it stops when the "
                + "phone reboots).", 12, Ui.DIM, false), Ui.lp(this, 4));

        CheckBox boot = new CheckBox(this);
        boot.setText("Start automatically when the phone boots");
        boot.setTextColor(Ui.FG);
        boot.setChecked(Cfg.autoStart(this));
        boot.setOnCheckedChangeListener((view, checked) -> Cfg.setAutoStart(this, checked));
        col.addView(boot, Ui.lp(this, 16));

        col.addView(Ui.button(this, "Battery settings for this app", v -> openBatterySettings()),
                Ui.lp(this, 12));
        col.addView(Ui.text(this, "Tip: set this app to Unrestricted battery use so Android never "
                + "stops it while the screen is off.", 13, Ui.DIM, false), Ui.lp(this, 4));
        col.addView(Ui.button(this, "Switch role", v -> chooseRole("")), Ui.lp(this, 24));
        return framed(Ui.scroll(this, col));
    }

    private void pairTablet() {
        SenderService.pairUntil = SystemClock.elapsedRealtime() + 120000L;
        if (!SenderService.active) {
            toggleSender();
        }
        Toast.makeText(this, "Open LinkMeter on the tablet now.", Toast.LENGTH_LONG).show();
    }

    private void toggleSender() {
        if (SenderService.active) {
            stopService();
            ui.postDelayed(this::refreshPhone, 400);
            return;
        }
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.READ_PHONE_STATE);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
            return;
        }
        startService();
        ui.postDelayed(this::refreshPhone, 400);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_PERMS && Cfg.PHONE.equals(Cfg.mode(this))) {
            // Start either way. Without the phone permission the tablet simply
            // shows "no permission" for the cell meter.
            startService();
            ui.postDelayed(this::refreshPhone, 400);
        }
    }

    void startService() {
        startForegroundService(new Intent(this, SenderService.class));
    }

    void stopService() {
        stopService(new Intent(this, SenderService.class));
    }

    private void refreshPhone() {
        if (pState == null || pToggle == null) {
            return;
        }
        boolean on = SenderService.active;
        pState.setText(on ? "Ready. The tablet can connect." : "Stopped.");
        pToggle.setText(on ? "Stop" : "Start");
        if (pPriv != null) {
            pPriv.setText(Priv.stateText(Priv.state()));
        }
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (RuntimeException e) {
            openAppInfo();
        }
    }

    void openAppInfo() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException ignored) {
            // No settings screen to open; nothing more to try.
        }
    }
}
