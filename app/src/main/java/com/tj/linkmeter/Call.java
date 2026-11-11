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

    /** Package-visible so the screen preview (tools/uipreview) can put a pretend phone here. */
    static Reach reach;

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
                    try {
                        return Client.call(host, method, target, Cfg.token(app), 2500, readMs);
                    } catch (Client.ConnectFailed e) {
                        throw new Client.ConnectFailed(new IOException(Client.why(e))); // still "nothing was sent", in plainer words
                    }
                }
            }, new Reach.Bluetooth() {
                @Override
                public boolean ready() {
                    return BtLink.ready(app);
                }

                @Override
                public Client.Resp call(String method, String target, int readMs, boolean pressed) throws IOException {
                    return BtLink.call(app, method, target, readMs, pressed);
                }
            }, SystemClock::elapsedRealtime);
        }
        return reach;
    }

    /** "wifi", "bt" or "": the route that carried the last answer (for a line on the screen). */
    static String lastVia(Context c) {
        return reach(c).lastVia();
    }

    /** A status of the phone as {@link FollowUp} looks at it; null for no status (the phone did not answer). */
    static FollowUp.Seen seen(JSONObject status) {
        return status == null ? null : new FollowUp.Seen(flag(status, "hs"), flag(status, "scr"), flag(status, "wifi"),
                flag(status, "air"), flag(status, "loc"), status.optString("dres", ""));
    }

    /** A yes/no field of the phone's status, or null when the phone left it out (it cannot tell, or its app is older). */
    static Boolean flag(JSONObject status, String name) {
        return status.has(name) ? Boolean.valueOf(status.optBoolean(name)) : null;
    }

    static void run(MainActivity act, String method, String target, int readMs, Done d) {
        run(act, method, target, readMs, false, d);
    }

    /** The same over the hotspot route only, never over Bluetooth: for a question a screen repeats ({@link Reach#hotspotOnly}). */
    static void runOverHotspot(MainActivity act, String method, String target, int readMs, Done d) {
        run(act, method, target, readMs, true, d);
    }

    private static void run(final MainActivity act, final String method, final String target,
            final int readMs, final boolean hotspotOnly, final Done d) {
        new Thread(() -> {
            boolean ok = false;
            String text;
            JSONObject json = null;
            Reach.Result res;
            try {
                res = hotspotOnly ? reach(act).hotspotOnly(method, target, readMs) : reach(act).call(method, target, readMs);
            } catch (RuntimeException e) {
                // an escape from this thread would end the app; said as what it is, a request that could not be made
                res = new Reach.Result();
                res.error = "LinkMeter could not send that (" + e + ").";
            }
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
