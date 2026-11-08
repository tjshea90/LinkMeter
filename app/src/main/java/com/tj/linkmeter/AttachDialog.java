package com.tj.linkmeter;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * "Send to the phone": the Attach button of the remote screen. Recent photos on this tablet come first (tap to choose), the system
 * file picker reaches everything else, and Send puts it all on the phone where its pickers find it (see {@link Attach}).
 */
final class AttachDialog {
    private final MainActivity act;
    private AlertDialog dialog;
    private LinearLayout body;
    private TextView tCount;
    private Button bSend;
    private TextView tRecent;
    private LinearLayout strip;
    private final List<FsEntry> recent = new ArrayList<>();
    private final Set<String> chosen = new LinkedHashSet<>();
    private final List<Attach.Item> picked = new ArrayList<>();
    private Attach.Job job;
    private DiskStore local;
    /** When set, the dialog only lets the user choose: the items go here instead of to the phone (for attaching to a text). */
    private java.util.function.Consumer<List<Attach.Item>> onChosen;

    private AttachDialog(MainActivity act) {
        this.act = act;
    }

    static void show(MainActivity act) {
        new AttachDialog(act).open();
    }

    /** The same chooser, for attaching to a text: the chosen items are handed to the callback, nothing is sent from here. */
    static void choose(MainActivity act, java.util.function.Consumer<List<Attach.Item>> chosen) {
        AttachDialog d = new AttachDialog(act);
        d.onChosen = chosen;
        d.open();
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        return Ui.text(act, s, sp, color, bold);
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = Ui.button(act, label, l);
        b.setTextSize(14);
        return b;
    }

