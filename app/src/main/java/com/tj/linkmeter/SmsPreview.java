package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/TextsLogicTest.java). The grey line under a conversation's name in the Texts list: what the
 * newest message says, on one line, the way every messaging app shows it.
 *
 * Why it needs rules of its own: the phone's database keeps a "snippet" for each conversation, but for a picture message (MMS) Android
 * fills it from the message's SUBJECT only. A text-only multimedia message (every group text, a picture with a caption) has no subject,
 * so the snippet is empty while the conversation is unread. The phone then reads the newest message itself ({@code SmsStore}) and
 * this class turns it into the line.
 */
final class SmsPreview {
    private SmsPreview() {}

    /** What the line says for a message that has files but no words. */
    static final String ATTACHMENT = "Attachment";

    /** The words on one line: every run of spaces and line breaks becomes one space, the ends are cut off. Never null. */
    static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /** Is there nothing to read in it? */
    static boolean blank(String s) {
        return clean(s).isEmpty();
    }

    /** The line for one message: its words; "Attachment" when it has only files; "" when there is nothing to show at all. */
    static String line(String body, boolean hasFiles) {
        String s = clean(body);
        if (!s.isEmpty()) {
            return s;
        }
        return hasFiles ? ATTACHMENT : "";
    }

    static String line(Sms.Msg m) {
        return m == null ? "" : line(m.body, !m.parts.isEmpty());
    }

    /**
     * Of a conversation's newest text and newest picture message (either may be missing), the one that came last. The texts count in
     * milliseconds and the picture messages in whole seconds, so both are given here in milliseconds; at the very same moment the text wins.
     */
    static Sms.Msg newer(Sms.Msg text, Sms.Msg picture) {
        if (text == null) {
            return picture;
        }
        if (picture == null) {
            return text;
        }
        return picture.time > text.time ? picture : text;
    }
}
