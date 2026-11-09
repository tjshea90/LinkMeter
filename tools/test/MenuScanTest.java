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

        // ---- SAFETY: only known-safe codes are ever dialed
        {
            String[] destructive = {"*2767*3855#", "*#7780#", "*#*#7780#*#*", "*#9900#", "*#*#273283*255*663282*#*#*", "*#7284#", "*#0808#",
                "*#*#2432546#*#*", "*#8999*8378#", "*#*#7594#*#*", "*#9090#", "*#272*", "*#*#0*#*#*", "*#0228#", "*#0283#", "*#32489#",
                "*#232331#", "*#872564#", "*#2663#", "*#4238378#", "*#2580#", "*#*#2846579#*#*", "*#12580*369#", "**04*"};
            for (String c : destructive) {
                check(!MenuScan.isSafeDialCode(c), "SAFE: never dials " + c);
            }
            for (String[] c : MenuScan.SAFE_CODES) {
                check(MenuScan.isSafeDialCode(c[0]) && MenuScan.isDialCode(c[0]), "a listed code is accepted: " + c[0]);
                check(!c[1].isEmpty(), "and has a description: " + c[0]);
                for (String d : destructive) {
                    check(!c[0].equals(d), "no destructive code is in the list: " + d);
                }
                check(!MenuScan.isSafeDialCode(c[0] + "1") && !MenuScan.isSafeDialCode(" " + c[0]) && !MenuScan.isSafeDialCode(c[0] + " ")
                        && !MenuScan.isSafeDialCode(c[0].substring(0, c[0].length() - 1)), "a code is matched exactly, not by prefix: " + c[0]);
            }
            check(!MenuScan.isSafeDialCode(null) && !MenuScan.isSafeDialCode(""), "null and empty are refused");
            check(MenuScan.SAFE_CODES.length >= 6, "the list has the useful codes");
        }

        // ---- SAFETY: the dial command exists only for the codes on the list
        {
            java.util.Set<String> allowed = new java.util.HashSet<>();
            java.util.Set<String> uris = new java.util.HashSet<>();
            for (String[] c : MenuScan.SAFE_CODES) {
                allowed.add(c[0]);
                uris.add(MenuScan.dialUri(c[0]));
                String cmd = MenuScan.dialCommand(c[0]);
                check(cmd != null && cmd.equals("input keyevent 224; am start -a android.intent.action.DIAL -d '" + MenuScan.dialUri(c[0]) + "' 2>&1 | head -6"),
                        "a listed code gives exactly the expected command: " + c[0]);
            }
            check(!allowed.contains("*#197328640#"), "the redundant Samsung alias is not on the list");
            // the codes people know can erase or alter the phone, and many more
            String[] bad = {"*2767*3855#", "*2767*2878#", "*2767*927#", "*2767*49927#", "*2767*688#", "*#7780#", "*#*#7780#*#*", "*#9900#", "*#9090#",
                "*#7284#", "*#872564#", "*#0808#", "*#2663#", "*#34971539#", "*#2580#", "*#272*123456789012345#", "*#12580*369#", "*#*#2846579#*#*",
                "*#*#273283*255*663282*#*#*", "*#*#2432546#*#*", "*#*#7594#*#*", "*#8999*8378#", "*#4238378#", "*#232331#", "*#0283#", "*#0228#",
                "**04*1234*5678*5678#", "**05*12345678*1111*1111#", "##72786#", "##786#", "##3282#", "##778#", "*228#", "*#21#", "*21*5551234#",
                "*#*#197328640#*#*", "*#197328640#", "*#3282*727336*#", "#*#", "*#*", "*", "#", "", "0", "911", "112", "*#06", "#06#", "*#06#*",
                "*#0011", "*#2263", "*#*#4636#*#", "*#*#4636#*#*#", "*#*#3646633#*#"};
            for (String c : bad) {
                check(MenuScan.dialCommand(c) == null, "SAFE: no dial command for " + c);
            }
            check(MenuScan.dialCommand(null) == null, "SAFE: none for null");
            // every one-character change (insert, delete, replace) of every listed code, over a wide alphabet
            String alphabet = "0123456789*#+ ;,'\"\\\n\t%$`&|<>()-_.abcxyzXYZ\u00A0\u200B\uFF0A\uFF03\u0662";
            int neighbours = 0;
            for (String c : allowed) {
                for (int i = 0; i <= c.length(); i++) {
                    for (int k = 0; k < alphabet.length(); k++) {
                        String ins = c.substring(0, i) + alphabet.charAt(k) + c.substring(i);
                        check(MenuScan.dialCommand(ins) == null || allowed.contains(ins), "SAFE: no command for an altered code (insert): " + ins.replace("\n", "\\n"));
                        neighbours++;
                        if (i < c.length()) {
                            String rep = c.substring(0, i) + alphabet.charAt(k) + c.substring(i + 1);
                            check(MenuScan.dialCommand(rep) == null || allowed.contains(rep), "SAFE: no command for an altered code (replace): " + rep.replace("\n", "\\n"));
                            neighbours++;
                        }
                    }
                    if (i < c.length()) {
                        String del = c.substring(0, i) + c.substring(i + 1);
                        check(MenuScan.dialCommand(del) == null || allowed.contains(del), "SAFE: no command for an altered code (delete): " + del);
                        neighbours++;
                    }
                }
                check(MenuScan.dialCommand(c + " ") == null && MenuScan.dialCommand(" " + c) == null && MenuScan.dialCommand(c + "\n") == null
                        && MenuScan.dialCommand(c.toLowerCase() + "x") == null, "SAFE: whitespace around a listed code makes it a different string: " + c);
            }
            check(neighbours > 3000, "many altered codes were tried: " + neighbours);
            // two codes glued together, a list of them, URL-encoded forms
            for (String a1 : allowed) {
                for (String b1 : allowed) {
                    check(MenuScan.dialCommand(a1 + b1) == null && MenuScan.dialCommand(a1 + ";" + b1) == null && MenuScan.dialCommand(a1 + "," + b1) == null,
                            "SAFE: two codes together are not a code: " + a1 + b1);
                }
                check(MenuScan.dialCommand(MenuScan.dialUri(a1)) == null && MenuScan.dialCommand(MenuScan.dialUri(a1).substring(4)) == null,
                        "SAFE: the encoded form is not accepted as a code: " + a1);
            }
            // random strings: a command only ever exists for a listed code, and only ever holds a listed code's address
            java.util.Random g = new java.util.Random(31337);
            String pool = "0123456789**##**##+;,' \"%\n\\abcdef";
            int made = 0;
            for (int run = 0; run < 300000; run++) {
                int n = g.nextInt(18);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    sb.append(pool.charAt(g.nextInt(pool.length())));
                }
                String code = sb.toString();
                String cmd = MenuScan.dialCommand(code);
                if (cmd != null) {
                    made++;
                    check(allowed.contains(code), "SAFE: a command was made for a code that is not listed: " + code);
                }
            }
            check(made == 0 || made > 0, "random strings were tried (" + made + " were listed codes)");
            // whatever the command contains, its address is a listed code's address and it holds no other dial or call
            for (String c : allowed) {
                String cmd = MenuScan.dialCommand(c);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("-d '([^']*)'").matcher(cmd);
                check(m.find() && uris.contains(m.group(1)) && !m.find(), "the command holds exactly one address and it is a listed one: " + cmd);
                check(cmd.indexOf("tel:") == cmd.lastIndexOf("tel:") && !cmd.contains("CALL") && !cmd.contains("ussd") && !cmd.contains(";" + "am "),
                        "and no second dial, call or command: " + cmd);
            }
        }

        // ---- SAFETY: screens that suggest wiping, resetting, IMEI, NVRAM, flashing or unlocking are never listed or opened
        {
            String[] bad = {"com.x/.FactoryReset", "com.x/.WipeData", "com.x/.EraseUserData", "com.x/.ImeiWriter", "com.mediatek.engineermode/.NvramBackup",
                "com.x/.NvdataRestore", "com.x/.UnlockActivity", "com.x/.SimLockSettings", "com.x/.FlashTool", "com.x/.FirmwareUpgrade",
                "com.x/.OdinMode", "com.x/.RsuActivity", "com.x/.FormatStorage", "com.x/.RecoveryMenu", "com.x/.BootloaderInfo", "com.x/.OemUnlock",
                "com.x/.EfsBackup", "com.x/.FotaUpdate", "com.x/.ResetNetwork", "com.x/.DOWNLOADMODE", "com.x/.RepartitionDisk", "com.x/.KnoxSettings",
                "com.factory.test/.Main", "com.x/.ActivationActivity", "com.x/.CalibrationTool", "com.x/.FirmwareInfo", "com.x/.ModemFirmwareMenu"};
            for (String c : bad) {
                check(MenuScan.isComponent(c) && !MenuScan.safeScreen(c), "SAFE: never opens " + c);
            }
            String[] good = {"com.mediatek.engineermode/.EngineerMode", "com.sec.android.RilServiceModeApp/.ServiceModeApp",
                "com.sec.android.app.servicemodeapp/.ServiceModeApp", "com.sec.android.app.modemui/.activities.ShowBandActivity",
                "com.android.phone/.settings.RadioInfo", "com.android.settings/.RadioInfo", "com.x/.PhoneInformation", "com.x/.BatteryInformation",
                "com.mediatek.engineermode/.bandselect.BandModeSelect", "com.x/.NetworkModeSelect", "com.x/.PerformanceInfo"};
            for (String c : good) {
                check(MenuScan.safeScreen(c), "a harmless screen is not hidden: " + c);
            }
            for (String c : MenuScan.USUAL_SCREENS) {
                check(MenuScan.safeScreen(c), "every 'usual' screen passes the filter: " + c);
            }
            check(!MenuScan.safeScreen(null) && !MenuScan.safeScreen("") && !MenuScan.safeScreen("a/b;reset"), "junk is refused");
            check(MenuScan.parseActivities("com.x/.FactoryReset\ncom.mediatek.engineermode/.EngineerMode\ncom.x/.ImeiWriter\n").equals(Arrays.asList("com.mediatek.engineermode/.EngineerMode")),
                    "dangerous screens never get into the list");
            MenuScan.Reply rr = MenuScan.parseReply("MENUS 1\nACT com.x/.FactoryReset\nACT com.mediatek.engineermode/.EngineerMode\nACT com.x/.WipeData\n");
            check(rr.activities.size() == 1 && rr.activities.get(0).contains("EngineerMode"), "and not from a reply either (an old or altered phone app)");
            check(!MenuScan.PACKAGE_WORDS.toLowerCase().contains("factory") && !MenuScan.PACKAGE_WORDS.contains("diagnos") && !MenuScan.PACKAGE_WORDS.contains("testmode"),
                    "factory, diagnostic and test-mode apps are not even searched for");
            check(!MenuScan.DISCOVER_COMMAND.toLowerCase().contains("factory"), "and the real command does not look for them");
            String sum = MenuScan.summary(MenuScan.parseReply(MenuScan.reply(MenuScan.parseDiscovery(discovery(PACKAGES, RECEIVERS)), new ArrayList<String>())));
            check(sum.contains("will not dial them"), "the summary says that only listed codes are dialed: " + sum);
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
        check(out.length() < 200_000, "the discovery output stays under what Priv.run keeps (200,000 bytes): " + out.length() + " bytes");
        // the worst case the command allows: 60 package lines and 900 lines of 160 characters
        check(60 * 80 + 900 * 161 < 200_000, "and even the largest output the command allows fits");
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
