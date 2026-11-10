package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/LinkStateTest.java). What the TABLET may say about the Bluetooth link to the phone,
 * from what it actually knows. The link is the only way back to a phone whose hotspot is off, so "Ready" must not be said
 * lightly: a stored address alone proves nothing (the phone's Bluetooth may be off, the call may never have worked).
 *
 * "Ready" means all of: the phone's address is stored, a request over Bluetooth has been answered since that address was
 * stored, the latest try did not fail, and the phone's last status says its side is listening. Anything less is named for
 * what it is, with the button that is the way forward (Set up or Test).
 */
final class LinkState {
    private LinkState() {}

    enum Kind {
        /** The tablet's own switch is off. */
        OFF_HERE,
        NO_BLUETOOTH,
        NOT_ALLOWED,
        /** The phone's Bluetooth address is not known yet. */
        NO_ADDRESS,
        /** The phone's app is from before the link existed. */
        PHONE_OLD,
        /** The phone's own status says its side is not listening (Bluetooth off, no permission, switched off, failed). */
        PHONE_NOT_LISTENING,
        /** An address is stored, and no call to it has been answered yet. */
        NOT_TESTED,
        /** It has worked with this address, but the latest call did not get through. */
        NOT_REACHED,
        READY
    }

    /** What the tablet notes as the phone's link state when a status has no "bt" field: the phone's app is too old. */
    static final String OLD_APP = "old";

    /**
     * enabled: the tablet's switch. hasBluetooth, allowed: this tablet can use Bluetooth. address: the phone's address is
     * stored. proven: a request over Bluetooth has been answered since that address was stored. lastFailed: the latest call
     * over Bluetooth did not get through. phoneBt: "bt" of the phone's last status ("on", "off", "perm", "disabled", "error",
     * "none"), {@link #OLD_APP}, or "" when the tablet has not heard a status yet.
     */
    static Kind of(boolean enabled, boolean hasBluetooth, boolean allowed, boolean address, boolean proven, boolean lastFailed,
            String phoneBt) {
        if (!enabled) {
            return Kind.OFF_HERE;
        }
        if (!hasBluetooth) {
            return Kind.NO_BLUETOOTH;
        }
        if (!allowed) {
            return Kind.NOT_ALLOWED;
        }
        if (!address) {
            return Kind.NO_ADDRESS;
        }
        if (OLD_APP.equals(phoneBt)) {
            return Kind.PHONE_OLD;
        }
        if (!phoneBt.isEmpty() && !"on".equals(phoneBt)) {
            return Kind.PHONE_NOT_LISTENING; // the phone itself says so: an earlier call that worked no longer counts
        }
        if (!proven) {
            return Kind.NOT_TESTED;
        }
        if (lastFailed) {
            return Kind.NOT_REACHED;
        }
        // (a call that worked is also the phone saying "on", so this last "not tested" cannot come up in the app: it is here
        // so that the rule reads as it is stated)
        return "on".equals(phoneBt) ? Kind.READY : Kind.NOT_TESTED;
    }

    /** The word for the small label next to the card's heading. */
    static String chip(Kind k) {
        switch (k) {
            case READY:
                return "Ready";
            case OFF_HERE:
                return "Off";
            case NOT_TESTED:
                return "Not tested";
            case NOT_REACHED:
                return "Not reached";
            case PHONE_OLD:
            case PHONE_NOT_LISTENING:
                return "Phone not ready";
            default:
                return "Not set up";
        }
    }

    /**
     * What is the matter and the way forward, in whole sentences. The same words serve the Settings card and the reason in the
     * Home tab's "turn the hotspot off anyway?" question, so they name the buttons (Set up, Test) but not where they are.
     */
    static String words(Kind k, String phoneBt) {
        switch (k) {
            case READY:
                return "The phone answered over Bluetooth, and its last status says it is listening.";
            case OFF_HERE:
                return "The Bluetooth link is switched off on this tablet.";
            case NO_BLUETOOTH:
                return "This tablet has no Bluetooth.";
            case NOT_ALLOWED:
                return "LinkMeter is not allowed to use Bluetooth on this tablet yet. Press Set up.";
            case NO_ADDRESS:
                return "The Bluetooth link is not set up yet. Press Set up while the tablet is on the phone's hotspot.";
            case PHONE_OLD:
                return "The phone's app is too old for the Bluetooth link. Update the phone's app first.";
            case PHONE_NOT_LISTENING:
                return phoneSide(phoneBt);
            case NOT_REACHED:
                return "The last Bluetooth call to the phone did not get through. Press Test: the phone must be in range, "
                        + "with its Bluetooth on.";
            default:
                return "Not tested: the phone's Bluetooth address is stored, but no call to it has been answered yet. Press Test.";
        }
    }

    private static String phoneSide(String phoneBt) {
        switch (phoneBt) {
            case "off":
                return "The phone says its Bluetooth is switched off. Press Set up while the tablet is on the phone's hotspot: "
                        + "that switches it on.";
            case "perm":
                return "The phone says LinkMeter is not allowed to use Bluetooth there yet. Press Set up while the tablet is on "
                        + "the phone's hotspot.";
            case "disabled":
                return "The phone says the Bluetooth link is switched off there. Open LinkMeter on the phone and switch it on.";
            case "error":
                return "The phone says it could not start listening on Bluetooth. Press Set up while the tablet is on the "
                        + "phone's hotspot.";
            default:
                return "The phone says it has no Bluetooth, or LinkMeter's service is not running on it.";
        }
    }

    /**
     * A status that came over the hotspot offers a Bluetooth address for the phone: is it stored? Always when the tablet has no
     * address. When it has a different one, only while that one is unproven: an address a call has proved (it may have been
     * found among the paired devices because the phone's own report was wrong) is not thrown away for what a status says.
     * Running Set up again is the way to replace it.
     */
    static boolean takeAddress(String current, boolean proven, String offered) {
        if (!BtAddr.valid(offered) || offered.equals(current)) {
            return false;
        }
        return !BtAddr.valid(current) || !proven;
    }
}
