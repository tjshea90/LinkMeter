package com.tj.linkmeter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * Tests the pure parts of the new Texts screens: the text-message counter, the reaction texts, the quick replies, the tablet's own
 * choices (pin, mute, archive, delete, drafts, what was read), the saved copy, the conversation list rules and the rows of a
 * conversation (days, groups, the new-messages line, reactions folded in). Not part of the app.
 */
public final class TextsLogicTest {
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

    static final long DAY = 86_400_000L;
    static final long D0 = (1_700_000_000_000L / DAY) * DAY; // a midnight, UTC
    static final TextTime.Env ENV = new TextTime.Env(TimeZone.getTimeZone("UTC"), Locale.US, false);
    static final long H = 3_600_000L;
    static final long MIN = 60_000L;

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

    static Sms.Thread thread(String key, String name, String snip, long time, boolean unread, int count) {
        Sms.Thread t = new Sms.Thread();
        t.key = key;
        t.name = name;
        t.snippet = snip;
        t.time = time;
        t.unread = unread;
        t.count = count;
        t.addresses.add("+1" + key);
        return t;
    }

    /** Blobs in memory. */
    static final class Mem implements BlobStore {
        final Map<String, byte[]> m = new TreeMap<>();
        boolean failWrites;

        @Override
        public byte[] read(String name) {
            byte[] b = m.get(name);
            return b == null ? null : b.clone();
        }

        @Override
        public void write(String name, byte[] data) throws IOException {
            if (failWrites) {
                throw new IOException("disk full");
            }
            m.put(name, data.clone());
        }

        @Override
        public void delete(String name) {
            m.remove(name);
        }

        @Override
        public List<String> names() {
            return new ArrayList<>(m.keySet());
        }
    }

    // ------------------------------------------------------------------ how many text messages

    static void lengths() {
        SmsLength.Count c = SmsLength.of("");
        eq("0/0/160", c.parts + "/" + c.units + "/" + c.left, "an empty text is no message");
        c = SmsLength.of("Hello");
        eq("1/5/155/false", c.parts + "/" + c.units + "/" + c.left + "/" + c.unicode, "a short GSM text");
        eq("1/0", SmsLength.of(rep('a', 160)).parts + "/" + SmsLength.of(rep('a', 160)).left, "160 letters are one message with nothing left");
        eq("2/145", SmsLength.of(rep('a', 161)).parts + "/" + SmsLength.of(rep('a', 161)).left, "161 letters are two messages (153 in each)");
        eq("2/0", SmsLength.of(rep('a', 306)).parts + "/" + SmsLength.of(rep('a', 306)).left, "306 letters fill two");
        eq(3, SmsLength.of(rep('a', 307)).parts, "307 letters are three");
        eq("1/160", SmsLength.of(rep('{', 80)).parts + "/" + SmsLength.of(rep('{', 80)).units, "80 braces count twice each: 160 places");
        eq(2, SmsLength.of(rep('{', 81)).parts, "81 braces are two messages");
        eq("1/2", SmsLength.of("€").parts + "/" + SmsLength.of("€").units, "the euro sign is an extension character");
        eq(false, SmsLength.of("café ñ ü à\n").unicode, "accents of the GSM set stay GSM");
        eq(true, SmsLength.of("ç").unicode, "c with a cedilla (small) is not in the GSM set");
        c = SmsLength.of("hi 😀");
        eq("true/5/1/4", c.unicode + "/" + c.units + "/" + c.parts + "/" + c.chars, "an emoji makes it 16-bit: the pair counts two places and one character for the reader");
        eq("1/0", SmsLength.of(rep('Ж', 70)).parts + "/" + SmsLength.of(rep('Ж', 70)).left, "70 Cyrillic letters are one message");
        eq("2/63", SmsLength.of(rep('Ж', 71)).parts + "/" + SmsLength.of(rep('Ж', 71)).left, "71 are two (67 in each)");
        eq(2, SmsLength.of(rep('Ж', 134)).parts, "134 fill two");
        eq(3, SmsLength.of(rep('Ж', 135)).parts, "135 are three");
        eq(0, SmsLength.of(null).parts, "no text");
    }

    static String rep(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    // ------------------------------------------------------------------ reaction texts

    static void reactions() {
        SmsReactions.Reaction r = SmsReactions.parse("Loved “See you at 7”");
        eq(SmsReactions.HEART + "|See you at 7|false|false", r.emoji + "|" + r.quote + "|" + r.media + "|" + r.remove, "Loved");
        eq(SmsReactions.LIKE, SmsReactions.parse("Liked \"ok\"").emoji, "Liked, with plain quotes");
        eq(SmsReactions.LAUGH, SmsReactions.parse("Laughed at “That is funny”").emoji, "Laughed at");
        eq(SmsReactions.EMPHASIS, SmsReactions.parse("Emphasized “Hi there”").emoji, "Emphasized");
        eq(SmsReactions.QUESTION, SmsReactions.parse("Questioned “Really now?”").emoji, "Questioned");
        eq(SmsReactions.DISLIKE, SmsReactions.parse("Disliked “No way”").emoji, "Disliked");
        r = SmsReactions.parse("Loved an image");
        yes(r != null && r.media && r.quote.isEmpty(), "a reaction to a picture");
        r = SmsReactions.parse("Removed a heart from “See you at 7”");
        yes(r != null && r.remove && r.emoji.equals(SmsReactions.HEART) && r.quote.equals("See you at 7"), "a taken back heart");
        r = SmsReactions.parse("Removed a like from a photo");
        yes(r != null && r.remove && r.media, "a taken back like on a photo");
        r = SmsReactions.parse("Reacted 😂 to “lol that”");
        yes(r != null && r.emoji.equals("😂") && r.quote.equals("lol that"), "Google Messages style");
        yes(SmsReactions.parse("Reacted well to “the news here”") == null, "'Reacted well' is a sentence, not a reaction");
        yes(SmsReactions.parse("Loved the movie, see you") == null, "no quotation: an ordinary text");
        yes(SmsReactions.parse("I Loved “this” so much") == null, "does not start with the verb");
        yes(SmsReactions.parse("Liked it") == null, "too short");
        yes(SmsReactions.parse("Loved “line\none”") == null, "one line only");
        yes(SmsReactions.parse(null) == null && SmsReactions.parse("") == null, "nothing");
        yes(SmsReactions.parse("Removed a hat from “See you at 7”") == null, "a thing that is no reaction");

        // which message it is about
        SmsReactions.Reaction cut = SmsReactions.parse("Loved “Hey are you coming to the…”");
        yes(SmsReactions.about(cut, msg("a", true, "Hey are you coming to the party tonight?", 1)), "a cut quotation matches the start of the text");
        yes(!SmsReactions.about(cut, msg("a", true, "Something else entirely", 1)), "and nothing else");
        SmsReactions.Reaction ap = SmsReactions.parse("Liked “I’m here”");
        yes(SmsReactions.about(ap, msg("a", true, "I'm here", 1)), "curly and straight apostrophes are the same");
        yes(!SmsReactions.about(ap, msg("a", true, "I'm here now", 1)), "the whole text, not a part, when it was not cut");
        Sms.Msg pic = msg("p", true, "", 1);
        pic.mms = true;
        SmsReactions.Reaction im = SmsReactions.parse("Loved an image");
        yes(SmsReactions.about(im, pic) && !SmsReactions.about(im, msg("t", true, "words", 1)), "a picture is about a picture message");
    }

    // ------------------------------------------------------------------ quick replies

    static List<Sms.Msg> last(boolean mine, String body, long age, long now) {
        List<Sms.Msg> l = new ArrayList<>();
        l.add(msg("x", mine, body, now - age));
        return l;
    }

    static void smart() {
        long now = D0 + 20 * H;
        eq("[Yes, No, Maybe later]", SmartReply.suggest(last(false, "Are you coming tonight?", MIN, now), now), "a question for yes or no");
        eq("[You're welcome!, No problem, 👍]", SmartReply.suggest(last(false, "Thanks a lot!", MIN, now), now), "thanks");
        eq("[On my way, Running late, Almost there]", SmartReply.suggest(last(false, "Where are you?", MIN, now), now), "where are you");
        eq("[Hi!, Hey, how are you?, What's up?]", SmartReply.suggest(last(false, "hey", MIN, now), now), "a greeting");
        eq("Good morning!", SmartReply.suggest(last(false, "Good morning :)", MIN, now), now).get(0), "good morning");
        eq("[I'll call you soon, Can't talk right now, Text me instead]", SmartReply.suggest(last(false, "Call me when you can", MIN, now), now), "call me gets only words");
        eq("[Sounds good, Let me check, What time works for you?]", SmartReply.suggest(last(false, "What time should we meet?", MIN, now), now), "when / what time");
        eq("[Yes, No, Let me check]", SmartReply.suggest(last(false, "Lunch at noon, works for you?", MIN, now), now), "any other question");
        eq("[]", SmartReply.suggest(last(false, "The meeting moved to Friday", MIN, now), now), "no question, nothing to say quickly");
        eq("[]", SmartReply.suggest(last(true, "Are you coming tonight?", MIN, now), now), "my own text last: nothing");
        eq("[]", SmartReply.suggest(last(false, "Are you coming tonight?", 2 * DAY, now), now), "an old text: nothing");
        eq("[]", SmartReply.suggest(last(false, "Loved “See you at 7”", MIN, now), now), "a reaction: nothing");
        eq("[]", SmartReply.suggest(new ArrayList<Sms.Msg>(), now), "an empty conversation");
        Random rnd = new Random(7);
        String[] words = {"are", "you", "where", "thanks", "hi", "?", "call", "me", "ok", "when", "love", "what", "time", "the", "\n", "😀"};
        for (int i = 0; i < 3000; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0, n = rnd.nextInt(8); j < n; j++) {
                sb.append(words[rnd.nextInt(words.length)]).append(' ');
            }
            List<String> s = SmartReply.suggest(last(false, sb.toString(), MIN, now), now);
            yes(s.size() == 0 || s.size() == 3, "zero or three suggestions: " + s);
            for (String x : s) {
                yes(!x.isEmpty(), "no empty suggestion");
            }
        }
    }

