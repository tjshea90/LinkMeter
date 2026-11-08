package com.tj.linkmeter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

/**
 * Shows the phone's screen picture, fitted into the space with black bars, and turns touches on it
 * into taps, long presses and swipes in the phone's own pixel positions.
 */
final class PhoneScreenView extends View {
    interface Listener {
        void tap(int x, int y);

        void swipe(int x1, int y1, int x2, int y2, int ms);
    }

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private Bitmap bmp;
    private int phoneW;
    private int phoneH;
    private String note = "Connecting…";
    private Listener listener;
    private float downX;
    private float downY;
    private long downT;
    private float markX = -1;
    private float markY = -1;

    PhoneScreenView(Context c) {
        super(c);
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Shows a new picture. phoneW/H are the phone's real screen size, used to place taps. */
    void setFrame(Bitmap b, int w, int h) {
        Bitmap old = bmp;
        bmp = b;
        phoneW = w;
        phoneH = h;
        note = "";
        invalidate();
        if (old != null && old != b && !old.isRecycled()) {
            old.recycle();
        }
    }

    /** A message over the picture (or on black if there is none yet). Empty clears it. */
    void setNote(String s) {
        if (!s.equals(note)) {
            note = s;
            invalidate();
        }
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onDraw(Canvas cv) {
        cv.drawColor(0xFF000000);
        if (bmp != null) {
            float s = Math.min((float) getWidth() / bmp.getWidth(), (float) getHeight() / bmp.getHeight());
            float w = bmp.getWidth() * s;
            float h = bmp.getHeight() * s;
            dst.set((getWidth() - w) / 2f, (getHeight() - h) / 2f, (getWidth() + w) / 2f,
                    (getHeight() + h) / 2f);
            cv.drawBitmap(bmp, null, dst, paint);
            if (!note.isEmpty()) {
                paint.setColor(0xB0000000);
                cv.drawRect(dst, paint);
            }
            if (markX >= 0) {
                paint.setColor(0x80FFFFFF);
                cv.drawCircle(markX, markY, sp(14), paint);
            }
        }
        if (!note.isEmpty()) {
            paint.setColor(0xFFF2F4F7);
            paint.setTextSize(sp(15));
            paint.setTextAlign(Paint.Align.CENTER);
            String[] lines = note.split("\n");
            float y = getHeight() / 2f - (lines.length - 1) * sp(10);
            for (String l : lines) {
                cv.drawText(l, getWidth() / 2f, y, paint);
                y += sp(20);
            }
        }
    }

    private int toPhone(float v, float from, float size, int phone) {
        int p = Math.round((v - from) / size * phone);
        return Math.max(0, Math.min(phone - 1, p));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (bmp == null || listener == null || phoneW <= 0 || dst.width() <= 0) {
            return true;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downT = e.getEventTime();
                return true;
            case MotionEvent.ACTION_UP: {
                if (!dst.contains(downX, downY)) {
                    return true;
                }
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                long dt = e.getEventTime() - downT;
                int x1 = toPhone(downX, dst.left, dst.width(), phoneW);
                int y1 = toPhone(downY, dst.top, dst.height(), phoneH);
                float slop = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12,
                        getResources().getDisplayMetrics());
                markX = downX;
                markY = downY;
                invalidate();
                if (Math.hypot(dx, dy) < slop) {
                    if (dt > 500) {
                        listener.swipe(x1, y1, x1, y1, (int) Math.min(dt, 2500)); // long press
                    } else {
                        listener.tap(x1, y1);
                    }
                } else {
                    int x2 = toPhone(e.getX(), dst.left, dst.width(), phoneW);
                    int y2 = toPhone(e.getY(), dst.top, dst.height(), phoneH);
                    listener.swipe(x1, y1, x2, y2, (int) Math.max(80, Math.min(dt, 1200)));
                }
                return true;
            }
            default:
                return true;
        }
    }
}
