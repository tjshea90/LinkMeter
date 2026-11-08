package com.tj.linkmeter;

import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.WindowManager;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.regex.Pattern;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Phone only: lets the paired tablet see and control the phone's screen. A picture of the screen is
 * taken through Shizuku, shrunk to a small JPEG and sent; taps, swipes, keys, text and the PIN are
 * typed in through Shizuku too. Nothing runs unless the tablet's Screen tab is open and asks.
 * The PIN is checked to be digits, used once, and never stored or logged.
 */
final class Screen {
    /** Keys the tablet may press: home, back, volume, power, menu, recents, sleep, wake. */
    private static final int[] KEYS = {3, 4, 24, 25, 26, 82, 187, 223, 224};

    private Screen() {}

    private static boolean allowedKey(int code) {
        for (int k : KEYS) {
            if (k == code) {
                return true;
            }
        }
        return false;
    }

    private static String body(Http.Req r, int max) throws IOException {
        int n = (int) Math.min(r.length, max);
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int k = r.body.read(b, off, n - off);
            if (k < 0) {
                break;
            }
            off += k;
        }
        return new String(b, 0, off, StandardCharsets.UTF_8);
    }

    private static void ok(OutputStream out, String text) throws IOException {
        Http.json(out, 200, "{\"ok\":true,\"out\":" + org.json.JSONObject.quote(text) + "}");
    }

    static void serve(Context c, Http.Req r, OutputStream out) throws IOException {
        if (!Cfg.screenControl(c)) {
            Http.err(out, 403, "Screen control is turned off on the phone.");
            return;
        }
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        if ("/screen/state".equals(r.path)) {
            Http.json(out, 200, "{\"on\":" + pm.isInteractive() + ",\"locked\":" + km.isKeyguardLocked() + "}");
            return;
        }
        if (!Priv.ready()) {
            Http.err(out, 409, "The phone can't do that: " + Priv.stateText(Priv.state()) + ".");
            return;
        }
        try {
            switch (r.path) {
                case "/screen/frame":
                    frame(c, r, out, pm);
                    break;
                case "/screen/video":
                    video(c, r, out, pm);
                    break;
                case "/screen/ctl":
                    ctl(c, r, out);
                    break;
                case "/screen/tap":
                    run(out, "input tap " + num(r, "x") + " " + num(r, "y"), "Tapped.");
                    break;
                case "/screen/swipe": {
                    long ms = Math.max(50, Math.min(3000, r.qLong("ms", 300)));
                    run(out, "input swipe " + num(r, "x1") + " " + num(r, "y1") + " " + num(r, "x2") + " "
                            + num(r, "y2") + " " + ms, "Swiped.");
                    break;
                }
                case "/screen/key": {
                    int code = (int) r.qLong("code", -1);
                    if (!allowedKey(code)) {
                        Http.err(out, 400, "That key is not allowed.");
                    } else {
                        run(out, "input keyevent " + code, "Done.");
                    }
                    break;
                }
                case "/screen/on":
                    run(out, "input keyevent 224", "Screen on.");
                    break;
                case "/screen/off":
                    run(out, "input keyevent 223", "Screen off.");
                    break;
                case "/screen/text": {
                    String t = body(r, 120);
                    if (t.isEmpty() || !t.matches("[A-Za-z0-9 .,@_+:/=-]*")) {
                        Http.err(out, 400, "Only letters, numbers and simple punctuation can be typed.");
                    } else {
                        run(out, "input text '" + t.replace(" ", "%s") + "'", "Typed.");
                    }
                    break;
                }
                case "/screen/pin":
                    ok(out, unlock(km, body(r, 40).trim()));
                    break;
                default:
                    Http.err(out, 404, "unknown request");
                    break;
            }
        } catch (IllegalArgumentException e) {
            Http.err(out, 400, String.valueOf(e.getMessage()));
        }
    }

    /** A whole number from the request, kept in a sane range so it can't carry anything else. */
    private static long num(Http.Req r, String key) {
        long v = r.qLong(key, Long.MIN_VALUE);
        if (v < 0 || v > 20000) {
            throw new IllegalArgumentException("bad " + key);
        }
        return v;
    }

    private static void run(OutputStream out, String cmd, String okText) throws IOException {
        Priv.Result res = Priv.run(cmd, 8);
        if (res.ok()) {
            ok(out, okText);
        } else {
            Http.err(out, 409, "The phone said: " + res.out);
        }
    }

    /** Types the PIN on the lock screen. Only if the phone really is locked, so it never types into an app. */
    private static String unlock(KeyguardManager km, String pin) throws IOException {
        if (!pin.matches("[0-9]{4,16}")) {
            throw new IllegalArgumentException("A PIN is 4 to 16 digits.");
        }
        if (!km.isKeyguardLocked()) {
            return "The phone is already unlocked.";
        }
        StringBuilder keys = new StringBuilder();
        for (int i = 0; i < pin.length(); i++) {
            keys.append(7 + (pin.charAt(i) - '0')).append(' '); // KEYCODE_0 is 7
        }
        String cmd = "S=$(wm size | grep -o '[0-9]*x[0-9]*' | tail -1); W=${S%x*}; H=${S#*x}; "
                + "input keyevent 224; sleep 1; "
                + "input swipe $((W/2)) $((H*85/100)) $((W/2)) $((H*20/100)) 300; sleep 1; "
                + "input keyevent " + keys + "; sleep 0.3; input keyevent 66";
        Priv.run(cmd, 20);
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return km.isKeyguardLocked()
                ? "Still locked. The PIN may be wrong, or this lock screen did not take the keys. "
                + "Too many wrong tries make Android lock you out for a while."
                : "Unlocked.";
    }

    // ------------------------------------------------------------ live touch channel

    private static Process injector;
    private static OutputStream injectorIn;
    private static String injectorErr = "";
    private static final Pattern CTL = Pattern.compile("^(?:[dmu] \\d{1,5} \\d{1,5}|k \\d{1,3})$");

    private static synchronized boolean injectorReady(Context c) {
        try {
            if (injector != null && injector.isAlive()) {
                return true;
            }
        } catch (RuntimeException ignored) {
            // treat as not running
        }
        if (injector != null) {
            injector.destroy();
            injector = null;
        }
        try {
            final Process p = Priv.spawn(Cells.mainCommand(c, "inject") + " 2>&1");
            final String[] first = new String[1];
            Thread t = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    first[0] = br.readLine();
                    while (br.readLine() != null) {
                        // keep reading so the helper never blocks on a full pipe
                    }
                } catch (IOException ignored) {
                    // the helper ended
                }
            }, "linkmeter-inject-out");
            t.setDaemon(true);
            t.start();
            long end = SystemClock.elapsedRealtime() + 10000;
            while (first[0] == null && SystemClock.elapsedRealtime() < end) {
                Thread.sleep(50);
            }
            if (first[0] != null && first[0].startsWith("OK")) {
                injector = p;
                injectorIn = p.getOutputStream();
                return true;
            }
            injectorErr = first[0] == null ? "no answer in 10 seconds" : first[0];
            p.destroy();
        } catch (IOException | InterruptedException e) {
            injectorErr = String.valueOf(e.getMessage());
        }
        return false;
    }

    private static synchronized void inject(String line) throws IOException {
        injectorIn.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
        injectorIn.flush();
    }

    /** A long connection: the tablet sends touch lines, the phone injects them at once. */
    private static void ctl(Context c, Http.Req r, OutputStream out) throws IOException {
        if (!injectorReady(c)) {
            Http.err(out, 409, "The phone's touch helper did not start: " + injectorErr);
            return;
        }
        r.sock.setSoTimeout(10 * 60 * 1000);
        Http.headOpen(out, "text/plain", "");
        out.flush();
        BufferedReader br = new BufferedReader(new InputStreamReader(r.body, StandardCharsets.US_ASCII));
        String line;
        while ((line = br.readLine()) != null) {
            if (!CTL.matcher(line).matches()) {
                continue;
            }
            if (line.startsWith("k ") && !allowedKey(Integer.parseInt(line.substring(2)))) {
                continue;
            }
            inject(line);
        }
    }

    // ------------------------------------------------------------ live video

    private static int[] realSize(Context c) {
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            return new int[] {b.width(), b.height()};
        }
        Point p = new Point();
        wm.getDefaultDisplay().getRealSize(p);
        return new int[] {p.x, p.y};
    }

    /**
     * H.264 video of the screen, made by the phone's own screenrecord tool (hardware encoder).
     * The stream is cut into whole frames and sent as [4-byte length][frame] so the tablet's
     * decoder never gets half a frame. screenrecord stops after 3 minutes, so it is restarted.
     */
    private static void video(Context c, Http.Req r, OutputStream out, PowerManager pm) throws IOException {
        r.sock.setSoTimeout(0);
        if (!pm.isInteractive()) {
            Http.json(out, 200, "{\"off\":true}");
            return;
        }
        int[] real = realSize(c);
        int tw = (int) Math.max(240, Math.min(1080, r.qLong("w", 720))) / 16 * 16;
        int th = Math.max(16, Math.round(real[1] * (float) tw / real[0] / 16f) * 16);
        long br = Math.max(500000, Math.min(12000000, r.qLong("br", 3000000)));
        Http.headOpen(out, "video/h264", "X-Size: " + real[0] + "x" + real[1] + "\r\nX-Video: " + tw + "x" + th + "\r\n");
        out.flush();
        DataOutputStream dout = new DataOutputStream(out);
        byte[] buf = new byte[64 * 1024];
        ByteArrayOutputStream acc = new ByteArrayOutputStream(128 * 1024);
        int failures = 0;
        try {
            while (true) {
                long started = SystemClock.elapsedRealtime();
                long bytes = 0;
                Process p = Priv.spawn("exec screenrecord --output-format=h264 --size " + tw + "x" + th
                        + " --bit-rate " + br + " - 2>/dev/null");
                try {
                    InputStream in = p.getInputStream();
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        acc.write(buf, 0, n);
                        bytes += n;
                        if (in.available() == 0) {
                            Thread.sleep(3); // the rest of this frame may still be on its way
                            if (in.available() == 0 || acc.size() > (2 << 20)) {
                                sendPacket(dout, acc);
                            }
                        } else if (acc.size() > (2 << 20)) {
                            sendPacket(dout, acc);
                        }
                    }
                    if (acc.size() > 0) {
                        sendPacket(dout, acc);
                    }
                } finally {
                    p.destroy();
                }
                if (bytes == 0 && SystemClock.elapsedRealtime() - started < 2500) {
                    if (++failures >= 2) {
                        return; // screenrecord does not run here; the tablet falls back to pictures
                    }
                } else {
                    failures = 0;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sendPacket(DataOutputStream dout, ByteArrayOutputStream acc) throws IOException {
        byte[] pkt = acc.toByteArray();
        acc.reset();
        dout.writeInt(pkt.length);
        dout.write(pkt);
        dout.flush();
    }

    // ------------------------------------------------------------ pictures

    private static Bitmap full;
    private static byte[] raw;

    /** The screen as raw pixels straight from screencap: no PNG, no file, no decode. */
    private static Bitmap rawCapture() throws IOException {
        Process p = Priv.spawn("exec screencap 2>/dev/null");
        try {
            DataInputStream din = new DataInputStream(p.getInputStream());
            byte[] h = new byte[16];
            din.readFully(h);
            ByteBuffer hb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            int w = hb.getInt();
            int hh = hb.getInt();
            int fmt = hb.getInt();
            long size = (long) w * hh * 4;
            if (fmt != 1 || w <= 0 || hh <= 0 || size > 40L * 1024 * 1024) {
                throw new IOException("unsupported capture format " + fmt);
            }
            if (raw == null || raw.length < size) {
                raw = new byte[(int) size];
            }
            din.readFully(raw, 0, (int) size);
            if (full == null || full.getWidth() != w || full.getHeight() != hh) {
                if (full != null) {
                    full.recycle();
                }
                full = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
            }
            full.copyPixelsFromBuffer(ByteBuffer.wrap(raw, 0, (int) size));
            return full;
        } finally {
            p.destroy();
        }
    }

    /** One screenshot as a small JPEG. The real size goes in the X-Size header for the tablet's taps. */
    private static synchronized void frame(Context c, Http.Req r, OutputStream out, PowerManager pm)
            throws IOException {
        if (!pm.isInteractive()) {
            Http.json(out, 200, "{\"off\":true}");
            return;
        }
        int tw = (int) Math.max(200, Math.min(1080, r.qLong("w", 540)));
        int q = (int) Math.max(20, Math.min(90, r.qLong("q", 55)));
        Bitmap big = null;
        int w = 0;
        int h = 0;
        try {
            big = rawCapture();
            w = big.getWidth();
            h = big.getHeight();
        } catch (IOException | RuntimeException e) {
            big = null; // fall back to the slower PNG route below
        }
        if (big == null) {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) {
                Http.err(out, 500, "The phone's storage is not available.");
                return;
            }
            File png = new File(dir, "screen.png");
            Priv.Result res = Priv.run("screencap -p '" + png.getAbsolutePath() + "'", 8);
            if (!res.ok() || !png.isFile()) {
                Http.err(out, 409, "The screen could not be captured: " + res.out);
                return;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(png.getAbsolutePath(), o);
            w = o.outWidth;
            h = o.outHeight;
            if (w <= 0 || h <= 0) {
                Http.err(out, 409, "The screenshot could not be read (the screen may be protected).");
                return;
            }
            int sample = 1;
            while (w / (sample * 2) >= tw) {
                sample *= 2;
            }
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            big = BitmapFactory.decodeFile(png.getAbsolutePath(), o);
            if (big == null) {
                Http.err(out, 409, "The screenshot could not be decoded.");
                return;
            }
        }
        Bitmap small = big.getWidth() == tw ? big
                : Bitmap.createScaledBitmap(big, tw, (int) ((long) big.getHeight() * tw / big.getWidth()), true);
        ByteArrayOutputStream bo = new ByteArrayOutputStream(64 * 1024);
        small.compress(Bitmap.CompressFormat.JPEG, q, bo);
        if (small != big) {
            small.recycle();
        }
        if (big != full) {
            big.recycle();
        }
        byte[] jpg = bo.toByteArray();
        Http.headWith(out, 200, "image/jpeg", jpg.length, "X-Size: " + w + "x" + h);
        out.write(jpg);
        out.flush();
    }
}
