package com.tj.linkmeter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Tests SaverPlan, the decisions behind the battery saver. Not part of the app.
 *
 * Three layers: every rule on its own; the saver's real shell lines run through `sh` against a fake `settings` program (so the
 * words the phone prints, an error text instead of a number, a write the phone ignores, are all really met); and stories in a
 * small world whose steps follow Eco's, in the same order, with ProfilePlan (the Screen tab's session) in it as well. The last
 * layer is random: whatever happens, switching the saver off at the end gives the phone back exactly as it was.
 */
public final class SaverPlanTest {
    private static int checks;
    private static int fails;

    private SaverPlanTest() {}

    private static void eq(Object want, Object got, String what) {
        checks++;
        String w = String.valueOf(want);
        String g = String.valueOf(got);
        if (!w.equals(g)) {
            fails++;
            System.out.println("FAIL " + what + ": wanted " + w + " but got " + g);
        }
    }

    private static void yes(boolean ok, String what) {
        checks++;
        if (!ok) {
            fails++;
            System.out.println("FAIL " + what);
        }
    }

    private static String[] s(String... v) {
        return v;
    }

    private static List<String> l(String... v) {
        return new ArrayList<>(Arrays.asList(v));
    }

    private static final long MIN = 60 * 1000L;
    private static final long T0 = 1_000_000L; // an arbitrary elapsed-realtime clock

    // ------------------------------------------------------------------------------------------------ the rules, one by one

    private static void profile() {
        eq(5, SaverPlan.PROFILE.length, "five settings");
        eq("system screen_off_timeout 15000", SaverPlan.PROFILE[SaverPlan.TIMEOUT][0] + " " + SaverPlan.PROFILE[SaverPlan.TIMEOUT][1] + " "
                + SaverPlan.PROFILE[SaverPlan.TIMEOUT][2], "the timeout is the short one");
        eq("15000", SaverPlan.timeoutMs(), "timeoutMs");
        eq("system aod_mode", SaverPlan.PROFILE[SaverPlan.AOD][0] + " " + SaverPlan.PROFILE[SaverPlan.AOD][1], "the Samsung display switch is in");
        for (String[] p : SaverPlan.PROFILE) {
            yes(!p[1].equals("tether_offload_disabled"), "the hotspot offload setting is no longer written");
            yes(!p[1].contains("bluetooth") && !p[1].contains("data_saver") && !p[1].contains("low_power"),
                    "never Bluetooth, never Data Saver, never the power saving mode: " + p[1]);
            yes(SaverPlan.valueOk(p[2]), "every target is a number");
        }
    }

    private static void reconcile() {
        long now = T0;
        long[] fails = {0, now - 10 * 1000L, now - 59 * 1000L, now - 60 * 1000L, now - 61 * 1000L, now - 10 * MIN};
        for (int mask = 0; mask < 16; mask++) {
            boolean auto = (mask & 1) != 0;
            boolean on = (mask & 2) != 0;
            boolean here = (mask & 4) != 0;
            boolean ready = (mask & 8) != 0;
            for (long f : fails) {
                boolean recent = f > 0 && now - f < 60 * 1000L;
                SaverPlan.Act want;
                if (!ready || recent) {
                    want = SaverPlan.Act.NOTHING;
                } else if (auto && !on && here) {
                    want = SaverPlan.Act.APPLY;
                } else if (!auto && on) {
                    want = SaverPlan.Act.RESTORE;
                } else {
                    want = SaverPlan.Act.NOTHING;
                }
                eq(want, SaverPlan.reconcile(auto, on, here, ready, now, f),
                        "reconcile auto=" + auto + " on=" + on + " here=" + here + " ready=" + ready + " failed " + (now - f) / 1000 + " s ago");
            }
        }
        // the cases the brief names
        eq(SaverPlan.Act.RESTORE, SaverPlan.reconcile(false, true, false, true, now, 0), "Off and still on, tablet not here (a new process): put back");
        eq(SaverPlan.Act.NOTHING, SaverPlan.reconcile(false, true, true, false, now, 0), "Off and on but Shizuku is down: wait");
        eq(SaverPlan.Act.APPLY, SaverPlan.reconcile(true, false, true, true, now, 0), "Automatic, off, tablet here: switch on");
        eq(SaverPlan.Act.NOTHING, SaverPlan.reconcile(true, false, false, true, now, 0), "Automatic, off, tablet not here (Bluetooth): wait");
        eq(SaverPlan.Act.NOTHING, SaverPlan.reconcile(false, false, true, true, now, 0), "Off and off: a request switches nothing on");
        yes(SaverPlan.waiting(now, now - 1), "a failure one ms ago is waited for");
        yes(!SaverPlan.waiting(now, 0), "no failure, no waiting");
    }

    private static SaverPlan.Idle idle(boolean auto, boolean on, long since, Boolean hotspot, boolean screen, Boolean answers, boolean ready) {
        return SaverPlan.idle(auto, on, T0, T0 - since, hotspot, screen, answers, ready);
    }

    private static void idleRules() {
        Boolean[] hs = {Boolean.TRUE, Boolean.FALSE, null};
        Boolean[] an = {null, Boolean.TRUE, Boolean.FALSE};
        long[] since = {0, 5 * MIN, 10 * MIN - 1, 10 * MIN, 10 * MIN + 1, 60 * MIN, 24 * 60 * MIN};
        for (int mask = 0; mask < 16; mask++) {
            boolean auto = (mask & 1) != 0;
            boolean on = (mask & 2) != 0;
            boolean screen = (mask & 4) != 0;
            boolean ready = (mask & 8) != 0;
            for (Boolean h : hs) {
                for (Boolean a : an) {
                    for (long sn : since) {
                        SaverPlan.Verdict wantV;
                        long wantMs = 0;
                        if (!on) {
                            wantV = SaverPlan.Verdict.NOTHING_TO_WATCH;
                        } else if (!auto) {
                            wantV = ready ? SaverPlan.Verdict.RESTORE : SaverPlan.Verdict.LOOK_AGAIN;
                            wantMs = ready ? 0 : 10 * MIN;
                        } else if (sn < 10 * MIN) {
                            wantV = SaverPlan.Verdict.LOOK_AGAIN;
                            wantMs = 10 * MIN - sn;
                        } else if (Boolean.TRUE.equals(h) && a == null) {
                            wantV = SaverPlan.Verdict.ASK_TABLET;
                        } else {
                            boolean inUse = Boolean.TRUE.equals(h) ? a.booleanValue() : !screen;
                            wantV = (inUse || !ready) ? SaverPlan.Verdict.LOOK_AGAIN : SaverPlan.Verdict.RESTORE;
                            wantMs = wantV == SaverPlan.Verdict.LOOK_AGAIN ? 10 * MIN : 0;
                        }
                        SaverPlan.Idle got = idle(auto, on, sn, h, screen, a, ready);
                        String what = "idle auto=" + auto + " on=" + on + " since=" + sn / MIN + "min hotspot=" + h + " screen=" + screen
                                + " answers=" + a + " ready=" + ready;
                        eq(wantV, got.verdict, what);
                        eq(wantMs, got.waitMs, what + " wait");
                    }
                }
            }
        }
        // the cases the brief names
        eq(SaverPlan.Verdict.LOOK_AGAIN, idle(true, true, 3 * 60 * MIN, Boolean.TRUE, false, Boolean.TRUE, true).verdict,
                "the tablet in another app for an hour with the hotspot on and answering: keep");
        eq(SaverPlan.Verdict.RESTORE, idle(true, true, 60 * MIN, Boolean.TRUE, false, Boolean.FALSE, true).verdict,
                "the hotspot is on but the tablet does not answer: gone, put back");
        eq(SaverPlan.Verdict.LOOK_AGAIN, idle(true, true, 24 * 60 * MIN, Boolean.FALSE, false, null, true).verdict,
                "Standby (hotspot off, screen off): keep, nobody sees the short timeout");
        eq(SaverPlan.Verdict.RESTORE, idle(true, true, 24 * 60 * MIN, Boolean.FALSE, true, null, true).verdict,
                "Standby for a day, then the screen comes on: the phone's own settings at once");
        eq(SaverPlan.Verdict.RESTORE, idle(true, true, 24 * 60 * MIN, null, true, null, true).verdict,
                "a hotspot nobody can tell, screen on: put back (fails safe)");
        eq(SaverPlan.Verdict.LOOK_AGAIN, idle(true, true, 2 * MIN, Boolean.FALSE, true, null, true).verdict,
                "the screen comes on two minutes after the tablet was heard (the Wake button): the tablet is here, keep");
        eq(10 * MIN - 2 * MIN, idle(true, true, 2 * MIN, Boolean.FALSE, true, null, true).waitMs, "...and look again when the ten minutes are over");
        eq(SaverPlan.Verdict.ASK_TABLET, idle(true, true, 20 * MIN, Boolean.TRUE, true, null, true).verdict, "ask only when the hotspot is on");
        yes(idle(true, true, 20 * MIN, Boolean.FALSE, true, null, true).verdict != SaverPlan.Verdict.ASK_TABLET, "no question when the hotspot is off");
        yes(idle(true, true, 20 * MIN, null, true, null, true).verdict != SaverPlan.Verdict.ASK_TABLET, "no question when the hotspot is not known");
    }

