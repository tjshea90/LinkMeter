package com.tj.linkmeter;

import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.PowerManager;
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

    /** One screenshot as a small JPEG. The real size goes in the X-Size header for the tablet's taps. */
    private static synchronized void frame(Context c, Http.Req r, OutputStream out, PowerManager pm)
            throws IOException {
        if (!pm.isInteractive()) {
            Http.json(out, 200, "{\"off\":true}");
            return;
        }
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
        int w = o.outWidth;
        int h = o.outHeight;
        if (w <= 0 || h <= 0) {
            Http.err(out, 409, "The screenshot could not be read (the screen may be protected).");
            return;
        }
        int tw = (int) Math.max(200, Math.min(1080, r.qLong("w", 540)));
        int sample = 1;
        while (w / (sample * 2) >= tw) {
            sample *= 2;
        }
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap big = BitmapFactory.decodeFile(png.getAbsolutePath(), o);
        if (big == null) {
            Http.err(out, 409, "The screenshot could not be decoded.");
            return;
        }
        Bitmap small = big.getWidth() == tw ? big
                : Bitmap.createScaledBitmap(big, tw, (int) ((long) big.getHeight() * tw / big.getWidth()), true);
        ByteArrayOutputStream bo = new ByteArrayOutputStream(64 * 1024);
        small.compress(Bitmap.CompressFormat.JPEG, 55, bo);
        if (small != big) {
            small.recycle();
        }
        big.recycle();
        byte[] jpg = bo.toByteArray();
        Http.headWith(out, 200, "image/jpeg", jpg.length, "X-Size: " + w + "x" + h);
        out.write(jpg);
        out.flush();
    }
}
