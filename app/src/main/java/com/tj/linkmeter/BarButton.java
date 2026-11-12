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

    /** The label's size in dp (the bottom bar uses a bigger one than the remote screen's narrow side bars). */
    void setLabelSize(float dps) {
        text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dps, getResources().getDisplayMetrics()));
        invalidate();
    }

    /** Highlights the button (a mode that is on). */
    void setActive(boolean a) {
        if (a != active) {
            active = a;
            invalidate();
        }
    }

    private int badge;
    private final Paint badgeText = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * A number of new things on this screen (the Texts tab's unread texts), 0 for none: the icon lights up and a small red circle with
     * the number sits on its corner, as in the messaging apps.
     */
    void setBadge(int n) {
        int b = Math.max(0, n);
        if (b != badge) {
            badge = b;
            setContentDescription((label.isEmpty() ? kind.name().toLowerCase() : label) + (b > 0 ? ", " + b + " new" : ""));
            invalidate();
        }
    }

    int badge() {
        return badge;
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
        // lit when it is the screen on show, and when it has news
        int color = !isEnabled() ? 0x55F2F4F7 : (active || badge > 0 ? Ui.ACCENT : Ui.FG);
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
            text.setFakeBoldText(badge > 0 && !active);
            canvas.drawText(label, cx, h - 5 * dp, text);
        }
        if (badge > 0) {
            String s = badge > 99 ? "99+" : String.valueOf(badge);
            badgeText.setTextAlign(Paint.Align.CENTER);
            badgeText.setTypeface(Typeface.DEFAULT_BOLD);
            badgeText.setTextSize(10.5f * dp);
            badgeText.setColor(0xFFFFFFFF);
            float bh = 17 * dp;
            float bw = Math.max(bh, badgeText.measureText(s) + 9 * dp);
            float bx = cx + r * 0.62f + bw / 2f - 6 * dp; // over the icon's top right corner
            float by = cy - r * 0.78f;
            bx = Math.min(bx, w - bw / 2f - 1 * dp);
            // a ring in the bar's colour keeps the circle apart from the icon under it
            fill.setColor(Ui.CARD);
            rect.set(bx - bw / 2f - 2 * dp, by - bh / 2f - 2 * dp, bx + bw / 2f + 2 * dp, by + bh / 2f + 2 * dp);
            canvas.drawRoundRect(rect, bh / 2f + 2 * dp, bh / 2f + 2 * dp, fill);
            fill.setColor(Ui.RED);
            rect.set(bx - bw / 2f, by - bh / 2f, bx + bw / 2f, by + bh / 2f);
            canvas.drawRoundRect(rect, bh / 2f, bh / 2f, fill);
            Paint.FontMetrics fm = badgeText.getFontMetrics();
            canvas.drawText(s, bx, by - (fm.ascent + fm.descent) / 2f, badgeText);
        }
        cv = null;
    }
}
