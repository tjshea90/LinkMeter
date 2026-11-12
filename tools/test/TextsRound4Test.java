package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Tests the pure parts of the Texts changes of 2026-11-12 (the user's report: the keyboard hides the newest messages, a conversation
 * opens scrolled up, "Sending…" for a long time, a number on the Texts tab, the list's newest line, emoji reactions on the bubble):
 * {@link SentCopies} (when a just-sent bubble gives way to the phone's copy), {@link ChatFollow} (the conversation that keeps its
 * newest message in sight). Not part of the app.
 */
public final class TextsRound4Test {
    static int checks;
    static int fails;

    static void eq(Object want, Object got, String what) {
        checks++;
        if (!String.valueOf(want).equals(String.valueOf(got))) {
            fails++;
            if (fails <= 40) {
                System.out.println("FAIL " + what + ": wanted '" + want + "' but got '" + got + "'");
            }
        }
    }

    static void yes(boolean ok, String what) {
        checks++;
        if (!ok) {
            fails++;
            if (fails <= 40) {
                System.out.println("FAIL " + what);
            }
        }
    }

    static Sms.Msg msg(String id, boolean mine, String body, long time) {
        Sms.Msg m = new Sms.Msg();
        m.id = id;
        m.key = "k";
        m.address = mine ? "" : "+15550001";
        m.mine = mine;
        m.body = body;
        m.time = time;
        return m;
    }

    static Sms.Msg withFile(Sms.Msg m) {
        Sms.Part p = new Sms.Part();
        p.id = m.id + ".0";
        p.type = "image/jpeg";
        p.name = "a.jpg";
        m.parts.add(p);
        m.mms = true;
        return m;
    }

    static Set<String> ids(String... s) {
        return new HashSet<>(Arrays.asList(s));
    }

    // ------------------------------------------------------------------ SentCopies

    static void sentCopies() {
        Sms.Msg t1 = msg("t1", true, "On my way", 1000);
        t1.state = Sms.STATE_SENDING;
        Map<String, Set<String>> shown = new HashMap<>();
        Map<String, Long> recorded = new HashMap<>();
        List<Sms.Msg> temps = new ArrayList<>(Collections.singletonList(t1));
        shown.put("t1", ids("s1", "s2"));
        List<Sms.Msg> old = Arrays.asList(msg("s1", false, "Hi", 10), msg("s2", true, "On my way", 20)); // the same words, sent before

        // the phone's page does not have it yet: the bubble stays (an older text with the same words is not its copy)
        eq("[]", SentCopies.replaced(temps, shown, recorded, old, 1), "an older text with the same words is not the copy");
        // the phone's outbox copy arrives in a page (also before the answer to the send came: no second bubble)
        List<Sms.Msg> page = new ArrayList<>(old);
        page.add(msg("o7", true, "On my way", 1001));
        eq("[t1]", SentCopies.replaced(temps, shown, recorded, page, 2), "the phone's new copy replaces the bubble");
        // spaces at the ends and inside do not matter
        List<Sms.Msg> spaced = new ArrayList<>(old);
        spaced.add(msg("s9", true, "  On  my\nway ", 1001));
        eq("[t1]", SentCopies.replaced(temps, shown, recorded, spaced, 2), "the same words with other spaces");
        // a message from the other person with the same words is not mine
        List<Sms.Msg> theirs = new ArrayList<>(old);
        theirs.add(msg("s9", false, "On my way", 1001));
        eq("[]", SentCopies.replaced(temps, shown, recorded, theirs, 2), "their text is never my copy");
        // other words: not the copy
        List<Sms.Msg> other = new ArrayList<>(old);
        other.add(msg("o8", true, "On my way!", 1001));
        eq("[]", SentCopies.replaced(temps, shown, recorded, other, 2), "other words are another text");

        // the phone said it recorded it: from the first page asked for after that, the phone shows it (even with no copy in hand)
        recorded.put("t1", 5L);
        eq("[]", SentCopies.replaced(temps, shown, recorded, old, 4), "a page asked for before the phone said so does not count");
        eq("[t1]", SentCopies.replaced(temps, shown, recorded, old, 5), "the page asked for after it");
        eq("[t1]", SentCopies.replaced(temps, shown, recorded, old, 9), "and any later one");
        recorded.clear();

        // two texts with the same words, sent one after the other: each takes one new copy
        Sms.Msg t2 = msg("t2", true, "ok", 2000);
        Sms.Msg t3 = msg("t3", true, "ok", 2001);
        List<Sms.Msg> two = Arrays.asList(t2, t3);
        Map<String, Set<String>> shown2 = new HashMap<>();
        shown2.put("t2", ids("s1"));
        shown2.put("t3", ids("s1", "t2"));
        List<Sms.Msg> oneCopy = Arrays.asList(msg("s1", false, "Hi", 10), msg("o1", true, "ok", 2000));
        eq("[t2]", SentCopies.replaced(two, shown2, recorded, oneCopy, 1), "one copy: the first bubble goes, the second waits");
        List<Sms.Msg> twoCopies = Arrays.asList(msg("s1", false, "Hi", 10), msg("o1", true, "ok", 2000), msg("o2", true, "ok", 2001));
        eq("[t2, t3]", SentCopies.replaced(two, shown2, recorded, twoCopies, 1), "two copies: both go");

        // pictures: a picture message only matches a message with files (or a picture message); a text never matches one
        Sms.Msg pic = withFile(msg("t4", true, "", 3000));
        Map<String, Set<String>> shown3 = new HashMap<>();
        shown3.put("t4", ids());
        eq("[t4]", SentCopies.replaced(Collections.singletonList(pic), shown3, recorded,
                Collections.singletonList(withFile(msg("o3", true, "", 3000))), 1), "a picture's copy");
        eq("[]", SentCopies.replaced(Collections.singletonList(pic), shown3, recorded,
                Collections.singletonList(msg("s5", true, "", 3000)), 1), "an empty text is not a picture's copy");
        Sms.Msg mmsNoParts = msg("m6", true, "", 3000);
        mmsNoParts.mms = true; // the phone's own picture message whose parts are still being written
        eq("[t4]", SentCopies.replaced(Collections.singletonList(pic), shown3, recorded, Collections.singletonList(mmsNoParts), 1),
                "a picture message of the phone counts");
        // a text to several people goes out as a picture message without files: the words match it
        Sms.Msg group = msg("t5", true, "See you all", 4000);
        Map<String, Set<String>> shown5 = new HashMap<>();
        shown5.put("t5", ids());
        Sms.Msg groupCopy = msg("m7", true, "See you all", 4000);
        groupCopy.mms = true;
        eq("[t5]", SentCopies.replaced(Collections.singletonList(group), shown5, recorded, Collections.singletonList(groupCopy), 1),
                "a group text's copy is a picture message without files");
        eq("[]", SentCopies.replaced(Collections.singletonList(group), shown5, recorded,
                Collections.singletonList(withFile(msg("m8", true, "See you all", 4000))), 1), "a text is not matched by a message with files");
        // no record of what was on screen: any new copy with the words counts
        eq("[t5]", SentCopies.replaced(Collections.singletonList(group), new HashMap<String, Set<String>>(), recorded,
                Collections.singletonList(groupCopy), 1), "without a record of the screen");

        // random: never more removed than temps; a temp is removed only with a copy or a record; copies are never shared
        Random rnd = new Random(42);
        String[] words = {"a", "b", "ok", ""};
        for (int round = 0; round < 3000; round++) {
            List<Sms.Msg> ts = new ArrayList<>();
            Map<String, Set<String>> sh = new HashMap<>();
            Map<String, Long> rec = new HashMap<>();
            List<Sms.Msg> pg = new ArrayList<>();
            int nt = rnd.nextInt(4);
            for (int i = 0; i < nt; i++) {
                Sms.Msg t = msg("t" + i, true, words[rnd.nextInt(words.length)], i);
                if (rnd.nextInt(4) == 0) {
                    withFile(t);
                }
                ts.add(t);
                sh.put(t.id, ids("p0", "p1"));
                if (rnd.nextInt(3) == 0) {
                    rec.put(t.id, (long) rnd.nextInt(5));
                }
            }
            int np = rnd.nextInt(5);
            for (int i = 0; i < np; i++) {
                Sms.Msg p = msg("p" + i, rnd.nextBoolean(), words[rnd.nextInt(words.length)], i);
                if (rnd.nextInt(4) == 0) {
                    withFile(p);
                }
                pg.add(p);
            }
            long gen = rnd.nextInt(5);
            Set<String> gone = SentCopies.replaced(ts, sh, rec, pg, gen);
            yes(gone.size() <= ts.size(), "never more than the temps");
            // an independent count: how many temps could be matched to distinct new copies of mine
            int copies = 0;
            Set<String> used = new HashSet<>();
            for (Sms.Msg t : ts) {
                boolean found = false;
                for (Sms.Msg p : pg) {
                    if (p.mine && !used.contains(p.id) && !sh.get(t.id).contains(p.id) && SentCopies.same(t, p)) {
                        used.add(p.id);
                        found = true;
                        break;
                    }
                }
                Long at = rec.get(t.id);
                boolean recordedNow = at != null && gen >= at;
                eq(found || recordedNow, gone.contains(t.id), "round " + round + ": " + t.id + " goes exactly when it has a copy or a record");
                if (found) {
                    copies++;
                }
            }
            yes(copies <= np, "copies are never shared");
        }
    }

    // ------------------------------------------------------------------ ChatFollow

    static List<ThreadRows.Row> rows(Object... mineAndTime) {
        List<ThreadRows.Row> out = new ArrayList<>();
        out.add(new ThreadRows.Row(ThreadRows.DAY, "Today", null));
        for (int i = 0; i < mineAndTime.length; i += 2) {
            out.add(new ThreadRows.Row(ThreadRows.MSG, null, msg("m" + i, (Boolean) mineAndTime[i], "x", (Long) mineAndTime[i + 1])));
        }
        out.add(new ThreadRows.Row(ThreadRows.NEW_MESSAGES, "New messages", null));
        return out;
    }

    static void follow() {
        // at the end: the last row on screen and ending within the slop below the edge
        yes(ChatFollow.atEnd(9, 10, 500, 500, 20), "the last row exactly at the edge");
        yes(ChatFollow.atEnd(9, 10, 520, 500, 20), "a little below it, within the slop");
        yes(!ChatFollow.atEnd(9, 10, 521, 500, 20), "further below: not at the end");
        yes(ChatFollow.atEnd(9, 10, 300, 500, 20), "a short conversation that does not fill the list");
        yes(!ChatFollow.atEnd(8, 10, 400, 500, 20), "the last row is not on screen");
        yes(ChatFollow.atEnd(0, 0, 0, 500, 20), "no rows: at the end");

        eq(0, ChatFollow.pinBy(9, 10, 500, 500), "already at the edge");
        eq(0, ChatFollow.pinBy(9, 10, 300, 500), "short: nothing to do (the list stacks from the bottom)");
        eq(71, ChatFollow.pinBy(9, 10, 571, 500), "71 pixels below the edge: move up 71 (the keyboard took room)");
        eq(ChatFollow.JUMP, ChatFollow.pinBy(5, 10, 9999, 500), "the last row is not laid out: jump");
        eq(0, ChatFollow.pinBy(0, 0, 0, 500), "no rows");
        // exhaustive over small numbers: pinBy says 0 exactly when the end is in place
        for (int count = 0; count <= 6; count++) {
            for (int last = 0; last < Math.max(1, count); last++) {
                for (int bottom = 0; bottom <= 40; bottom += 5) {
                    int by = ChatFollow.pinBy(last, count, bottom, 20);
                    if (count == 0) {
                        eq(0, by, "no rows");
                    } else if (last < count - 1) {
                        eq(ChatFollow.JUMP, by, "not laid out");
                    } else {
                        eq(Math.max(0, bottom - 20), by, "the distance below the edge");
                        yes(ChatFollow.atEnd(last, count, bottom - by, 20, 0), "after moving by it, it is at the end");
                    }
                }
            }
        }

        // the jump button's number: messages from others after the time you scrolled away
        List<ThreadRows.Row> r = rows(false, 100L, true, 200L, false, 300L, false, 400L);
        eq(0, ChatFollow.newSince(r, 0), "never scrolled away: no number");
        eq(2, ChatFollow.newSince(r, 200), "two from the other person since");
        eq(1, ChatFollow.newSince(r, 300), "one");
        eq(0, ChatFollow.newSince(r, 400), "none newer");
        eq(0, ChatFollow.newSince(rows(true, 500L, true, 600L), 100), "my own texts are not news");
        eq(400, ChatFollow.newest(r), "the newest time on screen");
        eq(0, ChatFollow.newest(new ArrayList<ThreadRows.Row>()), "nothing on screen");
        eq("", ChatFollow.badge(0), "no number");
        eq("7", ChatFollow.badge(7), "a number");
        eq("99", ChatFollow.badge(99), "99");
        eq("99+", ChatFollow.badge(100), "more than 99");
    }

    // ------------------------------------------------------------------ the number on the Texts button

    /** Blobs in memory. */
    static final class Mem implements BlobStore {
        final Map<String, byte[]> m = new HashMap<>();

        @Override
        public synchronized byte[] read(String name) {
            return m.get(name);
        }

        @Override
        public synchronized void write(String name, byte[] data) {
            m.put(name, data);
        }

        @Override
        public synchronized void delete(String name) {
            m.remove(name);
        }

        @Override
        public synchronized List<String> names() {
            return new ArrayList<>(m.keySet());
        }
    }

    static Sms.Thread thread(String key, String snip, long time, boolean unread, int count) {
        Sms.Thread t = new Sms.Thread();
        t.key = key;
        t.name = "N" + key;
        t.snippet = snip;
        t.time = time;
        t.unread = unread;
        t.count = count;
        t.addresses.add("+1555000" + key);
        return t;
    }

    static void badge() throws Exception {
        TextsLocal l = new TextsLocal(new Mem());
        List<Sms.Thread> ts = new ArrayList<>();
        ts.add(thread("1", "dinner?", 5000, true, 6));
        ts.add(thread("2", "ok", 9000, false, 3));
        ts.add(thread("3", "call me", 7000, true, 9));
        eq(2, ThreadListRules.badge(ts, l), "two unread conversations never opened here: one each");
        eq(0, ThreadListRules.badge(new ArrayList<Sms.Thread>(), l), "no conversations");
        // opened here: read, whatever the phone says
        l.markSeen("1", 5000, 6);
        eq(1, ThreadListRules.badge(ts, l), "the one opened here no longer counts");
        // three more texts came in it since: it counts three
        ts.get(0).time = 9500;
        ts.get(0).count = 9;
        eq(4, ThreadListRules.badge(ts, l), "three new since it was opened, plus one");
        // muted, archived and deleted ones are left out
        l.setMuted("3", true);
        eq(3, ThreadListRules.badge(ts, l), "a muted conversation is not counted");
        l.setMuted("3", false);
        l.archive("3", 7000);
        eq(3, ThreadListRules.badge(ts, l), "nor an archived one");
        l.unarchive("3");
        l.clear("3", 7000);
        eq(3, ThreadListRules.badge(ts, l), "nor one deleted on this tablet");
        // a new text brings an archived or deleted conversation back: it counts again
        ts.get(2).time = 8000;
        ts.get(2).count = 10;
        eq(4, ThreadListRules.badge(ts, l), "a deleted conversation with a new text counts again");
        // the same as the list: the sum over the unread, not muted rows of the main list
        Random rnd = new Random(7);
        for (int round = 0; round < 2000; round++) {
            TextsLocal lr = new TextsLocal(new Mem());
            List<Sms.Thread> tr = new ArrayList<>();
            int n = rnd.nextInt(6);
            for (int i = 0; i < n; i++) {
                Sms.Thread t = thread("k" + i, "x", 1000 + rnd.nextInt(1000), rnd.nextBoolean(), rnd.nextInt(50));
                tr.add(t);
                switch (rnd.nextInt(5)) {
                    case 0:
                        lr.markSeen(t.key, t.time - rnd.nextInt(2) * 10, Math.max(0, t.count - rnd.nextInt(5)));
                        break;
                    case 1:
                        lr.setMuted(t.key, true);
                        break;
                    case 2:
                        lr.archive(t.key, t.time);
                        break;
                    default:
                        break;
                }
            }
            int want = 0;
            for (ThreadListRules.Item it : ThreadListRules.build(tr, lr, "", false, false)) {
                if (it.unread && !it.muted) {
                    want += it.newCount > 0 ? it.newCount : 1;
                }
            }
            eq(want, ThreadListRules.badge(tr, lr), "round " + round + ": the badge sums what the list marks as new");
            yes(ThreadListRules.badge(tr, lr) >= ThreadListRules.unreadCount(ThreadListRules.build(tr, lr, "", false, false)),
                    "at least one per unread conversation");
        }
    }

    // ------------------------------------------------------------------ reactions

    static void reactionTexts() {
        Sms.Msg t = msg("s1", false, "Are you still up for pizza after?", 100);
        eq("Liked “Are you still up for pizza after?”", SmsReactions.textFor("👍", t, false), "a like, in the iPhone's words");
        eq("Loved “Are you still up for pizza after?”", SmsReactions.textFor("❤️", t, false), "a heart");
        eq("Loved “Are you still up for pizza after?”", SmsReactions.textFor("♥️", t, false), "the other heart is the same heart");
        eq("Laughed at “Are you still up for pizza after?”", SmsReactions.textFor("😂", t, false), "a laugh");
        eq("Emphasized “Are you still up for pizza after?”", SmsReactions.textFor("‼️", t, false), "an emphasis");
        eq("Questioned “Are you still up for pizza after?”", SmsReactions.textFor("❓", t, false), "a question");
        eq("Disliked “Are you still up for pizza after?”", SmsReactions.textFor("👎", t, false), "a dislike");
        eq("Reacted 😮 to “Are you still up for pizza after?”", SmsReactions.textFor("😮", t, false), "any other emoji");
        eq("Removed a like from “Are you still up for pizza after?”", SmsReactions.textFor("👍", t, true), "a like taken back");
        eq("Removed 🙏 from “Are you still up for pizza after?”", SmsReactions.textFor("🙏", t, true), "another emoji taken back");
        eq("Liked “two lines here”", SmsReactions.textFor("👍", msg("s2", false, "two\nlines   here ", 1), false), "one line, single spaces");
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            longText.append("word").append(i).append(' ');
        }
        String cut = SmsReactions.textFor("❤️", msg("s3", true, longText.toString(), 1), false);
        yes(cut.startsWith("Loved “word0 word1") && cut.endsWith("…”") && cut.codePointCount(0, cut.length()) <= "Loved “”".length() + 100,
                "a long text is quoted cut short: " + cut);
        yes(SmsReactions.about(SmsReactions.parse(cut), msg("s3", true, longText.toString(), 1)), "and still finds its message");
        eq("Loved an image", SmsReactions.textFor("❤️", withFile(msg("s4", false, "", 1)), false), "a picture");
        Sms.Msg video = msg("s5", false, "", 1);
        Sms.Part vp = new Sms.Part();
        vp.type = "video/mp4";
        video.parts.add(vp);
        video.mms = true;
        eq("Reacted 😮 to a video", SmsReactions.textFor("😮", video, false), "a video");
        Sms.Msg file = msg("s6", false, "", 1);
        Sms.Part fp = new Sms.Part();
        fp.type = "application/pdf";
        file.parts.add(fp);
        file.mms = true;
        eq("Liked an attachment", SmsReactions.textFor("👍", file, false), "a file");

        // every emoji the menu offers, on texts and pictures, reads back as the same reaction on the same message
        List<String> all = new ArrayList<>(Arrays.asList(EmojiText.QUICK));
        all.addAll(Arrays.asList(EmojiText.MORE));
        Sms.Msg[] targets = {t, msg("s7", true, "I'm here “inside” the door", 1), msg("s8", false, "ok", 1), withFile(msg("s9", false, "", 1)),
            withFile(msg("s10", false, "Look at this", 1))};
        for (String e : all) {
            for (Sms.Msg m : targets) {
                for (boolean remove : new boolean[] {false, true}) {
                    String text = SmsReactions.textFor(e, m, remove);
                    SmsReactions.Reaction r = SmsReactions.parse(text);
                    yes(r != null, "reads back: " + text);
                    if (r != null) {
                        eq(SmsReactions.same(e), SmsReactions.same(r.emoji), "the same emoji: " + text);
                        eq(remove, r.remove, "taken back or not: " + text);
                        yes(SmsReactions.about(r, m), "about its message: " + text);
                        yes(SmsReactions.previewOf(text) != null, "the list has words for it: " + text);
                    }
                }
            }
        }

        // what other phones send
        SmsReactions.Reaction r = SmsReactions.parse("Le encantó “Nos vemos a las 7”");
        yes(r != null && r.emoji.equals(SmsReactions.HEART) && r.quote.equals("Nos vemos a las 7"), "an iPhone in Spanish: a heart");
        eq(SmsReactions.LIKE, SmsReactions.parse("Le gustó “ok”").emoji, "Le gustó");
        eq(SmsReactions.DISLIKE, SmsReactions.parse("No le gustó “ok”").emoji, "No le gustó");
        eq(SmsReactions.LAUGH, SmsReactions.parse("Se rió de “jaja”").emoji, "Se rió de");
        eq(SmsReactions.LAUGH, SmsReactions.parse("Se rio de “jaja”").emoji, "Se rio de, without the accent");
        eq(SmsReactions.EMPHASIS, SmsReactions.parse("Enfatizó “mañana”").emoji, "Enfatizó");
        eq(SmsReactions.QUESTION, SmsReactions.parse("Cuestionó “mañana”").emoji, "Cuestionó");
        r = SmsReactions.parse("😮 to “See you there”");
        yes(r != null && r.emoji.equals("😮") && r.quote.equals("See you there") && !r.remove, "the emoji first: 😮 to “...”");
        r = SmsReactions.parse("Removed 🙏 from “thanks”");
        yes(r != null && r.remove && r.emoji.equals("🙏"), "any emoji taken back");
        r = SmsReactions.parse("Reacted 👍🏽 to “ok then”");
        yes(r != null && r.emoji.equals("👍🏽"), "an emoji with a skin tone is one emoji");
        // and what is an ordinary text
        yes(SmsReactions.parse("Reacted well to “the news here”") == null, "words are not an emoji");
        yes(SmsReactions.parse("😂😂 to “that joke”") == null, "two emoji are not one reaction");
        yes(SmsReactions.parse("I to “x and y”") == null, "a letter is not an emoji");
        yes(SmsReactions.parse("Le gustó mucho la cena") == null, "Spanish words without a quotation");
        yes(SmsReactions.parse("Se rió de mí todo el día") == null, "another sentence");
        yes(SmsReactions.parse("👍 to me") == null, "no quotation");
        yes(SmsReactions.parse("Removed the dog from “the couch”") == null, "a sentence that starts with Removed");

        eq("Reacted 👍 to “see you at 7”", SmsReactions.previewOf("Liked “see you at 7”"), "the list's line for a reaction");
        eq("Reacted 😮 to a picture", SmsReactions.previewOf("Reacted 😮 to an image"), "for a picture");
        eq("Took back 👍 from “ok”", SmsReactions.previewOf("Removed a like from “ok”"), "for one taken back");
        yes(SmsReactions.previewOf("See you at 7") == null, "an ordinary text has none");

        // the pill on the corner
        List<ThreadRows.Applied> ap = new ArrayList<>();
        eq("", ThreadRows.pill(ap), "no reactions");
        eq(null, ThreadRows.mine(ap), "none of mine");
        ap.add(new ThreadRows.Applied("❤️", false, "+15550001"));
        ap.add(new ThreadRows.Applied("👍", true, "me"));
        ap.add(new ThreadRows.Applied("♥️", false, "+15550002"));
        eq("❤️ 2  👍", ThreadRows.pill(ap), "each emoji once, in order, counted (the two hearts are one)");
        eq("👍", ThreadRows.mine(ap), "mine");
    }

    /** The list's line for a reaction text: in plain words, but never instead of words when previews are hidden. */
    static void reactionPreview() {
        Sms.Thread t = thread("1", "Liked “see you at 7”", 5000, false, 4);
        eq("Reacted 👍 to “see you at 7”", ThreadListRules.preview(t, false), "a reaction in plain words");
        eq("Message", ThreadListRules.preview(t, true), "previews hidden: no words");
        t.snippet = "See you at 7";
        eq("See you at 7", ThreadListRules.preview(t, false), "an ordinary text as it is");
    }

    public static void main(String[] args) throws Exception {
        sentCopies();
        follow();
        badge();
        reactionTexts();
        reactionPreview();
        System.out.println(checks + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
