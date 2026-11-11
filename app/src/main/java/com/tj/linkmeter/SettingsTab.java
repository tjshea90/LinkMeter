package com.tj.linkmeter;

import android.Manifest;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;

/**
 * The tablet's own settings, in groups: the Bluetooth link (the way to the phone while its hotspot is off), the connection, this
 * tablet, and pairing. Nothing here runs in the background.
 */
final class SettingsTab extends Tab {
    private TextView linkChip;
    private TextView linkState;
    private TextView linkSaid;
    /** The line under the phone address field. */
    private TextView usingView;
    private Button setUpBtn;
    private Button testBtn;
    private Button findBtn;
    /** What the last press of a link button said; kept when the views are rebuilt. */
    private String linkMsg = "";
    private boolean linkBusy;

    SettingsTab(MainActivity a) {
        super(a);
    }

    @Override
    String title() {
        return SETTINGS;
    }

    @Override
    Icons.Kind icon() {
        return Icons.Kind.GEAR;
    }

    @Override
    View build() {
        LinearLayout col = Ui.column(act);
        col.addView(Ui.title(act, SETTINGS));
        buildLink(col);
        buildConnection(col);
        buildTablet(col);
        buildPairing(col);
        return Ui.scroll(act, col);
    }

    @Override
    void onShow() {
        paintLink();
    }

    // ------------------------------------------------------------------------------------------------ Bluetooth link

