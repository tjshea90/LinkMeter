package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;

/**
 * What the Bluetooth headset card says and which buttons it offers, decided from what the phone told the tablet (tested offline in
 * tools/test/CallsLogicTest.java; see docs/PHONE_CALLS.md). The headset is connected to the PHONE, never to the tablet; this only
 * helps the user get it connected there. Nothing is claimed that the phone did not say.
 */
final class HeadsetRules {
    private HeadsetRules() {}

    enum Tone { GOOD, WARN, QUIET }

    /** What a button does. */
    enum Do { ALLOW, CONNECT, DISCONNECT, CHOOSE, PAIR, SETTINGS, SCREEN, RETRY }

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

    /** What the card shows. */
    static final class View {
        /** The words on the small chip at the top of the Calls tab. */
        String chip = "Headset";
        Tone tone = Tone.QUIET;
        String title = "";
        String line = "";
        final List<Button> buttons = new ArrayList<>();
        /** The device the card is about (the chosen headset, or the only one connected), or null. */
        Calls.Device device;

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

    private static String shortName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            return "headset";
        }
        return n.length() > 22 ? n.substring(0, 21) + "…" : n;
    }

    /**
     * @param h the phone's answer; null while nothing was asked yet or the phone could not be reached
     * @param lastProblem what the last Connect said when it failed ("" when nothing failed since)
     */
    static View of(Calls.Headsets h, String lastProblem) {
        View v = new View();
        String problem = lastProblem == null ? "" : lastProblem;
        if (h == null) {
            v.title = "Headset";
            v.line = "Not checked yet.";
            return v;
        }
        if (!h.allowed) {
            v.chip = "Headset: not allowed";
            v.tone = Tone.WARN;
            v.title = "Bluetooth is not allowed yet";
            v.line = h.canConnect
                    ? "The phone has not allowed LinkMeter to use Bluetooth devices. LinkMeter can ask the phone to allow it."
                    : "The phone has not allowed LinkMeter to use Bluetooth devices. On the phone, open LinkMeter's Phone tab and tap \""
                            + PhoneScreenRules.CALLS_BUTTON + "\".";
            if (h.canConnect) {
                v.buttons.add(new Button(Do.ALLOW, "Allow Bluetooth", true));
            }
            return v;
        }
        if (h.devices.isEmpty()) {
            v.chip = "No headset";
            v.tone = Tone.WARN;
            v.title = "No headset paired yet";
            v.line = "Put your headset in pairing mode (usually: hold its power button until it flashes), then press Pair. It pairs with the "
                    + "phone, which is where the call's sound has to go.";
            v.buttons.add(new Button(Do.PAIR, "Pair a headset", true));
            v.buttons.add(new Button(Do.SETTINGS, "Phone's Bluetooth settings", false));
            return v;
        }
        Calls.Device cur = h.current();
        v.device = cur;
        if (cur == null) {
            v.chip = "Choose headset";
            v.tone = Tone.WARN;
            v.title = "Which one is your headset?";
            v.line = "The phone has several paired devices. Choose the one to use for calls.";
            v.buttons.add(new Button(Do.CHOOSE, "Choose headset", true));
            v.buttons.add(new Button(Do.PAIR, "Pair another", false));
            return v;
        }
        String name = shortName(cur.name);
        if (cur.connected) {
            v.chip = name + " · connected";
            v.tone = Tone.GOOD;
            v.title = name + " is connected";
            StringBuilder line = new StringBuilder("Calls play on it. The call's sound stays on the phone, so keep the headset within a few "
                    + "metres of the phone.");
            if (h.drops > 0) {
                line.append(" It dropped ").append(h.drops).append(h.drops == 1 ? " time" : " times")
                        .append(" since the phone's app started: bring the phone closer to where you sit.");
            }
            v.line = line.toString();
            v.buttons.add(new Button(Do.DISCONNECT, "Disconnect", false));
            v.buttons.add(new Button(Do.CHOOSE, "Choose another", false));
            return v;
        }
        v.chip = name + " · not connected";
        v.tone = Tone.WARN;
        v.title = h.bluetoothOn ? name + " is not connected" : "Bluetooth is off on the phone";
        StringBuilder line = new StringBuilder();
        if (!h.bluetoothOn) {
            line.append(h.canConnect ? "LinkMeter will switch it on. " : "Switch Bluetooth on at the phone. ");
        }
        line.append("Turn the headset on and hold it near the phone.");
        if (!h.canConnect) {
            line.append(" The phone cannot connect it for you now (Shizuku is not ready): most headsets connect by themselves once they are on.");
        }
        if (!problem.isEmpty()) {
            line.append("\n").append(problem);
        }
        v.line = line.toString();
        if (h.canConnect) {
            v.buttons.add(new Button(Do.CONNECT, problem.isEmpty() ? "Connect headset" : "Try again", true));
            v.buttons.add(new Button(Do.SETTINGS, "Phone's Bluetooth settings", false));
        } else {
            v.buttons.add(new Button(Do.SETTINGS, "Phone's Bluetooth settings", true));
            v.buttons.add(new Button(Do.RETRY, "Check again", false));
        }
        v.buttons.add(new Button(Do.CHOOSE, "Choose another", false));
        return v;
    }

    /** The one sentence under a call's name about where the sound goes. */
    static String route(String route, String headsetName) {
        switch (route == null ? "" : route) {
            case Calls.ROUTE_HEADSET:
                return headsetName == null || headsetName.isEmpty() ? "Sound on the headset" : "Sound on " + headsetName;
            case Calls.ROUTE_SPEAKER:
                return "Sound on the phone's speaker";
            case Calls.ROUTE_PHONE:
                return "Sound on the phone's earpiece";
            default:
                return "";
        }
    }

    /** The label of the audio button of the call screen, from where the sound goes and whether a headset is connected. */
    static String routeButton(String route, boolean headsetConnected) {
        if (Calls.ROUTE_HEADSET.equals(route)) {
            return "Headset";
        }
        if (Calls.ROUTE_SPEAKER.equals(route)) {
            return "Speaker";
        }
        if (Calls.ROUTE_PHONE.equals(route)) {
            return headsetConnected ? "Phone" : "Earpiece";
        }
        return headsetConnected ? "Headset" : "Audio";
    }

    /**
     * Where to send the sound when the audio button is pressed: round the choices that exist (the headset only when one is
     * connected): headset, speaker, phone, headset ...
     */
    static String nextRoute(String route, boolean headsetConnected) {
        List<String> order = new ArrayList<>();
        if (headsetConnected) {
            order.add(Calls.ROUTE_HEADSET);
        }
        order.add(Calls.ROUTE_SPEAKER);
        order.add(Calls.ROUTE_PHONE);
        int at = order.indexOf(route);
        return order.get((at + 1) % order.size());
    }
}
