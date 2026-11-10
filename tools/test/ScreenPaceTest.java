package com.tj.linkmeter;

/** Tests ScreenPace, the rule for when the Screen tab rests. Not part of the app. */
public final class ScreenPaceTest {
    private static int checks;
    private static int fails;

    private ScreenPaceTest() {}

    private static void yes(boolean ok, String what) {
        checks++;
        if (!ok) {
            fails++;
            System.out.println("FAIL " + what);
        }
    }

    private static void eq(Object want, Object got, String what) {
        checks++;
        if (!String.valueOf(want).equals(String.valueOf(got))) {
            fails++;
            System.out.println("FAIL " + what + ": wanted " + want + " but got " + got);
        }
    }

    private static final long MIN = 60 * 1000L;
    private static final long NOW = 100 * 60 * MIN; // an arbitrary clock

    /** The same rule, written the other way round, to compare with. */
    private static boolean oracle(boolean phoneOn, long sinceOff, long sinceActivity) {
        long quiet = Math.min(sinceOff, sinceActivity); // time since the later of the two
        boolean untouched = sinceActivity >= 30 * MIN;
        boolean offLong = !phoneOn && quiet >= 2 * MIN;
        return untouched || offLong;
    }

    public static void main(String[] args) {
        eq(120000L, ScreenPace.PHONE_OFF_MS, "two minutes");
        eq(1800000L, ScreenPace.UNTOUCHED_MS, "half an hour");
        eq(3000L, ScreenPace.pollMs(false), "awake: every 3 s");
        eq(15000L, ScreenPace.pollMs(true), "resting: every 15 s");

        long[] since = {0, 1, MIN, 2 * MIN - 1, 2 * MIN, 2 * MIN + 1, 5 * MIN, 29 * MIN, 30 * MIN - 1, 30 * MIN, 31 * MIN, 600 * MIN};
        for (boolean on : new boolean[] {true, false}) {
            for (long off : since) {
                for (long act : since) {
                    boolean got = ScreenPace.resting(on, NOW, NOW - off, NOW - act);
                    eq(oracle(on, off, act), got, "resting phoneOn=" + on + " offFor=" + off / 1000 + "s activity=" + act / 1000 + "s ago");
                }
            }
        }

        // the cases the brief names
        yes(!ScreenPace.resting(true, NOW, 0, NOW - 29 * MIN), "the phone's screen is on and the tab was touched 29 minutes ago: not resting");
        yes(ScreenPace.resting(true, NOW, 0, NOW - 30 * MIN), "nobody touched the tab for 30 minutes: resting, although the phone's screen is on");
        yes(!ScreenPace.resting(false, NOW, NOW - 119 * 1000L, NOW - 119 * 1000L), "the phone's screen went off 119 s ago: not yet");
        yes(ScreenPace.resting(false, NOW, NOW - 2 * MIN, NOW - 2 * MIN), "the phone's screen went off 2 minutes ago, nobody touched: resting");

        // a touch takes the rest back at once, and the two minutes start again
        long off = NOW - 10 * MIN;
        yes(ScreenPace.resting(false, NOW, off, NOW - 10 * MIN), "setup: phone off for ten minutes, no touch: resting");
        yes(!ScreenPace.resting(false, NOW, off, NOW), "a touch ends the rest at once");
        yes(!ScreenPace.resting(false, NOW + 119 * 1000L, off, NOW), "...and for 119 more seconds");
        yes(ScreenPace.resting(false, NOW + 2 * MIN, off, NOW), "...then the phone's screen has been off long enough again");

        // the phone's screen coming on takes the rest back (the glue counts it as activity)
        yes(ScreenPace.resting(true, NOW, 0, NOW - 40 * MIN), "setup: untouched for 40 minutes: resting");
        yes(!ScreenPace.resting(true, NOW, 0, NOW), "the phone's screen came on: not resting");

        // a tab touched every 29 minutes is never at rest while the phone's screen is on
        boolean ever = false;
        for (long t = 0; t < 600 * MIN; t += MIN) {
            ever |= ScreenPace.resting(true, NOW + t, 0, NOW + t - (t % (29 * MIN)));
        }
        yes(!ever, "touched every 29 minutes with the phone on: never resting");

        // what rests: the hold-awake
        for (int m = 0; m < 8; m++) {
            boolean shown = (m & 1) != 0;
            boolean option = (m & 2) != 0;
            boolean resting = (m & 4) != 0;
            eq(shown && option && !resting, ScreenPace.holdAwake(shown, option, resting), "holdAwake shown=" + shown + " option=" + option + " resting=" + resting);
        }
        yes(!ScreenPace.holdAwake(true, true, true), "a resting tab lets the tablet sleep");
        yes(ScreenPace.holdAwake(true, true, false), "a tab in use keeps it awake");
        yes(!ScreenPace.holdAwake(false, true, false), "a tab that is not shown never holds the tablet awake");
        yes(!ScreenPace.holdAwake(true, false, false), "the option off: never");

        System.out.println(checks + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
