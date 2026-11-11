package com.tj.linkmeter;

import java.util.Random;

/**
 * Offline test of FollowUp: what the tablet says about a switch the phone only answered with "Sent". The rule that must never
 * break: "is on" / "is off" is said only for what a status of the phone itself reported; a phone that stops answering is said
 * to have stopped answering (which is what power off, a restart or a hotspot going off looks like), never "done"; and when
 * the time is over without either, the answer is "not confirmed yet" with what the last status said.
 * Run by tools/test.sh. Prints "N checks, M failed". ASCII only.
 */
public final class FollowUpTest {
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

    static final String[] FOLLOWED = {"hotspot_on", "boot_hotspot_on", "hotspot_off", "hotspot_restart", "standby", "wifi_on", "wifi_off",
        "airplane_on", "poweroff", "reboot"};

    /** A status: hotspot, screen, Wi-Fi, airplane. 1 = on, 0 = off, anything else = the status does not say. */
    static FollowUp.Seen seen(int hs, int scr, int wifi, int air) {
        return new FollowUp.Seen(b(hs), b(scr), b(wifi), b(air));
    }

    static Boolean b(int v) {
        return v == 1 ? Boolean.TRUE : v == 0 ? Boolean.FALSE : null;
    }

    static final FollowUp.Seen ON = seen(1, 1, 0, 0);
    static final FollowUp.Seen OFF = seen(0, 1, 0, 0);
    static final FollowUp.Seen UNKNOWN = seen(-1, 1, 0, 0);
    static final FollowUp.Seen SILENT = null;

    static void wellFormed(FollowUp.Outcome o) {
        String t = o.text;
        check(t.endsWith(".") && Character.isUpperCase(t.charAt(0)) && !t.contains("!") && !t.contains("  ") && !t.contains("..") && !t.contains(" - "),
                "whole plain sentences: [" + t + "]");
    }

    /** Runs the looks; returns the outcome of the first look that gave one (and checks none came earlier than 'at'). */
    static FollowUp.Outcome run(String action, int at, FollowUp.Seen... looks) {
        FollowUp f = FollowUp.of(action);
        for (int i = 0; i < looks.length; i++) {
            FollowUp.Outcome o = f.look(looks[i]);
            if (o != null) {
                check(i == at, action + ": the outcome comes at look " + at + ", not " + i + " (" + o.text + ")");
                wellFormed(o);
                return o;
            }
        }
        check(at < 0, action + ": an outcome was expected at look " + at);
        FollowUp.Outcome g = f.giveUp();
        wellFormed(g);
        return g;
    }

    static void which() {
        for (String a : FOLLOWED) {
            check(FollowUp.of(a) != null, a + " is followed up");
        }
        for (String a : new String[] {"radio_cycle", "restart_modem", "net_lte", "net_5g", "setup", "restore", "bt_setup", "screen_off", "eco_auto_on", "", "hotspot"}) {
            check(FollowUp.of(a) == null, a + " has nothing the status could show: 'Sent' stays the last word");
        }
        check(FollowUp.EVERY_MS == 3000 && FollowUp.GIVE_UP_MS == 40000 && FollowUp.GONE_AFTER == 2, "every 3 s, for about 40 s, two silent looks = gone");
        check(FollowUp.of("hotspot_off_deep") != null, "the deep Hotspot off is followed up");
        check(FollowUp.GIVE_UP_DEEP_MS == 60000 && FollowUp.GONE_AFTER_DEEP == 6 && FollowUp.GIVE_UP_FROM_AIRPLANE_MS == 90000,
                "deep: 60 s and six silent looks; from airplane mode: 90 s");
    }

    /** How long each follow-up looks: 40 s, except the deep Hotspot off (60 s) and Hotspot on from airplane mode (90 s). */
    static void patience() {
        for (String a : FOLLOWED) {
            check(FollowUp.of(a).giveUpMs() == 40000, a + " looks for 40 s");
            check(FollowUp.of(a, false).giveUpMs() == 40000, a + " looks for 40 s (not from airplane mode)");
            boolean on = a.equals("hotspot_on") || a.equals("boot_hotspot_on");
            check(FollowUp.of(a, true).giveUpMs() == (on ? 90000 : 40000), a + " from airplane mode: " + FollowUp.of(a, true).giveUpMs());
        }
        check(FollowUp.of("hotspot_off_deep").giveUpMs() == 60000 && FollowUp.of("hotspot_off_deep", true).giveUpMs() == 60000, "the deep Hotspot off looks for 60 s");
        check(FollowUp.of("nonsense", true) == null && FollowUp.of("radio_cycle", true) == null, "nothing to follow stays nothing from airplane mode");
    }

