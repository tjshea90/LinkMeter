package com.tj.linkmeter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import java.io.IOException;

/**
 * Tablet only: the floating phone (docs/FLOATING_PHONE.md). A small dark card drawn over every app (Android's "Display over other apps")
 * that pops up by itself when the phone rings or a call is on, shows who and how long, the headset's battery, a green Call / Answer and a
 * red End / Decline button, two Bluetooth buttons (the headset's link to the phone, and to this tablet) and a dial pad that opens and
 * closes; the user can drag it anywhere and it remembers the place. It is the phone's remote control and nothing more: the call is
 * placed, answered and ended by the phone through the same /call/* routes as the Calls tab (so the same guard refuses emergency numbers
 * and codes), and the call's sound is the headset's business with the phone.
 *
 * <p>It runs only while {@link FloatingPhone#wanted} says so, and it holds the one {@link CallWatch} of the process like the app's
 * screens do. What it shows and when is decided by {@link FloatRules}; what is drawn is {@link MiniPhoneView}.
 */
public final class FloatingPhoneService extends Service implements MiniPhoneView.Host {
    static final String ACTION_SHOW = "com.tj.linkmeter.FLOAT_SHOW";
    static final String ACTION_OFF = "com.tj.linkmeter.FLOAT_OFF";
    private static final String CHANNEL = "floating_phone";
    private static final int NOTE_ID = 7;
    private static final long WARN_MS = 7_000;
    private static final long SAY_MS = 5_000;
    /** How long the card stays when the phone cannot be heard in the middle of a call. */
    private static final long OFFLINE_MS = 20_000;

    private static volatile boolean running;
    private static volatile FloatingPhoneService instance;

    static boolean running() {
        return running;
    }

    /** Looks again at what to show (the app came to the front or went away). Does nothing when no service runs. */
    static void poke() {
        final FloatingPhoneService s = instance;
        if (s != null) {
            s.ui.post(s::update);
        }
    }

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
                watch.wake(); // the tablet was asleep: ask the phone at once, not after the pause of an unreached phone
            }
        }
    };

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        instance = this;
        wm = getSystemService(WindowManager.class);
        startInForeground();
        watch = CallWatch.shared(this);
        watch.addListener(watchListener);
        watch.start();
        tablet = TabletHeadset.of(this); // listened to only while the card is up (show/hide)
        try {
            registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        } catch (RuntimeException ignored) {
            // the screen-on nudge is a convenience
        }
        update();
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        startInForeground(); // Android wants this within seconds of every start
        String a = i == null ? null : i.getAction();
        if (ACTION_OFF.equals(a)) {
            Cfg.setFloatOn(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SHOW.equals(a)) {
            pinned = true;
        }
        update();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (instance == this) {
            instance = null;
        }
        ui.removeCallbacksAndMessages(null);
        hide();
        if (watch != null) {
            watch.removeListener(watchListener);
            watch.stop();
        }
        try {
            unregisterReceiver(screenOn);
        } catch (RuntimeException ignored) {
            // it was not registered
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        ui.post(this::place); // the screen turned: keep the card on it
    }

    // ------------------------------------------------------------------ the notification Android wants for a service that stays

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Floating phone", NotificationManager.IMPORTANCE_MIN));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent show = PendingIntent.getService(this, 1, new Intent(this, FloatingPhoneService.class).setAction(ACTION_SHOW),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent off = PendingIntent.getService(this, 2, new Intent(this, FloatingPhoneService.class).setAction(ACTION_OFF),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Floating phone is ready")
                .setContentText("It pops up when the phone rings or a call is on")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_launcher), "Show phone", show).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_launcher), "Turn off", off).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTE_ID, n);
        }
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
        in.enabled = Cfg.floatOn(this);
        in.canDraw = FloatingPhone.canDraw(this);
        in.pinned = pinned;
        in.padOpen = view != null && view.expanded();
        in.appInFront = FloatingPhone.appInFront();
        in.call = state;
        in.endedAt = endedAt;
        in.now = now;
        if (!in.canDraw) {
            stopSelf(); // Android took the permission back: nothing can be drawn
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
        m.askFirst = Cfg.callsHeadsetAsk(this);
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
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return dm.widthPixels;
    }

    private int screenH() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return dm.heightPixels;
    }

    private void show() {
        if (view != null) {
            return;
        }
        view = new MiniPhoneView(this, this);
        lp = new WindowManager.LayoutParams(Ui.dp(this, MiniPhoneView.WIDTH_DP), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        int x = Cfg.floatX(this);
        int y = Cfg.floatY(this);
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
        int h = view.getHeight() > 0 ? view.getHeight() : Ui.dp(this, 110);
        int[] c = FloatRules.clamp(lp.x, lp.y, w, h, screenW(), screenH(), Ui.dp(this, 6));
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
        if (phoneBusy) {
            return;
        }
        final boolean on = model.headsetOnPhone;
        phoneBusy = true;
        say(on ? "Disconnecting the headset from the phone…" : "Connecting the headset to the phone… (up to 25 seconds)", false, 0);
        CallWatch.Done done = (r, e) -> {
            phoneBusy = false;
            afterRequest(r, e, r != null && r.ok && !r.out.isEmpty() ? r.out : "");
            if (r != null && r.ok && e == null && r.out.isEmpty()) {
                say("", false, 0);
            }
        };
        if (on) {
            watch.headsetDisconnect(done);
        } else {
            watch.headsetConnect(done);
        }
    }

    @Override
    public void tabletBluetooth() {
        if (tabletBusy) {
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
            tablet.switchOn(this, () -> true, way -> {
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
                TabletHeadset.openList(this);
            }
            return;
        }
        final boolean connect = !d.connected;
        tabletBusy = true;
        say(connect ? "Connecting " + d.name + " to this tablet…" : "Disconnecting " + d.name + " from this tablet…", false, 0);
        tablet.set(this, d.addr, connect, () -> true, way -> {
            tabletBusy = false;
            say(connect ? TabletHeadsetRules.afterConnect(way, d.name) : TabletHeadsetRules.afterDisconnect(way, d.name),
                    way == TabletHeadsetRules.Way.FAILED, SAY_MS * 2);
        });
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
        int[] c = FloatRules.snap(lp.x, lp.y, view.getWidth(), view.getHeight(), screenW(), screenH(), Ui.dp(this, 6));
        lp.x = c[0];
        lp.y = c[1];
        apply();
        Cfg.setFloatPos(this, lp.x, lp.y);
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
