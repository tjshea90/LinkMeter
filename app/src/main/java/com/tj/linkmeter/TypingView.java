package com.tj.linkmeter;

import android.content.Context;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

/**
 * An invisible text field for the tablet's keyboard (the on-screen one, or a physical one). What is typed into it goes on to the phone
 * as text and key presses, as it is typed, for the remote screen.
 *
 * It behaves like a real text field, because keyboards (Microsoft SwiftKey, Gboard) switch their glide (swipe) typing and suggestions
 * off when a field looks wrong to them: it keeps the text typed so far, answers when the keyboard reads it back, and tells the keyboard
 * where the cursor is after every change, as an ordinary text box does. {@link TypingMirror} then keeps the phone's text equal to the
 * field's, whatever the keyboard does inside it (a word grown letter by letter, a glided word, a correction, a re-opened word, a paste).
 * The text of the field is short-lived: it starts empty each time the keyboard opens, the phone keeps what was typed.
 *
 * Two kinds of field, chosen by {@link #setSuggestions}. ON (the default): a plain text field with suggestions and glide typing.
 * OFF: a "visible password" field without suggestions, so the keyboard sends exactly the letters that are tapped (keyboards switch
 * glide typing off in such a field, which is why it is not the default). {@link #setIncognito} asks the keyboard not to learn from
 * what is typed; keyboards show that as their incognito mode, and some may then also switch glide typing off.
 */
final class TypingView extends View {
    interface Listener {
        void text(String s);

        void key(int keyCode);

        /** A * or # was left out of what was typed: the phone never types them (see TypingFilter). */
        void blocked();
    }

    /** The field keeps itself short: above this many characters it gives up the oldest ones (the phone keeps them). */
    private static final int LONGEST = 1500;
    private static final int KEEP = 500;

    private Listener listener;
    private boolean suggestions = true;
    private boolean incognito;
    private Conn conn;

    // What the keyboard asked of this field since the app started, for the diagnostics. Counts only, never what was typed.
    private int started;
    private int composing;
    private int committed;
    private int finished;
    private int deleted;
    private int keys;
    private int actions;
    private int cursors;
    private int regions;
    private int reads;
    private int extracts;
    private int anchors;
    private int reported;
    private String lastField = "no keyboard has opened it yet";

    TypingView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Suggestions and glide typing (a plain text field), or exactly the letters that are tapped. Read when the keyboard next opens. */
    void setSuggestions(boolean on) {
        suggestions = on;
    }

    /** Ask the keyboard not to learn from what is typed. Read when the keyboard next opens. */
    void setIncognito(boolean on) {
        incognito = on;
    }

    /** What the keyboard has asked of this field and done to it, in one line (counts and settings only, never what was typed). */
    String report() {
        return lastField + "; since the app started the keyboard opened it " + started + "x, composing " + composing + ", committed "
                + committed + ", finished " + finished + ", deleted " + deleted + ", keys " + keys + ", action buttons " + actions
                + ", cursor moves " + cursors + ", words re-opened " + regions + ", text read back " + reads
                + ", whole text asked for " + extracts + ", cursor position asked for " + anchors
                + ", cursor reported to it " + reported;
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    /** One key with no keyboard connection: editing and arrow keys go as key presses, anything that types a character as text. */
    private boolean handleDirect(KeyEvent e) {
        Listener l = listener;
        if (l == null || e.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            code = KeyEvent.KEYCODE_ENTER;
        }
        if (isEditingKey(code)) {
            l.key(code);
            return true;
        }
        if (e.isCtrlPressed() || e.isAltPressed() || e.isMetaPressed()) {
            return false; // shortcuts are not typed
        }
        int u = e.getUnicodeChar(e.getMetaState());
        if (u > 0 && u < 0x10000 && !Character.isISOControl(u)) {
            String s = String.valueOf((char) u);
            if (TypingFilter.keep(s).isEmpty()) {
                l.blocked();
            } else {
                l.text(s);
            }
            return true;
        }
        return false;
    }

    /** Keys that go to the phone as key presses and never type a character. */
    private static boolean isEditingKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_FORWARD_DEL:
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MOVE_HOME:
            case KeyEvent.KEYCODE_MOVE_END:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Conn c = conn;
        boolean handled = c != null ? c.key(event) : handleDirect(event);
        return handled || super.onKeyDown(keyCode, event);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        int options = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE;
        if (suggestions) {
            // A plain text field: neither a password field nor "no suggestions", because keyboards (Microsoft SwiftKey, Gboard) switch
            // glide typing off for both. The keyboard learns from what is typed unless the user asked for incognito.
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT;
            if (incognito) {
                options |= EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
            }
        } else {
            // Plain text without suggestions, so keys arrive as they are typed instead of as drafts to correct.
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        }
        outAttrs.imeOptions = options;
        // An empty field with the cursor at its start. EditorInfo's default is -1 ("unknown"), and a keyboard that is not told where
        // the cursor is may switch its text features (suggestions, glide typing) off.
        outAttrs.initialSelStart = 0;
        outAttrs.initialSelEnd = 0;
        if (Build.VERSION.SDK_INT >= 30) {
            outAttrs.setInitialSurroundingText(""); // what a text field says up front, so the keyboard need not ask
        }
        started++;
        lastField = (suggestions ? "plain text" + (incognito ? ", incognito" : "") : "exact letters (password-like)")
                + " (input type 0x" + Integer.toHexString(outAttrs.inputType) + ", options 0x" + Integer.toHexString(outAttrs.imeOptions) + ")";
        Conn c = new Conn(this);
        conn = c;
        return c;
    }

