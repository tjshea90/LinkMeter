package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure Java (no Android classes), so it is tested on a computer: tools/test.sh (SaverPlanTest), and tools/mutate_link.py ("saver:")
 * breaks each rule on purpose to see that the test notices.
 *
 * The decisions behind the battery saver (Eco): when it switches on and off by itself, when the tablet counts as gone, which
 * settings a run changes and what is stored to put them back, the shell lines for undoing a run (this version's and the older
 * version's), the words the user reads, and how to read the battery's current. Eco does only the Android part: the stored
 * values, the shell, the alarm, the screen receiver and the threads.
 *
 * One rule runs through all of it: a setting is only ever handed to the shell when it is digits or the word null, and only for
 * a name that is in this file. What the phone printed for a setting, or what an earlier version stored, may be an error text.
 */
final class SaverPlan {
    private SaverPlan() {}

    // ------------------------------------------------------------------------------------------ what the saver changes

    /** Namespace, key and the value while saving, in the order of the shell's answers (K0, K1, ...). */
    static final String[][] PROFILE = {
        {"global", "wifi_scan_always_enabled", "0"},
        {"global", "ble_scan_always_enabled", "0"},
        {"secure", "doze_always_on", "0"},
        // Samsung's own switch for the Always On Display (to our knowledge, not seen on this phone): only touched when the
        // phone says 0 or 1 for it, so a wrong guess about the name changes nothing.
        {"system", "aod_mode", "0"},
        {"system", "screen_off_timeout", "15000"},
    };
    static final int AOD = 3;
    static final int TIMEOUT = 4;

    /** The version before this one stored its originals under o0..o4 in this order (and also wrote the hotspot offload setting). */
    private static final String[][] OLD = {
        {"global", "wifi_scan_always_enabled"},
        {"global", "ble_scan_always_enabled"},
        {"secure", "doze_always_on"},
        {"global", "tether_offload_disabled"},
        {"system", "screen_off_timeout"},
    };

    /** The short screen timeout the saver uses, in milliseconds (as text). */
    static String timeoutMs() {
        return PROFILE[TIMEOUT][2];
    }

    private static boolean digits(String v) {
        return v != null && v.matches("\\d{1,10}");
    }

    /** What `settings get` printed is believed only when it is a number or the word null (= the setting is not set). */
    static boolean valueOk(String v) {
        return v != null && (v.equals("null") || digits(v));
    }

    /** A value read from the phone for PROFILE[i]; the display switch is a 0 or a 1 or it is not touched. */
    private static boolean readable(int i, String v) {
        return i == AOD ? ("0".equals(v) || "1".equals(v)) : valueOk(v);
    }

    private static boolean known(String ns, String key) {
        for (String[] p : PROFILE) {
            if (p[0].equals(ns) && p[1].equals(key)) {
                return true;
            }
        }
        for (String[] p : OLD) {
            if (p[0].equals(ns) && p[1].equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** What a setting is called in a sentence about it not having been read or changed. */
    static String subject(String key) {
        switch (key) {
            case "wifi_scan_always_enabled":
                return "background Wi-Fi scanning";
            case "ble_scan_always_enabled":
                return "background Bluetooth scanning";
            case "doze_always_on":
            case "aod_mode":
                return "the always-on display";
            case "screen_off_timeout":
                return "the screen timeout";
            case "tether_offload_disabled":
                return "the hotspot offload setting";
            default:
                return "an unknown setting";
        }
    }

    /** What a setting that was changed is called in a sentence about the saver being on. */
    private static String phrase(String key, String target) {
        switch (key) {
            case "wifi_scan_always_enabled":
                return "background Wi-Fi scanning off";
            case "ble_scan_always_enabled":
                return "background Bluetooth scanning off";
            case "doze_always_on":
            case "aod_mode":
                return "always-on display off";
            case "screen_off_timeout":
                return "screen timeout " + (digits(target) ? Long.parseLong(target) / 1000 : 0) + " seconds";
            default:
                return subject(key);
        }
    }

    /** One setting the saver changed, or has to put back. */
    static final class Entry {
        final String ns;
        final String key;
        /** The value before the saver, as the phone printed it: digits, or "null" = the setting was not set. */
        final String original;
        /** What the saver sets it to ("" for an entry read back from storage, which only needs the original). */
        final String target;
        /** Index in PROFILE, or -1. */
        final int idx;
        /** False: kept only so it can be put back later (a remote screen session owns the screen timeout right now). */
        final boolean write;

        Entry(String ns, String key, String original, String target, int idx, boolean write) {
            this.ns = ns;
            this.key = key;
            this.original = original;
            this.target = target;
            this.idx = idx;
            this.write = write;
        }

        boolean isTimeout() {
            return "system".equals(ns) && "screen_off_timeout".equals(key);
        }
    }

    // ------------------------------------------------------------------------------------------ when it switches on and off

    /** After a failed switch-on or switch-off, wait this long before trying again by itself. */
    static final long RETRY_MS = 60 * 1000L;
    /** The tablet counts as gone when it has not been heard over Wi-Fi for this long. */
    static final long IDLE_MS = 10 * 60 * 1000L;

    enum Act { NOTHING, APPLY, RESTORE }

    /** True while a failed try is too recent to repeat. failedAt is 0 when nothing has failed. */
    static boolean waiting(long now, long failedAt) {
        return failedAt > 0 && now - failedAt < RETRY_MS;
    }

    /**
     * Brings the saver in line with the stored choice. Called for every request the tablet makes over Wi-Fi and when the service
     * starts. Automatic and off: switch on, but only while the tablet is here. Off and still on: switch off, tablet or not (the
     * choice was made before the phone's app restarted, or Shizuku was down when it was made). A switch that has just failed is
     * not tried again for a minute, so a phone that cannot be read does not run a shell on every request.
     * The caller asks again under the saver's lock before it acts: this answer may be a moment old.
     */
    static Act reconcile(boolean auto, boolean on, boolean tabletHere, boolean shizukuReady, long now, long failedAt) {
        if (auto == on) {
            return Act.NOTHING; // Automatic and on, or Off and off: nothing to bring in line
        }
        if (!shizukuReady || waiting(now, failedAt)) {
            return Act.NOTHING;
        }
        if (auto) {
            return tabletHere ? Act.APPLY : Act.NOTHING;
        }
        return Act.RESTORE;
    }

    enum Verdict {
        /** The saver is off: drop the alarm and the screen receiver. */
        NOTHING_TO_WATCH,
        /** Look again after waitMs. */
        LOOK_AGAIN,
        /** Ask whether the tablet's last address answers, then decide again with the answer. */
        ASK_TABLET,
        /** Put the phone's settings back. */
        RESTORE
    }

    static final class Idle {
        final Verdict verdict;
        final long waitMs;

        Idle(Verdict verdict, long waitMs) {
            this.verdict = verdict;
            this.waitMs = waitMs;
        }
    }

    private static Idle again(long ms) {
        return new Idle(Verdict.LOOK_AGAIN, ms);
    }

    /**
     * Run by an alarm that does not wake the phone (so it may come late, on the first wake after it was due) and when the screen
     * comes on, which gives a phone picked up after Standby its own settings back at once.
     * The saver stays while the tablet is plainly still using the hotspot, which LinkMeter cannot see (it only hears from the
     * tablet while its app is in front): the hotspot is on and the tablet's last address answers. It also stays on a phone that
     * is resting with its screen off and no hotspot, where nobody sees the short screen timeout. Anything else means the tablet
     * is gone or somebody is using the phone, and the phone's own settings go back.
     *
     * @param hotspotOn true, false, or null when this phone does not say
     * @param tabletAnswers null = not asked yet
     */
    static Idle idle(boolean auto, boolean on, long now, long lastSeen, Boolean hotspotOn, boolean screenOn, Boolean tabletAnswers,
            boolean shizukuReady) {
        if (!on) {
            return new Idle(Verdict.NOTHING_TO_WATCH, 0);
        }
        boolean gone;
        if (!auto) {
            gone = true; // Off is the user's word: it does not matter whether the tablet is here
        } else {
            long since = now - lastSeen;
            if (since < IDLE_MS) {
                return again(IDLE_MS - since); // the tablet was heard recently
            }
            if (Boolean.TRUE.equals(hotspotOn)) {
                if (tabletAnswers == null) {
                    return new Idle(Verdict.ASK_TABLET, 0);
                }
                gone = !tabletAnswers.booleanValue();
            } else {
                gone = screenOn; // a hotspot that is off or not known: a resting phone keeps the values, a phone in a hand does not
            }
        }
        if (!gone || !shizukuReady) {
            return again(IDLE_MS); // still in use, or it cannot be done now: the next look may
        }
        return new Idle(Verdict.RESTORE, 0);
    }

    // ------------------------------------------------------------------------------------------ one run: read, plan, write, check

    /** The shell line that prints the present value of every key of PROFILE as K0=..., K1=..., */
    static String readCommand() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PROFILE.length; i++) {
            sb.append("echo K").append(i).append("=$(settings get ").append(PROFILE[i][0]).append(' ').append(PROFILE[i][1]).append("); ");
        }
        return sb.append("true").toString();
    }

    /** The values the shell printed, by key; null where no line came back for a key. Later lines win. */
    static String[] parseRead(String out) {
        String[] v = new String[PROFILE.length];
        if (out == null) {
            return v;
        }
        for (String line : out.split("\n")) {
            String l = line.trim();
            int eq = l.indexOf('=');
            if (l.length() < 3 || l.charAt(0) != 'K' || eq < 2) {
                continue;
            }
            try {
                int i = Integer.parseInt(l.substring(1, eq));
                if (i >= 0 && i < v.length) {
                    v[i] = l.substring(eq + 1).trim();
                }
            } catch (NumberFormatException ignored) {
                // not one of our lines
            }
        }
        return v;
    }

    /** What one switch-on will do. */
    static final class Run {
        /** Everything switch-off must put back, in PROFILE order. */
        final List<Entry> entries = new ArrayList<>();
        /** Settings whose present value could not be read: they are left alone, and tried again at the next switch-on. */
        final List<String> unreadable = new ArrayList<>();
        /** How many settings could be read at all. None means the phone did not answer, and the saver is not switched on. */
        int readable;

        List<Entry> writes() {
            List<Entry> w = new ArrayList<>();
            for (Entry e : entries) {
                if (e.write) {
                    w.add(e);
                }
            }
            return w;
        }

        /** True when a remote screen session holds the timeout, so the saver's timeout waits until that session ends. */
        boolean timeoutWaits() {
            for (Entry e : entries) {
                if (e.isTimeout() && !e.write) {
                    return true;
                }
            }
            return false;
        }

        /** What to store before anything is written, so that a crash half way can still be undone. */
        String stored() {
            return format(entries);
        }
    }

    /**
     * @param read what the shell printed per key (parseRead), null where nothing came back
     * @param sessionOwnsTimeout a remote screen session has changed the screen timeout and will put it back itself
     * @param sessionOriginal that session's record of the timeout from before it (only used then)
     */
    static Run plan(String[] read, boolean sessionOwnsTimeout, String sessionOriginal) {
        Run run = new Run();
        for (int i = 0; i < PROFILE.length; i++) {
            boolean session = i == TIMEOUT && sessionOwnsTimeout;
            String v = session ? sessionOriginal : (i < read.length ? read[i] : null);
            if (i == AOD && "null".equals(v)) {
                continue; // a phone without that switch: nothing to do and nothing to report
            }
            if (!readable(i, v)) {
                run.unreadable.add(subject(PROFILE[i][1]));
                continue;
            }
            run.readable++;
            if (v.equals(PROFILE[i][2])) {
                continue; // already as the saver wants it: nothing to change, nothing to put back
            }
            run.entries.add(new Entry(PROFILE[i][0], PROFILE[i][1], v, PROFILE[i][2], i, !session));
        }
        return run;
    }

    /**
     * The shell line that sets every setting of the run and prints what the phone then says for it (R0=..., R3=...): a write the
     * phone ignored or changed shows up as a different value.
     */
    static String writeCommand(Run run) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : run.writes()) {
            sb.append("settings put ").append(e.ns).append(' ').append(e.key).append(' ').append(e.target)
                    .append("; echo R").append(e.idx).append("=$(settings get ").append(e.ns).append(' ').append(e.key).append("); ");
        }
        return sb.append("true").toString();
    }

    /** Which settings the phone took and which it did not. */
    static final class Applied {
        final List<Entry> changed = new ArrayList<>();
        final List<Entry> failed = new ArrayList<>();
    }

    static Applied check(Run run, String out) {
        Applied a = new Applied();
        String[] back = new String[PROFILE.length];
        if (out != null) {
            for (String line : out.split("\n")) {
                String l = line.trim();
                int eq = l.indexOf('=');
                if (l.length() < 3 || l.charAt(0) != 'R' || eq < 2) {
                    continue;
                }
                try {
                    int i = Integer.parseInt(l.substring(1, eq));
                    if (i >= 0 && i < back.length) {
                        back[i] = l.substring(eq + 1).trim();
                    }
                } catch (NumberFormatException ignored) {
                    // not one of our lines
                }
            }
        }
        for (Entry e : run.writes()) {
            (e.target.equals(back[e.idx]) ? a.changed : a.failed).add(e);
        }
        return a;
    }

    // ------------------------------------------------------------------------------------------ what is stored, and putting it back

    /** The stored form of the originals: one line per setting, "namespace key original". */
    static String format(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            sb.append(e.ns).append(' ').append(e.key).append(' ').append(e.original).append('\n');
        }
        return sb.toString();
    }

    /** Originals read back from storage; whatever could not be trusted is named in skipped and never reaches the shell. */
    static final class Parsed {
        final List<Entry> entries = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
    }

    static Parsed parse(String stored) {
        Parsed p = new Parsed();
        if (stored == null) {
            return p;
        }
        for (String line : stored.split("\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            String[] f = line.trim().split(" ");
            if (f.length == 3 && known(f[0], f[1]) && valueOk(f[2])) {
                p.entries.add(new Entry(f[0], f[1], f[2], "", -1, false));
            } else if (f.length >= 2 && known(f[0], f[1])) {
                p.skipped.add(subject(f[1]));
            } else {
                p.skipped.add(subject(""));
            }
        }
        return p;
    }

    /** The originals the version before this one stored (o0..o4, null where nothing is stored under that name). */
    static Parsed parseOld(String[] o) {
        Parsed p = new Parsed();
        for (int i = 0; i < OLD.length; i++) {
            String v = (o == null || i >= o.length || o[i] == null) ? null : o[i].trim();
            if (valueOk(v)) {
                p.entries.add(new Entry(OLD[i][0], OLD[i][1], v, "", -1, false));
            } else {
                p.skipped.add(subject(OLD[i][1]));
            }
        }
        return p;
    }

    /** The shell line that puts a run back, and what it leaves out. */
    static final class Restore {
        final String command;
        /** How many things the line puts back. When it is 0 there is no need to run it. */
        final int count;
        final List<String> skipped;
        /**
         * Not null: a remote screen session holds the screen timeout, so the line leaves it alone. The session must be told
         * this value as its own record of the original, so that it puts the user's timeout back when it ends (and not the
         * saver's short one, which is what it had read as "the original" if it started while the saver was on).
         */
        final String sessionTimeout;

        Restore(String command, int count, List<String> skipped, String sessionTimeout) {
            this.command = command;
            this.count = count;
            this.skipped = skipped;
            this.sessionTimeout = sessionTimeout;
        }
    }

    /** @param bluetoothBack the older version had switched Bluetooth off for this run: switch it back on */
    static Restore restore(Parsed p, boolean bluetoothBack, boolean sessionOwnsTimeout) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        String handOver = null;
        for (Entry e : p.entries) {
            if (e.isTimeout() && sessionOwnsTimeout) {
                handOver = e.original;
                continue;
            }
            if (e.original.equals("null")) {
                sb.append("settings delete ").append(e.ns).append(' ').append(e.key).append("; ");
            } else {
                sb.append("settings put ").append(e.ns).append(' ').append(e.key).append(' ').append(e.original).append("; ");
            }
            n++;
        }
        if (bluetoothBack) {
            sb.append("svc bluetooth enable; ");
            n++;
        }
        return new Restore(sb.append("true").toString(), n, p.skipped, handOver);
    }

    // ------------------------------------------------------------------------------------------ the words the user reads

    static final String ALREADY_ON = "Battery saver was already on.";
    static final String ALREADY_OFF = "Battery saver was off.";
    /** The choice is Off but Shizuku is not running, so the settings cannot be put back yet (the choice itself is stored). */
    static final String OFF_STORED_WAITING = "Stored. The phone's settings go back as soon as Shizuku runs.";
    static final String ON_STORED_WAITING = "Stored. The battery saver switches on by itself once Shizuku runs and the tablet is connected.";
    static final String AUTOMATIC_NOTE = " It switches on by itself when the tablet connects.";
    static final String OFF_NOTE = " It stays off until you switch it on.";
    /** Restore everything also stores Off, or the next request from the tablet would switch the saver on again. */
    static final String RESTORE_ALL_NOTE = " The battery saver is now switched off. Switch it on again in the Phone tab.";
    static final String COULD_NOT_READ = "Couldn't read the phone's settings, so the battery saver was not switched on.";

    private static String join(List<String> l) {
        return String.join(", ", l);
    }

    /** The phrases of the settings that were changed, once each (two keys can be the same feature). */
    private static List<String> phrases(List<Entry> entries) {
        List<String> l = new ArrayList<>();
        for (Entry e : entries) {
            String s = phrase(e.key, e.target);
            if (!l.contains(s)) {
                l.add(s);
            }
        }
        return l;
    }

    private static List<String> subjects(List<Entry> entries) {
        List<String> l = new ArrayList<>();
        for (Entry e : entries) {
            String s = subject(e.key);
            if (!l.contains(s)) {
                l.add(s);
            }
        }
        return l;
    }

    /** True when the switch-on did what it should: nothing failed. */
    static boolean done(Applied a) {
        return a.failed.isEmpty();
    }

    /** The answer to a switch-on. When done() is false it is the sentence of the error. */
    static String appliedSentence(Run run, Applied a) {
        StringBuilder sb = new StringBuilder();
        if (!a.failed.isEmpty()) {
            sb.append(a.changed.isEmpty() ? "The battery saver could not be switched on." : "The battery saver is only partly on.")
                    .append(" The phone did not take: ").append(join(subjects(a.failed))).append('.');
            if (!a.changed.isEmpty()) {
                sb.append(" It did change: ").append(join(phrases(a.changed))).append(". Switching the saver off puts those back.");
            }
            return sb.toString();
        }
        if (a.changed.isEmpty()) {
            sb.append("Battery saver is on. Nothing had to be changed.");
        } else {
            sb.append("Battery saver is on: ").append(join(phrases(a.changed))).append('.');
        }
        if (run.timeoutWaits()) {
            sb.append(" The screen timeout changes when the remote screen session ends.");
        }
        if (!run.unreadable.isEmpty()) {
            sb.append(" Not changed, because the phone did not say what ").append(run.unreadable.size() == 1 ? "it is" : "they are")
                    .append(" now: ").append(join(run.unreadable)).append('.');
        }
        return sb.toString();
    }

    /** The answer to a switch-off. */
    static String restoredSentence(Restore r) {
        StringBuilder sb = new StringBuilder(r.count == 0 ? "Battery saver is off. Nothing had to be put back."
                : "Battery saver is off. Settings are back as they were.");
        if (r.sessionTimeout != null) {
            sb.append(" The screen timeout goes back when the remote screen session ends.");
        }
        if (!r.skipped.isEmpty()) {
            sb.append(" Left as they are, because the saved value could not be used: ").append(join(r.skipped)).append('.');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------ measuring

    /**
     * The battery's present current in milliamps for the status: negative while the phone is discharging, null when the phone does
     * not say. Makers differ: Android documents microamps and some phones report milliamps; some flip the sign. So the size decides
     * the unit (above 20,000 it must be microamps: no phone draws 20 amps) and whether the phone is charging decides the sign.
     * A phone that is awake and not charging draws something, so a flat 0 there means "not reported". The first readings from the
     * phone (Probe prints the raw number) will show whether this guess about its unit is right.
     */
    static Integer milliamps(long raw, boolean charging) {
        if (raw == Long.MIN_VALUE) {
            return null; // BatteryManager's word for "not supported"
        }
        long mag = Math.abs(raw);
        if (mag == 0 && !charging) {
            return null;
        }
        long ma = mag > 20000 ? Math.round(mag / 1000.0) : mag;
        if (ma > 30000) {
            return null; // not a battery current
        }
        return Integer.valueOf((int) (charging ? ma : -ma));
    }
}
