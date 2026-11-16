package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Every decision of the Calls feature that does not need Android (tested offline in tools/test/CallsLogicTest.java; see
 * docs/PHONE_CALLS.md): what may be dialed, how a number is shown while it is typed, the letters under the keys, which contacts a
 * typed number matches, how the call log is grouped, how long a call has lasted, which call the banner shows.
 */
final class CallRules {
    private CallRules() {}

    // ------------------------------------------------------------------ what may be dialed

    /** The answer of {@link #check}. */
    static final class Dial {
        /** Why it may not be dialed: one of the constants below, or "" when it may. */
        final String problem;
        /** The number to dial (digits, with a leading + or *), when it may. */
        final String number;

        Dial(String problem, String number) {
            this.problem = problem;
            this.number = number;
        }

        boolean ok() {
            return problem.isEmpty();
        }

        /** A sentence for the person; "" when the number may be dialed. */
        String why() {
            switch (problem) {
                case EMPTY:
                    return "Type a number first.";
                case SHORT:
                    return "That number is too short.";
                case LONG:
                    return "That number is too long.";
                case CHARS:
                    return "Only numbers can be dialed here.";
                case CODE:
                    return "Codes with # or more than one * cannot be dialed from the tablet. Use the phone's own keypad for those.";
                case EMERGENCY:
                    return "Emergency numbers cannot be dialed from the tablet. Use the phone itself.";
                default:
                    return "";
            }
        }
    }

    static final String EMPTY = "empty";
    static final String SHORT = "short";
    static final String LONG = "long";
    static final String CHARS = "chars";
    static final String CODE = "code";
    static final String EMERGENCY = "emergency";

    /** The most digits one number may have (an international number has at most 15; a little room for a prefix). */
    static final int MAX_DIGITS = 20;

    /**
     * Emergency numbers of many countries. A request for one is refused: Android hands an app's request for an emergency number to the
     * phone's own dialer anyway, and a remote control must not look as if it were one's way to an emergency service.
     */
    private static final Set<String> EMERGENCY_NUMBERS = new HashSet<>(Arrays.asList(
            "911", "112", "999", "000", "111", "110", "118", "119", "100", "101", "102", "103", "190", "192", "193", "120", "122", "08",
            "060", "065", "066"));

    /** What a person may type into a number: spaces, dashes, dots and brackets are ignored. */
    private static boolean ignorable(char c) {
        return c == ' ' || c == '-' || c == '.' || c == '(' || c == ')' || c == ' ' || c == '‑' || c == '–';
    }

    private static String strip(String s, int n) {
        return s.length() >= n ? s.substring(n) : "";
    }

