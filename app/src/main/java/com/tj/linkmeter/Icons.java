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
        ENTER, BACKSPACE, TAB, UP, DOWN, LEFT, RIGHT, PAPERCLIP,
        // the bottom bar's tabs
        HOUSE, GAUGE, BARS, PHONE, SCREEN, FOLDER, CHAT, GEAR,
        // the two ways to the phone
        BLUETOOTH, HOTSPOT,
        // the Texts screens
        PLUS, SEARCH, TRASH, PIN, ARCHIVE, SEND, MORE, CHECK, MUTE
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

    /** Points of a half circle (clockwise on the screen) from one end of its diameter to the other, in the 24-unit box of the paperclip. */
    private static void halfCircle(java.util.List<Float> out, double x1, double y1, double x2, double y2) {
        double mx = (x1 + x2) / 2;
        double my = (y1 + y2) / 2;
        double rad = Math.hypot(x2 - x1, y2 - y1) / 2;
        double a0 = Math.atan2(y1 - my, x1 - mx);
        for (int i = 1; i < 12; i++) {
            double a = a0 + Math.PI * i / 12;
            out.add((float) (mx + rad * Math.cos(a)));
            out.add((float) (my + rad * Math.sin(a)));
        }
    }

    /** A slanted paperclip: three half circles joined by straight pieces (the common 24-unit icon shape). */
    private static float[] paperclip(float cx, float cy, float r) {
        java.util.List<Float> v = new java.util.ArrayList<>();
        double[][] corners = {{21.44, 11.05}, {12.25, 20.24}};
        v.add((float) corners[0][0]);
        v.add((float) corners[0][1]);
        v.add((float) corners[1][0]);
        v.add((float) corners[1][1]);
        halfCircle(v, 12.25, 20.24, 3.76, 11.75);
        v.add(3.76f);
        v.add(11.75f);
        v.add(12.33f);
        v.add(3.18f);
        halfCircle(v, 12.33, 3.18, 18.0, 8.84);
        v.add(18.0f);
        v.add(8.84f);
        v.add(9.41f);
        v.add(17.41f);
        halfCircle(v, 9.41, 17.41, 6.58, 14.58);
        v.add(6.58f);
        v.add(14.58f);
        v.add(15.07f);
        v.add(6.1f);
        float[] out = new float[v.size()];
        for (int i = 0; i < out.length; i += 2) {
            out[i] = cx + (v.get(i) - 12f) / 12f * r * 1.05f;
            out[i + 1] = cy + (v.get(i + 1) - 12f) / 12f * r * 1.05f;
        }
        return out;
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
            case PAPERCLIP:
                p.poly(paperclip(cx, cy, r), false, false);
                break;
            case HOUSE:
                p.poly(pts(cx, cy, r, -0.9f, -0.05f, 0f, -0.82f, 0.9f, -0.05f), false, false);
                p.poly(pts(cx, cy, r, -0.62f, -0.22f, -0.62f, 0.78f, 0.62f, 0.78f, 0.62f, -0.22f), false, false);
                p.poly(pts(cx, cy, r, -0.17f, 0.78f, -0.17f, 0.3f, 0.17f, 0.3f, 0.17f, 0.78f), false, false);
                break;
            case GAUGE:
                // a speedometer: half a dial, a needle, its hub
                p.arc(cx - 0.9f * r, cy - 0.7f * r, cx + 0.9f * r, cy + 1.1f * r, 180f, 180f);
                p.line(cx - 0.9f * r, cy + 0.2f * r, cx + 0.9f * r, cy + 0.2f * r);
                p.line(cx, cy + 0.2f * r, cx + 0.42f * r, cy - 0.38f * r);
                p.dot(cx, cy + 0.2f * r, 0.12f * r);
                break;
            case BARS:
                // signal bars, rising to the right
                for (int i = 0; i < 4; i++) {
                    float l = -0.86f + i * 0.46f;
                    float top = 0.42f - i * 0.38f;
                    p.poly(pts(cx, cy, r, l, 0.78f, l, top, l + 0.3f, top, l + 0.3f, 0.78f), true, true);
                }
                break;
            case PHONE:
                p.rrect(cx - 0.5f * r, cy - 0.88f * r, cx + 0.5f * r, cy + 0.88f * r, 0.16f * r);
                p.line(cx - 0.14f * r, cy + 0.62f * r, cx + 0.14f * r, cy + 0.62f * r);
                break;
            case SCREEN:
                // a phone's picture inside a frame: the remote screen
                p.rrect(cx - 0.92f * r, cy - 0.7f * r, cx + 0.92f * r, cy + 0.7f * r, 0.14f * r);
                p.rrect(cx - 0.26f * r, cy - 0.42f * r, cx + 0.26f * r, cy + 0.42f * r, 0.08f * r);
                break;
            case FOLDER:
                p.poly(pts(cx, cy, r, -0.88f, -0.6f, -0.3f, -0.6f, -0.08f, -0.34f, 0.88f, -0.34f, 0.88f, 0.66f, -0.88f, 0.66f), true, false);
                break;
            case CHAT:
                p.poly(pts(cx, cy, r, -0.88f, -0.7f, 0.88f, -0.7f, 0.88f, 0.34f, -0.2f, 0.34f, -0.56f, 0.8f, -0.56f, 0.34f, -0.88f, 0.34f), true, false);
                p.line(cx - 0.5f * r, cy - 0.36f * r, cx + 0.5f * r, cy - 0.36f * r);
                p.line(cx - 0.5f * r, cy - 0.02f * r, cx + 0.2f * r, cy - 0.02f * r);
                break;
            case GEAR:
                p.circle(cx, cy, 0.3f * r);
                p.circle(cx, cy, 0.62f * r);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4;
                    float ca = (float) Math.cos(a);
                    float sa = (float) Math.sin(a);
                    p.line(cx + 0.62f * r * ca, cy + 0.62f * r * sa, cx + 0.9f * r * ca, cy + 0.9f * r * sa);
                }
                break;
            case BLUETOOTH:
                p.poly(pts(cx, cy, r, -0.42f, -0.42f, 0.42f, 0.42f, 0f, 0.86f, 0f, -0.86f, 0.42f, -0.42f, -0.42f, 0.42f), false, false);
                break;
            case HOTSPOT:
                // a dot that sends rings upward: the phone as a hotspot
                p.dot(cx, cy + 0.5f * r, 0.16f * r);
                p.arc(cx - 0.45f * r, cy + 0.05f * r, cx + 0.45f * r, cy + 0.95f * r, 215f, 110f);
                p.arc(cx - 0.9f * r, cy - 0.4f * r, cx + 0.9f * r, cy + 1.4f * r, 222f, 96f);
                break;
            case PLUS:
                p.line(cx - 0.7f * r, cy, cx + 0.7f * r, cy);
                p.line(cx, cy - 0.7f * r, cx, cy + 0.7f * r);
                break;
            case SEARCH:
                p.circle(cx - 0.14f * r, cy - 0.14f * r, 0.56f * r);
                p.line(cx + 0.28f * r, cy + 0.28f * r, cx + 0.86f * r, cy + 0.86f * r);
                break;
            case TRASH:
                p.line(cx - 0.8f * r, cy - 0.5f * r, cx + 0.8f * r, cy - 0.5f * r);
                p.poly(pts(cx, cy, r, -0.28f, -0.5f, -0.28f, -0.82f, 0.28f, -0.82f, 0.28f, -0.5f), false, false);
                p.poly(pts(cx, cy, r, -0.58f, -0.5f, -0.46f, 0.86f, 0.46f, 0.86f, 0.58f, -0.5f), false, false);
                p.line(cx - 0.18f * r, cy - 0.2f * r, cx - 0.18f * r, cy + 0.6f * r);
                p.line(cx + 0.18f * r, cy - 0.2f * r, cx + 0.18f * r, cy + 0.6f * r);
                break;
            case PIN:
                p.poly(pts(cx, cy, r, -0.34f, -0.86f, 0.34f, -0.86f, 0.26f, -0.1f, 0.58f, 0.2f, -0.58f, 0.2f, -0.26f, -0.1f), true, false);
                p.line(cx, cy + 0.2f * r, cx, cy + 0.92f * r);
                break;
            case ARCHIVE:
                p.rrect(cx - 0.88f * r, cy - 0.78f * r, cx + 0.88f * r, cy - 0.3f * r, 0.08f * r);
                p.poly(pts(cx, cy, r, -0.72f, -0.3f, -0.72f, 0.82f, 0.72f, 0.82f, 0.72f, -0.3f), false, false);
                p.line(cx - 0.26f * r, cy + 0.08f * r, cx + 0.26f * r, cy + 0.08f * r);
                break;
            case SEND:
                p.poly(pts(cx, cy, r, -0.9f, -0.72f, 0.92f, 0f, -0.9f, 0.72f, -0.5f, 0f), true, false);
                p.line(cx - 0.5f * r, cy, cx + 0.92f * r, cy);
                break;
            case MORE:
                p.dot(cx, cy - 0.62f * r, 0.13f * r);
                p.dot(cx, cy, 0.13f * r);
                p.dot(cx, cy + 0.62f * r, 0.13f * r);
                break;
            case CHECK:
                p.poly(pts(cx, cy, r, -0.74f, 0.04f, -0.26f, 0.56f, 0.78f, -0.56f), false, false);
                break;
            case MUTE:
                p.arc(cx - 0.58f * r, cy - 0.86f * r, cx + 0.58f * r, cy + 0.3f * r, 180f, 180f);
                p.line(cx - 0.58f * r, cy - 0.28f * r, cx - 0.78f * r, cy + 0.52f * r);
                p.line(cx + 0.58f * r, cy - 0.28f * r, cx + 0.78f * r, cy + 0.52f * r);
                p.line(cx - 0.78f * r, cy + 0.52f * r, cx + 0.78f * r, cy + 0.52f * r);
                p.dot(cx, cy + 0.82f * r, 0.12f * r);
                p.line(cx - 0.82f * r, cy - 0.82f * r, cx + 0.82f * r, cy + 0.82f * r);
                break;
            default:
                break;
        }
    }
}
