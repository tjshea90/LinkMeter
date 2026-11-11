package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/TextsLogicTest.java). When the Texts tab must ask for the tablet's screen lock: when the
 * lock is switched on and the tablet has a screen lock, the first time, and again after the tab (or the app) was left for longer than
 * a short while (so that a glance at another tab does not ask every time).
 */
final class TextsLock {
    /** How long the tab may be away before it asks again. */
    static final long GRACE_MS = 2 * 60_000L;

    private long unlockedAt;
    private long leftAt;

    /** Must the user unlock before the texts are shown? Call when the tab is about to be shown. */
    synchronized boolean needsUnlock(boolean lockOn, boolean deviceSecure, long now) {
        if (!lockOn || !deviceSecure) {
            return false;
        }
        if (unlockedAt > 0 && (leftAt == 0 || now - leftAt <= GRACE_MS)) {
            leftAt = 0; // back within the short while: still unlocked
            return false;
        }
        unlockedAt = 0;
        leftAt = 0;
        return true;
    }

    /** The user unlocked. */
    synchronized void unlocked(long now) {
        unlockedAt = now;
        leftAt = 0;
    }

    /** The tab left the screen (another tab, or the app went to the background). */
    synchronized void left(long now) {
        if (unlockedAt > 0 && leftAt == 0) {
            leftAt = now;
        }
    }

    /** Locks again at once. */
    synchronized void relock() {
        unlockedAt = 0;
        leftAt = 0;
    }
}
