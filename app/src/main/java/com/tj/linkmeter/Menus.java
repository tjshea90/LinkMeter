package com.tj.linkmeter;

import android.content.Context;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Phone only: finds and opens the phone's hidden service menus (what to look for and how to read it is in {@link MenuScan}).
 * Everything goes through {@link Priv}, so it needs Shizuku. Nothing here changes a setting by itself: it only opens a screen
 * (or the dialer with a code typed in) that the user then works with, for example from the tablet's Screen tab.
 */
final class Menus {
    private Menus() {}

    /** Looks for apps, dialer codes and screens. The answer is MenuScan's reply format. */
    static String find() throws IOException {
        Priv.Result r = Priv.run(MenuScan.DISCOVER_COMMAND, 60);
        MenuScan.Found f = MenuScan.parseDiscovery(r.out);
        List<String> screens = new ArrayList<>();
        String cmd = MenuScan.activitiesCommand(f.packages);
        if (cmd != null) {
            screens = MenuScan.parseActivities(Priv.run(cmd, 60).out);
        }
        return MenuScan.reply(f, screens);
    }

    /** Opens one screen, for example "com.mediatek.engineermode/.EngineerMode". The shell may start screens that are not exported. */
    static String open(Context c, String component) throws IOException {
        if (!MenuScan.isComponent(component)) {
            throw new IOException("that is not a screen name (it looks like package/.Class)");
        }
        if (!MenuScan.safeScreen(component)) {
            throw new IOException("this app does not open that screen: its name suggests it can erase, reset, flash or unlock the phone");
        }
        // 224 = wake up: a screen opened on a sleeping phone is not seen
        Priv.Result r = Priv.run("input keyevent 224; am start -n '" + component + "' 2>&1 | head -8", 20);
        if (MenuScan.started(r.out)) {
            menuOpened(c);
            return "Opened " + component + ". Look at the phone's screen (the Screen tab shows it; unlock the phone first if it is locked).";
        }
        return "Could not open " + component + ": " + firstLines(r.out);
    }

    /** Tries the usual service and engineering screens in turn and stops at the first that opens. */
    static String usual(Context c) throws IOException {
        StringBuilder missed = new StringBuilder();
        for (String comp : MenuScan.USUAL_SCREENS) {
            Priv.Result r = Priv.run("am start -n '" + comp + "' 2>&1 | head -8", 20);
            if (MenuScan.started(r.out)) {
                menuOpened(c);
                return "Opened " + comp + ". Look at the phone's screen (the Screen tab shows it)."
                        + (missed.length() == 0 ? "" : "\nNot on this phone: " + missed.toString().trim());
            }
            missed.append(comp).append("; ");
        }
        return "None of the usual service screens exists on this phone (" + missed.toString().trim()
                + "). Use \"Look for service menus\" to see what this phone really has.";
    }

    /**
     * Opens the phone's dialer with a service code typed in, for example *#2263#. The dialer runs a service code by itself when it
     * sees it. Only the codes in MenuScan.SAFE_CODES are ever dialed: the command is built by MenuScan.dialCommand, which gives
     * nothing for any other code.
     */
    static String dial(Context c, String code) throws IOException {
        String cmd = MenuScan.dialCommand(code);
        if (cmd == null) {
            throw new IOException("this app only dials service codes from its own short list of safe ones (some codes erase the phone), "
                    + "and that is not one of them");
        }
        Priv.Result r = Priv.run(cmd, 20);
        if (MenuScan.started(r.out)) {
            menuOpened(c);
            return "The dialer was opened with " + code + ". If a menu did not open by itself, look at the phone's screen (Screen tab) "
                    + "and press the call button. A message like \"RAT selection option restricted\" or nothing at all means this "
                    + "phone's firmware blocks that menu.";
        }
        return "Could not open the dialer: " + firstLines(r.out);
    }

    /**
     * A service menu was opened: for the next two hours the safety net watches mobile data, because a change made INSIDE such a menu
     * (a band, a network type) is something LinkMeter cannot see. If data dies, it resets what the phone's own software lets it
     * reset (network types, band lock); a band chosen inside the menu itself is put back by choosing "all bands" / "auto" there.
     */
    private static void menuOpened(Context c) {
        Saved.rememberMenuAt(c, System.currentTimeMillis());
        Guard.arm(c);
    }

    private static String firstLines(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) {
            return "(the phone said nothing)";
        }
        return t.length() > 300 ? t.substring(0, 300) : t;
    }
}
