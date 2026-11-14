package com.tj.linkmeter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Tests SystemSaver, Android's own Battery saver as an option of LinkMeter's saver. Not part of the app.
 *
 * Three layers: every rule on its own; the real shell lines run through `sh` against a fake `settings` program, by the REAL
 * sequence (SystemSaver.apply and restore, which Eco also runs, with a fake phone behind the same interface): a phone that ignores
 * the write, an Android that takes the setting and still says "off", an Android that will not say, a hotspot that goes away with
 * the saver, Shizuku down at each step; and a random layer whose invariant is the one that matters: whatever happens, restoring
 * leaves the phone's setting exactly as it was found, and nothing but numbers and the word null ever reaches the shell.
 */
public final class SystemSaverTest {
    private static int checks;
    private static int fails;

    private SystemSaverTest() {}

    private static void eq(Object want, Object got, String what) {
        checks++;
        String w = String.valueOf(want);
        String g = String.valueOf(got);
        if (!w.equals(g)) {
            fails++;
            System.out.println("FAIL " + what + ": wanted " + w + " but got " + g);
        }
    }

    private static void yes(boolean ok, String what) {
        checks++;
        if (!ok) {
            fails++;
            System.out.println("FAIL " + what);
        }
    }

    private static final Boolean[] TRI = {Boolean.TRUE, Boolean.FALSE, null};

    // ------------------------------------------------------------------------------------------------ the rules, one by one

    private static void commands() {
        eq("echo L=$(settings get global low_power); true", SystemSaver.readCommand(), "the read line");
        eq("settings put global low_power 1; echo L=$(settings get global low_power); true", SystemSaver.writeCommand(), "the write line reads back");
        eq("settings put global low_power 0; true", SystemSaver.restoreCommand("0"), "restore a number");
        eq("settings put global low_power 1; true", SystemSaver.restoreCommand("1"), "restore a 1");
        eq("settings delete global low_power; true", SystemSaver.restoreCommand("null"), "a setting that was not set is deleted again");
        for (String evil : new String[] {null, "", "NULL", "-1", "1.5", "0; reboot", "$(reboot)", "`reboot`", "12345678901", "Error: no", "1 2", "١٢٣"}) {
            eq(null, SystemSaver.restoreCommand(evil), "an untrusted stored value never reaches the shell: " + evil);
        }
        yes(!SystemSaver.ACTION_ON.equals(SystemSaver.ACTION_OFF) && SystemSaver.ACTION_ON.startsWith("eco_") && SystemSaver.ACTION_OFF.startsWith("eco_"),
                "two action names");
        yes(!SystemSaver.ACTION_ON.equals("eco_auto_on") && !SystemSaver.ACTION_ON.equals("eco_on") && !SystemSaver.ACTION_OFF.equals("eco_off")
                && !SystemSaver.ACTION_OFF.equals("eco_auto_off"), "not the names of the saver's own switch");
    }

    private static void reading() {
        eq("0", SystemSaver.parseRead("L=0"), "a 0");
        eq("1", SystemSaver.parseRead("L=1\n"), "a 1");
        eq("null", SystemSaver.parseRead("L=null"), "not set");
        eq("1", SystemSaver.parseRead("  L=1  "), "spaces are trimmed");
        eq("1", SystemSaver.parseRead("noise\nL=0\nL=1"), "the later line wins, noise is ignored");
        eq(null, SystemSaver.parseRead(null), "no output");
        eq(null, SystemSaver.parseRead(""), "empty output");
        eq(null, SystemSaver.parseRead("L="), "an empty value is not believed");
        eq(null, SystemSaver.parseRead("L=Exception occurred while executing 'get':\njava.lang.SecurityException"), "an error text is not believed");
        eq(null, SystemSaver.parseRead("L=-1"), "a negative number is not believed");
        eq(null, SystemSaver.parseRead("L=12345678901"), "eleven digits are not believed");
        eq(null, SystemSaver.parseRead("L=0; reboot"), "a number with a command behind it is not believed");
        eq(null, SystemSaver.parseRead("K0=1"), "a line that is not ours");
        eq(null, SystemSaver.parseRead("L = 1"), "not exactly L=<value>");
        eq(null, SystemSaver.parseRead("L=0\nL=garbage"), "the later line wins even when it is garbage (then nothing is believed)");
    }