    // ------------------------------------------------------------------ the tablet's own choices

    static void local() throws IOException {
        Mem mem = new Mem();
        TextsLocal l = new TextsLocal(mem);
        l.setPinned("a", true, 5);
        l.setMuted("b", true);
        l.archive("c", 100);
        l.clear("d", 200);
        l.hide(Arrays.asList("s1", "m2", "o3"));
        l.setDraft("e", "half a thought");
        l.markSeen("f", 300, 7);
        yes(l.isPinned("a") && !l.isPinned("b"), "pinned");
        yes(l.isMuted("b") && !l.isMuted("a"), "muted");
        yes(l.isArchived("c", 100) && l.isArchived("c", 50) && !l.isArchived("c", 101), "archived until a newer message");
        yes(!l.isArchived("zzz", 1), "an unknown conversation is not archived");
        yes(l.isCleared("d", 200) && !l.isCleared("d", 201), "deleted until a newer message");
        eq(200, l.clearedAt("d"), "deleted up to");
        yes(l.isHidden("s1") && l.isHidden("o3") && !l.isHidden("s9"), "hidden messages");
        eq("half a thought", l.draft("e"), "draft");
        eq("", l.draft("nobody"), "no draft");
        eq("300/7", l.seenAt("f") + "/" + l.seenCount("f"), "seen");
        yes(!l.dirty(), "everything but the draft was written at once");
        l.flush();
        yes(!l.dirty(), "flushed");

        TextsLocal back = new TextsLocal(mem); // a restart
        yes(back.isPinned("a") && back.isMuted("b") && back.isArchived("c", 50) && back.isCleared("d", 100) && back.isHidden("m2"), "everything comes back after a restart");
        eq("half a thought", back.draft("e"), "the draft too (it was flushed)");
        eq("300/7", back.seenAt("f") + "/" + back.seenCount("f"), "and what was read");

        // a draft is written at the next flush, not at every key
        byte[] before = mem.read(TextsLocal.BLOB);
        l.setDraft("e", "half a thought, more");
        yes(l.dirty() && Arrays.equals(before, mem.read(TextsLocal.BLOB)), "a draft change waits for the flush");
        l.flush();
        eq("half a thought, more", new TextsLocal(mem).draft("e"), "and is there after it");
        l.setDraft("e", "   ");
        eq("", l.draft("e"), "an empty draft is no draft");

        // seen only moves forward
        yes(!l.markSeen("f", 200, 3), "an older time does not move it");
        yes(l.markSeen("f", 400, 9), "a newer time does");
        eq("400/9", l.seenAt("f") + "/" + l.seenCount("f"), "now");
        yes(l.markSeen("f", 400, 8) && l.seenCount("f") == 8, "the same time with another count: the count follows (messages were deleted)");

        // deleting a conversation also lets go of its pin and draft; a later delete does not un-delete
        l.setPinned("d", true, 1);
        l.setDraft("d", "x");
        l.clear("d", 250);
        yes(!l.isPinned("d") && l.draft("d").isEmpty(), "deleting lets go of the pin and the draft");
        l.clear("d", 100);
        eq(250, l.clearedAt("d"), "an older delete does not bring messages back");
        l.unarchive("c");
        yes(!l.isArchived("c", 1), "unarchived");
        l.forget("a");
        yes(!l.isPinned("a"), "forgotten");

        // a damaged or missing file
        Mem bad = new Mem();
        bad.m.put(TextsLocal.BLOB, "{ this is not json".getBytes(StandardCharsets.UTF_8));
        yes(!new TextsLocal(bad).isPinned("a"), "a damaged file starts empty");
        bad.m.put(TextsLocal.BLOB, "[1,2,3]".getBytes(StandardCharsets.UTF_8));
        yes(!new TextsLocal(bad).isMuted("a"), "the wrong kind of JSON starts empty");

        // a disk that is full: the choice is kept in memory, and written when it can be
        Mem full = new Mem();
        TextsLocal fl = new TextsLocal(full);
        full.failWrites = true;
        fl.setMuted("m", true);
        yes(fl.isMuted("m") && fl.dirty(), "kept in memory, and still to be written");
        full.failWrites = false;
        fl.flush();
        yes(!fl.dirty() && new TextsLocal(full).isMuted("m"), "written when the disk works again");

        // the hidden set is bounded
        TextsLocal big = new TextsLocal(new Mem());
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < TextsLocal.MAX_HIDDEN + 50; i++) {
            ids.add("s" + i);
        }
        big.hide(ids);
        yes(!big.isHidden("s0") && big.isHidden("s" + (TextsLocal.MAX_HIDDEN + 49)), "the oldest hidden ids make room");

        // forget everything
        l.forgetAll();
        yes(mem.read(TextsLocal.BLOB) == null && !l.isMuted("b") && l.seenAt("f") == 0, "forgotten everywhere, on disk too");

