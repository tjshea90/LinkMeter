package com.tj.linkmeter;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;
import java.net.Inet4Address;
import java.net.InetAddress;

/** Tablet side: where is the phone? */
final class Net {
    /** How the last phone address was found ("typed", "route", "dhcp" or "none"), for the status line. */
    static volatile String how = "none";

    private Net() {}

    /** The other device: on the tablet that is the phone; on the phone it is the tablet that last connected. */
    static String peerHost(Context c) {
        return Cfg.PHONE.equals(Cfg.mode(c)) ? Cfg.tabletIp(c) : phoneHost(c);
    }

    /** The secret the other device expects. The tablet's own secret is the shared one. */
    static String peerToken(Context c) {
        return Cfg.PHONE.equals(Cfg.mode(c)) ? Cfg.tabletToken(c) : Cfg.token(c);
    }

    /** The Wi-Fi network Android says the tablet is on, or null. Needs no location permission. */
    static Network wifi(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getApplicationContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return n;
                }
            }
        } catch (RuntimeException ignored) {
            // no connectivity information available
        }
        return null;
    }

    /**
     * The phone's address: the typed one, else the default route of the Wi-Fi network (the
     * phone, while on its hotspot), else the gateway from the Wi-Fi manager. Empty if unknown.
     */
    static String phoneHost(Context c) {
        String typed = Cfg.host(c);
        if (!typed.isEmpty()) {
            how = "typed";
            return typed;
        }
        Context app = c.getApplicationContext();
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = wifi(c);
            LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
            if (lp != null) {
                for (RouteInfo r : lp.getRoutes()) {
                    InetAddress g = r.getGateway();
                    if (r.isDefaultRoute() && g instanceof Inet4Address && !g.isAnyLocalAddress()) {
                        how = "route";
                        return g.getHostAddress();
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // try the next way
        }
        try {
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            DhcpInfo d = wm.getDhcpInfo();
            if (d != null && d.gateway != 0) {
                int g = d.gateway;
                how = "dhcp";
                return (g & 0xFF) + "." + ((g >> 8) & 0xFF) + "." + ((g >> 16) & 0xFF) + "."
                        + ((g >> 24) & 0xFF);
            }
        } catch (RuntimeException ignored) {
            // no Wi-Fi information available
        }
        how = "none";
        return "";
    }
}
