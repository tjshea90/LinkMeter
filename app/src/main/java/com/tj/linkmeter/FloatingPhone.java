package com.tj.linkmeter;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

/**
 * Tablet only: the switchboard of the floating phone (docs/FLOATING_PHONE.md): whether it is wanted, whether Android allows it, and
 * starting and stopping the service that draws it. The service runs only while the floating phone can do something: the tablet role,
 * the setting on, calls working (the Calls tab found the phone allowing them) and Android's "Display over other apps" given. Anything
 * else and no service runs, so a tablet that does not use it pays nothing for it.
 */
final class FloatingPhone {
    private FloatingPhone() {}

    /** Tests only (tools/uipreview): a pretend answer to "may this app draw over other apps" (null = ask Android). */
    static volatile Boolean canDrawForTest;

    /** Tests only (tools/uipreview): a pretend answer to "is the accessibility overlay service switched on" (null = ask the service). */
    static volatile Boolean accessForTest;

    /** The accessibility service is switched on and connected: it draws the card, with no permission of Android's overlay kind. */
    static boolean accessOn() {
        Boolean t = accessForTest;
        return t != null ? t : FloatingPhoneAccess.connected();
    }

    /**
     * Android turns "Display over other apps" off on low-memory devices (Android 10 Go: "Feature not available: this feature has been turned
     * off because it slows down your phone"): the permission can not be asked for, and the accessibility service is the way.
     */
    static boolean overlayUnavailable(Context c) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            return am != null && am.isLowRamDevice();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The card can be drawn now: by the accessibility service, or because Android allows it over other apps. */
    static boolean canShow(Context c) {
        return accessOn() || canDraw(c);
    }

    /** LinkMeter's own screens are in front (the card keeps out of their way, see {@link FloatRules.In#appInFront}). */
    private static volatile boolean appInFront;

    static boolean appInFront() {
        return appInFront;
    }

    /** The app came to the front or went away: the card hides or shows at once. Called by the main screen. */
    static void setAppInFront(boolean front) {
        if (appInFront == front) {
            return;
        }
        appInFront = front;
        FloatingPhoneController.poke();
    }

    /** Does Android let this app draw over other apps (the special permission "Display over other apps")? */
    static boolean canDraw(Context c) {
        Boolean t = canDrawForTest;
        if (t != null) {
            return t;
        }
        try {
            return Settings.canDrawOverlays(c);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** True when the foreground service (the "Display over other apps" way) should run now. The accessibility service needs no such service. */
    static boolean wanted(Context c) {
        return Cfg.TABLET.equals(Cfg.mode(c)) && Cfg.floatOn(c) && Cfg.callsWatch(c) && canDraw(c) && !accessOn();
    }

    /** Starts or stops the service to match {@link #wanted}. Safe to call as often as you like (the app calls it whenever it comes to the front). */
    static void sync(Context c) {
        Context app = c.getApplicationContext();
        try {
            if (wanted(app)) {
                app.startForegroundService(new Intent(app, FloatingPhoneService.class));
            } else if (FloatingPhoneService.running()) {
                app.stopService(new Intent(app, FloatingPhoneService.class));
            }
        } catch (RuntimeException e) {
            // Android refused a start from the background: the next time the app is open it tries again
        }
        FloatingPhoneController.poke(); // the accessibility card looks again (a setting changed, calls started to work)
    }

    /**
     * The user asked to see the floating phone now (a button): it stays until they close it. Returns false when Android's permission is
     * missing (the caller then sends the user to {@link #permissionIntent}).
     */
    static boolean showNow(Context c) {
        Context app = c.getApplicationContext();
        if (!Cfg.TABLET.equals(Cfg.mode(app)) || !canShow(app)) {
            return false;
        }
        if (!Cfg.callsWatch(app)) {
            Cfg.setCallsWatch(app, true); // the user is using calls: the phone's picture is wanted
        }
        if (accessOn()) {
            return FloatingPhoneController.showNow();
        }
        try {
            app.startForegroundService(new Intent(app, FloatingPhoneService.class).setAction(FloatingPhoneService.ACTION_SHOW));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Android's own screen for the permission "Display over other apps", opened at this app. */
    static Intent permissionIntent(Context c) {
        return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + c.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** What the Settings card says about the state of the floating phone, in one line. */
    static String stateLine(Context c) {
        if (accessOn()) {
            return Cfg.floatOn(c) ? "On, through Accessibility: it pops up when the phone rings or a call is on."
                    : "Off: it will not pop up by itself. \"Show the phone now\" still works.";
        }
        if (!canDraw(c)) {
            return overlayUnavailable(c)
                    ? "Android turns \"Display over other apps\" off on this tablet. Use Accessibility instead (the button below)."
                    : "Needs Android's permission \"Display over other apps\", or the Accessibility way below.";
        }
        if (!Cfg.floatOn(c)) {
            return "Off: it will not pop up by itself. \"Show the phone now\" still works.";
        }
        if (!Cfg.callsWatch(c)) {
            return "Ready once calls work: open the Calls tab once, with calls switched on at the phone.";
        }
        return FloatingPhoneService.running() ? "On: it pops up when the phone rings or a call is on." : "On, starting…";
    }
}
