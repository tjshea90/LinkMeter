package com.tj.linkmeter;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.SystemClock;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Tablet only: the Launcher's "pocket return" (docs/LAUNCHER_BRIDGE.md) as a job on its own thread. {@link PocketRules} is the sequence (pure,
 * tested); this class is the real phone behind it (the one {@link Reach} that every screen of this app uses), the one-job-at-a-time rule, a partial
 * wake lock while it works (the tablet may be put to sleep half way: the phone's sleep takes up to a minute) and what is kept for the Launcher to read
 * (the last result survives this app's process).
 *
 * Nothing here switches the tablet's own Bluetooth or Wi-Fi: a request is sent as "not pressed", so a tablet whose Bluetooth the user switched off
 * keeps it off, and the answer says that the phone could not be reached.
 */
final class PocketJob {
    private PocketJob() {}

    private static final String PREFS = "pocket_job";
    /** The wake lock outlives the longest run (about 25 s + 100 s + the last steps) and then lets go by itself. */
    private static final long LOCK_MS = 200_000L;

    /** What the bridge tells the Launcher. */
    static final class Snapshot {
        final PocketRules.State state;
        final boolean sent;
        final String text;
        final long startedAt, endedAt;
        /** True when this is the answer to a start that found a job already running. */
        final boolean alreadyRunning;

        Snapshot(PocketRules.State state, boolean sent, String text, long startedAt, long endedAt, boolean alreadyRunning) {
            this.state = state;
            this.sent = sent;
            this.text = text;
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.alreadyRunning = alreadyRunning;
        }
    }

    private static final Object LOCK = new Object();
    private static volatile PocketRules.State state = PocketRules.State.IDLE;
    private static volatile boolean sent;
    private static volatile String text = "";
    private static volatile long startedAt, endedAt;
    private static volatile boolean loaded;

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** After a restart of this app's process the last finished run is still readable (a run that was cut off reads as FAILED). */
    private static void load(Context c) {
        if (loaded) {
            return;
        }
        synchronized (LOCK) {
            if (loaded) {
                return;
            }
            SharedPreferences p = sp(c);
            String s = p.getString("state", "IDLE");
            if ("RUNNING".equals(s) || "SENT".equals(s)) {
                state = PocketRules.State.FAILED; // the process ended in the middle of a run
                text = "The run was cut off when LinkMeter's process ended. " + p.getString("text", "");
            } else {
                state = "DONE".equals(s) ? PocketRules.State.DONE : "FAILED".equals(s) ? PocketRules.State.FAILED : PocketRules.State.IDLE;
                text = p.getString("text", "");
            }
            sent = p.getBoolean("sent", false);
            startedAt = p.getLong("startedAt", 0);
            endedAt = p.getLong("endedAt", 0);
            loaded = true;
        }
    }

    private static void save(Context c) {
        sp(c).edit().putString("state", state.name()).putBoolean("sent", sent).putString("text", PocketRules.oneLine(text))
                .putLong("startedAt", startedAt).putLong("endedAt", endedAt).apply();
    }

    static Snapshot snapshot(Context c) {
        load(c);
        return new Snapshot(state, sent, text, startedAt, endedAt, false);
    }

    /** Why a run cannot start at all, or "" when it can. */
    static String cannotStart(Context c) {
        if (!Cfg.TABLET.equals(Cfg.mode(c))) {
            return "This LinkMeter is not set up as the tablet.";
        }
        boolean hotspotRoute = !Net.phoneHost(c).isEmpty();
        if (!hotspotRoute && !BtLink.ready(c)) {
            return "No phone is linked: the tablet is not on the phone's hotspot and the Bluetooth link is not set up.";
        }
        return "";
    }

    /** Starts the job on its own thread, unless one is running (then that one is reported). Never blocks. */
    static Snapshot start(Context c) {
        final Context app = c.getApplicationContext();
        load(app);
        synchronized (LOCK) {
            if (state == PocketRules.State.RUNNING || state == PocketRules.State.SENT) {
                return new Snapshot(state, sent, text, startedAt, endedAt, true);
            }
            String why = cannotStart(app);
            if (!why.isEmpty()) {
                state = PocketRules.State.FAILED;
                sent = false;
                text = why;
                startedAt = System.currentTimeMillis();
                endedAt = startedAt;
                save(app);
                return new Snapshot(state, false, text, startedAt, endedAt, false);
            }
            state = PocketRules.State.RUNNING;
            sent = false;
            text = "";
            startedAt = System.currentTimeMillis();
            endedAt = 0;
            save(app);
        }
        new Thread(() -> runJob(app), "linkmeter-pocket").start();
        return new Snapshot(PocketRules.State.RUNNING, false, "", startedAt, 0, false);
    }

    private static void runJob(final Context app) {
        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "linkmeter:pocket");
                wl.setReferenceCounted(false);
                wl.acquire(LOCK_MS);
            }
        } catch (RuntimeException ignored) {
            // no wake lock: the run still goes on while the tablet is awake
        }
        try {
            PocketRules.run(new RealPhone(app), new PocketRules.Sink() {
                @Override
                public void state(PocketRules.State s, String t) {
                    synchronized (LOCK) {
                        state = s;
                        text = t;
                        if (s == PocketRules.State.DONE || s == PocketRules.State.FAILED) {
                            endedAt = System.currentTimeMillis();
                        }
                        save(app);
                    }
                }

                @Override
                public void sent() {
                    synchronized (LOCK) {
                        sent = true;
                    }
                }
            });
        } catch (RuntimeException e) {
            // an escape from this thread would end the app: said as what it is
            synchronized (LOCK) {
                state = PocketRules.State.FAILED;
                text = "The run stopped on an error: " + e;
                endedAt = System.currentTimeMillis();
                save(app);
            }
        } finally {
            try {
                if (wl != null && wl.isHeld()) {
                    wl.release();
                }
            } catch (RuntimeException ignored) {
                // already released
            }
        }
    }

    /** The real phone: the same {@link Reach} as the tablet's screens. */
    private static final class RealPhone implements PocketRules.Phone {
        private final Context app;

        RealPhone(Context app) {
            this.app = app;
        }

        @Override
        public PocketRules.Reply act(String name, int readMs) {
            Reach.Result res;
            try {
                res = Call.reach(app).call("POST", "/act?name=" + name, readMs, false);
            } catch (RuntimeException e) {
                return PocketRules.Reply.unreachable("LinkMeter could not send that (" + e + ")");
            }
            if (res.resp == null) {
                return PocketRules.Reply.unreachable(res.error);
            }
            String body = res.resp.body;
            JSONObject json = null;
            try {
                json = new JSONObject(body);
            } catch (JSONException notJson) {
                // a plain text reply: used as it is
            }
            String said = body;
            if (json != null) {
                if (json.has("err")) {
                    said = json.optString("err");
                } else if (json.has("out")) {
                    said = json.optString("out");
                }
            }
            if (res.resp.code == 401) {
                return PocketRules.Reply.failed("Not paired. On the phone tap Pair tablet.");
            }
            boolean ok = res.resp.code == 200 && (json == null || !json.has("err"));
            if (!ok) {
                return PocketRules.Reply.failed(said);
            }
            return json != null && json.optBoolean("later", false) ? PocketRules.Reply.sent(said) : PocketRules.Reply.done(said);
        }

        @Override
        public PocketRules.Status status() {
            try {
                Reach.Result res = Call.reach(app).call("GET", "/s", PocketRules.STATUS_MS, false);
                if (res.resp == null || res.resp.code != 200) {
                    return null;
                }
                JSONObject j = new JSONObject(res.resp.body);
                return new PocketRules.Status(Call.flag(j, "hs"), Call.flag(j, "air"), Call.flag(j, "scr"), j.optString("dres", ""));
            } catch (JSONException | RuntimeException e) {
                return null;
            }
        }

        @Override
        public long now() {
            return SystemClock.elapsedRealtime();
        }

        @Override
        public void pause(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
