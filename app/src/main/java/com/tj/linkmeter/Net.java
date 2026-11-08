package com.tj.linkmeter;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;

/** Tablet side: where is the phone? */
final class Net {
    private Net() {}

    /** The other device: on the tablet that is the phone; on the phone it is the tablet that last connected. */
    static String peerHost(Context c) {
        return Cfg.PHONE.equals(Cfg.mode(c)) ? Cfg.tabletIp(c) : phoneHost(c);
    }

    /** The secret the other device expects. The tablet's own secret is the shared one. */
    static String peerToken(Context c) {
        return Cfg.PHONE.equals(Cfg.mode(c)) ? Cfg.tabletToken(c) : Cfg.token(c);
    }

    /** The phone's address: the typed one, or the Wi-Fi gateway (the phone, while on its hotspot). */
    static String phoneHost(Context c) {
        String typed = Cfg.host(c);
        if (!typed.isEmpty()) {
            return typed;
        }
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            DhcpInfo d = wm.getDhcpInfo();
            if (d != null && d.gateway != 0) {
                int g = d.gateway;
                return (g & 0xFF) + "." + ((g >> 8) & 0xFF) + "." + ((g >> 16) & 0xFF) + "."
                        + ((g >> 24) & 0xFF);
            }
        } catch (RuntimeException ignored) {
            // no Wi-Fi information available
        }
        return "";
    }
}
