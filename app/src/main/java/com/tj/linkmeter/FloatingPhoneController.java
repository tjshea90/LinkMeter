package com.tj.linkmeter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import java.io.IOException;

/**
 * Tablet only: everything the floating phone does (docs/FLOATING_PHONE.md), apart from being a service. A small dark card drawn over every
 * app that pops up by itself when the phone rings or a call is on, shows who and how long, the headset's battery, a green Call / Answer and
 * a red End / Decline button, two Bluetooth buttons (the headset's link to the phone, and to this tablet) and a dial pad that opens and
 * closes; the user can drag it anywhere and it remembers the place. It is the phone's remote control and nothing more: the call is placed,
 * answered and ended by the phone through the same /call/* routes as the Calls tab (so the same guard refuses emergency numbers and
 * codes), and the call's sound is the headset's business with the phone.
 *
 * <p>Two hosts run it, and only one at a time: {@link FloatingPhoneService} draws it with Android's "Display over other apps"
 * ({@code TYPE_APPLICATION_OVERLAY}); {@link FloatingPhoneAccess} draws it as an accessibility overlay
 * ({@code TYPE_ACCESSIBILITY_OVERLAY}), which needs no such permission and so works on Android 10 Go tablets, where Android turns that
 * permission off. It holds the one {@link CallWatch} of the process like the app's screens do. What it shows and when is decided by
 * {@link FloatRules}; what is drawn is {@link MiniPhoneView}.
 */
final class FloatingPhoneController implements MiniPhoneView.Host {
    static final String ACTION_SHOW = "com.tj.linkmeter.FLOAT_SHOW";
    static final String ACTION_OFF = "com.tj.linkmeter.FLOAT_OFF";
    private static final long WARN_MS = 7_000;
    private static final long SAY_MS = 5_000;
    /** How long the card stays when the phone cannot be heard in the middle of a call. */
    private static final long OFFLINE_MS = 20_000;

    private static volatile FloatingPhoneController active;

    /** The controller that is running (the service's or the accessibility service's), or null. */
    static FloatingPhoneController active() {
        return active;
    }

    static boolean running() {
        return active != null;
    }

    /** Looks again at what to show (the app came to the front or went away, a setting changed). Does nothing when nothing runs. */
    static void poke() {
        final FloatingPhoneController s = active;
        if (s != null) {
            s.ui.post(s::refresh);
        }
    }

    /** "Show the phone now": keeps the card up until its cross is pressed. */
    static boolean showNow() {
        final FloatingPhoneController s = active;
        if (s == null) {
            return false;
        }
        s.ui.post(() -> s.command(ACTION_SHOW));
        return true;
    }

