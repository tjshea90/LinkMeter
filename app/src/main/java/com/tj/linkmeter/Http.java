package com.tj.linkmeter;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A very small HTTP/1.0 reader and writer over plain sockets. Plain sockets are
 * used on purpose: no cleartext-traffic rules apply, and there is no library.
 */
final class Http {
    private Http() {}

    /** The port both devices talk on. */
    static final int PORT = 8765;
    /** {@link Req#via} of a request that came over the Bluetooth link. */
    static final String VIA_BT = "bt";

    static final class Req {
        String method = "";
        String path = "";
        /** The request target exactly as it was sent (path and query), which is what a Bluetooth request's signature covers. */
        String target = "";
        String token = "";
        long length;
        /** The first byte asked for by "Range: bytes=N-", or -1 when the whole file is wanted. */
        long rangeStart = -1;
        InetAddress peer;
        /** How the request arrived: "" over the network, {@link #VIA_BT} over the Bluetooth link (see LinkServer). */
        String via = "";
        /** Bluetooth link only: the request's number and its signature (see LinkAuth). -1 and "" when absent. */
        long seq = -1;
        String sig = "";
        /** The connection, for handlers that stream or need a different timeout. null on the Bluetooth link. */
        Socket sock;
        /** Positioned at the first byte of the body. */
        InputStream body;
        final Map<String, String> query = new HashMap<>();

        String q(String key) {
            String v = query.get(key);
            return v == null ? "" : v;
        }

        long qLong(String key, long def) {
            try {
                return Long.parseLong(q(key));
            } catch (NumberFormatException e) {
                return def;
            }
        }
    }

    /** Reads the request line and headers. Leaves the body unread. */
    static Req read(Socket s) throws IOException {
        s.setSoTimeout(5000);
        Req r = readHead(new BufferedInputStream(s.getInputStream(), 4096));
        r.peer = s.getInetAddress();
        r.sock = s;
        return r;
    }

    /**
     * Reads one request line and its headers from a stream that may carry several requests one after another (the Bluetooth
     * link): exactly the bytes of this request's head are taken, nothing more. The body, if any, is left unread in the stream.
     */
    static Req readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int st = 0; // how much of "\r\n\r\n" has been seen
        while (true) {
            if (head.size() > 8192) {
                throw new IOException("header too big");
            }
            int x = in.read();
            if (x < 0) {
                throw new IOException("closed");
            }
            head.write(x);
            if (x == '\r' && (st == 0 || st == 2)) {
                st++;
            } else if (x == '\n' && (st == 1 || st == 3)) {
                st++;
            } else {
                st = (x == '\r') ? 1 : 0;
            }
            if (st == 4) {
                break;
            }
        }
        String text = head.toString("ISO-8859-1");
        String[] lines = text.split("\r\n");
        // a head of nothing but line ends splits into NO lines at all: that must be an ordinary "bad request", not a crash
        String[] first = lines.length == 0 ? new String[0] : lines[0].split(" ");
        if (first.length < 2) {
            throw new IOException("bad request line");
        }
        Req r = new Req();
        r.method = first[0];
        String target = first[1];
        r.target = target;
        int qm = target.indexOf('?');
        if (qm >= 0) {
            r.path = target.substring(0, qm);
            parseQuery(target.substring(qm + 1), r.query);
        } else {
            r.path = target;
        }
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c <= 0) {
                continue;
            }
            String name = lines[i].substring(0, c).trim().toLowerCase(Locale.US);
            String val = lines[i].substring(c + 1).trim();
            if (name.equals("x-token")) {
                r.token = val;
            } else if (name.equals("content-length")) {
                try {
                    r.length = Long.parseLong(val);
                } catch (NumberFormatException ignored) {
                    r.length = 0;
                }
            } else if (name.equals("x-seq")) {
                try {
                    r.seq = Long.parseLong(val);
                } catch (NumberFormatException ignored) {
                    r.seq = -1;
                }
            } else if (name.equals("x-sig")) {
                r.sig = val;
            } else if (name.equals("range") && val.toLowerCase(Locale.US).startsWith("bytes=")) {
                String from = val.substring(6).split("-")[0].trim();
                try {
                    r.rangeStart = Long.parseLong(from);
                } catch (NumberFormatException ignored) {
                    r.rangeStart = -1;
                }
            }
        }
        r.body = in;
        return r;
    }

    private static void parseQuery(String raw, Map<String, String> out) {
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                out.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (UnsupportedEncodingException | IllegalArgumentException ignored) {
                // skip a malformed pair
            }
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    // ------------------------------------------------------------ responses

    private static String reason(int code) {
        switch (code) {
            case 200:
                return "OK";
            case 206:
                return "Partial Content";
            case 400:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 409:
                return "Conflict";
            case 503:
                return "Service Unavailable";
            default:
                return "Error";
        }
    }

    static void head(OutputStream out, int code, String type, long len) throws IOException {
        String h = "HTTP/1.0 " + code + " " + reason(code) + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + len + "\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII));
    }

    /** Like {@link #head} with one extra header line, for example "X-Size: 1080x2340". */
    static void headWith(OutputStream out, int code, String type, long len, String extra)
            throws IOException {
        String h = "HTTP/1.0 " + code + " " + reason(code) + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + len + "\r\n" + extra + "\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII));
    }

    /** Starts a reply whose length is not known: the body runs until the connection closes. */
    static void headOpen(OutputStream out, String type, String extra) throws IOException {
        String h = "HTTP/1.0 200 OK\r\nContent-Type: " + type + "\r\n" + extra
                + "Connection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII));
    }

    static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        head(out, code, type, body.length);
        out.write(body);
        out.flush();
    }

    static void json(OutputStream out, int code, String json) throws IOException {
        send(out, code, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    static void text(OutputStream out, int code, String text) throws IOException {
        send(out, code, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    static void err(OutputStream out, int code, String msg) throws IOException {
        json(out, code, "{\"err\":" + Json.quote(msg) + "}");
    }

    /** The head of a reply to a ranged request: the bytes from start to the end of a file of total bytes. */
    static void headPartial(OutputStream out, String type, long start, long total) throws IOException {
        long len = total - start;
        String h = "HTTP/1.0 206 Partial Content\r\nContent-Type: " + type + "\r\nContent-Length: " + len
                + "\r\nContent-Range: bytes " + start + "-" + (total - 1) + "/" + total + "\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII));
    }
}
