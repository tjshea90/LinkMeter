package com.tj.linkmeter;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Tablet only. Runs {@link BootFlow} ("start the phone after it restarted") on the real tablet: once a second it tells the flow what it
 * can see (the tablet's hotspot, what the phone says through that hotspot and through the normal route) and carries out the commands
 * that come back. The decisions are all in BootFlow (tested on simulated devices); this class only looks, asks and sends.
 *
 * One of these lives as long as the app does, so the flow goes on while the user is in the Wi-Fi settings or another tab; the Control
 * tab's card attaches to it to show the steps.
 */
final class BootDriver {
    interface Listener {
        /** Called on the UI thread whenever there is something new to show. */
        void changed();
    }

    private static BootDriver instance;

    private final Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final BootFlow flow = new BootFlow();
    private final TabletHotspot hotspot;
    private final ExecutorService io = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "linkmeter-boot");
            t.setDaemon(true);
            return t;
        }
    });

    private Listener listener;
    private boolean ticking;
    private boolean starting;
    private long lastPollHotspotAt;

    private volatile BootFlow.Facts hotFacts;
    private volatile BootFlow.Facts normFacts;
    private volatile String hotBody = "";
    private volatile String normBody = "";
    private volatile String phoneHost = "";
    private volatile int hotMisses;
    private volatile boolean busyHot;
    private volatile boolean busyNorm;
    private volatile long lastNormPoll;
    private volatile String note = "";
    /** True while {@link #note} is the "stop the hotspot in NetShare" request, which goes away once that hotspot is gone. */
    private volatile boolean netShareNote;
    private volatile String lastCommandResult = "";

    private BootDriver(Context c) {
        app = c.getApplicationContext();
        hotspot = TabletHotspot.get(app);
    }

    static synchronized BootDriver get(Context c) {
        if (instance == null) {
            instance = new BootDriver(c);
        }
        return instance;
    }

    TabletHotspot hotspot() {
        return hotspot;
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean running() {
        return starting || flow.running();
    }

    /** Has a run been started since the app opened (so that the steps are worth showing)? */
    boolean active() {
        return starting || flow.stage() != BootFlow.Stage.IDLE;
    }

    /** The seven steps with their marks. */
    String steps() {
        return flow.render();
    }

    /** What is going on or what to do now. */
    String headline() {
        if (starting) {
            return "Looking for the phone…";
        }
        return flow.headline();
    }

    /** An extra remark from this side (for example "stop the hotspot in NetShare"). */
    String note() {
        return note;
    }

    String lastCommandResult() {
        return lastCommandResult;
    }

    BootFlow.Outcome outcome() {
        return flow.outcome();
    }

    /** One line about what the phone last said, through either route. */
    String phoneSummary() {
        boolean useNormal = normFacts != null && (hotFacts == null || normFacts.at >= hotFacts.at);
        String body = useNormal ? normBody : hotBody;
        String where = useNormal ? "on the normal route" : "on the tablet's hotspot";
        if (body.isEmpty()) {
            return "The phone has not been heard from yet.";
        }
        try {
            JSONObject j = new JSONObject(body);
            int priv = j.optInt("priv", 0);
            return "Phone (" + where + "): "
                    + (priv == Priv.READY ? "Shizuku is ready" : priv == Priv.NO_PERM ? "Shizuku runs, LinkMeter is not allowed yet" : "Shizuku is not running")
                    + "; wireless debugging " + (j.optBoolean("wadb") ? "on" : "off")
                    + "; LinkMeter " + (j.optBoolean("write") ? "may" : "may not yet") + " switch it on by itself"
                    + "; Boot assist " + (j.optBoolean("assist") ? "on" : "off")
                    + (j.optBoolean("keep") ? "; the 7-day pairing timeout is off" : "")
                    + (j.optBoolean("hotspot") ? "; its hotspot is on" : "") + ".";
        } catch (JSONException e) {
            return "The phone answered, but I could not read it.";
        }
    }

    // ------------------------------------------------------------------------------------------- start, cancel

    /** The big button. Looks at the normal route first (if the phone already answers with Shizuku running there is nothing to do). */
    void start() {
        if (running()) {
            return;
        }
        hotFacts = null;
        normFacts = null;
        hotBody = "";
        normBody = "";
        phoneHost = "";
        hotMisses = 0;
        note = "";
        netShareNote = false;
        lastCommandResult = "";
        starting = true;
        changed();
        pollNormal(true, new Runnable() {
            @Override
            public void run() {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        begin();
                    }
                });
            }
        });
    }

    private void begin() {
        starting = false;
        flow.start(observe(SystemClock.elapsedRealtime()));
        changed();
        scheduleTick();
    }

    void cancel() {
        starting = false;
        flow.cancel();
        changed();
    }

    // ------------------------------------------------------------------------------------------- the tick

    private void scheduleTick() {
        if (!ticking && flow.running()) {
            ticking = true;
            ui.postDelayed(tick, 1000);
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            ticking = false;
            if (!flow.running()) {
                changed();
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastPollHotspotAt >= 5000) {
                lastPollHotspotAt = now;
                hotspot.poll();
            }
            if (netShareNote && !hotspot.isExternal()) {
                netShareNote = false; // that hotspot is gone: the request no longer applies
                note = "";
            }
            pollHotspotNetwork(now);
            pollNormal(false, null);
            List<BootFlow.Cmd> cmds = flow.tick(observe(now));
            for (BootFlow.Cmd c : cmds) {
                execute(c);
            }
            changed();
            scheduleTick();
        }
    };

    private BootFlow.Obs observe(long now) {
        BootFlow.Obs o = new BootFlow.Obs();
        o.now = now;
        o.hotspot = hotspot.state();
        o.hotspotInfo = hotspot.info();
        o.onHotspot = hotFacts;
        o.onNormal = normFacts;
        return o;
    }

    private void changed() {
        Listener l = listener;
        if (l != null) {
            l.changed();
        }
    }

    // ------------------------------------------------------------------------------------------- looking

    private static BootFlow.Facts parse(String body, String ip, long at) {
        try {
            JSONObject j = new JSONObject(body);
            BootFlow.Facts f = new BootFlow.Facts(j.optInt("priv", 0), at);
            f.wadbOn = j.optBoolean("wadb");
            f.canWriteSecure = j.optBoolean("write");
            f.hotspotOn = j.optBoolean("hotspot");
            f.ip = ip;
            return f;
        } catch (JSONException e) {
            return null;
        }
    }

    /** Through the tablet's own hotspot: ask the phone where it was last found, or look for it on the hotspot's subnet. */
    private void pollHotspotNetwork(long now) {
        if (hotspot.state() != BootFlow.Hotspot.ON || busyHot) {
            return;
        }
        final String apIp = hotspot.ip();
        final String known = phoneHost;
        if (apIp.isEmpty() && known.isEmpty()) {
            return;
        }
        busyHot = true;
        final String token = Cfg.token(app);
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    PhoneFinder.Found f = known.isEmpty() ? null : PhoneFinder.ask(known, token, 800, 2500);
                    if (f == null && (known.isEmpty() || hotMisses >= 2)) {
                        f = PhoneFinder.find(Subnet.candidates(apIp, 24), token, 350, 1500, 48);
                    }
                    if (f != null) {
                        BootFlow.Facts facts = parse(f.body, f.host, SystemClock.elapsedRealtime());
                        if (facts != null) {
                            phoneHost = f.host;
                            hotBody = f.body;
                            hotFacts = facts;
                            hotMisses = 0;
                        }
                    } else {
                        hotMisses++;
                        if (hotMisses >= 4) {
                            phoneHost = ""; // look for it from scratch next time
                        }
                    }
                } finally {
                    busyHot = false;
                }
            }
        });
    }

    /** Through the normal route (the tablet on the phone's hotspot). done runs (on a worker thread) when the answer is in. */
    private void pollNormal(boolean force, final Runnable done) {
        long now = SystemClock.elapsedRealtime();
        if (busyNorm || (!force && now - lastNormPoll < 3000)) {
            if (done != null) {
                io.execute(done);
            }
            return;
        }
        final String host = Net.phoneHost(app);
        if (host.isEmpty()) {
            // nothing to ask: what was heard before ages out by itself
            if (done != null) {
                io.execute(done);
            }
            return;
        }
        busyNorm = true;
        lastNormPoll = now;
        final String token = Cfg.token(app);
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    PhoneFinder.Found f = PhoneFinder.ask(host, token, 1500, 3000);
                    if (f != null) {
                        BootFlow.Facts facts = parse(f.body, f.host, SystemClock.elapsedRealtime());
                        if (facts != null) {
                            normBody = f.body;
                            normFacts = facts;
                        }
                    }
                } finally {
                    busyNorm = false;
                    if (done != null) {
                        done.run();
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------- doing

    private void execute(BootFlow.Cmd c) {
        switch (c) {
            case START_HOTSPOT:
                hotspot.start();
                break;
            case STOP_HOTSPOT:
                hotspot.stop();
                if (hotspot.isExternal()) {
                    note = "Stop the hotspot in NetShare now: I cannot stop a hotspot that another app made.";
                    netShareNote = true;
                }
                break;
            case WADB_ON:
                post("boot_wadb");
                break;
            case NOTIFY:
                post("boot_notify");
                break;
            case HOTSPOT_ON:
                post("boot_hotspot_on");
                break;
            default:
                break;
        }
    }

    /** One question to the phone through the tablet's hotspot network, answered in the background. */
    private void post(final String action) {
        final String host = phoneHost;
        if (host.isEmpty()) {
            lastCommandResult = action + ": the phone's address on the hotspot is not known yet";
            return;
        }
        final String token = Cfg.token(app);
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Client.Resp r = Client.call(host, "POST", "/act?name=" + action, token, 2500, 25000);
                    String text = r.body;
                    try {
                        JSONObject j = new JSONObject(r.body);
                        text = j.has("err") ? j.optString("err") : j.optString("out", r.body);
                    } catch (JSONException plain) {
                        // plain text: show it as it came
                    }
                    lastCommandResult = action + ": " + (r.code == 200 ? "" : "(" + r.code + ") ") + text;
                } catch (IOException e) {
                    // the phone starting its own hotspot cuts this connection: that is expected for boot_hotspot_on
                    lastCommandResult = action + ": " + (action.equals("boot_hotspot_on") ? "sent (the link dropped, as expected)" : e.getMessage());
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        changed();
                    }
                });
            }
        });
    }

    /** One question to the phone through the normal route (the tablet on the phone's hotspot), for the setup buttons. */
    void normalPost(final String action, final int readMs, final Done d) {
        final String host = Net.phoneHost(app);
        final String token = Cfg.token(app);
        io.execute(new Runnable() {
            @Override
            public void run() {
                String text;
                boolean ok = false;
                if (host.isEmpty()) {
                    text = "The tablet is not on the phone's hotspot.";
                } else {
                    try {
                        Client.Resp r = Client.call(host, "POST", "/act?name=" + action, token, 2500, readMs);
                        text = r.body;
                        try {
                            JSONObject j = new JSONObject(r.body);
                            text = j.has("err") ? j.optString("err") : j.optString("out", r.body);
                        } catch (JSONException plain) {
                            // plain text
                        }
                        ok = r.code == 200;
                    } catch (IOException e) {
                        text = "Couldn't reach the phone: " + e.getMessage();
                    }
                }
                final boolean fOk = ok;
                final String fText = text;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        d.done(fOk, fText);
                    }
                });
            }
        });
    }

    interface Done {
        void done(boolean ok, String text);
    }
}
