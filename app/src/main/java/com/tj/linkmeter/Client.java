package com.tj.linkmeter;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** The other half of {@link Http}: asks the other device a question. Blocking; call it off the UI thread. */
final class Client {
    private Client() {}

    interface Progress {
        void onBytes(long done);
    }

    static final class Resp {
        int code;
        String body = "";
    }

    /** An open reply whose body has not been read yet. */
    static final class Stream implements Closeable {
        Socket sock;
        int code;
        long length = -1;
        InputStream in;

        @Override
        public void close() {
            try {
                sock.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    private static Socket connect(String host, int connectMs, int readMs) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, Cfg.PORT), connectMs);
            s.setSoTimeout(readMs);
            return s;
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
                // nothing to clean up
            }
            throw e;
        }
    }

    private static void writeHead(OutputStream out, String method, String target, String token,
            long bodyLen) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append(method).append(' ').append(target).append(" HTTP/1.0\r\n");
        if (!token.isEmpty()) {
            h.append("X-Token: ").append(token).append("\r\n");
        }
        if (bodyLen >= 0) {
            h.append("Content-Length: ").append(bodyLen).append("\r\n");
        }
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static void readHead(Stream st) throws IOException {
        StringBuilder line = new StringBuilder();
        boolean first = true;
        while (true) {
            int x = st.in.read();
            if (x < 0) {
                throw new IOException("closed");
            }
            if (x == '\n') {
                String l = line.toString().trim();
                line.setLength(0);
                if (first) {
                    String[] p = l.split(" ");
                    if (p.length < 2) {
                        throw new IOException("bad reply");
                    }
                    try {
                        st.code = Integer.parseInt(p[1]);
                    } catch (NumberFormatException e) {
                        throw new IOException("bad reply");
                    }
                    first = false;
                } else if (l.isEmpty()) {
                    return;
                } else if (l.toLowerCase(Locale.US).startsWith("content-length:")) {
                    try {
                        st.length = Long.parseLong(l.substring(15).trim());
                    } catch (NumberFormatException ignored) {
                        st.length = -1;
                    }
                }
            } else if (line.length() < 2048) {
                line.append((char) x);
            }
        }
    }

    private static String readAll(Stream st, int cap) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int r;
        while ((r = st.in.read(buf)) > 0) {
            bo.write(buf, 0, r);
            if (bo.size() > cap) {
                throw new IOException("reply too big");
            }
        }
        return bo.toString("UTF-8");
    }

    /** A small request with a small text reply. */
    static Resp call(String host, String method, String target, String token,
            int connectMs, int readMs) throws IOException {
        Socket s = connect(host, connectMs, readMs);
        try (Stream st = new Stream()) {
            st.sock = s;
            OutputStream out = s.getOutputStream();
            writeHead(out, method, target, token, "POST".equals(method) ? 0 : -1);
            out.flush();
            st.in = s.getInputStream();
            readHead(st);
            Resp r = new Resp();
            r.code = st.code;
            r.body = readAll(st, 2 * 1024 * 1024);
            return r;
        }
    }

    /** Starts a GET whose body the caller reads from {@link Stream#in}. Close it when done. */
    static Stream get(String host, String target, String token, int connectMs, int readMs)
            throws IOException {
        Socket s = connect(host, connectMs, readMs);
        Stream st = new Stream();
        st.sock = s;
        try {
            OutputStream out = s.getOutputStream();
            writeHead(out, "GET", target, token, -1);
            out.flush();
            st.in = s.getInputStream();
            readHead(st);
            return st;
        } catch (IOException e) {
            st.close();
            throw e;
        }
    }

    /** Sends len bytes from src as the request body and returns the reply. */
    static Resp put(String host, String target, String token, InputStream src, long len,
            Progress progress) throws IOException {
        Socket s = connect(host, 3000, 30000);
        try (Stream st = new Stream()) {
            st.sock = s;
            OutputStream out = s.getOutputStream();
            writeHead(out, "POST", target, token, len);
            byte[] buf = new byte[16 * 1024];
            long done = 0;
            int r;
            while (done < len && (r = src.read(buf, 0, (int) Math.min(buf.length, len - done))) > 0) {
                out.write(buf, 0, r);
                done += r;
                if (progress != null) {
                    progress.onBytes(done);
                }
            }
            out.flush();
            st.in = s.getInputStream();
            readHead(st);
            Resp resp = new Resp();
            resp.code = st.code;
            resp.body = readAll(st, 64 * 1024);
            return resp;
        }
    }
}
