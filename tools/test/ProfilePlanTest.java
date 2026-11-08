package com.tj.linkmeter;

import java.util.Arrays;
import java.util.Random;

/** Tests ProfilePlan, the decisions behind the phone's remote-session settings. Not part of the app. */
public final class ProfilePlanTest {
    private static int checks;
    private static int fails;

    private ProfilePlanTest() {}

    private static void eq(Object want, Object got, String what) {
        checks++;
        String w = show(want);
        String g = show(got);
        if (!w.equals(g)) {
            fails++;
            System.out.println("FAIL " + what + ": wanted " + w + " but got " + g);
        }
    }

    private static String show(Object o) {
        if (o instanceof Object[]) {
            return Arrays.toString((Object[]) o);
        }
        if (o instanceof boolean[]) {
            return Arrays.toString((boolean[]) o);
        }
        return String.valueOf(o);
    }

    private static String[] s(String... v) {
        return v;
    }

    private static boolean[] b(boolean... v) {
        return v;
    }

    /** The phone and what a session saved, so a test can run a whole story. */
    private static final class World {
        String[] phone;
        boolean on;
        String[] orig = new String[3];
        boolean[] chg = new boolean[3];
        String[] appl = new String[3];

        World(String... phone) {
            this.phone = phone.clone();
        }

        ProfilePlan.Begin begin(long keep, int level, boolean ecoOn) {
            ProfilePlan.Begin p = ProfilePlan.begin(on, orig, chg, appl, phone, keep, level, ecoOn, "15000");
            for (int i = 0; i < 3; i++) {
                if (p.writes[i] != null) {
                    phone[i] = p.writes[i];
                }
            }
            orig = p.orig.clone();
            chg = p.chg.clone();
            for (int i = 0; i < 3; i++) {
                appl[i] = p.chg[i] ? phone[i] : "null";
            }
            on = p.any();
            return p;
        }

        String[] end(boolean ecoOn) {
            String[] w = ProfilePlan.end(chg, appl, orig, phone, ecoOn, "15000");
            for (int i = 0; i < 3; i++) {
                if (w[i] != null) {
                    phone[i] = w[i];
                }
            }
            on = false;
            chg = new boolean[3];
            return w;
        }
    }

    public static void main(String[] args) {
        // 1. a fresh start: raise the timeout, manual brightness, lowest
        World w = new World("30000", "1", "120");
        ProfilePlan.Begin p = w.begin(120000, 0, false);
        eq(s("120000", "0", "0"), p.writes, "fresh: writes");
        eq(s("30000", "1", "120"), p.orig, "fresh: originals");
        eq(b(true, true, true), p.chg, "fresh: managed");
        eq(s("120000", "0", "0"), w.phone, "fresh: the phone");

        // 2. the same options again (tab entered again): nothing to write
        p = w.begin(120000, 0, false);
        eq(s(null, null, null), p.writes, "again: writes");
        eq(b(true, true, true), p.chg, "again: still managed");
        eq(s("30000", "1", "120"), p.orig, "again: originals kept");

        // 3. the brightness option changes to very low
        p = w.begin(120000, 13, false);
        eq(s(null, null, "13"), p.writes, "option change: only brightness");
        eq("13", w.phone[2], "option change: the phone");

        // 4. "don't change brightness" while a session runs: brightness and mode go back, the timeout stays
        p = w.begin(120000, -1, false);
        eq(s(null, "1", "120"), p.writes, "drop dim: writes");
        eq(b(true, false, false), p.chg, "drop dim: what is still managed");
        eq(s("120000", "1", "120"), w.phone, "drop dim: the phone");
        String[] out = w.end(false);
        eq(s("30000", null, null), out, "end after drop dim");
        eq(s("30000", "1", "120"), w.phone, "end after drop dim: the phone is as it was");

        // 5. the user changes brightness by hand during the session: their choice is kept
        w = new World("30000", "1", "120");
        w.begin(120000, 0, false);
        w.phone[2] = "90"; // the user moved the slider
        p = w.begin(120000, 0, false);
        eq(s(null, null, null), p.writes, "user change: nothing overwritten");
        eq(b(true, true, false), p.chg, "user change: brightness no longer managed");
        out = w.end(false);
        eq(s("30000", "1", null), out, "user change: end leaves their brightness");
        eq(s("30000", "1", "90"), w.phone, "user change: the phone");

        // 6. the battery saver (15 s timeout) is on when the session ends: the timeout goes back to its value
        w = new World("15000", "1", "120"); // the saver had already shortened it
        w.begin(120000, 0, true);
        eq("120000", w.phone[0], "saver on at start: raised");
        w.end(true);
        eq("15000", w.phone[0], "saver on at the end: back to the saver's timeout");
        // ... and when the saver came on during the session (so the saved original is the user's own 30 s)
        w = new World("30000", "1", "120");
        w.begin(120000, 0, false);
        w.end(true);
        eq("15000", w.phone[0], "saver came on during the session: the saver's timeout wins");

        // 7. nothing needed: a long timeout already, brightness untouched
        w = new World("300000", "1", "120");
        p = w.begin(120000, -1, false);
        eq(false, p.any(), "nothing needed: not a session");
        eq(s(null, null, null), p.writes, "nothing needed: no writes");

        // 8. brightness mode already manual: mode is not touched, brightness is
        w = new World("30000", "0", "120");
        p = w.begin(120000, 0, false);
        eq(s("120000", null, "0"), p.writes, "manual mode: mode left alone");
        eq(b(true, false, true), p.chg, "manual mode: managed");
        out = w.end(false);
        eq(s("30000", null, "120"), out, "manual mode: end");

        // 9. settings that were not set at all are deleted again at the end
        w = new World("null", "null", "null");
        w.begin(120000, 0, false);
        eq(s("120000", "0", "0"), w.phone, "unset: raised");
        out = w.end(false);
        eq(s("null", "null", "null"), out, "unset: written back as 'null' (the caller deletes them)");

        // 10. keep=0: the timeout is never touched
        w = new World("30000", "1", "120");
        p = w.begin(0, 13, false);
        eq(s(null, "0", "13"), p.writes, "keep off: only brightness");

        // 11. random stories: any begin, option changes, then end must give the phone back exactly as it was
        String[] ts = {"null", "15000", "30000", "60000", "300000"};
        String[] ms = {"null", "0", "1"};
        String[] bs = {"null", "0", "50", "255"};
        long[] keeps = {0, 60000, 120000, 600000};
        int[] levels = {-1, 0, 13, 38};
        Random r = new Random(11);
        for (int t = 0; t < 5000; t++) {
            World x = new World(ts[r.nextInt(ts.length)], ms[r.nextInt(ms.length)], bs[r.nextInt(bs.length)]);
            String[] before = x.phone.clone();
            int steps = 1 + r.nextInt(4);
            for (int i = 0; i < steps; i++) {
                x.begin(keeps[r.nextInt(keeps.length)], levels[r.nextInt(levels.length)], false);
            }
            x.end(false);
            eq(Arrays.toString(before), Arrays.toString(x.phone), "round trip " + t);
        }
        System.out.println(checks + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
