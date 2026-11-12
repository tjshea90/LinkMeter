package com.tj.linkmeter;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Tablet only. Fast Wi-Fi's quick settings tile (docs/FAST_WIFI.md): one tap in the notification shade switches between the normal
 * hotspot and Fast Wi-Fi, without opening LinkMeter. The first time, Android's VPN question needs the app: the tile then opens it.
 * On the phone the tile is shown as unavailable (Fast Wi-Fi is switched on from the tablet).
 */
public class FastTile extends TileService {
    @Override
    public void onStartListening() {
        paint();
    }

    @Override
    public void onClick() {
        if (!Cfg.TABLET.equals(Cfg.mode(this))) {
            paint();
            return;
        }
        if (Cfg.fastWanted(this)) {
            FastVpn.stop(this);
        } else {
            Intent ask = null;
            try {
                ask = VpnService.prepare(this);
            } catch (RuntimeException ignored) {
                // the app asks again
            }
            if (ask == null) {
                FastVpn.start(this);
            } else {
                // Android's question needs a screen: LinkMeter opens and asks it
                startActivityAndCollapse(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_FAST, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            }
        }
        paint();
    }

    private void paint() {
        Tile t = getQsTile();
        if (t == null) {
            return;
        }
        boolean tablet = Cfg.TABLET.equals(Cfg.mode(this));
        boolean wanted = tablet && Cfg.fastWanted(this);
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_fast));
        t.setLabel("Fast Wi-Fi");
        t.setState(!tablet ? Tile.STATE_UNAVAILABLE : wanted ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) {
            t.setSubtitle(!tablet ? "On the tablet" : !wanted ? "Off: normal hotspot"
                    : FastVpn.state == FastVpn.State.ON ? "On" : "Waiting for the phone");
        }
        t.updateTile();
    }

    /** Asks Android to show the tile's state again (after Fast Wi-Fi changed by any other way). */
    static void refresh(Context c) {
        try {
            TileService.requestListeningState(c, new ComponentName(c, FastTile.class));
        } catch (RuntimeException ignored) {
            // the tile is not in the user's quick settings
        }
    }
}
