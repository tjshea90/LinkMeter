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

    /** Called as bytes are sent. It may throw (a cancel): the transfer stops and the exception comes out of {@link #put}. */
    interface Progress {
        void onBytes(long done) throws IOException;
    }

    /**
     * The other device could not be reached at all: no connection was made, so nothing was sent and nothing can have happened
     * there. (Any other IOException may mean the request arrived and only the answer was lost.)
     */
    static final class ConnectFailed extends IOException {
        ConnectFailed(IOException cause) {
            super(cause.getMessage(), cause);
        }
    }

    /** For tests only: talk to this port instead of {@link Http#PORT}. */
    static volatile int portOverride = 0;

    static final class Resp {
        int code;
        String body = "";
    }

    /** An open reply whose body has not been read yet. */
    static final class Stream implements Closeable {
        Socket sock;
        int code;
        long length = -1;
        /** The X-Size header, if the other device sent one. */
        String size = "";
        /** The X-Video header (size of the encoded video), if any. */
        String video = "";
        InputStream in;

        @Override
        public void close() {
            try {
                if (sock != null) {
                    sock.close();
                }
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    private static Socket connect(String host, int connectMs, int readMs) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, portOverride > 0 ? portOverride : Http.PORT), connectMs);
        } catch (IOException | RuntimeException e) {
            closeQuietly(s);
            // an address that is not one (a typing mistake in Settings) is the same to the caller as a phone that is not there
            throw new ConnectFailed(e instanceof IOException ? (IOException) e : new IOException(String.valueOf(e.getMessage())));
        }
        try {
            s.setSoTimeout(readMs);
            return s;
        } catch (IOException e) {
            closeQuietly(s);
            throw e;
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // nothing to clean up
        }
    }

    /**
     * Reads one whole reply from a stream that stays open for more (the Bluetooth link): the head, then exactly as many bytes as
     * its Content-Length says. A reply without a length cannot be told from the next one, so it is refused.
     */
    static Resp readFramed(InputStream in, int cap) throws IOException {
        Stream st = new Stream();
        st.in = in;
        readHead(st);
        if (st.length < 0 || st.length > cap) {
            throw new IOException(st.length < 0 ? "reply without a length" : "reply too big");
        }
        byte[] b = new byte[(int) st.length];
        int off = 0;
        while (off < b.length) {
            int r = in.read(b, off, b.length - off);
            if (r < 0) {
                throw new IOException("closed");
            }
            off += r;
        }
        Resp resp = new Resp();
        resp.code = st.code;
        resp.body = new String(b, StandardCharsets.UTF_8);
        return resp;
    }

    private static void writeHead(OutputStream out, String method, String target, String token,
            long bodyLen) throws IOException {
        writeHead(out, method, target, token, bodyLen, -1);
    }

    /** rangeStart >= 0 adds "Range: bytes=N-" (only the rest of a file is wanted). */
    private static void writeHead(OutputStream out, String method, String target, String token,
            long bodyLen, long rangeStart) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append(method).append(' ').append(target).append(" HTTP/1.0\r\n");
        if (rangeStart >= 0) {
            h.append("Range: bytes=").append(rangeStart).append("-\r\n");
        }
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
                } else if (l.toLowerCase(Locale.US).startsWith("x-video:")) {
                    st.video = l.substring(8).trim();
                } else if (l.toLowerCase(Locale.US).startsWith("x-size:")) {
                    st.size = l.substring(7).trim();
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
        return get(host, target, token, connectMs, readMs, -1);
    }

    /** Like {@link #get(String, String, String, int, int)} but from byte offset on (the reply is then 206). */
    static Stream get(String host, String target, String token, int connectMs, int readMs, long offset)
            throws IOException {
        Socket s = connect(host, connectMs, readMs);
        Stream st = new Stream();
        st.sock = s;
        try {
            OutputStream out = s.getOutputStream();
            writeHead(out, "GET", target, token, -1, offset > 0 ? offset : -1);
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
