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
import android.os.Build;
import android.os.ParcelUuid;
import android.os.SystemClock;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Phone only: the Bluetooth headset, as far as the Calls feature needs it (docs/PHONE_CALLS.md). The headset is connected to THIS
 * phone, which is where a call's sound is. Reading what is paired and connected, finding and pairing a new one, and connecting
 * through the shell user (ShellMain "bt-connect"), because an ordinary app may not connect a device. Every claim is read back: a
 * connect is only believed once Android lists the headset as connected.
 */
final class HeadsetLink {
    private static final long SCAN_MS = 12_000;

    private final Context app;
    private final Runnable changed;
    private volatile BluetoothHeadset hfp;
    private volatile BluetoothA2dp a2dp;
    private volatile boolean registered;
    private volatile int drops;
    private volatile long scanEnd;
    private final List<Calls.Device> found = new CopyOnWriteArrayList<>();

    HeadsetLink(Context c, Runnable changed) {
        this.app = c.getApplicationContext();
        this.changed = changed;
    }

    private BluetoothAdapter adapter() {
        BluetoothManager bm = app.getSystemService(BluetoothManager.class);
        return bm == null ? null : bm.getAdapter();
    }

    private void fire() {
        try {
            changed.run();
        } catch (RuntimeException ignored) {
            // the listener is the API's gate: it never throws
        }
    }

