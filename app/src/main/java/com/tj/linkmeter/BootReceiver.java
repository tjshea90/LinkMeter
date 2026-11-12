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
        // After an update the phone side always restarts, so a tablet-driven update never leaves it off.
        boolean updated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(a);
        boolean start = (Cfg.PHONE.equals(mode) && (updated || Cfg.autoStart(c)))
                || (Cfg.TABLET.equals(mode) && Cfg.shareFiles(c));
        if (start) {
            try {
                c.startForegroundService(new Intent(c, SenderService.class));
            } catch (RuntimeException ignored) {
                // Android refused a background start; the user can start it from the app.
            }
        }
        // Tablet: Fast Wi-Fi comes back by itself if it was on (an update or a restart stopped it), once Android's VPN OK is given
        if (Cfg.TABLET.equals(mode) && Cfg.fastWanted(c)) {
            try {
                if (android.net.VpnService.prepare(c) == null) {
                    FastVpn.start(c);
                }
            } catch (RuntimeException ignored) {
                // the Home card shows it as off; one tap there starts it
            }
        }
    }
}
