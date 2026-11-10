package com.tj.linkmeter;

import java.util.regex.Pattern;

/**
 * No Android in here (tested in tools/test/MenuScanTest.java). The strict checks on a screen name before the phone opens it.
 *
 * The phone can open ONE screen by name (Menus.open: "am start -n"), and the plain Phone info screen (Menus.usual). Versions up to
 * v0.2.102 could also dial service codes and look for service menus; this phone's dialer answered "invalid MMI" to every code and the
 * finder was never used, so both are gone: the app dials nothing, and a tripwire (tools/test/test_dial_paths.py) fails the build if
 * a dial route ever comes back.
 */
final class MenuScan {
    private MenuScan() {}

    /**
     * Screens that are never opened: a name that suggests wiping, resetting, IMEI or NVRAM work, flashing or firmware, unlocking,
     * or backup and restore of the radio's memory. Looking at a service menu is safe; these are the entries where one wrong tap
     * can damage the phone for good.
     */
    private static final Pattern DANGEROUS_SCREEN = Pattern.compile("(?i)reset|wipe|erase|(?<!in)format|flash|imei|nvram|nvdata|factory"
            + "|recovery|rsu|simlock|sim_lock|unlock|bootloader|oem|root|fuse|efs|backup|restore|download|odin|fota|upgrade|update"
            + "|firmware|provision|activation|calibrat|repartition|partition|security|knox");

    /**
     * The plain "Phone info" screens, in the order they are tried: Android's own radio information screen (the one that was opened
     * on the user's phone and read through the Screen tab), then the older place of the same screen. MediaTek's engineering menu
     * and Samsung's service menus are NOT here: a button the user knows as "Phone info" must not open a technician menu.
     */
    static final String[] PHONE_INFO_SCREENS = {
        "com.android.phone/.settings.RadioInfo",
        "com.android.settings/.RadioInfo",
    };

    private static final Pattern COMPONENT = Pattern.compile("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+");

    static boolean isComponent(String s) {
        return s != null && s.length() <= 200 && COMPONENT.matcher(s).matches();
    }

    /** A screen the app may open: a well-formed name that does not suggest anything destructive. */
    static boolean safeScreen(String component) {
        return isComponent(component) && !DANGEROUS_SCREEN.matcher(component).find();
    }

    /** True when am start's own words say the screen did start (it prints "Starting: Intent ..." and no "Error"). */
    static boolean started(String amOut) {
        return amOut != null && amOut.contains("Starting:") && !amOut.contains("Error") && !amOut.contains("Exception");
    }
}
