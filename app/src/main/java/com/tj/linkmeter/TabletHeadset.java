package com.tj.linkmeter;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * Tablet only: the headset's link to THIS TABLET, so the same headset can play the tablet's sound as well as the phone's (the call's sound
 * stays on the phone; docs/FLOATING_PHONE.md). What the card says and when is decided by {@link TabletHeadsetRules}; this reads the
 * tablet's own Bluetooth and does the switching. An ordinary app may not connect or disconnect a headset (Android asks for a privilege
 * only the system and the shell have), so {@link #connect} and {@link #disconnect} try every way there is, in this order, and read the
 * tablet's own list after each, because "the call went through" proves nothing:
 * <ol>
 * <li>it is already so;</li>
 * <li>Shizuku, when it runs on this tablet and LinkMeter is allowed in it (the phone's way: {@code ShellMain bt-connect});</li>
 * <li>the hidden connect and disconnect of the hands-free and music profiles, called from this app (Android usually refuses it);</li>
 * <li>Android's own Bluetooth list, opened for ONE tap of the user. That is said as what it is: the app asked, nothing was switched.</li>
 * </ol>
 * Nothing here runs while the headset screens are not in use: the profile connections and the receiver are made when a screen asks and
 * dropped when the last one goes.
 */
final class TabletHeadset {
    /** How a switch came out; always delivered on the UI thread. */
    interface Done {
        void done(TabletHeadsetRules.Way way);
    }

    private static final long LOOK_MS = 400;
    private static final int LOOKS = 26;
    private static final String[] AUDIO_UUIDS = {"0000111e", "00001108", "0000110b"};

    /** Tests only (tools/uipreview): true stands for an Android that refuses every switch, so the tap-in-the-list path is the one shown. */
    static volatile boolean refusedForTest;
    /** Tests only: a pretend tablet Bluetooth (null = the real one). */
    static volatile TabletHeadsetRules.Facts pretend;

    private static TabletHeadset instance;

    static synchronized TabletHeadset of(Context c) {
        if (instance == null) {
            instance = new TabletHeadset(c.getApplicationContext());
        }
        return instance;
    }

    private final Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Integer> batteries = new ConcurrentHashMap<>();
    private volatile BluetoothHeadset hfp;
    private volatile BluetoothA2dp a2dp;
    private boolean registered;
    private volatile boolean reflectBlocked;
    private volatile Method levelMethod;

    private TabletHeadset(Context c) {
        app = c;
    }

    private BluetoothAdapter adapter() {
        try {
            BluetoothManager bm = app.getSystemService(BluetoothManager.class);
            return bm == null ? null : bm.getAdapter();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String up(String a) {
        return a == null ? "" : a.toUpperCase(Locale.US);
    }

    // ------------------------------------------------------------------ listening (only while a screen wants to know)

    private final BluetoothProfile.ServiceListener proxyListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.HEADSET) {
                hfp = (BluetoothHeadset) proxy;
            } else if (profile == BluetoothProfile.A2DP) {
                a2dp = (BluetoothA2dp) proxy;
            }
            tell();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.HEADSET) {
                hfp = null;
            } else if (profile == BluetoothProfile.A2DP) {
                a2dp = null;
            }
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (HeadsetLink.ACTION_BATTERY.equals(i.getAction())) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int level = BtBattery.clean(i.getIntExtra(HeadsetLink.EXTRA_BATTERY, Calls.BATTERY_UNKNOWN));
                if (d != null && d.getAddress() != null && level >= 0) {
                    batteries.put(up(d.getAddress()), level);
                }
            }
            tell();
        }
    };

    private void tell() {
        ui.post(() -> {
            for (Runnable r : listeners) {
                r.run();
            }
        });
    }

    /** Starts listening to the tablet's Bluetooth for this screen. Pair every call with {@link #remove}. */
    synchronized void add(Runnable l) {
        listeners.add(l);
        if (registered || pretend != null) {
            return;
        }
        BluetoothAdapter a = adapter();
        if (a == null) {
            return;
        }
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(HeadsetLink.ACTION_BATTERY);
        try {
            app.registerReceiver(receiver, f);
            a.getProfileProxy(app, proxyListener, BluetoothProfile.HEADSET);
            a.getProfileProxy(app, proxyListener, BluetoothProfile.A2DP);
            registered = true;
        } catch (RuntimeException e) {
            // no Bluetooth permission after all: the screens say so
        }
    }

    synchronized void remove(Runnable l) {
        listeners.remove(l);
        if (!listeners.isEmpty() || !registered) {
            return;
        }
        registered = false;
        try {
            app.unregisterReceiver(receiver);
        } catch (RuntimeException ignored) {
            // it was not registered
        }
        BluetoothAdapter a = adapter();
        try {
            if (a != null) {
                if (hfp != null) {
                    a.closeProfileProxy(BluetoothProfile.HEADSET, hfp);
                }
                if (a2dp != null) {
                    a.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
                }
            }
        } catch (RuntimeException ignored) {
            // nothing to close
        }
        hfp = null;
        a2dp = null;
    }

    // ------------------------------------------------------------------ reading

    boolean hasBluetooth() {
        return pretend != null ? pretend.hasBluetooth : adapter() != null;
    }

    boolean bluetoothOn() {
        if (pretend != null) {
            return pretend.bluetoothOn;
        }
        try {
            BluetoothAdapter a = adapter();
            return a != null && a.isEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Set<String> connectedAddrs() {
        Set<String> out = new HashSet<>();
        try {
            BluetoothHeadset h = hfp;
            if (h != null) {
                for (BluetoothDevice d : h.getConnectedDevices()) {
                    out.add(up(d.getAddress()));
                }
            }
            BluetoothA2dp s = a2dp;
            if (s != null) {
                for (BluetoothDevice d : s.getConnectedDevices()) {
                    out.add(up(d.getAddress()));
                }
            }
        } catch (RuntimeException e) {
            // the proxy went away while we looked
        }
        return out;
    }

    private static boolean isAudio(BluetoothDevice d) {
        try {
            BluetoothClass bc = d.getBluetoothClass();
            if (bc != null && bc.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO) {
                return true;
            }
            ParcelUuid[] uuids = d.getUuids();
            if (uuids != null) {
                for (ParcelUuid u : uuids) {
                    String s = u.toString().toLowerCase(Locale.US);
                    for (String a : AUDIO_UUIDS) {
                        if (s.startsWith(a)) {
                            return true;
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            // unknown device
        }
        return false;
    }

    private BluetoothDevice bonded(String addr) {
        BluetoothAdapter a = adapter();
        if (a == null) {
            return null;
        }
        try {
            for (BluetoothDevice d : a.getBondedDevices()) {
                if (up(d.getAddress()).equals(up(addr))) {
                    return d;
                }
            }
        } catch (RuntimeException e) {
            // no permission
        }
        return null;
    }

    boolean isConnected(String addr) {
        if (pretend != null) {
            for (TabletHeadsetRules.Dev d : pretend.bonded) {
                if (up(d.addr).equals(up(addr))) {
                    return d.connected;
                }
            }
            return false;
        }
        return connectedAddrs().contains(up(addr));
    }

    /**
     * Everything the card needs. {@code phone} is what the phone said about its headset (may be null: nothing asked yet). Never blocks.
     */
    TabletHeadsetRules.Facts facts(Calls.Headsets phone) {
        Calls.Device cur = phone == null ? null : phone.current();
        return facts(cur == null ? "" : cur.addr, cur == null ? "" : cur.name, cur != null && cur.connected);
    }

    /** The same from what is known of the phone's headset: its address and name ("" = not known) and whether it is connected to the phone. */
    TabletHeadsetRules.Facts facts(String phoneAddr, String phoneName, boolean phoneConnected) {
        if (pretend != null) {
            return pretend;
        }
        TabletHeadsetRules.Facts f = new TabletHeadsetRules.Facts();
        f.hasBluetooth = hasBluetooth();
        f.bluetoothOn = bluetoothOn();
        f.userAddr = Cfg.tabletHeadsetAddr(app);
        f.canSwitch = Priv.ready();
        f.phoneAddr = phoneAddr == null ? "" : phoneAddr;
        f.phoneName = phoneName == null ? "" : phoneName;
        f.phoneConnected = phoneConnected;
        if (!f.bluetoothOn) {
            return f;
        }
        Set<String> on = connectedAddrs();
        try {
            BluetoothAdapter a = adapter();
            if (a != null) {
                for (BluetoothDevice d : a.getBondedDevices()) {
                    if (!isAudio(d)) {
                        continue;
                    }
                    TabletHeadsetRules.Dev x = new TabletHeadsetRules.Dev();
                    x.addr = up(d.getAddress());
                    String n = null;
                    try {
                        n = d.getName();
                    } catch (RuntimeException e) {
                        // no permission
                    }
                    x.name = n == null || n.isEmpty() ? x.addr : n;
                    x.connected = on.contains(x.addr);
                    f.bonded.add(x);
                }
            }
        } catch (RuntimeException e) {
            // the list stays as far as it got
        }
        return f;
    }

    // ------------------------------------------------------------------ the battery, as far as this tablet's Bluetooth knows it

    /** The level of a headset connected to THIS tablet ({@link Calls#BATTERY_UNKNOWN} when nothing says). Never blocks. */
    int battery(String addr) {
        String a = up(addr);
        BluetoothDevice d = a.isEmpty() ? null : bonded(a);
        if (d != null && !reflectBlocked && isConnected(a)) {
            try {
                Method m = levelMethod;
                if (m == null) {
                    m = BluetoothDevice.class.getMethod("getBatteryLevel");
                    levelMethod = m;
                }
                Object r = m.invoke(d);
                if (r instanceof Integer && BtBattery.clean((Integer) r) >= 0) {
                    batteries.put(a, (Integer) r);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                reflectBlocked = true;
            }
        }
        Integer v = batteries.get(a);
        return v == null || !isConnected(a) ? Calls.BATTERY_UNKNOWN : v;
    }

    // ------------------------------------------------------------------ doing things

    /** The user chose which paired device is the headset to use with this tablet. */
    void choose(String addr) {
        Cfg.setTabletHeadsetAddr(app, up(addr));
        tell();
    }

    /** Switches Bluetooth on (a button press may; a status look never does). Reads back; false when it did not come on. */
    void switchOn(final Context act, final BooleanSupplier mayAsk, final Done done) {
        new Thread(() -> {
            boolean ok = bluetoothOn();
            if (!ok && !refusedForTest) {
                try {
                    BluetoothAdapter a = adapter();
                    @SuppressWarnings("deprecation")
                    boolean asked = a != null && a.enable();
                    ok = asked && waitUntil(true, null, 8000);
                } catch (RuntimeException e) {
                    ok = false;
                }
                if (!ok && Priv.ready()) {
                    try {
                        Priv.run("svc bluetooth enable", 15);
                        ok = waitUntil(true, null, 8000);
                    } catch (IOException | RuntimeException e) {
                        ok = false;
                    }
                }
            }
            final boolean did = ok;
            ui.post(() -> {
                if (did) {
                    done.done(TabletHeadsetRules.Way.DONE);
                } else if (mayAsk.getAsBoolean() && openList(act)) {
                    done.done(TabletHeadsetRules.Way.ASKED);
                } else {
                    done.done(TabletHeadsetRules.Way.FAILED);
                }
                tell();
            });
        }, "linkmeter-tablet-bt").start();
    }

    /** Waits for the tablet's list to show the wanted state: Bluetooth on (addr null) or the device connected. */
    private boolean waitUntil(boolean want, String addr, long ms) {
        long end = SystemClock.elapsedRealtime() + ms;
        while (true) {
            boolean now = addr == null ? bluetoothOn() : isConnected(addr);
            if (now == want) {
                return true;
            }
            if (SystemClock.elapsedRealtime() >= end) {
                return false;
            }
            try {
                Thread.sleep(LOOK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** Connects the headset to this tablet (or disconnects it): every way in order, each read back. Done is called on the UI thread. */
    void set(final Context act, final String addr, final boolean connect, final BooleanSupplier mayAsk, final Done done) {
        new Thread(() -> {
            boolean did = false;
            if (pretend != null) {
                did = !refusedForTest && isConnected(addr) == connect;
            } else {
                did = isConnected(addr) == connect || viaShizuku(addr, connect) || viaProfiles(addr, connect);
            }
            final boolean ok = did;
            ui.post(() -> {
                if (ok) {
                    done.done(TabletHeadsetRules.Way.DONE);
                } else if (mayAsk.getAsBoolean() && openList(act)) {
                    done.done(TabletHeadsetRules.Way.ASKED);
                } else {
                    done.done(TabletHeadsetRules.Way.FAILED);
                }
                tell();
            });
        }, "linkmeter-tablet-bt").start();
    }

    void connect(Context act, String addr, BooleanSupplier mayAsk, Done done) {
        set(act, addr, true, mayAsk, done);
    }

    void disconnect(Context act, String addr, BooleanSupplier mayAsk, Done done) {
        set(act, addr, false, mayAsk, done);
    }

    private boolean viaShizuku(String addr, boolean connect) {
        if (refusedForTest || !Priv.ready()) {
            return false;
        }
        try {
            Cells.shellMain(app, (connect ? "bt-connect " : "bt-disconnect ") + up(addr), 30);
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return waitUntil(connect, addr, LOOKS * LOOK_MS);
    }

    /** The hidden connect and disconnect of both profiles from this app. Android usually refuses (it wants a privilege this app has not): then false. */
    private boolean viaProfiles(String addr, boolean connect) {
        if (refusedForTest) {
            return false;
        }
        BluetoothDevice d = bonded(addr);
        if (d == null) {
            return false;
        }
        boolean tried = false;
        for (BluetoothProfile p : new BluetoothProfile[] {hfp, a2dp}) {
            if (p == null) {
                continue;
            }
            try {
                Method m = p.getClass().getMethod(connect ? "connect" : "disconnect", BluetoothDevice.class);
                m.invoke(p, d);
                tried = true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                // refused or hidden: the next way
            }
        }
        return tried && waitUntil(connect, addr, LOOKS * LOOK_MS);
    }

    /**
     * Android's own Bluetooth list (to tap the headset, or to pair it). Only from the UI thread, and while the app is in front or the floating
     * phone is up (Android 10 lets an app that may draw over other apps open a screen from the background).
     */
    static boolean openList(Context act) {
        try {
            act.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
