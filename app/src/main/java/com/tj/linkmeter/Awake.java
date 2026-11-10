package com.tj.linkmeter;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Phone only: keeps the processor awake for a short, bounded time while a request that reached a sleeping phone is carried out
 * (a request over Bluetooth, or an action that runs a moment after its answer). Every hold runs out by itself, so nothing here can
 * be left on by mistake.
 *
 * Android's wake lock is one switch with one timer, and several connections or actions can need it at once. {@link Holds} keeps
 * the list of who needs it until when, so one of them finishing (or a short hold arriving) cannot switch the processor off under
 * another that is still working. "who" is any object that stands for the holder: a connection, a running action.
 */
final class Awake {
    private static PowerManager.WakeLock link;
    private static PowerManager.WakeLock work;
    private static final Holds LINK = new Holds();
    private static final Holds WORK = new Holds();

    private Awake() {}

    private static PowerManager.WakeLock make(Context c, String tag) {
        PowerManager pm = (PowerManager) c.getApplicationContext().getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock w = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag);
        w.setReferenceCounted(false); // the counting is done in Holds; here every acquire(ms) simply sets the end anew
        return w;
    }

    /** Sets the lock to what the list of holders now needs: on for ms more, or off when ms is 0. */
    private static void set(PowerManager.WakeLock w, long ms) {
        try {
            if (ms > 0) {
                w.acquire(ms);
            } else if (w.isHeld()) {
                w.release();
            }
        } catch (RuntimeException ignored) {
            // no wake lock on this phone, or it had already run out: the work still runs while the phone happens to be awake
        }
    }

    /**
     * A Bluetooth connection needs the processor: from the moment it is accepted until its greeting is out, and while one of its
     * requests is being answered. Ended by {@link #linkDone} for the same connection, or after ms at the latest.
     */
    static synchronized void linkBusy(Context c, Object who, long ms) {
        try {
            if (link == null) {
                link = make(c, "linkmeter:bt");
            }
        } catch (RuntimeException e) {
            return; // no wake lock on this phone
        }
        set(link, LINK.take(who, SystemClock.elapsedRealtime(), ms));
    }

    static synchronized void linkDone(Object who) {
        if (link != null) {
            set(link, LINK.drop(who, SystemClock.elapsedRealtime()));
        }
    }

    /** For work that goes on after the answer was sent (an action that cuts the link). Ended by {@link #workDone}, or after ms. */
    static synchronized void work(Context c, Object who, long ms) {
        try {
            if (work == null) {
                work = make(c, "linkmeter:work");
            }
        } catch (RuntimeException e) {
            return; // as above
        }
        set(work, WORK.take(who, SystemClock.elapsedRealtime(), ms));
    }

    static synchronized void workDone(Object who) {
        if (work != null) {
            set(work, WORK.drop(who, SystemClock.elapsedRealtime()));
        }
    }
}
