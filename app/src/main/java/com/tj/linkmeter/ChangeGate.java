package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/SmsV2Test.java). A counter that goes up whenever something changes, and a way to wait for
 * it: how a request can hold on until there is news (long polling) instead of being asked again every few seconds.
 */
final class ChangeGate {
    private long version = 1;

    synchronized long version() {
        return version;
    }

    /** Something changed: everybody who waits is woken. */
    synchronized void bump() {
        version++;
        notifyAll();
    }

    /** Waits until the version is no longer {@code seen} or {@code ms} milliseconds have passed; returns the version then. */
    synchronized long await(long seen, long ms) throws InterruptedException {
        long end = System.nanoTime() + Math.max(0, ms) * 1_000_000L;
        while (version == seen) {
            long left = (end - System.nanoTime()) / 1_000_000L;
            if (left <= 0) {
                break;
            }
            wait(left);
        }
        return version;
    }
}
