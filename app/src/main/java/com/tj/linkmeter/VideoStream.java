package com.tj.linkmeter;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.view.Surface;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Tablet side: receives the phone's H.264 screen video and shows it with the tablet's hardware
 * decoder. Frames arrive as [length][frame]; each goes to the decoder as one piece and is shown the
 * moment it is decoded, with nothing queued up, which is what keeps the delay small.
 * A negative length is a message from the phone (why it could not start the video) instead of a frame.
 */
final class VideoStream {
    interface Events {
        void onInfo(int phoneW, int phoneH, int videoW, int videoH);

        void onFrame();

        /** About once a second: pictures shown per second and kilobits received per second. Any thread. */
        void onStats(int fps, int kbps);

        void onEnd(String why, boolean screenOff);
    }

    private final String host;
    private final String token;
    private final String query;
    private final Surface surface;
    private final Events ev;
    private volatile boolean run;
    private volatile Client.Stream st;
    private Thread thread;
    private final AtomicInteger frames = new AtomicInteger();
    private final AtomicLong bytes = new AtomicLong();

    VideoStream(String host, String token, String query, Surface surface, Events ev) {
        this.host = host;
        this.token = token;
        this.query = query;
        this.surface = surface;
        this.ev = ev;
    }

    void start() {
        run = true;
        thread = new Thread(this::loop, "linkmeter-video");
        thread.start();
    }

    void stop() {
        run = false;
        Client.Stream s = st;
        if (s != null) {
            s.close();
        }
    }

    private static int num(String s) {
        return Integer.parseInt(s.trim());
    }

    private static String errText(String s) {
        try {
            return new JSONObject(s).optString("err", s);
        } catch (JSONException e) {
            return s;
        }
    }

    /** True if the chunk holds picture data (a slice NAL unit, type 1 or 5), not only parameter sets. */
    private static boolean hasPicture(byte[] d, int len) {
        for (int i = 0; i + 3 < len; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
                int type = d[i + 3] & 0x1F;
                if (type == 1 || type == 5) {
                    return true;
                }
                i += 2;
            }
        }
        return false;
    }

    private void loop() {
        String why = "The phone ended the video.";
        boolean off = false;
        MediaCodec codec = null;
        Thread drain = null;
        try {
            body:
            {
                st = Client.get(host, "/screen/video?" + query, token, 3000, 15000);
                Client.Stream s = st;
                if (s.code != 200 || s.length >= 0) {
                    // not a video: the phone's screen is off, or it refused
                    byte[] b = new byte[(int) Math.max(0, Math.min(s.length, 2048))];
                    new DataInputStream(s.in).readFully(b);
                    String text = new String(b, StandardCharsets.UTF_8);
                    if (s.code == 200 && text.contains("\"off\"")) {
                        off = true;
                        why = "The phone's screen is off.";
                    } else {
                        why = errText(text);
                    }
                    break body;
                }
                String[] vs = s.video.split("x");
                String[] ps = s.size.split("x");
                final int vw = num(vs[0]);
                final int vh = num(vs[1]);
                ev.onInfo(num(ps[0]), num(ps[1]), vw, vh);
                s.sock.setSoTimeout(0);

                MediaFormat f = MediaFormat.createVideoFormat("video/avc", vw, vh);
                f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 20);
                final MediaCodec dec = MediaCodec.createDecoderByType("video/avc");
                codec = dec;
                dec.configure(f, surface, null, 0);
                dec.start();
                frames.set(0);
                bytes.set(0);
                drain = new Thread(() -> drain(dec), "linkmeter-video-out");
                drain.start();

                DataInputStream din = new DataInputStream(s.in);
                byte[] data = new byte[256 * 1024];
                byte[] config = null; // SPS/PPS that came without a picture: goes in front of the next one
                long pts = 0;
                while (run) {
                    int len = din.readInt();
                    if (len < 0) {
                        // the phone's own message instead of video
                        byte[] m = new byte[Math.min(-len, 2048)];
                        din.readFully(m);
                        why = new String(m, StandardCharsets.UTF_8);
                        break body;
                    }
                    if (len == 0) {
                        continue;
                    }
                    if (len > (4 << 20)) {
                        throw new IOException("bad video frame");
                    }
                    if (data.length < len) {
                        data = new byte[len];
                    }
                    din.readFully(data, 0, len);
                    bytes.addAndGet(len + 4);
                    if (!hasPicture(data, len)) {
                        // Parameter sets on their own: hold them and send them with the next picture,
                        // so the decoder is never handed a buffer with nothing to show.
                        int have = config == null ? 0 : config.length;
                        if (have + len > 65536) {
                            have = 0;
                            config = null;
                        }
                        byte[] merged = new byte[have + len];
                        if (have > 0) {
                            System.arraycopy(config, 0, merged, 0, have);
                        }
                        System.arraycopy(data, 0, merged, have, len);
                        config = merged;
                        continue;
                    }
                    int idx = dec.dequeueInputBuffer(100000);
                    for (int tries = 0; idx < 0 && run && tries < 10; tries++) {
                        idx = dec.dequeueInputBuffer(100000);
                    }
                    if (idx < 0) {
                        // Skipping a frame would leave a smeared picture until the next keyframe;
                        // ending here makes the tab reconnect and start again from a clean one.
                        throw new IOException("the decoder stopped taking video");
                    }
                    ByteBuffer ib = dec.getInputBuffer(idx);
                    ib.clear();
                    int cfg = config == null ? 0 : config.length;
                    if (cfg + len > ib.capacity()) {
                        dec.queueInputBuffer(idx, 0, 0, pts, 0); // too big for this decoder: hand the buffer back
                        continue;
                    }
                    if (cfg > 0) {
                        ib.put(config, 0, cfg);
                        config = null;
                    }
                    ib.put(data, 0, len);
                    dec.queueInputBuffer(idx, 0, cfg + len, pts, 0);
                    pts += 16666;
                }
            }
        } catch (IOException | RuntimeException e) {
            why = "The video stopped: " + e.getMessage();
        } finally {
            run = false;
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {
                    // already stopped
                }
                codec.release();
            }
            Client.Stream s = st;
            if (s != null) {
                s.close();
            }
            if (drain != null) {
                try {
                    drain.join(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        ev.onEnd(why, off);
    }

    private void drain(MediaCodec dec) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean first = false;
        long statAt = SystemClock.elapsedRealtime();
        while (true) {
            try {
                int i = dec.dequeueOutputBuffer(info, 20000);
                if (i >= 0) {
                    dec.releaseOutputBuffer(i, true);
                    frames.incrementAndGet();
                    if (!first) {
                        first = true;
                        ev.onFrame();
                    }
                } else if (!run && i == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    return;
                }
                long now = SystemClock.elapsedRealtime();
                if (first && now - statAt >= 1000) {
                    long ms = now - statAt;
                    statAt = now;
                    ev.onStats(Math.round(frames.getAndSet(0) * 1000f / ms), (int) (bytes.getAndSet(0) * 8 / ms));
                }
            } catch (RuntimeException e) {
                return;
            }
        }
    }
}
