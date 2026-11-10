package com.tj.linkmeter;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The app's small design system, so every screen is built from the same few pieces and looks like one app: the colours, the
 * text sizes, cards, three kinds of button (the main action, an ordinary one, a dangerous one), a switch with its explanation, a
 * row of choices, a status chip and the result strip. Everything is drawn with Android's own shapes; there is no library and no
 * picture file. Dark on purpose: the tablet is often used in the evening, and the remote screen's picture stands out on it.
 */
final class Ui {
    // ---- colours. Text on a card or on the background has a contrast of 6:1 or better (DIM on CARD is the lowest).
    static final int BG = 0xFF101216;
    static final int FG = 0xFFF2F4F7;
    static final int DIM = 0xFF9AA0A9;
    static final int CARD = 0xFF1B1E24;
    /** A step lighter than a card: ordinary buttons, the result strip. */
    static final int CARD_HI = 0xFF2A2F38;
    /** Thin lines between things. */
    static final int LINE = 0xFF333A44;
    static final int GREEN = 0xFF3DDC84;
    static final int AMBER = 0xFFFFB300;
    static final int RED = 0xFFFF5252;
    static final int GREY = 0xFF8A8F98;
    static final int ACCENT = 0xFF4C8DFF;
    /** Text on an ACCENT surface (white on that blue is too faint: 3.2:1; this is 6.4:1). */
    static final int ON_ACCENT = 0xFF06142B;
    private static final int DISABLED = 0x61F2F4F7;

    private Ui() {}

    static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    // ------------------------------------------------------------------------------------------------ text styles

    /** The name of a screen, at its top. */
    static TextView title(Context c, String s) {
        return text(c, s, 22, FG, true);
    }

    /** The heading of a card. */
    static TextView heading(Context c, String s) {
        return text(c, s, 16, FG, true);
    }

    /** An explanation under a heading or a switch. */
    static TextView hint(Context c, String s) {
        TextView t = text(c, s, 12.5f, DIM, false);
        t.setLineSpacing(0, 1.1f);
        return t;
    }

    /** A value or a sentence the user reads: the state of something. */
    static TextView body(Context c, String s) {
        return text(c, s, 14, FG, false);
    }