    private final Context ctx;
    private final int windowType;
    private final Runnable stopHost;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private MiniPhoneView view;
    private WindowManager.LayoutParams lp;
    private CallWatch watch;
    private TabletHeadset tablet;
    private boolean pinned;
    private boolean armed;
    private boolean phoneBusy;
    private boolean tabletBusy;
    /** Volume presses sent and not answered yet. */
    private int volPending;
    /** The volume the user switched the row to with its chip, or null: the call's while a call is on, else the media's. */
    private String volChosen;
    private boolean volCallWas;
    private String message = "";
    private boolean messageBad;
    private long endedAt;
    private long offlineSince;
    private long lastElapsed;
    private String lastCall = "";
    private MiniPhoneView.Model model = new MiniPhoneView.Model();
    private final CallWatch.Listener watchListener = w -> update();
    private final Runnable tabletListener = this::update;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (view != null) {
                tickText();
                ui.postDelayed(this, 1000);
            }
        }
    };
    private final Runnable disarm = () -> {
        armed = false;
        say("", false, 0);
    };
    private final Runnable clearSay = () -> say("", false, 0);
    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (watch != null) {
                watch.refresh(); // the tablet was asleep: a request left open may be dead; ask the phone at once, not after its timeout
            }
        }
    };

    /**
     * @param windowType {@code TYPE_APPLICATION_OVERLAY} or {@code TYPE_ACCESSIBILITY_OVERLAY}
     * @param stopHost ends the host (the foreground service stops itself; the accessibility service stays)
     */
    FloatingPhoneController(Context ctx, int windowType, Runnable stopHost) {
        this.ctx = ctx;
        this.windowType = windowType;
        this.stopHost = stopHost;
    }

    /** Starts listening to the phone's calls. Pair with {@link #stop}. */
    void start() {
        active = this;
        wm = ctx.getSystemService(WindowManager.class);
        watch = CallWatch.shared(ctx);
        watch.addListener(watchListener);
        watch.start();
        tablet = TabletHeadset.of(ctx); // listened to only while the card is up (show/hide)
        try {
            ctx.registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        } catch (RuntimeException ignored) {
            // the screen-on nudge is a convenience
        }
        update();
    }

    /** One of the host's commands: {@link #ACTION_SHOW} keeps the card up, {@link #ACTION_OFF} switches the floating phone off. */
    void command(String action) {
        if (ACTION_OFF.equals(action)) {
            Cfg.setFloatOn(ctx, false);
            FloatingPhone.sync(ctx);
            stopHost.run();
            return;
        }
        if (ACTION_SHOW.equals(action)) {
            pinned = true;
        }
        refresh();
    }

    /** Looks again, and makes sure the watch listens (calls may have started to work since). */
    void refresh() {
        if (watch != null) {
            watch.ensureRunning();
        }
        update();
    }

    void stop() {
        if (active == this) {
            active = null;
        }
        ui.removeCallbacksAndMessages(null);
        hide();
        if (watch != null) {
            watch.removeListener(watchListener);
            watch.stop();
        }
        try {
            ctx.unregisterReceiver(screenOn);
        } catch (RuntimeException ignored) {
            // it was not registered
        }
    }

    /** The screen turned: keep the card on it. */
    void configurationChanged() {
        ui.post(this::place);
    }

    // ------------------------------------------------------------------ what to show

    private static String leadName(Calls.Call c) {
        String n = c.name == null ? "" : c.name.trim();
        if (n.isEmpty() && (c.number == null || c.number.isEmpty())) {
            return c.incoming ? "Unknown caller" : "Phone call";
        }
        return CallRules.shown(n, c.number);
    }

    /** Looks at the phone's picture and the tablet's Bluetooth, and shows, changes or hides the card. */
    private void update() {
        final long now = SystemClock.elapsedRealtime();
        Calls.Call lead = watch.lead();
        boolean ok = watch.status() == CallWatch.OK;
        String state;
        if (ok) {
            offlineSince = 0;
            state = lead != null ? lead.state : "";
            if (!state.isEmpty()) {
                lastElapsed = 0;
                String st = CallRules.status(lead, watch.elapsed(lead));
                if (!st.equals("Incoming call") && !st.equals("Calling…")) {
                    lastElapsed = watch.elapsed(lead);
                }
                endedAt = 0;
            } else if (!lastCall.isEmpty()) {
                endedAt = now; // a call just ended: its length stays readable for a moment
                ui.postDelayed(this::update, FloatRules.LINGER_MS + 150);
            }
        } else {
            // The phone cannot be heard: that says nothing about the call. The card stays (its buttons off, "Can't reach the phone") for a
            // little while, then goes without claiming that the call ended.
            if (offlineSince == 0) {
                offlineSince = now;
                ui.postDelayed(this::update, OFFLINE_MS + 150);
            }
            state = !lastCall.isEmpty() && now - offlineSince < OFFLINE_MS ? lastCall : "";
        }
        lastCall = state;

        FloatRules.In in = new FloatRules.In();
        in.enabled = Cfg.floatOn(ctx);
        in.canDraw = FloatingPhone.canShow(ctx);
        in.pinned = pinned;
        in.padOpen = view != null && view.expanded();
        in.appInFront = FloatingPhone.appInFront();
        in.call = state;
        in.endedAt = endedAt;
        in.now = now;
        if (!in.canDraw) {
            stopHost.run(); // Android took the permission back: nothing can be drawn
            return;
        }
        if (!FloatRules.visible(in)) {
            hide();
            return;
        }
        show();
        buildModel(lead, ok, state, now);
        view.bind(model);
    }

    private void buildModel(Calls.Call lead, boolean ok, String state, long now) {
        MiniPhoneView.Model m = new MiniPhoneView.Model();
        Calls.Now n = watch.latest();
        m.call = state;
        m.offline = !ok;
        if (!state.isEmpty() && lead != null) {
            m.title = leadName(lead);
            m.subtitle = ok ? CallRules.status(lead, watch.elapsed(lead)) : FloatRules.idleLine(watch.status());
        } else if (!state.isEmpty()) {
            m.title = "Phone call";
            m.subtitle = FloatRules.idleLine(watch.status());
        } else if (endedAt > 0 && now - endedAt < FloatRules.LINGER_MS) {
            m.title = "Phone";
            m.subtitle = FloatRules.endedLine(lastElapsed);
        } else {
            m.title = "Phone";
            m.subtitle = FloatRules.idleLine(watch.status());
        }
        m.headsetOnPhone = ok && !n.headset.isEmpty();
        m.headsetName = n.headset;
        m.battery = n.headsetBattery;
        TabletHeadsetRules.Facts f = tablet.facts("", n.headset, m.headsetOnPhone);
        TabletHeadsetRules.Dev d = TabletHeadsetRules.pick(f);
        m.tabletOn = d != null && d.connected;
        m.tabletAble = f.hasBluetooth && f.bluetoothOn && d != null;
        if (!BatteryRules.known(m.battery) && m.tabletOn) {
            m.battery = BatteryRules.best(m.battery, tablet.battery(d.addr)); // the phone did not say: this tablet may know
        }
        m.batteryShown = m.headsetOnPhone || m.tabletOn;
        m.headsetName = m.headsetOnPhone ? n.headset : d != null ? d.name : "";
        m.phoneBusy = phoneBusy;
        m.tabletBusy = tabletBusy;
        boolean callOn = !state.isEmpty();
        if (callOn != volCallWas) {
            volCallWas = callOn;
            volChosen = null; // a call began or ended: the row goes back to the volume that matters now
        }
        m.volumeStream = volChosen != null ? volChosen : VolumeRules.defaultStream(callOn);
        m.volume = VolumeRules.row(n.volume, m.volumeStream, ok, m.headsetOnPhone, callOn);
        m.volumeShown = ok && m.headsetOnPhone && !n.volume.old;
        m.volumeBusy = volPending > 0;
        m.askFirst = Cfg.callsHeadsetAsk(ctx);
        m.armed = armed;
        m.message = message;
        m.messageIsBad = messageBad;
        model = m;
    }

    private void tickText() {
        Calls.Call lead = watch.lead();
        if (lead != null && watch.status() == CallWatch.OK) {
            model.subtitle = CallRules.status(lead, watch.elapsed(lead));
            view.bind(model);
        }
    }

    // ------------------------------------------------------------------ the window

    private int screenW() {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        return dm.widthPixels;
    }

    private int screenH() {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        return dm.heightPixels;
    }

    private void show() {
        if (view != null) {
            return;
        }
        view = new MiniPhoneView(ctx, this);
        lp = new WindowManager.LayoutParams(Ui.dp(ctx, MiniPhoneView.WIDTH_DP), WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        int x = Cfg.floatX(ctx);
        int y = Cfg.floatY(ctx);
        if (x < 0 || y < 0) {
            x = screenW(); // never moved: the right edge, a bit down (place() keeps it on the screen)
            y = screenH() / 6;
        }
        lp.x = x;
        lp.y = y;
        lp.setTitle("LinkMeter floating phone");
        try {
            wm.addView(view, lp);
        } catch (RuntimeException e) {
            view = null; // Android refused (the permission went): the next update stops the service
            return;
        }
        tablet.add(tabletListener); // the tablet's Bluetooth is listened to only while the card is up
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) {
                place(); // the card has its size (first layout) or the pad opened or closed: keep all of it on the screen
            }
        });
        ui.postDelayed(tick, 1000);
    }

    private void hide() {
        ui.removeCallbacks(tick);
        if (view == null) {
            return;
        }
        try {
            wm.removeView(view);
        } catch (RuntimeException ignored) {
            // it was not attached
        }
        view = null;
        armed = false;
        tablet.remove(tabletListener);
    }

    /** Puts the card where it is allowed to be: all of it on the screen. */
    private void place() {
        if (view == null) {
            return;
        }
        int w = view.getWidth() > 0 ? view.getWidth() : lp.width;
        int h = view.getHeight() > 0 ? view.getHeight() : Ui.dp(ctx, 110);
        int[] c = FloatRules.clamp(lp.x, lp.y, w, h, screenW(), screenH(), Ui.dp(ctx, 6));
        lp.x = c[0];
        lp.y = c[1];
        apply();
    }

    private void apply() {
        if (view == null) {
            return;
        }
        try {
            wm.updateViewLayout(view, lp);
        } catch (RuntimeException ignored) {
            // the view is going away
        }
    }

    // ------------------------------------------------------------------ the Host: what the user presses

    private void say(String text, boolean bad, long forMs) {
        message = text == null ? "" : text;
        messageBad = bad;
        ui.removeCallbacks(clearSay);
        if (forMs > 0 && !message.isEmpty()) {
            ui.postDelayed(clearSay, forMs);
        }
        update();
    }

    private void afterRequest(Calls.Result r, IOException e, String okSay) {
        String problem = e != null ? TextsTab.explain(e) : r != null && !r.ok ? r.why : "";
        if (!problem.isEmpty()) {
            say(problem, true, SAY_MS * 2);
        } else if (!okSay.isEmpty()) {
            say(okSay, false, SAY_MS);
        }
    }

    @Override
    public void answer() {
        watch.answer((r, e) -> afterRequest(r, e, ""));
    }

    @Override
    public void hangup() {
        watch.hangup((r, e) -> afterRequest(r, e, ""));
    }

    @Override
    public void dial(String number) {
        armed = false;
        watch.dial(number, (r, e) -> {
            afterRequest(r, e, "");
            if (r != null && r.ok && e == null && view != null) {
                view.clearTyped();
                view.setExpanded(false); // the call's own line takes over; the pad closes to leave the screen free
            }
        });
    }

    @Override
    public void warnHeadset() {
        armed = true;
        ui.removeCallbacks(disarm);
        ui.postDelayed(disarm, WARN_MS);
        say(FloatRules.headsetWarning(model.headsetName), false, WARN_MS);
    }

    @Override
    public void typedChanged() {
        armed = false;
        ui.removeCallbacks(disarm);
        if (!message.isEmpty()) {
            say("", false, 0);
        }
    }

    @Override
    public void phoneBluetooth() {
        if (phoneBusy || tabletBusy) {
            return;
        }
        if (model.headsetOnPhone) {
            phoneBusy = true;
            say(HandOverRules.working(HandOverRules.Step.DISCONNECT_PHONE, model.headsetName), false, 0);
            watch.headsetDisconnect((r, e) -> {
                phoneBusy = false;
                afterRequest(r, e, r != null && r.ok && !r.out.isEmpty() ? r.out : "");
                if (r != null && r.ok && e == null && r.out.isEmpty()) {
                    say("", false, 0);
                }
            });
            return;
        }
        // connecting it to the phone first lets go of this tablet (one connection at a time)
        phoneBusy = true;
        HeadsetHandOver.toPhone(ctx, watch, "", () -> true, text -> say(text, false, 0), (ok, text, bad) -> {
            phoneBusy = false;
            say(text, bad, text.isEmpty() ? 0 : SAY_MS * 2);
        });
    }

    @Override
    public void tabletBluetooth() {
        if (tabletBusy || phoneBusy) {
            return;
        }
        final TabletHeadsetRules.Facts f = tablet.facts("", model.headsetName, model.headsetOnPhone);
        final TabletHeadsetRules.Dev d = TabletHeadsetRules.pick(f);
        if (!f.hasBluetooth) {
            say("This tablet has no Bluetooth.", true, SAY_MS);
            return;
        }
        if (!f.bluetoothOn) {
            tabletBusy = true;
            say("Switching the tablet's Bluetooth on…", false, 0);
            tablet.switchOn(ctx, () -> true, way -> {
                tabletBusy = false;
                say(way == TabletHeadsetRules.Way.DONE ? "Bluetooth is on. Press again to connect the headset."
                        : way == TabletHeadsetRules.Way.ASKED ? "Android's Bluetooth list is open: switch Bluetooth on there."
                        : "The tablet would not switch Bluetooth on.", way == TabletHeadsetRules.Way.FAILED, SAY_MS * 2);
            });
            return;
        }
        if (d == null) {
            say(f.bonded.isEmpty() ? "No headset is paired with this tablet yet. Pair it in the list that opens."
                    : "Several devices are paired with this tablet: choose the headset in the app (Calls > headset).", false, SAY_MS * 2);
            if (f.bonded.isEmpty()) {
                TabletHeadset.openList(ctx);
            }
            return;
        }
        tabletBusy = true;
        if (d.connected) {
            say(HandOverRules.working(HandOverRules.Step.DISCONNECT_TABLET, d.name), false, 0);
            tablet.disconnect(ctx, d.addr, () -> true, way -> {
                tabletBusy = false;
                say(TabletHeadsetRules.afterDisconnect(way, d.name), way == TabletHeadsetRules.Way.FAILED, SAY_MS * 2);
            });
            return;
        }
        // connecting it to this tablet first lets the phone go (one connection at a time)
        HeadsetHandOver.toTablet(ctx, watch, () -> true, text -> say(text, false, 0), (ok, text, bad) -> {
            tabletBusy = false;
            say(text, bad, text.isEmpty() ? 0 : SAY_MS * 2);
        });
    }

    @Override
    public void volumeStep(int by) {
        VolumeRules.Row row = model.volume;
        if (!row.enabled) {
            if (!row.why.isEmpty()) {
                say(row.why, true, SAY_MS * 2); // a dimmed button says why instead of nothing
            }
            return;
        }
        if (volPending >= 3) {
            return; // three are on their way: the phone does them in turn
        }
        volPending++;
        update();
        watch.volume(model.volumeStream, by, (r, e) -> {
            volPending--;
            afterRequest(r, e, ""); // the bars show a step that worked; only a refusal is said
            update();
        });
    }

    @Override
    public void volumeSwitch() {
        volChosen = VolumeRules.other(model.volumeStream);
        update();
    }

    @Override
    public void dragBy(int dx, int dy) {
        if (view == null) {
            return;
        }
        lp.x += dx;
        lp.y += dy;
        int[] c = FloatRules.clamp(lp.x, lp.y, view.getWidth(), view.getHeight(), screenW(), screenH(), 0);
        lp.x = c[0];
        lp.y = c[1];
        apply();
    }

    @Override
    public void dragEnd() {
        if (view == null) {
            return;
        }
        int[] c = FloatRules.snap(lp.x, lp.y, view.getWidth(), view.getHeight(), screenW(), screenH(), Ui.dp(ctx, 6));
        lp.x = c[0];
        lp.y = c[1];
        apply();
        Cfg.setFloatPos(ctx, lp.x, lp.y);
    }

    @Override
    public void sizeChanged(boolean expanded) {
        ui.post(this::update); // an open pad keeps the card up; a closed one lets it go when nothing else holds it
    }

    @Override
    public void closed() {
        pinned = false;
        endedAt = 0;
        if (view != null) {
            view.setExpanded(false); // an open pad would keep it on the screen
        }
        update();
    }
}
