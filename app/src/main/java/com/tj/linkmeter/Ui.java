package com.tj.linkmeter;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Small helpers so every screen is built the same way. */
final class Ui {
    static final int BG = 0xFF101216;
    static final int FG = 0xFFF2F4F7;
    static final int DIM = 0xFF9AA0A9;
    static final int CARD = 0xFF1B1E24;
    static final int GREEN = 0xFF3DDC84;
    static final int AMBER = 0xFFFFB300;
    static final int RED = 0xFFFF5252;
    static final int GREY = 0xFF8A8F98;
    static final int ACCENT = 0xFF4C8DFF;

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

    static Button button(Context c, String label, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 20));
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