    private static void reading() {
        String cmd = SaverPlan.readCommand();
        yes(cmd.endsWith("true"), "the read line ends with true");
        for (int i = 0; i < SaverPlan.PROFILE.length; i++) {
            yes(cmd.contains("echo K" + i + "=$(settings get " + SaverPlan.PROFILE[i][0] + " " + SaverPlan.PROFILE[i][1] + ")"), "reads key " + i);
        }
        eq("[0, 1, null, 30000, null]", Arrays.toString(SaverPlan.parseRead("K0=0\nK1=1\nK3=30000\n")), "missing lines stay null");
        eq("[null, null, null, null, null]", Arrays.toString(SaverPlan.parseRead("")), "nothing came back");
        eq("[null, null, null, null, null]", Arrays.toString(SaverPlan.parseRead(null)), "null output");
        eq("[, null, null, null, null]", Arrays.toString(SaverPlan.parseRead("K0=")), "an empty value is kept empty (and is not believed later)");
        eq("[null, null, null, null, null]", Arrays.toString(SaverPlan.parseRead("K9=1\nK-1=1\nKx=1\nX0=1\nK=1\n=1")), "lines that are not ours");
        eq("7", SaverPlan.parseRead("  K1=7  ")[1], "spaces around a line are trimmed");
        eq(null, SaverPlan.parseRead("K1 = 7")[1], "a line that is not exactly K<n>=<value> is not ours");
        eq("Exception occurred while executing 'get':", SaverPlan.parseRead("K2=Exception occurred while executing 'get':\njava.lang.SecurityException")[2],
                "an error text is read as it is, and never believed (see plan)");
        eq("1", SaverPlan.parseRead("K0=0\nK0=1")[0], "a later line wins");

        yes(SaverPlan.valueOk("0") && SaverPlan.valueOk("15000") && SaverPlan.valueOk("2147483647") && SaverPlan.valueOk("null"), "numbers and null");
        yes(!SaverPlan.valueOk("") && !SaverPlan.valueOk(null) && !SaverPlan.valueOk("-1") && !SaverPlan.valueOk("1.5") && !SaverPlan.valueOk("12345678901")
                && !SaverPlan.valueOk("NULL") && !SaverPlan.valueOk("0; reboot") && !SaverPlan.valueOk("1 2") && !SaverPlan.valueOk("Error: no")
                && !SaverPlan.valueOk("١٢٣"), "everything else is not believed");
    }

    private static void planning() {
        // all as the saver wants it, the display switch missing: nothing to change, five readable? four (the display switch does not count)
        SaverPlan.Run r = SaverPlan.plan(s("0", "0", "0", "null", "15000"), false, "null");
        eq(0, r.entries.size(), "already as wanted: no entries");
        eq(4, r.readable, "...four settings were readable (the display switch is not on this phone)");
        eq("[]", r.unreadable.toString(), "...and the missing display switch is not reported");

        // the ordinary phone
        r = SaverPlan.plan(s("1", "1", "1", "1", "30000"), false, "null");
        eq(5, r.entries.size(), "every setting changes");
        eq(5, r.writes().size(), "...and every one is written");
        eq("global wifi_scan_always_enabled 1\nglobal ble_scan_always_enabled 1\nsecure doze_always_on 1\nsystem aod_mode 1\nsystem screen_off_timeout 30000\n",
                r.stored(), "the stored originals, by name");
        yes(!r.timeoutWaits(), "no session, no waiting timeout");

        // only what differs
        r = SaverPlan.plan(s("0", "1", "0", "null", "30000"), false, "null");
        eq("global ble_scan_always_enabled 1\nsystem screen_off_timeout 30000\n", r.stored(), "only changed settings are stored");

        // a setting that is not set is written and later deleted
        r = SaverPlan.plan(s("null", "0", "0", "null", "null"), false, "null");
        eq("global wifi_scan_always_enabled null\nsystem screen_off_timeout null\n", r.stored(), "unset settings are remembered as null");

        // an error text, an empty answer, a missing line: that key is left out and named
        r = SaverPlan.plan(s("Exception occurred", "", null, "null", "30000"), false, "null");
        eq("system screen_off_timeout 30000\n", r.stored(), "only the readable setting is stored");
        eq("[background Wi-Fi scanning, background Bluetooth scanning, the always-on display]", r.unreadable.toString(), "the others are named");
        eq(1, r.readable, "one readable");
        eq("[1, 1, 1, 1, null]", Arrays.toString(planKeys(SaverPlan.plan(s("1", "1", "1", "1", null), false, "null"))), "a missing timeout line leaves only the timeout out");

        // nothing readable at all
        r = SaverPlan.plan(new String[5], false, "null");
        eq(0, r.readable, "nothing readable: the caller must not switch the saver on");
        eq(5, r.unreadable.size(), "...and all five are named (a missing display line is not the same as the phone saying null)");

        // the display switch is touched only when the phone says 0 or 1
        for (String v : new String[] {"2", "on", "", "-1", "true"}) {
            r = SaverPlan.plan(s("0", "0", "0", v, "15000"), false, "null");
            yes(!r.stored().contains("aod_mode"), "the display switch is not touched when it says '" + v + "'");
        }
        r = SaverPlan.plan(s("0", "0", "0", "1", "15000"), false, "null");
        eq("system aod_mode 1\n", r.stored(), "the display switch is touched when it says 1");
        r = SaverPlan.plan(s("0", "0", "0", "0", "30000"), false, "null");
        yes(!r.stored().contains("aod_mode"), "...and not when it is already 0");

        // the numbers: 1 to 10 digits
        r = SaverPlan.plan(s("12345678901", "0", "0", "null", "30000"), false, "null");
        yes(!r.stored().contains("wifi_scan"), "eleven digits are not believed");
        r = SaverPlan.plan(s("-1", "0", "0", "null", "-1"), false, "null");
        yes(r.entries.isEmpty() && r.unreadable.size() == 2, "negative numbers are not believed");

        // a remote session owns the timeout: its original is stored, nothing is written for it
        r = SaverPlan.plan(s("1", "0", "0", "null", "60000"), true, "30000");
        eq("global wifi_scan_always_enabled 1\nsystem screen_off_timeout 30000\n", r.stored(), "the session's original is the one kept (not the 60000 on the phone)");
        eq(1, r.writes().size(), "only the other setting is written");
        yes(r.timeoutWaits(), "the sentence will say the timeout waits");
        r = SaverPlan.plan(s("0", "0", "0", "null", "60000"), true, "15000");
        yes(r.entries.isEmpty(), "a session whose original already is the short timeout: nothing to keep");
        r = SaverPlan.plan(s("0", "0", "0", "null", "60000"), true, "garbage");
        eq("[the screen timeout]", r.unreadable.toString(), "a session record that is no number is left out and named");
        r = SaverPlan.plan(s("0", "0", "0", "null", "60000"), true, "null");
        eq("system screen_off_timeout null\n", r.stored(), "a session whose original was 'not set' is kept as null");
    }

