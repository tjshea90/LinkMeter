package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * No Android in here (tested in tools/test/TextsLogicTest.java). Puts a page of messages that the phone just sent together with the
 * messages the screen already has, so that a conversation is fetched a page at a time and a change on the phone (a new text, a text
 * deleted there) shows without fetching it all again.
 */
final class ThreadMerge {
    private ThreadMerge() {}

    private static final Comparator<Sms.Msg> BY_TIME = new Comparator<Sms.Msg>() {
        @Override
        public int compare(Sms.Msg a, Sms.Msg b) {
            int c = Long.compare(a.time, b.time);
            return c != 0 ? c : a.id.compareTo(b.id);
        }
    };

    /**
     * @param have    the messages on screen now, oldest first
     * @param page    the phone's answer, oldest first
     * @param newest  the page is the newest messages of the conversation (the phone was not asked for older ones): what it does not
     *                hold from its first message on is gone from the phone, and goes from the screen too
     */
    static List<Sms.Msg> merge(List<Sms.Msg> have, List<Sms.Msg> page, boolean newest) {
        Map<String, Sms.Msg> out = new LinkedHashMap<>();
        if (newest) {
            if (page.isEmpty()) {
                return new ArrayList<>();
            }
            long from = Long.MAX_VALUE;
            for (Sms.Msg m : page) {
                from = Math.min(from, m.time);
            }
            for (Sms.Msg m : have) {
                if (m.time < from) {
                    out.put(m.id, m);
                }
            }
        } else {
            for (Sms.Msg m : have) {
                out.put(m.id, m);
            }
        }
        for (Sms.Msg m : page) {
            out.put(m.id, m);
        }
        List<Sms.Msg> l = new ArrayList<>(out.values());
        Collections.sort(l, BY_TIME);
        return l;
    }
}
