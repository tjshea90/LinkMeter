package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/SearchFollowTest.java). What became of the radio restart ("Make the phone search again")?
 *
 * The restart takes the phone's hotspot, and with it the way the tablet asked, away for some seconds, so the phone answers
 * "Sent" first and records how the restart went ("running", "done" or "failed: why"). The tablet asks radio_cycle_state every
 * {@link #EVERY_MS} until this class says how it ended. A question that gets no answer is what the restart looks like from
 * here, so it is not held against the phone; only a restart that never says it is over is given up after {@link #GIVE_UP_MS}.
 * One object follows one restart.
 */
final class SearchFollow {
    static final long EVERY_MS = 3_000L;
    /** The radio is off for about four seconds and the hotspot needs up to a minute to be back: two minutes is long enough. */
    static final long GIVE_UP_MS = 120_000L;
    /** "none" this many times in a row after the restart was started: the phone has no record of it (its app restarted). */
    static final int NONE_AFTER = 3;

    /** The last word. ok false: it failed, or nobody can tell. */
    static final class Outcome {
        final boolean ok;
        final String text;

        Outcome(boolean ok, String text) {
            this.ok = ok;
            this.text = text;
        }
    }

    private int none;

    /**
     * One look. answered false: the question got no answer (the hotspot is down, as expected). Returns null while the restart is
     * still going on.
     */
    Outcome look(boolean answered, String text) {
        if (!answered) {
            return null;
        }
        String t = text == null ? "" : text.trim();
        if (t.startsWith("done")) {
            return new Outcome(true, "The phone searched for a network again.");
        }
        if (t.startsWith("failed")) {
            String why = t.length() > "failed:".length() ? t.substring("failed:".length()).trim() : "";
            return new Outcome(false, "The phone could not restart its radio" + (why.isEmpty() ? "." : ": " + why + "."));
        }
        if (t.contains("unknown action")) {
            // a phone app from before this question restarted its radio while answering the request: it is over
            return new Outcome(true, "The phone searched for a network again.");
        }
        if (t.startsWith("none") && ++none >= NONE_AFTER) {
            return new Outcome(false, "The phone has no record of the restart. Scan to see where it is now.");
        }
        if (!t.startsWith("none")) {
            none = 0;
        }
        return null;
    }

    /** The time is over and the phone never said that the restart ended. */
    Outcome giveUp() {
        return new Outcome(false, "The phone did not say that the restart ended. Scan to see where it is now.");
    }
}