    private static String[] planKeys(SaverPlan.Run r) {
        String[] out = new String[5];
        for (SaverPlan.Entry e : r.entries) {
            out[e.idx] = e.original;
        }
        return out;
    }

    private static void storage() {
        SaverPlan.Parsed p = SaverPlan.parse("global wifi_scan_always_enabled 1\nsystem screen_off_timeout null\n");
        eq(2, p.entries.size(), "two stored lines");
        eq("[]", p.skipped.toString(), "nothing skipped");
        eq("null", p.entries.get(1).original, "null is kept");
        eq(0, SaverPlan.parse("").entries.size() + SaverPlan.parse(null).entries.size(), "empty storage is no entries");

        // nothing that is not a known name and a number reaches the shell
        String[] evil = {
            "global wifi_scan_always_enabled 0; reboot",
            "global wifi_scan_always_enabled $(reboot)",
            "global wifi_scan_always_enabled `reboot`",
            "global wifi_scan_always_enabled -1",
            "global wifi_scan_always_enabled 12345678901",
            "global wifi_scan_always_enabled Error:",
            "global; reboot x 1",
            "global secure_setting_that_is_not_ours 1",
            "system screen_off_timeout",
            "global wifi_scan_always_enabled  1",
            "stuff",
            "../../x y 1",
        };
        for (String e : evil) {
            SaverPlan.Parsed q = SaverPlan.parse(e + "\n");
            eq(0, q.entries.size(), "not trusted: " + e);
            eq(1, q.skipped.size(), "...and named: " + e);
            SaverPlan.Restore r = SaverPlan.restore(q, false, false);
            eq("true", r.command, "...so the shell line is empty: " + e);
        }
        // the hotspot offload setting of the older version may still be stored by name
        eq(1, SaverPlan.parse("global tether_offload_disabled 1\n").entries.size(), "the older version's setting is known");
    }

    private static void restoring() {
        // new format
        SaverPlan.Parsed p = SaverPlan.parse("global wifi_scan_always_enabled 1\nsystem aod_mode 1\nsystem screen_off_timeout null\n");
        SaverPlan.Restore r = SaverPlan.restore(p, false, false);
        eq("settings put global wifi_scan_always_enabled 1; settings put system aod_mode 1; settings delete system screen_off_timeout; true", r.command,
                "restore: put, put, delete");
        eq(3, r.count, "three things");
        eq(null, r.sessionTimeout, "no session");

        // the timeout is left to a session, and the session is told the original
        r = SaverPlan.restore(SaverPlan.parse("global wifi_scan_always_enabled 1\nsystem screen_off_timeout 30000\n"), false, true);
        eq("settings put global wifi_scan_always_enabled 1; true", r.command, "the timeout is not written while a session owns it");
        eq("30000", r.sessionTimeout, "...the session gets the original");
        eq(1, r.count, "one thing");
        r = SaverPlan.restore(SaverPlan.parse("system screen_off_timeout null\n"), false, true);
        eq("null", r.sessionTimeout, "...also when the original is 'not set'");
        eq(0, r.count, "...and then nothing is left to run");
        r = SaverPlan.restore(SaverPlan.parse("global wifi_scan_always_enabled 1\n"), false, true);
        eq(null, r.sessionTimeout, "no timeout stored, nothing to hand over");

        // nothing stored: nothing to run
        r = SaverPlan.restore(SaverPlan.parse(""), false, false);
        eq(0, r.count, "nothing stored");

        // the older version's profile (o0..o4 in its order, and its Bluetooth note)
        SaverPlan.Parsed old = SaverPlan.parseOld(s("1", "1", "0", "0", "30000"));
        r = SaverPlan.restore(old, false, false);
        eq("settings put global wifi_scan_always_enabled 1; settings put global ble_scan_always_enabled 1; settings put secure doze_always_on 0; "
                + "settings put global tether_offload_disabled 0; settings put system screen_off_timeout 30000; true", r.command, "old format, all five in their order");
        eq(5, r.count, "five things");
        r = SaverPlan.restore(SaverPlan.parseOld(s("null", "1", "0", "null", "null")), true, false);
        eq("settings delete global wifi_scan_always_enabled; settings put global ble_scan_always_enabled 1; settings put secure doze_always_on 0; "
                + "settings delete global tether_offload_disabled; settings delete system screen_off_timeout; svc bluetooth enable; true", r.command,
                "old format: null deletes, and Bluetooth comes back when the older version had switched it off");
        eq(6, r.count, "six things");
        r = SaverPlan.restore(SaverPlan.parseOld(s("1", "Error: x", "0; reboot", null, "30000")), false, false);
        eq("settings put global wifi_scan_always_enabled 1; settings put system screen_off_timeout 30000; true", r.command, "old format: bad values are not handed to the shell");
        eq("[background Bluetooth scanning, the always-on display, the hotspot offload setting]", r.skipped.toString(), "...they are named");
        r = SaverPlan.restore(SaverPlan.parseOld(s("1", "1", "0", "0", "30000")), false, true);
        eq("30000", r.sessionTimeout, "old format: the timeout is left to a session too");
        yes(!r.command.contains("screen_off_timeout"), "...and not in the line");
        eq(5 - 1, r.count, "...four things");
        eq(5, SaverPlan.parseOld(null).skipped.size(), "nothing stored in the old format: all five are skipped");
    }

    private static void writingAndChecking() {
        SaverPlan.Run run = SaverPlan.plan(s("1", "1", "1", "1", "30000"), false, "null");
        String cmd = SaverPlan.writeCommand(run);
        yes(cmd.startsWith("settings put global wifi_scan_always_enabled 0; echo R0=$(settings get global wifi_scan_always_enabled); "), "write then read back");
        yes(cmd.contains("settings put system screen_off_timeout 15000; echo R4=$(settings get system screen_off_timeout); "), "the timeout is written");
        yes(cmd.endsWith("true"), "ends with true");
        SaverPlan.Applied a = SaverPlan.check(run, "R0=0\nR1=0\nR2=0\nR3=0\nR4=15000");
        eq(5, a.changed.size(), "all five took");
        yes(SaverPlan.done(a), "done");
        a = SaverPlan.check(run, "R0=0\nR1=1\nR2=0\nR4=15000");
        eq("[ble_scan_always_enabled, aod_mode]", names(a.failed).toString(), "a value that did not move, and a line that is missing, are failures");
        eq(3, a.changed.size(), "the others took");
        yes(!SaverPlan.done(a), "not done");
        a = SaverPlan.check(run, null);
        eq(5, a.failed.size(), "no output: nothing is believed");
        a = SaverPlan.check(run, "R4=30000 \nR4=15000\nR9=1\nRx=1");
        eq(1, a.changed.size(), "stray lines are ignored, a later line wins");
        run = SaverPlan.plan(s("0", "0", "0", "null", "60000"), true, "30000");
        eq("true", SaverPlan.writeCommand(run), "a session owns the timeout, nothing else changes: nothing to write");
        eq(0, SaverPlan.check(run, "").failed.size(), "...and nothing fails");
    }

    private static List<String> names(List<SaverPlan.Entry> l) {
        List<String> n = new ArrayList<>();
        for (SaverPlan.Entry e : l) {
            n.add(e.key);
        }
        return n;
    }

