package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;

/**
 * No Android in here (tested in tools/test/TextsLogicTest.java). A conversation as the screen draws it, from its messages (oldest
 * first): day headings, a "new messages" line where the unread ones start, messages in groups (several in a row from one person are
 * drawn as one block with the time under the last), reactions folded into the message they are about, and nothing for what was
 * deleted on this tablet.
 */
final class ThreadRows {
    private ThreadRows() {}

    static final int DAY = 0;
    static final int MSG = 1;
    static final int NEW_MESSAGES = 2;

    /** Messages by one person within this time are one group. */
    static final long GROUP_MS = 3 * 60 * 1000L;

    /** One reaction on a message. */
    static final class Applied {
        final String emoji;
        final boolean mine;
        /** Who reacted ("me", the sender's address in a group, "them" in a conversation with one person). */
        final String who;

        Applied(String emoji, boolean mine, String who) {
            this.emoji = emoji;
            this.mine = mine;
            this.who = who;
        }
    }

    static final class Row {
        final int kind;
        /** The day heading (DAY rows). */
        final String label;
        final Sms.Msg msg;
        boolean groupStart;
        boolean groupEnd;
        /** The newest message I sent: its state (sending, sent, delivered, failed) is written under it. */
        boolean lastOfMine;
        final List<Applied> reactions = new ArrayList<>();
        /** Who sent it, for the name over a group's message; the sender's address. */
        String sender = "";

        Row(int kind, String label, Sms.Msg msg) {
            this.kind = kind;
            this.label = label;
            this.msg = msg;
        }
    }

    /**
     * The small pill on a message's corner: each emoji once, in the order they came, with how many people put it when more than one
     * ("❤️ 2 👍"), as the messaging apps draw it.
     */
    static String pill(List<Applied> list) {
        java.util.LinkedHashMap<String, Integer> n = new java.util.LinkedHashMap<>();
        for (Applied a : list) {
            String e = SmsReactions.same(a.emoji);
            Integer c = n.get(e);
            n.put(e, c == null ? 1 : c + 1);
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : n.entrySet()) {
            sb.append(sb.length() > 0 ? "  " : "").append(e.getKey());
            if (e.getValue() > 1) {
                sb.append(' ').append(e.getValue());
            }
        }
        return sb.toString();
    }

    /** The emoji I put on this message, or null. */
    static String mine(List<Applied> list) {
        for (Applied a : list) {
            if (a.mine) {
                return a.emoji;
            }
        }
        return null;
    }

    private static boolean sameSender(Sms.Msg a, Sms.Msg b, boolean group) {
        return a.mine == b.mine && (a.mine || !group || a.address.equals(b.address));
    }

    static List<Row> build(List<Sms.Msg> msgs, TextsLocal local, String key, boolean group, long seenAt, long now, TextTime.Env env) {
        long clearedAt = local.clearedAt(key);
        List<Row> msgRows = new ArrayList<>();
        for (Sms.Msg m : msgs) {
            if (m.time <= clearedAt || local.isHidden(m.id)) {
                continue;
            }
            SmsReactions.Reaction r = m.parts.isEmpty() ? SmsReactions.parse(m.body) : null;
            if (r != null) {
                Row target = null;
                for (int i = msgRows.size() - 1; i >= 0; i--) {
                    if (SmsReactions.about(r, msgRows.get(i).msg)) {
                        target = msgRows.get(i);
                        break;
                    }
                }
                if (target != null) {
                    String who = m.mine ? "me" : (group ? m.address : "them");
                    int at = -1;
                    for (int i = 0; i < target.reactions.size(); i++) {
                        if (target.reactions.get(i).who.equals(who)) {
                            at = i;
                        }
                    }
                    if (r.remove) { // taking back the kind of reaction that is there
                        if (at >= 0 && target.reactions.get(at).emoji.equals(r.emoji)) {
                            target.reactions.remove(at);
                        }
                    } else { // one reaction per person: a new one replaces the old
                        if (at >= 0) {
                            target.reactions.remove(at);
                        }
                        target.reactions.add(new Applied(r.emoji, m.mine, who));
                    }
                    continue; // the reaction is drawn on its message, not as a message of its own
                }
            }
            Row row = new Row(MSG, null, m);
            row.sender = m.mine ? "" : m.address;
            msgRows.add(row);
        }

        List<Row> out = new ArrayList<>();
        long prevDay = Long.MIN_VALUE;
        boolean dividerDone = seenAt <= 0 || msgRows.isEmpty();
        Row lastMine = null;
        for (int i = 0; i < msgRows.size(); i++) {
            Row row = msgRows.get(i);
            Sms.Msg m = row.msg;
            long day = TextTime.dayNumber(m.time, env.zone);
            boolean newDay = day != prevDay;
            if (newDay) {
                out.add(new Row(DAY, TextTime.day(m.time, now, env), null));
                prevDay = day;
            }
            if (!dividerDone && !m.mine && m.time > seenAt && i > 0) {
                for (int j = out.size() - 1; j >= 0; j--) { // the line splits a group: the message above it ends one
                    if (out.get(j).kind == MSG) {
                        out.get(j).groupEnd = true;
                        break;
                    }
                }
                out.add(new Row(NEW_MESSAGES, "New messages", null));
                dividerDone = true;
            } else if (!dividerDone && !m.mine && m.time > seenAt) {
                dividerDone = true; // everything is new: no line above the first message
            }
            Sms.Msg prev = i > 0 ? msgRows.get(i - 1).msg : null;
            Sms.Msg next = i + 1 < msgRows.size() ? msgRows.get(i + 1).msg : null;
            boolean afterLine = !out.isEmpty() && out.get(out.size() - 1).kind == NEW_MESSAGES;
            row.groupStart = prev == null || newDay || afterLine || !sameSender(prev, m, group) || m.time - prev.time > GROUP_MS;
            row.groupEnd = next == null || TextTime.dayNumber(next.time, env.zone) != day || !sameSender(next, m, group)
                    || next.time - m.time > GROUP_MS;
            if (m.mine) {
                lastMine = row;
            }
            out.add(row);
        }
        if (lastMine != null) {
            lastMine.lastOfMine = true;
        }
        return out;
    }
}
