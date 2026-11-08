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
 * physical one) and hands it on as text and key presses, one by one as they happen, for the remote screen.
 * It keeps no text of its own, so there is nothing to select or edit here and the keyboard shows no
 * suggestions: what you type goes straight to the phone.
 */
final class TypingView extends View {
    interface Listener {
        void text(String s);

        void key(int keyCode);
    }

    private Listener listener;

    TypingView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    void setListener(Listener l) {
        listener = l;
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
        // Plain text without suggestions, so keys arrive as they are typed instead of as drafts to correct.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_ACTION_NONE;
        return new Conn(this);
    }

    private final class Conn extends BaseInputConnection {
        /** What has been typed into the phone as a draft that the keyboard may still change. */
        private String composing = "";

        Conn(View v) {
            super(v, false);
        }

        /** Makes the phone's draft text equal to now: delete what no longer matches, type the rest. */
        private void replaceComposing(String now) {
            Listener l = listener;
            if (l == null) {
                return;
            }
            int common = 0;
            int max = Math.min(composing.length(), now.length());
            while (common < max && composing.charAt(common) == now.charAt(common)) {
                common++;
            }
            for (int i = common; i < composing.length(); i++) {
                l.key(KeyEvent.KEYCODE_DEL);
            }
            if (now.length() > common) {
                l.text(now.substring(common));
            }
            composing = now;
        }

        @Override
        public boolean setComposingText(CharSequence t, int newCursorPosition) {
            replaceComposing(t.toString());
            return true;
        }

        @Override
        public boolean commitText(CharSequence t, int newCursorPosition) {
            String s = t.toString();
            int nl = s.indexOf('\n');
            if (nl >= 0) { // an Enter typed as text
                replaceComposing(s.substring(0, nl));
                composing = "";
                Listener l = listener;
                if (l != null) {
                    l.key(KeyEvent.KEYCODE_ENTER);
                }
                if (nl + 1 < s.length()) {
                    replaceComposing(s.substring(nl + 1));
                    composing = "";
                }
                return true;
            }
            replaceComposing(s);
            composing = "";
            return true;
        }

        @Override
        public boolean finishComposingText() {
            composing = "";
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int before, int after) {
            Listener l = listener;
            if (l == null) {
                return true;
            }
            for (int i = 0; i < before; i++) {
                l.key(KeyEvent.KEYCODE_DEL);
            }
            for (int i = 0; i < after; i++) {
                l.key(KeyEvent.KEYCODE_FORWARD_DEL);
            }
            composing = composing.length() > before ? composing.substring(0, composing.length() - before) : "";
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent e) {
            handle(e);
            return true;
        }

        @Override
        public boolean performEditorAction(int actionCode) {
            Listener l = listener;
            if (l != null) {
                l.key(KeyEvent.KEYCODE_ENTER);
            }
            return true;
        }

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
