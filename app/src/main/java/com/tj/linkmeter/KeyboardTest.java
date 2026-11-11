package com.tj.linkmeter;

import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Options > Keyboard > "Test the keyboard". Two tries side by side, so that "glide typing does not work in LinkMeter" can be told apart:
 * a plain text box (does the keyboard glide in an ordinary LinkMeter field at all?) and the field of the Type button, with what it
 * would send to the phone written out underneath (does the keyboard glide there, and what does it write?). Nothing is sent anywhere.
 */
final class KeyboardTest {
    private KeyboardTest() {}

    static void show(final MainActivity act, ScreenPrefs prefs) {
        final TypingView field = new TypingView(act);
        field.setSuggestions(prefs.keyboardSuggestions());
        field.setIncognito(prefs.keyboardIncognito());

        String version = "?";
        try {
            version = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // keep the question mark
        }
        String ime = Settings.Secure.getString(act.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);

        int pad = Ui.dp(act, 16);
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad / 2, pad, pad / 2);

        col.addView(Ui.text(act, "LinkMeter " + version + ", keyboard: " + (ime == null || ime.isEmpty() ? "unknown" : ime), 11, Ui.DIM, false));

        col.addView(Ui.text(act, "1. A plain text box. Tap it and glide (swipe) a word:", 14, Ui.FG, true), Ui.lp(act, 12));
        EditText plain = new EditText(act);
        plain.setHint("glide a word here");
        plain.setHintTextColor(Ui.DIM);
        plain.setTextColor(Ui.FG);
        plain.setInputType(InputType.TYPE_CLASS_TEXT);
        col.addView(plain, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(Ui.text(act, "2. The field of the Type button. Press the button, glide a word, and read below what would go to the phone:",
                14, Ui.FG, true), Ui.lp(act, 16));
        final StringBuilder got = new StringBuilder();
        final TextView echo = Ui.mono(act, "(nothing yet)");
        final TextView counts = Ui.text(act, field.report(), 11, Ui.DIM, false);
        field.setListener(new TypingView.Listener() {
            @Override
            public void text(String s) {
                got.append(s);
                refresh();
            }

            @Override
            public void key(int keyCode) {
                got.append(keyCode == 67 ? "[Backspace]" : keyCode == 66 ? "[Enter]" : "[key " + keyCode + "]");
                refresh();
            }

            @Override
            public void blocked() {
                got.append("[* or # left out]");
                refresh();
            }

            private void refresh() {
                echo.setText(got.length() == 0 ? "(nothing yet)" : got.toString());
                counts.setText(field.report());
            }
        });
        col.addView(Ui.button(act, "Open the keyboard for the Type field", v -> {
            field.requestFocus();
            InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.restartInput(field);
                field.post(() -> imm.showSoftInput(field, 0));
            }
        }), Ui.lp(act, 6));
        col.addView(echo, Ui.lp(act, 8));
        col.addView(Ui.quiet(act, "Clear", v -> {
            got.setLength(0);
            echo.setText("(nothing yet)");
        }), Ui.lp(act, 4));
        col.addView(counts, Ui.lp(act, 6));
        col.addView(field, new LinearLayout.LayoutParams(1, 1));

        new AlertDialog.Builder(act).setTitle("Test the keyboard").setView(Ui.scroll(act, col)).setPositiveButton("Close", null).show();
    }
}
