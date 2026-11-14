package com.tj.linkmeter;

import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;

/**
 * The phone's own screen, built from the same pieces as the tablet's tabs ({@link Ui}). It says what the phone is ready for, asks
 * for the one thing that is missing first, and holds every switch that belongs to the phone: the Bluetooth link, the screen, the
 * texts, the battery saver and the start after a restart. What each line says is decided by {@link PhoneScreenRules}; this class
 * only reads the facts, paints them and carries out the buttons.
 *
 * Reading never happens on the screen's thread: the facts come from a thread of their own every two seconds while the screen is
 * shown (the hotspot check lists the network interfaces), and the thread ends when the screen is left. The version line is read
 * once, when the screen is shown.
 */
final class PhoneTab extends Tab {
    /**
     * Where the screen gets what it shows. The screen preview puts its own in place (Shizuku, the Bluetooth listener and the hotspot
     * cannot be faked there).
     */
    interface Source {
        PhoneScreenFacts read(MainActivity a);
    }

    static Source source = PhoneTab::readFacts;

    private static final long TICK_MS = 2000;

    private LinearLayout col;
    private Ui.ResultBar bar;
    private TextView chip;
    private TextView summary;

    // the card that asks for attention
    private LinearLayout attentionCard;
    private TextView attentionHeading;
    private TextView attentionText;
    private Button attentionButton;
    private PhoneScreenRules.Attention shownAttention = PhoneScreenRules.Attention.NONE;

    // Status
    private Ui.Fact fRunning;
    private Ui.Fact fHotspot;
    private Ui.Fact fBluetooth;
    private Ui.Fact fShizukuTop;
    private Ui.Fact fTablet;
    private TextView tRequests;
    private Button bStop;

    // Tablet link
    private Button bForget;
    private TextView tPairing;
    private Ui.Toggle tgBluetooth;
    private TextView tBluetooth;
    private Button bBluetooth;
    private PhoneScreenRules.BtButton shownBtButton = PhoneScreenRules.BtButton.NONE;
    private Ui.Toggle tgScreen;

    // Shizuku and permissions
    private TextView chipPermissions;
    private Ui.Fact fShizuku;
    private Button bShizuku;
    private final Ui.Fact[] fItem = new Ui.Fact[PhoneScreenRules.Item.values().length];
    private Button bAllowEverything;
    private LinearLayout itemButtons;
    private String shownItemButtons = "";

    // Texts
    private Ui.Toggle tgTexts;
    /** Fast Wi-Fi (docs/FAST_WIFI.md): may the tablet use the relay, and what it does now. */
    private Ui.Toggle tgFast;
    private TextView tFast;
    private final CompoundButton.OnCheckedChangeListener fastListener = (v, on) -> {
        FastRelay.setAllowed(act, on);
        if (tFast != null) {
            tFast.setText(FastRelay.line(act));
        }
    };
    private TextView tTexts;
    private Button bTexts;

    // Battery
    private Ui.Toggle tgSaver;
    private Ui.Fact fSaver;
    private Ui.Toggle tgAndroid;
    private Ui.Fact fAndroid;
    private LinearLayout batteryLimit;

    // After a restart
    private Ui.Toggle tgAutoStart;
    private Ui.Toggle tgAssist;
    private TextView tAssist;

    // App
    private TextView tVersion;
    private String versionLine = "";

    /** What was shown last, so a rebuilt screen has something to show before the first reading arrives. */
    private PhoneScreenFacts facts;
    /** How many times the screen has been painted (the preview waits for it). */
    volatile int paints;

    // the last result, which a rebuilt screen shows again
    private String resultWhat = "";
    private String resultText = "";
    private boolean resultOk = true;
    private boolean resultWorking;

    private volatile boolean shown;
    private HandlerThread worker;
    private volatile Handler bg;

