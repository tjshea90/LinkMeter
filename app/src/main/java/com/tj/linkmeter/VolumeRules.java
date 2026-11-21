package com.tj.linkmeter;

/**
 * The headset's volume buttons, pure (tested offline in tools/test/VolumeRulesTest.java; docs/PHONE_CALLS.md, "Headset volume"). No
 * Android and no network in here: what the tablet's two screens (the Phone tab and the floating phone) may offer, what the phone
 * decides before it moves a volume, and how it reads the answer back.
 *
 * <p>The promises, each one a test: the volume buttons only ever mean the HEADSET (when no headset is connected, or the sound is not
 * going there, nothing moves, so the phone's own speaker cannot suddenly get louder); one press is one step (a request for more than
 * {@link #MAX_STEP} is refused, so nothing can jump the sound to full loudness in somebody's ear); a change is believed only when
 * the phone reads the new volume back, and Android's own refusal to go louder (it may ask on the phone's screen) is said and not
 * hidden.
 */
final class VolumeRules {
    private VolumeRules() {}

    /** The most steps one request may move a volume. The buttons ask for 1. */
    static final int MAX_STEP = 3;

    // ---- the sentences (one place, so a screen, the phone and the tests say the same thing)
    static final String NO_HEADSET = "No headset is connected to the phone.";
    static final String OLD_PHONE = "The phone's LinkMeter is too old for volume. Phone tab > Update the phone.";
    static final String UNREACHED = "Can't reach the phone.";
    static final String UNKNOWN = "The phone did not say this volume.";
    static final String CALL_OFF = "The call volume can be changed while a call is on.";
    static final String MEDIA_ELSEWHERE = "The phone is not playing media to the headset (it may be connected for calls only).";
    static final String CALL_ELSEWHERE = "This call's sound is not on the headset. Move it to the headset first.";
    /** The phone's own sentence: it is asked for a step with no call or with the call's sound elsewhere. */
    static final String CALL_NOT_HEADSET = "The call volume only moves while a call's sound is on the headset.";
    static final String LOUDEST = "The volume is already as loud as it goes.";
    static final String QUIETEST = "The volume is already as quiet as it goes.";
    static final String WHICH = "Which volume: media or call?";
    static final String STEPS = "One press moves the volume by 1 to " + MAX_STEP + " steps.";

    static boolean validStream(String stream) {
        return Calls.STREAM_MEDIA.equals(stream) || Calls.STREAM_CALL.equals(stream);
    }

    /** The volume the small buttons move first: the call's while a call is on, else the media's. */
    static String defaultStream(boolean callOn) {
        return callOn ? Calls.STREAM_CALL : Calls.STREAM_MEDIA;
    }

    /** The other one (the floating phone's chip switches between the two). */
    static String other(String stream) {
        return Calls.STREAM_CALL.equals(stream) ? Calls.STREAM_MEDIA : Calls.STREAM_CALL;
    }

    /** What the person reads: "Media volume" or "Call volume". */
    static String name(String stream) {
        return Calls.STREAM_CALL.equals(stream) ? "Call volume" : "Media volume";
    }

    /** The short word for a chip: "Media" or "Call". */
    static String shortName(String stream) {
        return Calls.STREAM_CALL.equals(stream) ? "Call" : "Media";
    }

    static Calls.Level level(Calls.Volume v, String stream) {
        return Calls.STREAM_CALL.equals(stream) ? v.call : v.media;
    }

    /** Does this volume mean the headset right now (the sound of this kind goes there)? */
    static boolean onHeadset(Calls.Volume v, String stream) {
        return Calls.STREAM_CALL.equals(stream) ? v.callOnHeadset : v.mediaOnHeadset;
    }

    /**
     * Does a call's sound go to the headset? Only while a call is on and a headset is connected, and where Android does not say where the
     * sound goes ({@link Calls#ROUTE_UNKNOWN}) a connected headset is believed: one step of the wrong speaker is a small price, and the
     * route is often not reported while the headset is the one carrying the call. A call that is on the phone's ear or speaker is not.
     */
    static boolean callOnHeadset(boolean callOn, boolean headsetConnected, String route) {
        return callOn && headsetConnected && (Calls.ROUTE_HEADSET.equals(route) || Calls.ROUTE_UNKNOWN.equals(route));
    }

    /** "7 of 15", or a dash when the volume is not known. */
    static String words(Calls.Level l) {
        return l != null && l.known() ? l.now + " of " + l.max : "–";
    }

