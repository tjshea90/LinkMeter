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
            if (f.has("direct")) {
                p.direct = f.optBoolean("direct");
                p.directUp = f.optBoolean("dup", false);
                p.directErr = f.optString("derr", "");
                String ssid = f.optString("ssid", "");
                String pass = f.optString("pass", "");
                if (!ssid.isEmpty() && pass.length() >= 8) {
                    Cfg.setFastNetwork(act, ssid, pass);
                    if (direct()) {
                        TabletDirect.suggest(act, ssid, pass); // once; again only if the phone's password changed
                    }
                }
            }
        }
        p.hotspot = s.has("hs") ? Boolean.valueOf(s.optBoolean("hs")) : null;
        phone = p;
        paint();
    }

    /** Fast Wi-Fi uses the phone's own Wi-Fi Direct network (Settings > Fast Wi-Fi), not its hotspot. */
    private boolean direct() {
        return FastRules.LINK_DIRECT.equals(Cfg.fastLink(act));
    }

    /** Asks the phone to switch its own network on (the hotspot goes off) or off (the hotspot comes back). */
    private void sendDirect(boolean on) {
        button.setEnabled(false);
        note = on ? "Asking the phone to switch its hotspot off and its own Wi-Fi network on…"
                : "Asking the phone to switch its own Wi-Fi network off and the hotspot on…";
        paint();
        Call.run(act, "POST", "/act?name=" + (on ? "fast_direct_on" : "fast_direct_off"), 45000, (ok, text, json) -> {
            button.setEnabled(true);
            note = text;
            paint();
            if (on && ok) {
                // Android joins the network at its next look for networks: ask for a look now, and once more a little later
                act.ui.postDelayed(() -> TabletDirect.scan(act), 5000);
                act.ui.postDelayed(() -> TabletDirect.scan(act), 15000);
            }
        });
    }

    private void picked(int i) {
        note = "";
        boolean want = i == 1;
        if (want) {
            if (direct()) {
                TabletDirect.suggest(act, Cfg.fastSsid(act), Cfg.fastPass(act));
            }
            if (!Cfg.fastWanted(act) || FastVpn.state == FastVpn.State.OFF || FastVpn.state == FastVpn.State.PERMISSION) {
                act.startFastWifi();
            }
            if (direct() && phone.known && Boolean.FALSE.equals(phone.direct)) {
                sendDirect(true); // the hotspot goes off and the phone's own network comes on: the tablet follows by itself
            }
        } else {
            if (Cfg.fastWanted(act) || FastVpn.state != FastVpn.State.OFF) {
                FastVpn.stop(act);
            }
            if (phone.known && Boolean.TRUE.equals(phone.direct)) {
                sendDirect(false); // back to the normal hotspot, which the tablet joins by itself
            }
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
            case DIRECT_ON:
                if (direct()) {
                    TabletDirect.suggest(act, Cfg.fastSsid(act), Cfg.fastPass(act));
                }
                sendDirect(true);
                break;
            case WIFI_SETTINGS:
                TabletDirect.scan(act);
                if (!TabletWifi.openSwitch(act)) {
                    note = "The tablet's Wi-Fi settings could not be opened. Use Android's quick settings.";
                    paint();
                }
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
        FastRules.Card c = FastRules.card(Cfg.fastWanted(act), Cfg.fastLink(act), FastVpn.state.name(), FastVpn.detail, phone, speed);
        choice.select(c.pick);
        line.setText(note.isEmpty() ? c.line : c.line + "\n" + note);
        line.setTextColor(c.tone == FastRules.GREEN ? Ui.GREEN : c.tone == FastRules.AMBER ? Ui.AMBER : Ui.DIM);
        action = c.action;
        button.setText(c.actionLabel);
        button.setVisibility(c.action == FastRules.Action.NONE ? View.GONE : View.VISIBLE);
    }
}
