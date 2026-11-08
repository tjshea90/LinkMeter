package com.tj.linkmeter;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * See and control the phone's screen from the tablet.
 *
 * Layout (see RemoteLayout): the picture in the middle and a column of buttons on each side, never over
 * the picture. Typing opens the tablet's keyboard without shrinking the picture. The options (picture quality,
 * bitrate, decoder, what the phone does while you use this) are in the Options panel.
 *
 * Video mode: the phone's hardware encoder streams H.264 and this tablet's hardware decoder shows it. When
 * the phone's screen stops changing, a sharp still picture is shown, so a still screen never depends on
 * the decoder letting go of its last pictures. Pictures mode: a small JPEG of the screen, one after
 * another. Touches travel over one long connection as they happen; if that cannot start, taps and swipes
 * are sent one at a time instead. Nothing runs unless this tab is open.
 */
final class ScreenTab extends Tab {
    private static final int BAR_BG = 0xFF0B0D11;
    private static final int STILL_IDLE_MS = 450;

    private ScreenPrefs prefs;

    // views
    private LinearLayout root;
    private LinearLayout header;
    private TextView status;
    private Button modeChip;
    private Button qualChip;
    private RemoteLayout remote;
    private FrameLayout stage;
    private PhoneScreenView pics;
    private AspectSurfaceView vid;
    private PhoneScreenView still;
    private TextView note;
    private TypingView typingView;
    private FrameLayout overlay;
    private FrameLayout rightBar;
    private BarColumn rightNormal;
    private BarColumn rightTyping;
    private BarButton keysBtn;
    private BarButton powerBtn;
    private BarButton unlockBtn;
    private BarButton fullBtn;
    private TextView hud;
    private AdvancedPanel advanced;

    // state
    private boolean shown;
    private boolean surfaceReady;
    private boolean fullscreen;
    private boolean typing;
    private boolean fellBack;
    private boolean tuneOff;
    private boolean woke;
    private boolean waitingForScreen;
    private int mode;
    private boolean phoneOn = true;
    private boolean phoneLocked;
    /** The phone's app version from its last answer: null = it has not answered yet, "" = an older phone that does not say. */
    private String phoneVersion;
    private int failures;
    private String statusMsg = "Connecting…";
    private String profileText = "";
    private String diagText = "";
    private String lastError = "";
    private String ctlText = "not connected yet";
    private int fps;
    private int kbps;
    private int peak;
    /** Times this visit that the video arrived damaged (a picture was cut) and was restarted from a keyframe. */
    private int resyncs;
    private int damageStreak;
    private long lastDamageAt;
    private boolean quickResync;

    // video
    private VideoStream video;
    private volatile int videoGen;
    private boolean gotFrame;
    private volatile int fed;
    private volatile long lastPacketAt;
    private volatile int packetSeq;
    private volatile boolean stillVisible;
    private volatile long stillHideAt;
    private volatile int stillShownSeq;
    private int stillSeq = -1;
    private boolean stillBusy;

    // pictures
    private HandlerThread worker;
    private volatile Handler bg;
    private volatile boolean polling;

    // touch and keys
    private CtlChannel ctl;
    private boolean ctlTrying;
    private long ctlFailedAt;
    private boolean gestureOnCtl;
    private boolean swallow;
    private int fbX;
    private int fbY;
    private long fbT;
    private final StringBuilder pendingText = new StringBuilder();

    ScreenTab(MainActivity a) {
        super(a);
    }

    @Override
    String title() {
        return "Screen";
    }

    // ------------------------------------------------------------ layout

