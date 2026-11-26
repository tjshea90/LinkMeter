package com.tj.linkmeter;

import android.content.Context;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

/**
 * Tablet only: the keyboard for a text box inside a floating card (docs/FLOATING_PHONE.md). A floating window is made so that it never
 * takes the keyboard (that is what keeps a card from stealing the typing of the app under it), so it has to be made able to, and only for
 * as long as somebody types in it: the first touch of a box asks the window ({@link Window#wantKeyboard}) to become one that can take the
 * keyboard, the box takes the focus a moment later and Android shows the keyboard; touching anything outside the card, the box's own
 * Done key and Back give the focus up again and the window goes back to never taking it. It uses Android's keyboard, so the user's own
 * keyboard (and its glide typing) types here. One of these per card.
 */
final class FloatKeyboard {
    /** The window of the card. */
    interface Window {
        /** true: let the window take the keyboard (and keep the card above it); false: back to a window that never does. */
        void wantKeyboard(boolean on);
    }

    private final View owner;
    private final Window window;
    private EditText field;
    private boolean up;

    FloatKeyboard(View owner, Window window) {
        this.owner = owner;
        this.window = window;
    }

    /** Makes this box ask for the keyboard when it is touched, and let it go on its Done key. */
    void bind(final EditText box) {
        box.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                want(box, true);
            }
            return false; // the box still handles the touch itself (the cursor)
        });
        box.setOnEditorActionListener((v, id, ev) -> {
            want(box, false);
            return true;
        });
    }

    boolean up() {
        return up;
    }

    /** The keyboard goes away (a tab was changed, the card closed). */
    void close() {
        want(field, false);
    }

    void want(final EditText box, boolean on) {
        if (on) {
            field = box;
            if (!up) {
                up = true;
                window.wantKeyboard(true);
            }
            focusSoon(box);
            return;
        }
        if (!up) {
            return;
        }
        up = false;
        window.wantKeyboard(false);
        if (box != null) {
            box.clearFocus();
        }
        InputMethodManager imm = (InputMethodManager) owner.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(owner.getWindowToken(), 0);
        }
    }

    /** The window needs a moment to become one that can take the keyboard; then the box takes the focus and Android shows the keyboard. */
    private void focusSoon(final EditText box) {
        Runnable go = () -> {
            if (up && field == box && owner.isAttachedToWindow()) {
                box.requestFocus();
                InputMethodManager imm = (InputMethodManager) owner.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(box, InputMethodManager.SHOW_IMPLICIT);
                }
            }
        };
        owner.postDelayed(go, 180);
        owner.postDelayed(go, 600);
    }

    /** Call from the card's onTouchEvent: a touch outside the card (the window asks to hear them) ends the typing. */
    void outside(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE && up) {
            want(field, false);
        }
    }

    /** Call from the card's dispatchKeyEvent: Back ends the typing first. Returns true when it took the key. */
    boolean key(KeyEvent e) {
        if (up && e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                want(field, false);
            }
            return true;
        }
        return false;
    }
}
