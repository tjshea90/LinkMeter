package com.tj.linkmeter;

/**
 * Phone side: the steps for putting the phone to sleep or waking it, kept free of Android so they can be tested on
 * a computer with a simulated phone (tools/test/PowerSequenceTest.java).
 *
 * Why it needs care: a key press that exits cleanly can still do nothing, some phones are slow to follow, and the
 * power key TOGGLES, so pressing it at the wrong moment wakes a phone that was only slow to fall asleep. So: the
 * exact methods first, each one followed by a wait and a look at whether the phone followed; the power key last,
 * and only if the phone is still wrong a second after everything else was given time. After success, a short
 * wait to see that the phone stays that way (something may wake it again, and then the user should be told).
 * Even so a toggle cannot be made fully safe: starting the shell command takes a while, so a phone that follows
 * the previous method between the last look and the key press has it undone. The waits cover phones that follow a
 * key within about two seconds; a slower phone is reported in the trace ("power key"), not protected.
 */
final class PowerSequence {
    private PowerSequence() {}

    /** The phone, as far as the steps need it. */
    interface Phone {
        /** PowerManager.isInteractive(): the device is awake (false while asleep, and while dozing with the Always-On Display). */
        boolean awake();

        /** The display is in its doze state (the Always-On Display is showing). */
        boolean dozing();

        /** Presses a key through the shell; "done", or what went wrong. */
        String pressKey(int keyCode);

        /** Asks the power service to put the device to sleep (noDoze: skip the Always-On Display); "OK" or "ERR ...". */
        String sleepService(boolean noDoze);

        void pause(long ms) throws InterruptedException;

        long now();
    }

    static final class Result {
        /** The phone is now in the state that was asked for, and stayed so for a moment. */
        boolean reached;
        /** What was tried, in a few words, for the tablet's Information box. */
        String how;
        long ms;
    }

    static final int KEY_WAKEUP = 224;
    static final int KEY_SLEEP = 223;
    static final int KEY_POWER = 26;

    /** Waits up to ms for the phone to become (or stop being) awake. */
    private static boolean await(Phone p, boolean wantOn, long ms) throws InterruptedException {
        long end = p.now() + ms;
        while (p.awake() != wantOn && p.now() < end) {
            p.pause(50);
        }
        return p.awake() == wantOn;
    }

    static Result run(Phone p, boolean wantOn, boolean noDoze) throws InterruptedException {
        long t0 = p.now();
        StringBuilder how = new StringBuilder();
        boolean reached = p.awake() == wantOn;
        if (wantOn) {
            if (!reached) {
                how.append("wake key ").append(p.pressKey(KEY_WAKEUP));
                reached = await(p, true, 1500);
            }
        } else {
            // asleep with the Always-On Display showing is "reached" for the phone, but looks like a phone that stayed on
            if (!reached || (noDoze && p.dozing())) {
                String res = p.sleepService(noDoze);
                how.append("power service ").append(res.length() > 80 ? res.substring(0, 80) : res);
                reached = await(p, false, 1500);
            }
            if (!reached) {
                how.append("; sleep key ").append(p.pressKey(KEY_SLEEP));
                reached = await(p, false, 1500);
            }
        }
        if (!reached) {
            p.pause(1000); // a slow phone may still be on its way: look once more before pressing a toggle
            reached = p.awake() == wantOn;
            if (!reached) {
                how.append("; power key ").append(p.pressKey(KEY_POWER));
                reached = await(p, wantOn, 1500);
            }
        }
        if (reached) {
            p.pause(600); // does it stay that way?
            if (p.awake() != wantOn) {
                reached = false;
                how.append("; but it ").append(wantOn ? "went back to sleep" : "woke up again").append(" by itself");
            }
        }
        Result r = new Result();
        r.reached = reached;
        r.ms = p.now() - t0;
        r.how = (how.length() == 0 ? "already so" : how.toString()) + " (" + r.ms + " ms)";
        return r;
    }
}
