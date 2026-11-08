package com.tj.linkmeter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import java.util.Locale;

/**
 * The speed-test screen: a gauge with a colour sweep, a live graph of the test so far,
 * and the three results. It redraws only when a value changes, and the needle glides
 * to each new value with a few frames of easing (no Animator, so it also works with
 * system animations switched off).
 */
final class SpeedView extends View {
    private static final float START = 135f;
    private static final float SWEEP = 270f;
    private static final float[] SCALES = {10f, 25f, 50f, 100f, 200f, 300f, 500f, 1000f, 2000f};

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Matrix m = new Matrix();

    private float target;
    private float shown;
    private float scaleMax = 25f;
    private int phase;
    private double ping;
    private double down;
    private double up;
    private final float[] sv = new float[600];
    private final int[] sph = new int[600];
    private int sn;
    private Shader sweep;
    private float gcx;
    private float gcy;

    private final Runnable glide = new Runnable() {
        @Override
        public void run() {
            float d = target - shown;
            if (Math.abs(d) < 0.05f) {
                shown = target;
                invalidate();
                return;
            }
            shown += d * 0.25f;
            invalidate();
            postOnAnimation(this);
        }
    };

    SpeedView(Context c) {
        super(c);
    }

    void reset() {
        sn = 0;
        target = 0;
        shown = 0;
        scaleMax = 25f;
        phase = SpeedTest.IDLE;
        ping = 0;
        down = 0;
        up = 0;
        invalidate();
    }

    void addSample(int ph, float v) {
        if (sn < sv.length) {
            sv[sn] = v;
            sph[sn] = ph;
            sn++;
        }
    }

    void setState(int ph, float live, double pingMs, double downMbps, double upMbps) {
        phase = ph;
        ping = pingMs;
        down = downMbps;
        up = upMbps;
        float shownTarget = live;
        if (ph == SpeedTest.DONE) {
            shownTarget = 0;
        }
        target = shownTarget;
        float top = (float) Math.max(live, Math.max(down, up));
        scaleMax = Math.max(scaleMax, niceScale(top * 1.1f));
        removeCallbacks(glide);
        postOnAnimation(glide);
        invalidate();
    }

    private static float niceScale(float v) {
        for (float s : SCALES) {
            if (v <= s) {
                return s;
            }
        }
        return SCALES[SCALES.length - 1];
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(glide);
        super.onDetachedFromWindow();
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }

    private float gaugeSize(float w) {
        return Math.min(w, dp(320));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        float g = gaugeSize(w);
        int h = (int) (g * 0.86f + dp(10) + dp(70) + dp(10) + dp(84));
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float g = gaugeSize(w);
        float stroke = dp(18);
        float radius = g / 2f - stroke;
        gcx = w / 2f;
        gcy = stroke + radius;
        SweepGradient sg = new SweepGradient(gcx, gcy,
                new int[] {Ui.ACCENT, Ui.GREEN, Ui.AMBER, Ui.RED, Ui.RED},
                new float[] {0f, 0.25f, 0.5f, 0.75f, 1f});
        m.setRotate(START, gcx, gcy);
        sg.setLocalMatrix(m);
        sweep = sg;
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth();
        float g = gaugeSize(w);
        float stroke = dp(18);
        float radius = g / 2f - stroke;
        r.set(gcx - radius, gcy - radius, gcx + radius, gcy + radius);

        // track and value arc
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0xFF2A2F37);
        cv.drawArc(r, START, SWEEP, false, p);
        float frac = Math.max(0f, Math.min(1f, shown / scaleMax));
        if (frac > 0.002f) {
            p.setShader(sweep);
            cv.drawArc(r, START, SWEEP * frac, false, p);
            p.setShader(null);
        }

