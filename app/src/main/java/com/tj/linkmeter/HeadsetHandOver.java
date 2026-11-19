package com.tj.linkmeter;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Tablet only: moves the headset between the phone and this tablet, one connection at a time (docs/FLOATING_PHONE.md). Connecting it to the
 * tablet first asks the phone to let go; connecting it to the phone first lets go of the tablet. What to do in which order is decided by
 * {@link HandOverRules}; this carries the steps out through the same two ways as everywhere else (the phone: {@link CallWatch} and the
 * /call/headset routes; the tablet: {@link TabletHeadset}, every switch read back) and says what happened. Used by the Headset screen, the
 * floating phone's two Bluetooth buttons and "Connect and call". Everything is called and answered on the screen's thread.
 */
final class HeadsetHandOver {
    /** A line to show while a step runs. */
    interface Progress {
        void say(String text);
    }

    /** The end. {@code connected}: the headset is where it was wanted; {@code say}: what to tell the person; {@code bad}: it is a problem (red). */
    interface Done {
        void done(boolean connected, String say, boolean bad);
    }

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private HeadsetHandOver() {}

    private static final class Run {
        Context ctx;
        CallWatch watch;
        TabletHeadset tablet;
        TabletHeadsetRules.Dev dev;
        String phoneAddr = "";
        String name = "";
        List<HandOverRules.Step> steps;
        BooleanSupplier mayAsk;
        Progress progress;
        Done done;
        final StringBuilder notes = new StringBuilder();
    }

    private static boolean phoneHasHeadset(CallWatch w) {
        return w.status() == CallWatch.OK && !w.latest().headset.isEmpty();
    }

    private static Run prepare(Context ctx, CallWatch w, BooleanSupplier mayAsk, Progress p, Done d) {
        Run r = new Run();
        r.ctx = ctx;
        r.watch = w;
        r.tablet = TabletHeadset.of(ctx);
        r.mayAsk = mayAsk;
        r.progress = p;
        r.done = d;
        return r;
    }

    /** Connects the headset to THIS TABLET; the phone lets go first (unless the headset can keep both, or a call is on: then it asks to end the call). */
    static void toTablet(Context ctx, CallWatch w, BooleanSupplier mayAsk, Progress p, Done d) {
        Run r = prepare(ctx, w, mayAsk, p, d);
        boolean phoneOn = phoneHasHeadset(w);
        TabletHeadsetRules.Facts f = r.tablet.facts("", w.latest().headset, phoneOn);
        if (!f.hasBluetooth) {
            d.done(false, "This tablet has no Bluetooth.", true);
            return;
        }
        if (!f.bluetoothOn) {
            d.done(false, "Bluetooth is off on this tablet. Switch it on first.", true);
            return;
        }
        r.dev = TabletHeadsetRules.pick(f);
        if (r.dev == null) {
            d.done(false, f.bonded.isEmpty() ? "No headset is paired with this tablet yet. Pair it first (Calls > headset > This tablet)."
                    : "Choose the headset for this tablet first (Calls > headset > This tablet).", true);
            return;
        }
        r.name = r.dev.name;
        boolean callOn = w.status() == CallWatch.OK && w.lead() != null;
        HandOverRules.Plan plan = HandOverRules.plan(HandOverRules.Target.TABLET, phoneOn, r.dev.connected, callOn, Cfg.headsetBoth(ctx));
        if (plan.refused()) {
            d.done(false, plan.refusal, true);
            return;
        }
        if (plan.steps.isEmpty()) {
            d.done(true, r.name + " is already connected to this tablet.", false);
            return;
        }
        r.steps = plan.steps;
        run(r, 0);
    }

    /**
     * Connects the headset to the PHONE ({@code addr} "" = the one the user chose there); this tablet lets go first (unless the headset can
     * keep both). When the tablet's own list can only be asked for a tap, it stops there and says so: the headset is still on the tablet.
     */
    static void toPhone(Context ctx, CallWatch w, String addr, BooleanSupplier mayAsk, Progress p, Done d) {
        Run r = prepare(ctx, w, mayAsk, p, d);
        r.phoneAddr = addr == null ? "" : addr;
        boolean phoneOn = phoneHasHeadset(w);
        TabletHeadsetRules.Facts f = r.tablet.facts(r.phoneAddr, w.latest().headset, phoneOn);
        TabletHeadsetRules.Dev dev = f.hasBluetooth && f.bluetoothOn ? TabletHeadsetRules.pick(f) : null;
        boolean sameHeadset = dev != null && (r.phoneAddr.isEmpty() || dev.addr.equalsIgnoreCase(r.phoneAddr));
        boolean tabletOn = sameHeadset && dev.connected;
        r.dev = sameHeadset ? dev : null;
        r.name = dev != null ? dev.name : w.latest().headset;
        HandOverRules.Plan plan = HandOverRules.plan(HandOverRules.Target.PHONE, phoneOn, tabletOn, false, Cfg.headsetBoth(ctx));
        if (plan.steps.isEmpty()) {
            d.done(true, (r.name.isEmpty() ? "The headset" : r.name) + " is already connected to the phone.", false);
            return;
        }
        r.steps = plan.steps;
        run(r, 0);
    }

    private static void later(Runnable next) {
        UI.postDelayed(next, HandOverRules.SETTLE_MS);
    }

    private static void run(final Run r, final int i) {
        if (i >= r.steps.size()) {
            r.done.done(true, "", false);
            return;
        }
        final HandOverRules.Step step = r.steps.get(i);
        r.progress.say(HandOverRules.working(step, r.name));
        switch (step) {
            case DISCONNECT_PHONE:
                r.watch.headsetDisconnect((res, e) -> {
                    boolean reached = e == null;
                    boolean ok = res != null && res.ok;
                    if (HandOverRules.afterPhoneDisconnect(reached, ok) == HandOverRules.Then.STOP) {
                        r.done.done(false, HandOverRules.phoneStillHolds(res == null ? "" : res.why, r.name), true);
                        return;
                    }
                    if (!reached) {
                        r.notes.append(HandOverRules.phoneNotAsked(r.name));
                    }
                    later(() -> run(r, i + 1));
                });
                break;
            case DISCONNECT_TABLET:
                r.tablet.disconnect(r.ctx, r.dev.addr, r.mayAsk, way -> {
                    if (HandOverRules.afterTabletDisconnect(way) == HandOverRules.Then.STOP) {
                        r.done.done(false, HandOverRules.tabletStillHolds(way, r.name), way == TabletHeadsetRules.Way.FAILED);
                        return;
                    }
                    later(() -> run(r, i + 1));
                });
                break;
            case CONNECT_TABLET:
                r.tablet.connect(r.ctx, r.dev.addr, r.mayAsk, way -> r.done.done(way == TabletHeadsetRules.Way.DONE,
                        TabletHeadsetRules.afterConnect(way, r.name) + r.notes, way == TabletHeadsetRules.Way.FAILED));
                break;
            default:
                r.watch.headsetConnect(r.phoneAddr, (res, e) -> {
                    String problem = e != null ? TextsTab.explain(e) : res != null && !res.ok ? res.why : "";
                    if (!problem.isEmpty()) {
                        r.done.done(false, problem, true);
                    } else {
                        r.done.done(true, res != null && !res.out.isEmpty() ? res.out : "", false);
                    }
                });
                break;
        }
    }
}
