package com.tj.linkmeter;

import java.io.IOException;

/**
 * Pure Java (no Android classes), so it is tested on a computer: tools/test.sh (SystemSaverTest), and tools/mutate_link.py
 * ("syssaver:") breaks each rule on purpose to see that the test notices.
 *
 * Android's OWN Battery saver (Samsung calls it "Power saving"), as an option of LinkMeter's saver. LinkMeter's saver (SaverPlan,
 * Eco) is a profile of its own: it never touched Android's Battery saver, and the tablet said "Battery saver: on" while the
 * phone's own Battery saver was off (the user's report of 2026-11-14: "I pressed automatic ... but battery saver was not turned
 * on"). This class is the optional way to ask for the real one, and the words that keep the two apart.
 *
 * It is OFF unless the user asks (Cfg.ecoSystem): nothing is known about what Android's Battery saver does to the hotspot or to
 * the phone's wake from Standby on this phone. So it is believed only when Android itself says it is on afterwards (the setting
 * alone is not enough), it is put back at once when the hotspot went away with it, the setting's original value is stored before
 * anything is written, and it is only put back when LinkMeter switched it on (somebody else's Battery saver is left alone).
 *
 * The whole sequence is here (apply, restore), written against {@link Env}; Eco gives it the real phone and the test a fake one.
 * One rule runs through all of it, as in SaverPlan: only digits or the word null are ever handed to the shell, and only for the
 * one name in this file.
 */
final class SystemSaver {
    private SystemSaver() {}

    static final String NS = "global";
    static final String KEY = "low_power";
    static final String TARGET = "1";

    /** The tablet's names for the option (the action names of /act). */
    static final String ACTION_ON = "eco_android_on";
    static final String ACTION_OFF = "eco_android_off";

    /** After the write, how long to wait for Android itself to say its Battery saver is on (it follows the setting a moment later). */
    static final long SETTLE_MS = 3000;
    /** After Android says it is on, how long to wait before looking at the hotspot again (a hotspot that goes, goes at once). */
    static final long HOTSPOT_LOOK_MS = 2000;

    // ------------------------------------------------------------------------------------------ reading and writing

    /** The shell line that prints the setting as L=<value>. */
    static String readCommand() {
        return "echo L=$(settings get " + NS + " " + KEY + "); true";
    }

    /** The shell line that switches it on and prints what the phone then says for it. */
    static String writeCommand() {
        return "settings put " + NS + " " + KEY + " " + TARGET + "; echo L=$(settings get " + NS + " " + KEY + "); true";
    }

    /** The value the shell printed, or null when no usable line came back: only a number or the word null is believed. Later lines win. */
    static String parseRead(String out) {
        if (out == null) {
            return null;
        }
        String v = null;
        for (String line : out.split("\n")) {
            String l = line.trim();
            if (l.startsWith("L=")) {
                v = l.substring(2).trim();
            }
        }
        return SaverPlan.valueOk(v) ? v : null;
    }

    /** The shell line that puts the setting back; null when the stored original cannot be trusted (it never reaches the shell). */
    static String restoreCommand(String original) {
        if (!SaverPlan.valueOk(original)) {
            return null;
        }
        return original.equals("null") ? "settings delete " + NS + " " + KEY + "; true" : "settings put " + NS + " " + KEY + " " + original + "; true";
    }

    // ------------------------------------------------------------------------------------------ the decisions

    enum Step { ALREADY_ON, APPLY, UNKNOWN }

    static final class Plan {
        final Step step;
        /** The setting's value before (digits or "null"): only for APPLY. */
        final String original;

        Plan(Step step, String original) {
            this.step = step;
            this.original = original;
        }
    }

    /**
     * What a switch-on does. Android's own saver already on (the user's, or a schedule's) is left as it is and is never switched off
     * later; a setting the phone did not read back as a number or null is not touched.
     *
     * @param read what `settings get` printed (null = no usable answer)
     * @param osOn what Android itself says right now (true, false, or null = this phone will not say)
     */
    static Plan plan(String read, Boolean osOn) {
        if (Boolean.TRUE.equals(osOn)) {
            return new Plan(Step.ALREADY_ON, null);
        }
        if (!SaverPlan.valueOk(read)) {
            return new Plan(Step.UNKNOWN, null);
        }
        return new Plan(Step.APPLY, read);
    }

