package com.tj.linkmeter;

import android.content.Context;
import android.os.SystemClock;
import java.io.IOException;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Tablet side: one request to the phone on a short-lived thread, answered on the UI thread. The route (the hotspot, or Bluetooth
 * when the hotspot is off) is chosen by {@link Reach}.
 */
final class Call {
    private Call() {}

    interface Done {
        /** ok is true when the phone answered 200. text is the phone's message or the error. */
        void done(boolean ok, String text, JSONObject json);
    }

    private static Reach reach;

    /** The one chooser of routes for this app (the hotspot first, Bluetooth when the hotspot route cannot connect): see {@link Reach}. */
    static synchronized Reach reach(Context c) {
        if (reach == null) {
            final Context app = c.getApplicationContext();
            reach = new Reach(new Reach.Wifi() {
                @Override
                public boolean known() {
                    return !Net.phoneHost(app).isEmpty();
                }

                @Override
                public Client.Resp call(String method, String target, int readMs) throws IOException {
                    String host = Net.phoneHost(app);
                    if (host.isEmpty()) {
                        throw new Client.ConnectFailed(new IOException("the tablet is not on the phone's hotspot"));
                    }
                    return Client.call(host, method, target, Cfg.token(app), 2500, readMs);
                }
            }, new Reach.Bluetooth() {
                @Override
                public boolean ready() {
                    return BtLink.ready(app);
                }

                @Override
                public Client.Resp call(String method, String target, int readMs) throws IOException {
                    return BtLink.call(app, method, target, readMs);
                }
            }, SystemClock::elapsedRealtime);
        }
        return reach;
    }

    /** "wifi", "bt" or "": the route that carried the last answer (for a line on the screen). */
    static String lastVia(Context c) {
        return reach(c).lastVia();
    }

    static void run(final MainActivity act, final String method, final String target,
            final int readMs, final Done d) {
        new Thread(() -> {
            boolean ok = false;
            String text;
            JSONObject json = null;
            Reach.Result res = reach(act).call(method, target, readMs);
            if (res.resp == null) {
                text = res.error;
            } else {
                Client.Resp r = res.resp;
                text = r.body;
                try {
                    json = new JSONObject(r.body);
                    if (json.has("err")) {
                        text = json.optString("err");
                    } else if (json.has("out")) {
                        text = json.optString("out");
                    }
                } catch (JSONException notJson) {
                    // plain text reply: show it as it is
                }
                ok = r.code == 200 && (json == null || !json.has("err"));
                if (r.code == 401) {
                    text = "Not paired. On the phone tap Pair tablet.";
                }
            }
            final boolean fOk = ok;
            final String fText = text;
            final JSONObject fJson = json;
            act.ui.post(() -> d.done(fOk, fText, fJson));
        }, "linkmeter-call").start();
    }
}
