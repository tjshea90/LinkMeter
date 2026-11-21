package com.tj.linkmeter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;

/** Tablet only: the small pieces the Calls screens share (names, round buttons, what an answer from the phone says). */
final class CallsViews {
    private CallsViews() {}

    static final int CALL_GREEN = 0xFF2E9E5B;
    static final int END_RED = 0xFFD94040;

    /**
     * What to call the other person: the name Texts shows for the number (contacts are shared), else the phone's name for them, else
     * the number. A call with no number at all is "Unknown caller" when it rings and "Phone call" when it was placed on the phone itself.
     */
    static String shownName(MainActivity act, String number, String phoneName, boolean incoming) {
        String name = phoneName == null ? "" : phoneName;
        String n = number == null ? "" : number;
        if (!n.isEmpty()) {
            TextsTab texts = act.tabOf(TextsTab.class);
            if (texts != null) {
                name = texts.nameForNumber(n, name);
            }
        }
        if (name.isEmpty() && n.isEmpty()) {
            return incoming ? "Unknown caller" : "Phone call";
        }
        return CallRules.shown(name, n);
    }

    /** Says what the phone did with a request when it did not do it (a sentence from the phone, or why it could not be asked). */
    static void report(Context c, Calls.Result r, IOException problem) {
        String say = problem != null ? TextsTab.explain(problem) : r != null && !r.ok ? r.why : "";
        if (!say.isEmpty()) {
            Toast.makeText(c, say, Toast.LENGTH_LONG).show();
        }
    }

    /** A background for something that is pressed: a plain shape, the press itself is lit by {@link PressFeedback}. */
    static android.graphics.drawable.Drawable ripple(int fill, float radiusPx) {
        return Ui.round(fill, radiusPx);
    }

    /** A round button with an icon: 48 dp for lists, larger for the call screen. */
    static IconButton round(Context c, Icons.Kind kind, int background, int sizeDp, float scale, String label, View.OnClickListener l) {
        IconButton b = new IconButton(c, kind, 0xFFFFFFFF, background, label, l);
        b.setScale(scale);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(c, sizeDp), Ui.dp(c, sizeDp));
        b.setLayoutParams(p);
        return b;
    }

    /** One line of a list: avatar, two lines of words, and room for buttons on the right (added by the caller). */
    static final class Row extends LinearLayout {
        final TextView avatar;
        final TextView title;
        final TextView detail;
        final LinearLayout actions;

        Row(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(Ui.dp(c, 6), Ui.dp(c, 8), Ui.dp(c, 6), Ui.dp(c, 8));
            avatar = TextsViews.avatar(c, 44);
            addView(avatar);
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(VERTICAL);
            title = Ui.text(c, "", 16, Ui.FG, true);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            detail = Ui.text(c, "", 12.5f, Ui.DIM, false);
            detail.setSingleLine(true);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(title);
            col.addView(detail);
            LayoutParams cp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            cp.leftMargin = Ui.dp(c, 12);
            cp.rightMargin = Ui.dp(c, 6);
            addView(col, cp);
            actions = new LinearLayout(c);
            actions.setOrientation(HORIZONTAL);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            addView(actions);
        }
    }

    /** A line of small words under a heading in a list ("A", "B", ...). */
    static TextView sectionHeader(Context c, String s) {
        TextView t = Ui.text(c, s, 13, Ui.ACCENT, true);
        t.setPadding(Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 2));
        return t;
    }
}
