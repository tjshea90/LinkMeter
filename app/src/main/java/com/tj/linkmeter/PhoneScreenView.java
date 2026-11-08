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
        /** action: 0 = finger down, 1 = move, 2 = up. x and y are in the phone's own pixels. */
        void touch(int action, int x, int y);
    }

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private Bitmap bmp;
    private int phoneW;
    private int phoneH;
    private String note = "Connecting…";
    private Listener listener;
    private boolean tracking;
    private long lastMove;
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
        int x = toPhone(e.getX(), dst.left, dst.width(), phoneW);
        int y = toPhone(e.getY(), dst.top, dst.height(), phoneH);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tracking = dst.contains(e.getX(), e.getY());
                if (tracking) {
                    markX = e.getX();
                    markY = e.getY();
                    invalidate();
                    listener.touch(0, x, y);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (tracking && e.getEventTime() - lastMove >= 25) {
                    lastMove = e.getEventTime();
                    listener.touch(1, x, y);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (tracking) {
                    tracking = false;
                    listener.touch(2, x, y);
                }
                markX = -1; // the touch marker goes away with the finger
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
