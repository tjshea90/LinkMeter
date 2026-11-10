package com.tj.linkmeter;

import android.app.AlertDialog;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
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
 *
 * What this screen takes care never to do: say that a switch moved when the phone only answered "Sent" (it looks at the status
 * until that shows the result, {@link FollowUp}); promise that the phone can be reached again after its hotspot goes off without
 * having called it over Bluetooth a moment before; keep waking a phone on Standby with questions nobody reads
 * ({@link PollPace}); or switch the tablet's Bluetooth on just to read the status.
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
    private Button bHotspotOff;
    private LinearLayout unknownRow;
    private Button bScreen;
    private Button bStandby;
    private TextView tNeed;
    private TextView tAction;

    private HandlerThread worker;
    private volatile Handler bg;
    private volatile boolean polling;
    /** Guards the one rule of the polling chain: at most one run of pollTask is waiting in the worker's queue. */
    private final Object chain = new Object();
    /** Questions in a row that got no status. Written by the worker thread only. */
    private volatile int misses;
    /** When the user last did something on this screen (opened it, pressed a button), on the clock that never jumps. */
    private volatile long lastAction;
    /** The next question is the result of a press, so it may switch the tablet's Bluetooth on. A status read by itself may not. */
    private volatile boolean pressed;
    /** True while {@link #follow} is set: the worker then asks every few seconds. */
    private volatile boolean following;

    private JSONObject lastPhone;
    private String lastVia = "";
    private long lastOk;
    /** The phone answered the last question. */
    private boolean reachable;
    /** What the last button did, kept across a rebuild of the views. */
    private String actionMsg = "";
    private boolean actionOk = true;
    /** A switch was just pressed: until the phone has answered, the buttons wait. */
    private boolean busy;
    /** A switch was answered "Sent" (the phone acts after it answers): what to look for in the status, and since when. */
    private FollowUp follow;
    private long followSince;
    /** What the first hotspot button does when pressed. */
    private boolean hotspotButtonTurnsOff;

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
        /** When this question was begun (on the clock that never jumps): tells a look from before a switch from one after it. */
        long began;
        /** Why there is no status; null when there is one (or the tablet has just paired). */
        Trouble.Kind trouble;
        /** The phone was called over Bluetooth for this (answered or not). */
        boolean btTried;
        int misses;
        /** The tablet does not ask again by itself after this one (see {@link PollPace}). */
        boolean stopped;
    }

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            Handler h = bg;
            if (h == null || !polling) {
                return;
            }
            boolean press = pressed || following; // looking for the result of a switch is part of that press
            pressed = false;
            final Snap s = poll(press);
            if (s.phone != null) {
                misses = 0;
            } else if (!s.justPaired) {
                misses++;
            }
            s.misses = misses;
            long wait = s.justPaired ? 300L : PollPace.next(Cfg.intervalMs(act), s.btTried,
                    SystemClock.elapsedRealtime() - lastAction, misses, following);
            synchronized (chain) {
                // A press while this question was on its way has already queued the next one: that run carries the chain on.
                // Queuing another here would make two chains, each asking at the full rate, for as long as the tab is open.
                if (polling && bg == h && !h.hasCallbacks(this)) {
                    if (wait == PollPace.STOP) {
                        s.stopped = true;
                    } else {
                        h.postDelayed(this, wait);
                    }
                }
            }
            act.ui.post(() -> show(s));
        }
    };

    HomeTab(MainActivity a) {
        super(a);
    }

    @Override
    String title() {
        return HOME;
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

        // Only shown when it has something to say: the phone is not reached through its hotspot, or the way back to it is not ready.
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
        bStandby = Ui.button(act, "Standby", v -> confirmOff(true));
        sw.addView(Ui.row(act, bHotspot, bScreen, bStandby), Ui.lp(act, 10));
        // Only while the phone does not say whether its hotspot is on: the button above then switches it on, this one off.
        bHotspotOff = Ui.button(act, "Hotspot off", v -> confirmOff(false));
        unknownRow = new LinearLayout(act);
        unknownRow.setOrientation(LinearLayout.VERTICAL);
        unknownRow.addView(bHotspotOff);
        unknownRow.addView(Ui.hint(act, "The phone does not say whether its hotspot is on, so both hotspot buttons are here."),
                Ui.lp(act, 2));
        unknownRow.setVisibility(View.GONE);
        sw.addView(unknownRow, Ui.lp(act, 2));
        tNeed = Ui.hint(act, "");
        tNeed.setTextColor(Ui.AMBER);
        tNeed.setVisibility(View.GONE);
        sw.addView(tNeed, Ui.lp(act, 6));
        tAction = Ui.hint(act, actionMsg);
        tAction.setTextColor(actionOk ? Ui.DIM : Ui.AMBER);
        tAction.setVisibility(actionMsg.isEmpty() ? View.GONE : View.VISIBLE);
        sw.addView(tAction, Ui.lp(act, 6));
        sw.addView(Ui.hint(act, "Standby switches the phone's hotspot and screen off, so the phone uses much less battery. "
                + "\"Hotspot on\" wakes it again over Bluetooth, if that link works. More switches are in the " + PHONE
                + " tab."), Ui.lp(act, 6));
        col.addView(sw, Ui.lp(act, 10));

        paintPhone(lastPhone == null);
        paintSwitches();
        return Ui.scroll(act, col);
    }

    @Override
    void onShow() {
        if (polling) {
            return;
        }
        polling = true;
        misses = 0;
        pressed = false; // opening the screen is not a press: it does not switch the tablet's Bluetooth on
        lastAction = SystemClock.elapsedRealtime();
        worker = new HandlerThread("linkmeter-poll");
        worker.start();
        bg = new Handler(worker.getLooper());
        bg.post(pollTask);
    }

    @Override
    void onHide() {
        polling = false;
        reachable = false;
        Handler h = bg;
        bg = null;
        if (h != null) {
            h.removeCallbacksAndMessages(null);
        }
        if (worker != null) {
            worker.quitSafely();
            worker = null;
        }
        if (follow != null) {
            // nobody is looking any more: the phone is not asked on, and nothing is claimed about the switch
            endFollow("Sent. LinkMeter stopped looking for the result when this screen was left. The lines above show how the "
                    + "phone is now.", true);
        }
    }

    /** The user did something on this screen: the status is worth asking for again (see {@link PollPace}). */
    private void touched() {
        lastAction = SystemClock.elapsedRealtime();
    }

    /** Asks the phone again right away, because the user pressed something. */
    private void askNow() {
        touched();
        pressed = true;
        Handler h = bg;
        if (polling && h != null) {
            synchronized (chain) {
                h.removeCallbacks(pollTask);
                h.post(pollTask);
            }
        }
    }

    /** Runs on the worker thread. press: the user pressed something for this question. */
    private Snap poll(boolean press) {
        Snap s = new Snap();
        s.at = System.currentTimeMillis();
        s.began = SystemClock.elapsedRealtime();
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

        Reach.Result res = Call.reach(act).call("GET", "/s", 3500, press);
        s.btTried = res.btTried;
        if (res.resp == null) {
            s.error = res.error;
            s.trouble = Trouble.of(false, 0, res.asked, res.btOff);
            return s;
        }
        s.via = res.via;
        if (res.resp.code == 401) {
            s.trouble = Trouble.of(true, 401, true, false);
            if (Reach.BLUETOOTH.equals(res.via)) {
                s.error = "The phone does not know this tablet. Pair them over the phone's hotspot first (on the phone: Pair tablet).";
                return s;
            }
            // Pairing only ever happens over the hotspot, and only while the phone's Pair window is open.
            try {
                Client.Resp p = Client.call(Net.phoneHost(act), "POST", "/pair", Cfg.token(act), 2500, 3500);
                if (p.code == 200) {
                    s.justPaired = true;
                    s.trouble = null;
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
                s.trouble = Trouble.of(true, 200, true, false);
            }
        } else {
            s.error = "The phone replied with an error (" + res.resp.code + ").";
            s.trouble = Trouble.of(true, res.resp.code, true, false);
        }
        return s;
    }

    /** UI thread. Only touches views that actually need to change. */
    private void show(Snap s) {
        if (!polling || mBat == null) {
            return;
        }
        reachable = s.phone != null;
        if (s.phone != null) {
            lastPhone = s.phone;
            lastVia = s.via;
            lastOk = s.at;
            if (Reach.WIFI.equals(s.via)) {
                BtLink.heardOverHotspot(act, s.phone); // where its Bluetooth is, and what it says about its side of the link
            }
        }
        boolean stale = s.phone == null && s.misses >= 2;

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
            // what the phone itself says about its hotspot, when it answered another way; nothing is guessed from the silence
            Boolean hs = s.phone == null ? null : Call.flag(s.phone, "hs");
            mWifi.set("Tablet Wi-Fi to phone", "Not connected", s.phone == null ? "join the phone's hotspot"
                    : hs == null ? "the tablet is not on the phone's hotspot"
                    : hs ? "the hotspot is on, the tablet has not joined it" : "the phone's hotspot is off", -1, -1f, Ui.GREY);
        }

        paintPhone(stale || s.stopped);

        DateFormat tf = DateFormat.getTimeInstance(DateFormat.MEDIUM);
        if (s.phone != null) {
            boolean bt = Reach.BLUETOOTH.equals(s.via);
            Ui.paintChip(chip, bt ? "Bluetooth" : "Hotspot", bt ? Ui.ACCENT : Ui.GREEN);
            tStatus.setText(s.stopped
                    ? "Last asked at " + tf.format(new Date(s.at)) + ". LinkMeter has stopped asking by itself, because every "
                    + "question over Bluetooth wakes the phone. Press Ask again for a fresh reading."
                    : "Connected · updated " + tf.format(new Date(s.at)));
            tStatus.setTextColor(Ui.DIM);
        } else {
            Ui.paintChip(chip, s.justPaired ? "Pairing" : "Not connected", s.justPaired ? Ui.GREEN : Ui.AMBER);
            String extra = lastPhone != null
                    ? " Showing the last values from " + tf.format(new Date(lastOk)) + "."
                    : "";
            tStatus.setText(s.error + extra + (s.stopped ? " LinkMeter has stopped asking by itself." : ""));
            tStatus.setTextColor(Ui.AMBER);
        }
        // (before the card and the buttons are painted: when this look settles a switch, they are free again)
        if (follow != null && !s.justPaired && s.began >= followSince) {
            FollowUp.Outcome o = follow.look(Call.seen(s.phone));
            if (o == null && SystemClock.elapsedRealtime() - followSince >= FollowUp.GIVE_UP_MS) {
                o = follow.giveUp();
            }
            if (o != null) {
                endFollow(o.text, o.ok);
            }
        }
        paintBanner(s);
        paintSwitches();
    }

    /** Why the switches cannot be used although the phone answers; "" when they can. p: the phone's last status. */
    private static String needs(JSONObject p) {
        int priv = p.optInt("priv", Priv.READY);
        if (priv == Priv.READY) {
            return "";
        }
        return priv == Priv.NO_PERM
                ? "The switches need Shizuku on the phone, and LinkMeter is not allowed in it yet. Open LinkMeter on the phone "
                + "and tap Allow in Shizuku."
                : "The switches need Shizuku on the phone, and it is not running. After a phone restart: " + PHONE
                + " tab > Start everything.";
    }

    /**
     * Why the Bluetooth link cannot be relied on, for this screen: LinkState's words, and where the button they name is (the
     * words are written for the Settings card, where the button is right below them).
     */
    private String linkWords() {
        String w = BtLink.words(act);
        return w.contains("Press ") ? w + " That button is in " + SETTINGS + ", under Bluetooth link." : w;
    }

    /** The card under the title: only when it has something to say. */
    private void paintBanner(Snap s) {
        if (s.phone != null && Reach.BLUETOOTH.equals(s.via)) {
            Boolean hs = Call.flag(s.phone, "hs");
            banner.setVisibility(View.VISIBLE);
            if (hs != null && hs) {
                bannerTitle.setText("The hotspot is on, the tablet has not joined it yet");
                bannerText.setText("The tablet joins the phone's hotspot by itself, usually within half a minute. Until then "
                        + "the phone is reached over Bluetooth. If it does not join, open the tablet's Wi-Fi settings.");
                bannerMain.setText("Ask again");
                bannerMain.setOnClickListener(v -> askNow());
                bannerMain.setEnabled(true);
                bannerOther.setVisibility(View.GONE);
            } else {
                String need = needs(s.phone);
                bannerTitle.setText(hs == null ? "The phone is reached over Bluetooth" : "The phone's hotspot is off");
                bannerText.setText((hs == null
                        ? "The phone does not say whether its hotspot is on. Bluetooth carries the switches and the status but "
                        + "no internet, files or screen. Switching the hotspot on does no harm if it is on already."
                        : "The phone is reached over Bluetooth, which carries the switches and the status but no internet, "
                        + "files or screen.") + (need.isEmpty() ? "" : " " + need));
                bannerMain.setText("Turn the hotspot on");
                bannerMain.setOnClickListener(v -> send("hotspot_on", "Switching the hotspot on", 45000));
                bannerMain.setEnabled(!busy && need.isEmpty());
                // over Bluetooth the tablet asks seldom, and stops by itself: the button to ask now is always at hand
                bannerOther.setVisibility(View.VISIBLE);
                bannerOther.setText("Ask again");
                bannerOther.setOnClickListener(v -> askNow());
            }
            return;
        }
        Trouble.Kind k = s.trouble;
        // At once when the phone has not been heard at all, or when the cause is plain (it answered, or nothing could be
        // tried); after two misses when it had been heard and merely did not answer (one lost answer is not news).
        if (k != null && (lastPhone == null || k != Trouble.Kind.NO_ANSWER || s.misses >= 2)) {
            boolean linkOk = BtLink.state(act) == LinkState.Kind.READY;
            String text = Trouble.text(k, Reach.BLUETOOTH.equals(s.via), s.btTried, linkOk ? "" : linkWords());
            if (k == Trouble.Kind.PHONE_ERROR) {
                text += " The " + PHONE + " tab has \"Update phone from this tablet\".";
            }
            banner.setVisibility(View.VISIBLE);
            bannerTitle.setText(Trouble.title(k));
            bannerText.setText(text);
            bannerMain.setText(Trouble.button(k));
            bannerMain.setOnClickListener(v -> askNow()); // a press: this one may switch the tablet's Bluetooth on
            bannerMain.setEnabled(true);
            if (Trouble.offersRestart(k)) {
                bannerOther.setVisibility(View.VISIBLE);
                bannerOther.setText("After a phone restart");
                bannerOther.setOnClickListener(v -> act.openTab(PHONE));
            } else {
                bannerOther.setVisibility(View.GONE);
            }
            return;
        }
        LinkState.Kind link = BtLink.state(act);
        if (s.phone != null && s.phone.has("bt") && link != LinkState.Kind.READY && link != LinkState.Kind.OFF_HERE
                && link != LinkState.Kind.NO_BLUETOOTH && !BtLink.offerHidden(act)) {
            // On the hotspot, and the way to reach the phone WITHOUT the hotspot is not there: say so while it is easy to mend.
            // (Not when the link is switched off here, which is the user's choice, nor to a phone app from before the link.)
            banner.setVisibility(View.VISIBLE);
            if (link == LinkState.Kind.NO_ADDRESS || link == LinkState.Kind.NOT_ALLOWED) {
                bannerTitle.setText("Reach the phone when its hotspot is off");
                bannerText.setText("With the Bluetooth link the tablet can switch the phone's hotspot on and off, so the hotspot "
                        + "only runs while you use it. Setting it up is one button in " + SETTINGS + ".");
                bannerMain.setText("Set it up");
            } else {
                bannerTitle.setText("The Bluetooth link is not ready");
                bannerText.setText(linkWords() + " Until it works, a hotspot that is switched off can only be switched on "
                        + "again at the phone.");
                bannerMain.setText("Open " + SETTINGS);
            }
            bannerMain.setOnClickListener(v -> act.openTab(SETTINGS));
            bannerMain.setEnabled(true);
            bannerOther.setVisibility(View.VISIBLE);
            bannerOther.setText("Not now");
            bannerOther.setOnClickListener(v -> {
                BtLink.hideOffer(act); // hides this card and nothing else: the link's own switch is in Settings and stays as it is
                banner.setVisibility(View.GONE);
            });
            return;
        }
        banner.setVisibility(View.GONE);
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

    /** The three lines and the buttons of the Switches card, from the phone's last status. */
    private void paintSwitches() {
        if (fHotspot == null) {
            return;
        }
        JSONObject p = lastPhone;
        // A phone app from before these switches says neither "hs" nor "bt". A newer one always says "bt", and leaves "hs" out
        // only when it cannot tell whether its hotspot is on: that is "not known", never "off".
        boolean newApp = p != null && p.has("bt");
        Boolean hs = p == null ? null : Call.flag(p, "hs");
        Boolean scr = p == null ? null : Call.flag(p, "scr");
        if (p == null) {
            fHotspot.set("--", Ui.DIM);
            fScreen.set("--", Ui.DIM);
            fSaver.set("--", Ui.DIM);
        } else {
            // (values from a phone that no longer answers are the last ones seen: shown, but not lit)
            if (hs != null) {
                fHotspot.set(hs ? "on" : "off", hs && reachable ? Ui.GREEN : Ui.DIM);
            } else {
                fHotspot.set(newApp ? "not known" : "update the phone's app", newApp ? Ui.DIM : Ui.AMBER);
            }
            if (scr != null) {
                fScreen.set(scr ? "on" : "off", scr && reachable ? Ui.FG : Ui.DIM);
            } else {
                fScreen.set("--", Ui.DIM);
            }
            fSaver.set(p.optBoolean("eco", false) ? "on" : (p.optBoolean("ecoAuto", true) ? "off (automatic)" : "off"),
                    reachable ? Ui.FG : Ui.DIM);
        }
        // Not known (a newer phone app that cannot tell): both hotspot buttons. An older app: the old guess, on when the
        // phone is reached through a network.
        boolean both = hs == null && newApp;
        hotspotButtonTurnsOff = !both && (hs != null ? hs : p != null && !Reach.BLUETOOTH.equals(lastVia));
        boolean screenOn = scr == null || scr;
        bHotspot.setText(hotspotButtonTurnsOff ? "Hotspot off" : "Hotspot on");
        unknownRow.setVisibility(both ? View.VISIBLE : View.GONE);
        bScreen.setText(screenOn ? "Sleep screen" : "Wake screen");
        String need = p == null ? "" : needs(p);
        boolean can = reachable && !busy && p != null && need.isEmpty();
        bHotspot.setEnabled(can);
        bHotspotOff.setEnabled(can);
        bScreen.setEnabled(can);
        bStandby.setEnabled(can && (hs == null || hs || screenOn));
        // Made from the status every time, not kept as a message: it goes away by itself once Shizuku is running again.
        tNeed.setText(need);
        tNeed.setVisibility(reachable && !need.isEmpty() ? View.VISIBLE : View.GONE);
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

    private void send(final String action, String label, int readMs) {
        touched();
        busy = true;
        paintSwitches();
        if (bannerMain != null) {
            bannerMain.setEnabled(false);
        }
        setAction(label + "…", true);
        Call.run(act, "POST", "/act?name=" + action, readMs, (ok, text, json) -> {
            // "later": the phone answered BEFORE acting, because the action takes away the link it was asked over. That answer
            // only means "sent"; what became of the switch is read from the status.
            FollowUp f = ok && json != null && json.optBoolean("later", false) ? FollowUp.of(action) : null;
            Handler h = bg;
            if (f != null && polling && h != null) {
                follow = f;
                followSince = SystemClock.elapsedRealtime();
                following = true;
                setAction("Sent. Looking at the phone's status to see what it did…", true);
                synchronized (chain) {
                    h.removeCallbacks(pollTask);
                    h.postDelayed(pollTask, FollowUp.EVERY_MS);
                }
                return;
            }
            busy = false;
            setAction(text, ok); // a switch that did not move comes back as an error: shown in the warning colour
            paintSwitches();
            // look again now and a little later: a hotspot takes a few seconds to come up or go down
            askNow();
            act.ui.postDelayed(this::askNow, 4000);
        });
    }

    /** The follow-up of a "sent" switch is over: its last word. */
    private void endFollow(String text, boolean ok) {
        follow = null;
        following = false;
        busy = false;
        setAction(text, ok);
        paintSwitches();
    }

    private void hotspotPressed() {
        if (hotspotButtonTurnsOff) {
            confirmOff(false);
        } else {
            send("hotspot_on", "Switching the hotspot on", 45000);
        }
    }

    private void screenPressed() {
        touched();
        boolean on = lastPhone == null || lastPhone.optBoolean("scr", true);
        busy = true;
        paintSwitches();
        setAction(on ? "Putting the phone's screen to sleep…" : "Waking the phone's screen…", true);
        Call.run(act, "POST", on ? "/screen/off?nodoze=1" : "/screen/on", 20000, (ok, text, json) -> {
            busy = false;
            setAction(text, ok);
            paintSwitches();
            askNow();
        });
    }

    /**
     * "Hotspot off" or Standby was pressed. With the hotspot off, Bluetooth is the only way back to the phone, so before
     * anything is sent the tablet CALLS the phone over Bluetooth, now. A stored address, or a link that worked yesterday, proves
     * nothing about this moment (the phone's Bluetooth may be off, the phone out of range). Only if that call is answered does
     * the question say the hotspot can be switched on again from here; otherwise it says the phone may have to be picked up,
     * with the reason, and leaves the choice to the user.
     */
    private void confirmOff(final boolean standby) {
        touched();
        if (!BtLink.ready(act)) {
            askOff(standby, false, linkWords()); // there is nothing to call: say why
            return;
        }
        busy = true;
        paintSwitches();
        setAction("Checking the Bluetooth link…", true);
        new Thread(() -> {
            String w;
            try {
                w = linkTrouble(Call.reach(act).bluetoothOnly("GET", "/s", 8000));
            } catch (RuntimeException e) {
                // an escape from this thread would end the app; a test that could not be made is a test that failed
                w = "Bluetooth could not be tried on this tablet (" + e + ").";
            }
            final String why = w;
            act.ui.post(() -> {
                busy = false;
                setAction("", true);
                paintSwitches();
                if (polling && fHotspot != null) { // this screen is still the one on show
                    askOff(standby, why.isEmpty(), why);
                }
            });
        }, "linkmeter-bt-check").start();
    }

    /** What the live call over Bluetooth showed: "" when the phone answered and says its link is on, else the reason. */
    private static String linkTrouble(Reach.Result res) {
        if (res.resp == null) {
            String e = res.error;
            return (res.btTried ? "Bluetooth was tried just now and did not get through: " : "Bluetooth could not be tried: ")
                    + e + (e.endsWith(".") || e.endsWith(")") ? "" : ".") + " Test and Set up are in " + SETTINGS
                    + ", under Bluetooth link.";
        }
        if (res.resp.code == 401) {
            return "The phone answered over Bluetooth just now, but it does not know this tablet.";
        }
        if (res.resp.code != 200) {
            return "The phone answered over Bluetooth just now with an error (" + res.resp.code + ").";
        }
        try {
            if ("on".equals(new JSONObject(res.resp.body).optString("bt", ""))) {
                return "";
            }
        } catch (JSONException e) {
            // not a status: said below
        }
        return "The phone answered over Bluetooth just now, but its status does not say that its Bluetooth link is on.";
    }

    private void askOff(final boolean standby, boolean linkWorks, String why) {
        String what = standby
                ? "The phone's hotspot and its screen go off. The phone stays on and can still take calls and texts."
                : "The phone's hotspot goes off, and the tablet loses its internet.";
        String back = linkWorks
                ? "The phone answered over Bluetooth just now, so " + (standby ? "it can be woken" : "the hotspot can be switched "
                + "on again") + " from this tablet with \"Hotspot on\", as long as the phone stays in Bluetooth range."
                : "After this the phone may have to be picked up to switch its hotspot on again, because Bluetooth is then the "
                + "only way to it from this tablet.\n\n" + why;
        String go = (standby ? "Standby" : "Turn it off") + (linkWorks ? "" : " anyway");
        new AlertDialog.Builder(act)
                .setTitle(standby ? "Put the phone on standby?" : "Turn the hotspot off?")
                .setMessage(what + "\n\n" + back)
                .setPositiveButton(go, (d, w) -> {
                    if (standby) {
                        send("standby", "Putting the phone on standby", 60000);
                    } else {
                        send("hotspot_off", "Switching the hotspot off", 45000);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
