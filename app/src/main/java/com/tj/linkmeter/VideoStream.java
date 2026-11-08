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
 * decoder. The video arrives as packets, [length][bytes], and a negative length is a message from the phone
 * (why it could not start the video) instead of video. The phone cuts its stream into packets by guessing
 * where a picture ends, and on a busy phone it guesses wrong (a picture in two pieces, or two in one), which
 * leaves the decoder with a wrong reference picture and the screen full of mixed-up blocks until the next
 * keyframe. So the packets are only treated as a byte stream: AnnexBFramer finds the real picture
 * boundaries, each picture goes to the decoder as one piece and is shown the moment it is decoded, with
 * nothing queued up, which is what keeps the delay small. If a picture still turns out to have been cut,
 * the stream ends as "damaged" and the tab restarts it from a keyframe.
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

        /** The stream arrived damaged (a picture was cut): it ends now, and should be restarted from a keyframe. */
        default void onDamaged(String why) {}
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
    /** Set by the output thread when the decoder fails, so the input side stops at once instead of after a stall. */
    private volatile String drainError;

    /** The stream does not fit together any more. */
    private static final class Damaged extends IOException {
        Damaged(String why) {
            super(why);
        }
    }

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
        boolean damaged = false;
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

                final AnnexBFramer framer = new AnnexBFramer(new Feeder(dec));
                DataInputStream din = new DataInputStream(s.in);
                byte[] data = new byte[256 * 1024];
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
                    // Where the stream went quiet is the phone's word; where the pictures really end is in the bytes.
                    framer.push(data, 0, len);
                    framer.endOfPacket();
                }
            }
        } catch (Damaged d) {
            why = "The picture got damaged (" + d.getMessage() + ")";
            damaged = true;
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
        if (damaged) {
            ev.onDamaged(why);
        }
        ev.onEnd(why, off);
    }

    /** Gives the decoder one picture per input buffer (AnnexBFramer has already cut the stream into pictures). */
    private final class Feeder implements AnnexBFramer.Sink {
        private final MediaCodec dec;
        private long pts;
        private boolean diagSent;

        Feeder(MediaCodec dec) {
            this.dec = dec;
        }

        @Override
        public void unit(byte[] d, int n, boolean key, boolean sps, long at) throws IOException {
            String broken = drainError;
            if (broken != null) {
                throw new IOException(broken);
            }
            byte[] fixed = sps && tune ? SpsFix.fix(d, n) : null;
            ev.onPacket();
            // Ten tries of 0.1 s is a long time for a decoder; three seconds covers a busy tablet.
            int idx = dec.dequeueInputBuffer(100000);
            for (int tries = 0; idx < 0 && run && tries < 30; tries++) {
                idx = dec.dequeueInputBuffer(100000);
            }
            if (idx < 0) {
                // Skipping a picture would leave a smeared one until the next keyframe;
                // ending here makes the tab reconnect and start again from a clean one.
                throw new IOException("the decoder stopped taking video");
            }
            ByteBuffer ib = dec.getInputBuffer(idx);
            ib.clear();
            if (sps && !diagSent) {
                diagSent = true;
                ev.onDiag("Decoder: " + dec.getName() + " (input buffers " + (ib.capacity() / 1024) + " KB)\nStream: "
                        + SpsFix.describe(d, n)
                        + (!tune ? "\nHeader fix: off"
                                : (fixed != null ? "\nSent to the decoder as: " + SpsFix.describe(fixed, fixed.length)
                                        : "\nHeader fix: not needed or not understood")));
            }
            byte[] out = fixed != null ? fixed : d;
            int olen = fixed != null ? fixed.length : n;
            if (olen > ib.capacity()) {
                dec.queueInputBuffer(idx, 0, 0, pts, 0); // hand the buffer back
                throw new IOException("a picture of " + (olen / 1024) + " KB does not fit this decoder's input buffers ("
                        + (ib.capacity() / 1024) + " KB)");
            }
            ib.put(out, 0, olen);
            dec.queueInputBuffer(idx, 0, olen, pts, key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            pts += 16666;
        }

        @Override
        public void damaged(String why, long at) throws IOException {
            throw new Damaged(why); // ends this stream; the tab restarts it from a keyframe
        }
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
                if (run) {
                    drainError = "the decoder failed: " + e;
                }
                return;
            }
        }
    }
}
