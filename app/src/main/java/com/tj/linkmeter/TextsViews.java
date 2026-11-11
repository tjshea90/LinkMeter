package com.tj.linkmeter;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small pieces the Texts screens share: the round picture with a person's initials, the unread badge, an icon button, a menu. */
final class TextsViews {
    private TextsViews() {}

    private static final int[] AVATARS = {0xFF3F7CE0, 0xFF2E9E6B, 0xFFC2662D, 0xFF8E5BD6, 0xFFC24A7B, 0xFF2C93A8, 0xFF9A8A2A};

    static int colorOf(String key) {
        return AVATARS[(key.hashCode() & 0x7fffffff) % AVATARS.length];
    }

    static GradientDrawable shape(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radiusPx);
        g.setColor(color);
        return g;
    }

    /** A round picture {@code sizeDp} wide; fill it with {@link #bindAvatar}. */
    static TextView avatar(Context c, int sizeDp) {
        TextView v = Ui.text(c, "", sizeDp >= 44 ? 17 : 14, Color.WHITE, true);
        v.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        v.setBackground(g);
        v.setLayoutParams(new ViewGroup.LayoutParams(Ui.dp(c, sizeDp), Ui.dp(c, sizeDp)));
        return v;
    }

    /** The initials of the person on their colour; ticked (a check mark on a dark circle) when the conversation is selected. */
    static void bindAvatar(TextView v, String name, String key, boolean selected) {
        GradientDrawable g = (GradientDrawable) v.getBackground();
        g.setColor(selected ? Ui.ACCENT : colorOf(key));
        v.setText(selected ? "✓" : PhoneNumbers.initials(name));
        v.setTextColor(selected ? Ui.ON_ACCENT : Color.WHITE);
    }

    /** The round number on a conversation with news (a dot when the number is not known). */
    static TextView badge(Context c) {
        TextView b = Ui.text(c, "", 12, Ui.ON_ACCENT, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(shape(Ui.ACCENT, Ui.dp(c, 10)));
        b.setMinWidth(Ui.dp(c, 20));
        b.setMinHeight(Ui.dp(c, 20));
        b.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0);
        return b;
    }

    /** @param count 0 for a dot, more for a number, negative to hide it */
    static void bindBadge(TextView b, int count) {
        if (count < 0) {
            b.setVisibility(View.GONE);
            return;
        }
        b.setVisibility(View.VISIBLE);
        b.setText(count == 0 ? "" : count > 99 ? "99+" : String.valueOf(count));
        Context c = b.getContext();
        b.setMinWidth(Ui.dp(c, count == 0 ? 10 : 20));
        b.setMinHeight(Ui.dp(c, count == 0 ? 10 : 20));
    }

    /** A 44 dp touch target with an icon in the text colour. */
    static IconButton icon(Context c, Icons.Kind kind, String label, View.OnClickListener l) {
        IconButton b = new IconButton(c, kind, Ui.FG, 0, label, l);
        b.setScale(0.5f);
        b.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        return b;
    }

    /** A list of choices in a dialog; each label runs the action at the same place. */
    static void menu(Context c, String title, final String[] labels, final Runnable[] actions) {
        AlertDialog.Builder b = new AlertDialog.Builder(c);
        if (title != null) {
            b.setTitle(title);
        }
        b.setItems(labels, (d, i) -> {
            if (i >= 0 && i < actions.length && actions[i] != null) {
                actions[i].run();
            }
        });
        b.show();
    }
}
