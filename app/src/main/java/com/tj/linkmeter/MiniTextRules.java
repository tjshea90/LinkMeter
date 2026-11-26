package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * No Android in here (tested in tools/test/FloatingLogicTest.java). The decisions of the floating phone's small texting card
 * (docs/FLOATING_PHONE.md): who a typed recipient is, which contacts to suggest, when Send works, how the bubbles that are on their way
 * join the messages the phone has, and the words of the lists. The texts themselves are read and sent by the phone through the same
 * routes as the Texts tab ({@link RemoteSms}); nothing here reaches the phone.
 */
final class MiniTextRules {
    private MiniTextRules() {}

    /** How many messages of a conversation the card keeps (the Texts tab has the rest). */
    static final int SHOW = 30;
    /** How many conversations the list asks for. */
    static final int THREADS = 30;
    /** A copy of my message that the phone has recorded this long before the tablet drew its bubble still counts as that message. */
    static final long COPY_SLACK_MS = 120_000;

    /** A person the recipient box can offer. */
    static final class Pick {
        /** "" when only a number is known (a typed number). */
        final String name;
        /** The number as it is handed to the network ({@link PhoneNumbers#forSending}), or as the phone listed it for a contact. */
        final String number;

        Pick(String name, String number) {
            this.name = name == null ? "" : name;
            this.number = number == null ? "" : number;
        }

        String shown() {
            return name.isEmpty() ? PhoneNumbers.pretty(number) : name;
        }
    }

    /** The number a typed recipient stands for, or "" when it is not a usable number (a name has to be chosen from the contacts). */
    static String recipient(String typed) {
        return PhoneNumbers.forSending(typed == null ? "" : typed);
    }

    /**
     * What the recipient box offers for what is typed: the typed number itself first (when it is one), then the contacts whose name contains
     * it or whose number matches its digits, one line per person, at most {@code limit}. Nothing for an empty box.
     */
    static List<Pick> suggestions(List<Sms.Person> people, String typed, int limit) {
        List<Pick> out = new ArrayList<>();
        String q = typed == null ? "" : typed.trim();
        if (q.isEmpty() || limit <= 0) {
            return out;
        }
        String own = recipient(q);
        Set<String> seen = new HashSet<>();
        String ownKey = own.isEmpty() ? "" : ContactBook.matchKey(own);
        if (!own.isEmpty()) {
            out.add(new Pick("", own));
            seen.add(ownKey);
        }
        if (people == null) {
            return out;
        }
        boolean digitsOnly = q.matches("[0-9+*#() .-]+");
        if (digitsOnly) {
            for (CallRules.Match m : CallRules.search(people, q, limit + 1)) {
                if (out.size() >= limit) {
                    break;
                }
                String key = ContactBook.matchKey(m.number);
                String send = PhoneNumbers.forSending(m.number).isEmpty() ? m.number : PhoneNumbers.forSending(m.number);
                if (!own.isEmpty() && key.equals(ownKey)) {
                    out.set(0, new Pick(m.name, send)); // the typed number is a contact: show the contact, not the bare number
                } else if (seen.add(key.isEmpty() ? m.number : key)) {
                    out.add(new Pick(m.name, send));
                }
            }
            return out;
        }
        String low = q.toLowerCase(Locale.US);
        for (Sms.Person p : people) {
            if (out.size() >= limit) {
                break;
            }
            String name = p.name == null ? "" : p.name.trim();
            if (!name.toLowerCase(Locale.US).contains(low)) {
                continue;
            }
            String send = PhoneNumbers.forSending(p.number);
            if (send.isEmpty()) {
                continue; // a contact whose number cannot be texted
            }
            String key = ContactBook.matchKey(send);
            if (seen.add(key.isEmpty() ? send : key)) {
                out.add(new Pick(name, send));
            }
        }
        return out;
    }

    /** Send works when there is something to send and somebody to send it to. */
    static boolean canSend(String text, List<String> to) {
        return text != null && !text.trim().isEmpty() && to != null && !to.isEmpty();
    }

    /**
     * The messages the card draws: what the phone sent (oldest first), then my own bubbles that are still on their way, unless the phone
     * already has a copy of one (the same words, from me, not much older than the bubble). A copy takes the place of ONE bubble, so two
     * equal texts sent one after the other stay two bubbles until the phone has both. At most {@link #SHOW}, the newest.
     */
    static List<Sms.Msg> merge(List<Sms.Msg> loaded, List<Sms.Msg> temps) {
        List<Sms.Msg> out = new ArrayList<>();
        if (loaded != null) {
            out.addAll(loaded);
        }
        if (temps != null) {
            boolean[] used = new boolean[out.size()];
            for (Sms.Msg t : temps) {
                boolean copied = false;
                if (t.state != Sms.STATE_FAILED) {
                    for (int i = 0; i < used.length && !copied; i++) {
                        Sms.Msg m = out.get(i);
                        if (!used[i] && m.mine && m.body.trim().equals(t.body.trim()) && m.time >= t.time - COPY_SLACK_MS) {
                            used[i] = true;
                            copied = true;
                        }
                    }
                }
                if (!copied) {
                    out.add(t);
                }
            }
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(a.time, b.time));
        return out.size() > SHOW ? new ArrayList<>(out.subList(out.size() - SHOW, out.size())) : out;
    }

    /** The name of a conversation in the list: the contact's name, else the number. */
    static String title(Sms.Thread t) {
        String n = t.name == null ? "" : t.name.trim();
        if (!n.isEmpty()) {
            return PhoneNumbers.looksLikeNumber(n) ? PhoneNumbers.pretty(n) : n;
        }
        return t.addresses.isEmpty() ? "Unknown" : PhoneNumbers.pretty(t.addresses.get(0));
    }

    /** The line under the name: the newest words, or what kind of message it was. */
    static String preview(Sms.Thread t) {
        String s = t.snippet == null ? "" : t.snippet.replace('\n', ' ').trim();
        if (!s.isEmpty()) {
            return s;
        }
        return t.attachment ? "Picture or file" : "No messages";
    }

    /** What to say under my newest message, "" when there is nothing to say (it was sent, or it is not mine). */
    static String sendLine(Sms.Msg last) {
        if (last == null || !last.mine) {
            return "";
        }
        if (last.state == Sms.STATE_SENDING) {
            return "Sending…";
        }
        if (last.state == Sms.STATE_FAILED) {
            String why = last.error == null ? "" : last.error.trim();
            return why.isEmpty() ? "Not sent" : "Not sent: " + why;
        }
        return "";
    }

    /** A card title for a conversation: the name, or "New message" while nobody is chosen. */
    static String threadTitle(String name, List<String> to) {
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        if (to != null && to.size() == 1) {
            return PhoneNumbers.pretty(to.get(0));
        }
        if (to != null && to.size() > 1) {
            return to.size() + " people";
        }
        return "New message";
    }
}
