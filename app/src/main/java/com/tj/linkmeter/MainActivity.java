package com.tj.linkmeter;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
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
    private static final int REQ_PICK = 4;
    private static final int REQ_LOC = 5;
    /*
     * The phone's own screen asks for these with buttons of its own. None of them starts the service (REQ_PERMS does, as the
     * answer to Start), and the answer repaints the screen.
     */
    private static final int REQ_PHONE = 6;
    private static final int REQ_TEXTS = 7;
    private static final int REQ_NOTIFICATIONS = 8;
    /** Also asked by the tablet's Settings tab. */
    static final int REQ_BLUETOOTH = 9;
    private static final int REQ_UNLOCK = 10;

    /** Shared with the tabs. */
    final Handler ui = new Handler(Looper.getMainLooper());

    private boolean resumed;
    private FrameLayout content;
    private Tab[] tabs;
    private BarButton[] bar;
    private Tab cur;
    private int index;
    private View tabBar;
    private boolean immersive;
    /** If set, Back runs this instead of leaving the app (used to leave full screen). */
    Runnable backHandler;

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
        if (Cfg.TABLET.equals(Cfg.mode(this))) {
            // no Bluetooth connection is kept while the app is out of sight (a run that goes on in the background opens a new one)
            BtLink.closeSoon();
        }
        super.onPause();
    }

    private void afterShow() {
        applyKeepOn();
        if (cur != null) {
            cur.onShow();
        }
    }

    /**
     * Full screen: hides the tab bar and the system bars so a tab can use the whole display.
     * The "layout" flags are left out on purpose: with them the window no longer shrinks when the
     * on-screen keyboard opens, and the keyboard would cover the bottom of the screen.
     */
    void setFullscreen(boolean on) {
        immersive = on;
        setTabBarVisible(!on);
        applyImmersive();
    }

    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(immersive
                ? (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN)
                : View.SYSTEM_UI_FLAG_VISIBLE);
    }

    /** Shows or hides the row of tabs (hidden in full screen whatever is asked, and while typing). */
    void setTabBarVisible(boolean visible) {
        if (tabBar != null) {
            tabBar.setVisibility(visible && !immersive ? View.VISIBLE : View.GONE);
        }
    }

    /** Android shows the system bars again after the keyboard or a dialog; hide them again in full screen. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && immersive) {
            applyImmersive();
        }
    }

    @Override
    public void onBackPressed() {
        Runnable h = backHandler;
        if (h != null) {
            h.run();
        } else {
            super.onBackPressed();
        }
    }

    /** A tab (the Screen tab) asks for the tablet to stay awake while it is shown, on top of the Settings choice. */
    private boolean holdAwake;
    /** For the Screen tab only: a second, independent way of keeping the screen bright. Renewed while it is wanted. */
    private PowerManager.WakeLock awakeLock;
    private static final long WAKE_LOCK_MS = 10 * 60 * 1000L;
    private final Runnable renewLock = new Runnable() {
        @Override
        public void run() {
            if (holdAwake && resumed) {
                holdLock(true);
            }
        }
    };

    void setHoldAwake(boolean on) {
        holdAwake = on;
        applyKeepOn();
    }

    void applyKeepOn() {
        boolean tablet = Cfg.TABLET.equals(Cfg.mode(this));
        boolean keep = tablet && (Cfg.keepOn(this) || holdAwake);
        if (keep) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        getWindow().getDecorView().setKeepScreenOn(keep); // the same wish through the view, in case the flag gets lost
        holdLock(tablet && holdAwake);
    }

    /**
     * A bright-screen wake lock for as long as the Screen tab is open. It is only a backup for the window flag
     * above. It runs out by itself after ten minutes unless renewed, so it can never be left on by mistake.
     */
    @SuppressWarnings("deprecation")
    private void holdLock(boolean on) {
        try {
            ui.removeCallbacks(renewLock);
            if (on) {
                if (awakeLock == null) {
                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    awakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "linkmeter:remote-screen");
                    awakeLock.setReferenceCounted(false);
                }
                awakeLock.acquire(WAKE_LOCK_MS);
                ui.postDelayed(renewLock, WAKE_LOCK_MS / 2);
            } else if (awakeLock != null && awakeLock.isHeld()) {
                awakeLock.release();
            }
        } catch (RuntimeException ignored) {
            // no wake lock on this tablet: the window flag still does the job
        }
    }

    @Override
    protected void onDestroy() {
        holdLock(false);
        super.onDestroy();
    }

    /** For the Information box: what this tablet is doing about its own screen timeout, and what Android has set. */
    String awakeReport() {
        StringBuilder sb = new StringBuilder("Tablet screen: ");
        sb.append(holdAwake ? "held on by the Screen tab" : "not held on by the Screen tab");
        sb.append(" (window flag ")
                .append((getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0 ? "set" : "not set")
                .append(", wake lock ").append(awakeLock != null && awakeLock.isHeld() ? "held" : "not held").append(')');
        try {
            int timeout = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, -1);
            int level = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1);
            int auto = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
            sb.append("; Android's own screen timeout ").append(timeout < 0 ? "unknown" : (timeout / 1000) + " s");
            sb.append(", brightness ").append(level < 0 ? "unknown" : level + " of 255").append(auto == 1 ? " (automatic)" : "");
        } catch (RuntimeException ignored) {
            // the settings could not be read
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            sb.append(", battery saver ").append(pm.isPowerSaveMode() ? "ON" : "off");
        }
        return sb.toString();
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
            root = buildTabs(new Tab[] {new HomeTab(this), new SpeedTab(this), new SignalTab(this),
                    new ControlTab(this), new ScreenTab(this), new FilesTab(this), new TextsTab(this),
                    new SettingsTab(this)});
        } else if (Cfg.PHONE.equals(mode)) {
            root = buildTabs(new Tab[] {new PhoneTab(this), new FilesTab(this)});
        } else {
            root = buildChooser();
        }
        setContentView(root);
        applySecure();
        if (cur != null && resumed) {
            cur.onShow();
        }
        if (resumed) {
            applyKeepOn();
        }
    }

    /**
     * Changes this device's role ("" = ask again). The phone service, with the Bluetooth listener and Boot assist that live in it, belongs
     * to the phone role: it ends with the role, and only what the new role wants is started again (the tablet's file sharing). The
     * safety net, {@link Guard}, is left alone on purpose: it watches only while something LinkMeter changed is still in place, and
     * it ends by itself.
     */
    void chooseRole(String role) {
        String before = Cfg.mode(this);
        Cfg.setMode(this, role);
        if (!before.equals(role)) {
            if (SenderService.active) {
                stopService();
            }
            if (Cfg.TABLET.equals(role) && Cfg.shareFiles(this)) {
                startService();
            }
        }
        index = 0;
        render();
    }

    private View buildChooser() {
        LinearLayout col = Ui.column(this);
        col.addView(Ui.title(this, "LinkMeter"));
        col.addView(Ui.hint(this, "See and control the phone from the tablet while the phone is your hotspot. Install this app on "
                + "both, then pick this device's role. You can change it later."), Ui.lp(this, 4));
        LinearLayout phone = Ui.card(this, "The phone", "Shares its hotspot and does what the tablet asks. You pair the tablet with it "
                + "once.");
        phone.addView(Ui.primary(this, "This is the phone", v -> chooseRole(Cfg.PHONE)), Ui.lp(this, 10));
        col.addView(phone, Ui.lp(this, 16));
        LinearLayout tablet = Ui.card(this, "The tablet", "Shows the phone's signal, speed and screen, and switches the phone's hotspot, "
                + "Wi-Fi and power from where you sit.");
        tablet.addView(Ui.button(this, "This is the tablet", v -> chooseRole(Cfg.TABLET)), Ui.lp(this, 10));
        col.addView(tablet, Ui.lp(this, 10));
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

        // The bottom bar: one icon with its name per screen; the one on show is lit.
        LinearLayout barRow = new LinearLayout(this);
        barRow.setOrientation(LinearLayout.HORIZONTAL);
        barRow.setBackgroundColor(Ui.CARD);

        tabs = t;
        bar = new BarButton[tabs.length];
        for (int i = 0; i < tabs.length; i++) {
            final int idx = i;
            BarButton b = new BarButton(this, tabs[i].icon(), tabs[i].title(), v -> showTab(idx, true));
            b.setLabelSize(11f);
            barRow.addView(b, new LinearLayout.LayoutParams(0, Ui.dp(this, 58), 1f));
            bar[i] = b;
        }
        LinearLayout barBox = new LinearLayout(this);
        barBox.setOrientation(LinearLayout.VERTICAL);
        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        barBox.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 1))));
        barBox.addView(barRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tabBar = barBox;
        root.addView(barBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (index >= tabs.length) {
            index = 0;
        }
        showTab(index, false);
        return root;
    }

    /** The title of the tab on show, or "". */
    /** The tab that is in front. For the pictures made by tools/uipreview. */
    Tab currentTab() {
        return cur;
    }

    String shownTitle() {
        return cur == null ? "" : cur.title();
    }

    /** Switches to the tab with this title (one of the names in {@link Tab}), for a button on another tab. Does nothing if this role has no such tab. */
    void openTab(String title) {
        if (tabs == null) {
            return;
        }
        for (int i = 0; i < tabs.length; i++) {
            if (tabs[i].title().equals(title)) {
                showTab(i, true);
                return;
            }
        }
    }

    /** Any touch in the app: the current tab may be resting (the Screen tab) and wants to know that somebody is here. */
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        if (cur != null && resumed) {
            cur.onUserInteraction();
        }
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
            bar[j].setActive(j == i);
        }
        applySecure();
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
        } else if (req == REQ_PHONE || req == REQ_TEXTS || req == REQ_NOTIFICATIONS || req == REQ_BLUETOOTH) {
            if (req == REQ_BLUETOOTH) {
                if (Cfg.PHONE.equals(Cfg.mode(this))) {
                    BtServer.recheck(); // the listener looks again, now that it may be allowed
                }
                sendToAppPage(Manifest.permission.BLUETOOTH_CONNECT, results, "Nearby devices");
            } else if (req == REQ_PHONE) {
                sendToAppPage(Manifest.permission.READ_PHONE_STATE, results, "Phone");
            } else if (req == REQ_NOTIFICATIONS && Build.VERSION.SDK_INT >= 33) {
                sendToAppPage(Manifest.permission.POST_NOTIFICATIONS, results, "Notifications");
            }
            refreshPhone();
        } else if (req == REQ_LOC && locationDone != null) {
            Runnable r = locationDone;
            locationDone = null;
            r.run(); // granted or not: the hotspot says plainly if it cannot start
        }
    }

    private Runnable locationDone;

    /**
     * Asks for the location permission and runs then once the user has answered (at once if it is already given). Android 10 wants it
     * before an app may make a hotspot; LinkMeter does not read the location.
     */
    void requestLocation(Runnable then) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            then.run();
            return;
        }
        locationDone = then;
        requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOC);
    }

    /** Told when the system file picker (started by {@link #pickFiles}) is done. */
    interface PickResult {
        void onResult(int resultCode, Intent data);
    }

    private PickResult pickResult;

    /** Opens the system file picker for one or several files of any kind, and reports what was chosen to the callback. */
    void pickFiles(PickResult callback) {
        pickResult = callback;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (RuntimeException e) {
            pickResult = null;
            Toast.makeText(this, "This tablet has no file picker.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_PICK && pickResult != null) {
            PickResult cb = pickResult;
            pickResult = null;
            cb.onResult(result, data);
        } else if (req == REQ_UNLOCK && unlockResult != null) {
            UnlockResult cb = unlockResult;
            unlockResult = null;
            cb.onResult(result == RESULT_OK);
        }
    }

    /** Told when the tablet's screen-lock question (started by {@link #confirmDeviceCredential}) is answered. */
    interface UnlockResult {
        void onResult(boolean passed);
    }

    private UnlockResult unlockResult;

    /**
     * Asks for the tablet's own screen lock (PIN, pattern, password or fingerprint, whichever the tablet offers) and tells the callback
     * whether it was passed. Returns false, and never calls the callback, when the tablet has no screen lock or cannot show the question.
     */
    boolean confirmDeviceCredential(String title, String text, UnlockResult callback) {
        android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        Intent i = km == null ? null : km.createConfirmDeviceCredentialIntent(title, text);
        if (i == null) {
            return false;
        }
        unlockResult = callback;
        try {
            startActivityForResult(i, REQ_UNLOCK);
            return true;
        } catch (RuntimeException e) {
            unlockResult = null;
            return false;
        }
    }

    /**
     * The window of a tab can be kept out of screenshots, screen recordings and the recent apps list ({@link Tab#secure}). The flag is
     * only changed when the tab in front changes, never when the app is paused: the recent apps picture is taken after the pause.
     */
    void applySecure() {
        boolean on = cur != null && cur.secure();
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    /** Asks for the phone permission that lets the app read the cell signal. Only the screen is repainted afterwards: the service is not started. */
    void requestPhonePermission() {
        requestPermissions(new String[] {Manifest.permission.READ_PHONE_STATE}, REQ_PHONE);
    }

    /** Asks for the notification permission (Android 13 and later: LinkMeter's notification is what keeps it running). */
    void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    /** Asks for the texts permissions through Android's own question. */
    void requestTextsPermissions() {
        requestPermissions(SmsAccess.ASK, REQ_TEXTS);
    }

    /** Asks for the Bluetooth permission ("Nearby devices", Android 12 and later); the answer makes the phone's listener look again. */
    void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT}, REQ_BLUETOOTH);
        }
    }

    /**
     * After a refusal Android stops asking, and the button would do nothing. So when a permission was refused and Android will not
     * ask again, the app's own page opens, with a word on what to allow there.
     */
    private void sendToAppPage(String permission, int[] results, String name) {
        boolean answered = results.length > 0;
        boolean granted = answered && results[0] == PackageManager.PERMISSION_GRANTED;
        if (answered && !granted && !shouldShowRequestPermissionRationale(permission)) {
            Toast.makeText(this, "Android will not ask again. Open Permissions here and allow " + name + ".", Toast.LENGTH_LONG).show();
            openAppInfo();
        }
    }

    /** Android's own question that switches Bluetooth on; the Bluetooth settings if this phone will not show it. */
    void askBluetoothOn() {
        try {
            startActivity(new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE));
        } catch (RuntimeException e) {
            try {
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            } catch (RuntimeException ignored) {
                // nothing to open
            }
        }
    }

    /** Opens the Shizuku app (Android's developer options if it is not installed). */
    void openShizuku() {
        try {
            startActivity(Boot.shizukuIntent(this));
        } catch (RuntimeException e) {
            Toast.makeText(this, "Could not open Shizuku. Open it from the app list.", Toast.LENGTH_LONG).show();
        }
    }

    void startService() {
        startForegroundService(new Intent(this, SenderService.class));
    }

    void stopService() {
        stopService(new Intent(this, SenderService.class));
    }

    void refreshPhone() {
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
