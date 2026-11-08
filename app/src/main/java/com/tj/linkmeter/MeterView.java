package com.tj.linkmeter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

/**
 * One card: a title, a big value, a small line, and either signal bars or a
 * battery bar. Draws with a handful of shapes and no bitmaps, and only redraws
 * when something it shows has actually changed.
 */
final class MeterView extends View {
    private static final int CARD = 0xFF1B1E24;
    private static final int OFF = 0xFF3A3F47;
    private static final int TITLE = 0xFF9AA0A9;
    private static final int BIG = 0xFFF2F4F7;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private String title = "";
    private String big = "--";
    private String sub = "";
    private int level = -1;     // 0..4 signal bars; -1 shows all bars dim
    private float frac = -1f;   // 0..1 draws a battery bar instead of signal bars
    private int color = 0xFF8A8F98;

    MeterView(Context c) {
        super(c);
    }

    /** Pass frac >= 0 for a battery bar, or frac < 0 for signal bars. */
    void set(String t, String b, String s, int lvl, float f, int col) {
        if (t.equals(title) && b.equals(big) && s.equals(sub)
                && lvl == level && f == frac && col == color) {
            return;
        }
        title = t;
        big = b;
        sub = s;
        level = lvl;
        frac = f;
        color = col;
        invalidate();
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }

    /** Height of one text line at the given size, so large system fonts never clip. */
    private float line(float spSize) {
        p.setTextSize(sp(spSize));
        return p.descent() - p.ascent();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        float h = dp(14) * 2 + line(13) + line(34) + line(13);
        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), widthSpec), (int) Math.ceil(h));
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth();
        float h = getHeight();
        float pad = dp(14);

        p.setStyle(Paint.Style.FILL);
        p.setColor(CARD);
        r.set(0, 0, w, h);
        cv.drawRoundRect(r, dp(14), dp(14), p);

        p.setFakeBoldText(false);
        p.setTextSize(sp(13));
        p.setColor(TITLE);
        float y1 = pad - p.ascent();
        cv.drawText(title, pad, y1, p);
        float d13 = p.descent();
        float a13 = p.ascent();

        p.setTextSize(sp(34));
        p.setFakeBoldText(true);
        p.setColor(BIG);
        float y2 = y1 + d13 - p.ascent();
        cv.drawText(big, pad, y2, p);
        float d34 = p.descent();
        p.setFakeBoldText(false);

        p.setTextSize(sp(13));
        p.setColor(TITLE);
        float y3 = y2 + d34 - a13;
        cv.drawText(sub, pad, y3, p);

        if (frac >= 0f) {
            drawBattery(cv, w, h, pad);
        } else {
            drawBars(cv, w, h, pad);
        }
    }

    private void drawBars(Canvas cv, float w, float h, float pad) {
        float barW = dp(11);
        float gap = dp(5);
        float area = Math.min(h - 2 * pad, dp(46));
        float bottom = h / 2f + area / 2f;
        float right = w - pad;
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 4; i++) {
            float left = right - (4 - i) * barW - (3 - i) * gap;
            float top = bottom - area * (i + 1) / 4f;
            p.setColor(i < level ? color : OFF);
            r.set(left, top, left + barW, bottom);
            cv.drawRoundRect(r, dp(2), dp(2), p);
        }
    }

    private void drawBattery(Canvas cv, float w, float h, float pad) {
        float bw = dp(110);
        float bh = dp(28);
        float right = w - pad - dp(5);
        float left = right - bw;
        float top = h / 2f - bh / 2f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2));
        p.setColor(OFF);
        r.set(left, top, right, top + bh);
        cv.drawRoundRect(r, dp(5), dp(5), p);

        p.setStyle(Paint.Style.FILL);
        float inset = dp(4);
        float fillW = (bw - 2 * inset) * Math.max(0f, Math.min(1f, frac));
        if (fillW > 0f) {
            p.setColor(color);
            r.set(left + inset, top + inset, left + inset + fillW, top + bh - inset);
            cv.drawRoundRect(r, dp(2), dp(2), p);
        }
        p.setColor(OFF);
        r.set(right + dp(1), top + bh * 0.3f, right + dp(4), top + bh * 0.7f);
        cv.drawRoundRect(r, dp(1), dp(1), p);
    }
}
