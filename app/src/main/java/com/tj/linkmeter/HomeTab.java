package com.tj.linkmeter;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The main screen: three meters, refreshed only while this tab is on screen.
 * When the tablet screen turns off or another tab opens, the worker thread is
 * shut down completely.
 */
final class HomeTab extends Tab {
    private MeterView mBat;
    private MeterView mCell;
    private MeterView mWifi;
    private TextView tStatus;
    private android.widget.Button startHint;
    private TextView tDetail;

    private HandlerThread worker;
    private volatile Handler bg;
    private volatile boolean polling;
    private volatile int fails;
    private JSONObject lastPhone;
    private long lastOk;

    /** What one poll found. Built on the worker thread, shown on the UI thread. */
    private static final class Snap {
        boolean wifiOk;
        int wRssi;
        int wLvl = -1;
        int wLink;
        int wFreq;
        JSONObject phone;
        String error = "";
        boolean justPaired;
        boolean onWifi;
        String host = "";
        String how = "";
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
            act.ui.post(() -> show(s));
            if (polling && bg == h) {
                h.postDelayed(this, s.justPaired ? 300L : delayMs());
            }
        }
    };

    HomeTab(MainActivity a) {
        super(a);
    }

    @Override
    String title() {
        return "Home";
    }

    @Override
    Icons.Kind icon() {
        return Icons.Kind.HOUSE;
    }

    @Override
    View build() {
        LinearLayout col = Ui.column(act);
        col.addView(Ui.text(act, "LinkMeter", 22, Ui.FG, true));
        tStatus = Ui.text(act, "Starting…", 14, Ui.DIM, false);
        col.addView(tStatus, Ui.lp(act, 4));
        tDetail = Ui.text(act, "", 11, Ui.DIM, false);
        col.addView(tDetail, Ui.lp(act, 2));
        // When the phone does not answer (it has just restarted): one tap to the card that starts everything again.
        startHint = Ui.button(act, "Phone not answering? Start it after a restart", v -> act.openTab("Control"));
        startHint.setVisibility(View.GONE);
        col.addView(startHint, Ui.lp(act, 4));

        mBat = new MeterView(act);
        mCell = new MeterView(act);
        mWifi = new MeterView(act);
        col.addView(mBat, Ui.lp(act, 12));
        col.addView(mCell, Ui.lp(act, 10));
        col.addView(mWifi, Ui.lp(act, 10));
        mBat.set("Phone battery", "--", "waiting for the phone", -1, 0f, Ui.GREY);
        mCell.set("Phone cell signal", "--", "waiting for the phone", -1, -1f, Ui.GREY);
        mWifi.set("Tablet Wi-Fi to phone", "--", "checking", -1, -1f, Ui.GREY);
        if (lastPhone != null) {
            paintPhone(false);
        }
        return Ui.scroll(act, col);
    }

    @Override
    void onShow() {
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

    @Override
    void onHide() {
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
        long base = Cfg.intervalMs(act);
        // After a few misses (phone off, hotspot off) ask less often.
        return fails >= 3 ? Math.max(base, 15000L) : base;
    }

    /** Called when the interval setting changes while this tab is showing. */
    void pollNow() {
        Handler h = bg;
        if (polling && h != null) {
            h.removeCallbacks(pollTask);
            h.post(pollTask);
        }
    }

    /** Runs on the worker thread. */
    private Snap poll() {
        Snap s = new Snap();
        s.at = System.currentTimeMillis();
        // The tablet's own Wi-Fi strength. Shown whenever Android gives a real reading,
        // even if it won't say which network it is (some tablets hide that).
        try {
            WifiManager wm = (WifiManager) act.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            if (wi != null) {
                int rssi = wi.getRssi();
                if (rssi > -127 && rssi < 0) {
                    s.wifiOk = true;
                    s.wRssi = rssi;
                    s.wLink = wi.getLinkSpeed();
                    s.wFreq = wi.getFrequency();
                    s.wLvl = WifiManager.calculateSignalLevel(rssi, 5);
                }
            }
        } catch (RuntimeException ignored) {
            // leave the strength meter on "not reported"
        }
        s.onWifi = s.wifiOk || Net.wifi(act) != null;

        // Try the phone whenever an address is known, whatever the checks above said.
        String host = Net.phoneHost(act);
        s.host = host;
        s.how = Net.how;

        if (host.isEmpty()) {
            s.error = s.onWifi
                    ? "Can't find the phone's address. Type it in Settings."
                    : "Tablet isn't on Wi-Fi. Join the phone's hotspot.";
        } else {
            String at = " (" + host + ")";
            try {
                String token = Cfg.token(act);
                Client.Resp r = Client.call(host, "GET", "/s", token, 2500, 3500);
                if (r.code == 401) {
                    Client.Resp p = Client.call(host, "POST", "/pair", token, 2500, 3500);
                    if (p.code == 200) {
                        s.justPaired = true;
                        s.error = "Paired with the phone. Reading status…";
                    } else {
                        s.error = "The phone is reachable but not pairing yet. On the phone tap "
                                + "Pair tablet, then wait a few seconds.";
                    }
                } else if (r.code == 200) {
                    s.phone = new JSONObject(r.body);
                } else {
                    s.error = "The phone replied with an error (" + r.code + ")" + at + ".";
                }
            } catch (SocketTimeoutException e) {
                s.error = "No answer from" + at + ". Is the phone's hotspot on and LinkMeter "
                        + "running on the phone?";
            } catch (ConnectException e) {
                s.error = "The phone" + at + " refused the connection. Open LinkMeter on the "
                        + "phone and tap Start.";
            } catch (NoRouteToHostException e) {
                s.error = "No route to" + at + ". Is the tablet on the phone's hotspot?";
            } catch (JSONException e) {
                s.error = "Couldn't read the phone's reply" + at + ".";
            } catch (IOException e) {
                String m = String.valueOf(e.getMessage());
                if (m.contains("EPERM")) {
                    s.error = "Android blocked this app's network access. Open App info "
                            + "(Settings tab) and check permissions, or restart the tablet.";
                } else {
                    s.error = "Couldn't talk to the phone" + at + ": " + m;
                }
            }
        }
        return s;
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
        } else if (!s.justPaired) {
            fails++;
        }
        boolean stale = s.phone == null && fails >= 2;

        if (s.wifiOk) {
            StringBuilder sub = new StringBuilder();
            sub.append(s.wRssi).append(" dBm");
            if (s.wLink > 0) {
                sub.append(" · ").append(s.wLink).append(" Mbps");
            }
            if (s.wFreq > 0) {
                sub.append(" · ").append(s.wFreq >= 4900 ? "5 GHz" : "2.4 GHz");
            }
            mWifi.set("Tablet Wi-Fi to phone", Ui.quality(s.wLvl), sub.toString(),
                    s.wLvl, -1f, Ui.levelColor(s.wLvl));
        } else if (s.onWifi) {
            mWifi.set("Tablet Wi-Fi to phone", "Connected", "this tablet doesn't report the signal",
                    -1, -1f, Ui.GREY);
        } else {
            mWifi.set("Tablet Wi-Fi to phone", "Not connected", "join the phone's hotspot",
                    -1, -1f, Ui.GREY);
        }
        tDetail.setText(s.host.isEmpty() ? "No phone address found yet."
                : "Phone address " + s.host + " (" + s.how + ")"
                + (s.wifiOk ? " · tablet Wi-Fi " + s.wRssi + " dBm" : ""));

        if (lastPhone != null) {
            paintPhone(stale);
        }

        DateFormat tf = DateFormat.getTimeInstance(DateFormat.MEDIUM);
        if (s.phone != null) {
            tStatus.setText("Connected · updated " + tf.format(new Date(s.at)));
            tStatus.setTextColor(Ui.DIM);
            startHint.setVisibility(View.GONE);
        } else {
            startHint.setVisibility(View.VISIBLE);
            String extra = lastPhone != null
                    ? " Showing the last values from " + tf.format(new Date(lastOk)) + "."
                    : "";
            tStatus.setText(s.error + extra);
            tStatus.setTextColor(Ui.AMBER);
        }
    }

    private void paintPhone(boolean stale) {
        int bat = lastPhone.optInt("bat", -1);
        boolean chg = lastPhone.optBoolean("chg", false);
        StringBuilder bsub = new StringBuilder(chg ? "Charging" : "On battery");
        if (lastPhone.has("tmp")) {
            bsub.append(String.format(Locale.US, " · %.0f°C", lastPhone.optDouble("tmp")));
        }
        int bcol = stale ? Ui.GREY : (bat >= 50 ? Ui.GREEN : (bat >= 20 ? Ui.AMBER : Ui.RED));
        mBat.set("Phone battery", bat >= 0 ? bat + "%" : "--", bsub.toString(),
                -1, bat >= 0 ? bat / 100f : 0f, bcol);

        int lvl = lastPhone.optInt("lvl", -1);
        StringBuilder csub = new StringBuilder(lastPhone.optString("net", "?"));
        if (lastPhone.has("dbm")) {
            csub.append(" · ").append(lastPhone.optInt("dbm")).append(" dBm");
        }
        mCell.set("Phone cell signal", Ui.quality(lvl), csub.toString(),
                lvl, -1f, stale ? Ui.GREY : Ui.levelColor(lvl));
    }
}
