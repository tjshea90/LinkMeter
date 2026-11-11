package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests DeepSleepRules against simulated phones: "Hotspot off" does its four steps in order, switches location off only if it was
 * on, switches airplane mode on only while the Bluetooth link is up and keeps it only if Bluetooth stays up (otherwise it is switched
 * off again, and if that fails too the words say the phone has to be picked up); "Hotspot on" ends airplane mode first, never starts
 * the hotspot in airplane mode and leaves location and the screen alone. Not part of the app.
 */
public final class DeepSleepRulesTest {
    private static int checks;
    private static int fails;

    private DeepSleepRulesTest() {}

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

    private static SwitchWords.Said said(boolean done, String text) {
        return new SwitchWords.Said(done, text);
    }

    /** A phone that does what it is told to do, and writes down what it was asked. */
    private static class Sim implements DeepSleepRules.Phone {
        boolean hotspotOffDone = true;
        Boolean location = Boolean.TRUE;
        boolean locationOffDone = true;
        boolean linkUp = true;
        boolean airplaneOnDone = true;
        boolean staysUp = true;
        boolean airplaneOffDone = true;
        boolean screenDone = true;
        boolean airplane;
        boolean hotspotOnDone = true;
        final List<String> log = new ArrayList<>();

        @Override
        public SwitchWords.Said hotspotOff() {
            log.add("hotspotOff");
            return said(hotspotOffDone, hotspotOffDone ? "The hotspot is off." : "The hotspot could not be switched off.");
        }

        @Override
        public Boolean locationOn() {
            log.add("locationOn");
            return location;
        }

        @Override
        public SwitchWords.Said locationOff() {
            log.add("locationOff");
            return said(locationOffDone, locationOffDone ? "Location is off." : "Location could not be switched off.");
        }

        @Override
        public boolean linkIsUp() {
            log.add("linkIsUp");
            return linkUp;
        }

        @Override
        public SwitchWords.Said airplaneOn() {
            log.add("airplaneOn");
            if (airplaneOnDone) {
                airplane = true;
            }
            return said(airplaneOnDone, airplaneOnDone ? "Airplane mode is on." : "Airplane mode could not be switched on.");
        }

        @Override
        public boolean linkStaysUp() {
            log.add("linkStaysUp");
            return staysUp;
        }

        @Override
        public SwitchWords.Said airplaneOff() {
            log.add("airplaneOff");
            if (airplaneOffDone) {
                airplane = false;
            }
            return said(airplaneOffDone, airplaneOffDone ? "Airplane mode is off." : "Airplane mode could not be switched off.");
        }

        @Override
        public SwitchWords.Said screenOff() {
            log.add("screenOff");
            return said(screenDone, screenDone ? "The screen is off." : "The screen did not stay off.");
        }

        @Override
        public boolean airplaneIsOn() {
            log.add("airplaneIsOn");
            return airplane;
        }

        @Override
        public void waitForRadio() {
            log.add("waitForRadio");
        }

        @Override
        public SwitchWords.Said hotspotOn() {
            log.add("hotspotOn");
            return said(hotspotOnDone, hotspotOnDone ? "The hotspot is on." : "The hotspot could not be switched on.");
        }
    }