    static void stories() {
        FollowUp.Outcome o;
        // hotspot on: said only when the status says so
        for (String a : new String[] {"hotspot_on", "boot_hotspot_on"}) {
            o = run(a, 2, OFF, OFF, ON);
            check(o.ok && o.text.equals("The hotspot is on."), a + " confirmed: " + o.text);
            o = run(a, -1, OFF, OFF, OFF);
            check(!o.ok && o.text.equals("The phone has not confirmed it yet: its status still says the hotspot is off."), a + " not confirmed: " + o.text);
            o = run(a, -1, UNKNOWN, UNKNOWN);
            check(!o.ok && o.text.equals("The phone has not confirmed it yet: it does not say whether its hotspot is on."), a + " phone cannot tell: " + o.text);
            o = run(a, -1, SILENT, SILENT, SILENT, SILENT);
            check(!o.ok && o.text.equals("The phone has not confirmed it yet: it does not answer right now."), a + " silence is not 'on': " + o.text);
        }
        // hotspot off: the status says so (over Bluetooth), or the phone is simply gone (no Bluetooth link)
        o = run("hotspot_off", 2, ON, SILENT, OFF);
        check(o.ok && o.text.equals("The hotspot is off."), "hotspot_off confirmed: " + o.text);
        o = run("hotspot_off", 2, ON, SILENT, SILENT);
        check(o.ok && o.text.equals("The phone no longer answers, which is what its hotspot going off looks like from here. The tablet cannot ask it whether it did."),
                "hotspot_off, phone gone: " + o.text);
        o = run("hotspot_off", -1, SILENT, ON, SILENT, ON);
        check(!o.ok && o.text.equals("The phone has not confirmed it yet: its status still says the hotspot is on."), "one lost answer is not 'gone': " + o.text);
        o = run("hotspot_off", -1, ON, ON, ON, SILENT);
        check(o.ok && o.text.startsWith("The phone no longer answers"), "time over right after the phone stopped answering: said as that: " + o.text);
        o = run("hotspot_off", -1, UNKNOWN, UNKNOWN);
        check(!o.ok && o.text.contains("does not say whether its hotspot is on"), "hotspot_off, phone cannot tell: " + o.text);
        // standby: both, or nothing
        o = run("standby", 2, seen(1, 1, 0, 0), seen(0, 1, 0, 0), seen(0, 0, 0, 0));
        check(o.ok && o.text.equals("The hotspot and the screen are off."), "standby confirmed: " + o.text);
        o = run("standby", -1, seen(0, 1, 0, 0), seen(0, 1, 0, 0));
        check(!o.ok && o.text.equals("The phone has not confirmed it yet: its status says the hotspot is off and the screen is on."), "half a standby: " + o.text);
        o = run("standby", -1, seen(-1, 0, 0, 0));
        check(!o.ok && o.text.contains("the hotspot is not known and the screen is off"), "standby, hotspot not known: " + o.text);
        o = run("standby", 1, SILENT, SILENT);
        check(o.ok && o.text.startsWith("The phone no longer answers, which is what its hotspot going off looks like"), "standby, phone gone: " + o.text);
        // restart: it must have been away in between
        o = run("hotspot_restart", -1, ON, ON, ON);
        check(!o.ok && o.text.equals("The phone has not confirmed it yet: its hotspot is on, but the tablet did not see it go off in between."), "restart never seen off: " + o.text);
        o = run("hotspot_restart", 2, ON, OFF, ON);
        check(o.ok && o.text.equals("The hotspot is back on."), "restart seen off, then on: " + o.text);
        o = run("hotspot_restart", 3, ON, SILENT, SILENT, ON);
        check(o.ok && o.text.equals("The hotspot is back on."), "restart: the tablet lost it, then it is on: " + o.text);
        o = run("hotspot_restart", -1, ON, SILENT, SILENT, SILENT);
        check(!o.ok && o.text.equals("The phone has not confirmed it yet: it does not answer right now."), "restart: silence is not 'back on': " + o.text);
        o = run("hotspot_restart", -1, OFF, OFF);
        check(!o.ok && o.text.equals("The phone has not confirmed it yet: its status says the hotspot is off."), "restart: stayed off: " + o.text);
        // the phone's Wi-Fi
        o = run("wifi_on", 1, seen(1, 1, 0, 0), seen(1, 1, 1, 0));
        check(o.ok && o.text.equals("The phone's Wi-Fi is on."), "wifi_on: " + o.text);
        o = run("wifi_off", 0, seen(1, 1, 0, 0));
        check(o.ok && o.text.equals("The phone's Wi-Fi is off."), "wifi_off: " + o.text);
        o = run("wifi_on", -1, seen(1, 1, 0, 0), SILENT, SILENT);
        check(!o.ok && o.text.contains("does not answer right now"), "wifi_on: silence is not 'on': " + o.text);
        o = run("wifi_off", -1, seen(1, 1, 1, 0));
        check(!o.ok && o.text.equals("The phone has not confirmed it yet: its status says its Wi-Fi is on."), "wifi_off not done: " + o.text);
        // the actions whose success is that the phone is gone
        o = run("poweroff", 3, ON, ON, SILENT, SILENT);
        check(o.ok && o.text.equals("The phone no longer answers, which is what powering off looks like from here. It has to be switched on by hand."), "poweroff: " + o.text);
        o = run("poweroff", -1, ON, ON, ON);
        check(!o.ok && o.text.equals("The phone has not confirmed it yet: it still answers."), "poweroff, still there: " + o.text);
        o = run("reboot", 1, SILENT, SILENT);
        check(o.ok && o.text.startsWith("The phone no longer answers, which is what a restart looks like from here.") && o.text.contains("unlocked")
                && o.text.contains("Shizuku"), "reboot: " + o.text);
        o = run("airplane_on", 1, SILENT, SILENT);
        check(o.ok && o.text.startsWith("The phone no longer answers, which is what airplane mode looks like from here.") && o.text.contains("15 minutes"),
                "airplane_on, gone: " + o.text);
        o = run("airplane_on", 0, seen(0, 1, 0, 1));
        check(o.ok && o.text.equals("Airplane mode is on."), "airplane_on, still reachable and it says so: " + o.text);
        o = run("airplane_on", -1, seen(1, 1, 0, 0));
        check(!o.ok && o.text.contains("it still answers") && o.text.contains("airplane mode is off"), "airplane_on, not done: " + o.text);
    }