    /** Is this (digits only) an emergency number, also with a service prefix such as *67 or a leading 1 in front of it? */
    static boolean emergencyDigits(String digits, boolean star) {
        String core = star ? strip(digits, 2) : digits;
        String[] candidates = {digits, core, strip(digits, digits.startsWith("1") ? 1 : digits.length() + 1),
                strip(core, core.startsWith("1") ? 1 : core.length() + 1)};
        for (String c : candidates) {
            if (EMERGENCY_NUMBERS.contains(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * May this be dialed from the tablet? Numbers (also with a leading + or a leading *, as in *67) may. Not: nothing at all, one
     * digit, too many digits, letters, pauses, any # and any * after the first character (service and USSD codes: decision of
     * 2026-11-10, see docs/SAFETY.md), emergency numbers.
     */
    static Dial check(String raw) {
        if (raw == null) {
            return new Dial(EMPTY, "");
        }
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!ignorable(c)) {
                clean.append(c);
            }
        }
        String s = clean.toString();
        if (s.isEmpty()) {
            return new Dial(EMPTY, "");
        }
        if (s.indexOf('#') >= 0) {
            return new Dial(CODE, "");
        }
        int stars = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '*') {
                stars++;
                if (i != 0) {
                    return new Dial(CODE, "");
                }
            }
        }
        boolean star = stars > 0;
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else if (c == '+' && i == 0) {
                continue;
            } else if (c == '*' && i == 0) {
                continue;
            } else {
                return new Dial(CHARS, "");
            }
        }
        if (digits.length() < 2) {
            return new Dial(digits.length() == 0 ? EMPTY : SHORT, "");
        }
        if (digits.length() > MAX_DIGITS) {
            return new Dial(LONG, "");
        }
        if (emergencyDigits(digits.toString(), star)) {
            return new Dial(EMERGENCY, "");
        }
        return new Dial("", s);
    }

    // ------------------------------------------------------------------ how a number is shown

    static String digitsOf(String s) {
        StringBuilder d = new StringBuilder();
        if (s != null) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    d.append(c);
                }
            }
        }
        return d.toString();
    }

    /** Ten digits or fewer in the North American way: 555, 555-1, 555-1234, (555) 123, (555) 123-4567. */
    private static String national(String d) {
        int n = d.length();
        if (n <= 3) {
            return d;
        }
        if (n <= 7) {
            return d.substring(0, 3) + "-" + d.substring(3);
        }
        if (n <= 10) { // 8 to 10 digits: (555) 123-45, (555) 123-4567
            return "(" + d.substring(0, 3) + ") " + d.substring(3, 6) + "-" + d.substring(6);
        }
        return d;
    }

    /**
     * A number as it is shown while it is typed and in the lists: (555) 123-4567, +1 (555) 123-4567, 555-1234. A number that is not
     * North American keeps its digits (with its + or *) because its grouping cannot be known.
     */
    static String pretty(String number) {
        if (number == null) {
            return "";
        }
        String n = number.trim();
        if (n.isEmpty()) {
            return "";
        }
        boolean plus = n.startsWith("+");
        boolean star = n.startsWith("*");
        String d = digitsOf(n);
        if (n.indexOf('#') >= 0 || n.indexOf(',') >= 0 || n.indexOf(';') >= 0) {
            return n; // a code or something with pauses is shown as it is
        }
        for (int i = 0; i < n.length(); i++) {
            if (Character.isLetter(n.charAt(i))) {
                return n; // a sender name
            }
        }
        if (d.isEmpty()) {
            return n;
        }
        if (star) {
            return "*" + (d.length() <= 3 ? d : national(d));
        }
        if (plus) {
            if (d.startsWith("1") && d.length() >= 2 && d.length() <= 11) {
                return "+1 " + national(d.substring(1));
            }
            return "+" + d;
        }
        if (d.length() == 11 && d.startsWith("1")) {
            return "1 " + national(d.substring(1));
        }
        return national(d);
    }

    // ------------------------------------------------------------------ the keypad

    /** The keys, row by row. */
    static final char[][] KEYS = {{'1', '2', '3'}, {'4', '5', '6'}, {'7', '8', '9'}, {'*', '0', '#'}};

    /** The letters printed under a key ("ABC" under 2); "+" under 0, nothing under the others. */
    static String letters(char key) {
        switch (key) {
            case '2':
                return "ABC";
            case '3':
                return "DEF";
            case '4':
                return "GHI";
            case '5':
                return "JKL";
            case '6':
                return "MNO";
            case '7':
                return "PQRS";
            case '8':
                return "TUV";
            case '9':
                return "WXYZ";
            case '0':
                return "+";
            default:
                return "";
        }
    }

    /** The digit that has this letter on its key, or 0 when the character is not a letter. */
    static char keyOf(char letter) {
        char c = Character.toUpperCase(letter);
        if (c < 'A' || c > 'Z') {
            return 0;
        }
        if (c <= 'C') {
            return '2';
        }
        if (c <= 'F') {
            return '3';
        }
        if (c <= 'I') {
            return '4';
        }
        if (c <= 'L') {
            return '5';
        }
        if (c <= 'O') {
            return '6';
        }
        if (c <= 'S') {
            return '7';
        }
        if (c <= 'V') {
            return '8';
        }
        return '9';
    }

    /** The digits that spell a word on the keypad ("Mom" -> "666"); letters it does not know (accents) are left out. */
    static String spell(String word) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c >= '0' && c <= '9') {
                b.append(c);
            } else {
                char k = keyOf(c);
                if (k != 0) {
                    b.append(k);
                }
            }
        }
        return b.toString();
    }

    /** The key to add to the typed number after a long press on 0: a plus, but only as the first character. */
    static String afterLongZero(String typed) {
        return typed.isEmpty() ? "+" : typed + "0";
    }

    /** What the typed number holds after one more key; the number never gets longer than {@link #MAX_DIGITS} digits plus a prefix. */
    static String press(String typed, char key) {
        if (typed.length() >= MAX_DIGITS + 2) {
            return typed;
        }
        return typed + key;
    }

    static String backspace(String typed) {
        return typed.isEmpty() ? "" : typed.substring(0, typed.length() - 1);
    }

    // ------------------------------------------------------------------ the contacts a typed number matches

    /** A contact shown with the number it matched on. */
    static final class Match {
        final String name;
        final String number;
        final int rank;

        Match(String name, String number, int rank) {
            this.name = name;
            this.number = number;
            this.rank = rank;
        }
    }

    private static String[] words(String name) {
        return name.trim().split("[\\s,.\\-]+");
    }

    /**
     * The contacts a typed number (digits) matches, best first: a saved number that starts with it, a name with a word that starts with
     * it on the keypad (typing 666 finds Mom), a name that starts with it, a saved number that holds it (three digits or more), a name
     * that holds it (three digits or more). Each number once. Nothing for fewer than one digit.
     */
    static List<Match> search(List<Sms.Person> people, String typed, int limit) {
        String t = digitsOf(typed);
        List<Match> out = new ArrayList<>();
        if (t.isEmpty() || people == null || limit <= 0) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (Sms.Person p : people) {
            String key = ContactBook.matchKey(p.number);
            if (!seen.add(key.isEmpty() ? p.number : key)) {
                continue;
            }
            String nd = digitsOf(p.number);
            String national = nd.length() > 10 ? nd.substring(nd.length() - 10) : nd;
            String all = spell(p.name);
            int rank = -1;
            if (nd.startsWith(t) || national.startsWith(t)) {
                rank = 0;
            } else {
                for (String w : words(p.name)) {
                    if (!w.isEmpty() && spell(w).startsWith(t)) {
                        rank = 1;
                        break;
                    }
                }
                if (rank < 0 && all.startsWith(t)) {
                    rank = 2;
                }
                if (rank < 0 && t.length() >= 3 && nd.contains(t)) {
                    rank = 3;
                }
                if (rank < 0 && t.length() >= 3 && all.contains(t)) {
                    rank = 4;
                }
            }
            if (rank >= 0) {
                out.add(new Match(p.name, p.number, rank));
            }
        }
        Collections.sort(out, (a, b) -> a.rank != b.rank ? Integer.compare(a.rank, b.rank) : a.name.compareToIgnoreCase(b.name));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** The letter in the avatar circle: the first letter of the name, or # when there is none (a number). */
    static String initial(String name) {
        String n = name == null ? "" : name.trim();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (Character.isLetter(c)) {
                return String.valueOf(c).toUpperCase(Locale.US);
            }
        }
        return "#";
    }

    // ------------------------------------------------------------------ the call log

    /** Calls in a row with the same person and the same kind of call, shown as one line with a count ("Mom (3)"). */
    static final class Group {
        final Calls.Entry first;
        final List<Calls.Entry> all = new ArrayList<>();

        Group(Calls.Entry first) {
            this.first = first;
            all.add(first);
        }

        int count() {
            return all.size();
        }
    }

    /** "missed", "in" or "out": the three kinds the grouping tells apart (a rejected call counts as missed, a voicemail as in). */
    static String kind(String type) {
        if (Calls.MISSED.equals(type) || Calls.REJECTED.equals(type) || Calls.BLOCKED.equals(type)) {
            return Calls.MISSED;
        }
        return Calls.OUT.equals(type) ? Calls.OUT : Calls.IN;
    }

    private static String who(Calls.Entry e) {
        String k = ContactBook.matchKey(e.number);
        return k.isEmpty() ? "?" + e.name : k;
    }

    /** The log newest first, with neighbours of the same person and kind joined. */
    static List<Group> group(List<Calls.Entry> log) {
        List<Group> out = new ArrayList<>();
        for (Calls.Entry e : log) {
            Group last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && who(last.first).equals(who(e)) && kind(last.first.type).equals(kind(e.type))) {
                last.all.add(e);
            } else {
                out.add(new Group(e));
            }
        }
        return out;
    }

    /** The word for a log line: "Missed", "Incoming", "Outgoing", ... */
    static String typeWord(String type) {
        switch (type) {
            case Calls.MISSED:
                return "Missed";
            case Calls.REJECTED:
                return "Declined";
            case Calls.VOICEMAIL:
                return "Voicemail";
            case Calls.BLOCKED:
                return "Blocked";
            case Calls.OUT:
                return "Outgoing";
            default:
                return "Incoming";
        }
    }

    /** The second line of a recents row: "Missed · 3:45 PM", "Outgoing · Yesterday · 2:07". */
    static String detail(Calls.Entry e, long now, TextTime.Env env) {
        StringBuilder sb = new StringBuilder(typeWord(e.type));
        String when = TextTime.stamp(e.time, now, env);
        if (!when.isEmpty()) {
            sb.append(" · ").append(when);
        }
        if (e.seconds > 0 && (Calls.IN.equals(e.type) || Calls.OUT.equals(e.type))) {
            sb.append(" · ").append(timer(e.seconds * 1000L));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ a call that is on

    /** The length of a call as the timer shows it: 0:07, 12:03, 1:02:03. */
    static String timer(long ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
        }
        return String.format(Locale.US, "%d:%02d", m, sec);
    }

    /** The call the screen and the banner are about: a ringing one first, then one that is on, then one on hold; null when idle. */
    static Calls.Call lead(Calls.Now now) {
        if (now == null) {
            return null;
        }
        for (String state : new String[] {Calls.RINGING, Calls.ACTIVE, Calls.DIALING, Calls.HELD}) {
            for (Calls.Call c : now.calls) {
                if (state.equals(c.state)) {
                    return c;
                }
            }
        }
        return null;
    }

    /** How long an outgoing call is called "Calling" before the screen says "On a call" (Android does not say when the other side answers). */
    static final long CALLING_MS = 5000;

    /**
     * What the line under the name of a call says. {@code elapsedMs} is how long the call has been going on (from the phone's clock).
     * A ringing call says so; an outgoing call says "Calling..." at first and then shows the timer; an answered call shows the timer.
     */
    static String status(Calls.Call c, long elapsedMs) {
        if (c == null) {
            return "";
        }
        switch (c.state) {
            case Calls.RINGING:
                return "Incoming call";
            case Calls.HELD:
                return "On hold · " + timer(elapsedMs);
            default:
                if (!c.incoming && c.answered == 0 && elapsedMs < CALLING_MS) {
                    return "Calling…";
                }
                return timer(elapsedMs);
        }
    }

    /** The milliseconds a call has gone on, on the phone's clock {@code at}; never below zero. */
    static long elapsed(Calls.Call c, long at) {
        long from = c.answered > 0 ? c.answered : c.since;
        return from <= 0 ? 0 : Math.max(0, at - from);
    }

    /** What to call the other person: the name when there is one, else the number as it is shown, else "Unknown". */
    static String shown(String name, String number) {
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        String p = pretty(number);
        return p.isEmpty() ? "Unknown" : p;
    }

    /** What the Calls button's number is: missed calls nobody has looked at, never negative, "9+" is the tablet's business. */
    static int badge(Calls.Now now) {
        return now == null ? 0 : Math.max(0, now.missed);
    }
}