    private final CompoundButton.OnCheckedChangeListener bluetoothListener = (v, on) -> {
        Cfg.setBtLink(act, on);
        BtServer.recheck(); // never blocks: the listener opens or closes on its own thread
        refresh();
    };
    private final CompoundButton.OnCheckedChangeListener screenListener = (v, on) -> Cfg.setScreenControl(act, on);
    private final CompoundButton.OnCheckedChangeListener textsListener = (v, on) -> {
        Cfg.setTextsOn(act, on);
        if (on && !SmsAccess.missing(act).isEmpty()) {
            allowTexts();
        }
        refresh();
    };
    private final CompoundButton.OnCheckedChangeListener saverListener = (v, on) -> setSaver(on);
    private final CompoundButton.OnCheckedChangeListener androidListener = (v, on) -> setAndroidSaver(on);
    private final CompoundButton.OnCheckedChangeListener autoStartListener = (v, on) -> {
        Cfg.setAutoStart(act, on);
        refresh(); // Boot assist depends on it
    };
    private final CompoundButton.OnCheckedChangeListener assistListener = (v, on) -> {
        Boot.setAssist(act, on);
        refresh();
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            Handler h = bg;
            if (h == null) {
                return;
            }
            PhoneScreenFacts f = null;
            try {
                f = source.read(act);
            } catch (RuntimeException ignored) {
                // a reading that failed must not end the screen's updates: the next one is tried in two seconds
            }
            if (f != null) {
                final PhoneScreenFacts shownFacts = f;
                act.ui.post(() -> {
                    if (shown) {
                        paint(shownFacts);
                    }
                });
            }
            h.removeCallbacks(this);
            h.postDelayed(this, TICK_MS);
        }
    };

    PhoneTab(MainActivity a) {
        super(a);
    }

    @Override
    String title() {
        return PHONE;
    }

    @Override
    Icons.Kind icon() {
        return Icons.Kind.PHONE;
    }

    // ---------------------------------------------------------------------------------------------------- building

    @Override
    View build() {
        bar = new Ui.ResultBar(act);
        col = Ui.column(act);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.title(act, "LinkMeter"), Ui.weight());
        chip = Ui.chip(act, "Looking…", Ui.GREY);
        head.addView(chip);
        col.addView(head);
        summary = Ui.text(act, "", 13, Ui.DIM, false);
        col.addView(summary, Ui.lp(act, 2));

        // only while something has to be done first
        attentionCard = Ui.card(act);
        attentionHeading = Ui.heading(act, "");
        attentionCard.addView(attentionHeading);
        attentionText = Ui.hint(act, "");
        attentionCard.addView(attentionText, Ui.lp(act, 2));
        attentionButton = Ui.primary(act, "", v -> attentionPressed());
        attentionCard.addView(attentionButton, Ui.lp(act, 10));
        attentionCard.setVisibility(View.GONE);
        col.addView(attentionCard, Ui.lp(act, 10));

        buildStatus();
        buildBreaks();
        buildTabletLink();
        buildFast();
        buildPermissions();
        buildTexts();
        buildBattery();
        buildRestart();
        buildApp();

        if (resultWorking) {
            bar.working(resultWhat);
        } else if (!resultText.isEmpty()) {
            bar.working(resultWhat);
            bar.show(resultText, resultOk);
        }
        shownItemButtons = "";
        shownAttention = PhoneScreenRules.Attention.NONE;
        if (facts != null) {
            paint(facts);
        }
        return Ui.withResultBar(act, col, bar);
    }

    private void buildStatus() {
        LinearLayout card = Ui.card(act, "Status", null);
        fRunning = new Ui.Fact(act, "LinkMeter on this phone");
        fHotspot = new Ui.Fact(act, "Hotspot");
        fBluetooth = new Ui.Fact(act, "Bluetooth link");
        fShizukuTop = new Ui.Fact(act, "Shizuku");
        fTablet = new Ui.Fact(act, "Tablet");
        card.addView(fRunning.view, Ui.lp(act, 8));
        card.addView(fHotspot.view, Ui.lp(act, 6));
        card.addView(fBluetooth.view, Ui.lp(act, 6));
        card.addView(fShizukuTop.view, Ui.lp(act, 6));
        card.addView(fTablet.view, Ui.lp(act, 6));
        tRequests = Ui.hint(act, "");
        card.addView(tRequests, Ui.lp(act, 8));
        bStop = Ui.quiet(act, "Stop", v -> ask(PhoneScreenRules.stopQuestion(current()), () -> {
            if (SenderService.active) { // (the question may have stood open while the notification's Stop did it: Start must not follow)
                act.toggleSender();
            }
            refresh();
        }));
        card.addView(bStop, Ui.lp(act, 8));
        col.addView(card, Ui.lp(act, 12));
    }

    private void buildBreaks() {
        LinearLayout card = Ui.card(act, "If something breaks", PhoneScreenRules.BREAKS_HINT);
        card.addView(Ui.primary(act, "Restore everything", v -> restore()), Ui.lp(act, 10));
        card.addView(Ui.row(act, Ui.button(act, "Safety check", v -> safety()),
                Ui.button(act, "Mobile network settings", v -> act.openNetworkSettings())), Ui.lp(act, 4));
        card.addView(Ui.quiet(act, "Without Shizuku: what to do", v -> new AlertDialog.Builder(act).setTitle("Without Shizuku")
                .setMessage(Actions.MANUAL).setPositiveButton("OK", null).show()), Ui.lp(act, 4));
        card.addView(Ui.hint(act, PhoneScreenRules.RESTORE_HINT), Ui.lp(act, 6));
        col.addView(card, Ui.lp(act, 12));
    }

    private void buildTabletLink() {
        LinearLayout card = Ui.card(act, "Tablet link", PhoneScreenRules.PAIR_HINT);
        bForget = Ui.danger(act, PhoneScreenRules.FORGET_BUTTON, v -> ask(PhoneScreenRules.forgetQuestion(current()), () -> {
            Cfg.clearTrusted(act);
            say("Forgetting all tablets", "All tablets are forgotten. Pair a tablet again to use it.", true);
            refresh();
        }));
        card.addView(Ui.row(act, Ui.button(act, PhoneScreenRules.PAIR_BUTTON, v -> {
            act.pairTablet();
            refresh();
        }), bForget), Ui.lp(act, 10));
        tPairing = Ui.body(act, "");
        tPairing.setTextColor(Ui.AMBER);
        tPairing.setVisibility(View.GONE);
        card.addView(tPairing, Ui.lp(act, 6));
        card.addView(Ui.divider(act));
        tgBluetooth = new Ui.Toggle(act, PhoneScreenRules.BT_SWITCH, PhoneScreenRules.BT_EXPLAIN, Cfg.btLink(act), bluetoothListener);
        card.addView(tgBluetooth.view, Ui.lp(act, 6));
        tBluetooth = Ui.body(act, "");
        card.addView(tBluetooth, Ui.lp(act, 4));
        bBluetooth = Ui.button(act, "", v -> bluetoothButtonPressed());
        bBluetooth.setVisibility(View.GONE);
        card.addView(bBluetooth, Ui.lp(act, 6));
        card.addView(Ui.divider(act));
        tgScreen = new Ui.Toggle(act, PhoneScreenRules.SCREEN_SWITCH, PhoneScreenRules.SCREEN_EXPLAIN, Cfg.screenControl(act), screenListener);
        card.addView(tgScreen.view, Ui.lp(act, 6));
        col.addView(card, Ui.lp(act, 12));
    }

    private void buildPermissions() {
        LinearLayout card = Ui.card(act);
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.heading(act, "Shizuku and permissions"), Ui.weight());
        chipPermissions = Ui.chip(act, "", Ui.GREY);
        head.addView(chipPermissions);
        card.addView(head);
        fShizuku = new Ui.Fact(act, "Shizuku");
        card.addView(fShizuku.view, Ui.lp(act, 8));
        for (PhoneScreenRules.Item i : PhoneScreenRules.Item.values()) {
            fItem[i.ordinal()] = new Ui.Fact(act, PhoneScreenRules.itemName(i));
            card.addView(fItem[i.ordinal()].view, Ui.lp(act, 6));
        }
        // the facts stay together; what can be pressed comes after them: Shizuku first (it gives the rest), then one button or several
        bShizuku = Ui.button(act, "", v -> shizukuPressed());
        bShizuku.setVisibility(View.GONE);
        card.addView(bShizuku, Ui.lp(act, 10));
        bAllowEverything = Ui.primary(act, "Allow everything", v -> allowEverything());
        bAllowEverything.setVisibility(View.GONE);
        card.addView(bAllowEverything, Ui.lp(act, 10));
        itemButtons = new LinearLayout(act);
        itemButtons.setOrientation(LinearLayout.VERTICAL);
        card.addView(itemButtons, Ui.lp(act, 2));
        card.addView(Ui.hint(act, PhoneScreenRules.PERMISSIONS_HINT), Ui.lp(act, 8));
        col.addView(card, Ui.lp(act, 12));
    }

    private void buildFast() {
        LinearLayout card = Ui.card(act, "Fast Wi-Fi", null);
        tgFast = new Ui.Toggle(act, "Let the tablet use Fast Wi-Fi",
                "The tablet's internet goes through LinkMeter on this phone instead of the hotspot's tethering, so the carrier's "
                + "hotspot speed limit does not apply to it. Only the paired tablet can use it. Switch it on and off on the tablet.",
                Cfg.fastAllowed(act), fastListener);
        card.addView(tgFast.view, Ui.lp(act, 6));
        tFast = Ui.body(act, FastRelay.line(act));
        card.addView(tFast, Ui.lp(act, 4));
        col.addView(card, Ui.lp(act, 12));
    }

    private void buildTexts() {
        LinearLayout card = Ui.card(act, "Texts", null);
        tgTexts = new Ui.Toggle(act, PhoneScreenRules.TEXTS_SWITCH, PhoneScreenRules.TEXTS_EXPLAIN, Cfg.textsOn(act), textsListener);
        card.addView(tgTexts.view, Ui.lp(act, 6));
        tTexts = Ui.body(act, "");
        card.addView(tTexts, Ui.lp(act, 4));
        bTexts = Ui.button(act, PhoneScreenRules.TEXTS_BUTTON, v -> allowTexts());
        bTexts.setVisibility(View.GONE);
        card.addView(bTexts, Ui.lp(act, 6));
        card.addView(Ui.hint(act, PhoneScreenRules.TEXTS_RCS), Ui.lp(act, 6));
        col.addView(card, Ui.lp(act, 12));
    }

    private void buildBattery() {
        LinearLayout card = Ui.card(act, "Battery", null);
        tgSaver = new Ui.Toggle(act, PhoneScreenRules.SAVER_SWITCH, PhoneScreenRules.SAVER_EXPLAIN, Cfg.ecoAuto(act), saverListener);
        card.addView(tgSaver.view, Ui.lp(act, 6));
        fSaver = new Ui.Fact(act, "LinkMeter's saver right now");
        card.addView(fSaver.view, Ui.lp(act, 6));
        tgAndroid = new Ui.Toggle(act, PhoneScreenRules.ANDROID_SAVER_SWITCH, PhoneScreenRules.ANDROID_SAVER_EXPLAIN, Cfg.ecoSystem(act),
                androidListener);
        card.addView(tgAndroid.view, Ui.lp(act, 6));
        fAndroid = new Ui.Fact(act, "Android's Battery saver right now");
        card.addView(fAndroid.view, Ui.lp(act, 6));
        // only while Android limits LinkMeter in the background
        batteryLimit = new LinearLayout(act);
        batteryLimit.setOrientation(LinearLayout.VERTICAL);
        batteryLimit.addView(Ui.divider(act));
        TextView tip = Ui.body(act, "Android may stop LinkMeter in the background.");
        tip.setTextColor(Ui.AMBER);
        batteryLimit.addView(tip, Ui.lp(act, 6));
        batteryLimit.addView(Ui.hint(act, PhoneScreenRules.BATTERY_TIP), Ui.lp(act, 2));
        batteryLimit.addView(Ui.button(act, PhoneScreenRules.itemButtonLabel(PhoneScreenRules.Item.BATTERY),
                v -> act.openBatterySettings()), Ui.lp(act, 8));
        batteryLimit.setVisibility(View.GONE);
        card.addView(batteryLimit, Ui.lp(act, 4));
        col.addView(card, Ui.lp(act, 12));
    }

    private void buildRestart() {
        LinearLayout card = Ui.card(act, "After a restart", null);
        tgAutoStart = new Ui.Toggle(act, PhoneScreenRules.AUTOSTART_SWITCH, PhoneScreenRules.AUTOSTART_EXPLAIN, Cfg.autoStart(act),
                autoStartListener);
        card.addView(tgAutoStart.view, Ui.lp(act, 6));
        tgAssist = new Ui.Toggle(act, PhoneScreenRules.ASSIST_SWITCH, null, Boot.assist(act), assistListener);
        card.addView(tgAssist.view, Ui.lp(act, 6));
        tAssist = Ui.hint(act, "");
        card.addView(tAssist, Ui.lp(act, 2));
        card.addView(Ui.hint(act, PhoneScreenRules.RESTART_HINT), Ui.lp(act, 8));
        col.addView(card, Ui.lp(act, 12));
    }

    private void buildApp() {
        LinearLayout card = Ui.card(act, "App", null);
        tVersion = Ui.hint(act, versionLine);
        card.addView(tVersion, Ui.lp(act, 6));
        card.addView(Ui.row(act, Ui.quiet(act, "App info and permissions", v -> act.openAppInfo()),
                Ui.quiet(act, "Switch role", v -> ask(PhoneScreenRules.roleQuestion(current()), () -> act.chooseRole("")))), Ui.lp(act, 10));
        col.addView(card, Ui.lp(act, 12));
    }

    // ----------------------------------------------------------------------------------------------- showing and hiding

    @Override
    void onShow() {
        shown = true;
        readVersion(); // once, not at every tick: it reads the update log
        if (worker == null) {
            worker = new HandlerThread("linkmeter-phone-screen");
            worker.start();
            bg = new Handler(worker.getLooper());
        }
        refresh();
    }

    @Override
    void onHide() {
        shown = false;
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

    /** Reads the facts again soon (on their own thread) and paints them. Called after every button, and by MainActivity. */
    void refresh() {
        Handler h = bg;
        if (h != null) {
            h.removeCallbacks(poll);
            h.post(poll);
        }
    }

    /** Reads and paints now, on the calling thread (the screen's): for the screen preview. */
    void refreshNow() {
        paint(source.read(act));
    }

    private void readVersion() {
        String last = Updater.lastResult(act);
        versionLine = "App version " + Updater.versionName(act) + " (code " + Updater.versionCode(act) + ")"
                + (last.isEmpty() ? "" : ". Last update from the tablet: " + last);
        if (tVersion != null) {
            tVersion.setText(versionLine);
        }
    }

    private PhoneScreenFacts current() {
        return facts != null ? facts : new PhoneScreenFacts();
    }

    // ------------------------------------------------------------------------------------------------------- painting

    private static int factColor(PhoneScreenRules.Tone t) {
        return t == PhoneScreenRules.Tone.GOOD ? Ui.GREEN : t == PhoneScreenRules.Tone.WARN ? Ui.AMBER : Ui.DIM;
    }

    private static int lineColor(PhoneScreenRules.Tone t) {
        return t == PhoneScreenRules.Tone.GOOD ? Ui.FG : t == PhoneScreenRules.Tone.WARN ? Ui.AMBER : Ui.DIM;
    }

    private static void fact(Ui.Fact f, PhoneScreenRules.Line l) {
        f.set(l.text, factColor(l.tone));
    }

    private static void visible(View v, boolean on) {
        v.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    /** A switch is moved only when it is not where the facts say, so a repaint cannot disturb a finger that is on it. */
    private static void toggle(Ui.Toggle t, boolean on, CompoundButton.OnCheckedChangeListener l) {
        if (t.sw.isChecked() != on) {
            t.show(on, l);
        }
    }

    private void paint(PhoneScreenFacts f) {
        if (col == null || chip == null) {
            return;
        }
        facts = f;
        paints++;
        if (tFast != null) {
            tFast.setText(FastRelay.line(act));
            tgFast.show(Cfg.fastAllowed(act), fastListener);
        }
        PhoneScreenRules.Chip c = PhoneScreenRules.chip(f);
        Ui.paintChip(chip, PhoneScreenRules.chipText(c), factColor(PhoneScreenRules.chipTone(c)));
        summary.setText(PhoneScreenRules.summary(f));

        PhoneScreenRules.Attention a = PhoneScreenRules.attention(f);
        shownAttention = a;
        visible(attentionCard, a != PhoneScreenRules.Attention.NONE);
        if (a != PhoneScreenRules.Attention.NONE) {
            attentionHeading.setText(PhoneScreenRules.attentionHeading(a));
            attentionText.setText(PhoneScreenRules.attentionText(a));
            attentionButton.setText(PhoneScreenRules.attentionButton(a));
        }

        // Status
        PhoneScreenRules.BtLine bt = PhoneScreenRules.btLine(f);
        fact(fRunning, PhoneScreenRules.runningFact(f));
        fact(fHotspot, PhoneScreenRules.hotspotFact(f));
        fact(fBluetooth, bt.value);
        fact(fShizukuTop, PhoneScreenRules.shizukuFact(f));
        fact(fTablet, PhoneScreenRules.tabletFact(f));
        tRequests.setText(PhoneScreenRules.requestsLine(f));
        visible(bStop, f.running);

        // Tablet link
        bForget.setEnabled(f.tablets > 0);
        String pairing = PhoneScreenRules.pairingLine(f);
        tPairing.setText(pairing);
        visible(tPairing, !pairing.isEmpty());
        toggle(tgBluetooth, f.btSwitch, bluetoothListener);
        tBluetooth.setText(bt.sentence);
        tBluetooth.setTextColor(lineColor(bt.value.tone));
        shownBtButton = bt.button;
        visible(bBluetooth, bt.button != PhoneScreenRules.BtButton.NONE);
        bBluetooth.setText(PhoneScreenRules.btButtonLabel(bt.button));
        toggle(tgScreen, f.screenControl, screenListener);

        // Shizuku and permissions
        PhoneScreenRules.Line pc = PhoneScreenRules.permissionsChip(f);
        Ui.paintChip(chipPermissions, pc.text, factColor(pc.tone));
        fact(fShizuku, PhoneScreenRules.shizukuFact(f));
        String shizukuButton = PhoneScreenRules.shizukuButton(f);
        visible(bShizuku, !shizukuButton.isEmpty());
        bShizuku.setText(shizukuButton);
        for (PhoneScreenRules.Item i : PhoneScreenRules.Item.values()) {
            fact(fItem[i.ordinal()], PhoneScreenRules.itemValue(f, i));
        }
        visible(bAllowEverything, PhoneScreenRules.allowEverything(f));
        paintItemButtons(f);

        // Texts
        toggle(tgTexts, f.textsOn, textsListener);
        PhoneScreenRules.Line tl = PhoneScreenRules.textsLine(f);
        tTexts.setText(tl.text);
        tTexts.setTextColor(lineColor(tl.tone));
        visible(tTexts, !tl.text.isEmpty());
        visible(bTexts, PhoneScreenRules.textsButton(f));

        // Battery
        toggle(tgSaver, f.saverAuto, saverListener);
        fact(fSaver, PhoneScreenRules.saverFact(f));
        toggle(tgAndroid, f.androidWanted, androidListener);
        fact(fAndroid, PhoneScreenRules.androidSaverFact(f));
        visible(batteryLimit, PhoneScreenRules.batteryButton(f));

        // After a restart
        toggle(tgAutoStart, f.autoStart, autoStartListener);
        PhoneScreenRules.Assist as = PhoneScreenRules.assist(f);
        toggle(tgAssist, f.bootAssist, assistListener);
        // it can always be switched off, also when it cannot run: a switch that is on in name only must not be stuck on
        boolean usable = as.enabled || f.bootAssist;
        tgAssist.sw.setEnabled(usable);
        tgAssist.view.setEnabled(usable);
        tgAssist.view.setAlpha(as.enabled ? 1f : 0.5f);
        tAssist.setText(as.hint);
        tAssist.setTextColor(!as.enabled && f.bootAssist ? Ui.AMBER : Ui.DIM);
    }

    /** The buttons for single permissions are rebuilt only when the list changes, not at every tick. */
    private void paintItemButtons(PhoneScreenFacts f) {
        final java.util.List<PhoneScreenRules.Item> items = PhoneScreenRules.itemButtons(f);
        String key = items.toString();
        if (key.equals(shownItemButtons)) {
            return;
        }
        shownItemButtons = key;
        itemButtons.removeAllViews();
        for (final PhoneScreenRules.Item i : items) {
            itemButtons.addView(Ui.button(act, PhoneScreenRules.itemButtonLabel(i), v -> askItem(i)), Ui.lp(act, 6));
        }
    }

    // ------------------------------------------------------------------------------------------------------ the buttons

    /** A question before something that cuts the tablet off or cannot be undone: a stray tap, also one from the tablet's Screen tab, must not do it. */
    private void ask(PhoneScreenRules.Question q, final Runnable yes) {
        new AlertDialog.Builder(act).setTitle(q.title).setMessage(q.message)
                .setPositiveButton(q.yes, (d, w) -> yes.run()).setNegativeButton(q.no, null).show();
    }

    private void attentionPressed() {
        switch (shownAttention) {
            case STOPPED:
                if (!SenderService.active) { // (the card may be a moment old: a service that has started since must not be stopped)
                    act.toggleSender();
                }
                break;
            case NO_TABLET:
                act.pairTablet();
                break;
            case SHIZUKU_OFF:
                act.openShizuku();
                break;
            case SHIZUKU_NOT_ALLOWED:
                Priv.requestPermission(2);
                break;
            case BLUETOOTH_NOT_ALLOWED:
                allowBluetooth();
                break;
            case BLUETOOTH_OFF:
                switchBluetoothOn();
                break;
            default:
                break;
        }
        refresh();
    }

    private void shizukuPressed() {
        if (PhoneScreenFacts.Shizuku.OFF == current().shizuku) {
            act.openShizuku();
        } else {
            Priv.requestPermission(2);
        }
    }

    private void bluetoothButtonPressed() {
        switch (shownBtButton) {
            case ALLOW:
                allowBluetooth();
                break;
            case SWITCH_ON:
                switchBluetoothOn();
                break;
            case TRY_AGAIN:
                say("Bluetooth link", "Trying again…", true);
                BtServer.recheck();
                break;
            default:
                break;
        }
        refresh();
    }

    private void askItem(PhoneScreenRules.Item i) {
        switch (i) {
            case PHONE_STATE:
                act.requestPhonePermission();
                break;
            case NOTIFICATIONS:
                act.requestNotificationPermission();
                break;
            case FILES:
                act.fixStorageAccess();
                break;
            default:
                break;
        }
    }

    /** Shows what a button did, at the bottom of the screen. */
    private void say(String what, String text, boolean ok) {
        resultWhat = what;
        resultText = text;
        resultOk = ok;
        resultWorking = false;
        bar.working(what);
        bar.show(text, ok);
    }

    private void working(String what) {
        resultWhat = what;
        resultText = "";
        resultWorking = true;
        bar.working(what);
    }

    /** The answer of a button that ran on its own thread. */
    private void result(final String text, final boolean ok) {
        act.ui.post(() -> {
            resultText = text;
            resultOk = ok;
            resultWorking = false;
            bar.show(text, ok);
            refresh();
        });
    }

    /** What a button does off the screen's thread; it answers through {@link #result}. */
    private interface Work {
        void run(Context app) throws IOException;
    }

    /**
     * Runs a button's work on a thread of its own. Whatever happens, the strip is answered: an exception that the work did not expect
     * would otherwise leave "…" on the screen for ever.
     *
     * @param ioPrefix put before the message of an IOException ("" where the work's own sentence is already a whole one)
     * @param broken what to say when something unexpected went wrong
     */
    private void off(String threadName, final String ioPrefix, final String broken, final Work work) {
        final Context app = act.getApplicationContext();
        new Thread(() -> {
            try {
                work.run(app);
            } catch (IOException e) {
                result(ioPrefix + e.getMessage(), false);
            } catch (RuntimeException e) {
                result(broken, false);
            }
        }, threadName).start();
    }

    private void restore() {
        final boolean ready = Priv.ready(); // without Shizuku the phone can only say what to do by hand
        working("Restoring everything");
        off("linkmeter-restore", "", "Restore everything did not finish. Use the steps under \"Without Shizuku\".",
                app -> result(Actions.restoreAll(app), ready));
    }

    private void safety() {
        working("Checking the phone");
        off("linkmeter-safety", "Couldn't check the phone: ", "The check did not finish.", app -> result(Actions.run(app, "safety"), true));
    }

    /** Gets the texts permissions: through Shizuku if it is ready, otherwise through Android's own permission question. */
    private void allowTexts() {
        if (Priv.ready()) {
            working("Allowing texts access");
            off("linkmeter-sms-grant", "Couldn't allow texts access: ", "Texts access could not be allowed.",
                    app -> result(SmsAccess.grant(app), SmsAccess.missing(app).isEmpty()));
        } else {
            act.requestTextsPermissions();
            Toast.makeText(act, "If Android says the setting is restricted, start Shizuku and tap this again, or allow restricted "
                    + "settings in this app's info page.", Toast.LENGTH_LONG).show();
        }
    }

    /** Through Shizuku, which can give every permission at once (the button is only offered while it is ready). */
    private void allowEverything() {
        working("Allowing everything");
        off("linkmeter-allow-everything", "", "Not everything could be allowed.", app -> {
            StringBuilder sb = new StringBuilder();
            boolean ok = true;
            try {
                sb.append(Actions.run(app, "setup"));
            } catch (IOException e) {
                sb.append(e.getMessage());
                ok = false;
            }
            if (Cfg.btLink(app) && !BtServer.hasPermission(app)) {
                try {
                    sb.append('\n').append(BtServer.grantPermission(app) ? "LinkMeter may now use Bluetooth."
                            : "The phone did not give the Bluetooth permission.");
                    BtServer.recheck();
                } catch (IOException e) {
                    sb.append("\nBluetooth: ").append(e.getMessage());
                    ok = false;
                }
            }
            if (Cfg.textsOn(app) && !SmsAccess.missing(app).isEmpty()) {
                try {
                    sb.append('\n').append(SmsAccess.grant(app));
                } catch (IOException e) {
                    sb.append("\nTexts: ").append(e.getMessage());
                    ok = false;
                }
            }
            result(sb.toString(), ok);
        });
    }

    private void allowBluetooth() {
        if (!Priv.ready()) {
            act.requestBluetoothPermission(); // Android's own question; the answer makes the listener look again
            return;
        }
        working("Allowing Bluetooth");
        off("linkmeter-bt-allow", "Couldn't allow Bluetooth: ", "Bluetooth could not be allowed.", app -> {
            boolean ok = BtServer.grantPermission(app);
            BtServer.recheck();
            result(ok ? "LinkMeter may now use Bluetooth. The link starts listening."
                    : "The phone did not give the Bluetooth permission. Open the app's info page and allow Nearby devices.", ok);
        });
    }

    private void switchBluetoothOn() {
        if (!Priv.ready()) {
            act.askBluetoothOn(); // Android's own question
            return;
        }
        working("Switching Bluetooth on");
        off("linkmeter-bt-on", "Couldn't switch Bluetooth on: ", "Bluetooth could not be switched on.", app -> {
            boolean ok = BtServer.switchOn(app);
            BtServer.recheck();
            result(ok ? "Bluetooth is on." : "Bluetooth could not be switched on. Switch it on in the quick settings.", ok);
        });
    }

    /** The same switch the tablet's buttons move ({@link Eco#setAuto}): the choice is stored at once, the shell work is off the screen's thread. */
    private void setSaver(final boolean on) {
        Cfg.setEcoAuto(act, on);
        working(on ? "Switching LinkMeter's saver to Automatic" : "Switching LinkMeter's saver off");
        off("linkmeter-eco-switch", "", "The battery saver could not be changed.", app -> result(Eco.setAuto(app, on), true));
    }

    /** The option: also switch on Android's OWN Battery saver with the saver. The same switch the tablet's choice moves ({@link Eco#setSystem}). */
    private void setAndroidSaver(final boolean on) {
        working(on ? "Switching Android's own Battery saver on with LinkMeter's saver" : "Leaving Android's own Battery saver alone");
        off("linkmeter-eco-android", "", "Android's own Battery saver could not be changed.", app -> result(Eco.setSystem(app, on), true));
    }

    // ------------------------------------------------------------------------------------------------------ the facts

    /** Reads what the screen shows from the phone. Not for the screen's thread (the hotspot check lists the network interfaces). */
    static PhoneScreenFacts readFacts(MainActivity a) {
        PhoneScreenFacts f = new PhoneScreenFacts();
        f.running = SenderService.active;
        f.tablets = Cfg.trusted(a).size();
        long left = SenderService.pairUntil - SystemClock.elapsedRealtime();
        f.pairingLeftSec = left > 0 ? (int) ((left + 999) / 1000) : 0;
        f.requests = Api.hits;
        f.lastNote = Api.lastNote;
        f.lastAtText = f.requests == 0 ? "" : DateFormat.getTimeInstance().format(new Date(Api.lastAt));

        f.shizuku = PhoneScreenFacts.shizukuFrom(Priv.state());
        f.phoneStateAllowed = a.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
        f.notificationsAllowed = Build.VERSION.SDK_INT < 33
                || a.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        f.filesAllowed = Files.hasAccess(a);
        f.btPermission = BtServer.hasPermission(a);
        PowerManager pm = (PowerManager) a.getSystemService(Context.POWER_SERVICE);
        f.batteryUnrestricted = pm != null && pm.isIgnoringBatteryOptimizations(a.getPackageName());

        f.btSwitch = Cfg.btLink(a);
        f.bt = PhoneScreenFacts.btFrom(BtServer.state());
        f.btNote = BtServer.note();
        f.btAsked = BtServer.lastAskedText();
        f.bluetoothHere = BtServer.adapter(a) != null;
        if (f.bt == PhoneScreenFacts.Bt.PERM && f.btPermission) {
            BtServer.recheck(); // the permission was given in Android's own settings: the listener has not noticed yet
        }

        f.hotspot = Radios.hotspot(a);
        f.hotspotAddress = Boolean.TRUE.equals(f.hotspot) ? Boot.hotspotAddress(a) : "";

        f.textsOn = Cfg.textsOn(a);
        f.textsMissing = SmsAccess.missing(a);
        f.saverAuto = Cfg.ecoAuto(a);
        f.saverOn = Eco.isOn(a);
        f.androidOn = Radios.powerSave(a);
        f.androidWanted = Cfg.ecoSystem(a);
        f.androidOurs = Eco.systemOn(a);
        f.screenControl = Cfg.screenControl(a);
        f.autoStart = Cfg.autoStart(a);
        f.bootAssist = Boot.assist(a);
        f.bootPermission = Boot.canWriteSecure(a);
        return f;
    }
}
