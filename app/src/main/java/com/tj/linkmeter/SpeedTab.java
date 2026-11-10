package com.tj.linkmeter;

import android.os.Handler;
import android.os.HandlerThread;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Speed test of the phone's mobile data, shown on the tablet. The test runs on the
 * phone. This tab asks for progress about 2.5 times a second (every 1.5 s when the phone
 * is reached over Bluetooth), only while a test is running and only while the tab is on screen.
 */
final class SpeedTab extends Tab {
    private SpeedView view;
    private Button button;
    private TextView note;

    private HandlerThread worker;
    private volatile Handler bg;
    private volatile boolean polling;
    private volatile int from;
    private volatile boolean running;
    private int failures;

    /** One progress reply, built on the worker thread. */
    private static final class Poll {
        JSONObject json;
        String error = "";
        /** The phone was called over Bluetooth for this: ask less often (each question wakes it, and the link is slow). */
        boolean bluetooth;
    }

    private final Runnable task = new Runnable() {
        @Override
        public void run() {
            Handler h = bg;
            if (h == null || !polling) {
                return;
            }
            final Poll p = fetch();
            act.ui.post(() -> apply(p));
            if (polling && bg == h && (p.json == null || isRunning(p.json))) {
                h.postDelayed(this, p.bluetooth ? 1500 : 400);
            }
        }
    };

    SpeedTab(MainActivity a) {
        super(a);
    }

    @Override
    String title() {
        return SPEED;
    }

    @Override
    Icons.Kind icon() {
        return Icons.Kind.GAUGE;
    }

    @Override
    View build() {
        LinearLayout col = Ui.column(act);
        col.addView(Ui.text(act, "Speed test", 22, Ui.FG, true));
        view = new SpeedView(act);
        col.addView(view, Ui.lp(act, 8));
        button = Ui.button(act, running ? "Stop" : "Start test", v -> toggle());
        col.addView(button, Ui.lp(act, 8));
        note = Ui.text(act, "Measures the phone's own mobile data, not the Wi-Fi link to the "
                + "tablet. Takes about 20 seconds and uses about as many MB as the download and upload speeds added together "
                + "(around 20 MB at 10 Mbit/s each way).",
                12, Ui.DIM, false);
        col.addView(note, Ui.lp(act, 8));
        from = 0;
        return Ui.scroll(act, col);
    }

    @Override
    void onShow() {
        startPolling();
    }

    @Override
    void onHide() {
        stopPolling();
    }

    private void startPolling() {
        if (polling) {
            return;
        }
        polling = true;
        failures = 0;
        worker = new HandlerThread("linkmeter-speed-poll");
        worker.start();
        bg = new Handler(worker.getLooper());
        bg.post(task);
    }

    private void stopPolling() {
        polling = false;
        Handler h = bg;
        bg = null;
        if (h != null) {
            h.removeCallbacksAndMessages(null);
        }
        if (worker != null) {
            worker.quitSafely();
            worker = null;
        }
    }

    private void toggle() {
        if (running) {
            Call.run(act, "POST", "/speed/stop", 4000, (ok, text, json) -> {
                note.setText(ok ? "Stopping…" : text);
            });
            return;
        }
        button.setEnabled(false);
        Call.run(act, "POST", "/speed/start", 5000, (ok, text, json) -> {
            button.setEnabled(true);
            if (!ok) {
                note.setText(text);
                return;
            }
            view.reset();
            from = 0;
            running = true;
            button.setText("Stop");
            stopPolling();
            startPolling();
        });
    }

    private static boolean isRunning(JSONObject j) {
        int ph = j.optInt("phase", 0);
        return ph == SpeedTest.PING || ph == SpeedTest.DOWN || ph == SpeedTest.UP;
    }

    /**
     * Worker thread. Asked through the one chooser of routes, as Start and Stop are, so a test that was started while the phone
     * is reached over Bluetooth shows its progress and its result too.
     */
    private Poll fetch() {
        Poll p = new Poll();
        Reach.Result res = Call.reach(act).call("GET", "/speed/status?from=" + from, 4000);
        p.bluetooth = res.btTried;
        if (res.resp == null) {
            p.error = res.error;
        } else if (res.resp.code == 200) {
            try {
                p.json = new JSONObject(res.resp.body);
            } catch (JSONException e) {
                p.error = "Couldn't read the phone's reply.";
            }
        } else if (res.resp.code == 401) {
            p.error = "Not paired. On the phone tap Pair tablet.";
        } else {
            p.error = "The phone replied with an error (" + res.resp.code + ").";
        }
        return p;
    }

    /** UI thread. */
    private void apply(Poll p) {
        if (view == null) {
            return;
        }
        if (p.json == null) {
            failures++;
            note.setText(p.error);
            if (failures >= 5) {
                running = false;
                button.setText("Start test");
                stopPolling();
            }
            return;
        }
        failures = 0;
        JSONObject j = p.json;
        JSONArray s = j.optJSONArray("s");
        JSONArray sp = j.optJSONArray("sp");
        if (s != null && sp != null) {
            for (int i = 0; i < s.length(); i++) {
                view.addSample(sp.optInt(i), (float) s.optDouble(i));
            }
        }
        from = j.optInt("n", from);
        int phase = j.optInt("phase", 0);
        view.setState(phase, (float) j.optDouble("live", 0), j.optDouble("ping", 0),
                j.optDouble("down", 0), j.optDouble("up", 0));
        boolean now = isRunning(j);
        running = now;
        button.setText(now ? "Stop" : "Start test");
        if (phase == SpeedTest.ERROR) {
            note.setText("The test failed: " + j.optString("error", "unknown error"));
        } else if (phase == SpeedTest.DONE) {
            String warn = j.optString("warn", "");
            note.setText("Done. Used about " + j.optLong("mb", 0) + " MB of data, over the "
                    + j.optString("via", "network") + "." + (warn.isEmpty() ? "" : "\n" + warn));
        }
        if (!now) {
            stopPolling();
        }
    }
}
