package com.tj.linkmeter;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/**
 * Fast Wi-Fi end to end, for real: the tablet's engine (TunStack) on a REAL Linux tun device made by tools/test/fast_tun_bridge.py,
 * with the Linux kernel's own TCP/UDP as the "apps" (Java sockets here that talk to 198.18.0.1, routed into the tun), the phone's
 * relay (FastRelayCore) on the loopback address, and test servers standing in for the internet. Checks content byte for byte
 * (CRC32), speed, many connections at once, half-close, refused and reset connections, a server that speaks first, UDP, DNS through
 * the phone's resolver, QUIC refused at once, DNS-over-TLS refused at once, and that nothing is left open afterwards.
 * Run by tools/fast_e2e.sh (needs root and /dev/net/tun). Not part of the app.
 */
public final class FastE2E {
    private static int checks;
    private static int fails;
    private static final int RELAY = 18767;
    private static final int BLOB = 18080;
    private static final int BANNER = 18081;
    private static final int UDP_ECHO = 18090;
    private static final int NOBODY = 18099;
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final InetAddress NET = addr("198.18.0.1");

    private FastE2E() {}

    private static InetAddress addr(String s) {
        try {
            return InetAddress.getByName(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void yes(boolean ok, String what) {
        checks++;
        if (!ok) {
            fails++;
            System.out.println("FAIL " + what);
        } else {
            System.out.println("ok   " + what);
        }
    }

    public static void main(String[] args) throws Exception {
        startServers();
        FastRelayCore.Dialer toLoopback = new FastRelayCore.Dialer() {
            @Override
            public InetSocketAddress target(int a, int p) {
                return new InetSocketAddress(InetAddress.getLoopbackAddress(), p);
            }

            @Override
            public int back(int real) {
                return real == Ip4.addr("127.0.0.1") ? Ip4.addr("198.18.0.1") : real;
            }
        };
        FastRelayCore relay = new FastRelayCore(RELAY, () -> Collections.singletonList(FastProto.key(SECRET)), FastE2E::fakeDns,
                toLoopback, on -> System.out.println("     relay busy=" + on));
        relay.loopbackOk = true;
        relay.start();

        ServerSocket bridgeDoor = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Process bridge = new ProcessBuilder("python3", "tools/test/fast_tun_bridge.py", String.valueOf(bridgeDoor.getLocalPort()))
                .inheritIO().start();
        bridgeDoor.setSoTimeout(15_000);
        Socket b = bridgeDoor.accept();
        b.setTcpNoDelay(true);
        DataInputStream bin = new DataInputStream(new java.io.BufferedInputStream(b.getInputStream(), 1 << 16));
        OutputStream bout = b.getOutputStream();
        TunStack.Tun tun = new TunStack.Tun() {
            @Override
            public int read(byte[] buf, int off, int len) throws IOException {
                int n;
                try {
                    n = bin.readUnsignedShort();
                } catch (java.io.EOFException e) {
                    return -1;
                }
                bin.readFully(buf, off, n);
                return n;
            }

            private final byte[] frame = new byte[65538];

            @Override
            public void write(byte[] buf, int off, int len) throws IOException {
                frame[0] = (byte) (len >>> 8);
                frame[1] = (byte) len;
                System.arraycopy(buf, off, frame, 2, len);
                bout.write(frame, 0, len + 2);
            }
        };
        final List<String> trouble = Collections.synchronizedList(new ArrayList<String>());
        TunStack engine = new TunStack(tun, new TunStack.Link() {
            @Override
            public InetSocketAddress relay() {
                return new InetSocketAddress(InetAddress.getLoopbackAddress(), RELAY);
            }

            @Override
            public SocketChannel tcp() throws IOException {
                return SocketChannel.open();
            }

            @Override
            public DatagramChannel udp() throws IOException {
                return DatagramChannel.open();
            }
        }, new TunStack.Events() {
            @Override
            public void relayTrouble(String why) {
                trouble.add(why);
            }

            @Override
            public void relayFine() {
                // fine
            }
        }, FastProto.key(SECRET));
        engine.start();
        Thread.sleep(300);

        try {
            small();
            bigDownload();
            bigUpload();
            parallel();
            halfClose();
            serverSpeaksFirst();
            refused();
            reset();
            udp();
            dns();
            quicRefused();
            dotRefused();
            Thread.sleep(1500);
            yes(engine.open == 0, "the engine holds no connection afterwards (" + engine.open + ")");
            yes(relay.pairs == 0, "the relay holds no connection afterwards (" + relay.pairs + ")");
            yes(trouble.isEmpty(), "no relay trouble was reported " + trouble);
            System.out.println("     engine: up " + engine.tcpUp + " down " + engine.tcpDown + " opened " + engine.opened
                    + " refused " + engine.refused + " dropped " + engine.dropped);
        } finally {
            engine.stop();
            relay.stop();
            b.close();
            bridge.destroy();
            bridge.waitFor(5, TimeUnit.SECONDS);
        }
        System.out.println(checks + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------------------------------- the "internet"

    private static void startServers() throws IOException {
        ServerSocket blob = new ServerSocket(BLOB, 128, InetAddress.getLoopbackAddress());
        daemon(() -> {
            while (true) {
                Socket s = blob.accept();
                daemon(() -> serveBlob(s));
            }
        });
        ServerSocket banner = new ServerSocket(BANNER, 16, InetAddress.getLoopbackAddress());
        daemon(() -> {
            while (true) {
                Socket s = banner.accept();
                daemon(() -> {
                    try (Socket x = s) {
                        x.getOutputStream().write("HELLO FROM THE SERVER\n".getBytes(StandardCharsets.US_ASCII));
                        byte[] buf = new byte[4096];
                        int n;
                        InputStream in = x.getInputStream();
                        while ((n = in.read(buf)) > 0) {
                            x.getOutputStream().write(buf, 0, n);
                        }
                    }
                    return null;
                });
            }
        });
        DatagramSocket echo = new DatagramSocket(UDP_ECHO, InetAddress.getLoopbackAddress());
        daemon(() -> {
            byte[] buf = new byte[65536];
            while (true) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                echo.receive(p);
                echo.send(new DatagramPacket(p.getData(), p.getLength(), p.getSocketAddress()));
            }
        });
    }

    interface Job {
        Object run() throws Exception;
    }

    private static void daemon(Job j) {
        Thread t = new Thread(() -> {
            try {
                j.run();
            } catch (Exception e) {
                // a server thread ends with its socket
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static Object serveBlob(Socket s) throws IOException {
        try (Socket x = s) {
            BufferedReader r = new BufferedReader(new InputStreamReader(x.getInputStream(), StandardCharsets.US_ASCII));
            String line = r.readLine();
            OutputStream out = x.getOutputStream();
            String[] w = line.split(" ");
            if (w[0].equals("GET")) {
                long n = Long.parseLong(w[1]);
                byte[] buf = new byte[64 * 1024];
                Blob g = new Blob(Long.parseLong(w[2]));
                while (n > 0) {
                    int k = (int) Math.min(buf.length, n);
                    g.fill(buf, k);
                    out.write(buf, 0, k);
                    n -= k;
                }
            } else if (w[0].equals("PUT")) {
                long n = Long.parseLong(w[1]);
                CRC32 crc = new CRC32();
                char[] cb = new char[64 * 1024];
                // the reader has buffered characters: ASCII-safe, as the client sends only bytes 0..127 for uploads
                while (n > 0) {
                    int k = r.read(cb, 0, (int) Math.min(cb.length, n));
                    if (k < 0) {
                        break;
                    }
                    for (int i = 0; i < k; i++) {
                        crc.update(cb[i]);
                    }
                    n -= k;
                }
                out.write(("OK " + crc.getValue() + "\n").getBytes(StandardCharsets.US_ASCII));
            } else if (w[0].equals("ECHO")) {
                long count = 0;
                char[] cb = new char[8192];
                int k;
                StringBuilder sb = new StringBuilder();
                while ((k = r.read(cb)) > 0) {
                    count += k;
                    sb.append(cb, 0, k);
                }
                // the client closed its side: answer after that, which only works if the half-close was passed on
                out.write(("BYE " + count + " " + sb.toString().hashCode() + "\n").getBytes(StandardCharsets.US_ASCII));
            } else if (w[0].equals("RESET")) {
                byte[] buf = new byte[100_000];
                out.write(buf);
                out.flush();
                x.setSoLinger(true, 0); // close with a reset
            }
        }
        return null;
    }

    /** A fast repeatable stream of bytes (xorshift), so both ends can check content without storing it. */
    static final class Blob {
        private long s;

        Blob(long seed) {
            s = seed * 0x9E3779B97F4A7C15L + 1;
        }

        void fill(byte[] b, int n) {
            for (int i = 0; i < n; i += 8) {
                s ^= s << 13;
                s ^= s >>> 7;
                s ^= s << 17;
                long v = s;
                for (int j = 0; j < 8 && i + j < n; j++) {
                    b[i + j] = (byte) v;
                    v >>>= 8;
                }
            }
        }
    }

    private static long crcOf(long seed, long n) {
        CRC32 crc = new CRC32();
        byte[] buf = new byte[64 * 1024];
        Blob g = new Blob(seed);
        while (n > 0) {
            int k = (int) Math.min(buf.length, n);
            g.fill(buf, k);
            crc.update(buf, 0, k);
            n -= k;
        }
        return crc.getValue();
    }

    // ------------------------------------------------------------------------------------------- the "apps"

    private static Socket connect(int port) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(NET, port), 10_000);
        s.setSoTimeout(30_000);
        return s;
    }

    /** GET n bytes with seed through the tun; returns {crc, millis}. */
    private static long[] get(long n, long seed) throws IOException {
        long t0 = System.nanoTime();
        try (Socket s = connect(BLOB)) {
            s.getOutputStream().write(("GET " + n + " " + seed + "\n").getBytes(StandardCharsets.US_ASCII));
            InputStream in = s.getInputStream();
            CRC32 crc = new CRC32();
            byte[] buf = new byte[64 * 1024];
            long got = 0;
            int k;
            while ((k = in.read(buf)) > 0) {
                crc.update(buf, 0, k);
                got += k;
            }
            if (got != n) {
                return new long[] {-1, 0, got};
            }
            return new long[] {crc.getValue(), (System.nanoTime() - t0) / 1_000_000, got};
        }
    }

    private static void small() throws IOException {
        long[] r = get(1000, 1);
        yes(r[0] == crcOf(1, 1000), "a small download arrives whole (" + r[2] + " bytes)");
    }

    private static void bigDownload() throws IOException {
        long n = 200L * 1024 * 1024;
        long[] r = get(n, 2);
        yes(r[0] == crcOf(2, n), "200 MB download, every byte right");
        System.out.println("     download speed through the engine: " + (n / 1024 / 1024 * 8 * 1000 / Math.max(1, r[1])) + " Mbit/s");
    }

    private static void bigUpload() throws IOException {
        long n = 100L * 1024 * 1024;
        long t0 = System.nanoTime();
        try (Socket s = connect(BLOB)) {
            OutputStream out = s.getOutputStream();
            out.write(("PUT " + n + "\n").getBytes(StandardCharsets.US_ASCII));
            byte[] buf = new byte[64 * 1024];
            CRC32 crc = new CRC32();
            Blob g = new Blob(3);
            long left = n;
            while (left > 0) {
                int k = (int) Math.min(buf.length, left);
                g.fill(buf, k);
                for (int i = 0; i < k; i++) {
                    buf[i] &= 0x7F; // ASCII only, so the server's reader counts bytes as characters
                }
                crc.update(buf, 0, k);
                out.write(buf, 0, k);
                left -= k;
            }
            String reply = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            yes(("OK " + crc.getValue()).equals(reply), "100 MB upload, every byte right");
            System.out.println("     upload speed through the engine: " + (n / 1024 / 1024 * 8 * 1000 / Math.max(1, ms)) + " Mbit/s");
        }
    }

    private static void parallel() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(48);
        List<Future<long[]>> fs = new ArrayList<>();
        for (int i = 0; i < 96; i++) {
            final long seed = 100 + i;
            final long n = 300_000 + i * 7919L;
            fs.add(pool.submit(() -> {
                long[] r = get(n, seed);
                return new long[] {r[0] == crcOf(seed, n) ? 1 : 0};
            }));
        }
        int good = 0;
        for (Future<long[]> f : fs) {
            good += f.get(120, TimeUnit.SECONDS)[0];
        }
        pool.shutdown();
        yes(good == 96, "96 downloads at once (48 at a time), all whole: " + good);
    }

    private static void halfClose() throws IOException {
        try (Socket s = connect(BLOB)) {
            StringBuilder sent = new StringBuilder();
            for (int i = 0; i < 2000; i++) {
                sent.append("line ").append(i).append('\n');
            }
            String body = sent.toString();
            s.getOutputStream().write(("ECHO\n" + body).getBytes(StandardCharsets.US_ASCII));
            s.shutdownOutput();
            String reply = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            yes(("BYE " + body.length() + " " + body.hashCode()).equals(reply), "half-close: the server answers after the app closed its side");
        }
    }

    private static void serverSpeaksFirst() throws IOException {
        try (Socket s = connect(BANNER)) {
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            yes("HELLO FROM THE SERVER".equals(r.readLine()), "a server that speaks first is heard");
            s.getOutputStream().write("ping\n".getBytes(StandardCharsets.US_ASCII));
            yes("ping".equals(r.readLine()), "and answered after that");
        }
    }

    private static void refused() throws IOException {
        long t0 = System.nanoTime();
        boolean refused = false;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(NET, NOBODY), 10_000);
        } catch (ConnectException e) {
            refused = true;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        yes(refused && ms < 3000, "a refused connection is refused at once, as on a normal network (" + ms + " ms)");
    }

    private static void reset() throws IOException {
        boolean wasReset = false;
        try (Socket s = connect(BLOB)) {
            s.getOutputStream().write("RESET\n".getBytes(StandardCharsets.US_ASCII));
            InputStream in = s.getInputStream();
            byte[] buf = new byte[8192];
            while (in.read(buf) > 0) {
                // read until the reset
            }
        } catch (SocketException e) {
            wasReset = String.valueOf(e.getMessage()).toLowerCase().contains("reset");
        }
        yes(wasReset, "a server's reset reaches the app as a reset");
    }

    private static void udp() throws IOException {
        try (DatagramSocket d = new DatagramSocket()) {
            d.setSoTimeout(5000);
            d.connect(NET, UDP_ECHO);
            int good = 0;
            for (int i = 0; i < 50; i++) {
                byte[] msg = ("datagram " + i + " " + "x".repeat(i * 20)).getBytes(StandardCharsets.US_ASCII);
                d.send(new DatagramPacket(msg, msg.length));
                DatagramPacket p = new DatagramPacket(new byte[65536], 65536);
                d.receive(p);
                if (new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII).equals(new String(msg, StandardCharsets.US_ASCII))) {
                    good++;
                }
            }
            yes(good == 50, "UDP goes there and back (" + good + " of 50)");
            byte[] big = new byte[8000];
            new Blob(9).fill(big, big.length);
            d.send(new DatagramPacket(big, big.length));
            DatagramPacket p = new DatagramPacket(new byte[65536], 65536);
            d.receive(p);
            yes(p.getLength() == 8000, "an 8000-byte datagram comes back whole");
        }
    }

    private static void dns() throws IOException {
        try (DatagramSocket d = new DatagramSocket()) {
            d.setSoTimeout(5000);
            byte[] q = query(0x4242, "example.com");
            d.send(new DatagramPacket(q, q.length, addr("10.111.222.53"), 53));
            DatagramPacket p = new DatagramPacket(new byte[2048], 2048);
            d.receive(p);
            byte[] a = p.getData();
            int n = p.getLength();
            yes(n > q.length && Ip4.u16(a, 0) == 0x4242 && Ip4.u16(a, 6) == 1, "DNS through the phone's resolver: one answer, the question's number");
            yes(Ip4.i32(a, n - 4) == Ip4.addr("198.18.0.1"), "the answer's address arrived");
            yes(p.getAddress().getHostAddress().equals("10.111.222.53") && p.getPort() == 53, "the answer comes from the VPN's DNS address");
        }
    }

    static byte[] query(int id, String name) {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        b.write(id >>> 8);
        b.write(id);
        b.write(1);
        b.write(0); // recursion desired
        b.write(0);
        b.write(1); // one question
        for (int i = 0; i < 6; i++) {
            b.write(0);
        }
        for (String label : name.split("\\.")) {
            b.write(label.length());
            b.write(label.getBytes(StandardCharsets.US_ASCII), 0, label.length());
        }
        b.write(0);
        b.write(0);
        b.write(1); // A
        b.write(0);
        b.write(1); // IN
        return b.toByteArray();
    }

    /** The phone's resolver, faked: every name is 198.18.0.1. */
    private static void fakeDns(byte[] q, FastRelayCore.Answer answer) {
        byte[] a = new byte[q.length + 16];
        System.arraycopy(q, 0, a, 0, q.length);
        a[2] = (byte) 0x81;
        a[3] = (byte) 0x80;
        a[6] = 0;
        a[7] = 1; // one answer
        int i = q.length;
        a[i++] = (byte) 0xC0;
        a[i++] = 12; // the name in the question
        a[i++] = 0;
        a[i++] = 1;
        a[i++] = 0;
        a[i++] = 1;
        a[i++] = 0;
        a[i++] = 0;
        a[i++] = 0;
        a[i++] = 60;
        a[i++] = 0;
        a[i++] = 4;
        Ip4.w32(a, i, Ip4.addr("198.18.0.1"));
        a[0] = 0; // a resolver may hand back another number: the relay must put the question's back
        a[1] = 7;
        new Thread(() -> answer.done(a)).start();
    }

    private static void quicRefused() throws IOException {
        try (DatagramSocket d = new DatagramSocket()) {
            d.setSoTimeout(3000);
            d.connect(NET, 443);
            byte[] msg = new byte[1200];
            long t0 = System.nanoTime();
            d.send(new DatagramPacket(msg, msg.length));
            boolean unreachable = false;
            try {
                d.receive(new DatagramPacket(new byte[2000], 2000));
            } catch (PortUnreachableException e) {
                unreachable = true;
            } catch (SocketTimeoutException e) {
                unreachable = false;
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            yes(unreachable && ms < 1000, "QUIC (UDP 443) is refused at once, so apps use TCP (" + ms + " ms)");
        }
    }

    private static void dotRefused() throws IOException {
        long t0 = System.nanoTime();
        boolean refused = false;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(addr("10.111.222.53"), 853), 5000);
        } catch (ConnectException e) {
            refused = true;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        yes(refused && ms < 1000, "DNS over TLS to the VPN's DNS address is refused at once (" + ms + " ms)");
    }
}
