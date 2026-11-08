package com.tj.linkmeter;

import android.content.Context;
import android.os.SystemClock;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

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
        }
        switch (r.path) {
            case "/s":
                if (phone) {
                    Http.json(out, 200, Status.json(c));
                } else {
                    Http.err(out, 404, "not a phone");
                }
                break;
            default:
                Http.err(out, 404, "unknown request");
                break;
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
