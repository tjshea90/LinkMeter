package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Tests TypingDraft, what the remote keyboard sends the phone for what the tablet's keyboard (tapped letters, glide typing,
 * corrections, suggestions) does. A pretend phone applies exactly what is sent (typed text, Backspace, Delete, Enter) and its text
 * must then equal what the keyboard meant, after every step. Not part of the app.
 */
public final class TypingDraftTest {
    private static int checks;
    private static int fails;

    private TypingDraftTest() {}

    private static void eq(Object want, Object got, String what) {
        checks++;
        if (!String.valueOf(want).equals(String.valueOf(got))) {
            fails++;
            if (fails <= 30) {
                System.out.println("FAIL " + what + ": wanted '" + want + "' but got '" + got + "'");
            }
        }
    }

    private static void yes(boolean ok, String what) {
        checks++;
        if (!ok) {
            fails++;
            if (fails <= 30) {
                System.out.println("FAIL " + what);
            }
        }
    }

    /** The phone: the text in its field (the cursor stays at the end), and every operation that reached it, in order. */
    private static final class Phone implements TypingDraft.Sink {
        final StringBuilder text = new StringBuilder();
        final List<String> ops = new ArrayList<>();

        @Override
        public void text(String s) {
            ops.add("t:" + s);
            for (int i = 0; i < s.length(); i++) {
                text.append(s.charAt(i));
            }
        }

        @Override
        public void key(int code) {
            if (code == TypingDraft.DEL) {
                ops.add("DEL");
                if (text.length() > 0) {
                    text.setLength(text.length() - 1);
                }
            } else if (code == TypingDraft.ENTER) {
                ops.add("ENTER");
                text.append('\n');
            } else {
                ops.add("key" + code); // the Delete key: nothing after the cursor to delete
            }
        }

        void clearOps() {
            ops.clear();
        }
    }

    private static TypingDraft draft(Phone p) {
        return new TypingDraft(p);
    }

