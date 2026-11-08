package com.tj.linkmeter;

import java.io.IOException;
import org.json.JSONException;
import org.json.JSONObject;

/** Tablet side: one request to the phone on a short-lived thread, answered on the UI thread. */
final class Call {
    private Call() {}

    interface Done {
        /** ok is true when the phone answered 200. text is the phone's message or the error. */
        void done(boolean ok, String text, JSONObject json);
    }

    static void run(final MainActivity act, final String method, final String target,
            final int readMs, final Done d) {
        new Thread(() -> {
            boolean ok = false;
            String text;
            JSONObject json = null;
            String host = Net.phoneHost(act);
            if (host.isEmpty()) {
                text = "The tablet is not on the phone's hotspot.";
            } else {
                try {
                    Client.Resp r = Client.call(host, method, target, Cfg.token(act), 2500, readMs);
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
                } catch (IOException e) {
                    text = "Couldn't reach the phone: " + e.getMessage();
                }
            }
            final boolean fOk = ok;
            final String fText = text;
            final JSONObject fJson = json;
            act.ui.post(() -> d.done(fOk, fText, fJson));
        }, "linkmeter-call").start();
    }
}