        // ticks and scale labels
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStrokeWidth(dp(2));
        p.setColor(0xFF4A515C);
        for (int i = 0; i <= 10; i++) {
            double a = Math.toRadians(START + SWEEP * i / 10f);
            float r1 = radius - stroke * 0.8f;
            float r2 = r1 - dp(7);
            cv.drawLine(gcx + (float) Math.cos(a) * r1, gcy + (float) Math.sin(a) * r1,
                    gcx + (float) Math.cos(a) * r2, gcy + (float) Math.sin(a) * r2, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(sp(11));
        p.setColor(Ui.DIM);
        p.setTextAlign(Paint.Align.CENTER);
        label(cv, "0", START, radius - stroke - dp(20));
        label(cv, fmt(scaleMax / 2f), START + SWEEP / 2f, radius - stroke - dp(24));
        label(cv, fmt(scaleMax), START + SWEEP, radius - stroke - dp(20));

        // centre readout
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(Ui.DIM);
        p.setTextSize(sp(13));
        cv.drawText(phaseName(), gcx, gcy - dp(46), p);
        p.setColor(Ui.FG);
        p.setTextSize(sp(46));
        p.setTypeface(Typeface.DEFAULT_BOLD);
        cv.drawText(fmt(shown), gcx, gcy + dp(12), p);
        p.setTypeface(Typeface.DEFAULT);
        p.setColor(Ui.DIM);
        p.setTextSize(sp(14));
        cv.drawText("Mbps", gcx, gcy + dp(36), p);

        // live graph
        float gTop = g * 0.86f + dp(10);
        float gH = dp(70);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Ui.CARD);
        r.set(dp(4), gTop, w - dp(4), gTop + gH);
        cv.drawRoundRect(r, dp(10), dp(10), p);
        if (sn > 1) {
            float max = 1f;
            for (int i = 0; i < sn; i++) {
                max = Math.max(max, sv[i]);
            }
            float left = dp(14);
            float right = w - dp(14);
            float step = (right - left) / Math.max(63, sn - 1);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2.5f));
            p.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 1; i < sn; i++) {
                p.setColor(sph[i] == SpeedTest.UP ? Ui.ACCENT : Ui.GREEN);
                float x1 = left + (i - 1) * step;
                float x2 = left + i * step;
                float y1 = gTop + gH - dp(8) - (gH - dp(16)) * (sv[i - 1] / max);
                float y2 = gTop + gH - dp(8) - (gH - dp(16)) * (sv[i] / max);
                cv.drawLine(x1, y1, x2, y2, p);
            }
        }

        // results
        float rTop = gTop + gH + dp(10);
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        column(cv, w * 1f / 6f, rTop, "Ping ms", ping > 0 ? fmt((float) ping) : "--", Ui.FG);
        column(cv, w * 3f / 6f, rTop, "Download Mbps", down > 0 ? fmt((float) down) : "--", Ui.GREEN);
        column(cv, w * 5f / 6f, rTop, "Upload Mbps", up > 0 ? fmt((float) up) : "--", Ui.ACCENT);
    }

    private void label(Canvas cv, String s, float deg, float rad) {
        double a = Math.toRadians(deg);
        cv.drawText(s, gcx + (float) Math.cos(a) * rad, gcy + (float) Math.sin(a) * rad + dp(4), p);
    }

    private void column(Canvas cv, float cx, float top, String name, String value, int color) {
        p.setTypeface(Typeface.DEFAULT);
        p.setColor(Ui.DIM);
        p.setTextSize(sp(12));
        cv.drawText(name, cx, top + dp(16), p);
        p.setColor(color);
        p.setTextSize(sp(26));
        p.setTypeface(Typeface.DEFAULT_BOLD);
        cv.drawText(value, cx, top + dp(54), p);
        p.setTypeface(Typeface.DEFAULT);
    }

    private String phaseName() {
        switch (phase) {
            case SpeedTest.PING:
                return "PING";
            case SpeedTest.DOWN:
                return "DOWNLOAD";
            case SpeedTest.UP:
                return "UPLOAD";
            case SpeedTest.DONE:
                return "DONE";
            case SpeedTest.ERROR:
                return "ERROR";
            default:
                return "READY";
        }
    }

    private static String fmt(float v) {
        return v < 10f ? String.format(Locale.US, "%.1f", v) : String.format(Locale.US, "%.0f", v);
    }
}