    private static void planning() {
        String[] reads = {null, "", "0", "1", "2", "null", "Error: no", "12345678901", "0; reboot", "-1"};
        for (String read : reads) {
            boolean ok = SaverPlan.valueOk(read);
            for (Boolean os : TRI) {
                SystemSaver.Plan p = SystemSaver.plan(read, os);
                SystemSaver.Step want;
                if (Boolean.TRUE.equals(os)) {
                    want = SystemSaver.Step.ALREADY_ON;
                } else if (!ok) {
                    want = SystemSaver.Step.UNKNOWN;
                } else {
                    want = SystemSaver.Step.APPLY;
                }
                eq(want, p.step, "plan read=" + read + " os=" + os);
                eq(want == SystemSaver.Step.APPLY ? read : null, p.original, "the original is kept only for a switch-on: read=" + read + " os=" + os);
            }
        }
        eq(SystemSaver.Step.ALREADY_ON, SystemSaver.plan("0", Boolean.TRUE).step, "somebody else's Battery saver is left alone, whatever the setting says");
        eq(SystemSaver.Step.APPLY, SystemSaver.plan("null", null).step, "a phone that will not say is still switched on, and then checked");
    }

    private static void checking() {
        String took = "L=1";
        for (Boolean os : TRI) {
            for (String out : new String[] {took, "L=0", "L=null", "", null, "L=garbage", "L=1; reboot", "L=0\nL=1"}) {
                SystemSaver.Result want;
                if (!"1".equals(SystemSaver.parseRead(out))) {
                    want = SystemSaver.Result.NOT_TAKEN;
                } else if (Boolean.TRUE.equals(os)) {
                    want = SystemSaver.Result.ON;
                } else {
                    want = SystemSaver.Result.STAYED_OFF;
                }
                eq(want, SystemSaver.check(out, os), "check out=" + out + " os=" + os);
            }
        }
        for (Boolean b : TRI) {
            for (Boolean a : TRI) {
                eq(Boolean.TRUE.equals(b) && Boolean.FALSE.equals(a), SystemSaver.hotspotLost(b, a), "hotspot lost? before=" + b + " after=" + a);
            }
        }
        yes(!SystemSaver.hotspotLost(null, Boolean.FALSE), "a hotspot that was not known before is never called lost");
        yes(!SystemSaver.hotspotLost(Boolean.TRUE, null), "a hotspot that is not known now is never called lost");
        yes(!SystemSaver.hotspotLost(Boolean.FALSE, Boolean.FALSE), "a hotspot that was off is not lost");
    }

    private static void facts() {
        for (Boolean os : TRI) {
            for (int mask = 0; mask < 8; mask++) {
                boolean wanted = (mask & 1) != 0;
                boolean ours = (mask & 2) != 0;
                boolean saverOn = (mask & 4) != 0;
                SystemSaver.Line l = SystemSaver.fact(os, wanted, ours, saverOn);
                String what = " (os=" + os + " wanted=" + wanted + " ours=" + ours + " saverOn=" + saverOn + ")";
                if (os == null) {
                    eq("not known", l.text, "an unknown state is not guessed" + what);
                    eq(SystemSaver.Tone.DIM, l.tone, "...and dim" + what);
                    continue;
                }
                yes(l.text.startsWith(os.booleanValue() ? "on" : "off"), "'on' only when Android says on, 'off' only when it says off: " + l.text + what);
                yes(!l.text.startsWith(os.booleanValue() ? "off" : "on"), "never the other word: " + l.text + what);
                eq(os.booleanValue() ? SystemSaver.Tone.GOOD : (wanted && saverOn ? SystemSaver.Tone.WARN : SystemSaver.Tone.PLAIN), l.tone, "tone" + what);
                if (os.booleanValue()) {
                    yes(l.text.contains(ours ? "switched it on" : "not by LinkMeter"), "it says whose it is: " + l.text + what);
                } else if (!wanted) {
                    yes(l.text.contains("leaves it alone"), "off and not asked for: " + l.text + what);
                } else if (!saverOn) {
                    yes(l.text.contains("switches on with LinkMeter's saver"), "asked for, the saver is not on yet: " + l.text + what);
                } else {
                    yes(l.text.contains(ours ? "went off again" : "did not switch on"), "asked for, the saver is on and it is off: " + l.text + what);
                }
                yes(!l.text.contains("!") && !l.text.contains(" - ") && !l.text.contains("—") && !l.text.contains("–"), "plain words: " + l.text);
            }
        }
        eq("on (not by LinkMeter: it stays as it is)", SystemSaver.fact(Boolean.TRUE, false, false, false).text, "the user's own Battery saver");
        eq("off (LinkMeter leaves it alone)", SystemSaver.fact(Boolean.FALSE, false, false, true).text, "the default: not touched");
    }

