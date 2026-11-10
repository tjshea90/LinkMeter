package com.tj.linkmeter;

/**
 * Offline test of MenuScan (the strict checks on a screen name before the phone opens it). Run by tools/test.sh. Prints "N checks,
 * M failed".
 *
 * Every injection attempt on a screen name must be refused, and a screen whose name suggests erasing, resetting, IMEI or NVRAM work,
 * flashing or unlocking must never be opened. (The app dials nothing and looks for no menus any more: the tripwire
 * tools/test/test_dial_paths.py fails the build if a dial route ever comes back.)
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

    public static void main(String[] a) {
        // ---- the strict checks
        String[] goodComponents = {"com.mediatek.engineermode/.EngineerMode", "com.a.b/com.a.b.C$D", "a/b", "com.android.phone/.settings.RadioInfo"};
        String[] badComponents = {"", null, "nopackage", "a/b c", "a/b;reboot", "a/b'c", "a/b\nc", "a/$(reboot)", "a/`id`", "a/b|c", "a//b",
            "a/b&c", "a/b>c", "/b", "a/", "a b/c", "a/b\"c", "x".repeat(201) + "/y", "tel:*#06#", "*#06#", "-a android.intent.action.DIAL/.X"};
        for (String s : goodComponents) {
            check(MenuScan.isComponent(s), "a good screen name: " + s);
        }
        for (String s : badComponents) {
            check(!MenuScan.isComponent(s), "refused screen name: " + s);
            check(!MenuScan.safeScreen(s), "and not a screen to open: " + s);
        }

        // ---- SAFETY: screens that suggest wiping, resetting, IMEI, NVRAM, flashing or unlocking are never opened
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
        check(!MenuScan.safeScreen(null) && !MenuScan.safeScreen("") && !MenuScan.safeScreen("a/b;reset"), "junk is refused");

        // ---- the Phone info screens (what the button opens)
        check(MenuScan.PHONE_INFO_SCREENS.length == 2, "two places for the Phone info screen");
        check(MenuScan.PHONE_INFO_SCREENS[0].equals("com.android.phone/.settings.RadioInfo"), "Android's own screen comes first");
        check(MenuScan.PHONE_INFO_SCREENS[1].equals("com.android.settings/.RadioInfo"), "its older place second");
        for (String c : MenuScan.PHONE_INFO_SCREENS) {
            check(MenuScan.safeScreen(c), "every Phone info screen passes the filter: " + c);
            check(!c.toLowerCase().contains("mediatek") && !c.toLowerCase().contains("engineer") && !c.toLowerCase().contains("servicemode"),
                    "and none is a technician menu, which a button called Phone info must not open: " + c);
        }

        // ---- reading am start's words
        check(MenuScan.started("Starting: Intent { cmp=a/.B }"), "am start success");
        check(!MenuScan.started("Starting: Intent { cmp=a/.B }\nError type 3\nError: Activity class {a/.B} does not exist."), "am start failure");
        check(!MenuScan.started("Error type 3"), "no 'Starting' at all");
        check(!MenuScan.started(null) && !MenuScan.started(""), "null and empty");
        check(!MenuScan.started("Starting: Intent { cmp=a/.B }\njava.lang.SecurityException: Permission Denial"), "an exception is not a start");
        check(MenuScan.started("Starting: Intent { cmp=a/.B }\nWarning: Activity not started, its current task has been brought to the front"),
                "a warning about the task is still a start");

        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
