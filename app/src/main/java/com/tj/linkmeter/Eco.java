package com.tj.linkmeter;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phone only: the battery-saving profile for hotspot use. Every decision is in SaverPlan (tested on a computer); this class is
 * the Android part: the stored values, the shell, the alarm, the screen receiver and the threads.
 *
 * It only turns off things a hotspot does not need (background Wi-Fi and Bluetooth scanning, the always-on display) and shortens
 * the screen timeout. It never touches mobile data, the radio, the CPU speed or Bluetooth itself (the tablet's remote link uses
 * it), so the cell link and hotspot speed are unaffected. Every value it changes is saved first and put back afterwards.
 *
 * There is ONE stored switch, Cfg.ecoAuto (Automatic or Off), and {@link #setAuto} is the only way to change it. The saver itself
 * is "on" while its settings are in place. Whenever the tablet talks to us over Wi-Fi, and when the service starts, the two are
 * brought in line: Automatic and not on, with the tablet here: switch on. Off and on: switch off (the choice may have been made
 * before this process was restarted, or while Shizuku was down).
 *
 * When it ends by itself: ten minutes after the tablet was last heard, an alarm that does not wake the phone decides whether the
 * tablet is gone (SaverPlan.idle). The screen coming on decides the same, so a phone picked up after Standby gets its own
 * settings back at once. Nothing stays behind when the saver is off and the service is stopped.
 */
final class Eco {
    private static final String ALARM_TAG = "linkmeter-eco";
    /** How long to wait for the tablet's address to answer before deciding the tablet is gone. */
    private static final int PING_MS = 2000;
    private static final Handler H = new Handler(Looper.getMainLooper());

    /** The last request from the tablet over Wi-Fi (elapsed real time). */
    private static volatile long lastSeen;
    /** When a switch-on or switch-off last failed, so that it is not repeated on every request (0 = never). */
    private static volatile long failedAt;
    private static volatile Context appCtx;
    private static final AtomicBoolean busy = new AtomicBoolean();
    /** The alarm is set. Its own flag, so a request never has to wait behind a shell command of the saver. */
    private static final AtomicBoolean armed = new AtomicBoolean();
    private static final Object IDLE_LOCK = new Object();
    private static final Object WATCH = new Object();
    private static BroadcastReceiver screenReceiver; // guarded by WATCH

    private static final AlarmManager.OnAlarmListener ALARM = () -> {
        armed.set(false);
        Context app = appCtx;
        if (app != null) {
            startIdleCheck(app);
        }
    };

    private Eco() {}

    /** The short screen timeout the battery saver uses, in milliseconds (as text). */
    static String timeoutMs() {
        return SaverPlan.timeoutMs();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("eco", Context.MODE_PRIVATE);
    }

    static boolean isOn(Context c) {
        return prefs(c).getBoolean("on", false);
    }

    private static long elapsed() {
        return SystemClock.elapsedRealtime();
    }

    // ------------------------------------------------------------------------------------------ the service and the tablet

    /** The service just started (possibly after a reboot, or a restart of the app, with the profile still on). */
    static void onServiceStart(Context c) {
        Context app = c.getApplicationContext();
        appCtx = app;
        lastSeen = elapsed();
        failedAt = 0;
        if (isOn(app)) {
            arm(app, lastSeen + SaverPlan.IDLE_MS);
            watchScreen(app);
        }
        if (Cfg.ecoAuto(app) != isOn(app)) {
            reconcileInBackground(app, false); // Off, and still on from before: put it back
        }
    }

    /** The service is being destroyed: no alarm and no receiver may outlive it. */
    static void onServiceStop(Context c) {
        Context app = c.getApplicationContext();
        disarm(app);
        unwatchScreen(app);
    }

    /** The tablet just talked to us over the network (a request over Bluetooth does not come here: that means the hotspot is off). */
    static void touch(Context c, boolean fromLocalNet) {
        final Context app = c.getApplicationContext();
        appCtx = app;
        long now = elapsed();
        lastSeen = now;
        boolean auto = Cfg.ecoAuto(app);
        boolean on = isOn(app);
        if (on) {
            arm(app, now + SaverPlan.IDLE_MS); // a no-op while the alarm is set
        }
        if (auto == on) {
            return; // the common case: Automatic and on, or Off and off. SaverPlan.reconcile says nothing to do either, without asking Shizuku
        }
        if (SaverPlan.reconcile(auto, on, fromLocalNet, Priv.ready(), now, failedAt) != SaverPlan.Act.NOTHING) {
            reconcileInBackground(app, fromLocalNet);
        }
    }

    private static void reconcileInBackground(final Context app, final boolean fromLocalNet) {
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> {
            try {
                reconcileNow(app, fromLocalNet);
            } catch (IOException | RuntimeException e) {
                failedAt = elapsed(); // not again within a minute
            } finally {
                busy.set(false);
            }
        }, "linkmeter-eco").start();
    }

    /**
     * Asks again under the saver's lock before it acts: the facts of a moment ago may be old. A "turn off" that arrived with the
     * very request that started this thread must win.
     */
    private static void reconcileNow(Context app, boolean fromLocalNet) throws IOException {
        synchronized (Eco.class) {
            SaverPlan.Act a = SaverPlan.reconcile(Cfg.ecoAuto(app), isOn(app), fromLocalNet, Priv.ready(), elapsed(), failedAt);
            if (a == SaverPlan.Act.APPLY) {
                applyLocked(app);
            } else if (a == SaverPlan.Act.RESTORE) {
                restoreLocked(app);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ the one switch

    /** The stored choice, changed now. What the phone did is in the sentence; a switch that did not move is an error. */
    static String setAuto(Context c, boolean auto) throws IOException {
        return setAuto(c, auto, auto ? SaverPlan.AUTOMATIC_NOTE : SaverPlan.OFF_NOTE);
    }

    /**
     * Storing the choice never fails, and is done first. Then the saver is brought in line with it at once (no waiting for the
     * tablet's next request). When that cannot be done yet (Shizuku is not running) the answer says so and the next request or
     * the alarm does it. Every user of the switch comes through here: the tablet's actions, the phone's own checkbox and
     * "Restore everything".
     *
     * @param note what to add to the sentence (what the choice means from now on)
     */
    static String setAuto(Context c, boolean auto, String note) throws IOException {
        Context app = c.getApplicationContext();
        appCtx = app;
        Cfg.setEcoAuto(app, auto);
        synchronized (Eco.class) {
            boolean on = isOn(app);
            if (auto) {
                lastSeen = elapsed(); // pressing the switch counts as hearing from the tablet: the ten minutes start now
                if (on) {
                    arm(app, lastSeen + SaverPlan.IDLE_MS);
                    watchScreen(app);
                    return withSystemLocked(app, SaverPlan.ALREADY_ON) + note;
                }
                if (!Priv.ready()) {
                    return SaverPlan.ON_STORED_WAITING;
                }
                return applyLocked(app) + note;
            }
            if (!on && !systemOn(app)) {
                return SaverPlan.ALREADY_OFF + note;
            }
            if (!Priv.ready() && (restorePlan(app).count > 0 || systemOn(app))) {
                return SaverPlan.OFF_STORED_WAITING + note;
            }
            return restoreLocked(app) + note;
        }
    }

    // ------------------------------------------------------------------------------------------ switching on and off (under the lock)

    private static void clearStored(Context app) {
        SharedPreferences.Editor e = prefs(app).edit().remove("on").remove("fmt").remove("orig2").remove("bt");
        for (int i = 0; i < 5; i++) {
            e.remove("o" + i);
        }
        e.commit();
    }

    private static String applyLocked(Context app) throws IOException {
        if (isOn(app)) {
            return SaverPlan.ALREADY_ON;
        }
        try {
            Priv.Result r = Priv.run(SaverPlan.readCommand(), 15);
            if (!r.ok()) {
                throw new Actions.NotDone("Couldn't read the phone's settings: " + r.out);
            }
            // A remote screen session may have raised the screen timeout: the saver then leaves it alone, and remembers the
            // session's record of the timeout from before (the session puts the saver's value back when it ends).
            SaverPlan.Run run = SaverPlan.plan(SaverPlan.parseRead(r.out), RemoteProfile.ownsTimeout(app), RemoteProfile.originalTimeout(app));
            if (run.readable == 0) {
                throw new Actions.NotDone(SaverPlan.COULD_NOT_READ);
            }
            // Save the originals before changing anything, so a crash half-way can still be undone. The older version stored
            // them as o0..o4 and bt: a new run replaces those.
            SharedPreferences.Editor e = prefs(app).edit().remove("bt").putInt("fmt", 2).putString("orig2", run.stored()).putBoolean("on", true);
            for (int i = 0; i < 5; i++) {
                e.remove("o" + i);
            }
            if (!e.commit()) {
                throw new Actions.NotDone("Couldn't save the phone's settings first, so LinkMeter's saver was not switched on.");
            }
            SaverPlan.Applied a = new SaverPlan.Applied();
            if (!run.writes().isEmpty()) {
                a = SaverPlan.check(run, Priv.run(SaverPlan.writeCommand(run), 20).out);
            }
            String said = SaverPlan.appliedSentence(run, a);
            if (!SaverPlan.done(a)) {
                if (a.changed.isEmpty()) {
                    clearStored(app); // nothing was changed: there is nothing to put back
                }
                throw new Actions.NotDone(said);
            }
            arm(app, elapsed() + SaverPlan.IDLE_MS);
            watchScreen(app);
            return withSystemLocked(app, said); // the saver IS on: only the extra can fail, and the answer then says both
        } catch (IOException e) {
            failedAt = elapsed();
            throw e;
        }
    }

    /** What putting the saver back would do, from whichever form the settings were stored in. */
    private static SaverPlan.Restore restorePlan(Context app) {
        SharedPreferences p = prefs(app);
        boolean session = RemoteProfile.ownsTimeout(app);
        if (p.getInt("fmt", 0) >= 2) {
            return SaverPlan.restore(SaverPlan.parse(p.getString("orig2", "")), false, session);
        }
        // a profile applied by the version before this one: o0..o4 in its own order, and "bt" = it had switched Bluetooth off
        String[] o = new String[5];
        for (int i = 0; i < o.length; i++) {
            o[i] = p.getString("o" + i, null);
        }
        return SaverPlan.restore(SaverPlan.parseOld(o), "1".equals(p.getString("bt", "0")), session);
    }

    private static String restoreLocked(Context app) throws IOException {
        // Android's own Battery saver goes first, and only when LinkMeter switched it on. When its line cannot run (Shizuku is down)
        // this throws and everything stays as it is, so the next try does both.
        String sys = SystemSaver.restore(systemEnv(app)).text;
        if (!isOn(app)) {
            return sys.isEmpty() ? SaverPlan.ALREADY_OFF : SaverPlan.ALREADY_OFF + " " + sys;
        }
        SaverPlan.Restore r = restorePlan(app);
        try {
            if (r.count > 0) {
                Priv.Result res = Priv.run(r.command, 20);
                if (!res.ok()) {
                    throw new Actions.NotDone("Couldn't restore everything: " + res.out);
                }
            }
            if (r.sessionTimeout != null) {
                RemoteProfile.adoptOriginalTimeout(app, r.sessionTimeout); // the session puts the user's timeout back when it ends
            }
            clearStored(app); // only now: a failed restore leaves the saver on, so it is tried again
            disarm(app);
            unwatchScreen(app);
            return SaverPlan.restoredSentence(r) + (sys.isEmpty() ? "" : " " + sys);
        } catch (IOException e) {
            failedAt = elapsed();
            throw e;
        }
    }

    // ------------------------------------------------------------------------------------------ Android's own Battery saver (the option)

    /** True while LinkMeter has switched Android's own Battery saver on and holds the setting's original, to put back. */
    static boolean systemOn(Context c) {
        return prefs(c).getString("sys", null) != null;
    }

    /** The real phone behind {@link SystemSaver}'s sequence (the same one the offline test runs against a fake phone). */
    private static SystemSaver.Env systemEnv(final Context app) {
        return new SystemSaver.Env() {
            @Override
            public String shell(String cmd, int seconds) throws IOException {
                return Priv.run(cmd, seconds).out;
            }

            @Override
            public Boolean osOn() {
                return Radios.powerSave(app);
            }

            @Override
            public Boolean waitOs(boolean wantOn, long ms) {
                return Radios.waitPowerSave(app, wantOn, ms);
            }

            @Override
            public Boolean hotspot() {
                return Radios.hotspot(app);
            }

            @Override
            public void pause(long ms) {
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public String stored() {
                return prefs(app).getString("sys", null);
            }

            @Override
            public boolean store(String original) {
                return prefs(app).edit().putString("sys", original).commit();
            }

            @Override
            public void forget() {
                prefs(app).edit().remove("sys").commit();
            }
        };
    }

    /**
     * Under the lock. Brings Android's own Battery saver in line with the option while the saver is on: switched on when the option
     * is on and LinkMeter has not done it yet, put back when the option is off and LinkMeter did it. Returns what to tell the user
     * ("" = nothing to say). A switch that did not move is an error answer; the option is given up when the hotspot went with it.
     */
    private static String systemInLineLocked(Context app) throws IOException {
        boolean want = Cfg.ecoSystem(app);
        if (want && isOn(app)) {
            SystemSaver.Outcome o = SystemSaver.apply(systemEnv(app));
            if (!o.ok) {
                if (o.giveUp) {
                    Cfg.setEcoSystem(app, false);
                }
                throw new Actions.NotDone(o.text);
            }
            return o.text;
        }
        if (!want && systemOn(app)) {
            SystemSaver.Outcome o = SystemSaver.restore(systemEnv(app));
            if (!o.ok) {
                throw new Actions.NotDone(o.text);
            }
            return o.text;
        }
        return "";
    }

    /** The saver's own answer with the option's added; when the option fails the saver stays as it is and the error names both. */
    private static String withSystemLocked(Context app, String base) throws IOException {
        try {
            String sys = systemInLineLocked(app);
            return sys.isEmpty() ? base : base + " " + sys;
        } catch (Actions.NotDone e) {
            throw new Actions.NotDone(base + " " + e.getMessage());
        }
    }

    /**
     * The option's own switch (the tablet's "also Android's Battery saver", and the phone's own switch): stored first, then carried
     * out at once when the saver is on. When the saver is not on the option waits for it. Nothing here runs on its own later: a
     * switch that fails is not tried again until it is pressed again (a loop of failing shell lines would only cost battery).
     */
    static String setSystem(Context c, boolean want) throws IOException {
        Context app = c.getApplicationContext();
        appCtx = app;
        Cfg.setEcoSystem(app, want);
        synchronized (Eco.class) {
            if (want) {
                if (!isOn(app)) {
                    return Cfg.ecoAuto(app) ? SystemSaver.STORED_WAITING : SystemSaver.STORED_SAVER_OFF;
                }
                if (!Priv.ready()) {
                    return SystemSaver.STORED_NEEDS_SHIZUKU;
                }
                return systemInLineLocked(app);
            }
            if (!systemOn(app)) {
                return SystemSaver.STORED_LEFT_ALONE;
            }
            if (!Priv.ready()) {
                return SystemSaver.STORED_BACK_WAITING;
            }
            return systemInLineLocked(app);
        }
    }

    // ------------------------------------------------------------------------------------------ when it ends by itself

    /**
     * Sets the alarm, unless it is set. It does not wake the phone (elapsed real time, not a wake-up alarm) and needs no
     * permission: after Standby or any deep sleep it goes off at the first wake after it was due.
     */
    private static void arm(Context app, long atElapsed) {
        if (!armed.compareAndSet(false, true)) {
            return;
        }
        appCtx = app;
        try {
            app.getSystemService(AlarmManager.class).set(AlarmManager.ELAPSED_REALTIME, Math.max(atElapsed, elapsed() + 1000), ALARM_TAG, ALARM, H);
        } catch (RuntimeException e) {
            armed.set(false); // no alarm service: the screen receiver and the next request still look at it
        }
    }

    private static void disarm(Context app) {
        armed.set(false);
        try {
            app.getSystemService(AlarmManager.class).cancel(ALARM);
        } catch (RuntimeException ignored) {
            // no alarm service
        }
    }

    private static void startIdleCheck(final Context app) {
        new Thread(() -> {
            try {
                idleCheck(app);
            } catch (RuntimeException e) {
                arm(app, elapsed() + SaverPlan.IDLE_MS); // look again later rather than never
            }
        }, "linkmeter-eco-idle").start();
    }

    private static boolean tabletAnswers(Context app) {
        String ip = Cfg.tabletIp(app);
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        try {
            return InetAddress.getByName(ip).isReachable(PING_MS);
        } catch (IOException | RuntimeException e) {
            return false; // does not answer, or this phone will not ask: the saver ends, which is the safe side
        }
    }

    /**
     * The alarm went off, or the screen came on. Runs on its own thread. One at a time: a second one waits and then finds out the
     * first one's result.
     */
    private static void idleCheck(Context app) {
        synchronized (IDLE_LOCK) {
            long now = elapsed();
            boolean ready = Priv.ready();
            Boolean hotspot = Radios.hotspot(app);
            boolean screen = Radios.screenOn(app);
            Boolean answers = null;
            SaverPlan.Idle d = SaverPlan.idle(Cfg.ecoAuto(app), isOn(app), now, lastSeen, hotspot, screen, null, ready);
            if (d.verdict == SaverPlan.Verdict.ASK_TABLET) {
                answers = Boolean.valueOf(tabletAnswers(app));
                now = elapsed();
                d = SaverPlan.idle(Cfg.ecoAuto(app), isOn(app), now, lastSeen, hotspot, screen, answers, ready);
            }
            if (d.verdict == SaverPlan.Verdict.RESTORE) {
                synchronized (Eco.class) {
                    // asked again under the lock, with the newest facts: a request that arrived meanwhile must win
                    now = elapsed();
                    d = SaverPlan.idle(Cfg.ecoAuto(app), isOn(app), now, lastSeen, hotspot, screen, answers, Priv.ready());
                    if (d.verdict == SaverPlan.Verdict.RESTORE) {
                        try {
                            restoreLocked(app);
                            return; // the alarm and the receiver went with it
                        } catch (IOException | RuntimeException e) {
                            d = new SaverPlan.Idle(SaverPlan.Verdict.LOOK_AGAIN, SaverPlan.IDLE_MS);
                        }
                    }
                }
            }
            if (d.verdict == SaverPlan.Verdict.NOTHING_TO_WATCH) {
                disarm(app);
                unwatchScreen(app);
            } else {
                // LOOK_AGAIN, or a verdict that cannot be acted on any more (the facts changed under us): look again later rather than never
                arm(app, now + (d.verdict == SaverPlan.Verdict.LOOK_AGAIN ? d.waitMs : SaverPlan.IDLE_MS));
            }
        }
    }

    // ------------------------------------------------------------------------------------------ the screen coming on

    /** Listens for the screen coming on while the saver is on. Costs nothing between screen events. */
    private static void watchScreen(final Context app) {
        synchronized (WATCH) {
            if (screenReceiver != null) {
                return;
            }
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent i) {
                    startIdleCheck(ctx.getApplicationContext());
                }
            };
            try {
                IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
                if (Build.VERSION.SDK_INT >= 33) {
                    app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED); // the system's own broadcast still arrives
                } else {
                    app.registerReceiver(r, f);
                }
                screenReceiver = r;
            } catch (RuntimeException e) {
                screenReceiver = null; // the alarm still does the job, only up to ten minutes later
            }
        }
    }

    private static void unwatchScreen(Context app) {
        synchronized (WATCH) {
            if (screenReceiver != null) {
                try {
                    app.unregisterReceiver(screenReceiver);
                } catch (RuntimeException ignored) {
                    // was not registered
                }
                screenReceiver = null;
            }
        }
    }
}
