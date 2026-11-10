package com.tj.linkmeter;

import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.WindowManager;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    /**
     * Keys the tablet may press: home, back, volume, power, menu, recents, sleep, wake, and the keys used
     * to type and move around (arrows, enter, tab, escape, backspace, delete, page and home/end keys).
     */
    private static final int[] KEYS = {3, 4, 24, 25, 26, 82, 187, 223, 224,
        19, 20, 21, 22, 23, 61, 66, 67, 92, 93, 111, 112, 122, 123};

    /** Video and touch connections that are open right now. While any is open the tablet is still there. */
    private static final AtomicInteger OPEN = new AtomicInteger();

    static int openStreams() {
        return OPEN.get();
    }

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
        if ("/screen/profile".equals(r.path)) {
            profile(c, r, out); // before the switch check: ending a session must always work
            return;
        }
        if (!Cfg.screenControl(c)) {
            Http.err(out, 403, "Screen control is turned off on the phone.");
            return;
        }
        RemoteProfile.seen();
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        if ("/screen/state".equals(r.path)) {
            // "vn" is this phone's app version, so the tablet can tell if the phone was left on an older build.
            String vn = Updater.versionName(c).replaceAll("[^0-9A-Za-z._-]", "");
            Http.json(out, 200, "{\"on\":" + pm.isInteractive() + ",\"locked\":" + km.isKeyguardLocked()
                    + ",\"prof\":" + RemoteProfile.isOn(c) + ",\"ds\":" + displayState(c) + ",\"vn\":\"" + vn + "\"}");
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
                    OPEN.incrementAndGet();
                    try {
                        video(c, r, out, pm);
                    } finally {
                        OPEN.decrementAndGet();
                    }
                    break;
                case "/screen/ctl":
                    OPEN.incrementAndGet();
                    try {
                        ctl(c, r, out);
                    } finally {
                        OPEN.decrementAndGet();
                    }
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
                    power(c, r, out, pm, true);
                    break;
                case "/screen/off":
                    power(c, r, out, pm, false);
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

    /**
     * The remote session's effect on the phone: POST ?keep=ms&dim=0..255 starts or updates it, POST ?end=1
     * puts everything back, GET says what it is doing. See RemoteProfile.
     */
    private static void profile(Context c, Http.Req r, OutputStream out) throws IOException {
        try {
            if ("POST".equals(r.method)) {
                if (!Priv.ready()) {
                    Http.err(out, 409, "The phone can't do that: " + Priv.stateText(Priv.state()) + ".");
                } else if ("1".equals(r.q("end"))) {
                    ok(out, RemoteProfile.end(c));
                } else if (!Cfg.screenControl(c)) {
                    Http.err(out, 403, "Screen control is turned off on the phone.");
                } else {
                    ok(out, RemoteProfile.begin(c, r.qLong("keep", 0), (int) r.qLong("dim", -1)));
                }
            } else {
                ok(out, RemoteProfile.status(c));
            }
        } catch (IOException e) {
            Http.err(out, 409, String.valueOf(e.getMessage()));
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

    /** The display's own state: 1 off, 2 on, 3 doze (the Always-On Display), 4 doze suspended, 5 VR, 6 on suspended; 0 unknown. */
    private static int displayState(Context c) {
        try {
            DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            return d == null ? 0 : d.getState();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Runs one shell command for Sleep or Wake; what happened, in a few words, for the trace. */
    private static String press(String cmd) {
        try {
            Priv.Result res = Priv.run(cmd, 10);
            return res.ok() ? "done" : (res.out.isEmpty() ? "failed" : res.out);
        } catch (IOException e) {
            return String.valueOf(e.getMessage()); // Shizuku went away in the middle of it
        }
    }

    /**
     * Wakes or sleeps the phone with the steps in PowerSequence (the exact methods first, each checked, the power key last
     * and only if needed; tested on a computer with a simulated phone). The answer says what the phone really is afterwards
     * ("on" = awake, "ds" = the display state) and what was tried ("how"), so a Sleep that "does nothing" can be understood
     * from the tablet. With ?nodoze=1 the display goes fully dark instead of showing the Always-On Display.
     */
    private static void power(final Context c, Http.Req r, OutputStream out, final PowerManager pm, final boolean wantOn)
            throws IOException {
        final boolean noDoze = !wantOn && "1".equals(r.q("nodoze"));
        PowerSequence.Result res;
        try {
            res = powerNow(c, wantOn, noDoze);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Http.err(out, 409, "The request was interrupted.");
            return;
        }
        String state = ",\"on\":" + pm.isInteractive() + ",\"ds\":" + displayState(c)
                + ",\"how\":" + org.json.JSONObject.quote(res.how);
        if (res.reached) {
            Http.json(out, 200, "{\"ok\":true,\"out\":\"" + (wantOn ? "Screen on." : "Screen off.") + "\"" + state + "}");
        } else {
            Http.json(out, 409, "{\"err\":" + org.json.JSONObject.quote("The phone did not "
                    + (wantOn ? "wake up" : "stay asleep") + ": " + res.how) + state + "}");
        }
    }

    /**
     * The checked Sleep or Wake itself (see {@link #power}), for callers without a request: the Standby action and the Control
     * tab's "Screen off". Needs Shizuku.
     */
    static PowerSequence.Result powerNow(final Context c, final boolean wantOn, final boolean noDoze) throws InterruptedException {
        final PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return PowerSequence.run(new PowerSequence.Phone() {
                @Override
                public boolean awake() {
                    return pm.isInteractive();
                }

                @Override
                public boolean dozing() {
                    int ds = displayState(c);
                    return ds == 3 || ds == 4;
                }

                @Override
                public String pressKey(int keyCode) {
                    return press("input keyevent " + keyCode);
                }

                @Override
                public String sleepService(boolean nd) {
                    try {
                        Priv.Result sr = Priv.run(Cells.mainCommandExec(c, "sleep" + (nd ? " nodoze" : "")), 10);
                        return sr.out.isEmpty() ? "no answer" : sr.out;
                    } catch (IOException e) {
                        return "ERR " + e.getMessage();
                    }
                }

                @Override
                public void pause(long ms) throws InterruptedException {
                    Thread.sleep(ms);
                }

                @Override
                public long now() {
                    return SystemClock.elapsedRealtime();
                }
            }, wantOn, noDoze);
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
    /**
     * Lines the tablet may send: d/m/u x y (finger down, move, up), k code (a key), p (still here), and
     * t followed by hex digits, 4 per character (typed text).
     */
    private static final Pattern CTL = Pattern.compile(
            "^(?:[dmu] \\d{1,5} \\d{1,5}|k \\d{1,3}|p|t [0-9a-f]{4,800})$");

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
            final Process p = Priv.spawn(Cells.mainCommandExec(c, "inject") + " 2>&1");
            final String[] first = new String[1];
            Thread t = new Thread(() -> {
                StringBuilder noise = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (first[0] == null && (line.startsWith("OK") || line.startsWith("ERR"))) {
                            first[0] = line;
                        } else if (first[0] == null && noise.length() < 300) {
                            noise.append(line).append(' '); // stray output before the verdict
                        }
                        // keep reading so the helper never blocks on a full pipe
                    }
                } catch (IOException ignored) {
                    // the helper ended
                }
                if (first[0] == null) {
                    first[0] = "ERR the helper ended: " + noise.toString().trim();
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
        if (injectorIn == null) {
            throw new IOException("the touch helper has stopped");
        }
        injectorIn.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
        injectorIn.flush();
    }

    /** Open touch connections. The helper runs only while at least one is open. Guarded by Screen.class. */
    private static int ctlUsers;

    /** Starts the helper if needed and counts this connection, in one step so a closing one cannot stop it. */
    private static synchronized boolean ctlOpen(Context c) {
        if (!injectorReady(c)) {
            return false;
        }
        ctlUsers++;
        return true;
    }

    /** The last touch connection closing stops the helper, so nothing idles on the phone between uses. */
    private static synchronized void ctlClose() {
        if (--ctlUsers <= 0) {
            ctlUsers = 0;
            if (injector != null) {
                injector.destroy();
                injector = null;
                injectorIn = null;
            }
        }
    }

    /** A long connection: the tablet sends touch lines, the phone injects them at once. */
    private static void ctl(Context c, Http.Req r, OutputStream out) throws IOException {
        if (!ctlOpen(c)) {
            Http.err(out, 409, "The phone's touch helper did not start: " + injectorErr);
            return;
        }
        boolean down = false; // a finger is held down (no "up" seen yet)
        int lx = 0;
        int ly = 0;
        try {
            // The tablet sends a "p" line every minute, so a connection that stays silent this long is gone.
            r.sock.setSoTimeout(3 * 60 * 1000);
            Http.headOpen(out, "text/plain", "");
            out.flush();
            BufferedReader br = new BufferedReader(new InputStreamReader(r.body, StandardCharsets.US_ASCII));
            String line;
            while ((line = br.readLine()) != null) {
                if (!CTL.matcher(line).matches()) {
                    continue;
                }
                char ch = line.charAt(0);
                if (ch == 'p') {
                    RemoteProfile.seen(); // the tablet is still there; nothing to inject
                    continue;
                }
                if (ch == 'k' && !allowedKey(Integer.parseInt(line.substring(2)))) {
                    continue;
                }
                if (ch == 't') {
                    if (line.length() % 4 != 2) {
                        continue; // "t " and then whole characters of 4 hex digits
                    }
                    line = TypingFilter.filterLine(line); // NEVER * or # (they start phone service codes); the helper checks again
                    if (line == null) {
                        continue;
                    }
                }
                inject(line);
                if (ch == 'd' || ch == 'm' || ch == 'u') {
                    String[] q = line.split(" ");
                    lx = Integer.parseInt(q[1]);
                    ly = Integer.parseInt(q[2]);
                    down = ch != 'u';
                }
            }
        } finally {
            if (down) {
                // The tablet went away mid-touch: lift the finger so the phone is not left holding a press.
                try {
                    inject("u " + lx + " " + ly);
                } catch (IOException ignored) {
                    // the helper is gone anyway
                }
            }
            ctlClose();
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

    /** Where screenrecord's own error text goes, so the tablet can be told why video would not start. */
    private static final String SR_ERR = "/data/local/tmp/linkmeter-screenrecord.err";

    /**
     * H.264 video of the screen, made by the phone's own screenrecord tool (hardware encoder).
     * The stream is sent as packets, [4-byte length][bytes], cut where the pipe went quiet. That is only a
     * guess at where a picture ends (screenrecord marks nothing), so the tablet finds the real picture
     * boundaries itself (AnnexBFramer). A negative length means "the next -length bytes are a
     * message" (why video could not start). screenrecord stops after 3 minutes, so it is restarted.
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
        // The tablet sends nothing after its request, so a read that ends means it has gone away.
        // Without this watcher screenrecord would keep running on a still screen (it only writes when
        // the picture changes, so the write that would notice the closed connection never comes).
        final AtomicBoolean gone = new AtomicBoolean();
        final AtomicReference<Process> cur = new AtomicReference<>();
        Thread watch = new Thread(() -> {
            try {
                while (r.body.read() >= 0) {
                    // ignore anything the tablet might send
                }
            } catch (IOException ignored) {
                // the connection was closed
            }
            gone.set(true);
            Process q = cur.get();
            if (q != null) {
                q.destroy();
            }
        }, "linkmeter-video-watch");
        watch.setDaemon(true);
        watch.start();
        try {
            while (!gone.get()) {
                long started = SystemClock.elapsedRealtime();
                long bytes = 0;
                Process p = Priv.spawn("exec screenrecord --output-format=h264 --size " + tw + "x" + th
                        + " --bit-rate " + br + " - 2>" + SR_ERR);
                cur.set(p);
                try {
                    if (gone.get()) {
                        break;
                    }
                    InputStream in = p.getInputStream();
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        acc.write(buf, 0, n);
                        bytes += n;
                        // The tablet finds the pictures in the stream by itself (AnnexBFramer), so this only decides
                        // when to send what has arrived: when the pipe has been quiet for a moment, or when a lot has
                        // piled up. After a big picture the quiet time is longer, because its pieces can arrive with
                        // gaps when the phone is busy (the relay process in between is scheduled like any other),
                        // while a small picture is written in one go and arrives whole.
                        if (acc.size() > (2 << 20) || quiet(in, acc.size() < ONE_PIECE ? 3 : 12)) {
                            sendPacket(dout, acc);
                        }
                    }
                    if (acc.size() > 0) {
                        sendPacket(dout, acc);
                    }
                } finally {
                    cur.set(null);
                    p.destroy();
                }
                if (gone.get()) {
                    break;
                }
                if (bytes == 0 && SystemClock.elapsedRealtime() - started < 2500) {
                    if (++failures >= 2) {
                        sendError(dout, screenrecordError());
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

    /** Up to this many bytes, a picture is written by screenrecord in one go and reaches us in one piece. */
    private static final int ONE_PIECE = 3000;

    /** True if nothing more arrives for ms milliseconds (looked at every millisecond, so new data ends the wait at once). */
    private static boolean quiet(InputStream in, long ms) throws IOException, InterruptedException {
        long end = SystemClock.elapsedRealtime() + ms;
        do {
            if (in.available() > 0) {
                return false;
            }
            Thread.sleep(1);
        } while (SystemClock.elapsedRealtime() < end);
        return in.available() == 0;
    }

    private static void sendPacket(DataOutputStream dout, ByteArrayOutputStream acc) throws IOException {
        byte[] pkt = acc.toByteArray();
        acc.reset();
        dout.writeInt(pkt.length);
        dout.write(pkt);
        dout.flush();
    }

    /** A negative length tells the tablet that the next bytes are a message, not video. */
    private static void sendError(DataOutputStream dout, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(b.length, 1000);
        dout.writeInt(-n);
        dout.write(b, 0, n);
        dout.flush();
    }

    /** What screenrecord itself said when it would not run. */
    private static String screenrecordError() {
        try {
            String t = Priv.run("head -c 300 " + SR_ERR, 5).out.trim();
            return t.isEmpty() ? "screenrecord produced no video" : "screenrecord said: " + t;
        } catch (IOException e) {
            return "screenrecord produced no video";
        }
    }

    // ------------------------------------------------------------ pictures

    private static Bitmap full;
    private static byte[] raw;
    /** Guards the two buffers above. Separate from the class lock so touch events never wait for a picture. */
    private static final Object FRAME_LOCK = new Object();

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
    private static void frame(Context c, Http.Req r, OutputStream out, PowerManager pm)
            throws IOException {
        synchronized (FRAME_LOCK) {
            frameLocked(c, r, out, pm);
        }
    }

    private static void frameLocked(Context c, Http.Req r, OutputStream out, PowerManager pm)
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