    private static void sentences() {
        SaverPlan.Run run = SaverPlan.plan(s("1", "1", "1", "1", "30000"), false, "null");
        SaverPlan.Applied a = SaverPlan.check(run, "R0=0\nR1=0\nR2=0\nR3=0\nR4=15000");
        eq("LinkMeter's saver is on: background Wi-Fi scanning off, background Bluetooth scanning off, always-on display off, screen timeout 15 seconds.",
                SaverPlan.appliedSentence(run, a), "the sentence names what changed, once each");
        run = SaverPlan.plan(s("1", "0", "0", "null", "15000"), false, "null");
        a = SaverPlan.check(run, "R0=0");
        eq("LinkMeter's saver is on: background Wi-Fi scanning off.", SaverPlan.appliedSentence(run, a), "one change");
        run = SaverPlan.plan(s("0", "0", "0", "null", "15000"), false, "null");
        eq("LinkMeter's saver is on. Nothing had to be changed.", SaverPlan.appliedSentence(run, SaverPlan.check(run, "")), "nothing to change");
        run = SaverPlan.plan(s("1", "", "0", "null", "15000"), false, "null");
        a = SaverPlan.check(run, "R0=0");
        eq("LinkMeter's saver is on: background Wi-Fi scanning off. Not changed, because the phone did not say what it is now: background Bluetooth scanning.",
                SaverPlan.appliedSentence(run, a), "an unreadable setting is named");
        run = SaverPlan.plan(s("1", "", "", "null", "15000"), false, "null");
        yes(SaverPlan.appliedSentence(run, SaverPlan.check(run, "R0=0")).contains("what they are now: background Bluetooth scanning, the always-on display."),
                "two unreadable settings: 'they are'");
        run = SaverPlan.plan(s("1", "0", "0", "null", "60000"), true, "30000");
        yes(SaverPlan.appliedSentence(run, SaverPlan.check(run, "R0=0")).contains("The screen timeout changes when the remote screen session ends."),
                "a session holds the timeout: the sentence says it waits");
        // failures
        run = SaverPlan.plan(s("1", "1", "1", "1", "30000"), false, "null");
        a = SaverPlan.check(run, "R0=0\nR1=0\nR2=1\nR3=0\nR4=15000");
        eq("LinkMeter's saver is only partly on. The phone did not take: the always-on display. It did change: background Wi-Fi scanning off, background Bluetooth "
                + "scanning off, always-on display off, screen timeout 15 seconds. Switching the saver off puts those back.", SaverPlan.appliedSentence(run, a),
                "a partly failed write is an error sentence that names both");
        a = SaverPlan.check(run, "");
        eq("LinkMeter's saver could not be switched on. The phone did not take: background Wi-Fi scanning, background Bluetooth scanning, the always-on display, the screen timeout.",
                SaverPlan.appliedSentence(run, a), "nothing took");
        // restore words
        SaverPlan.Restore r = SaverPlan.restore(SaverPlan.parse("global wifi_scan_always_enabled 1\n"), false, false);
        eq("LinkMeter's saver is off. Settings are back as they were.", SaverPlan.restoredSentence(r), "restored");
        eq("LinkMeter's saver is off. Nothing had to be put back.", SaverPlan.restoredSentence(SaverPlan.restore(SaverPlan.parse(""), false, false)), "nothing to put back");
        r = SaverPlan.restore(SaverPlan.parse("system screen_off_timeout 30000\n"), false, true);
        yes(SaverPlan.restoredSentence(r).contains("The screen timeout goes back when the remote screen session ends."), "the session gets the timeout");
        r = SaverPlan.restore(SaverPlan.parseOld(s("1", "x", "0", "0", "30000")), false, false);
        yes(SaverPlan.restoredSentence(r).endsWith("Left as they are, because the saved value could not be used: background Bluetooth scanning."), "a value that could not be used is named");
        // the plain-words rules for everything the user reads
        List<String> all = new ArrayList<>(Arrays.asList(SaverPlan.ALREADY_ON, SaverPlan.ALREADY_OFF, SaverPlan.OFF_STORED_WAITING, SaverPlan.ON_STORED_WAITING,
                SaverPlan.AUTOMATIC_NOTE, SaverPlan.OFF_NOTE, SaverPlan.RESTORE_ALL_NOTE, SaverPlan.COULD_NOT_READ));
        all.add(SaverPlan.restoredSentence(r));
        all.add(SaverPlan.appliedSentence(run, a));
        for (String t : all) {
            yes(!t.contains("!") && !t.contains(" - ") && !t.contains("—") && !t.contains("–"), "no exclamation marks or dashes: " + t);
            yes(!t.contains("Eco") && !t.contains("Priv") && !t.contains("settings put") && !t.contains("ShellMain"), "no class names or commands: " + t);
        }
        yes(SaverPlan.RESTORE_ALL_NOTE.contains("switched off") && SaverPlan.RESTORE_ALL_NOTE.contains("Switch it on again in the Phone tab"),
                "Restore everything says the saver is now off and where to switch it on");
        yes(SaverPlan.OFF_STORED_WAITING.contains("Shizuku"), "Off while Shizuku is down says what it waits for");
    }

    private static void homeWords() {
        eq("on", SaverPlan.homeFact(true, true, 2), "on and automatic: on");
        eq("on, going off", SaverPlan.homeFact(true, false, 2), "on but Off was chosen: it is not called plain on or off");
        eq("on, going off", SaverPlan.homeFact(true, false, 0), "...whatever Shizuku says");
        eq("off", SaverPlan.homeFact(false, false, 2), "off and Off");
        eq("automatic: starts when the tablet is on the hotspot", SaverPlan.homeFact(false, true, 2), "off but Automatic: named by its choice, not called on");
        eq("automatic: starts when the tablet is on the hotspot", SaverPlan.homeFact(false, true, -1), "...also when the phone did not say about Shizuku");
        eq("automatic, needs Shizuku", SaverPlan.homeFact(false, true, 0), "Automatic but Shizuku is not running: it cannot start");
        eq("automatic, needs Shizuku", SaverPlan.homeFact(false, true, 1), "...or not allowed yet");
        for (int priv = -1; priv <= 2; priv++) {
            yes(!SaverPlan.homeFact(false, true, priv).equals("on") && !SaverPlan.homeFact(false, false, priv).equals("on"), "never 'on' for a saver that is off");
        }
    }

    private static void current() {
        eq(null, SaverPlan.milliamps(Long.MIN_VALUE, false), "not supported");
        eq(null, SaverPlan.milliamps(0, false), "0 on a phone that is not charging is 'not reported'");
        eq(0, SaverPlan.milliamps(0, true), "0 while charging is 0");
        eq(-312, SaverPlan.milliamps(-312000, false), "microamps, discharging");
        eq(-312, SaverPlan.milliamps(312000, false), "microamps with the sign the other way round: the charging flag decides");
        eq(-312, SaverPlan.milliamps(312, false), "milliamps");
        eq(-312, SaverPlan.milliamps(-312, false), "milliamps, negative");
        eq(2450, SaverPlan.milliamps(-2450000, true), "microamps, charging: positive whatever the phone's sign");
        eq(1500, SaverPlan.milliamps(1500, true), "milliamps, charging");
        eq(-21, SaverPlan.milliamps(-20500, false), "just above 20,000 is microamps: 20.5 mA, rounded");
        eq(-20000, SaverPlan.milliamps(-20000, false), "20,000 itself is milliamps");
        eq(-1235, SaverPlan.milliamps(-1234567, false), "rounded to a whole number");
        eq(null, SaverPlan.milliamps(-90000000L, false), "90 A is not a battery current");
        eq(-40, SaverPlan.milliamps(-40000L, false), "40,000 microamps is 40 mA");
    }

    // ------------------------------------------------------------------------------------------------ a fake phone to run the shell lines on

    /** A directory of files as the phone's settings, and `settings` and `svc` programs to go with it. */
    private static final class Phone {
        final Path dir;
        final Path bin;
        String ignorePuts = "";
        String brokenGets = "";
        final List<String> shellLines = new ArrayList<>();

