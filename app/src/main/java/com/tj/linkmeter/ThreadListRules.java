package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * No Android in here (tested in tools/test/TextsLogicTest.java). Which conversations the Texts list shows and in which order: the
 * phone's conversations, with the tablet's own choices ({@link TextsLocal}) applied: deleted ones are gone, archived ones are in
 * their own list, pinned ones come first, unread ones are marked, a search or "unread only" narrows the list.
 */
final class ThreadListRules {
    private ThreadListRules() {}

    /** One line of the list. */
    static final class Item {
        final Sms.Thread t;
        /** The phone says it is unread and it has not been opened on this tablet since. */
        final boolean unread;
        /** How many messages came since it was last opened here (0 when that is not known: show a dot, not a number). */
        final int newCount;
        final boolean pinned;
        final boolean muted;

        Item(Sms.Thread t, boolean unread, int newCount, boolean pinned, boolean muted) {
            this.t = t;
            this.unread = unread;
            this.newCount = newCount;
            this.pinned = pinned;
            this.muted = muted;
        }
    }

    /**
     * @param query       what was typed in the search box ("" = no search)
     * @param unreadOnly  only the unread ones
     * @param archived    the archived list instead of the main list
     */
    static List<Item> build(List<Sms.Thread> threads, TextsLocal local, String query, boolean unreadOnly, boolean archived) {
        List<Item> out = new ArrayList<>();
        for (Sms.Thread t : threads) {
            if (local.isCleared(t.key, t.time)) {
                continue;
            }
            boolean isArchived = local.isArchived(t.key, t.time);
            if (isArchived != archived) {
                continue;
            }
            if (!matches(t, query)) {
                continue;
            }
            boolean unread = t.unread && t.time > local.seenAt(t.key);
            if (unreadOnly && !unread) {
                continue;
            }
            int seen = local.seenCount(t.key);
            int fresh = unread && seen > 0 ? Math.max(1, Math.min(99, t.count - seen)) : 0;
            out.add(new Item(t, unread, fresh, local.isPinned(t.key), local.isMuted(t.key)));
        }
        Collections.sort(out, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                if (a.pinned != b.pinned) {
                    return a.pinned ? -1 : 1;
                }
                int c = Long.compare(b.t.time, a.t.time);
                return c != 0 ? c : a.t.key.compareTo(b.t.key);
            }
        });
        return out;
    }

    /** How many conversations are unread (the number on the tab); muted ones do not count. */
    static int unreadCount(List<Item> items) {
        int n = 0;
        for (Item i : items) {
            if (i.unread && !i.muted) {
                n++;
            }
        }
        return n;
    }

    /** How many conversations are in the archived list. */
    static int archivedCount(List<Sms.Thread> threads, TextsLocal local) {
        int n = 0;
        for (Sms.Thread t : threads) {
            if (!local.isCleared(t.key, t.time) && local.isArchived(t.key, t.time)) {
                n++;
            }
        }
        return n;
    }

    private static String digits(String s) {
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                d.append(s.charAt(i));
            }
        }
        return d.toString();
    }

    /** Does the conversation answer the search? Names and texts by letters (any case), numbers by their digits (three or more). */
    static boolean matches(Sms.Thread t, String query) {
        if (query == null) {
            return true;
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return true;
        }
        if (t.name.toLowerCase(Locale.ROOT).contains(q) || t.snippet.toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        String qd = digits(q);
        if (qd.length() >= 3) {
            for (String a : t.addresses) {
                if (digits(a).contains(qd)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The grey line under the name: the last text on one line, or what is in it that is not text; nothing readable when previews are
     * hidden. "No messages" is only for a conversation that really has none: when the phone says there are messages but gives no words
     * (an older phone app, or a message with nothing to read in it) the line says so and does not say there is nothing.
     */
    static String preview(Sms.Thread t, boolean hide) {
        if (hide) {
            return t.unread ? "New message" : "Message";
        }
        String s = SmsPreview.line(t.snippet, t.attachment);
        if (!s.isEmpty()) {
            return s;
        }
        return t.unread ? "New message" : t.count > 0 ? "Message" : "No messages";
    }

    /** The name to show: the contact's name, or the number written nicely. */
    static String title(Sms.Thread t) {
        if (!t.name.isEmpty()) {
            return t.name;
        }
        StringBuilder sb = new StringBuilder();
        for (String a : t.addresses) {
            sb.append(sb.length() > 0 ? ", " : "").append(PhoneNumbers.pretty(a));
        }
        return sb.length() == 0 ? "Unknown" : sb.toString();
    }
}
