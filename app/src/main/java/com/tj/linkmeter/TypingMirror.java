package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/TypingMirrorTest.java). Keeps the phone's text equal to the text of the tablet's typing
 * field, using the only two things the remote screen can do on the phone: type text, and press a key.
 *
 * The typing field (TypingView) is a real, honest text field for the tablet's keyboard (SwiftKey, Gboard, ...): it holds the text typed
 * so far, the keyboard reads it back, and the keyboard may change it anywhere (a letter tapped, a word glided, a typo corrected, a
 * suggestion tapped, a word re-opened, a selection replaced, a pasted text). After each such edit this class is given the field's whole
 * text. It remembers what the phone has been sent, and brings the phone up to date with the least that works: Backspace for the end of
 * the old text that is no longer there, then type the new end. A word that only grows sends the new letter; a glided word is typed once.
 * The phone's cursor is always taken to be at the end of what this field typed, wherever the keyboard's cursor is, so the phone's text
 * is right whatever the keyboard does inside its text.
 *
 * The phone's own text from BEFORE the field was opened is not known here. Backspace and Delete that reach beyond the field's text are
 * passed on as they are ({@link #deleteBefore}, {@link #deleteAfter}), so the keyboard can still erase what was on the phone.
 */
final class TypingMirror {
    /** The same numbers as android.view.KeyEvent.KEYCODE_DEL, KEYCODE_FORWARD_DEL and KEYCODE_ENTER. */
    static final int DEL = 67;
    static final int FORWARD_DEL = 112;
    static final int ENTER = 66;

    /** Where the changes go: typed text (never holding a line break), and key presses (key codes as above). */
    interface Sink {
        void text(String s);

        void key(int keyCode);
    }

    private final Sink sink;
    /** What the phone has been sent from this field, in order. The phone's cursor is at its end. */
    private String sent = "";

    TypingMirror(Sink sink) {
        this.sink = sink;
    }

    /** How many characters the phone has been sent from this field and still holds. */
    int length() {
        return sent.length();
    }

    /** The field's text is now {@code now}: Backspace the end of the old text that is gone, then type the new end. */
    void sync(String now) {
        if (now == null) {
            now = "";
        }
        int common = 0;
        int max = Math.min(sent.length(), now.length());
        while (common < max && sent.charAt(common) == now.charAt(common)) {
            common++;
        }
        if (common > 0 && (common < sent.length() || common < now.length())
                && Character.isHighSurrogate(sent.charAt(common - 1))) {
            common--; // never split a pair: one Backspace on the phone removes the whole character
        }
        for (int i = common; i < sent.length(); ) {
            sink.key(DEL);
            i += Character.charCount(sent.codePointAt(i));
        }
        typeRest(now, common);
        sent = now;
    }

    /** Types {@code now} from {@code from} on: text, with each line break as the Enter key. */
    private void typeRest(String now, int from) {
        int start = from;
        for (int i = from; i < now.length(); i++) {
            if (now.charAt(i) == '\n') {
                if (i > start) {
                    sink.text(now.substring(start, i));
                }
                sink.key(ENTER);
                start = i + 1;
            }
        }
        if (start < now.length()) {
            sink.text(now.substring(start));
        }
    }

    /** {@code n} Backspace presses for the phone's own text before the cursor (more than this field ever typed). */
    void deleteBefore(int n) {
        for (int i = 0; i < n; i++) {
            sink.key(DEL);
        }
    }

    /** {@code n} Delete presses for the phone's own text after the cursor. */
    void deleteAfter(int n) {
        for (int i = 0; i < n; i++) {
            sink.key(FORWARD_DEL);
        }
    }

    /** The field gave up the first {@code n} characters of its text (it keeps itself short): the phone keeps them, nothing is sent. */
    void forget(int n) {
        sent = n >= sent.length() ? "" : sent.substring(Math.max(0, n));
    }

    /** Starts over: nothing of what this field typed is followed any more (a key moved the phone's cursor, or the field was cleared). */
    void reset() {
        sent = "";
    }
}