    /** Does this status show what the action promised? An oracle written from the list of promises, not from the code's order. */
    static boolean shows(String action, FollowUp.Seen s) {
        if (s == null) {
            return false;
        }
        switch (action) {
            case "hotspot_on":
            case "boot_hotspot_on":
            case "hotspot_restart":
                return Boolean.TRUE.equals(s.hotspot);
            case "hotspot_off":
                return Boolean.FALSE.equals(s.hotspot);
            case "standby":
                return Boolean.FALSE.equals(s.hotspot) && Boolean.FALSE.equals(s.screen);
            case "wifi_on":
                return Boolean.TRUE.equals(s.wifi);
            case "wifi_off":
                return Boolean.FALSE.equals(s.wifi);
            case "airplane_on":
                return Boolean.TRUE.equals(s.airplane);
            default:
                return false;
        }
    }

    static boolean silenceFits(String action) {
        return action.equals("hotspot_off") || action.equals("standby") || action.equals("wifi_off") || action.equals("airplane_on")
                || action.equals("poweroff") || action.equals("reboot");
    }

    static void random() {
        Random rnd = new Random(2026);
        for (int story = 0; story < 40000; story++) {
            String action = FOLLOWED[rnd.nextInt(FOLLOWED.length)];
            FollowUp f = FollowUp.of(action);
            int silent = 0;
            boolean away = false;
            FollowUp.Seen last = null;
            FollowUp.Outcome o = null;
            int looks = 1 + rnd.nextInt(13);
            for (int i = 0; i < looks && o == null; i++) {
                FollowUp.Seen s = rnd.nextInt(3) == 0 ? null : seen(rnd.nextInt(3) - 1, rnd.nextInt(3) - 1, rnd.nextInt(3) - 1, rnd.nextInt(3) - 1);
                silent = s == null ? silent + 1 : 0;
                away |= s == null || Boolean.FALSE.equals(s.hotspot);
                last = s;
                o = f.look(s);
                if (o != null) {
                    wellFormed(o);
                    boolean gone = o.text.startsWith("The phone no longer answers");
                    check(o.ok, action + ": a look only ever settles it in the phone's favour (not confirmed is said at the end): " + o.text);
                    if (gone) {
                        check(s == null && silent >= FollowUp.GONE_AFTER && silenceFits(action), action + ": 'no longer answers' only after two silent looks, and only where silence fits");
                        check(!o.text.contains(" is off.") && !o.text.contains(" is on.") && !o.text.contains("are off."), "silence claims no state: " + o.text);
                    } else {
                        check(shows(action, s), action + ": a claim only for a status that shows it: " + o.text);
                        check(!action.equals("hotspot_restart") || away, "a restart is only 'back on' after it was away");
                    }
                } else {
                    check(!shows(action, s) || (action.equals("hotspot_restart") && !away), action + ": a status that shows it settles it at once");
                    check(!(silent >= FollowUp.GONE_AFTER && silenceFits(action)), action + ": two silent looks settle an action that takes the phone away");
                }
            }
            if (o == null) {
                FollowUp.Outcome g = f.giveUp();
                wellFormed(g);
                if (g.ok) {
                    check(last == null && silenceFits(action) && g.text.startsWith("The phone no longer answers"), action + ": giving up is only 'ok' when the phone has stopped answering and that fits: " + g.text);
                } else {
                    check(g.text.startsWith("The phone has not confirmed it yet: "), action + ": not confirmed is said as that: " + g.text);
                    check(!g.text.equals("The hotspot is on.") && !g.text.equals("The hotspot is off."), "no claim at give-up");
                    check((last == null) == g.text.contains("does not answer right now"), action + ": 'does not answer' exactly when the last look was silent: " + g.text);
                }
            }
        }
    }

