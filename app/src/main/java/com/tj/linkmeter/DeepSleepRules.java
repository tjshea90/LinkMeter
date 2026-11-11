package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/DeepSleepRulesTest.java). "Hotspot off" from the tablet, as the phone carries it out, and
 * "Hotspot on" as it undoes it:
 *
 * <pre>
 *   going to sleep:  1. the hotspot off   2. location off, if it was on   3. airplane mode on   4. the screen off (and the phone asleep)
 *   waking up:       1. airplane mode off, if it is on   2. the hotspot on   (location and the screen are left alone)
 * </pre>
 *
 * The rule this class exists for: on this phone airplane mode takes Bluetooth away, and Bluetooth is the tablet's ONLY way back to
 * the phone once the hotspot is off. So airplane mode is switched on only while the Bluetooth link is up; it STAYS on only if
 * Bluetooth is up again afterwards and stays up; if not, it is switched off again at once. The phone is never left in airplane mode
 * with no way for the tablet to wake it, unless airplane mode cannot be switched off again either, and then the words say that the
 * phone has to be picked up.
 *
 * The phone's steps are behind {@link Phone}, so the rules are tried against simulated phones. Every step answers with a
 * {@link SwitchWords.Said}: it says "done" only for what was read back, never for a command that merely went out.
 */
final class DeepSleepRules {
    private DeepSleepRules() {}

    /** What the phone can do. Every method is a whole step, checked before it answers; none throws. */
    interface Phone {
        // ---- going to sleep
        /** The hotspot off, the clean way only (the airplane blip would end in airplane mode anyway). */
        SwitchWords.Said hotspotOff();

        /** Is location on? null when the phone does not say. */
        Boolean locationOn();

        /** Location off, read back. */
        SwitchWords.Said locationOff();

        /** Is the Bluetooth link up right now: Bluetooth on and the phone listening for the tablet? */
        boolean linkIsUp();

        /** Airplane mode on, read back. */
        SwitchWords.Said airplaneOn();

        /**
         * After airplane mode went on: is the link up again, and does it stay up? (Bluetooth is switched on again if airplane mode
         * took it, and looked at twice a few seconds apart.)
         */
        boolean linkStaysUp();

        /** Airplane mode off, read back. */
        SwitchWords.Said airplaneOff();

        /** The screen off and the phone asleep, checked. */
        SwitchWords.Said screenOff();

        // ---- waking up
        /** Is airplane mode on now? */
        boolean airplaneIsOn();

        /** Gives the phone's radio time to come back after airplane mode went off (best effort; the hotspot is started either way). */
        void waitForRadio();

        /** The hotspot on, as {@link Actions} does it. */
        SwitchWords.Said hotspotOn();
    }

    /** How going to sleep came out. */
    static final class Result {
        /** Every step did what it was asked (an "already" counts). */
        final boolean done;
        /** Airplane mode was left on. */
        final boolean airplane;
        /** What happened, in whole sentences, in the order of the steps. */
        final String text;

        Result(boolean done, boolean airplane, String text) {
            this.done = done;
            this.airplane = airplane;
            this.text = text;
        }
    }

    private static void add(StringBuilder sb, String sentence) {
        String t = sentence.trim();
        if (t.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(SwitchWords.dot(t));
    }

    /** The four steps of "Hotspot off", always in this order; the screen step is tried whatever came before it. */
    static Result sleep(Phone p) {
        StringBuilder text = new StringBuilder();
        boolean done = true;

        SwitchWords.Said hotspot = p.hotspotOff();
        add(text, hotspot.text);
        done &= hotspot.done;

        Boolean location = p.locationOn();
        SwitchWords.Said loc;
        if (location == null) {
            loc = new SwitchWords.Said(false, "The phone does not say whether location is on, so it was left alone.");
        } else if (!location.booleanValue()) {
            loc = new SwitchWords.Said(true, "Location was already off.");
        } else {
            loc = p.locationOff();
        }
        add(text, loc.text);
        done &= loc.done;

        boolean airplane = false;
        SwitchWords.Said air;
        if (!p.linkIsUp()) {
            air = new SwitchWords.Said(false, "Airplane mode was not switched on: the Bluetooth link is not up, and then the tablet could not "
                    + "wake the phone again.");
        } else {
            SwitchWords.Said on = p.airplaneOn();
            if (!on.done) {
                air = on;
            } else if (p.linkStaysUp()) {
                air = new SwitchWords.Said(true, "Airplane mode is on, and Bluetooth stays on so that the tablet can wake the phone.");
                airplane = true;
            } else {
                SwitchWords.Said off = p.airplaneOff();
                if (off.done) {
                    air = new SwitchWords.Said(false, "Airplane mode was switched off again: Bluetooth could not stay on, and then the tablet "
                            + "could not wake the phone.");
                } else {
                    air = new SwitchWords.Said(false, "Airplane mode is on and Bluetooth is not, and airplane mode could not be switched off "
                            + "again. The phone has to be picked up: swipe down and tap Airplane mode.");
                    airplane = true;
                }
            }
        }
        add(text, air.text);
        done &= air.done;

        SwitchWords.Said screen = p.screenOff();
        add(text, screen.text);
        done &= screen.done;

        return new Result(done, airplane, text.toString());
    }

    /**
     * "Hotspot on": airplane mode off first if it is on (the hotspot cannot work in airplane mode), then the hotspot. If airplane mode
     * cannot be switched off the hotspot is not started and the answer says so. Location and the screen are not touched.
     */
    static SwitchWords.Said wake(Phone p) {
        String lead = "";
        if (p.airplaneIsOn()) {
            SwitchWords.Said off = p.airplaneOff();
            if (!off.done) {
                return new SwitchWords.Said(false, SwitchWords.dot(off.text) + " The hotspot was not started, because it cannot work in airplane mode.");
            }
            lead = "Airplane mode is off. ";
            p.waitForRadio();
        }
        SwitchWords.Said hotspot = p.hotspotOn();
        return new SwitchWords.Said(hotspot.done, lead + hotspot.text);
    }
}
