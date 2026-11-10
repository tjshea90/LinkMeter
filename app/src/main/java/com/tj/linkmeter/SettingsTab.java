package com.tj.linkmeter;

import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Tablet settings. Nothing here runs in the background. */
final class SettingsTab extends Tab {
    private TextView tInterval;

    SettingsTab(MainActivity a) {
        super(a);
    }

    @Override
    String title() {
        return "Settings";
    }

    @Override
    Icons.Kind icon() {
        return Icons.Kind.GEAR;
    }

    @Override
    View build() {
        LinearLayout col = Ui.column(act);
        col.addView(Ui.text(act, "Settings", 22, Ui.FG, true));

        col.addView(Ui.text(act, "Phone address (leave blank for automatic)", 13, Ui.DIM, false),
                Ui.lp(act, 16));
        EditText host = new EditText(act);
        host.setSingleLine(true);
        host.setKeyListener(DigitsKeyListener.getInstance("0123456789."));
        host.setTextColor(Ui.FG);
        host.setHint("automatic");
        host.setHintTextColor(Ui.DIM);
        host.setText(Cfg.host(act));
        host.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable e) {
                Cfg.setHost(act, e.toString().trim());
            }
        });
        col.addView(host);

        tInterval = Ui.text(act, intervalLabel(Cfg.intervalMs(act)), 13, Ui.DIM, false);
        col.addView(tInterval, Ui.lp(act, 16));
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(Ui.button(act, "Eco 15 s", v -> setInterval(15000L)), Ui.weight());
        row.addView(Ui.button(act, "Normal 5 s", v -> setInterval(5000L)), Ui.weight());
        row.addView(Ui.button(act, "Live 2 s", v -> setInterval(2000L)), Ui.weight());
        col.addView(row);

        CheckBox keep = new CheckBox(act);
        keep.setText("Keep the screen on while this app is open");
        keep.setTextColor(Ui.FG);
        keep.setChecked(Cfg.keepOn(act));
        keep.setOnCheckedChangeListener((view, checked) -> {
            Cfg.setKeepOn(act, checked);
            act.applyKeepOn();
        });
        col.addView(keep, Ui.lp(act, 12));

        CheckBox share = new CheckBox(act);
        share.setText("Let the phone browse this tablet's files");
        share.setTextColor(Ui.FG);
        share.setChecked(Cfg.shareFiles(act));
        share.setOnCheckedChangeListener((view, checked) -> {
            Cfg.setShareFiles(act, checked);
            if (checked) {
                act.startService();
            } else {
                act.stopService();
            }
        });
        col.addView(share, Ui.lp(act, 4));

        col.addView(Ui.button(act, "Pair again (new secret)", v -> {
            Cfg.resetToken(act);
            Toast.makeText(act, "Done. On the phone tap Pair tablet, then reopen Home.",
                    Toast.LENGTH_LONG).show();
        }), Ui.lp(act, 16));
        col.addView(Ui.button(act, "App info and permissions", v -> act.openAppInfo()),
                Ui.lp(act, 8));
        col.addView(Ui.button(act, "Switch role", v -> act.chooseRole("")), Ui.lp(act, 8));
        return Ui.scroll(act, col);
    }

    private void setInterval(long ms) {
        Cfg.setIntervalMs(act, ms);
        if (tInterval != null) {
            tInterval.setText(intervalLabel(ms));
        }
    }

    private static String intervalLabel(long ms) {
        return "Ask the phone every " + (ms / 1000) + " s while Home is open "
                + "(longer = less battery on both devices)";
    }
}
