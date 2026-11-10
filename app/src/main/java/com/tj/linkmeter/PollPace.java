package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/PollPaceTest.java). How long the Home tab waits before it asks the phone for its
 * status again.
 *
 * Over the hotspot a question costs next to nothing (the hotspot is awake anyway), so the tablet asks as often as the user
 * chose. Over Bluetooth every question wakes a phone that may be on Standby, which exists so that the phone can idle for days:
 * there the tablet asks every 15 s only during the first minute after the user did something on the screen, then once a
 * minute, and after about five minutes it stops and the screen offers "Ask again". A phone that does not answer is asked
 * less and less often on either route.
 *
 * The minute and the five minutes are time spent asking over BLUETOOTH with nobody at the screen. Time spent on the hotspot
 * is not part of them: a screen that has been open for an hour and loses the hotspot for one question starts its five
 * minutes then, and does not stop on the spot (it would never see the hotspot come back).
 */
final class PollPace {
    private PollPace() {}

    /** Do not ask again by itself. */
    static final long STOP = -1L;

    static final long BT_FRESH_MS = 15_000L;
    static final long BT_FRESH_FOR_MS = 60_000L;
    static final long BT_SLOW_MS = 60_000L;
    static final long BT_STOP_AFTER_MS = 5 * 60_000L;

    /**
     * The wait before the next question, in ms, or {@link #STOP}.
     * baseMs: the interval chosen in Settings. bluetooth: the last question went out over Bluetooth (answered or not).
     * sinceActionMs: how long ago the user last did something on this screen (opened it, pressed a button).
     * onBluetoothMs: for how long the questions have been going out over Bluetooth without a break (0 for the first one
     * after the hotspot, or after the screen was opened).
     * misses: how many questions in a row got no status. following: a switch was pressed and answered "sent", and the screen
     * is looking for what became of it (FollowUp): every 3 s on either route, for the short time that lasts.
     */
    static long next(long baseMs, boolean bluetooth, long sinceActionMs, long onBluetoothMs, int misses, boolean following) {
        if (following) {
            return FollowUp.EVERY_MS;
        }
        if (bluetooth) {
            // the shorter of the two: a press starts the count again, and so does Bluetooth coming into use
            long quiet = Math.min(sinceActionMs, onBluetoothMs);
            if (quiet >= BT_STOP_AFTER_MS) {
                return STOP;
            }
            long step = quiet < BT_FRESH_FOR_MS ? BT_FRESH_MS : BT_SLOW_MS;
            if (misses == 1) {
                step = Math.max(step, 30_000L);
            } else if (misses >= 2) {
                step = BT_SLOW_MS;
            }
            return Math.max(baseMs, step);
        }
        if (misses >= 10) {
            return Math.max(baseMs, 60_000L);
        }
        if (misses >= 6) {
            return Math.max(baseMs, 30_000L);
        }
        return misses >= 3 ? Math.max(baseMs, 15_000L) : baseMs;
    }
}
