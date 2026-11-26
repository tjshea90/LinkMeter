package com.tj.linkmeter;

/**
 * The floating phone's decisions, pure (tested offline in tools/test/FloatingLogicTest.java; docs/FLOATING_PHONE.md): when it shows, what
 * its green and red buttons do, what its lines say, and where it may sit on the screen. The screen glue ({@code MiniPhoneView},
 * {@code FloatingPhoneService}) only draws what is decided here. The call itself is always placed, answered and ended by the phone through
 * the same /call/* routes as the Calls tab; this class never reaches the phone.
 */
final class FloatRules {
    private FloatRules() {}

    /** How long the widget stays after a call ended, so the length of the call can be read. */
    static final long LINGER_MS = 6_000;

    /** What the widget has to decide from. */
    static final class In {
        /** "Pop up automatically" (Settings). */
        boolean enabled;
        /** Android lets this app draw over other apps. */
        boolean canDraw;
        /** The user asked to see the phone now (Show phone, or the pad is open): it stays until closed. */
        boolean pinned;
        /** The dial pad is open. */
        boolean padOpen;
        /** LinkMeter's own screens are in front: the strip at the top of every tab and the Calls tab already show the call, so the card keeps out of the way. */
        boolean appInFront;
        /** The state of the call the screen is about: "", {@link Calls#RINGING}, {@link Calls#DIALING}, {@link Calls#ACTIVE} or {@link Calls#HELD}. */
        String call = "";
        /** When the last call ended, on the tablet's elapsed clock (0 = none ended). */
        long endedAt;
        long now;
    }

    /**
     * Does the widget show? Never without Android's permission; never over LinkMeter's own screens unless the user asked for it (they show
     * the call themselves); a call shows it when the automatic pop-up is on; the user's own request keeps it.
     */
    static boolean visible(In in) {
        if (!in.canDraw) {
            return false;
        }
        if (in.appInFront && !in.pinned) {
            return false;
        }
        boolean call = in.call != null && !in.call.isEmpty();
        if (call && (in.enabled || in.pinned)) {
            return true;
        }
        if (in.pinned || in.padOpen) {
            return true;
        }
        return in.enabled && in.endedAt > 0 && in.now - in.endedAt >= 0 && in.now - in.endedAt < LINGER_MS;
    }

    /**
     * Does the small texting card show? Only while the user has it open, Android lets this app draw, and LinkMeter's own screens are not in
     * front (the Texts tab is the whole thing there; the card comes back when the user leaves the app).
     */
    static boolean textVisible(boolean canDraw, boolean appInFront, boolean open) {
        return canDraw && open && !appInFront;
    }

    // ------------------------------------------------------------------ the two big buttons

    /** What the green button does. */
    enum Green { ANSWER, DIAL, OPEN_PAD, ASK_HEADSET, NONE }

    /**
     * @param call the call's state ("" when there is none)
     * @param typed the digits on the pad
     * @param headsetOn the headset is connected to the phone
     * @param askFirst the user's setting "Ask before calling without the headset"
     * @param armed the user already saw the warning and pressed again
     */
    static Green green(String call, boolean padOpen, String typed, boolean headsetOn, boolean askFirst, boolean armed) {
        if (Calls.RINGING.equals(call)) {
            return Green.ANSWER;
        }
        if (call != null && !call.isEmpty()) {
            return Green.NONE; // a call is on: there is nothing to dial
        }
        if (typed == null || typed.isEmpty()) {
            return padOpen ? Green.NONE : Green.OPEN_PAD;
        }
        if (askFirst && !headsetOn && !armed) {
            return Green.ASK_HEADSET;
        }
        return Green.DIAL;
    }

    static String greenLabel(Green g) {
        switch (g) {
            case ANSWER:
                return "Answer";
            case DIAL:
                return "Call";
            case OPEN_PAD:
                return "Open the dial pad";
            case ASK_HEADSET:
                return "Call anyway";
            default:
                return "Call";
        }
    }

    /** The red button works when a call rings, dials or is on. */
    static boolean redOn(String call) {
        return call != null && !call.isEmpty();
    }

    static String redLabel(String call) {
        return Calls.RINGING.equals(call) ? "Decline" : "End call";
    }

    // ------------------------------------------------------------------ the words

    /** The line under "Phone" when there is no call: what the tablet knows about the phone. */
    static String idleLine(int watch) {
        switch (watch) {
            case Calls.WATCH_OK:
                return "Ready";
            case Calls.WATCH_OFF:
                return "Calls are off on the phone";
            case Calls.WATCH_OLD:
                return "Update LinkMeter on the phone";
            case Calls.WATCH_NO_PEER:
                return "Not paired with a phone";
            case Calls.WATCH_UNREACHED:
                return "Can't reach the phone";
            default:
                return "Looking for the phone…";
        }
    }

    /** The line after a call ended: "Call ended · 3:12" (the length when the phone said it was answered, else only "Call ended"). */
    static String endedLine(long answeredMs) {
        return answeredMs > 0 ? "Call ended · " + CallRules.timer(answeredMs) : "Call ended";
    }

    /** The warning that stands in for a call that would play on the phone, not on the headset. */
    static String headsetWarning(String headsetName) {
        String n = headsetName == null || headsetName.trim().isEmpty() ? "Your headset" : headsetName.trim();
        return n + " is not connected to the phone. Press again to call on the phone, or tap the Bluetooth button.";
    }

    /** What the digits are cleaned of before the pad shows them: only what the keypad can type. */
    static String clean(String typed) {
        if (typed == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < typed.length() && sb.length() < CallRules.MAX_DIGITS + 4; i++) {
            char c = typed.charAt(i);
            if ((c >= '0' && c <= '9') || c == '*' || c == '#' || c == '+') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ where it may sit

    /** The nearest place for the widget's top-left corner that keeps all of it on the screen, with {@code margin} spare when the screen allows. */
    static int[] clamp(int x, int y, int w, int h, int screenW, int screenH, int margin) {
        int maxX = Math.max(0, screenW - w - margin);
        int maxY = Math.max(0, screenH - h - margin);
        int minX = Math.min(margin, maxX);
        int minY = Math.min(margin, maxY);
        return new int[] {Math.max(minX, Math.min(maxX, x)), Math.max(minY, Math.min(maxY, y))};
    }

    /** After a drag: slides to the nearer side edge so the widget does not cover the middle of the screen (the vertical place stays). */
    static int[] snap(int x, int y, int w, int h, int screenW, int screenH, int margin) {
        int[] c = clamp(x, y, w, h, screenW, screenH, margin);
        int left = margin;
        int right = Math.max(left, screenW - w - margin);
        c[0] = (c[0] + w / 2 < screenW / 2) ? left : right;
        return c;
    }
}