    enum Result { ON, NOT_TAKEN, STAYED_OFF }

    /**
     * Did the switch-on work? Two things must be true: the phone reads the setting back as 1, and Android itself says its Battery
     * saver is on (a phone that will not say is not believed).
     */
    static Result check(String writeOut, Boolean osOnAfter) {
        if (!TARGET.equals(parseRead(writeOut))) {
            return Result.NOT_TAKEN;
        }
        return Boolean.TRUE.equals(osOnAfter) ? Result.ON : Result.STAYED_OFF;
    }

    /** The hotspot was on before and is off now. "Not known" on either side is never taken as lost. */
    static boolean hotspotLost(Boolean before, Boolean after) {
        return Boolean.TRUE.equals(before) && Boolean.FALSE.equals(after);
    }

    // ------------------------------------------------------------------------------------------ the sequence

    /** What the sequence needs from the phone. Eco gives the real ones, the test fake ones. */
    interface Env {
        /** Runs one line as the shell user and returns what it printed; throws only when the line could not run at all (Shizuku is down). */
        String shell(String cmd, int seconds) throws IOException;

        /** What Android itself says: true while its Battery saver is on, null when this phone will not say. */
        Boolean osOn();

        /** Waits until osOn() is wantOn or ms have passed, and returns the last answer. */
        Boolean waitOs(boolean wantOn, long ms);

        /** Is the phone's hotspot on: true, false, or null when not known. */
        Boolean hotspot();

        void pause(long ms);

        /** The original that was stored when LinkMeter switched it on (digits or "null"), or null when LinkMeter has not. */
        String stored();

        /** Stores the original before anything is written; false = it could not be saved. */
        boolean store(String original);

        /** Forgets the stored original. */
        void forget();
    }

    static final class Outcome {
        /** Android's own Battery saver is as asked (on after apply, back after restore), or there was nothing to do. */
        final boolean ok;
        /** The option must be switched off: this phone loses its hotspot with Android's Battery saver on. */
        final boolean giveUp;
        /** What the user reads ("" = nothing to say). */
        final String text;

        Outcome(boolean ok, boolean giveUp, String text) {
            this.ok = ok;
            this.giveUp = giveUp;
            this.text = text;
        }
    }

    /**
     * Switches Android's own Battery saver on and checks it. Order that matters: what is there is read first, the original is stored
     * before anything is written, and the answer is believed only after Android itself says it is on and the hotspot is still up.
     * Anything else puts the setting back. Throws only when a shell line could not run (the stored original then stays, so a later
     * restore still puts it back).
     */
    static Outcome apply(Env env) throws IOException {
        if (env.stored() == null) {
            Plan p = plan(parseRead(env.shell(readCommand(), 15)), env.osOn());
            if (p.step == Step.ALREADY_ON) {
                return new Outcome(true, false, WAS_ON);
            }
            if (p.step == Step.UNKNOWN) {
                return new Outcome(false, false, NOT_READ);
            }
            if (!env.store(p.original)) {
                return new Outcome(false, false, NOT_SAVED);
            }
        } else if (Boolean.TRUE.equals(env.osOn())) {
            return new Outcome(true, false, ON); // LinkMeter switched it on before, and it is still on
        }
        // (else: LinkMeter switched it on before and it went off again: ask again, the original stays as it was stored)
        Boolean hotspotBefore = env.hotspot();
        String out;
        try {
            out = env.shell(writeCommand(), 20);
        } catch (IOException e) {
            try {
                restore(env); // the write may have gone through: put it back, whatever the line said
            } catch (IOException ignored) {
                // the stored original stays: Restore everything tries again
            }
            throw e;
        }
        Result r = check(out, env.waitOs(true, SETTLE_MS));
        boolean lost = false;
        if (r == Result.ON) {
            env.pause(HOTSPOT_LOOK_MS);
            lost = hotspotLost(hotspotBefore, env.hotspot());
            if (!lost) {
                return new Outcome(true, false, ON);
            }
        }
        String why = lost ? HOTSPOT_LOST : r == Result.NOT_TAKEN ? NOT_TAKEN : STAYED_OFF;
        try {
            Outcome back = restore(env);
            if (!back.ok) {
                why += " " + back.text;
            }
        } catch (IOException e) {
            why += " " + NOT_PUT_BACK;
        }
        return new Outcome(false, lost, why);
    }

