package com.tj.linkmeter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * A picture that fills the view, and can be pinched, dragged and double-tapped to zoom. Made from the platform's own gesture
 * detectors (no library). It never lets the picture leave the view: smaller than the view it is centred, larger it stays edge to edge.
 */
final class ZoomView extends View {
    private Bitmap bitmap;
    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private final RectF rect = new RectF();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private float fitScale = 1f;
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;

    ZoomView(Context c) {
        super(c);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                matrix.postTranslate(-dx, -dy);
                keepInside();
                invalidate();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (scale() > fitScale * 1.5f) {
                    fit();
                } else {
                    zoomBy(2.5f, e.getX(), e.getY());
                }
                invalidate();
                return true;
            }
        });
    }

    /** Shows this picture, fitted to the view. */
    void setBitmap(Bitmap b) {
        bitmap = b;
        fit();
        invalidate();
    }

    boolean hasBitmap() {
        return bitmap != null;
    }

    private void fit() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        fitScale = Math.min((float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
        matrix.reset();
        matrix.postScale(fitScale, fitScale);
        matrix.postTranslate((getWidth() - bitmap.getWidth() * fitScale) / 2f, (getHeight() - bitmap.getHeight() * fitScale) / 2f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fit();
    }

    private float scale() {
        matrix.getValues(values);
        return values[Matrix.MSCALE_X];
    }

    private void zoomBy(float factor, float fx, float fy) {
        float cur = scale();
        if (cur <= 0f) {
            return;
        }
        float target = Math.max(fitScale, Math.min(fitScale * 6f, cur * factor));
        float f = target / cur;
        matrix.postScale(f, f, fx, fy);
        keepInside();
        invalidate();
    }

    private void keepInside() {
        if (bitmap == null) {
            return;
        }
        rect.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
        matrix.mapRect(rect);
        float dx = 0;
        float dy = 0;
        if (rect.width() <= getWidth()) {
            dx = (getWidth() - rect.width()) / 2f - rect.left;
        } else if (rect.left > 0) {
            dx = -rect.left;
        } else if (rect.right < getWidth()) {
            dx = getWidth() - rect.right;
        }
        if (rect.height() <= getHeight()) {
            dy = (getHeight() - rect.height()) / 2f - rect.top;
        } else if (rect.top > 0) {
            dy = -rect.top;
        } else if (rect.bottom < getHeight()) {
            dy = getHeight() - rect.bottom;
        }
        matrix.postTranslate(dx, dy);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, matrix, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        if (!scaler.isInProgress()) {
            gestures.onTouchEvent(e);
        }
        return true;
    }
}
