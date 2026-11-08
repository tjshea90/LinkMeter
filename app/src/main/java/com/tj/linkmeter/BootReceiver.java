package com.tj.linkmeter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts the service after a reboot or an app update, so the phone is ready
 * for the tablet without opening anything. The receiver itself does no work
 * except that one start.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            return;
        }
        String mode = Cfg.mode(c);
        boolean start = (Cfg.PHONE.equals(mode) && Cfg.autoStart(c))
                || (Cfg.TABLET.equals(mode) && Cfg.shareFiles(c));
        if (start) {
            try {
                c.startForegroundService(new Intent(c, SenderService.class));
            } catch (RuntimeException ignored) {
                // Android refused a background start; the user can start it from the app.
            }
        }
    }
}
