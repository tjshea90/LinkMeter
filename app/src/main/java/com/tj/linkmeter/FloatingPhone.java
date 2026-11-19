package com.tj.linkmeter;

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

    /** True when the service should run now. */
    static boolean wanted(Context c) {
        return Cfg.TABLET.equals(Cfg.mode(c)) && Cfg.floatOn(c) && Cfg.callsWatch(c) && canDraw(c);
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
    }

    /**
     * The user asked to see the floating phone now (a button): it stays until they close it. Returns false when Android's permission is
     * missing (the caller then sends the user to {@link #permissionIntent}).
     */
    static boolean showNow(Context c) {
        Context app = c.getApplicationContext();
        if (!Cfg.TABLET.equals(Cfg.mode(app)) || !canDraw(app)) {
            return false;
        }
        if (!Cfg.callsWatch(app)) {
            Cfg.setCallsWatch(app, true); // the user is using calls: the phone's picture is wanted
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
        if (!canDraw(c)) {
            return "Needs Android's permission \"Display over other apps\".";
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
