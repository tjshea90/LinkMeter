package com.tj.linkmeter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;

/** One button of the remote screen's side bars: an icon (see Icons) with a small label under it. */
final class BarButton extends View {
    private Icons.Kind kind;
    private String label;
    private boolean active;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private Canvas cv;

    private final Icons.Pen pen = new Icons.Pen() {
        @Override
        public void line(float x1, float y1, float x2, float y2) {
            cv.drawLine(x1, y1, x2, y2, stroke);
        }

        @Override
        public void circle(float cx, float cy, float r) {
            cv.drawCircle(cx, cy, r, stroke);
        }

        @Override
        public void dot(float cx, float cy, float r) {
            cv.drawCircle(cx, cy, r, fill);
        }

        @Override
        public void rrect(float l, float t, float r, float b, float rx) {
            rect.set(l, t, r, b);
            cv.drawRoundRect(rect, rx, rx, stroke);
        }

        @Override
        public void arc(float l, float t, float r, float b, float start, float sweep) {
            rect.set(l, t, r, b);
            cv.drawArc(rect, start, sweep, false, stroke);
        }

        @Override
        public void poly(float[] xy, boolean closed, boolean filled) {
            path.reset();
            path.moveTo(xy[0], xy[1]);
            for (int i = 2; i < xy.length; i += 2) {
                path.lineTo(xy[i], xy[i + 1]);
            }
            if (closed) {
                path.close();
            }
            cv.drawPath(path, filled ? fill : stroke);
        }
    };

    BarButton(Context c, Icons.Kind kind, String label, View.OnClickListener l) {
        super(c);
        this.kind = kind;
        this.label = label;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 9.5f, c.getResources().getDisplayMetrics()));
        setClickable(true);
        setOnClickListener(l);
        setContentDescription(label.isEmpty() ? kind.name().toLowerCase() : label);
    }

    /** Changes the icon and label (for a button that is two things, like Wake and Sleep). */
    void set(Icons.Kind k, String l) {
        if (k != kind || !l.equals(label)) {
            kind = k;
            label = l;
            setContentDescription(l.isEmpty() ? k.name().toLowerCase() : l);
            invalidate();
        }
    }

    /** Highlights the button (a mode that is on). */
    void setActive(boolean a) {
        if (a != active) {
            active = a;
            invalidate();
        }
    }

    @Override
    public void setPressed(boolean p) {
        super.setPressed(p);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        cv = canvas;
        float w = getWidth();
        float h = getHeight();
        float dp = getResources().getDisplayMetrics().density;
        if (isPressed() || active) {
            fill.setColor(isPressed() ? 0x40FFFFFF : 0x334C8DFF);
            rect.set(3 * dp, 3 * dp, w - 3 * dp, h - 3 * dp);
            canvas.drawRoundRect(rect, 10 * dp, 10 * dp, fill);
        }
        int color = !isEnabled() ? 0x55F2F4F7 : (active ? Ui.ACCENT : Ui.FG);
        float labelH = label.isEmpty() ? 0 : text.getTextSize() + 3 * dp;
        float r = Math.min(w, h - labelH) * 0.30f;
        float cx = w / 2f;
        float cy = (h - labelH) / 2f;
        stroke.setColor(color);
        stroke.setStrokeWidth(Math.max(1.5f * dp, r * 0.17f));
        fill.setColor(color);
        Icons.draw(kind, pen, cx, cy, r);
        if (!label.isEmpty()) {
            text.setColor(color);
            canvas.drawText(label, cx, h - 5 * dp, text);
        }
        cv = null;
    }
}