    /** A status for the deep Hotspot off: hotspot, screen, airplane, location (1 on, 0 off, other = not said) and the phone's note. */
    static FollowUp.Seen deepSeen(int hs, int scr, int air, int loc, String note) {
        return new FollowUp.Seen(b(hs), b(scr), Boolean.FALSE, b(air), b(loc), note);
    }

    static FollowUp.Outcome runDeep(int at, FollowUp.Seen... looks) {
        return run("hotspot_off_deep", at, looks);
    }

    static void deep() {
        FollowUp.Outcome o;
        // the usual night: the statuses come in the order the phone does its steps
        o = runDeep(4, deepSeen(1, 1, 0, 1, ""), deepSeen(0, 1, 0, 1, ""), deepSeen(0, 1, 0, 0, ""), deepSeen(0, 1, 1, 0, ""), deepSeen(0, 0, 1, 0, ""));
        check(o.ok && o.text.equals("The hotspot and the screen are off and airplane mode is on. Location is off."), "deep, the usual night: " + o.text);
        // location still on: told, and the rest still counts
        o = runDeep(0, deepSeen(0, 0, 1, 1, ""));
        check(o.ok && o.text.equals("The hotspot and the screen are off and airplane mode is on. Location is still on."), "deep, location still on: " + o.text);
        // location not said (an older status): nothing is said about it
        o = runDeep(0, deepSeen(0, 0, 1, -1, ""));
        check(o.ok && o.text.equals("The hotspot and the screen are off and airplane mode is on."), "deep, location not said: " + o.text);
        // part of it is not the result: all three have to show
        for (int hs = 0; hs <= 1; hs++) {
            for (int scr = 0; scr <= 1; scr++) {
                for (int air = 0; air <= 1; air++) {
                    FollowUp.Outcome r = runDeepMaybe(deepSeen(hs, scr, air, 0, ""));
                    boolean all = hs == 0 && scr == 0 && air == 1;
                    check((r != null) == all, "deep: hotspot " + hs + ", screen " + scr + ", airplane " + air + (all ? " settles it" : " does not settle it"));
                }
            }
        }
        // a status that does not say a field does not show it
        check(runDeepMaybe(deepSeen(-1, 0, 1, 0, "")) == null && runDeepMaybe(deepSeen(0, -1, 1, 0, "")) == null && runDeepMaybe(deepSeen(0, 0, -1, 0, "")) == null,
                "deep: a field the phone does not say is not 'done'");
        // Bluetooth is away for a few looks while the phone puts it back: five silent looks are not 'gone', the sixth is
        o = runDeep(-1, deepSeen(1, 1, 0, 1, ""), null, null, null, null, null);
        check(o != null, "deep: six silent looks end it");
        FollowUp f = FollowUp.of("hotspot_off_deep");
        boolean early = false;
        for (int i = 0; i < 5; i++) {
            early |= f.look(null) != null;
        }
        check(!early, "deep: five silent looks in a row are not 'gone' yet");
        o = f.look(null);
        check(o != null && o.ok && o.text.equals("The phone no longer answers, which is what its hotspot going off and the phone going to sleep looks like from here. "
                + "The tablet cannot ask it what it did. Press Hotspot on to wake it; if that does not answer either, the phone has to be picked up."),
                "deep, the sixth silent look: " + (o == null ? "nothing" : o.text));
        wellFormed(o);
        // ... and an answer in between starts the count again, and settles it when it shows the result
        f = FollowUp.of("hotspot_off_deep");
        for (int i = 0; i < 5; i++) {
            f.look(null);
        }
        check(f.look(deepSeen(0, 1, 0, 0, "")) == null, "deep: an answer that does not show the result settles nothing");
        for (int i = 0; i < 5; i++) {
            check(f.look(null) == null, "deep: the silent count started again");
        }
        o = f.look(deepSeen(0, 0, 1, 0, ""));
        check(o != null && o.ok && o.text.startsWith("The hotspot and the screen are off and airplane mode is on."), "deep: a late answer settles it");
        // time over: what the last status said, and what the phone says it did
        o = runDeep(-1, deepSeen(0, 0, 0, 0, "Airplane mode was switched off again: Bluetooth could not stay on, and then the tablet could not wake the phone."));
        check(!o.ok && o.text.equals("The phone has not confirmed it yet: its status says the hotspot is off, airplane mode is off and the screen is off, location is off. "
                + "The phone says: Airplane mode was switched off again: Bluetooth could not stay on, and then the tablet could not wake the phone."),
                "deep, time over, airplane mode undone by the phone: " + o.text);
        o = runDeep(-1, deepSeen(1, 1, 0, -1, ""));
        check(!o.ok && o.text.equals("The phone has not confirmed it yet: its status says the hotspot is on, airplane mode is off and the screen is on."),
                "deep, time over, nothing happened, no note: " + o.text);
        o = runDeep(-1, deepSeen(0, 1, -1, 1, "  The screen did not stay off.  "));
        check(!o.ok && o.text.equals("The phone has not confirmed it yet: its status says the hotspot is off, airplane mode is not known and the screen is on, location is on. "
                + "The phone says: The screen did not stay off."), "deep, time over, note tidied: " + o.text);
        String gone = "The phone no longer answers, which is what its hotspot going off and the phone going to sleep looks like from here. "
                + "The tablet cannot ask it what it did. Press Hotspot on to wake it; if that does not answer either, the phone has to be picked up.";
        o = runDeep(-1, null, null);
        check(o.ok && o.text.equals(gone), "deep, silent when the time is over (fewer than six looks): said as that: " + o.text);
        o = runDeep(-1, deepSeen(1, 1, 0, 1, ""), null, null);
        check(o.ok && o.text.equals(gone), "deep, silent at the end of the time: " + o.text);
        o = FollowUp.of("hotspot_off_deep").giveUp();
        check(!o.ok && o.text.equals("The phone has not confirmed it yet: it does not answer right now."), "deep, no look at all: " + o.text);
    }

