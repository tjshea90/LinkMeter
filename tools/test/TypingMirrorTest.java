package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Tests TypingMirror, which keeps the phone's text equal to the text of the tablet's typing field. A pretend phone applies exactly what is
 * sent (typed text, Backspace, Delete, Enter) and its text must equal the keyboard's text after every step: tapped letters, glided
 * words, corrections, suggestions, re-opened words, a moved cursor, a selection that is replaced, pasted text, emoji, line breaks. Not
 * part of the app.
 */
public final class TypingMirrorTest {
    private static int checks;
    private static int fails;

    private TypingMirrorTest() {}

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
    private static final class Phone implements TypingMirror.Sink {
        final StringBuilder text = new StringBuilder();
        final List<String> ops = new ArrayList<>();

        Phone(String seed) {
            text.append(seed);
        }

        @Override
        public void text(String s) {
            yes(!s.isEmpty() && s.indexOf('\n') < 0, "typed text is never empty and never holds a line break: '" + s + "'");
            ops.add("t:" + s);
            text.append(s);
        }

        @Override
        public void key(int code) {
            if (code == TypingMirror.DEL) {
                ops.add("DEL");
                if (text.length() > 0) {
                    text.setLength(text.offsetByCodePoints(text.length(), -1)); // one Backspace removes a whole character
                }
            } else if (code == TypingMirror.ENTER) {
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

    /** The keyboard's side: a text with a selection and a draft (composing region), edited the way keyboards edit. */
    private static final class Field {
        final StringBuilder ed = new StringBuilder();
        int selA;
        int selB;
        int compA = -1;
        int compB = -1;

        int align(int i) {
            i = Math.max(0, Math.min(ed.length(), i));
            if (i > 0 && i < ed.length() && Character.isLowSurrogate(ed.charAt(i)) && Character.isHighSurrogate(ed.charAt(i - 1))) {
                return i - 1;
            }
            return i;
        }

        private void replace(int a, int b, String t, boolean composing) {
            ed.replace(a, b, t);
            selA = a + t.length();
            selB = selA;
            if (composing) {
                compA = a;
                compB = a + t.length();
            } else {
                compA = -1;
                compB = -1;
            }
        }

        String draft() {
            return compA >= 0 ? ed.substring(compA, compB) : "";
        }

        void setComposing(String t) {
            if (compA >= 0) {
                replace(compA, compB, t, true);
            } else {
                replace(selA, selB, t, true);
            }
        }

        void commit(String t) {
            if (compA >= 0) {
                replace(compA, compB, t, false);
            } else {
                replace(selA, selB, t, false);
            }
        }

        void finish() {
            compA = -1;
            compB = -1;
        }

        void deleteAround(int before, int after) {
            int a = selA;
            int b = selB;
            if (compA >= 0) {
                a = Math.min(a, compA);
                b = Math.max(b, compB);
            }
            int end = Math.min(ed.length(), b + after);
            int start = Math.max(0, a - before);
            ed.delete(b, end);
            start = align(start);
            ed.delete(start, a);
            selA = start;
            selB = start;
            compA = -1;
            compB = -1;
        }

        void backspaceKey() {
            if (selA != selB) {
                ed.delete(selA, selB);
                selB = selA;
            } else if (selA > 0) {
                int start = ed.offsetByCodePoints(selA, -1);
                ed.delete(start, selA);
                selA = start;
                selB = start;
            }
            compA = -1;
            compB = -1;
        }

        void select(int a, int b) {
            a = align(a);
            b = align(b);
            selA = Math.min(a, b);
            selB = Math.max(a, b);
        }

        void region(int a, int b) {
            a = align(a);
            b = align(b);
            compA = Math.min(a, b);
            compB = Math.max(a, b);
        }
    }

    public static void main(String[] args) {
        // ---- a word tapped letter by letter: the text grows, only the new letter is sent
        {
            Phone p = new Phone("");
            TypingMirror m = new TypingMirror(p);
            String[] steps = {"h", "he", "hel", "hell", "hello"};
            String[] want = {"t:h", "t:e", "t:l", "t:l", "t:o"};
            for (int i = 0; i < steps.length; i++) {
                p.clearOps();
                m.sync(steps[i]);
                eq("[" + want[i] + "]", p.ops, "a growing text sends only the new letter (" + steps[i] + ")");
            }
            p.clearOps();
            m.sync("hello ");
            eq("[t: ]", p.ops, "the space after the word is the only thing sent at its end");
            eq("hello ", p.text, "the word and its space are on the phone");
        }

        // ---- a glide: the whole word at once, then the space, then the next word
        {
            Phone p = new Phone("");
            TypingMirror m = new TypingMirror(p);
            m.sync("hello");
            eq("[t:hello]", p.ops, "a glide sends the word once");
            m.sync("hello ");
            m.sync("hello world");
            m.sync("hello world ");
            eq("hello world ", p.text, "two glided words");
            eq("[t:hello, t: , t:world, t: ]", p.ops, "and nothing was deleted and typed again");
        }

        // ---- a correction: the finished word replaces the draft ("teh" becomes "the")
        {
            Phone p = new Phone("");
            TypingMirror m = new TypingMirror(p);
            m.sync("t");
            m.sync("te");
            m.sync("teh");
            p.clearOps();
            m.sync("the ");
            eq("[DEL, DEL, t:he ]", p.ops, "only what differs is deleted and typed again");
            eq("the ", p.text, "the corrected word is on the phone");
        }

        // ---- a tapped suggestion that completes the word, and one that replaces it
        {
            Phone p = new Phone("");
            TypingMirror m = new TypingMirror(p);
            m.sync("hel");
            p.clearOps();
            m.sync("hello ");
            eq("[t:lo ]", p.ops, "a suggestion that continues the text only adds the rest");
            m.sync("hello cat");
            m.sync("hello dog ");
            eq("hello dog ", p.text, "a different word replaces the draft");
        }

        // ---- Backspace, and a word that is re-opened after its space was deleted
        {
            Phone p = new Phone("");
            TypingMirror m = new TypingMirror(p);
            m.sync("hello ");
            p.clearOps();
            m.sync("hello");
            eq("[DEL]", p.ops, "one character less is one Backspace");
            p.clearOps();
            m.sync("hallo");
            eq("[DEL, DEL, DEL, DEL, t:allo]", p.ops, "a re-opened word is changed from the first letter that differs");
            eq("hallo", p.text, "and the phone has the new word");
        }

        // ---- the keyboard's cursor is not at the end: the text is still right
        {
            Phone p = new Phone("");
            TypingMirror m = new TypingMirror(p);
            m.sync("hello world");
            p.clearOps();
            m.sync("hello big world");
            eq("hello big world", p.text, "text inserted in the middle");
            eq("[DEL, DEL, DEL, DEL, DEL, t:big world]", p.ops, "the end after the change is typed again");
            m.sync("big world");
            eq("big world", p.text, "text removed at the start");
        }

        // ---- line breaks are the Enter key, in both directions
        {
            Phone p = new Phone("");
            TypingMirror m = new TypingMirror(p);
            m.sync("ab");
            m.sync("ab\ncd");
            eq("[t:ab, ENTER, t:cd]", p.ops, "a line break inside the text is the Enter key between two parts");
            eq("ab\ncd", p.text, "and the phone has two lines");
            p.clearOps();
            m.sync("ab\nc");
            eq("[DEL]", p.ops, "a letter less after the line break");
            p.clearOps();
            m.sync("ab");
            eq("[DEL, DEL]", p.ops, "removing a line break is Backspace");
            Phone q = new Phone("");
            TypingMirror n = new TypingMirror(q);
            n.sync("\n");
            eq("[ENTER]", q.ops, "a line break alone is one Enter");
            n.sync("a\n\nb");
            eq("a\n\nb", q.text, "several line breaks in a row");
        }

        // ---- nothing is sent for nothing
        {
            Phone p = new Phone("seed");
            TypingMirror m = new TypingMirror(p);
            m.sync("");
            m.sync(null);
            eq("[]", p.ops, "an empty field sends nothing");
            m.sync("ab");
            p.clearOps();
            m.sync("ab");
            eq("[]", p.ops, "the same text again sends nothing");
            eq("seedab", p.text, "the phone's own text is kept");
        }

        // ---- emoji (two chars each): one Backspace on the phone removes the whole character
        {
            Phone p = new Phone("");
            TypingMirror m = new TypingMirror(p);
            m.sync("a\uD83D\uDE00");
            p.clearOps();
            m.sync("a\uD83D\uDE01");
            eq("[DEL, t:\uD83D\uDE01]", p.ops, "two emoji that share their first half are never split");
            eq("a\uD83D\uDE01", p.text, "the new emoji is on the phone");
            p.clearOps();
            m.sync("a");
            eq("[DEL]", p.ops, "an emoji is deleted with one Backspace");
        }

        // ---- text from before the field was opened
        {
            Phone p = new Phone("hello");
            TypingMirror m = new TypingMirror(p);
            m.deleteBefore(3);
            eq("[DEL, DEL, DEL]", p.ops, "Backspace beyond the field's text is passed on");
            eq("he", p.text, "and erases the phone's own text");
            p.clearOps();
            m.deleteAfter(2);
            eq("[key112, key112]", p.ops, "Delete beyond the field's text is the Delete key");
        }

        // ---- the field shortens itself, or starts over
        {
            Phone p = new Phone("");
            TypingMirror m = new TypingMirror(p);
            m.sync("abcdef");
            m.forget(3);
            eq(3, m.length(), "three characters are no longer followed");
            p.clearOps();
            m.sync("def!");
            eq("[t:!]", p.ops, "the rest is followed as before");
            eq("abcdef!", p.text, "and the forgotten text stays on the phone");
            m.forget(100);
            eq(0, m.length(), "forgetting more than there is leaves nothing");
            p.clearOps();
            m.sync("x");
            eq("[t:x]", p.ops, "the next text starts clean");
            m.reset();
            p.clearOps();
            m.sync("y");
            eq("[t:y]", p.ops, "after a reset nothing is deleted");
            eq("abcdef!xy", p.text, "everything typed before stays");
        }

        // ---- a long text is never sent twice
        {
            Phone p = new Phone("");
            TypingMirror m = new TypingMirror(p);
            StringBuilder big = new StringBuilder();
            for (int i = 0; i < 300; i++) {
                big.append((char) ('a' + i % 26));
                m.sync(big.toString());
            }
            int typed = 0;
            for (String o : p.ops) {
                yes(o.startsWith("t:") && o.length() == 3, "a growing text only ever sends one new letter: " + o);
                typed++;
            }
            eq(300, typed, "300 letters, 300 sends");
            eq(big.toString(), p.text, "and the phone has them all");
        }

        // ---- a keyboard simulated at random: after EVERY step the phone shows what the keyboard's field shows
        Random rnd = new Random(20261111L);
        String letters = "abcdefghijklmnopqrstuvwxyz";
        String[] emoji = {"\uD83D\uDE00", "\uD83D\uDE01", "\uD83D\uDE02"};
        int steps = 0;
        int trims = 0;
        int resets = 0;
        for (int round = 0; round < 3000; round++) {
            String seed = rnd.nextBoolean() ? "" : "seed text";
            Phone p = new Phone(seed);
            TypingMirror m = new TypingMirror(p);
            Field f = new Field();
            StringBuilder kept = new StringBuilder(); // typed text the field gave up: it stays on the phone, before the field's text
            for (int n = 0; n < 40; n++) {
                int kind = rnd.nextInt(19);
                String what;
                switch (kind) {
                    case 0:
                    case 1:
                    case 2: // a letter is tapped: the draft grows
                        f.setComposing(f.draft() + letters.charAt(rnd.nextInt(letters.length())));
                        what = "tap";
                        break;
                    case 3: { // a glide: the draft becomes a whole word
                        StringBuilder w = new StringBuilder();
                        for (int i = 0, len = 1 + rnd.nextInt(7); i < len; i++) {
                            w.append(rnd.nextInt(12) == 0 ? emoji[rnd.nextInt(emoji.length)] : String.valueOf(letters.charAt(rnd.nextInt(letters.length()))));
                        }
                        f.setComposing(w.toString());
                        what = "glide";
                        break;
                    }
                    case 4: // the word is finished with a space
                        f.commit(f.draft() + " ");
                        what = "space";
                        break;
                    case 5: { // autocorrect: the finished word is another word
                        StringBuilder w = new StringBuilder(f.draft());
                        if (w.length() > 0) {
                            w.setCharAt(rnd.nextInt(w.length()), letters.charAt(rnd.nextInt(letters.length())));
                        }
                        f.commit(w + (rnd.nextBoolean() ? "s" : "") + " ");
                        what = "autocorrect";
                        break;
                    }
                    case 6: // Backspace inside the draft
                        if (f.draft().length() > 0) {
                            String d = f.draft();
                            f.setComposing(d.substring(0, d.offsetByCodePoints(d.length(), -1)));
                        }
                        what = "backspace in the draft";
                        break;
                    case 7: // Backspace as the key
                        f.backspaceKey();
                        what = "backspace key";
                        break;
                    case 8: // the keyboard stops working on the draft and keeps it
                        f.finish();
                        what = "finish";
                        break;
                    case 9: // a tapped suggestion
                        f.commit(f.draft() + "ing ");
                        what = "suggestion";
                        break;
                    case 10: // Enter
                        f.commit(f.draft() + "\n");
                        what = "enter";
                        break;
                    case 11: // the cursor moves
                        f.finish();
                        f.select(rnd.nextInt(f.ed.length() + 1), rnd.nextInt(f.ed.length() + 1));
                        f.selB = f.selA;
                        what = "cursor";
                        break;
                    case 12: // a range is selected
                        f.finish();
                        f.select(rnd.nextInt(f.ed.length() + 1), rnd.nextInt(f.ed.length() + 1));
                        what = "selection";
                        break;
                    case 13: { // the word before the cursor is re-opened
                        int end = f.selB;
                        int start = end;
                        while (start > 0 && f.ed.charAt(start - 1) != ' ' && f.ed.charAt(start - 1) != '\n') {
                            start--;
                        }
                        f.region(start, end);
                        what = "re-open";
                        break;
                    }
                    case 14: { // a paste
                        StringBuilder w = new StringBuilder();
                        for (int i = 0, len = 1 + rnd.nextInt(12); i < len; i++) {
                            int r = rnd.nextInt(20);
                            w.append(r == 0 ? "\n" : r == 1 ? " " : r == 2 ? emoji[rnd.nextInt(emoji.length)] : String.valueOf(letters.charAt(rnd.nextInt(letters.length()))));
                        }
                        f.commit(w.toString());
                        what = "paste";
                        break;
                    }
                    case 15: // deleteSurroundingText
                        f.deleteAround(rnd.nextInt(6), rnd.nextInt(6));
                        what = "delete around";
                        break;
                    case 16: // the keyboard re-types its draft as it is
                        f.setComposing(f.draft());
                        what = "same draft";
                        break;
                    case 17: // the field gives up its first characters (it keeps itself short)
                    case 18: // or starts over (a key moved the phone's cursor)
                    default:
                        what = "nothing";
                        break;
                }
                m.sync(f.ed.toString());
                if (kind == 17 && f.compA < 0 && f.ed.length() > 0) {
                    int k = f.align(rnd.nextInt(f.ed.length() + 1));
                    kept.append(f.ed, 0, k);
                    f.ed.delete(0, k);
                    f.selA = Math.max(0, f.selA - k);
                    f.selB = Math.max(0, f.selB - k);
                    m.forget(k);
                    trims++;
                    what = "forget";
                } else if (kind == 18) {
                    kept.append(f.ed);
                    f.ed.setLength(0);
                    f.selA = 0;
                    f.selB = 0;
                    f.compA = -1;
                    f.compB = -1;
                    m.reset();
                    resets++;
                    what = "reset";
                }
                steps++;
                eq(seed + kept + f.ed, p.text.toString(), "the phone shows what the keyboard shows after " + what + " (round " + round + ", step " + n + ")");
            }
        }
        yes(steps == 3000 * 40, "every random step was checked: " + steps);
        yes(trims > 1000 && resets > 1000, "the field shortened itself and started over often enough: " + trims + " and " + resets);

        System.out.println(checks + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
