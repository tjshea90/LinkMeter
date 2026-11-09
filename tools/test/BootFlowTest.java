package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Offline test of BootFlow (the order of "start the phone after it restarted"), against a simulated tablet, phone and two hotspots on a
 * virtual clock. Run by tools/test.sh. Prints "N checks, M failed". ASCII only.
 *
 * The simulated world knows the physical truth (is the phone on the tablet's Wi-Fi? is its own hotspot on? does Shizuku run?), the flow
 * only sees what a real tablet would see: answers that arrive every two seconds and go stale. The rules that matter are checked against
 * the truth: the tablet's hotspot is never stopped while the phone's is not on (the tablet would be stranded), the phone's hotspot is
 * never asked for while Shizuku is not running, "success" is only claimed when the tablet really is on the phone's hotspot with Shizuku
 * running, and after Cancel nothing more is sent.
 */
public final class BootFlowTest {
    static int checks;
    static int failed;

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            failed++;
            if (failed <= 30) {
                System.out.println("  FAIL: " + msg);
            }
        }
    }

    static final long S = 1000L;

    /** One simulated tablet + phone. Every field above "state" is a knob. */
    static final class World {
        long hotspotStartMs = 2 * S;
        int hotspotFailTimes;
        boolean hotspotNeverStarts;
        long userStartsOwnHotspotAt = -1;
        long phoneJoinsAfterMs = 20 * S;
        boolean phoneNeverJoins;
        boolean canWriteSecure = true;
        boolean wadbWorks = true;
        long wadbDelayMs = 2 * S;
        long wadbWorksAfterMs;
        long userManualWadbAfterMs = 20 * S;
        long userStartsShizukuAfterMs = 30 * S;
        boolean shizukuNeverStarts;
        boolean needsAllow;
        long userAllowsAfterMs = 10 * S;
        boolean phoneHotspotWorks = true;
        long phoneHotspotDelayMs = 3 * S;
        long userStartsPhoneHotspotAfterMs = 30 * S;
        boolean userNeverStartsPhoneHotspot;
        long tabletJoinsAfterMs = 8 * S;
        boolean tabletNeverJoins;
        long userJoinsTabletAfterMs = 40 * S;
        boolean shizukuDiesOnLeave;
        boolean permLostOnLeave;
        boolean hotspotStopFails;
        long hotspotDropAfterOnMs = -1;
        long phoneDropsAt = -1;
        long phoneDropMs;
        long pollMs = 2 * S;
        long cmdLatencyMs = 500;
        boolean startFine;
        boolean startPhoneHotspotOnNoShizuku;
        long userSwitchesPhoneHotspotOffAfterMs = 15 * S;

        // ---- state
        long t;
        BootFlow.Hotspot hs = BootFlow.Hotspot.OFF;
        long hsOnAt = -1;
        long hsOffAt = -1;
        int hotspotFailsLeft;
        boolean phoneOnTablet;
        long phoneOnSince = -1;
        long phoneJoinedAt = -1;
        boolean wadbOn;
        long wadbOnAt = -1;
        int priv = BootFlow.PRIV_OFF;
        boolean phoneHotspotOn;
        long phoneHotspotOnAt = -1;
        boolean tabletOnPhone;
        long hotspotOnCmdAt = -1;
        boolean droppedOnce;
        boolean hotspotDroppedOnce;
        long rejoinAt = -1;
        long userOffAt = -1;
        boolean ownHotspotDone;
        BootFlow.Facts lastHot;
        BootFlow.Facts lastNorm;
        String failNote = "";

        // ---- counters and violations
        int cStart;
        long firstStartAt = -1;
        int cStop;
        int cWadb;
        int cNotify;
        int cHotspotOn;
        boolean stranded;
        boolean hotspotOnWithoutShizuku;
        final List<long[]> events = new ArrayList<>(); // {at, kind}

        static final int EV_START_FAIL = 1;
        static final int EV_START_OK = 2;
        static final int EV_STOP = 3;
        static final int EV_WADB = 4;
        static final int EV_HOTSPOT_ON = 5;
        static final int EV_PRIV_READY = 6;

        World() {
            hotspotFailsLeft = 0;
        }

        void init() {
            hotspotFailsLeft = hotspotFailTimes;
            if (startFine) {
                tabletOnPhone = true;
                phoneHotspotOn = true;
                priv = BootFlow.PRIV_READY;
                lastNorm = facts(0, "192.168.43.1");
            } else if (startPhoneHotspotOnNoShizuku) {
                tabletOnPhone = true;
                phoneHotspotOn = true;
                priv = BootFlow.PRIV_OFF;
                lastNorm = facts(0, "192.168.43.1");
                userOffAt = userSwitchesPhoneHotspotOffAfterMs;
            }
        }

        BootFlow.Facts facts(long at, String ip) {
            BootFlow.Facts f = new BootFlow.Facts(priv, at);
            f.wadbOn = wadbOn;
            f.canWriteSecure = canWriteSecure;
            f.hotspotOn = phoneHotspotOn;
            f.ip = ip;
            return f;
        }

        void schedule(long at, int kind) {
            events.add(new long[] {at, kind});
        }

        void phoneLeavesTablet() {
            phoneOnTablet = false;
            if (shizukuDiesOnLeave && priv != BootFlow.PRIV_OFF) {
                priv = BootFlow.PRIV_OFF;
            }
            if (permLostOnLeave && priv == BootFlow.PRIV_READY) {
                priv = BootFlow.PRIV_NO_PERM;
            }
            wadbOn = false; // Android switches wireless debugging off when the Wi-Fi goes
        }

        /** Moves the world to time now: due events, then the things that happen by themselves. */
        void advance(long now) {
            t = now;
            for (int i = 0; i < events.size(); i++) {
                long[] e = events.get(i);
                if (e[0] <= now) {
                    events.remove(i);
                    i--;
                    fire((int) e[1]);
                }
            }
            if (!ownHotspotDone && userStartsOwnHotspotAt >= 0 && now >= userStartsOwnHotspotAt) {
                ownHotspotDone = true;
                if (hs != BootFlow.Hotspot.ON) {
                    hs = BootFlow.Hotspot.ON;
                    hsOnAt = now;
                }
            }
            if (!hotspotDroppedOnce && hotspotDropAfterOnMs >= 0 && hs == BootFlow.Hotspot.ON && hsOnAt >= 0 && now - hsOnAt >= hotspotDropAfterOnMs) {
                hotspotDroppedOnce = true;
                hs = BootFlow.Hotspot.OFF;
                hsOffAt = now;
                if (phoneOnTablet) {
                    phoneLeavesTablet();
                }
            }
            if (!droppedOnce && phoneDropsAt >= 0 && now >= phoneDropsAt && phoneOnTablet) {
                droppedOnce = true;
                phoneOnTablet = false;
                rejoinAt = now + phoneDropMs;
            }
            if (rejoinAt >= 0 && now >= rejoinAt) {
                rejoinAt = -1;
                if (hs == BootFlow.Hotspot.ON && !phoneHotspotOn) {
                    phoneOnTablet = true;
                    phoneOnSince = now;
                    phoneJoinedAt = now;
                }
            }
            if (hs == BootFlow.Hotspot.ON && !phoneOnTablet && !phoneHotspotOn && !phoneNeverJoins && rejoinAt < 0 && !startFine
                    && now - hsOnAt >= phoneJoinsAfterMs && !(droppedOnce && now < phoneDropsAt + phoneDropMs)) {
                phoneOnTablet = true;
                phoneOnSince = now;
                phoneJoinedAt = now;
            }
            if (hs != BootFlow.Hotspot.ON && phoneOnTablet) {
                phoneLeavesTablet();
            }
            if ((!canWriteSecure || !wadbWorks) && phoneOnTablet && !wadbOn && phoneJoinedAt >= 0 && now - phoneJoinedAt >= userManualWadbAfterMs) {
                wadbOn = true;
                wadbOnAt = now;
            }
            if (wadbOn && priv == BootFlow.PRIV_OFF && !shizukuNeverStarts && wadbOnAt >= 0 && now - wadbOnAt >= userStartsShizukuAfterMs) {
                if (needsAllow) {
                    priv = BootFlow.PRIV_NO_PERM;
                    schedule(now + userAllowsAfterMs, EV_PRIV_READY);
                } else {
                    priv = BootFlow.PRIV_READY;
                }
            }
            if (!phoneHotspotWorks && !userNeverStartsPhoneHotspot && hotspotOnCmdAt >= 0 && !phoneHotspotOn && phoneOnTablet
                    && now - hotspotOnCmdAt >= userStartsPhoneHotspotAfterMs) {
                startPhoneHotspot();
            }
            if (userOffAt >= 0 && now >= userOffAt && phoneHotspotOn && startPhoneHotspotOnNoShizuku) {
                userOffAt = -1;
                phoneHotspotOn = false;
                tabletOnPhone = false;
            }
            if (!tabletOnPhone && hs != BootFlow.Hotspot.ON && phoneHotspotOn) {
                long since = Math.max(hsOffAt, phoneHotspotOnAt);
                long wait = tabletNeverJoins ? userJoinsTabletAfterMs : tabletJoinsAfterMs;
                if (since >= 0 && now - since >= wait) {
                    tabletOnPhone = true;
                }
            }
            if (tabletOnPhone && hs == BootFlow.Hotspot.ON && !startFine && !startPhoneHotspotOnNoShizuku) {
                tabletOnPhone = false; // a tablet cannot be on the phone's hotspot and host its own at once (single radio in this model)
            }
        }

        void startPhoneHotspot() {
            phoneHotspotOn = true;
            phoneHotspotOnAt = t;
            if (phoneOnTablet) {
                phoneLeavesTablet();
            }
        }

        void fire(int kind) {
            switch (kind) {
                case EV_START_FAIL:
                    hs = BootFlow.Hotspot.FAILED;
                    failNote = "no group";
                    break;
                case EV_START_OK:
                    if (hs == BootFlow.Hotspot.STARTING) {
                        hs = BootFlow.Hotspot.ON;
                        hsOnAt = t;
                    }
                    break;
                case EV_STOP:
                    if (!hotspotStopFails) {
                        hs = BootFlow.Hotspot.OFF;
                        hsOffAt = t;
                        if (phoneOnTablet) {
                            phoneLeavesTablet();
                        }
                    }
                    break;
                case EV_WADB:
                    if (phoneOnTablet && canWriteSecure && wadbWorks && !wadbOn && t >= wadbWorksAfterMs) {
                        wadbOn = true;
                        wadbOnAt = t;
                    }
                    break;
                case EV_HOTSPOT_ON:
                    if (phoneOnTablet && !phoneHotspotOn && priv == BootFlow.PRIV_READY) { // the phone needs Shizuku to start its hotspot
                        startPhoneHotspot();
                    }
                    break;
                case EV_PRIV_READY:
                    if (priv == BootFlow.PRIV_NO_PERM) {
                        priv = BootFlow.PRIV_READY;
                    }
                    break;
                default:
                    break;
            }
        }

        BootFlow.Obs obs() {
            BootFlow.Obs o = new BootFlow.Obs();
            o.now = t;
            o.hotspot = hs;
            o.hotspotInfo = hs == BootFlow.Hotspot.ON ? "DIRECT-LM-Tablet" : failNote;
            if (t % pollMs == 0) {
                if (hs == BootFlow.Hotspot.ON && phoneOnTablet) {
                    lastHot = facts(t, "192.168.49.2");
                }
                if (tabletOnPhone && phoneHotspotOn) {
                    lastNorm = facts(t, "192.168.43.1");
                }
            }
            o.onHotspot = lastHot;
            o.onNormal = lastNorm;
            return o;
        }

        void apply(List<BootFlow.Cmd> cmds, boolean cancelled) {
            for (BootFlow.Cmd c : cmds) {
                switch (c) {
                    case START_HOTSPOT:
                        cStart++;
                        if (firstStartAt < 0) {
                            firstStartAt = t;
                        }
                        hs = BootFlow.Hotspot.STARTING;
                        if (hotspotNeverStarts || hotspotFailsLeft > 0) {
                            if (hotspotFailsLeft > 0) {
                                hotspotFailsLeft--;
                            }
                            schedule(t + cmdLatencyMs, EV_START_FAIL);
                        } else {
                            schedule(t + cmdLatencyMs + hotspotStartMs, EV_START_OK);
                        }
                        break;
                    case STOP_HOTSPOT:
                        cStop++;
                        if (phoneOnTablet && !phoneHotspotOn && phoneOnSince >= 0 && t - phoneOnSince >= 5 * S) {
                            stranded = true; // the phone has been on the tablet's Wi-Fi for a while and its hotspot is not on
                        }
                        schedule(t + cmdLatencyMs, EV_STOP);
                        break;
                    case WADB_ON:
                        cWadb++;
                        schedule(t + cmdLatencyMs + wadbDelayMs, EV_WADB);
                        break;
                    case NOTIFY:
                        cNotify++;
                        break;
                    case HOTSPOT_ON:
                        cHotspotOn++;
                        // rule 2 is about what the flow knew: the phone's own last answer (at most FRESH_MS old) must have said READY
                        if (lastHot == null || t - lastHot.at > BootFlow.FRESH_MS || lastHot.priv != BootFlow.PRIV_READY) {
                            hotspotOnWithoutShizuku = true;
                        }
                        hotspotOnCmdAt = t;
                        if (phoneHotspotWorks) {
                            schedule(t + cmdLatencyMs + phoneHotspotDelayMs, EV_HOTSPOT_ON);
                        }
                        break;
                    default:
                        break;
                }
            }
        }

        boolean truthSuccess() {
            return tabletOnPhone && phoneHotspotOn && priv == BootFlow.PRIV_READY;
        }
    }

    static final class Run {
        BootFlow flow;
        World w;
        long finishedAt = -1;
        boolean commandsAfterCancel;
        boolean cancelled;
        int ticks;
    }

    static long limit() {
        return BootFlow.OVERALL_CAP_MS + 120 * S;
    }

    static Run simulate(World w, long cancelAt) {
        Run r = new Run();
        r.w = w;
        r.flow = new BootFlow();
        w.init();
        w.advance(0);
        r.flow.start(w.obs());
        long lim = limit();
        for (long t = 0; t <= lim; t += S) {
            w.advance(t);
            BootFlow.Obs o = w.obs();
            if (t == cancelAt) {
                r.flow.cancel();
                r.cancelled = true;
            }
            List<BootFlow.Cmd> cmds = r.flow.tick(o);
            r.ticks++;
            if (r.cancelled && !cmds.isEmpty()) {
                r.commandsAfterCancel = true;
            }
            w.apply(cmds, r.cancelled);
            if (r.flow.finished()) {
                r.finishedAt = t;
                break;
            }
        }
        // let the last commands take effect
        for (long t = r.finishedAt < 0 ? lim : r.finishedAt; t <= (r.finishedAt < 0 ? lim : r.finishedAt) + 20 * S; t += S) {
            w.advance(t);
        }
        return r;
    }

    static void rules(String name, Run r) {
        World w = r.w;
        check(!w.stranded, name + ": the tablet's hotspot was stopped while the phone's was not on (the tablet would be stranded)");
        check(!w.hotspotOnWithoutShizuku, name + ": the phone was asked to start its hotspot while Shizuku was not running");
        check(w.cStart <= BootFlow.MAX_HOTSPOT_STARTS * (BootFlow.MAX_BACK_TO_HOTSPOT + 1), name + ": too many hotspot starts: " + w.cStart);
        check(w.cWadb <= BootFlow.MAX_WADB_SENDS * (BootFlow.MAX_BACK_TO_HOTSPOT + 2), name + ": too many wireless debugging requests: " + w.cWadb);
        check(w.cNotify <= BootFlow.MAX_BACK_TO_HOTSPOT + 2, name + ": the notification was asked for " + w.cNotify + " times");
        check(w.cHotspotOn <= BootFlow.MAX_HOTSPOT_ON_SENDS, name + ": too many phone hotspot requests: " + w.cHotspotOn);
        check(w.cStop <= BootFlow.MAX_STOP_SENDS, name + ": too many stop requests: " + w.cStop);
        check(r.finishedAt >= 0, name + ": the flow never finished");
        check(!r.commandsAfterCancel, name + ": a command was given after Cancel");
        BootFlow.Outcome oc = r.flow.outcome();
        if (oc == BootFlow.Outcome.SUCCESS) {
            check(w.truthSuccess(), name + ": SUCCESS was claimed but the tablet is not on the phone's hotspot with Shizuku running");
        }
        if (oc == BootFlow.Outcome.SHIZUKU_DIED) {
            check(w.priv == BootFlow.PRIV_OFF, name + ": SHIZUKU_DIED but Shizuku runs");
        }
        if (oc == BootFlow.Outcome.NOT_ALLOWED) {
            check(w.priv == BootFlow.PRIV_NO_PERM, name + ": NOT_ALLOWED but the state is " + w.priv);
        }
        if (oc == BootFlow.Outcome.ALREADY_FINE) {
            check(w.startFine, name + ": ALREADY_FINE for a world that was not fine");
        }
        check(!r.flow.headline().isEmpty() || oc == BootFlow.Outcome.CANCELLED, name + ": no headline");
    }

    static Run expect(String name, World w, BootFlow.Outcome want) {
        Run r = simulate(w, -1);
        rules(name, r);
        check(r.flow.outcome() == want, name + ": outcome " + r.flow.outcome() + ", wanted " + want + " (finished at " + r.finishedAt / S + " s)\n" + r.flow.render());
        return r;
    }

    public static void main(String[] args) {
        stories();
        cancelEverywhere();
        fuzz();
        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static void stories() {
        // 1. the happy path
        World w = new World();
        Run r = expect("happy path", w, BootFlow.Outcome.SUCCESS);
        check(r.finishedAt < 150 * S, "happy path takes under 2.5 minutes of simulated time: " + r.finishedAt / S + " s");
        check(w.cStart == 1 && w.cWadb >= 1 && w.cNotify == 1 && w.cHotspotOn == 1 && w.cStop == 1, "happy path: one of each command: start " + w.cStart + " wadb "
                + w.cWadb + " notify " + w.cNotify + " hotspot " + w.cHotspotOn + " stop " + w.cStop);
        check(w.hs == BootFlow.Hotspot.OFF && w.tabletOnPhone && w.phoneHotspotOn && w.priv == BootFlow.PRIV_READY, "happy path: end state");
        check(w.firstStartAt == 0, "the first hotspot start is given on the very first tick, not after a wait: " + w.firstStartAt);
        for (int i = 0; i < 7; i++) {
            BootFlow.Line l = r.flow.line(i);
            check(l == BootFlow.Line.DONE || l == BootFlow.Line.SKIPPED, "happy path: step " + (i + 1) + " is " + l);
        }
        check(r.flow.render().split("\n").length >= 7, "the card shows at least seven lines");
        check(r.flow.render().contains("Tablet hotspot") && r.flow.render().contains("Shizuku started"), "the card names its steps");

        // 2. nothing to do
        w = new World();
        w.startFine = true;
        r = expect("already fine", w, BootFlow.Outcome.ALREADY_FINE);
        check(w.cStart + w.cStop + w.cWadb + w.cNotify + w.cHotspotOn == 0, "already fine: no command at all");
        check(r.finishedAt == 0, "already fine: finished on the first tick");
        BootFlow f = new BootFlow();
        BootFlow.Obs o = new BootFlow.Obs();
        o.now = 1000;
        check(f.tick(o).isEmpty() && !f.running() && !f.finished(), "a flow that was never started does nothing");

        // 3. the phone's hotspot is on and Shizuku is dead: the user has to switch it off first, nothing is started before
        w = new World();
        w.startPhoneHotspotOnNoShizuku = true;
        w.userSwitchesPhoneHotspotOffAfterMs = 40 * S;
        r = simulate(w, -1);
        rules("phone hotspot on at start", r);
        check(r.flow.outcome() == BootFlow.Outcome.SUCCESS, "phone hotspot on at start: " + r.flow.outcome() + "\n" + r.flow.render());
        check(r.finishedAt > 40 * S, "the flow waited for the user to switch the phone's hotspot off: " + r.finishedAt / S);
        BootFlow g = new BootFlow();
        World w3 = new World();
        w3.startPhoneHotspotOnNoShizuku = true;
        w3.init();
        w3.advance(0);
        g.start(w3.obs());
        check(g.stage() == BootFlow.Stage.PHONE_HOTSPOT_OFF && g.line(0) == BootFlow.Line.NEEDS, "asks for the phone's hotspot to be switched off");
        check(g.headline().toLowerCase().contains("hotspot") && g.headline().toLowerCase().contains("off"), "and says so: " + g.headline());
        boolean early = false;
        for (long t = 0; t < 20 * S; t += S) {
            w3.advance(t);
            early |= !g.tick(w3.obs()).isEmpty();
        }
        check(!early, "no command while the phone's hotspot is still on");

        // 4. the tablet's hotspot fails twice, then works
        w = new World();
        w.hotspotFailTimes = 2;
        r = expect("hotspot fails twice", w, BootFlow.Outcome.SUCCESS);
        check(w.cStart == 3, "three starts: " + w.cStart);

        // 5. the tablet cannot make a hotspot: needs the user (NetShare), and carries on when one appears
        w = new World();
        w.hotspotNeverStarts = true;
        w.userStartsOwnHotspotAt = 150 * S;
        r = expect("tablet hotspot never starts, user starts one", w, BootFlow.Outcome.SUCCESS);
        check(w.cStart == BootFlow.MAX_HOTSPOT_STARTS, "it tried " + BootFlow.MAX_HOTSPOT_STARTS + " times and then stopped trying: " + w.cStart);
        w = new World();
        w.hotspotNeverStarts = true;
        r = expect("tablet hotspot never starts, nobody helps", w, BootFlow.Outcome.TIMED_OUT);
        check(r.flow.line(0) == BootFlow.Line.NEEDS && w.cStart == BootFlow.MAX_HOTSPOT_STARTS, "the first step shows that it needs you; no endless retries");
        check(r.finishedAt >= BootFlow.OVERALL_CAP_MS, "it kept watching until the overall limit: " + r.finishedAt / S);

        // 6. the phone joins late
        w = new World();
        w.phoneJoinsAfterMs = 400 * S;
        r = expect("phone joins after 400 s", w, BootFlow.Outcome.SUCCESS);
        w = new World();
        w.phoneNeverJoins = true;
        r = expect("phone never joins", w, BootFlow.Outcome.TIMED_OUT);
        check(r.flow.line(1) == BootFlow.Line.NEEDS, "step 2 shows that it needs you");
        check(w.cHotspotOn == 0 && w.cStop == 0 && w.cWadb == 0, "nothing was sent to a phone that never came");
        check(w.hs == BootFlow.Hotspot.ON, "the tablet's hotspot is left on for the user");

        // 7. LinkMeter may not switch wireless debugging on: skipped, the user does it
        w = new World();
        w.canWriteSecure = false;
        r = expect("no permission to switch wireless debugging on", w, BootFlow.Outcome.SUCCESS);
        check(r.flow.line(2) == BootFlow.Line.SKIPPED && w.cWadb == 0, "step 3 skipped, nothing sent: wadb " + w.cWadb);
        // 8. it has the permission but the switch does not work
        w = new World();
        w.wadbWorks = false;
        w.userManualWadbAfterMs = 120 * S;
        r = expect("wireless debugging switch fails", w, BootFlow.Outcome.SUCCESS);
        check(w.cWadb == BootFlow.MAX_WADB_SENDS, "it asked " + BootFlow.MAX_WADB_SENDS + " times and then stopped: " + w.cWadb);

        // 9. Shizuku never starts
        w = new World();
        w.shizukuNeverStarts = true;
        r = expect("Shizuku never starts", w, BootFlow.Outcome.TIMED_OUT);
        check(w.cHotspotOn == 0 && w.cStop == 0, "no hotspot request and no stop without Shizuku");
        check(r.flow.line(3) == BootFlow.Line.NEEDS, "step 4 needs you");
        check(w.hs == BootFlow.Hotspot.ON && w.phoneOnTablet, "the tablet and the phone are still connected");

        // 10. Shizuku runs but LinkMeter must be allowed first
        w = new World();
        w.needsAllow = true;
        r = expect("Shizuku needs Allow", w, BootFlow.Outcome.SUCCESS);
        w = new World();
        w.needsAllow = true;
        w.userAllowsAfterMs = 3000 * S;
        r = expect("nobody taps Allow", w, BootFlow.Outcome.TIMED_OUT);
        check(w.cHotspotOn == 0, "no hotspot request while LinkMeter is not allowed");

        // 11. the phone's hotspot does not start by itself
        w = new World();
        w.phoneHotspotWorks = false;
        w.userStartsPhoneHotspotAfterMs = 100 * S;
        r = expect("phone hotspot by hand", w, BootFlow.Outcome.SUCCESS);
        check(w.cHotspotOn == BootFlow.MAX_HOTSPOT_ON_SENDS, "it asked twice: " + w.cHotspotOn);
        w = new World();
        w.phoneHotspotWorks = false;
        w.userNeverStartsPhoneHotspot = true;
        r = expect("phone hotspot never starts", w, BootFlow.Outcome.TIMED_OUT);
        check(w.cStop == 0 && w.hs == BootFlow.Hotspot.ON, "the tablet's hotspot is not stopped while the phone's is off");
        check(r.flow.line(4) == BootFlow.Line.NEEDS, "step 5 needs you");

        // 12. the tablet does not join the phone's hotspot by itself
        w = new World();
        w.tabletNeverJoins = true;
        w.userJoinsTabletAfterMs = 150 * S;
        r = expect("tablet joins by hand", w, BootFlow.Outcome.SUCCESS);
        check(r.flow.line(5) == BootFlow.Line.DONE, "step 6 was marked as needing you and then done");
        w = new World();
        w.tabletNeverJoins = true;
        w.userJoinsTabletAfterMs = 1_000_000 * S;
        r = expect("tablet never joins", w, BootFlow.Outcome.TIMED_OUT);
        check(r.flow.line(5) == BootFlow.Line.NEEDS, "step 6 needs you");

        // 13. Shizuku does not survive the phone leaving the tablet's Wi-Fi: never reported as success
        w = new World();
        w.shizukuDiesOnLeave = true;
        r = expect("Shizuku dies on leave", w, BootFlow.Outcome.SHIZUKU_DIED);
        check(r.flow.finalText().toLowerCase().contains("shizuku stopped"), "and says what happened: " + r.flow.finalText());
        // 14. Shizuku survives but loses LinkMeter's permission
        w = new World();
        w.permLostOnLeave = true;
        r = expect("permission lost on leave", w, BootFlow.Outcome.NOT_ALLOWED);

        // 15. Android removes the tablet's hotspot while the flow waits: it is started again
        w = new World();
        w.hotspotDropAfterOnMs = 25 * S;
        w.phoneJoinsAfterMs = 60 * S;
        r = expect("tablet hotspot dropped once", w, BootFlow.Outcome.SUCCESS);
        check(w.cStart >= 2, "the hotspot was started again: " + w.cStart);

        // 15b. Android removes the tablet's hotspot while the phone is being asked for its hotspot: nothing may be taken as confirmed
        w = new World();
        w.phoneHotspotWorks = false;
        w.userStartsPhoneHotspotAfterMs = 400 * S;
        w.hotspotDropAfterOnMs = 70 * S;
        w.phoneJoinsAfterMs = 10 * S;
        r = simulate(w, -1);
        rules("hotspot dropped while the phone is asked for its hotspot", r);
        check(w.cStop == 0 || w.phoneHotspotOn, "the tablet's hotspot is only stopped for real after the phone's is on: stop " + w.cStop + " phone hotspot " + w.phoneHotspotOn);

        // 16. the phone drops off the tablet's Wi-Fi for a short time (wireless debugging restarts the phone's debug service)
        w = new World();
        w.phoneDropsAt = 40 * S;
        w.phoneDropMs = 12 * S;
        r = expect("short drop of the phone", w, BootFlow.Outcome.SUCCESS);
        w = new World();
        w.phoneDropsAt = 40 * S;
        w.phoneDropMs = 50 * S;
        r = expect("long drop of the phone", w, BootFlow.Outcome.SUCCESS);

        // 16b. the debug service is not ready at first: every request of the first join is used up, the phone leaves and rejoins, and
        // the switch is asked for again (a new Wi-Fi connection starts with wireless debugging off)
        w = new World();
        w.wadbWorksAfterMs = 100 * S;
        w.phoneDropsAt = 70 * S;
        w.phoneDropMs = 50 * S;
        r = expect("wireless debugging asked for again after a new join", w, BootFlow.Outcome.SUCCESS);
        check(w.cWadb > BootFlow.MAX_WADB_SENDS, "more requests than one join allows, because it joined again: " + w.cWadb);

        // 17. the tablet cannot stop its hotspot: stop is asked a few times, never more
        w = new World();
        w.hotspotStopFails = true;
        r = expect("stop never works", w, BootFlow.Outcome.TIMED_OUT);
        check(w.cStop == BootFlow.MAX_STOP_SENDS, "stop was asked " + BootFlow.MAX_STOP_SENDS + " times and no more: " + w.cStop);
    }

    static void cancelEverywhere() {
        for (long at = 0; at <= 240 * S; at += S) {
            World w = new World();
            Run r = simulate(w, at);
            check(!r.commandsAfterCancel, "cancel at " + at / S + " s: a command followed");
            if (r.cancelled) {
                check(r.flow.outcome() == BootFlow.Outcome.CANCELLED && r.flow.finished(), "cancel at " + at / S + " s: stopped");
                check(!w.stranded && !w.hotspotOnWithoutShizuku, "cancel at " + at / S + " s: no stranding");
            } else {
                check(r.flow.outcome() == BootFlow.Outcome.SUCCESS, "cancel after the end changes nothing: " + r.flow.outcome());
            }
        }
        BootFlow f = new BootFlow();
        f.cancel();
        check(f.outcome() == BootFlow.Outcome.NONE, "cancelling a flow that never started does nothing");
    }

    static World random(long seed, boolean[] cooperative) {
        Random r = new Random(seed);
        World w = new World();
        boolean coop = true;
        w.hotspotStartMs = 500 + r.nextInt(14 * 1000);
        w.hotspotFailTimes = r.nextInt(100) < 80 ? 0 : r.nextInt(5);
        if (w.hotspotFailTimes > 2) {
            coop = false;
        }
        if (r.nextInt(100) < 5) {
            w.hotspotNeverStarts = true;
            coop = false;
        }
        if (w.hotspotFailTimes > 2 || w.hotspotNeverStarts) {
            if (r.nextBoolean()) {
                w.userStartsOwnHotspotAt = 30 * S + r.nextInt(300 * 1000);
                coop = w.userStartsOwnHotspotAt < 300 * S;
            }
        }
        w.phoneJoinsAfterMs = S + r.nextInt(280 * 1000);
        if (r.nextInt(100) < 3) {
            w.phoneNeverJoins = true;
            coop = false;
        }
        w.canWriteSecure = r.nextInt(100) < 70;
        w.wadbWorks = r.nextInt(100) < 85;
        w.wadbDelayMs = 500 + r.nextInt(10 * 1000);
        w.userManualWadbAfterMs = 5 * S + r.nextInt(80 * 1000);
        w.userStartsShizukuAfterMs = S + r.nextInt(200 * 1000);
        if (r.nextInt(100) < 5) {
            w.shizukuNeverStarts = true;
            coop = false;
        }
        w.needsAllow = r.nextInt(100) < 25;
        w.userAllowsAfterMs = S + r.nextInt(80 * 1000);
        w.phoneHotspotWorks = r.nextInt(100) < 80;
        w.phoneHotspotDelayMs = S + r.nextInt(10 * 1000);
        w.userStartsPhoneHotspotAfterMs = 5 * S + r.nextInt(200 * 1000);
        if (r.nextInt(100) < 5) {
            w.userNeverStartsPhoneHotspot = true;
            if (!w.phoneHotspotWorks) {
                coop = false;
            }
        }
        w.tabletJoinsAfterMs = S + r.nextInt(60 * 1000);
        if (r.nextInt(100) < 10) {
            w.tabletNeverJoins = true;
            w.userJoinsTabletAfterMs = 10 * S + r.nextInt(300 * 1000);
        }
        w.shizukuDiesOnLeave = r.nextInt(100) < 20;
        w.permLostOnLeave = !w.shizukuDiesOnLeave && r.nextInt(100) < 5;
        if (r.nextInt(100) < 5) {
            w.hotspotStopFails = true;
            coop = false;
        }
        if (r.nextInt(100) < 10) {
            w.hotspotDropAfterOnMs = 5 * S + r.nextInt(250 * 1000);
            // Android removing the tablet's hotspot is only harmless before the phone has joined it; later it can leave the phone and
            // the tablet unable to find each other (for example when it happens while the phone starts its own hotspot)
            if (w.hotspotDropAfterOnMs >= w.phoneJoinsAfterMs) {
                coop = false;
            }
            // a hotspot that the user started by hand (the tablet cannot make one) is not started again when Android removes it
            if (w.hotspotNeverStarts || w.hotspotFailTimes > 2) {
                coop = false;
            }
        }
        if (r.nextInt(100) < 10) {
            w.phoneDropsAt = 40 * S + r.nextInt(260 * 1000);
            w.phoneDropMs = 5 * S + r.nextInt(55 * 1000);
            // The phone vanishing for a while right after it was asked for its hotspot looks exactly like it leaving to start it: no flow
            // can tell the two apart, so a drop that is long enough to be mistaken for that is not a cooperative world
            if (w.phoneDropMs >= 11 * S) {
                coop = false;
            }
        }
        w.pollMs = r.nextBoolean() ? 2 * S : 3 * S;
        w.cmdLatencyMs = r.nextInt(2000);
        int init = r.nextInt(100);
        if (init < 10) {
            w.startFine = true;
        } else if (init < 18) {
            w.startPhoneHotspotOnNoShizuku = true;
            w.userSwitchesPhoneHotspotOffAfterMs = 5 * S + r.nextInt(120 * 1000);
        }
        cooperative[0] = coop;
        return w;
    }

    static void fuzz() {
        int success = 0;
        int died = 0;
        int timedOut = 0;
        for (int seed = 0; seed < 4000; seed++) {
            boolean[] coop = new boolean[1];
            World w = random(seed, coop);
            Run r = simulate(w, -1);
            String name = "random world " + seed;
            rules(name, r);
            BootFlow.Outcome oc = r.flow.outcome();
            if (coop[0]) {
                boolean ok = oc == BootFlow.Outcome.SUCCESS || oc == BootFlow.Outcome.ALREADY_FINE
                        || (w.shizukuDiesOnLeave && oc == BootFlow.Outcome.SHIZUKU_DIED)
                        || (w.permLostOnLeave && oc == BootFlow.Outcome.NOT_ALLOWED);
                check(ok, name + ": a cooperative world ended with " + oc + "\n" + r.flow.render());
            }
            if (oc == BootFlow.Outcome.SUCCESS) {
                success++;
            } else if (oc == BootFlow.Outcome.SHIZUKU_DIED) {
                died++;
            } else if (oc == BootFlow.Outcome.TIMED_OUT) {
                timedOut++;
            }
            // the same world again gives the same result
            if (seed % 200 == 0) {
                boolean[] c2 = new boolean[1];
                Run again = simulate(random(seed, c2), -1);
                check(again.flow.outcome() == oc && again.finishedAt == r.finishedAt, name + ": not deterministic");
            }
            // cancel somewhere in the middle: no more commands, no stranding
            if (seed % 4 == 0) {
                boolean[] c3 = new boolean[1];
                long at = (seed * 7919L) % 600 * S;
                Run cr = simulate(random(seed, c3), at);
                check(!cr.commandsAfterCancel, name + ": a command after Cancel at " + at / S + " s");
                check(!cr.w.stranded && !cr.w.hotspotOnWithoutShizuku, name + ": stranding with a Cancel at " + at / S + " s");
            }
        }
        check(success > 1500, "the random worlds include many successes: " + success);
        check(died > 100, "and many Shizuku-died endings: " + died);
        check(timedOut > 50, "and some time-outs: " + timedOut);
    }
}
