package com.tj.linkmeter;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Tablet only: the headset's battery as a small glowing battery with its percentage beside it (the floating phone, the Calls tab, the
 * Headset screen, Home). What the colours and the words mean is decided by {@link BatteryRules}; this only draws: a rounded body with a
 * terminal, a fill that rises with a soft light-to-dark gradient and a glossy stripe, four faint lamp ticks at 20 % steps, a colour for
 * the band (green, yellow, orange, red; grey for "not known" with a dash), and a slow pulse when the level is critical. The fill glides
 * when the level changes. The size comes from {@code scale}: 1 is small enough for a header, 2 is for a card.
 */
final class BatteryIconView extends View {
    private static final int C_GOOD = 0xFF3DDC84;
    private static final int C_OK = 0xFFE6C84A;
    private static final int C_LOW = 0xFFFF9F43;
    private static final int C_CRIT = 0xFFFF5252;
    private static final int C_UNKNOWN = 0xFF8A8F98;

    private final float scale;
    private final float density;
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final RectF clip = new RectF();
    private int level = Calls.BATTERY_UNKNOWN;
    private boolean init;
    private float shown;
    private float pulse = 1f;
    private boolean showText = true;
    private ValueAnimator glide;
    private ValueAnimator beat;
    // the fill's gradient is built again only when its colour or place changes (the pulse changes the paint's alpha only)
    private int shaderColor;
    private float shaderTop = -1;
    private float shaderBottom = -1;

    BatteryIconView(Context c, float scale) {
        super(c);
        this.scale = scale;
        density = c.getResources().getDisplayMetrics().density;
        body.setStyle(Paint.Style.STROKE);
        fill.setStyle(Paint.Style.FILL);
        gloss.setStyle(Paint.Style.FILL);
        tick.setStyle(Paint.Style.STROKE);
        text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        text.setTextSize(13f * scale * c.getResources().getConfiguration().fontScale * density);
        setLevel(Calls.BATTERY_UNKNOWN, false);
    }

    /** True: the percentage is drawn to the right of the icon (the default); false: the icon alone. */
    void showText(boolean on) {
        showText = on;
        requestLayout();
        invalidate();
    }

    int level() {
        return level;
    }

    static int colorFor(BatteryRules.Band b) {
        switch (b) {
            case GOOD:
                return C_GOOD;
            case OK:
                return C_OK;
            case LOW:
                return C_LOW;
            case CRITICAL:
                return C_CRIT;
            default:
                return C_UNKNOWN;
        }
    }

    /** Shows this level (0 to 100, or {@link Calls#BATTERY_UNKNOWN}); {@code animate} makes the fill glide to it. */
    void setLevel(int newLevel, boolean animate) {
        if (init && newLevel == level) {
            return; // nothing moved: no new animation, no new layout (the floating phone asks once a second)
        }
        init = true;
        boolean sameBand = BatteryRules.band(newLevel) == BatteryRules.band(level);
        level = newLevel;
        setContentDescription(BatteryRules.words(newLevel, ""));
        float target = BatteryRules.fraction(newLevel);
        if (glide != null) {
            glide.cancel();
        }
        if (animate && isAttachedToWindow() && Math.abs(target - shown) > 0.005f) {
            glide = ValueAnimator.ofFloat(shown, target);
            glide.setDuration(450);
            glide.setInterpolator(new DecelerateInterpolator());
            glide.addUpdateListener(a -> {
                shown = (Float) a.getAnimatedValue();
                invalidate();
            });
            glide.start();
        } else {
            shown = target;
        }
        if (!sameBand || beat == null) {
            updateBeat();
        }
        requestLayout();
        invalidate();
    }

