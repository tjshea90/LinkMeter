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

    public static void main(String[] args) {
        which();
        stories();
        random();
        System.out.println(checks + " checks, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
