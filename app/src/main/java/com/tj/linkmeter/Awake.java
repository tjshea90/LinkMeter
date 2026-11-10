package com.tj.linkmeter;

import android.content.Context;
import android.os.PowerManager;

/**
 * Phone only: keeps the processor awake for a short, bounded time while a request that reached a sleeping phone is carried out
 * (a request over Bluetooth, or an action that runs a moment after its answer). Every hold runs out by itself, so nothing here can
 * be left on by mistake. The wake locks are not counted: asking again only moves the end.
 */
final class Awake {
    private static PowerManager.WakeLock link;
    private static PowerManager.WakeLock work;

    private Awake() {}

    private static PowerManager.WakeLock make(Context c, String tag) {
        PowerManager pm = (PowerManager) c.getApplicationContext().getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock w = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag);
        w.setReferenceCounted(false);
        return w;
    }

    /** While a Bluetooth request is being answered. Released by {@link #linkDone} when the answer is out, or after ms at the latest. */
    static synchronized void linkBusy(Context c, long ms) {
        try {
            if (link == null) {
                link = make(c, "linkmeter:bt");
            }
            link.acquire(ms);
        } catch (RuntimeException ignored) {
            // no wake lock on this phone: the request still runs while the phone happens to be awake
        }
    }

    static synchronized void linkDone() {
        try {
            if (link != null && link.isHeld()) {
                link.release();
            }
        } catch (RuntimeException ignored) {
            // it had already run out
        }
    }

    /** For work that goes on after the answer was sent (an action that cuts the link). Runs out after ms. */
    static synchronized void work(Context c, long ms) {
        try {
            if (work == null) {
                work = make(c, "linkmeter:work");
            }
            work.acquire(ms);
        } catch (RuntimeException ignored) {
            // as above
        }
    }
}
