package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * No Android in here (tested in tools/test/TextsLogicTest.java). Up to three quick replies for the text that was last received, from
 * plain rules (no learning, nothing leaves the tablet): a question that can be answered yes or no, thanks, a greeting, "where are you",
 * "call me", "when / what time", love, "ok". Nothing is offered when the last text is mine, old, or not worth a quick reply.
 */
final class SmartReply {
    private SmartReply() {}

    /** A text older than this is not a thing to answer quickly. */
    static final long FRESH_MS = 24L * 60 * 60 * 1000;

    private static boolean has(String s, String... words) {
        for (String w : words) {
            if (s.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(String s, String... heads) {
        for (String h : heads) {
            if (s.startsWith(h)) {
                return true;
            }
        }
        return false;
    }

    /** {@code msgs} oldest first. */
    static List<String> suggest(List<Sms.Msg> msgs, long now) {
        List<String> out = new ArrayList<>();
        if (msgs.isEmpty()) {
            return out;
        }
        Sms.Msg last = msgs.get(msgs.size() - 1);
        if (last.mine || last.body == null || now - last.time > FRESH_MS || last.body.trim().isEmpty() || last.body.length() > 400
                || SmsReactions.parse(last.body) != null) {
            return out;
        }
        String t = " " + last.body.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ") + " ";
        boolean question = t.contains("?");
        if (has(t, " thank you", " thanks", " thx ", " ty ")) {
            add(out, "You're welcome!", "No problem", "👍");
        } else if (has(t, " love you", " miss you")) {
            add(out, "Love you too ❤️", "Miss you too", "😘");
        } else if (has(t, " where are you", " where r u", " where you at", " how far ", " eta")) {
            add(out, "On my way", "Running late", "Almost there");
        } else if (has(t, " call me", " can you call", " give me a call", " ring me")) {
            add(out, "I'll call you soon", "Can't talk right now", "Text me instead");
        } else if (has(t, " what time", " when ") && question) {
            add(out, "Sounds good", "Let me check", "What time works for you?");
        } else if (question && startsWith(t.trim(), "are ", "can ", "could ", "do ", "does ", "did ", "will ", "would ", "is ", "have ",
                "should ", "want ", "wanna ", "you ")) {
            add(out, "Yes", "No", "Maybe later");
        } else if (startsWith(t.trim(), "hi", "hello", "hey", "good morning", "good evening", "good night", "yo ", "sup")) {
            add(out, t.trim().startsWith("good morning") ? "Good morning!" : "Hi!", "Hey, how are you?", "What's up?");
        } else if (has(t, " ok ", " okay ", " sounds good", " see you", " got it ", " will do ", " perfect ")) {
            add(out, "👍", "Great", "See you then");
        } else if (question) {
            add(out, "Yes", "No", "Let me check");
        }
        return out;
    }

    private static void add(List<String> out, String a, String b, String c) {
        out.add(a);
        out.add(b);
        out.add(c);
    }
}
