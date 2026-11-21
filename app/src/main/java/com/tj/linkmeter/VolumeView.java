package com.tj.linkmeter;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Tablet only: one headset volume as a row the user can press (the Phone tab's "Headset volume" card shows two of these; the floating phone
 * has a compact one): a quieter button (a speaker with a minus), the rising bars, a louder button (a speaker with a plus) and, in the full
 * size, the words "7 of 15" with the name of the volume above. It decides nothing: what to show and whether the buttons work is a
 * {@link VolumeRules.Row}, and a press goes out as a step of -1 or +1 through the {@link Step}. One press is one step, as with the phone's
 * own volume keys.
 */
final class VolumeView extends LinearLayout {
    /** What a press asks for: -1 quieter, +1 louder. Called on the screen's thread, also for a row that is not enabled (the owner says why). */
    interface Step {
        void by(int delta);
    }

    private final boolean compact;
    private final TextView label;
    private final TextView value;
    private final IconButton quieter;
    private final IconButton louder;
    private final VolumeBarView bar;
    private final String quieterWord;
    private final String louderWord;

    /**
     * @param compact the floating phone's size and look (no words above, smaller buttons); false for the Phone tab's card
     * @param name what this volume is called in the full size ("Media", "Call") and in the buttons' spoken labels
     */
    VolumeView(Context c, boolean compact, String name, Step step) {
        super(c);
        this.compact = compact;
        quieterWord = name + " volume quieter";
        louderWord = name + " volume louder";
        setOrientation(VERTICAL);
        int btn = Ui.dp(c, compact ? 34 : 48);
        float scale = compact ? 0.5f : 0.46f;
        int bg = compact ? Ui.CARD_HI : 0xFF2A2F38;

        LinearLayout line = new LinearLayout(c);
        line.setOrientation(HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        quieter = new IconButton(c, Icons.Kind.VOL_DOWN, Ui.FG, bg, quieterWord, v -> step.by(-1));
        quieter.setScale(scale);
        louder = new IconButton(c, Icons.Kind.VOL_UP, Ui.FG, bg, louderWord, v -> step.by(1));
        louder.setScale(scale);
        bar = new VolumeBarView(c);

        if (compact) {
            label = null;
            value = null;
        } else {
            LinearLayout top = new LinearLayout(c);
            top.setOrientation(HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            label = Ui.text(c, name, 14, Ui.DIM, false);
            top.addView(label, Ui.weight());
            value = Ui.text(c, "–", 14, Ui.FG, true);
            top.addView(value);
            addView(top, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        LayoutParams bp = new LayoutParams(btn, btn);
        line.addView(quieter, bp);
        LayoutParams mp = new LayoutParams(0, Ui.dp(c, compact ? 26 : 34), 1f);
        mp.leftMargin = Ui.dp(c, compact ? 8 : 12);
        mp.rightMargin = Ui.dp(c, compact ? 8 : 12);
        line.addView(bar, mp);
        line.addView(louder, new LayoutParams(btn, btn));
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = compact ? 0 : Ui.dp(c, 4);
        addView(line, lp);
        bind(new VolumeRules.Row(), false);
    }

    /**
     * Shows a row. A button that cannot do anything now looks dimmed but stays pressable while the row has a reason to give (the owner shows
     * it), so the person is told why instead of nothing happening; {@code busy} dims all while a step is on its way.
     */
    void bind(VolumeRules.Row row, boolean busy) {
        if (value != null) {
            value.setText(row.words);
            value.setTextColor(row.enabled ? Ui.FG : Ui.DIM);
        }
        bar.set(row.steps, row.filled, row.enabled, Ui.ACCENT);
        boolean lowerOk = row.enabled && row.canLower;
        boolean louderOk = row.enabled && row.canRaise;
        quieter.setAlpha(busy ? 0.5f : lowerOk ? 1f : 0.35f);
        louder.setAlpha(busy ? 0.5f : louderOk ? 1f : 0.35f);
        // pressable when it can act, or when a reason can be given; a row at its end (loudest, quietest) has nothing to say and nothing to do
        quieter.setEnabled(lowerOk || !row.why.isEmpty());
        louder.setEnabled(louderOk || !row.why.isEmpty());
        String words = row.enabled ? row.words : row.why;
        setContentDescription(words);
        quieter.setContentDescription(quieterWord);
        louder.setContentDescription(louderWord);
    }

    boolean compact() {
        return compact;
    }

    /** The label above the bars of the full size (a chip takes its place in the compact one). */
    TextView label() {
        return label;
    }

    View quieterButton() {
        return quieter;
    }

    View louderButton() {
        return louder;
    }
}
