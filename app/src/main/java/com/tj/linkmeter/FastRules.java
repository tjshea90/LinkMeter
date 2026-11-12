package com.tj.linkmeter;

import java.util.Locale;

/**
 * No Android in here (tested in tools/test/FastRulesTest.java). What the tablet's Fast Wi-Fi card says and offers, and what a check
 * of the phone's relay means in words. Never claims more than was just seen: "on" only while the VPN is up, a speed only from bytes
 * that really went through it.
 */
final class FastRules {
    private FastRules() {}

    /** The VPN service's state, as the card gets it (FastVpn.State's names). */
    static final String OFF = "OFF";
    static final String WAITING = "WAITING";
    static final String ON = "ON";
    static final String PERMISSION = "PERMISSION";

    /** What the phone's last status said about its relay. */
    static final class Phone {
        /** False: no status yet (or none for a while): nothing is known about the phone. */
        boolean known;
        /** The status has a "fast" part: this phone app can relay. */
        boolean hasFast;
        boolean allowed = true;
        boolean relayOn;
        String err = "";
        /** null: the phone does not say. */
        Boolean hotspot;
        /** Fast Wi-Fi's own Wi-Fi Direct network: the phone's mode (null: a phone app without it), and whether it is up. */
        Boolean direct;
        boolean directUp;
        String directErr = "";
    }

    /** Fast Wi-Fi's Wi-Fi (the tablet's choice, Settings > Fast Wi-Fi). */
    static final String LINK_HOTSPOT = "hotspot";
    static final String LINK_DIRECT = "direct";

    enum Action { NONE, CONSENT, HOTSPOT_ON, ALLOW, DIRECT_ON, WIFI_SETTINGS }

    static final class Card {
        /** 0: Hotspot chosen, 1: Fast Wi-Fi chosen. */
        int pick;
        String line = "";
        /** 0 grey, 1 green, 2 amber. */
        int tone;
        Action action = Action.NONE;
        String actionLabel = "";
    }

    /** The Wi-Fi Direct network's name (FastDirect.NAME on the phone). */
    static final String FAST_NET = "DIRECT-LM-LinkMeter";

    static final int GREY = 0;
    static final int GREEN = 1;
    static final int AMBER = 2;

    /**
     * The card for this moment. wanted: the user chose Fast Wi-Fi. state and detail: the VPN service's. speed: the words for what
     * went through it lately ("" when not measured).
     */
    static Card card(boolean wanted, String state, String detail, Phone p, String speed) {
        return card(wanted, LINK_HOTSPOT, state, detail, p, speed);
    }

    /** The same, for Fast Wi-Fi's Wi-Fi as the tablet chose it: the phone's hotspot, or its own Wi-Fi Direct network. */
    static Card card(boolean wanted, String link, String state, String detail, Phone p, String speed) {
        Card c = new Card();
        boolean direct = LINK_DIRECT.equals(link);
        c.pick = wanted ? 1 : 0;
        if (!wanted) {
            c.tone = GREY;
            c.line = "The tablet uses the phone's hotspot as usual, with the carrier's hotspot speed limit. Fast Wi-Fi sends the "
                    + "tablet's internet through LinkMeter on the phone instead, at the phone's own speed.";
            if (p.known && !p.hasFast) {
                c.line += " Update LinkMeter on the phone first: its version cannot do that yet.";
            }
            return c;
        }
        if (ON.equals(state)) {
            c.tone = GREEN;
            c.line = "Fast Wi-Fi is on: the tablet's internet goes through LinkMeter on the phone"
                    + (speed.isEmpty() ? "." : ". " + speed);
            if (direct && p.known && Boolean.FALSE.equals(p.direct)) {
                // the phone's hotspot carries it now (its own network was ended, by a hotspot button for example)
                c.line += " It runs over the phone's hotspot now, not over its own network.";
                c.action = Action.DIRECT_ON;
                c.actionLabel = "Use its own network";
            }
            return c;
        }
        if (PERMISSION.equals(state)) {
            c.tone = AMBER;
            c.line = "Android asks once whether LinkMeter may set up a VPN (that is how the tablet's traffic reaches the phone). "
                    + "Nothing leaves the tablet and the phone.";
            c.action = Action.CONSENT;
            c.actionLabel = "Give the OK";
            return c;
        }
        c.tone = AMBER;
        if (p.known && !p.hasFast) {
            c.line = "Update LinkMeter on the phone: its version cannot relay yet. Until then the tablet uses the normal hotspot.";
            return c;
        }
        if (p.known && !p.allowed) {
            c.line = "Fast Wi-Fi is switched off on the phone (on LinkMeter's own screen there). Until it is allowed the tablet uses "
                    + "the normal hotspot.";
            c.action = Action.ALLOW;
            c.actionLabel = "Allow it on the phone";
            return c;
        }
        if (p.known && !p.err.isEmpty()) {
            c.line = "The phone could not start Fast Wi-Fi: " + dot(p.err) + " Until then the tablet uses the normal hotspot.";
            return c;
        }
        if (direct && p.known && p.direct == null) {
            c.line = "Update LinkMeter on the phone: its version has no Wi-Fi network of its own for Fast Wi-Fi yet (Settings > "
                    + "Fast Wi-Fi can use the hotspot instead).";
            return c;
        }
        if (direct && p.known && Boolean.TRUE.equals(p.direct)) {
            if (!p.directUp) {
                c.line = "The phone's own Wi-Fi network for Fast Wi-Fi is not up"
                        + (p.directErr.isEmpty() ? " yet." : ": " + dot(p.directErr));
                c.action = Action.DIRECT_ON;
                c.actionLabel = "Try again";
                return c;
            }
            c.line = "The phone's own Wi-Fi network (" + FAST_NET + ") is on, and the hotspot is off. The tablet joins it by itself; if "
                    + "Android asks \"Allow suggested Wi-Fi networks?\", tap Yes. Or pick it in Wi-Fi settings once.";
            c.action = Action.WIFI_SETTINGS;
            c.actionLabel = "Wi-Fi settings";
            return c;
        }
        if (direct && p.known && Boolean.FALSE.equals(p.direct)) {
            c.line = "Fast Wi-Fi uses the phone's own Wi-Fi network instead of the hotspot, and that network is off.";
            c.action = Action.DIRECT_ON;
            c.actionLabel = "Switch it on";
            return c;
        }
        if (p.known && Boolean.FALSE.equals(p.hotspot)) {
            c.line = "Fast Wi-Fi runs over the phone's hotspot, and the hotspot is off.";
            c.action = Action.HOTSPOT_ON;
            c.actionLabel = "Turn the hotspot on";
            return c;
        }
        if (WAITING.equals(state)) {
            c.line = detail.isEmpty() ? "Fast Wi-Fi is waiting for the phone." : "Fast Wi-Fi is waiting: " + detail;
            return c;
        }
        c.tone = GREY;
        c.line = "Fast Wi-Fi is starting…";
        return c;
    }

