package com.tj.linkmeter;

import android.content.Context;
import android.os.SystemClock;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Every request the service answers, on either device, comes through here. */
final class Api {
    private Api() {}

    static boolean authorized(Context c, String token) {
        if (token.length() != 32) {
            return false;
        }
        byte[] t = token.getBytes(StandardCharsets.UTF_8);
        if (Cfg.PHONE.equals(Cfg.mode(c))) {
            boolean ok = false;
            for (String s : Cfg.trusted(c)) {
                ok |= MessageDigest.isEqual(t, s.getBytes(StandardCharsets.UTF_8));
            }
            return ok;
        }
        return MessageDigest.isEqual(t, Cfg.token(c).getBytes(StandardCharsets.UTF_8));
    }

    static void handle(Context c, Http.Req r, OutputStream out) throws IOException {
        boolean phone = Cfg.PHONE.equals(Cfg.mode(c));
        if ("/pair".equals(r.path)) {
            pair(c, r, out, phone);
            return;
        }
        if (!authorized(c, r.token)) {
            Http.err(out, 401, "not paired");
            return;
        }
        if (phone && r.peer != null) {
            Cfg.setTablet(c, r.peer.getHostAddress(), r.token);
            Eco.touch(c, r.peer.isSiteLocalAddress());
        }
        switch (r.path) {
            case "/s":
                if (phone) {
                    Http.json(out, 200, Status.json(c));
                } else {
                    Http.err(out, 404, "not a phone");
                }
                break;
            case "/act":
                if (phone) {
                    act(c, r, out);
                } else {
                    Http.err(out, 404, "not a phone");
                }
                break;
            case "/probe":
                if (phone) {
                    probe(out);
                } else {
                    Http.err(out, 404, "not a phone");
                }
                break;
            case "/speed/start":
            case "/speed/stop":
            case "/speed/status":
                if (phone) {
                    speed(c, r, out);
                } else {
                    Http.err(out, 404, "not a phone");
                }
                break;
            default:
                Http.err(out, 404, "unknown request");
                break;
        }
    }

    private static void act(Context c, Http.Req r, OutputStream out) throws IOException {
        String name = r.q("name");
        if (!"POST".equals(r.method) || name.isEmpty()) {
            Http.err(out, 400, "bad request");
            return;
        }
        if (Actions.isDelayed(name)) {
            if (!Priv.ready()) {
                Http.err(out, 409, Priv.stateText(Priv.state()));
                return;
            }
            Http.json(out, 200, "{\"ok\":true,\"out\":\"Sent. The phone will do it in a moment.\"}");
            Actions.later(name);
            return;
        }
        try {
            String res = Actions.run(c, name);
            Http.json(out, 200, "{\"ok\":true,\"out\":" + JSONObject.quote(res) + "}");
        } catch (IOException e) {
            Http.err(out, 409, String.valueOf(e.getMessage()));
        }
    }

    private static void speed(Context c, Http.Req r, OutputStream out) throws IOException {
        switch (r.path) {
            case "/speed/start":
                SpeedTest.start(c);
                Http.json(out, 200, "{\"ok\":true}");
                break;
            case "/speed/stop":
                SpeedTest.stop();
                Http.json(out, 200, "{\"ok\":true}");
                break;
            default:
                Http.json(out, 200, SpeedTest.json((int) r.qLong("from", 0)));
                break;
        }
    }

    private static void probe(OutputStream out) throws IOException {
        try {
            Http.text(out, 200, Actions.probe());
        } catch (IOException e) {
            Http.err(out, 409, String.valueOf(e.getMessage()));
        }
    }

    /** The phone trusts a tablet only while its Pair button's two-minute window is open. */
    private static void pair(Context c, Http.Req r, OutputStream out, boolean phone)
            throws IOException {
        if (!phone) {
            Http.err(out, 404, "not a phone");
            return;
        }
        boolean open = SystemClock.elapsedRealtime() < SenderService.pairUntil;
        if (!open || r.token.length() != 32) {
            Http.err(out, 403, "pairing is closed");
            return;
        }
        Cfg.addTrusted(c, r.token);
        if (r.peer != null) {
            Cfg.setTablet(c, r.peer.getHostAddress(), r.token);
        }
        SenderService.pairUntil = 0;
        Http.json(out, 200, "{\"ok\":1}");
    }
}
