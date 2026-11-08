package com.tj.linkmeter;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Lays its children out in a column, each as tall as the bar is wide (at most maxItem), shrinking them
 * evenly if the column is too short, and centred as a group. Used for the remote screen's side bars, which
 * must fit whatever height the keyboard leaves.
 */
final class BarColumn extends ViewGroup {
    private final int maxItem;

    BarColumn(Context c, int maxItemPx) {
        super(c);
        maxItem = maxItemPx;
    }

    private int visibleCount() {
        int n = 0;
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i).getVisibility() != View.GONE) {
                n++;
            }
        }
        return Math.max(1, n);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        int item = Math.min(maxItem, h / visibleCount());
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() != View.GONE) {
                c.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(item, MeasureSpec.EXACTLY));
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int h = b - t;
        int item = Math.min(maxItem, h / visibleCount());
        int y = (h - item * visibleCount()) / 2;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() != View.GONE) {
                c.layout(0, y, w, y + item);
                y += item;
            }
        }
    }
}
