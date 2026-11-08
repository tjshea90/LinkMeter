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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/**
 * One activity, built in code (no layout files). Both roles use a row of tabs at the
 * bottom. Only the tab on screen does any work, and only while the app is in front.
 */
public class MainActivity extends Activity {
    private static final int REQ_PERMS = 1;
    private static final int REQ_STORAGE = 3;

    /** Shared with the tabs. */
    final Handler ui = new Handler(Looper.getMainLooper());

    private boolean resumed;
    private FrameLayout content;
    private Tab[] tabs;
    private Button[] bar;
    private Tab cur;
    private int index;

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

        String mode = Cfg.mode(this);
        View root;
        if (Cfg.TABLET.equals(mode)) {
            root = buildTabs(new Tab[] {new HomeTab(this), new SpeedTab(this), new BandsTab(this),
                    new ControlTab(this), new FilesTab(this), new SettingsTab(this)});
        } else if (Cfg.PHONE.equals(mode)) {
            root = buildTabs(new Tab[] {new PhoneTab(this), new FilesTab(this)});
        } else {
            root = buildChooser();
        }
        setContentView(root);
        if (cur != null && resumed) {
            cur.onShow();
        }
        if (resumed) {
            applyKeepOn();
        }
    }

    void chooseRole(String role) {
        Cfg.setMode(this, role);
        index = 0;
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
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(Ui.BG);
        f.addView(Ui.scroll(this, col), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return f;
    }

    private View buildTabs(Tab[] t) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout barRow = new LinearLayout(this);
        barRow.setOrientation(LinearLayout.HORIZONTAL);
        barRow.setBackgroundColor(Ui.CARD);

        tabs = t;
        bar = new Button[tabs.length];
        for (int i = 0; i < tabs.length; i++) {
            final int idx = i;
            Button b = Ui.button(this, tabs[i].title(), v -> showTab(idx, true));
            b.setTextSize(12);
            b.setPadding(0, b.getPaddingTop(), 0, b.getPaddingBottom());
            barRow.addView(b, Ui.weight());
            bar[i] = b;
        }
        root.addView(barRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (index >= tabs.length) {
            index = 0;
        }
        showTab(index, false);
        return root;
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

    // ---------------------------------------------------------------- phone actions

    void pairTablet() {
        SenderService.pairUntil = SystemClock.elapsedRealtime() + 120000L;
        if (!SenderService.active) {
            toggleSender();
        }
        Toast.makeText(this, "Open LinkMeter on the tablet now.", Toast.LENGTH_LONG).show();
    }

    void toggleSender() {
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
        } else if (req == REQ_STORAGE && tabs != null) {
            showTab(index, true);
        }
    }

    void startService() {
        startForegroundService(new Intent(this, SenderService.class));
    }

    void stopService() {
        stopService(new Intent(this, SenderService.class));
    }

    private void refreshPhone() {
        if (cur instanceof PhoneTab) {
            ((PhoneTab) cur).refresh();
        }
    }

    void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (RuntimeException e) {
            openAppInfo();
        }
    }

    /** The phone's own Network mode page: the manual way to undo a network change, no Shizuku needed. */
    void openNetworkSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS));
        } catch (RuntimeException e) {
            try {
                startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
            } catch (RuntimeException ignored) {
                // nothing to open
            }
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

    /** Android 11+: the "All files access" page. Android 10 and older: the storage permission prompt. */
    void fixStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (RuntimeException ignored) {
                    openAppInfo();
                }
            }
        } else {
            requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }
}
