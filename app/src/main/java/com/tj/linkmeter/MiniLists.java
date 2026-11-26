package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * No Android in here (tested in tools/test/FloatingLogicTest.java). What the floating phone's Recents and Contacts tabs list
 * (docs/FLOATING_PHONE.md): the same people and calls as the Calls tab, in short rows. The rules are the Calls tab's own: calls in a row
 * with the same person and kind are one line with a count, contacts are sorted by name with a letter above each group, one line per
 * person, and a typed number is searched by its digits.
 */
final class MiniLists {
    private MiniLists() {}

    /** The card is small: only this many lines of recent calls are drawn (the Calls tab has the rest). */
    static final int MAX_RECENTS = 40;
    /** And this many lines of contacts for one search. */
    static final int MAX_CONTACTS = 300;

    /** One line of the Recents tab: who, what kind of call and when, and whether it was missed. */
    static final class Recent {
        /** What the line shows as the person: the name, else the number, else "Unknown". */
        final String label;
        /** The number to call or text; "" when the caller hid it (nothing can be dialed from that line). */
        final String number;
        /** "Missed · 3:12 PM", "Outgoing · Yesterday"... */
        final String detail;
        final boolean missed;
        /** How many calls in a row the line stands for (1 for a single call). */
        final int count;

        Recent(String label, String number, String detail, boolean missed, int count) {
            this.label = label;
            this.number = number;
            this.detail = detail;
            this.missed = missed;
            this.count = count;
        }

        /** The title as drawn: "Mom (3)" for three calls in a row. */
        String title() {
            return count > 1 ? label + " (" + count + ")" : label;
        }
    }

    /** The newest calls as short lines. {@code log} is newest first, as the phone sends it. */
    static List<Recent> recents(List<Calls.Entry> log, long now, TextTime.Env env) {
        List<Recent> out = new ArrayList<>();
        if (log == null) {
            return out;
        }
        for (CallRules.Group g : CallRules.group(log)) {
            if (out.size() >= MAX_RECENTS) {
                break;
            }
            Calls.Entry e = g.first;
            String name = e.name == null ? "" : e.name.trim();
            String number = e.number == null ? "" : e.number;
            String label = !name.isEmpty() ? name : number.isEmpty() ? "Unknown" : CallRules.pretty(number);
            out.add(new Recent(label, number, CallRules.detail(e, now, env), CallRules.kind(e.type).equals(Calls.MISSED), g.count()));
        }
        return out;
    }

    /**
     * The Contacts tab's rows for what is typed in its search box. Strings are the letters above a group, {@link CallRules.Match} are
     * people. A search made of digits (and the signs of a number) looks at numbers; anything else looks at names. Empty = everybody.
     */
    static List<Object> contacts(List<Sms.Person> people, String query) {
        List<Object> rows = new ArrayList<>();
        if (people == null) {
            return rows;
        }
        String q = query == null ? "" : query.trim();
        boolean digitsOnly = !q.isEmpty() && q.matches("[0-9+*#() .-]+");
        List<CallRules.Match> list = new ArrayList<>();
        if (digitsOnly) {
            list.addAll(CallRules.search(people, q, MAX_CONTACTS));
        } else {
            Set<String> seen = new HashSet<>();
            String low = q.toLowerCase(Locale.US);
            for (Sms.Person p : people) {
                String key = ContactBook.matchKey(p.number);
                if (!seen.add(key.isEmpty() ? p.number : key)) {
                    continue; // one line per person, however many times the phone listed the number
                }
                String name = p.name == null ? "" : p.name.trim();
                if (q.isEmpty() || name.toLowerCase(Locale.US).contains(low)) {
                    list.add(new CallRules.Match(name, p.number, 0));
                }
            }
            Collections.sort(list, (a, b) -> a.name.compareToIgnoreCase(b.name));
        }
        String letter = "";
        int n = 0;
        for (CallRules.Match m : list) {
            if (n++ >= MAX_CONTACTS) {
                break;
            }
            if (!digitsOnly) {
                String l = CallRules.initial(m.name.isEmpty() ? CallRules.pretty(m.number) : m.name);
                if (!l.equals(letter)) {
                    letter = l;
                    rows.add(l);
                }
            }
            rows.add(m);
        }
        return rows;
    }

    /** How many people (not letters) the rows hold. */
    static int people(List<Object> rows) {
        int n = 0;
        for (Object o : rows) {
            if (o instanceof CallRules.Match) {
                n++;
            }
        }
        return n;
    }
}
