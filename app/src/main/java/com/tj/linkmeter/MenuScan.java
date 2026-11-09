package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * No Android in here (tested in tools/test/MenuScanTest.java). Finds the phone's hidden service menus (Samsung's band menu, the
 * MediaTek engineering menu, the "phone information" screen), which sometimes allow what Android's own API does not.
 *
 * Three things are looked for, all with commands the shell user may run:
 *  1. packages whose names suggest an engineering or service app (pm list packages),
 *  2. the dialer "secret codes" the phone has a receiver for (dumpsys package, the receiver table: filters with the scheme
 *     android_secret_code; each filter lists its codes as authorities), which tells which codes exist on THIS phone,
 *  3. the screens (activities) of the packages from step 1, which the shell may open with am start even when they are not exported.
 * The phone sends the tablet a short reply in lines ("PKG ...", "CODE ...", "ACT ...", "NOTE ..."); the tablet builds its buttons
 * from it. Everything that is passed back to a shell command (a component, a dial code) is checked here first.
 *
 * Lesson of the Bands scan (HANDOFF): never hold a big output in a shell variable or an argument on the phone; pipe it.
 */
final class MenuScan {
    private MenuScan() {}

    static final String PACKAGES_MARK = "== packages";
    static final String CODES_MARK = "== codes";
    /** Words in a package name that suggest a service or engineering app. */
    static final String PACKAGE_WORDS = "engineer|servicemode|modemui|rilservice|hiddenmenu|sysscope|mediatek|mtk|secphone"
            + "|cqatest|bandselect|networkmode|radioinfo";

    /**
     * Step 1 and 2 in one command, pipes only (the dump is large). The output is at most 60 + 900 lines of 160 characters: well under
     * the 200,000 bytes that Priv.run keeps (more would cut off its exit-code marker).
     */
    static final String DISCOVER_COMMAND = "echo '" + PACKAGES_MARK + "'; pm list packages 2>&1 | grep -iE '" + PACKAGE_WORDS
            + "' | sed 's/^package://' | sort | head -60; echo '" + CODES_MARK + "'; "
            + "dumpsys package r receiver 2>&1 | grep -B8 -A90 'Scheme: \"android_secret_code\"' | cut -c1-160 | head -900";

    /**
     * The ONLY dialer codes the app will dial: they show information or open a band / network menu. A free-text code would be a
     * risk to the phone: Samsung has codes that wipe it at once (for example *2767*3855#), and others that reset settings. So there
     * is no way to dial anything else from here, whatever the tablet asks for.
     */
    static final String[][] SAFE_CODES = {
        {"*#2263#", "band selection (Samsung)"},
        {"*#0011#", "service mode: radio status (Samsung)"},
        {"*#0*#", "hardware test menu (Samsung)"},
        {"*#06#", "shows the IMEI"},
        {"*#1234#", "shows the software versions (Samsung)"},
        {"*#*#4636#*#*", "phone information and network type (Android)"},
        {"*#*#3646633#*#*", "engineering mode (MediaTek): a powerful technician menu, change nothing but bands"},
    };

    /**
     * Screens that are never listed and never opened: a name that suggests wiping, resetting, IMEI or NVRAM work, flashing or
     * firmware, unlocking, or backup and restore of the radio's memory. Looking at a service menu is safe; these are the entries
     * where one wrong tap can damage the phone for good.
     */
    private static final Pattern DANGEROUS_SCREEN = Pattern.compile("(?i)reset|wipe|erase|(?<!in)format|flash|imei|nvram|nvdata|factory"
            + "|recovery|rsu|simlock|sim_lock|unlock|bootloader|oem|root|fuse|efs|backup|restore|download|odin|fota|upgrade|update"
            + "|firmware|provision|activation|calibrat|repartition|partition|security|knox");

    /** Codes worth pointing out, with what they usually are. */
    static final String[][] KNOWN_CODES = {
        {"2263", "band selection (Samsung)"},
        {"0011", "service mode (Samsung)"},
        {"4636", "phone information / testing"},
        {"3646633", "MediaTek engineering mode"},
        {"197328640", "service menu (Samsung)"},
        {"0*", "hardware test (Samsung)"},
    };

