package com.tj.linkmeter;

import android.app.AlertDialog;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The tablet's "Phone" tab: the controls for the phone that Home does not have (the phone's Wi-Fi, the battery saver, Restart and
 * Power off, the app update, Restore everything, the safety check, and the steps after a restart of the phone), in the app's small
 * design system. The class keeps its old name: PhoneTab is the phone's own screen.
 *
 * It asks the phone for its status when it is shown and again after each press, and for nothing else: no timer runs while nothing
 * is happening, and nothing is sent while the tab is hidden. A switch that takes the link away (Restart, Power off, the phone's
 * Wi-Fi) is answered "Sent" before the phone acts; the tab then looks at the status ({@link FollowUp}) and says what became of it.
 * The last answer stays in a strip at the bottom of the screen and is kept when the tab is rebuilt.
 */
final class ControlTab extends Tab {
    // ---- what the phone last said
    private JSONObject lastPhone;
    private String lastVia = "";
    private boolean reachable;
    /** The tab has had one answer or one miss: before that there is nothing to say about reaching the phone. */
    private boolean heard;
    private Trouble.Kind trouble;
    private boolean btTried;
    private String troubleError = "";

    // ---- what the tab is doing
    /** The tab is on screen: nothing it started later may do work while it is hidden. */
    private boolean shown;
    /** A request is out: the buttons wait, so nothing is sent twice. */
    private boolean busy;
    private boolean asking;
    private FollowUp follow;
    private long followSince;
    /** Counts the follow-ups, so that a look from one that has ended cannot go on. */
    private int followGen;
    private boolean moreOpen;
    private long ownCode = -2;

    // ---- the last answer, kept across a rebuild of the views (a tab is rebuilt each time it is shown)
    private String resultWhat = "";
    private String resultText = "";
    private boolean resultOk = true;
    private boolean resultWorking;

    // ---- views
    private Ui.ResultBar bar;
    private ScrollView scroll;
    private TextView chip;
    private TextView status;
    private LinearLayout banner;
    private TextView bannerTitle;
    private TextView bannerText;
    private Button bannerMain;
    private Button bannerOther;
    private boolean bannerSends;
    private PhoneTabRules.Banner bannerKind = PhoneTabRules.Banner.NONE;
    private Ui.Fact fSaver;
    private Ui.Choice saver;
    private TextView tCurrent;
    private Ui.Fact fWifi;
    private Button bWifiOn;
    private Button bWifiOff;
    private Button bRestart;
    private Button bPoweroff;
    private Button bRestore;
    private Button bSafety;
    private LinearLayout bootCard;
    private BootSection boot;
    private Ui.Fact fOwn;
    private Ui.Fact fPhoneApp;
    private TextView tLastUpdate;
    private TextView tUpdateNote;
    private Button bUpdate;
    private Button bMore;
    private LinearLayout moreCard;
    private Ui.Choice band;
    private Button bRestartHotspot;
    private Button bPrepare;
    private Button bProbe;

    ControlTab(MainActivity a) {
        super(a);
    }

    /** On screen this tab is "Phone". The class keeps its name: PhoneTab is the phone's own screen. */
    @Override
    String title() {
        return PHONE;
    }

    @Override
    Icons.Kind icon() {
        return Icons.Kind.PHONE;
    }

    // ------------------------------------------------------------------------------------------------ the screen

    @Override
    View build() {
        LinearLayout col = Ui.column(act);
        bar = new Ui.ResultBar(act);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.title(act, PHONE), Ui.weight());
        chip = Ui.chip(act, "Looking…", Ui.GREY);
        head.addView(chip);
        col.addView(head);
        status = Ui.text(act, "Looking for the phone…", 13, Ui.DIM, false);
        col.addView(status, Ui.lp(act, 2));

        // Only shown when it has something to say.
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