    private static void words() {
        String[] all = {SystemSaver.ON, SystemSaver.WAS_ON, SystemSaver.NOT_READ, SystemSaver.NOT_SAVED, SystemSaver.NOT_TAKEN, SystemSaver.STAYED_OFF,
            SystemSaver.HOTSPOT_LOST, SystemSaver.BACK, SystemSaver.BACK_BUT_ON, SystemSaver.NOT_PUT_BACK, SystemSaver.UNUSABLE, SystemSaver.STORED_WAITING,
            SystemSaver.STORED_SAVER_OFF, SystemSaver.STORED_NEEDS_SHIZUKU, SystemSaver.STORED_LEFT_ALONE, SystemSaver.STORED_BACK_WAITING};
        for (String t : all) {
            yes(!t.contains("!") && !t.contains(" - ") && !t.contains("—") && !t.contains("–"), "no exclamation marks or dashes: " + t);
            yes(!t.contains("Eco") && !t.contains("Priv") && !t.contains("settings put") && !t.contains("low_power") && !t.contains("PowerManager"),
                    "no class names or commands: " + t);
            yes(t.startsWith("Android's own Battery saver") || t.startsWith("Stored.") || t.startsWith("LinkMeter ") || t.startsWith("The saved value"),
                    "every sentence is about Android's own saver, so it is never mistaken for LinkMeter's: " + t);
            yes(t.endsWith("."), "a sentence ends with a full stop: " + t);
        }
        yes(SystemSaver.HOTSPOT_LOST.contains("Hotspot on") && SystemSaver.HOTSPOT_LOST.contains("will not switch it on again"),
                "the hotspot sentence says how to get the hotspot back and that the option is given up");
        yes(SystemSaver.BACK_BUT_ON.contains("quick settings"), "a saver that stays on says where to switch it off");
    }

    // ------------------------------------------------------------------------------------------------ a fake phone to run the shell lines on

    /** A directory of files as the phone's settings, and a `settings` program to go with it. */
    private static final class Phone {
        final Path dir;
        final Path bin;
        String ignorePuts = "";
        String brokenGets = "";
        final List<String> shellLines = new ArrayList<>();

        Phone() throws IOException {
            dir = Files.createTempDirectory("syssaver-settings");
            bin = Files.createTempDirectory("syssaver-bin");
            write(bin.resolve("settings"), "#!/bin/sh\n"
                    + "d=\"$FAKE_SETTINGS\"; op=\"$1\"; ns=\"$2\"; key=\"$3\"\n"
                    + "case \"$op\" in\n"
                    + "  get)\n"
                    + "    case \" $FAKE_BROKEN_GET \" in *\" $ns.$key \"*) echo \"Exception occurred while executing 'get':\"; echo \"java.lang.SecurityException: no\" >&2; exit 0;; esac\n"
                    + "    if [ -f \"$d/$ns.$key\" ]; then cat \"$d/$ns.$key\"; else echo null; fi ;;\n"
                    + "  put)\n"
                    + "    case \" $FAKE_IGNORE \" in *\" $ns.$key \"*) exit 0;; esac\n"
                    + "    echo \"$ns.$key=$4\" >> \"$d/puts.log\"\n"
                    + "    printf '%s\\n' \"$4\" > \"$d/$ns.$key\" ;;\n"
                    + "  delete) echo \"$ns.$key=DELETE\" >> \"$d/puts.log\"; rm -f \"$d/$ns.$key\" ;;\n"
                    + "  *) echo \"bad usage\" >&2; exit 1 ;;\n"
                    + "esac\n");
            bin.resolve("settings").toFile().setExecutable(true);
        }

        static void write(Path p, String text) throws IOException {
            Files.write(p, text.getBytes(StandardCharsets.UTF_8));
        }

