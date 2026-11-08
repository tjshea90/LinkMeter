package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Offline test of PowerSequence (the phone's Sleep / Wake steps) against a simulated phone on a virtual clock.
 * Run by tools/test.sh. Prints "N checks, M failed".
 */
public final class PowerSequenceTest {
    static int checks;
    static int failed;

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            failed++;
            if (failed <= 25) {
                System.out.println("  FAIL: " + msg);
            }
        }
    }

    /** A phone whose state changes are events at virtual times. Commands take time, as starting a shell process does. */
    static final class Sim implements PowerSequence.Phone {
        long now;
        boolean awake;
        boolean dozing;
        boolean wantOn; // what the run under test wants (for the violation check)
        final List<long[]> events = new ArrayList<>(); // {time, kind}: 1 sleep, 2 sleep into doze, 3 wake, 4 toggle
        // how this phone behaves
        boolean aod; // sleeping by key or power key ends in the Always-On Display
        String serviceReply = "OK";
        long serviceDelay = 100; // -1: the service call does nothing
        long sleepKeyDelay = 100; // -1: the sleep key does nothing
        long wakeKeyDelay = 100; // -1: the wake key does nothing
        long powerKeyDelay = 100; // -1: the power key does nothing
        // what the run did
        int serviceCalls;
        int sleepKeys;
        int wakeKeys;
        int powerKeys;
        int badPowerKeys; // the power key was injected while the phone already was as wanted: it undoes the job
        long lastCommandAt;

        Sim(boolean awake) {
            this.awake = awake;
        }

        void at(long time, int kind) {
            events.add(new long[] {time, kind});
        }

        private void apply() {
            for (int i = 0; i < events.size(); i++) {
                long[] e = events.get(i);
                if (e[0] <= now) {
                    events.remove(i--);
                    switch ((int) e[1]) {
                        case 1:
                            awake = false;
                            dozing = false;
                            break;
                        case 2:
                            awake = false;
                            dozing = true;
                            break;
                        case 3:
                            awake = true;
                            dozing = false;
                            break;
                        default:
                            awake = !awake;
                            dozing = !awake && aod;
                            break;
                    }
                }
            }
        }

        @Override
        public boolean awake() {
            apply();
            return awake;
        }

        @Override
        public boolean dozing() {
            apply();
            return dozing;
        }

        @Override
        public String pressKey(int key) {
            now += 400; // the shell process starts, then the key is injected
            apply();
            lastCommandAt = now;
            if (key == PowerSequence.KEY_SLEEP) {
                sleepKeys++;
                if (sleepKeyDelay >= 0) {
                    at(now + sleepKeyDelay, aod ? 2 : 1);
                }
            } else if (key == PowerSequence.KEY_WAKEUP) {
                wakeKeys++;
                if (wakeKeyDelay >= 0) {
                    at(now + wakeKeyDelay, 3);
                }
            } else if (key == PowerSequence.KEY_POWER) {
                powerKeys++;
                if (awake == wantOn) {
                    badPowerKeys++; // pressed too late: the toggle undoes what has happened by itself
                }
                if (powerKeyDelay >= 0) {
                    at(now + powerKeyDelay, 4);
                }
            }
            return "done";
        }

        @Override
        public String sleepService(boolean noDoze) {
            now += 600;
            apply();
            serviceCalls++;
            if (serviceReply.startsWith("OK") && serviceDelay >= 0) {
                at(now + serviceDelay, aod && !noDoze ? 2 : 1);
            }
            return serviceReply;
        }

        @Override
        public void pause(long ms) {
            now += ms;
            apply();
        }

        @Override
        public long now() {
            return now;
        }
    }

    static PowerSequence.Result run(Sim s, boolean wantOn, boolean noDoze) throws InterruptedException {
        s.wantOn = wantOn;
        return PowerSequence.run(s, wantOn, noDoze);
    }

    public static void main(String[] args) throws Exception {
        // an awake phone, the service works at once
        {
            Sim s = new Sim(true);
            PowerSequence.Result r = run(s, false, true);
            check(r.reached && !s.awake, "service: asleep");
            check(s.serviceCalls == 1 && s.sleepKeys == 0 && s.powerKeys == 0, "service: nothing else is pressed");
            check(r.how.contains("power service OK"), "service: the trace says what was done: " + r.how);
            check(r.ms < 3000, "service: quick (" + r.ms + " ms)");
        }
        // a slow phone: it falls asleep 2 s after the service call, so the first wait runs out and the sleep key is pressed
        // (harmless on a phone that is about to sleep), and the power key must stay untouched
        {
            Sim s = new Sim(true);
            s.serviceDelay = 2000;
            PowerSequence.Result r = run(s, false, true);
            check(r.reached, "slow: reached");
            check(s.powerKeys == 0, "slow: the power key is NOT pressed (" + s.powerKeys + ")");
        }
        // a very slow phone: it sleeps 3.4 s after the service call; the look before the toggle sees it asleep
        {
            Sim s = new Sim(true);
            s.serviceDelay = 3400;
            s.sleepKeyDelay = -1;
            PowerSequence.Result r = run(s, false, true);
            check(r.reached && s.powerKeys == 0, "very slow: reached without the power key (" + s.powerKeys + " presses)");
        }
        // the service fails, the sleep key does nothing, the power key works
        {
            Sim s = new Sim(true);
            s.serviceReply = "ERR no goToSleep";
            s.sleepKeyDelay = -1;
            PowerSequence.Result r = run(s, false, true);
            check(r.reached && !s.awake, "power key: reached");
            check(s.powerKeys == 1 && s.badPowerKeys == 0, "power key: pressed once, when it was needed");
            check(r.how.contains("ERR no goToSleep") && r.how.contains("power key"), "power key: the trace tells the story: " + r.how);
        }
        // the sleep key works when the service does not
        {
            Sim s = new Sim(true);
            s.serviceReply = "ERR x";
            PowerSequence.Result r = run(s, false, false);
            check(r.reached && s.sleepKeys == 1 && s.powerKeys == 0, "sleep key: used, power key not");
        }
        // something wakes the phone 300 ms after it went to sleep: the user is told, and no key is pressed to "fix" it
        {
            Sim s = new Sim(true);
            s.serviceDelay = 100;
            s.at(1000, 3); // wakes by itself shortly after it fell asleep (the service answered at 600 ms, slept at 700 ms)
            PowerSequence.Result r = run(s, false, true);
            check(!r.reached && s.awake, "wakes again: reported as not reached");
            check(r.how.contains("woke up again by itself"), "wakes again: the trace says so: " + r.how);
            check(s.powerKeys == 0, "wakes again: the power key was not pressed");
        }
        // an Always-On Display phone: asleep (not awake) but dozing; with nodoze the service is still called to turn it fully off
        {
            Sim s = new Sim(false);
            s.dozing = true;
            s.aod = true;
            PowerSequence.Result r = run(s, false, true);
            check(r.reached && !s.dozing && s.serviceCalls == 1, "AOD phone: the service is called and the display goes fully off");
            Sim t = new Sim(false);
            t.dozing = true;
            t.aod = true;
            PowerSequence.Result q = run(t, false, false);
            check(q.reached && t.serviceCalls == 0 && t.dozing && q.how.startsWith("already so"),
                    "AOD phone without nodoze: left alone (asleep is asleep)");
        }
        // already asleep with the display off: nothing is done
        {
            Sim s = new Sim(false);
            PowerSequence.Result r = run(s, false, true);
            check(r.reached && s.serviceCalls + s.sleepKeys + s.powerKeys == 0 && r.how.startsWith("already so"), "already asleep: nothing done");
        }
        // wake
        {
            Sim s = new Sim(false);
            PowerSequence.Result r = run(s, true, false);
            check(r.reached && s.awake && s.wakeKeys == 1 && s.powerKeys == 0, "wake: the wake key is enough");
            Sim t = new Sim(false);
            t.wakeKeyDelay = -1;
            PowerSequence.Result q = run(t, true, false);
            check(q.reached && t.powerKeys == 1 && t.badPowerKeys == 0, "wake: the power key when the wake key does nothing");
        }
        // nothing works: it gives up in a bounded time, having pressed each thing at most once
        {
            Sim s = new Sim(true);
            s.serviceReply = "ERR x";
            s.sleepKeyDelay = -1;
            s.powerKeyDelay = -1;
            PowerSequence.Result r = run(s, false, true);
            check(!r.reached && s.serviceCalls == 1 && s.sleepKeys == 1 && s.powerKeys == 1, "nothing works: each tried once");
            check(r.ms <= 8000, "nothing works: gives up in " + r.ms + " ms");
        }

        // random phones. A method that works takes effect after a random time. Up to 1.8 s (every real phone) the power key
        // must never undo a job; slower phones (up to 3.5 s) only need bounded time and nothing pressed twice.
        for (int round = 0; round < 2; round++) {
            int maxDelay = round == 0 ? 1800 : 3500;
            for (int seed = 0; seed < 4000; seed++) {
                Random r = new Random(seed * 2 + round);
                boolean wantOn = r.nextBoolean();
                Sim s = new Sim(!wantOn);
                s.aod = r.nextBoolean();
                s.dozing = !s.awake && s.aod && r.nextBoolean();
                s.serviceReply = r.nextInt(4) == 0 ? "ERR boom" : "OK";
                s.serviceDelay = r.nextInt(5) == 0 ? -1 : r.nextInt(maxDelay);
                s.sleepKeyDelay = r.nextInt(3) == 0 ? -1 : r.nextInt(maxDelay);
                s.wakeKeyDelay = r.nextInt(3) == 0 ? -1 : r.nextInt(maxDelay);
                s.powerKeyDelay = r.nextInt(10) == 0 ? -1 : r.nextInt(800);
                boolean selfEvent = r.nextInt(5) == 0;
                if (selfEvent) {
                    s.at(r.nextInt(8000), r.nextBoolean() ? 3 : 1);
                }
                boolean noDoze = r.nextBoolean();
                PowerSequence.Result res = run(s, wantOn, noDoze);
                String tag = "round " + round + " seed " + seed + " (" + res.how + ")";
                if (round == 0 && !selfEvent) {
                    // (with an outside event the phone can change in the very moment the key is being started: nothing can prevent that)
                    check(s.badPowerKeys == 0, tag + ": the power key undid a job");
                }
                check(res.ms <= 12000, tag + ": took " + res.ms + " ms");
                check(s.serviceCalls <= 1 && s.sleepKeys <= 1 && s.wakeKeys <= 1 && s.powerKeys <= 1,
                        tag + ": something was pressed twice");
                if (res.reached) {
                    check(s.awake == wantOn, tag + ": reported reached but the phone is not");
                } else {
                    check(!res.how.isEmpty(), tag + ": a failure needs a trace");
                }
                if (round == 0 && !selfEvent && wantOn && s.wakeKeyDelay >= 0) {
                    check(res.reached, tag + ": a working wake key must succeed on a phone that follows in under 1.8 s");
                }
            }
        }
        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
