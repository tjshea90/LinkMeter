package com.tj.linkmeter;

import android.app.AlertDialog;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The main screen: how the phone is reached right now (its hotspot, Bluetooth, or not at all), three meters, and the switches
 * used most (hotspot, the phone's screen, Standby). Refreshed only while this tab is on screen; when the tablet's screen turns
 * off or another tab opens, the worker thread is shut down completely.
 */
final class HomeTab extends Tab {
    private MeterView mBat;
    private MeterView mCell;
    private MeterView mWifi;
    private TextView tStatus;
    private TextView chip;
    private LinearLayout banner;
    private TextView bannerTitle;
    private TextView bannerText;
    private Button bannerMain;
    private Button bannerOther;
    private Ui.Fact fHotspot;
    private Ui.Fact fScreen;
    private Ui.Fact fSaver;
    private Button bHotspot;
    private Button bScreen;
    private Button bStandby;
    private TextView tAction;

    private HandlerThread worker;
    private volatile Handler bg;
    private volatile boolean polling;
    private volatile int fails;
    private JSONObject lastPhone;
    private String lastVia = "";
    private long lastOk;
    /** What the last button did, kept across a rebuild of the views. */
    private String actionMsg = "";
    private boolean actionOk = true;
    /** A switch was just pressed: until the phone has answered, the buttons wait. */
    private boolean busy;

    /** What one poll found. Built on the worker thread, shown on the UI thread. */
    private static final class Snap {
        boolean wifiOk;
        int wRssi;
        int wLvl = -1;
        int wLink;
        int wFreq;
        JSONObject phone;
        String via = "";
        String error = "";
        boolean justPaired;
        boolean onWifi;
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
                h.postDelayed(this, s.justPaired ? 300L : delayMs(s.via));
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

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.title(act, "LinkMeter"), Ui.weight());
        chip = Ui.chip(act, "Looking…", Ui.GREY);
        head.addView(chip);
        col.addView(head);
        tStatus = Ui.text(act, "Looking for the phone…", 13, Ui.DIM, false);
        col.addView(tStatus, Ui.lp(act, 2));

        // Only shown when the phone is not reached through its hotspot: what that means and the one thing to do about it.
        banner = Ui.card(act);
        bannerTitle = Ui.heading(act, "");
        banner.addView(bannerTitle);
        bannerText = Ui.hint(act, "");
        banner.addView(bannerText, Ui.lp(act, 2));
        bannerMain = Ui.primary(act, "", null);
        bannerOther = Ui.button(act, "", null);
        banner.addView(Ui.row(act, bannerMain, bannerOther), Ui.lp(act, 8));
        banner.setVisibility(View.GONE);
        col.addView(banner, Ui.lp(act, 10));

        mBat = new MeterView(act);
        mCell = new MeterView(act);
        mWifi = new MeterView(act);
        col.addView(mBat, Ui.lp(act, 10));
        col.addView(mCell, Ui.lp(act, 8));
        col.addView(mWifi, Ui.lp(act, 8));
        mBat.set("Phone battery", "--", "waiting for the phone", -1, 0f, Ui.GREY);
        mCell.set("Phone cell signal", "--", "waiting for the phone", -1, -1f, Ui.GREY);
        mWifi.set("Tablet Wi-Fi to phone", "--", "checking", -1, -1f, Ui.GREY);

        LinearLayout sw = Ui.card(act, "Switches", null);
        fHotspot = new Ui.Fact(act, "Phone's hotspot");
        fScreen = new Ui.Fact(act, "Phone's screen");
        fSaver = new Ui.Fact(act, "Battery saver");
        sw.addView(fHotspot.view, Ui.lp(act, 8));
        sw.addView(fScreen.view, Ui.lp(act, 6));
        sw.addView(fSaver.view, Ui.lp(act, 6));
        bHotspot = Ui.button(act, "Hotspot", v -> hotspotPressed());
        bScreen = Ui.button(act, "Screen", v -> screenPressed());
        bStandby = Ui.button(act, "Standby", v -> standbyPressed());
        sw.addView(Ui.row(act, bHotspot, bScreen, bStandby), Ui.lp(act, 10));
        tAction = Ui.hint(act, actionMsg);
        tAction.setTextColor(actionOk ? Ui.DIM : Ui.AMBER);
        tAction.setVisibility(actionMsg.isEmpty() ? View.GONE : View.VISIBLE);
        sw.addView(tAction, Ui.lp(act, 6));
        sw.addView(Ui.hint(act, "Standby switches the hotspot and the phone's screen off, so the phone lasts for days; "
                + "\"Hotspot on\" wakes it again over Bluetooth. More switches are in the Phone tab."), Ui.lp(act, 6));
        col.addView(sw, Ui.lp(act, 10));

        paintPhone(lastPhone == null);
        paintSwitches(false);
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

    private long delayMs(String via) {
        long base = Cfg.intervalMs(act);
        // Over Bluetooth, and after a few misses (phone off, out of range), ask less often: it spares both batteries.
        return (fails >= 3 || Reach.BLUETOOTH.equals(via)) ? Math.max(base, 15000L) : base;
    }

    /** Asks the phone again right away (a switch was pressed, or a setting changed). */
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

        Reach.Result res = Call.reach(act).call("GET", "/s", 3500);
        if (res.resp == null) {
            s.error = res.error;
            return s;
        }
        s.via = res.via;
        if (res.resp.code == 401) {
            if (Reach.BLUETOOTH.equals(res.via)) {
                s.error = "The phone does not know this tablet. Pair them over the phone's hotspot first (on the phone: Pair tablet).";
                return s;
            }
            // Pairing only ever happens over the hotspot, and only while the phone's Pair window is open.
            try {
                Client.Resp p = Client.call(Net.phoneHost(act), "POST", "/pair", Cfg.token(act), 2500, 3500);
                if (p.code == 200) {
                    s.justPaired = true;
                    s.error = "Paired with the phone. Reading status…";
                } else {
                    s.error = "The phone is reachable but not pairing yet. On the phone tap Pair tablet, then wait a few seconds.";
                }
            } catch (IOException e) {
                s.error = "The phone is reachable but pairing did not get through. On the phone tap Pair tablet.";
            }
        } else if (res.resp.code == 200) {
            try {
                s.phone = new JSONObject(res.resp.body);
            } catch (JSONException e) {
                s.error = "Couldn't read the phone's reply.";
            }
        } else {
            s.error = "The phone replied with an error (" + res.resp.code + ").";
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
            lastVia = s.via;
            lastOk = s.at;
            if (Reach.WIFI.equals(s.via)) {
                // the phone tells where its Bluetooth is, so the tablet can call it when the hotspot is off
                String mac = s.phone.optString("btmac", "");
                if (BtAddr.valid(mac) && !mac.equals(Cfg.btMac(act))) {
                    Cfg.setBtMac(act, mac);
                }
                String name = s.phone.optString("btname", "");
                if (!name.isEmpty() && !name.equals(Cfg.getStr(act, "btName", ""))) {
                    Cfg.setStr(act, "btName", name);
                }
            }
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
            mWifi.set("Tablet Wi-Fi to phone", "Not connected",
                    s.phone != null ? "the phone's hotspot is off" : "join the phone's hotspot", -1, -1f, Ui.GREY);
        }

        paintPhone(stale);

        DateFormat tf = DateFormat.getTimeInstance(DateFormat.MEDIUM);
        if (s.phone != null) {
            boolean bt = Reach.BLUETOOTH.equals(s.via);
            Ui.paintChip(chip, bt ? "Bluetooth" : "Hotspot", bt ? Ui.ACCENT : Ui.GREEN);
            tStatus.setText("Connected · updated " + tf.format(new Date(s.at)));
            tStatus.setTextColor(Ui.DIM);
        } else {
            Ui.paintChip(chip, s.justPaired ? "Pairing" : "Not connected", s.justPaired ? Ui.GREEN : Ui.AMBER);
            String extra = lastPhone != null
                    ? " Showing the last values from " + tf.format(new Date(lastOk)) + "."
                    : "";
            tStatus.setText(s.error + extra);
            tStatus.setTextColor(Ui.AMBER);
        }
        paintBanner(s);
        paintSwitches(s.phone != null);
    }

    /** The card under the title: only when the phone is not reached through its hotspot. */
    private void paintBanner(Snap s) {
        if (s.phone != null && Reach.BLUETOOTH.equals(s.via)) {
            boolean hs = s.phone.optBoolean("hs", false);
            banner.setVisibility(View.VISIBLE);
            bannerOther.setVisibility(View.GONE);
            if (hs) {
                bannerTitle.setText("The hotspot is on, the tablet has not joined it yet");
                bannerText.setText("The tablet joins the phone's hotspot by itself, usually within half a minute. Until then "
                        + "the phone is reached over Bluetooth. If it does not join, open the tablet's Wi-Fi settings.");
                bannerMain.setText("Ask again");
                bannerMain.setOnClickListener(v -> pollNow());
                bannerMain.setEnabled(true);
            } else {
                bannerTitle.setText("The phone's hotspot is off");
                bannerText.setText("The phone is reached over Bluetooth, which carries the switches and the status but no internet, "
                        + "files or screen.");
                bannerMain.setText("Turn the hotspot on");
                bannerMain.setOnClickListener(v -> send("hotspot_on", "Switching the hotspot on", 45000));
                bannerMain.setEnabled(!busy && s.phone.optInt("priv", Priv.READY) == Priv.READY);
            }
        } else if (s.phone == null && !s.justPaired && (lastPhone == null || fails >= 2)) {
            // at once when the phone has not been heard at all; after two misses when it had (one lost answer is not news)
            banner.setVisibility(View.VISIBLE);
            bannerTitle.setText("Can't reach the phone");
            boolean linkReady = BtLink.ready(act);
            bannerText.setText(linkReady
                    ? "Neither the hotspot nor Bluetooth got through. Is the phone on, in range, with Bluetooth on? After a restart "
                    + "of the phone LinkMeter starts only once the phone has been unlocked."
                    : "The tablet is not on the phone's hotspot, and the Bluetooth link (which reaches the phone while its "
                    + "hotspot is off) is not set up yet. Switch the hotspot on at the phone once, then set the link up in Settings.");
            bannerMain.setText("Ask again");
            bannerMain.setOnClickListener(v -> pollNow());
            bannerMain.setEnabled(true);
            bannerOther.setVisibility(View.VISIBLE);
            bannerOther.setText("After a phone restart");
            bannerOther.setOnClickListener(v -> act.openTab("Phone"));
        } else if (s.phone != null && s.phone.has("bt") && !BtLink.ready(act) && BtLink.enabled(act)) {
            // on the hotspot, and the way to reach the phone WITHOUT the hotspot is not set up yet: say that it exists
            banner.setVisibility(View.VISIBLE);
            bannerTitle.setText("Reach the phone when its hotspot is off");
            bannerText.setText("With the Bluetooth link the tablet can switch the phone's hotspot on and off, so the hotspot only "
                    + "runs while you use it. Setting it up takes one tap and no touch on the phone.");
            bannerMain.setText("Set it up");
            bannerMain.setOnClickListener(v -> act.openTab("Settings"));
            bannerMain.setEnabled(true);
            bannerOther.setVisibility(View.VISIBLE);
            bannerOther.setText("Not now");
            bannerOther.setOnClickListener(v -> {
                BtLink.setEnabled(act, false); // the same switch as in Settings: off means "do not use or offer it"
                banner.setVisibility(View.GONE);
            });
        } else {
            banner.setVisibility(View.GONE);
        }
    }

    private void paintPhone(boolean stale) {
        if (lastPhone == null || mBat == null) {
            return;
        }
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

    /** The three lines and three buttons of the Switches card. reachable: the phone answered the last question. */
    private void paintSwitches(boolean reachable) {
        if (fHotspot == null) {
            return;
        }
        JSONObject p = lastPhone;
        boolean known = p != null && p.has("hs"); // a phone app from before these switches does not say
        if (p == null) {
            fHotspot.set("--", Ui.DIM);
            fScreen.set("--", Ui.DIM);
            fSaver.set("--", Ui.DIM);
        } else if (!known) {
            fHotspot.set("update the phone's app", Ui.AMBER);
            fScreen.set("--", Ui.DIM);
            fSaver.set(p.optBoolean("eco", false) ? "on" : "off", Ui.FG);
        } else {
            boolean hs = p.optBoolean("hs", false);
            boolean scr = p.optBoolean("scr", false);
            fHotspot.set(hs ? "on" : "off", hs ? Ui.GREEN : Ui.DIM);
            fScreen.set(scr ? "on" : "off", scr ? Ui.FG : Ui.DIM);
            fSaver.set(p.optBoolean("eco", false) ? "on" : (p.optBoolean("ecoAuto", true) ? "off (automatic)" : "off"), Ui.FG);
        }
        boolean hs = p != null && p.optBoolean("hs", !Reach.BLUETOOTH.equals(lastVia));
        boolean scr = p != null && p.optBoolean("scr", true);
        bHotspot.setText(hs ? "Hotspot off" : "Hotspot on");
        bScreen.setText(scr ? "Sleep screen" : "Wake screen");
        boolean helper = p != null && p.optInt("priv", Priv.READY) == Priv.READY;
        boolean can = reachable && !busy && helper;
        bHotspot.setEnabled(can);
        bScreen.setEnabled(can);
        bStandby.setEnabled(can && (hs || scr));
        if (reachable && p != null && !helper && actionMsg.isEmpty()) {
            setAction("The switches need Shizuku on the phone, and it is not running (after a phone restart: Phone tab > "
                    + "Start everything).", false);
        }
    }

    private void setAction(String s, boolean ok) {
        actionMsg = s;
        actionOk = ok;
        if (tAction != null) {
            tAction.setText(s);
            tAction.setTextColor(ok ? Ui.DIM : Ui.AMBER);
            tAction.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    // ------------------------------------------------------------------------------------------------ the switches

    private void send(String action, String label, int readMs) {
        busy = true;
        paintSwitches(true);
        if (bannerMain != null) {
            bannerMain.setEnabled(false);
        }
        setAction(label + "…", true);
        Call.run(act, "POST", "/act?name=" + action, readMs, (ok, text, json) -> {
            busy = false;
            setAction(text, ok);
            paintSwitches(true);
            // look again now and a little later: a hotspot takes a few seconds to come up or go down
            pollNow();
            act.ui.postDelayed(this::pollNow, 4000);
        });
    }

    private void hotspotPressed() {
        boolean on = lastPhone != null && lastPhone.optBoolean("hs", !Reach.BLUETOOTH.equals(lastVia));
        if (!on) {
            send("hotspot_on", "Switching the hotspot on", 45000);
            return;
        }
        boolean link = BtLink.ready(act);
        new AlertDialog.Builder(act)
                .setTitle("Turn the hotspot off?")
                .setMessage(link
                        ? "The tablet loses its internet. The phone stays reachable over Bluetooth, so you can switch the hotspot "
                        + "back on from this screen."
                        : "The tablet loses its internet AND its connection to the phone: the Bluetooth link is not set up, so the "
                        + "hotspot would have to be switched on again at the phone. Set the link up first (Settings > Bluetooth link).")
                .setPositiveButton(link ? "Turn it off" : "Turn it off anyway", (d, w) -> send("hotspot_off", "Switching the hotspot off", 45000))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void screenPressed() {
        boolean on = lastPhone == null || lastPhone.optBoolean("scr", true);
        busy = true;
        paintSwitches(true);
        setAction(on ? "Putting the phone's screen to sleep…" : "Waking the phone's screen…", true);
        Call.run(act, "POST", on ? "/screen/off?nodoze=1" : "/screen/on", 20000, (ok, text, json) -> {
            busy = false;
            setAction(text, ok);
            paintSwitches(true);
            pollNow();
        });
    }

    private void standbyPressed() {
        boolean link = BtLink.ready(act);
        new AlertDialog.Builder(act)
                .setTitle("Put the phone on standby?")
                .setMessage("The phone's hotspot and its screen go off. The phone stays on and reachable for calls and texts, and "
                        + "uses very little battery. "
                        + (link ? "Wake it from this screen with \"Hotspot on\" (over Bluetooth)."
                        : "The Bluetooth link is NOT set up, so only the phone itself could switch the hotspot on again. Set the "
                        + "link up first (Settings > Bluetooth link)."))
                .setPositiveButton(link ? "Standby" : "Standby anyway", (d, w) -> send("standby", "Putting the phone on standby", 60000))
                .setNegativeButton("Cancel", null)
                .show();
    }
}