        LinearLayout bat = Ui.card(act, "Battery", "While the tablet uses the phone's hotspot, the saver turns off background "
                + "scanning and the always-on display and shortens the phone's screen timeout. It puts everything back when the "
                + "tablet is gone. It never touches mobile data or the hotspot's speed.");
        fSaver = new Ui.Fact(act, "Battery saver");
        bat.addView(fSaver.view, Ui.lp(act, 8));
        saver = new Ui.Choice(act, new String[] {"Automatic", "Off"}, -1, i -> send(i == 0 ? "eco_auto_on" : "eco_auto_off",
                i == 0 ? "Switching the battery saver to Automatic" : "Switching the battery saver off", 45000));
        bat.addView(saver.view, Ui.lp(act, 8));
        tCurrent = Ui.text(act, "", 13, Ui.FG, false);
        tCurrent.setVisibility(View.GONE);
        bat.addView(tCurrent, Ui.lp(act, 8));
        bat.addView(Ui.hint(act, "Standby on " + HOME + " saves the most: it switches the phone's hotspot and screen off. "
                + "4G only (in the " + BANDS + " tab) also saves battery."), Ui.lp(act, 6));
        col.addView(bat, Ui.lp(act, 10));

        LinearLayout sw = Ui.card(act, "Switches", null);
        fWifi = new Ui.Fact(act, "Phone's Wi-Fi");
        sw.addView(fWifi.view, Ui.lp(act, 8));
        bWifiOn = Ui.button(act, "Wi-Fi on", v -> wifiPressed(true));
        bWifiOff = Ui.button(act, "Wi-Fi off", v -> wifiPressed(false));
        sw.addView(Ui.row(act, bWifiOn, bWifiOff), Ui.lp(act, 10));
        sw.addView(Ui.hint(act, "Hotspot, screen and Standby are on " + HOME + "."), Ui.lp(act, 6));
        col.addView(sw, Ui.lp(act, 10));

        LinearLayout pw = Ui.card(act, "Power", "For every day, Standby on " + HOME + " is better: the tablet can wake the phone "
                + "again. Restart and Power off cannot be undone from the tablet.");
        bRestart = Ui.danger(act, "Restart", v -> askRestart());
        bPoweroff = Ui.danger(act, "Power off", v -> askPowerOff());
        pw.addView(Ui.row(act, bRestart, bPoweroff), Ui.lp(act, 10));
        col.addView(pw, Ui.lp(act, 10));

        LinearLayout fix = Ui.card(act, "If something breaks", "Works over the hotspot and over Bluetooth, even when the phone has "
                + "no mobile data.");
        bRestore = Ui.primary(act, "Restore everything", v -> send("restore", "Putting everything back", 90000));
        bSafety = Ui.button(act, "Safety check", v -> send("safety", "Checking the phone", 40000));
        fix.addView(bRestore, Ui.lp(act, 10));
        fix.addView(bSafety, Ui.lp(act, 4));
        col.addView(fix, Ui.lp(act, 10));

        bootCard = Ui.card(act);
        boot = new BootSection(act);
        boot.build(bootCard); // start the phone after a restart: the tablet's hotspot, wireless debugging, Shizuku, the swap
        col.addView(bootCard, Ui.lp(act, 10));

        LinearLayout app = Ui.card(act, "Phone app", null);
        fOwn = new Ui.Fact(act, "This tablet");
        fPhoneApp = new Ui.Fact(act, "Phone");
        app.addView(fOwn.view, Ui.lp(act, 8));
        app.addView(fPhoneApp.view, Ui.lp(act, 6));
        tLastUpdate = Ui.hint(act, "");
        tLastUpdate.setVisibility(View.GONE);
        app.addView(tLastUpdate, Ui.lp(act, 6));
        bUpdate = Ui.button(act, "Update the phone", v -> updatePhone());
        app.addView(bUpdate, Ui.lp(act, 10));
        tUpdateNote = Ui.hint(act, "");
        app.addView(tUpdateNote, Ui.lp(act, 6));
        col.addView(app, Ui.lp(act, 10));

