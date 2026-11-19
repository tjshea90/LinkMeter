package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The headset's link to THIS TABLET, decided from what the tablet's Bluetooth says (tested offline in tools/test/FloatingLogicTest.java;
 * docs/FLOATING_PHONE.md). The call's sound always stays on the phone; this is only about letting the same headset also play the
 * tablet's own sound (music, videos). Pure: no Android. Which headset is meant, what the card says, which button it offers, and what is
 * said after a try. Nothing is claimed that was not read back from Android: "connected" comes from the tablet's own profile list, and a
 * switch that Android only opened for the user to tap is said to be that, never "done".
 */
final class TabletHeadsetRules {
    private TabletHeadsetRules() {}

    enum Tone { GOOD, WARN, QUIET }

    /** What a button does. */
    enum Do { CONNECT, DISCONNECT, PAIR, CHOOSE, BLUETOOTH_ON, SETTINGS, NONE }

    /** How a connect or disconnect came out. */
    enum Way {
        /** Android's own list shows the change (read back). */
        DONE,
        /** The tablet could not do it itself: Android's Bluetooth screen was opened for one tap of the user. Nothing was switched. */
        ASKED,
        /** Nothing worked and nothing could be opened. */
        FAILED
    }

    /** One audio device paired with the tablet. */
    static final class Dev {
        String addr = "";
        String name = "";
        boolean connected;
    }

    /** Everything the card needs to know. */
    static final class Facts {
        boolean hasBluetooth = true;
        boolean bluetoothOn;
        /** The audio devices paired with the tablet. */
        final List<Dev> bonded = new ArrayList<>();
        /** The headset the user chose on the tablet ("" = none). */
        String userAddr = "";
        /** The headset the phone uses (its address and name, "" = not known). */
        String phoneAddr = "";
        String phoneName = "";
        /** The phone says that headset is connected to it. */
        boolean phoneConnected;
        /** Shizuku runs on the tablet and LinkMeter may use it: connecting and disconnecting need no tap. */
        boolean canSwitch;
        /** The user's headset can stay connected to the phone and the tablet at once (else it moves between them, one at a time). */
        boolean keepBoth;
    }

    /** What the card shows. */
    static final class View {
        String chip = "Tablet";
        Tone tone = Tone.QUIET;
        String title = "";
        String line = "";
        final List<Button> buttons = new ArrayList<>();
        /** The device the card is about, or null. */
        Dev device;

        Button main() {
            for (Button b : buttons) {
                if (b.main) {
                    return b;
                }
            }
            return null;
        }

        boolean has(Do what) {
            for (Button b : buttons) {
                if (b.what == what) {
                    return true;
                }
            }
            return false;
        }
    }

    static final class Button {
        final Do what;
        final String label;
        final boolean main;

        Button(Do what, String label, boolean main) {
            this.what = what;
            this.label = label;
            this.main = main;
        }
    }

