package com.tj.linkmeter;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * The remote screen's advanced settings, drawn inside the app over the picture (a dialog would pull the
 * system bars back in full screen). Changes are saved at once; the picture is restarted, or the phone
 * told, only when the panel is closed, so moving a slider does not restart anything.
 */
final class AdvancedPanel {
    interface Host {
        /** The panel was closed: video settings and/or the phone options changed. */
        void closed(boolean video, boolean power);

        /** Put the phone's screen settings back right now. */
        void restorePhone();

        String diagnostics();

        String phoneText();

        void copy(String text);
    }

    private static final int[] PIC_QUALITY = {20, 30, 40, 50, 60, 70, 80, 90};

    private final Context c;
    private final ScreenPrefs prefs;
    private final Host host;
    private final ScrollView view;
    private boolean dirtyVideo;
    private boolean dirtyPower;
    private TextView phoneInfo;

    AdvancedPanel(Context c, ScreenPrefs prefs, Host host) {
        this.c = c;
        this.prefs = prefs;
        this.host = host;
        view = new ScrollView(c);
        view.setBackgroundColor(0xF2101216);
        view.setClickable(true);
        build();
    }

    View view() {
        return view;
    }

    /** Shows the phone's answer about its screen settings (called when it arrives). */
    void setPhoneText(String t) {
        if (phoneInfo != null) {
            phoneInfo.setText(t);
        }
    }

    private void rebuild() {
        final int y = view.getScrollY();
        build();
        view.post(() -> view.scrollTo(0, y));
    }

    private TextView heading(String s) {
        TextView t = Ui.text(c, s, 15, Ui.FG, true);
        t.setPadding(0, Ui.dp(c, 16), 0, Ui.dp(c, 2));
        return t;
    }

    private TextView note(String s) {
        return Ui.text(c, s, 11, Ui.DIM, false);
    }

    private static int nearest(int[] arr, int v) {
        int best = 0;
        for (int i = 1; i < arr.length; i++) {
            if (Math.abs(arr[i] - v) < Math.abs(arr[best] - v)) {
                best = i;
            }
        }
        return best;
    }

    private static String kbps(int k) {
        return k < 1000 ? k + " kbit/s" : String.format(Locale.US, "%.1f Mbit/s", k / 1000f).replace(".0 ", " ");
    }

