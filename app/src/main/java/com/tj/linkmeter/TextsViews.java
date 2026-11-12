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
        Context c = b.getContext();
        boolean dot = count == 0;
        b.setText(dot ? "" : count > 99 ? "99+" : String.valueOf(count));
        b.setMinWidth(Ui.dp(c, dot ? 10 : 20));
        b.setMinHeight(Ui.dp(c, dot ? 10 : 20));
        b.setPadding(dot ? 0 : Ui.dp(c, 6), 0, dot ? 0 : Ui.dp(c, 6), 0);
        ViewGroup.LayoutParams lp = b.getLayoutParams();
        if (lp != null) {
            lp.width = dot ? Ui.dp(c, 10) : ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.height = dot ? Ui.dp(c, 10) : Ui.dp(c, 20);
            b.setLayoutParams(lp);
        }
    }

    /** A 44 dp touch target with an icon in the text colour. */
    static IconButton icon(Context c, Icons.Kind kind, String label, View.OnClickListener l) {
        IconButton b = new IconButton(c, kind, Ui.FG, 0, label, l);
        b.setScale(0.5f);
        b.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        return b;
    }

    /** A small rounded button of words ("Copy code") for the list and the bubbles. */
    static TextView pill(Context c) {
        TextView t = Ui.text(c, "Copy code", 12.5f, Ui.ACCENT, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(shape(0x334C8DFF, Ui.dp(c, 14)));
        t.setPadding(Ui.dp(c, 10), Ui.dp(c, 3), Ui.dp(c, 10), Ui.dp(c, 3));
        t.setSingleLine(true);
        t.setClickable(true);
        t.setFocusable(false);
        t.setContentDescription("Copy the code");
        return t;
    }

    /** Puts a one-time code on the clipboard, marked sensitive where Android knows that mark (13 and later: it is hidden in the clipboard preview). */
    static void copyCode(Context c, String code) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || code.isEmpty()) {
            return;
        }
        android.content.ClipData clip = android.content.ClipData.newPlainText("code", code);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        cm.setPrimaryClip(clip);
        android.widget.Toast.makeText(c, "Code copied", android.widget.Toast.LENGTH_SHORT).show();
    }

    interface PickText {
        void picked(String text);
    }

    /**
     * The menu of a message: a row of emoji to react with (when {@code emojis} is not null; the one you already put on the message is
     * ringed, and tapping it takes it back), a "+" for more emoji, then the actions as lines. One tap closes the menu and does it.
     */
    static void messageMenu(final Context c, String[] emojis, String chosen, final PickText onEmoji, final Runnable onMore, String[] labels,
            final Runnable[] actions) {
        final AlertDialog[] box = new AlertDialog[1];
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 6));
        if (emojis != null && emojis.length > 0) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            row.setBackground(shape(Ui.CARD_HI, Ui.dp(c, 30)));
            row.setPadding(Ui.dp(c, 6), Ui.dp(c, 4), Ui.dp(c, 6), Ui.dp(c, 4));
            for (final String e : emojis) {
                boolean mine = chosen != null && SmsReactions.same(chosen).equals(SmsReactions.same(e));
                TextView t = Ui.text(c, e, 26, Ui.FG, false);
                t.setGravity(Gravity.CENTER);
                t.setContentDescription(mine ? "Take back " + e : "React " + e);
                if (mine) {
                    t.setBackground(shape(0x664C8DFF, Ui.dp(c, 24)));
                }
                t.setOnClickListener(v -> {
                    box[0].dismiss();
                    onEmoji.picked(e);
                });
                LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1f);
                ep.setMargins(Ui.dp(c, 1), Ui.dp(c, 2), Ui.dp(c, 1), Ui.dp(c, 2));
                row.addView(t, ep);
            }
            if (onMore != null) {
                IconButton more = new IconButton(c, Icons.Kind.PLUS, Ui.FG, Ui.BG, "More emoji", v -> {
                    box[0].dismiss();
                    onMore.run();
                });
                more.setScale(0.42f);
                LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40));
                mp.leftMargin = Ui.dp(c, 4);
                row.addView(more, mp);
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.bottomMargin = Ui.dp(c, 6);
            col.addView(row, rp);
        }
        for (int i = 0; i < labels.length; i++) {
            final Runnable a = i < actions.length ? actions[i] : null;
            TextView t = Ui.text(c, labels[i], 16, Ui.FG, false);
            t.setPadding(Ui.dp(c, 14), Ui.dp(c, 14), Ui.dp(c, 14), Ui.dp(c, 14));
            t.setOnClickListener(v -> {
                box[0].dismiss();
                if (a != null) {
                    a.run();
                }
            });
            col.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        box[0] = new AlertDialog.Builder(c).setView(Ui.scroll(c, col)).create();
        show(box[0]);
    }

    /** A grid of emoji to pick one from (the "+" of a message's menu). */
    static void emojiGrid(final Context c, String title, String[] emojis, final PickText onEmoji) {
        final AlertDialog[] box = new AlertDialog[1];
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8));
        int perRow = 7;
        LinearLayout row = null;
        for (int i = 0; i < emojis.length; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                col.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final String e = emojis[i];
            TextView t = Ui.text(c, e, 26, Ui.FG, false);
            t.setGravity(Gravity.CENTER);
            t.setContentDescription("React " + e);
            t.setOnClickListener(v -> {
                box[0].dismiss();
                onEmoji.picked(e);
            });
            row.addView(t, new LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1f));
        }
        while (row != null && row.getChildCount() < perRow) { // the last row keeps the same widths
            row.addView(new View(c), new LinearLayout.LayoutParams(0, Ui.dp(c, 50), 1f));
        }
        box[0] = new AlertDialog.Builder(c).setTitle(title).setView(Ui.scroll(c, col)).setNegativeButton("Cancel", null).create();
        show(box[0]);
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
        show(b);
    }

    private static final java.util.List<java.lang.ref.WeakReference<android.app.Dialog>> OPEN = new java.util.ArrayList<>();

    /**
     * Every question, menu and preview of the Texts screens is shown through here, so that all of them can be closed at once when the
     * Texts lock engages (a question left open while the app was away must not stay on top of the lock screen).
     */
    static AlertDialog show(AlertDialog.Builder b) {
        return show(b.create());
    }

    /** The same for a dialog that was made by hand. */
    static <D extends android.app.Dialog> D show(D d) {
        d.show();
        synchronized (OPEN) {
            for (java.util.Iterator<java.lang.ref.WeakReference<android.app.Dialog>> it = OPEN.iterator(); it.hasNext(); ) {
                android.app.Dialog o = it.next().get();
                if (o == null || !o.isShowing()) {
                    it.remove();
                }
            }
            OPEN.add(new java.lang.ref.WeakReference<android.app.Dialog>(d));
        }
        return d;
    }

    /** Closes every dialog that {@link #show} put up. */
    static void closeAll() {
        java.util.List<java.lang.ref.WeakReference<android.app.Dialog>> all;
        synchronized (OPEN) {
            all = new java.util.ArrayList<>(OPEN);
            OPEN.clear();
        }
        for (java.lang.ref.WeakReference<android.app.Dialog> r : all) {
            android.app.Dialog d = r.get();
            if (d != null && d.isShowing()) {
                d.dismiss();
            }
        }
    }
}
