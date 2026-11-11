package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/TypingDraftTest.java). What a keyboard does to the text it is typing into, turned into
 * the only two things the remote screen can do on the phone: type text, and press a key.
 *
 * The tablet's keyboard (SwiftKey, Gboard, ...) does not only send letters. It writes a DRAFT of the word it is working on (every
 * letter of a tapped word, or the whole word at once after a glide) and changes it until the word is finished: a typo is fixed by
 * replacing the draft, a suggestion that is tapped replaces it, a glide may first show one word and then another. The phone has no
 * such draft: it only ever got key presses. So the draft is kept here, and each change of it is sent as the least that makes the
 * phone's text equal to the keyboard's: Backspace for the part of the draft that is no longer there, then the new rest. A word that
 * only grows ("h", "he", "hel") sends nothing but the new letter; a glide sends the whole word once.
 *
 * Nothing is remembered about text that is already finished (committed): this class cannot see the phone's text and does not pretend
 * to. The keyboard is told there is no text around the cursor (TypingView), so it does not try to re-open an earlier word.
 */
final class TypingDraft {
    /** The same numbers as android.view.KeyEvent.KEYCODE_DEL, KEYCODE_FORWARD_DEL and KEYCODE_ENTER. */
    static final int DEL = 67;
    static final int FORWARD_DEL = 112;
    static final int ENTER = 66;

    /** Where the changes go: typed text, and key presses (key codes as above). */
    interface Sink {
        void text(String s);

        void key(int keyCode);
    }

    private final Sink sink;
    /** The draft the phone has now: what was typed for the word that the keyboard may still change. */
    private String draft = "";

    TypingDraft(Sink sink) {
        this.sink = sink;
    }

    /** The keyboard changed its draft to this (letters tapped, a glide, a correction, a suggestion). */
    void setComposing(String now) {
        replace(now == null ? "" : now);
    }

    /**
     * The keyboard finished a piece of text: its draft becomes this text for good (a word and the space after it, a tapped
     * suggestion, a glide, one character). An empty text finishes by deleting the draft. A new line goes as the Enter key.
     */
    void commit(String s) {
        if (s == null) {
            s = "";
        }
        int nl = s.indexOf('\n');
        if (nl >= 0) {
            replace(s.substring(0, nl));
            draft = "";
            sink.key(ENTER);
            if (nl + 1 < s.length()) {
                replace(s.substring(nl + 1));
                draft = "";
            }
            return;
        }
        replace(s);
        draft = "";
    }

    /** The keyboard keeps the draft as it is and stops working on it. */
    void finish() {
        draft = "";
    }

    /** Characters deleted before and after the cursor: Backspace and Delete, one press each. */
    void delete(int before, int after) {
        for (int i = 0; i < before; i++) {
            sink.key(DEL);
        }
        for (int i = 0; i < after; i++) {
            sink.key(FORWARD_DEL);
        }
        draft = draft.length() > before ? draft.substring(0, draft.length() - before) : "";
    }

    /** Enter, as a key (the keyboard's action button, or the key itself): the draft is finished first. */
    void enter() {
        draft = "";
        sink.key(ENTER);
    }

    /** Makes the phone's draft equal to {@code now}: Backspace what no longer matches, type the rest. */
    private void replace(String now) {
        int common = 0;
        int max = Math.min(draft.length(), now.length());
        while (common < max && draft.charAt(common) == now.charAt(common)) {
            common++;
        }
        for (int i = common; i < draft.length(); i++) {
            sink.key(DEL);
        }
        if (now.length() > common) {
            sink.text(now.substring(common));
        }
        draft = now;
    }
}
