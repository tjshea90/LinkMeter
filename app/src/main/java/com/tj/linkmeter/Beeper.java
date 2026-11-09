package com.tj.linkmeter;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * Tablet side: plays one short beep at a pitch. The samples come from {@link SignalHunt#tone} (tested); this only hands them to
 * the speaker. One small buffer (about 2.4 KB) per beep, released as soon as the next one starts or the hunt ends, so the Go
 * tablet's memory is not touched. Every call is safe on a tablet with no usable speaker: a failure is swallowed.
 */
final class Beeper {
    private static final int RATE = 8000;
    private AudioTrack track;

    void beep(int hz) {
        short[] buf = SignalHunt.tone(hz, RATE, 150);
        release();
        if (buf.length == 0) {
            return;
        }
        AudioTrack t = null;
        try {
            t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(buf.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            t.write(buf, 0, buf.length);
            t.play();
            track = t;
        } catch (RuntimeException e) {
            // no speaker, or the audio system is busy: the numbers are still on the screen. A track that was made but could not
            // play must still be released, or a beep every second would leak one native audio track after another.
            track = null;
            if (t != null) {
                try {
                    t.release();
                } catch (RuntimeException ignored) {
                    // already gone
                }
            }
        }
    }

    void release() {
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try {
                t.release();
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
    }
}
