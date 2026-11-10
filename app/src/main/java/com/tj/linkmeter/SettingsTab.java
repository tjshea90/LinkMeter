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

/**
 * The tablet's own settings, in groups: the Bluetooth link (the way to the phone while its hotspot is off), the connection, this
 * tablet, and pairing. Nothing here runs in the background.
 */
final class SettingsTab extends Tab {
    private TextView linkChip;
    private TextView linkState;
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
        return "Settings";
    }

    @Override
    Icons.Kind icon() {
        return Icons.Kind.GEAR;
    }

    @Override
    View build() {
        LinearLayout col = Ui.column(act);
        col.addView(Ui.title(act, "Settings"));
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
                + "texts and the phone's screen still need the hotspot. Nothing has to be done on the phone."), Ui.lp(act, 2));
        linkState = Ui.body(act, "");
        card.addView(linkState, Ui.lp(act, 8));
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

    private void paintLink() {
        if (linkChip == null) {
            return;
        }
        String problem = BtLink.problem(act);
        boolean on = BtLink.enabled(act);
        boolean ready = BtLink.ready(act);
        if (!on) {
            Ui.paintChip(linkChip, "Off", Ui.GREY);
        } else if (ready) {
            Ui.paintChip(linkChip, "Ready", Ui.GREEN);
        } else {
            Ui.paintChip(linkChip, "Not set up", Ui.AMBER);
        }
        String name = Cfg.getStr(act, "btName", "");
        String base = ready
                ? "Ready. The tablet calls the phone" + (name.isEmpty() ? "" : " (" + name + ")") + " over Bluetooth whenever the hotspot "
                + "route does not answer." + (problem.isEmpty() ? "" : "\n" + problem)
                : problem;
        linkState.setText(linkMsg.isEmpty() ? base : linkMsg);
        linkState.setTextColor(linkMsg.isEmpty() && !ready && on ? Ui.AMBER : Ui.FG);
        setUpBtn.setEnabled(on && !linkBusy);
        setUpBtn.setText(ready ? "Set up again" : "Set up");
        testBtn.setEnabled(on && ready && !linkBusy);
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
        act.requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT}, 9);
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
                        ? "The phone's app is too old for this. Update it first: Phone tab > Update phone from this tablet."
                        : "The phone could not be asked: " + text + "\nThe setup needs the tablet on the phone's hotspot, and "
                        + "Shizuku running on the phone.");
                return;
            }
            final String phoneSaid = text;
            say(phoneSaid + "\nReading the phone's Bluetooth address…");
            Call.run(act, "GET", "/s", 6000, (ok2, text2, json2) -> {
                String mac = json2 == null ? "" : json2.optString("btmac", "");
                String name = json2 == null ? "" : json2.optString("btname", "");
                if (!name.isEmpty()) {
                    Cfg.setStr(act, "btName", name);
                }
                if (!BtAddr.valid(mac)) {
                    busy(false);
                    say(phoneSaid + "\nThe phone did not tell its Bluetooth address. Pair the phone and the tablet once in "
                            + "Android's Bluetooth settings (on both: Settings > Bluetooth, tap the other device), then press "
                            + "\"Find the phone among paired Bluetooth devices\".");
                    return;
                }
                Cfg.setBtMac(act, mac);
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
                Client.Resp r = BtLink.call(act, "GET", "/s", 8000);
                if (r.code == 200) {
                    out = "The Bluetooth link works. When the phone's hotspot is off, the Home tab offers \"Turn the hotspot on\".";
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
                Cfg.setHost(act, e.toString().trim());
            }
        });
        card.addView(host);
        card.addView(Ui.hint(act, "Leave it empty: on the phone's hotspot the tablet finds the phone by itself. Type an address "
                + "only if Home says it cannot find the phone."), Ui.lp(act, 0));

        card.addView(Ui.text(act, "Ask the phone for its status every", 14, Ui.FG, false), Ui.lp(act, 12));
        final long[] values = {15000L, 5000L, 2000L};
        long cur = Cfg.intervalMs(act);
        int sel = cur >= 15000L ? 0 : cur <= 2000L ? 2 : 1;
        Ui.Choice every = new Ui.Choice(act, new String[] {"15 s", "5 s", "2 s"}, sel, i -> Cfg.setIntervalMs(act, values[i]));
        card.addView(every.view, Ui.lp(act, 2));
        card.addView(Ui.hint(act, "Only while the Home tab is open. Longer saves battery on both devices; over Bluetooth it is "
                + "never more often than every 15 s."), Ui.lp(act, 2));
        col.addView(card, Ui.lp(act, 12));
    }

    // ------------------------------------------------------------------------------------------------ this tablet

    private void buildTablet(LinearLayout col) {
        LinearLayout card = Ui.card(act, "This tablet", null);
        Ui.Toggle keep = new Ui.Toggle(act, "Keep the screen on in every tab",
                "The Screen tab keeps the tablet awake by itself while it is open (its own option). This is for all the other tabs.",
                Cfg.keepOn(act), (b, on) -> {
                    Cfg.setKeepOn(act, on);
                    act.applyKeepOn();
                });
        card.addView(keep.view, Ui.lp(act, 6));
        Ui.Toggle share = new Ui.Toggle(act, "Let the phone browse this tablet's files",
                "Runs a small file service on the tablet (a notification shows while it is on). The Files tab on the tablet does "
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
                + "the Phone tab."), Ui.lp(act, 2));
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
                        + "(with the tablet on the phone's hotspot), then open Home here.")
                .setPositiveButton("New secret", (d, w) -> {
                    Cfg.resetToken(act);
                    new Thread(BtLink::close, "linkmeter-bt-close").start(); // its connection was made with the old secret
                    Toast.makeText(act, "Done. On the phone tap Pair tablet, then open Home.", Toast.LENGTH_LONG).show();
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
