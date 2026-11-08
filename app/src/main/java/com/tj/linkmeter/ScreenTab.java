package com.tj.linkmeter;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * See and control the phone's screen from the tablet.
 *
 * Video mode (fast): the phone's hardware encoder streams H.264 and this tablet's hardware decoder
 * shows it, a few frames behind at most. Pictures mode (compatible): a small JPEG of the screen
 * every moment. Touches travel over one long connection as they happen; if that cannot start, taps
 * and swipes are sent one at a time instead. Nothing runs unless this tab is open.
 */
final class ScreenTab extends Tab {
    private static final int[] VID_W = {540, 720, 1080};
    private static final int[] VID_BR = {1500000, 3000000, 8000000};
    private static final int[] PIC_W = {360, 540, 720};
    private static final int[] PIC_Q = {40, 55, 70};
    private static final String[] QUAL = {"Fast", "Balanced", "Sharp"};

    private LinearLayout root;
    private LinearLayout topBar;
    private LinearLayout row1;
    private LinearLayout row2;
    private LinearLayout fsBar;
    private FrameLayout stage;
    private PhoneScreenView pics;
    private AspectSurfaceView vid;
    private TextView note;
    private TextView status;
    private Button fsToggle;
    private CheckBox sleepBox;
    private final Button[] modeBtn = new Button[2];
    private final Button[] qualBtn = new Button[3];

    private boolean shown;
    private boolean surfaceReady;
    private boolean fullscreen;
    private boolean fsBarOn = true;
    private boolean woke;
    private int mode;
    private int quality;
    private boolean fellBack;

    private VideoStream video;
    private int videoGen;
    private boolean gotFrame;
    private CtlChannel ctl;
    private boolean ctlTrying;
    private long ctlFailedAt;
    /** The finger that is down right now is being sent over the live channel. */
    private boolean gestureOnCtl;
    private int fbX;
    private int fbY;
    private long fbT;

    private HandlerThread worker;
    private volatile Handler bg;
    private volatile boolean polling;

    ScreenTab(MainActivity a) {
        super(a);
    }

    @Override
    String title() {
        return "Screen";
    }

    // ------------------------------------------------------------ screen layout

