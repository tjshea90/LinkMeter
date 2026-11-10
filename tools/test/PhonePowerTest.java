package com.tj.linkmeter;

import java.util.Random;

/** Offline test of PhonePower (the Sleep / Wake button rules). Run by tools/test.sh. Prints "N checks, M failed". */
public final class PhonePowerTest {
    static int checks;
    static int failed;

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            failed++;
            System.out.println("  FAIL: " + msg);
        }
    }

    static final long S = PhonePower.SETTLE_MS;

    public static void main(String[] a) {
        // one Sleep and one Wake for the Sleep button, for leaving the Screen tab and for Home; Sleep is always the fully-off form
        check(PhonePower.SLEEP.equals("/screen/off?nodoze=1"), "Sleep is the fully-off form (the Always-On Display keeps using power)");
        check(PhonePower.WAKE.equals("/screen/on"), "Wake is plain");
        check(PhonePower.SLEEP.startsWith("/screen/off?") && PhonePower.SLEEP.indexOf("nodoze=1") > 0, "an older phone app reads the path and ignores the word it does not know");
        // starts on; a poll that agrees changes nothing
        {
            PhonePower p = new PhonePower();
            check(p.on() && !p.busy(), "starts on and idle");
            check(p.poll(true, 0) == PhonePower.Change.NONE, "an agreeing poll changes nothing");
        }
        // pressing Sleep: shown at once, a second press is ignored while busy
        {
            PhonePower p = new PhonePower();
            check(p.press(1000), "the first press is accepted");
            check(!p.on() && p.busy() && !p.wanted(), "Sleep shows 'off' at once and is busy");
            check(!p.press(1100), "a press while busy is ignored");
            check(!p.on() && !p.wanted(), "... and changes nothing");
            check(!p.wake(1100), "tapping to wake while busy is ignored");
            // a state answer asked before the press, arriving now with the old answer, must not flip it back
            check(p.poll(true, 1500) == PhonePower.Change.NONE && !p.on(), "a stale 'on' poll is not believed while busy");
            check(p.confirmed(false, 2000) == PhonePower.Change.WENT_OFF, "confirmation says: went off");
            check(!p.on() && !p.busy(), "off and idle after confirmation");
            check(p.poll(true, 2500) == PhonePower.Change.NONE && !p.on(), "a stale 'on' poll just after the answer is not believed");
            check(p.poll(false, 2600) == PhonePower.Change.NONE, "an agreeing poll changes nothing");
            check(p.poll(true, 2000 + S - 1) == PhonePower.Change.NONE, "still not believed one millisecond before the quiet time ends");
            check(p.poll(true, 2000 + S) == PhonePower.Change.WOKE && p.on(), "after the quiet time an 'on' poll is believed (the phone woke by itself)");
            check(p.poll(true, 2000 + S + 10) == PhonePower.Change.NONE, "and it is reported once");
        }
        // a slow phone: the press takes longer than the quiet time, and polls keep arriving meanwhile
        {
            PhonePower p = new PhonePower();
            p.press(0);
            check(p.poll(true, S + 5000) == PhonePower.Change.NONE && !p.on(),
                    "a stale 'on' poll is not believed while a slow press is still busy");
            check(p.confirmed(false, S + 6000) == PhonePower.Change.WENT_OFF, "the slow press finally confirms");
        }
        // a press whose answer never comes (a lost callback) is given up on after a minute
        {
            PhonePower p = new PhonePower();
            p.press(0);
            check(!p.press(PhonePower.BUSY_MAX_MS - 1), "still busy just before the limit");
            check(p.press(PhonePower.BUSY_MAX_MS) && p.wanted(), "after the limit a new press is accepted (and toggles the shown state)");
            PhonePower q = new PhonePower();
            q.press(0);
            check(q.poll(false, PhonePower.BUSY_MAX_MS - 1) == PhonePower.Change.NONE, "polls are still ignored just before the limit");
            check(q.poll(true, PhonePower.BUSY_MAX_MS + 5) == PhonePower.Change.WOKE && q.on() && !q.busy(),
                    "after the limit a poll is believed again and the press is forgotten");
        }
        // the press fails: back to the old state
        {
            PhonePower p = new PhonePower();
            p.press(0);
            p.failed(500);
            check(p.on() && !p.busy(), "a failed Sleep goes back to 'on'");
            check(p.poll(false, 600) == PhonePower.Change.NONE && p.on(), "no flicker right after a failure");
            check(p.press(10000), "and the button works again");
        }
        // the press worked but its reply was lost: the phone's own state sorts it out after the quiet time
        {
            PhonePower p = new PhonePower();
            p.press(0);
            p.failed(20000); // "could not reach the phone"
            check(p.on(), "after a lost reply the button shows 'on'");
            check(p.poll(false, 20000 + S) == PhonePower.Change.WENT_OFF && !p.on(),
                    "the next answer after the quiet time shows that the phone did sleep");
        }
        // the phone falls asleep by itself (screen timeout) and wakes by its power button
        {
            PhonePower p = new PhonePower();
            check(p.poll(false, 100000) == PhonePower.Change.WENT_OFF && !p.on(), "a timeout sleep is noticed");
            check(p.poll(false, 103000) == PhonePower.Change.NONE, "once");
            check(p.poll(true, 106000) == PhonePower.Change.WOKE && p.on(), "a wake by the phone's own button is noticed");
        }
        // tap to wake
        {
            PhonePower p = new PhonePower();
            check(!p.wake(0), "tapping a phone that is on does nothing");
            p.press(0);
            p.confirmed(false, 1000);
            check(p.wake(5000), "tapping a sleeping phone wakes it");
            check(p.on() && p.busy() && p.wanted(), "shown as on at once, busy");
            check(p.confirmed(true, 6000) == PhonePower.Change.WOKE, "confirmed: woke");
        }
        // the phone said 'off' itself (the video request was refused)
        {
            PhonePower p = new PhonePower();
            p.forceOff();
            check(!p.on(), "forceOff turns it off");
            PhonePower q = new PhonePower();
            q.press(0); // sleep in progress ... then wake pressed after it confirmed
            q.confirmed(false, 100);
            q.press(200); // wake
            q.forceOff();
            check(q.on(), "forceOff does not argue with a press in progress");
        }
        // random stories: whatever happens, once a press is over and the quiet time has passed, the phone's real state wins
        for (int seed = 0; seed < 3000; seed++) {
            Random r = new Random(seed);
            PhonePower p = new PhonePower();
            boolean real = true; // what the phone really is
            long now = 0;
            for (int step = 0; step < 60; step++) {
                now += r.nextInt(2500);
                switch (r.nextInt(6)) {
                    case 0:
                        if (p.press(now)) {
                            // the phone does it (or not) some time later
                            if (r.nextInt(5) == 0) {
                                p.failed(now + 50); // not reachable: it did not happen
                            } else if (r.nextInt(8) == 0) {
                                real = p.wanted();
                                p.failed(now + 50); // it happened but the reply was lost
                            } else {
                                real = p.wanted();
                                p.confirmed(real, now + 50);
                            }
                            now += 50;
                        }
                        break;
                    case 1:
                        real = r.nextBoolean(); // the phone changes by itself
                        break;
                    default:
                        p.poll(real, now);
                        break;
                }
                check(!p.busy(), "seed " + seed + ": a press is always finished before the next step");
            }
            now += S + 1;
            p.poll(real, now);
            check(p.on() == real, "seed " + seed + ": in the end the button shows the phone's real state");
        }
        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
