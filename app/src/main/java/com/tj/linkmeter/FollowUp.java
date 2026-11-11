package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/FollowUpTest.java). What became of a switch the phone only answered with "Sent"?
 *
 * An action that takes away the link it was asked over is answered BEFORE the phone carries it out (the reply has
 * "later": true), so that reply says nothing about the result. The tablet then looks at the phone's status every
 * {@link #EVERY_MS} for up to {@link #GIVE_UP_MS} and this class decides, look by look, whether the status shows what the
 * action promised. It only ever says "is on" / "is off" for what a status of the phone itself reported. For the actions whose
 * success is that the phone can no longer be asked (power off, restart, and a hotspot switched off with no Bluetooth link to
 * fall back on) it says exactly that: the phone no longer answers, which is what this looks like from here.
 *
 * One object follows one request: {@link #of} when the "Sent" arrives, {@link #look} after every status read (null for a
 * read that got no answer), {@link #giveUp} when the time is over. The Home tab uses it; the Phone tab can use it the same way.
 */
final class FollowUp {
    static final long EVERY_MS = 3_000L;
    static final long GIVE_UP_MS = 40_000L;
    /** This many looks in a row without an answer, and the phone counts as out of reach (one lost answer is not that). */
    static final int GONE_AFTER = 2;
    /**
     * "Hotspot off" in its deep form ends in airplane mode, and Bluetooth may be away for several looks while the phone puts it back:
     * so it takes longer to give up, and more silent looks before the phone counts as out of reach.
     */
    static final long GIVE_UP_DEEP_MS = 60_000L;
    static final int GONE_AFTER_DEEP = 6;
    /** "Hotspot on" from airplane mode waits for the radio before it starts the hotspot: the phone may need this long. */
    static final long GIVE_UP_FROM_AIRPLANE_MS = 90_000L;

    /** What one status of the phone showed. A field the status left out (the phone cannot tell) is null. */
    static final class Seen {
        final Boolean hotspot;
        final Boolean screen;
        final Boolean wifi;
        final Boolean airplane;
        /** Location, as the phone's status says; null when it does not. */
        final Boolean location;
        /** What the phone says it did the last time it went to sleep ("dres"), "" when it says nothing. */
        final String note;

        Seen(Boolean hotspot, Boolean screen, Boolean wifi, Boolean airplane) {
            this(hotspot, screen, wifi, airplane, null, "");
        }

        Seen(Boolean hotspot, Boolean screen, Boolean wifi, Boolean airplane, Boolean location, String note) {
            this.hotspot = hotspot;
            this.screen = screen;
            this.wifi = wifi;
            this.airplane = airplane;
            this.location = location;
            this.note = note == null ? "" : note;
        }
    }

    /** The last word on a request. ok false: the phone did not confirm it, or its status says otherwise. */
    static final class Outcome {
        final boolean ok;
        final String text;

        Outcome(boolean ok, String text) {
            this.ok = ok;
            this.text = text;
        }
    }

    private final String action;
    /** How long to look before giving up, and how many silent looks in a row mean the phone is out of reach. */
    private final long giveUpMs;
    private final int goneAfter;
    /** Looks in a row that got no answer. */
    private int silent;
    /** Since the request, the hotspot was seen off or the phone out of reach (a restart must pass through that). */
    private boolean wentAway;
    private Seen last;

    private FollowUp(String action, long giveUpMs, int goneAfter) {
        this.action = action;
        this.giveUpMs = giveUpMs;
        this.goneAfter = goneAfter;
    }

    /** How long this follow-up looks before it gives up (the caller asks {@link #giveUp} when that time is over). */
    long giveUpMs() {
        return giveUpMs;
    }

    /**
     * The follow-up for this action, or null when no status can show what it did (then "Sent" stays the last word; the radio
     * restart is one of those: it has its own question, radio_cycle_state).
     */
    static FollowUp of(String action) {
        return of(action, false);
    }

    /**
     * The same; fromAirplane: the phone was in airplane mode when the hotspot was asked to come on. The phone then ends airplane mode,
     * waits for its radio and only then starts the hotspot, so the wait is longer.
     */
    static FollowUp of(String action, boolean fromAirplane) {
        switch (action) {
            case "hotspot_off_deep":
                return new FollowUp(action, GIVE_UP_DEEP_MS, GONE_AFTER_DEEP);
            case "hotspot_on":
            case "boot_hotspot_on":
                return new FollowUp(action, fromAirplane ? GIVE_UP_FROM_AIRPLANE_MS : GIVE_UP_MS, GONE_AFTER);
            case "hotspot_off":
            case "hotspot_restart":
            case "standby":
            case "wifi_on":
            case "wifi_off":
            case "airplane_on":
            case "poweroff":
            case "reboot":
                return new FollowUp(action, GIVE_UP_MS, GONE_AFTER);
            default:
                return null;
        }
    }

    private static boolean is(Boolean field, boolean wanted) {
        return field != null && field.booleanValue() == wanted;
    }

    /** One look at the phone: seen is its status, or null when it did not answer. The outcome, or null to keep looking. */
    Outcome look(Seen seen) {
        last = seen;
        silent = seen == null ? silent + 1 : 0;
        if (seen == null || is(seen.hotspot, false)) {
            wentAway = true;
        }
        boolean gone = silent >= goneAfter;
        switch (action) {
            case "hotspot_on":
            case "boot_hotspot_on":
                return seen != null && is(seen.hotspot, true) ? new Outcome(true, "The hotspot is on.") : null;
            case "hotspot_off":
                if (seen != null && is(seen.hotspot, false)) {
                    return new Outcome(true, "The hotspot is off.");
                }
                return gone ? silence() : null;
            case "standby":
                if (seen != null && is(seen.hotspot, false) && is(seen.screen, false)) {
                    return new Outcome(true, "The hotspot and the screen are off.");
                }
                return gone ? silence() : null;
            case "hotspot_off_deep":
                // the hotspot, airplane mode and the screen are the result; location is told as the status shows it
                if (seen != null && is(seen.hotspot, false) && is(seen.airplane, true) && is(seen.screen, false)) {
                    return new Outcome(true, "The hotspot and the screen are off and airplane mode is on."
                            + (seen.location == null ? "" : seen.location.booleanValue() ? " Location is still on." : " Location is off."));
                }
                return gone ? silence() : null;
            case "hotspot_restart":
                return seen != null && is(seen.hotspot, true) && wentAway ? new Outcome(true, "The hotspot is back on.") : null;
            case "wifi_on":
                return seen != null && is(seen.wifi, true) ? new Outcome(true, "The phone's Wi-Fi is on.") : null;
            case "wifi_off":
                if (seen != null && is(seen.wifi, false)) {
                    return new Outcome(true, "The phone's Wi-Fi is off.");
                }
                return gone ? silence() : null;
            case "airplane_on":
                if (seen != null && is(seen.airplane, true)) {
                    return new Outcome(true, "Airplane mode is on.");
                }
                return gone ? silence() : null;
            default: // poweroff, reboot: there is no status that could confirm them
                return gone ? silence() : null;
        }
    }

    /** Does a phone that no longer answers fit what was asked? (Not for "on" requests: there silence is just silence.) */
    private boolean silenceFits() {
        return !action.equals("hotspot_on") && !action.equals("boot_hotspot_on") && !action.equals("hotspot_restart")
                && !action.equals("wifi_on");
    }

    /** The phone stopped answering after an action that takes it out of reach: said as what it is, not as "done". */
    private Outcome silence() {
        String what;
        String then;
        switch (action) {
            case "poweroff":
                what = "powering off";
                then = "It has to be switched on by hand.";
                break;
            case "reboot":
                what = "a restart";
                then = "When it is back it has to be unlocked before LinkMeter runs again, and Shizuku has to be started again.";
                break;
            case "airplane_on":
                what = "airplane mode";
                then = "The phone switches airplane mode off by itself after 15 minutes.";
                break;
            case "wifi_off":
                what = "its Wi-Fi going off";
                then = "The tablet cannot ask it whether it did.";
                break;
            case "hotspot_off_deep":
                what = "its hotspot going off and the phone going to sleep";
                then = "The tablet cannot ask it what it did. Press Hotspot on to wake it; if that does not answer either, the phone has to be picked up.";
                break;
            default: // hotspot_off, standby
                what = "its hotspot going off";
                then = "The tablet cannot ask it whether it did.";
                break;
        }
        return new Outcome(true, "The phone no longer answers, which is what " + what + " looks like from here. " + then);
    }

    /** The time is over and no look settled it: what the last look showed, in the phone's favour only where it reported it. */
    Outcome giveUp() {
        if (last == null) {
            if (silent > 0 && silenceFits()) {
                return silence();
            }
            return notYet("it does not answer right now");
        }
        switch (action) {
            case "hotspot_on":
            case "boot_hotspot_on":
                return notYet(last.hotspot == null ? "it does not say whether its hotspot is on" : "its status still says the hotspot is off");
            case "hotspot_off":
                return notYet(last.hotspot == null ? "it does not say whether its hotspot is on" : "its status still says the hotspot is on");
            case "standby":
                return notYet("its status says the hotspot is " + onOff(last.hotspot) + " and the screen is " + onOff(last.screen));
            case "hotspot_off_deep":
                return notYet("its status says the hotspot is " + onOff(last.hotspot) + ", airplane mode is " + onOff(last.airplane) + " and the screen is "
                        + onOff(last.screen) + (last.location == null ? "" : ", location is " + onOff(last.location)) + note());
            case "hotspot_restart":
                if (last.hotspot == null) {
                    return notYet("it does not say whether its hotspot is on");
                }
                return notYet(last.hotspot ? "its hotspot is on, but the tablet did not see it go off in between"
                        : "its status says the hotspot is off");
            case "wifi_on":
            case "wifi_off":
                return notYet("its status says its Wi-Fi is " + onOff(last.wifi));
            case "airplane_on":
                return notYet("it still answers, and its status says airplane mode is " + onOff(last.airplane));
            default:
                return notYet("it still answers");
        }
    }

    /** What the phone said it did, as the end of a sentence: ". The phone says: ...", or nothing. */
    private String note() {
        String n = last == null ? "" : last.note.trim();
        while (n.endsWith(".")) {
            n = n.substring(0, n.length() - 1).trim();
        }
        return n.isEmpty() ? "" : ". The phone says: " + n;
    }

    private static String onOff(Boolean b) {
        return b == null ? "not known" : b ? "on" : "off";
    }

    private static Outcome notYet(String why) {
        return new Outcome(false, "The phone has not confirmed it yet: " + why + ".");
    }
}
