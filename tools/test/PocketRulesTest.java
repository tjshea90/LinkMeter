package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tests PocketRules (the six steps of the Launcher's "pocket return" against simulated phones) and LauncherIdentity (who may ask). Not part of the app.
 * The steps run in a fixed order; a phone that cannot be reached ends the run at once; one failing step never stops the next; the tablet's Wi-Fi is
 * released (sent) only when the sleep order was accepted and before any waiting; "done" only when every answer was ok AND the status showed the end
 * state; nothing loops for ever.
 */
public final class PocketRulesTest {
    private static int checks;
    private static int fails;

    private PocketRulesTest() {}

    private static void eq(Object want, Object got, String what) {
        checks++;
        if (!String.valueOf(want).equals(String.valueOf(got))) {
            fails++;
            System.out.println("FAIL " + what + ": wanted " + want + " but got " + got);
        }
    }

    private static void yes(boolean ok, String what) {
        checks++;
        if (!ok) {
            fails++;
            System.out.println("FAIL " + what);
        }
    }

    /** A phone that answers from a script, keeps a clock that only pauses advance, and writes everything down in one log. */
    private static final class Sim implements PocketRules.Phone, PocketRules.Sink {
        final List<String> log = new ArrayList<>();
        final List<String> acts = new ArrayList<>();
        PocketRules.Reply wifi = PocketRules.Reply.done("The phone's Wi-Fi is off.");
        PocketRules.Reply saver = PocketRules.Reply.done("LinkMeter's saver is on.");
        PocketRules.Reply sleep = PocketRules.Reply.sent("Sent. The phone will do it in a moment.");
        PocketRules.Reply system = PocketRules.Reply.done("Android's Battery saver is on.");
        PocketRules.Reply screen = PocketRules.Reply.done("The screen is off.");
        /** Status before the order, then one per look; the last one repeats. */
        PocketRules.Status before = new PocketRules.Status(Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, "");
        final List<PocketRules.Status> looks = new ArrayList<>();
        int lookAt;
        boolean ordered;
        long clock;
        int pauses;
        int sentCalls;
        PocketRules.State last;
        String lastText = "";

        @Override
        public PocketRules.Reply act(String name, int readMs) {
            acts.add(name);
            log.add("act:" + name);
            if (name.equals(PocketRules.SLEEP)) {
                ordered = true;
            }
            switch (name) {
                case PocketRules.WIFI_OFF: return wifi;
                case PocketRules.SAVER: return saver;
                case PocketRules.SLEEP: return sleep;
                case PocketRules.SYSTEM_SAVER: return system;
                default: return screen;
            }
        }

        @Override
        public PocketRules.Status status() {
            log.add("status");
            if (!ordered) {
                return before;
            }
            if (looks.isEmpty()) {
                return null;
            }
            PocketRules.Status s = looks.get(Math.min(lookAt, looks.size() - 1));
            lookAt++;
            return s;
        }

        @Override
        public long now() {
            return clock;
        }

        @Override
        public void pause(long ms) {
            pauses++;
            clock += ms;
            log.add("pause");
        }

        @Override
        public void state(PocketRules.State s, String text) {
            last = s;
            lastText = text;
            log.add("state:" + s);
        }

        @Override
        public void sent() {
            sentCalls++;
            log.add("sent");
        }
    }

    private static PocketRules.Status asleep() {
        return new PocketRules.Status(Boolean.FALSE, Boolean.TRUE, Boolean.FALSE, "Airplane mode is on, and Bluetooth stays on so that the tablet can wake the phone.");
    }

    private static PocketRules.Status working() {
        return new PocketRules.Status(Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, "");
    }

    private static List<String> list(String... s) {
        return Arrays.asList(s);
    }

    // ------------------------------------------------------------------------------------------------------------ the steps

    private static void happyPathOverTheHotspot() {
        Sim p = new Sim();
        p.looks.add(working());
        p.looks.add(asleep());
        PocketRules.Out out = PocketRules.run(p, p);
        eq(PocketRules.State.DONE, out.state, "happy path: done");
        eq(list("wifi_off", "eco_auto_on", "hotspot_off_deep", "eco_android_on"), p.acts, "happy path: steps in order, no second screen-off when the status shows the screen off");
        eq(1, p.sentCalls, "happy path: the tablet's Wi-Fi is released once");
        int sentAt = p.log.indexOf("sent");
        int sleepAt = p.log.indexOf("act:hotspot_off_deep");
        int firstPauseAfter = p.log.subList(sleepAt, p.log.size()).indexOf("pause") + sleepAt;
        yes(sleepAt < sentAt && sentAt < firstPauseAfter, "happy path: sent comes after the order and before any waiting");
        yes(out.text.contains("Wi-Fi") && out.text.contains("saver") && out.text.contains("Bluetooth stays on"), "happy path: the words name each step");
        eq(PocketRules.State.DONE, p.last, "happy path: the sink heard the end");
    }

    private static void screenStillOnGetsOneMoreSleep() {
        Sim p = new Sim();
        // the phone says hotspot off + airplane on, but the screen is still on at the end: the last step puts it off
        PocketRules.Status almost = new PocketRules.Status(Boolean.FALSE, Boolean.TRUE, Boolean.TRUE, "all but the screen");
        p.looks.add(almost);
        PocketRules.Out out = PocketRules.run(p, p);
        eq(list("wifi_off", "eco_auto_on", "hotspot_off_deep", "eco_android_on", "screen_off"), p.acts, "screen on: the screen step runs last");
        yes(out.state == PocketRules.State.FAILED, "screen on after the sleep: not reported as done (the status never showed the end state)");
    }

    private static void unreachableAtTheStartEndsTheRun() {
        Sim p = new Sim();
        p.wifi = PocketRules.Reply.unreachable("Bluetooth is off on this tablet");
        PocketRules.Out out = PocketRules.run(p, p);
        eq(PocketRules.State.FAILED, out.state, "unreachable: failed");
        eq(list("wifi_off"), p.acts, "unreachable: nothing else is tried");
        eq(0, p.sentCalls, "unreachable: the tablet's Wi-Fi is not released by sent (the caller decides at the end)");
        yes(out.text.contains("could not be reached") && out.text.contains("Bluetooth is off"), "unreachable: the words say why");
    }

    private static void oneFailureNeverStopsTheNext() {
        Sim p = new Sim();
        p.wifi = PocketRules.Reply.failed("The phone's Wi-Fi did not switch off");
        p.looks.add(asleep());
        PocketRules.Out out = PocketRules.run(p, p);
        eq(list("wifi_off", "eco_auto_on", "hotspot_off_deep", "eco_android_on"), p.acts, "wifi failed: the other steps still run");
        eq(PocketRules.State.FAILED, out.state, "wifi failed: the run is not 'done'");
        yes(out.text.contains("did not switch off"), "wifi failed: the phone's words are kept");
    }

    private static void sleepRefusedIsNotSentAndNotWaitedFor() {
        Sim p = new Sim();
        p.sleep = PocketRules.Reply.failed("Shizuku is not running on the phone");
        PocketRules.Out out = PocketRules.run(p, p);
        eq(0, p.sentCalls, "sleep refused: no release of the tablet's Wi-Fi");
        eq(0, p.pauses, "sleep refused: no waiting");
        eq(PocketRules.State.FAILED, out.state, "sleep refused: failed");
        yes(p.acts.contains("eco_android_on") && p.acts.contains("screen_off"), "sleep refused: the savers and the screen are still tried");
        yes(out.text.contains("Shizuku"), "sleep refused: the words say why");
    }

    private static void overBluetoothTheSleepAnswersAfterActing() {
        Sim p = new Sim();
        p.sleep = PocketRules.Reply.done("Airplane mode is on, and Bluetooth stays on so that the tablet can wake the phone.");
        p.looks.add(asleep());
        PocketRules.Out out = PocketRules.run(p, p);
        eq(0, p.pauses, "over Bluetooth: no waiting, the phone already did it");
        eq(1, p.sentCalls, "over Bluetooth: released once");
        eq(PocketRules.State.DONE, out.state, "over Bluetooth: done when the status shows the end state");

        Sim q = new Sim();
        q.sleep = PocketRules.Reply.done("The phone was put to sleep.");
        q.looks.add(working()); // it says so but the status does not show it
        PocketRules.Out outQ = PocketRules.run(q, q);
        eq(PocketRules.State.FAILED, outQ.state, "over Bluetooth: an answer without the status is not 'done'");
    }

    private static void thePhoneNeverFinishesAndTheWaitEnds() {
        Sim p = new Sim();
        p.looks.add(working()); // for ever
        PocketRules.Out out = PocketRules.run(p, p);
        eq(PocketRules.State.FAILED, out.state, "never finishes: failed");
        yes(p.clock >= PocketRules.FOLLOW_MS, "never finishes: it waited the whole time");
        yes(p.clock <= PocketRules.FOLLOW_MS + PocketRules.LOOK_EVERY_MS + PocketRules.FIRST_LOOK_MS, "never finishes: and not much longer");
        yes(p.clock <= 120_000L, "never finishes: the wait is under two minutes in absolute terms (the Launcher's own cap relies on it)");
        yes(out.text.contains("had not finished"), "never finishes: the words say so");
    }

    private static void thePhonesOwnWordsEndTheWait() {
        Sim p = new Sim();
        p.before = new PocketRules.Status(Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, "an older run");
        p.looks.add(new PocketRules.Status(Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, "an older run")); // not new: keep waiting
        p.looks.add(new PocketRules.Status(Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, "Airplane mode was switched off again: Bluetooth could not stay on."));
        PocketRules.Out out = PocketRules.run(p, p);
        eq(PocketRules.State.FAILED, out.state, "its own words: not asleep = failed");
        yes(out.text.contains("switched off again"), "its own words: the phone's sentence is shown");
        yes(p.pauses <= 3, "its own words: the wait ended at the new words, not at the limit");
    }

    private static void statusNeverReadable() {
        Sim p = new Sim(); // no looks: status() answers null after the order
        PocketRules.Out out = PocketRules.run(p, p);
        eq(PocketRules.State.FAILED, out.state, "status unreadable: failed");
        yes(out.text.contains("could not be read"), "status unreadable: the words say so");
        yes(p.acts.contains("screen_off"), "status unreadable: the screen is tried (its state is not known)");
        yes(p.clock <= PocketRules.FOLLOW_MS + PocketRules.LOOK_EVERY_MS + PocketRules.FIRST_LOOK_MS, "status unreadable: the wait ends");
    }

    private static void phoneStopsAnsweringHalfWay() {
        Sim p = new Sim();
        p.saver = PocketRules.Reply.unreachable("no answer");
        PocketRules.Out out = PocketRules.run(p, p);
        eq(list("wifi_off", "eco_auto_on"), p.acts, "gone after step 1: the run ends");
        eq(PocketRules.State.FAILED, out.state, "gone after step 1: failed");
        yes(out.text.contains("Wi-Fi"), "gone after step 1: what already happened is kept in the words");
    }

    /** Every combination of the six answers and several status stories: nothing loops, nothing is out of order, 'done' is never a guess. */
    private static void everyCombination() {
        PocketRules.Reply[] kinds = {
            PocketRules.Reply.done("ok"), PocketRules.Reply.sent("sent"), PocketRules.Reply.failed("no"), PocketRules.Reply.unreachable("gone"),
        };
        PocketRules.Status[][] stories = {
            {}, {working()}, {asleep()}, {working(), asleep()},
            {new PocketRules.Status(null, null, null, "")},
            {new PocketRules.Status(Boolean.FALSE, Boolean.TRUE, Boolean.TRUE, "words")},
        };
        List<String> order = list("wifi_off", "eco_auto_on", "hotspot_off_deep", "eco_android_on", "screen_off");
        int runs = 0;
        for (PocketRules.Reply w : kinds) {
            for (PocketRules.Reply v : kinds) {
                for (PocketRules.Reply s : kinds) {
                    for (PocketRules.Reply y : kinds) {
                        for (PocketRules.Reply c : kinds) {
                            for (PocketRules.Status[] story : stories) {
                                Sim p = new Sim();
                                p.wifi = w;
                                p.saver = v;
                                p.sleep = s;
                                p.system = y;
                                p.screen = c;
                                p.looks.addAll(Arrays.asList(story));
                                PocketRules.Out out = PocketRules.run(p, p);
                                runs++;
                                String id = w.text + "/" + v.text + "/" + s.text + "/" + y.text + "/" + c.text + "/" + story.length;
                                yes(out.state == PocketRules.State.DONE || out.state == PocketRules.State.FAILED, "combo " + id + ": ends done or failed");
                                yes(p.sentCalls <= 1, "combo " + id + ": released at most once");
                                yes(p.sentCalls == 0 || (s.reached && s.ok && !p.log.isEmpty()), "combo " + id + ": released only for an accepted order");
                                int prev = -1;
                                boolean inOrder = true;
                                for (String a : p.acts) {
                                    int at = order.indexOf(a);
                                    if (at < prev) {
                                        inOrder = false;
                                    }
                                    prev = at;
                                }
                                yes(inOrder, "combo " + id + ": actions in the fixed order");
                                yes(p.pauses <= (int) (PocketRules.FOLLOW_MS / PocketRules.LOOK_EVERY_MS) + 3, "combo " + id + ": bounded waiting");
                                if (out.state == PocketRules.State.DONE) {
                                    yes(w.ok && v.ok && s.ok && y.ok, "combo " + id + ": done only when every answer was ok");
                                    yes(p.acts.contains("hotspot_off_deep") && p.acts.contains("eco_android_on"), "combo " + id + ": done only after both savers were asked");
                                }
                                if (!w.reached) {
                                    eq(1, p.acts.size(), "combo " + id + ": unreachable at the start = one request");
                                }
                                if (!s.ok) {
                                    eq(0, p.pauses, "combo " + id + ": a refused sleep is never waited for");
                                }
                            }
                        }
                    }
                }
            }
        }
        yes(runs == 4 * 4 * 4 * 4 * 4 * 6, "combinations counted: " + runs);
    }

    // ------------------------------------------------------------------------------------------------------------ the identity

    private static LauncherIdentity.Certs certs(final String... have) {
        return pkg -> have;
    }

    private static final String PIN = LauncherIdentity.PINNED[0];

    private static void identity() {
        String[] mine = {"com.tj.launcher"};
        yes(LauncherIdentity.accepts(mine, certs(PIN), false), "identity: the Launcher with its certificate is accepted");
        yes(LauncherIdentity.accepts(mine, certs(PIN.replace(":", "").toLowerCase()), false), "identity: spelling (no colons, lower case) does not matter");
        yes(LauncherIdentity.accepts(new String[] {"x.y", "com.tj.launcher"}, certs("ab", PIN), false), "identity: one right certificate among several is enough");
        yes(!LauncherIdentity.accepts(mine, certs("00" + PIN.substring(2)), false), "identity: another certificate is refused");
        yes(!LauncherIdentity.accepts(mine, certs(PIN.substring(0, PIN.length() - 3)), false), "identity: a truncated certificate is refused");
        yes(!LauncherIdentity.accepts(mine, certs(""), false), "identity: an empty certificate is refused");
        yes(!LauncherIdentity.accepts(mine, certs(), false), "identity: no certificate is refused");
        yes(!LauncherIdentity.accepts(mine, pkg -> null, false), "identity: unreadable certificates are refused");
        yes(!LauncherIdentity.accepts(new String[] {"com.evil.app"}, certs(PIN), false), "identity: another package is refused even with the right certificate");
        yes(!LauncherIdentity.accepts(new String[] {"com.tj.launcher.evil"}, certs(PIN), false), "identity: a package that only starts like it is refused");
        yes(!LauncherIdentity.accepts(null, certs(PIN), false), "identity: an unknown uid is refused");
        yes(!LauncherIdentity.accepts(new String[0], certs(PIN), false), "identity: a uid with no package is refused");
        yes(!LauncherIdentity.accepts(mine, null, false), "identity: no way to read certificates is refused");
        yes(LauncherIdentity.accepts(mine, certs("nothing"), true), "identity: a debug build accepts the package name alone");
        yes(!LauncherIdentity.accepts(new String[] {"com.evil.app"}, certs(PIN), true), "identity: a debug build still refuses another package");
        eq(64, LauncherIdentity.normalize(PIN).length(), "identity: the pinned certificate is a SHA-256 (64 hex digits)");
        eq("0D86C7", LauncherIdentity.normalize("0d:86 c7").substring(0, 6), "identity: normalize");
    }

    private static void oneLine() {
        eq("a b c", PocketRules.oneLine("a\nb\tc\r"), "oneLine: control characters become spaces");
        eq(900, PocketRules.oneLine(new String(new char[2000]).replace('\0', 'x')).length(), "oneLine: long text is cut");
        eq("", PocketRules.oneLine(null), "oneLine: null");
    }

    public static void main(String[] args) {
        happyPathOverTheHotspot();
        screenStillOnGetsOneMoreSleep();
        unreachableAtTheStartEndsTheRun();
        oneFailureNeverStopsTheNext();
        sleepRefusedIsNotSentAndNotWaitedFor();
        overBluetoothTheSleepAnswersAfterActing();
        thePhoneNeverFinishesAndTheWaitEnds();
        thePhonesOwnWordsEndTheWait();
        statusNeverReadable();
        phoneStopsAnsweringHalfWay();
        everyCombination();
        identity();
        oneLine();
        System.out.println(checks + " checks, " + fails + " failed");
        if (fails > 0) {
            System.exit(1);
        }
    }
}
