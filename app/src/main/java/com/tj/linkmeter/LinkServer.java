package com.tj.linkmeter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * No Android in here (tested in tools/test/LinkProtocolTest.java). The phone's side of ONE Bluetooth connection: it greets with a
 * random number, then answers request after request on the same connection until the other side goes away. A request is served
 * only if it is signed with a secret the phone trusts (see {@link LinkAuth}), carries a number higher than every one before it
 * on this connection (so a recorded request cannot be played again), has no body, and is one of the small routes in
 * {@link RemoteRoutes}. Everything else gets an error and nothing reaches the handler.
 *
 * A Bluetooth socket has no read timeout, and anybody in range may connect, so the caller is told through {@link Watch} how long
 * the connection may now stay silent; it closes the connection when that runs out.
 */
final class LinkServer {
    private LinkServer() {}

    /** A caller that has not yet sent one good request gets this long. */
    static final long FIRST_MS = 15_000L;
    /** After a good request the connection may be silent this long (the tablet asks every few seconds while it is in use). */
    static final long IDLE_MS = 90_000L;
    /** After this many requests that were not signed properly the connection is closed. */
    static final int MAX_BAD = 3;

    /** The real work: {@link Api#handle} on the phone. It writes one whole reply. */
    interface Handler {
        void handle(Http.Req r, OutputStream out) throws IOException;
    }

    /** The secrets of the paired tablets, read fresh for every request (a tablet may be forgotten while a connection is open). */
    interface Trust {
        Iterable<String> secrets();
    }

    interface Watch {
        /** The connection is now waiting for a request and may be closed after this long without one. */
        void waiting(long ms);

        /** A request is being worked on (this can take a minute): do not close the connection for silence. */
        void working();
    }

    /** Serves the connection until it ends; returns how many requests reached the handler. Never throws for what the caller sends. */
    static int serve(InputStream rawIn, OutputStream rawOut, String nonce, Trust trust, Handler handler, Watch watch) {
        int served = 0;
        try {
            InputStream in = new BufferedInputStream(rawIn, 4096);
            OutputStream out = new BufferedOutputStream(rawOut, 8192);
            out.write(LinkAuth.greeting(nonce).getBytes(StandardCharsets.US_ASCII));
            out.flush();
            watch.waiting(FIRST_MS);
            long lastSeq = -1;
            int bad = 0;
            boolean proven = false;
            while (true) {
                Http.Req r;
                try {
                    r = Http.readHead(in); // throws when the other side closes, or sends something that is not a request
                } catch (RuntimeException e) {
                    return served; // bytes no parser expected: the same as garbage
                }
                watch.working();
                r.via = Http.VIA_BT;
                if (r.length != 0) {
                    // a body would have to be read to find the next request, and nothing with a body belongs here
                    Http.err(out, 400, "A request with a body cannot travel over Bluetooth.");
                    out.flush();
                    return served;
                }
                String secret = r.seq > lastSeq
                        ? LinkAuth.match(trust.secrets(), nonce, r.seq, r.method, r.target, r.sig) : null;
                if (secret == null) {
                    Http.err(out, 401, "not paired");
                    out.flush();
                    if (++bad >= MAX_BAD) {
                        return served;
                    }
                    watch.waiting(proven ? IDLE_MS : FIRST_MS);
                    continue;
                }
                lastSeq = r.seq;
                proven = true;
                r.token = secret;
                if (!RemoteRoutes.overBluetooth(r.method, r.path)) {
                    Http.err(out, 404, "That needs the phone's hotspot: it cannot be done over Bluetooth.");
                } else {
                    // the handler's reply is collected first, so a handler that fails half-way cannot leave half a reply on the line
                    ByteArrayOutputStream reply = new ByteArrayOutputStream(512);
                    try {
                        handler.handle(r, reply);
                        reply.writeTo(out);
                    } catch (IOException | RuntimeException e) {
                        Http.err(out, 500, String.valueOf(e.getMessage() == null ? e : e.getMessage()));
                    }
                    served++;
                }
                out.flush();
                watch.waiting(IDLE_MS);
            }
        } catch (IOException e) {
            return served; // the other side went away (or was closed for silence)
        }
    }
}
