package com.tj.linkmeter;

/**
 * The remote screen's button icons as plain geometry, drawn through a tiny {@link Pen}. No Android
 * classes here, so the same code is drawn by a Java2D pen in tools/test/IconSheet.java to look at the
 * icons on a computer. Every icon fits in a square of 2r by 2r around (cx, cy).
 */
final class Icons {
    private Icons() {}

    enum Kind {
        BACK, HOME, RECENTS, VOL_UP, VOL_DOWN, KEYBOARD, POWER, LOCK, SLIDERS, FULL, EXIT_FULL, CLOSE,
        ENTER, BACKSPACE, TAB, UP, DOWN, LEFT, RIGHT
    }

    /** The drawing operations the icons need. Angles are degrees, clockwise from 3 o'clock. */
    interface Pen {
        void line(float x1, float y1, float x2, float y2);

        void circle(float cx, float cy, float r);

        /** A filled circle. */
        void dot(float cx, float cy, float r);

        void rrect(float l, float t, float r, float b, float rx);

        void arc(float l, float t, float r, float b, float start, float sweep);

        /** Points as x0, y0, x1, y1, ... */
        void poly(float[] xy, boolean closed, boolean filled);
    }

    private static float[] pts(float cx, float cy, float r, float... uv) {
        float[] out = new float[uv.length];
        for (int i = 0; i < uv.length; i += 2) {
            out[i] = cx + uv[i] * r;
            out[i + 1] = cy + uv[i + 1] * r;
        }
        return out;
    }

    private static void speaker(Pen p, float cx, float cy, float r) {
        p.poly(pts(cx, cy, r, -0.85f, -0.25f, -0.45f, -0.25f, 0f, -0.65f, 0f, 0.65f, -0.45f, 0.25f, -0.85f, 0.25f),
                true, false);
    }