    private void buildLink(LinearLayout col) {
        LinearLayout card = Ui.card(act);
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.heading(act, "Bluetooth link"), Ui.weight());
        linkChip = Ui.chip(act, "", Ui.GREY);
        head.addView(linkChip);
        card.addView(head);
        card.addView(Ui.hint(act, "Reaches the phone while its hotspot is off, so the tablet can switch the hotspot (and the phone's "
                + "Wi-Fi, screen and standby) on and off by itself. It carries the switches and the status only: internet, files, "
                + "texts and the phone's screen still need the hotspot."), Ui.lp(act, 2));
        // how far the link can be relied on, from what the tablet knows: always the present state, whatever a button last said
        linkState = Ui.body(act, "");
        card.addView(linkState, Ui.lp(act, 8));
        // what the last press of a button here answered
        linkSaid = Ui.hint(act, "");
        card.addView(linkSaid, Ui.lp(act, 6));
        setUpBtn = Ui.primary(act, "Set up", v -> setUp());
        testBtn = Ui.button(act, "Test", v -> test());
        card.addView(Ui.row(act, setUpBtn, testBtn), Ui.lp(act, 8));
        findBtn = Ui.quiet(act, "Find the phone among paired Bluetooth devices", v -> find());
        card.addView(findBtn, Ui.lp(act, 2));
        card.addView(Ui.hint(act, "\"Find\" is only needed if Set up says the phone would not tell its Bluetooth address: pair the "
                + "phone and the tablet once in Android's Bluetooth settings first."), Ui.lp(act, 2));
        card.addView(Ui.divider(act));
        final Ui.Toggle[] use = new Ui.Toggle[1];
        use[0] = new Ui.Toggle(act, "Use Bluetooth when the hotspot is off",
                "Off: the tablet only ever talks to the phone over the hotspot.", BtLink.enabled(act), (b, on) -> {
                    BtLink.setEnabled(act, on);
                    paintLink();
                });
        card.addView(use[0].view);
        col.addView(card, Ui.lp(act, 12));
        paintLink();
    }

    /**
     * The chip and the state line say how far the link can be relied on, from what the tablet has seen (LinkState): "Ready"
     * only when the phone's address is stored, a call to it has been answered, the latest call did not fail and the phone's last
     * status says its side is listening. Anything less is named, with the button that is the way forward.
     */
    private void paintLink() {
        if (linkChip == null) {
            return;
        }
        LinkState.Kind k = BtLink.state(act);
        boolean on = BtLink.enabled(act);
        boolean ready = k == LinkState.Kind.READY;
        Ui.paintChip(linkChip, LinkState.chip(k), ready ? Ui.GREEN : on ? Ui.AMBER : Ui.GREY);
        StringBuilder state = new StringBuilder(BtLink.words(act));
        long okAt = Cfg.btOkAt(act);
        if (okAt > 0 && on) {
            String name = Cfg.getStr(act, "btName", "");
            state.append("\nLast answered over Bluetooth: ")
                    .append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(okAt)))
                    .append(name.isEmpty() ? "" : " (" + name + ")").append('.');
        }
        if (on && BtLink.tabletBluetoothOff(act)) {
            state.append("\nBluetooth is off on this tablet right now. A button that needs it switches it on; reading the "
                    + "status does not.");
        }
        linkState.setText(state);
        linkState.setTextColor(!ready && on ? Ui.AMBER : Ui.FG);
        linkSaid.setText(linkMsg);
        linkSaid.setVisibility(linkMsg.isEmpty() ? View.GONE : View.VISIBLE);
        boolean callable = BtLink.ready(act); // there is an address to call
        setUpBtn.setEnabled(on && !linkBusy);
        setUpBtn.setText(callable ? "Set up again" : "Set up");
        testBtn.setEnabled(on && callable && !linkBusy);
        findBtn.setEnabled(on && !linkBusy);
    }

    private void say(String s) {
        linkMsg = s;
        paintLink();
    }

    private void busy(boolean b) {
        linkBusy = b;
        paintLink();
    }

    /** Android 12 and newer ask the user before an app may use Bluetooth (the user's tablet is older and is never asked). */
    private boolean permitted() {
        if (BtServer.hasPermission(act)) {
            return true;
        }
        act.requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT}, MainActivity.REQ_BLUETOOTH);
        say("Allow \"Nearby devices\" for LinkMeter, then press the button again.");
        return false;
    }

    /**
     * The whole setup from the tablet, while it is on the phone's hotspot: the phone gives itself the Bluetooth permission and
     * switches Bluetooth on (through Shizuku), reads its own Bluetooth address and tells it; then the tablet calls it once.
     */
    private void setUp() {
        if (!permitted()) {
            return;
        }
        busy(true);
        say("Asking the phone to get its side ready…");
        Call.run(act, "POST", "/act?name=bt_setup", 60000, (ok, text, json) -> {
            if (!ok) {
                busy(false);
                say(text.contains("unknown action")
                        ? "The phone's app is too old for this. Update it first: " + PHONE + " tab > Update phone from this tablet."
                        : json != null ? text // the phone answered: its own words say why its side is not ready
                        : "The phone could not be asked: " + text + "\nThe setup needs the tablet on the phone's hotspot, and "
                        + "Shizuku running on the phone.");
                return;
            }
            final String phoneSaid = text;
            say(phoneSaid + "\nReading the phone's Bluetooth address…");
            Call.run(act, "GET", "/s", 6000, (ok2, text2, json2) -> {
                if (!ok2 || json2 == null) {
                    // the question did not get an answer: that says nothing about the phone's address, so no pairing advice
                    busy(false);
                    say(phoneSaid + "\nThen the phone's status could not be read (" + text2 + "), so the tablet does not have "
                            + "the phone's Bluetooth address yet. Press Set up again.");
                    return;
                }
                if (Reach.WIFI.equals(Call.lastVia(act))) {
                    // (only a status that came over the hotspot is believed about the address: Bluetooth replies are not signed)
                    BtLink.heardOverHotspot(act, json2);
                    String mac = json2.optString("btmac", "");
                    if (!BtAddr.valid(mac)) {
                        busy(false);
                        say(phoneSaid + "\nThe phone did not tell its Bluetooth address. Pair the phone and the tablet once in "
                                + "Android's Bluetooth settings (on both: Settings > Bluetooth, tap the other device), then press "
                                + "\"Find the phone among paired Bluetooth devices\".");
                        return;
                    }
                    Cfg.setBtMac(act, mac); // Set up is the one place where an address that had been proved is replaced
                } else if (!BtAddr.valid(Cfg.btMac(act))) {
                    busy(false);
                    say(phoneSaid + "\nThe tablet has to be on the phone's hotspot to learn the phone's Bluetooth address. Join "
                            + "the hotspot and press Set up again.");
                    return;
                }
                say(phoneSaid + "\nCalling the phone over Bluetooth to try it…");
                tryLink(phoneSaid + "\n");
            });
        });
    }

    private void test() {
        if (!permitted()) {
            return;
        }
        busy(true);
        say("Calling the phone over Bluetooth…");
        tryLink("");
    }

    /** One question over Bluetooth, off the screen's thread; the answer says whether the link works. */
    private void tryLink(final String before) {
        new Thread(() -> {
            String out;
            try {
                BtLink.close(); // a fresh connection: this is a test of calling, not of a connection that happens to be open
                Client.Resp r = BtLink.call(act, "GET", "/s", 8000, true);
                if (r.code == 200) {
                    out = "The phone answered over Bluetooth just now. When its hotspot is off, the " + HOME + " tab offers "
                            + "\"Turn the hotspot on\".";
                } else if (r.code == 401) {
                    out = "The phone answered over Bluetooth but does not know this tablet. Pair them in LinkMeter first (on the "
                            + "phone: Pair tablet, with the tablet on the hotspot).";
                } else {
                    out = "The phone answered over Bluetooth with an error (" + r.code + "): " + r.body;
                }
            } catch (IOException e) {
                out = "Bluetooth did not get through: " + e.getMessage() + ".\nIf Android asked to pair on the phone or on the "
                        + "tablet, accept it on both and press Test. The two devices must be within Bluetooth range (about one "
                        + "or two rooms).";
            }
            final String fOut = out;
            act.ui.post(() -> {
                busy(false);
                say(before + fOut);
            });
        }, "linkmeter-bt-test").start();
    }

    private void find() {
        if (!permitted()) {
            return;
        }
        busy(true);
        say("Trying each paired Bluetooth device (a few seconds each)…");
        final String prefer = Cfg.getStr(act, "btName", "");
        new Thread(() -> {
            final String out = BtLink.findAmongPaired(act, prefer);
            act.ui.post(() -> {
                busy(false);
                say(out);
            });
        }, "linkmeter-bt-find").start();
    }

    // ------------------------------------------------------------------------------------------------ connection

    /** The line under the phone address field: which address is in use right now, and how it was found. */
    private void paintUsing(boolean notSaved) {
        if (usingView == null) {
            return;
        }
        String found = Cfg.host(act).isEmpty() ? Net.phoneHost(act) : "";
        usingView.setText(PhoneAddress.using(notSaved, Cfg.host(act), found));
    }

    private void buildConnection(LinearLayout col) {
        LinearLayout card = Ui.card(act, "Connection", null);
        card.addView(Ui.text(act, "Phone address", 14, Ui.FG, false), Ui.lp(act, 8));
        EditText host = new EditText(act);
        host.setSingleLine(true);
        host.setKeyListener(DigitsKeyListener.getInstance("0123456789."));
        host.setTextColor(Ui.FG);
        host.setHint("automatic");
        host.setHintTextColor(Ui.DIM);
        host.setText(Cfg.host(act));
        host.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable e) {
                // only a complete address, or nothing, is stored: a half typed "192." must not switch the automatic search off everywhere
                String store = PhoneAddress.toStore(e.toString());
                if (store != null) {
                    Cfg.setHost(act, store);
                }
                paintUsing(store == null);
            }
        });
        card.addView(host);
        usingView = Ui.hint(act, "");
        card.addView(usingView, Ui.lp(act, 2));
        paintUsing(false);
        card.addView(Ui.hint(act, "Leave it empty: on the phone's hotspot the tablet finds the phone by itself. Type an address "
                + "only if " + HOME + " says it cannot find the phone."), Ui.lp(act, 6));

        card.addView(Ui.text(act, "Ask the phone for its status every", 14, Ui.FG, false), Ui.lp(act, 12));
        final long[] values = {15000L, 5000L, 2000L};
        long cur = Cfg.intervalMs(act);
        int sel = cur >= 15000L ? 0 : cur <= 2000L ? 2 : 1;
        Ui.Choice every = new Ui.Choice(act, new String[] {"15 s", "5 s", "2 s"}, sel, i -> Cfg.setIntervalMs(act, values[i]));
        card.addView(every.view, Ui.lp(act, 2));
        card.addView(Ui.hint(act, "Only while the " + HOME + " tab is open. Longer saves battery on both devices. Over Bluetooth "
                + "every question wakes the phone, so there it is every 15 s at most, once a minute after the first minute, and "
                + "not at all after five minutes."), Ui.lp(act, 2));
        col.addView(card, Ui.lp(act, 12));
    }

    // ------------------------------------------------------------------------------------------------ this tablet

    private void buildTablet(LinearLayout col) {
        LinearLayout card = Ui.card(act, "This tablet", null);
        Ui.Toggle follow = new Ui.Toggle(act, "Switch the tablet's Wi-Fi with the hotspot",
                "\"Hotspot on\" switches this tablet's Wi-Fi on; \"Hotspot off\" and Standby switch it off. Android does not let an app "
                + "do that by itself: LinkMeter opens Android's Wi-Fi switch for one tap (with Shizuku running on this tablet it "
                + "needs none). A tablet that is on another Wi-Fi network keeps its Wi-Fi.",
                Cfg.wifiFollow(act), (b, on) -> Cfg.setWifiFollow(act, on));
        card.addView(follow.view, Ui.lp(act, 6));
        Ui.Toggle keep = new Ui.Toggle(act, "Keep the screen on in every tab",
                "The " + SCREEN + " tab keeps the tablet awake by itself while it is open (its own option). This is for all the other tabs.",
                Cfg.keepOn(act), (b, on) -> {
                    Cfg.setKeepOn(act, on);
                    act.applyKeepOn();
                });
        card.addView(keep.view, Ui.lp(act, 6));
        Ui.Toggle share = new Ui.Toggle(act, "Let the phone browse this tablet's files",
                "Runs a small file service on the tablet (a notification shows while it is on). The " + FILES + " tab on the tablet does "
                + "not need it.", Cfg.shareFiles(act), (b, on) -> {
                    Cfg.setShareFiles(act, on);
                    if (on) {
                        act.startService();
                    } else {
                        act.stopService();
                    }
                });
        card.addView(share.view, Ui.lp(act, 6));
        col.addView(card, Ui.lp(act, 12));
    }

    // ------------------------------------------------------------------------------------------------ pairing and app

    private void buildPairing(LinearLayout col) {
        LinearLayout card = Ui.card(act, "Pairing and app", null);
        card.addView(Ui.hint(act, "Tablet app " + version() + ". The phone's version and the button that updates the phone are in "
                + "the " + PHONE + " tab."), Ui.lp(act, 2));
        card.addView(Ui.row(act,
                Ui.button(act, "App info and permissions", v -> act.openAppInfo()),
                Ui.button(act, "Switch role", v -> confirmRole())), Ui.lp(act, 8));
        card.addView(Ui.danger(act, "Pair again (new secret)", v -> confirmPair()), Ui.lp(act, 2));
        card.addView(Ui.hint(act, "Only if the phone was paired with something it should not trust: the tablet makes a new secret, "
                + "and the phone must pair again (on the phone: Forget all tablets, then Pair tablet)."), Ui.lp(act, 2));
        col.addView(card, Ui.lp(act, 12));
    }

    private String version() {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private void confirmPair() {
        new AlertDialog.Builder(act)
                .setTitle("Make a new secret?")
                .setMessage("The phone will no longer know this tablet until they are paired again: on the phone tap Pair tablet "
                        + "(with the tablet on the phone's hotspot), then open " + HOME + " here. The saved copy of the phone's texts on "
                        + "this tablet is erased too (it may be another phone next).")
                .setPositiveButton("New secret", (d, w) -> {
                    Cfg.resetToken(act);
                    TextsVault.wipe(act);
                    BtLink.closeSoon(); // its connection was made with the old secret
                    paintLink(); // and so was its "Ready"
                    Toast.makeText(act, "Done. On the phone tap Pair tablet, then open " + HOME + ".", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmRole() {
        new AlertDialog.Builder(act)
                .setTitle("Switch this device's role?")
                .setMessage("This device is set up as the TABLET. Switching shows the first screen again, where it can be made the "
                        + "phone. Its pairing and settings are kept.")
                .setPositiveButton("Switch role", (d, w) -> act.chooseRole(""))
                .setNegativeButton("Cancel", null)
                .show();
    }
}
