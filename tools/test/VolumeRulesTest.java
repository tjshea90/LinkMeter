package com.tj.linkmeter;

import java.util.Map;

/**
 * Tests the headset's volume buttons (VolumeRules, and their wire form in CallJson; docs/PHONE_CALLS.md "Headset volume"). Every
 * combination of what the tablet may know is checked against a second, independent way of working the answer out. The promises:
 * the buttons only mean the HEADSET (no headset, or a sound that goes elsewhere, and nothing moves), one press is one to three steps
 * (never a jump to full loudness), a level is believed only when the phone read it back, and an old phone is told apart. Not part of the app.
 */
public final class VolumeRulesTest {
    private static int checks;
    private static int fails;

    private VolumeRulesTest() {}

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

    private static final String[] STREAMS = {Calls.STREAM_MEDIA, Calls.STREAM_CALL};
    private static final String[] ROUTES = {Calls.ROUTE_PHONE, Calls.ROUTE_SPEAKER, Calls.ROUTE_HEADSET, Calls.ROUTE_UNKNOWN};
    /** (min, max) of a few real-looking volumes: media 0..15, call 1..5, a tiny 0..1 and a 2-step one. */
    private static final int[][] RANGES = {{0, 15}, {1, 5}, {0, 1}, {1, 7}};

    private static Calls.Volume volume(int mediaNow, int callNow, int[] mediaRange, int[] callRange, boolean mediaOn, boolean callOn) {
        Calls.Volume v = new Calls.Volume();
        v.media.now = mediaNow;
        v.media.min = mediaRange[0];
        v.media.max = mediaRange[1];
        v.call.now = callNow;
        v.call.min = callRange[0];
        v.call.max = callRange[1];
        v.mediaOnHeadset = mediaOn;
        v.callOnHeadset = callOn;
        return v;
    }

    public static void main(String[] args) throws Exception {
        streams();
        callOnHeadsetTable();
        words();
        rows();
        unheard();
        cardNote();
        plans();
        verifies();
        wire();
        System.out.println(checks + " checks, " + fails + " failed");
        if (fails > 0) {
            System.exit(1);
        }
    }

    private static void streams() {
        yes(VolumeRules.validStream("media") && VolumeRules.validStream("call"), "the two streams are valid");
        for (String bad : new String[] {"", "ring", "music", "Media", "call ", null, "alarm", "notification", "../call"}) {
            yes(!VolumeRules.validStream(bad), "not a stream: " + bad);
        }
        eq(Calls.STREAM_CALL, VolumeRules.defaultStream(true), "during a call the call volume is first");
        eq(Calls.STREAM_MEDIA, VolumeRules.defaultStream(false), "without a call the media volume is first");
        eq(Calls.STREAM_MEDIA, VolumeRules.other(Calls.STREAM_CALL), "the other of call");
        eq(Calls.STREAM_CALL, VolumeRules.other(Calls.STREAM_MEDIA), "the other of media");
        eq("Call volume", VolumeRules.name(Calls.STREAM_CALL), "name call");
        eq("Media volume", VolumeRules.name(Calls.STREAM_MEDIA), "name media");
        eq("Call", VolumeRules.shortName(Calls.STREAM_CALL), "short call");
        eq("Media", VolumeRules.shortName(Calls.STREAM_MEDIA), "short media");
    }

    private static void callOnHeadsetTable() {
        for (boolean callOn : new boolean[] {false, true}) {
            for (boolean headset : new boolean[] {false, true}) {
                for (String route : ROUTES) {
                    boolean want = callOn && headset && !route.equals(Calls.ROUTE_PHONE) && !route.equals(Calls.ROUTE_SPEAKER);
                    eq(want, VolumeRules.callOnHeadset(callOn, headset, route), "callOnHeadset " + callOn + " " + headset + " " + route);
                }
            }
        }
        yes(!VolumeRules.callOnHeadset(true, true, Calls.ROUTE_SPEAKER), "a call on the phone's speaker is not the headset's");
        yes(!VolumeRules.callOnHeadset(true, true, Calls.ROUTE_PHONE), "a call at the phone's ear is not the headset's");
    }