    static void draw(Kind k, Pen p, float cx, float cy, float r) {
        switch (k) {
            case BACK:
                p.poly(pts(cx, cy, r, 0.45f, -0.6f, -0.5f, 0f, 0.45f, 0.6f), true, false);
                break;
            case HOME:
                p.circle(cx, cy, 0.55f * r);
                break;
            case RECENTS:
                p.rrect(cx - 0.5f * r, cy - 0.5f * r, cx + 0.5f * r, cy + 0.5f * r, 0.14f * r);
                break;
            case VOL_UP:
                speaker(p, cx, cy, r);
                p.line(cx + 0.3f * r, cy, cx + 0.85f * r, cy);
                p.line(cx + 0.575f * r, cy - 0.275f * r, cx + 0.575f * r, cy + 0.275f * r);
                break;
            case VOL_DOWN:
                speaker(p, cx, cy, r);
                p.line(cx + 0.3f * r, cy, cx + 0.85f * r, cy);
                break;
            case KEYBOARD:
                p.rrect(cx - 0.95f * r, cy - 0.58f * r, cx + 0.95f * r, cy + 0.58f * r, 0.16f * r);
                for (int i = 0; i < 5; i++) {
                    p.dot(cx + (-0.6f + 0.3f * i) * r, cy - 0.27f * r, 0.065f * r);
                }
                for (int i = 0; i < 4; i++) {
                    p.dot(cx + (-0.45f + 0.3f * i) * r, cy, 0.065f * r);
                }
                p.line(cx - 0.45f * r, cy + 0.3f * r, cx + 0.45f * r, cy + 0.3f * r);
                break;
            case POWER:
                p.arc(cx - 0.6f * r, cy - 0.5f * r, cx + 0.6f * r, cy + 0.7f * r, -55f, 290f);
                p.line(cx, cy - 0.85f * r, cx, cy - 0.05f * r);
                break;
            case LOCK:
                p.rrect(cx - 0.55f * r, cy - 0.05f * r, cx + 0.55f * r, cy + 0.75f * r, 0.12f * r);
                p.arc(cx - 0.34f * r, cy - 0.7f * r, cx + 0.34f * r, cy - 0.02f * r, 180f, 180f);
                p.line(cx - 0.34f * r, cy - 0.36f * r, cx - 0.34f * r, cy - 0.05f * r);
                p.line(cx + 0.34f * r, cy - 0.36f * r, cx + 0.34f * r, cy - 0.05f * r);
                break;
            case SLIDERS:
                p.line(cx - 0.85f * r, cy - 0.5f * r, cx + 0.85f * r, cy - 0.5f * r);
                p.line(cx - 0.85f * r, cy, cx + 0.85f * r, cy);
                p.line(cx - 0.85f * r, cy + 0.5f * r, cx + 0.85f * r, cy + 0.5f * r);
                p.dot(cx + 0.3f * r, cy - 0.5f * r, 0.2f * r);
                p.dot(cx - 0.35f * r, cy, 0.2f * r);
                p.dot(cx + 0.45f * r, cy + 0.5f * r, 0.2f * r);
                break;
            case FULL:
                p.poly(pts(cx, cy, r, -0.8f, -0.35f, -0.8f, -0.8f, -0.35f, -0.8f), false, false);
                p.poly(pts(cx, cy, r, 0.35f, -0.8f, 0.8f, -0.8f, 0.8f, -0.35f), false, false);
                p.poly(pts(cx, cy, r, -0.8f, 0.35f, -0.8f, 0.8f, -0.35f, 0.8f), false, false);
                p.poly(pts(cx, cy, r, 0.35f, 0.8f, 0.8f, 0.8f, 0.8f, 0.35f), false, false);
                break;
            case EXIT_FULL:
                p.poly(pts(cx, cy, r, -0.8f, -0.3f, -0.3f, -0.3f, -0.3f, -0.8f), false, false);
                p.poly(pts(cx, cy, r, 0.3f, -0.8f, 0.3f, -0.3f, 0.8f, -0.3f), false, false);
                p.poly(pts(cx, cy, r, -0.8f, 0.3f, -0.3f, 0.3f, -0.3f, 0.8f), false, false);
                p.poly(pts(cx, cy, r, 0.3f, 0.8f, 0.3f, 0.3f, 0.8f, 0.3f), false, false);
                break;
            case CLOSE:
                p.line(cx - 0.55f * r, cy - 0.55f * r, cx + 0.55f * r, cy + 0.55f * r);
                p.line(cx + 0.55f * r, cy - 0.55f * r, cx - 0.55f * r, cy + 0.55f * r);
                break;
            case ENTER:
                p.poly(pts(cx, cy, r, 0.75f, -0.6f, 0.75f, 0.1f, -0.6f, 0.1f), false, false);
                p.poly(pts(cx, cy, r, -0.25f, -0.25f, -0.6f, 0.1f, -0.25f, 0.45f), false, false);
                break;
            case BACKSPACE:
                p.poly(pts(cx, cy, r, -0.9f, 0f, -0.4f, -0.58f, 0.9f, -0.58f, 0.9f, 0.58f, -0.4f, 0.58f), true, false);
                p.line(cx - 0.05f * r, cy - 0.25f * r, cx + 0.45f * r, cy + 0.25f * r);
                p.line(cx + 0.45f * r, cy - 0.25f * r, cx - 0.05f * r, cy + 0.25f * r);
                break;
            case TAB:
                p.line(cx - 0.85f * r, cy, cx + 0.5f * r, cy);
                p.poly(pts(cx, cy, r, 0.15f, -0.35f, 0.5f, 0f, 0.15f, 0.35f), false, false);
                p.line(cx + 0.85f * r, cy - 0.45f * r, cx + 0.85f * r, cy + 0.45f * r);
                break;
            case UP:
                p.poly(pts(cx, cy, r, 0f, -0.55f, 0.55f, 0.4f, -0.55f, 0.4f), true, true);
                break;
            case DOWN:
                p.poly(pts(cx, cy, r, 0f, 0.55f, 0.55f, -0.4f, -0.55f, -0.4f), true, true);
                break;
            case LEFT:
                p.poly(pts(cx, cy, r, -0.55f, 0f, 0.4f, -0.55f, 0.4f, 0.55f), true, true);
                break;
            case RIGHT:
                p.poly(pts(cx, cy, r, 0.55f, 0f, -0.4f, -0.55f, -0.4f, 0.55f), true, true);
                break;
            default:
                break;
        }
    }
}
