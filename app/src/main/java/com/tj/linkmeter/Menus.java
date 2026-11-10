package com.tj.linkmeter;

import android.content.Context;
import java.io.IOException;

/**
 * Phone only: opens a screen of the phone by name (what is allowed is decided in {@link MenuScan}). Everything goes through
 * {@link Priv}, so it needs Shizuku. Nothing here changes a setting by itself: it only opens a screen that the user then works with,
 * for example from the tablet's Screen tab. This app dials nothing and looks for no menus (see MenuScan).
 */
final class Menus {
    private Menus() {}

    /** Opens one screen, for example "com.android.phone/.settings.RadioInfo". The shell may start screens that are not exported. */
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

    /**
     * Opens the plain Phone info screen: the first of MenuScan.PHONE_INFO_SCREENS that this phone has. The phone's screen is woken
     * once, with the first try (a screen opened on a sleeping phone is not seen). Older tablets still send this as "the usual ones".
     */
    static String usual(Context c) throws IOException {
        StringBuilder missed = new StringBuilder();
        for (int i = 0; i < MenuScan.PHONE_INFO_SCREENS.length; i++) {
            String comp = MenuScan.PHONE_INFO_SCREENS[i];
            Priv.Result r = Priv.run((i == 0 ? "input keyevent 224; " : "") + "am start -n '" + comp + "' 2>&1 | head -8", 20);
            if (MenuScan.started(r.out)) {
                menuOpened(c);
                return "Opened the Phone info screen (" + comp + "). Look at the phone's screen (the Screen tab shows it; unlock the phone first if it is locked)."
                        + (missed.length() == 0 ? "" : "\nNot on this phone: " + missed.toString().trim());
            }
            missed.append(comp).append("; ");
        }
        return "This phone has no Phone info screen that LinkMeter could open (" + missed.toString().trim() + ").";
    }

    /**
     * A screen was opened: for the next two hours the safety net watches mobile data, because a change made INSIDE a phone menu
     * (a band, a network type) is something LinkMeter cannot see. If data dies, it resets what the phone's own software lets it
     * reset (network types); a band chosen inside a menu itself is put back by choosing "all bands" / "auto" there.
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