    @Override
    View build() {
        prefs = new ScreenPrefs(act);
        mode = prefs.mode();
        fellBack = false;
        tuneOff = false;
        resyncs = 0;
        damageStreak = 0;
        quickResync = false;
        surfaceReady = false;
        fullscreen = false;
        typing = false;
        waitingForScreen = false;
        failures = 0;
        advanced = null;

        // ---- the picture
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
        still = new PhoneScreenView(act);
        still.setListener(this::touch);
        still.setVisibility(View.GONE);
        note = Ui.text(act, "", 15, Ui.FG, false);
        note.setGravity(Gravity.CENTER);
        note.setBackgroundColor(0xB0000000);
        note.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10));
        note.setVisibility(View.GONE);
        typingView = new TypingView(act);
        typingView.setListener(new TypingView.Listener() {
            @Override
            public void text(String s) {
                sendText(s);
            }

            @Override
            public void key(int keyCode) {
                sendKey(keyCode);
            }
        });
        overlay = new FrameLayout(act);
        overlay.setBackgroundColor(0xB0000000);
        overlay.setClickable(true);
        overlay.setVisibility(View.GONE);

        stage.addView(pics, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stage.addView(vid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        stage.addView(still, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stage.addView(note, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        stage.addView(typingView, new FrameLayout.LayoutParams(1, 1));
        stage.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- the side bars
        int item = Ui.dp(act, 56);
        BarColumn leftCol = new BarColumn(act, item);
        leftCol.addView(new BarButton(act, Icons.Kind.RECENTS, "Apps", v -> sendKey(187)));
        leftCol.addView(new BarButton(act, Icons.Kind.HOME, "Home", v -> sendKey(3)));
        leftCol.addView(new BarButton(act, Icons.Kind.BACK, "Back", v -> sendKey(4)));
        leftCol.addView(new BarButton(act, Icons.Kind.VOL_UP, "", v -> sendKey(24)));
        leftCol.addView(new BarButton(act, Icons.Kind.VOL_DOWN, "", v -> sendKey(25)));
        hud = Ui.text(act, "", 9, Ui.DIM, false);
        hud.setGravity(Gravity.CENTER);
        FrameLayout leftBar = new FrameLayout(act);
        leftBar.setBackgroundColor(BAR_BG);
        leftBar.addView(leftCol, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        leftBar.addView(hud, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        rightNormal = new BarColumn(act, item);
        keysBtn = new BarButton(act, Icons.Kind.KEYBOARD, "Type", v -> toggleTyping());
        powerBtn = new BarButton(act, Icons.Kind.POWER, "Sleep", v -> togglePower());
        unlockBtn = new BarButton(act, Icons.Kind.LOCK, "Unlock", v -> showPin());
        fullBtn = new BarButton(act, Icons.Kind.FULL, "Full", v -> toggleFullscreen());
        rightNormal.addView(keysBtn);
        rightNormal.addView(powerBtn);
        rightNormal.addView(unlockBtn);
        rightNormal.addView(new BarButton(act, Icons.Kind.SLIDERS, "Options", v -> showAdvanced()));
        rightNormal.addView(fullBtn);

        rightTyping = new BarColumn(act, item);
        rightTyping.addView(new BarButton(act, Icons.Kind.CLOSE, "Done", v -> endTyping()));
        rightTyping.addView(new BarButton(act, Icons.Kind.ENTER, "", v -> sendKey(66)));
        rightTyping.addView(new BarButton(act, Icons.Kind.BACKSPACE, "", v -> sendKey(67)));
        rightTyping.addView(new BarButton(act, Icons.Kind.TAB, "", v -> sendKey(61)));
        rightTyping.addView(new BarButton(act, Icons.Kind.LEFT, "", v -> sendKey(21)));
        rightTyping.addView(new BarButton(act, Icons.Kind.UP, "", v -> sendKey(19)));
        rightTyping.addView(new BarButton(act, Icons.Kind.DOWN, "", v -> sendKey(20)));
        rightTyping.addView(new BarButton(act, Icons.Kind.RIGHT, "", v -> sendKey(22)));
        rightBar = new FrameLayout(act);
        rightBar.setBackgroundColor(BAR_BG);
        rightBar.addView(rightNormal, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        remote = new RemoteLayout(act, stage, leftBar, rightBar);
        remote.setKeyboardListener(this::endTyping);

        // ---- the header (hidden in full screen and while typing)
        header = new LinearLayout(act);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(act, 8), Ui.dp(act, 4), Ui.dp(act, 8), Ui.dp(act, 4));
        modeChip = chip(v -> cycleMode());
        qualChip = chip(v -> cycleQuality());
        status = Ui.text(act, statusMsg, 11, Ui.DIM, false);
        status.setMaxLines(2);
        header.addView(modeChip);
        header.addView(qualChip);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sp.leftMargin = Ui.dp(act, 8);
        header.addView(status, sp);

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.addView(header);
        root.addView(remote, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        paintChips();
        return root;
    }

    private Button chip(View.OnClickListener l) {
        Button b = Ui.button(act, "", l);
        b.setTextSize(11);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(Ui.dp(act, 10), Ui.dp(act, 6), Ui.dp(act, 10), Ui.dp(act, 6));
        return b;
    }

    private void paintChips() {
        if (modeChip != null) {
            modeChip.setText(mode == 0 ? "Live video" : "Still pictures");
            qualChip.setText(ScreenPrefs.PRESETS[prefs.preset()]);
        }
    }

    private void cycleMode() {
        mode = 1 - mode;
        prefs.setMode(mode);
        fellBack = false;
        tuneOff = false;
        paintChips();
        if (shown) {
            applyMode();
        }
    }

    private void cycleQuality() {
        int p = prefs.preset();
        prefs.setPreset(p >= 3 ? 0 : p + 1);
        paintChips();
        if (shown) {
            applyMode();
        }
    }

    private void paintBars() {
        if (powerBtn != null) {
            powerBtn.set(Icons.Kind.POWER, phoneOn ? "Sleep" : "Wake");
            unlockBtn.setActive(phoneLocked && phoneOn);
        }
    }

    private void setStatus(String s) {
        statusMsg = s;
        if (status != null) {
            status.setText(s);
        }
    }

    private void showNote(String s) {
        if (note != null) {
            note.setText(s);
            note.setVisibility(View.VISIBLE);
        }
    }

    private void paintLive() {
        if (mode == 0 && gotFrame) {
            setStatus("Live video · " + ScreenPrefs.PRESETS[prefs.preset()]
                    + (prefs.showStats() ? " · " + fps + " fps · " + kbps + " kbit/s (peak " + peak + ")" : ""));
            if (hud != null) {
                hud.setText(prefs.showStats() && !typing ? fps + " fps\n" + kbps + " k" : "");
            }
        }
    }

    // ------------------------------------------------------------ overlays, full screen, back

    private void showOverlay(View v, boolean centered) {
        overlay.removeAllViews();
        overlay.addView(v, centered
                ? new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                : new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setVisibility(View.VISIBLE);
        updateBack();
    }

    private void hideOverlay() {
        if (overlay != null) {
            overlay.setVisibility(View.GONE);
            overlay.removeAllViews();
        }
        advanced = null;
        updateBack();
    }

    private void updateBack() {
        boolean any = typing || fullscreen || (overlay != null && overlay.getVisibility() == View.VISIBLE);
        act.backHandler = any ? this::onBack : null;
    }

    private void onBack() {
        if (overlay != null && overlay.getVisibility() == View.VISIBLE) {
            if (advanced != null) {
                advanced.close();
            } else {
                hideOverlay();
            }
        } else if (typing) {
            endTyping();
        } else if (fullscreen) {
            exitFullscreen();
        }
    }

    private void toggleFullscreen() {
        if (fullscreen) {
            exitFullscreen();
        } else {
            fullscreen = true;
            act.setFullscreen(true);
            header.setVisibility(View.GONE);
            fullBtn.set(Icons.Kind.EXIT_FULL, "Exit");
            updateBack();
        }
    }

    private void exitFullscreen() {
        if (!fullscreen) {
            return;
        }
        fullscreen = false;
        act.setFullscreen(false);
        if (header != null && !typing) {
            header.setVisibility(View.VISIBLE);
        }
        if (fullBtn != null) {
            fullBtn.set(Icons.Kind.FULL, "Full");
        }
        updateBack();
    }

    // ------------------------------------------------------------ typing

    private void toggleTyping() {
        if (typing) {
            endTyping();
        } else {
            startTyping();
        }
    }

    private void startTyping() {
        if (typing) {
            return;
        }
        typing = true;
        remote.setTyping(true);
        act.setTabBarVisible(false);
        header.setVisibility(View.GONE);
        rightBar.removeAllViews();
        rightBar.addView(rightTyping, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        keysBtn.setActive(true);
        if (hud != null) {
            hud.setText("");
        }
        typingView.requestFocus();
        final InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            typingView.post(() -> imm.showSoftInput(typingView, 0));
        }
        updateBack();
    }

    private void endTyping() {
        if (!typing) {
            return;
        }
        typing = false;
        InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(typingView.getWindowToken(), 0);
        }
        typingView.clearFocus();
        remote.setTyping(false);
        act.setTabBarVisible(true);
        if (!fullscreen) {
            header.setVisibility(View.VISIBLE);
        }
        rightBar.removeAllViews();
        rightBar.addView(rightNormal, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        keysBtn.setActive(false);
        paintLive();
        updateBack();
    }

    // ------------------------------------------------------------ lifecycle

    /** The tablet's own screen stays on and bright while this tab is shown (nobody touches it while they watch the phone). */
    private void applyAwake() {
        act.setHoldAwake(shown && prefs != null && prefs.tabletAwake());
    }

    @Override
    void onShow() {
        shown = true;
        applyAwake();
        connectCtl();
        act.ui.removeCallbacks(statePoll);
        act.ui.post(statePoll);
        act.ui.removeCallbacks(stillTick);
        act.ui.postDelayed(stillTick, 250);
        beginProfile();
        applyMode();
    }

    @Override
    void onHide() {
        shown = false;
        applyAwake();
        act.ui.removeCallbacks(statePoll);
        act.ui.removeCallbacks(stillTick);
        stopStreams();
        CtlChannel c = ctl;
        ctl = null;
        gestureOnCtl = false;
        if (c != null) {
            c.close();
        }
        endTyping();
        if (overlay != null) {
            hideOverlay();
        }
        exitFullscreen();
        updateBack();
        if (prefs != null) {
            endProfile(false);
            if (woke && prefs.sleepOnLeave()) {
                post("/screen/off");
            }
        }
        woke = false;
    }

    private void applyMode() {
        stopStreams();
        if (note == null) {
            return;
        }
        note.setVisibility(View.GONE);
        hideStill();
        boolean v = mode == 0;
        vid.setVisibility(v ? View.VISIBLE : View.GONE);
        pics.setVisibility(v ? View.GONE : View.VISIBLE);
        if (hud != null) {
            hud.setText("");
        }
        if (!shown) {
            return;
        }
        if (v) {
            setStatus("Starting video…");
            if (surfaceReady) {
                startVideo();
            }
        } else {
            setStatus("Starting pictures…");
            startPictures();
        }
    }

    private void stopStreams() {
        stopVideo();
        stopPictures();
    }

    // ------------------------------------------------------------ the phone's state and its power options

    private final Runnable statePoll = new Runnable() {
        @Override
        public void run() {
            if (!shown) {
                return;
            }
            Call.run(act, "GET", "/screen/state", 4000, (ok, text, json) -> {
                if (!shown) {
                    return;
                }
                if (ok && json != null) {
                    phoneOn = json.optBoolean("on", true);
                    phoneLocked = json.optBoolean("locked", false);
                    phoneVersion = json.optString("vn", "");
                    paintBars();
                    if (phoneOn && waitingForScreen && mode == 0 && video == null && surfaceReady) {
                        waitingForScreen = false;
                        if (note != null) {
                            note.setVisibility(View.GONE);
                        }
                        startVideo();
                    }
                }
                act.ui.postDelayed(this, 3000);
            });
        }
    };

    /**
     * The calls that start and end the phone's power options go through one queue, in order: leaving the
     * tab and coming straight back must not let "end" overtake "begin" and switch the dimming off again.
     */
    private static final ThreadPoolExecutor PROFILE_QUEUE =
            new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());

    private interface ProfileDone {
        void done(boolean ok, String text);
    }

    private void profileCall(final String method, final String target, final ProfileDone d) {
        final String host = Net.phoneHost(act);
        final String token = Cfg.token(act);
        PROFILE_QUEUE.execute(() -> {
            boolean ok = false;
            String text;
            if (host.isEmpty()) {
                text = "The tablet is not on the phone's hotspot.";
            } else {
                try {
                    Client.Resp r = Client.call(host, method, target, token, 2500, 30000);
                    text = r.body;
                    try {
                        JSONObject j = new JSONObject(r.body);
                        text = j.has("err") ? j.optString("err") : j.optString("out", r.body);
                        ok = r.code == 200 && !j.has("err");
                    } catch (JSONException plain) {
                        // not JSON: show it as it came
                    }
                } catch (IOException e) {
                    text = "Couldn't reach the phone: " + e.getMessage();
                }
            }
            final boolean fOk = ok;
            final String fText = text;
            act.ui.post(() -> d.done(fOk, fText));
        });
    }

    /** Asks the phone to keep its screen on a while and dim it, as set in Options. */
    private void beginProfile() {
        if (prefs.keepIndex() == 0 && prefs.dimIndex() == ScreenPrefs.DIM_NAMES.length - 1) {
            profileText = "Not changing the phone's screen settings (turned off in Options).";
            return;
        }
        profileCall("POST", "/screen/profile?" + prefs.profileQuery(), (ok, text) -> {
            profileText = ok ? text : "Couldn't change the phone's screen settings: " + text;
            if (advanced != null) {
                advanced.setPhoneText(profileText);
            }
        });
    }

    /** Asks the phone to put its screen settings back. */
    private void endProfile(final boolean show) {
        profileCall("POST", "/screen/profile?end=1", (ok, text) -> {
            profileText = ok ? text : "Couldn't put the phone's screen settings back: " + text;
            if (show) {
                Toast.makeText(act, profileText, Toast.LENGTH_LONG).show();
            }
            if (advanced != null) {
                advanced.setPhoneText(profileText);
            }
        });
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
        fed = 0;
        stillSeq = -1;
        final int gen = ++videoGen;
        String q = "w=" + prefs.vidWidth() + "&br=" + (prefs.vidKbps() * 1000L);
        boolean tune = prefs.lowDelay() == 1 || (prefs.lowDelay() == 0 && !tuneOff);
        setStatus("Starting video…");
        video = new VideoStream(host, Cfg.token(act), q, vid.getHolder().getSurface(), prefs.decoder(), tune,
                new VideoStream.Events() {
                    @Override
                    public void onInfo(final int pw, final int ph, final int vw, final int vh) {
                        act.ui.post(() -> {
                            if (gen == videoGen) {
                                // both views fit the phone's own shape, so touches map exactly
                                vid.setPhoneSize(pw, ph);
                                vid.setAspect(pw, ph);
                            }
                        });
                    }

                    @Override
                    public void onFrame() {
                        act.ui.post(() -> {
                            if (gen == videoGen) {
                                gotFrame = true;
                                failures = 0;
                                note.setVisibility(View.GONE);
                                paintLive();
                            }
                        });
                    }

                    @Override
                    public void onPacket() {
                        if (gen != videoGen) {
                            return; // a stream that has already been replaced
                        }
                        fed++;
                        long now = SystemClock.elapsedRealtime();
                        lastPacketAt = now;
                        packetSeq++;
                        if (stillVisible) {
                            // The screen is moving again. Keep the sharp picture up for a moment so the video has
                            // caught up before it takes over, instead of flashing back to its older, blurrier frame.
                            if (stillHideAt == 0) {
                                stillHideAt = now + 120;
                            }
                            if (now >= stillHideAt || packetSeq - stillShownSeq >= 4) {
                                stillVisible = false;
                                act.ui.post(() -> hideStill());
                            }
                        }
                    }

                    @Override
                    public void onStats(int f, int k) {
                        onStats(f, k, k);
                    }

                    @Override
                    public void onStats(final int f, final int k, final int p) {
                        act.ui.post(() -> {
                            if (gen == videoGen) {
                                fps = f;
                                kbps = k;
                                peak = p;
                                paintLive();
                            }
                        });
                    }

                    @Override
                    public void onDiag(final String text) {
                        act.ui.post(() -> {
                            if (gen == videoGen) {
                                diagText = text;
                            }
                        });
                    }

                    @Override
                    public void onDamaged(final String why) {
                        act.ui.post(() -> {
                            if (gen != videoGen) {
                                return;
                            }
                            resyncs++;
                            long now = SystemClock.elapsedRealtime();
                            damageStreak = now - lastDamageAt < 8000 ? damageStreak + 1 : 1;
                            lastDamageAt = now;
                            quickResync = true;
                            showStillNow(); // covers the damaged picture with a sharp one while the video restarts
                        });
                    }

                    @Override
                    public void onEnd(final String why, final boolean screenOff) {
                        act.ui.post(() -> videoEnded(gen, why, screenOff));
                    }
                });
        video.start();
        act.ui.postDelayed(() -> noPictureCheck(gen, false), 8000);
    }

    private void videoEnded(int gen, String why, boolean screenOff) {
        if (gen != videoGen) {
            return;
        }
        video = null;
        lastError = why;
        if (screenOff) {
            phoneOn = false;
            waitingForScreen = true;
            paintBars();
            showNote("The phone's screen is off.\nTap Wake.");
            setStatus("The phone's screen is off.");
            return;
        }
        final boolean damaged = quickResync;
        quickResync = false;
        if (damaged) {
            // A picture was cut on the way: what the decoder shows is wrong until the next keyframe, so start the
            // video again (the phone sends a keyframe first). Four times in a row means this will not get better.
            if (damageStreak >= 4) {
                damageStreak = 0;
                fallBack("the video keeps arriving damaged");
                return;
            }
            setStatus("Picture damaged: restarting the video for a clean one…");
            final int gd = gen;
            act.ui.postDelayed(() -> {
                if (shown && mode == 0 && surfaceReady && video == null && gd == videoGen) {
                    startVideo();
                }
            }, 100);
            return;
        }
        if (!gotFrame) {
            fallBack(why);
            return;
        }
        // It was working: reconnect quietly. The last picture stays on the screen meanwhile.
        failures++;
        setStatus("Video interrupted (" + why + "). Reconnecting…");
        if (failures >= 3) {
            showNote(why + "\nReconnecting…");
        }
        final int g = gen;
        act.ui.postDelayed(() -> {
            if (shown && mode == 0 && surfaceReady && video == null && g == videoGen) {
                startVideo();
            }
        }, failures >= 3 ? 2000 : 600);
    }

    /** Checks that a picture really came out of the decoder; if not, finds out why and tries the next thing. */
    private void noPictureCheck(int gen, boolean second) {
        if (gen != videoGen || gotFrame || video == null || !shown) {
            return;
        }
        if (fed >= 6) {
            // pictures arrive but the decoder shows none
            if (prefs.lowDelay() == 0 && !tuneOff) {
                tuneOff = true;
                lastError = "The decoder showed nothing with the low-delay tuning on: trying again without it.";
                startVideo();
                return;
            }
            stopVideo();
            fallBack("the decoder shows no picture");
            return;
        }
        if (!second) {
            // Few pictures came: the phone's screen is probably still. The still picture covers that; give it longer.
            act.ui.postDelayed(() -> noPictureCheck(gen, true), 12000);
            return;
        }
        if (fed == 0 || !prefs.stillImage()) {
            stopVideo();
            fallBack(fed == 0 ? "no video from the phone" : "no picture on screen");
        }
    }

    private void stopVideo() {
        videoGen++;
        if (video != null) {
            video.stop();
            video = null;
        }
        stillBusy = false;
    }

    /** Video would not start here: use pictures for now (the saved choice is left alone). */
    private void fallBack(String why) {
        fellBack = true;
        lastError = why;
        mode = 1;
        paintChips();
        applyMode();
        setStatus("Video didn't start (" + why + "). Using pictures for now.");
    }

    // ------------------------------------------------------------ the sharp still picture

    private final Runnable stillTick = new Runnable() {
        @Override
        public void run() {
            if (!shown) {
                return;
            }
            maybeStill();
            act.ui.postDelayed(this, 200);
        }
    };

    private void hideStill() {
        stillVisible = false;
        stillHideAt = 0;
        if (still != null) {
            still.setVisibility(View.GONE);
        }
    }

    /** Covers the video with a sharp picture of the phone's screen right now (while a damaged video restarts). */
    private void showStillNow() {
        if (mode != 0 || !prefs.stillImage() || stillBusy || !phoneOn || !shown) {
            return;
        }
        stillBusy = true;
        final String host = Net.phoneHost(act);
        final int w = Math.max(720, Math.min(1080, prefs.vidWidth()));
        new Thread(() -> {
            final Frame f = fetchFrame(host, w, 75);
            act.ui.post(() -> {
                stillBusy = false;
                if (!shown || mode != 0 || f.bmp == null) {
                    return;
                }
                still.setFrame(f.bmp, f.w, f.h);
                still.setVisibility(View.VISIBLE);
                stillHideAt = 0;
                stillShownSeq = packetSeq;
                stillVisible = true; // the live video takes over again when its pictures arrive (see onPacket)
            });
        }, "linkmeter-still").start();
    }

    /** When the phone's screen has stopped changing, shows one sharp picture of it over the video. */
    private void maybeStill() {
        if (mode != 0 || !prefs.stillImage() || stillBusy || !phoneOn || fed == 0 || video == null) {
            return;
        }
        long idle = SystemClock.elapsedRealtime() - lastPacketAt;
        if (idle < STILL_IDLE_MS || packetSeq == stillSeq) {
            return;
        }
        final int seq = packetSeq;
        stillSeq = seq; // one picture per quiet period
        stillBusy = true;
        final String host = Net.phoneHost(act);
        final int w = Math.max(720, Math.min(1080, prefs.vidWidth()));
        new Thread(() -> {
            final Frame f = fetchFrame(host, w, 75);
            act.ui.post(() -> {
                stillBusy = false;
                if (!shown || mode != 0 || f.bmp == null || seq != packetSeq) {
                    return; // the screen moved on while it was being fetched
                }
                still.setFrame(f.bmp, f.w, f.h);
                still.setVisibility(View.VISIBLE);
                if (!gotFrame) {
                    setStatus("Live video · showing a sharp picture until the phone's screen changes");
                }
                stillHideAt = 0;
                stillShownSeq = packetSeq;
                stillVisible = true;
            });
        }, "linkmeter-still").start();
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
            long t0 = SystemClock.elapsedRealtime();
            final Frame f = fetchFrame(Net.phoneHost(act), prefs.picWidth(), prefs.picQuality());
            act.ui.post(() -> show(f));
            if (polling && bg == h) {
                int fi = prefs.picFpsIndex();
                long gap = fi == 0 ? 0 : 1000L / ScreenPrefs.PIC_FPS[fi];
                long took = SystemClock.elapsedRealtime() - t0;
                h.postDelayed(this, f.bmp != null ? Math.max(40, gap - took) : (f.off ? 1200 : 2000));
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

    /** One picture of the phone's screen as a bitmap, or why there is none. Blocking: not for the UI thread. */
    private Frame fetchFrame(String host, int width, int quality) {
        Frame f = new Frame();
        if (host.isEmpty()) {
            f.note = "The tablet is not on the phone's hotspot.";
            return f;
        }
        String target = "/screen/frame?w=" + width + "&q=" + quality;
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
            phoneOn = true;
            pics.setFrame(f.bmp, f.w, f.h);
            note.setVisibility(View.GONE);
            setStatus((fellBack ? "Pictures (video was not available) · " : "Pictures · ")
                    + ScreenPrefs.PRESETS[prefs.preset()] + ". Drag on the picture to control the phone.");
        } else {
            if (f.off) {
                phoneOn = false;
                paintBars();
            }
            showNote(f.note);
            setStatus(f.off ? "The phone's screen is off." : f.note);
        }
    }

    // ------------------------------------------------------------ touch, keys and text

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
                    ctlText = "slower mode (" + fe + ")";
                    setStatus("Touch is in slower mode (" + fe + ")");
                } else {
                    ctlText = "live";
                    flushText(fc);
                }
            });
        }, "linkmeter-ctl").start();
    }

    /** One touch event, in the phone's pixels. action: 0 down, 1 move, 2 up. */
    private void touch(int action, int x, int y) {
        if (action == 0) {
            swallow = false;
            if (!phoneOn && prefs.tapToWake()) {
                swallow = true; // this touch only wakes the phone
                wake();
                phoneOn = true;
                paintBars();
                return;
            }
            fbX = x;
            fbY = y;
            fbT = SystemClock.elapsedRealtime();
            CtlChannel c = ctl;
            gestureOnCtl = c != null && !c.isDead();
            if (!gestureOnCtl && shown) {
                connectCtl(); // the live channel is gone or never started: try again for the next gesture
            }
        } else if (swallow) {
            if (action == 2) {
                swallow = false;
            }
            return;
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
                setStatus(text);
            }
        });
    }

    private void sendKey(int code) {
        CtlChannel c = ctl;
        if (c != null && !c.isDead()) {
            c.send("k " + code);
        } else {
            post("/screen/key?code=" + code);
            connectCtl();
        }
    }

    /** Sends typed text over the live channel (a phone key press per character); held back until it is up. */
    private void sendText(String s) {
        CtlChannel c = ctl;
        if (c != null && !c.isDead()) {
            sendChunks(c, s);
            return;
        }
        pendingText.append(s);
        if (pendingText.length() > 400) {
            pendingText.delete(0, pendingText.length() - 400);
        }
        connectCtl();
    }

    private void flushText(CtlChannel c) {
        if (pendingText.length() > 0) {
            String s = pendingText.toString();
            pendingText.setLength(0);
            sendChunks(c, s);
        }
    }

    /** "t" and the text as 4 hex digits per character, at most 60 characters per line. */
    private static void sendChunks(CtlChannel c, String s) {
        for (int i = 0; i < s.length(); i += 60) {
            String part = s.substring(i, Math.min(s.length(), i + 60));
            StringBuilder sb = new StringBuilder("t ");
            for (int j = 0; j < part.length(); j++) {
                sb.append(String.format(Locale.US, "%04x", (int) part.charAt(j)));
            }
            c.send(sb.toString());
        }
    }

    private void togglePower() {
        if (phoneOn) {
            post("/screen/off");
            woke = false;
            phoneOn = false;
        } else {
            wake();
            phoneOn = true;
        }
        paintBars();
    }

    private void wake() {
        woke = true;
        post("/screen/on");
    }

    // ------------------------------------------------------------ PIN and settings panels

    private void showPin() {
        if (!phoneOn) {
            wake();
            phoneOn = true;
            paintBars();
        }
        View pad = PinPad.build(act, new PinPad.Done() {
            @Override
            public void unlock(String pin) {
                hideOverlay();
                woke = true;
                sendPin(pin);
            }

            @Override
            public void cancel() {
                hideOverlay();
            }
        });
        showOverlay(pad, true);
    }

    private void sendPin(final String pin) {
        setStatus("Entering the PIN…");
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
                setStatus(fMsg);
                phoneLocked = !fMsg.startsWith("Unlocked") && !fMsg.contains("already unlocked");
                paintBars();
            });
        }, "linkmeter-pin").start();
    }

    private void showAdvanced() {
        if (advanced != null) {
            return;
        }
        advanced = new AdvancedPanel(act, prefs, new AdvancedPanel.Host() {
            @Override
            public void tabletAwakeChanged() {
                applyAwake();
            }

            @Override
            public void closed(boolean video, boolean power) {
                hideOverlay();
                applyAwake(); // "Reset all" may have changed the tablet's keep-awake choice
                if (power) {
                    beginProfile();
                }
                if (video) {
                    mode = prefs.mode();
                    fellBack = false;
                    tuneOff = false;
                    paintChips();
                    if (shown) {
                        applyMode();
                    }
                } else {
                    paintChips();
                }
            }

            @Override
            public void restorePhone() {
                endProfile(true);
            }

            @Override
            public String diagnostics() {
                return diagnosticsText();
            }

            @Override
            public String phoneText() {
                return profileText.isEmpty() ? "Asking the phone…" : profileText;
            }

            @Override
            public void copy(String text) {
                ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("LinkMeter", text));
                    Toast.makeText(act, "Copied.", Toast.LENGTH_SHORT).show();
                }
            }
        });
        showOverlay(advanced.view(), false);
        Call.run(act, "GET", "/screen/profile", 8000, (ok, text, json) -> {
            profileText = text;
            if (advanced != null) {
                advanced.setPhoneText(text);
            }
        });
    }

    /** Everything useful for a bug report, in one block. */
    private String diagnosticsText() {
        String version = "?";
        try {
            version = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // keep the question mark
        }
        List<String> decs = VideoStream.decoders();
        StringBuilder sb = new StringBuilder();
        sb.append("LinkMeter ").append(version).append(" on ").append(Build.MODEL).append(", Android ")
                .append(Build.VERSION.RELEASE).append('\n');
        sb.append("Phone app: ").append(phoneVersion == null ? "the phone has not answered yet"
                : phoneVersion.isEmpty() ? "version not reported (the phone has an older build than this tablet)"
                : phoneVersion.equals(version) ? phoneVersion + " (same as this tablet)"
                : phoneVersion + " (this tablet has " + version + ")").append('\n');
        sb.append("Mode: ").append(mode == 0 ? "live video" : "still pictures").append(fellBack ? " (fell back)" : "")
                .append(", preset ").append(ScreenPrefs.PRESETS[prefs.preset()])
                .append(": video ").append(prefs.vidWidth()).append(" px at ").append(prefs.vidKbps()).append(" kbit/s, pictures ")
                .append(prefs.picWidth()).append(" px quality ").append(prefs.picQuality()).append('\n');
        sb.append("Low-delay tuning: ").append(ScreenPrefs.FIX_NAMES[prefs.lowDelay()])
                .append(tuneOff ? " (switched off for this visit because the decoder showed nothing)" : "").append('\n');
        sb.append(diagText.isEmpty() ? "Stream: not started yet" : diagText).append('\n');
        sb.append("Now: ").append(fps).append(" fps, ").append(kbps).append(" kbit/s, peak ").append(peak).append(" kbit/s\n");
        sb.append("Touch channel: ").append(ctlText).append('\n');
        sb.append("Phone screen: ").append(phoneOn ? "on" : "off").append(phoneLocked ? ", locked" : "").append('\n');
        sb.append("Last video problem: ").append(lastError.isEmpty() ? "none" : lastError).append('\n');
        sb.append("Video restarts because a picture arrived damaged: ").append(resyncs).append('\n');
        sb.append("Phone settings: ").append(profileText).append('\n');
        sb.append("Decoders on this tablet: ").append(decs.isEmpty() ? "none found" : decs.toString());
        return sb.toString();
    }
}
