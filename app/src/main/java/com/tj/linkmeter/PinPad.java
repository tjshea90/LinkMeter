package com.tj.linkmeter;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A number pad for the phone's PIN, drawn inside the app: unlocking the phone never needs the tablet's
 * keyboard, so nothing slides over the picture. The PIN is passed on once and not kept.
 */
final class PinPad {
    interface Done {
        void unlock(String pin);

        void cancel();
    }

    private PinPad() {}

    static View build(final Context c, final Done done) {
        final StringBuilder pin = new StringBuilder();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 14);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.CARD);
        bg.setCornerRadius(Ui.dp(c, 14));
        box.setBackground(bg);
        box.setClickable(true); // taps on the pad do not reach what is behind it

        box.addView(Ui.text(c, "Unlock the phone", 16, Ui.FG, true));
        final TextView dots = Ui.text(c, "", 26, Ui.FG, true);
        dots.setGravity(Gravity.CENTER);
        dots.setLetterSpacing(0.15f);
        box.addView(dots, Ui.lp(c, 6));
        box.addView(Ui.text(c, "Sent once to the phone and never saved. Wrong tries make Android make you wait.",
                11, Ui.DIM, false), Ui.lp(c, 2));

        final Runnable paint = () -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pin.length(); i++) {
                sb.append('•').append(' ');
            }
            dots.setText(sb.length() == 0 ? "–" : sb.toString().trim());
        };
        paint.run();

        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"←", "0", "OK"}};
        for (String[] row : rows) {
            LinearLayout r = new LinearLayout(c);
            r.setOrientation(LinearLayout.HORIZONTAL);
            for (final String key : row) {
                Button b = Ui.button(c, key, v -> {
                    if (key.equals("OK")) {
                        if (pin.length() >= 4) {
                            String p = pin.toString();
                            pin.setLength(0);
                            paint.run();
                            done.unlock(p);
                        }
                    } else if (key.equals("←")) {
                        if (pin.length() > 0) {
                            pin.setLength(pin.length() - 1);
                        }
                        paint.run();
                    } else if (pin.length() < 16) {
                        pin.append(key);
                        paint.run();
                    }
                });
                b.setTextSize(20);
                r.addView(b, new LinearLayout.LayoutParams(0, Ui.dp(c, 54), 1f));
            }
            box.addView(r);
        }
        box.addView(Ui.button(c, "Cancel", v -> {
            pin.setLength(0);
            done.cancel();
        }), Ui.lp(c, 4));
        box.setLayoutParams(new ViewGroup.LayoutParams(Ui.dp(c, 250), ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }
}
