package com.tj.linkmeter;

import android.content.Context;
import android.view.MotionEvent;
import android.view.SurfaceView;

/** A video surface that keeps the phone's shape inside whatever space it is given, and reports touches in phone pixels. */
final class AspectSurfaceView extends SurfaceView {
    interface Listener {
        void touch(int action, int x, int y);
    }

    private int vw;
    private int vh;
    private int phoneW;
    private int phoneH;
    private Listener listener;
    private boolean tracking;
    private long lastMove;

    AspectSurfaceView(Context c) {
        super(c);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setAspect(int w, int h) {
        if (w != vw || h != vh) {
            vw = w;
            vh = h;
            requestLayout();
        }
    }

    void setPhoneSize(int w, int h) {
        phoneW = w;
        phoneH = h;
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int mw = MeasureSpec.getSize(ws);
        int mh = MeasureSpec.getSize(hs);
        if (vw <= 0 || vh <= 0) {
            setMeasuredDimension(mw, mh);
            return;
        }
        float s = Math.min((float) mw / vw, (float) mh / vh);
        setMeasuredDimension(Math.round(vw * s), Math.round(vh * s));
    }

    private int map(float v, int size, int phone) {
        return Math.max(0, Math.min(phone - 1, Math.round(v / size * phone)));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (listener == null || phoneW <= 0 || getWidth() <= 0 || getHeight() <= 0) {
            return true;
        }
        int x = map(e.getX(), getWidth(), phoneW);
        int y = map(e.getY(), getHeight(), phoneH);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tracking = true;
                listener.touch(0, x, y);
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
                return true;
            default:
                return true;
        }
    }
}
