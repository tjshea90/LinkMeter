package com.tj.linkmeter;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.SystemClock;
import java.util.List;

/**
 * Phone only: reads and moves the volume of the Bluetooth headset that is connected to the phone (docs/PHONE_CALLS.md, "Headset volume").
 * This class is the thin Android part; what may be moved and how the answer is believed is {@link VolumeRules}.
 *
 * <p>Android keeps one volume per stream and per output device. "Media" is {@code STREAM_MUSIC} (with a Bluetooth headset connected, Android
 * routes it to the headset and sends the new level to it, "absolute volume"). "Call" is {@code STREAM_VOICE_CALL}, which only means the
 * headset while a call's sound is on it. So the call volume is moved only then, and the media volume only while the media really goes
 * to a Bluetooth device: pressing a button can never change the loudness of the phone's own speaker or earpiece.
 */
final class HeadsetVolume {
    /** Android needs a moment before the new level can be read back (a Bluetooth headset is told asynchronously). */
    private static final long SETTLE_MS = 250;

    /** The phone's own question, never the tablet's: reads both volumes and where each one goes. */
    Calls.Volume read(AudioManager am, boolean callOnHeadset) {
        Calls.Volume v = new Calls.Volume();
        if (am == null) {
            return v;
        }
        level(am, AudioManager.STREAM_MUSIC, v.media);
        level(am, AudioManager.STREAM_VOICE_CALL, v.call);
        v.mediaOnHeadset = mediaToBluetooth(am);
        v.callOnHeadset = callOnHeadset;
        return v;
    }

    private static void level(AudioManager am, int stream, Calls.Level l) {
        try {
            l.min = Build.VERSION.SDK_INT >= 28 ? am.getStreamMinVolume(stream) : stream == AudioManager.STREAM_VOICE_CALL ? 1 : 0;
            l.max = am.getStreamMaxVolume(stream);
            l.now = am.getStreamVolume(stream);
        } catch (RuntimeException e) {
            l.now = -1; // not known: the screens say so
            l.min = 0;
            l.max = 0;
        }
    }

    private static boolean bluetoothType(int type) {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_BROADCAST
                || type == AudioDeviceInfo.TYPE_HEARING_AID;
    }

    /** Does what plays now (music, video) go to a Bluetooth audio device? Android's own answer for the media attributes when it gives one. */
    private static boolean mediaToBluetooth(AudioManager am) {
        try {
            List<AudioDeviceInfo> routed = null;
            if (Build.VERSION.SDK_INT >= 33) {
                routed = am.getAudioDevicesForAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build());
            }
            if (routed == null || routed.isEmpty()) {
                // an older Android, or no answer: a Bluetooth audio output that is connected is where media goes
                AudioDeviceInfo[] all = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                for (AudioDeviceInfo d : all) {
                    if (bluetoothType(d.getType())) {
                        return true;
                    }
                }
                return false;
            }
            for (AudioDeviceInfo d : routed) {
                if (bluetoothType(d.getType())) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int streamType(String stream) {
        return Calls.STREAM_CALL.equals(stream) ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC;
    }

    /**
     * Moves the volume to the plan's target, with no sound and no panel on the phone's screen, and says what the phone reads back. The caller
     * has already had {@link VolumeRules#plan} approve it.
     */
    Calls.Result move(AudioManager am, String stream, VolumeRules.Plan plan, boolean callOnHeadset) {
        try {
            am.setStreamVolume(streamType(stream), plan.target, 0);
        } catch (RuntimeException e) {
            return Calls.Result.fail("The phone would not change the volume: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        SystemClock.sleep(SETTLE_MS);
        return VolumeRules.verify(stream, plan, VolumeRules.level(read(am, callOnHeadset), stream));
    }
}
