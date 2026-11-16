package com.tj.linkmeter;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Tablet only: the large dial pad of the Calls tab: twelve big keys with the letters under them, the green Call button in the middle
 * of the bottom row and Backspace beside it (a long press clears the number; a long press on 0 types the plus). The keys are wide
 * rounded buttons, 96 dp tall, so they are easy to hit; the layout is the one every phone's keypad has.
 */
final class DialPad extends LinearLayout {
    interface Listener {
        void key(char c);

        void longZero();

        void backspace();

        void clear();

        void call();
    }

    static final int KEY_DP = 96;

    DialPad(Context c, final Listener l) {
        super(c);
        setOrientation(VERTICAL);
        for (char[] row : CallRules.KEYS) {
            LinearLayout r = new LinearLayout(c);
            r.setOrientation(HORIZONTAL);
            for (final char k : row) {
                r.addView(key(c, k, l), cell(c));
            }
            addView(r, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        LinearLayout bottom = new LinearLayout(c);
        bottom.setOrientation(HORIZONTAL);
        bottom.addView(new View(c), cell(c)); // room, so that the Call button is in the middle
        bottom.addView(callButton(c, l), cell(c));
        bottom.addView(backspace(c, l), cell(c));
        addView(bottom, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static LinearLayout.LayoutParams cell(Context c) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Ui.dp(c, KEY_DP), 1f);
        int m = Ui.dp(c, 5);
        p.setMargins(m, m, m, m);
        return p;
    }

    private static View key(Context c, final char k, final Listener l) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(VERTICAL);
        v.setGravity(Gravity.CENTER);
        v.setBackground(CallsViews.ripple(Ui.CARD_HI, Ui.dp(c, 22)));
        v.setContentDescription(k == '*' ? "star" : k == '#' ? "pound" : String.valueOf(k));
        TextView digit = Ui.text(c, String.valueOf(k), 34, Ui.FG, false);
        digit.setGravity(Gravity.CENTER);
        v.addView(digit);
        String letters = CallRules.letters(k);
        if (!letters.isEmpty() && k != '0') {
            TextView sub = Ui.text(c, letters, 10, Ui.DIM, true);
            sub.setLetterSpacing(0.15f);
            sub.setGravity(Gravity.CENTER);
            v.addView(sub);
        } else if (k == '0') {
            TextView sub = Ui.text(c, "+", 12, Ui.DIM, true);
            sub.setGravity(Gravity.CENTER);
            v.addView(sub);
        }
        v.setClickable(true);
        v.setOnClickListener(x -> l.key(k));
        if (k == '0') {
            v.setOnLongClickListener(x -> {
                l.longZero();
                return true;
            });
        }
        return v;
    }

    private static View callButton(Context c, final Listener l) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(CallsViews.ripple(CallsViews.CALL_GREEN, Ui.dp(c, 40)));
        f.setClickable(true);
        f.setContentDescription("Call");
        f.setOnClickListener(x -> l.call());
        IconButton icon = new IconButton(c, Icons.Kind.HANDSET, 0xFFFFFFFF, 0, "Call", null);
        icon.setScale(0.46f);
        f.addView(icon, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return f;
    }

    private static View backspace(Context c, final Listener l) {
        FrameLayout f = new FrameLayout(c);
        f.setClickable(true);
        f.setContentDescription("Delete the last digit");
        f.setBackground(CallsViews.ripple(0x00000000, Ui.dp(c, 22)));
        f.setOnClickListener(x -> l.backspace());
        f.setOnLongClickListener(x -> {
            l.clear();
            return true;
        });
        IconButton icon = new IconButton(c, Icons.Kind.BACKSPACE, Ui.DIM, 0, "Delete", null);
        icon.setScale(0.46f);
        f.addView(icon, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return f;
    }
}
