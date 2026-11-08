package com.tj.linkmeter;

import android.content.Context;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Phone only: what the tablet's Control tab can ask the phone to do. All of it goes through {@link Priv}. */
final class Actions {
    /** Bit for 5G (NR) in Android's allowed-network-types bitmask: 1 << (NETWORK_TYPE_NR - 1). */
    private static final long NR_BIT = 1L << 19;
    /** Bit for LTE: 1 << (NETWORK_TYPE_LTE - 1). */
    private static final long LTE_BIT = 1L << 12;
    private static final String GET_TYPES = "cmd phone get-allowed-network-types-for-users";
    private static final String SET_TYPES = "cmd phone set-allowed-network-types-for-users";

    /** What the telephony-API route said last time it could not be used, for the error message. */
    private static volatile String apiNote = "";

    private Actions() {}

    /** These cut the link to the tablet, so the phone answers first and acts a moment later. */
    static boolean isDelayed(String name) {
        return name.equals("airplane_on") || name.equals("hotspot_off") || name.equals("poweroff")
                || name.equals("hotspot_restart");
    }

    private static String delayedCommand(Context c, String name) {
        switch (name) {
            case "hotspot_restart":
                return Cells.mainCommand(c, "hotspot-restart");
            case "airplane_on":
                return "cmd connectivity airplane-mode enable";
            case "hotspot_off":
                // Airplane mode switches the hotspot off; switching it back off restores mobile data.
                return "cmd connectivity airplane-mode enable; sleep 3; "
                        + "cmd connectivity airplane-mode disable";
            default:
                return "svc power shutdown || reboot -p";
        }
    }

