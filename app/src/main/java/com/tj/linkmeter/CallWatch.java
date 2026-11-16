package com.tj.linkmeter;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tablet only: while the app is in front and the user has used calls, hears from the phone when it rings and while a call is on
 * (docs/PHONE_CALLS.md). It holds ONE long request open at the phone (/call/now waits for news), so a ring shows within a second and
 * the tablet and the phone are quiet in between. Nothing is asked in the background, when calls are switched off on the phone, or
 * for a tablet that never used calls. The Calls tab, the strip at the top of every tab and the number on the Calls button listen here.
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

    private final MainActivity act;
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

    CallWatch(MainActivity a) {
        act = a;
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

    /** The app is in front: listen (when the user uses calls). */
    void start() {
        if (running || !Cfg.callsWatch(act)) {
            return;
        }
        running = true;
        final int my = ++gen;
        Thread t = new Thread(() -> loop(my), "linkmeter-callwatch");
        t.setDaemon(true);
        t.start();
    }

    /** The app went to the background: stop listening (the request that is held ends by itself within half a minute). */
    void stop() {
        running = false;
        gen++;
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /** Ask now, whatever the last answer was (the Calls tab was opened, the user pressed Check again). Starts listening if it is not. */
    void wake() {
        parked = false;
        if (!running) {
            running = true;
            final int my = ++gen;
            Thread t = new Thread(() -> loop(my), "linkmeter-callwatch");
            t.setDaemon(true);
            t.start();
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
                parked = true;
                stamp = "";
                now = new Calls.Now();
                tell(OFF, e.getMessage());
            } catch (RemoteCalls.TooOld e) {
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
                long wait = Math.min(30_000L, 2000L << Math.min(failures, 4));
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

    void dial(final String number, Done d) {
        run(rc -> rc.dial(number), d);
    }
}
