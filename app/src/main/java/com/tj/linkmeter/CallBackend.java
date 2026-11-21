package com.tj.linkmeter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.CallLog;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Phone only: what {@link CallApi} needs from Android (docs/PHONE_CALLS.md): the telecom service to place, answer and end calls,
 * the audio manager for the microphone and the sound's route, the call log, the contacts (the same reader the Texts tab uses) and
 * the Bluetooth headset. Nothing here is believed without reading it back; the sentences say what the phone did, not what was asked.
 */
final class CallBackend implements CallApi.Backend {
    private final Context ctx;
    private final SmsBackend sms;
    private final CallTracker tracker = new CallTracker();
    private final HeadsetLink headset;
    private final HeadsetVolume volumes = new HeadsetVolume();
    private volatile Runnable listener;
    private volatile boolean started;

    CallBackend(Context c, SmsBackend contacts) {
        ctx = c.getApplicationContext();
        sms = contacts;
        headset = new HeadsetLink(ctx, this::fire);
    }

    private void fire() {
        Runnable r = listener;
        if (r != null) {
            r.run();
        }
    }

    private final BroadcastReceiver phoneState = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String state = i.getStringExtra(TelephonyManager.EXTRA_STATE);
            String number = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
            if (state != null && tracker.onState(state, number, System.currentTimeMillis())) {
                fire();
            }
        }
    };

    /**
     * Android's own announcement that a volume moved (the phone's keys, the headset's buttons, this app): the tablet's picture is told at
     * once, so the volume shown on the tablet is the volume the headset really has. A hidden but long-standing system broadcast.
     */
    private final BroadcastReceiver volumeChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int stream = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
            if (stream == AudioManager.STREAM_MUSIC || stream == AudioManager.STREAM_VOICE_CALL) {
                fire();
            }
        }
    };

    /** Starts listening to the phone's state. Safe to call again and again. */
    synchronized void start() {
        if (started) {
            headset.ensure();
            return;
        }
        started = true;
        IntentFilter f = new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(phoneState, f, Context.RECEIVER_EXPORTED); // a system broadcast
        } else {
            ctx.registerReceiver(phoneState, f);
        }
        try {
            IntentFilter vf = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(volumeChanged, vf, Context.RECEIVER_EXPORTED); // a system broadcast
            } else {
                ctx.registerReceiver(volumeChanged, vf);
            }
        } catch (RuntimeException ignored) {
            // the tablet then sees a changed volume at its next look
        }
        try {
            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
            if (tm != null && CallAccess.has(ctx, CallAccess.STATE) && tm.getCallState() == TelephonyManager.CALL_STATE_OFFHOOK) {
                tracker.onState(CallTracker.OFFHOOK, "", System.currentTimeMillis()); // the app started in the middle of a call
            }
        } catch (RuntimeException ignored) {
            // the state is learned from the next broadcast
        }
        headset.ensure();
    }

    // ------------------------------------------------------------------ the switch and the permissions

    @Override
    public boolean on() {
        return Cfg.callsOn(ctx);
    }

    @Override
    public Calls.State state() {
        Calls.State s = new Calls.State();
        s.call = CallAccess.has(ctx, CallAccess.CALL);
        s.answer = CallAccess.has(ctx, CallAccess.ANSWER);
        s.phone = CallAccess.has(ctx, CallAccess.STATE);
        s.log = CallAccess.has(ctx, CallAccess.LOG);
        s.contacts = CallAccess.has(ctx, android.Manifest.permission.READ_CONTACTS);
        s.bluetooth = CallAccess.bluetooth(ctx);
        s.shell = Priv.ready();
        s.note = CallAccess.note(ctx);
        return s;
    }

    @Override
    public void grant() throws IOException {
        try {
            CallAccess.grant(ctx);
            for (String p : new String[] {android.Manifest.permission.READ_CONTACTS, SmsAccess.WRITE_CONTACTS}) { // the contacts are shared with Texts
                if (!SmsAccess.has(ctx, p) && (p.equals(SmsAccess.WRITE_CONTACTS) ? SmsAccess.has(ctx, android.Manifest.permission.READ_CONTACTS) : true)) {
                    Priv.run("pm grant " + ctx.getPackageName() + " " + p, 15);
                }
            }
        } finally {
            start();
            fire();
        }
    }

    @Override
    public void setChangeListener(Runnable r) {
        listener = r;
    }

    // ------------------------------------------------------------------ the live picture

    @Override
    public Calls.Now now() {
        start();
        Calls.Now n = new Calls.Now();
        boolean names = CallAccess.has(ctx, android.Manifest.permission.READ_CONTACTS);
        for (Calls.Call c : tracker.calls()) {
            c.name = names && !c.number.isEmpty() ? sms.nameFor(c.number) : "";
            n.calls.add(c);
        }
        AudioManager am = ctx.getSystemService(AudioManager.class);
        headset.fill(n); // the name of the connected headset and its battery level
        if (am != null) {
            n.muted = am.isMicrophoneMute();
            n.route = n.calls.isEmpty() ? Calls.ROUTE_UNKNOWN : routeNow(am, !n.headset.isEmpty());
        }
        n.volume = volumes.read(am, VolumeRules.callOnHeadset(!n.calls.isEmpty(), !n.headset.isEmpty(), n.route));
        n.missed = missedCount();
        return n;
    }

    private static String typeRoute(AudioDeviceInfo d) {
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return Calls.ROUTE_SPEAKER;
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                return Calls.ROUTE_PHONE;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_HEARING_AID:
                return Calls.ROUTE_HEADSET;
            default:
                return Calls.ROUTE_UNKNOWN;
        }
    }

    /** Where the sound of the call goes, as far as Android says; unknown when it does not say. */
    private String routeNow(AudioManager am, boolean headsetConnected) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo d = am.getCommunicationDevice();
                if (d != null) {
                    String r = typeRoute(d);
                    if (!r.equals(Calls.ROUTE_UNKNOWN)) {
                        return r;
                    }
                }
            }
            if (am.isSpeakerphoneOn()) {
                return Calls.ROUTE_SPEAKER;
            }
            if (am.isBluetoothScoOn()) {
                return Calls.ROUTE_HEADSET;
            }
        } catch (RuntimeException e) {
            return Calls.ROUTE_UNKNOWN;
        }
        // nothing says where it goes: with no headset connected it can only be the earpiece
        return headsetConnected ? Calls.ROUTE_UNKNOWN : Calls.ROUTE_PHONE;
    }

    // ------------------------------------------------------------------ doing things in a call

    @Override
    public Calls.Result dial(String number) {
        // the guard again, here, where the call is placed: nothing that fails it is ever dialed (tools/test/test_dial_paths.py checks this)
        CallRules.Dial d = CallRules.check(number);
        if (!d.ok()) {
            return Calls.Result.fail(d.why());
        }
        if (!CallAccess.has(ctx, CallAccess.CALL)) {
            return Calls.Result.fail("The phone has not allowed LinkMeter to place calls yet. " + CallAccess.note(ctx));
        }
        if (!tracker.calls().isEmpty()) {
            return Calls.Result.fail("A call is already on or ringing. End it first.");
        }
        try {
            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
            if (tm != null && tm.isEmergencyNumber(CallRules.digitsOf(d.number))) {
                return Calls.Result.fail(new CallRules.Dial(CallRules.EMERGENCY, "").why());
            }
        } catch (RuntimeException ignored) {
            // the phone would not say: the list in CallRules has already been through
        }
        TelecomManager tc = ctx.getSystemService(TelecomManager.class);
        if (tc == null) {
            return Calls.Result.fail("This phone has no telecom service.");
        }
        try {
            tracker.noteDial(d.number, System.currentTimeMillis());
            tc.placeCall(Uri.fromParts("tel", d.number, null), new Bundle());
        } catch (SecurityException e) {
            return Calls.Result.fail("Android would not let LinkMeter place the call: " + e.getMessage());
        } catch (RuntimeException e) {
            return Calls.Result.fail("The phone could not place the call: " + e.getMessage());
        }
        return Calls.Result.ok("Calling " + CallRules.pretty(d.number));
    }

    @Override
    public Calls.Result answer() {
        if (!CallAccess.has(ctx, CallAccess.ANSWER)) {
            return Calls.Result.fail("The phone has not allowed LinkMeter to answer calls yet. " + CallAccess.note(ctx));
        }
        boolean ringing = false;
        for (Calls.Call c : tracker.calls()) {
            ringing |= Calls.RINGING.equals(c.state);
        }
        if (!ringing) {
            return Calls.Result.fail("Nothing is ringing.");
        }
        TelecomManager tc = ctx.getSystemService(TelecomManager.class);
        try {
            tc.acceptRingingCall();
        } catch (RuntimeException e) {
            return Calls.Result.fail("The phone would not answer: " + e.getMessage());
        }
        return Calls.Result.ok("Answered.");
    }

    @Override
    public Calls.Result hangup() {
        if (!CallAccess.has(ctx, CallAccess.ANSWER)) {
            return Calls.Result.fail("The phone has not allowed LinkMeter to end calls yet. " + CallAccess.note(ctx));
        }
        if (tracker.calls().isEmpty()) {
            return Calls.Result.fail("No call is on.");
        }
        TelecomManager tc = ctx.getSystemService(TelecomManager.class);
        try {
            return tc.endCall() ? Calls.Result.ok("Call ended.") : Calls.Result.fail("The phone did not end the call. Use the phone itself.");
        } catch (RuntimeException e) {
            return Calls.Result.fail("The phone would not end the call: " + e.getMessage());
        }
    }

    @Override
    public Calls.Result mute(boolean on) {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if (am == null) {
            return Calls.Result.fail("This phone has no audio service.");
        }
        try {
            am.setMicrophoneMute(on);
        } catch (RuntimeException e) {
            return Calls.Result.fail("The phone would not change the microphone: " + e.getMessage());
        }
        fire();
        return am.isMicrophoneMute() == on ? Calls.Result.ok(on ? "Microphone off." : "Microphone on.")
                : Calls.Result.fail("The phone did not change the microphone. Use the phone's own Mute button.");
    }

    private static boolean typeIsHeadset(int t) {
        return t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_BLE_HEADSET || t == AudioDeviceInfo.TYPE_HEARING_AID;
    }

    private boolean setRoute(AudioManager am, String to) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    boolean match = to.equals(Calls.ROUTE_SPEAKER) ? d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                            : to.equals(Calls.ROUTE_PHONE) ? d.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE : typeIsHeadset(d.getType());
                    if (match) {
                        return am.setCommunicationDevice(d);
                    }
                }
                return false;
            }
            if (to.equals(Calls.ROUTE_SPEAKER)) {
                am.stopBluetoothSco();
                am.setSpeakerphoneOn(true);
            } else if (to.equals(Calls.ROUTE_PHONE)) {
                am.stopBluetoothSco();
                am.setSpeakerphoneOn(false);
            } else {
                am.setSpeakerphoneOn(false);
                am.startBluetoothSco();
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public Calls.Result route(String to) {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if (am == null) {
            return Calls.Result.fail("This phone has no audio service.");
        }
        if (tracker.calls().isEmpty()) {
            return Calls.Result.fail("No call is on.");
        }
        boolean hs = !headset.connectedName().isEmpty();
        if (to.equals(Calls.ROUTE_HEADSET) && !hs) {
            return Calls.Result.fail("No headset is connected to the phone.");
        }
        setRoute(am, to);
        SystemClock.sleep(700); // the audio system takes a moment to move the sound
        String now = routeNow(am, hs);
        fire();
        if (now.equals(to)) {
            return Calls.Result.ok(HeadsetRules.route(to, headset.connectedName()));
        }
        return Calls.Result.fail("The phone did not move the sound. Use the phone's own audio button (the Screen tab shows the phone).");
    }

    /** The headset's volume, one step at a time: only while the sound really goes to the headset (see {@link VolumeRules} and {@link HeadsetVolume}). */
    @Override
    public Calls.Result volume(String stream, int by) {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if (am == null) {
            return Calls.Result.fail("This phone has no audio service.");
        }
        boolean hs = !headset.connectedName().isEmpty();
        boolean callOn = !tracker.calls().isEmpty();
        boolean callOnHeadset = VolumeRules.callOnHeadset(callOn, hs, callOn ? routeNow(am, hs) : Calls.ROUTE_UNKNOWN);
        VolumeRules.Plan plan = VolumeRules.plan(stream, by, volumes.read(am, callOnHeadset), hs);
        if (!plan.ok) {
            return Calls.Result.fail(plan.why);
        }
        Calls.Result r = volumes.move(am, stream, plan, callOnHeadset);
        fire(); // the tablet's picture shows what the phone really has now
        return r;
    }

    // ------------------------------------------------------------------ the call log

    private static String typeName(int t) {
        switch (t) {
            case CallLog.Calls.OUTGOING_TYPE:
                return Calls.OUT;
            case CallLog.Calls.MISSED_TYPE:
                return Calls.MISSED;
            case CallLog.Calls.VOICEMAIL_TYPE:
                return Calls.VOICEMAIL;
            case CallLog.Calls.REJECTED_TYPE:
                return Calls.REJECTED;
            case CallLog.Calls.BLOCKED_TYPE:
                return Calls.BLOCKED;
            default:
                return Calls.IN;
        }
    }

    @Override
    public List<Calls.Entry> log(int limit, long before) {
        List<Calls.Entry> out = new ArrayList<>();
        if (!CallAccess.has(ctx, CallAccess.LOG)) {
            return out;
        }
        boolean names = CallAccess.has(ctx, android.Manifest.permission.READ_CONTACTS);
        long seen = Cfg.callsSeenAt(ctx);
        Bundle q = new Bundle();
        if (before > 0) {
            q.putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, CallLog.Calls.DATE + " < ?");
            q.putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[] {String.valueOf(before)});
        }
        q.putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, CallLog.Calls.DATE + " DESC");
        q.putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit);
        try (Cursor c = ctx.getContentResolver().query(CallLog.Calls.CONTENT_URI, new String[] {CallLog.Calls._ID, CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION}, q, null)) {
            while (c != null && c.moveToNext() && out.size() < limit) {
                Calls.Entry e = new Calls.Entry();
                e.id = String.valueOf(c.getLong(0));
                String number = c.getString(1);
                e.number = number == null || number.startsWith("-") ? "" : number;
                String cached = c.getString(2);
                e.name = cached != null && !cached.isEmpty() ? cached : names && !e.number.isEmpty() ? sms.nameFor(e.number) : "";
                e.type = typeName(c.getInt(3));
                e.time = c.getLong(4);
                e.seconds = c.getLong(5);
                e.isNew = Calls.MISSED.equals(e.type) && e.time > seen;
                out.add(e);
            }
        } catch (RuntimeException e) {
            // no permission after all, or the provider is busy: an empty list says so
        }
        return out;
    }

    /** How many missed calls are newer than the last time the user looked. */
    private int missedCount() {
        if (!CallAccess.has(ctx, CallAccess.LOG)) {
            return tracker.missed();
        }
        try (Cursor c = ctx.getContentResolver().query(CallLog.Calls.CONTENT_URI, new String[] {CallLog.Calls._ID},
                CallLog.Calls.TYPE + " = ? AND " + CallLog.Calls.DATE + " > ?",
                new String[] {String.valueOf(CallLog.Calls.MISSED_TYPE), String.valueOf(Cfg.callsSeenAt(ctx))}, null)) {
            return c == null ? 0 : c.getCount();
        } catch (RuntimeException e) {
            return tracker.missed();
        }
    }

    @Override
    public void logSeen() {
        Cfg.setCallsSeenAt(ctx, System.currentTimeMillis());
        tracker.resetMissed();
        fire();
    }

    // ------------------------------------------------------------------ the contacts (the same reader as Texts)

    @Override
    public List<Sms.Person> contacts(String query, int limit) {
        if (!CallAccess.has(ctx, android.Manifest.permission.READ_CONTACTS)) {
            return new ArrayList<>();
        }
        return sms.contacts(query, limit);
    }

    @Override
    public Sms.ContactResult saveContact(String name, String number) {
        return sms.saveContact(name, number);
    }

    // ------------------------------------------------------------------ the headset

    @Override
    public Calls.Headsets headsets() {
        start();
        return headset.read();
    }

    @Override
    public Calls.Result headsetChoose(String addr) {
        return headset.choose(addr);
    }

    @Override
    public Calls.Result headsetConnect(String addr) {
        return headset.connect(addr);
    }

    @Override
    public Calls.Result headsetDisconnect() {
        return headset.disconnect();
    }

    @Override
    public Calls.Result headsetScan() {
        return headset.scan();
    }

    @Override
    public Calls.Found headsetFound() {
        return headset.found();
    }

    @Override
    public Calls.Result headsetPair(String addr) {
        return headset.pair(addr);
    }

    @Override
    public Calls.Result bluetoothSettings() {
        return headset.settings();
    }
}