    @Override
    View build() {
        mode = Cfg.screenMode(act);
        quality = Cfg.screenQuality(act);
        fellBack = false;
        surfaceReady = false;

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 8));

        topBar = new LinearLayout(act);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        modeBtn[0] = chip("Video", v -> setMode(0));
        modeBtn[1] = chip("Pictures", v -> setMode(1));
        topBar.addView(modeBtn[0], Ui.weight());
        topBar.addView(modeBtn[1], Ui.weight());
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            qualBtn[i] = chip(QUAL[i], v -> setQuality(idx));
            topBar.addView(qualBtn[i], Ui.weight());
        }
        topBar.addView(chip("Full", v -> enterFullscreen()), Ui.weight());
        root.addView(topBar);

        status = Ui.text(act, "Connecting…", 12, Ui.DIM, false);
        root.addView(status);

        stage = new FrameLayout(act);
        stage.setBackgroundColor(0xFF000000);
        pics = new PhoneScreenView(act);
        pics.setListener(this::touch);
        vid = new AspectSurfaceView(act);
        vid.setListener(this::touch);
        vid.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder h) {
                surfaceReady = true;
                if (shown && mode == 0 && video == null) {
                    startVideo();
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}

            @Override
            public void surfaceDestroyed(SurfaceHolder h) {
                surfaceReady = false;
                stopVideo();
            }
        });
        stage.addView(pics, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stage.addView(vid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        note = Ui.text(act, "", 15, Ui.FG, false);
        note.setGravity(Gravity.CENTER);
        note.setVisibility(View.GONE);
        stage.addView(note, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        fsBar = new LinearLayout(act);
        fsBar.setOrientation(LinearLayout.HORIZONTAL);
        fsBar.setBackgroundColor(0xB0000000);
        fsBar.addView(small("Exit", v -> exitFullscreen()), Ui.weight());
        fsBar.addView(small("Back", v -> key(4)), Ui.weight());
        fsBar.addView(small("Home", v -> key(3)), Ui.weight());
        fsBar.addView(small("Recents", v -> key(187)), Ui.weight());
        fsBar.addView(small("Wake", v -> wake()), Ui.weight());
        fsBar.addView(small("Sleep", v -> post("/screen/off")), Ui.weight());
        fsBar.addView(small("Unlock", v -> askPin()), Ui.weight());
        fsBar.setVisibility(View.GONE);
        stage.addView(fsBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        fsToggle = small("▾", v -> toggleFsBar());
        fsToggle.setVisibility(View.GONE);
        stage.addView(fsToggle, new FrameLayout.LayoutParams(Ui.dp(act, 48), Ui.dp(act, 40),
                Gravity.TOP | Gravity.END));
        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        row1 = new LinearLayout(act);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(Ui.button(act, "Wake", v -> wake()), Ui.weight());
        row1.addView(Ui.button(act, "Sleep", v -> post("/screen/off")), Ui.weight());
        row1.addView(Ui.button(act, "Unlock", v -> askPin()), Ui.weight());
        root.addView(row1);

        row2 = new LinearLayout(act);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(Ui.button(act, "Back", v -> key(4)), Ui.weight());
        row2.addView(Ui.button(act, "Home", v -> key(3)), Ui.weight());
        row2.addView(Ui.button(act, "Recents", v -> key(187)), Ui.weight());
        row2.addView(Ui.button(act, "Vol -", v -> key(25)), Ui.weight());
        row2.addView(Ui.button(act, "Vol +", v -> key(24)), Ui.weight());
        root.addView(row2);

        sleepBox = new CheckBox(act);
        sleepBox.setText("Turn the phone's screen off when I leave this tab");
        sleepBox.setTextColor(Ui.FG);
        sleepBox.setChecked(true);
        root.addView(sleepBox);

        paintChips();
        return root;
    }

    private Button chip(String label, View.OnClickListener l) {
        Button b = Ui.button(act, label, l);
        b.setTextSize(11);
        b.setPadding(0, b.getPaddingTop(), 0, b.getPaddingBottom());
        return b;
    }

    private Button small(String label, View.OnClickListener l) {
        Button b = Ui.button(act, label, l);
        b.setTextSize(11);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private void paintChips() {
        for (int i = 0; i < modeBtn.length; i++) {
            modeBtn[i].setAlpha(i == mode ? 1f : 0.5f);
        }
        for (int i = 0; i < qualBtn.length; i++) {
            qualBtn[i].setAlpha(i == quality ? 1f : 0.5f);
        }
    }

    private void setMode(int m) {
        mode = m;
        fellBack = false;
        Cfg.setScreenMode(act, m);
        paintChips();
        if (shown) {
            applyMode();
        }
    }

    private void setQuality(int q) {
        quality = q;
        Cfg.setScreenQuality(act, q);
        paintChips();
        if (shown) {
            applyMode();
        }
    }

    // ------------------------------------------------------------ full screen

    private void enterFullscreen() {
        fullscreen = true;
        act.setFullscreen(true);
        act.backHandler = this::exitFullscreen;
        root.setPadding(0, 0, 0, 0);
        topBar.setVisibility(View.GONE);
        status.setVisibility(View.GONE);
        row1.setVisibility(View.GONE);
        row2.setVisibility(View.GONE);
        sleepBox.setVisibility(View.GONE);
        fsBarOn = true;
        fsBar.setVisibility(View.VISIBLE);
        fsToggle.setText("▾");
        fsToggle.setVisibility(View.VISIBLE);
    }

    private void exitFullscreen() {
        if (!fullscreen) {
            return;
        }
        fullscreen = false;
        act.setFullscreen(false);
        act.backHandler = null;
        if (root != null) {
            int pad = Ui.dp(act, 8);
            root.setPadding(pad, pad, pad, pad);
            topBar.setVisibility(View.VISIBLE);
            status.setVisibility(View.VISIBLE);
            row1.setVisibility(View.VISIBLE);
            row2.setVisibility(View.VISIBLE);
            sleepBox.setVisibility(View.VISIBLE);
            fsBar.setVisibility(View.GONE);
            fsToggle.setVisibility(View.GONE);
        }
    }

    private void toggleFsBar() {
        fsBarOn = !fsBarOn;
        fsBar.setVisibility(fsBarOn ? View.VISIBLE : View.GONE);
        fsToggle.setText(fsBarOn ? "▾" : "▴");
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    void onShow() {
        shown = true;
        connectCtl();
        applyMode();
    }

    @Override
    void onHide() {
        shown = false;
        stopStreams();
        CtlChannel c = ctl;
        ctl = null;
        gestureOnCtl = false;
        if (c != null) {
            c.close();
        }
        exitFullscreen();
        if (woke && sleepBox != null && sleepBox.isChecked()) {
            post("/screen/off");
        }
        woke = false;
    }

    private void applyMode() {
        stopStreams();
        if (note == null) {
            return;
        }
        note.setVisibility(View.GONE);
        boolean v = mode == 0;
        vid.setVisibility(v ? View.VISIBLE : View.GONE);
        pics.setVisibility(v ? View.GONE : View.VISIBLE);
        if (!shown) {
            return;
        }
        if (v) {
            if (surfaceReady) {
                startVideo();
            }
        } else {
            startPictures();
        }
    }

    private void stopStreams() {
        stopVideo();
        stopPictures();
    }

    private void showNote(String s) {
        if (note != null) {
            note.setText(s);
            note.setVisibility(View.VISIBLE);
        }
    }

    // ------------------------------------------------------------ video mode

    private void startVideo() {
        stopVideo();
        String host = Net.phoneHost(act);
        if (host.isEmpty()) {
            showNote("The tablet is not on the phone's hotspot.");
            return;
        }
        gotFrame = false;
        final int gen = ++videoGen;
        String q = "w=" + VID_W[quality] + "&br=" + VID_BR[quality];
        status.setText("Starting video…");
        video = new VideoStream(host, Cfg.token(act), q, vid.getHolder().getSurface(), new VideoStream.Events() {
            @Override
            public void onInfo(final int pw, final int ph, final int vw, final int vh) {
                act.ui.post(() -> {
                    if (gen == videoGen) {
                        vid.setPhoneSize(pw, ph);
                        vid.setAspect(vw, vh);
                    }
                });
            }

            @Override
            public void onFrame() {
                act.ui.post(() -> {
                    if (gen == videoGen) {
                        gotFrame = true;
                        note.setVisibility(View.GONE);
                        status.setText("Live video, " + QUAL[quality].toLowerCase() + ". Drag on the picture to control the phone.");
                    }
                });
            }

            @Override
            public void onStats(final int fps, final int kbps) {
                act.ui.post(() -> {
                    if (gen == videoGen && gotFrame && status != null) {
                        status.setText("Live video, " + QUAL[quality].toLowerCase() + " \u00b7 " + fps + " fps \u00b7 "
                                + kbps + " kbit/s. Drag on the picture to control the phone.");
                    }
                });
            }

            @Override
            public void onEnd(final String why, final boolean screenOff) {
                act.ui.post(() -> {
                    if (gen != videoGen) {
                        return;
                    }
                    video = null;
                    if (!gotFrame && !screenOff) {
                        fallBack(why);
                        return;
                    }
                    showNote(screenOff ? why + "\nTap Wake." : why + "\nReconnecting…");
                    status.setText(why);
                    act.ui.postDelayed(() -> {
                        if (shown && mode == 0 && surfaceReady && video == null && gen == videoGen) {
                            startVideo();
                        }
                    }, 2000);
                });
            }
        });
        video.start();
        act.ui.postDelayed(() -> {
            if (gen == videoGen && !gotFrame && video != null) {
                stopVideo();
                fallBack("no picture after 8 seconds");
            }
        }, 8000);
    }

    private void stopVideo() {
        videoGen++;
        if (video != null) {
            video.stop();
            video = null;
        }
    }

    /** Video would not start here: use pictures for now (the saved choice is left alone). */
    private void fallBack(String why) {
        fellBack = true;
        mode = 1;
        paintChips();
        status.setText("Video didn't start (" + why + "). Using pictures.");
        applyMode();
    }

    // ------------------------------------------------------------ pictures mode

    private static final class Frame {
        Bitmap bmp;
        int w;
        int h;
        boolean off;
        String note = "";
    }

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            Handler h = bg;
            if (h == null || !polling) {
                return;
            }
            final Frame f = fetch();
            act.ui.post(() -> show(f));
            if (polling && bg == h) {
                h.postDelayed(this, f.bmp != null ? 40 : (f.off ? 1200 : 2000));
            }
        }
    };

    private void startPictures() {
        stopPictures();
        polling = true;
        worker = new HandlerThread("linkmeter-screen");
        worker.start();
        bg = new Handler(worker.getLooper());
        bg.post(loop);
    }

    private void stopPictures() {
        polling = false;
        Handler h = bg;
        bg = null;
        if (h != null) {
            h.removeCallbacksAndMessages(null);
        }
        if (worker != null) {
            worker.quitSafely();
            worker = null;
        }
    }

    private Frame fetch() {
        Frame f = new Frame();
        String host = Net.phoneHost(act);
        if (host.isEmpty()) {
            f.note = "The tablet is not on the phone's hotspot.";
            return f;
        }
        String target = "/screen/frame?w=" + PIC_W[quality] + "&q=" + PIC_Q[quality];
        try (Client.Stream st = Client.get(host, target, Cfg.token(act), 3000, 10000)) {
            byte[] data = readAll(st);
            if (st.code != 200) {
                f.note = errText(data);
                return f;
            }
            if (data.length > 0 && data[0] == '{') {
                f.off = true;
                f.note = "The phone's screen is off.\nTap Wake.";
                return f;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            f.bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            String[] wh = st.size.split("x");
            if (f.bmp == null || wh.length != 2) {
                f.bmp = null;
                f.note = "The phone sent a picture this tablet couldn't read.";
                return f;
            }
            f.w = Integer.parseInt(wh[0].trim());
            f.h = Integer.parseInt(wh[1].trim());
        } catch (IOException | NumberFormatException e) {
            f.bmp = null;
            f.note = "Couldn't reach the phone: " + e.getMessage();
        }
        return f;
    }

    private static byte[] readAll(Client.Stream st) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(st.length > 0 ? (int) Math.min(st.length, 1 << 20) : 4096);
        byte[] buf = new byte[8192];
        int r;
        while ((r = st.in.read(buf)) > 0) {
            bo.write(buf, 0, r);
            if (bo.size() > (1 << 20)) {
                throw new IOException("picture too big");
            }
        }
        return bo.toByteArray();
    }

    private static String errText(byte[] data) {
        String s = new String(data, StandardCharsets.UTF_8);
        try {
            return new JSONObject(s).optString("err", s);
        } catch (JSONException e) {
            return s;
        }
    }

    private void show(Frame f) {
        if (pics == null || !polling) {
            return;
        }
        if (f.bmp != null) {
            pics.setFrame(f.bmp, f.w, f.h);
            note.setVisibility(View.GONE);
            status.setText((fellBack ? "Pictures (video was not available). " : "Pictures. ")
                    + "Drag on the picture to control the phone.");
        } else {
            showNote(f.note);
            status.setText(f.off ? "The phone's screen is off." : f.note);
        }
    }

    // ------------------------------------------------------------ touch and keys

    private void connectCtl() {
        if ((ctl != null && !ctl.isDead()) || ctlTrying) {
            return;
        }
        if (SystemClock.elapsedRealtime() - ctlFailedAt < 5000) {
            return; // it just failed: don't hammer a phone whose touch helper will not start
        }
        final String host = Net.phoneHost(act);
        if (host.isEmpty()) {
            return;
        }
        ctlTrying = true;
        new Thread(() -> {
            CtlChannel c = null;
            String err = "";
            try {
                c = CtlChannel.open(host, Cfg.token(act));
            } catch (IOException e) {
                err = String.valueOf(e.getMessage());
            }
            final CtlChannel fc = c;
            final String fe = err;
            act.ui.post(() -> {
                ctlTrying = false;
                if (!shown) {
                    if (fc != null) {
                        fc.close();
                    }
                    return;
                }
                CtlChannel old = ctl;
                ctl = fc;
                if (old != null && old != fc) {
                    old.close(); // the dead one this replaces
                }
                if (fc == null) {
                    ctlFailedAt = SystemClock.elapsedRealtime();
                    if (status != null) {
                        status.setText("Touch is in slower mode (" + fe + ")");
                    }
                }
            });
        }, "linkmeter-ctl").start();
    }

    /** One touch event, in the phone's pixels. action: 0 down, 1 move, 2 up. */
    private void touch(int action, int x, int y) {
        if (action == 0) {
            fbX = x;
            fbY = y;
            fbT = SystemClock.elapsedRealtime();
            CtlChannel c = ctl;
            gestureOnCtl = c != null && !c.isDead();
            if (!gestureOnCtl && shown) {
                connectCtl(); // the live channel is gone or never started: try again for the next gesture
            }
        }
        if (gestureOnCtl) {
            // A gesture stays on the channel it started on. If the channel dies half way, the phone
            // lifts the finger itself; nothing is replayed here (a replay would tap at old positions).
            CtlChannel c = ctl;
            if (c != null && !c.isDead()) {
                c.send((action == 0 ? "d " : (action == 1 ? "m " : "u ")) + x + " " + y);
            }
            if (action == 2) {
                gestureOnCtl = false;
            }
            return;
        }
        if (action != 2) {
            return;
        }
        // No live channel: send one finished tap, long press or swipe when the finger lifts.
        long dt = SystemClock.elapsedRealtime() - fbT;
        if (Math.hypot(x - fbX, y - fbY) < 24) {
            if (dt > 500) {
                post("/screen/swipe?x1=" + x + "&y1=" + y + "&x2=" + x + "&y2=" + y + "&ms=" + Math.min(dt, 2500));
            } else {
                post("/screen/tap?x=" + x + "&y=" + y);
            }
        } else {
            post("/screen/swipe?x1=" + fbX + "&y1=" + fbY + "&x2=" + x + "&y2=" + y + "&ms="
                    + Math.max(80, Math.min(dt, 1200)));
        }
    }

    private void post(String target) {
        Call.run(act, "POST", target, 15000, (ok, text, json) -> {
            if (!ok && status != null) {
                status.setText(text);
            }
        });
    }

    private void key(int code) {
        CtlChannel c = ctl;
        if (c != null && !c.isDead()) {
            c.send("k " + code);
        } else {
            post("/screen/key?code=" + code);
        }
    }

    private void wake() {
        woke = true;
        post("/screen/on");
    }

    private void askPin() {
        final EditText pin = new EditText(act);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        pin.setSingleLine(true);
        pin.setHint("PIN");
        new AlertDialog.Builder(act)
                .setTitle("Unlock the phone")
                .setMessage("Type the phone's PIN. It is sent once to the phone and never saved. "
                        + "After several wrong tries Android makes you wait.")
                .setView(pin)
                .setPositiveButton("Unlock", (d, w) -> {
                    String p = pin.getText().toString();
                    pin.setText("");
                    woke = true;
                    unlock(p);
                })
                .setNegativeButton("Cancel", (d, w) -> pin.setText(""))
                .show();
    }

    private void unlock(final String pin) {
        status.setText("Entering the PIN…");
        new Thread(() -> {
            byte[] bytes = pin.getBytes(StandardCharsets.UTF_8);
            String msg;
            try {
                String host = Net.phoneHost(act);
                if (host.isEmpty()) {
                    throw new IOException("The tablet is not on the phone's hotspot.");
                }
                try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
                    Client.Resp r = Client.put(host, "/screen/pin", Cfg.token(act), in, bytes.length, null);
                    msg = r.body;
                    try {
                        JSONObject j = new JSONObject(r.body);
                        msg = j.has("err") ? j.optString("err") : j.optString("out", r.body);
                    } catch (JSONException plain) {
                        // not JSON: show it as it came
                    }
                }
            } catch (IOException e) {
                msg = "Couldn't reach the phone: " + e.getMessage();
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
            final String fMsg = msg;
            act.ui.post(() -> {
                if (status != null) {
                    status.setText(fMsg);
                }
            });
        }, "linkmeter-pin").start();
    }
}
