package com.tj.linkmeter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Tablet only: a headset volume as a row of rising bars, like a signal meter (the Phone tab's volume card and the floating phone). It only
 * draws what {@link VolumeRules.Row} says: {@code steps} bars, the first {@code filled} lit. Lit bars are the accent colour while the
 * buttons work and grey while they do not (no headset, the sound goes elsewhere), so a bar that cannot be changed never looks alive.
 */
final class VolumeBarView extends View {
    private static final int OFF_BAR = 0xFF333A44;
    private static final int DEAD_BAR = 0xFF5A616C;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float density;
    private int steps;
    private int filled;
    private boolean live;
    private int accent = Ui.ACCENT;

    VolumeBarView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        setMinimumHeight(Ui.dp(c, 26));
    }

    /** {@code steps} 0 draws a flat row of empty bars (the volume is not known). */
    void set(int steps, int filled, boolean live, int accent) {
        this.steps = steps;
        this.filled = Math.max(0, Math.min(steps, filled));
        this.live = live;
        this.accent = accent;
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED ? Math.round(120 * density) : MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getMode(hSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(hSpec) : Math.max(getSuggestedMinimumHeight(), Math.round(26 * density));
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas cv) {
        int n = steps > 0 ? steps : 10;
        float gap = (n > 10 ? 2f : 3f) * density;
        float w = getWidth();
        float h = getHeight();
        float bar = (w - gap * (n - 1)) / n;
        if (bar < 1f) {
            return;
        }
        float radius = Math.min(bar / 2f, 2.5f * density);
        for (int i = 0; i < n; i++) {
            float frac = n == 1 ? 1f : 0.32f + 0.68f * i / (n - 1);
            float top = h - h * frac;
            float left = i * (bar + gap);
            r.set(left, top, left + bar, h);
            paint.setColor(steps <= 0 ? OFF_BAR : i < filled ? (live ? accent : DEAD_BAR) : OFF_BAR);
            cv.drawRoundRect(r, radius, radius, paint);
        }
    }
}
