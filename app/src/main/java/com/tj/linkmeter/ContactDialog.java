package com.tj.linkmeter;

import android.app.AlertDialog;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Tablet only: the question for a contact, the same for a new one and for a rename: the name, the number (fixed when it belongs to a
 * conversation, typed for a new contact), and whether to save it on the phone as well. It only asks; {@link TextsTab#saveContact} does the saving.
 */
final class ContactDialog {
    private ContactDialog() {}

    interface Save {
        void save(String name, String number, boolean alsoOnPhone);
    }

    /** What the phone can do about contacts (decides what the switch says and whether it can be on). */
    static final int PHONE_CANNOT = 0;
    static final int PHONE_NEEDS_PERMISSION = 1;
    static final int PHONE_READY = 2;

    private static EditText field(Context c, String hint, String text, int inputType) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(Ui.DIM);
        e.setTextColor(Ui.FG);
        e.setTextSize(17);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setText(text);
        e.setBackground(TextsViews.shape(Ui.CARD_HI, Ui.dp(c, 10)));
        e.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 10));
        return e;
    }

    /**
     * @param title          "Add to contacts", "Rename contact", "New contact"
     * @param number         the number as it is (fixed unless {@code numberEditable})
     * @param phoneMode      {@link #PHONE_CANNOT}, {@link #PHONE_NEEDS_PERMISSION} or {@link #PHONE_READY}
     * @param phoneNote      one sentence under the switch that says what will happen on the phone
     * @param hasTabletName  this tablet has a name of its own for the person: a button gives it up for the phone's
     */
    static void show(final Context c, String title, String name, String number, boolean numberEditable, int phoneMode, String phoneNote,
            boolean hasTabletName, final Save save, final Runnable usePhoneName) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(c, 20), Ui.dp(c, 8), Ui.dp(c, 20), Ui.dp(c, 4));
        col.addView(Ui.text(c, "Name", 13, Ui.DIM, true));
        final EditText nameBox = field(c, "Name", name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        col.addView(nameBox, Ui.lp(c, 4));
        col.addView(Ui.text(c, "Number", 13, Ui.DIM, true), Ui.lp(c, 12));
        final EditText numberBox;
        if (numberEditable) {
            numberBox = field(c, "Phone number", number, InputType.TYPE_CLASS_PHONE);
            col.addView(numberBox, Ui.lp(c, 4));
        } else {
            numberBox = null;
            TextView fixed = Ui.text(c, PhoneNumbers.pretty(number), 17, Ui.FG, false);
            fixed.setPadding(Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 4));
            col.addView(fixed);
        }
        final Ui.Toggle phone = new Ui.Toggle(c, "Also save on the phone", phoneNote, phoneMode != PHONE_CANNOT, (b, on) -> {
            // nothing to do until Save
        });
        if (phoneMode == PHONE_CANNOT) {
            phone.sw.setEnabled(false);
            phone.view.setOnClickListener(null);
        }
        col.addView(phone.view, Ui.lp(c, 14));

        AlertDialog.Builder b = new AlertDialog.Builder(c).setTitle(title).setView(Ui.scroll(c, col)).setNegativeButton("Cancel", null);
        b.setPositiveButton("Save", (d, w) -> save.save(nameBox.getText().toString().trim(),
                numberBox == null ? number : numberBox.getText().toString().trim(), phone.sw.isChecked() && phone.sw.isEnabled()));
        if (hasTabletName && usePhoneName != null) {
            b.setNeutralButton("Use the phone's name", (d, w) -> usePhoneName.run());
        }
        final AlertDialog d = TextsViews.show(b);
        final TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b2, int c2) {
                // nothing
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b2, int c2) {
                // nothing
            }

            @Override
            public void afterTextChanged(Editable s) {
                boolean ok = !nameBox.getText().toString().trim().isEmpty() && nameBox.getText().length() <= TextsLocal.MAX_NAME
                        && (numberBox == null || !PhoneNumbers.forSending(numberBox.getText().toString().trim()).isEmpty());
                d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(ok);
            }
        };
        nameBox.addTextChangedListener(watcher);
        if (numberBox != null) {
            numberBox.addTextChangedListener(watcher);
        }
        watcher.afterTextChanged(null);
        nameBox.requestFocus();
        nameBox.setSelection(nameBox.getText().length());
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
    }
}
