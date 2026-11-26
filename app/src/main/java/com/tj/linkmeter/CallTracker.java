package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;

/**
 * Phone only, but no Android in it (tested in tools/test/CallsLogicTest.java): turns what Android says about the phone ("it rings",
 * "it is off the hook", "it is idle", with the number when Android gives it) into the calls the tablet is shown. Android's phone
 * state is coarse: it does not say when the other side of an outgoing call answers, and with a call already on it does not say what
 * became of a call that was waiting. The tracker never claims more than it was told.
 */
final class CallTracker {
    static final String IDLE = "IDLE";
    static final String RINGING = "RINGING";
    static final String OFFHOOK = "OFFHOOK";

    /** A number noted for the next outgoing call is believed for this long. */
    static final long DIAL_MS = 20_000;

    private Calls.Call ringing;
    private Calls.Call active;
    private String pendingDial = "";
    private long pendingAt;
    private int missed;
    private int seq;

    private Calls.Call fresh(String state, boolean incoming, String number, long now) {
        Calls.Call c = new Calls.Call();
        c.id = "c" + (++seq) + "-" + now;
        c.state = state;
        c.incoming = incoming;
        c.number = number == null ? "" : number;
        c.since = now;
        return c;
    }

    /** The phone was told to dial this number (by the tablet): the next outgoing call is that one. */
    synchronized void noteDial(String number, long now) {
        pendingDial = number == null ? "" : number;
        pendingAt = now;
    }

    /**
     * Android's phone state changed. {@code number} is the other party's number when Android gave it, else "" or null.
     * Returns true when what the tablet sees changed.
     */
    synchronized boolean onState(String state, String number, long now) {
        String num = number == null ? "" : number.trim();
        boolean changed = false;
        if (RINGING.equals(state)) {
            if (ringing == null) {
                ringing = fresh(Calls.RINGING, true, num, now);
                changed = true;
            } else if (!num.isEmpty() && !num.equals(ringing.number)) {
                ringing.number = num;
                changed = true;
            }
        } else if (OFFHOOK.equals(state)) {
            if (ringing != null) {
                if (active == null) {
                    active = ringing;
                    active.state = Calls.ACTIVE;
                    active.answered = now;
                }
                // with a call already on, what became of the waiting one is not known: only the call that is on stays
                ringing = null;
                changed = true;
            } else if (active == null) {
                String n = !pendingDial.isEmpty() && now - pendingAt <= DIAL_MS && now >= pendingAt ? pendingDial : "";
                pendingDial = "";
                active = fresh(Calls.ACTIVE, false, n, now);
                changed = true;
            }
        } else if (IDLE.equals(state)) {
            if (ringing != null) {
                missed++;
                ringing = null;
                changed = true;
            }
            if (active != null) {
                active = null;
                changed = true;
            }
            pendingDial = "";
        }
        return changed;
    }

    /**
     * Android's phone state as it is RIGHT NOW (the phone was asked, not told by a broadcast). A broadcast can be lost or arrive late (the app's
     * process was frozen, Samsung's battery rules, a hang-up while the tablet was away), and a call that the tracker still holds after Android
     * said "idle" would stay on the tablet for ever. This brings the tracker to what Android says; nothing changes when both already agree.
     * Returns true when what the tablet sees changed.
     */
    synchronized boolean reconcile(String androidState, long now) {
        if (IDLE.equals(androidState)) {
            return ringing != null || active != null ? onState(IDLE, "", now) : false;
        }
        if (OFFHOOK.equals(androidState)) {
            return active != null && ringing == null ? false : onState(OFFHOOK, "", now);
        }
        if (RINGING.equals(androidState)) {
            return ringing != null ? false : onState(RINGING, "", now);
        }
        return false; // a state nobody knows: believe nothing
    }

    /** The calls now: a ringing one first, then the one that is on. */
    synchronized List<Calls.Call> calls() {
        List<Calls.Call> out = new ArrayList<>();
        if (ringing != null) {
            out.add(copy(ringing));
        }
        if (active != null) {
            out.add(copy(active));
        }
        return out;
    }

    /** Calls that rang and were not answered since the app started (the call log has the exact count; this is the fallback). */
    synchronized int missed() {
        return missed;
    }

    synchronized void resetMissed() {
        missed = 0;
    }

    private static Calls.Call copy(Calls.Call c) {
        Calls.Call k = new Calls.Call();
        k.id = c.id;
        k.state = c.state;
        k.number = c.number;
        k.name = c.name;
        k.incoming = c.incoming;
        k.since = c.since;
        k.answered = c.answered;
        return k;
    }
}
