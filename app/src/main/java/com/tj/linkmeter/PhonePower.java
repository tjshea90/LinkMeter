package com.tj.linkmeter;

/**
 * Tablet side: what the Sleep / Wake button and the phone's own screen state say about each other, kept in one
 * small class with no Android in it, so the rules can be tested on a computer (tools/test/PhonePowerTest.java).
 *
 * The trouble it solves: the button flips the state at once (the user wants to see it react), the phone needs a
 * second or two to follow, and an answer about the phone's state that was asked just before the press can still
 * arrive after it, with the old answer, and flip the button back (so Sleep looked as if it had done nothing).
 * So answers about the phone's state are not believed while a press is being carried out, and for a few seconds
 * after it. A press the phone says did not work flips the state back; if the press did work but its reply was
 * lost, the first answer after the quiet time puts things right.
 */
final class PhonePower {
    enum Change { NONE, WENT_OFF, WOKE }

    /** After a press (or its answer), an answer about the phone's state is not believed for this long. */
    static final long SETTLE_MS = 3000;

    /** A press that has had no answer for this long is given up on, so a lost callback cannot leave the button dead. */
    static final long BUSY_MAX_MS = 60000;

    private boolean on = true;
    private boolean busy;
    private boolean want = true;
    private long busySince;
    private long settledFrom = Long.MIN_VALUE / 2;

    private boolean isBusy(long now) {
        if (busy && now - busySince >= BUSY_MAX_MS) {
            busy = false;
        }
        return busy;
    }

    /** Whether the phone's screen is thought to be on. */
    boolean on() {
        return on;
    }

    /** A press is being carried out. */
    boolean busy() {
        return busy;
    }

    /** What the press in progress asks for: true = wake, false = sleep. */
    boolean wanted() {
        return want;
    }

    /** The button was pressed. False (and nothing changes) if the last press is still being carried out. */
    boolean press(long now) {
        if (isBusy(now)) {
            return false;
        }
        busy = true;
        busySince = now;
        want = !on;
        on = want; // shown at once; the phone confirms or the press fails
        settledFrom = now + SETTLE_MS;
        return true;
    }

    /** The picture of a sleeping phone was tapped: a press that only ever wakes. False if the phone is on or busy. */
    boolean wake(long now) {
        return !on && press(now);
    }

    /** The phone confirmed that its screen is now phoneOn. Says what the screen should now show. */
    Change confirmed(boolean phoneOn, long now) {
        busy = false;
        on = phoneOn;
        settledFrom = now + SETTLE_MS;
        return phoneOn ? Change.WOKE : Change.WENT_OFF;
    }

    /** The press could not be carried out (the phone did not follow, or could not be reached): the old state again. */
    void failed(long now) {
        busy = false;
        on = !want;
        settledFrom = now + SETTLE_MS;
    }

    /**
     * An answer about the phone's state arrived (from the timer, or the pictures). Returns what changed. Nothing
     * is believed while a press is busy or just done: the answer may have been asked before it.
     */
    Change poll(boolean phoneOn, long now) {
        if (isBusy(now) || now < settledFrom || phoneOn == on) {
            return Change.NONE;
        }
        on = phoneOn;
        return on ? Change.WOKE : Change.WENT_OFF;
    }

    /** The phone itself said, just now, that its screen is off (the video request was refused): believed at once. */
    void forceOff() {
        if (!busy) {
            on = false; // (a press in progress knows better, and its own answer comes soon)
        }
    }
}