    private static void words() {
        Calls.Level l = new Calls.Level();
        eq("–", VolumeRules.words(l), "not known: a dash");
        l.min = 0;
        l.max = 15;
        l.now = 7;
        eq("7 of 15", VolumeRules.words(l), "7 of 15");
        l.now = 16;
        eq("–", VolumeRules.words(l), "a position outside the range is not believed");
        l.now = -1;
        eq("–", VolumeRules.words(l), "no position");
        l.min = 3;
        l.now = 3;
        l.max = 3;
        eq("–", VolumeRules.words(l), "a range that cannot move is not known");
        eq("–", VolumeRules.words(null), "null");
    }

    /** The second way: the same decision written as a plain chain, read top to bottom. */
    private static String expectedWhy(Calls.Volume v, String stream, boolean reachable, boolean headset, boolean callOn) {
        Calls.Level l = stream.equals(Calls.STREAM_CALL) ? v.call : v.media;
        boolean on = stream.equals(Calls.STREAM_CALL) ? v.callOnHeadset : v.mediaOnHeadset;
        boolean known = l.max > l.min && l.now >= l.min && l.now <= l.max;
        if (!reachable) {
            return VolumeRules.UNREACHED;
        }
        if (v.old) {
            return VolumeRules.OLD_PHONE;
        }
        if (!headset) {
            return VolumeRules.NO_HEADSET;
        }
        if (!known) {
            return VolumeRules.UNKNOWN;
        }
        if (stream.equals(Calls.STREAM_CALL) && !callOn) {
            return VolumeRules.CALL_OFF;
        }
        if (!on) {
            return stream.equals(Calls.STREAM_CALL) ? VolumeRules.CALL_ELSEWHERE : VolumeRules.MEDIA_ELSEWHERE;
        }
        return "";
    }

