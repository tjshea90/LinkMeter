package com.tj.linkmeter;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Tablet only. The tablet's own hotspot, the Wi-Fi network the phone joins so that wireless debugging has something to run on (the
 * tablet has no hotspot of its own to switch on, and no mobile data). Two ways, then a third that is not ours:
 *  1. a Wi-Fi Direct group with a FIXED name and password (Android 10 lets an app choose both): the phone saves the network once and
 *     joins it by itself afterwards;
 *  2. a local-only hotspot, which Android gives a random name and password at every start (the phone has to be told them by hand);
 *  3. a hotspot made by another app (NetShare): noticed, used, but not stopped by us.
 * Everything here is asynchronous: {@link #start} returns at once and {@link #state} changes when Android answers. It needs the location
 * permission (Android 10 wants it before an app may make a Wi-Fi Direct group or a local-only hotspot).
 */
final class TabletHotspot {
    private static TabletHotspot instance;

    private final Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WifiP2pManager p2p;
    private WifiP2pManager.Channel channel;
    private WifiManager.LocalOnlyHotspotReservation loh;

    private volatile BootFlow.Hotspot state = BootFlow.Hotspot.OFF;
    private volatile String info = "";
    private volatile String ssid = "";
    private volatile String pass = "";
    private volatile String ip = "";
    private volatile String mode = "";
    private volatile boolean needsLocation;
    private volatile boolean external;
    /** When the hotspot last became ON (a Wi-Fi Direct group takes a moment to show up in the group info). */
    private volatile long onAt;
    /** When our own {@link #stop} ran: the group's network interface can stay up for a few seconds and must not look like NetShare's. */
    private volatile long stoppedAt = -1;
    /** Real Wi-Fi Direct failures since the last stop or success. "Wi-Fi is off" does not count: the third real try is a local-only hotspot. */
    private int directFails;

    /** When the last start began (to notice an Android that never answers). */
    private volatile long startedAt;

    private static final long GROUP_FORMING_MS = 8_000L;
    private static final long LINGER_MS = 15_000L;
    private static final long START_TIMEOUT_MS = 25_000L;

    private TabletHotspot(Context c) {
        app = c.getApplicationContext();
    }

    static synchronized TabletHotspot get(Context c) {
        if (instance == null) {
            instance = new TabletHotspot(c);
        }
        return instance;
    }

    BootFlow.Hotspot state() {
        return state;
    }

    /** The name and password while the hotspot is on, or why it did not start. */
    String info() {
        return info;
    }

    String ssid() {
        return ssid;
    }

    String password() {
        return pass;
    }

    String ip() {
        return ip;
    }

    /** "Wi-Fi Direct", "local-only" or "another app", while the hotspot is on or starting. */
    String mode() {
        return mode;
    }

    boolean needsLocation() {
        return needsLocation;
    }

    /** True if a hotspot made by another app is on (we cannot stop it). */
    boolean isExternal() {
        return external;
    }

    /** The password of the fixed hotspot: made once and kept, so the phone can save the network once. */
    String fixedPassword() {
        String p = Cfg.getStr(app, "bootPass", "");
        if (!BootNames.validPassword(p)) {
            p = BootNames.newPassword(new SecureRandom());
            Cfg.setStr(app, "bootPass", p);
        }
        return p;
    }

    String fixedName() {
        String n = Cfg.getStr(app, "bootSsid", BootNames.DEFAULT_SSID);
        return BootNames.validSsid(n) ? n : BootNames.DEFAULT_SSID;
    }

    // ------------------------------------------------------------------------------------------- start and stop

    /** Starts the hotspot: the first two tries make the fixed Wi-Fi Direct group, the third a local-only hotspot. Answers come later. */
    synchronized void start() {
        if (state == BootFlow.Hotspot.ON || state == BootFlow.Hotspot.STARTING) {
            return;
        }
        external = false;
        needsLocation = false;
        if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needsLocation = true;
            fail("The tablet needs the location permission before Android lets an app make a hotspot. Allow it when asked.");
            return;
        }
        startedAt = SystemClock.elapsedRealtime();
        state = BootFlow.Hotspot.STARTING;
        info = "starting…";
        boolean localOnly = Cfg.getBool(app, "bootLoh", false) || directFails >= 2;
        mode = localOnly ? "local-only" : "Wi-Fi Direct";
        try {
            if (localOnly) {
                startLocalOnly();
            } else {
                startDirect();
            }
        } catch (RuntimeException e) {
            fail("Android refused: " + e);
        }
    }

    /** Stops our own hotspot. A hotspot made by another app cannot be stopped from here. */
    synchronized void stop() {
        if (external) {
            info = "This hotspot was made by another app (NetShare?): stop it there.";
            return;
        }
        try {
            if (loh != null) {
                loh.close();
            }
        } catch (RuntimeException ignored) {
            // already closed
        }
        loh = null;
        if (p2p != null && channel != null) {
            try {
                p2p.removeGroup(channel, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        // the group is gone
                    }

                    @Override
                    public void onFailure(int reason) {
                        // nothing to remove, or it was already gone
                    }
                });
            } catch (RuntimeException ignored) {
                // no group
            }
        }
        stoppedAt = SystemClock.elapsedRealtime();
        state = BootFlow.Hotspot.OFF;
        info = "";
        ip = "";
        directFails = 0;
    }

    /** Is the group still there? Called every few seconds while the hotspot should be on, and looks for a hotspot made by another app. */
    void poll() {
        if (state == BootFlow.Hotspot.STARTING && SystemClock.elapsedRealtime() - startedAt > START_TIMEOUT_MS) {
            // Android never called back: say so, so that the next try can begin (a late answer still turns the hotspot on)
            String why = "Android did not answer within " + (START_TIMEOUT_MS / 1000) + " seconds.";
            if ("Wi-Fi Direct".equals(mode)) {
                directFail("Wi-Fi Direct: " + why);
            } else {
                fail("The local-only hotspot: " + why);
            }
        }
        if (state == BootFlow.Hotspot.ON && !external && p2p != null && channel != null && "Wi-Fi Direct".equals(mode)
                && SystemClock.elapsedRealtime() - onAt >= GROUP_FORMING_MS) {
            try {
                p2p.requestGroupInfo(channel, group -> {
                    if (group == null && state == BootFlow.Hotspot.ON && !external) {
                        state = BootFlow.Hotspot.OFF;
                        info = "Android removed the hotspot.";
                    }
                });
            } catch (RuntimeException ignored) {
                // cannot ask now
            }
        }
        detectExternal();
    }

    // ------------------------------------------------------------------------------------------- Wi-Fi Direct

    /** Is the tablet's Wi-Fi switched on? (An app cannot switch it on since Android 10; a Wi-Fi Direct group needs it.) */
    boolean wifiOn() {
        try {
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            return wm != null && wm.isWifiEnabled();
        } catch (RuntimeException e) {
            return true; // cannot tell: try anyway
        }
    }

    private void startDirect() {
        if (!wifiOn()) {
            fail("The tablet's Wi-Fi is off. Switch it on (the button below opens the switch); I try again by myself.");
            return;
        }
        p2p = (WifiP2pManager) app.getSystemService(Context.WIFI_P2P_SERVICE);
        if (p2p == null) {
            directFail("This tablet has no Wi-Fi Direct. Use a local-only hotspot or NetShare.");
            return;
        }
        if (channel == null) {
            // one connection to Wi-Fi Direct is kept; Android tells us when it is lost, and the next start makes a new one
            channel = p2p.initialize(app, Looper.getMainLooper(), new WifiP2pManager.ChannelListener() {
                @Override
                public void onChannelDisconnected() {
                    channel = null;
                }
            });
        }
        if (channel == null) {
            directFail("Android gave no Wi-Fi Direct connection. Switch the tablet's Wi-Fi on and try again.");
            return;
        }
        final String name = fixedName();
        final String password = fixedPassword();
        // An old group (from an earlier run, or from another app) would make the new one answer "busy": remove it first.
        p2p.requestGroupInfo(channel, group -> {
            if (group != null) {
                p2p.removeGroup(channel, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        ui.postDelayed(() -> createGroup(name, password), 800);
                    }

                    @Override
                    public void onFailure(int reason) {
                        createGroup(name, password);
                    }
                });
            } else {
                createGroup(name, password);
            }
        });
    }

    private void createGroup(final String name, final String password) {
        try {
            WifiP2pConfig cfg = new WifiP2pConfig.Builder()
                    .setNetworkName(name)
                    .setPassphrase(password)
                    .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                    .build();
            p2p.createGroup(channel, cfg, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    p2p.requestGroupInfo(channel, group -> {
                        ssid = group != null && group.getNetworkName() != null ? group.getNetworkName() : name;
                        pass = group != null && group.getPassphrase() != null ? group.getPassphrase() : password;
                        String iface = group != null ? group.getInterface() : null;
                        ip = addressOf(iface);
                        info = ssid + "  password " + pass;
                        directFails = 0;
                        turnedOn();
                    });
                }

                @Override
                public void onFailure(int reason) {
                    directFail("Wi-Fi Direct said no: " + p2pReason(reason));
                }
            });
        } catch (IllegalArgumentException | SecurityException e) {
            directFail("Android refused the name or password: " + e.getMessage());
        }
    }

    private static String p2pReason(int r) {
        switch (r) {
            case WifiP2pManager.P2P_UNSUPPORTED:
                return "this tablet does not support Wi-Fi Direct groups";
            case WifiP2pManager.BUSY:
                return "Wi-Fi Direct is busy (switch the tablet's Wi-Fi off and on, or wait a moment)";
            case WifiP2pManager.ERROR:
                return "an internal error (switch the tablet's Wi-Fi and location on, then try again)";
            default:
                return "code " + r;
        }
    }

    // ------------------------------------------------------------------------------------------- local-only hotspot

    @SuppressWarnings("deprecation")
    private void startLocalOnly() {
        WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            fail("This tablet has no Wi-Fi service.");
            return;
        }
        wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
            @Override
            public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                loh = reservation;
                WifiConfiguration cfg = reservation.getWifiConfiguration();
                ssid = unquote(cfg == null ? null : cfg.SSID);
                pass = unquote(cfg == null ? null : cfg.preSharedKey);
                ip = addressOfApInterface();
                info = ssid + "  password " + pass + "  (new at every start: the phone has to join it by hand)";
                turnedOn();
            }

            @Override
            public void onStopped() {
                if (state == BootFlow.Hotspot.ON && !external) {
                    state = BootFlow.Hotspot.OFF;
                    info = "The local-only hotspot stopped.";
                }
            }

            @Override
            public void onFailed(int reason) {
                fail("The local-only hotspot said no: " + lohReason(reason));
            }
        }, ui);
    }

    private static String lohReason(int r) {
        switch (r) {
            case WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL:
                return "no free Wi-Fi channel";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE:
                return "the tablet's Wi-Fi is in a mode that does not allow it (switch Wi-Fi off and on)";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED:
                return "tethering is not allowed on this tablet";
            default:
                return "a general error (is Location switched on?)";
        }
    }

    private static String unquote(String s) {
        if (s == null) {
            return "";
        }
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    // ------------------------------------------------------------------------------------------- a hotspot from another app

    /** If another app (NetShare) has made a hotspot and ours is not on, say it is on: the flow only needs a network the phone can join. */
    private void detectExternal() {
        if (state == BootFlow.Hotspot.STARTING) {
            return;
        }
        if (state == BootFlow.Hotspot.ON && !external) {
            return;
        }
        if (stoppedAt >= 0 && SystemClock.elapsedRealtime() - stoppedAt < LINGER_MS) {
            return; // our own group is still closing: its network interface is not another app's hotspot
        }
        String found = addressOfApInterface();
        if (!found.isEmpty()) {
            if (!external) {
                external = true;
                mode = "another app";
                ip = found;
                ssid = "";
                pass = "";
                info = "A hotspot is on (made by another app, maybe NetShare) at " + found + ".";
                turnedOn();
            }
        } else if (external) {
            external = false;
            state = BootFlow.Hotspot.OFF;
            info = "";
            ip = "";
        }
    }

    /** The address of a hotspot interface of this tablet (the phone's gateway when it joins), or "". */
    static String addressOfApInterface() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String n = ni.getName().toLowerCase(Locale.US);
                boolean apName = n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") || n.startsWith("p2p")
                        || (n.startsWith("wlan") && !n.equals("wlan0"));
                if (apName && ni.isUp()) {
                    String a = firstPrivateV4(ni);
                    if (!a.isEmpty()) {
                        return a;
                    }
                }
            }
        } catch (Exception | LinkageError ignored) {
            // cannot tell
        }
        return "";
    }

    private static String addressOf(String iface) {
        try {
            if (iface != null) {
                NetworkInterface ni = NetworkInterface.getByName(iface);
                if (ni != null) {
                    String a = firstPrivateV4(ni);
                    if (!a.isEmpty()) {
                        return a;
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through to the usual one
        }
        String any = addressOfApInterface();
        return any.isEmpty() ? "192.168.49.1" : any;
    }

    private static String firstPrivateV4(NetworkInterface ni) {
        List<InetAddress> addrs = Collections.list(ni.getInetAddresses());
        for (InetAddress a : addrs) {
            if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                return a.getHostAddress();
            }
        }
        return "";
    }

    private void fail(String why) {
        info = why;
        state = BootFlow.Hotspot.FAILED;
    }

    /** A real Wi-Fi Direct failure (not "the Wi-Fi is off"): after two of them the next try is a local-only hotspot. */
    private void directFail(String why) {
        directFails++;
        fail(why);
    }

    private void turnedOn() {
        onAt = SystemClock.elapsedRealtime();
        state = BootFlow.Hotspot.ON;
    }

    // ------------------------------------------------------------------------------------------- NetShare

    /** The launch intent of an installed app whose name contains "netshare", or null. */
    static Intent netShareIntent(Context c) {
        PackageManager pm = c.getPackageManager();
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                CharSequence label = pm.getApplicationLabel(ai);
                if (label != null && label.toString().toLowerCase(Locale.US).contains("netshare")) {
                    Intent i = pm.getLaunchIntentForPackage(ai.packageName);
                    if (i != null) {
                        return i;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // cannot list the apps
        }
        return null;
    }
}
