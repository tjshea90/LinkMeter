package com.tj.linkmeter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * An icon of {@link Icons} on its own, in any colour, optionally on a round background: the buttons of the Texts screens (search,
 * more, delete, send, the round "new message" button). Drawn with the same geometry as the remote screen's buttons.
 */
final class IconButton extends View {
    private Icons.Kind kind;
    private int ink;
    private int background;
    private float scale = 0.5f;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint back = new Paint(Paint.ANTI_ALIAS_FLAG);
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

    /** @param background the colour of a round background, or 0 for none; @param label what a screen reader says */
    IconButton(Context c, Icons.Kind kind, int ink, int background, String label, View.OnClickListener l) {
        super(c);
        this.kind = kind;
        this.ink = ink;
        this.background = background;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        back.setStyle(Paint.Style.FILL);
        setContentDescription(label);
        if (l != null) {
            setClickable(true);
            setOnClickListener(l);
        }
    }

    void set(Icons.Kind kind, int ink, int background) {
        this.kind = kind;
        this.ink = ink;
        this.background = background;
        invalidate();
    }

    /** How much of the button the icon fills (0.5 = half its width). */
    void setScale(float s) {
        scale = s;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        cv = canvas;
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float size = Math.min(w, h);
        if (background != 0) {
            back.setColor(background);
            canvas.drawCircle(cx, cy, size / 2f, back);
        }
        float r = size * scale / 2f;
        stroke.setColor(ink);
        fill.setColor(ink);
        stroke.setStrokeWidth(Math.max(2f, r * 0.17f));
        Icons.draw(kind, pen, cx, cy, r);
        cv = null;
    }
}