    public static void main(String[] args) {
        stories();
        everySleep();
        wakeStories();
        everyWake();
        System.out.println(checks + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }

    private static boolean has(Sim s, String call) {
        return s.log.contains(call);
    }

    /** The cases a person would tell, with the words. */
    private static void stories() {
        // the usual night
        Sim s = new Sim();
        DeepSleepRules.Result r = DeepSleepRules.sleep(s);
        eq("[hotspotOff, locationOn, locationOff, linkIsUp, airplaneOn, linkStaysUp, screenOff]", s.log, "the usual night: the steps in the order asked for");
        eq("The hotspot is off. Location is off. Airplane mode is on, and Bluetooth stays on so that the tablet can wake the phone. The screen is off.",
                r.text, "the usual night: the words");
        yes(r.done && r.airplane, "the usual night: done, airplane mode left on");
        yes(s.airplane, "the usual night: the phone is in airplane mode");

        // location was off already: nothing is switched, and it says so
        s = new Sim();
        s.location = Boolean.FALSE;
        r = DeepSleepRules.sleep(s);
        yes(!has(s, "locationOff"), "location already off: not switched again");
        yes(r.text.contains("Location was already off."), "location already off: says so: " + r.text);
        yes(r.done, "location already off counts as done");

        // location not known: left alone, and not called done
        s = new Sim();
        s.location = null;
        r = DeepSleepRules.sleep(s);
        yes(!has(s, "locationOff"), "location not known: left alone");
        yes(r.text.contains("does not say whether location is on"), "location not known: says so");
        yes(!r.done, "location not known is not done");
        yes(has(s, "airplaneOn") && has(s, "screenOff"), "location not known: the rest goes on");

        // the Bluetooth link is not up: airplane mode is never switched on
        s = new Sim();
        s.linkUp = false;
        r = DeepSleepRules.sleep(s);
        yes(!has(s, "airplaneOn"), "link not up: airplane mode is not switched on");
        yes(!s.airplane && !r.airplane, "link not up: the phone is not in airplane mode");
        yes(r.text.contains("Airplane mode was not switched on: the Bluetooth link is not up"), "link not up: says why: " + r.text);
        yes(has(s, "screenOff") && has(s, "locationOff"), "link not up: location and the screen still follow");
        yes(!r.done, "link not up: not done");

        // airplane mode went on but Bluetooth did not stay up: airplane mode is switched off again
        s = new Sim();
        s.staysUp = false;
        r = DeepSleepRules.sleep(s);
        eq("[hotspotOff, locationOn, locationOff, linkIsUp, airplaneOn, linkStaysUp, airplaneOff, screenOff]", s.log, "Bluetooth not back: airplane mode is undone, then the screen");
        yes(!s.airplane && !r.airplane, "Bluetooth not back: the phone is not left in airplane mode");
        yes(r.text.contains("Airplane mode was switched off again: Bluetooth could not stay on"), "Bluetooth not back: says so: " + r.text);
        yes(!r.text.contains("Airplane mode is on"), "Bluetooth not back: never says airplane mode is on");
        yes(!r.done, "Bluetooth not back: not done");

        // ... and it cannot be undone: the phone is stranded and the words say it has to be picked up
        s = new Sim();
        s.staysUp = false;
        s.airplaneOffDone = false;
        r = DeepSleepRules.sleep(s);
        yes(s.airplane && r.airplane, "stranded: it is in airplane mode, and the result knows");
        yes(r.text.contains("has to be picked up"), "stranded: says the phone has to be picked up: " + r.text);
        yes(!r.done, "stranded: not done");
        yes(has(s, "screenOff"), "stranded: the screen still goes off");

        // airplane mode could not be switched on: its own words, nothing to undo
        s = new Sim();
        s.airplaneOnDone = false;
        r = DeepSleepRules.sleep(s);
        yes(!has(s, "linkStaysUp") && !has(s, "airplaneOff"), "airplane mode would not go on: nothing to check, nothing to undo");
        yes(r.text.contains("Airplane mode could not be switched on."), "airplane mode would not go on: its words");

        // the hotspot would not go off: the rest is still done (airplane mode takes it down anyway), and it says so
        s = new Sim();
        s.hotspotOffDone = false;
        r = DeepSleepRules.sleep(s);
        yes(has(s, "airplaneOn") && has(s, "screenOff") && has(s, "locationOff"), "hotspot would not go off: the rest is done");
        yes(r.text.startsWith("The hotspot could not be switched off."), "hotspot would not go off: says so first");
        yes(!r.done, "hotspot would not go off: not done");

        // a sentence that has no full stop gets one, and two sentences never run together
        s = new Sim() {
            @Override
            public SwitchWords.Said hotspotOff() {
                log.add("hotspotOff");
                return said(true, "The hotspot is off");
            }
        };
        r = DeepSleepRules.sleep(s);
        yes(r.text.startsWith("The hotspot is off. Location is off."), "a missing full stop is added: " + r.text);
        yes(!r.text.contains("  "), "no double spaces");
    }

    /** All 384 outcomes of the eight things the phone may answer. */
    private static void everySleep() {
        int worlds = 0;
        Boolean[] locs = {Boolean.TRUE, Boolean.FALSE, null};
        for (boolean hot : new boolean[] {true, false}) {
            for (Boolean loc : locs) {
                for (boolean locDone : new boolean[] {true, false}) {
                    for (boolean up : new boolean[] {true, false}) {
                        for (boolean airOn : new boolean[] {true, false}) {
                            for (boolean stays : new boolean[] {true, false}) {
                                for (boolean airOff : new boolean[] {true, false}) {
                                    for (boolean scr : new boolean[] {true, false}) {
                                        worlds++;
                                        Sim s = new Sim();
                                        s.hotspotOffDone = hot;
                                        s.location = loc;
                                        s.locationOffDone = locDone;
                                        s.linkUp = up;
                                        s.airplaneOnDone = airOn;
                                        s.staysUp = stays;
                                        s.airplaneOffDone = airOff;
                                        s.screenDone = scr;
                                        DeepSleepRules.Result r = DeepSleepRules.sleep(s);
                                        String w = "hot=" + hot + " loc=" + loc + "/" + locDone + " up=" + up + " airOn=" + airOn + " stays=" + stays
                                                + " airOff=" + airOff + " scr=" + scr + " -> " + s.log + " | " + r.text;
                                        // the steps, in order, each only when it is its turn
                                        List<String> want = new ArrayList<>();
                                        want.add("hotspotOff");
                                        want.add("locationOn");
                                        if (Boolean.TRUE.equals(loc)) {
                                            want.add("locationOff");
                                        }
                                        want.add("linkIsUp");
                                        boolean keptOn = false;
                                        boolean stranded = false;
                                        if (up) {
                                            want.add("airplaneOn");
                                            if (airOn) {
                                                want.add("linkStaysUp");
                                                if (stays) {
                                                    keptOn = true;
                                                } else {
                                                    want.add("airplaneOff");
                                                    stranded = !airOff;
                                                    keptOn = stranded;
                                                }
                                            }
                                        }
                                        want.add("screenOff");
                                        eq(want, s.log, "the steps: " + w);
                                        // the safety rule: airplane mode is left on only with Bluetooth up, or the words say the phone must be picked up
                                        eq(keptOn, s.airplane, "the phone's airplane mode: " + w);
                                        eq(keptOn, r.airplane, "the result knows: " + w);
                                        if (s.airplane && !stays) {
                                            yes(r.text.contains("has to be picked up") && !r.done, "stranded is said and not done: " + w);
                                        }
                                        if (s.airplane && stays) {
                                            yes(r.text.contains("Airplane mode is on, and Bluetooth stays on"), "kept on with Bluetooth up says so: " + w);
                                        }
                                        if (!s.airplane) {
                                            yes(!r.text.contains("Airplane mode is on"), "never says airplane mode is on when it is not: " + w);
                                        }
                                        // done only when every step did its job
                                        boolean locOk = Boolean.FALSE.equals(loc) || (Boolean.TRUE.equals(loc) && locDone);
                                        boolean airOk = up && airOn && stays;
                                        eq(hot && locOk && airOk && scr, r.done, "done: " + w);
                                        // every step's own sentence is in the answer, in order, and the screen's is last
                                        yes(r.text.startsWith(hot ? "The hotspot is off." : "The hotspot could not be switched off."), "the hotspot first: " + w);
                                        yes(r.text.endsWith(scr ? "The screen is off." : "The screen did not stay off."), "the screen last: " + w);
                                        yes(!r.text.contains("  ") && !r.text.endsWith(" "), "tidy text: " + w);
                                        if (!stranded && !(stays && airOn && up)) {
                                            // not kept on: the stranding sentence must not appear
                                            yes(!r.text.contains("picked up"), "no stranding words when it is not stranded: " + w);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        yes(worlds == 2 * 3 * 2 * 2 * 2 * 2 * 2 * 2, "all the worlds were visited: " + worlds);
    }

    private static void wakeStories() {
        // from airplane mode: off first, the radio is waited for, then the hotspot; location and the screen are never touched
        Sim s = new Sim();
        s.airplane = true;
        SwitchWords.Said r = DeepSleepRules.wake(s);
        eq("[airplaneIsOn, airplaneOff, waitForRadio, hotspotOn]", s.log, "wake from airplane mode: the order");
        eq("Airplane mode is off. The hotspot is on.", r.text, "wake from airplane mode: the words");
        yes(r.done && !s.airplane, "wake from airplane mode: done, airplane mode is off");

        // not in airplane mode: only the hotspot
        s = new Sim();
        r = DeepSleepRules.wake(s);
        eq("[airplaneIsOn, hotspotOn]", s.log, "wake with no airplane mode: only the hotspot");
        eq("The hotspot is on.", r.text, "wake with no airplane mode: the words");

        // airplane mode will not go off: no hotspot, and it says why
        s = new Sim();
        s.airplane = true;
        s.airplaneOffDone = false;
        r = DeepSleepRules.wake(s);
        yes(!has(s, "hotspotOn") && !has(s, "waitForRadio"), "airplane mode stays on: the hotspot is not started");
        yes(!r.done && r.text.contains("Airplane mode could not be switched off.") && r.text.contains("The hotspot was not started"),
                "airplane mode stays on: says so: " + r.text);

        // the hotspot would not come on after airplane mode went off: its own words
        s = new Sim();
        s.airplane = true;
        s.hotspotOnDone = false;
        r = DeepSleepRules.wake(s);
        yes(!r.done && r.text.equals("Airplane mode is off. The hotspot could not be switched on."), "hotspot refused after airplane mode: " + r.text);
    }

    private static void everyWake() {
        int worlds = 0;
        for (boolean air : new boolean[] {true, false}) {
            for (boolean off : new boolean[] {true, false}) {
                for (boolean hot : new boolean[] {true, false}) {
                    worlds++;
                    Sim s = new Sim();
                    s.airplane = air;
                    s.airplaneOffDone = off;
                    s.hotspotOnDone = hot;
                    // location and the screen are not the wake's business: any call to them would be seen in the log
                    SwitchWords.Said r = DeepSleepRules.wake(s);
                    String w = "air=" + air + " off=" + off + " hot=" + hot + " -> " + s.log + " | " + r.text;
                    yes(!has(s, "locationOn") && !has(s, "locationOff") && !has(s, "screenOff") && !has(s, "airplaneOn") && !has(s, "linkIsUp"),
                            "waking touches only airplane mode and the hotspot: " + w);
                    if (air && !off) {
                        yes(!has(s, "hotspotOn"), "never the hotspot while airplane mode cannot be ended: " + w);
                        yes(!r.done, "not done: " + w);
                    } else {
                        yes(has(s, "hotspotOn"), "the hotspot is tried: " + w);
                        eq(hot, r.done, "done is the hotspot's: " + w);
                    }
                    eq(air && off, has(s, "waitForRadio"), "the radio is waited for only after airplane mode really went off: " + w);
                    eq(air && off, r.text.startsWith("Airplane mode is off. "), "the lead only when airplane mode was ended: " + w);
                    yes(!(air && off && s.airplane), "airplane mode is off afterwards: " + w);
                }
            }
        }
        yes(worlds == 8, "all the worlds were visited");
    }
}