    private void open() {
        body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(act, 16);
        body.setPadding(pad, pad, pad, Ui.dp(act, 8));
        ScrollView sv = new ScrollView(act);
        sv.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog = new AlertDialog.Builder(act).setView(sv).create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d -> {
            if (job != null) {
                job.cancel();
            }
        });
        buildChooser();
        dialog.show();
        loadRecent();
    }

    private void buildChooser() {
        body.removeAllViews();
        body.addView(text(onChosen != null ? "Add to the text" : "Send to the phone", 20, Ui.FG, true));
        body.addView(text(onChosen != null ? "Choose photos or files from this tablet to send with your text."
                : "Choose photos or files from this tablet. They are put on the phone where every app's attach button looks: "
                + "photos in Pictures/LinkMeter, videos in Movies/LinkMeter, other files in Download/LinkMeter.", 13, Ui.DIM, false),
                Ui.lp(act, 6));
        body.addView(text("Recent photos on this tablet", 14, Ui.FG, true), Ui.lp(act, 14));
        tRecent = text("Looking for your photos…", 13, Ui.DIM, false);
        body.addView(tRecent, Ui.lp(act, 4));
        HorizontalScrollView hs = new HorizontalScrollView(act);
        hs.setHorizontalScrollBarEnabled(false);
        strip = new LinearLayout(act);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(strip);
        body.addView(hs, Ui.lp(act, 4));
        if (!Files.hasAccess(act)) {
            tRecent.setText("LinkMeter needs permission to show the photos on this tablet. You can still choose files below.");
            body.addView(button("Allow storage access", v -> act.fixStorageAccess()), Ui.lp(act, 4));
        }
        body.addView(button("Choose other photos or files…", v -> pickMore()), Ui.lp(act, 12));
        tCount = text("", 14, Ui.FG, true);
        body.addView(tCount, Ui.lp(act, 10));
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(button("Cancel", v -> dialog.dismiss()), Ui.weight());
        bSend = button("Send", v -> send());
        row.addView(bSend, Ui.weight());
        body.addView(row, Ui.lp(act, 8));
        refreshCount();
        showStrip();
    }

    private void refreshCount() {
        int n = chosen.size() + picked.size();
        tCount.setText(n == 0 ? "Nothing chosen yet" : n + (n == 1 ? " chosen" : " chosen"));
        String verb = onChosen != null ? "Add" : "Send";
        bSend.setText(n == 0 ? verb : verb + " " + n);
        bSend.setEnabled(n > 0);
    }

    private void loadRecent() {
        if (!Files.hasAccess(act)) {
            return;
        }
        Thread t = new Thread(() -> {
            final List<FsEntry> found = new AndroidMedia(act).recent("images", 40);
            act.ui.post(() -> {
                recent.clear();
                recent.addAll(found);
                if (strip != null) {
                    showStrip();
                }
            });
        }, "linkmeter-attach-recent");
        t.setDaemon(true);
        t.start();
    }

    private void showStrip() {
        strip.removeAllViews();
        if (!Files.hasAccess(act)) {
            return;
        }
        tRecent.setText(recent.isEmpty() ? "No photos found yet." : "Tap the photos to send.");
        try {
            if (local == null) {
                local = new DiskStore(Files.root(), "this");
            }
        } catch (IOException e) {
            tRecent.setText("This tablet's storage can't be read.");
            return;
        }
        for (final FsEntry e : recent) {
            FrameLayout cell = new FrameLayout(act);
            final ImageView iv = new ImageView(act);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(new FileIcon(FileKind.IMAGE, e.name));
            cell.addView(iv, new FrameLayout.LayoutParams(Ui.dp(act, 84), Ui.dp(act, 84)));
            final TextView tick = text("✓", 16, Color.WHITE, true);
            tick.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Ui.ACCENT);
            tick.setBackground(g);
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(Ui.dp(act, 24), Ui.dp(act, 24), Gravity.TOP | Gravity.END);
            tp.topMargin = Ui.dp(act, 4);
            tp.rightMargin = Ui.dp(act, 4);
            cell.addView(tick, tp);
            tick.setVisibility(chosen.contains(e.path) ? View.VISIBLE : View.GONE);
            cell.setOnClickListener(v -> {
                if (!chosen.remove(e.path)) {
                    chosen.add(e.path);
                }
                tick.setVisibility(chosen.contains(e.path) ? View.VISIBLE : View.GONE);
                refreshCount();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(act, 84), Ui.dp(act, 84));
            lp.rightMargin = Ui.dp(act, 6);
            strip.addView(cell, lp);
            final DiskStore disk = local;
            Thumbs.get(act).load(iv, "attach|" + e.path + "|" + e.time + "|" + e.size, e.path, FileKind.IMAGE, 160,
                    (key, p, kind, px) -> ThumbMaker.bitmap(act, disk.resolve(p), kind, 160));
        }
    }

    private void pickMore() {
        act.pickFiles((result, data) -> {
            if (result == Activity.RESULT_OK) {
                picked.addAll(Attach.fromPicker(act, data));
                if (tCount != null) {
                    refreshCount();
                }
            }
        });
    }

    private void send() {
        List<Attach.Item> items = new ArrayList<>();
        for (String path : chosen) {
            for (FsEntry e : recent) {
                if (e.path.equals(path)) {
                    items.add(new Attach.Item(e.name, e.size, e.time, path, null));
                    break;
                }
            }
        }
        items.addAll(picked);
        if (items.isEmpty()) {
            Toast.makeText(act, "Choose something first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (onChosen != null) {
            dialog.dismiss();
            onChosen.accept(items);
            return;
        }
        body.removeAllViews();
        body.addView(text("Sending to the phone…", 18, Ui.FG, true));
        final TextView tProg = text("Starting…", 13, Ui.FG, false);
        body.addView(tProg, Ui.lp(act, 10));
        final ProgressBar bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        body.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 10)));
        final Button stop = button("Stop", null);
        body.addView(stop, Ui.lp(act, 12));
        job = Attach.send(act, items, new Attach.Listener() {
            @Override
            public void progress(String t, int permille) {
                tProg.setText(t);
                bar.setProgress(permille);
            }

            @Override
            public void done(Attach.Result r) {
                showResult(r);
            }
        });
        stop.setOnClickListener(v -> {
            job.cancel();
            tProg.setText("Stopping…");
        });
    }

    private void showResult(Attach.Result r) {
        body.removeAllViews();
        StringBuilder sb = new StringBuilder();
        if (r.sent > 0) {
            body.addView(text(r.cancelled ? "Stopped" : "Sent", 20, Ui.GREEN, true));
            sb.append(r.sent == 1 ? "1 file" : r.sent + " files").append(" (").append(FsFormat.size(r.bytes)).append(") now on the phone");
            if (r.cancelled) {
                sb.append(" before it was stopped");
            }
            sb.append(".\n\nFolders:\n");
            Set<String> dirs = new LinkedHashSet<>();
            for (String w : r.where) {
                dirs.add(FsPath.parent(w));
            }
            for (String d : dirs) {
                sb.append("• ").append(d.substring(1)).append('\n');
            }
            sb.append("\nIn the app you are using on the phone, tap its attach (paperclip) button, then choose Gallery, Photos or Files "
                    + "and look under Recent, in the LinkMeter album, or in Download > LinkMeter.");
        } else {
            body.addView(text(r.cancelled ? "Stopped" : "Nothing was sent", 20, Ui.AMBER, true));
            sb.append(r.cancelled ? "Nothing reached the phone." : "The phone did not take the files.");
        }
        if (!r.errors.isEmpty()) {
            sb.append("\n\nProblems:\n");
            for (String e : r.errors) {
                sb.append("• ").append(e).append('\n');
            }
        }
        TextView t = text(sb.toString().trim(), 14, Ui.FG, false);
        t.setTextIsSelectable(true);
        body.addView(t, Ui.lp(act, 8));
        body.addView(button("OK", v -> dialog.dismiss()), Ui.lp(act, 12));
    }
}