        Phone() throws IOException {
            dir = Files.createTempDirectory("saver-settings");
            bin = Files.createTempDirectory("saver-bin");
            write(bin.resolve("settings"), "#!/bin/sh\n"
                    + "d=\"$FAKE_SETTINGS\"; op=\"$1\"; ns=\"$2\"; key=\"$3\"\n"
                    + "case \"$op\" in\n"
                    + "  get)\n"
                    + "    case \" $FAKE_BROKEN_GET \" in *\" $ns.$key \"*) echo \"Exception occurred while executing 'get':\"; echo \"java.lang.SecurityException: no\" >&2; exit 0;; esac\n"
                    + "    if [ -f \"$d/$ns.$key\" ]; then cat \"$d/$ns.$key\"; else echo null; fi ;;\n"
                    + "  put)\n"
                    + "    case \" $FAKE_IGNORE \" in *\" $ns.$key \"*) exit 0;; esac\n"
                    + "    echo \"$ns.$key=$4\" >> \"$d/puts.log\"\n"
                    + "    printf '%s\\n' \"$4\" > \"$d/$ns.$key\" ;;\n"
                    + "  delete) echo \"$ns.$key=DELETE\" >> \"$d/puts.log\"; rm -f \"$d/$ns.$key\" ;;\n"
                    + "  *) echo \"bad usage\" >&2; exit 1 ;;\n"
                    + "esac\n");
            write(bin.resolve("svc"), "#!/bin/sh\necho \"svc $*\" >> \"$FAKE_SETTINGS/svc.log\"\n");
            for (String f : new String[] {"settings", "svc"}) {
                bin.resolve(f).toFile().setExecutable(true);
            }
        }

        static void write(Path p, String text) throws IOException {
            Files.write(p, text.getBytes(StandardCharsets.UTF_8));
        }

        String get(String ns, String key) throws IOException {
            Path f = dir.resolve(ns + "." + key);
            return Files.exists(f) ? new String(Files.readAllBytes(f), StandardCharsets.UTF_8).trim() : "null";
        }

        void set(String ns, String key, String v) throws IOException {
            if (v.equals("null")) {
                Files.deleteIfExists(dir.resolve(ns + "." + key));
            } else {
                write(dir.resolve(ns + "." + key), v + "\n");
            }
        }

        /** The five settings of the profile, as the phone has them. */
        String[] profile() throws IOException {
            String[] v = new String[SaverPlan.PROFILE.length];
            for (int i = 0; i < v.length; i++) {
                v[i] = get(SaverPlan.PROFILE[i][0], SaverPlan.PROFILE[i][1]);
            }
            return v;
        }

        void profile(String... v) throws IOException {
            for (int i = 0; i < v.length; i++) {
                set(SaverPlan.PROFILE[i][0], SaverPlan.PROFILE[i][1], v[i]);
            }
        }