    /**
     * The connection to the keyboard: a real editor on a text of its own (the system's BaseInputConnection does the editing, exactly as
     * in any text field), whose changes are passed on to the phone and whose cursor is reported to the keyboard.
     */
    private final class Conn extends BaseInputConnection {
        /** Brings the phone's text up to date with this field's text after each edit. */
        private final TypingMirror mirror = new TypingMirror(new TypingMirror.Sink() {
            @Override
            public void text(String s) {
                Listener l = listener;
                if (l != null) {
                    l.text(s);
                }
            }

            @Override
            public void key(int keyCode) {
                Listener l = listener;
                if (l != null) {
                    l.key(keyCode);
                }
            }
        });
        /** Edits in progress: the keyboard's own batches, and the ones the system's editing code opens inside each call. */
        private int nest;
        private boolean open = true;

        Conn(View v) {
            super(v, true);
        }

        // ---- the phone follows, and the keyboard is told, when the outermost batch of edits ends

        @Override
        public boolean beginBatchEdit() {
            nest++;
            return true;
        }

        @Override
        public boolean endBatchEdit() {
            if (nest <= 0) {
                return false;
            }
            nest--;
            if (nest == 0 && open) {
                afterEdit();
            }
            return true;
        }

        private void afterEdit() {
            Editable ed = getEditable();
            mirror.sync(ed.toString());
            shorten(ed);
            tell(ed);
        }

        /** Tells the keyboard where the cursor and the draft are now, as a text field does after every change. */
        private void tell(Editable ed) {
            InputMethodManager imm = (InputMethodManager) TypingView.this.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.updateSelection(TypingView.this, Selection.getSelectionStart(ed), Selection.getSelectionEnd(ed),
                        getComposingSpanStart(ed), getComposingSpanEnd(ed));
                reported++;
            }
        }

        /** Gives up the oldest part of a long text, at a space and while no word is being drafted. The phone keeps it. */
        private void shorten(Editable ed) {
            if (ed.length() <= LONGEST || getComposingSpanStart(ed) >= 0) {
                return;
            }
            int cut = ed.length() - KEEP;
            while (cut < ed.length() && !Character.isWhitespace(ed.charAt(cut - 1))) {
                cut++;
            }
            if (cut >= ed.length()) {
                return;
            }
            ed.delete(0, cut);
            mirror.forget(cut);
        }

        // ---- what the keyboard writes: the same as in any text field, except that * and # are left out

        @Override
        public boolean setComposingText(CharSequence t, int newCursorPosition) {
            composing++;
            CharSequence c = clean(t);
            if (c == null) {
                tell(getEditable());
                return true;
            }
            return super.setComposingText(c, newCursorPosition);
        }

        @Override
        public boolean commitText(CharSequence t, int newCursorPosition) {
            committed++;
            CharSequence c = clean(t);
            if (c == null) {
                tell(getEditable());
                return true;
            }
            return super.commitText(c, newCursorPosition);
        }

        /** The text without * and # (the phone never types them). Null when only such characters were sent: then nothing changes. */
        private CharSequence clean(CharSequence t) {
            if (t == null) {
                return "";
            }
            String s = t.toString();
            String k = TypingFilter.keep(s);
            if (k.length() == s.length()) {
                return t;
            }
            Listener l = listener;
            if (l != null) {
                l.blocked();
            }
            return k.isEmpty() ? null : k;
        }

        @Override
        public boolean finishComposingText() {
            finished++;
            return super.finishComposingText();
        }

        @Override
        public boolean setComposingRegion(int start, int end) {
            regions++;
            return super.setComposingRegion(start, end);
        }

        @Override
        public boolean setSelection(int start, int end) {
            cursors++;
            boolean r = super.setSelection(start, end);
            tell(getEditable());
            return r;
        }

        @Override
        public boolean deleteSurroundingText(int before, int after) {
            deleted++;
            return erase(before, after, false);
        }

        @Override
        public boolean deleteSurroundingTextInCodePoints(int before, int after) {
            deleted++;
            return erase(before, after, true);
        }

