package com.tj.linkmeter;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * The remote screen's layout: the phone's picture in the middle and a column of buttons on each side, so
 * no button is ever on top of the picture (and so the phone's own navigation bar, at the bottom of its
 * picture, stays visible and tappable, also in full screen). A portrait phone on a portrait tablet leaves
 * unused room on both sides anyway.
 *
 * Typing mode: the tablet's keyboard takes the bottom of the window. Instead of shrinking the picture to
 * fit above it, the picture keeps the size it had and stays anchored at the top; only the bottom, where
 * the phone's own keyboard and navigation bar are, goes under the tablet's keyboard.
 */
final class RemoteLayout extends ViewGroup {
    interface KeyboardListener {
        /** The tablet's keyboard was closed (for example with its own Back button) while typing mode was on. */
        void keyboardClosed();
    }

    private final View stage;
    private final View left;
    private final View right;
    private final int bar;
    private boolean typing;
    private int fullH;
    private boolean shrunk;
    private KeyboardListener listener;

    RemoteLayout(Context c, View stage, View left, View right) {
        super(c);
        this.stage = stage;
        this.left = left;
        this.right = right;
        bar = Ui.dp(c, 56);
        addView(stage);
        addView(left);
        addView(right);
        setClipChildren(false);
    }

    void setKeyboardListener(KeyboardListener l) {
        listener = l;
    }

    int barWidth() {
        return bar;
    }

    void setTyping(boolean t) {
        typing = t;
        shrunk = false;
        requestLayout();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        if (!typing) {
            fullH = h; // remember the height without the keyboard
        } else {
            if (fullH <= 0) {
                fullH = h;
            }
            int slack = Ui.dp(getContext(), 40);
            if (h < fullH - slack) {
                shrunk = true;
            } else if (shrunk) {
                shrunk = false; // the keyboard went away
                final KeyboardListener l = listener;
                if (l != null) {
                    post(l::keyboardClosed);
                }
            }
        }
        int availW = Math.max(0, w - 2 * bar);
        int stageH = typing ? Math.max(h, fullH) : h;
        stage.measure(MeasureSpec.makeMeasureSpec(availW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(stageH, MeasureSpec.EXACTLY));
        left.measure(MeasureSpec.makeMeasureSpec(bar, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        right.measure(MeasureSpec.makeMeasureSpec(bar, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int h = b - t;
        int availW = Math.max(0, w - 2 * bar);
        int stageH = typing ? Math.max(h, fullH) : h;
        stage.layout(bar, 0, bar + availW, stageH);
        left.layout(0, 0, bar, h);
        right.layout(w - bar, 0, w, h);
    }
}