    public static void main(String[] args) {
        // ---- a word tapped letter by letter: the draft grows, only the new letter is sent
        {
            Phone p = new Phone();
            TypingDraft d = draft(p);
            String[] steps = {"h", "he", "hel", "hell", "hello"};
            String[] want = {"t:h", "t:e", "t:l", "t:l", "t:o"};
            for (int i = 0; i < steps.length; i++) {
                p.clearOps();
                d.setComposing(steps[i]);
                eq("[" + want[i] + "]", p.ops, "a growing draft sends only the new letter (" + steps[i] + ")");
            }
            p.clearOps();
            d.commit("hello ");
            eq("[t: ]", p.ops, "the space after the word is the only thing sent at its end");
            eq("hello ", p.text, "the word and its space are on the phone");
        }

        // ---- a glide: the whole word at once, then the space
        {
            Phone p = new Phone();
            TypingDraft d = draft(p);
            d.setComposing("hello");
            eq("[t:hello]", p.ops, "a glide sends the word once");
            d.commit("hello ");
            d.setComposing("world");
            d.commit("world ");
            eq("hello world ", p.text, "two glided words");
            eq("[t:hello, t: , t:world, t: ]", p.ops, "and nothing was deleted and typed again");
        }

        // ---- a glide that the keyboard commits directly
        {
            Phone p = new Phone();
            TypingDraft d = draft(p);
            d.commit("hello ");
            d.commit("world ");
            eq("hello world ", p.text, "words committed without a draft");
            eq("[t:hello , t:world ]", p.ops, "sent as they are");
        }

        // ---- a correction: the keyboard replaces the draft when the word is finished ("teh" becomes "the")
        {
            Phone p = new Phone();
            TypingDraft d = draft(p);
            d.setComposing("t");
            d.setComposing("te");
            d.setComposing("teh");
            p.clearOps();
            d.commit("the ");
            eq("[DEL, DEL, t:he ]", p.ops, "only what differs is deleted and typed again");
            eq("the ", p.text, "the corrected word is on the phone");
        }

        // ---- a tapped suggestion that completes the word
        {
            Phone p = new Phone();
            TypingDraft d = draft(p);
            d.setComposing("hel");
            p.clearOps();
            d.commit("hello ");
            eq("[t:lo ]", p.ops, "a suggestion that continues the draft only adds the rest");
            eq("hello ", p.text, "the suggestion is on the phone");
        }

        // ---- a suggestion that differs from the start
        {
            Phone p = new Phone();
            TypingDraft d = draft(p);
            d.setComposing("cat");
            d.commit("dog ");
            eq("dog ", p.text, "a different word replaces the draft");
        }

        // ---- Backspace inside the draft
        {
            Phone p = new Phone();
            TypingDraft d = draft(p);
            d.setComposing("hello");
            p.clearOps();
            d.setComposing("hell");
            eq("[DEL]", p.ops, "one letter less is one Backspace");
            d.setComposing("hel");
            d.setComposing("");
            eq("", p.text, "the whole draft deleted");
        }

        // ---- an empty commit deletes the draft (that is what committing nothing over it means)
        {
            Phone p = new Phone();
            TypingDraft d = draft(p);
            d.setComposing("abc");
            d.commit("");
            eq("", p.text, "an empty commit removes the draft");
            d.setComposing("x");
            eq("x", p.text, "and the next word starts clean");
        }

        // ---- finishing keeps the text, and the next draft is a new word
        {
            Phone p = new Phone();
            TypingDraft d = draft(p);
            d.setComposing("abc");
            d.finish();
            d.setComposing("de");
            eq("abcde", p.text, "text that was finished stays when the next draft starts");
            p.clearOps();
            d.setComposing("d");
            eq("[DEL]", p.ops, "and only the new draft is edited");
        }

        // ---- a draft is not confused with what was committed before it
        {
            Phone p = new Phone();
            TypingDraft d = draft(p);
            d.commit("one ");
            d.setComposing("tw");
            d.setComposing("two");
            d.commit("two ");
            d.setComposing("x");
            d.setComposing("");
            eq("one two ", p.text, "committed words are never touched by a later draft");
        }

        // ---- Enter: as a line break in a commit, with text before and after it, as the key, and as the keyboard's action button
        {
            Phone p = new Phone();
            TypingDraft d = draft(p);
            d.setComposing("ab");
            d.commit("ab\ncd");
            eq("ab\ncd", p.text, "a line break inside a commit");
            eq("[t:ab, ENTER, t:cd]", p.ops, "is the Enter key between the two parts");
            Phone q = new Phone();
            TypingDraft e = draft(q);
            e.setComposing("ab");
            e.enter();
            e.setComposing("c");
            eq("ab\nc", q.text, "Enter finishes the draft: the next word does not edit the old one");
            Phone r = new Phone();
            TypingDraft f = draft(r);
            f.commit("\n");
            eq("[ENTER]", r.ops, "a commit of a line break alone is one Enter");
            f.commit("a\nb\nc");
            eq("\na\nb\nc", r.text, "several line breaks in one commit");
        }

        // ---- the keyboard deletes around the cursor
        {
            Phone p = new Phone();
            TypingDraft d = draft(p);
            d.commit("abc");
            d.delete(1, 0);
            eq("ab", p.text, "one character before the cursor");
            d.setComposing("xyz");
            d.delete(1, 0);
            eq("abxy", p.text, "deleting inside a draft shortens the draft");
            d.setComposing("xyw");
            eq("abxyw", p.text, "and the draft goes on from there ('xy' stays, 'w' is added)");
            p.clearOps();
            d.delete(0, 2);
            eq("[key112, key112]", p.ops, "characters after the cursor are the Delete key");
            d.delete(5, 0);
            d.setComposing("q");
            eq("q", p.text, "deleting more than the draft holds leaves no draft behind");
        }

        // ---- nothing is sent for nothing
        {
            Phone p = new Phone();
            TypingDraft d = draft(p);
            d.setComposing("");
            d.setComposing(null);
            d.commit(null);
            d.finish();
            d.delete(0, 0);
            eq("[]", p.ops, "empty changes send nothing");
            d.setComposing("ab");
            p.clearOps();
            d.setComposing("ab");
            eq("[]", p.ops, "the same draft again sends nothing");
        }

        // ---- a keyboard simulated at random: after EVERY step the phone shows what the keyboard shows (committed text + draft)
        Random rnd = new Random(20261111L);
        String letters = "abcdefghijklmnopqrstuvwxyz";
        int steps = 0;
        for (int round = 0; round < 3000; round++) {
            Phone p = new Phone();
            TypingDraft d = draft(p);
            StringBuilder committed = new StringBuilder();
            String comp = "";
            for (int n = 0; n < 40; n++) {
                int kind = rnd.nextInt(11);
                String what;
                switch (kind) {
                    case 0:
                    case 1:
                    case 2: // a letter is tapped: the draft grows
                        comp = comp + letters.charAt(rnd.nextInt(letters.length()));
                        d.setComposing(comp);
                        what = "tap";
                        break;
                    case 3: { // a glide: the draft becomes a whole word
                        StringBuilder w = new StringBuilder();
                        for (int i = 0, len = 1 + rnd.nextInt(7); i < len; i++) {
                            w.append(letters.charAt(rnd.nextInt(letters.length())));
                        }
                        comp = w.toString();
                        d.setComposing(comp);
                        what = "glide";
                        break;
                    }
                    case 4: // the word is finished with a space
                        committed.append(comp).append(' ');
                        d.commit(comp + " ");
                        comp = "";
                        what = "space";
                        break;
                    case 5: { // autocorrect: the finished word is another word
                        StringBuilder w = new StringBuilder(comp);
                        if (w.length() > 0) {
                            w.setCharAt(rnd.nextInt(w.length()), letters.charAt(rnd.nextInt(letters.length())));
                        }
                        String fixed = w.toString() + (rnd.nextBoolean() ? "s" : "");
                        committed.append(fixed).append(' ');
                        d.commit(fixed + " ");
                        comp = "";
                        what = "autocorrect";
                        break;
                    }
                    case 6: // Backspace inside the draft
                        if (comp.length() > 0) {
                            comp = comp.substring(0, comp.length() - 1);
                            d.setComposing(comp);
                        }
                        what = "backspace in the draft";
                        break;
                    case 7: // Backspace after a finished word (the keyboard has no draft to shorten)
                        if (comp.isEmpty() && committed.length() > 0) {
                            committed.setLength(committed.length() - 1);
                            d.delete(1, 0);
                        }
                        what = "backspace after";
                        break;
                    case 8: // the keyboard stops working on the draft and keeps it
                        committed.append(comp);
                        comp = "";
                        d.finish();
                        what = "finish";
                        break;
                    case 9: // a tapped suggestion: the word is completed (the draft is a prefix, or something else)
                        committed.append(comp).append("ing ");
                        d.commit(comp + "ing ");
                        comp = "";
                        what = "suggestion";
                        break;
                    default: // Enter
                        committed.append(comp).append('\n');
                        d.commit(comp + "\n");
                        comp = "";
                        what = "enter";
                        break;
                }
                steps++;
                eq(committed.toString() + comp, p.text.toString(), "the phone shows what the keyboard shows after " + what + " (round " + round + ", step " + n + ")");
            }
        }
        yes(steps == 3000 * 40, "every random step was checked: " + steps);

        // ---- a long draft is never sent twice
        {
            Phone p = new Phone();
            TypingDraft d = draft(p);
            StringBuilder big = new StringBuilder();
            for (int i = 0; i < 300; i++) {
                big.append((char) ('a' + i % 26));
                d.setComposing(big.toString());
            }
            int typed = 0;
            for (String o : p.ops) {
                yes(o.startsWith("t:") && o.length() == 3, "a growing draft only ever sends one new letter: " + o);
                typed++;
            }
            eq(300, typed, "300 letters, 300 sends");
            eq(big.toString(), p.text, "and the phone has them all");
        }

        System.out.println(checks + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
