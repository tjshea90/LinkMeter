package com.tj.linkmeter;

import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * An invisible view that receives what is typed on the tablet's keyboard (the on-screen one, or a
 * physical one) and hands it on as text and key presses, as they happen, for the remote screen.
 * It keeps no text of its own, so there is nothing to select or edit here: what you type goes straight to the phone.
 *
 * Two kinds of field, chosen by {@link #setSuggestions}. ON (the default): a normal text field, so the keyboard offers its suggestions
 * and glide (swipe) typing, and the drafts it writes (a tapped word growing, a glided word, a correction) are followed by
 * {@link TypingDraft}; the keyboard is asked not to learn from what is typed. OFF: a "visible password" field without suggestions, so
 * the keyboard sends exactly the letters that are tapped (keyboards switch glide typing off in such a field, which is why it is not
 * the default).
 */
final class TypingView extends View {
    interface Listener {
        void text(String s);

        void key(int keyCode);
    }

    private Listener listener;
    private boolean suggestions = true;

    TypingView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Suggestions and glide typing (a normal text field), or exactly the letters that are tapped. Read when the keyboard next opens. */
    void setSuggestions(boolean on) {
        suggestions = on;
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    /** Handles one key: editing and arrow keys go as key presses, anything that types a character as text. */
    private boolean handle(KeyEvent e) {
        Listener l = listener;
        if (l == null || e.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }
        int code = e.getKeyCode();
        switch (code) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                l.key(KeyEvent.KEYCODE_ENTER);
                return true;
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
                l.key(code);
                return true;
            default:
                break;
        }
        if (e.isCtrlPressed() || e.isAltPressed() || e.isMetaPressed()) {
            return false; // shortcuts are not typed
        }
        int u = e.getUnicodeChar(e.getMetaState());
        if (u > 0 && u < 0x10000 && !Character.isISOControl(u)) {
            l.text(String.valueOf((char) u));
            return true;
        }
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return handle(event) || super.onKeyDown(keyCode, event);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        if (suggestions) {
            // A normal text field: neither a password field nor "no suggestions", because keyboards (Microsoft SwiftKey, Gboard) switch
            // glide typing off for both. The keyboard is asked not to learn from it: what is typed here may be a password.
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT;
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_ACTION_NONE;
        } else {
            // Plain text without suggestions, so keys arrive as they are typed instead of as drafts to correct.
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    | EditorInfo.IME_ACTION_NONE;
        }
        // An empty field with the cursor at its start. EditorInfo's default is -1 ("unknown"), and a keyboard that is not told where
        // the cursor is may switch its text features (suggestions, glide typing) off.
        outAttrs.initialSelStart = 0;
        outAttrs.initialSelEnd = 0;
        return new Conn(this);
    }

    private final class Conn extends BaseInputConnection {
        /** What the keyboard is writing, and what of it the phone has (see {@link TypingDraft}). */
        private final TypingDraft draft = new TypingDraft(new TypingDraft.Sink() {
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

        Conn(View v) {
            super(v, false);
        }

        @Override
        public boolean setComposingText(CharSequence t, int newCursorPosition) {
            draft.setComposing(t.toString());
            return true;
        }

        @Override
        public boolean commitText(CharSequence t, int newCursorPosition) {
            draft.commit(t.toString());
            return true;
        }

        @Override
        public boolean finishComposingText() {
            draft.finish();
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int before, int after) {
            draft.delete(before, after);
            return true;
        }

        @Override
        public boolean deleteSurroundingTextInCodePoints(int before, int after) {
            draft.delete(before, after); // (a character that is two chars long was never typed on the phone: this is the usual case)
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent e) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                switch (e.getKeyCode()) {
                    case KeyEvent.KEYCODE_DEL:
                        draft.delete(1, 0); // the draft keeps pace with the phone
                        return true;
                    case KeyEvent.KEYCODE_FORWARD_DEL:
                        draft.delete(0, 1);
                        return true;
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_NUMPAD_ENTER:
                        draft.enter();
                        return true;
                    default:
                        draft.finish(); // a key that is typed on its own ends the draft
                        break;
                }
            }
            handle(e);
            return true;
        }

        @Override
        public boolean performEditorAction(int actionCode) {
            draft.enter();
            return true;
        }

        // The keyboard is told that there is no text around the cursor. It then does not try to re-open an earlier word for
        // correction, which the phone's text could not follow (the text is already on the phone, and only keys can change it).
        @Override
        public CharSequence getTextBeforeCursor(int n, int flags) {
            return "";
        }

        @Override
        public CharSequence getTextAfterCursor(int n, int flags) {
            return "";
        }

        @Override
        public CharSequence getSelectedText(int flags) {
            return null;
        }

        @Override
        public boolean beginBatchEdit() {
            return true;
        }

        @Override
        public boolean endBatchEdit() {
            return true;
        }
    }
}
