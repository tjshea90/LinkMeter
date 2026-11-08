package com.tj.linkmeter;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.view.Surface;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Tablet side: receives the phone's H.264 screen video and shows it with the tablet's hardware
 * decoder. Frames arrive as [length][frame]; each goes to the decoder as one piece and is shown the
 * moment it is decoded, with nothing queued up, which is what keeps the delay small.
 * A negative length is a message from the phone (why it could not start the video) instead of a frame.
 *
 * "Tuning" (on by default) is about decoders that hold finished pictures back: the stream header is
 * rewritten to say pictures come out in order (see SpsFix) and the decoder is asked for its low-latency
 * mode. If the decoder refuses those requests it is started without them.
 */
final class VideoStream {
    interface Events {
        void onInfo(int phoneW, int phoneH, int videoW, int videoH);

        /** The first picture came out of the decoder. */
        void onFrame();

        void onStats(int fps, int kbps);

        void onEnd(String why, boolean screenOff);

        /** A picture arrived from the phone (every one, from the video thread: keep it cheap). */
        default void onPacket() {}

        /** Like onStats, with the highest rate of the last 10 seconds as well. */
        default void onStats(int fps, int kbps, int peakKbps) {
            onStats(fps, kbps);
        }

        /** The decoder and the stream's own description, once per start. */
        default void onDiag(String text) {}
    }

    private final String host;
    private final String token;
    private final String query;
    private final Surface surface;
    private final String decoderName;
    private final boolean tune;
    private final Events ev;
    private volatile boolean run;
    private volatile Client.Stream st;
    private Thread thread;
    private final AtomicInteger frames = new AtomicInteger();
    private final AtomicLong bytes = new AtomicLong();
    private final int[] recent = new int[10]; // kbit/s of the last ten seconds
    private int recentAt;

    VideoStream(String host, String token, String query, Surface surface, Events ev) {
        this(host, token, query, surface, "", false, ev);
    }

    VideoStream(String host, String token, String query, Surface surface, String decoderName, boolean tune,
            Events ev) {
        this.host = host;
        this.token = token;
        this.query = query;
        this.surface = surface;
        this.decoderName = decoderName == null ? "" : decoderName;
        this.tune = tune;
        this.ev = ev;
    }

    private static List<String> decoderNames;

    /** Names of this tablet's H.264 decoders, for the choice in the advanced settings. Looked up once. */
    static synchronized List<String> decoders() {
        if (decoderNames != null) {
            return decoderNames;
        }
        List<String> out = new ArrayList<>();
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder()) {
                    continue;
                }
                for (String t : info.getSupportedTypes()) {
                    if (t.equalsIgnoreCase("video/avc")) {
                        out.add(info.getName());
                        break;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // no list: the default decoder is still used
        }
        decoderNames = out;
        return out;
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

    private MediaCodec makeDecoder() throws IOException {
        if (!decoderName.isEmpty()) {
            try {
                return MediaCodec.createByCodecName(decoderName);
            } catch (IOException | RuntimeException e) {
                // that decoder is not there any more: use the default one
            }
        }
        return MediaCodec.createDecoderByType("video/avc");
    }

    /** Asks decoders that know these options to show each picture at once. Unknown keys are ignored. */
    private static void addHints(MediaFormat f) {
        f.setInteger("low-latency", 1); // MediaFormat.KEY_LOW_LATENCY on Android 11 and newer
        f.setInteger("vendor.qti-ext-dec-low-latency.enable", 1);
        f.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1);
        f.setInteger("vendor.low-latency.enable", 1);
        f.setInteger(MediaFormat.KEY_PRIORITY, 0); // real-time
        f.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE);
    }

    /** Starts a decoder on the surface. With tuning, the low-latency hints go first; a decoder that refuses is started plain. */
    private MediaCodec startDecoder(int vw, int vh) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            MediaCodec d = null;
            try {
                d = makeDecoder();
                MediaFormat f = MediaFormat.createVideoFormat("video/avc", vw, vh);
                f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 20);
                if (tune && attempt == 0) {
                    addHints(f);
                }
                d.configure(f, surface, null, 0);
                d.start();
                return d;
            } catch (RuntimeException | IOException e) {
                if (d != null) {
                    try {
                        d.release();
                    } catch (RuntimeException ignored) {
                        // already gone
                    }
                }
                if (attempt == 1 || !tune) {
                    throw e;
                }
            }
        }
        throw new IOException("no video decoder could be started");
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

                final MediaCodec dec = startDecoder(vw, vh);
                codec = dec;
                frames.set(0);
                bytes.set(0);
                drain = new Thread(() -> drain(dec), "linkmeter-video-out");
                drain.start();

                DataInputStream din = new DataInputStream(s.in);
                byte[] data = new byte[256 * 1024];
                byte[] config = null; // SPS/PPS that came without a picture: goes in front of the next one
                boolean diagSent = false;
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
                    byte[] pkt = data;
                    int plen = len;
                    if (SpsFix.hasSps(data, len)) {
                        byte[] fixed = tune ? SpsFix.fix(data, len) : null;
                        if (!diagSent) {
                            diagSent = true;
                            ev.onDiag("Decoder: " + dec.getName() + "\nStream: " + SpsFix.describe(data, len)
                                    + (!tune ? "\nHeader fix: off"
                                            : (fixed != null ? "\nSent to the decoder as: " + SpsFix.describe(fixed, fixed.length)
                                                    : "\nHeader fix: not needed or not understood")));
                        }
                        if (fixed != null) {
                            pkt = fixed;
                            plen = fixed.length;
                        }
                    }
                    if (!hasPicture(pkt, plen)) {
                        // Parameter sets on their own: hold them and send them with the next picture,
                        // so the decoder is never handed a buffer with nothing to show.
                        int have = config == null ? 0 : config.length;
                        if (have + plen > 65536) {
                            have = 0;
                            config = null;
                        }
                        byte[] merged = new byte[have + plen];
                        if (have > 0) {
                            System.arraycopy(config, 0, merged, 0, have);
                        }
                        System.arraycopy(pkt, 0, merged, have, plen);
                        config = merged;
                        continue;
                    }
                    ev.onPacket();
                    // Ten tries of 0.1 s is a long time for a decoder; three seconds covers a busy tablet.
                    int idx = dec.dequeueInputBuffer(100000);
                    for (int tries = 0; idx < 0 && run && tries < 30; tries++) {
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
                    if (cfg + plen > ib.capacity()) {
                        dec.queueInputBuffer(idx, 0, 0, pts, 0); // too big for this decoder: hand the buffer back
                        continue;
                    }
                    if (cfg > 0) {
                        ib.put(config, 0, cfg);
                        config = null;
                    }
                    ib.put(pkt, 0, plen);
                    dec.queueInputBuffer(idx, 0, cfg + plen, pts, 0);
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
                    int kbps = (int) (bytes.getAndSet(0) * 8 / ms);
                    recent[recentAt++ % recent.length] = kbps;
                    int peak = 0;
                    for (int v : recent) {
                        peak = Math.max(peak, v);
                    }
                    ev.onStats(Math.round(frames.getAndSet(0) * 1000f / ms), kbps, peak);
                }
            } catch (RuntimeException e) {
                return;
            }
        }
    }
}
