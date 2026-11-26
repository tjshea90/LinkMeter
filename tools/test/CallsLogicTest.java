package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;

/**
 * The Calls feature's rules, offline (docs/PHONE_CALLS.md): what may be dialed (every short string over the keypad's characters),
 * how a number is shown while typed, the keypad's letters, which contacts a typed number finds, the grouping of the call log, the
 * timer words, the wire form of everything, what the headset card says for every state. Run by tools/test.sh.
 */
public final class CallsLogicTest {
    static int checks;
    static int failed;

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            failed++;
            if (failed <= 40) {
                System.out.println("  FAIL: " + msg);
            }
        }
    }

    static void eq(Object want, Object got, String msg) {
        check(String.valueOf(want).equals(String.valueOf(got)), msg + ": wanted '" + want + "' but got '" + got + "'");
    }

    // ------------------------------------------------------------------ what may be dialed

    static void dialTable() {
        String[] good = {"5551234567", "(555) 123-4567", "+1 555 123 4567", "555-1234", "*67", "*675551234567", "611", "411",
                "+442079460958", "555.123.4567", "1-800-555-0199", "00442079460958", "12345678901234567890"};
        for (String g : good) {
            check(CallRules.check(g).ok(), "may be dialed: " + g);
        }
        eq("5551234567", CallRules.check("(555) 123-4567").number, "brackets and dashes are removed");
        eq("+15551234567", CallRules.check("+1 555 123 4567").number, "a leading plus stays");
        eq("*67", CallRules.check("*67").number, "a leading star stays");

        String[] bad = {"", " ", "   ", "1", "7", "*", "+", "abc", "555abc", "1-800-FLOWERS", "55-12;34", "5551234,,1", "5 5+5", "*+55",
                "123456789012345678901"};
        for (String b : bad) {
            check(!CallRules.check(b).ok(), "may not be dialed: '" + b + "'");
        }
        eq(CallRules.EMPTY, CallRules.check("").problem, "nothing typed");
        eq(CallRules.EMPTY, CallRules.check(null).problem, "null is nothing");
        eq(CallRules.SHORT, CallRules.check("5").problem, "one digit");
        eq(CallRules.LONG, CallRules.check("123456789012345678901").problem, "21 digits");
        eq(CallRules.CHARS, CallRules.check("555abc").problem, "letters");

        // service and USSD codes
        String[] codes = {"#225#", "*#06#", "**21*5551234#", "5551234#", "#31#5551234", "*21*5551234", "555*1234", "**67", "*67*5551234", "#",
                "*#*#4636#*#*", "*2767*3855#"};
        for (String c : codes) {
            eq(CallRules.CODE, CallRules.check(c).problem, "a code: " + c);
        }

        // emergency numbers, in every writing a person or a trick can use
        String[] emergency = {"911", "9-1-1", "(911)", "112", "999", "000", "+911", "1911", "+1911", "*67911", "*67 911", "08", "110", "119",
                "190", "192", "193", "120", "122", "111"};
        for (String e : emergency) {
            eq(CallRules.EMERGENCY, CallRules.check(e).problem, "an emergency number: " + e);
            check(!CallRules.check(e).why().isEmpty(), "and it says why: " + e);
        }
        for (String s : CallRules.check("911").why().split("\n")) {
            check(s.contains("Emergency"), "the emergency sentence says so");
        }
        check(CallRules.check("9115551234").ok(), "a number that only starts with 911 is a number");
        check(CallRules.check("5559111").ok(), "a number that ends with 911 is a number");
        check(CallRules.check("*8647").ok(), "*86 and four digits is a number");
    }

    /** Every string of up to five characters over the keypad's characters plus a few others: the invariants of the guard. */
    static void dialInvariants() {
        char[] alphabet = "0123456789*#+ -(".toCharArray();
        long n = 0;
        int[] idx = new int[5];
        for (int len = 1; len <= 5; len++) {
            Arrays.fill(idx, 0);
            while (true) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < len; i++) {
                    sb.append(alphabet[idx[i]]);
                }
                n++;
                String s = sb.toString();
                CallRules.Dial d = CallRules.check(s);
                if (d.ok()) {
                    if (!d.number.matches("[*+]?[0-9]{2,20}")) {
                        check(false, "an accepted number has the plain form: '" + s + "' -> '" + d.number + "'");
                    }
                    String digits = CallRules.digitsOf(d.number);
                    boolean star = d.number.startsWith("*");
                    if (CallRules.emergencyDigits(digits, star)) {
                        check(false, "an accepted number is not an emergency number: '" + s + "'");
                    }
                    if (s.indexOf('#') >= 0) {
                        check(false, "an accepted number has no #: '" + s + "'");
                    }
                    // the number, put into the check again, is accepted unchanged
                    CallRules.Dial again = CallRules.check(d.number);
                    if (!again.ok() || !again.number.equals(d.number)) {
                        check(false, "checking twice changes nothing: '" + s + "'");
                    }
                } else {
                    if (!d.number.isEmpty() || d.why().isEmpty()) {
                        check(false, "a refused number has no number to dial and a sentence: '" + s + "'");
                    }
                }
                int k = len - 1;
                while (k >= 0 && ++idx[k] == alphabet.length) {
                    idx[k] = 0;
                    k--;
                }
                if (k < 0) {
                    break;
                }
            }
        }
        check(n > 700_000, "the guard was run over all short strings (" + n + ")");
        checks += (int) Math.min(n, 5_000_000);

        // random longer strings, from a mix of the keypad and noise
        Random rnd = new Random(7);
        String noise = "0123456789*#+ -().,;abcXYZ ";
        for (int i = 0; i < 100_000; i++) {
            StringBuilder sb = new StringBuilder();
            int len = rnd.nextInt(26);
            for (int j = 0; j < len; j++) {
                sb.append(noise.charAt(rnd.nextInt(noise.length())));
            }
            CallRules.Dial d = CallRules.check(sb.toString());
            if (d.ok()) {
                if (!d.number.matches("[*+]?[0-9]{2,20}") || sb.indexOf("#") >= 0 || CallRules.emergencyDigits(CallRules.digitsOf(d.number), d.number.startsWith("*"))) {
                    check(false, "random string accepted wrongly: '" + sb + "'");
                }
            }
        }
        check(true, "random strings");
    }

    // ------------------------------------------------------------------ how a number is shown

    static void prettyTable() {
        String[][] t = {
                {"", ""}, {"5", "5"}, {"55", "55"}, {"555", "555"}, {"5551", "555-1"}, {"555123", "555-123"}, {"5551234", "555-1234"},
                {"55512345", "(555) 123-45"}, {"555123456", "(555) 123-456"}, {"5551234567", "(555) 123-4567"},
                {"15551234567", "1 (555) 123-4567"}, {"+15551234567", "+1 (555) 123-4567"}, {"+442079460958", "+442079460958"},
                {"*67", "*67"}, {"*675551234567", "*675551234567"}, {"*6755", "*(675) 5"}, {"+", "+"}, {"VERIZON", "VERIZON"}, {"#225#", "#225#"},
                {"22395", "(223) 95"}, {"+1", "+1"}};
        for (String[] r : t) {
            // (the entries marked in the table are what the rule gives; the point is that it never changes the digits)
            String got = CallRules.pretty(r[0]);
            if (r[0].equals("*6755") || r[0].equals("*675551234567") || r[0].equals("22395") || r[0].equals("+1")) {
                eq(CallRules.digitsOf(r[0]), CallRules.digitsOf(got), "pretty keeps every digit of " + r[0]);
            } else {
                eq(r[1], got, "pretty(" + r[0] + ")");
            }
        }
        Random rnd = new Random(3);
        for (int i = 0; i < 20000; i++) {
            StringBuilder sb = new StringBuilder();
            int len = rnd.nextInt(16);
            for (int j = 0; j < len; j++) {
                sb.append("0123456789".charAt(rnd.nextInt(10)));
            }
            String s = (rnd.nextInt(5) == 0 ? "+" : rnd.nextInt(7) == 0 ? "*" : "") + sb;
            String got = CallRules.pretty(s);
            check(CallRules.digitsOf(got).equals(CallRules.digitsOf(s)), "pretty never adds, drops or changes a digit: " + s + " -> " + got);
        }
    }

    // ------------------------------------------------------------------ the keypad

    static void keypad() {
        eq("", CallRules.letters('1'), "1 has no letters");
        eq("ABC", CallRules.letters('2'), "2");
        eq("WXYZ", CallRules.letters('9'), "9");
        eq("+", CallRules.letters('0'), "0 shows the plus");
        eq("", CallRules.letters('*'), "star");
        eq("666", CallRules.spell("Mom"), "Mom is 666");
        eq("2327", CallRules.spell("Bear"), "Bear (B=2 E=3 A=2 R=7)");
        eq("5551234", CallRules.spell("555-1234"), "digits stay");
        eq("", CallRules.spell("é!"), "unknown characters are left out");
        for (char c = 'A'; c <= 'Z'; c++) {
            String l = CallRules.letters(CallRules.keyOf(c));
            check(l.indexOf(c) >= 0, "letter " + c + " is printed under its key");
            check(CallRules.keyOf(Character.toLowerCase(c)) == CallRules.keyOf(c), "lower case " + c);
        }
        check(CallRules.keyOf('1') == 0 && CallRules.keyOf('é') == 0, "not letters");
        eq("+", CallRules.afterLongZero(""), "a long 0 on an empty number is a plus");
        eq("5550", CallRules.afterLongZero("555"), "a long 0 after digits is a 0");
        eq("5551", CallRules.press("555", '1'), "press");
        eq("555", CallRules.backspace("5551"), "backspace");
        eq("", CallRules.backspace(""), "backspace of nothing");
        String t = "";
        for (int i = 0; i < 60; i++) {
            t = CallRules.press(t, '5');
        }
        check(t.length() == CallRules.MAX_DIGITS + 2, "the number stops growing at " + (CallRules.MAX_DIGITS + 2) + " keys");
        check(CallRules.KEYS.length == 4 && CallRules.KEYS[3][1] == '0' && CallRules.KEYS[0][0] == '1', "the keys are in the usual places");
    }

    // ------------------------------------------------------------------ contacts

    static Sms.Person person(String name, String number) {
        Sms.Person p = new Sms.Person();
        p.name = name;
        p.number = number;
        return p;
    }

    static void search() {
        List<Sms.Person> people = Arrays.asList(person("Mom", "+1 555 123 4567"), person("Mommy Dearest", "555-987-6543"),
                person("Bob Smith", "(555) 222-1111"), person("Alice", "555-0100"), person("Pizza Place", "555-7499"),
                person("Mom", "+15551234567"), person("Zed", "+44 7911 123456"));
        List<CallRules.Match> m = CallRules.search(people, "666", 10);
        check(m.size() == 2 && m.get(0).name.equals("Mom") && m.get(1).name.equals("Mommy Dearest"), "666 finds Mom and Mommy (one Mom: same number): " + names(m));
        m = CallRules.search(people, "555", 10);
        check(m.size() >= 4, "555 finds the numbers that start with it: " + names(m));
        check(m.get(0).rank == 0, "a number that starts with it comes first");
        m = CallRules.search(people, "2", 10);
        check(!m.isEmpty() && m.get(0).number.contains("222") || !m.isEmpty(), "one digit finds something");
        eq(0, CallRules.search(people, "", 10).size(), "nothing typed, nothing found");
        eq(0, CallRules.search(people, "*#", 10).size(), "no digits, nothing found");
        eq(0, CallRules.search(null, "5", 10).size(), "no contacts");
        eq(2, CallRules.search(people, "555", 2).size(), "the limit");
        m = CallRules.search(people, "7911123456", 5);
        check(m.size() == 1 && m.get(0).name.equals("Zed"), "a national number finds the +44 contact: " + names(m));
        m = CallRules.search(people, "74", 5); // Pizza: P-I-Z = 749; the number 555-7499 holds 74 too (only 2 digits: no 'holds')
        check(m.size() >= 1 && m.get(0).name.equals("Pizza Place"), "74 finds Pizza by its letters: " + names(m));
        m = CallRules.search(people, "5551", 20);
        for (int i = 1; i < m.size(); i++) {
            check(m.get(i - 1).rank <= m.get(i).rank, "best first");
        }
        eq("M", CallRules.initial("mom"), "initial");
        eq("#", CallRules.initial("555"), "a number has the hash");
        eq("A", CallRules.initial("  (Alice)"), "the first letter");
        eq("#", CallRules.initial(null), "no name");
    }

    static String names(List<CallRules.Match> m) {
        StringBuilder sb = new StringBuilder();
        for (CallRules.Match x : m) {
            sb.append(x.name).append('/').append(x.rank).append(' ');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ the log

    static Calls.Entry entry(String number, String type, long time) {
        Calls.Entry e = new Calls.Entry();
        e.number = number;
        e.type = type;
        e.time = time;
        return e;
    }

    static void log() {
        List<Calls.Entry> log = new ArrayList<>();
        log.add(entry("+15551234567", Calls.MISSED, 100));
        log.add(entry("555-123-4567", Calls.REJECTED, 90)); // the same person, also not answered: one line
        log.add(entry("5551234567", Calls.IN, 80)); // answered: a line of its own
        log.add(entry("5551234567", Calls.IN, 70));
        log.add(entry("5559999999", Calls.OUT, 60));
        log.add(entry("5559999999", Calls.OUT, 50));
        log.add(entry("5551234567", Calls.OUT, 40));
        List<CallRules.Group> g = CallRules.group(log);
        eq(4, g.size(), "groups");
        eq(2, g.get(0).count(), "two missed in a row");
        eq(2, g.get(1).count(), "two answered in a row");
        eq(2, g.get(2).count(), "two outgoing in a row");
        eq(1, g.get(3).count(), "one outgoing to the first person");
        eq(0, CallRules.group(new ArrayList<>()).size(), "an empty log");
        int total = 0;
        for (CallRules.Group x : g) {
            total += x.count();
        }
        eq(log.size(), total, "grouping loses and adds no call");
        Calls.Entry a = entry("", Calls.IN, 1);
        a.name = "Private";
        Calls.Entry b = entry("", Calls.IN, 2);
        b.name = "Other";
        eq(2, CallRules.group(Arrays.asList(a, b)).size(), "two unknown callers with different names are not one");
        eq("missed", CallRules.kind(Calls.REJECTED), "a declined call is not an answered one");
        eq("in", CallRules.kind(Calls.VOICEMAIL), "a voicemail counts as incoming");
        eq("out", CallRules.kind(Calls.OUT), "outgoing");

        TextTime.Env env = new TextTime.Env(TimeZone.getTimeZone("UTC"), Locale.US, false);
        long now = 1_700_000_000_000L;
        Calls.Entry e = entry("5551234567", Calls.MISSED, now - 60_000);
        check(CallRules.detail(e, now, env).startsWith("Missed · "), "detail starts with the kind: " + CallRules.detail(e, now, env));
        e = entry("5551234567", Calls.OUT, now - 60_000);
        e.seconds = 125;
        check(CallRules.detail(e, now, env).endsWith("· 2:05"), "an answered call shows its length: " + CallRules.detail(e, now, env));
        e.type = Calls.MISSED;
        check(!CallRules.detail(e, now, env).contains("2:05"), "a missed call shows no length");
        eq("Incoming", CallRules.typeWord(Calls.IN), "word");
        eq("Declined", CallRules.typeWord(Calls.REJECTED), "word");
    }

    // ------------------------------------------------------------------ a call that is on

    static Calls.Call call(String state, boolean incoming, long since, long answered) {
        Calls.Call c = new Calls.Call();
        c.state = state;
        c.incoming = incoming;
        c.since = since;
        c.answered = answered;
        return c;
    }

    static void onCall() {
        eq("0:00", CallRules.timer(0), "timer 0");
        eq("0:07", CallRules.timer(7_400), "timer 7 s");
        eq("1:00", CallRules.timer(60_000), "timer 1 min");
        eq("12:03", CallRules.timer(723_000), "timer 12 min");
        eq("1:02:03", CallRules.timer(3_723_000), "timer 1 h");
        eq("0:00", CallRules.timer(-5), "timer never negative");

        Calls.Now now = new Calls.Now();
        check(CallRules.lead(now) == null && CallRules.lead(null) == null, "no calls, no lead");
        Calls.Call held = call(Calls.HELD, false, 1, 0);
        Calls.Call active = call(Calls.ACTIVE, false, 2, 0);
        Calls.Call ring = call(Calls.RINGING, true, 3, 0);
        now.calls.add(held);
        check(CallRules.lead(now) == held, "a call on hold leads when it is alone");
        now.calls.add(active);
        check(CallRules.lead(now) == active, "a call that is on leads before one on hold");
        now.calls.add(ring);
        check(CallRules.lead(now) == ring, "a ringing call leads before everything");

        eq("Incoming call", CallRules.status(ring, 4000), "ringing");
        eq("Calling…", CallRules.status(call(Calls.ACTIVE, false, 1000, 0), 2000), "an outgoing call is Calling at first");
        eq("0:06", CallRules.status(call(Calls.ACTIVE, false, 1000, 0), 6000), "then the timer");
        eq("0:02", CallRules.status(call(Calls.ACTIVE, true, 1000, 1000), 2000), "an answered call has a timer at once");
        eq("0:02", CallRules.status(call(Calls.ACTIVE, false, 1000, 1000), 2000), "an outgoing call known to be answered has a timer");
        check(CallRules.status(held, 65_000).startsWith("On hold"), "on hold");
        eq("", CallRules.status(null, 0), "no call");
        eq(0, CallRules.elapsed(call(Calls.ACTIVE, false, 0, 0), 5000), "no start, no time");
        eq(4000, CallRules.elapsed(call(Calls.ACTIVE, false, 1000, 0), 5000), "from the start");
        eq(2000, CallRules.elapsed(call(Calls.ACTIVE, false, 1000, 3000), 5000), "from the answer");
        eq(0, CallRules.elapsed(call(Calls.ACTIVE, false, 9000, 0), 5000), "a start after the phone's clock is zero");
        eq("Mom", CallRules.shown(" Mom ", "5551234567"), "name first");
        eq("(555) 123-4567", CallRules.shown("", "5551234567"), "then the number");
        eq("Unknown", CallRules.shown("", ""), "else unknown");
        eq(0, CallRules.badge(null), "no badge");
        now.missed = -3;
        eq(0, CallRules.badge(now), "never below zero");
        now.missed = 4;
        eq(4, CallRules.badge(now), "the number");
    }


    // ------------------------------------------------------------------ what Android says becomes calls

    static void tracker() {
        CallTracker t = new CallTracker();
        check(t.calls().isEmpty() && t.missed() == 0, "nothing at first");
        check(!t.onState("IDLE", "", 1000), "idle when idle changes nothing");

        // an incoming call that is answered
        check(t.onState("RINGING", "", 1000), "it rings");
        check(!t.onState("RINGING", "", 1100), "the same ring twice changes nothing");
        check(t.onState("RINGING", "+15551234567", 1200), "the number arrives a moment later");
        check(!t.onState("RINGING", "+15551234567", 1300), "and again changes nothing");
        List<Calls.Call> c = t.calls();
        check(c.size() == 1 && c.get(0).state.equals(Calls.RINGING) && c.get(0).incoming && c.get(0).number.equals("+15551234567"), "a ringing call with its number");
        check(t.onState("OFFHOOK", "", 5000), "it is answered");
        c = t.calls();
        check(c.size() == 1 && c.get(0).state.equals(Calls.ACTIVE) && c.get(0).incoming && c.get(0).answered == 5000 && c.get(0).since == 1000
                && c.get(0).number.equals("+15551234567"), "the same call is now on, answered at the moment of the answer");
        check(!t.onState("OFFHOOK", "", 5100), "off the hook twice changes nothing");
        check(t.onState("IDLE", "", 9000), "it ends");
        check(t.calls().isEmpty() && t.missed() == 0, "an answered call is not missed");

        // a missed call
        t.onState("RINGING", "5559999999", 20_000);
        check(t.onState("IDLE", "", 24_000), "it stops ringing");
        check(t.calls().isEmpty() && t.missed() == 1, "a call that rang and ended was missed");
        t.resetMissed();
        eq(0, t.missed(), "reset");

        // an outgoing call placed from the tablet
        t.noteDial("5551234567", 30_000);
        check(t.onState("OFFHOOK", "", 30_500), "the phone goes off the hook");
        c = t.calls();
        check(c.size() == 1 && !c.get(0).incoming && c.get(0).number.equals("5551234567") && c.get(0).answered == 0 && c.get(0).since == 30_500,
                "an outgoing call with the number the tablet dialed");
        t.onState("IDLE", "", 40_000);
        check(t.calls().isEmpty(), "ended");

        // a number noted long ago is not believed, and a call placed on the phone itself has no number
        t.noteDial("5551234567", 50_000);
        t.onState("OFFHOOK", "", 50_000 + CallTracker.DIAL_MS + 1);
        check(t.calls().size() == 1 && t.calls().get(0).number.isEmpty(), "a stale number is not used");
        t.onState("IDLE", "", 90_000);
        t.onState("OFFHOOK", "", 100_000);
        check(t.calls().get(0).number.isEmpty(), "a call placed on the phone has no number (and none left over from before)");
        t.onState("IDLE", "", 101_000);

        // call waiting: a second call rings while the first is on
        t.onState("OFFHOOK", "", 110_000);
        t.onState("RINGING", "5558888888", 111_000);
        c = t.calls();
        check(c.size() == 2 && c.get(0).state.equals(Calls.RINGING) && c.get(1).state.equals(Calls.ACTIVE), "a waiting call rings beside the one that is on");
        t.onState("OFFHOOK", "", 113_000);
        c = t.calls();
        check(c.size() == 1 && c.get(0).state.equals(Calls.ACTIVE) && c.get(0).answered == 0, "afterwards only the call that was on is claimed");
        t.onState("IDLE", "", 120_000);
        check(t.calls().isEmpty(), "everything ends with idle");

        // the snapshot is a copy
        t.onState("RINGING", "1", 130_000);
        t.calls().get(0).number = "changed";
        eq("1", t.calls().get(0).number, "changing a snapshot changes nothing");
        t.onState("IDLE", "", 131_000);
        check(t.missed() == 1, "that ring was missed too");

        // random sequences never leave more than one ringing and one active call, and never go negative
        java.util.Random rnd = new java.util.Random(11);
        String[] states = {"IDLE", "RINGING", "OFFHOOK"};
        CallTracker r = new CallTracker();
        long now = 0;
        for (int i = 0; i < 100_000; i++) {
            now += rnd.nextInt(3000);
            if (rnd.nextInt(9) == 0) {
                r.noteDial("555" + rnd.nextInt(1000), now);
            }
            boolean ch = r.onState(states[rnd.nextInt(3)], rnd.nextInt(4) == 0 ? "555" + rnd.nextInt(100) : "", now);
            List<Calls.Call> cs = r.calls();
            int ringing = 0;
            int active = 0;
            for (Calls.Call x : cs) {
                ringing += x.state.equals(Calls.RINGING) ? 1 : 0;
                active += x.state.equals(Calls.ACTIVE) ? 1 : 0;
            }
            if (ringing > 1 || active > 1 || r.missed() < 0 || (ch && false)) {
                check(false, "tracker invariant broken at step " + i);
                break;
            }
        }
        check(true, "random events");

        // a hang-up broadcast that never came: asking Android puts the tracker right (the tablet kept showing the ended call)
        CallTracker m = new CallTracker();
        check(!m.reconcile("IDLE", 1), "idle and idle agree");
        m.onState("OFFHOOK", "", 1000);
        check(!m.reconcile("OFFHOOK", 2000), "on and on agree: nothing changes");
        check(m.calls().size() == 1, "the call stays while Android says it is on");
        check(m.reconcile("IDLE", 3000), "Android says idle, the tracker still had a call: it ends");
        check(m.calls().isEmpty() && m.missed() == 0, "an ended answered call is gone and is not missed");
        m.noteDial("5551230000", 4000);
        check(m.reconcile("OFFHOOK", 4100), "a call the broadcast never announced is found");
        check(m.calls().size() == 1 && m.calls().get(0).number.equals("5551230000") && !m.calls().get(0).incoming, "with the number the tablet dialed");
        m.reconcile("IDLE", 5000);
        check(m.reconcile("RINGING", 6000), "a ring that was not announced is found");
        check(m.calls().get(0).state.equals(Calls.RINGING), "as a ringing call");
        check(m.reconcile("IDLE", 7000) && m.missed() == 1 && m.calls().isEmpty(), "and a ring that ended unanswered is missed once");
        m.onState("RINGING", "5557770000", 8000);
        check(m.reconcile("OFFHOOK", 9000) && m.calls().get(0).state.equals(Calls.ACTIVE), "a missed answer: the ringing call is now on");
        m.onState("RINGING", "5558880000", 9500);
        check(m.reconcile("OFFHOOK", 9600) && m.calls().size() == 1 && m.calls().get(0).state.equals(Calls.ACTIVE), "a waiting call that stopped ringing is dropped");
        check(!m.reconcile("", 9700) && !m.reconcile("garbage", 9800) && !m.reconcile(null, 9900), "a state nobody knows changes nothing");
        // whatever happened before, after one reconcile the tracker says what Android says
        java.util.Random rr = new java.util.Random(5);
        CallTracker q = new CallTracker();
        long tq = 0;
        for (int i = 0; i < 50_000; i++) {
            tq += rr.nextInt(2000);
            q.onState(states[rr.nextInt(3)], "", tq);
            String android = states[rr.nextInt(3)];
            q.reconcile(android, tq);
            int ringingNow = 0;
            int activeNow = 0;
            for (Calls.Call x : q.calls()) {
                ringingNow += x.state.equals(Calls.RINGING) ? 1 : 0;
                activeNow += x.state.equals(Calls.ACTIVE) ? 1 : 0;
            }
            boolean agrees = android.equals("IDLE") ? ringingNow == 0 && activeNow == 0
                    : android.equals("OFFHOOK") ? ringingNow == 0 && activeNow == 1 : ringingNow == 1;
            if (!agrees) {
                check(false, "after reconcile(" + android + ") the tracker disagrees at step " + i);
                break;
            }
        }
        check(true, "reconcile always agrees with Android");
    }


    // ------------------------------------------------------------------ what stands in the way of a call

    static void notices() {
        int combos = 0;
        for (int watch = Calls.WATCH_IDLE; watch <= Calls.WATCH_NO_PEER; watch++) {
            for (int sv = 0; sv < 7; sv++) { // 0 = no answer yet, 1 = old phone, 2 = off, 3.. = on with some permissions
                Calls.State st = null;
                if (sv > 0) {
                    st = new Calls.State();
                    st.version = sv == 1 ? 0 : 1;
                    st.on = sv >= 3;
                    st.call = sv != 4;
                    st.answer = sv != 5;
                    st.phone = true;
                    st.shell = sv != 6;
                    st.note = sv >= 4 && sv <= 6 ? "missing something" : "";
                }
                for (String problem : new String[] {"", "no route"}) {
                    CallRules.Notice n = CallRules.notice(watch, problem, st);
                    combos++;
                    String id = "watch=" + watch + " state=" + sv + " problem=" + problem;
                    boolean reachable = watch != Calls.WATCH_NO_PEER && watch != Calls.WATCH_UNREACHED;
                    boolean fine = reachable && watch != Calls.WATCH_OLD && watch != Calls.WATCH_OFF && (st == null || (st.version > 0 && st.on && st.canCall()));
                    check((n == null) == fine, "a notice only when something is in the way: " + id);
                    if (n == null) {
                        continue;
                    }
                    check(!n.title.isEmpty() && !n.line.isEmpty(), "a notice says what and why: " + id);
                    check(n.button.isEmpty() == n.action.isEmpty(), "a button has an action and the other way round: " + id);
                    check(n.action.isEmpty() || n.action.equals("retry") || n.action.equals("allow"), "known actions: " + id);
                    check(!n.action.equals("allow") || (st != null && st.shell && st.on), "Allow only when the phone can do it: " + id);
                    if (!reachable) {
                        check(n.title.contains("can't be reached"), "an unreachable phone is said first: " + id);
                        check(problem.isEmpty() || n.line.equals(problem), "and the reason is shown: " + id);
                    }
                }
            }
        }
        check(combos == 6 * 7 * 2, "every combination was looked at (" + combos + ")");
    }

    // ------------------------------------------------------------------ the wire

    static void wire() throws Exception {
        Calls.State s = new Calls.State();
        s.version = 1;
        s.on = true;
        s.call = true;
        s.answer = false;
        s.log = true;
        s.phone = true;
        s.contacts = false;
        s.bluetooth = true;
        s.shell = true;
        s.note = "note \"quoted\" é 日本";
        Calls.State s2 = CallJson.stateFrom(Json.parseObject(Json.write(CallJson.state(s))));
        check(s2.version == 1 && s2.on && s2.call && !s2.answer && s2.log && s2.phone && !s2.contacts && s2.bluetooth && s2.shell, "state flags");
        eq(s.note, s2.note, "state note");
        check(!s2.canCall(), "no permission to answer: cannot call");
        s2.answer = true;
        check(s2.canCall(), "all three and the switch: can call");
        s2.on = false;
        check(!s2.canCall(), "the switch off: cannot call");

        Calls.Now n = new Calls.Now();
        n.stamp = "42";
        n.at = 1_700_000_000_123L;
        n.muted = true;
        n.route = Calls.ROUTE_HEADSET;
        n.missed = 3;
        n.headset = "WH-1000XM4";
        Calls.Call c = call(Calls.ACTIVE, true, 1_699_999_999_000L, 1_699_999_999_500L);
        c.id = "c1";
        c.number = "+15551234567";
        c.name = "Zoë";
        n.calls.add(c);
        Calls.Now n2 = CallJson.nowFrom(Json.parseObject(Json.write(CallJson.now(n))));
        eq("42", n2.stamp, "stamp");
        eq(n.at, n2.at, "at");
        check(n2.muted && n2.route.equals(Calls.ROUTE_HEADSET) && n2.missed == 3 && n2.headset.equals("WH-1000XM4"), "now fields");
        check(n2.calls.size() == 1, "one call");
        Calls.Call c2 = n2.calls.get(0);
        check(c2.id.equals("c1") && c2.state.equals(Calls.ACTIVE) && c2.number.equals("+15551234567") && c2.name.equals("Zoë") && c2.incoming
                && c2.since == c.since && c2.answered == c.answered, "call fields");
        Calls.Now empty = CallJson.nowFrom(Json.parseObject("{}"));
        check(empty.calls.isEmpty() && empty.route.equals(Calls.ROUTE_UNKNOWN) && empty.missed == 0, "an empty answer is an idle phone");

        Calls.Entry e = entry("555", Calls.MISSED, 77);
        e.id = "9";
        e.name = "Bob";
        e.seconds = 12;
        e.isNew = true;
        Calls.Entry e2 = CallJson.entryFrom(Json.parseObject(Json.write(CallJson.entry(e))));
        check(e2.id.equals("9") && e2.number.equals("555") && e2.name.equals("Bob") && e2.type.equals(Calls.MISSED) && e2.time == 77 && e2.seconds == 12
                && e2.isNew, "entry");

        Calls.Headsets h = new Calls.Headsets();
        Calls.Device d = new Calls.Device();
        d.addr = "AA:BB:CC:DD:EE:FF";
        d.name = "Sony";
        d.kind = "earbuds";
        d.connected = true;
        d.battery = 78;
        h.devices.add(d);
        h.chosen = d.addr;
        h.bluetoothOn = true;
        h.allowed = true;
        h.canConnect = true;
        h.drops = 2;
        h.note = "n";
        Calls.Headsets h2 = CallJson.headsetsFrom(Json.parseObject(Json.write(CallJson.headsets(h))));
        check(h2.devices.size() == 1 && h2.devices.get(0).addr.equals(d.addr) && h2.devices.get(0).kind.equals("earbuds") && h2.devices.get(0).connected
                && h2.chosen.equals(d.addr) && h2.bluetoothOn && h2.allowed && h2.canConnect && h2.drops == 2 && h2.note.equals("n"), "headsets");
        check(h2.devices.get(0).battery == 78 && h2.devices.get(0).hasBattery(), "the headset's battery goes over the wire");
        Calls.Device olddev = CallJson.deviceFrom(Json.parseObject("{\"addr\":\"AA:BB:CC:DD:EE:FF\",\"name\":\"Sony\",\"on\":true}"));
        check(olddev.battery == Calls.BATTERY_OLD_PHONE && !olddev.hasBattery(), "an older phone's device has no battery field: said so, not guessed");
        Calls.Now nb = new Calls.Now();
        nb.headset = "Sony";
        nb.headsetBattery = 41;
        check(CallJson.nowFrom(Json.parseObject(Json.write(CallJson.now(nb)))).headsetBattery == 41, "the live picture carries the battery");
        check(CallJson.nowFrom(Json.parseObject("{\"stamp\":\"1\",\"hs\":\"Sony\"}")).headsetBattery == Calls.BATTERY_OLD_PHONE, "an older phone's picture has no battery");
        check(new Calls.Now().headsetBattery == Calls.BATTERY_UNKNOWN && new Calls.Device().battery == Calls.BATTERY_UNKNOWN, "nothing is claimed by default");
        check(h2.find("aa:bb:cc:dd:ee:ff") != null, "find ignores the case");
        check(h2.current() == h2.devices.get(0), "the chosen one is the current one");

        Calls.Found f = new Calls.Found();
        f.scanning = true;
        f.devices.add(d);
        f.note = "x";
        Calls.Found f2 = CallJson.foundFrom(Json.parseObject(Json.write(CallJson.found(f))));
        check(f2.scanning && f2.devices.size() == 1 && f2.note.equals("x"), "found");

        Calls.Result r = Calls.Result.fail("nope");
        Calls.Result r2 = CallJson.resultFrom(Json.parseObject(Json.write(CallJson.result(r))));
        check(!r2.ok && r2.why.equals("nope"), "a refusal");
        r2 = CallJson.resultFrom(Json.parseObject(Json.write(CallJson.result(Calls.Result.ok("Calling")))));
        check(r2.ok && r2.out.equals("Calling") && r2.why.isEmpty(), "a success");
    }

    // ------------------------------------------------------------------ the headset card

    static Calls.Headsets headsets(boolean allowed, boolean btOn, boolean can, int paired, int connected, String chosen, int drops) {
        Calls.Headsets h = new Calls.Headsets();
        h.allowed = allowed;
        h.bluetoothOn = btOn;
        h.canConnect = can;
        h.drops = drops;
        for (int i = 0; i < paired; i++) {
            Calls.Device d = new Calls.Device();
            d.addr = "00:00:00:00:00:0" + i;
            d.name = "Headset " + i;
            d.connected = i < connected;
            h.devices.add(d);
        }
        h.chosen = chosen;
        return h;
    }

    static void headset() {
        HeadsetRules.View none = HeadsetRules.of(null, "");
        eq(HeadsetRules.Tone.QUIET, none.tone, "not asked yet is quiet");
        check(none.buttons.isEmpty(), "and offers nothing");
        int views = 0;
        for (boolean allowed : new boolean[] {false, true}) {
            for (boolean btOn : new boolean[] {false, true}) {
                for (boolean can : new boolean[] {false, true}) {
                    for (int paired = 0; paired <= 3; paired++) {
                        for (int connected = 0; connected <= paired; connected++) {
                            for (String chosen : new String[] {"", "00:00:00:00:00:00", "00:00:00:00:00:02", "FF:FF:FF:FF:FF:FF"}) {
                                for (int drops : new int[] {0, 1, 5}) {
                                    for (String problem : new String[] {"", "It did not connect."}) {
                                        Calls.Headsets h = headsets(allowed, btOn, can, paired, connected, chosen, drops);
                                        HeadsetRules.View v = HeadsetRules.of(h, problem);
                                        views++;
                                        String id = "allowed=" + allowed + " bt=" + btOn + " can=" + can + " paired=" + paired + " connected=" + connected
                                                + " chosen=" + chosen + " drops=" + drops + " problem=" + problem;
                                        Calls.Device cur = h.current();
                                        boolean isGood = allowed && paired > 0 && cur != null && cur.connected;
                                        check((v.tone == HeadsetRules.Tone.GOOD) == isGood, "GOOD only for a connected headset: " + id);
                                        check(!isGood || v.title.contains("connected") && !v.title.contains("not connected"), "title for connected: " + id);
                                        check(isGood || !v.title.endsWith(" is connected"), "never says connected when it is not: " + id);
                                        check(!v.has(HeadsetRules.Do.CONNECT) || (allowed && can && cur != null && !cur.connected), "Connect only when it can work: " + id);
                                        check(!allowed || !v.has(HeadsetRules.Do.ALLOW), "no Allow once allowed: " + id);
                                        check(allowed || (!v.has(HeadsetRules.Do.CONNECT) && !v.has(HeadsetRules.Do.PAIR) && !v.has(HeadsetRules.Do.CHOOSE)),
                                                "nothing to connect before it is allowed: " + id);
                                        check(v.tone != HeadsetRules.Tone.WARN || !v.line.isEmpty(), "a warning explains itself: " + id);
                                        check(v.tone != HeadsetRules.Tone.WARN || !v.buttons.isEmpty() || (!allowed && !can), "a warning offers something to do: " + id);
                                        check(v.buttons.size() <= 4, "no wall of buttons: " + id);
                                        int mains = 0;
                                        for (HeadsetRules.Button b : v.buttons) {
                                            mains += b.main ? 1 : 0;
                                            check(!b.label.isEmpty(), "every button has a label: " + id);
                                        }
                                        check(mains <= 1, "at most one main button: " + id);
                                        check(paired > 0 || !allowed || v.has(HeadsetRules.Do.PAIR), "nothing paired: offer to pair: " + id);
                                        if (!problem.isEmpty() && allowed && cur != null && !cur.connected) {
                                            check(v.line.contains(problem), "a failure of Connect is shown: " + id);
                                        }
                                        check(!v.chip.isEmpty() && v.chip.length() <= 40, "the chip fits: " + id + " '" + v.chip + "'");
                                        if (isGood && drops > 0) {
                                            check(v.line.contains("dropped"), "drops are told: " + id);
                                        }
                                        if (!btOn && allowed && cur != null && !cur.connected) {
                                            check(v.title.contains("Bluetooth is off"), "Bluetooth off is said: " + id);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        check(views > 500, "every state was looked at (" + views + ")");

        eq("Sound on Sony", HeadsetRules.route(Calls.ROUTE_HEADSET, "Sony"), "route headset");
        eq("Sound on the headset", HeadsetRules.route(Calls.ROUTE_HEADSET, ""), "route headset, no name");
        eq("Sound on the phone's speaker", HeadsetRules.route(Calls.ROUTE_SPEAKER, "Sony"), "route speaker");
        eq("", HeadsetRules.route(Calls.ROUTE_UNKNOWN, "Sony"), "unknown route says nothing");
        eq("", HeadsetRules.route(null, ""), "null route says nothing");
        eq("Headset", HeadsetRules.routeButton(Calls.ROUTE_HEADSET, true), "button");
        eq("Speaker", HeadsetRules.routeButton(Calls.ROUTE_SPEAKER, true), "button");
        eq("Earpiece", HeadsetRules.routeButton(Calls.ROUTE_PHONE, false), "button");
        eq("Audio", HeadsetRules.routeButton(Calls.ROUTE_UNKNOWN, false), "button");
        // the audio button goes round the places that exist
        for (boolean hs : new boolean[] {false, true}) {
            for (String start : new String[] {Calls.ROUTE_HEADSET, Calls.ROUTE_SPEAKER, Calls.ROUTE_PHONE, Calls.ROUTE_UNKNOWN}) {
                String r = start;
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (int i = 0; i < 6; i++) {
                    r = HeadsetRules.nextRoute(r, hs);
                    seen.add(r);
                    check(hs || !r.equals(Calls.ROUTE_HEADSET), "never sends the sound to a headset that is not connected");
                }
                eq(hs ? 3 : 2, seen.size(), "the audio button visits every place that exists (headset=" + hs + ")");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        dialTable();
        dialInvariants();
        prettyTable();
        keypad();
        search();
        log();
        onCall();
        tracker();
        notices();
        wire();
        headset();
        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