    private static String up(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.US);
    }

    private static Dev find(List<Dev> list, String addr) {
        String a = up(addr);
        if (a.isEmpty()) {
            return null;
        }
        for (Dev d : list) {
            if (up(d.addr).equals(a)) {
                return d;
            }
        }
        return null;
    }

    private static boolean sameName(String a, String b) {
        String x = a == null ? "" : a.trim().toLowerCase(Locale.US);
        String y = b == null ? "" : b.trim().toLowerCase(Locale.US);
        return !x.isEmpty() && x.equals(y);
    }

    /**
     * The headset the tablet's switch is about: the one the user chose on the tablet, else the one the phone uses (the same device has the
     * same address on both), else one with the phone's headset's name, else the only audio device paired with the tablet. Null when there is
     * none, or when several are paired and nothing says which.
     */
    static Dev pick(Facts f) {
        Dev d = find(f.bonded, f.userAddr);
        if (d != null) {
            return d;
        }
        d = find(f.bonded, f.phoneAddr);
        if (d != null) {
            return d;
        }
        if (!f.phoneName.trim().isEmpty()) {
            for (Dev x : f.bonded) {
                if (sameName(x.name, f.phoneName)) {
                    return x;
                }
            }
        }
        return f.bonded.size() == 1 ? f.bonded.get(0) : null;
    }

    private static String shortName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            return "headset";
        }
        return n.length() > 22 ? n.substring(0, 21) + "…" : n;
    }

    /** @param lastProblem what the last try said when it failed ("" when nothing failed since) */
    static View of(Facts f, String lastProblem) {
        View v = new View();
        String problem = lastProblem == null ? "" : lastProblem;
        if (!f.hasBluetooth) {
            v.chip = "Tablet: no Bluetooth";
            v.tone = Tone.WARN;
            v.title = "This tablet has no Bluetooth";
            v.line = "The headset can only be used with the phone.";
            return v;
        }
        if (!f.bluetoothOn) {
            v.chip = "Tablet: Bluetooth off";
            v.tone = Tone.WARN;
            v.title = "Bluetooth is off on this tablet";
            v.line = "Switch it on to use the headset here too. The phone's link to the tablet needs it as well.";
            v.buttons.add(new Button(Do.BLUETOOTH_ON, "Switch Bluetooth on", true));
            return v;
        }
        Dev d = pick(f);
        if (d == null) {
            if (f.bonded.isEmpty()) {
                String who = f.phoneName.trim().isEmpty() ? "your headset" : f.phoneName.trim();
                v.chip = "Tablet: not paired";
                v.tone = Tone.WARN;
                v.title = "Not paired with this tablet yet";
                v.line = "To use " + who + " with the tablet as well, pair it with the tablet once: put it in pairing mode (hold its power "
                        + "button until it flashes) and press Pair. The phone stays paired.";
                v.buttons.add(new Button(Do.PAIR, "Pair with this tablet", true));
                v.buttons.add(new Button(Do.SETTINGS, "Tablet's Bluetooth settings", false));
            } else {
                v.chip = "Tablet: choose";
                v.tone = Tone.WARN;
                v.title = "Which one is your headset?";
                v.line = "Several audio devices are paired with this tablet. Choose the headset to use here.";
                v.buttons.add(new Button(Do.CHOOSE, "Choose headset", true));
                v.buttons.add(new Button(Do.PAIR, "Pair another", false));
            }
            return v;
        }
        v.device = d;
        String name = shortName(d.name);
        if (d.connected) {
            v.chip = "Tablet: connected";
            v.tone = Tone.GOOD;
            v.title = name + " is connected to this tablet";
            v.line = "The tablet's music and video play on it. Phone calls still go through the phone."
                    + (f.phoneConnected ? "" : " (It is not connected to the phone right now.)");
            v.buttons.add(new Button(Do.DISCONNECT, "Disconnect from tablet", false));
            v.buttons.add(new Button(Do.CHOOSE, "Choose another", false));
            return v;
        }
        v.chip = "Tablet: not connected";
        v.tone = Tone.WARN;
        v.title = name + " is not connected to this tablet";
        StringBuilder line = new StringBuilder("Turn the headset on and hold it near the tablet. ");
        line.append(f.canSwitch ? "LinkMeter connects it for you." : "Android will show its Bluetooth list: tap the headset there once.");
        if (f.phoneConnected && !f.keepBoth) {
            line.append(" It is connected to the phone now: LinkMeter disconnects it from the phone first (one connection at a time), and "
                    + "back again when you connect it to the phone.");
        }
        if (!problem.isEmpty()) {
            line.append('\n').append(problem);
        }
        v.line = line.toString();
        v.buttons.add(new Button(Do.CONNECT, problem.isEmpty() ? "Connect to tablet" : "Try again", true));
        v.buttons.add(new Button(Do.CHOOSE, "Choose another", false));
        return v;
    }

    /** What to tell the user after the tablet tried to connect. */
    static String afterConnect(Way way, String name) {
        String n = shortName(name);
        switch (way) {
            case DONE:
                return n + " is connected to this tablet.";
            case ASKED:
                return "Android's Bluetooth list is open: tap " + n + " there. LinkMeter could not connect it by itself.";
            default:
                return n + " did not connect to this tablet. Turn it off and on, then try again.";
        }
    }

    /** What to tell the user after the tablet tried to disconnect. */
    static String afterDisconnect(Way way, String name) {
        String n = shortName(name);
        switch (way) {
            case DONE:
                return n + " is disconnected from this tablet.";
            case ASKED:
                return "Android's Bluetooth list is open: tap the gear beside " + n + ", then Disconnect. LinkMeter could not do it by itself.";
            default:
                return n + " is still connected to this tablet. Turn the headset off instead.";
        }
    }

    /** Whether a switch that was only asked for (the user's tap) may be believed: never. {@code readBack} is what the tablet's own list says now. */
    static boolean believed(Way way, boolean readBack, boolean wanted) {
        return way == Way.DONE && readBack == wanted;
    }
}
