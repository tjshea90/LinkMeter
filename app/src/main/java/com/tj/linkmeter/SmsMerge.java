package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts what the phone's database holds together with the tablet's own log of what it sent. An app that is not the phone's default
 * messaging app cannot write the messages it sends into that database, so the sent messages exist only in the log (until, on some
 * phones, the database has them too: then the log's copy is dropped). No Android in it (tested offline).
 */
final class SmsMerge {
    private SmsMerge() {}

    /** How close in time two copies of the same sent message must be to count as one. */
    static final long SAME_MS = 120_000;

    /** One conversation, oldest first: the database's messages plus the log's messages that the database does not have. */
    static List<Sms.Msg> thread(List<Sms.Msg> provider, List<Sms.Msg> log) {
        List<Sms.Msg> out = new ArrayList<>(provider);
        for (Sms.Msg o : log) {
            if (!duplicate(o, provider)) {
                out.add(o);
            }
        }
        Collections.sort(out, new Comparator<Sms.Msg>() {
            @Override
            public int compare(Sms.Msg a, Sms.Msg b) {
                int c = Long.compare(a.time, b.time);
                return c != 0 ? c : a.id.compareTo(b.id);
            }
        });
        return out;
    }

    /** Does the database already hold this message of the log? */
    static boolean duplicate(Sms.Msg logged, List<Sms.Msg> provider) {
        for (Sms.Msg p : provider) {
            if (!p.mine || !p.key.equals(logged.key) || Math.abs(p.time - logged.time) > SAME_MS) {
                continue;
            }
            if (p.mms == logged.mms && (logged.mms || p.body.trim().equals(logged.body.trim()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The conversation list, newest first: the database's threads, with a newer message of the log taking over the snippet and the
     * time, plus a thread for every person that only the log has.
     */
    static List<Sms.Thread> threads(List<Sms.Thread> provider, List<Sms.Msg> log) {
        Map<String, Sms.Thread> byKey = new HashMap<>();
        for (Sms.Thread t : provider) {
            byKey.put(t.key, t.copy()); // the caller's threads are left as they were
        }
        // the newest log message of each conversation
        Map<String, Sms.Msg> newest = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (Sms.Msg m : log) {
            Sms.Msg cur = newest.get(m.key);
            if (cur == null || m.time > cur.time) {
                newest.put(m.key, m);
            }
            Integer c = counts.get(m.key);
            counts.put(m.key, c == null ? 1 : c + 1);
        }
        for (Map.Entry<String, Sms.Msg> e : newest.entrySet()) {
            Sms.Msg m = e.getValue();
            Sms.Thread t = byKey.get(e.getKey());
            if (t == null) {
                t = new Sms.Thread();
                t.key = m.key;
                StringBuilder name = new StringBuilder();
                for (String a : m.address.split(",")) { // a group's message lists everybody, comma separated
                    if (!a.trim().isEmpty()) {
                        t.addresses.add(a.trim());
                        name.append(name.length() > 0 ? ", " : "").append(PhoneNumbers.pretty(a));
                    }
                }
                t.name = name.toString();
                t.count = counts.get(e.getKey());
                byKey.put(t.key, t);
            }
            if (m.time >= t.time) {
                t.time = m.time;
                t.snippet = m.body.isEmpty() && !m.parts.isEmpty() ? "Attachment" : m.body;
                t.attachment |= !m.parts.isEmpty();
            }
        }
        List<Sms.Thread> out = new ArrayList<>(byKey.values());
        Collections.sort(out, new Comparator<Sms.Thread>() {
            @Override
            public int compare(Sms.Thread a, Sms.Thread b) {
                int c = Long.compare(b.time, a.time);
                return c != 0 ? c : a.key.compareTo(b.key);
            }
        });
        return out;
    }
}
