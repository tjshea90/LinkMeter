package com.tj.linkmeter;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.wifi.DhcpInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One activity, built in code (no layout files, no libraries).
 *
 * Phone role: shows the pairing code and starts or stops the sender service.
 * Tablet role: asks the phone for its status and shows three meters.
 *
 * The tablet polls only while this screen is visible. When the screen turns
 * off or another app is in front, the worker thread is shut down completely.
 */
public class MainActivity extends Activity {
    private static final int BG = 0xFF101216;
    private static final int FG = 0xFFF2F4F7;
    private static final int DIM = 0xFF9AA0A9;
    private static final int GREEN = 0xFF3DDC84;
    private static final int AMBER = 0xFFFFB300;
    private static final int RED = 0xFFFF5252;
    private static final int GREY = 0xFF8A8F98;
    private static final int REQ_PERMS = 1;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread worker;
    private volatile Handler bg;
    private volatile boolean polling;
    private volatile int fails;
    private boolean resumed;
    private JSONObject lastPhone;
    private long lastOk;

    // Tablet views
    private MeterView mBat;
    private MeterView mCell;
    private MeterView mWifi;
    private TextView tStatus;
    private TextView tInterval;

    // Phone views
    private TextView pState;
    private Button pToggle;

    /** What one poll found. Built on the worker thread, shown on the UI thread. */
    private static final class Snap {
        boolean wifiOk;
        int wRssi;
        int wLvl = -1;
        int wLink;
        int wFreq;
        JSONObject phone;
        String error = "";
        long at;
    }

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            Handler h = bg;
            if (h == null || !polling) {
                return;
            }
            final Snap s = poll();
            ui.post(() -> show(s));
            if (polling && bg == h) {
                h.postDelayed(this, delayMs());
            }
        }
    };

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
        stopPolling();
        super.onPause();
    }

    private void afterShow() {
        String mode = Cfg.mode(this);
        boolean tablet = Cfg.TABLET.equals(mode);
        if (tablet && Cfg.keepOn(this)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (tablet) {
            startPolling();
        } else if (Cfg.PHONE.equals(mode)) {
            refreshPhone();
        }
    }

    // ---------------------------------------------------------------- screens

    private void render() {
        stopPolling();
        lastPhone = null;
        mBat = null;
        mCell = null;
        mWifi = null;
        tStatus = null;
        tInterval = null;
        pState = null;
        pToggle = null;

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(20), dp(16), dp(24));
        sv.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String mode = Cfg.mode(this);
        if (Cfg.PHONE.equals(mode)) {
            buildPhone(col);
        } else if (Cfg.TABLET.equals(mode)) {
            buildTablet(col);
        } else {
            buildChooser(col);
        }
        setContentView(sv);
        if (resumed) {
            afterShow();
        }
    }

    private void buildChooser(LinearLayout col) {
        col.addView(text("LinkMeter", 28, FG, true));
        col.addView(text("See the phone's battery and signal on the tablet while the phone "
                + "is your hotspot. Install this app on both, then pick this device's role.",
                15, DIM, false), lp(8));
        col.addView(button("This is the PHONE (sends status)", v -> chooseRole(Cfg.PHONE)), lp(24));
        col.addView(button("This is the TABLET (shows status)", v -> chooseRole(Cfg.TABLET)), lp(8));
    }

    private void chooseRole(String role) {
        Cfg.setMode(this, role);
        render();
    }

    private void buildPhone(LinearLayout col) {
        col.addView(text("LinkMeter · phone", 24, FG, true));
        col.addView(text("Pairing code", 14, DIM, false), lp(20));
        TextView code = text(Cfg.ensureCode(this), 44, FG, true);
        code.setLetterSpacing(0.2f);
        col.addView(code);
        col.addView(text("Type this code into LinkMeter on the tablet (only needed once).",
                14, DIM, false));
        pState = text("", 16, FG, false);
        col.addView(pState, lp(20));
        pToggle = button("", v -> toggleSender());
        col.addView(pToggle, lp(8));
        col.addView(button("Battery settings for this app", v -> openBatterySettings()), lp(8));
        col.addView(text("Tip: set this app to Unrestricted battery use so Android doesn't "
                + "stop the sender while the screen is off. Turn the hotspot on first, then "
                + "start the sender.", 13, DIM, false), lp(8));
        col.addView(button("Switch role", v -> chooseRole("")), lp(24));
    }

    private void buildTablet(LinearLayout col) {
        col.addView(text("LinkMeter · tablet", 22, FG, true));
        tStatus = text("Starting…", 14, DIM, false);
        col.addView(tStatus, lp(4));

        mBat = new MeterView(this);
        mCell = new MeterView(this);
        mWifi = new MeterView(this);
        col.addView(mBat, lp(12));
        col.addView(mCell, lp(10));
        col.addView(mWifi, lp(10));
        mBat.set("Phone battery", "--", "waiting for the phone", -1, 0f, GREY);
        mCell.set("Phone cell signal", "--", "waiting for the phone", -1, -1f, GREY);
        mWifi.set("Tablet Wi-Fi to phone", "--", "checking", -1, -1f, GREY);

        col.addView(text("Settings", 17, FG, true), lp(26));

        col.addView(text("Pairing code (shown on the phone)", 13, DIM, false), lp(8));
        EditText code = new EditText(this);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setFilters(new InputFilter[] {new InputFilter.LengthFilter(4)});
        code.setSingleLine(true);
        code.setTextColor(FG);
        code.setText(Cfg.code(this));
        code.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable e) {
                Cfg.setCode(MainActivity.this, e.toString().trim());
                fails = 0;
            }
        });
        col.addView(code);

        col.addView(text("Phone address (leave blank for automatic)", 13, DIM, false), lp(8));
        EditText host = new EditText(this);
        host.setSingleLine(true);
        host.setKeyListener(DigitsKeyListener.getInstance("0123456789."));
        host.setTextColor(FG);
        host.setHint("automatic");
        host.setHintTextColor(DIM);
        host.setText(Cfg.host(this));
        host.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable e) {
                Cfg.setHost(MainActivity.this, e.toString().trim());
                fails = 0;
            }
        });
        col.addView(host);

        tInterval = text("", 13, DIM, false);
        col.addView(tInterval, lp(14));
        tInterval.setText(intervalLabel(Cfg.intervalMs(this)));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(button("Eco 15 s", v -> setInterval(15000L)), weight());
        row.addView(button("Normal 5 s", v -> setInterval(5000L)), weight());
        row.addView(button("Live 2 s", v -> setInterval(2000L)), weight());
        col.addView(row);

        CheckBox keep = new CheckBox(this);
        keep.setText("Keep the screen on while this app is open");
        keep.setTextColor(FG);
        keep.setChecked(Cfg.keepOn(this));
        keep.setOnCheckedChangeListener((view, checked) -> {
            Cfg.setKeepOn(this, checked);
            afterShow();
        });
        col.addView(keep, lp(8));

        col.addView(button("App info and permissions", v -> openAppInfo()), lp(8));
        col.addView(button("Switch role", v -> chooseRole("")), lp(8));
    }

    // ---------------------------------------------------------------- phone role

    private void toggleSender() {
        if (SenderService.active) {
            stopService(new Intent(this, SenderService.class));
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
        startSender();
    }

    private void startSender() {
        startForegroundService(new Intent(this, SenderService.class));
        ui.postDelayed(this::refreshPhone, 400);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_PERMS && Cfg.PHONE.equals(Cfg.mode(this))) {
            // Start either way. Without the phone permission the tablet simply
            // shows "no permission" for the cell meter.
            startSender();
        }
    }

    private void refreshPhone() {
        if (pState == null || pToggle == null) {
            return;
        }
        boolean on = SenderService.active;
        pState.setText(on ? "Sender is running. Now open LinkMeter on the tablet."
                : "Sender is stopped.");
        pToggle.setText(on ? "Stop sender" : "Start sender");
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception e) {
            openAppInfo();
        }
    }

    private void openAppInfo() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
            // No settings screen to open; nothing more to try.
        }
    }

    // ---------------------------------------------------------------- tablet role

    private void startPolling() {
        if (polling) {
            return;
        }
        polling = true;
        fails = 0;
        worker = new HandlerThread("linkmeter-poll");
        worker.start();
        bg = new Handler(worker.getLooper());
        bg.post(pollTask);
    }

    private void stopPolling() {
        polling = false;
        Handler h = bg;
        bg = null;
        if (h != null) {
            h.removeCallbacksAndMessages(null);
        }
        if (worker != null) {
            worker.quitSafely();
            worker = null;
        }
    }

    private long delayMs() {
        long base = Cfg.intervalMs(this);
        // After a few misses (phone off, hotspot off) ask less often.
        return fails >= 3 ? Math.max(base, 15000L) : base;
    }

    private void setInterval(long ms) {
        Cfg.setIntervalMs(this, ms);
        if (tInterval != null) {
            tInterval.setText(intervalLabel(ms));
        }
        Handler h = bg;
        if (polling && h != null) {
            h.removeCallbacks(pollTask);
            h.post(pollTask);
        }
    }

    private static String intervalLabel(long ms) {
        return "Ask the phone every " + (ms / 1000) + " s while this screen is open "
                + "(longer = less battery on both devices)";
    }

    /** Runs on the worker thread. */
    private Snap poll() {
        Snap s = new Snap();
        s.at = System.currentTimeMillis();
        int gateway = 0;
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            if (wm.isWifiEnabled() && wi != null && wi.getNetworkId() != -1) {
                s.wifiOk = true;
                s.wRssi = wi.getRssi();
                s.wLink = wi.getLinkSpeed();
                s.wFreq = wi.getFrequency();
                s.wLvl = WifiManager.calculateSignalLevel(s.wRssi, 5);
            }
            DhcpInfo d = wm.getDhcpInfo();
            if (d != null) {
                gateway = d.gateway;
            }
        } catch (RuntimeException ignored) {
            // Leave the Wi-Fi meter as "not connected".
        }

        String host = Cfg.host(this);
        if (host.isEmpty() && gateway != 0) {
            host = (gateway & 0xFF) + "." + ((gateway >> 8) & 0xFF) + "."
                    + ((gateway >> 16) & 0xFF) + "." + ((gateway >> 24) & 0xFF);
        }

        if (!s.wifiOk) {
            s.error = "Tablet isn't on Wi-Fi. Join the phone's hotspot.";
        } else if (host.isEmpty()) {
            s.error = "Can't find the phone's address. Type it in Settings below.";
        } else if (Cfg.code(this).length() != 4) {
            s.error = "Type the 4-digit pairing code from the phone into Settings below.";
        } else {
            try {
                s.phone = query(host);
            } catch (SocketTimeoutException e) {
                s.error = "The phone didn't answer. Is its hotspot on and the sender running?";
            } catch (ConnectException e) {
                s.error = "The phone refused. Start the sender in LinkMeter on the phone.";
            } catch (NoRouteToHostException e) {
                s.error = "No route to the phone. Is the tablet on the phone's hotspot?";
            } catch (IOException e) {
                String m = String.valueOf(e.getMessage());
                if (m.contains("EPERM")) {
                    s.error = "Android blocked this app's network access. Open App info and "
                            + "check permissions, or restart the tablet.";
                } else if ("forbidden".equals(m)) {
                    s.error = "Wrong pairing code. Check the code shown on the phone.";
                } else {
                    s.error = "Couldn't read the phone's reply (" + m + ").";
                }
            }
        }
        return s;
    }

    private JSONObject query(String host) throws IOException {
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(host, Cfg.PORT), 1500);
            sock.setSoTimeout(2000);
            OutputStream out = sock.getOutputStream();
            out.write(("GET /s?c=" + Cfg.code(this) + " HTTP/1.0\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = sock.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int r;
            while ((r = in.read(buf)) > 0 && bo.size() < 4096) {
                bo.write(buf, 0, r);
            }
            String resp = bo.toString("ISO-8859-1");
            if (resp.startsWith("HTTP/1.0 403")) {
                throw new IOException("forbidden");
            }
            int i = resp.indexOf("\r\n\r\n");
            if (!resp.startsWith("HTTP/1.0 200") || i < 0) {
                throw new IOException("bad reply");
            }
            return new JSONObject(resp.substring(i + 4));
        } catch (JSONException e) {
            throw new IOException("bad reply");
        }
    }

    /** UI thread. Only touches views that actually need to change. */
    private void show(Snap s) {
        if (!polling || mBat == null) {
            return;
        }
        if (s.phone != null) {
            fails = 0;
            lastPhone = s.phone;
            lastOk = s.at;
        } else {
            fails++;
        }
        boolean stale = s.phone == null && fails >= 2;

        // Tablet Wi-Fi (read locally, always fresh)
        if (s.wifiOk) {
            StringBuilder sub = new StringBuilder();
            sub.append(s.wRssi).append(" dBm");
            if (s.wLink > 0) {
                sub.append(" · ").append(s.wLink).append(" Mbps");
            }
            if (s.wFreq > 0) {
                sub.append(" · ").append(s.wFreq >= 4900 ? "5 GHz" : "2.4 GHz");
            }
            mWifi.set("Tablet Wi-Fi to phone", quality(s.wLvl), sub.toString(),
                    s.wLvl, -1f, levelColor(s.wLvl));
        } else {
            mWifi.set("Tablet Wi-Fi to phone", "Not connected", "join the phone's hotspot",
                    -1, -1f, GREY);
        }

        // Phone battery and cell (last values the phone sent)
        if (lastPhone != null) {
            int bat = lastPhone.optInt("bat", -1);
            boolean chg = lastPhone.optBoolean("chg", false);
            StringBuilder bsub = new StringBuilder(chg ? "Charging" : "On battery");
            if (lastPhone.has("tmp")) {
                bsub.append(String.format(Locale.US, " · %.0f°C", lastPhone.optDouble("tmp")));
            }
            int bcol = stale ? GREY : (bat >= 50 ? GREEN : (bat >= 20 ? AMBER : RED));
            mBat.set("Phone battery", bat >= 0 ? bat + "%" : "--", bsub.toString(),
                    -1, bat >= 0 ? bat / 100f : 0f, bcol);

            int lvl = lastPhone.optInt("lvl", -1);
            StringBuilder csub = new StringBuilder(lastPhone.optString("net", "?"));
            if (lastPhone.has("dbm")) {
                csub.append(" · ").append(lastPhone.optInt("dbm")).append(" dBm");
            }
            mCell.set("Phone cell signal", quality(lvl), csub.toString(),
                    lvl, -1f, stale ? GREY : levelColor(lvl));
        }

        // Status line
        DateFormat tf = DateFormat.getTimeInstance(DateFormat.MEDIUM);
        if (s.phone != null) {
            tStatus.setText("Connected · updated " + tf.format(new Date(s.at)));
            tStatus.setTextColor(DIM);
        } else {
            String extra = lastPhone != null
                    ? " Showing the last values from " + tf.format(new Date(lastOk)) + "."
                    : "";
            tStatus.setText(s.error + extra);
            tStatus.setTextColor(AMBER);
        }
    }

    private static String quality(int lvl) {
        switch (lvl) {
            case 4:
                return "Excellent";
            case 3:
                return "Good";
            case 2:
                return "Fair";
            case 1:
                return "Poor";
            case 0:
                return "None";
            default:
                return "--";
        }
    }

    private static int levelColor(int lvl) {
        if (lvl >= 3) {
            return GREEN;
        }
        if (lvl == 2) {
            return AMBER;
        }
        if (lvl >= 0) {
            return RED;
        }
        return GREY;
    }

    // ---------------------------------------------------------------- small UI helpers

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        return p;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }
}