    /**
     * What a check of the phone's relay found, in words, or null when all is well. answer: the byte the phone sent (0 OK, 3 refused),
     * -1 when it closed without one, -2 when the connection itself failed (error is then the system's message).
     */
    static String probeWords(int answer, String error) {
        if (answer == FastProto.OK) {
            return null;
        }
        if (answer == FastProto.DENIED) {
            return "The phone does not know this tablet's key. Pair the tablet with the phone again (Settings).";
        }
        if (answer == -1) {
            return "LinkMeter on the phone closed the check without an answer (update it on the phone).";
        }
        if (answer >= 0) {
            return "The phone's relay gave an unknown answer (" + answer + "). Update LinkMeter on both devices.";
        }
        String m = error == null ? "" : error;
        String low = m.toLowerCase(Locale.US);
        if (low.contains("refused")) {
            return "LinkMeter on the phone does not offer Fast Wi-Fi yet (update it on the phone, and check that Fast Wi-Fi is allowed "
                    + "on the phone's own screen).";
        }
        if (low.contains("timed out") || low.contains("timeout")) {
            return "The phone did not answer in time.";
        }
        return "The phone does not answer" + (m.isEmpty() ? "." : " (" + m + ").");
    }

    /**
     * The phone's own screen: what its relay is doing. sinceMs: how long ago the tablet last opened a connection (-1: never since the
     * service started).
     */
    static String phoneLine(boolean allowed, boolean running, String problem, int conns, long bytes, long sinceMs) {
        if (!allowed) {
            return "Off. The tablet uses the normal hotspot.";
        }
        if (!running) {
            return problem == null || problem.isEmpty() ? "Not running: start LinkMeter's service (Status card)." : dot(problem);
        }
        if (sinceMs < 0) {
            return "Ready. The tablet has not used it since LinkMeter started.";
        }
        if (conns > 0) {
            return "In use by the tablet: " + conns + (conns == 1 ? " connection" : " connections") + ", " + size(bytes)
                    + " carried so far.";
        }
        return "Ready. Last used " + ago(sinceMs) + "; " + size(bytes) + " carried so far.";
    }

    static String ago(long ms) {
        long s = ms / 1000;
        if (s < 60) {
            return "less than a minute ago";
        }
        long m = s / 60;
        if (m < 60) {
            return m + (m == 1 ? " minute ago" : " minutes ago");
        }
        long h = m / 60;
        return h + (h == 1 ? " hour ago" : " hours ago");
    }

    /** "12.3 Mbit/s down, 1.4 up" from bytes that went each way in ms; "" when ms is too short to say. */
    static String speed(long downBytes, long upBytes, long ms) {
        if (ms < 500 || downBytes < 0 || upBytes < 0) {
            return "";
        }
        double down = downBytes * 8.0 / ms / 1000.0;
        double up = upBytes * 8.0 / ms / 1000.0;
        if (down < 0.05 && up < 0.05) {
            return "Quiet right now.";
        }
        return "Now " + mbit(down) + " Mbit/s down, " + mbit(up) + " up.";
    }

    static String mbit(double v) {
        if (v >= 100) {
            return String.format(Locale.US, "%.0f", v);
        }
        return String.format(Locale.US, "%.1f", v);
    }

    /** "1.2 GB", "340 MB", "12 KB" for a byte count. */
    static String size(long b) {
        if (b >= 1_000_000_000L) {
            return String.format(Locale.US, "%.1f GB", b / 1e9);
        }
        if (b >= 1_000_000L) {
            return String.format(Locale.US, "%.0f MB", b / 1e6);
        }
        return Math.max(0, b / 1000) + " KB";
    }

    private static String dot(String s) {
        String t = s.trim();
        return t.endsWith(".") ? t : t + ".";
    }
}