    /** Output of the phone, as it came: fixed-width so tables line up, and it can be selected and copied. */
    static TextView mono(Context c, String s) {
        TextView t = text(c, s, 12, FG, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        return t;
    }

    // ------------------------------------------------------------------------------------------------ shapes

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** A button's background: a rounded shape that ripples when touched, with a little room around it so neighbours do not touch. */
    private static Drawable buttonShape(Context c, int fill, int stroke) {
        float rad = dp(c, 10);
        GradientDrawable shape = round(fill, rad);
        if (stroke != 0) {
            shape.setStroke(Math.max(1, dp(c, 1)), stroke);
        }
        RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), shape, round(0xFFFFFFFF, rad));
        int in = dp(c, 3);
        return new InsetDrawable(ripple, in, in, in, in);
    }

    private static Button styled(Context c, String label, View.OnClickListener l, int fill, int stroke, int textColor) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        b.setBackground(buttonShape(c, fill, stroke));
        b.setStateListAnimator(null); // flat: no shadow that jumps when pressed
        b.setTextColor(new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}}, new int[] {DISABLED, textColor}));
        b.setTextSize(14);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setMinHeight(dp(c, 48));
        b.setMinimumHeight(dp(c, 48));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        return b;
    }

    /** An ordinary button. */
    static Button button(Context c, String label, View.OnClickListener l) {
        return styled(c, label, l, CARD_HI, 0, FG);
    }

    /** The one main thing to do on a card or a screen. */
    static Button primary(Context c, String label, View.OnClickListener l) {
        return styled(c, label, l, ACCENT, 0, ON_ACCENT);
    }

    /** Something that cannot simply be taken back (power off, forget, erase): red letters, and the caller asks before doing it. */
    static Button danger(Context c, String label, View.OnClickListener l) {
        return styled(c, label, l, CARD_HI, 0, 0xFFFF8A80);
    }

    /** A quiet button: an outline only. For things that are there but rarely needed. */
    static Button quiet(Context c, String label, View.OnClickListener l) {
        return styled(c, label, l, 0x00000000, LINE, FG);
    }

    // ------------------------------------------------------------------------------------------------ layout

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 20));
        return l;
    }

    static ScrollView scroll(Context c, LinearLayout col) {
        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        sv.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    static LinearLayout.LayoutParams lp(Context c, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(c, topDp);
        return p;
    }

    static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    /** Views side by side, each as wide as the others. */
    static LinearLayout row(Context c, View... views) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        for (View v : views) {
            r.addView(v, weight());
        }
        return r;
    }

    /** A card: one subject, with its heading, its state and its buttons together. Add it to a column with {@link #lp}(c, 12). */
    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(CARD, dp(c, 14)));
        l.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        return l;
    }

    /** A card with its heading already in it, and an explanation if one is given. */
    static LinearLayout card(Context c, String heading, String hint) {
        LinearLayout l = card(c);
        l.addView(heading(c, heading));
        if (hint != null && !hint.isEmpty()) {
            l.addView(hint(c, hint), lp(c, 2));
        }
        return l;
    }

    /** A thin line across a card, between two groups of things. */
    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1)));
        p.topMargin = dp(c, 10);
        p.bottomMargin = dp(c, 4);
        v.setLayoutParams(p);
        return v;
    }

    // ------------------------------------------------------------------------------------------------ small pieces

    /** A small coloured label that says what state something is in ("Hotspot", "Bluetooth", "Off"). */
    static TextView chip(Context c, String s, int color) {
        TextView t = text(c, s, 12, color, true);
        t.setPadding(dp(c, 9), dp(c, 3), dp(c, 9), dp(c, 3));
        paintChip(t, s, color);
        return t;
    }

    static void paintChip(TextView chip, String s, int color) {
        chip.setText(s);
        chip.setTextColor(color);
        chip.setBackground(round((color & 0x00FFFFFF) | 0x2E000000, dp(chip.getContext(), 20)));
    }

    /** A line with a name on the left and its state on the right, inside a card: "Hotspot ........ on". */
    static final class Fact {
        final LinearLayout view;
        final TextView value;

        Fact(Context c, String name) {
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.addView(text(c, name, 14, DIM, false), weight());
            value = text(c, "--", 14, FG, true);
            view.addView(value);
        }

        void set(String s, int color) {
            value.setText(s);
            value.setTextColor(color);
        }
    }

    /** A switch with what it does written next to it. */
    static final class Toggle {
        final LinearLayout view;
        final Switch sw;
        final TextView sub;

        Toggle(Context c, String title, String explain, boolean on, CompoundButton.OnCheckedChangeListener l) {
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setMinimumHeight(dp(c, 48));
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(text(c, title, 14.5f, FG, false));
            sub = hint(c, explain == null ? "" : explain);
            sub.setVisibility(explain == null || explain.isEmpty() ? View.GONE : View.VISIBLE);
            texts.addView(sub);
            LinearLayout.LayoutParams tp = weight();
            tp.rightMargin = dp(c, 10);
            view.addView(texts, tp);
            sw = new Switch(c);
            sw.setChecked(on);
            sw.setThumbTintList(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}}, new int[] {ACCENT, 0xFFB8BEC8}));
            sw.setTrackTintList(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}}, new int[] {0x804C8DFF, 0xFF4A515C}));
            sw.setOnCheckedChangeListener(l);
            view.addView(sw);
            // the whole line is the switch: a bigger thing to hit than the little knob
            view.setOnClickListener(v -> sw.toggle());
        }

        /** Shows a state that came from the phone without calling the listener (it is not the user's tap). */
        void show(boolean on, CompoundButton.OnCheckedChangeListener l) {
            sw.setOnCheckedChangeListener(null);
            sw.setChecked(on);
            sw.setOnCheckedChangeListener(l);
        }
    }

    interface Pick {
        void picked(int index);
    }

    /** A row of choices of which one is on ("15 s | 5 s | 2 s"). */
    static final class Choice {
        final LinearLayout view;
        private final Button[] buttons;
        private int selected;

        Choice(Context c, String[] labels, int initial, final Pick onPick) {
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.HORIZONTAL);
            buttons = new Button[labels.length];
            for (int i = 0; i < labels.length; i++) {
                final int idx = i;
                buttons[i] = button(c, labels[i], v -> {
                    select(idx);
                    onPick.picked(idx);
                });
                view.addView(buttons[i], weight());
            }
            select(initial);
        }

        void select(int idx) {
            selected = idx;
            for (int i = 0; i < buttons.length; i++) {
                Context c = buttons[i].getContext();
                boolean on = i == idx;
                buttons[i].setBackground(buttonShape(c, on ? ACCENT : CARD_HI, 0));
                buttons[i].setTextColor(on ? ColorStateList.valueOf(ON_ACCENT)
                        : new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}}, new int[] {DISABLED, FG}));
            }
        }

        int selected() {
            return selected;
        }
    }

    // ------------------------------------------------------------------------------------------------ the result strip

    /**
     * What the last button did, pinned to the bottom of a long screen, so the answer is seen wherever the screen is scrolled to
     * (it used to appear in a box at the very end of the page, out of sight). Tap it for the whole text and a Copy button.
     */
    static final class ResultBar {
        final LinearLayout view;
        private final TextView label;
        private final TextView more;
        private String full = "";
        private String what = "";

        ResultBar(final Context c) {
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setBackgroundColor(CARD_HI);
            view.setPadding(dp(c, 14), dp(c, 8), dp(c, 10), dp(c, 8));
            label = text(c, "", 13, FG, false);
            label.setMaxLines(3);
            label.setEllipsize(TextUtils.TruncateAt.END);
            view.addView(label, weight());
            more = text(c, "Details", 13, ACCENT, true);
            more.setPadding(dp(c, 12), dp(c, 8), dp(c, 6), dp(c, 8));
            view.addView(more);
            view.setVisibility(View.GONE);
            view.setOnClickListener(v -> details(c));
        }

        /** A request is on its way. */
        void working(String whatIsDone) {
            what = whatIsDone;
            full = whatIsDone + "…";
            label.setText(full);
            label.setTextColor(DIM);
            more.setVisibility(View.GONE);
            view.setVisibility(View.VISIBLE);
        }

        /** The answer. */
        void show(String text, boolean ok) {
            full = text == null ? "" : text;
            label.setText(full.trim());
            label.setTextColor(ok ? FG : AMBER);
            more.setVisibility(full.length() > 90 || full.indexOf('\n') >= 0 ? View.VISIBLE : View.GONE);
            view.setVisibility(full.isEmpty() ? View.GONE : View.VISIBLE);
        }

        /** Everything of the last answer, for a Copy button. */
        String fullText() {
            return full;
        }

        private void details(final Context c) {
            if (full.isEmpty()) {
                return;
            }
            TextView t = mono(c, full);
            t.setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 4));
            ScrollView sv = new ScrollView(c);
            sv.addView(t);
            new AlertDialog.Builder(c)
                    .setTitle(what.isEmpty() ? "Result" : what)
                    .setView(sv)
                    .setPositiveButton("Close", null)
                    .setNeutralButton("Copy", (d, w) -> copy(c, full))
                    .show();
        }
    }

    static void copy(Context c, String text) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && text != null && !text.isEmpty()) {
            cm.setPrimaryClip(ClipData.newPlainText("LinkMeter", text));
            Toast.makeText(c, "Copied.", Toast.LENGTH_SHORT).show();
        }
    }

    /** A screen whose content scrolls while its result strip stays at the bottom. */
    static View withResultBar(Context c, LinearLayout col, ResultBar bar) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(scroll(c, col), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(bar.view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    // ------------------------------------------------------------------------------------------------ words for numbers

    static String quality(int lvl) {
        switch (lvl) {
            case 4:
                return "Excellent";
            case 3:
                return "Good";
            case 2:
                return "Fair";
            case 1:
                return "Poor";
            case 0:
                return "None";
            default:
                return "--";
        }
    }

    static int levelColor(int lvl) {
        if (lvl >= 3) {
            return GREEN;
        }
        if (lvl == 2) {
            return AMBER;
        }
        if (lvl >= 0) {
            return RED;
        }
        return GREY;
    }
}
