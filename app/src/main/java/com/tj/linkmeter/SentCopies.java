package com.tj.linkmeter;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * No Android in here (tested in tools/test/TextsLogicTest.java). A text this tablet sends is drawn at once, as "Sending…", and must give
 * way to the phone's own copy of it as soon as the phone has one: never earlier (the bubble would blink away) and never later (two
 * bubbles, one of them "Sending…" for ever). The phone's copy is found in two ways:
 * <ul>
 * <li>a message of mine with the same words that was NOT on screen when the text was sent (this also finds it when the phone's answer to
 * the send was lost on the way, and it tells two texts with the same words apart: each takes one new copy);</li>
 * <li>the phone answered that it has recorded the text, and the page in hand was asked for after that answer.</li>
 * </ul>
 */
final class SentCopies {
    private SentCopies() {}

    /**
     * @param temps         the texts drawn by this tablet, oldest first
     * @param shownWhenSent for each of them, the ids of the messages on screen when it was sent (none = every copy counts as new)
     * @param recordedAt    for each one the phone has recorded: the number of the first page asked for after the phone said so
     * @param page          the phone's answer
     * @param gen           the number of that answer
     * @return the ids of the temps that the page holds (they go; the page's copy is drawn instead)
     */
    static Set<String> replaced(List<Sms.Msg> temps, Map<String, Set<String>> shownWhenSent, Map<String, Long> recordedAt, List<Sms.Msg> page,
            long gen) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> claimed = new HashSet<>();
        for (Sms.Msg t : temps) {
            Set<String> before = shownWhenSent.get(t.id);
            Sms.Msg copy = null;
            for (Sms.Msg p : page) {
                if (p.mine && !claimed.contains(p.id) && (before == null || !before.contains(p.id)) && same(t, p)) {
                    copy = p;
                    break;
                }
            }
            Long at = recordedAt.get(t.id);
            if (copy != null) {
                claimed.add(copy.id);
                out.add(t.id);
            } else if (at != null && gen >= at) {
                out.add(t.id); // the phone has it, and this page was asked for after it said so: it is the phone's to show now
            }
        }
        return out;
    }

    /** Same words (spaces aside), and a message with files only matches a message with files. */
    static boolean same(Sms.Msg temp, Sms.Msg p) {
        if (!words(temp.body).equals(words(p.body))) {
            return false;
        }
        boolean files = !temp.parts.isEmpty();
        boolean pFiles = !p.parts.isEmpty();
        // a text to several people goes out as a picture message without files: words are enough for it
        return files ? (pFiles || p.mms) : !pFiles;
    }

    private static String words(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }
}
