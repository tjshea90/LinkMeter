package com.tj.linkmeter;

/**
 * Tests FastRules: what the tablet's Fast Wi-Fi card says and offers in every combination (never "on" unless the VPN is up, the
 * one thing to do first, the phone's state only when it is known), what a check of the phone's relay means, and the speed words.
 * Not part of the app.
 */
public final class FastRulesTest {
    private static int checks;
    private static int fails;

    private FastRulesTest() {}

    private static void eq(Object want, Object got, String what) {
        checks++;
        if (!String.valueOf(want).equals(String.valueOf(got))) {
            fails++;
            System.out.println("FAIL " + what + ": wanted " + want + " but got " + got);
        }
    }

    private static void yes(boolean ok, String what) {
        checks++;
        if (!ok) {
            fails++;
            System.out.println("FAIL " + what);
        }
    }

    public static void main(String[] args) {
        offCard();
        onCard();
        blockers();
        directLink();
        everything();
        probe();
        speed();
        System.out.println(checks + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }

    private static FastRules.Phone phone(boolean known, boolean hasFast, boolean allowed, String err, Boolean hotspot) {
        FastRules.Phone p = new FastRules.Phone();
        p.known = known;
        p.hasFast = hasFast;
        p.allowed = allowed;
        p.err = err;
        p.hotspot = hotspot;
        return p;
    }

    private static final FastRules.Phone GOOD = phone(true, true, true, "", Boolean.TRUE);

    private static void offCard() {
        FastRules.Card c = FastRules.card(false, FastRules.OFF, "", GOOD, "");
        eq(0, c.pick, "not wanted: Hotspot is chosen");
        eq(FastRules.GREY, c.tone, "not wanted: grey");
        eq(FastRules.Action.NONE, c.action, "not wanted: no button");
        yes(c.line.contains("hotspot as usual") && c.line.contains("speed limit"), "not wanted: says the normal hotspot is used, limits and all");
        FastRules.Card old = FastRules.card(false, FastRules.OFF, "", phone(true, false, true, "", Boolean.TRUE), "");
        yes(old.line.contains("Update LinkMeter on the phone"), "not wanted, old phone app: says to update it first");
        FastRules.Card unknown = FastRules.card(false, FastRules.OFF, "", phone(false, false, true, "", null), "");
        yes(!unknown.line.contains("Update"), "nothing known about the phone: no claim that it is old");
    }

    private static void onCard() {
        FastRules.Card c = FastRules.card(true, FastRules.ON, "", GOOD, "Now 23.4 Mbit/s down, 1.2 up.");
        eq(1, c.pick, "wanted: Fast Wi-Fi is chosen");
        eq(FastRules.GREEN, c.tone, "on: green");
        yes(c.line.startsWith("Fast Wi-Fi is on") && c.line.endsWith("1.2 up."), "on: says so, with the speed");
        eq(FastRules.Action.NONE, c.action, "on: no button");
        FastRules.Card quiet = FastRules.card(true, FastRules.ON, "", GOOD, "");
        yes(quiet.line.endsWith("on the phone."), "on without a measurement: no speed is made up");
        // on wins over old knowledge of the phone: the VPN is up, so the relay answered just now
        FastRules.Card stale = FastRules.card(true, FastRules.ON, "", phone(true, false, true, "", Boolean.FALSE), "");
        eq(FastRules.GREEN, stale.tone, "on with an outdated phone status: still on (the relay itself answered)");
    }

    private static void blockers() {
        FastRules.Card perm = FastRules.card(true, FastRules.PERMISSION, "", GOOD, "");
        eq(FastRules.Action.CONSENT, perm.action, "Android's VPN question first");
        eq("Give the OK", perm.actionLabel, "its button");
        eq(FastRules.AMBER, perm.tone, "amber while it cannot work");

        FastRules.Card old = FastRules.card(true, FastRules.WAITING, "x", phone(true, false, true, "", Boolean.TRUE), "");
        yes(old.line.startsWith("Update LinkMeter on the phone"), "old phone app: update it");
        eq(FastRules.Action.NONE, old.action, "old phone app: no button (the update is done on the phone)");

        FastRules.Card off = FastRules.card(true, FastRules.WAITING, "x", phone(true, true, false, "", Boolean.TRUE), "");
        eq(FastRules.Action.ALLOW, off.action, "switched off on the phone: a button allows it");
        yes(off.line.contains("switched off on the phone"), "and says where it was switched off");

        FastRules.Card err = FastRules.card(true, FastRules.WAITING, "x", phone(true, true, true, "port busy", Boolean.TRUE), "");
        yes(err.line.contains("could not start Fast Wi-Fi: port busy."), "the phone's own error, with a full stop");

        FastRules.Card hs = FastRules.card(true, FastRules.WAITING, "x", phone(true, true, true, "", Boolean.FALSE), "");
        eq(FastRules.Action.HOTSPOT_ON, hs.action, "hotspot off: a button turns it on");
        FastRules.Card hsUnknown = FastRules.card(true, FastRules.WAITING, "the phone does not answer.", phone(true, true, true, "", null), "");
        eq(FastRules.Action.NONE, hsUnknown.action, "hotspot not known: no claim that it is off");
        yes(hsUnknown.line.contains("the phone does not answer."), "waiting: the service's own reason is shown");

        FastRules.Card starting = FastRules.card(true, FastRules.OFF, "", GOOD, "");
        yes(starting.line.contains("starting"), "wanted but not yet running: starting");
        FastRules.Card waitingNoWord = FastRules.card(true, FastRules.WAITING, "", GOOD, "");
        eq("Fast Wi-Fi is waiting for the phone.", waitingNoWord.line, "waiting without a reason");
    }

    private static FastRules.Phone directPhone(Boolean direct, boolean up, String err) {
        FastRules.Phone p = phone(true, true, true, "", Boolean.FALSE);
        p.direct = direct;
        p.directUp = up;
        p.directErr = err;
        return p;
    }

    /** Fast Wi-Fi over the phone's own Wi-Fi Direct network (stage 2): the hotspot is off on purpose there. */
    private static void directLink() {
        String D = FastRules.LINK_DIRECT;
        FastRules.Card old = FastRules.card(true, D, FastRules.WAITING, "", directPhone(null, false, ""), "");
        yes(old.line.contains("has no Wi-Fi network of its own"), "a phone app without the network: update it, or use the hotspot");
        eq(FastRules.Action.NONE, old.action, "nothing to press for an old app");

        FastRules.Card off = FastRules.card(true, D, FastRules.WAITING, "", directPhone(Boolean.FALSE, false, ""), "");
        eq(FastRules.Action.DIRECT_ON, off.action, "the phone's network is off: a button switches it on");
        eq("Switch it on", off.actionLabel, "its label");
        yes(!off.line.contains("hotspot is off."), "never the hotspot complaint over this link");

        FastRules.Card up = FastRules.card(true, D, FastRules.WAITING, "", directPhone(Boolean.TRUE, true, ""), "");
        eq(FastRules.Action.WIFI_SETTINGS, up.action, "the network is up, the tablet not on it yet: Wi-Fi settings at hand");
        yes(up.line.contains(FastRules.FAST_NET) && up.line.contains("Allow suggested Wi-Fi networks"), "names the network and Android's question");

        FastRules.Card notUp = FastRules.card(true, D, FastRules.WAITING, "", directPhone(Boolean.TRUE, false, "Wi-Fi Direct is busy"), "");
        eq(FastRules.Action.DIRECT_ON, notUp.action, "the mode is on but the network is not up: try again");
        yes(notUp.line.endsWith("Wi-Fi Direct is busy."), "with the phone's reason");

        FastRules.Card on = FastRules.card(true, D, FastRules.ON, "", directPhone(Boolean.TRUE, true, ""), "Now 30.0 Mbit/s down, 2.0 up.");
        eq(FastRules.GREEN, on.tone, "on over its own network: green");
        eq(FastRules.Action.NONE, on.action, "and nothing to press");

        FastRules.Card overHotspot = FastRules.card(true, D, FastRules.ON, "", directPhone(Boolean.FALSE, false, ""), "");
        eq(FastRules.GREEN, overHotspot.tone, "on, but over the hotspot (its network was ended): still on");
        eq(FastRules.Action.DIRECT_ON, overHotspot.action, "with a button back to its own network");
        yes(overHotspot.line.contains("over the phone's hotspot now"), "and says which Wi-Fi carries it");

        // the hotspot link never shows the Direct words, the Direct link never the hotspot button
        FastRules.Card hs = FastRules.card(true, FastRules.LINK_HOTSPOT, FastRules.WAITING, "", directPhone(Boolean.TRUE, true, ""), "");
        eq(FastRules.Action.HOTSPOT_ON, hs.action, "hotspot link with the phone in its own-network mode: Turn the hotspot on");
        FastRules.Card notWanted = FastRules.card(false, D, FastRules.OFF, "", directPhone(Boolean.TRUE, true, ""), "");
        eq(FastRules.Action.NONE, notWanted.action, "not wanted: nothing to press, whatever the link");
    }

    /** Every combination: never green unless ON, a button only with its label, the pick always follows the wish. */
    private static void everything() {
        String[] states = {FastRules.OFF, FastRules.WAITING, FastRules.ON, FastRules.PERMISSION};
        Boolean[] hs = {null, Boolean.TRUE, Boolean.FALSE};
        int n = 0;
        for (boolean wanted : new boolean[] {false, true}) {
            for (String st : states) {
                for (boolean known : new boolean[] {false, true}) {
                    for (boolean hasFast : new boolean[] {false, true}) {
                        for (boolean allowed : new boolean[] {false, true}) {
                            for (String err : new String[] {"", "e"}) {
                                for (Boolean h : hs) {
                                  for (String link : new String[] {FastRules.LINK_HOTSPOT, FastRules.LINK_DIRECT}) {
                                   for (Boolean dir : new Boolean[] {null, Boolean.FALSE, Boolean.TRUE}) {
                                    FastRules.Phone ph = phone(known, hasFast, allowed, err, h);
                                    ph.direct = dir;
                                    ph.directUp = Boolean.TRUE.equals(dir);
                                    FastRules.Card c = FastRules.card(wanted, link, st, "d", ph, "s");
                                    n++;
                                    if (FastRules.LINK_DIRECT.equals(link) && c.action == FastRules.Action.HOTSPOT_ON) {
                                        yes(false, "the Direct link never offers to turn the hotspot on");
                                    }
                                    if (FastRules.LINK_HOTSPOT.equals(link)) {
                                        yes(c.action != FastRules.Action.DIRECT_ON && c.action != FastRules.Action.WIFI_SETTINGS,
                                                "the hotspot link never offers the phone's own network");
                                    }
                                    eq(wanted ? 1 : 0, c.pick, "pick follows the wish");
                                    if (c.tone == FastRules.GREEN) {
                                        yes(wanted && FastRules.ON.equals(st), "green only when on");
                                    }
                                    yes((c.action == FastRules.Action.NONE) == c.actionLabel.isEmpty(), "a button always has its label");
                                    yes(!c.line.isEmpty(), "always a line");
                                    if (!wanted) {
                                        yes(c.action == FastRules.Action.NONE, "nothing to press while Fast Wi-Fi is not wanted");
                                    }
                                    if (!known && c.action != FastRules.Action.NONE) {
                                        yes(c.action == FastRules.Action.CONSENT, "with nothing known about the phone, only Android's question");
                                    }
                                    if (c.line.contains("Fast Wi-Fi is on")) {
                                        yes(FastRules.ON.equals(st), "never says on unless on");
                                    }
                                   }
                                  }
                                }
                            }
                        }
                    }
                }
            }
        }
        yes(n == 2 * 4 * 2 * 2 * 2 * 2 * 3 * 2 * 3, "all combinations seen");
    }

    private static void probe() {
        eq(null, FastRules.probeWords(FastProto.OK, null), "OK: nothing to say");
        yes(FastRules.probeWords(FastProto.DENIED, null).contains("Pair the tablet"), "unknown key: pair again");
        yes(FastRules.probeWords(-1, null).contains("update it on the phone"), "closed without a word: an old app");
        yes(FastRules.probeWords(7, null).contains("unknown answer (7)"), "a strange answer is named");
        yes(FastRules.probeWords(-2, "failed to connect to /192.168.43.1 (port 8767): connect failed: ECONNREFUSED (Connection refused)")
                .contains("does not offer Fast Wi-Fi"), "refused: the phone app has no relay (old, or switched off)");
        yes(FastRules.probeWords(-2, "connect timed out").contains("did not answer in time"), "timed out");
        eq("The phone does not answer (Network is unreachable).", FastRules.probeWords(-2, "Network is unreachable"), "other errors, quoted");
        eq("The phone does not answer.", FastRules.probeWords(-2, null), "no message");
    }

    private static void speed() {
        eq("", FastRules.speed(1_000_000, 0, 100), "too short a time: no speed");
        eq("Quiet right now.", FastRules.speed(100, 100, 2000), "almost nothing went through");
        eq("Now 8.0 Mbit/s down, 0.4 up.", FastRules.speed(2_000_000, 100_000, 2000), "2 MB in 2 s is 8 Mbit/s");
        eq("Now 120 Mbit/s down, 12.0 up.", FastRules.speed(30_000_000, 3_000_000, 2000), "big numbers without a decimal");
        eq("", FastRules.speed(-5, 0, 2000), "a counter that went back (engine restarted): no speed");
        eq("12 KB", FastRules.size(12_345), "kilobytes");
        eq("340 MB", FastRules.size(340_000_000), "megabytes");
        eq("1.2 GB", FastRules.size(1_234_000_000), "gigabytes");
        eq("0 KB", FastRules.size(-1), "never negative");
        // the phone's own screen
        eq("Off. The tablet uses the normal hotspot.", FastRules.phoneLine(false, true, "", 3, 5, 10), "switched off on the phone");
        eq("Fast Wi-Fi could not open port 8767: in use.", FastRules.phoneLine(true, false, "Fast Wi-Fi could not open port 8767: in use", 0, 0, -1),
                "the relay's own problem, with a full stop");
        yes(FastRules.phoneLine(true, false, "", 0, 0, -1).startsWith("Not running"), "not running without a known reason");
        eq("Ready. The tablet has not used it since LinkMeter started.", FastRules.phoneLine(true, true, "", 0, 0, -1), "never used");
        eq("In use by the tablet: 1 connection, 12 KB carried so far.", FastRules.phoneLine(true, true, "", 1, 12_000, 5), "one connection");
        eq("In use by the tablet: 7 connections, 340 MB carried so far.", FastRules.phoneLine(true, true, "", 7, 340_000_000, 5), "several");
        eq("Ready. Last used 3 minutes ago; 1.2 GB carried so far.", FastRules.phoneLine(true, true, "", 0, 1_200_000_000, 200_000), "idle");
        eq("less than a minute ago", FastRules.ago(59_999), "seconds");
        eq("1 minute ago", FastRules.ago(60_000), "one minute");
        eq("2 hours ago", FastRules.ago(2 * 3600_000L + 5), "hours");
    }
}