    /** The outcome of one look at a new follow-up, or null when it keeps looking. */
    static FollowUp.Outcome runDeepMaybe(FollowUp.Seen s) {
        return FollowUp.of("hotspot_off_deep").look(s);
    }

    /** Hotspot on from airplane mode is the same as Hotspot on; only the patience differs (the status is what counts). */
    static void fromAirplane() {
        for (String a : new String[] {"hotspot_on", "boot_hotspot_on"}) {
            FollowUp f = FollowUp.of(a, true);
            check(f.look(OFF) == null && f.look(null) == null, a + " from airplane mode: keeps looking");
            FollowUp.Outcome o = f.look(ON);
            check(o != null && o.ok && o.text.equals("The hotspot is on."), a + " from airplane mode: confirmed by the status");
            f = FollowUp.of(a, true);
            for (int i = 0; i < 20; i++) {
                check(f.look(null) == null, a + " from airplane mode: silence is only silence");
            }
            FollowUp.Outcome g = f.giveUp();
            check(!g.ok && g.text.equals("The phone has not confirmed it yet: it does not answer right now."), a + " from airplane mode: not confirmed: " + g.text);
        }
    }

    /** Random stories for the deep Hotspot off, against an oracle written from the list of promises. */
    static void randomDeep() {
        Random rnd = new Random(77);
        String[] notes = {"", "Location could not be switched off.", "Airplane mode was switched off again: Bluetooth could not stay on."};
        for (int story = 0; story < 30000; story++) {
            FollowUp f = FollowUp.of("hotspot_off_deep");
            int silent = 0;
            FollowUp.Seen last = null;
            FollowUp.Outcome o = null;
            int looks = 1 + rnd.nextInt(20);
            for (int i = 0; i < looks && o == null; i++) {
                FollowUp.Seen s = rnd.nextInt(3) == 0 ? null
                        : deepSeen(rnd.nextInt(3) - 1, rnd.nextInt(3) - 1, rnd.nextInt(3) - 1, rnd.nextInt(3) - 1, notes[rnd.nextInt(notes.length)]);
                silent = s == null ? silent + 1 : 0;
                last = s;
                o = f.look(s);
                boolean shows = s != null && Boolean.FALSE.equals(s.hotspot) && Boolean.TRUE.equals(s.airplane) && Boolean.FALSE.equals(s.screen);
                if (o != null) {
                    wellFormed(o);
                    check(o.ok, "deep: a look only ever settles it in the phone's favour: " + o.text);
                    if (o.text.startsWith("The phone no longer answers")) {
                        check(s == null && silent >= 6, "deep: 'no longer answers' only after six silent looks");
                        check(!o.text.contains(" is off.") && !o.text.contains(" is on.") && !o.text.contains("are off."), "deep: silence claims no state: " + o.text);
                    } else {
                        check(shows, "deep: a claim only for a status that shows all three: " + o.text);
                        check(s.location == null ? !o.text.contains("Location") : o.text.endsWith(s.location.booleanValue() ? "Location is still on." : "Location is off."),
                                "deep: location told as the status shows it: " + o.text);
                        check(o.text.startsWith("The hotspot and the screen are off and airplane mode is on."), "deep: the claim's words: " + o.text);
                    }
                } else {
                    check(!shows, "deep: a status that shows it settles it at once");
                    check(silent < 6, "deep: six silent looks settle it");
                }
            }
            if (o == null) {
                FollowUp.Outcome g = f.giveUp();
                wellFormed(g);
                if (g.ok) {
                    check(last == null && silent > 0 && g.text.startsWith("The phone no longer answers"), "deep: giving up is only 'ok' when the phone stopped answering: " + g.text);
                } else {
                    check(g.text.startsWith("The phone has not confirmed it yet: "), "deep: not confirmed is said as that: " + g.text);
                    check((last == null) == g.text.contains("does not answer right now"), "deep: 'does not answer' exactly when the last look was silent: " + g.text);
                    if (last != null) {
                        check(g.text.contains("its status says the hotspot is " + (last.hotspot == null ? "not known" : last.hotspot ? "on" : "off")), "deep: the hotspot as the last status said: " + g.text);
                        check(last.note.isEmpty() ? !g.text.contains("The phone says") : g.text.contains("The phone says: "), "deep: the phone's note only when there is one: " + g.text);
                    }
                }
            }
        }
    }

    public static void main(String[] args) {
        which();
        patience();
        stories();
        random();
        deep();
        fromAirplane();
        randomDeep();
        System.out.println(checks + " checks, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
