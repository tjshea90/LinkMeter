package com.tj.linkmeter;

import java.util.Locale;

/**
 * No Android in here (tested in tools/test/TextsLogicTest.java and TextsRound4Test.java). An iPhone sends its "tapbacks" to a phone that
 * is not on iMessage as ordinary texts (Loved "see you at 7"; in Spanish Le encantó "..."), and Google Messages and newer iPhones do it
 * with Reacted [emoji] to "...". Shown as they arrive they look like noise; this finds them, so that the conversation can show the
 * reaction on the message it is about instead. It also writes this tablet's own reactions the same way ({@link #textFor}): Google
 * Messages turns such a text back into a small emoji on the message, an iPhone shows the words, and this tablet folds it like any other.
 */
final class SmsReactions {
    private SmsReactions() {}

    static final String HEART = "❤️";
    static final String LIKE = "👍";
    static final String DISLIKE = "👎";
    static final String LAUGH = "😂";
    static final String EMPHASIS = "‼️";
    static final String QUESTION = "❓";

    /** What a reaction text says. */
    static final class Reaction {
        final String emoji;
        /** The words of the message it is about (an end cut off with "..." is kept as it is), "" when it is about a picture or a link. */
        final String quote;
        /** It is about a picture, video, link or file, not about words. */
        final boolean media;
        /** The reaction was taken back. */
        final boolean remove;

        Reaction(String emoji, String quote, boolean media, boolean remove) {
            this.emoji = emoji;
            this.quote = quote;
            this.media = media;
            this.remove = remove;
        }
    }

    private static final String[][] VERBS = {{"Loved ", HEART}, {"Liked ", LIKE}, {"Disliked ", DISLIKE}, {"Laughed at ", LAUGH},
        {"Emphasized ", EMPHASIS}, {"Questioned ", QUESTION},
        // an iPhone set to Spanish (the longer "No le gustó" before "Le gustó" is not needed: they start differently)
        {"Le encantó ", HEART}, {"Le gustó ", LIKE}, {"No le gustó ", DISLIKE}, {"Se rió de ", LAUGH}, {"Se rio de ", LAUGH},
        {"Enfatizó ", EMPHASIS}, {"Cuestionó ", QUESTION}};
    private static final String[][] REMOVALS = {{"a heart", HEART}, {"a like", LIKE}, {"a dislike", DISLIKE}, {"a laugh", LAUGH},
        {"an exclamation", EMPHASIS}, {"an exclamation mark", EMPHASIS}, {"an emphasis", EMPHASIS}, {"a question mark", QUESTION},
        {"a question", QUESTION}};

    private static boolean isOpen(char c) {
        return c == '“' || c == '"' || c == '„';
    }

    private static boolean isClose(char c) {
        return c == '”' || c == '"' || c == '“';
    }

    /** The words inside quotation marks, or null when the text is not a quotation. */
    private static String quoted(String s) {
        String t = s.trim();
        if (t.length() >= 2 && isOpen(t.charAt(0)) && isClose(t.charAt(t.length() - 1))) {
            return t.substring(1, t.length() - 1);
        }
        return null;
    }

    private static boolean mediaWords(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.equals("an image") || t.equals("a photo") || t.equals("a picture") || t.equals("a video") || t.equals("a link")
                || t.equals("an attachment") || t.equals("a file") || t.equals("a sticker") || t.equals("a voice message")
                || t.equals("an audio message") || t.equals("a gif") || t.equals("a location") || t.equals("a contact");
    }