    /** A row of buttons of which one is selected. */
    private LinearLayout choice(String[] names, int selected, final IntConsumer pick) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final Button[] bs = new Button[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            Button b = Ui.button(c, names[i], v -> {
                pick.accept(idx);
                for (int j = 0; j < bs.length; j++) {
                    bs[j].setAlpha(j == idx ? 1f : 0.5f);
                }
            });
            b.setTextSize(12);
            b.setPadding(0, 0, 0, 0);
            b.setAlpha(i == selected ? 1f : 0.5f);
            bs[i] = b;
            row.addView(b, Ui.weight());
        }
        return row;
    }

    /** A slider over a list of values; the value is saved when the finger lifts. */
    private View slider(final String title, final int[] values, int current, final java.util.function.Function<Integer, String> fmt,
            final IntConsumer set) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView label = Ui.text(c, title + ": " + fmt.apply(values[nearest(values, current)]), 13, Ui.FG, false);
        box.addView(label, Ui.lp(c, 8));
        SeekBar sb = new SeekBar(c);
        sb.setMax(values.length - 1);
        sb.setProgress(nearest(values, current));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                label.setText(title + ": " + fmt.apply(values[p]));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                set.accept(values[s.getProgress()]);
                rebuild(); // a preset may now read "Custom"
            }
        });
        box.addView(sb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private CheckBox check(String text, boolean on, final java.util.function.Consumer<Boolean> set) {
        CheckBox cb = new CheckBox(c);
        cb.setText(text);
        cb.setTextColor(Ui.FG);
        cb.setTextSize(13);
        cb.setChecked(on);
        cb.setOnCheckedChangeListener((v, checked) -> set.accept(checked));
        return cb;
    }

    private void build() {
        view.removeAllViews();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 14);
        col.setPadding(pad, pad, pad, pad * 2);

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(Ui.text(c, "Remote screen options", 18, Ui.FG, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(Ui.button(c, "Done", v -> finish()));
        col.addView(top);

        // ---------------------------------------------------------------- picture
        col.addView(heading("Picture"));
        col.addView(choice(new String[] {"Live video", "Still pictures"}, prefs.mode(), i -> {
            prefs.setMode(i);
            dirtyVideo = true;
        }));
        col.addView(note("Live video is smooth. Still pictures is the fallback: one picture after another, "
                + "slower but it works everywhere."), Ui.lp(c, 2));

        col.addView(Ui.text(c, "Quality preset", 13, Ui.FG, false), Ui.lp(c, 10));
        col.addView(choice(ScreenPrefs.PRESETS, prefs.preset(), i -> {
            if (i < 4) {
                prefs.setPreset(i);
                dirtyVideo = true;
                rebuild();
            }
        }));

        col.addView(slider("Video width", ScreenPrefs.WIDTHS, prefs.vidWidth(), v -> v + " px", v -> {
            prefs.setVidWidth(v);
            dirtyVideo = true;
        }));
        col.addView(slider("Video bitrate limit", ScreenPrefs.KBPS, prefs.vidKbps(), AdvancedPanel::kbps, v -> {
            prefs.setVidKbps(v);
            dirtyVideo = true;
        }));
        col.addView(note("The phone's video encoder aims for this bitrate on average. A lower number uses "
                + "less data and the phone works less; busy screens can still go above it for a moment (the "
                + "speed numbers show the real rate and the peak). The phone's own recorder has no setting for "
                + "frames per second, so there is none here for video."), Ui.lp(c, 2));

        col.addView(slider("Pictures width", ScreenPrefs.WIDTHS, prefs.picWidth(), v -> v + " px", v -> {
            prefs.setPicWidth(v);
            dirtyVideo = true;
        }));
        col.addView(slider("Pictures quality", PIC_QUALITY, prefs.picQuality(), v -> String.valueOf(v), v -> {
            prefs.setPicQuality(v);
            dirtyVideo = true;
        }));
        final int[] idx = {0, 1, 2, 3, 4};
        col.addView(slider("Pictures per second (at most)", idx, prefs.picFpsIndex(), v -> {
            int f = ScreenPrefs.PIC_FPS[v];
            return f == 0 ? "as fast as possible" : String.valueOf(f);
        }, v -> {
            prefs.setPicFpsIndex(v);
            dirtyVideo = true;
        }));

        col.addView(check("Show a sharp still picture when the phone's screen stops changing", prefs.stillImage(), v -> {
            prefs.setStillImage(v);
            dirtyVideo = true;
        }), Ui.lp(c, 8));
        col.addView(check("Show speed numbers (pictures per second, kbit/s)", prefs.showStats(), prefs::setShowStats));

        // ---------------------------------------------------------------- decoder
        col.addView(heading("Video decoder on this tablet"));
        col.addView(Ui.text(c, "Low-delay tuning", 13, Ui.FG, false));
        col.addView(choice(ScreenPrefs.FIX_NAMES, prefs.lowDelay(), i -> {
            prefs.setLowDelay(i);
            dirtyVideo = true;
        }));
        col.addView(note("Asks the tablet's decoder to show each picture at once instead of holding some back, "
                + "which is what made the picture stick until you touched the screen. Automatic turns it off "
                + "by itself if the decoder shows nothing."), Ui.lp(c, 2));
        final List<String> names = VideoStream.decoders();
        String cur = prefs.decoder();
        col.addView(Ui.button(c, "Decoder: " + (cur.isEmpty() ? "automatic" : cur), v -> {
            int at = names.indexOf(prefs.decoder());
            int next = at + 1; // -1 (automatic) goes to 0, the last one wraps to automatic
            prefs.setDecoder(next >= names.size() ? "" : names.get(next));
            dirtyVideo = true;
            rebuild();
        }), Ui.lp(c, 6));

        // ---------------------------------------------------------------- phone
        col.addView(heading("The phone while you use this"));
        col.addView(Ui.text(c, "Keep the phone's screen on for at least", 13, Ui.FG, false));
        String[] keepNames = new String[ScreenPrefs.KEEP_MIN.length];
        for (int i = 0; i < keepNames.length; i++) {
            keepNames[i] = ScreenPrefs.KEEP_MIN[i] == 0 ? "Off" : ScreenPrefs.KEEP_MIN[i] + " min";
        }
        col.addView(choice(keepNames, prefs.keepIndex(), i -> {
            prefs.setKeepIndex(i);
            dirtyPower = true;
        }));
        col.addView(Ui.text(c, "Phone screen brightness", 13, Ui.FG, false), Ui.lp(c, 8));
        col.addView(choice(ScreenPrefs.DIM_NAMES, prefs.dimIndex(), i -> {
            prefs.setDimIndex(i);
            dirtyPower = true;
        }));
        col.addView(note("The picture on this tablet is not dimmed: it comes from the screen's contents, not "
                + "its brightness. Everything is put back when you leave this tab, or by itself a minute "
                + "after the tablet disappears. Settings you change by hand on the phone meanwhile are kept."),
                Ui.lp(c, 2));
        col.addView(check("Turn the phone's screen off when I leave (if I woke it from here)", prefs.sleepOnLeave(),
                prefs::setSleepOnLeave), Ui.lp(c, 6));
        col.addView(check("Wake a sleeping phone when I tap the picture", prefs.tapToWake(), prefs::setTapToWake));
        phoneInfo = Ui.text(c, host.phoneText(), 12, Ui.DIM, false);
        col.addView(phoneInfo, Ui.lp(c, 6));
        col.addView(Ui.button(c, "Put the phone's screen settings back now", v -> host.restorePhone()), Ui.lp(c, 4));

        // ---------------------------------------------------------------- info
        col.addView(heading("Information"));
        final TextView info = Ui.text(c, host.diagnostics(), 11, Ui.FG, false);
        info.setTypeface(Typeface.MONOSPACE);
        info.setTextIsSelectable(true);
        info.setPadding(Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10));
        info.setBackgroundColor(Ui.CARD);
        col.addView(info);
        LinearLayout last = new LinearLayout(c);
        last.setOrientation(LinearLayout.HORIZONTAL);
        last.addView(Ui.button(c, "Copy this information", v -> host.copy(host.diagnostics())), Ui.weight());
        last.addView(Ui.button(c, "Reset all", v -> {
            prefs.reset();
            dirtyVideo = true;
            dirtyPower = true;
            rebuild();
        }), Ui.weight());
        col.addView(last, Ui.lp(c, 6));
        col.addView(Ui.button(c, "Done", v -> finish()), Ui.lp(c, 10));

        view.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Closes the panel as the Done button does (the Back key uses this). */
    void close() {
        finish();
    }

    private void finish() {
        host.closed(dirtyVideo, dirtyPower);
        dirtyVideo = false;
        dirtyPower = false;
    }
}