        bMore = Ui.quiet(act, moreOpen ? "Less" : "More", v -> toggleMore());
        col.addView(bMore, Ui.lp(act, 10));
        moreCard = Ui.card(act, "Hotspot Wi-Fi band", "Experimental: this phone may keep its own setting. Pick a band, then restart "
                + "the hotspot to use it. The answer says whether the phone kept your choice.");
        band = new Ui.Choice(act, new String[] {"2.4 GHz", "5 GHz", "Either"}, -1, i -> send(i == 0 ? "hotspot_band_2"
                : i == 1 ? "hotspot_band_5" : "hotspot_band_auto", "Setting the hotspot band", 40000));
        moreCard.addView(band.view, Ui.lp(act, 8));
        bRestartHotspot = Ui.button(act, "Restart hotspot", v -> askRestartHotspot());
        moreCard.addView(bRestartHotspot, Ui.lp(act, 8));
        moreCard.addView(Ui.divider(act));
        moreCard.addView(Ui.heading(act, "Setup and checks"), Ui.lp(act, 6));
        bPrepare = Ui.button(act, "Prepare phone", v -> send("setup", "Preparing the phone", 40000));
        bProbe = Ui.button(act, "Probe phone", v -> probe());
        moreCard.addView(Ui.row(act, bPrepare, bProbe), Ui.lp(act, 8));
        moreCard.addView(Ui.hint(act, "Prepare phone gives LinkMeter the permissions it needs on the phone. Probe phone lists what "
                + "the phone's shell answers, for finding out what it supports."), Ui.lp(act, 6));
        moreCard.setVisibility(moreOpen ? View.VISIBLE : View.GONE);
        col.addView(moreCard, Ui.lp(act, 10));

