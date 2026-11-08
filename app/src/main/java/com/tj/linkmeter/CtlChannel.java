package com.tj.linkmeter;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Tablet side: one long connection to the phone that carries touch events as they happen. */
final class CtlChannel {
    private final Client.Stream st;
    private final OutputStream out;
    private final ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean dead;

    private CtlChannel(Client.Stream s) throws IOException {
        st = s;
        out = s.sock.getOutputStream();
        // A short line now and then: the phone ignores it, but it tells the phone this tablet is still
        // there (the phone drops a silent connection after a few minutes) and shows us a dead link early.
        ex.scheduleAtFixedRate(() -> write("p"), 60, 60, TimeUnit.SECONDS);
    }

    /** Connects. Throws if the phone's touch helper can't start (the caller then uses slower taps). */
    static CtlChannel open(String host, String token) throws IOException {
        Client.Stream s = Client.get(host, "/screen/ctl", token, 3000, 20000);
        if (s.code != 200) {
            StringBuilder sb = new StringBuilder();
            try {
                byte[] b = new byte[512];
                int n = s.in.read(b);
                if (n > 0) {
                    sb.append(new String(b, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // no message
            }
            s.close();
            String text = sb.toString();
            try {
                text = new org.json.JSONObject(text).optString("err", text); // the phone's reason, without the JSON
            } catch (org.json.JSONException plain) {
                // not JSON: show it as it came
            }
            throw new IOException(text.isEmpty() ? "error " + s.code : text);
        }
        try {
            s.sock.setSoTimeout(0);
        } catch (java.net.SocketException ignored) {
            // keep the old timeout
        }
        return new CtlChannel(s);
    }

    boolean isDead() {
        return dead;
    }

    private void write(String line) {
        try {
            out.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (IOException e) {
            dead = true;
        }
    }

    /** Queues one line ("d x y", "m x y", "u x y" or "k code"). Never blocks the caller. */
    void send(final String line) {
        if (dead) {
            return;
        }
        ex.execute(() -> write(line));
    }

    void close() {
        dead = true;
        ex.shutdownNow();
        st.close();
    }
}
