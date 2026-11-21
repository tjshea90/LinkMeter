package com.tj.linkmeter;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.AbsSeekBar;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;

/**
 * One look for "this was pressed", for every button of a screen: at the moment the finger lands, a tint comes over the button
 * (accent blue on an ordinary button, darker on a coloured one), and goes again when the finger lifts, but never sooner than
 * {@link #MIN_SHOW_MS} after it came, so even a quick tap is seen.
 *
 * Why not Android's own ripple: the tablet's launcher turns the animation scales to zero while Quickstep is in front ("Remove
 * animations"), and a ripple that has no time to run shows nothing at all; a button inside a scrolling screen also waits 100 ms
 * before it shows its pressed state. A plain tint that is switched on in the touch event itself has neither problem (field report
 * 2026-11-21: the buttons of the Calls tab "don't respond", nothing shows that a press was taken).
 *
 * A screen hands every touch event to {@link #onTouch} before it dispatches it (MainActivity.dispatchTouchEvent; the floating
 * phone's own window does the same). The button under the finger is found the way Android finds it: the topmost visible, enabled,
 * clickable view that contains the point. Views that have a look of their own (the bottom bar's buttons), text fields, sliders,
 * switches, pictures of the phone's screen and anything very large are left alone.
 */
final class PressFeedback {
    /** How long the tint stays at least, so a tap shorter than a few frames is still seen. */
    static final long MIN_SHOW_MS = 130L;

    /** A view that draws its own pressed look and does not want the tint. */
    interface OwnLook {}

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final int[] loc = new int[2];
    private View lit;
    private Drawable before;
    private long litAt;
    private float downX;
    private float downY;
    private final Runnable unlight = this::unlightNow;

    /** The screen's touch events, before they are dispatched. */
    void onTouch(View root, MotionEvent e) {
        if (root == null || e == null) {
            return;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                unlightNow();
                View v = hit(root, e.getRawX(), e.getRawY(), root.getWidth() * (long) root.getHeight());
                if (v != null) {
                    downX = e.getRawX();
                    downY = e.getRawY();
                    light(v);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (lit != null) {
                    float slop = ViewConfiguration.get(lit.getContext()).getScaledTouchSlop() * 2f;
                    if (Math.abs(e.getRawX() - downX) > slop || Math.abs(e.getRawY() - downY) > slop) {
                        unlightNow(); // the finger left the button, or this is a scroll: no press
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (lit != null) {
                    long left = MIN_SHOW_MS - (SystemClock.uptimeMillis() - litAt);
                    if (left <= 0) {
                        unlightNow();
                    } else {
                        ui.removeCallbacks(unlight);
                        ui.postDelayed(unlight, left);
                    }
                }
                break;
            default:
                break;
        }
    }

    /** Takes the tint off at once (the screen is going away, or a new touch begins). */
    void unlightNow() {
        ui.removeCallbacks(unlight);
        if (lit != null) {
            try {
                lit.setForeground(before);
            } catch (RuntimeException ignored) {
                // the view is gone: nothing to restore
            }
            lit = null;
            before = null;
        }
    }

    private void light(View v) {
        lit = v;
        litAt = SystemClock.uptimeMillis();
        before = v.getForeground();
        v.setForeground(tintFor(v));
    }

    /** The tint: its colour and its shape follow the kind of button. */
    static Drawable tintFor(View v) {
        float dp = v.getResources().getDisplayMetrics().density;
        int w = v.getWidth();
        int h = v.getHeight();
        GradientDrawable g = new GradientDrawable();
        if (v instanceof IconButton) {
            g.setShape(GradientDrawable.OVAL);
            g.setColor(((IconButton) v).hasBackground() ? 0x59000000 : 0x704C8DFF);
            return g;
        }
        if (v instanceof Button) {
            boolean main = ((Button) v).getCurrentTextColor() == Ui.ON_ACCENT; // the blue "main" button: a tint of its own colour is invisible
            g.setColor(main ? 0x59000000 : 0x804C8DFF);
            g.setCornerRadius(10 * dp);
            return new InsetDrawable(g, Math.round(3 * dp)); // the button's shape has a 3 dp margin (Ui.buttonShape)
        }
        g.setColor(0x594C8DFF);
        if (Math.abs(w - h) < 4 * dp && w <= 72 * dp) {
            g.setShape(GradientDrawable.OVAL);
        } else {
            g.setCornerRadius(Math.min(h / 2f, 14 * dp));
        }
        return g;
    }

    /** The topmost view under the point that a press should light, or null. x and y are screen coordinates. */
    private View hit(View v, float x, float y, long rootArea) {
        if (v.getVisibility() != View.VISIBLE || !contains(v, x, y)) {
            return null;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View f = hit(g.getChildAt(i), x, y, rootArea);
                if (f != null) {
                    return f;
                }
            }
        }
        return lights(v, rootArea) ? v : null;
    }

    private boolean contains(View v, float x, float y) {
        v.getLocationOnScreen(loc);
        return x >= loc[0] && x < loc[0] + v.getWidth() && y >= loc[1] && y < loc[1] + v.getHeight();
    }

    private static boolean lights(View v, long rootArea) {
        if (!v.isClickable() || !v.isEnabled() || v instanceof OwnLook) {
            return false;
        }
        if (v instanceof EditText || v instanceof AbsSeekBar || v instanceof CompoundButton
                || v instanceof android.view.SurfaceView || v instanceof android.view.TextureView) {
            return false;
        }
        long area = v.getWidth() * (long) v.getHeight();
        return rootArea <= 0 || area * 3 < rootArea * 2 && v.getHeight() < 220 * v.getResources().getDisplayMetrics().density;
    }
}
