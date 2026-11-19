package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * "One connection at a time" for the headset (docs/FLOATING_PHONE.md): connecting the headset to the tablet first disconnects it from the
 * phone, and connecting it to the phone first disconnects it from the tablet, so a headset that keeps only one connection never ends up
 * half on each. Pure (tested in tools/test/FloatingLogicTest.java): what to do in which order for what is known, when to stop, and what to
 * say. The Android part that carries the steps out is {@link HeadsetHandOver}. A headset that can keep two connections ("multipoint") is
 * the user's choice ({@code Cfg.headsetBoth}); then nothing is disconnected.
 */
final class HandOverRules {
    private HandOverRules() {}

    /** Where the headset is wanted. */
    enum Target { PHONE, TABLET }

    /** One thing to do. */
    enum Step { DISCONNECT_PHONE, DISCONNECT_TABLET, CONNECT_PHONE, CONNECT_TABLET }

    static final class Plan {
        final List<Step> steps;
        /** Not "" when nothing may be done now; a sentence for the person. */
        final String refusal;

        Plan(List<Step> steps, String refusal) {
            this.steps = Collections.unmodifiableList(steps);
            this.refusal = refusal;
        }

        boolean refused() {
            return !refusal.isEmpty();
        }
    }

    private static Plan steps(Step... s) {
        List<Step> l = new ArrayList<>();
        Collections.addAll(l, s);
        return new Plan(l, "");
    }

    /** The pause between a disconnect and the connect after it, so the headset can take the new link. */
    static final long SETTLE_MS = 1500;

    /**
     * @param phoneOn the headset is connected to the phone (as the phone says)
     * @param tabletOn it is connected to this tablet (as the tablet's own list says)
     * @param callOn a call is on: its sound is on the headset that is connected to the phone
     * @param keepBoth the user's headset can stay connected to both
     */
    static Plan plan(Target target, boolean phoneOn, boolean tabletOn, boolean callOn, boolean keepBoth) {
        if (target == Target.TABLET) {
            if (tabletOn) {
                return steps(); // already there
            }
            if (phoneOn && !keepBoth) {
                if (callOn) {
                    return new Plan(new ArrayList<>(), "A call is on and its sound is on the headset. End the call first, then connect the headset to "
                            + "this tablet (the call would lose its sound).");
                }
                return steps(Step.DISCONNECT_PHONE, Step.CONNECT_TABLET);
            }
            return steps(Step.CONNECT_TABLET);
        }
        if (phoneOn) {
            return steps();
        }
        if (tabletOn && !keepBoth) {
            return steps(Step.DISCONNECT_TABLET, Step.CONNECT_PHONE);
        }
        return steps(Step.CONNECT_PHONE);
    }

    /** What a step that did not go well means for the steps after it. */
    enum Then {
        /** Go on with the next step. */
        CONTINUE,
        /** Stop here and say why. */
        STOP
    }

    /**
     * A phone that cannot be reached for the disconnect says nothing about the headset: the connect to the tablet goes on (the headset may
     * not be on the phone at all, or it leaves by itself), and the words say the phone was not asked. A phone that answers "no" stops it.
     */
    static Then afterPhoneDisconnect(boolean reached, boolean ok) {
        return !reached || ok ? Then.CONTINUE : Then.STOP;
    }

    /**
     * The tablet's own disconnect must be SEEN to have worked ({@link TabletHeadsetRules.Way#DONE}) before the phone connects: a list that was only
     * opened for the user's tap ({@code ASKED}) or a refusal stops it, because the headset is still on the tablet.
     */
    static Then afterTabletDisconnect(TabletHeadsetRules.Way way) {
        return way == TabletHeadsetRules.Way.DONE ? Then.CONTINUE : Then.STOP;
    }

    // ------------------------------------------------------------------ the words

    private static String who(String name) {
        String n = name == null ? "" : name.trim();
        return n.isEmpty() ? "The headset" : n;
    }

    static String working(Step step, String name) {
        String n = name == null || name.trim().isEmpty() ? "the headset" : name.trim();
        switch (step) {
            case DISCONNECT_PHONE:
                return "Disconnecting " + n + " from the phone…";
            case DISCONNECT_TABLET:
                return "Disconnecting " + n + " from this tablet…";
            case CONNECT_PHONE:
                return "Connecting " + n + " to the phone… (up to 25 seconds)";
            default:
                return "Connecting " + n + " to this tablet…";
        }
    }

    /** Said when the phone could not be asked to let go but the connect to the tablet went on. */
    static String phoneNotAsked(String name) {
        return " The phone could not be reached to let " + (name == null || name.trim().isEmpty() ? "the headset" : name.trim()) + " go first.";
    }

    /** Said when the tablet's disconnect only opened Android's list: the connect to the phone did not happen. */
    static String tabletStillHolds(TabletHeadsetRules.Way way, String name) {
        String n = who(name);
        if (way == TabletHeadsetRules.Way.ASKED) {
            return n + " is still connected to this tablet. Disconnect it in the list that opened, then press the Phone button again.";
        }
        return n + " is still connected to this tablet, so it was not connected to the phone. Turn the headset off and on, then try again.";
    }

    /** Said when the phone refused to let go: nothing was connected to the tablet. */
    static String phoneStillHolds(String why, String name) {
        String n = who(name);
        return n + " is still connected to the phone, so it was not connected to this tablet" + (why == null || why.isEmpty() ? "." : " (" + why + ").");
    }
}
