package com.tj.linkmeter;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.res.Configuration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Tablet only: hosts the floating phone ({@link FloatingPhoneController}) as an accessibility overlay (docs/FLOATING_PHONE.md). Android
 * 10 Go turns "Display over other apps" off for every app ("Feature not available: this feature has been turned off because it slows down
 * your phone"), but an enabled accessibility service may draw its own window over other apps ({@code TYPE_ACCESSIBILITY_OVERLAY}), and
 * that is all this service is for: it declares no window content and no events (res/xml/floating_phone_access.xml), {@link
 * #onAccessibilityEvent} does nothing, and it never looks at what is on the screen. The user switches it on once in Android's
 * Accessibility settings. Android keeps an enabled accessibility service running, so there is no notification and no foreground service.
 */
public final class FloatingPhoneAccess extends AccessibilityService {
    private static volatile boolean connected;

    /** The service is switched on in Android's Accessibility settings and connected. */
    static boolean connected() {
        return connected;
    }

    private FloatingPhoneController controller;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        if (Cfg.TABLET.equals(Cfg.mode(this))) {
            controller = new FloatingPhoneController(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, () -> {
            });
            controller.start();
        }
        FloatingPhone.sync(this); // the foreground service has nothing left to do: this one carries the card
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // receives none (see the configuration) and never looks at one
    }

    @Override
    public void onInterrupt() {
        // nothing is spoken
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (controller != null) {
            controller.configurationChanged();
        }
    }

    private void release() {
        connected = false;
        if (controller != null) {
            controller.stop();
            controller = null;
        }
        FloatingPhone.sync(this); // the other way (Display over other apps) may take over where Android allows it
    }

    @Override
    public boolean onUnbind(Intent intent) {
        release();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        release();
        super.onDestroy();
    }
}