    static void later(final Context c, final String name) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                Thread.sleep(800);
                Priv.run(delayedCommand(app, name), 60);
            } catch (IOException | InterruptedException ignored) {
                // the tablet has already lost the link; nothing to report to
            }
        }, "linkmeter-delayed").start();
    }

    /** Runs an action and returns text for the tablet to show. */
    static String run(Context c, String name) throws IOException {
        switch (name) {
            case "airplane_off":
                return report(Priv.run("cmd connectivity airplane-mode disable", 15), "Airplane mode is off.");
            case "screen_off":
                return report(Priv.run("input keyevent 223", 10), "Screen is off.");
            case "eco_on":
                Eco.setSuppressed(false);
                return Eco.apply(c);
            case "eco_off":
                Eco.setSuppressed(true);
                return Eco.restore(c);
            case "setup":
                return setup(c);
            case "hotspot_info":
                return Cells.shellMain(c, "hotspot-info").out;
            case "hotspot_band_2":
                return Cells.shellMain(c, "hotspot-band 2").out;
            case "hotspot_band_5":
                return Cells.shellMain(c, "hotspot-band 5").out;
            case "hotspot_band_auto":
                return Cells.shellMain(c, "hotspot-band auto").out;
            case "net_get":
                return netGet(c);
            case "net_lte":
                return setNr(c, false);
            case "net_5g":
                return setNr(c, true);
            default:
                throw new IOException("unknown action: " + name);
        }
    }

    private static String report(Priv.Result r, String okText) {
        return r.ok() ? okText : "Failed (code " + r.rc + "): " + r.out;
    }

    /** One-time grants that make the phone side reliable. Safe to run again. */
    private static String setup(Context c) throws IOException {
        String p = c.getPackageName();
        String cmd = "cmd deviceidle whitelist +" + p
                + "; am set-standby-bucket " + p + " active"
                + "; appops set " + p + " MANAGE_EXTERNAL_STORAGE allow"
                + "; pm grant " + p + " android.permission.READ_PHONE_STATE"
                + "; pm grant " + p + " android.permission.POST_NOTIFICATIONS";
        Priv.Result r = Priv.run(cmd, 25);
        return "Setup ran (battery exemption, all-files access, phone permission).\n" + r.out;
    }

    /**
     * Turns 5G on or off by reading the phone's allowed network types, changing only
     * the 5G entry, and writing it back. Nothing is written if the read looks wrong,
     * and every write is checked by reading the setting back.
     */
    private static String setNr(Context c, boolean allow5g) throws IOException {
        String viaApi = setNrViaApi(c, allow5g);
        if (viaApi != null) {
            return viaApi;
        }
        Priv.Result g = Priv.run(GET_TYPES, 15);
        long mask = parseTypes(g.out);
        if (mask < 0 || (mask & LTE_BIT) == 0) {
            return "Couldn't read the phone's current network types, so nothing was changed.\n"
                    + "The phone said:\n" + g.out;
        }
        long target = allow5g ? (mask | NR_BIT) : (mask & ~NR_BIT);
        if (target == mask) {
            return allow5g ? "5G is already allowed (" + typesText(mask) + ")."
                    : "5G is already off. The phone is on " + typesText(mask) + ".";
        }
        // The phone prints types as words ("LTE|LTE_CA"); which form the set command takes
        // is not documented, so try the likely ones and keep the first that reads back right.
        String[] forms = {"'" + typesText(target) + "'", String.valueOf(target)};
        StringBuilder log = new StringBuilder();
        for (String f : forms) {
            for (String slot : new String[] {"", "-s 0 "}) {
                String cmd = SET_TYPES + " " + slot + f;
                Priv.Result s = Priv.run(cmd, 15);
                log.append("$ ").append(cmd).append('\n').append(s.out).append('\n');
                if (parseTypes(Priv.run(GET_TYPES, 15).out) == target) {
                    return allow5g ? "5G is allowed again (" + typesText(target) + ")."
                            : "Forced to 4G. The phone is now on " + typesText(target) + ".";
                }
            }
        }
        Priv.Result usage = Priv.run(SET_TYPES + " 2>&1 | head -20", 15);
        return "The phone did not accept the change.\n" + log.toString().trim()
                + "\nUsage the phone printed:\n" + usage.out
                + (apiNote.isEmpty() ? "" : "\nThe telephony API route said:\n" + apiNote);
    }

    /** The phone's allowed network types, from the telephony API (first choice) or the shell command. */
    private static String netGet(Context c) throws IOException {
        long viaApi = parseOk(Cells.shellMain(c, "nt-get").out);
        if (viaApi >= 0) {
            return "Allowed network types: " + typesText(viaApi)
                    + ((viaApi & NR_BIT) != 0 ? " (5G is allowed)" : " (5G is off)");
        }
        Priv.Result r = Priv.run(GET_TYPES, 15);
        long m = parseTypes(r.out);
        if (m >= 0) {
            return "Allowed network types: " + typesText(m)
                    + ((m & NR_BIT) != 0 ? " (5G is allowed)" : " (5G is off)");
        }
        return "Allowed network types (shell command):\n" + r.out;
    }

    /**
     * The same read-change-verify as {@link #setNr}, but through the phone's own telephony API
     * (run as the shell user by ShellMain). Returns null if that route isn't available here,
     * so the caller can try the shell command instead.
     */
    private static String setNrViaApi(Context c, boolean allow5g) throws IOException {
        Priv.Result first = Cells.shellMain(c, "nt-get");
        long mask = parseOk(first.out);
        if (mask < 0 || (mask & LTE_BIT) == 0) {
            apiNote = first.out;
            return null;
        }
        apiNote = "";
        long target = allow5g ? (mask | NR_BIT) : (mask & ~NR_BIT);
        if (target == mask) {
            return allow5g ? "5G is already allowed (" + typesText(mask) + ")."
                    : "5G is already off. The phone is on " + typesText(mask) + ".";
        }
        Priv.Result s = Cells.shellMain(c, "nt-set " + target);
        long now = parseOk(Cells.shellMain(c, "nt-get").out);
        if (now == target) {
            return allow5g ? "5G is allowed again (" + typesText(target) + ")."
                    : "Forced to 4G. The phone is now on " + typesText(target) + ".";
        }
        return "The phone did not accept the change.\n" + s.out;
    }

    /** Reads "OK 12345" from ShellMain's output. Returns -1 if the output isn't that. */
    private static long parseOk(String out) {
        Matcher m = Pattern.compile("(?m)^OK (\\d+)\\s*$").matcher(out);
        if (!m.find()) {
            return -1;
        }
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Network type names in order of Android's NETWORK_TYPE_* numbers (index = number; bit = 1 << (number - 1)). */
    private static final String[] TYPE_NAMES = {"", "GPRS", "EDGE", "UMTS", "CDMA", "EVDO_0", "EVDO_A",
        "1xRTT", "HSDPA", "HSUPA", "HSPA", "IDEN", "EVDO_B", "LTE", "EHRPD", "HSPAP", "GSM",
        "TD_SCDMA", "IWLAN", "LTE_CA", "NR"};

    /** Writes a bitmask as the phone prints it, for example "LTE|LTE_CA|NR". */
    static String typesText(long mask) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < TYPE_NAMES.length; i++) {
            if ((mask & (1L << (i - 1))) != 0) {
                sb.append(sb.length() > 0 ? "|" : "").append(TYPE_NAMES[i]);
            }
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    /**
     * Reads the shell command's answer, either a number or words such as "LTE|LTE_CA".
     * Returns -1 if any word is not recognised, so a misread can never be written back.
     */
    static long parseTypes(String out) {
        String line = "";
        for (String l : out.split("\n")) {
            if (!l.trim().isEmpty()) {
                line = l.trim();
            }
        }
        int colon = line.lastIndexOf(':');
        if (colon >= 0) {
            line = line.substring(colon + 1).trim();
        }
        if (line.matches("\\d{1,9}")) {
            return Long.parseLong(line);
        }
        long mask = 0;
        for (String tok : line.split("[|,\\s]+")) {
            if (tok.isEmpty()) {
                continue;
            }
            String t = tok.equals("HSPA+") ? "HSPAP" : tok;
            int idx = -1;
            for (int i = 1; i < TYPE_NAMES.length; i++) {
                if (TYPE_NAMES[i].equalsIgnoreCase(t)) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                return -1;
            }
            mask |= 1L << (idx - 1);
        }
        return mask == 0 ? -1 : mask;
    }

    /** Facts about this phone's shell commands, for working out what it supports. Contains no IDs. */
    static String probe() throws IOException {
        String[] cmds = {
            "getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk;"
                    + " getprop ro.board.platform; getprop gsm.version.baseband",
            "id",
            "cmd phone help 2>&1 | head -150",
            GET_TYPES + " 2>&1",
            "settings get global preferred_network_mode",
            "cmd connectivity airplane-mode 2>&1",
            "cmd wifi help 2>&1 | grep -iE 'softap|tether' | head -10",
            SET_TYPES + " 2>&1 | head -20",
            "cmd phone radio help 2>&1 | head -40",
            "dumpsys telephony.registry 2>&1 | grep -iE 'mServiceState|mSignalStrength|"
                    + "mDataConnectionState|mDataNetworkType' | cut -c1-500 | head -8",
            "dumpsys telephony.registry 2>&1 | grep -i 'mCellInfo' | cut -c1-3000 | head -3",
            "getprop 2>&1 | grep -iE 'ril|radio|telephony|mtk' "
                    + "| grep -viE 'imei|imsi|iccid|serial|msisdn|number' | head -40",
        };
        StringBuilder sb = new StringBuilder();
        for (String cmd : cmds) {
            sb.append("$ ").append(cmd).append('\n');
            sb.append(Priv.run(cmd, 20).out).append("\n\n");
        }
        return sb.toString();
    }
}
