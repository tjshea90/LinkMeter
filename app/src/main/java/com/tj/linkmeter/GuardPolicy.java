package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/GuardPolicyTest.java). The decisions of the phone's safety net ({@link Guard}): when to
 * undo what LinkMeter changed because the phone's mobile data has stopped working.
 *
 * The promise this class keeps: whatever LinkMeter left changed on the phone (network types, a band lock or a 5G leg request that
 * a version up to v0.2.102 left, a radio that was switched off for a moment, airplane mode it started) is put back by the phone ITSELF, with no tablet
 * and no internet, if mobile data is down for long enough. The rules:
 *  - it only ever undoes something when LinkMeter really has something changed ({@code pending});
 *  - the first ten minutes after a change are watched closely: two minutes without mobile data is enough;
 *  - after that the watch goes on, slower, for as long as something is still changed: four minutes without mobile data;
 *  - the time without data must be CONTINUOUS: one good reading starts the count again, and so does a reading that says nothing
 *    (the user switched mobile data off, airplane mode, no answer from Android): LinkMeter never fights the user;
 *  - after an undo that did not clear everything (Shizuku is off, for example) it waits five minutes before trying again, so it
 *    can never hammer the phone.
 */
final class GuardPolicy {
    /** What the phone's mobile data looks like right now. UNKNOWN is "cannot tell, or the user chose this": never counts as bad. */
    enum Data { GOOD, BAD, UNKNOWN }

    enum Act { WATCH, RESTORE, STOP }

    static final long FAST_WINDOW_MS = 10L * 60 * 1000;
    static final long FAST_BAD_MS = 2L * 60 * 1000;
    static final long SLOW_BAD_MS = 4L * 60 * 1000;
    static final long FAST_STEP_MS = 20L * 1000;
    static final long SLOW_STEP_MS = 60L * 1000;
    static final long RETRY_AFTER_RESTORE_MS = 5L * 60 * 1000;

    static final class Decision {
        final Act act;
        /** When to look again (WATCH and RESTORE); 0 for STOP. */
        final long nextMs;

        Decision(Act act, long nextMs) {
            this.act = act;
            this.nextMs = nextMs;
        }
    }

    private long armedAt = Long.MIN_VALUE;
    private long badSince = Long.MIN_VALUE;
    private long lastRestore = Long.MIN_VALUE;

    /** A change was just made (or the service has just started with something still changed): watch closely from now. */
    void arm(long now) {
        armedAt = now;
        badSince = Long.MIN_VALUE;
    }

    boolean armed() {
        return armedAt != Long.MIN_VALUE;
    }

    /** One look at the phone. pending: LinkMeter still has something changed. */
    Decision step(long now, boolean pending, Data data) {
        if (!pending) {
            badSince = Long.MIN_VALUE;
            return new Decision(Act.STOP, 0);
        }
        boolean fast = armedAt != Long.MIN_VALUE && now - armedAt < FAST_WINDOW_MS;
        long next = fast ? FAST_STEP_MS : SLOW_STEP_MS;
        if (data != Data.BAD) {
            badSince = Long.MIN_VALUE;
            return new Decision(Act.WATCH, next);
        }
        if (badSince == Long.MIN_VALUE) {
            badSince = now;
            return new Decision(Act.WATCH, next);
        }
        long limit = fast ? FAST_BAD_MS : SLOW_BAD_MS;
        if (now - badSince >= limit) {
            if (lastRestore != Long.MIN_VALUE && now - lastRestore < RETRY_AFTER_RESTORE_MS) {
                return new Decision(Act.WATCH, next); // it was tried a moment ago: give it time
            }
            lastRestore = now;
            badSince = Long.MIN_VALUE;
            return new Decision(Act.RESTORE, next);
        }
        return new Decision(Act.WATCH, next);
    }
}
