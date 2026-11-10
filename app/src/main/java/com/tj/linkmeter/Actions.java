package com.tj.linkmeter;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
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

    /**
     * How the last radio restart went, for the tablet to ask after the link is back: "none", "running", "done" (the radio was
     * switched off and on) or "failed: why". The restart drops the hotspot, so the tablet cannot be told when it ends by the reply.
     */
    private static volatile String cycleState = "none";

    /**
     * Airplane mode started from the tablet ends by itself after this long: the tablet cannot end it (the link goes down with the
     * radio), so without this a forgotten "Airplane on" would leave the phone offline until someone picks it up.
     */
    static final long AIRPLANE_AUTO_OFF_MS = 15L * 60 * 1000;
    /** A deliberate radio-off is over in a few seconds; if it is still on after this long, the safety net switches the radio back on. */
    private static final long BLIP_GRACE_MS = 45L * 1000;

    /**
     * The phone could not do what was asked: the switch did not move. The message is a whole sentence for the user. Asked over
     * a link that is still there, the tablet gets it as an error (409, see Api.act), so "it worked" is only ever said when it
     * did; after an answer-first action nobody is left to tell (see {@link #later}).
     */
    static final class NotDone extends IOException {
        NotDone(String sentence) {
            super(sentence);
        }
    }

    /** Called before the reply to an answer-first action is sent, so that a question asked right after it gets the right answer. */
    static void prepareDelayed(String name) {
        if (name.equals("radio_cycle")) {
            cycleState = "running";
        }
    }

    /**
     * For an action that takes away the link it was asked over (see {@link RemoteRoutes#cutsLink}): the phone has already answered
     * "Sent", and carries the action out a moment later. Nobody can be told how it went, except through what the action records
     * (the radio restart's state, the hotspot's result) and the next status request.
     */
    static void later(final Context c, final String name, final boolean viaBluetooth) {
        final Context app = c.getApplicationContext();
        final Object hold = new Object();
        Awake.work(app, hold, 90_000L); // the phone may be asleep, and the link that kept it awake is about to go
        new Thread(() -> {
            try {
                Thread.sleep(800);
                perform(app, name, viaBluetooth);
            } catch (IOException | InterruptedException | RuntimeException e) {
                // The tablet has already lost the link; nothing to report to (but a radio restart records how it went, unless
                // perform already did). RuntimeException too: one that escaped this thread would end the phone's app.
                if (name.equals("radio_cycle") && cycleState.equals("running")) {
                    cycleState = "failed: " + e.getMessage();
                }
            } finally {
                Awake.workDone(hold); // done, or given up: the phone may sleep again (a Standby should not sit awake for 90 s)
            }
        }, "linkmeter-delayed").start();
    }

    /** The answer as text, or as the error it is when the switch did not move. */
    private static String said(SwitchWords.Said s) throws NotDone {
        if (!s.done) {
            throw new NotDone(s.text);
        }
        return s.text;
    }

    /**
     * The actions that can take a link away: the work itself, and what happened in plain words (the words of the switches are
     * SwitchWords'). Called a moment after the answer when the action cuts the link it came over ({@link #later}), and at once
     * when it does not (over Bluetooth), so that the tablet then gets the real result. Returns only when the phone did it (or,
     * for the hotspot, when the phone was asked and cannot say whether it did: the sentence then says exactly that); throws
     * {@link NotDone} when it plainly did not.
     */
    static String perform(Context app, String name, boolean viaBluetooth) throws IOException {
        switch (name) {
            case "hotspot_on":
            case "boot_hotspot_on": {
                String r = SwitchWords.resultLine(Cells.shellMain(app, "hotspot-start").out);
                Boot.hotspotResult = r; // "start the phone after a restart" asks how it went once the tablet is on the hotspot
                return said(SwitchWords.hotspotOn(r));
            }
            case "hotspot_off":
                return said(hotspotOff(app, viaBluetooth));
            case "hotspot_restart":
                // longer than the usual 40 s: off (up to 8 s), then on (up to 20 s for the phone's answer and 10 s to come up)
                return said(SwitchWords.hotspotRestart(SwitchWords.resultLine(Cells.shellMain(app, "hotspot-restart", 55).out)));
            case "radio_cycle": {
                // for the phone's choice of cell: the modem searches from scratch when its radio comes back
                markRadioRisk(app, name); // BEFORE the radio goes off: a cut-off must still be undone
                String r;
                try {
                    r = SwitchWords.resultLine(Cells.shellMain(app, "radio-cycle").out);
                } catch (IOException e) {
                    cycleState = "failed: " + e.getMessage();
                    throw e;
                }
                cycleState = SwitchWords.ok(r) ? "done" : "failed: " + r;
                finishRadioRisk(app, name);
                if (!SwitchWords.ok(r)) {
                    throw new NotDone(SwitchWords.dot("The radio restart failed: " + SwitchWords.afterWord(r)));
                }
                return "The radio was switched off for a few seconds and on again.";
            }
            case "wifi_on":
                return wifi(app, true);
            case "wifi_off":
                return wifi(app, false);
            case "standby":
                return standby(app, viaBluetooth);
            case "airplane_on":
                markRadioRisk(app, name);
                return report(Priv.run("cmd connectivity airplane-mode enable", 30), "Airplane mode is on.");
            case "reboot":
                return report(Priv.run("svc power reboot || reboot", 60), "Restarting.");
            case "poweroff":
                return report(Priv.run("svc power shutdown || reboot -p", 60), "Shutting down.");
            default:
                throw new IOException("unknown action: " + name);
        }
    }

    /**
     * Switches the hotspot off. The direct way first (the system's tethering service, checked). Only if that fails, and only when
     * the request did not come over Bluetooth, the old way: airplane mode on for three seconds, which also switches the hotspot
     * off but takes mobile data (and Bluetooth) away for a moment.
     */
    private static SwitchWords.Said hotspotOff(Context app, boolean viaBluetooth) throws IOException {
        String direct = SwitchWords.resultLine(Cells.shellMain(app, "hotspot-stop").out);
        SwitchWords.Said said = SwitchWords.hotspotOff(direct, viaBluetooth);
        if (said.done || viaBluetooth) {
            return said;
        }
        markRadioRisk(app, "hotspot_off");
        Priv.run("cmd connectivity airplane-mode enable; sleep 3; cmd connectivity airplane-mode disable", 60);
        finishRadioRisk(app, "hotspot_off");
        return SwitchWords.hotspotOffByAirplane(direct, Radios.waitHotspot(app, false, 4000));
    }

    /** The phone's own Wi-Fi switch (not the hotspot). Checked by asking Android afterwards. */
    private static String wifi(Context app, boolean on) throws IOException {
        Priv.Result r = Priv.run("cmd wifi set-wifi-enabled " + (on ? "enabled" : "disabled") + " 2>&1 || svc wifi "
                + (on ? "enable" : "disable") + " 2>&1", 20);
        if (!Radios.waitWifi(app, on, 6000)) {
            throw new NotDone(SwitchWords.dot("The phone's Wi-Fi did not switch " + (on ? "on" : "off") + (r.out.isEmpty() ? "" : ": " + firstLine(r.out))));
        }
        return "The phone's Wi-Fi is " + (on ? "on." : "off.");
    }

    /**
     * Standby: the hotspot off and the screen off, so the phone idles (days, not hours) until the tablet wakes it over Bluetooth
     * with "hotspot on". Mobile data, calls and texts stay as they are. Both switches are always tried; what the answer is when
     * one of them did not move is decided in {@link SwitchWords#standby}.
     */
    private static String standby(Context app, boolean viaBluetooth) throws IOException {
        SwitchWords.Said hotspot = hotspotOff(app, viaBluetooth);
        return said(SwitchWords.standby(hotspot, screenOff(app), viaBluetooth));
    }

    /** The checked Sleep (the same steps as the Screen tab's Sleep button). */
    private static SwitchWords.Said screenOff(Context app) {
        try {
            PowerSequence.Result res = Screen.powerNow(app, false, true);
            return SwitchWords.screenOff(res.reached, res.how);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SwitchWords.screenOff(false, "the phone was interrupted while doing it");
        }
    }

    static String run(Context c, String name) throws IOException {
        return run(c, name, "", false);
    }

    /**
     * Runs an action and returns text for the tablet to show. arg: the one extra value a few actions take (a screen name).
     * viaBluetooth: the request came over the Bluetooth link (a few actions must then not use airplane mode).
     */
    static String run(Context c, String name, String arg, boolean viaBluetooth) throws IOException {
        switch (name) {
            case "safety":
                return safety(c);
            // the actions that can cut a link, asked over a link they do not cut (see Api.act): done now, real result
            case "hotspot_on":
            case "boot_hotspot_on":
            case "hotspot_off":
            case "hotspot_restart":
            case "radio_cycle":
            case "wifi_on":
            case "wifi_off":
            case "standby":
                return perform(c, name, viaBluetooth);
            case "bt_setup":
                return BtServer.setup(c);
            case "eco_auto_on":
                // the stored choice: the saver switches on by itself whenever the tablet connects (and now)
                Cfg.setEcoAuto(c, true);
                Eco.setSuppressed(false);
                return Eco.apply(c) + " It switches on by itself when the tablet connects.";
            case "eco_auto_off":
                Cfg.setEcoAuto(c, false);
                Eco.setSuppressed(true);
                return Eco.restore(c) + " It stays off until you switch it on.";
            case "boot_wadb":
                return Boot.enableWadb(c);
            case "boot_notify":
                Boot.notifyShizuku(c, Priv.state() == Priv.NO_PERM ? BootPlan.Notice.NEEDS_ALLOW : BootPlan.Notice.NOT_RUNNING);
                return "OK the notification is on the phone";
            case "boot_setup":
                return Boot.setup(c);
            case "boot_undo":
                return Boot.undo(c);
            case "boot_assist_on":
                Boot.setAssist(c, true);
                return "Boot assist is on.";
            case "boot_assist_off":
                Boot.setAssist(c, false);
                return "Boot assist is off.";
            case "boot_keep_on":
                return Boot.setPairingKept(c, true);
            case "boot_keep_off":
                return Boot.setPairingKept(c, false);
            case "menu_find":
                return Menus.find();
            case "menu_open":
                return Menus.open(c, arg);
            case "menu_usual":
                return Menus.usual(c);
            case "menu_dial":
                return Menus.dial(c, arg);
            case "airplane_off":
                Saved.clearAirplaneUntil(c);
                Saved.clearBlip(c);
                return report(Priv.run("cmd connectivity airplane-mode disable", 15), "Airplane mode is off.");
            case "screen_off":
                return said(screenOff(c)); // checked: the old way pressed a key and said "off" whatever happened
            case "eco_on":
                Eco.setSuppressed(false);
                return Eco.apply(c);
            case "eco_off":
                Eco.setSuppressed(true);
                return Eco.restore(c);
            case "setup":
                return setup(c);
            case "restore":
                return restoreAll(c);
            case "radio_cycle_state":
                return cycleState;
            case "restart_modem":
                return restartModem();
            case "hotspot_info":
                return Cells.shellMain(c, "hotspot-info").out;
            case "hotspot_band_2":
                return setHotspotBand(c, "2");
            case "hotspot_band_5":
                return setHotspotBand(c, "5");
            case "hotspot_band_auto":
                return setHotspotBand(c, "auto");
            case "net_get":
                return netGet(c);
            case "nrleg_get":
                return nrLeg(c, null);
            case "nrleg_off":
                return nrLeg(c, "off");
            case "nrleg_off_now":
                return nrLeg(c, "off-now");
            case "nrleg_on":
                return nrLeg(c, "on");
            case "net_lte": {
                String res = setNr(c, false);
                Guard.arm(c);
                return res;
            }
            case "net_5g": {
                String res = setNr(c, true);
                Guard.arm(c);
                return res;
            }
            default:
                throw new IOException("unknown action: " + name);
        }
    }

    /**
     * The 5G leg (NR dual connectivity): read it (want == null) or switch it (on, off, off-now). Switching it off is remembered
     * BEFORE it is done, and the safety net (Guard) is armed, so a cut-off or a dead connection still ends with the leg back on.
     * The words of the answer are NrLeg's; the tablet reads them back.
     */
    private static String nrLeg(Context c, String want) throws IOException {
        if (want == null) {
            return NrLeg.describe(NrLeg.parse(Cells.shellMain(c, "nrdc-get").out));
        }
        if (!want.equals("on")) {
            Saved.rememberNrLegChanged(c);
        }
        NrLeg.State s = NrLeg.parse(Cells.shellMain(c, "nrdc-set " + want).out);
        if (want.equals("on")) {
            if (Boolean.TRUE.equals(s.enabled) || (s.error.isEmpty() && s.result.equals("SUCCESS") && s.enabled == null)) {
                Saved.clearNrLeg(c);
            }
        } else {
            Guard.arm(c);
        }
        return NrLeg.describeSet(want, s);
    }

    /** okText when the shell command ended well; otherwise the phone did not do it, and what it printed says why. */
    private static String report(Priv.Result r, String okText) throws NotDone {
        if (!r.ok()) {
            throw new NotDone(SwitchWords.dot("The phone did not do that" + (r.timedOut ? " in time" : "") + (r.out.isEmpty() ? "" : ": " + firstLine(r.out))));
        }
        return okText;
    }

    /**
     * Asks the phone's own telephony command to restart the modem. Android only obeys it on test builds with root (its source
     * says so: "RestartModem: Permission denied" for everybody else), so on an ordinary phone this changes nothing, and it then
     * says so instead of the old "Failed (code 255)". It is NOT answered first like the actions that cut the link: where it is
     * refused nothing happens to the hotspot, and where it works the hotspot's Wi-Fi stays up (only its internet pauses), so
     * the real answer can always be delivered.
     */
    private static String restartModem() throws IOException {
        Priv.Result r = Priv.run("cmd phone restart-modem", 30);
        if (!r.ok() && r.out.contains("Permission denied")) {
            throw new NotDone("This phone does not let an app restart its modem, so nothing was changed. To make the phone look for "
                    + "its network again, switch airplane mode on and off at the phone.");
        }
        return report(r, "Modem restart requested. Mobile data comes back in a minute or so.");
    }

    /**
     * One-time grants that make the phone side reliable. Safe to run again. Each step is run and judged by itself (the old way
     * ran them in one line and said "Setup ran" whatever happened): if any of them failed, the answer is an error that names it.
     */
    private static String setup(Context c) throws IOException {
        String p = c.getPackageName();
        String[][] steps = {
            {"no battery limits for LinkMeter", "cmd deviceidle whitelist +" + p},
            {"kept ready in the background", "am set-standby-bucket " + p + " active"},
            {"access to all files", Build.VERSION.SDK_INT >= 30 ? "appops set " + p + " MANAGE_EXTERNAL_STORAGE allow" : ""},
            {"the phone permission", "pm grant " + p + " android.permission.READ_PHONE_STATE"},
            {"notifications", Build.VERSION.SDK_INT >= 33 ? "pm grant " + p + " android.permission.POST_NOTIFICATIONS" : ""},
        };
        StringBuilder done = new StringBuilder();
        StringBuilder failed = new StringBuilder();
        for (String[] s : steps) {
            if (s[1].isEmpty()) {
                continue; // this Android version has no such setting
            }
            Priv.Result r = Priv.run(s[1], 15);
            if (r.ok()) {
                done.append(done.length() == 0 ? "" : ", ").append(s[0]);
            } else {
                failed.append(failed.length() == 0 ? "" : "; ").append(s[0]).append(r.out.isEmpty() ? "" : " (" + firstLine(r.out) + ")");
            }
        }
        if (failed.length() > 0) {
            throw new NotDone("Not every step worked. Not done: " + failed + "." + (done.length() == 0 ? "" : " Done: " + done + "."));
        }
        return "The phone is prepared: " + done + ".";
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
        Saved.rememberTypes(c, mask);
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
        Saved.rememberTypes(c, mask);
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

    /**
     * Saves the hotspot's current band the first time, then changes it. The helper reads the band back from the phone: "saved"
     * is only said when the phone gives the new band back, and when it kept its own (Samsung has hotspot settings of its own)
     * the answer is an error that says which band the phone kept.
     */
    private static String setHotspotBand(Context c, String arg) throws IOException {
        if (Saved.hotspotBand(c) < 0) {
            Matcher m = Pattern.compile("band=(\\d)").matcher(Cells.shellMain(c, "hotspot-info").out);
            if (m.find()) {
                Saved.rememberHotspotBand(c, Integer.parseInt(m.group(1)));
            }
        }
        String r = SwitchWords.resultLine(Cells.shellMain(c, "hotspot-band " + arg).out);
        if (!SwitchWords.ok(r)) {
            throw new NotDone(SwitchWords.dot("The hotspot's Wi-Fi band was not changed: " + SwitchWords.afterWord(r)));
        }
        return SwitchWords.dot("The hotspot's Wi-Fi band is now " + SwitchWords.afterWord(r));
    }

    // ------------------------------------------------------------ restore everything

    /** Allowed types that match what this phone normally uses: 2G/3G/4G and 5G. */
    private static final long DEFAULT_TYPES = 840583L;

    static final String MANUAL = "If that doesn't fix mobile data, do these on the phone, in order:\n"
            + "1. Settings > Connections > Mobile networks > Network mode: choose 5G/LTE/3G/2G (auto connect).\n"
            + "2. Turn airplane mode on for 10 seconds, then off.\n"
            + "3. Restart the phone.\n"
            + "4. Settings > General management > Reset > Reset mobile network settings (or Reset network "
            + "settings, which also forgets saved Wi-Fi and Bluetooth devices).\n"
            + "To fix the hotspot band: Settings > Connections > Mobile Hotspot and Tethering > Mobile "
            + "Hotspot > Configure > Band.";

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }

    /** The Safety check: what is mobile data doing, and is anything LinkMeter changed still in place? The words are SafetyReport's. */
    private static String safety(Context c) {
        SafetyReport.Facts f = new SafetyReport.Facts();
        long types = currentTypes(c);
        f.types = types;
        f.typesText = types >= 0 ? typesText(types) : "";
        f.typesChanged = Saved.types(c) > 0;
        try {
            Boolean off = NrLeg.offFromText(NrLeg.describe(NrLeg.parse(Cells.shellMain(c, "nrdc-get").out)));
            f.legOn = off == null ? null : Boolean.valueOf(!off);
        } catch (IOException ignored) {
            // Shizuku is off: unknown
        }
        f.legChanged = Saved.nrLegChanged(c);
        try {
            org.json.JSONObject j = new org.json.JSONObject(Cells.status());
            f.lock = j.optString("lock", "");
            f.lockKept = j.optBoolean("kept", false);
        } catch (org.json.JSONException ignored) {
            // plain values only
        }
        f.lockFlag = Saved.lockOn(c);
        try {
            f.airplaneOn = Boolean.valueOf(Settings.Global.getInt(c.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1);
            TelephonyManager tm = c.getSystemService(TelephonyManager.class);
            f.dataEnabled = tm == null ? null : Boolean.valueOf(tm.isDataEnabled());
        } catch (RuntimeException ignored) {
            // unknown
        }
        f.cellular = Guard.cellularText(c);
        f.radioBlip = Saved.blip(c) > 0;
        f.airplaneTimer = Saved.airplaneUntil(c) > 0;
        f.menuRecent = Saved.menuRecent(c);
        f.hotspotBandChanged = Saved.hotspotBand(c) > 0;
        f.batterySaver = Eco.isOn(c);
        f.guardWatching = Guard.watching();
        f.secureGranted = Boot.canWriteSecure(c);
        f.pairingKept = Boot.pairingKept(c);
        f.bootAssist = Boot.assist(c);
        return SafetyReport.report(f);
    }

    // ------------------------------------------------------------ radio-off and airplane failsafes

    /** Before a deliberate radio-off, or an airplane mode the tablet cannot end: write it down, so a cut-off session still gets undone. */
    private static void markRadioRisk(Context app, String name) {
        long now = System.currentTimeMillis();
        if (name.equals("radio_cycle") || name.equals("hotspot_off")) {
            Saved.rememberBlip(app, now);
            Guard.arm(app);
        } else if (name.equals("airplane_on")) {
            Saved.rememberAirplaneUntil(app, now + AIRPLANE_AUTO_OFF_MS);
            Guard.arm(app);
        }
    }

    /** After a radio-off: make sure the radio really is back on, forcing it if not, and only then forget the risk. */
    private static void finishRadioRisk(Context app, String name) {
        if ((name.equals("radio_cycle") || name.equals("hotspot_off")) && radioBack(app)) {
            Saved.clearBlip(app);
        } // otherwise the flag stays and the safety net keeps trying
    }

    /** 1 = the radio is on and airplane mode is off, 0 = it is definitely off, -1 = cannot tell. Android's own state, no Shizuku needed. */
    private static int radioState(Context app) {
        try {
            if (Settings.Global.getInt(app.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1) {
                return 0;
            }
            TelephonyManager tm = app.getSystemService(TelephonyManager.class);
            ServiceState ss = tm == null ? null : tm.getServiceState();
            if (ss == null) {
                return -1;
            }
            return ss.getState() == ServiceState.STATE_POWER_OFF ? 0 : 1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Polls for up to `seconds` while the radio is definitely off; returns the last state (see radioState). */
    private static int waitRadioState(Context app, int seconds) {
        int st = radioState(app);
        for (int i = 0; i < seconds / 2 && st == 0; i++) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            st = radioState(app);
        }
        return st;
    }

    /**
     * Makes sure the radio is on and airplane mode is off: both are switched on (harmless if they already are), and if the radio is
     * then DEFINITELY still off, the airplane toggle a person would do. True when it is on or cannot be told to be off.
     */
    private static boolean radioBack(Context app) {
        try {
            Priv.run("cmd connectivity airplane-mode disable", 15);
            Cells.shellMain(app, "radio-on");
            if (waitRadioState(app, 20) != 0) {
                return true;
            }
            Priv.run("cmd connectivity airplane-mode enable; sleep 3; cmd connectivity airplane-mode disable", 30);
            Cells.shellMain(app, "radio-on");
            return waitRadioState(app, 30) != 0;
        } catch (IOException e) {
            return false; // Shizuku is off: the Phone tab shows the manual steps
        }
    }

    /** Is a radio-off that never ended, or an airplane mode whose time is up, waiting to be undone? */
    static boolean timersDue(Context app) {
        long now = System.currentTimeMillis();
        long blip = Saved.blip(app);
        long air = Saved.airplaneUntil(app);
        boolean blipDue = blip > 0 && (now - blip > BLIP_GRACE_MS || now < blip);
        boolean airDue = air > 0 && (now >= air || air - now > AIRPLANE_AUTO_OFF_MS + 60_000L);
        return blipDue || airDue;
    }

    /** Called by the safety net (and at every service start): finishes what a cut-off session left half-way. */
    static void recoverTimers(Context app) {
        if (timersDue(app) && radioBack(app)) {
            Saved.clearBlip(app);
            Saved.clearAirplaneUntil(app);
        }
    }

    /** Puts the radio back: no band lock, and the network types as they were (or the usual default). */
    static String restoreRadio(Context c) {
        StringBuilder sb = new StringBuilder();
        try {
            Priv.Result r = Cells.shellMain(c, "bands-clear");
            sb.append("Band lock: ").append(r.out.contains("OK ") ? "cleared."
                    : "could not be cleared (" + firstLine(r.out) + ").").append('\n');
            if (r.out.contains("OK ")) {
                Cells.clearState();
                Saved.clearLock(c);
            }
        } catch (IOException e) {
            sb.append("Band lock: ").append(e.getMessage()).append('\n');
        }
        try {
            Cells.shellMain(c, "radio-on"); // a band test may have been cut off while the radio was off
        } catch (IOException ignored) {
            // nothing more to do here
        }
        if (Saved.blip(c) > 0 || Saved.airplaneUntil(c) > 0) {
            // a radio-off or an airplane mode that LinkMeter started is still in place: end it
            try {
                Priv.run("cmd connectivity airplane-mode disable", 15);
                Saved.clearBlip(c);
                Saved.clearAirplaneUntil(c);
                sb.append("Airplane mode and radio: switched back on.\n");
            } catch (IOException e) {
                sb.append("Airplane mode and radio: ").append(e.getMessage()).append('\n');
            }
        }
        if (Saved.nrLegChanged(c)) {
            // LinkMeter switched the 5G leg off: put it back on (a normal phone has it on)
            try {
                NrLeg.State leg = NrLeg.parse(Cells.shellMain(c, "nrdc-set on").out);
                if (Boolean.TRUE.equals(leg.enabled) || (leg.error.isEmpty() && leg.result.equals("SUCCESS"))) {
                    Saved.clearNrLeg(c);
                    sb.append("5G leg: switched back on.\n");
                } else {
                    sb.append("5G leg: could not be switched back on. ").append(NrLeg.describeSet("on", leg)).append('\n');
                }
            } catch (IOException e) {
                sb.append("5G leg: ").append(e.getMessage()).append('\n');
            }
        }
        Saved.clearMenu(c);
        long saved = Saved.types(c);
        long target = saved > 0 ? saved : DEFAULT_TYPES;
        try {
            if (saved <= 0) {
                // LinkMeter has not changed the types, so don't touch them if they already look normal.
                long cur = currentTypes(c);
                if (cur > 0 && (cur & LTE_BIT) != 0 && (cur & NR_BIT) != 0) {
                    sb.append("Network types: already normal (").append(typesText(cur))
                            .append("), left as they are.\n");
                    return sb.toString();
                }
            }
            String err = forceTypes(c, target);
            if (err == null) {
                Saved.clearTypes(c);
                sb.append("Network types: back to ").append(typesText(target))
                        .append(saved > 0 ? " (as they were before LinkMeter changed them).\n"
                                : " (the usual default).\n");
            } else {
                sb.append("Network types: ").append(err).append('\n');
            }
        } catch (IOException e) {
            sb.append("Network types: ").append(e.getMessage()).append('\n');
        }
        return sb.toString();
    }

    /** The allowed network types right now, or -1 if they can't be read. */
    private static long currentTypes(Context c) {
        try {
            long a = parseOk(Cells.shellMain(c, "nt-get").out);
            return a >= 0 ? a : parseTypes(Priv.run(GET_TYPES, 15).out);
        } catch (IOException e) {
            return -1;
        }
    }

    /** Sets the allowed network types to target. Returns null on success, or a message. */
    private static String forceTypes(Context c, long target) throws IOException {
        long cur = parseOk(Cells.shellMain(c, "nt-get").out);
        if (cur >= 0) {
            if (cur == target) {
                return null;
            }
            Cells.shellMain(c, "nt-set " + target);
            if (parseOk(Cells.shellMain(c, "nt-get").out) == target) {
                return null;
            }
        }
        if (parseTypes(Priv.run(GET_TYPES, 15).out) == target) {
            return null;
        }
        String[] forms = {"'" + typesText(target) + "'", String.valueOf(target)};
        for (String f : forms) {
            for (String slot : new String[] {"", "-s 0 "}) {
                Priv.run(SET_TYPES + " " + slot + f, 15);
                if (parseTypes(Priv.run(GET_TYPES, 15).out) == target) {
                    return null;
                }
            }
        }
        return "the phone did not take the setting; use Settings > Connections > Mobile networks > "
                + "Network mode.";
    }

    /**
     * The one button that undoes everything LinkMeter can change: band lock, network types, hotspot
     * band, battery saver, airplane mode and mobile data. Each step runs on its own, so one failure
     * never stops the others.
     */
    static String restoreAll(Context c) {
        if (!Priv.ready()) {
            return "LinkMeter can't change phone settings right now (" + Priv.stateText(Priv.state())
                    + ").\n\n" + MANUAL;
        }
        StringBuilder sb = new StringBuilder(restoreRadio(c));
        int hb = Saved.hotspotBand(c);
        if (hb > 0) {
            try {
                String arg = hb == 1 ? "2" : (hb == 2 ? "5" : "auto");
                String r = SwitchWords.resultLine(Cells.shellMain(c, "hotspot-band " + arg).out);
                if (SwitchWords.ok(r)) {
                    Saved.clearHotspotBand(c);
                    sb.append("Hotspot band: put back. Restart the hotspot to use it.\n");
                } else {
                    sb.append("Hotspot band: could not be put back (").append(SwitchWords.afterWord(r)).append(").\n");
                }
            } catch (IOException e) {
                sb.append("Hotspot band: ").append(e.getMessage()).append('\n');
            }
        }
        try {
            Eco.setSuppressed(true);
            sb.append("Battery saver: ").append(Eco.restore(c)).append('\n');
        } catch (IOException e) {
            sb.append("Battery saver: ").append(e.getMessage()).append('\n');
        }
        try {
            Priv.run("cmd connectivity airplane-mode disable; svc data enable", 20);
            sb.append("Airplane mode is off and mobile data is on.\n");
        } catch (IOException e) {
            sb.append("Airplane mode and mobile data: ").append(e.getMessage()).append('\n');
        }
        sb.append("\n").append(MANUAL);
        return sb.toString();
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
            // the carriers the radio is really using (several with carrier aggregation or 5G) and the label the phone shows
            "dumpsys telephony.registry 2>&1 | grep -E 'mPhysicalChannelConfigs|mTelephonyDisplayInfo' | cut -c1-2500 | head -4",
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