    private static void rows() {
        int[] nows = {-1, 0, 1, 2, 5, 7, 14, 15, 16};
        for (int[] range : RANGES) {
            for (int now : nows) {
                for (boolean old : new boolean[] {false, true}) {
                    for (boolean mediaOn : new boolean[] {false, true}) {
                        for (boolean callOnHs : new boolean[] {false, true}) {
                            Calls.Volume v = volume(now, now, range, range, mediaOn, callOnHs);
                            v.old = old;
                            for (String stream : STREAMS) {
                                for (boolean reachable : new boolean[] {false, true}) {
                                    for (boolean headset : new boolean[] {false, true}) {
                                        for (boolean callOn : new boolean[] {false, true}) {
                                            String id = stream + " " + range[0] + ".." + range[1] + " now " + now + " old " + old + " m" + mediaOn
                                                    + " c" + callOnHs + " reach" + reachable + " hs" + headset + " call" + callOn;
                                            VolumeRules.Row r = VolumeRules.row(v, stream, reachable, headset, callOn);
                                            String why = expectedWhy(v, stream, reachable, headset, callOn);
                                            eq(why, r.why, "the sentence: " + id);
                                            eq(why.isEmpty(), r.enabled, "enabled only without a reason: " + id);
                                            boolean known = now >= range[0] && now <= range[1];
                                            eq(r.enabled && known && now > range[0], r.canLower, "can lower: " + id);
                                            eq(r.enabled && known && now < range[1], r.canRaise, "can raise: " + id);
                                            eq(known ? now + " of " + range[1] : "–", r.words, "words: " + id);
                                            int base = Math.max(range[0] - 1, 0);
                                            eq(known ? range[1] - base : 0, r.steps, "bar steps: " + id);
                                            eq(known ? now - base : 0, r.filled, "bar filled: " + id);
                                            yes(r.filled >= 0 && r.filled <= r.steps, "the bar never overfills: " + id);
                                            // the promise: nothing is offered unless a headset is connected AND the sound of this kind goes there
                                            if (r.enabled) {
                                                yes(headset && reachable && !old, "enabled needs a headset, a reachable phone, a new phone: " + id);
                                                yes(stream.equals(Calls.STREAM_CALL) ? callOnHs && callOn : mediaOn, "enabled needs the sound on the headset: " + id);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void unheard() {
        String sw = "Let the tablet make and answer calls";
        eq("", VolumeRules.unheard(Calls.WATCH_OK, "", true, sw), "heard: nothing to say");
        eq("", VolumeRules.unheard(Calls.WATCH_OK, "whatever", false, sw), "heard: nothing to say, whatever the problem text");
        yes(VolumeRules.unheard(Calls.WATCH_OFF, "", true, sw).contains(sw), "switched off: names the phone's switch");
        eq("their words", VolumeRules.unheard(Calls.WATCH_OFF, "their words", true, sw), "switched off: the watch's own words win");
        eq(VolumeRules.OLD_PHONE, VolumeRules.unheard(Calls.WATCH_OLD, "", true, sw), "old phone");
        yes(VolumeRules.unheard(Calls.WATCH_NO_PEER, "", true, sw).contains("does not know the phone"), "no phone known");
        yes(VolumeRules.unheard(Calls.WATCH_UNREACHED, "no route", true, sw).startsWith(VolumeRules.UNREACHED), "unreached");
        yes(VolumeRules.unheard(Calls.WATCH_UNREACHED, "no route", true, sw).endsWith("no route"), "unreached: and why");
        yes(VolumeRules.unheard(Calls.WATCH_IDLE, "", false, sw).contains("Calls") && VolumeRules.unheard(Calls.WATCH_IDLE, "", false, sw).contains(sw),
                "never used calls: says where to start");
        eq("Looking for the phone…", VolumeRules.unheard(Calls.WATCH_IDLE, "", true, sw), "used calls, nothing heard yet");
        for (int st = 0; st <= 5; st++) {
            yes((st == Calls.WATCH_OK) == VolumeRules.unheard(st, "", false, sw).isEmpty(), "only OK is silent: " + st);
        }
    }

    private static void cardNote() {
        VolumeRules.Row a = new VolumeRules.Row();
        VolumeRules.Row b = new VolumeRules.Row();
        eq("", VolumeRules.cardNote(a, b), "both work: no note");
        b.why = "call off";
        eq("call off", VolumeRules.cardNote(a, b), "only the call row is off");
        a.why = "media off";
        eq("media off call off", VolumeRules.cardNote(a, b), "two different reasons are both said");
        b.why = "media off";
        eq("media off", VolumeRules.cardNote(a, b), "the same reason once");
        b.why = "";
        eq("media off", VolumeRules.cardNote(a, b), "only the media row is off");
    }

    private static void plans() {
        for (int[] range : RANGES) {
            for (int now = range[0] - 1; now <= range[1] + 1; now++) {
                for (int by = -5; by <= 5; by++) {
                    for (boolean mediaOn : new boolean[] {false, true}) {
                        for (boolean callOnHs : new boolean[] {false, true}) {
                            for (boolean headset : new boolean[] {false, true}) {
                                Calls.Volume v = volume(now, now, range, range, mediaOn, callOnHs);
                                for (String stream : STREAMS) {
                                    String id = stream + " " + range[0] + ".." + range[1] + " now " + now + " by " + by + " m" + mediaOn + " c" + callOnHs + " hs" + headset;
                                    VolumeRules.Plan p = VolumeRules.plan(stream, by, v, headset);
                                    boolean known = now >= range[0] && now <= range[1];
                                    boolean on = stream.equals(Calls.STREAM_CALL) ? callOnHs : mediaOn;
                                    int target = Math.max(range[0], Math.min(range[1], now + by));
                                    boolean want = by != 0 && Math.abs(by) <= VolumeRules.MAX_STEP && headset && known && on && target != now;
                                    eq(want, p.ok, "plan ok: " + id);
                                    if (p.ok) {
                                        eq(target, p.target, "the target is one clamped step: " + id);
                                        eq(now, p.before, "the starting point: " + id);
                                        yes(Math.abs(p.target - p.before) <= VolumeRules.MAX_STEP, "never more than the largest step: " + id);
                                        yes((p.target - p.before > 0) == (by > 0), "the right direction: " + id);
                                        yes(p.target >= range[0] && p.target <= range[1], "inside the range: " + id);
                                        yes(p.why.isEmpty(), "no sentence when it goes: " + id);
                                    } else {
                                        yes(!p.why.isEmpty(), "a refusal says why: " + id);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // the sentences for the refusals that a person reads
        Calls.Volume top = volume(15, 5, RANGES[0], RANGES[1], true, true);
        eq(VolumeRules.LOUDEST, VolumeRules.plan(Calls.STREAM_MEDIA, 1, top, true).why, "already loudest");
        Calls.Volume bottom = volume(0, 1, RANGES[0], RANGES[1], true, true);
        eq(VolumeRules.QUIETEST, VolumeRules.plan(Calls.STREAM_MEDIA, -1, bottom, true).why, "already quietest");
        eq(VolumeRules.QUIETEST, VolumeRules.plan(Calls.STREAM_CALL, -2, bottom, true).why, "the call's floor is its own minimum (1)");
        yes(VolumeRules.plan("ring", 1, top, true).why.equals(VolumeRules.WHICH), "which volume");
        yes(VolumeRules.plan(Calls.STREAM_MEDIA, 0, top, true).why.equals(VolumeRules.STEPS), "no zero step");
        yes(VolumeRules.plan(Calls.STREAM_MEDIA, 4, bottom, true).why.equals(VolumeRules.STEPS), "no large jump");
        yes(VolumeRules.plan(Calls.STREAM_MEDIA, 1, bottom, false).why.contains(VolumeRules.NO_HEADSET), "no headset: nothing moves");
        Calls.Volume speaker = volume(5, 3, RANGES[0], RANGES[1], false, false);
        yes(VolumeRules.plan(Calls.STREAM_MEDIA, 1, speaker, true).why.equals(VolumeRules.MEDIA_ELSEWHERE), "media on the phone's own speaker is left alone");
        yes(VolumeRules.plan(Calls.STREAM_CALL, 1, speaker, true).why.equals(VolumeRules.CALL_NOT_HEADSET), "no call, or a call not on the headset, is left alone");
    }

    private static void verifies() {
        for (int[] range : RANGES) {
            for (int before = range[0]; before < range[1]; before++) {
                for (int by : new int[] {-3, -1, 1, 3}) {
                    int target = Math.max(range[0], Math.min(range[1], before + by));
                    if (target == before) {
                        continue;
                    }
                    Calls.Volume v = volume(before, before, range, range, true, true);
                    for (String stream : STREAMS) {
                        VolumeRules.Plan p = VolumeRules.plan(stream, by, v, true);
                        yes(p.ok, "a plan for the read-back test");
                        for (int after = range[0] - 1; after <= range[1] + 1; after++) {
                            Calls.Level l = new Calls.Level();
                            l.min = range[0];
                            l.max = range[1];
                            l.now = after;
                            Calls.Result r = VolumeRules.verify(stream, p, l);
                            String id = stream + " " + range[0] + ".." + range[1] + " " + before + " by " + by + " read back " + after;
                            boolean inRange = after >= range[0] && after <= range[1];
                            eq(inRange && after == target, r.ok, "believed only when it reads back as asked: " + id);
                            if (r.ok) {
                                eq(VolumeRules.name(stream) + " " + after + " of " + range[1] + ".", r.out, "the words of success: " + id);
                            } else {
                                yes(!r.why.isEmpty(), "a failure says why: " + id);
                            }
                            if (inRange && after == before) {
                                yes(by > 0 ? r.why.startsWith("Android did not make it louder (") : r.why.startsWith("Android did not make it quieter ("),
                                        "a refusal by Android is told as that: " + id);
                                yes(by > 0 ? r.why.contains("look at it") : true, "louder: look at the phone's screen: " + id);
                            }
                        }
                    }
                }
            }
        }
        Calls.Level unknown = new Calls.Level();
        VolumeRules.Plan any = VolumeRules.plan(Calls.STREAM_MEDIA, 1, volume(3, 3, RANGES[0], RANGES[0], true, true), true);
        yes(!VolumeRules.verify(Calls.STREAM_MEDIA, any, unknown).ok, "an unreadable volume is never a success");
        yes(!VolumeRules.verify(Calls.STREAM_MEDIA, any, null).ok, "null is never a success");
        // a read-back that is only a number matching the target, from a range that makes no sense, is not believed either
        for (int[] odd : new int[][] {{any.target, any.target, any.target}, {0, any.target, any.target - 1}, {any.target + 1, any.target, 20}}) {
            Calls.Level l = new Calls.Level();
            l.min = odd[0];
            l.now = odd[1];
            l.max = odd[2];
            yes(!VolumeRules.verify(Calls.STREAM_MEDIA, any, l).ok, "a nonsense range is never a success: " + odd[0] + " " + odd[1] + " " + odd[2]);
        }
    }

    private static void wire() throws Exception {
        for (int[] range : RANGES) {
            for (boolean mediaOn : new boolean[] {false, true}) {
                for (boolean callOnHs : new boolean[] {false, true}) {
                    Calls.Now n = new Calls.Now();
                    n.volume = volume(range[1], range[0], range, range, mediaOn, callOnHs);
                    n.headset = "Sony WH";
                    String text = Json.write(CallJson.now(n));
                    Calls.Now back = CallJson.nowFrom(Json.parseObject(text));
                    String id = range[0] + ".." + range[1] + " " + mediaOn + " " + callOnHs;
                    yes(!back.volume.old, "a new phone is not old: " + id);
                    eq(range[1], back.volume.media.now, "media now: " + id);
                    eq(range[0], back.volume.call.now, "call now: " + id);
                    eq(range[0], back.volume.media.min, "media min: " + id);
                    eq(range[1], back.volume.media.max, "media max: " + id);
                    eq(range[0], back.volume.call.min, "call min: " + id);
                    eq(range[1], back.volume.call.max, "call max: " + id);
                    eq(mediaOn, back.volume.mediaOnHeadset, "media on headset: " + id);
                    eq(callOnHs, back.volume.callOnHeadset, "call on headset: " + id);
                    yes(back.headset.equals("Sony WH"), "the rest of the picture is untouched: " + id);
                }
            }
        }
        // an older phone's picture has no volume at all: it is told apart from "not known"
        Map<String, Object> older = Json.parseObject("{\"stamp\":\"3\",\"at\":1,\"calls\":[],\"mute\":false,\"route\":\"phone\",\"missed\":0,\"hs\":\"Sony\",\"hsb\":50}");
        Calls.Now n = CallJson.nowFrom(older);
        yes(n.volume.old, "an older phone says nothing about volume");
        VolumeRules.Row r = VolumeRules.row(n.volume, Calls.STREAM_MEDIA, true, true, false);
        eq(VolumeRules.OLD_PHONE, r.why, "the old phone's sentence");
        yes(!r.enabled, "an old phone's buttons are off");
        // a picture that was never heard has nothing to offer either
        VolumeRules.Row fresh = VolumeRules.row(new Calls.Now().volume, Calls.STREAM_MEDIA, true, true, false);
        yes(!fresh.enabled, "nothing heard: nothing offered");
    }
}
