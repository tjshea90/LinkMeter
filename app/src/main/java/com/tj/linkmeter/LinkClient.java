package com.tj.linkmeter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * No Android in here (tested in tools/test/LinkProtocolTest.java). The tablet's side of one open Bluetooth connection to the phone
 * (see {@link LinkServer}): reads the phone's greeting, then sends signed requests one after another and reads each reply whole.
 * The tablet's secret is only ever the key of the signature; it is never written to the connection.
 */
final class LinkClient {
    private final InputStream in;
    private final OutputStream out;
    private final String secret;
    private final String nonce;
    private long seq;

    /** Reads the greeting. Throws if the other side is not LinkMeter's phone service. */
    LinkClient(InputStream rawIn, OutputStream rawOut, String secret) throws IOException {
        this.in = new BufferedInputStream(rawIn, 4096);
        this.out = new BufferedOutputStream(rawOut, 1024);
        this.secret = secret;
        StringBuilder line = new StringBuilder();
        while (true) {
            int x = in.read();
            if (x < 0) {
                throw new IOException("closed");
            }
            if (x == '\n') {
                break;
            }
            if (line.length() > 64) {
                throw new IOException("the other device is not LinkMeter on the phone");
            }
            line.append((char) x);
        }
        nonce = LinkAuth.parseGreeting(line.toString());
        if (nonce == null) {
            throw new IOException("the other device is not LinkMeter on the phone");
        }
    }

    /** One request and its whole reply. Only requests without a body (the link carries nothing else). */
    synchronized Client.Resp call(String method, String target) throws IOException {
        long n = ++seq;
        StringBuilder h = new StringBuilder();
        h.append(method).append(' ').append(target).append(" HTTP/1.0\r\n");
        h.append("X-Seq: ").append(n).append("\r\n");
        h.append("X-Sig: ").append(LinkAuth.sign(secret, nonce, n, method, target)).append("\r\n");
        if ("POST".equals(method)) {
            h.append("Content-Length: 0\r\n");
        }
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return Client.readFramed(in, 2 * 1024 * 1024);
    }
}