        // a random story against a plain model
        Random rnd = new Random(99);
        for (int round = 0; round < 200; round++) {
            Mem m = new Mem();
            TextsLocal t = new TextsLocal(m);
            Set<String> pinnedM = new HashSet<>();
            Set<String> mutedM = new HashSet<>();
            Map<String, Long> archM = new HashMap<>();
            Map<String, Long> clearM = new HashMap<>();
            Map<String, Long> seenM = new HashMap<>();
            String[] keys = {"1", "2", "3", "4"};
            for (int step = 0; step < 60; step++) {
                String k = keys[rnd.nextInt(keys.length)];
                long v = rnd.nextInt(1000);
                switch (rnd.nextInt(7)) {
                    case 0:
                        t.setPinned(k, true, v);
                        pinnedM.add(k);
                        break;
                    case 1:
                        t.setPinned(k, false, v);
                        pinnedM.remove(k);
                        break;
                    case 2:
                        t.setMuted(k, rnd.nextBoolean());
                        if (t.isMuted(k)) {
                            mutedM.add(k);
                        } else {
                            mutedM.remove(k);
                        }
                        break;
                    case 3:
                        t.archive(k, v);
                        archM.put(k, v);
                        break;
                    case 4:
                        t.clear(k, v);
                        clearM.put(k, Math.max(v, clearM.containsKey(k) ? clearM.get(k) : 0));
                        archM.remove(k);
                        pinnedM.remove(k);
                        break;
                    case 5:
                        t.markSeen(k, v, 1);
                        if (v > (seenM.containsKey(k) ? seenM.get(k) : 0)) {
                            seenM.put(k, v);
                        }
                        break;
                    default:
                        t.unarchive(k);
                        archM.remove(k);
                        break;
                }
                if (rnd.nextInt(10) == 0) {
                    t = new TextsLocal(m); // restart
                }
                for (String kk : keys) {
                    long probe = rnd.nextInt(1000);
                    yes(t.isPinned(kk) == pinnedM.contains(kk), "pinned matches the model");
                    yes(t.isMuted(kk) == mutedM.contains(kk), "muted matches the model");
                    yes(t.isArchived(kk, probe) == (archM.containsKey(kk) && probe <= archM.get(kk)), "archived matches the model");
                    yes(t.isCleared(kk, probe) == (clearM.containsKey(kk) && probe <= clearM.get(kk)), "cleared matches the model");
                    yes(t.seenAt(kk) == (seenM.containsKey(kk) ? seenM.get(kk) : 0), "seen matches the model");
                }
            }
        }
    }

    // ------------------------------------------------------------------ the saved copy

    static void cache() throws IOException {
        Mem mem = new Mem();
        final long[] now = {1000};
        TextsCache c = new TextsCache(mem, () -> now[0], 3, 5);
        yes(c.loadThreads() == null && c.loadConvo("k") == null, "nothing saved yet");
        List<Sms.Thread> ts = new ArrayList<>();
        ts.add(thread("5551", "Alex", "hello", 10, true, 4));
        ts.add(thread("5552", "Sam", "yo", 5, false, 2));
        c.saveThreads(ts);
        TextsCache.Saved<List<Sms.Thread>> got = c.loadThreads();
        eq("2|Alex|hello|true|4|1000", got.value.size() + "|" + got.value.get(0).name + "|" + got.value.get(0).snippet + "|" + got.value.get(0).unread + "|"
                + got.value.get(0).count + "|" + got.at, "the list comes back as it was, with when it was saved");

        Sms.Convo cv = new Sms.Convo();
        cv.head.name = "Alex";
        cv.head.addresses.add("+15551");
        for (int i = 0; i < 8; i++) {
            cv.msgs.add(msg("s" + i, i % 2 == 0, "m" + i, 100 + i));
        }
        now[0] = 2000;
        c.saveConvo("5551", cv);
        TextsCache.Saved<Sms.Convo> back = c.loadConvo("5551");
        eq("5|s3|s7|true|Alex|2000", back.value.msgs.size() + "|" + back.value.msgs.get(0).id + "|" + back.value.msgs.get(4).id + "|" + back.value.more + "|"
                + back.value.head.name + "|" + back.at, "the newest five messages are kept, and it says there are older ones");
        yes(back.value.head.addresses.contains("+15551"), "and who it is with");
        yes(c.loadConvo("nobody") == null, "another conversation is not there");
        for (String n : mem.names()) {
            yes(!n.contains("5551"), "no phone number in a blob name: " + n);
        }
        // bounds: three conversations at most, the oldest save goes
        for (int i = 1; i <= 5; i++) {
            now[0] = 3000 + i;
            c.saveConvo("k" + i, cv);
        }
        eq(3, c.convos(), "at most three conversations are kept");
        yes(c.loadConvo("k5") != null && c.loadConvo("k4") != null && c.loadConvo("k3") != null, "the newest three are there");
        yes(c.loadConvo("k1") == null && c.loadConvo("k2") == null && c.loadConvo("5551") == null, "older ones went");
        yes(c.loadThreads() != null, "the list is not counted among the conversations");
        // a restart keeps the index, so the bound still holds
        TextsCache again = new TextsCache(mem, () -> now[0], 3, 5);
        now[0] = 9000;
        again.saveConvo("k6", cv);
        eq(3, again.convos(), "after a restart too");
        yes(again.loadConvo("k3") == null && again.loadConvo("k6") != null, "the oldest went");
        again.remove("k6");
        yes(again.loadConvo("k6") == null, "removed");
        // a blob that holds another conversation (a hash collision, or a swapped file) is not shown
        mem.m.put(TextsCache.blobOf("k5"), mem.m.get(TextsCache.blobOf("k4")));
        yes(again.loadConvo("k5") == null, "a blob for another key is not used");
        mem.m.put(TextsCache.blobOf("k4"), "garbage".getBytes(StandardCharsets.UTF_8));
        yes(again.loadConvo("k4") == null, "a damaged blob is not used");
        // clear
        again.clear();
        yes(mem.names().isEmpty() || mem.names().equals(Arrays.asList("local.json")), "clear leaves no saved text: " + mem.names());
        yes(again.loadThreads() == null && again.convos() == 0, "nothing is left");
        // a disk that fails
        mem.failWrites = true;
        again.saveThreads(ts);
        again.saveConvo("z", cv);
        yes(again.loadThreads() == null, "a failed write is no crash and saves nothing");
    }

    // ------------------------------------------------------------------ the conversation list

    static void listRules() throws IOException {
        TextsLocal l = new TextsLocal(new Mem());
        List<Sms.Thread> ts = new ArrayList<>();
        ts.add(thread("1", "Alex", "dinner?", D0 + 5 * H, true, 6));
        ts.add(thread("2", "Sam", "ok", D0 + 9 * H, false, 3));
        ts.add(thread("3", "Mom", "call me", D0 + 7 * H, true, 9));
        ts.add(thread("4", "", "your code is 123456", D0 + 1 * H, false, 1));
        ts.add(thread("5", "Old pal", "bye", D0 - DAY, false, 20));
        ts.get(0).addresses.set(0, "+15551234567");
        List<ThreadListRules.Item> items = ThreadListRules.build(ts, l, "", false, false);
        eq("2,3,1,4,5", keys(items), "newest first");
        eq(2, ThreadListRules.unreadCount(items), "two unread");
        l.setPinned("5", true, 1);
        l.setPinned("4", true, 2);
        eq("4,5,2,3,1", keys(ThreadListRules.build(ts, l, "", false, false)), "pinned ones first, then newest first");
        l.setPinned("4", false, 0);
        l.setPinned("5", false, 0);
        // read on this tablet
        l.markSeen("1", D0 + 5 * H, 6);
        eq("3", keys(ThreadListRules.build(ts, l, "", true, false)), "unread only: the one opened here is no longer unread");
        // a new message in it makes it unread again, with a count
        ts.get(0).time = D0 + 10 * H;
        ts.get(0).count = 9;
        ts.get(0).snippet = "hello?";
        items = ThreadListRules.build(ts, l, "", true, false);
        eq("1,3", keys(items), "unread again");
        eq(3, items.get(0).newCount, "three new messages since it was opened");
        eq(0, items.get(1).newCount, "never opened here: a dot, no number");
        // muted ones do not count
        l.setMuted("1", true);
        eq(1, ThreadListRules.unreadCount(ThreadListRules.build(ts, l, "", false, false)), "a muted conversation is not counted");
        // archive and delete
        l.archive("2", D0 + 9 * H);
        l.clear("4", D0 + 1 * H);
        eq("1,3,5", keys(ThreadListRules.build(ts, l, "", false, false)), "archived and deleted ones are not in the main list");
        eq("2", keys(ThreadListRules.build(ts, l, "", false, true)), "the archived list has the archived one only");
        eq(1, ThreadListRules.archivedCount(ts, l), "one archived");
        ts.get(1).time = D0 + 11 * H; // a new message in the archived conversation
        ts.get(3).time = D0 + 12 * H; // and in the deleted one
        eq("4,2,1,3,5", keys(ThreadListRules.build(ts, l, "", false, false)), "both come back with a newer message");
        eq(0, ThreadListRules.archivedCount(ts, l), "nothing is archived any more");
        // search
        eq("1", keys(ThreadListRules.build(ts, l, "hello", false, false)), "by the last text");
        eq("3", keys(ThreadListRules.build(ts, l, "MOM", false, false)), "by name, any case");
        eq("1", keys(ThreadListRules.build(ts, l, "5551234", false, false)), "by digits of the number (three or more)");
        eq("", keys(ThreadListRules.build(ts, l, "55", false, false)), "two digits are not a search of numbers");
        eq("4,2,1,3,5", keys(ThreadListRules.build(ts, l, "   ", false, false)), "blank: everything");
        // previews and titles
        eq("dinner? yes", ThreadListRules.preview(thread("9", "x", "dinner?\n  yes", 1, false, 1), false), "one line");
        eq("New message", ThreadListRules.preview(thread("9", "x", "secret", 1, true, 1), true), "hidden, unread");
        eq("Message", ThreadListRules.preview(thread("9", "x", "secret", 1, false, 1), true), "hidden, read");
        Sms.Thread att = thread("9", "x", "", 1, false, 1);
        att.attachment = true;
        eq("Attachment", ThreadListRules.preview(att, false), "only a picture");
        eq("No messages", ThreadListRules.preview(thread("9", "x", "", 1, false, 0), false), "empty");
        eq("Alex", ThreadListRules.title(thread("1", "Alex", "", 1, false, 1)), "the name");
        eq("(555) 000-1234", ThreadListRules.title(numberOnly()), "or the number, nicely written");
    }

    static Sms.Thread numberOnly() {
        Sms.Thread t = thread("5550001234", "", "", 1, false, 1);
        t.addresses.clear();
        t.addresses.add("+15550001234");
        return t;
    }

    static String keys(List<ThreadListRules.Item> items) {
        StringBuilder sb = new StringBuilder();
        for (ThreadListRules.Item i : items) {
            sb.append(sb.length() > 0 ? "," : "").append(i.t.key);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ the rows of a conversation

    static List<String> flat(List<ThreadRows.Row> rows) {
        List<String> out = new ArrayList<>();
        for (ThreadRows.Row r : rows) {
            if (r.kind == ThreadRows.DAY) {
                out.add("D:" + r.label);
            } else if (r.kind == ThreadRows.NEW_MESSAGES) {
                out.add("N");
            } else {
                out.add(r.msg.id + (r.groupStart ? "[" : "") + (r.groupEnd ? "]" : "") + (r.lastOfMine ? "*" : "")
                        + (r.reactions.isEmpty() ? "" : "{" + r.reactions.size() + "}"));
            }
        }
        return out;
    }

    static void rows() throws IOException {
        TextsLocal l = new TextsLocal(new Mem());
        long now = D0 + DAY + 12 * H;
        // groups and days
        List<Sms.Msg> ms = new ArrayList<>();
        ms.add(msg("m1", false, "a", D0 + 10 * H));
        ms.add(msg("m2", false, "b", D0 + 10 * H + MIN));
        ms.add(msg("m3", false, "c", D0 + 10 * H + 9 * MIN));
        ms.add(msg("m4", true, "d", D0 + 10 * H + 9 * MIN + 30_000));
        ms.add(msg("m5", true, "e", D0 + 10 * H + 10 * MIN));
        ms.add(msg("m6", true, "f", D0 + DAY + 2 * H));
        eq("[D:Yesterday, m1[, m2], m3[], m4[, m5], D:Today, m6[]*]", flat(ThreadRows.build(ms, l, "k", false, 0, now, ENV)), "days, groups and the last message I sent");
        // a group chat: two other people in a row are two groups
        List<Sms.Msg> g = new ArrayList<>();
        Sms.Msg a = msg("g1", false, "hi", D0 + 10 * H);
        a.address = "+1555A";
        Sms.Msg b = msg("g2", false, "yo", D0 + 10 * H + 5_000);
        b.address = "+1555B";
        Sms.Msg a2 = msg("g3", false, "hey", D0 + 10 * H + 9_000);
        a2.address = "+1555A";
        g.add(a);
        g.add(b);
        g.add(a2);
        eq("[D:Yesterday, g1[], g2[], g3[]]", flat(ThreadRows.build(g, l, "k", true, 0, now, ENV)), "in a group chat each person's run is a group");
        eq("[D:Yesterday, g1[, g2, g3]]", flat(ThreadRows.build(g, l, "k", false, 0, now, ENV)), "in a chat with one person the address does not matter");
        // the new-messages line
        List<Sms.Msg> n = new ArrayList<>();
        n.add(msg("n1", false, "a", D0 + 10 * H));
        n.add(msg("n2", true, "b", D0 + 10 * H + MIN));
        n.add(msg("n3", false, "c", D0 + 10 * H + 5 * MIN));
        n.add(msg("n4", false, "d", D0 + 10 * H + 6 * MIN));
        eq("[D:Yesterday, n1[], n2[]*, N, n3[, n4]]", flat(ThreadRows.build(n, l, "k", false, D0 + 10 * H + 2 * MIN, now, ENV)), "the line is above the first message that is new");
        eq("[D:Yesterday, n1[], n2[]*, n3[, n4]]", flat(ThreadRows.build(n, l, "k", false, 0, now, ENV)), "never opened: no line");
        eq("[D:Yesterday, n1[], n2[]*, n3[, n4]]", flat(ThreadRows.build(n, l, "k", false, D0 + 10 * H + 6 * MIN, now, ENV)), "everything seen: no line");
        eq("[D:Yesterday, n1[], n2[]*, n3[, n4]]", flat(ThreadRows.build(n, l, "k", false, D0 + 10 * H - 1, now, ENV)), "everything is new: no line above the first message");
        List<Sms.Msg> split = new ArrayList<>();
        split.add(msg("p1", false, "a", D0 + 10 * H));
        split.add(msg("p2", false, "b", D0 + 10 * H + MIN));
        eq("[D:Yesterday, p1[], N, p2[]]", flat(ThreadRows.build(split, l, "k", false, D0 + 10 * H + 30_000, now, ENV)), "the line splits a run of messages from one person into two groups");
        // deleted on this tablet
        l.hide(Arrays.asList("m2"));
        l.clear("k", D0 + 10 * H);
        eq("[D:Yesterday, m3[], m4[, m5], D:Today, m6[]*]", flat(ThreadRows.build(ms, l, "k", false, 0, now, ENV)), "a hidden message and the cleared ones are gone");
        // reactions
        TextsLocal l2 = new TextsLocal(new Mem());
        List<Sms.Msg> r = new ArrayList<>();
        r.add(msg("r1", true, "See you at 7", D0 + 10 * H));
        r.add(msg("r2", false, "Loved “See you at 7”", D0 + 10 * H + MIN));
        r.add(msg("r3", true, "Hello there", D0 + 10 * H + 2 * MIN));
        r.add(msg("r4", false, "Laughed at “Hello there”", D0 + 10 * H + 3 * MIN));
        r.add(msg("r5", false, "Liked “Hello there”", D0 + 10 * H + 4 * MIN)); // replaces r4's reaction by the same person
        r.add(msg("r6", true, "Loved “Never sent”", D0 + 10 * H + 5 * MIN)); // about nothing: stays a message
        List<ThreadRows.Row> rr = ThreadRows.build(r, l2, "k", false, 0, now, ENV);
        eq("[D:Yesterday, r1[{1}, r3{1}, r6]*]", flat(rr), "reactions are drawn on their message, not as messages; one per person");
        eq(SmsReactions.LIKE, rr.get(2).reactions.get(0).emoji, "the later reaction of the same person replaced the earlier");
        eq(SmsReactions.HEART, rr.get(1).reactions.get(0).emoji, "a heart");
        r.add(msg("r7", false, "Removed a like from “Hello there”", D0 + 10 * H + 6 * MIN));
        eq("[D:Yesterday, r1[{1}, r3, r6]*]", flat(ThreadRows.build(r, l2, "k", false, 0, now, ENV)), "taken back");
        r.add(msg("r8", false, "Removed a heart from “See you at 7”", D0 + 10 * H + 7 * MIN));
        eq("[D:Yesterday, r1[, r3, r6]*]", flat(ThreadRows.build(r, l2, "k", false, 0, now, ENV)), "both taken back");

        randomRows();
    }

    static void randomRows() throws IOException {
        Random rnd = new Random(2026);
        long now = D0 + 3 * DAY + 5 * H;
        String[] bodies = {"hi", "ok", "Loved “hi”", "Liked “ok”", "see you", "Removed a heart from “hi”", "", "ok"};
        for (int round = 0; round < 1500; round++) {
            TextsLocal l = new TextsLocal(new Mem());
            int n = rnd.nextInt(25);
            List<Sms.Msg> ms = new ArrayList<>();
            long t = D0 + rnd.nextInt(5) * H;
            boolean group = rnd.nextBoolean();
            for (int i = 0; i < n; i++) {
                t += rnd.nextInt(4) == 0 ? rnd.nextInt(30) * H : rnd.nextInt(8) * MIN;
                Sms.Msg m = msg("x" + i, rnd.nextBoolean(), bodies[rnd.nextInt(bodies.length)], t);
                if (!m.mine) {
                    m.address = rnd.nextBoolean() ? "+1555A" : "+1555B";
                }
                if (rnd.nextInt(10) == 0) {
                    l.hide(Arrays.asList(m.id));
                }
                ms.add(m);
            }
            long seen = rnd.nextBoolean() ? 0 : D0 + rnd.nextInt(60) * H;
            List<ThreadRows.Row> rows = ThreadRows.build(ms, l, "k", group, seen, now, ENV);
            int dividers = 0;
            long prevDay = Long.MIN_VALUE;
            String prevKind = "";
            Sms.Msg prevMsg = null;
            boolean prevEnd = true;
            Set<String> shown = new HashSet<>();
            int lastMine = 0;
            for (int i = 0; i < rows.size(); i++) {
                ThreadRows.Row r = rows.get(i);
                if (r.kind == ThreadRows.NEW_MESSAGES) {
                    dividers++;
                    yes(i > 0 && i + 1 < rows.size() && rows.get(i + 1).kind == ThreadRows.MSG && !rows.get(i + 1).msg.mine && rows.get(i + 1).msg.time > seen, "the line stands above a new incoming message");
                    continue;
                }
                if (r.kind == ThreadRows.DAY) {
                    yes(i + 1 < rows.size() && (rows.get(i + 1).kind == ThreadRows.MSG
                            || (rows.get(i + 1).kind == ThreadRows.NEW_MESSAGES && i + 2 < rows.size() && rows.get(i + 2).kind == ThreadRows.MSG)),
                            "a heading is followed by a message (or by the new-messages line and then a message)");
                    yes(!(i > 0 && rows.get(i - 1).kind == ThreadRows.DAY), "no two headings in a row");
                    prevDay = Long.MIN_VALUE + 1;
                    continue;
                }
                yes(shown.add(r.msg.id), "a message is shown once: " + r.msg.id);
                yes(!l.isHidden(r.msg.id), "a hidden message is not shown");
                if (r.lastOfMine) {
                    lastMine++;
                    yes(r.msg.mine, "only my own message can be the last I sent");
                }
                long day = TextTime.dayNumber(r.msg.time, ENV.zone);
                int before = i - 1;
                while (before >= 0 && rows.get(before).kind == ThreadRows.NEW_MESSAGES) {
                    before--;
                }
                yes(prevMsg == null || TextTime.dayNumber(prevMsg.time, ENV.zone) == day || (before >= 0 && rows.get(before).kind == ThreadRows.DAY), "a new day has a heading");
                yes(r.groupStart == (prevMsg == null || prevEnd), "a group starts exactly after a group ended: " + r.msg.id);
                prevEnd = r.groupEnd;
                prevMsg = r.msg;
            }
            yes(dividers <= 1, "at most one line");
            yes(rows.isEmpty() || prevEnd, "the last message ends its group");
            int messages = 0;
            for (ThreadRows.Row r : rows) {
                if (r.kind == ThreadRows.MSG) {
                    messages++;
                }
            }
            yes(lastMine == (anyMine(rows) ? 1 : 0), "exactly one last message of mine when I wrote any");
            yes(messages <= n, "no message appears from nowhere");
        }
    }

    static boolean anyMine(List<ThreadRows.Row> rows) {
        for (ThreadRows.Row r : rows) {
            if (r.kind == ThreadRows.MSG && r.msg.mine) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the files

    static void files() throws IOException {
        File dir = Files.createTempDirectory("blobs").toFile();
        try {
            FileBlobStore s = new FileBlobStore(new File(dir, "inner"));
            yes(s.read("nothing") == null, "a blob that is not there reads as null");
            s.write("b.json", "one".getBytes(StandardCharsets.UTF_8));
            s.write("a.json", "two".getBytes(StandardCharsets.UTF_8));
            eq("one", new String(s.read("b.json"), StandardCharsets.UTF_8), "written and read back");
            s.write("b.json", "three".getBytes(StandardCharsets.UTF_8));
            eq("three", new String(s.read("b.json"), StandardCharsets.UTF_8), "overwritten");
            eq("[a.json, b.json]", s.names(), "the names, sorted");
            File leftover = new File(new File(dir, "inner"), "c.json.tmp");
            Files.write(leftover.toPath(), new byte[] {1});
            eq("[a.json, b.json]", s.names(), "a half-written file is not a blob");
            s.delete("a.json");
            s.delete("a.json");
            eq("[b.json]", s.names(), "deleted, and deleting twice is no failure");
            byte[] big = new byte[1_500_000];
            new Random(5).nextBytes(big);
            s.write("big", big);
            yes(Arrays.equals(big, s.read("big")), "a big blob comes back whole");
            s.write("e", new byte[0]);
            eq(0, s.read("e").length, "an empty blob is not a missing one");
            for (String bad : new String[] {"../x", ".hidden", "a/b", "", "x y", "q.tmp", rep('n', 101)}) {
                boolean threw = false;
                try {
                    s.write(bad, new byte[] {1});
                } catch (IOException e) {
                    threw = true;
                }
                yes(threw, "not a name for a blob: '" + bad + "'");
            }
            eq("[b.json, big, e]", s.names(), "nothing was written under a bad name");
            // the local state and the saved copy work on real files
            TextsLocal l = new TextsLocal(s);
            l.setPinned("k", true, 5);
            yes(new TextsLocal(new FileBlobStore(new File(dir, "inner"))).isPinned("k"), "the choices survive a restart on real files");
        } finally {
            deleteTree(dir);
        }
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }

    // ------------------------------------------------------------------ a page from the phone joins what is on screen

    static String ids(List<Sms.Msg> l) {
        StringBuilder sb = new StringBuilder();
        for (Sms.Msg m : l) {
            sb.append(sb.length() > 0 ? "," : "").append(m.id);
        }
        return sb.toString();
    }

    static List<Sms.Msg> seq(int from, int to) {
        List<Sms.Msg> l = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            l.add(msg("s" + i, i % 2 == 0, "m" + i, 1000 + i * 10L));
        }
        return l;
    }

    static void merges() throws IOException {
        eq("s1,s2,s3,s4,s5,s6", ids(ThreadMerge.merge(seq(1, 4), seq(3, 6), true)), "a newer page: the new ones are added, the shared ones stay once");
        eq("s1,s2,s3", ids(ThreadMerge.merge(seq(1, 5), seq(1, 3), true)), "a page that no longer has them: what the phone dropped goes from the screen");
        eq("s1,s2,s3,s4,s5,s6", ids(ThreadMerge.merge(seq(4, 6), seq(1, 3), false)), "an older page goes before what is there");
        eq("s1,s2,s3,s4,s5,s6", ids(ThreadMerge.merge(seq(1, 6), seq(2, 4), false)), "an older page that overlaps adds nothing twice");
        eq("", ids(ThreadMerge.merge(seq(1, 3), new ArrayList<Sms.Msg>(), true)), "an empty newest page: the conversation is empty");
        eq("s1,s2,s3", ids(ThreadMerge.merge(seq(1, 3), new ArrayList<Sms.Msg>(), false)), "an empty older page: nothing changes");
        List<Sms.Msg> have = seq(1, 3);
        Sms.Msg changed = msg("s3", true, "edited state", 1030);
        changed.state = Sms.STATE_DELIVERED;
        List<Sms.Msg> page = new ArrayList<>();
        page.add(changed);
        List<Sms.Msg> merged = ThreadMerge.merge(have, page, true);
        eq("s1,s2,s3", ids(merged), "the same id keeps its place");
        eq(Sms.STATE_DELIVERED, merged.get(2).state, "and takes the phone's newer state");
        // the undo of a delete
        TextsLocal l = new TextsLocal(new Mem());
        l.setPinned("k", true, 1);
        long before = l.clear("k", 500);
        eq(0, before, "it was not deleted before");
        yes(l.isCleared("k", 400), "deleted");
        l.setCleared("k", before);
        yes(!l.isCleared("k", 400), "undone");
        l.clear("k", 300);
        long again = l.clear("k", 500);
        eq(300, again, "the time before is given back");
        l.setCleared("k", again);
        yes(l.isCleared("k", 300) && !l.isCleared("k", 400), "undone to the time before");
        l.hide(Arrays.asList("s1", "s2"));
        l.unhide(Arrays.asList("s1"));
        yes(!l.isHidden("s1") && l.isHidden("s2"), "unhidden");
    }

    // ------------------------------------------------------------------ sealed storage and the lock

    static javax.crypto.SecretKey key(int seed) {
        byte[] k = new byte[32];
        new Random(seed).nextBytes(k);
        return new javax.crypto.spec.SecretKeySpec(k, "AES");
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    static String hexOf(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }

    static boolean throwsIo(Callable c) {
        try {
            c.call();
            return false;
        } catch (IOException e) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    interface Callable {
        void call() throws Exception;
    }

    static void sealed() throws Exception {
        // the standard test vector for AES-GCM (NIST test case 4: key, 96-bit IV, 20 bytes of AAD, 60 bytes of text): what we would
        // store for it is IV, ciphertext, tag, and we open it
        byte[] kat = hex("cafebabefacedbaddecaf888"
                + "42831ec2217774244b7221b784d0d49ce3aa212f2c02a4e035c17e2329aca12e21d514b25466931c7d8f6a5aac84aa051ba30b396a0aac973d58e091"
                + "5bc94fbc3221a5db94fae95ae7121a47");
        byte[] katKey = hex("feffe9928665731c6d6a8f9467308308");
        AesGcmSealer std = new AesGcmSealer(new javax.crypto.spec.SecretKeySpec(katKey, "AES"));
        byte[] katAad = hex("feedfacedeadbeeffeedfacedeadbeefabaddad2");
        eq("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39",
                hexOf(std.open(kat, katAad)), "AES-GCM known answer");
        yes(throwsIo(() -> std.open(kat, new byte[0])), "the same bytes with other AAD do not open");
        // what seal() writes is IV, ciphertext, tag, in that order: the platform cipher opens it by itself
        byte[] mine = std.seal("hello".getBytes(StandardCharsets.UTF_8), katAad);
        eq(12 + 5 + 16, mine.length, "IV + text + tag");
        javax.crypto.Cipher plainCipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        plainCipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(katKey, "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, Arrays.copyOfRange(mine, 0, 12)));
        plainCipher.updateAAD(katAad);
        eq("hello", new String(plainCipher.doFinal(mine, 12, mine.length - 12), StandardCharsets.UTF_8), "the layout is IV, ciphertext, tag");

        Mem mem = new Mem();
        SealedBlobStore s = new SealedBlobStore(mem, new AesGcmSealer(key(1)));
        for (int n : new int[] {0, 1, 100, 100_000}) {
            byte[] data = new byte[n];
            new Random(n).nextBytes(data);
            s.write("b", data);
            yes(Arrays.equals(data, s.read("b")), "a sealed blob of " + n + " bytes reads back");
        }
        byte[] secret = "dinner at seven with Alex, code 482913".getBytes(StandardCharsets.UTF_8);
        s.write("note.json", secret);
        byte[] stored = mem.m.get("note.json");
        yes(stored[0] == SealedBlobStore.MAGIC && stored[1] == SealedBlobStore.VERSION, "stored with the sealed header");
        yes(!new String(stored, StandardCharsets.ISO_8859_1).contains("Alex") && !new String(stored, StandardCharsets.ISO_8859_1).contains("482913"),
                "nothing readable is stored");
        s.write("again.json", secret);
        yes(!Arrays.equals(mem.m.get("note.json"), mem.m.get("again.json")), "the same text is sealed differently each time (a new IV)");

        // damage anywhere is noticed
        for (int pos : new int[] {2, 5, 13, 14, stored.length - 1, stored.length - 8}) {
            byte[] bad = stored.clone();
            bad[pos] ^= 0x01;
            mem.m.put("bad", bad);
            yes(throwsIo(() -> s.read("bad")), "a changed byte at " + pos + " is noticed");
        }
        byte[] cut = Arrays.copyOf(stored, stored.length - 3);
        mem.m.put("cut", cut);
        yes(throwsIo(() -> s.read("cut")), "a cut blob is noticed");
        mem.m.put("junk", new byte[] {1, 2, 3, 4, 5});
        yes(throwsIo(() -> s.read("junk")), "something that is neither sealed nor JSON is refused");
        // bound to its name
        mem.m.put("other.json", stored.clone());
        yes(throwsIo(() -> s.read("other.json")), "a blob copied under another name does not open");
        // another key
        SealedBlobStore wrong = new SealedBlobStore(mem, new AesGcmSealer(key(2)));
        yes(throwsIo(() -> wrong.read("note.json")), "another key does not open it");
        // before sealing existed: plain JSON is read, and the next write seals it
        mem.m.put("old.json", "{\"pinned\":{\"k\":5}}".getBytes(StandardCharsets.UTF_8));
        eq("{\"pinned\":{\"k\":5}}", new String(s.read("old.json"), StandardCharsets.UTF_8), "plain JSON from before is still read");
        s.write("old.json", "{}".getBytes(StandardCharsets.UTF_8));
        yes(mem.m.get("old.json")[0] == SealedBlobStore.MAGIC, "and is sealed by the next write");
        yes(s.read("nothing") == null, "no blob, null");

        // the tablet's choices and saved copy on top of it
        SealedBlobStore store = new SealedBlobStore(new Mem(), new AesGcmSealer(key(3)));
        TextsLocal l = new TextsLocal(store);
        l.setPinned("k", true, 7);
        l.setDraft("k", "secret draft");
        l.flush();
        yes(new TextsLocal(store).isPinned("k") && new TextsLocal(store).draft("k").equals("secret draft"), "the choices survive a restart through the seal");
        TextsCache cache = new TextsCache(store, () -> 5L, 5, 5);
        List<Sms.Thread> ts = new ArrayList<>();
        ts.add(thread("5551", "Alex", "hello", 10, true, 4));
        cache.saveThreads(ts);
        eq("Alex", cache.loadThreads().value.get(0).name, "the saved copy survives it too");
        // a key that is gone (a restored backup, a new tablet): the choices and the copy start empty, nothing crashes
        SealedBlobStore lost = new SealedBlobStore(((SealedBlobStore) store).inner(), new AesGcmSealer(key(9)));
        yes(!new TextsLocal(lost).isPinned("k"), "without the key the choices start empty");
        yes(new TextsCache(lost, () -> 5L, 5, 5).loadThreads() == null, "and so does the saved copy");
    }

    static void lock() {
        TextsLock k = new TextsLock();
        yes(k.needsUnlock(true, true, 1000), "the first time it asks");
        yes(k.needsUnlock(true, true, 1001), "and keeps asking until it is unlocked");
        yes(!k.needsUnlock(false, true, 1002), "never when the lock is off");
        yes(!k.needsUnlock(true, false, 1002), "never when the tablet has no screen lock to ask for");
        k.unlocked(2000);
        yes(!k.needsUnlock(true, true, 2001), "unlocked: it does not ask");
        yes(!k.needsUnlock(true, true, 900_000), "and does not ask while the tab stays on screen, however long");
        k.left(10_000);
        yes(!k.needsUnlock(true, true, 10_000 + TextsLock.GRACE_MS), "back within the short while: still unlocked");
        k.left(20_000);
        k.left(30_000); // leaving twice does not move the time
        yes(k.needsUnlock(true, true, 20_000 + TextsLock.GRACE_MS + 1), "away for longer: it asks again");
        k.unlocked(100_000);
        k.relock();
        yes(k.needsUnlock(true, true, 100_001), "relocked at once");
        k.unlocked(200_000);
        k.left(200_100);
        yes(!k.needsUnlock(true, true, 200_200), "a quick look at another tab does not ask");
        k.left(200_300);
        yes(k.needsUnlock(true, true, 200_300 + TextsLock.GRACE_MS + 5), "but a long one does");
    }

    // ------------------------------------------------------------------ one-time codes

    static void codes() {
        String[][] yes = {
            {"Your verification code is 482913. It expires in 10 minutes.", "482913"},
            {"G-583920 is your Google verification code.", "583920"},
            {"Use 123456 as your Uber code", "123456"},
            {"Your Amazon OTP is 4821. Do not share it with anyone.", "4821"},
            {"Code: 77 and then 908172", "908172"},
            {"Your code is 123-456", "123456"},
            {"Your code is 123 456 and it expires soon", "123456"},
            {"<#> 9081 is your Facebook confirmation code", "9081"},
            {"Your one-time password: 55120934", "55120934"},
            {"Security code 8841", "8841"},
            {"YOUR LOGIN PIN IS 6602.", "6602"},
            {"Your PIN is 1357", "1357"},
            {"Please confirm your sign in with 246810", "246810"},
            {"Your Amazon OTP is 4821. Order 1234567 ships soon", "4821"},
            {"Use the password 4711, valid for 5 min", "4711"},
            {"Order 1234567 shipped. Use 4821 as your OTP", "4821"},
        };
        for (String[] c : yes) {
            eq(c[1], OtpCodes.find(c[0]), "a code is found in: " + c[0]);
        }
        String[] none = {
            "Your Verizon Wireless bill is ready. Pay at vzw.com/pay",
            "Let me know if 7 still works for you?",
            "Reminder: your appointment is on Thursday at 3:30 PM. Reply C to confirm.",
            "Call me on 555-1234 about the code",
            "Your verification code is +15551234567",
            "The code is 1234567890123",
            "Your code is $4821 this month",
            "Your code is 4821% off",
            "verification 12:30",
            "We are pinned down at 4821 Main Street",
            "My pin 3.1415 is not it",
            "Lunch at 12? Order number 482913",
            "482913",
            "",
            "your code is 12",
            "the codes are here: 123",
            "Use code SAVE20 for 20% off",
            "Your code +4821",
            "Your screen code is 1080p",
            "Your verification code is ready but nothing follows this long sentence which goes on and on to be far from any number 482913",
        };
        for (String c : none) {
            eq("", OtpCodes.find(c), "no code in: " + c);
        }
        yes(OtpCodes.find(null).isEmpty(), "no text, no code");
        long now = 1_000_000_000L;
        String body = "Your verification code is 482913.";
        eq("482913", OtpCodes.forList(body, true, now - 5 * 60_000L, now), "an unread fresh text offers its code in the list");
        eq("", OtpCodes.forList(body, false, now - 5 * 60_000L, now), "a text that was read does not");
        eq("", OtpCodes.forList(body, true, now - OtpCodes.RECENT_MS - 1, now), "an old one does not");
        eq("482913", OtpCodes.forList(body, true, now - OtpCodes.RECENT_MS, now), "exactly at the limit it still does");
        eq("", OtpCodes.forList(body, true, now + 60_000L, now), "a text from the future (a clock that moved) does not");
        eq("482913", OtpCodes.forBubble(body, false, now - 3 * 60 * 60_000L, now), "an incoming text of today offers its code in a conversation");
        eq("", OtpCodes.forBubble(body, true, now - 60_000L, now), "never for a text I sent");
        eq("", OtpCodes.forBubble(body, false, now - OtpCodes.BUBBLE_MS - 1, now), "nor for one older than a day");
    }

    // ------------------------------------------------------------------ the contact book and the request log

    static void contactBook() {
        ContactBook b = new ContactBook();
        b.add("+1 (555) 123-4567", "Alex Rivera");
        b.add("07911 123456", "Sam UK");
        b.add("VERIZON", "Verizon Wireless");
        b.add("611", "Customer care");
        b.add("5551234567", "Someone else"); // the same number: the first name stays
        b.add("", "No number");
        b.add("555-0000", "");
        b.add(null, "Nobody");
        b.add("12", "Too short to be a number");
        eq("Alex Rivera", b.nameFor("5551234567"), "a number written plainly finds the contact");
        eq("Alex Rivera", b.nameFor("+15551234567"), "with the country code");
        eq("Alex Rivera", b.nameFor("555-123-4567"), "with dashes");
        eq("Alex Rivera", b.nameFor(" (555) 123 4567 "), "with spaces and brackets");
        eq("Sam UK", b.nameFor("+44 7911 123456"), "a foreign number written with its country code is the one written with a 0");
        eq("Verizon Wireless", b.nameFor("verizon"), "a sender name, in any case");
        eq("Verizon Wireless", b.nameFor("VERIZON"), "a sender name in capitals");
        eq("Customer care", b.nameFor("611"), "a short number");
        eq("", b.nameFor("5551234568"), "another number has no name");
        eq("", b.nameFor(null), "no address, no name");
        eq("", b.nameFor(""), "an empty address has no name");
        eq("", b.nameFor("12"), "two digits are not a number");
        eq(4, b.size(), "four numbers are known");
        ContactBook same = new ContactBook();
        same.add("5551234567", "Alex Rivera");
        same.add("7911123456", "Sam UK");
        same.add("verizon", "Verizon Wireless");
        same.add("611", "Customer care");
        yes(b.equals(same) && b.hashCode() == same.hashCode(), "the same numbers and names are the same book, however they were written");
        ContactBook renamed = new ContactBook();
        renamed.add("5551234567", "Alex R.");
        renamed.add("7911123456", "Sam UK");
        renamed.add("verizon", "Verizon Wireless");
        renamed.add("611", "Customer care");
        yes(!b.equals(renamed), "a changed name makes another book");
        yes(!b.equals(new ContactBook()), "an empty book is another book");
        eq("5551234567", ContactBook.matchKey("+1 555 123 4567"), "match key: the last ten digits");
        eq("verizon", ContactBook.matchKey(" Verizon "), "match key: a sender name in lower case");
        eq("", ContactBook.matchKey("+"), "match key: no digits");
    }

    static void linkLog() throws Exception {
        LinkLog l = new LinkLog(3);
        eq("Nothing was asked of the phone yet.", l.summary(1000), "an empty log says so");
        l.add(1000, "GET /sms/threads", 1800, "ok");
        l.add(2000, "GET /sms/thread", 31000, "timed out waiting for the phone");
        l.add(3000, "POST /sms/send", 250, "ok");
        l.add(4000, "GET /sms/state", 90, "ok");
        eq(3, l.newestFirst().size(), "the log keeps only its capacity");
        eq("GET /sms/state", l.newestFirst().get(0).what, "newest first");
        eq("GET /sms/thread", l.newestFirst().get(2).what, "the oldest kept is the third from the end (the first was dropped)");
        String s = l.summary(5000);
        yes(s.startsWith("3 requests, 1 did not work"), "the summary counts what did not work: " + s);
        yes(s.contains("Slowest that worked: POST /sms/send · 250 ms · ok · 2 s ago"), "and names the slowest that worked: " + s);
        yes(s.contains("GET /sms/thread · 31.0 s · timed out waiting for the phone · 3 s ago"), "and lists the requests: " + s);
        l.clear();
        eq(0, l.newestFirst().size(), "cleared");
        eq("/sms/thread", LinkLog.route("/sms/thread?key=5551234567&limit=40"), "the question part (numbers, search words) is never kept");
        eq("/sms/stamp", LinkLog.route("/sms/stamp"), "a route without a question stays");
        eq("", LinkLog.route(null), "no route, no text");
        eq("850 ms", LinkLog.seconds(850), "milliseconds");
        eq("1.0 s", LinkLog.seconds(1000), "one second");
        eq("31.4 s", LinkLog.seconds(31400), "seconds with a tenth");
        eq("timed out waiting for the phone", LinkLog.outcome(new java.net.SocketTimeoutException("Read timed out")), "a read timeout");
        eq("no connection in time", LinkLog.outcome(new Client.ConnectFailed(new java.net.SocketTimeoutException("connect timed out"))), "a connect timeout");
        eq("no connection", LinkLog.outcome(new Client.ConnectFailed(new java.net.ConnectException("refused"))), "a refused connection");
        eq("texts are off", LinkLog.outcome(new RemoteSms.Off()), "texts switched off");
        eq("failed", LinkLog.outcome(new IOException("x")), "anything else");
        yes(LinkLog.line(new LinkLog.Entry(0, "GET /x", 5, "ok"), 89_000).endsWith("89 s ago"), "89 seconds is still said in seconds");
        yes(LinkLog.line(new LinkLog.Entry(0, "GET /x", 5, "ok"), 90_000).endsWith("1 min ago"), "90 seconds is a minute");
        yes(LinkLog.line(new LinkLog.Entry(0, "GET /x", 5, "ok"), 600_000).endsWith("10 min ago"), "ten minutes in minutes");

        // the wrapper: it times what the link did, never keeps the question part, and keeps quiet about a waiting request that worked
        final long[] clock = {0};
        RemoteStore.Link fake = new RemoteStore.Link() {
            @Override
            public Client.Resp call(String method, String target, int readMs) throws IOException {
                clock[0] += 120;
                if (target.contains("slow")) {
                    throw new java.net.SocketTimeoutException("Read timed out");
                }
                Client.Resp r = new Client.Resp();
                r.code = target.contains("bad") ? 500 : 200;
                return r;
            }

            @Override
            public Client.Stream get(String target, long offset) throws IOException {
                throw new IOException("no streams here");
            }

            @Override
            public Client.Resp put(String target, java.io.InputStream src, long len, Client.Progress p) throws IOException {
                throw new IOException("no uploads here");
            }
        };
        LinkLog log = new LinkLog(10);
        TimedLink t = new TimedLink(fake, log, () -> clock[0]);
        t.call("GET", "/sms/thread?key=SECRETNUMBER&limit=40", 1000);
        eq("GET /sms/thread", log.newestFirst().get(0).what, "the wrapper keeps the route");
        eq(120L, log.newestFirst().get(0).ms, "and what it cost");
        eq("ok", log.newestFirst().get(0).result, "and that it worked");
        yes(!log.summary(0).contains("SECRETNUMBER"), "never what was asked about");
        t.call("GET", "/sms/stamp?since=w5&wait=20", 1000);
        eq(1, log.newestFirst().size(), "a waiting request that worked is not written down (it is meant to be long)");
        t.call("GET", "/sms/bad", 1000);
        eq("answered 500", log.newestFirst().get(0).result, "an error answer is written down");
        try {
            t.call("GET", "/sms/slow?q=zzz", 1000);
            yes(false, "the timeout reaches the caller");
        } catch (java.net.SocketTimeoutException expected) {
            eq("timed out waiting for the phone", log.newestFirst().get(0).result, "a timeout is written down and passed on");
        }
        try {
            t.call("GET", "/sms/stamp?since=slow", 1000);
        } catch (java.net.SocketTimeoutException expected) {
            eq("GET /sms/stamp", log.newestFirst().get(0).what, "a waiting request that failed is written down");
        }
        try {
            t.put("/sms/attach?name=photo.jpg", null, 0, null);
        } catch (IOException expected) {
            eq("PUT /sms/attach", log.newestFirst().get(0).what, "an upload that failed is written down without its name");
        }
    }

    static void emoji() {
        eq(1, EmojiText.count("👍"), "one thumbs up");
        eq(1, EmojiText.count("👍🏽"), "a thumbs up with a skin tone is one");
        eq(1, EmojiText.count("❤️"), "a heart with its variation mark is one");
        eq(1, EmojiText.count("❤"), "a bare heart is one");
        eq(2, EmojiText.count("😂😂"), "two faces");
        eq(3, EmojiText.count("😂 😂 😂"), "three faces with spaces");
        eq(1, EmojiText.count("👨‍👩‍👧"), "a family joined with joiners is one picture");
        eq(1, EmojiText.count("🇺🇸".substring(0, 2)) , "a flag half is still an emoji mark");
        eq(0, EmojiText.count("ok 👍"), "a word and an emoji is not only emoji");
        eq(0, EmojiText.count("👍!"), "a mark and an emoji is not only emoji");
        eq(0, EmojiText.count("1"), "a digit is not an emoji");
        eq(0, EmojiText.count("haha"), "a word is not");
        eq(0, EmojiText.count(""), "nothing is nothing");
        eq(0, EmojiText.count("   "), "spaces are nothing");
        eq(0, EmojiText.count(null), "null is nothing");
        eq(0, EmojiText.count("\uFE0F"), "a lone variation mark is not an emoji");
        yes(EmojiText.only("😂", 3) && EmojiText.only("😂😂😂", 3), "up to three are big");
        yes(!EmojiText.only("😂😂😂😂", 3), "four are small again");
        yes(!EmojiText.only("hi", 3), "words are not");
        eq(6, EmojiText.QUICK.length, "six quick replies");
        for (String q : EmojiText.QUICK) {
            eq(1, EmojiText.count(q), "each quick reply is exactly one emoji: " + q);
        }
    }

    public static void main(String[] args) throws Exception {
        emoji();
        contactBook();
        linkLog();
        codes();
        sealed();
        lock();
        merges();
        files();
        lengths();
        reactions();
        smart();
        local();
        cache();
        listRules();
        rows();
        System.out.println(checks + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
