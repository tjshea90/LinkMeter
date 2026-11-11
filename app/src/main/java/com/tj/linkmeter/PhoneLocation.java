package com.tj.linkmeter;

import android.content.Context;
import android.os.SystemClock;
import java.io.IOException;

/**
 * Phone only: the location switch (the master "Location" tile of the quick settings), moved through Shizuku and always read back.
 * Both ways Android has are run, because this phone has never been asked: the system's own shell command, and the setting it stores
 * the choice in. Whichever works, the switch is only said to have moved when {@link Radios#location} shows it.
 */
final class PhoneLocation {
    private PhoneLocation() {}

    /** The shell lines. Each way is harmless where the other already did the job; one of them not existing is no error. */
    static String command(boolean on) {
        return "cmd location set-location-enabled " + (on ? "true" : "false") + " 2>&1; settings put secure location_mode " + (on ? 3 : 0) + " 2>&1";
    }

    /** Is location on? null when the phone does not say. */
    static Boolean on(Context c) {
        return Radios.location(c);
    }

    /** Waits up to ms for location to be as wanted. */
    private static boolean waitFor(Context c, boolean wanted, long ms) {
        long end = SystemClock.elapsedRealtime() + ms;
        while (true) {
            Boolean now = Radios.location(c);
            if (now != null && now.booleanValue() == wanted) {
                return true;
            }
            if (SystemClock.elapsedRealtime() >= end) {
                return false;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** Switches location on or off and says what the phone then shows. Needs Shizuku; not for the screen's thread. */
    static SwitchWords.Said set(Context c, boolean on) {
        String word = on ? "on" : "off";
        Boolean before = Radios.location(c);
        if (before != null && before.booleanValue() == on) {
            return new SwitchWords.Said(true, "Location was already " + word + ".");
        }
        String why = "";
        try {
            Priv.Result r = Priv.run(command(on), 20);
            why = firstLine(r.out);
        } catch (IOException e) {
            return new SwitchWords.Said(false, SwitchWords.dot("Location could not be switched " + word + ": " + e.getMessage()));
        }
        if (waitFor(c, on, 4000)) {
            return new SwitchWords.Said(true, "Location is " + word + ".");
        }
        return new SwitchWords.Said(false, SwitchWords.dot("Location could not be switched " + word + (why.isEmpty() ? "" : ": " + why)));
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }
}
