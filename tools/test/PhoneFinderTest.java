package com.tj.linkmeter;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline test of PhoneFinder (finding the phone on the tablet's hotspot) over the loopback network: the whole 127.0.0.x range answers
 * on this machine, so "the hotspot's subnet" is a list of 127.0.0.x addresses and the phone is a fake server on one of them. The real
 * Client and Http classes are used. Run by tools/test.sh. Prints "N checks, M failed". ASCII only.
 */
public final class PhoneFinderTest {
    static int checks;
    static int failed;

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            failed++;
            if (failed <= 25) {
                System.out.println("  FAIL: " + msg);
            }
        }
    }

    static final String TOKEN = "0123456789abcdef0123456789abcdef";

    /** A fake phone. mode: "phone" answers GET /boot properly, "blackhole" accepts and never answers, "other" answers 200 with something else. */
    static final class Fake implements AutoCloseable {
        final ServerSocket ss;
        final String mode;
        volatile boolean open = true;
        final List<Socket> held = new ArrayList<>();

        Fake(String host, int port, String mode) throws IOException {
            this.mode = mode;
            ss = new ServerSocket(port, 20, InetAddress.getByName(host));
            Thread t = new Thread(() -> {
                while (open) {
                    try {
                        final Socket s = ss.accept();
                        Thread h = new Thread(() -> serve(s));
                        h.setDaemon(true);
                        h.start();
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        void serve(Socket s) {
            try {
                if (mode.equals("blackhole")) {
                    synchronized (held) {
                        held.add(s);
                    }
                    return; // never answers, never closes
                }
                Http.Req r = Http.read(s);
                OutputStream out = s.getOutputStream();
                if (mode.equals("other")) {
                    Http.json(out, 200, "{\"hello\":\"this is some other service\"}");
                } else if (!TOKEN.equals(r.token)) {
                    Http.err(out, 401, "not paired");
                } else if (!r.path.equals("/boot")) {
                    Http.err(out, 404, "unknown request");
                } else {
                    Http.json(out, 200, "{\"priv\":2,\"wifi\":true,\"wadb\":false,\"write\":true}");
                }
                out.flush();
            } catch (IOException ignored) {
                // the caller went away
            } finally {
                if (!mode.equals("blackhole")) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                        // closed
                    }
                }
            }
        }

        @Override
        public void close() throws IOException {
            open = false;
            ss.close();
            synchronized (held) {
                for (Socket s : held) {
                    s.close();
                }
            }
        }
    }

    static List<String> hosts(int from, int to) {
        List<String> l = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            l.add("127.0.0." + i);
        }
        return l;
    }

    /** A port nobody listens on. Every group of fake phones gets its own: a listening socket that was just closed can still hold its port for a moment. */
    static int freshPort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            int p = probe.getLocalPort();
            Client.portOverride = p;
            return p;
        }
    }

    public static void main(String[] args) throws Exception {
        int port = freshPort();

        // 1. one phone among many addresses
        try (Fake phone = new Fake("127.0.0.77", port, "phone")) {
            long t0 = System.currentTimeMillis();
            PhoneFinder.Found f = PhoneFinder.find(hosts(2, 120), TOKEN, 400, 1500, 32);
            long took = System.currentTimeMillis() - t0;
            check(f != null && f.host.equals("127.0.0.77"), "finds the phone: " + (f == null ? "null" : f.host));
            check(f != null && f.body.contains("\"priv\""), "and keeps what it said");
            check(took < 3000, "quickly: " + took + " ms");

            // 2. the wrong secret: the phone refuses (401), so it is not found
            PhoneFinder.Found g = PhoneFinder.find(hosts(70, 80), "ffffffffffffffffffffffffffffffff", 400, 1500, 8);
            check(g == null, "a tablet the phone does not know does not find it");
            check(PhoneFinder.ask("127.0.0.77", TOKEN, 400, 1500) != null, "ask() on the right address and secret works");
            check(PhoneFinder.ask("127.0.0.78", TOKEN, 400, 1500) == null, "ask() where nothing listens gives null");

            // 3. the phone is not in the list
            check(PhoneFinder.find(hosts(2, 60), TOKEN, 400, 1500, 16) == null, "not in the list: not found");
            check(PhoneFinder.find(new ArrayList<String>(), TOKEN, 400, 1500, 16) == null, "an empty list gives null");
        }

        // 4. a service that answers 200 with something else, and one that never answers, do not hide the phone
        port = freshPort();
        try (Fake phone = new Fake("127.0.0.60", port, "phone");
                Fake other = new Fake("127.0.0.61", port, "other");
                Fake hole = new Fake("127.0.0.62", port, "blackhole")) {
            long t0 = System.currentTimeMillis();
            PhoneFinder.Found f = PhoneFinder.find(hosts(55, 65), TOKEN, 400, 1200, 16);
            long took = System.currentTimeMillis() - t0;
            check(f != null && f.host.equals("127.0.0.60"), "finds the phone beside an other service and a black hole: " + (f == null ? "null" : f.host));
            check(took < 3000, "and does not wait for the black hole: " + took + " ms");
            check(PhoneFinder.ask("127.0.0.61", TOKEN, 400, 1200) == null, "another service that says 200 is not a phone");
            t0 = System.currentTimeMillis();
            check(PhoneFinder.ask("127.0.0.62", TOKEN, 400, 600) == null, "a black hole gives null");
            took = System.currentTimeMillis() - t0;
            check(took < 2500, "after its read timeout, not never: " + took + " ms");
        }
        port = freshPort();
        try (Fake other = new Fake("127.0.0.61", port, "other");
                Fake hole = new Fake("127.0.0.62", port, "blackhole")) {
            long t0 = System.currentTimeMillis();
            check(PhoneFinder.find(hosts(55, 65), TOKEN, 400, 700, 16) == null, "no phone among the others: null");
            check(System.currentTimeMillis() - t0 < 4000, "within a few seconds");
        }

        // 5. nothing answers at all: fast refusals on the loopback network
        long t0 = System.currentTimeMillis();
        check(PhoneFinder.find(hosts(2, 254), TOKEN, 300, 800, 48) == null, "a whole empty subnet: null");
        check(System.currentTimeMillis() - t0 < 5000, "in a few seconds: " + (System.currentTimeMillis() - t0) + " ms");

        // 6. the threads do not pile up
        int before = Thread.activeCount();
        for (int i = 0; i < 20; i++) {
            PhoneFinder.find(hosts(2, 30), TOKEN, 200, 300, 16);
        }
        Thread.sleep(300);
        check(Thread.activeCount() <= before + 4, "no threads left behind: " + before + " before, " + Thread.activeCount() + " after");

        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
