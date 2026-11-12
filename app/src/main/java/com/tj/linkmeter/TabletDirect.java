package com.tj.linkmeter;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import java.util.Collections;
import java.util.List;

/**
 * Tablet only. Lets the tablet join the phone's own Wi-Fi Direct network for Fast Wi-Fi (stage 2, docs/FAST_WIFI.md) without the user
 * typing its password: Android 10 does not let an app add a Wi-Fi network itself any more, but it may SUGGEST one, which Android then
 * joins by itself whenever it is in range and nothing better is (the phone's hotspot and that network are never on at the same time).
 * The first time, Android asks the user once ("Allow suggested Wi-Fi networks?"). A network picked by hand in Wi-Fi settings works
 * just as well.
 */
final class TabletDirect {
    private TabletDirect() {}

    /** What the last suggestion said (for the card). */
    static volatile String last = "";

    /** Suggests the network (again only when its name or password changed). True when Android has it now. */
    static boolean suggest(Context c, String ssid, String pass) {
        if (ssid.isEmpty() || pass.length() < 8) {
            last = "The phone has not told the tablet its network yet.";
            return false;
        }
        String done = Cfg.getStr(c, "fastSuggested", "");
        String key = ssid + "\n" + pass;
        if (key.equals(done)) {
            return true;
        }
        WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            return false;
        }
        try {
            if (!done.isEmpty()) {
                int nl = done.indexOf('\n');
                wm.removeNetworkSuggestions(list(done.substring(0, nl), done.substring(nl + 1))); // the old password is no use
            }
            int r = wm.addNetworkSuggestions(list(ssid, pass));
            boolean ok = r == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
                    || r == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE;
            if (ok) {
                Cfg.setStr(c, "fastSuggested", key);
                last = "";
            } else {
                last = "Android did not take the suggested network (code " + r + "). Pick " + ssid + " in Wi-Fi settings once.";
            }
            return ok;
        } catch (RuntimeException e) {
            last = "Android did not take the suggested network: " + e.getMessage();
            return false;
        }
    }

    private static List<WifiNetworkSuggestion> list(String ssid, String pass) {
        return Collections.singletonList(new WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(pass)
                .setIsAppInteractionRequired(false)
                .build());
    }

    /** Asks Android to look for networks now, so it joins sooner than its own next look (allowed a few times a minute). */
    @SuppressWarnings("deprecation")
    static void scan(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wm.startScan();
            }
        } catch (RuntimeException ignored) {
            // Android looks by itself a little later
        }
    }
}
