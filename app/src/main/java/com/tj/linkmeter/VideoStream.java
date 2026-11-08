package com.tj.linkmeter;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Tablet side: receives the phone's H.264 screen video and shows it with the tablet's hardware
 * decoder. Frames arrive as [length][frame]; each goes to the decoder as one piece and is shown the
 * moment it is decoded, with nothing queued up, which is what keeps the delay small.
 */
final class VideoStream {
    interface Events {
        void onInfo(int phoneW, int phoneH, int videoW, int videoH);

        void onFrame();

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
            drain = new Thread(() -> drain(dec), "linkmeter-video-out");
            drain.start();

            DataInputStream din = new DataInputStream(s.in);
            byte[] data = new byte[256 * 1024];
            long pts = 0;
            while (run) {
                int len = din.readInt();
                if (len <= 0 || len > (4 << 20)) {
                    throw new IOException("bad video frame");
                }
                if (data.length < len) {
                    data = new byte[len];
                }
                din.readFully(data, 0, len);
                int idx = dec.dequeueInputBuffer(500000);
                if (idx < 0) {
                    continue; // the decoder is badly behind: skip this frame
                }
                ByteBuffer ib = dec.getInputBuffer(idx);
                ib.clear();
                ib.put(data, 0, len);
                dec.queueInputBuffer(idx, 0, len, pts, 0);
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
        while (true) {
            try {
                int i = dec.dequeueOutputBuffer(info, 20000);
                if (i >= 0) {
                    dec.releaseOutputBuffer(i, true);
                    if (!first) {
                        first = true;
                        ev.onFrame();
                    }
                } else if (!run && i == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    return;
                }
            } catch (RuntimeException e) {
                return;
            }
        }
    }
}
