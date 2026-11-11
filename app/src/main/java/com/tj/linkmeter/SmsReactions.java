package com.tj.linkmeter;

import java.util.Locale;

/**
 * No Android in here (tested in tools/test/TextsLogicTest.java). An iPhone sends its "tapbacks" to a phone that is not on iMessage as
 * ordinary texts (Loved "see you at 7"), and Google Messages does it with Reacted [emoji] to "...". Shown as they arrive they look like
 * noise; this finds them, so that the conversation can show the reaction on the message it is about instead.
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
        {"Emphasized ", EMPHASIS}, {"Questioned ", QUESTION}};
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
        if (s.isEmpty() || s.length() > 16) {
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
            return null;
        }
        if (b.startsWith("Reacted ")) {
            int to = b.indexOf(" to ", 8);
            if (to > 8) {
                String emoji = b.substring(8, to);
                String rest = b.substring(to + 4);
                if (emojiOnly(emoji)) {
                    String q = quoted(rest);
                    if (q != null) {
                        return new Reaction(emoji, q, false, false);
                    }
                    if (mediaWords(rest)) {
                        return new Reaction(emoji, "", true, false);
                    }
                }
            }
        }
        return null;
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