    // ------------------------------------------------------------------ what Android says

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (a == null) {
                return;
            }
            BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a) && d != null && d.getAddress() != null
                    && d.getAddress().equalsIgnoreCase(Cfg.headsetAddr(app))) {
                drops++;
            } else if (BluetoothDevice.ACTION_FOUND.equals(a) && d != null) {
                addFound(d);
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(a)) {
                scanEnd = 0;
            }
            fire();
        }
    };

    private final BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.HEADSET) {
                hfp = (BluetoothHeadset) proxy;
            } else if (profile == BluetoothProfile.A2DP) {
                a2dp = (BluetoothA2dp) proxy;
            }
            fire();
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

    /** Starts listening, once Bluetooth may be used. Safe to call again and again. */
    synchronized void ensure() {
        if (registered || !CallAccess.bluetooth(app)) {
            return;
        }
        BluetoothAdapter a = adapter();
        if (a == null) {
            return;
        }
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED); // the broadcasts come from the system
            } else {
                app.registerReceiver(receiver, f);
            }
            a.getProfileProxy(app, listener, BluetoothProfile.HEADSET);
            a.getProfileProxy(app, listener, BluetoothProfile.A2DP);
            registered = true;
        } catch (RuntimeException e) {
            // no permission after all: the next read tries again
        }
    }

    private static String up(String addr) {
        return addr == null ? "" : addr.toUpperCase(Locale.US);
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

    private static final String[] AUDIO_UUIDS = {"0000111e", "00001108", "0000110b"}; // hands-free, headset, A2DP sink

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

    private static String kind(BluetoothDevice d) {
        try {
            BluetoothClass bc = d.getBluetoothClass();
            if (bc != null) {
                switch (bc.getDeviceClass()) {
                    case BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO:
                        return "car";
                    case BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER:
                    case BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO:
                    case BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO:
                        return "speaker";
                    case BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET:
                    case BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE:
                    case BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES:
                        return "headset";
                    default:
                        break;
                }
            }
        } catch (RuntimeException e) {
            // unknown device
        }
        return "headset";
    }

    private static String nameOf(BluetoothDevice d) {
        String n = null;
        try {
            n = d.getName();
        } catch (RuntimeException e) {
            // no permission
        }
        return n == null || n.isEmpty() ? d.getAddress() : n;
    }

    // ------------------------------------------------------------------ reading

    /** The paired audio devices and which are connected, with what the Calls tab's headset card needs to decide. */
    Calls.Headsets read() {
        Calls.Headsets h = new Calls.Headsets();
        h.allowed = CallAccess.bluetooth(app);
        BluetoothAdapter a = adapter();
        h.bluetoothOn = a != null && a.isEnabled();
        h.canConnect = Priv.ready();
        h.chosen = up(Cfg.headsetAddr(app));
        h.drops = drops;
        if (!h.allowed || a == null) {
            h.note = h.allowed ? "This phone has no Bluetooth." : CallAccess.note(app);
            return h;
        }
        ensure();
        if (h.bluetoothOn) {
            Set<String> on = connectedAddrs();
            try {
                for (BluetoothDevice d : a.getBondedDevices()) {
                    if (!isAudio(d)) {
                        continue;
                    }
                    Calls.Device x = new Calls.Device();
                    x.addr = up(d.getAddress());
                    x.name = nameOf(d);
                    x.kind = kind(d);
                    x.connected = on.contains(x.addr);
                    h.devices.add(x);
                }
            } catch (RuntimeException e) {
                h.note = "The phone would not list its paired devices.";
            }
        }
        if (h.find(h.chosen) == null && !h.chosen.isEmpty()) {
            // Bluetooth is off, or the headset is no longer paired: still say which one the user chose
            if (!h.bluetoothOn) {
                Calls.Device x = new Calls.Device();
                x.addr = h.chosen;
                x.name = Cfg.headsetName(app).isEmpty() ? h.chosen : Cfg.headsetName(app);
                h.devices.add(x);
            } else {
                h.chosen = "";
            }
        }
        return h;
    }

    /** The name of the connected headset (the chosen one when it is), or "". */
    String connectedName() {
        if (!CallAccess.bluetooth(app)) {
            return "";
        }
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) {
            return "";
        }
        ensure();
        Set<String> on = connectedAddrs();
        if (on.isEmpty()) {
            return "";
        }
        String chosen = up(Cfg.headsetAddr(app));
        String first = "";
        try {
            for (BluetoothDevice d : a.getBondedDevices()) {
                String addr = up(d.getAddress());
                if (on.contains(addr) && isAudio(d)) {
                    if (addr.equals(chosen)) {
                        return nameOf(d);
                    }
                    if (first.isEmpty()) {
                        first = nameOf(d);
                    }
                }
            }
        } catch (RuntimeException e) {
            return "";
        }
        return first;
    }

    boolean isConnected(String addr) {
        return connectedAddrs().contains(up(addr));
    }

    // ------------------------------------------------------------------ choosing and connecting

    private BluetoothDevice bonded(String addr) {
        BluetoothAdapter a = adapter();
        if (a == null || !CallAccess.bluetooth(app)) {
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

    Calls.Result choose(String addr) {
        BluetoothDevice d = bonded(addr);
        if (d == null) {
            return Calls.Result.fail("That device is not paired with the phone.");
        }
        Cfg.setHeadset(app, up(addr), nameOf(d));
        fire();
        return Calls.Result.ok(nameOf(d) + " is your headset.");
    }

    /** Connects the headset ({@code addr} "" = the one the user chose, or the only one paired). Waits up to about 25 seconds. */
    Calls.Result connect(String addr) {
        if (!CallAccess.bluetooth(app)) {
            return Calls.Result.fail(CallAccess.note(app));
        }
        BluetoothAdapter a = adapter();
        if (a == null) {
            return Calls.Result.fail("This phone has no Bluetooth.");
        }
        String want = addr == null || addr.isEmpty() ? up(Cfg.headsetAddr(app)) : up(addr);
        if (want.isEmpty()) {
            Calls.Headsets h = read();
            if (h.devices.size() == 1) {
                want = h.devices.get(0).addr;
            }
        }
        if (want.isEmpty()) {
            return Calls.Result.fail("Choose which headset to connect first.");
        }
        if (!a.isEnabled()) {
            if (!Priv.ready()) {
                return Calls.Result.fail("Bluetooth is off on the phone. Switch it on, then try again.");
            }
            try {
                Priv.run("svc bluetooth enable", 15);
            } catch (IOException e) {
                return Calls.Result.fail("Bluetooth is off and the phone would not switch it on: " + e.getMessage());
            }
            long end = SystemClock.elapsedRealtime() + 8000;
            while (!a.isEnabled() && SystemClock.elapsedRealtime() < end) {
                SystemClock.sleep(300);
            }
            if (!a.isEnabled()) {
                return Calls.Result.fail("Bluetooth did not come on within a few seconds.");
            }
            SystemClock.sleep(1200); // the proxies and the bonded list take a moment after the radio comes on
        }
        ensure();
        BluetoothDevice d = bonded(want);
        if (d == null) {
            return Calls.Result.fail("That headset is not paired with the phone. Pair it first.");
        }
        String name = nameOf(d);
        if (Cfg.headsetAddr(app).isEmpty() || !Cfg.headsetAddr(app).equalsIgnoreCase(want)) {
            Cfg.setHeadset(app, want, name);
        }
        if (isConnected(want)) {
            fire();
            return Calls.Result.ok(name + " is already connected.");
        }
        if (!Priv.ready()) {
            return Calls.Result.fail("The phone cannot connect it for you now (Shizuku is not ready). Turn " + name
                    + " on and hold it near the phone: most headsets connect by themselves.");
        }
        String said;
        try {
            Priv.Result r = Cells.shellMain(app, "bt-connect " + want, 30);
            said = r.out.trim();
        } catch (IOException e) {
            return Calls.Result.fail("Could not ask the phone to connect: " + e.getMessage());
        }
        long end = SystemClock.elapsedRealtime() + 12_000;
        while (SystemClock.elapsedRealtime() < end) {
            if (isConnected(want)) {
                fire();
                return Calls.Result.ok(name + " is connected.");
            }
            SystemClock.sleep(500);
        }
        fire();
        String why = said.startsWith("ERR") ? said.substring(3).trim() : "";
        return Calls.Result.fail(name + " did not connect" + (why.isEmpty() ? "" : " (" + shorten(why) + ")")
                + ". Turn it off and on, or use the phone's Bluetooth settings.");
    }

    private static String shorten(String s) {
        String one = s.replace('\n', ' ').trim();
        return one.length() > 140 ? one.substring(0, 140) : one;
    }

    Calls.Result disconnect() {
        String want = up(Cfg.headsetAddr(app));
        BluetoothDevice d = want.isEmpty() ? null : bonded(want);
        if (d == null) {
            return Calls.Result.fail("No headset is chosen.");
        }
        if (!isConnected(want)) {
            return Calls.Result.ok(nameOf(d) + " is not connected.");
        }
        if (!Priv.ready()) {
            return Calls.Result.fail("The phone cannot disconnect it for you now (Shizuku is not ready). Turn the headset off instead.");
        }
        try {
            Cells.shellMain(app, "bt-disconnect " + want, 30);
        } catch (IOException e) {
            return Calls.Result.fail("Could not ask the phone to disconnect: " + e.getMessage());
        }
        long end = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < end) {
            if (!isConnected(want)) {
                fire();
                return Calls.Result.ok(nameOf(d) + " is disconnected.");
            }
            SystemClock.sleep(400);
        }
        return Calls.Result.fail(nameOf(d) + " is still connected. Turn it off instead.");
    }

    // ------------------------------------------------------------------ a new headset

    private void addFound(BluetoothDevice d) {
        if (!isAudio(d) || d.getBondState() == BluetoothDevice.BOND_BONDED) {
            return;
        }
        String addr = up(d.getAddress());
        for (Calls.Device x : found) {
            if (x.addr.equals(addr)) {
                return;
            }
        }
        Calls.Device x = new Calls.Device();
        x.addr = addr;
        x.name = nameOf(d);
        x.kind = kind(d);
        found.add(x);
    }

    /** Looks for headsets that are in pairing mode for about 12 seconds (the answer of {@link #found} fills while it looks). */
    Calls.Result scan() {
        if (!CallAccess.bluetooth(app)) {
            return Calls.Result.fail(CallAccess.note(app));
        }
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) {
            return Calls.Result.fail("Bluetooth is off on the phone.");
        }
        ensure();
        found.clear();
        try {
            if (a.isDiscovering()) {
                a.cancelDiscovery();
            }
            if (!a.startDiscovery()) {
                return Calls.Result.fail("The phone would not start looking for devices.");
            }
        } catch (RuntimeException e) {
            return Calls.Result.fail("The phone would not start looking for devices: " + e.getMessage());
        }
        scanEnd = SystemClock.elapsedRealtime() + SCAN_MS;
        fire();
        return Calls.Result.ok("Looking for headsets.");
    }

    Calls.Found found() {
        Calls.Found f = new Calls.Found();
        BluetoothAdapter a = adapter();
        boolean on = scanEnd != 0 && SystemClock.elapsedRealtime() < scanEnd;
        if (!on && scanEnd != 0) {
            scanEnd = 0;
            try {
                if (a != null && a.isDiscovering() && CallAccess.bluetooth(app)) {
                    a.cancelDiscovery();
                }
            } catch (RuntimeException e) {
                // nothing to cancel
            }
        }
        f.scanning = on;
        f.devices.addAll(found);
        f.note = on ? "Put your headset in pairing mode." : found.isEmpty() ? "No headset found. Put it in pairing mode and look again." : "";
        return f;
    }

    /** Pairs with a device the search found. Waits up to about 25 seconds for Android to say it is paired. */
    Calls.Result pair(String addr) {
        BluetoothAdapter a = adapter();
        if (a == null || !CallAccess.bluetooth(app)) {
            return Calls.Result.fail(CallAccess.note(app));
        }
        BluetoothDevice d;
        try {
            d = a.getRemoteDevice(up(addr));
            if (a.isDiscovering()) {
                a.cancelDiscovery();
            }
            scanEnd = 0;
            if (d.getBondState() != BluetoothDevice.BOND_BONDED && !d.createBond()) {
                return Calls.Result.fail("The phone would not start pairing with " + nameOf(d) + ".");
            }
        } catch (RuntimeException e) {
            return Calls.Result.fail("The phone would not start pairing: " + e.getMessage());
        }
        long end = SystemClock.elapsedRealtime() + 25_000;
        while (SystemClock.elapsedRealtime() < end) {
            if (d.getBondState() == BluetoothDevice.BOND_BONDED) {
                Cfg.setHeadset(app, up(addr), nameOf(d));
                fire();
                return Calls.Result.ok(nameOf(d) + " is paired. It should connect by itself in a moment; if not, press Connect.");
            }
            if (d.getBondState() == BluetoothDevice.BOND_NONE && SystemClock.elapsedRealtime() > end - 22_000) {
                break; // pairing was refused
            }
            SystemClock.sleep(500);
        }
        return Calls.Result.fail(nameOf(d) + " did not pair. Put it in pairing mode again (hold its power button until it flashes) and try once more.");
    }

    /** Opens the phone's Bluetooth settings (needs Shizuku). */
    Calls.Result settings() {
        if (!Priv.ready()) {
            return Calls.Result.fail("The phone cannot open its settings for you now (Shizuku is not ready). Open Settings > Connections > Bluetooth on the phone.");
        }
        try {
            String said = Menus.bluetoothSettings();
            return said.isEmpty() ? Calls.Result.fail("This phone's Bluetooth settings screen could not be opened. Open Settings > Connections > Bluetooth.")
                    : Calls.Result.ok(said + " The Screen tab shows it.");
        } catch (IOException e) {
            return Calls.Result.fail(e.getMessage());
        }
    }
}