        /** What the app's shell helper does: runs a line, output with errors, and the line's own exit status says whether it ran. */
        Object[] run(String cmd) throws Exception {
            shellLines.add(cmd);
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "(" + cmd + ") 2>&1");
            pb.environment().put("PATH", bin + File.pathSeparator + System.getenv("PATH"));
            pb.environment().put("FAKE_SETTINGS", dir.toString());
            pb.environment().put("FAKE_IGNORE", ignorePuts);
            pb.environment().put("FAKE_BROKEN_GET", brokenGets);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("the shell line did not end: " + cmd);
            }
            return new Object[] {p.exitValue() == 0, new String(out, StandardCharsets.UTF_8).trim()};
        }

        /** Every setting ever written, as "namespace.key=value": for checking that only known names and numbers were ever sent. */
        List<String> writes() throws IOException {
            Path f = dir.resolve("puts.log");
            return Files.exists(f) ? Files.readAllLines(f) : new ArrayList<String>();
        }

        List<String> svcCalls() throws IOException {
            Path f = dir.resolve("svc.log");
            return Files.exists(f) ? Files.readAllLines(f) : new ArrayList<String>();
        }
    }

    // ------------------------------------------------------------------------------------------------ a world whose steps follow Eco's

    /** Stands for Actions.NotDone (that class needs Android): a switch that did not move, with the sentence for the user. */
    private static final class NotDone extends IOException {
        NotDone(String sentence) {
            super(sentence);
        }
    }

    /** The saver's stored state, a running process, a Screen-tab session, and the phone. Each method follows the Eco method of the same name. */
    private static final class World {
        final Phone phone;
        // stored (Eco's preferences and Cfg.ecoAuto)
        boolean auto = true;
        boolean on;
        boolean newFormat;
        String orig2 = "";
        String[] oldO;
        boolean oldBt;
        // in the process (lost when the process dies)
        long now = T0;
        long lastSeen;
        long failedAt;
        boolean armed;
        boolean watchingScreen;
        // the world
        boolean shizuku = true;
        Boolean hotspot = Boolean.TRUE;
        boolean screenOn;
        boolean tabletAnswers = true;
        // a Screen-tab session (RemoteProfile): the screen timeout only
        boolean sOn;
        boolean sChg;
        String sOrig = "null";
        String sAppl = "null";
        // what was said
        String said = "";
        boolean errored;
        int shellCalls;
        int pings;

        World(Phone phone) {
            this.phone = phone;
        }

        // ---- the shell (Priv.run)
        Object[] shell(String cmd) throws Exception {
            if (!shizuku) {
                throw new IOException("Shizuku is not running");
            }
            shellCalls++;
            return phone.run(cmd);
        }

        // ---- the session (RemoteProfile) and its link to the saver
        boolean ownsTimeout() {
            return sOn && sChg;
        }

        void sessionBegin(long keepMs) throws Exception {
            String cur = phone.get("system", "screen_off_timeout");
            String[] curA = {cur, "null", "null"};
            String[] orig = {sOrig, "null", "null"};
            boolean[] chg = {sChg, false, false};
            String[] appl = {sAppl, "null", "null"};
            ProfilePlan.Begin p = ProfilePlan.begin(sOn, orig, chg, appl, curA, keepMs, -1, on, SaverPlan.timeoutMs());
            if (p.writes[0] != null) {
                phone.set("system", "screen_off_timeout", p.writes[0]);
            }
            sOrig = p.orig[0];
            sChg = p.chg[0];
            sAppl = p.chg[0] ? phone.get("system", "screen_off_timeout") : "null";
            sOn = p.any();
        }

        void sessionEnd() throws Exception {
            if (!sOn) {
                return;
            }
            String cur = phone.get("system", "screen_off_timeout");
            String[] w = ProfilePlan.end(new boolean[] {sChg, false, false}, new String[] {sAppl, "null", "null"}, new String[] {sOrig, "null", "null"},
                    new String[] {cur, "null", "null"}, on, SaverPlan.timeoutMs());
            if (w[0] != null) {
                phone.set("system", "screen_off_timeout", w[0]);
            }
            sOn = false;
            sChg = false;
        }

        // ---- Eco
        void arm() {
            armed = true;
        }

        void disarm() {
            armed = false;
        }

        void apply() throws Exception {
            if (on) {
                said = SaverPlan.ALREADY_ON;
                return;
            }
            try {
                Object[] r = shell(SaverPlan.readCommand());
                if (!(Boolean) r[0]) {
                    throw new NotDone("Couldn't read the phone's settings: " + r[1]);
                }
                SaverPlan.Run run = SaverPlan.plan(SaverPlan.parseRead((String) r[1]), ownsTimeout(), sOrig);
                if (run.readable == 0) {
                    throw new NotDone(SaverPlan.COULD_NOT_READ);
                }
                newFormat = true;
                orig2 = run.stored();
                oldO = null;
                oldBt = false;
                on = true;
                SaverPlan.Applied a = new SaverPlan.Applied();
                if (!run.writes().isEmpty()) {
                    Object[] w = shell(SaverPlan.writeCommand(run));
                    a = SaverPlan.check(run, (String) w[1]);
                }
                said = SaverPlan.appliedSentence(run, a);
                if (!SaverPlan.done(a)) {
                    if (a.changed.isEmpty()) {
                        on = false;
                        orig2 = "";
                        newFormat = false;
                        failedAt = now;
                    }
                    throw new NotDone(said);
                }
                arm();
                watchingScreen = true;
            } catch (IOException e) {
                failedAt = now;
                throw e;
            }
        }

        void restore() throws Exception {
            if (!on) {
                said = SaverPlan.ALREADY_OFF;
                return;
            }
            SaverPlan.Restore r = restorePlan();
            try {
                if (r.count > 0) {
                    Object[] res = shell(r.command);
                    if (!(Boolean) res[0]) {
                        throw new NotDone("Couldn't restore everything: " + res[1]);
                    }
                }
                if (r.sessionTimeout != null && ownsTimeout() && SaverPlan.valueOk(r.sessionTimeout)) {
                    sOrig = r.sessionTimeout; // RemoteProfile.adoptOriginalTimeout
                }
                on = false;
                orig2 = "";
                newFormat = false;
                oldO = null;
                oldBt = false;
                disarm();
                watchingScreen = false;
                said = SaverPlan.restoredSentence(r);
            } catch (IOException e) {
                failedAt = now;
                throw e;
            }
        }

        SaverPlan.Restore restorePlan() {
            SaverPlan.Parsed parsed = newFormat ? SaverPlan.parse(orig2) : SaverPlan.parseOld(oldO);
            return SaverPlan.restore(parsed, !newFormat && oldBt, ownsTimeout());
        }

        /** Eco.touch: a request from the tablet. */
        void touch(boolean fromLocalNet) throws Exception {
            lastSeen = now;
            if (on) {
                arm();
            }
            if (auto == on) {
                return; // the common case; reconcile would say NOTHING too, without asking Shizuku
            }
            if (SaverPlan.reconcile(auto, on, fromLocalNet, shizuku, now, failedAt) == SaverPlan.Act.NOTHING) {
                return;
            }
            reconcileNow(fromLocalNet);
        }

        /** Eco.reconcileNow: the background thread, which asks again under the lock before it acts. */
        void reconcileNow(boolean fromLocalNet) throws Exception {
            try {
                SaverPlan.Act a = SaverPlan.reconcile(auto, on, fromLocalNet, shizuku, now, failedAt);
                if (a == SaverPlan.Act.APPLY) {
                    apply();
                } else if (a == SaverPlan.Act.RESTORE) {
                    restore();
                }
            } catch (IOException | RuntimeException e) {
                failedAt = now;
            }
        }

        /** A request that carries a switch: the request is counted first (Eco.touch), then the action runs. */
        void requestThenSetAuto(boolean fromLocalNet, boolean newAuto) throws Exception {
            touch(fromLocalNet);
            setAuto(newAuto);
        }

        void setAuto(boolean value) throws Exception {
            auto = value;
            errored = false;
            try {
                if (value) {
                    lastSeen = now;
                    if (on) {
                        said = SaverPlan.ALREADY_ON + SaverPlan.AUTOMATIC_NOTE;
                        arm();
                        watchingScreen = true;
                    } else if (!shizuku) {
                        said = SaverPlan.ON_STORED_WAITING;
                    } else {
                        apply();
                        said = said + SaverPlan.AUTOMATIC_NOTE;
                    }
                } else if (!on) {
                    said = SaverPlan.ALREADY_OFF + SaverPlan.OFF_NOTE;
                } else if (!shizuku && restorePlan().count > 0) {
                    said = SaverPlan.OFF_STORED_WAITING;
                } else {
                    restore();
                    said = said + SaverPlan.OFF_NOTE;
                }
            } catch (IOException e) {
                said = e.getMessage();
                errored = true;
            }
        }

        /** The alarm (alarm = true: it has gone off, so it is no longer set) or the screen coming on. */
        void idleCheck(boolean alarm) throws Exception {
            if (alarm) {
                armed = false;
            }
            Boolean answers = null;
            SaverPlan.Idle d = SaverPlan.idle(auto, on, now, lastSeen, hotspot, screenOn, null, shizuku);
            if (d.verdict == SaverPlan.Verdict.ASK_TABLET) {
                pings++;
                answers = tabletAnswers;
                d = SaverPlan.idle(auto, on, now, lastSeen, hotspot, screenOn, answers, shizuku);
            }
            if (d.verdict == SaverPlan.Verdict.RESTORE) {
                d = SaverPlan.idle(auto, on, now, lastSeen, hotspot, screenOn, answers, shizuku); // asked again under the lock
                if (d.verdict == SaverPlan.Verdict.RESTORE) {
                    try {
                        restore();
                        return;
                    } catch (IOException | RuntimeException e) {
                        failedAt = now;
                        d = new SaverPlan.Idle(SaverPlan.Verdict.LOOK_AGAIN, SaverPlan.IDLE_MS);
                    }
                }
            }
            if (d.verdict == SaverPlan.Verdict.NOTHING_TO_WATCH) {
                disarm();
                watchingScreen = false;
            } else if (d.verdict == SaverPlan.Verdict.LOOK_AGAIN) {
                arm();
            }
        }

        void serviceStart() throws Exception {
            lastSeen = now;
            armed = false; // a new process: the alarm and the receiver went with the old one
            watchingScreen = false;
            failedAt = 0;
            if (on) {
                arm();
                watchingScreen = true;
            }
            if (auto != on) {
                reconcileNow(false); // the tablet is not here yet: only "Off and still on" can do anything
            }
        }

        void serviceStop() {
            disarm();
            watchingScreen = false;
        }
    }

    // ------------------------------------------------------------------------------------------------ stories

    private static void realShellLines() throws Exception {
        Phone ph = new Phone();
        ph.profile("1", "null", "1", "1", "30000");
        World w = new World(ph);
        w.touch(true);
        eq(true, w.on, "the first request from the tablet switches the saver on");
        eq("[0, 0, 0, 0, 15000]", Arrays.toString(ph.profile()), "the phone has the saver's values (an unset setting is written too)");
        eq("LinkMeter's saver is on: background Wi-Fi scanning off, background Bluetooth scanning off, always-on display off, screen timeout 15 seconds.",
                w.said, "the sentence is what really changed");
        w.setAuto(false);
        eq("[1, null, 1, 1, 30000]", Arrays.toString(ph.profile()), "turning it off puts everything back, the unset setting is deleted again");
        yes(w.said.startsWith("LinkMeter's saver is off. Settings are back as they were."), "restored sentence");
        yes(!w.on && !w.armed && !w.watchingScreen, "no alarm and no receiver left behind");
        for (String line : ph.writes()) {
            yes(line.matches("(global|secure|system)\\.[a-z_]+=(\\d{1,10}|DELETE)"), "only numbers and deletes were ever sent: " + line);
        }
    }

    private static void stories() throws Exception {
        // ---- the process is new and the stored choice is Off (the old bug): Off must win
        Phone ph = new Phone();
        ph.profile("1", "1", "1", "null", "30000");
        World w = new World(ph);
        w.touch(true);
        yes(w.on, "setup: the saver is on");
        w.setAuto(false);
        yes(!w.on, "setup: turned off");
        w.serviceStart(); // the process died and came back
        w.touch(true);
        yes(!w.on, "STORY the old bug: after the process restarts, Off stays off (the next request does not switch it on)");
        eq("[1, 1, 1, null, 30000]", Arrays.toString(ph.profile()), "...and the phone keeps its own values");

        // ---- Off chosen while Shizuku is down, then Shizuku comes up
        ph = new Phone();
        ph.profile("1", "1", "1", "null", "30000");
        w = new World(ph);
        w.touch(true);
        w.shizuku = false;
        w.setAuto(false);
        eq(SaverPlan.OFF_STORED_WAITING, w.said, "STORY Off while Shizuku is down: stored, and it says so");
        yes(!w.errored, "...as an answer, not an error");
        yes(!w.auto && w.on, "...Off is stored, the settings are still the saver's");
        w.now += 5000;
        w.touch(true);
        yes(w.on, "...a request while Shizuku is still down changes nothing");
        eq(0, w.failedAt > 0 ? 1 : 0, "...and it is not counted as a failure (it just cannot be done yet)");
        w.shizuku = true;
        w.now += 5000;
        w.touch(true);
        yes(!w.on, "...the first request after Shizuku runs puts everything back");
        eq("[1, 1, 1, null, 30000]", Arrays.toString(ph.profile()), "...exactly as it was");

        // ---- the same through a restart of the process while Shizuku is down
        ph = new Phone();
        ph.profile("1", "1", "1", "null", "30000");
        w = new World(ph);
        w.touch(true);
        w.shizuku = false;
        w.setAuto(false);
        w.serviceStart();
        yes(w.on && w.armed && w.watchingScreen, "STORY new process, Off stored, still on: the service watches it (alarm and screen receiver)");
        w.shizuku = true;
        w.now += SaverPlan.IDLE_MS;
        w.idleCheck(true);
        yes(!w.on, "...and the alarm puts it back when Shizuku is there, tablet or not");

        // ---- a turn-off request arriving when the saver is off and Automatic was stored
        ph = new Phone();
        ph.profile("1", "1", "1", "null", "30000");
        w = new World(ph);
        yes(w.auto && !w.on, "setup: Automatic, saver off");
        w.auto = false; // the action has stored Off ...
        w.reconcileNow(true); // ... before the thread that the same request started got the saver's lock: it asks again and finds Off
        yes(!w.on, "STORY a 'turn off' request must not switch the saver on: the decision is asked again under the lock");
        eq(0, w.shellCalls, "...not even a shell line was run");
        w = new World(ph);
        w.requestThenSetAuto(true, false); // the thread won the lock first: it switches on, the action then switches off
        yes(!w.on && !w.auto, "...and when the thread was first, the request still ends with the saver off");
        eq("[1, 1, 1, null, 30000]", Arrays.toString(ph.profile()), "...and the phone as it was");

        // ---- an old-format profile restored by the new code
        ph = new Phone();
        ph.set("global", "wifi_scan_always_enabled", "0");
        ph.set("global", "ble_scan_always_enabled", "0");
        ph.set("secure", "doze_always_on", "0");
        ph.set("global", "tether_offload_disabled", "0");
        ph.set("system", "screen_off_timeout", "15000");
        w = new World(ph);
        w.on = true;
        w.newFormat = false;
        w.oldO = s("1", "1", "1", "1", "30000");
        w.oldBt = true;
        w.setAuto(false);
        eq("1 1 1 1 30000", ph.get("global", "wifi_scan_always_enabled") + " " + ph.get("global", "ble_scan_always_enabled") + " " + ph.get("secure", "doze_always_on")
                + " " + ph.get("global", "tether_offload_disabled") + " " + ph.get("system", "screen_off_timeout"),
                "STORY old profile: all five originals come back, including the hotspot offload setting the new version no longer writes");
        eq("[svc bluetooth enable]", ph.svcCalls().toString(), "...and Bluetooth is switched back on");
        yes(!w.on, "...and the saver is off");
        // the old profile with stored values that are an error text and a command
        ph = new Phone();
        w = new World(ph);
        w.on = true;
        w.oldO = s("1", "Error: no such key", "1; reboot", "1", "30000");
        w.setAuto(false);
        yes(w.said.contains("Left as they are, because the saved value could not be used: background Bluetooth scanning, the always-on display."), "...bad stored values are named");
        for (String line : ph.shellLines) {
            yes(!line.contains("reboot") && !line.contains("Error"), "...and never reach the shell: " + line);
        }
        eq("1 null null 1 30000", ph.get("global", "wifi_scan_always_enabled") + " " + ph.get("global", "ble_scan_always_enabled") + " " + ph.get("secure", "doze_always_on")
                + " " + ph.get("global", "tether_offload_disabled") + " " + ph.get("system", "screen_off_timeout"), "...the others are put back, those two are left as they are");

        // ---- a `settings get` that answered an error text
        ph = new Phone();
        ph.profile("1", "1", "1", "null", "30000");
        ph.brokenGets = "global.ble_scan_always_enabled";
        w = new World(ph);
        w.touch(true);
        yes(w.on, "STORY an unreadable setting: the saver still switches on for the others");
        yes(w.said.contains("Not changed, because the phone did not say what it is now: background Bluetooth scanning."), "...and names the one it left alone");
        eq("[0, 1, 0, null, 15000]", Arrays.toString(ph.profile()), "...which is not written");
        w.setAuto(false);
        eq("[1, 1, 1, null, 30000]", Arrays.toString(ph.profile()), "...and not touched when the saver goes off");
        for (String line : ph.writes()) {
            yes(!line.contains("ble_scan"), "...nothing was ever written for it: " + line);
        }
        // every setting unreadable: the saver does not switch on, and waits a minute
        ph = new Phone();
        ph.profile("1", "1", "1", "null", "30000");
        ph.brokenGets = "global.wifi_scan_always_enabled global.ble_scan_always_enabled secure.doze_always_on system.screen_off_timeout";
        w = new World(ph);
        w.touch(true);
        yes(!w.on, "STORY nothing readable: not switched on");
        yes(w.failedAt > 0, "...and the failure is remembered");
        int calls = w.shellCalls;
        w.now += 30 * 1000L;
        w.touch(true);
        eq(calls, w.shellCalls, "...no new try within a minute (a request every five seconds does not run a shell every time)");
        w.now += 31 * 1000L;
        ph.brokenGets = "";
        w.touch(true);
        yes(w.on, "...a minute later it tries again and succeeds");

        // ---- a write the phone does not take
        ph = new Phone();
        ph.profile("1", "1", "1", "1", "30000");
        ph.ignorePuts = "system.aod_mode";
        w = new World(ph);
        w.setAuto(true);
        yes(w.errored && w.said.startsWith("LinkMeter's saver is only partly on. The phone did not take: the always-on display."),
                "STORY a write the phone ignores: an explicit switch-on answers an error that names it");
        yes(w.on, "...and the saver is left in a state that can be undone");
        ph.ignorePuts = "";
        w.setAuto(false);
        eq("[1, 1, 1, 1, 30000]", Arrays.toString(ph.profile()), "...which puts everything back");
        // nothing took at all
        ph = new Phone();
        ph.profile("1", "1", "1", "null", "30000");
        ph.ignorePuts = "global.wifi_scan_always_enabled global.ble_scan_always_enabled secure.doze_always_on system.screen_off_timeout";
        w = new World(ph);
        w.setAuto(true);
        yes(w.errored && !w.on, "STORY no write took: an error, and the saver is not left 'on'");

        // ---- Standby for a day, then the screen comes on
        ph = new Phone();
        ph.profile("1", "1", "1", "null", "30000");
        w = new World(ph);
        w.touch(true);
        w.hotspot = Boolean.FALSE; // Standby: hotspot off,
        w.screenOn = false; // screen off
        w.now += 24 * 60 * MIN;
        w.idleCheck(true);
        yes(w.on && w.armed, "STORY Standby for a day: the saver stays (nobody sees the screen timeout), and the alarm is set again");
        eq(0, w.pings, "...no question is asked of a tablet that cannot be reached");
        w.screenOn = true; // somebody picks the phone up
        w.idleCheck(false);
        yes(!w.on, "...when the screen comes on, the phone's own settings come back at once");
        eq("[1, 1, 1, null, 30000]", Arrays.toString(ph.profile()), "...exactly");
        yes(!w.armed && !w.watchingScreen, "...and the alarm and the receiver are gone");

        // ---- the tablet in another app for an hour with the hotspot on
        ph = new Phone();
        ph.profile("1", "1", "1", "null", "30000");
        w = new World(ph);
        w.touch(true);
        for (int i = 0; i < 6; i++) {
            w.now += 10 * MIN;
            w.hotspot = Boolean.TRUE;
            w.tabletAnswers = true;
            w.idleCheck(true);
            yes(w.on && w.armed, "STORY the tablet in another app, ten minutes more (" + (i + 1) + "): still on, looking again");
        }
        eq(6, w.pings, "...checked by asking the tablet's address once in ten minutes");
        w.tabletAnswers = false;
        w.now += 10 * MIN;
        w.idleCheck(true);
        yes(!w.on, "...and when the tablet stops answering it is put back");
        // Bluetooth requests do not count: the saver is not switched on for them
        ph = new Phone();
        ph.profile("1", "1", "1", "null", "30000");
        w = new World(ph);
        w.touch(false);
        yes(!w.on, "a request that did not come over the local network does not switch the saver on");

        // ---- a recent request keeps the saver, whatever else is going on
        ph = new Phone();
        ph.profile("1", "1", "1", "null", "30000");
        w = new World(ph);
        w.touch(true);
        w.now += 4 * MIN;
        w.hotspot = Boolean.FALSE;
        w.screenOn = true;
        w.idleCheck(false);
        yes(w.on, "STORY the tablet spoke four minutes ago: not gone, even with the screen on and no hotspot");

        // ---- the Screen tab's session and the saver (the audit's D18)
        // session first, saver while it runs, saver off while it runs, session ends: the user's timeout is back
        ph = new Phone();
        ph.profile("0", "0", "0", "null", "30000");
        w = new World(ph);
        w.sessionBegin(120000);
        eq("120000", ph.get("system", "screen_off_timeout"), "setup: the session keeps the screen on for two minutes");
        w.setAuto(true);
        yes(w.on && w.said.contains("The screen timeout changes when the remote screen session ends."), "STORY session, then saver: the timeout is left to the session");
        eq("120000", ph.get("system", "screen_off_timeout"), "...the phone still has the session's timeout");
        w.setAuto(false);
        eq("120000", ph.get("system", "screen_off_timeout"), "...turning the saver off during the session leaves the timeout alone (the screen stays on)");
        yes(w.said.contains("goes back when the remote screen session ends"), "...and says so");
        w.sessionEnd();
        eq("30000", ph.get("system", "screen_off_timeout"), "...and the session puts the USER'S timeout back, not the saver's 15 seconds");
        // saver first, then the session, saver off during the session, session ends
        ph = new Phone();
        ph.profile("0", "0", "0", "null", "30000");
        w = new World(ph);
        w.setAuto(true);
        eq("15000", ph.get("system", "screen_off_timeout"), "setup: the saver is on with its 15 seconds");
        w.sessionBegin(120000);
        eq("15000", w.sOrig, "...the session read the saver's 15 seconds as its 'original'");
        w.setAuto(false);
        eq("120000", ph.get("system", "screen_off_timeout"), "STORY saver, then session, then saver off: the session's timeout stays");
        eq("30000", w.sOrig, "...and the session was told the real original");
        w.sessionEnd();
        eq("30000", ph.get("system", "screen_off_timeout"), "...so it ends with the user's own timeout, not 15 seconds for ever");
        // saver first, session ends first: the saver's value stays until the saver goes
        ph = new Phone();
        ph.profile("0", "0", "0", "null", "30000");
        w = new World(ph);
        w.setAuto(true);
        w.sessionBegin(120000);
        w.sessionEnd();
        eq("15000", ph.get("system", "screen_off_timeout"), "STORY saver, session, session ends: back to the saver's value");
        w.setAuto(false);
        eq("30000", ph.get("system", "screen_off_timeout"), "...and the saver then restores the user's");
        // the saver comes on again during the session after it was switched off during it
        ph = new Phone();
        ph.profile("0", "0", "0", "null", "30000");
        w = new World(ph);
        w.setAuto(true);
        w.sessionBegin(120000);
        w.setAuto(false);
        w.setAuto(true);
        w.setAuto(false);
        w.sessionEnd();
        eq("30000", ph.get("system", "screen_off_timeout"), "STORY off, on, off during one session: still the user's own timeout in the end");

        // ---- the service is stopped: nothing is left behind
        ph = new Phone();
        ph.profile("1", "1", "1", "null", "30000");
        w = new World(ph);
        w.touch(true);
        yes(w.armed && w.watchingScreen, "the saver being on has an alarm and a receiver");
        w.serviceStop();
        yes(!w.armed && !w.watchingScreen, "STORY the service stops: no alarm and no receiver stay behind");

        // ---- Off but Shizuku down on a request: nothing happens, and it is no error
        ph = new Phone();
        ph.profile("1", "1", "1", "null", "30000");
        w = new World(ph);
        w.touch(true);
        int before = w.shellCalls;
        w.auto = false;
        w.shizuku = false;
        w.touch(true);
        yes(w.on, "STORY Off but Shizuku down: nothing happens");
        eq(before, w.shellCalls, "...and no shell line is attempted");
    }

    // ------------------------------------------------------------------------------------------------ random

    private static void random() throws Exception {
        Random rnd = new Random(2026);
        String[] timeouts = {"null", "15000", "30000", "60000", "300000"};
        String[] bits = {"null", "0", "1"};
        int runs = 120;
        for (int t = 0; t < runs; t++) {
            Phone ph = new Phone();
            String[] start = {bits[rnd.nextInt(3)], bits[rnd.nextInt(3)], bits[rnd.nextInt(3)], bits[rnd.nextInt(3)], timeouts[rnd.nextInt(timeouts.length)]};
            ph.profile(start);
            if (rnd.nextInt(4) == 0) {
                ph.brokenGets = "global.ble_scan_always_enabled";
            }
            World w = new World(ph);
            w.auto = rnd.nextBoolean();
            int steps = 3 + rnd.nextInt(10);
            for (int i = 0; i < steps; i++) {
                w.now += 1000L * (1 + rnd.nextInt(900));
                switch (rnd.nextInt(11)) {
                    case 0:
                        w.touch(rnd.nextInt(5) != 0);
                        break;
                    case 1:
                        w.setAuto(rnd.nextBoolean());
                        break;
                    case 2:
                        w.idleCheck(true);
                        break;
                    case 3:
                        w.screenOn = rnd.nextBoolean();
                        w.idleCheck(false);
                        break;
                    case 4:
                        w.serviceStart();
                        break;
                    case 5:
                        w.shizuku = rnd.nextBoolean();
                        break;
                    case 6:
                        w.hotspot = rnd.nextInt(3) == 0 ? null : Boolean.valueOf(rnd.nextBoolean());
                        w.tabletAnswers = rnd.nextBoolean();
                        break;
                    case 7:
                        w.sessionBegin(rnd.nextBoolean() ? 120000 : 600000);
                        break;
                    case 8:
                        w.sessionEnd();
                        break;
                    case 9:
                        w.requestThenSetAuto(rnd.nextBoolean(), rnd.nextBoolean());
                        break;
                    default:
                        w.serviceStop();
                        break;
                }
                // whatever happened, the stored state is one the code understands
                if (w.on) {
                    yes(w.newFormat || w.oldO != null, "run " + t + ": on means something is stored to put back");
                }
            }
            // the end: the session ends, Shizuku is there, the saver is switched off
            w.shizuku = true;
            w.sessionEnd();
            w.setAuto(false);
            yes(!w.on, "run " + t + ": the saver is off at the end");
            eq(Arrays.toString(start), Arrays.toString(ph.profile()), "run " + t + ": the phone is exactly as it was");
            // and only known names with numbers were ever sent
            for (String line : ph.writes()) {
                yes(line.matches("(global|secure|system)\\.(wifi_scan_always_enabled|ble_scan_always_enabled|doze_always_on|aod_mode|screen_off_timeout)=(\\d{1,10}|DELETE)"),
                        "run " + t + ": only known settings and numbers were written: " + line);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        profile();
        reconcile();
        idleRules();
        reading();
        planning();
        storage();
        restoring();
        writingAndChecking();
        sentences();
        homeWords();
        current();
        realShellLines();
        stories();
        random();
        System.out.println(checks + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