    /** Screens worth trying first on any phone: opening one that does not exist only answers "does not exist". */
    static final String[] USUAL_SCREENS = {
        "com.mediatek.engineermode/.EngineerMode",
        "com.sec.android.RilServiceModeApp/.ServiceModeApp",
        "com.sec.android.app.servicemodeapp/.ServiceModeApp",
        "com.sec.android.app.modemui/.activities.ShowBandActivity",
        "com.android.phone/.settings.RadioInfo",
        "com.android.settings/.RadioInfo",
    };

    private static final Pattern COMPONENT = Pattern.compile("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+");
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9_.]+");
    private static final Pattern DIAL = Pattern.compile("[0-9*#]{1,24}");
    private static final Pattern FILTER_HEADER = Pattern.compile("([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)\\s+filter\\b");
    private static final Pattern AUTHORITY = Pattern.compile("Authority:\\s*\"([^\"]*)\"");

    /** What the discovery command found. */
    static final class Found {
        final List<String> packages = new ArrayList<>();
        /** {code, component}, sorted as found. */
        final List<String[]> codes = new ArrayList<>();
        /** The part of the output that holds the codes was there (so "no codes" means none, not "could not look"). */
        boolean sawCodesSection;
    }

    static boolean isComponent(String s) {
        return s != null && s.length() <= 200 && COMPONENT.matcher(s).matches();
    }

    static boolean isPackage(String s) {
        return s != null && s.length() <= 120 && PACKAGE.matcher(s).matches();
    }

    static boolean isDialCode(String s) {
        return s != null && DIAL.matcher(s).matches();
    }

    /** Is this exactly one of the codes the app may dial? */
    static boolean isSafeDialCode(String s) {
        if (s == null) {
            return false;
        }
        for (String[] c : SAFE_CODES) {
            if (c[0].equals(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The shell command that opens the phone's dialer with a service code typed in, or null unless the code is EXACTLY one of
     * {@link #SAFE_CODES}. This is the only place in the app where a dial command is built, so a code that is not on the list cannot
     * reach the phone whatever asks for it. (A tripwire test fails the build if "android.intent.action.DIAL" ever appears anywhere
     * else in the app.)
     */
    static String dialCommand(String code) {
        if (!isSafeDialCode(code)) {
            return null;
        }
        // 224 = wake up, so the dialer is seen
        return "input keyevent 224; am start -a android.intent.action.DIAL -d '" + dialUri(code) + "' 2>&1 | head -6";
    }

    /** A screen the app may list and open: a well-formed name that does not suggest anything destructive. */
    static boolean safeScreen(String component) {
        return isComponent(component) && !DANGEROUS_SCREEN.matcher(component).find();
    }

    /** The tel: address for a dial code: # and * must be written as %23 and %2A. */
    static String dialUri(String code) {
        return "tel:" + code.replace("*", "%2A").replace("#", "%23");
    }

    /** Reads the output of {@link #DISCOVER_COMMAND}. Never throws. */
    static Found parseDiscovery(String out) {
        Found f = new Found();
        if (out == null) {
            return f;
        }
        Set<String> pk = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        boolean inPackages = false;
        boolean inCodes = false;
        String component = null;
        boolean secret = false;
        for (String raw : out.split("\n")) {
            String line = raw.trim();
            if (line.equals(PACKAGES_MARK)) {
                inPackages = true;
                inCodes = false;
                continue;
            }
            if (line.equals(CODES_MARK)) {
                inPackages = false;
                inCodes = true;
                f.sawCodesSection = true;
                continue;
            }
            if (inPackages) {
                if (isPackage(line)) {
                    pk.add(line);
                }
            } else if (inCodes) {
                if (line.startsWith("--")) { // grep's separator between two groups of lines
                    component = null;
                    secret = false;
                    continue;
                }
                Matcher h = FILTER_HEADER.matcher(line);
                if (h.find()) {
                    component = h.group(1);
                    secret = false;
                    continue;
                }
                if (line.startsWith("Scheme:")) {
                    secret = line.contains("\"android_secret_code\"");
                    continue;
                }
                Matcher a = AUTHORITY.matcher(line);
                if (secret && component != null && a.find()) {
                    String code = a.group(1);
                    if (!code.isEmpty() && code.length() <= 24 && seen.add(code + " " + component)) {
                        f.codes.add(new String[] {code, component});
                    }
                }
            }
        }
        f.packages.addAll(pk);
        return f;
    }

    /** The command that lists the screens of these packages (null if there are none to look at). */
    static String activitiesCommand(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String p : packages) {
            if (isPackage(p) && n < 12) {
                sb.append(" -e '").append(p).append("/'");
                n++;
            }
        }
        if (n == 0) {
            return null;
        }
        return "dumpsys package r activity 2>&1 | grep -E ' filter ' | grep -F" + sb
                + " | sed 's/^ *[0-9a-f]* //; s/ filter .*//' | sort -u | head -120";
    }

    /** Components from the output of the activities command. */
    static List<String> parseActivities(String out) {
        Set<String> r = new LinkedHashSet<>();
        if (out != null) {
            for (String raw : out.split("\n")) {
                String line = raw.trim();
                if (safeScreen(line)) {
                    r.add(line);
                }
            }
        }
        return new ArrayList<>(r);
    }

    /** What the phone sends the tablet. */
    static String reply(Found f, List<String> activities) {
        StringBuilder sb = new StringBuilder("MENUS 1\n");
        for (int i = 0; i < f.packages.size() && i < 60; i++) {
            sb.append("PKG ").append(f.packages.get(i)).append('\n');
        }
        for (int i = 0; i < f.codes.size() && i < 100; i++) {
            sb.append("CODE ").append(f.codes.get(i)[0]).append(' ').append(f.codes.get(i)[1]).append('\n');
        }
        for (int i = 0; i < activities.size() && i < 80; i++) {
            sb.append("ACT ").append(activities.get(i)).append('\n');
        }
        if (!f.sawCodesSection) {
            sb.append("NOTE the phone's list of dialer codes could not be read\n");
        } else if (f.codes.isEmpty()) {
            sb.append("NOTE no dialer codes are registered that this app can see (or the list was not allowed)\n");
        }
        if (f.packages.isEmpty()) {
            sb.append("NOTE no service or engineering apps were found by name\n");
        }
        return sb.toString();
    }

    /** What the tablet read from the phone's reply. */
    static final class Reply {
        final List<String> packages = new ArrayList<>();
        final List<String[]> codes = new ArrayList<>();
        final List<String> activities = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        boolean valid;
    }

    static Reply parseReply(String text) {
        Reply r = new Reply();
        if (text == null) {
            return r;
        }
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.equals("MENUS 1")) {
                r.valid = true;
            } else if (line.startsWith("PKG ") && isPackage(line.substring(4))) {
                r.packages.add(line.substring(4));
            } else if (line.startsWith("CODE ")) {
                String[] p = line.substring(5).split(" ");
                if (p.length == 2 && !p[0].isEmpty() && p[0].length() <= 24 && isComponent(p[1])) {
                    r.codes.add(new String[] {p[0], p[1]});
                }
            } else if (line.startsWith("ACT ") && safeScreen(line.substring(4))) {
                r.activities.add(line.substring(4));
            } else if (line.startsWith("NOTE ")) {
                r.notes.add(line.substring(5));
            }
        }
        return r;
    }

    /** What a code is usually for, or "". */
    static String knownCode(String code) {
        for (String[] k : KNOWN_CODES) {
            if (k[0].equals(code)) {
                return k[1];
            }
        }
        return "";
    }

    /** The words the tablet shows about a reply. */
    static String summary(Reply r) {
        if (!r.valid) {
            return "The phone did not answer in the expected form (an older phone app? update it from the Control tab).";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(r.packages.size()).append(r.packages.size() == 1 ? " app" : " apps").append(" that may hold a service menu");
        sb.append(", ").append(r.codes.size()).append(r.codes.size() == 1 ? " dialer code" : " dialer codes").append(" registered");
        sb.append(", ").append(r.activities.size()).append(r.activities.size() == 1 ? " screen" : " screens").append(" to try.\n");
        for (String p : r.packages) {
            sb.append("  app ").append(p).append('\n');
        }
        for (String[] c : r.codes) {
            String k = knownCode(c[0]);
            sb.append("  code ").append(c[0]).append(k.isEmpty() ? "" : " (" + k + ")").append(" -> ").append(c[1]).append('\n');
        }
        for (String n : r.notes) {
            sb.append("  note: ").append(n).append('\n');
        }
        sb.append("  (Only the codes in the list below are offered to dial. Others are shown so you know they exist, but this app will not "
                + "dial them: some service codes erase the phone.)");
        return sb.toString().trim();
    }

    /** True when am start's own words say the screen did start (it prints "Starting: Intent ..." and no "Error"). */
    static boolean started(String amOut) {
        return amOut != null && amOut.contains("Starting:") && !amOut.contains("Error") && !amOut.contains("Exception");
    }
}
