package com.tj.linkmeter;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * A row that wraps: the recipient pills of the New message screen and the box to type the next one in. Children are put side by side
 * and a child that does not fit moves to the next line. The LAST child (the box to type in) takes what is left of its line, and at
 * least {@code fillMinPx}: when less than that is left it starts a new line, as in the To line of every texting app.
 */
final class FlowRow extends ViewGroup {
    private final int gap;
    private final int fillMin;
    private int[] xs = new int[8];
    private int[] ys = new int[8];

    FlowRow(Context c, int gapPx, int fillMinPx) {
        super(c);
        gap = gapPx;
        fillMin = fillMinPx;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int maxW = Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
        int n = getChildCount();
        if (xs.length < n) {
            xs = new int[n + 8];
            ys = new int[n + 8];
        }
        int x = 0;
        int y = 0;
        int lineH = 0;
        for (int i = 0; i < n; i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) {
                continue;
            }
            boolean last = i == n - 1;
            int cw;
            if (last) {
                int left = maxW - x;
                if (left < fillMin && x > 0) { // no room for a useful box on this line: a line of its own
                    y += lineH + gap;
                    x = 0;
                    lineH = 0;
                    left = maxW;
                }
                cw = Math.max(left, 0);
                c.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            } else {
                c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                cw = c.getMeasuredWidth();
                if (x > 0 && x + cw > maxW) {
                    y += lineH + gap;
                    x = 0;
                    lineH = 0;
                }
            }
            xs[i] = x;
            ys[i] = y;
            x += cw + gap;
            lineH = Math.max(lineH, c.getMeasuredHeight());
        }
        int h = y + lineH + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), resolveSize(h, heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int n = getChildCount();
        for (int i = 0; i < n; i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) {
                continue;
            }
            int left = getPaddingLeft() + xs[i];
            int top = getPaddingTop() + ys[i];
            c.layout(left, top, left + c.getMeasuredWidth(), top + c.getMeasuredHeight());
        }
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }
}