    private void updateBeat() {
        if (beat != null) {
            beat.cancel();
            beat = null;
        }
        pulse = 1f;
        if (BatteryRules.band(level) == BatteryRules.Band.CRITICAL && isAttachedToWindow()) {
            beat = ValueAnimator.ofFloat(1f, 0.45f);
            beat.setDuration(700);
            beat.setRepeatMode(ValueAnimator.REVERSE);
            beat.setRepeatCount(ValueAnimator.INFINITE);
            beat.addUpdateListener(a -> {
                pulse = (Float) a.getAnimatedValue();
                invalidate();
            });
            beat.start();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateBeat();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (glide != null) {
            glide.cancel();
        }
        if (beat != null) {
            beat.cancel();
            beat = null;
        }
        super.onDetachedFromWindow();
    }

    private float iconW() {
        return 30f * scale * density;
    }

    private float iconH() {
        return 15f * scale * density;
    }

    private float nub() {
        return 3f * scale * density;
    }

    private String label() {
        return BatteryRules.percent(level);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        float w = iconW() + nub();
        if (showText) {
            w += 5f * scale * density + text.measureText("100%");
        }
        int h = (int) Math.ceil(Math.max(iconH() + 4 * density, text.getTextSize() * 1.25f));
        setMeasuredDimension(resolveSize((int) Math.ceil(w) + 2, widthSpec), resolveSize(h, heightSpec));
    }

    @Override
    protected void onDraw(Canvas c) {
        BatteryRules.Band band = BatteryRules.band(level);
        int color = colorFor(band);
        float w = iconW();
        float h = iconH();
        float top = (getHeight() - h) / 2f;
        float stroke = Math.max(1.4f * density, 1.6f * scale * density);
        float radius = 3.2f * scale * density;

        // the body: a dark glass tube with a coloured rim
        r.set(stroke / 2f, top + stroke / 2f, w - stroke / 2f, top + h - stroke / 2f);
        body.setStyle(Paint.Style.FILL);
        body.setColor(0xFF14171C);
        c.drawRoundRect(r, radius, radius, body);
        body.setStyle(Paint.Style.STROKE);
        body.setStrokeWidth(stroke);
        body.setColor(band == BatteryRules.Band.UNKNOWN ? 0xFF59606B : (color & 0x00FFFFFF) | 0xCC000000);
        c.drawRoundRect(r, radius, radius, body);

        // the terminal
        body.setStyle(Paint.Style.FILL);
        r.set(w + 0.5f * density, top + h * 0.3f, w + nub(), top + h * 0.7f);
        c.drawRoundRect(r, 1.4f * density, 1.4f * density, body);

        // the fill
        float inset = stroke + 1.2f * scale * density;
        float fw = (w - 2 * inset) * shown;
        if (fw > 0.5f) {
            clip.set(inset, top + inset, inset + fw, top + h - inset);
            if (shaderColor != color || shaderTop != clip.top || shaderBottom != clip.bottom) {
                fill.setShader(new LinearGradient(0, clip.top, 0, clip.bottom, lighten(color, 0.35f), darken(color, 0.25f), Shader.TileMode.CLAMP));
                shaderColor = color;
                shaderTop = clip.top;
                shaderBottom = clip.bottom;
            }
            fill.setAlpha((int) (255 * pulse));
            float rr = Math.max(1f, radius - inset * 0.6f);
            c.drawRoundRect(clip, rr, rr, fill);
            // the gloss: a pale stripe along the upper third
            gloss.setColor(0x33FFFFFF);
            r.set(clip.left + 1f * density, clip.top + 0.8f * density, clip.right - 1f * density, clip.top + (clip.height()) * 0.38f);
            if (r.width() > 1f && r.height() > 0.5f) {
                c.drawRoundRect(r, rr, rr, gloss);
            }
        }

        // lamp ticks every 20 %
        tick.setStrokeWidth(Math.max(1f, 0.8f * scale * density));
        tick.setColor(0x55000000);
        for (int i = 1; i < BatteryRules.SEGMENTS; i++) {
            float x = inset + (w - 2 * inset) * i / BatteryRules.SEGMENTS;
            c.drawLine(x, top + inset, x, top + h - inset, tick);
        }

        // "not known": a dash in the middle of the empty body
        if (band == BatteryRules.Band.UNKNOWN) {
            tick.setColor(0xFF8A8F98);
            tick.setStrokeWidth(Math.max(1.4f, 1.6f * scale * density));
            c.drawLine(w * 0.36f, top + h / 2f, w * 0.64f, top + h / 2f, tick);
        }

        if (showText) {
            text.setColor(band == BatteryRules.Band.UNKNOWN ? 0xFF9AA0A9 : color);
            Paint.FontMetrics fm = text.getFontMetrics();
            float baseline = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
            c.drawText(label(), w + nub() + 5f * scale * density, baseline, text);
        }
    }

    private static int lighten(int color, float f) {
        int rr = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return 0xFF000000 | ((int) (rr + (255 - rr) * f) << 16) | ((int) (g + (255 - g) * f) << 8) | (int) (b + (255 - b) * f);
    }

    private static int darken(int color, float f) {
        int rr = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return 0xFF000000 | ((int) (rr * (1 - f)) << 16) | ((int) (g * (1 - f)) << 8) | (int) (b * (1 - f));
    }
}
