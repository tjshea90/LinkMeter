package com.tj.linkmeter;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tablet only: while the app is in front (or the floating phone is on) and the user has used calls, hears from the phone when it
 * rings and while a call is on (docs/PHONE_CALLS.md, docs/FLOATING_PHONE.md). It holds ONE long request open at the phone (/call/now
 * waits for news), so a ring shows within a second and the tablet and the phone are quiet in between. Nothing is asked when nobody
 * wants to hear (the app is away and the floating phone is off), when calls are switched off on the phone, or for a tablet that never
 * used calls. There is ONE of these in the process ({@link #shared}), kept by the number of those who want to hear ({@link #start},
 * {@link #stop}): the app's screens (the Calls tab, the strip at the top of every tab, the number on the Calls button) and the floating
 * phone listen to the same picture.
 */
final class CallWatch {
    static final int IDLE = Calls.WATCH_IDLE;
    static final int OK = Calls.WATCH_OK;
    static final int OFF = Calls.WATCH_OFF;
    static final int OLD = Calls.WATCH_OLD;
    static final int UNREACHED = Calls.WATCH_UNREACHED;
    static final int NO_PEER = Calls.WATCH_NO_PEER;

    /** For the pictures of tools/uipreview: a pretend phone. */
    static volatile RemoteStore.Link testLink;

    interface Listener {
        void changed(CallWatch w);
    }

    /** What an action (answer, end, mute, route) came to. */
    interface Done {
        void done(Calls.Result result, IOException problem);
    }

    private static CallWatch shared;

    /** The one watch of this process. */
    static synchronized CallWatch shared(Context c) {
        if (shared == null) {
            shared = new CallWatch(c.getApplicationContext());
        }
        return shared;
    }

    /**
     * Tests only (tools/uipreview): forgets the watch the test before left behind, with its listeners, its holders and its thread, so every
     * test starts from a watch nobody has used. Never called by the app.
     */
    static synchronized void dropForTest() {
        CallWatch w = shared;
        shared = null;
        if (w != null) {
            synchronized (w) {
                w.holds = 0;
                w.running = false;
                w.gen++;
                w.listeners.clear();
            }
            synchronized (w.lock) {
                w.lock.notifyAll();
            }
        }
    }

    private final Context act;
    private int holds;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService net = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "linkmeter-calls");
        t.setDaemon(true);
        return t;
    });
    private final Object lock = new Object();
    private volatile Calls.Now now = new Calls.Now();
    private volatile long receivedAt;
    private volatile int status = IDLE;
    private volatile String problem = "";
    private volatile boolean running;
    private volatile boolean parked;
    private volatile int gen;
    private volatile String stamp = "";

    private CallWatch(Context a) {
        act = a;
    }


    /** When the loop was last started (the tablet's elapsed clock): {@link #refresh} does not start another one at once. */
    private volatile long launchedAt;
    private static final long REFRESH_GAP_MS = 1_500;

    /** Starts a new loop and ends the old one (its request, if one is open, is dropped unheard). Call with the lock of this watch held. */
    private void launch() {
        running = true;
        launchedAt = SystemClock.elapsedRealtime();
        final int my = ++gen;
        Thread t = new Thread(() -> loop(my), "linkmeter-callwatch");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Back to the state of a watch nobody has used (what the app's screens expect at a new start): forgets the picture and the problem and asks
     * again from nothing. Those who want to hear (the count of {@link #start}) are NOT forgotten: the floating phone keeps listening while the
     * app draws its screens again. The listeners stay too: each owner takes its own away.
     */
    synchronized void reset() {
        gen++; // the old request ends without being heard
        parked = false;
        running = false;
        stamp = "";
        now = new Calls.Now();
        receivedAt = 0;
        status = IDLE;
        problem = "";
        synchronized (lock) {
            lock.notifyAll();
        }
        if (holds > 0 && Cfg.callsWatch(act)) {
            launch();
        }
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** The newest picture of the phone's calls (an idle phone before anything was heard). */
    Calls.Now latest() {
        return now;
    }

    int status() {
        return status;
    }

    /** What went wrong, in a sentence, while the status is {@link #UNREACHED}. */
    String problem() {
        return problem;
    }

    /** The one call the screen is about, or null. */
    Calls.Call lead() {
        return CallRules.lead(now);
    }

    /** How long the call has gone on, counted from the phone's clock at the moment its picture came and by this tablet's clock since. */
    long elapsed(Calls.Call c) {
        Calls.Now n = now;
        long at = n.at + (receivedAt == 0 ? 0 : SystemClock.elapsedRealtime() - receivedAt);
        return CallRules.elapsed(c, at);
    }

    RemoteCalls remote() throws IOException {
        RemoteStore.Link t = testLink;
        if (t != null) {
            return new RemoteCalls(t);
        }
        String host = Net.peerHost(act);
        if (host.isEmpty()) {
            throw new IOException(FileBrowser.noPeer(act));
        }
        return new RemoteCalls(new PeerLink(host, Net.peerToken(act), 8000));
    }

    /** Somebody wants to hear (the app is in front, the floating phone is on): listen, when the user uses calls. Pair with {@link #stop}. */
    synchronized void start() {
        holds++;
        if (running) {
            synchronized (lock) {
                lock.notifyAll(); // somebody came back: a loop that rests between failed tries asks again now
            }
            return;
        }
        if (!Cfg.callsWatch(act)) {
            return;
        }
        launch();
    }

    /** Calls may have started to work since somebody asked to hear: starts the listening that {@link #start} could not start then. */
    synchronized void ensureRunning() {
        if (running || holds <= 0 || !Cfg.callsWatch(act)) {
            return;
        }
        launch();
    }

    /** One of those who wanted to hear has gone; the last one stops the listening (the request that is held ends by itself within half a minute). */
    synchronized void stop() {
        if (holds > 0) {
            holds--;
        }
        if (holds > 0) {
            return;
        }
        running = false;
        gen++;
        stamp = ""; // nobody hears from now on: whoever comes next starts from a full look, never from "tell me when it changes"
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /**
     * The app came back to the front, the screen came on, or the user opened a screen after a pause: look at the phone NOW. A request that is
     * still open is not trusted (a connection that died while the tablet slept holds a loop for half a minute, and the call that ended meanwhile
     * would stay on the screen); it is dropped, and a new look is made that does not wait for news. Once per 1.5 seconds at most, and only when
     * somebody wants to hear.
     */
    synchronized void refresh() {
        if (holds <= 0 || !Cfg.callsWatch(act)) {
            return;
        }
        if (running && SystemClock.elapsedRealtime() - launchedAt < REFRESH_GAP_MS) {
            return; // a look began a moment ago
        }
        parked = false;
        stamp = "";
        launch();
        synchronized (lock) {
            lock.notifyAll(); // a loop that rests between failed tries ends now (it sees the new generation)
        }
    }

    /** Ask now, whatever the last answer was (the Calls tab was opened, the user pressed Check again). Starts listening if it is not. */
    synchronized void wake() {
        parked = false;
        if (!running) {
            launch();
        }
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private void tell(final int s, final String p) {
        status = s;
        problem = p;
        ui.post(() -> {
            for (Listener l : listeners) {
                l.changed(this);
            }
        });
    }

    private void deliver(Calls.Now n) {
        now = n;
        receivedAt = SystemClock.elapsedRealtime();
        stamp = n.stamp;
        tell(OK, "");
    }

    private void loop(int my) {
        int failures = 0;
        while (running && my == gen) {
            if (parked) {
                synchronized (lock) {
                    try {
                        lock.wait(5 * 60_000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                continue;
            }
            try {
                RemoteCalls rc = remote();
                Calls.Now n = rc.now(stamp, RemoteCalls.WAIT_S);
                if (my != gen) {
                    return;
                }
                failures = 0;
                deliver(n);
            } catch (RemoteCalls.Off e) {
                if (my != gen) {
                    return;
                }
                parked = true;
                stamp = "";
                now = new Calls.Now();
                tell(OFF, e.getMessage());
            } catch (RemoteCalls.TooOld e) {
                if (my != gen) {
                    return;
                }
                parked = true;
                stamp = "";
                tell(OLD, e.getMessage());
            } catch (IOException e) {
                if (my != gen) {
                    return;
                }
                stamp = "";
                String host = Net.peerHost(act);
                tell(host.isEmpty() && testLink == null ? NO_PEER : UNREACHED, TextsTab.explain(e));
                failures++;
                // a phone that cannot be reached for long (the hotspot is off, the tablet is on another network) is asked about rarely:
                // two seconds, then four, eight, sixteen, thirty; after six failures in a row every two minutes. The Calls tab, the
                // screen coming on and any button press ask at once ({@link #wake}).
                long wait = failures > 6 ? 120_000L : Math.min(30_000L, 2000L << Math.min(failures, 4));
                synchronized (lock) {
                    try {
                        lock.wait(wait);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ doing things (one request each, off the screen's thread)

    private void run(final Request r, final Done d) {
        net.execute(() -> {
            Calls.Result res = null;
            IOException bad = null;
            try {
                res = r.go(remote());
            } catch (IOException e) {
                bad = e;
            }
            final Calls.Result fr = res;
            final IOException fb = bad;
            ui.post(() -> {
                if (d != null) {
                    d.done(fr, fb);
                }
            });
            wake(); // the phone's picture changes now: hear it at once
        });
    }

    private interface Request {
        Calls.Result go(RemoteCalls rc) throws IOException;
    }

    void answer(Done d) {
        run(RemoteCalls::answer, d);
    }

    void hangup(Done d) {
        run(RemoteCalls::hangup, d);
    }

    void mute(final boolean on, Done d) {
        run(rc -> rc.mute(on), d);
    }

    void route(final String to, Done d) {
        run(rc -> rc.route(to), d);
    }

    /** The headset's media or call volume, one press = {@code by} steps (the buttons send 1 or -1). The phone's answer says what it read back. */
    void volume(final String stream, final int by, Done d) {
        run(rc -> rc.volume(stream, by), d);
    }

    void dial(final String number, Done d) {
        run(rc -> rc.dial(number), d);
    }

    /** Connects the headset the user chose to the PHONE (the phone looks for up to 25 seconds). */
    void headsetConnect(Done d) {
        headsetConnect("", d);
    }

    /** Connects this headset ("" = the one the user chose on the phone) to the PHONE. */
    void headsetConnect(final String addr, Done d) {
        run(rc -> rc.headsetConnect(addr == null ? "" : addr), d);
    }

    /** Disconnects the headset from the phone. */
    void headsetDisconnect(Done d) {
        run(RemoteCalls::headsetDisconnect, d);
    }
}
