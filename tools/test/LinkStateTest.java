package com.tj.linkmeter;

/**
 * Offline test of LinkState: what the tablet may say about the Bluetooth link. The rule that must never break: "Ready" is said
 * only when the phone's address is stored, a call to it has been answered, the latest call did not fail and the phone's last
 * status says its side is listening; and an address that a call has proved is not replaced by what a status says. Also: which
 * states put a card on the Home tab (one failed call after the link had worked does not).
 * Run by tools/test.sh. Prints "N checks, M failed". ASCII only.
 */
public final class LinkStateTest {
    static int checks;
    static int failed;

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            failed++;
            if (failed <= 25) {
                System.out.println("  FAIL: " + msg);
            }
        }
    }

    static final String[] PHONE = {"", "on", "off", "perm", "disabled", "error", "none", LinkState.OLD_APP, "something new"};

    static void everyCombination() {
        boolean[] tf = {false, true};
        int ready = 0;
        for (boolean enabled : tf) {
            for (boolean has : tf) {
                for (boolean allowed : tf) {
                    for (boolean address : tf) {
                        for (boolean proven : tf) {
                            for (boolean lastFailed : tf) {
                                for (String phone : PHONE) {
                                    LinkState.Kind k = LinkState.of(enabled, has, allowed, address, proven, lastFailed, phone);
                                    String what = enabled + " " + has + " " + allowed + " " + address + " " + proven + " " + lastFailed + " [" + phone + "] -> " + k;
                                    boolean should = enabled && has && allowed && address && proven && !lastFailed && phone.equals("on");
                                    check((k == LinkState.Kind.READY) == should, "Ready exactly when everything is known to be in place: " + what);
                                    ready += k == LinkState.Kind.READY ? 1 : 0;
                                    check((k == LinkState.Kind.OFF_HERE) == !enabled, "Off exactly when the tablet's switch is off: " + what);
                                    if (enabled && has && allowed && address) {
                                        // the phone's own word outweighs an old success
                                        boolean phoneSaysNo = !phone.isEmpty() && !phone.equals("on");
                                        check(!phoneSaysNo || k == LinkState.Kind.PHONE_OLD || k == LinkState.Kind.PHONE_NOT_LISTENING,
                                                "a phone that says its side is not listening is believed: " + what);
                                        check((k == LinkState.Kind.PHONE_OLD) == phone.equals(LinkState.OLD_APP), "an old phone app is named as that: " + what);
                                        if (!phoneSaysNo) {
                                            check(proven || k == LinkState.Kind.NOT_TESTED, "an address no call has proved is 'not tested': " + what);
                                            check(!(proven && lastFailed) || k == LinkState.Kind.NOT_REACHED, "a failed last call is 'not reached': " + what);
                                        }
                                    } else if (enabled) {
                                        check(k == (!has ? LinkState.Kind.NO_BLUETOOTH : !allowed ? LinkState.Kind.NOT_ALLOWED : LinkState.Kind.NO_ADDRESS),
                                                "what is missing on the tablet comes first: " + what);
                                    }
                                    words(k, phone);
                                }
                            }
                        }
                    }
                }
            }
        }
        check(ready == 1, "exactly one combination is Ready (got " + ready + ")");
    }

    static void words(LinkState.Kind k, String phone) {
        String chip = LinkState.chip(k);
        String w = LinkState.words(k, phone);
        check(chip.equals("Ready") == (k == LinkState.Kind.READY), "the chip says Ready only for Ready: " + k + " " + chip);
        check(!chip.isEmpty() && chip.length() <= 16, "a short chip: " + chip);
        check(w.endsWith(".") && Character.isUpperCase(w.charAt(0)) && !w.contains("!") && !w.contains("  ") && !w.contains(" - "),
                "whole plain sentences: [" + w + "]");
        // the way forward is named
        if (k == LinkState.Kind.NOT_TESTED || k == LinkState.Kind.NOT_REACHED) {
            check(w.contains("Press Test"), k + " names Test: " + w);
        }
        if (k == LinkState.Kind.NO_ADDRESS || k == LinkState.Kind.NOT_ALLOWED) {
            check(w.contains("Press Set up"), k + " names Set up: " + w);
        }
        if (k == LinkState.Kind.PHONE_NOT_LISTENING) {
            check(w.startsWith("The phone says"), "the phone's own word is given as that: " + w);
            if (phone.equals("off") || phone.equals("perm") || phone.equals("error")) {
                check(w.contains("Press Set up"), "what Set up can mend names Set up: " + w);
            } else {
                check(!w.contains("Press Set up") && !w.contains("Press Test"), "what the tablet cannot mend names no button: " + w);
            }
            if (phone.equals("disabled")) {
                check(w.contains("on the phone"), "a switch on the phone is mended on the phone: " + w);
            }
        }
        if (k != LinkState.Kind.READY) {
            check(!w.contains("answered over Bluetooth"), "nothing but Ready says the phone answered: " + w);
        }
    }

    static void scenarios() {
        // the user's story from the audit: Set up stored the address, the test call failed
        check(LinkState.of(true, true, true, true, false, true, "on") == LinkState.Kind.NOT_TESTED, "address stored, call failed: not tested, not Ready");
        // it worked once, then the phone's Bluetooth was switched off and a status over the hotspot said so
        check(LinkState.of(true, true, true, true, true, false, "off") == LinkState.Kind.PHONE_NOT_LISTENING, "the phone's Bluetooth is off: not Ready");
        // it worked, later the phone was out of range
        check(LinkState.of(true, true, true, true, true, true, "on") == LinkState.Kind.NOT_REACHED, "last call failed: not Ready");
        // everything in place
        check(LinkState.of(true, true, true, true, true, false, "on") == LinkState.Kind.READY, "all in place: Ready");
        // proved, but the tablet never heard the phone's own word (cannot happen in the app; the rule still refuses)
        check(LinkState.of(true, true, true, true, true, false, "") == LinkState.Kind.NOT_TESTED, "no word from the phone: not Ready");
        check(LinkState.chip(LinkState.Kind.OFF_HERE).equals("Off") && LinkState.chip(LinkState.Kind.NO_ADDRESS).equals("Not set up")
                && LinkState.chip(LinkState.Kind.NOT_TESTED).equals("Not tested") && LinkState.chip(LinkState.Kind.NOT_REACHED).equals("Not reached"),
                "the chips");
    }

    static void addresses() {
        String a = "5C:F3:70:8A:1B:2C";
        String b = "AA:BB:CC:11:22:33";
        check(LinkState.takeAddress("", false, a), "no address yet: the phone's is taken");
        check(LinkState.takeAddress("", true, a), "no address yet (whatever 'proven' says): taken");
        check(LinkState.takeAddress(b, false, a), "an address nothing has proved is replaced by the phone's newer word");
        check(!LinkState.takeAddress(b, true, a), "an address a call has proved is NOT replaced by a status");
        check(!LinkState.takeAddress(a, true, a) && !LinkState.takeAddress(a, false, a), "the same address: nothing to do");
        for (String bad : new String[] {"", "02:00:00:00:00:00", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "XX:XX:XX:XX:1B:2C", "5c:f3:70:8a:1b:2c", "5C:F3:70:8A:1B", null}) {
            check(!LinkState.takeAddress("", false, bad) && !LinkState.takeAddress(b, false, bad), "not an address, never taken: " + bad);
        }
        check(LinkState.takeAddress("02:00:00:00:00:00", true, a), "a stored placeholder is no address: the phone's is taken");
    }

    /** Which states put a card about the link on the Home tab. */
    static void onHome() {
        for (LinkState.Kind k : LinkState.Kind.values()) {
            boolean said = LinkState.saidOnHome(k);
            boolean missingOrUnproven = k == LinkState.Kind.NOT_ALLOWED || k == LinkState.Kind.NO_ADDRESS || k == LinkState.Kind.PHONE_OLD
                    || k == LinkState.Kind.PHONE_NOT_LISTENING || k == LinkState.Kind.NOT_TESTED;
            check(said == missingOrUnproven, "a card on Home exactly when the link is known to be missing or unproven: " + k + " -> " + said);
        }
        check(!LinkState.saidOnHome(LinkState.Kind.READY), "a link that is ready is not a card on Home");
        check(!LinkState.saidOnHome(LinkState.Kind.NOT_REACHED), "one failed call after the link had worked is not a card on Home (Settings shows it)");
        check(!LinkState.saidOnHome(LinkState.Kind.OFF_HERE) && !LinkState.saidOnHome(LinkState.Kind.NO_BLUETOOTH),
                "nor is a link the user switched off here, or a tablet without Bluetooth");
        check(LinkState.saidOnHome(LinkState.Kind.NOT_TESTED) && LinkState.saidOnHome(LinkState.Kind.NO_ADDRESS)
                && LinkState.saidOnHome(LinkState.Kind.PHONE_NOT_LISTENING), "a link never tested, not set up, or not listening on the phone is");
        // the phone left the house and came back: it worked, one call failed, the phone says "on" again over the hotspot
        check(!LinkState.saidOnHome(LinkState.of(true, true, true, true, true, true, "on")), "the phone was out of range once: no card on Home");
    }

    public static void main(String[] args) {
        everyCombination();
        scenarios();
        addresses();
        onHome();
        System.out.println(checks + " checks, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
