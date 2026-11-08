package com.tj.linkmeter;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** The small dialogs of the file manager: a name clash, rename, new folder, delete, sort, details. */
final class FileDialogs {
    private FileDialogs() {}

    /** "Nov 8, 2026  14:32" from seconds. */
    static String when(long seconds) {
        if (seconds <= 0) {
            return "unknown date";
        }
        return new SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault()).format(new Date(seconds * 1000));
    }

    private static String describe(FsEntry e) {
        return (e.dir ? "folder" : FsFormat.size(e.size)) + ", " + when(e.time);
    }

    /** A name is already taken at the destination: replace, keep both, skip, or stop (Back). */
    static void conflict(Activity a, String destPath, FsEntry existing, FsEntry incoming, final Transfers.Answer answer) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(a, 20);
        box.setPadding(pad, Ui.dp(a, 8), pad, 0);
        String msg;
        if (existing.dir && !incoming.dir) {
            msg = "A folder with this name is in the way, so this file cannot replace it. You can keep both.";
        } else if (!existing.dir && incoming.dir) {
            msg = "A file with this name is in the way of this folder. Replace removes that file.";
        } else {
            msg = "Already there:  " + describe(existing) + "\nBeing copied:  " + describe(incoming);
        }
        TextView t = new TextView(a);
        t.setText(msg);
        t.setTextSize(15);
        box.addView(t);
        final CheckBox all = new CheckBox(a);
        all.setText("Do this for the rest");
        box.addView(all);
        final boolean[] answered = {false};
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("\"" + FsPath.name(destPath) + "\" already exists")
                .setView(box)
                .setPositiveButton("Replace", (x, w) -> {
                    answered[0] = true;
                    answer.set(TransferEngine.Decision.of(TransferEngine.Policy.REPLACE, all.isChecked()));
                })
                .setNeutralButton("Keep both", (x, w) -> {
                    answered[0] = true;
                    answer.set(TransferEngine.Decision.of(TransferEngine.Policy.BOTH, all.isChecked()));
                })
                .setNegativeButton("Skip", (x, w) -> {
                    answered[0] = true;
                    answer.set(TransferEngine.Decision.of(TransferEngine.Policy.SKIP, all.isChecked()));
                })
                .setOnCancelListener(x -> {
                    if (!answered[0]) {
                        answered[0] = true;
                        answer.set(TransferEngine.Decision.stop());
                    }
                })
                .create();
        d.setCanceledOnTouchOutside(false);
        d.show();
    }

    /** Asks for a line of text. selectBase: preselect the name without its extension (rename). */
    static void prompt(Activity a, String title, String initial, String button, boolean selectBase, final Consumer<String> ok) {
        final EditText et = new EditText(a);
        et.setSingleLine(true);
        et.setText(initial);
        et.setSelection(0, selectBase ? FsPath.base(initial).length() : initial.length());
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(et)
                .setPositiveButton(button, (x, w) -> ok.accept(et.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .create();
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        d.show();
    }

    static void confirmDelete(Activity a, List<String> names, String where, final Runnable ok) {
        String what = names.size() == 1 ? "\"" + names.get(0) + "\"" : names.size() + " items";
        new AlertDialog.Builder(a)
                .setTitle("Delete " + what + "?")
                .setMessage("This removes " + (names.size() == 1 ? "it" : "them") + " from " + where + " for good. It can't be undone.")
                .setPositiveButton("Delete", (x, w) -> ok.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    static void message(Activity a, String title, String body) {
        TextView t = new TextView(a);
        t.setText(body);
        t.setTextSize(14);
        t.setTextIsSelectable(true);
        int pad = Ui.dp(a, 20);
        t.setPadding(pad, Ui.dp(a, 10), pad, 0);
        new AlertDialog.Builder(a).setTitle(title).setView(t).setPositiveButton("OK", null).show();
    }

    /** Choose the order. Choosing the one already in use reverses it. */
    static void sort(Activity a, final FilesPrefs p, final Runnable changed) {
        final FileSort.Order[] orders = FileSort.Order.values();
        String[] base = {"Name", "Date modified", "Size", "Type"};
        String[] labels = new String[orders.length];
        int cur = p.order().ordinal();
        for (int i = 0; i < orders.length; i++) {
            labels[i] = base[i] + (i == cur ? (p.ascending() ? "   ▲" : "   ▼") : "");
        }
        final int fcur = cur;
        new AlertDialog.Builder(a)
                .setTitle("Sort by (choose again to reverse)")
                .setSingleChoiceItems(labels, cur, (d, which) -> {
                    FileSort.Order o = orders[which];
                    boolean asc = which == fcur ? !p.ascending() : (o == FileSort.Order.NAME || o == FileSort.Order.KIND);
                    p.setOrder(o, asc);
                    d.dismiss();
                    changed.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
