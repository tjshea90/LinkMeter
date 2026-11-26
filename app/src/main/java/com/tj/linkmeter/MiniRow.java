package com.tj.linkmeter;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Tablet only: one short line of a list in the floating cards (Recents, Contacts, the conversations of the small texting card): a round
 * picture with the initials, a name and a line of small words, and room on the right for a time and for round buttons. The caller
 * decides what the line is; this only draws it (docs/FLOATING_PHONE.md).
 */
final class MiniRow extends LinearLayout {
    final TextView avatar;
    final TextView title;
    final TextView detail;
    /** A small word on the right: the time of a text. */
    final TextView stamp;
    /** The dot of a conversation with news. */
    final TextView dot;
    /** Round buttons on the right, added by the caller. */
    final LinearLayout actions;

    MiniRow(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4));
        setMinimumHeight(Ui.dp(c, 46));
        avatar = TextsViews.avatar(c, 34);
        addView(avatar);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        title = Ui.text(c, "", 14, Ui.FG, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        detail = Ui.text(c, "", 11.5f, Ui.DIM, false);
        detail.setSingleLine(true);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title);
        col.addView(detail);
        LayoutParams cp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.leftMargin = Ui.dp(c, 8);
        cp.rightMargin = Ui.dp(c, 4);
        addView(col, cp);
        stamp = Ui.text(c, "", 11, Ui.DIM, false);
        stamp.setSingleLine(true);
        stamp.setVisibility(GONE);
        addView(stamp);
        dot = TextsViews.badge(c);
        TextsViews.bindBadge(dot, 0);
        dot.setVisibility(GONE);
        LayoutParams dp = new LayoutParams(Ui.dp(c, 10), Ui.dp(c, 10));
        dp.leftMargin = Ui.dp(c, 6);
        addView(dot, dp);
        actions = new LinearLayout(c);
        actions.setOrientation(HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        addView(actions);
    }

    /** A round button for the right of a line: 32 dp, with its words for TalkBack. */
    static IconButton button(Context c, Icons.Kind kind, int background, String label, View.OnClickListener l) {
        IconButton b = CallsViews.round(c, kind, background, 32, 0.5f, label, l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(c, 32), Ui.dp(c, 32));
        p.leftMargin = Ui.dp(c, 6);
        b.setLayoutParams(p);
        return b;
    }
}
