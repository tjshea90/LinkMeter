package com.tj.linkmeter;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * See and control the phone's screen from the tablet: a picture of the screen about every half
 * second, taps and swipes on it, Wake / Sleep, the phone's PIN, and the usual keys. Pictures are
 * fetched only while this tab is open, one at a time.
 */
final class ScreenTab extends Tab {
    private PhoneScreenView view;
    private TextView status;
    private CheckBox sleepBox;

    private HandlerThread worker;
    private volatile Handler bg;
    private volatile boolean polling;
    private boolean woke;

    private static final class Frame {
        Bitmap bmp;
        int w;
        int h;
        boolean off;
        String note = "";
    }

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            Handler h = bg;
            if (h == null || !polling) {
                return;
            }
            final Frame f = fetch();
            act.ui.post(() -> show(f));
            if (polling && bg == h) {
                h.postDelayed(this, f.bmp != null ? 120 : (f.off ? 1200 : 2000));
            }
        }
    };

    ScreenTab(MainActivity a) {
        super(a);
    }

    @Override
    String title() {
        return "Screen";
    }

    @Override
    View build() {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(act, 8);
        col.setPadding(pad, pad, pad, pad);

        status = Ui.text(act, "Connecting…", 12, Ui.DIM, false);
        col.addView(status);

        view = new PhoneScreenView(act);
        view.setListener(new PhoneScreenView.Listener() {
            @Override
            public void tap(int x, int y) {
                post("/screen/tap?x=" + x + "&y=" + y);
            }

            @Override
            public void swipe(int x1, int y1, int x2, int y2, int ms) {
                post("/screen/swipe?x1=" + x1 + "&y1=" + y1 + "&x2=" + x2 + "&y2=" + y2 + "&ms=" + ms);
            }
        });
        col.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout r1 = new LinearLayout(act);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.addView(Ui.button(act, "Wake", v -> {
            woke = true;
            post("/screen/on");
        }), Ui.weight());
        r1.addView(Ui.button(act, "Sleep", v -> post("/screen/off")), Ui.weight());
        r1.addView(Ui.button(act, "Unlock", v -> askPin()), Ui.weight());
        col.addView(r1);

        LinearLayout r2 = new LinearLayout(act);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        r2.addView(Ui.button(act, "Back", v -> key(4)), Ui.weight());
        r2.addView(Ui.button(act, "Home", v -> key(3)), Ui.weight());
        r2.addView(Ui.button(act, "Recents", v -> key(187)), Ui.weight());
        r2.addView(Ui.button(act, "Vol -", v -> key(25)), Ui.weight());
        r2.addView(Ui.button(act, "Vol +", v -> key(24)), Ui.weight());
        col.addView(r2);

        sleepBox = new CheckBox(act);
        sleepBox.setText("Turn the phone's screen off when I leave this tab");
        sleepBox.setTextColor(Ui.FG);
        sleepBox.setChecked(true);
        col.addView(sleepBox);
        return col;
    }

    @Override
    void onShow() {
        if (polling) {
            return;
        }
        polling = true;
        worker = new HandlerThread("linkmeter-screen");
        worker.start();
        bg = new Handler(worker.getLooper());
        bg.post(loop);
    }

    @Override
    void onHide() {
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
        if (woke && sleepBox != null && sleepBox.isChecked()) {
            post("/screen/off");
        }
        woke = false;
    }

    // ------------------------------------------------------------ sending input

    private void post(String target) {
        Call.run(act, "POST", target, 15000, (ok, text, json) -> {
            if (!ok && status != null) {
                status.setText(text);
            }
        });
    }

    private void key(int code) {
        post("/screen/key?code=" + code);
    }

    private void askPin() {
        final EditText pin = new EditText(act);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        pin.setSingleLine(true);
        pin.setHint("PIN");
        new AlertDialog.Builder(act)
                .setTitle("Unlock the phone")
                .setMessage("Type the phone's PIN. It is sent once to the phone and never saved. "
                        + "After several wrong tries Android makes you wait.")
                .setView(pin)
                .setPositiveButton("Unlock", (d, w) -> {
                    String p = pin.getText().toString();
                    pin.setText("");
                    woke = true;
                    unlock(p);
                })
                .setNegativeButton("Cancel", (d, w) -> pin.setText(""))
                .show();
    }

    private void unlock(final String pin) {
        status.setText("Entering the PIN…");
        new Thread(() -> {
            byte[] bytes = pin.getBytes(StandardCharsets.UTF_8);
            String msg;
            try {
                String host = Net.phoneHost(act);
                if (host.isEmpty()) {
                    throw new IOException("The tablet is not on the phone's hotspot.");
                }
                try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
                    Client.Resp r = Client.put(host, "/screen/pin", Cfg.token(act), in, bytes.length, null);
                    msg = r.body;
                    try {
                        JSONObject j = new JSONObject(r.body);
                        msg = j.has("err") ? j.optString("err") : j.optString("out", r.body);
                    } catch (JSONException plain) {
                        // not JSON: show it as it came
                    }
                }
            } catch (IOException e) {
                msg = "Couldn't reach the phone: " + e.getMessage();
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
            final String fMsg = msg;
            act.ui.post(() -> {
                if (status != null) {
                    status.setText(fMsg);
                }
            });
        }, "linkmeter-pin").start();
    }

    // ------------------------------------------------------------ getting pictures

    /** Worker thread: one picture, or a note saying why not. */
    private Frame fetch() {
        Frame f = new Frame();
        String host = Net.phoneHost(act);
        if (host.isEmpty()) {
            f.note = "The tablet is not on the phone's hotspot.";
            return f;
        }
        try (Client.Stream st = Client.get(host, "/screen/frame?w=540", Cfg.token(act), 3000, 10000)) {
            byte[] data = readAll(st);
            if (st.code != 200) {
                f.note = errText(data);
                return f;
            }
            if (data.length > 0 && data[0] == '{') {
                f.off = true;
                f.note = "The phone's screen is off.\nTap Wake.";
                return f;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            f.bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            String[] wh = st.size.split("x");
            if (f.bmp == null || wh.length != 2) {
                f.bmp = null;
                f.note = "The phone sent a picture this tablet couldn't read.";
                return f;
            }
            f.w = Integer.parseInt(wh[0].trim());
            f.h = Integer.parseInt(wh[1].trim());
        } catch (IOException | NumberFormatException e) {
            f.bmp = null;
            f.note = "Couldn't reach the phone: " + e.getMessage();
        }
        return f;
    }

    private static byte[] readAll(Client.Stream st) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(st.length > 0 ? (int) Math.min(st.length, 1 << 20) : 4096);
        byte[] buf = new byte[8192];
        int r;
        while ((r = st.in.read(buf)) > 0) {
            bo.write(buf, 0, r);
            if (bo.size() > (1 << 20)) {
                throw new IOException("picture too big");
            }
        }
        return bo.toByteArray();
    }

    private static String errText(byte[] data) {
        String s = new String(data, StandardCharsets.UTF_8);
        try {
            return new JSONObject(s).optString("err", s);
        } catch (JSONException e) {
            return s;
        }
    }

    /** UI thread. */
    private void show(Frame f) {
        if (view == null) {
            return;
        }
        if (f.bmp != null) {
            view.setFrame(f.bmp, f.w, f.h);
            status.setText("Phone screen " + f.w + "×" + f.h + ". Tap or drag the picture to control it.");
        } else {
            view.setNote(f.note);
            status.setText(f.off ? "The phone's screen is off." : f.note);
        }
    }
}