    /** What one row of the volume screens shows and allows. */
    static final class Row {
        /** The buttons may be pressed. */
        boolean enabled;
        boolean canLower;
        boolean canRaise;
        /** "7 of 15" or "–". */
        String words = "–";
        /** The sentence to read when the row is not enabled; "" when it is. */
        String why = "";
        /** The bar's steps (max - min) and how many are filled (now - min); 0 and 0 when the volume is not known. */
        int steps;
        int filled;
    }

    /**
     * @param reachable the tablet hears the phone (the live picture is fresh)
     * @param headset a headset is connected to the phone
     * @param callOn a call is ringing, dialing, on or held
     */
    static Row row(Calls.Volume v, String stream, boolean reachable, boolean headset, boolean callOn) {
        Row r = new Row();
        Calls.Level l = level(v, stream);
        if (l.known()) {
            r.words = words(l);
            r.steps = l.max - l.min;
            r.filled = l.now - l.min;
        }
        if (!reachable) {
            r.why = UNREACHED;
        } else if (v.old) {
            r.why = OLD_PHONE;
        } else if (!headset) {
            r.why = NO_HEADSET;
        } else if (!l.known()) {
            r.why = UNKNOWN;
        } else if (Calls.STREAM_CALL.equals(stream) && !callOn) {
            r.why = CALL_OFF;
        } else if (!onHeadset(v, stream)) {
            r.why = Calls.STREAM_CALL.equals(stream) ? CALL_ELSEWHERE : MEDIA_ELSEWHERE;
        } else {
            r.enabled = true;
            r.canLower = l.now > l.min;
            r.canRaise = l.now < l.max;
        }
        return r;
    }

    /** What the phone decided before it touches anything. */
    static final class Plan {
        final boolean ok;
        /** The sentence for the person when the answer is no. */
        final String why;
        /** Where the volume was, and where it is to go (when ok). */
        final int before;
        final int target;
        final int by;

        private Plan(boolean ok, String why, int before, int target, int by) {
            this.ok = ok;
            this.why = why;
            this.before = before;
            this.target = target;
            this.by = by;
        }

        static Plan no(String why) {
            return new Plan(false, why, 0, 0, 0);
        }

        static Plan go(int before, int target, int by) {
            return new Plan(true, "", before, target, by);
        }
    }

    /**
     * The phone's decision for "move this volume by this many steps". The same refusals as {@link #row}, because the phone must not take
     * the tablet's word for what the sound is doing.
     */
    static Plan plan(String stream, int by, Calls.Volume v, boolean headset) {
        if (!validStream(stream)) {
            return Plan.no(WHICH);
        }
        if (by == 0 || Math.abs(by) > MAX_STEP) {
            return Plan.no(STEPS);
        }
        if (!headset) {
            return Plan.no(NO_HEADSET + " The volume was left alone.");
        }
        Calls.Level l = level(v, stream);
        if (!l.known()) {
            return Plan.no(UNKNOWN);
        }
        if (!onHeadset(v, stream)) {
            return Plan.no(Calls.STREAM_CALL.equals(stream) ? CALL_NOT_HEADSET : MEDIA_ELSEWHERE);
        }
        int target = Math.max(l.min, Math.min(l.max, l.now + by));
        if (target == l.now) {
            return Plan.no(by > 0 ? LOUDEST : QUIETEST);
        }
        return Plan.go(l.now, target, by);
    }

    /**
     * What the phone says after it moved the volume and read it back. Only a volume that reads back as asked is "ok"; Android refusing to
     * go louder (it may ask on the phone's own screen) is told as that.
     */
    static Calls.Result verify(String stream, Plan plan, Calls.Level after) {
        if (after == null || !after.known()) {
            return Calls.Result.fail("The phone did not say what the " + name(stream).toLowerCase(java.util.Locale.US) + " is now.");
        }
        if (after.now == plan.target) {
            return Calls.Result.ok(name(stream) + " " + words(after) + ".");
        }
        if (after.now == plan.before) {
            return Calls.Result.fail(plan.by > 0
                    ? "Android did not make it louder (" + words(after) + "). The phone may be asking on its own screen to allow louder sound: look at it."
                    : "Android did not make it quieter (" + words(after) + ").");
        }
        return Calls.Result.fail(name(stream) + " went to " + words(after) + ", not " + plan.target + ".");
    }
}
