package com.tj.linkmeter;

import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

/**
 * Tablet only. The Home card that switches the tablet's internet between the phone's normal hotspot and Fast Wi-Fi
 * (docs/FAST_WIFI.md): one row of two choices, one line that says what is true now (FastRules), and one button when something has
 * to be done first (Android's VPN question, the hotspot, the phone's switch). While Home is on screen and Fast Wi-Fi is wanted it
 * shows the speed every two seconds; otherwise it does nothing.
 */
final class FastCard {
    private final MainActivity act;
    private final Runnable hotspotOn;
    final LinearLayout view;
    private final Ui.Choice choice;
    private final TextView line;
    private final Button button;
    private FastRules.Action action = FastRules.Action.NONE;
    private FastRules.Phone phone = new FastRules.Phone();
    private boolean shown;
    private long lastDown = -1;
    private long lastUp;
    private long lastAt;
    private String speed = "";
    private String note = "";

    /** hotspotOn: Home's own "Hotspot on" (with its follow-up and the tablet's Wi-Fi). */
    FastCard(MainActivity a, Runnable hotspotOn) {
        act = a;
        this.hotspotOn = hotspotOn;
        view = Ui.card(a, "Internet for this tablet", null);
        choice = new Ui.Choice(a, new String[] {"Hotspot", "Fast Wi-Fi"}, Cfg.fastWanted(a) ? 1 : 0, this::picked);
        view.addView(choice.view, Ui.lp(a, 8));
        line = Ui.hint(a, "");
        view.addView(line, Ui.lp(a, 8));
        button = Ui.primary(a, "", v -> pressed());
        button.setVisibility(View.GONE);
        view.addView(button, Ui.lp(a, 8));
        paint();
    }

    void onShow() {
        shown = true;
        lastDown = -1;
        tick();
    }

    void onHide() {
        shown = false;
        act.ui.removeCallbacks(tickTask);
    }

    /** The phone's latest status (null: no answer this time; what was known stays until a new answer). */
    void phone(JSONObject s) {
        if (s == null) {
            return;
        }
        FastRules.Phone p = new FastRules.Phone();
        p.known = true;
        JSONObject f = s.optJSONObject("fast");
        p.hasFast = f != null;
        if (f != null) {
            p.allowed = f.optBoolean("allowed", true);
            p.relayOn = f.optBoolean("on", false);
            p.err = f.optString("err", "");
        }
        p.hotspot = s.has("hs") ? Boolean.valueOf(s.optBoolean("hs")) : null;
        phone = p;
        paint();
    }

    private void picked(int i) {
        note = "";
        boolean want = i == 1;
        if (want) {
            if (!Cfg.fastWanted(act) || FastVpn.state == FastVpn.State.OFF || FastVpn.state == FastVpn.State.PERMISSION) {
                act.startFastWifi();
            }
        } else if (Cfg.fastWanted(act) || FastVpn.state != FastVpn.State.OFF) {
            FastVpn.stop(act);
        }
        lastDown = -1;
        speed = "";
        paint();
        act.ui.removeCallbacks(tickTask);
        act.ui.postDelayed(tickTask, 700); // the service answers within a moment: show it
    }

    private void pressed() {
        switch (action) {
            case CONSENT:
                act.startFastWifi();
                break;
            case HOTSPOT_ON:
                hotspotOn.run();
                break;
            case ALLOW:
                button.setEnabled(false);
                Call.run(act, "POST", "/act?name=fast_allow", 15000, (ok, text, json) -> {
                    button.setEnabled(true);
                    note = text;
                    paint();
                });
                break;
            default:
                break;
        }
    }

    private final Runnable tickTask = this::tick;

    private void tick() {
        measure();
        paint();
        if (shown && Cfg.fastWanted(act)) {
            act.ui.postDelayed(tickTask, 2000);
        }
    }

    private void measure() {
        TunStack e = FastVpn.engine;
        if (e == null || FastVpn.state != FastVpn.State.ON) {
            lastDown = -1;
            speed = "";
            return;
        }
        long d = e.tcpDown + e.udpDown;
        long u = e.tcpUp + e.udpUp;
        long now = SystemClock.elapsedRealtime();
        if (lastDown >= 0) {
            String s = FastRules.speed(d - lastDown, u - lastUp, now - lastAt);
            speed = s + " " + FastRules.size(d + u) + " so far, " + e.open + (e.open == 1 ? " connection." : " connections.");
        }
        lastDown = d;
        lastUp = u;
        lastAt = now;
    }

    private void paint() {
        FastRules.Card c = FastRules.card(Cfg.fastWanted(act), FastVpn.state.name(), FastVpn.detail, phone, speed);
        choice.select(c.pick);
        line.setText(note.isEmpty() ? c.line : c.line + "\n" + note);
        line.setTextColor(c.tone == FastRules.GREEN ? Ui.GREEN : c.tone == FastRules.AMBER ? Ui.AMBER : Ui.DIM);
        action = c.action;
        button.setText(c.actionLabel);
        button.setVisibility(c.action == FastRules.Action.NONE ? View.GONE : View.VISIBLE);
    }
}
