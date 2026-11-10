package com.tj.linkmeter;

/**
 * Pure Java (no Android classes), so it is tested on a computer: tools/test/ScreenPaceTest.java, and tools/mutate_link.py ("pace2:")
 * breaks each rule on purpose.
 *
 * How hard the tablet's Screen tab works while it is open. Left open on a phone whose screen went off long ago, the tab used to
 * keep the tablet's own screen on and bright for ever and to ask the phone about its screen every 3 seconds: the largest avoidable
 * drain on both devices, and a phone on Standby woken twelve hundred times an hour. Now the tab RESTS when there is nothing to
 * watch: it lets go of the hold-awake (the tablet may dim and sleep by its own settings) and asks every 15 seconds.
 *
 * It rests once the phone's screen has been off for two minutes, or once nobody has touched the tab for half an hour. A touch on
 * the tab, or the phone's screen coming on, ends the rest at once.
 */
final class ScreenPace {
    private ScreenPace() {}

    /** The phone's screen has been off this long, counted from the later of the moment it went off and the last activity. */
    static final long PHONE_OFF_MS = 2 * 60 * 1000L;
    /** Nobody has touched the tab, and the phone's screen has not come on, for this long. */
    static final long UNTOUCHED_MS = 30 * 60 * 1000L;
    static final long ACTIVE_POLL_MS = 3 * 1000L;
    static final long RESTING_POLL_MS = 15 * 1000L;

    /**
     * @param phoneScreenOn whether the tab believes the phone's screen is on
     * @param phoneOffSince when it was first seen off (only read while it is off)
     * @param lastActivity the later of the last touch of the tab and the moment the phone's screen last came on (or the tab was shown)
     */
    static boolean resting(boolean phoneScreenOn, long now, long phoneOffSince, long lastActivity) {
        if (now - lastActivity >= UNTOUCHED_MS) {
            return true;
        }
        // a touch while the phone's screen is off starts the two minutes again: somebody is at the tablet, perhaps to wake the phone
        return !phoneScreenOn && now - Math.max(phoneOffSince, lastActivity) >= PHONE_OFF_MS;
    }

    /** How long to wait before asking the phone about its screen again. */
    static long pollMs(boolean resting) {
        return resting ? RESTING_POLL_MS : ACTIVE_POLL_MS;
    }

    /** The tablet is held awake only while the tab is shown, the user asked for it, and the phone's screen is being watched. */
    static boolean holdAwake(boolean shown, boolean option, boolean resting) {
        return shown && option && !resting;
    }
}