    /**
     * Puts the setting back, but only when LinkMeter switched it on. The stored original is forgotten only after the line ran: when
     * Shizuku is down this throws and the original stays for the next try.
     */
    static Outcome restore(Env env) throws IOException {
        String original = env.stored();
        if (original == null) {
            return new Outcome(true, false, "");
        }
        String cmd = restoreCommand(original);
        if (cmd == null) {
            env.forget();
            return new Outcome(true, false, UNUSABLE);
        }
        env.shell(cmd, 20);
        Boolean os = env.waitOs(false, SETTLE_MS);
        env.forget();
        return Boolean.TRUE.equals(os) ? new Outcome(false, false, BACK_BUT_ON) : new Outcome(true, false, BACK);
    }

    // ------------------------------------------------------------------------------------------ the words the user reads

    static final String ON = "Android's own Battery saver is on.";
    static final String WAS_ON = "Android's own Battery saver was already on, so LinkMeter leaves it as it is and does not switch it off later.";
    static final String NOT_READ = "Android's own Battery saver was not switched on, because the phone did not say what it is now.";
    static final String NOT_SAVED = "Android's own Battery saver was not switched on, because LinkMeter could not save the phone's setting first.";
    static final String NOT_TAKEN = "Android's own Battery saver was not switched on: the phone did not take the switch.";
    static final String STAYED_OFF = "Android's own Battery saver was not switched on: the phone took the switch but still says it is off. "
            + "LinkMeter put its switch back.";
    static final String HOTSPOT_LOST = "Android's own Battery saver was switched on, but the hotspot went off with it. LinkMeter put it back "
            + "and will not switch it on again. Hotspot on, from the tablet, brings the hotspot back.";
    static final String BACK = "Android's own Battery saver is off again.";
    static final String BACK_BUT_ON = "LinkMeter put its switch back, but the phone still says Android's own Battery saver is on. "
            + "Switch it off in the phone's quick settings.";
    static final String NOT_PUT_BACK = "LinkMeter could not put its switch back yet. Switching the saver off or Restore everything tries again.";
    static final String UNUSABLE = "The saved value for Android's own Battery saver could not be used, so it is left as it is.";
    /** For an answer to the option's own switch. */
    static final String STORED_WAITING = "Stored. Android's own Battery saver switches on with LinkMeter's saver.";
    static final String STORED_SAVER_OFF = "Stored. Android's own Battery saver switches on with LinkMeter's saver, which is off now. Choose Automatic.";
    static final String STORED_NEEDS_SHIZUKU = "Stored. Android's own Battery saver switches on as soon as Shizuku runs.";
    static final String STORED_LEFT_ALONE = "Stored. LinkMeter leaves Android's own Battery saver alone.";
    static final String STORED_BACK_WAITING = "Stored. Android's own Battery saver goes back off as soon as Shizuku runs.";

    enum Tone { GOOD, PLAIN, WARN, DIM }

    static final class Line {
        final String text;
        final Tone tone;

        Line(String text, Tone tone) {
            this.text = text;
            this.tone = tone;
        }
    }

    /**
     * The line for Android's own Battery saver on the tablet's Home and Phone tab and on the phone's own screen. "On" is said only
     * when Android itself says so; the stored option alone is called by its name.
     *
     * @param osOn what Android says (null = the phone did not say)
     * @param wanted the option: switch it on with LinkMeter's saver
     * @param ours LinkMeter switched it on and has the original to put back
     * @param saverOn LinkMeter's own saver is on now
     */
    static Line fact(Boolean osOn, boolean wanted, boolean ours, boolean saverOn) {
        if (osOn == null) {
            return new Line("not known", Tone.DIM);
        }
        if (osOn.booleanValue()) {
            return new Line(ours ? "on (LinkMeter switched it on, and puts it back)" : "on (not by LinkMeter: it stays as it is)", Tone.GOOD);
        }
        if (!wanted) {
            return new Line("off (LinkMeter leaves it alone)", Tone.PLAIN);
        }
        if (!saverOn) {
            return new Line("off (switches on with LinkMeter's saver)", Tone.PLAIN);
        }
        return new Line(ours ? "off (it went off again)" : "off (it did not switch on)", Tone.WARN);
    }
}
