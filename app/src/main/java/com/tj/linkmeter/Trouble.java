package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/TroubleTest.java). Why the Home tab has no status from the phone, so that its card
 * names the real cause and offers the button that helps. One card used to say "Can't reach the phone" (and blame the hotspot
 * and the Bluetooth setup) for all of these, also when the phone had answered.
 */
final class Trouble {
    private Trouble() {}

    enum Kind {
        /** Nothing could be tried: the tablet is not on the phone's hotspot and has no usable Bluetooth link. */
        NO_ROUTE,
        /** Bluetooth was the way left, it is switched off on this tablet, and a status read does not switch it on. */
        BLUETOOTH_OFF,
        /** The phone was asked and did not answer. */
        NO_ANSWER,
        /** The phone answered: it does not know this tablet. */
        NOT_PAIRED,
        /** The phone answered with an error, or with something this app cannot read. */
        PHONE_ERROR
    }

    /**
     * answered: a reply came back (code is its HTTP code; a 200 that could not be read is still an answer).
     * asked: a route was really tried. bluetoothOff: see {@link Reach.Result#btOff}.
     */
    static Kind of(boolean answered, int code, boolean asked, boolean bluetoothOff) {
        if (answered) {
            return code == 401 ? Kind.NOT_PAIRED : Kind.PHONE_ERROR;
        }
        if (bluetoothOff) {
            return Kind.BLUETOOTH_OFF;
        }
        return asked ? Kind.NO_ANSWER : Kind.NO_ROUTE;
    }

    /**
     * Is the card shown for this miss? heardBefore: the phone has answered with its status since the app was opened (the screen
     * still shows those values). misses: questions in a row without a status. stopped: the tablet does not ask again by itself
     * (PollPace). bluetoothTried: the phone was called over Bluetooth for this question.
     *
     * Not for ONE lost answer over the hotspot from a phone that had been heard: the next question is a few seconds away, and
     * a card that flashes up for every lost answer is noise. In every other case the user needs the card's button NOW: the
     * switches are greyed out while the phone is not reached, and after a try over Bluetooth the next question is half a
     * minute or more away, or never comes (stopped).
     */
    static boolean shown(Kind k, boolean heardBefore, int misses, boolean stopped, boolean bluetoothTried) {
        return !heardBefore || k != Kind.NO_ANSWER || misses >= 2 || stopped || bluetoothTried;
    }

    static String title(Kind k) {
        switch (k) {
            case NO_ROUTE:
                return "Not connected to the phone";
            case BLUETOOTH_OFF:
                return "Bluetooth is off on this tablet";
            case NOT_PAIRED:
                return "The phone does not know this tablet";
            case PHONE_ERROR:
                return "The phone answered with an error";
            default:
                return "Can't reach the phone";
        }
    }

    /** The main button of the card. */
    static String button(Kind k) {
        return k == Kind.BLUETOOTH_OFF ? "Switch Bluetooth on" : "Ask again";
    }

    /**
     * Is "After a phone restart" offered? Only when the phone did not answer at all: a phone that answers, even with a refusal,
     * is running LinkMeter and does not need to be started.
     */
    static boolean offersRestart(Kind k) {
        return k == Kind.NO_ROUTE || k == Kind.NO_ANSWER;
    }

    /**
     * Does the card lead with "Switch Wi-Fi on"? When the phone was not reached and the tablet's own Wi-Fi is off: the phone's hotspot
     * cannot be reached at all then, whatever else is wrong, and after "Hotspot on" the hotspot is very likely on (the tablet joins it
     * by itself once its Wi-Fi is on). tabletWifiOn: the tablet's Wi-Fi, null when Android does not say (then the card stays as it was).
     */
    static boolean wifiFirst(Kind k, Boolean tabletWifiOn) {
        return Boolean.FALSE.equals(tabletWifiOn) && (k == Kind.NO_ROUTE || k == Kind.NO_ANSWER);
    }

    static final String WIFI_OFF_TITLE = "The tablet's Wi-Fi is off";

    /** The card's text when it leads with "Switch Wi-Fi on" ({@link #wifiFirst}); the arguments as for {@link #text}. */
    static String wifiOffText(Kind k, boolean bluetoothTried, String linkWords) {
        String also = k == Kind.NO_ROUTE ? "and it has no Bluetooth link to the phone"
                : bluetoothTried ? "and the phone did not answer over Bluetooth either" : "and the phone did not answer";
        return "The tablet cannot reach the phone's hotspot while its own Wi-Fi is off, " + also + ". Switch the Wi-Fi on: the tablet "
                + "joins the phone's hotspot by itself once the hotspot is on." + (k == Kind.NO_ROUTE && !linkWords.isEmpty() ? " " + linkWords : "");
    }

    /**
     * The card's text (the line above the card already gives the error as it came). overBluetooth: the answer, if any, came
     * over Bluetooth. bluetoothTried: the phone was called over Bluetooth. linkWords: why the Bluetooth link cannot be relied
     * on, "" when it is ready (LinkState.words).
     */
    static String text(Kind k, boolean overBluetooth, boolean bluetoothTried, String linkWords) {
        switch (k) {
            case NO_ROUTE:
                return "The tablet is not on the phone's hotspot. Join it in the tablet's Wi-Fi settings, or switch the hotspot "
                        + "on at the phone." + (linkWords.isEmpty() ? "" : " " + linkWords);
            case BLUETOOTH_OFF:
                return "The phone could not be reached over its hotspot, and this tablet's Bluetooth is switched off. LinkMeter "
                        + "does not switch it on just to read the status. This button does, and asks the phone again.";
            case NOT_PAIRED:
                return overBluetooth
                        ? "The phone answered over Bluetooth, but it has not been paired with this tablet. Pairing needs the "
                        + "phone's hotspot: switch it on at the phone, open LinkMeter there and tap Pair tablet."
                        : "The phone answered, but it has not been paired with this tablet. On the phone open LinkMeter and tap "
                        + "Pair tablet. The tablet then pairs by itself within a few seconds.";
            case PHONE_ERROR:
                return "The phone is there and answered, but not with its status. The two apps may be of different versions. "
                        + "Put the same version on both.";
            default:
                return (bluetoothTried ? "Neither the hotspot nor Bluetooth got through." : "The phone did not answer.")
                        + " Is the phone on and in range? After a restart of the phone LinkMeter starts only once the phone has "
                        + "been unlocked." + (bluetoothTried || linkWords.isEmpty() ? "" : " Bluetooth was not tried. " + linkWords);
        }
    }
}
