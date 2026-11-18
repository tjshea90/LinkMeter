package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/PocketRulesTest.java). "Pocket return" (the user, 2026-11-18, for the Launcher app on the SAME
 * tablet; docs/LAUNCHER_BRIDGE.md): when the Launcher returns to its custom home it asks this app to put the linked PHONE to rest, using only
 * actions the phone already knows (/act names), in this order:
 *
 * <pre>
 *   1. the phone's Wi-Fi off                           (wifi_off)
 *   2. LinkMeter's battery saver on                    (eco_auto_on)
 *   3. the deep sleep: hotspot off, location off, airplane mode on (only while Bluetooth stays up), screen off   (hotspot_off_deep)
 *   4. wait until the phone says it has finished       (status: hotspot off, airplane on, screen off)
 *   5. Android's own Battery saver on, AFTER the hotspot is down (SystemSaver puts it back if the hotspot dies with it)   (eco_android_on)
 *   6. the screen off once more, checked, if it is on  (screen_off)
 * </pre>
 *
 * The tablet's own Wi-Fi may go off as soon as step 3's order is ACCEPTED ({@link Sink#sent}): the hotspot is the way to the phone until then,
 * Bluetooth carries steps 4-6. Rules that matter:
 *  - a phone that cannot be reached at all (nothing was sent) ends the run at once: every later step would fail the same way, slowly;
 *  - one step that fails never stops the next (each is reported), except that step 4 waits only for an accepted order;
 *  - "done" is said only for what the phone's STATUS showed or what it answered, never for an order that merely went out;
 *  - nothing here ever switches anything on except the two savers.
 */
final class PocketRules {
    private PocketRules() {}

    enum State { IDLE, RUNNING, SENT, DONE, FAILED }

    static final String WIFI_OFF = "wifi_off";
    static final String SAVER = "eco_auto_on";
    static final String SLEEP = "hotspot_off_deep";
    static final String SYSTEM_SAVER = "eco_android_on";
    static final String SCREEN_OFF = "screen_off";

    /** How long an action may take (the hotspot route; the Bluetooth route gives actions at least a minute by itself, see Reach). */
    static final int WIFI_MS = 25_000, SAVER_MS = 40_000, SLEEP_MS = 60_000, SYSTEM_MS = 40_000, SCREEN_MS = 30_000, STATUS_MS = 8_000;
    /** The phone's sleep (hotspot, location, airplane mode with its Bluetooth checks, screen) takes up to about 50 s; this is how long the tablet waits for it. */
    static final long FOLLOW_MS = 100_000L;
    /** Between two looks at the phone's status, and before the first one (the phone starts the sleep 0.8 s after it answered). */
    static final long LOOK_EVERY_MS = 3_000L, FIRST_LOOK_MS = 4_000L;

    /** One answer of the phone to an action. */
    static final class Reply {
        /** False: the phone could not be reached at all, so nothing was sent (text says why). */
        final boolean reached;
        /** The phone did it (a "Sent" counts as ok, see later). */
        final boolean ok;
        /** The phone answered BEFORE acting, because the action takes away the link it came over: only "sent". */
        final boolean later;
        final String text;

        Reply(boolean reached, boolean ok, boolean later, String text) {
            this.reached = reached;
            this.ok = ok;
            this.later = later;
            this.text = text == null ? "" : text;
        }

        static Reply done(String text) { return new Reply(true, true, false, text); }

        static Reply sent(String text) { return new Reply(true, true, true, text); }

        static Reply failed(String text) { return new Reply(true, false, false, text); }

        static Reply unreachable(String text) { return new Reply(false, false, false, text); }
    }

    /** What the phone's status says (null = it did not say). */
    static final class Status {
        final Boolean hotspot, airplane, screen;
        final String deepSleep;

        Status(Boolean hotspot, Boolean airplane, Boolean screen, String deepSleep) {
            this.hotspot = hotspot;
            this.airplane = airplane;
            this.screen = screen;
            this.deepSleep = deepSleep == null ? "" : deepSleep;
        }

        /** The end state of the deep sleep, as the phone reports it: hotspot off, airplane mode on, screen off. */
        boolean asleep() {
            return Boolean.FALSE.equals(hotspot) && Boolean.TRUE.equals(airplane) && Boolean.FALSE.equals(screen);
        }
    }

    interface Phone {
        /** One /act action. Never throws. */
        Reply act(String name, int readMs);

        /** The phone's status; null when it does not answer. Never throws. */
        Status status();

        long now();

        void pause(long ms);
    }

    interface Sink {
        /** The state changed (RUNNING at the start, SENT when the sleep order was accepted, DONE or FAILED at the end). */
        void state(State s, String text);

        /** The sleep order was accepted: the tablet's Wi-Fi may go off (called at most once, before the wait). */
        void sent();
    }

    static final class Out {
        final State state;
        final String text;

        Out(State state, String text) {
            this.state = state;
            this.text = text;
        }
    }

    private static String dot(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) {
            return "";
        }
        char last = t.charAt(t.length() - 1);
        return last == '.' || last == '!' || last == '?' ? t : t + ".";
    }

    private static void add(StringBuilder sb, String sentence) {
        String t = dot(sentence);
        if (t.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(t);
    }

    /** The step's sentence: what the phone said when it did it, or "label: why not". */
    private static String said(String label, Reply r) {
        if (r.ok) {
            return r.text.isEmpty() ? label + " done." : r.text;
        }
        return label + ": " + (r.text.isEmpty() ? "the phone did not do it" : r.text);
    }

    /** Runs the six steps against a phone. Synchronous; the caller gives it a thread. Never throws. */
    static Out run(Phone p, Sink sink) {
        StringBuilder text = new StringBuilder();
        boolean allGood = true;
        sink.state(State.RUNNING, "");

        Reply wifi = p.act(WIFI_OFF, WIFI_MS);
        if (!wifi.reached) {
            return end(sink, State.FAILED, "The phone could not be reached, so nothing was changed on it: " + dot(wifi.text));
        }
        add(text, said("The phone's Wi-Fi", wifi));
        allGood &= wifi.ok;

        Reply saver = p.act(SAVER, SAVER_MS);
        if (!saver.reached) {
            return end(sink, State.FAILED, text + " " + "The phone stopped answering before its saver and sleep: " + dot(saver.text));
        }
        add(text, said("LinkMeter's saver", saver));
        allGood &= saver.ok;

        Status before = p.status();
        String deepBefore = before == null ? "" : before.deepSleep;
        Reply sleep = p.act(SLEEP, SLEEP_MS);
        if (!sleep.reached) {
            return end(sink, State.FAILED, text + " " + "The phone stopped answering before it could be put to sleep: " + dot(sleep.text));
        }
        boolean accepted = sleep.ok;
        if (accepted) {
            sink.sent();
            sink.state(State.SENT, text.toString());
        }
        Status last = null;
        boolean asleep = false;
        if (!accepted) {
            add(text, said("Putting the phone to sleep", sleep));
            allGood = false;
        } else if (!sleep.later) {
            // answered AFTER acting (the Bluetooth route): the words are the phone's own result
            add(text, sleep.text.isEmpty() ? "The phone was put to sleep." : sleep.text);
            last = p.status();
            asleep = last != null && last.asleep();
            if (!asleep) {
                allGood = false;
                add(text, "The phone's status does not yet show hotspot off, airplane mode on and the screen off.");
            }
        } else {
            long start = p.now();
            p.pause(FIRST_LOOK_MS);
            while (true) {
                last = p.status();
                if (last != null && (last.asleep() || (!last.deepSleep.isEmpty() && !last.deepSleep.equals(deepBefore)))) {
                    break; // the end state, or the phone's own words for how the sleep ended
                }
                if (p.now() - start >= FOLLOW_MS) {
                    break;
                }
                p.pause(LOOK_EVERY_MS);
            }
            asleep = last != null && last.asleep();
            String words = last == null ? "" : last.deepSleep;
            if (asleep) {
                add(text, words.isEmpty() ? "The phone is asleep: hotspot off, airplane mode on, screen off." : words);
            } else if (last == null) {
                allGood = false;
                add(text, "The phone's sleep was ordered but its status could not be read afterwards (the Bluetooth link may be down).");
            } else {
                allGood = false;
                add(text, words.isEmpty() ? "The phone had not finished going to sleep after " + (FOLLOW_MS / 1000) + " seconds." : words);
            }
        }

        // Android's own Battery saver goes on only now: with the hotspot still up it could take the hotspot down with it
        Reply system = p.act(SYSTEM_SAVER, SYSTEM_MS);
        if (system.reached) {
            add(text, said("Android's Battery saver", system));
            allGood &= system.ok;
        } else {
            add(text, "Android's Battery saver could not be asked: " + dot(system.text));
            allGood = false;
        }

        // the screen last, as the user listed it: only if it is on (or not known)
        Status now = p.status();
        boolean screenOn = now == null || !Boolean.FALSE.equals(now.screen);
        if (screenOn) {
            Reply screen = p.act(SCREEN_OFF, SCREEN_MS);
            if (screen.reached) {
                add(text, screen.ok ? "The phone's screen is off." : said("The phone's screen", screen));
                allGood &= screen.ok;
            } else {
                add(text, "The phone's screen could not be switched off: " + dot(screen.text));
                allGood = false;
            }
        }
        return end(sink, allGood ? State.DONE : State.FAILED, text.toString());
    }

    private static Out end(Sink sink, State s, String text) {
        sink.state(s, text);
        return new Out(s, text);
    }

    // ------------------------------------------------------------------------------------------------ the bridge's answers

    /** A JSON-safe copy of a sentence (the bridge answers with plain strings, not JSON, but the Launcher stores them in a line). */
    static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c == '\n' || c == '\r' || c == '\t' ? ' ' : c);
        }
        String t = b.toString().trim();
        return t.length() > 900 ? t.substring(0, 900) : t;
    }
}