        String low() throws IOException {
            Path f = dir.resolve("global.low_power");
            return Files.exists(f) ? new String(Files.readAllBytes(f), StandardCharsets.UTF_8).trim() : "null";
        }

        void setLow(String v) throws IOException {
            if (v.equals("null")) {
                Files.deleteIfExists(dir.resolve("global.low_power"));
            } else {
                write(dir.resolve("global.low_power"), v + "\n");
            }
        }

        String run(String cmd) throws Exception {
            shellLines.add(cmd);
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", "(" + cmd + ") 2>&1");
            pb.environment().put("PATH", bin + File.pathSeparator + System.getenv("PATH"));
            pb.environment().put("FAKE_SETTINGS", dir.toString());
            pb.environment().put("FAKE_IGNORE", ignorePuts);
            pb.environment().put("FAKE_BROKEN_GET", brokenGets);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("the shell line did not end: " + cmd);
            }
            return new String(out, StandardCharsets.UTF_8).trim();
        }

        List<String> writes() throws IOException {
            Path f = dir.resolve("puts.log");
            return Files.exists(f) ? Files.readAllLines(f) : new ArrayList<String>();
        }
    }

    /** How Android behaves once the setting is written. */
    private enum Os { FOLLOWS, IGNORES, WILL_NOT_SAY }

    /** The phone behind SystemSaver.Env: a real shell on the fake settings, a stored original, an Android and a hotspot. */
    private static class FakeEnv implements SystemSaver.Env {
        final Phone phone;
        String stored;
        boolean storeFails;
        Os os = Os.FOLLOWS;
        /** Somebody else's Battery saver is on, whatever the setting says. */
        boolean osAlreadyOn;
        Boolean hotspot = Boolean.TRUE;
        /** The hotspot goes off when Android's saver comes on. */
        boolean saverKillsHotspot;
        /** Shizuku is down: every shell line fails to run. */
        boolean shizukuDown;
        /** The write line is the one that fails to run (it may have gone through). */
        boolean writeThrows;
        /** The write line took effect and THEN failed. */
        boolean writeLandsThenThrows;
        final List<String> events = new ArrayList<>();
        long paused;

        FakeEnv(Phone phone) {
            this.phone = phone;
        }

        @Override
        public String shell(String cmd, int seconds) throws IOException {
            if (shizukuDown) {
                throw new IOException("Shizuku is not running");
            }
            boolean isWrite = cmd.startsWith("settings put global low_power 1;");
            if (isWrite && writeThrows) {
                throw new IOException("timed out");
            }
            try {
                String out = phone.run(cmd);
                if (isWrite && writeLandsThenThrows) {
                    writeLandsThenThrows = false;
                    throw new IOException("timed out after the write");
                }
                events.add(cmd.split(";")[0]);
                return out;
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private boolean saverIsOn() {
            try {
                return osAlreadyOn || (os == Os.FOLLOWS && "1".equals(phone.low()));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Boolean osOn() {
            return os == Os.WILL_NOT_SAY ? null : Boolean.valueOf(saverIsOn());
        }

        @Override
        public Boolean waitOs(boolean wantOn, long ms) {
            events.add("wait " + wantOn);
            return osOn();
        }

        @Override
        public Boolean hotspot() {
            return saverKillsHotspot && saverIsOn() ? Boolean.FALSE : hotspot;
        }

        @Override
        public void pause(long ms) {
            paused += ms;
        }

        @Override
        public String stored() {
            return stored;
        }

        @Override
        public boolean store(String original) {
            events.add("store " + original);
            if (storeFails) {
                return false;
            }
            stored = original;
            return true;
        }

        @Override
        public void forget() {
            events.add("forget");
            stored = null;
        }
    }

    private static void sequence() throws Exception {
        // ---- the ordinary phone: the setting is 0, Android follows it
        Phone ph = new Phone();
        ph.setLow("0");
        FakeEnv env = new FakeEnv(ph);
        SystemSaver.Outcome o = SystemSaver.apply(env);
        yes(o.ok && !o.giveUp, "an ordinary switch-on works");
        eq(SystemSaver.ON, o.text, "...and says so");
        eq("1", ph.low(), "...the phone's setting is 1");
        eq("0", env.stored, "...the original is stored");
        yes(env.events.indexOf("store 0") >= 0 && env.events.indexOf("store 0") < env.events.indexOf("settings put global low_power 1"),
                "the original is stored BEFORE anything is written: " + env.events);
        eq(SystemSaver.HOTSPOT_LOOK_MS, env.paused, "the hotspot is looked at again after a pause, not at once");
        yes(env.events.contains("wait true"), "Android's own answer is waited for after the write (it follows the setting a moment later)");
        o = SystemSaver.restore(env);
        yes(env.events.contains("wait false"), "...and after the putting back");
        yes(o.ok, "restoring works");
        eq(SystemSaver.BACK, o.text, "...and says so");
        eq("0", ph.low(), "...the setting is back");
        eq(null, env.stored, "...and the stored original is forgotten");

        // ---- not set before: deleted again
        ph = new Phone();
        env = new FakeEnv(ph);
        o = SystemSaver.apply(env);
        yes(o.ok, "a setting that was not set is switched on");
        eq("null", env.stored, "...and remembered as null");
        SystemSaver.restore(env);
        eq("null", ph.low(), "...and deleted again, as it was");
        yes(ph.writes().contains("global.low_power=DELETE"), "...by a delete");

        // ---- somebody else's Battery saver is on: left alone, and never switched off later
        ph = new Phone();
        ph.setLow("1");
        env = new FakeEnv(ph);
        o = SystemSaver.apply(env);
        yes(o.ok && !o.giveUp, "already on: fine");
        eq(SystemSaver.WAS_ON, o.text, "...and says it is not LinkMeter's");
        eq(null, env.stored, "...nothing is stored");
        eq(0, ph.writes().size(), "...nothing is written");
        o = SystemSaver.restore(env);
        yes(o.ok && o.text.isEmpty(), "...so a restore has nothing to do and says nothing");
        eq("1", ph.low(), "...and the user's Battery saver stays on");

        // ---- the phone ignores the write
        ph = new Phone();
        ph.setLow("0");
        ph.ignorePuts = "global.low_power";
        env = new FakeEnv(ph);
        o = SystemSaver.apply(env);
        yes(!o.ok && !o.giveUp, "a write the phone ignored is a failure, and the option is kept");
        eq(SystemSaver.NOT_TAKEN, o.text, "...with its own sentence");
        eq(null, env.stored, "...nothing is left stored");
        eq("0", ph.low(), "...the setting is as it was");

        // ---- the phone takes the setting, Android does not follow
        ph = new Phone();
        ph.setLow("0");
        env = new FakeEnv(ph);
        env.os = Os.IGNORES;
        o = SystemSaver.apply(env);
        yes(!o.ok && !o.giveUp, "a setting Android does not follow is a failure");
        eq(SystemSaver.STAYED_OFF, o.text, "...with its own sentence (the setting alone is never believed)");
        eq("0", ph.low(), "...and the setting was put back");
        eq(null, env.stored, "...and nothing is left stored");

        // ---- Android will not say
        ph = new Phone();
        ph.setLow("0");
        env = new FakeEnv(ph);
        env.os = Os.WILL_NOT_SAY;
        o = SystemSaver.apply(env);
        yes(!o.ok, "a phone that will not say is not believed");
        eq("0", ph.low(), "...and the setting was put back");

        // ---- the hotspot goes away with the saver
        ph = new Phone();
        ph.setLow("0");
        env = new FakeEnv(ph);
        env.saverKillsHotspot = true;
        o = SystemSaver.apply(env);
        yes(!o.ok && o.giveUp, "a saver that takes the hotspot away is put back and the option is given up");
        eq(SystemSaver.HOTSPOT_LOST, o.text, "...with the sentence for it");
        eq("0", ph.low(), "...the setting is back");
        eq(null, env.stored, "...nothing is left stored");
        // the hotspot was not known, or was already off: never a reason to give up
        for (Boolean before : new Boolean[] {null, Boolean.FALSE}) {
            ph = new Phone();
            ph.setLow("0");
            env = new FakeEnv(ph);
            env.hotspot = before;
            env.saverKillsHotspot = true;
            o = SystemSaver.apply(env);
            yes(o.ok && !o.giveUp, "a hotspot that was " + before + " before is not a reason to give up");
        }

        // ---- the setting cannot be read
        ph = new Phone();
        ph.setLow("0");
        ph.brokenGets = "global.low_power";
        env = new FakeEnv(ph);
        o = SystemSaver.apply(env);
        yes(!o.ok && !o.giveUp, "an unreadable setting is not touched");
        eq(SystemSaver.NOT_READ, o.text, "...with its own sentence");
        eq(0, ph.writes().size(), "...nothing was written");
        eq(null, env.stored, "...nothing was stored");

        // ---- the original cannot be saved
        ph = new Phone();
        ph.setLow("0");
        env = new FakeEnv(ph);
        env.storeFails = true;
        o = SystemSaver.apply(env);
        yes(!o.ok, "no original saved, no switch-on");
        eq(SystemSaver.NOT_SAVED, o.text, "...with its own sentence");
        eq(0, ph.writes().size(), "...nothing was written");

        // ---- Shizuku is down
        ph = new Phone();
        ph.setLow("0");
        env = new FakeEnv(ph);
        env.shizukuDown = true;
        try {
            SystemSaver.apply(env);
            yes(false, "Shizuku down at the first line must be an error");
        } catch (IOException e) {
            yes(true, "Shizuku down at the first line is an error");
        }
        eq(null, env.stored, "...and nothing was stored");

        // ---- the write line does not run: the original stays, a later restore puts everything back
        ph = new Phone();
        ph.setLow("0");
        env = new FakeEnv(ph);
        env.writeLandsThenThrows = true;
        try {
            SystemSaver.apply(env);
            yes(false, "a write that failed after it landed must be an error");
        } catch (IOException e) {
            yes(true, "a write that failed after it landed is an error");
        }
        eq("0", ph.low(), "...and the setting is put back at once, because it may have gone through");
        eq(null, env.stored, "...and the stored original is forgotten after that");
        ph = new Phone();
        ph.setLow("0");
        env = new FakeEnv(ph);
        env.writeThrows = true;
        env.shizukuDown = false;
        try {
            SystemSaver.apply(env);
        } catch (IOException e) {
            // expected
        }
        eq("0", ph.low(), "a write that never ran leaves the setting alone");

        // Shizuku dies for good after the write landed: the original must stay for a later restore
        ph = new Phone();
        ph.setLow("0");
        final FakeEnv dying = new FakeEnv(ph) {
            @Override
            public String shell(String cmd, int seconds) throws IOException {
                String out = super.shell(cmd, seconds);
                if (cmd.startsWith("settings put global low_power 1;")) {
                    shizukuDown = true;
                    throw new IOException("Shizuku went away");
                }
                return out;
            }
        };
        try {
            SystemSaver.apply(dying);
            yes(false, "Shizuku dying after the write is an error");
        } catch (IOException e) {
            yes(true, "Shizuku dying after the write is an error");
        }
        eq("1", ph.low(), "the setting is on (the put went through)");
        eq("0", dying.stored, "...and the original STAYED stored, because the putting back could not run");
        dying.shizukuDown = false;
        o = SystemSaver.restore(dying);
        yes(o.ok, "...so the next restore puts it back");
        eq("0", ph.low(), "...exactly as it was");

        // ---- apply when it is already ours
        ph = new Phone();
        env = new FakeEnv(ph); // the setting was not set
        SystemSaver.apply(env);
        int before = ph.shellLines.size();
        o = SystemSaver.apply(env);
        yes(o.ok && o.text.equals(SystemSaver.ON), "ours and still on: nothing to do");
        eq(before, ph.shellLines.size(), "...and no shell line is run");
        ph.setLow("0"); // somebody switched Android's saver off again
        o = SystemSaver.apply(env);
        yes(o.ok, "ours and it went off again: switched on again");
        eq("null", env.stored, "...with the original that was stored the first time (not the 0 it finds now)");
        eq("1", ph.low(), "...the setting is 1 again");
        SystemSaver.restore(env);
        eq("null", ph.low(), "...and back to 'not set', as it was found the first time");

        // ---- a stored value that cannot be trusted never reaches the shell
        ph = new Phone();
        env = new FakeEnv(ph);
        env.stored = "0; reboot";
        o = SystemSaver.restore(env);
        yes(o.ok && o.text.equals(SystemSaver.UNUSABLE), "an untrusted stored value is forgotten and said");
        eq(0, ph.shellLines.size(), "...and no shell line is run");
        eq(null, env.stored, "...and forgotten");

        // ---- restore with Android still on afterwards
        ph = new Phone();
        ph.setLow("0");
        env = new FakeEnv(ph);
        SystemSaver.apply(env);
        env.osAlreadyOn = true; // somebody switched it on by hand in the meantime: Android stays on after the setting went back
        o = SystemSaver.restore(env);
        yes(!o.ok && o.text.equals(SystemSaver.BACK_BUT_ON), "a saver that stays on after the setting went back is said");
        eq(null, env.stored, "...and the stored original is forgotten anyway (nothing more can be done)");

        // ---- only numbers and the word null ever reach the shell, and only for the one name
        ph = new Phone();
        ph.setLow("0");
        env = new FakeEnv(ph);
        SystemSaver.apply(env);
        SystemSaver.restore(env);
        for (String line : ph.shellLines) {
            yes(line.equals("echo L=$(settings get global low_power); true") || line.equals("settings put global low_power 1; echo L=$(settings get global low_power); true")
                    || line.equals("settings put global low_power 0; true"), "only the known lines were run: " + line);
        }
        for (String w : ph.writes()) {
            yes(w.matches("global\\.low_power=(\\d{1,10}|DELETE)"), "only numbers and deletes were ever written: " + w);
        }
    }

    // ------------------------------------------------------------------------------------------------ random

    private static void random() throws Exception {
        Random rnd = new Random(20261114L);
        int runs = 90;
        String[] initial = {"null", "0", "0", "1", "2", "garbage"};
        for (int run = 0; run < runs; run++) {
            Phone ph = new Phone();
            String start = initial[rnd.nextInt(initial.length)];
            boolean garbage = start.equals("garbage");
            if (garbage) {
                ph.brokenGets = "global.low_power";
                ph.setLow("0");
            } else {
                ph.setLow(start);
            }
            if (rnd.nextInt(6) == 0) {
                ph.ignorePuts = "global.low_power";
            }
            FakeEnv env = new FakeEnv(ph);
            env.os = Os.values()[rnd.nextInt(Os.values().length)];
            env.osAlreadyOn = rnd.nextInt(8) == 0;
            env.hotspot = TRI[rnd.nextInt(3)];
            env.saverKillsHotspot = rnd.nextInt(5) == 0;
            env.storeFails = rnd.nextInt(10) == 0;
            env.writeLandsThenThrows = rnd.nextInt(10) == 0;
            String what = " (run " + run + ": start=" + start + " os=" + env.os + " otherOn=" + env.osAlreadyOn + " hotspot=" + env.hotspot
                    + " kills=" + env.saverKillsHotspot + " ignore=" + ph.ignorePuts + " storeFails=" + env.storeFails + ")";
            SystemSaver.Outcome o = null;
            try {
                o = SystemSaver.apply(env);
            } catch (IOException e) {
                // a line that did not run: the invariant below still holds
            }
            if (o != null && o.ok && env.stored != null) {
                yes("1".equals(ph.low()) && Boolean.TRUE.equals(env.osOn()), "a switch-on that says it worked: the setting is 1 and Android says on" + what);
                yes(!SystemSaver.hotspotLost(env.hotspot, env.hotspot()), "...and the hotspot did not go away with it" + what);
            }
            if (o != null && !o.ok && env.stored == null && !garbage) {
                eq(garbage ? "0" : start, ph.low(), "a failed switch-on left nothing behind" + what);
            }
            // the invariant: restoring (with Shizuku up) leaves the setting exactly as it was found
            env.shizukuDown = false;
            env.osAlreadyOn = false;
            try {
                SystemSaver.restore(env);
            } catch (IOException e) {
                yes(false, "restore with Shizuku up must not fail" + what);
            }
            eq(garbage ? "0" : start, ph.low(), "restoring leaves the setting as it was found" + what);
            eq(null, env.stored, "...and nothing stays stored" + what);
            for (String w : ph.writes()) {
                yes(w.matches("global\\.low_power=(\\d{1,10}|DELETE)"), "only numbers and deletes were ever written: " + w + what);
            }
            if (garbage) {
                eq(0, ph.writes().size(), "an unreadable setting is never written" + what);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        commands();
        reading();
        planning();
        checking();
        facts();
        words();
        sequence();
        random();
        System.out.println(checks + " checks, " + fails + " failed");
        if (fails != 0) {
            System.exit(1);
        }
    }
}