        /**
         * Deletes around the cursor as the system does. What the field cannot delete, because it holds less than that, is the phone's own
         * text from before the field was opened: Backspace or Delete is passed on, but only when the field is empty, so that the phone's
         * cursor is where the keyboard's cursor is.
         */
        private boolean erase(int before, int after, boolean codePoints) {
            Editable ed = getEditable();
            int a = Math.min(Selection.getSelectionStart(ed), Selection.getSelectionEnd(ed));
            int b = Math.max(Selection.getSelectionStart(ed), Selection.getSelectionEnd(ed));
            int ca = getComposingSpanStart(ed);
            int cb = getComposingSpanEnd(ed);
            if (ca >= 0 && cb >= 0) {
                a = Math.min(a, Math.min(ca, cb));
                b = Math.max(b, Math.max(ca, cb));
            }
            a = Math.max(a, 0);
            b = Math.max(b, a);
            int haveBefore = codePoints ? Character.codePointCount(ed, 0, a) : a;
            int haveAfter = codePoints ? Character.codePointCount(ed, b, ed.length()) : ed.length() - b;
            int lackBefore = Math.max(0, before - haveBefore);
            int lackAfter = Math.max(0, after - haveAfter);
            boolean r = codePoints ? super.deleteSurroundingTextInCodePoints(before, after) : super.deleteSurroundingText(before, after);
            passOn(lackBefore, lackAfter);
            return r;
        }

        private void passOn(int lackBefore, int lackAfter) {
            Editable ed = getEditable();
            if (ed.length() == 0) {
                mirror.deleteBefore(lackBefore);
                mirror.deleteAfter(lackAfter);
            }
        }

        // ---- keys: from the keyboard (sendKeyEvent) and from a physical keyboard (TypingView.onKeyDown)

        @Override
        public boolean sendKeyEvent(KeyEvent e) {
            keys++;
            key(e);
            return true;
        }

        @Override
        public boolean performEditorAction(int actionCode) {
            actions++;
            super.commitText("\n", 1); // the keyboard's action button is Enter
            return true;
        }

        /** One key: it edits the field as it would edit a text field, or (for cursor and control keys) goes to the phone as a key. */
        boolean key(KeyEvent e) {
            if (e.getAction() != KeyEvent.ACTION_DOWN) {
                return true;
            }
            int code = e.getKeyCode();
            switch (code) {
                case KeyEvent.KEYCODE_DEL:
                    eraseKey(false);
                    return true;
                case KeyEvent.KEYCODE_FORWARD_DEL:
                    eraseKey(true);
                    return true;
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    super.commitText("\n", 1);
                    return true;
                default:
                    break;
            }
            if (isEditingKey(code)) {
                moveKey(code);
                return true;
            }
            if (e.isCtrlPressed() || e.isAltPressed() || e.isMetaPressed()) {
                return false; // shortcuts are not typed
            }
            int u = e.getUnicodeChar(e.getMetaState());
            if (u > 0 && u < 0x10000 && !Character.isISOControl(u)) {
                CharSequence c = clean(String.valueOf((char) u));
                if (c != null) {
                    super.commitText(c, 1);
                }
                return true;
            }
            return false;
        }

        /** The Backspace or Delete key: removes the selection, or the character before or after the cursor. */
        private void eraseKey(boolean forward) {
            Editable ed = getEditable();
            int a = Math.min(Selection.getSelectionStart(ed), Selection.getSelectionEnd(ed));
            int b = Math.max(Selection.getSelectionStart(ed), Selection.getSelectionEnd(ed));
            a = Math.max(a, 0);
            b = Math.max(b, a);
            int lackBefore = 0;
            int lackAfter = 0;
            beginBatchEdit();
            if (a != b) {
                ed.delete(a, b);
            } else if (!forward) {
                if (a > 0) {
                    ed.delete(Character.offsetByCodePoints(ed, a, -1), a);
                } else {
                    lackBefore = 1;
                }
            } else if (b < ed.length()) {
                ed.delete(b, Character.offsetByCodePoints(ed, b, 1));
            } else {
                lackAfter = 1;
            }
            endBatchEdit();
            passOn(lackBefore, lackAfter);
        }

        /**
         * A cursor or control key goes to the phone. The phone's cursor then is not where this field's text ended, so the text typed so
         * far is no longer followed: the field starts empty again.
         */
        private void moveKey(int code) {
            Listener l = listener;
            if (l != null) {
                l.key(code);
            }
            Editable ed = getEditable();
            removeComposingSpans(ed);
            ed.clear();
            Selection.setSelection(ed, 0);
            mirror.reset();
            tell(ed);
        }

        // ---- reads: answered by the system's editor from the text of this field; counted for the diagnostics

        @Override
        public CharSequence getTextBeforeCursor(int n, int flags) {
            reads++;
            return super.getTextBeforeCursor(n, flags);
        }

        @Override
        public CharSequence getTextAfterCursor(int n, int flags) {
            reads++;
            return super.getTextAfterCursor(n, flags);
        }

        @Override
        public CharSequence getSelectedText(int flags) {
            reads++;
            return super.getSelectedText(flags);
        }

        @Override
        public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            extracts++;
            return super.getExtractedText(request, flags);
        }

        @Override
        public boolean requestCursorUpdates(int cursorUpdateMode) {
            anchors++;
            return super.requestCursorUpdates(cursorUpdateMode);
        }

        @Override
        public void closeConnection() {
            super.closeConnection();
            open = false;
            if (conn == this) {
                conn = null;
            }
        }
    }
}
