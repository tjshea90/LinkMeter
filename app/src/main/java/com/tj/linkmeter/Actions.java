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

    private Actions() {}

    /** These cut the link to the tablet, so the phone answers first and acts a moment later. */
    static boolean isDelayed(String name) {
        return name.equals("airplane_on") || name.equals("hotspot_off") || name.equals("poweroff");
    }

    private static String delayedCommand(String name) {
        switch (name) {
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

    static void later(final String name) {
        new Thread(() -> {
            try {
                Thread.sleep(800);
                Priv.run(delayedCommand(name), 40);
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
            case "net_get":
                return "Allowed network types:\n" + Priv.run(GET_TYPES, 15).out;
            case "net_lte":
                return setNr(false);
            case "net_5g":
                return setNr(true);
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
     * the 5G bit, and writing it back. Nothing is written if the read looks wrong.
     */
    private static String setNr(boolean allow5g) throws IOException {
        Priv.Result g = Priv.run(GET_TYPES, 15);
        long mask = parseMask(g.out);
        if (mask < 0) {
            return "Couldn't read the phone's current network types, so nothing was changed.\n"
                    + "The phone said:\n" + g.out;
        }
        long target = allow5g ? (mask | NR_BIT) : (mask & ~NR_BIT);
        if (target == mask) {
            return allow5g ? "5G was already allowed." : "5G was already off (4G/LTE mode).";
        }
        StringBuilder log = new StringBuilder();
        String[] tries = {SET_TYPES + " " + target, SET_TYPES + " -s 0 " + target};
        for (String t : tries) {
            Priv.Result s = Priv.run(t, 15);
            log.append(s.out).append('\n');
            String low = s.out.toLowerCase();
            if (s.rc == 0 && !low.contains("usage") && !low.contains("unknown")
                    && !low.contains("error") && !low.contains("fail")) {
                break;
            }
        }
        long now = parseMask(Priv.run(GET_TYPES, 15).out);
        if (now == target) {
            return allow5g ? "5G is allowed again." : "Forced to 4G (5G turned off).";
        }
        return "The phone did not accept the change.\n" + log.toString().trim();
    }

    /** Finds the network-types bitmask in the command's output. Returns -1 if it can't be trusted. */
    static long parseMask(String out) {
        Matcher m = Pattern.compile("\\b(\\d{3,})\\b").matcher(out);
        while (m.find()) {
            try {
                long v = Long.parseLong(m.group(1));
                if ((v & LTE_BIT) != 0 && v < (1L << 24)) {
                    return v;
                }
            } catch (NumberFormatException ignored) {
                // too long to be a mask
            }
        }
        return -1;
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
            "dumpsys telephony.registry 2>&1 | grep -iE 'mServiceState|mCellInfo|mSignalStrength|"
                    + "mDataConnectionState|mDataNetworkType' | cut -c1-500 | head -12",
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