    private static boolean emojiOnly(String s) {
        if (s.isEmpty() || s.length() > 16 || EmojiText.count(s) < 1) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    /** The reaction a text is, or null when it is an ordinary text. */
    static Reaction parse(String body) {
        if (body == null) {
            return null;
        }
        String b = body.trim();
        if (b.length() < 8 || b.length() > 1200 || b.indexOf('\n') >= 0) {
            return null;
        }
        for (String[] v : VERBS) {
            if (b.startsWith(v[0])) {
                String rest = b.substring(v[0].length());
                String q = quoted(rest);
                if (q != null) {
                    return new Reaction(v[1], q, false, false);
                }
                if (mediaWords(rest)) {
                    return new Reaction(v[1], "", true, false);
                }
                return null;
            }
        }
        if (b.startsWith("Removed ")) {
            for (String[] r : REMOVALS) {
                String head = "Removed " + r[0] + " from ";
                if (b.startsWith(head)) {
                    String rest = b.substring(head.length());
                    String q = quoted(rest);
                    if (q != null) {
                        return new Reaction(r[1], q, false, true);
                    }
                    if (mediaWords(rest)) {
                        return new Reaction(r[1], "", true, true);
                    }
                    return null;
                }
            }
            // any other emoji taken back: Removed 😮 from "..."
            return emojiTo(b, "Removed ", " from ", true);
        }
        if (b.startsWith("Reacted ")) {
            return emojiTo(b, "Reacted ", " to ", false);
        }
        // the emoji first: 😮 to "..."
        return emojiTo(b, "", " to ", false);
    }

    /** {@code head} + emoji + {@code join} + a quotation (or a picture's words), or null. */
    private static Reaction emojiTo(String b, String head, String join, boolean remove) {
        if (!b.startsWith(head)) {
            return null;
        }
        int at = b.indexOf(join, head.length());
        if (at <= head.length()) {
            return null;
        }
        String emoji = b.substring(head.length(), at);
        String rest = b.substring(at + join.length());
        if (!emojiOnly(emoji) || EmojiText.count(emoji) != 1) {
            return null;
        }
        String q = quoted(rest);
        if (q != null) {
            return new Reaction(emoji, q, false, remove);
        }
        if (mediaWords(rest)) {
            return new Reaction(emoji, "", true, remove);
        }
        return null;
    }

    // ------------------------------------------------------------ this tablet's own reactions

    /** The most of a text that a reaction quotes; a longer one is cut with "…" (the receiving apps match the start). */
    static final int QUOTE_MAX = 100;

    /** The words an iPhone uses for its six reactions, which every app that shows reactions knows. */
    private static final String[][] CLASSIC = {{HEART, "Loved", "a heart"}, {"♥️", "Loved", "a heart"}, {LIKE, "Liked", "a like"},
        {DISLIKE, "Disliked", "a dislike"}, {LAUGH, "Laughed at", "a laugh"}, {EMPHASIS, "Emphasized", "an exclamation"},
        {QUESTION, "Questioned", "a question mark"}};

    /** What a reaction quotes: the text on one line (cut when long), or the words for a picture, a video or a file. */
    static String quote(Sms.Msg m) {
        String words = m.body == null ? "" : m.body.replaceAll("\\s+", " ").trim();
        if (words.isEmpty()) {
            for (Sms.Part p : m.parts) {
                if (p.type.startsWith("image/")) {
                    return "an image";
                }
                if (p.type.startsWith("video/")) {
                    return "a video";
                }
            }
            return "an attachment";
        }
        if (words.codePointCount(0, words.length()) > QUOTE_MAX) {
            int end = words.offsetByCodePoints(0, QUOTE_MAX - 1);
            words = words.substring(0, end).trim() + "…";
        }
        return "“" + words + "”";
    }

    /** Is this the words for a picture, a video or a file (not a quotation)? */
    private static boolean isMediaQuote(String q) {
        return !q.startsWith("“");
    }

    /**
     * The text that sends a reaction to {@code target}, the way Google Messages and iPhones send them to a phone that is not on their
     * chat service: the six iPhone reactions in their words (Loved “...”), any other emoji as Reacted 😮 to “...”. With {@code remove},
     * the text that takes it back (Removed a heart from “...”, Removed 😮 from “...”).
     */
    static String textFor(String emoji, Sms.Msg target, boolean remove) {
        String q = quote(target);
        for (String[] c : CLASSIC) {
            if (c[0].equals(emoji)) {
                return remove ? "Removed " + c[2] + " from " + q : c[1] + " " + q;
            }
        }
        return (remove ? "Removed " + emoji + " from " : "Reacted " + emoji + " to ") + q;
    }

    /** The line for the list of conversations when the newest text is a reaction ("Reacted 👍 to “see you at 7”"), else null. */
    static String previewOf(String body) {
        Reaction r = parse(body);
        if (r == null) {
            return null;
        }
        String what = r.media ? "a picture" : "“" + r.quote + "”";
        return (r.remove ? "Took back " + r.emoji + " from " : "Reacted " + r.emoji + " to ") + what;
    }

    /** One emoji that is a reaction drawn the same way (the iPhone's heart is sent as ❤️, a phone may send ♥️). */
    static String same(String emoji) {
        return "♥️".equals(emoji) || "♥".equals(emoji) ? HEART : emoji;
    }

    private static String norm(String s) {
        return s.replace('’', '\'').replace('‘', '\'').replaceAll("\\s+", " ").trim();
    }

    /** Is this message the one the reaction is about? */
    static boolean about(Reaction r, Sms.Msg m) {
        if (r.media) {
            return m.mms || !m.parts.isEmpty();
        }
        String q = norm(r.quote);
        String body = norm(m.body);
        if (q.isEmpty() || body.isEmpty()) {
            return false;
        }
        if (q.endsWith("…") || q.endsWith("...")) { // the phone that sent it cut a long text short
            String head = q.endsWith("...") ? q.substring(0, q.length() - 3) : q.substring(0, q.length() - 1);
            return head.length() >= 3 && body.startsWith(head.trim());
        }
        return body.equals(q);
    }
}