        // the last answer, if there is one (a tab is rebuilt when it is shown again)
        if (resultWorking) {
            bar.working(resultWhat);
        } else if (!resultText.isEmpty()) {
            bar.working(resultWhat);
            bar.show(resultText, resultOk);
        }
        View root = Ui.withResultBar(act, col, bar);
        scroll = col.getParent() instanceof ScrollView ? (ScrollView) col.getParent() : null;
        paintAll();
        return root;
    }

    @Override
    void onHide() {
        shown = false;
        followGen++; // a look that is on its way ends here
        if (follow != null) {
            follow = null;
            busy = false;
            setResult(resultWhat, "Sent. LinkMeter stopped looking for the result when this tab was left. The values shown are the "
                    + "phone's latest answer, which may be from before.", true);
        }
        if (boot != null) {
            boot.onHide();
        }
    }

    @Override
    void onShow() {
        shown = true;
        if (boot != null) {
            boot.onShow();
        }
        paintAll();
        refreshStatus();
    }

    // ------------------------------------------------------------------------------------------------ asking the phone

    /** What one question found. Built on a short-lived thread, used on the UI thread. */
    private static final class Snap {
        JSONObject phone;
        String via = "";
        String error = "";
        Trouble.Kind trouble;
        boolean btTried;
    }

    private interface Got {
        void got(Snap s);
    }

    /** press: this question is the result of a button, so it may switch the tablet's Bluetooth on (a status read by itself may not). */
    private void askStatus(final boolean press, final Got g) {
        new Thread(() -> {
            Snap s = new Snap();
            try {
                Reach.Result res = Call.reach(act).call("GET", "/s", 4000, press);
                s.btTried = res.btTried;
                if (res.resp == null) {
                    s.error = res.error;
                    s.trouble = Trouble.of(false, 0, res.asked, res.btOff);
                } else {
                    s.via = res.via;
                    if (res.resp.code == 200) {
                        try {
                            s.phone = new JSONObject(res.resp.body);
                        } catch (JSONException e) {
                            s.error = "Couldn't read the phone's reply.";
                            s.trouble = Trouble.of(true, 200, true, false);
                        }
                    } else {
                        s.error = res.resp.code == 401 ? "The phone does not know this tablet."
                                : "The phone replied with an error (" + res.resp.code + ").";
                        s.trouble = Trouble.of(true, res.resp.code, true, false);
                    }
                }
            } catch (RuntimeException e) {
                // an escape from this thread would end the app
                s.error = "LinkMeter could not ask the phone (" + e + ").";
                s.trouble = Trouble.Kind.NO_ANSWER;
            }
            final Snap fs = s;
            act.ui.post(() -> g.got(fs));
        }, "linkmeter-phone-status").start();
    }

    /** One status read: when the tab is shown, and after each press. Never while hidden, never two at once. */
    private void refreshStatus() {
        if (!shown || asking) {
            return;
        }
        asking = true;
        askStatus(false, s -> {
            asking = false;
            noted(s);
        });
    }

    /** The user pressed "Ask again": this question may switch the tablet's Bluetooth on. */
    private void askAgain() {
        if (!shown || asking) {
            return;
        }
        asking = true;
        askStatus(true, s -> {
            asking = false;
            noted(s);
        });
    }

    private void noted(Snap s) {
        heard = true;
        reachable = s.phone != null;
        trouble = s.trouble;
        btTried = s.btTried;
        troubleError = s.error;
        if (s.phone != null) {
            lastPhone = s.phone;
            lastVia = s.via;
        }
        if (shown) {
            paintAll();
        }
    }

    // ------------------------------------------------------------------------------------------------ painting

    private long ownCode() {
        if (ownCode == -2) {
            try {
                ownCode = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).getLongVersionCode();
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                ownCode = -1;
            }
        }
        return ownCode;
    }

    private String ownVersion() {
        try {
            android.content.pm.PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            return pi.versionName + " (code " + pi.getLongVersionCode() + ")";
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "not known";
        }
    }

    private boolean overBluetooth() {
        return Reach.BLUETOOTH.equals(lastVia);
    }

    private void paintAll() {
        if (status == null) {
            return;
        }
        paintHeader();
        paintBanner();
        paintBattery();
        paintSwitches();
        paintApp();
        paintButtons();
    }

    private void paintHeader() {
        if (!heard) {
            Ui.paintChip(chip, "Looking…", Ui.GREY);
            status.setText("Looking for the phone…");
            status.setTextColor(Ui.DIM);
        } else if (reachable) {
            boolean bt = overBluetooth();
            Ui.paintChip(chip, bt ? "Bluetooth" : "Hotspot", bt ? Ui.ACCENT : Ui.GREEN);
            int priv = lastPhone.optInt("priv", -1);
            status.setText("Phone app " + PhoneTabRules.versionText(lastPhone.optString("vn", ""), lastPhone.optLong("vc", -1)) + ". "
                    + (priv == Priv.READY ? "Shizuku is ready." : priv == Priv.NO_PERM ? "Shizuku is running, but LinkMeter is not allowed in it yet."
                    : priv == Priv.OFF ? "Shizuku is not running." : ""));
            status.setTextColor(Ui.DIM);
        } else {
            Ui.paintChip(chip, "Not connected", Ui.AMBER);
            status.setText(troubleError.isEmpty() ? "The phone does not answer." : troubleError);
            status.setTextColor(Ui.AMBER);
        }
    }

    /** The card under the title: only when it has something to say, the first of the four that applies. */
    private void paintBanner() {
        JSONObject p = lastPhone;
        Boolean air = p == null ? null : Call.flag(p, "air");
        Boolean running = null;
        Boolean allowed = null;
        if (p != null && p.has("priv")) {
            int priv = p.optInt("priv", -1);
            running = Boolean.valueOf(priv != Priv.OFF);
            allowed = Boolean.valueOf(priv == Priv.READY);
        }
        long phoneCode = p == null ? -1 : p.optLong("vc", -1);
        bannerKind = !heard ? PhoneTabRules.Banner.NONE : PhoneTabRules.banner(reachable, air, running, allowed, phoneCode, ownCode());
        bannerSends = false;
        bannerOther.setVisibility(View.GONE);
        bannerMain.setVisibility(View.VISIBLE);
        banner.setVisibility(bannerKind == PhoneTabRules.Banner.NONE ? View.GONE : View.VISIBLE);
        switch (bannerKind) {
            case UNREACHABLE: {
                Trouble.Kind k = trouble != null ? trouble : Trouble.Kind.NO_ANSWER;
                boolean linkOk = BtLink.state(act) == LinkState.Kind.READY;
                String text = Trouble.text(k, overBluetooth(), btTried, linkOk ? "" : BtLink.words(act));
                if (k == Trouble.Kind.PHONE_ERROR) {
                    text += " \"Update the phone\" is further down in this tab.";
                }
                bannerTitle.setText(Trouble.title(k));
                bannerText.setText(text);
                bannerMain.setText(Trouble.button(k));
                bannerMain.setOnClickListener(v -> askAgain()); // a press: this one may switch the tablet's Bluetooth on
                if (Trouble.offersRestart(k)) {
                    bannerOther.setVisibility(View.VISIBLE);
                    bannerOther.setText("After a phone restart");
                    bannerOther.setOnClickListener(v -> showRestartCard());
                }
                break;
            }
            case AIRPLANE:
                bannerTitle.setText("Airplane mode is on");
                bannerText.setText("The phone's radios are off, so it has no mobile data and no hotspot.");
                bannerMain.setText("Switch it off");
                bannerMain.setOnClickListener(v -> send("airplane_off", "Switching airplane mode off", 20000));
                bannerSends = true;
                break;
            case SHIZUKU_OFF:
                bannerTitle.setText("Shizuku is not running on the phone");
                bannerText.setText("Most of the controls below need it. After a phone restart it has to be started again: the "
                        + "steps are further down, under Start the phone after a restart.");
                bannerMain.setText("After a phone restart");
                bannerMain.setOnClickListener(v -> showRestartCard());
                break;
            case SHIZUKU_NOT_ALLOWED:
                bannerTitle.setText("LinkMeter is not allowed in Shizuku yet");
                bannerText.setText("Open LinkMeter on the phone itself and tap Allow in Shizuku. Most of the controls below need it.");
                bannerMain.setVisibility(View.GONE);
                break;
            case VERSION:
                bannerTitle.setText("The phone's app is a different version");
                bannerText.setText("Phone: " + PhoneTabRules.versionText(p.optString("vn", ""), phoneCode) + ". This tablet: "
                        + ownVersion() + ". Put the same version on both. "
                        + PhoneTabRules.updateBlocked(true, overBluetooth()));
                bannerMain.setText("Update the phone");
                bannerMain.setOnClickListener(v -> updatePhone());
                bannerSends = true;
                break;
            default:
                break;
        }
    }

    private void paintBattery() {
        if (lastPhone == null) {
            fSaver.set("not known", Ui.DIM);
            saver.select(-1);
            tCurrent.setVisibility(View.GONE);
            return;
        }
        boolean on = lastPhone.optBoolean("eco", false);
        boolean auto = lastPhone.optBoolean("ecoAuto", true);
        fSaver.set(SaverPlan.homeFact(on, auto, lastPhone.optInt("priv", -1)), reachable ? Ui.FG : Ui.DIM);
        saver.select(lastPhone.has("ecoAuto") ? (auto ? 0 : 1) : -1);
        String cur = PhoneTabRules.currentWords(lastPhone.has("cur") ? Integer.valueOf(lastPhone.optInt("cur")) : null);
        tCurrent.setVisibility(cur == null ? View.GONE : View.VISIBLE);
        if (cur != null) {
            tCurrent.setText(cur);
            tCurrent.setTextColor(reachable ? Ui.FG : Ui.DIM);
        }
    }

    private void paintSwitches() {
        Boolean wifi = lastPhone == null ? null : Call.flag(lastPhone, "wifi");
        fWifi.set(PhoneTabRules.switchFact(wifi), reachable && wifi != null ? Ui.FG : Ui.DIM);
    }

    private void paintApp() {
        fOwn.set(ownVersion(), Ui.FG);
        long phoneCode = lastPhone == null ? -1 : lastPhone.optLong("vc", -1);
        fPhoneApp.set(PhoneTabRules.versionText(lastPhone == null ? "" : lastPhone.optString("vn", ""), phoneCode), reachable ? Ui.FG : Ui.DIM);
        String upd = lastPhone == null ? "" : lastPhone.optString("upd", "");
        tLastUpdate.setText("Last update on the phone: " + upd);
        tLastUpdate.setVisibility(upd.isEmpty() ? View.GONE : View.VISIBLE);
        String blocked = PhoneTabRules.updateBlocked(reachable, overBluetooth());
        tUpdateNote.setText(blocked.isEmpty()
                ? "Install the new version on this tablet first (open the downloaded file). This sends the tablet's copy to the phone, "
                + "which installs it and restarts the app by itself. Needs Shizuku on the phone."
                : "Not now: " + blocked + ".");
    }

    /** Which buttons can be pressed: the phone's buttons only while the phone answers and nothing is out. */
    private void paintButtons() {
        if (status == null) {
            return;
        }
        boolean can = reachable && !busy;
        for (Button b : new Button[] {bWifiOn, bWifiOff, bRestart, bPoweroff, bRestore, bSafety, bRestartHotspot, bPrepare, bProbe}) {
            b.setEnabled(can);
        }
        setChoiceEnabled(saver, can);
        setChoiceEnabled(band, can);
        boolean canUpdate = can && PhoneTabRules.updateBlocked(reachable, overBluetooth()).isEmpty();
        bUpdate.setEnabled(canUpdate);
        bannerMain.setEnabled(bannerSends ? (bannerKind == PhoneTabRules.Banner.VERSION ? canUpdate : can) : !busy);
    }

    private static void setChoiceEnabled(Ui.Choice c, boolean on) {
        for (int i = 0; i < c.view.getChildCount(); i++) {
            c.view.getChildAt(i).setEnabled(on);
        }
    }

    private void showRestartCard() {
        if (scroll != null && bootCard != null) {
            scroll.smoothScrollTo(0, bootCard.getTop());
        }
    }

    private void toggleMore() {
        moreOpen = !moreOpen;
        bMore.setText(moreOpen ? "Less" : "More");
        moreCard.setVisibility(moreOpen ? View.VISIBLE : View.GONE);
        if (moreOpen && reachable) {
            readBand();
        }
    }

    // ------------------------------------------------------------------------------------------------ the last answer

    private void working(String what) {
        resultWhat = what;
        resultText = "";
        resultWorking = true;
        if (bar != null) {
            bar.working(what);
        }
    }

    private void setResult(String what, String text, boolean ok) {
        resultWhat = what;
        resultText = text == null ? "" : text;
        resultOk = ok;
        resultWorking = false;
        if (bar != null) {
            bar.working(what);
            bar.show(resultText, ok);
        }
    }

    // ------------------------------------------------------------------------------------------------ sending

    /** One press: sent once, its answer in the strip, then one more status read. */
    private void send(final String action, final String label, final int readMs) {
        if (busy) {
            return;
        }
        busy = true;
        working(label);
        paintButtons();
        Call.run(act, "POST", "/act?name=" + action, readMs, (ok, text, json) -> {
            // "later": the phone answered BEFORE acting, because the action takes away the link it was asked over. That answer
            // only means "sent"; what became of the switch is read from the status.
            FollowUp f = ok && json != null && json.optBoolean("later", false) ? FollowUp.of(action) : null;
            if (f != null && shown) {
                startFollow(f, label);
                return;
            }
            String said = !ok && PhoneTabRules.tooOld(text)
                    ? "The phone's app is too old for this. Update it first: \"Update the phone\" is further down in this tab."
                    : f != null ? "Sent. LinkMeter stopped looking for the result when this tab was left." : text;
            busy = false;
            setResult(label, said, ok || f != null);
            paintButtons();
            refreshStatus();
        });
    }

    private void startFollow(FollowUp f, String label) {
        follow = f;
        followSince = SystemClock.elapsedRealtime();
        final int gen = ++followGen;
        setResult(label, "Sent. Looking at the phone's status to see what it did…", true);
        act.ui.postDelayed(() -> followLook(gen), FollowUp.EVERY_MS);
    }

    private void followLook(final int gen) {
        if (gen != followGen || follow == null || !shown) {
            return;
        }
        askStatus(true, s -> { // looking for the result of a press is part of that press
            if (gen != followGen || follow == null) {
                return;
            }
            noted(s);
            FollowUp.Outcome o = follow.look(Call.seen(s.phone));
            if (o == null && SystemClock.elapsedRealtime() - followSince >= FollowUp.GIVE_UP_MS) {
                o = follow.giveUp();
            }
            if (o != null) {
                follow = null;
                busy = false;
                setResult(resultWhat, o.text, o.ok);
                paintButtons();
            } else {
                act.ui.postDelayed(() -> followLook(gen), FollowUp.EVERY_MS);
            }
        });
    }

    private void probe() {
        if (busy) {
            return;
        }
        busy = true;
        working("Asking the phone");
        paintButtons();
        Call.run(act, "GET", "/probe", 60000, (ok, text, json) -> {
            busy = false;
            setResult("Probe phone", text, ok);
            paintButtons();
        });
    }

    /** Reads the hotspot's band from the phone, to show the choice. Only when the More card is opened. */
    private void readBand() {
        Call.run(act, "POST", "/act?name=hotspot_info", 40000, (ok, text, json) -> {
            if (!ok || band == null || !shown) {
                return;
            }
            Matcher m = Pattern.compile("band=(\\d+)").matcher(text);
            if (m.find()) {
                int b = Integer.parseInt(m.group(1));
                band.select(b == 1 ? 0 : b == 2 ? 1 : b == 3 ? 2 : -1);
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ questions before a press

    private void confirm(String title, String message, String go, final Runnable then) {
        new AlertDialog.Builder(act)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(go, (d, w) -> then.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void wifiPressed(boolean on) {
        final String action = on ? "wifi_on" : "wifi_off";
        final String label = on ? "Switching the phone's Wi-Fi on" : "Switching the phone's Wi-Fi off";
        Boolean hs = lastPhone == null ? null : Call.flag(lastPhone, "hs");
        if (!RemoteRoutes.cutsLink(action, overBluetooth(), hs)) {
            send(action, label, 45000);
            return;
        }
        // only where it can cut the way to the phone: the rule is the phone's own (RemoteRoutes.cutsLink)
        if (on) {
            confirm("Switch the phone's Wi-Fi on?", "Many phones cannot be a hotspot and join a Wi-Fi network at the same time, so the "
                    + "phone's hotspot may stop when its Wi-Fi comes on. The tablet then loses its connection to the phone. With the "
                    + "Bluetooth link set up, the hotspot can be switched on again from " + HOME + ".", "Switch it on",
                    () -> send(action, label, 45000));
        } else {
            confirm("Switch the phone's Wi-Fi off?", "The tablet may be reaching the phone through that Wi-Fi network. If so, it loses "
                    + "the phone when the Wi-Fi goes off. With the Bluetooth link set up the tablet still reaches the phone over "
                    + "Bluetooth.", "Switch it off", () -> send(action, label, 45000));
        }
    }

    private void askRestart() {
        confirm("Restart the phone?", "The phone restarts. Afterwards it has to be unlocked by hand, and Shizuku has to be started "
                + "again (the steps are under Start the phone after a restart). Until then the tablet cannot switch anything on the "
                + "phone.", "Restart", () -> send("reboot", "Restarting the phone", 15000));
    }

    private void askPowerOff() {
        confirm("Power the phone off?", "The phone shuts down. Nothing can switch it on from this tablet: you will have to switch "
                + "it on by hand.", "Power off", () -> send("poweroff", "Powering the phone off", 15000));
    }

    /**
     * Restart hotspot. The hotspot is the way to the phone and it goes off for a moment, so first the tablet CALLS the phone over
     * Bluetooth, now ({@link LinkCheck}), and the question says only what that call showed.
     */
    private void askRestartHotspot() {
        if (busy) {
            return;
        }
        busy = true;
        working("Checking the Bluetooth link");
        paintButtons();
        LinkCheck.run(act, why -> {
            busy = false;
            resultWorking = false;
            resultText = "";
            if (bar != null) {
                bar.show("", true);
            }
            paintButtons();
            if (!shown) {
                return;
            }
            boolean linkWorks = why.isEmpty();
            String back = linkWorks
                    ? "The phone answered over Bluetooth just now, so if the hotspot does not come back it can be switched on again "
                    + "from this tablet with \"Hotspot on\" on " + HOME + "."
                    : "If the hotspot does not come back, the phone may have to be picked up to switch it on, because Bluetooth is "
                    + "then the only way to it from this tablet.\n\n" + why;
            confirm("Restart the hotspot?", "The phone's hotspot goes off and on again. The tablet loses its connection for about ten "
                    + "seconds and joins again when the hotspot is back.\n\n" + back, linkWorks ? "Restart" : "Restart anyway",
                    () -> send("hotspot_restart", "Restarting the hotspot", 45000));
        });
    }

    // ------------------------------------------------------------------------------------------------ the app update

    /** Sends this tablet's installed copy of the app to the phone, which installs it over itself. Over the hotspot only. */
    private void updatePhone() {
        if (busy) {
            return;
        }
        busy = true;
        working("Sending the app to the phone");
        paintButtons();
        new Thread(() -> {
            boolean ok = false;
            String text;
            try {
                String host = Net.phoneHost(act);
                if (host.isEmpty()) {
                    throw new IOException("The tablet is not on the phone's hotspot.");
                }
                File apk = new File(act.getApplicationInfo().sourceDir);
                try (FileInputStream in = new FileInputStream(apk)) {
                    Client.Resp r = Client.put(host, "/apk", Cfg.token(act), in, apk.length(), null);
                    text = r.body;
                    try {
                        JSONObject j = new JSONObject(r.body);
                        text = j.has("err") ? j.optString("err") : j.optString("out", r.body);
                    } catch (JSONException plain) {
                        // not JSON: show the text as it came
                    }
                    ok = r.code == 200;
                }
            } catch (IOException e) {
                text = "Couldn't update the phone: " + e.getMessage();
            }
            final boolean fOk = ok;
            final String fText = text;
            act.ui.post(() -> {
                busy = false;
                setResult("Update the phone", fText, fOk);
                paintButtons();
                if (fOk && fText.contains("installing")) {
                    // The phone app restarts; look again once it is back (and only if this tab is still the one on show).
                    act.ui.postDelayed(this::refreshStatus, 15000);
                    act.ui.postDelayed(this::refreshStatus, 30000);
                }
            });
        }, "linkmeter-update").start();
    }

    /** Asks the phone to put everything back and shows its report. Used by the Bands tab. */
    static void restoreDialog(final MainActivity act) {
        Toast.makeText(act, "Asking the phone to restore everything…", Toast.LENGTH_SHORT).show();
        Call.run(act, "POST", "/act?name=restore", 90000, (ok, text, json) ->
                new AlertDialog.Builder(act)
                        .setTitle(ok ? "Restore everything" : "Couldn't restore")
                        .setMessage(text)
                        .setPositiveButton("OK", null)
                        .show());
    }
}
