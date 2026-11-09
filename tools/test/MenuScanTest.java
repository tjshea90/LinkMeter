package com.tj.linkmeter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Offline test of MenuScan (the hidden-menu finder). Run by tools/test.sh. Prints "N checks, M failed".
 *
 * Two kinds of checks. (1) The reading of the phone's text, and the strict checks on anything that is passed back into a shell
 * command (a screen name, a dial code): every injection attempt must be refused. (2) The REAL shell commands, run in bash with
 * the built-in printf switched off (Android's shell has none) against a fake `pm` and a fake `dumpsys` that prints several
 * megabytes, the way the Bands scan was tested after it broke on the phone with "Argument list too long".
 */
public final class MenuScanTest {
    static int checks;
    static int failed;

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            failed++;
            System.out.println("  FAIL: " + msg);
        }
    }

    static final String RECEIVERS =
            "Receiver Resolver Table:\n"
            + "  Non-Data Actions:\n"
            + "      android.intent.action.BOOT_COMPLETED:\n"
            + "        a1b2c3 com.foo/.BootReceiver filter d4e5f6\n"
            + "          Action: \"android.intent.action.BOOT_COMPLETED\"\n"
            + "  Schemes:\n"
            + "      android_secret_code:\n"
            + "        1f2a3b4 com.sec.android.RilServiceModeApp/.SecretCodeReceiver filter 5c6d7e8\n"
            + "          Action: \"android.provider.Telephony.SECRET_CODE\"\n"
            + "          Scheme: \"android_secret_code\"\n"
            + "          Authority: \"2263\": -1\n"
            + "          Authority: \"0011\": -1\n"
            + "          mPriority=0, mOrder=0, mHasStaticPartialTypes=false, mHasDynamicPartialTypes=false\n"
            + "        9a8b7c6 com.android.phone/.SpecialCharSequenceReceiver$Inner filter 1d2e3f4\n"
            + "          Action: \"android.provider.Telephony.SECRET_CODE\"\n"
            + "          Scheme: \"android_secret_code\"\n"
            + "          Authority: \"4636\": -1\n"
            + "      http:\n"
            + "        aa11bb2 com.browser/.Receiver filter cc33dd4\n"
            + "          Action: \"android.intent.action.VIEW\"\n"
            + "          Scheme: \"http\"\n"
            + "          Authority: \"example.com\": 80\n";

    static final String PACKAGES = "com.mediatek.engineermode\ncom.sec.android.RilServiceModeApp\n";

    static String discovery(String packages, String receivers) {
        return MenuScan.PACKAGES_MARK + "\n" + packages + MenuScan.CODES_MARK + "\n" + receivers;
    }

    public static void main(String[] a) throws Exception {
        // ---- reading the discovery text
        {
            MenuScan.Found f = MenuScan.parseDiscovery(discovery(PACKAGES, RECEIVERS));
            check(f.packages.equals(Arrays.asList("com.mediatek.engineermode", "com.sec.android.RilServiceModeApp")), "the apps are listed");
            check(f.sawCodesSection, "the codes section was seen");
            check(f.codes.size() == 3, "three codes: " + f.codes.size());
            check(has(f, "2263", "com.sec.android.RilServiceModeApp/.SecretCodeReceiver"), "2263 and its receiver");
            check(has(f, "0011", "com.sec.android.RilServiceModeApp/.SecretCodeReceiver"), "0011 and its receiver");
            check(has(f, "4636", "com.android.phone/.SpecialCharSequenceReceiver$Inner"), "4636 and a nested class");
            check(!anyCode(f, "example.com"), "an authority of another scheme is not a dialer code");
            // grep's separator between two groups must not carry a receiver over
            MenuScan.Found g = MenuScan.parseDiscovery(discovery("", "x/.A filter 1\n  Scheme: \"android_secret_code\"\n  Authority: \"1111\": -1\n--\n  Authority: \"2222\": -1\n"));
            check(g.codes.size() == 1 && g.codes.get(0)[0].equals("1111"), "an authority after a separator belongs to nobody");
            // an authority before any filter header belongs to nobody either
            MenuScan.Found h = MenuScan.parseDiscovery(discovery("", "  Scheme: \"android_secret_code\"\n  Authority: \"3333\": -1\n"));
            check(h.codes.isEmpty(), "an authority with no receiver is dropped");
            // duplicates are listed once
            MenuScan.Found d = MenuScan.parseDiscovery(discovery("p.q\np.q\n", "x/.A filter 1\n Scheme: \"android_secret_code\"\n Authority: \"1\": -1\n Authority: \"1\": -1\n"));
            check(d.codes.size() == 1 && d.packages.size() == 1, "duplicates once");
            // junk
            check(MenuScan.parseDiscovery(null).codes.isEmpty(), "null");
            check(!MenuScan.parseDiscovery("").sawCodesSection, "empty text: the codes section was not seen");
            check(MenuScan.parseDiscovery(discovery("not a package!\n; reboot\n", "")).packages.isEmpty(), "package lines that are not package names are dropped");
            check(MenuScan.parseDiscovery("garbage \u0000 \n\n\n").packages.isEmpty(), "garbage");
        }

        // ---- the strict checks
        String[] goodComponents = {"com.mediatek.engineermode/.EngineerMode", "com.a.b/com.a.b.C$D", "a/b"};
        String[] badComponents = {"", null, "nopackage", "a/b c", "a/b;reboot", "a/b'c", "a/b\nc", "a/$(reboot)", "a/`id`", "a/b|c", "a//b",
            "a/b&c", "a/b>c", "/b", "a/", "a b/c", "a/b\"c", "x".repeat(201) + "/y"};
        for (String s : goodComponents) {
            check(MenuScan.isComponent(s), "a good screen name: " + s);
        }
        for (String s : badComponents) {
            check(!MenuScan.isComponent(s), "refused screen name: " + s);
        }
        String[] goodCodes = {"*#2263#", "*#*#4636#*#*", "*#0011#", "*#0*#", "12", "*#197328640#"};
        String[] badCodes = {"", null, "*#2263# ", "*#22;63#", "$(id)", "*#a#", "'", "*#\n#", "*".repeat(25), "tel:123", "*#2263#'; reboot; '"};
        for (String s : goodCodes) {
            check(MenuScan.isDialCode(s), "a good code: " + s);
        }
        for (String s : badCodes) {
            check(!MenuScan.isDialCode(s), "refused code: " + s);
        }
        check(MenuScan.dialUri("*#2263#").equals("tel:%2A%232263%23"), "# and * are written as %23 and %2A");
        check(MenuScan.dialUri("*#*#4636#*#*").equals("tel:%2A%23%2A%234636%23%2A%23%2A"), "a long code");
        for (String s : goodCodes) {
            String u = MenuScan.dialUri(s);
            check(u.matches("tel:[0-9%A-F]+"), "a dial address has only safe characters: " + u);
        }

        // ---- the activities command and its reading
        check(MenuScan.activitiesCommand(new ArrayList<String>()) == null, "no packages, no command");
        check(MenuScan.activitiesCommand(Arrays.asList("a;reboot", "b c", "$(id)")) == null, "bad package names give no command");
        String cmd = MenuScan.activitiesCommand(Arrays.asList("com.a.b", "bad name", "com.c.d"));
        check(cmd != null && cmd.contains("-e 'com.a.b/'") && cmd.contains("-e 'com.c.d/'") && !cmd.contains("bad name"), "only good packages are in the command");
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add("com.p" + i);
        }
        String big = MenuScan.activitiesCommand(many);
        check(big != null && big.split("-e '").length - 1 == 12, "at most 12 packages are looked at");
        check(!cmd.contains("$(") && !cmd.contains("`"), "the command holds no command substitution");
        List<String> acts = MenuScan.parseActivities("com.a.b/.Main\ncom.a.b/.Main\nnot a component\n; reboot\ncom.c.d/com.c.d.Other$Inner\n\n");
        check(acts.equals(Arrays.asList("com.a.b/.Main", "com.c.d/com.c.d.Other$Inner")), "screens are read, once, and junk dropped: " + acts);

        // ---- the reply, both ways
        {
            MenuScan.Found f = MenuScan.parseDiscovery(discovery(PACKAGES, RECEIVERS));
            String reply = MenuScan.reply(f, Arrays.asList("com.a.b/.Main", "com.c.d/.Other"));
            MenuScan.Reply r = MenuScan.parseReply(reply);
            check(r.valid && r.packages.size() == 2 && r.codes.size() == 3 && r.activities.size() == 2 && r.notes.isEmpty(), "a reply reads back whole");
            check(r.codes.get(0)[0].equals("2263") && r.codes.get(0)[1].contains("RilServiceModeApp"), "a code and its receiver read back");
            String sum = MenuScan.summary(r);
            check(sum.contains("2 apps") && sum.contains("3 dialer codes") && sum.contains("2 screens"), "the summary counts: " + sum.split("\n")[0]);
            check(sum.contains("code 2263 (band selection (Samsung))"), "a known code is named");
            check(sum.contains("code 4636 (phone information / testing)"), "another known code is named");
            MenuScan.Reply none = MenuScan.parseReply(MenuScan.reply(new MenuScan.Found(), new ArrayList<String>()));
            check(none.valid && none.notes.size() == 2, "an empty finding says why: " + none.notes);
            MenuScan.Found noCodes = MenuScan.parseDiscovery(discovery(PACKAGES, ""));
            check(MenuScan.reply(noCodes, new ArrayList<String>()).contains("NOTE no dialer codes are registered"), "an empty code section is explained");
            check(MenuScan.summary(MenuScan.parseReply("junk")).contains("did not answer in the expected form"), "an old phone app is recognized");
            MenuScan.Reply inj = MenuScan.parseReply("MENUS 1\nACT a/b;reboot\nPKG x y\nCODE 1 not-a-component\nCODE 2 a/b extra\n");
            check(inj.valid && inj.activities.isEmpty() && inj.packages.isEmpty() && inj.codes.isEmpty(),
                    "injected or malformed lines in a reply are dropped");
        }
        check(MenuScan.started("Starting: Intent { cmp=a/.B }"), "am start success");
        check(!MenuScan.started("Starting: Intent { cmp=a/.B }\nError type 3\nError: Activity class {a/.B} does not exist."), "am start failure");
        check(!MenuScan.started("Error type 3"), "no 'Starting' at all");
        check(!MenuScan.started(null) && !MenuScan.started(""), "null and empty");
        check(MenuScan.started("Starting: Intent { cmp=a/.B }\nWarning: Activity not started, its current task has been brought to the front"),
                "a warning about the task is still a start");

        // ---- the real commands, the way Android's shell runs them
        Path bin = Files.createTempDirectory("menuscan-bin");
        write(bin.resolve("pm"), "#!/bin/bash\n"
                + "if [ \"$1\" = list ] && [ \"$2\" = packages ]; then\n"
                + "  echo package:com.android.settings; echo package:com.mediatek.engineermode; echo package:com.sec.android.RilServiceModeApp\n"
                + "  echo package:com.google.android.gms; echo package:com.samsung.android.app.mtk-ish\n"
                + "fi\n");
        // a fake dumpsys: ~3 MB of other receivers, with the interesting table in the middle
        Path table = Files.createTempFile("menuscan-table", ".txt");
        StringBuilder t = new StringBuilder();
        pad(t, 20000, "before");
        t.append(RECEIVERS);
        pad(t, 20000, "after");
        Files.write(table, t.toString().getBytes(StandardCharsets.UTF_8));
        Path acttable = Files.createTempFile("menuscan-acts", ".txt");
        StringBuilder at = new StringBuilder("Activity Resolver Table:\n");
        for (int i = 0; i < 4000; i++) {
            at.append("        ").append(Integer.toHexString(0x100000 + i)).append(" com.other.app").append(i % 50).append("/.Main").append(i).append(" filter ").append(Integer.toHexString(0x900000 + i)).append('\n');
            at.append("          Action: \"android.intent.action.MAIN\"\n");
        }
        at.append("        abc123 com.mediatek.engineermode/.EngineerMode filter def456\n          Action: \"android.intent.action.MAIN\"\n");
        at.append("        abc124 com.sec.android.RilServiceModeApp/com.sec.android.RilServiceModeApp.ServiceModeApp filter def457\n");
        at.append("        abc125 com.mediatek.engineermode/.EngineerMode filter def458\n"); // listed twice
        Files.write(acttable, at.toString().getBytes(StandardCharsets.UTF_8));
        write(bin.resolve("dumpsys"), "#!/bin/bash\n"
                + "if [ \"$1\" = package ] && [ \"$2\" = r ] && [ \"$3\" = receiver ]; then cat '" + table + "'; fi\n"
                + "if [ \"$1\" = package ] && [ \"$2\" = r ] && [ \"$3\" = activity ]; then cat '" + acttable + "'; fi\n");
        String out = run(bin, MenuScan.DISCOVER_COMMAND);
        MenuScan.Found f = MenuScan.parseDiscovery(out);
        check(out.length() < 300_000, "the discovery output stays small: " + out.length() + " bytes");
        check(f.packages.contains("com.mediatek.engineermode") && f.packages.contains("com.sec.android.RilServiceModeApp"), "the real command finds the apps: " + f.packages);
        check(!f.packages.contains("com.android.settings") && !f.packages.contains("com.google.android.gms"), "and only the apps that match");
        check(f.codes.size() == 3 && has(f, "2263", "com.sec.android.RilServiceModeApp/.SecretCodeReceiver"), "the real command finds the codes: " + f.codes.size());
        check(!out.contains("Error") && !out.contains("Argument list too long"), "no shell errors in the output");

        String acmd = MenuScan.activitiesCommand(f.packages);
        String aout = run(bin, acmd);
        List<String> screens = MenuScan.parseActivities(aout);
        check(screens.equals(Arrays.asList("com.mediatek.engineermode/.EngineerMode",
                "com.sec.android.RilServiceModeApp/com.sec.android.RilServiceModeApp.ServiceModeApp")),
                "the real activities command finds the two screens, once each: " + screens);
        check(aout.length() < 10_000, "and its output is small: " + aout.length());

        // the test can fail: the way the Bands scan was written first (the dump held in a variable) breaks on this shell
        String bad = run(bin, "D=$(dumpsys package r receiver); printf '%s\\n' \"$D\" | head -3");
        check(bad.contains("Argument list too long") || bad.trim().isEmpty() || bad.contains("not found"),
                "a command that holds the dump in a variable fails here, as on the phone: " + bad.replace('\n', ' ').substring(0, Math.min(80, bad.length())));
        check(!MenuScan.DISCOVER_COMMAND.contains("$(") && !MenuScan.DISCOVER_COMMAND.contains("`"), "the discovery command holds no command substitution");

        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static boolean has(MenuScan.Found f, String code, String component) {
        for (String[] c : f.codes) {
            if (c[0].equals(code) && c[1].equals(component)) {
                return true;
            }
        }
        return false;
    }

    static boolean anyCode(MenuScan.Found f, String code) {
        for (String[] c : f.codes) {
            if (c[0].equals(code)) {
                return true;
            }
        }
        return false;
    }

    static void pad(StringBuilder sb, int n, String tag) {
        for (int i = 0; i < n; i++) {
            sb.append("        ").append(Integer.toHexString(0x200000 + i)).append(" com.").append(tag).append(".app").append(i % 97)
                    .append("/.Recv").append(i).append(" filter ").append(Integer.toHexString(0x700000 + i)).append('\n')
                    .append("          Action: \"android.intent.action.").append(tag.toUpperCase()).append("_").append(i % 31).append("\"\n")
                    .append("          Scheme: \"http\"\n          Authority: \"host").append(i % 13).append(".example.com\": 80\n");
        }
    }

    static void write(Path p, String s) throws Exception {
        if (s == null) {
            Files.write(p, new byte[0]);
            return;
        }
        Files.write(p, s.getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    /** Runs a command in bash with the built-in printf off and the fake pm / dumpsys first on the PATH. */
    static String run(Path bin, String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", "enable -n printf; " + command);
        pb.environment().put("PATH", bin + File.pathSeparator + System.getenv("PATH"));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("the command did not end");
        }
        return new String(out, StandardCharsets.UTF_8);
    }
}
