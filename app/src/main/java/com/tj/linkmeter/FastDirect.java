package com.tj.linkmeter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Phone only. Fast Wi-Fi without the hotspot (stage 2, docs/FAST_WIFI.md): the phone's hotspot goes off and a Wi-Fi Direct group takes
 * its place, a Wi-Fi network named {@link #NAME} on 2.4 GHz with a fixed password, which the tablet joins like any Wi-Fi network (as
 * NetShare does). Android serves such a group "local only": addresses, but no forwarding to the internet and no tethering at all, so
 * the tablet's internet can only go through LinkMeter's relay ({@link FastRelay}), which is the point.
 *
 * The mode is remembered ({@link Cfg#fastDirect}): the group is made again after a restart of the service, and again if Android takes
 * it away while the mode is on (a few seconds later, a few times). Every hotspot button ends the mode first ({@link #end}).
 */
final class FastDirect {
    private FastDirect() {}

    /** The network's name: Android wants "DIRECT-" and two characters in front. */
    static final String NAME = FastRules.FAST_NET;
    static final String NEARBY = "android.permission.NEARBY_WIFI_DEVICES";

    private static HandlerThread thread;
    private static Handler h;
    private static volatile WifiP2pManager p2p;
    private static volatile WifiP2pManager.Channel channel;
    private static BroadcastReceiver watcher;
    /** The group is up now (as Android last said). */
    static volatile boolean up;
    /** What the last try said, for the status and the phone's screen. */
    static volatile String last = "";
    private static int againTries;

    /** The group's password: made once, kept, so the tablet saves the network once. */
    static String password(Context c) {
        String p = Cfg.getStr(c, "fastDirectPass", "");
        if (p.length() < 12) {
            String abc = "abcdefghjkmnpqrstuvwxyz23456789";
            SecureRandom r = new SecureRandom();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                sb.append(abc.charAt(r.nextInt(abc.length())));
            }
            p = sb.toString();
            Cfg.setStr(c, "fastDirectPass", p);
        }
        return p;
    }

    // ------------------------------------------------------------------------------------------- the actions (worker threads)

    /** fast_direct_on: the hotspot off, the phone's Wi-Fi on, the group up. Takes up to half a minute; says what happened. */
    static String on(Context c) throws IOException {
        Cfg.setFastDirect(c, true);
        againTries = 0;
        if (Boolean.TRUE.equals(Radios.hotspot(c))) {
            Cells.shellMain(c, "hotspot-stop"); // the direct way only: the airplane-mode way would take mobile data away for a moment
            Radios.waitHotspot(c, false, 6000);
        }
        if (!Radios.wifiOn(c)) {
            Priv.run("cmd wifi set-wifi-enabled enabled 2>&1 || svc wifi enable 2>&1", 20);
            if (!Radios.waitWifi(c, true, 8000)) {
                throw new Actions.NotDone("Fast Wi-Fi without the hotspot needs the phone's Wi-Fi on, and it did not come on"
                        + (Priv.ready() ? "." : " (Shizuku is not running)."));
            }
        }
        allow(c);
        String err = start(c, 20_000L);
        if (err != null) {
            throw new Actions.NotDone("The phone's Wi-Fi Direct network did not start: " + err);
        }
        watch(c);
        return "Fast Wi-Fi now has its own Wi-Fi network on the phone (" + NAME + ", 2.4 GHz), and the hotspot is off. The tablet joins "
                + "it by itself.";
    }

    /** fast_direct_off: the group goes, then the hotspot comes back (as Home's Hotspot on does it). */
    static String off(Context c) throws IOException {
        end(c);
        return Actions.perform(c, "hotspot_on", false);
    }

    /** Every hotspot button (on, off, deep, Standby) ends the mode first, so the hotspot buttons always mean the normal hotspot. */
    static void end(Context c) {
        boolean was = Cfg.fastDirect(c) || up;
        Cfg.setFastDirect(c, false);
        if (was) {
            unwatch(c);
            remove(5000L);
        }
    }

    /** At service start: the group comes back if the mode was on (the hotspot is not touched here). */
    static void resume(final Context c) {
        if (!Cfg.fastDirect(c)) {
            return;
        }
        new Thread(() -> {
            try {
                allow(c);
                String err = start(c, 20_000L);
                last = err == null ? "" : err;
                if (err == null) {
                    watch(c);
                }
            } catch (RuntimeException e) {
                last = String.valueOf(e.getMessage());
            }
        }, "fast-direct-resume").start();
    }

    // ------------------------------------------------------------------------------------------- the group

    /** Gives LinkMeter "Nearby devices: Wi-Fi" through Shizuku if it does not have it (Android 13 and newer want it for a group). */
    private static void allow(Context c) {
        if (Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(NEARBY) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            Priv.run("pm grant " + c.getPackageName() + " " + NEARBY, 15);
        } catch (IOException ignored) {
            // the group's own failure then says it
        }
    }

    private static synchronized void init(Context c) {
        if (thread == null) {
            thread = new HandlerThread("fast-direct");
            thread.start();
            h = new Handler(thread.getLooper());
        }
        if (p2p == null) {
            p2p = (WifiP2pManager) c.getApplicationContext().getSystemService(Context.WIFI_P2P_SERVICE);
        }
        if (p2p != null && channel == null) {
            channel = p2p.initialize(c.getApplicationContext(), thread.getLooper(), () -> {
                synchronized (FastDirect.class) {
                    channel = null; // Android dropped the connection: the next use makes a new one
                }
                up = false;
            });
        }
    }

    /** Makes the group (an old one is removed first). null when it is up, else why not. Waits up to ms. */
    static String start(Context c, long ms) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(NEARBY) != PackageManager.PERMISSION_GRANTED) {
            return "LinkMeter is not allowed to use Nearby Wi-Fi devices. Press Allow everything on the phone's LinkMeter screen "
                    + "(Shizuku must be running), or allow Nearby devices in its app settings.";
        }
        init(c);
        if (p2p == null || channel == null) {
            return "this phone has no Wi-Fi Direct.";
        }
        remove(4000L); // a group from an earlier run (or another app) would make the new one fail as "busy"
        final String[] result = {null};
        final CountDownLatch done = new CountDownLatch(1);
        try {
            WifiP2pConfig cfg = new WifiP2pConfig.Builder()
                    .setNetworkName(NAME)
                    .setPassphrase(password(c))
                    .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                    .build();
            p2p.createGroup(channel, cfg, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    // Android took it; the group shows in the group information a moment later
                    h.postDelayed(() -> check(result, done, 8), 500);
                }

                @Override
                public void onFailure(int reason) {
                    result[0] = reason(reason);
                    done.countDown();
                }
            });
        } catch (RuntimeException e) {
            result[0] = String.valueOf(e.getMessage());
            done.countDown();
        }
        try {
            if (!done.await(ms, TimeUnit.MILLISECONDS)) {
                result[0] = "Android did not answer in time.";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result[0] = "interrupted.";
        }
        last = result[0] == null ? "" : result[0];
        return result[0];
    }

    /** Looks (up to tries times, half a second apart) whether the group is there now. */
    private static void check(final String[] result, final CountDownLatch done, final int tries) {
        if (p2p == null || channel == null) {
            result[0] = "Wi-Fi Direct went away.";
            done.countDown();
            return;
        }
        p2p.requestGroupInfo(channel, g -> {
            if (g != null && g.isGroupOwner()) {
                up = true;
                result[0] = null;
                done.countDown();
            } else if (tries > 0) {
                h.postDelayed(() -> check(result, done, tries - 1), 500);
            } else {
                result[0] = "the network did not show up.";
                done.countDown();
            }
        });
    }

    /** Removes our group (any group of this phone, in fact), waiting up to ms for Android. */
    private static void remove(long ms) {
        WifiP2pManager m = p2p;
        WifiP2pManager.Channel ch = channel;
        up = false;
        if (m == null || ch == null) {
            return;
        }
        final CountDownLatch done = new CountDownLatch(1);
        try {
            m.removeGroup(ch, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    done.countDown();
                }

                @Override
                public void onFailure(int reason) {
                    done.countDown(); // nothing to remove
                }
            });
            done.await(ms, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            // no group
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String reason(int r) {
        switch (r) {
            case WifiP2pManager.P2P_UNSUPPORTED:
                return "this phone does not offer Wi-Fi Direct groups.";
            case WifiP2pManager.BUSY:
                return "Wi-Fi Direct is busy (is the hotspot still on?).";
            case WifiP2pManager.ERROR:
                return "an internal error of Wi-Fi Direct (switch the phone's Wi-Fi off and on).";
            default:
                return "Wi-Fi Direct said no (code " + r + ").";
        }
    }

    // ------------------------------------------------------------------------------------------- keeping it up

    /** While the mode is on: when Android ends the group (Wi-Fi switched off, Wi-Fi Direct reset), it is made again a few times. */
    private static synchronized void watch(final Context c) {
        if (watcher != null) {
            return;
        }
        watcher = new BroadcastReceiver() {
            @Override
            public void onReceive(Context x, Intent i) {
                WifiP2pGroup g = i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP);
                boolean now = g != null && g.isGroupOwner() && NAME.equals(g.getNetworkName());
                if (now) {
                    up = true;
                    againTries = 0;
                    return;
                }
                if (up && Cfg.fastDirect(c) && againTries < 5) {
                    up = false;
                    againTries++;
                    final Context app = c.getApplicationContext();
                    h.postDelayed(() -> new Thread(() -> {
                        if (Cfg.fastDirect(app)) {
                            String err = start(app, 20_000L);
                            last = err == null ? "" : "Wi-Fi Direct stopped and could not be started again: " + err;
                        }
                    }, "fast-direct-again").start(), 3000L * againTries);
                }
            }
        };
        try {
            IntentFilter f = new IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                c.getApplicationContext().registerReceiver(watcher, f, Context.RECEIVER_NOT_EXPORTED); // the system's own still arrive
            } else {
                c.getApplicationContext().registerReceiver(watcher, f);
            }
        } catch (RuntimeException e) {
            watcher = null;
        }
    }

    private static synchronized void unwatch(Context c) {
        if (watcher == null) {
            return;
        }
        try {
            c.getApplicationContext().unregisterReceiver(watcher);
        } catch (RuntimeException ignored) {
            // gone
        }
        watcher = null;
    }
}
