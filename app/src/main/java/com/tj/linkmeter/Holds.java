package com.tj.linkmeter;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * No Android in here (tested in tools/test/HoldsTest.java). Who is keeping the phone awake, and until when.
 *
 * Several things can need the processor at the same time (two Bluetooth connections, a connection that was only just accepted,
 * an action that runs a moment after its answer), each for a bounded time. Android's wake lock is one switch with one timer. Used
 * bare, "the last one to ask sets the end" and "the first one to finish switches it off" both let the phone fall asleep under
 * somebody who is still working. This keeps the list: the switch must stay on until the LATEST end among those still holding,
 * and goes off when the last of them is done. Every hold has an end, so nothing here can keep the phone awake for ever.
 */
final class Holds {
    /** Each holder and the moment its hold runs out. */
    private final Map<Object, Long> ends = new HashMap<>();

    /**
     * who starts to hold for ms from now, or moves the end of the hold it already has (a holder has one hold).
     * Returns how long the switch must be on, counted from now: the longest time anybody still needs, never less than 1.
     */
    synchronized long take(Object who, long now, long ms) {
        ends.put(who, now + Math.max(1, ms));
        return left(now);
    }

    /**
     * who is done (nothing happens if it held nothing). Returns how long the switch must still be on for the others, counted
     * from now; 0 means nobody holds it any more: switch it off.
     */
    synchronized long drop(Object who, long now) {
        ends.remove(who);
        return left(now);
    }

    /** The longest time anybody still needs. Holders whose time is up are forgotten on the way (they never said they were done). */
    private long left(long now) {
        long longest = 0;
        for (Iterator<Long> it = ends.values().iterator(); it.hasNext();) {
            long end = it.next();
            if (end <= now) {
                it.remove();
            } else {
                longest = Math.max(longest, end - now);
            }
        }
        return longest;
    }

    /** How many are holding right now (for the test: the list must not grow with holders that never said they were done). */
    synchronized int holders(long now) {
        left(now);
        return ends.size();
    }
}
