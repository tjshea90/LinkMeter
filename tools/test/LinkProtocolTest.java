package com.tj.linkmeter;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Offline test of the Bluetooth link's protocol (LinkAuth, RemoteRoutes, LinkServer, LinkClient, Http.readHead, Client.readFramed).
 * A Bluetooth serial connection is just two byte streams, so here a localhost socket stands in for it: the real server code runs on
 * one end and the real client code, or an attacker writing raw bytes, on the other. What must hold: only a request signed with a
 * trusted secret reaches the handler, exactly once; a recorded request cannot be played again; the secret is never sent; only the
 * small routes are served; whatever the caller sends, the phone's side neither hangs nor throws.
 * Run by tools/test.sh. Prints "N checks, M failed". ASCII only.
 */
public final class LinkProtocolTest {
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

    static final String SECRET = "0123456789abcdef0123456789abcdef";
    static final String OTHER = "fedcba9876543210fedcba9876543210";
    static final String STRANGER = "11111111111111112222222222222222";

    /** What the fake phone's handler saw, and what the watch was told. */
    static final class Phone implements AutoCloseable {
        final ServerSocket ss;
        final List<String> seen = new CopyOnWriteArrayList<>();
        final List<String> watch = new CopyOnWriteArrayList<>();
        final List<String> trusted = new CopyOnWriteArrayList<>(Arrays.asList(SECRET, OTHER));
        final AtomicInteger served = new AtomicInteger(-1);
        final AtomicInteger connections = new AtomicInteger();
        final List<String> nonces = new CopyOnWriteArrayList<>();
        volatile Socket last;
        /** If set, the watch closes the connection this many milliseconds after it starts waiting (a silent caller). */
        volatile long closeAfterMs = -1;
        volatile boolean open = true;

        Phone() throws IOException {
            ss = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"));
            Thread t = new Thread(() -> {
                while (open) {
                    try {
                        final Socket s = ss.accept();
                        connections.incrementAndGet();
                        last = s;
                        Thread h = new Thread(() -> one(s));
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

        void one(final Socket s) {
            String nonce = LinkAuth.newNonce();
            nonces.add(nonce);
            try {
                int n = LinkServer.serve(s.getInputStream(), s.getOutputStream(), nonce, () -> trusted, this::handle,
                        new LinkServer.Watch() {
                            @Override
                            public void waiting(long ms) {
                                watch.add("waiting " + ms);
                                final long after = closeAfterMs;
                                if (after >= 0) {
                                    Thread k = new Thread(() -> {
                                        try {
                                            Thread.sleep(after);
                                            s.close();
                                        } catch (IOException | InterruptedException ignored) {
                                            // already closed
                                        }
                                    });
                                    k.setDaemon(true);
                                    k.start();
                                }
                            }

                            @Override
                            public void working() {
                                watch.add("working");
                            }
                        });
                served.set(n);
            } catch (IOException e) {
                served.set(-2);
            } catch (RuntimeException e) {
                served.set(-3); // serve() must never throw for what a caller sends
                seen.add("THREW " + e);
            } finally {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // closed
                }
            }
        }

        /** The stand-in for Api.handle. The target decides what it does. */
        void handle(Http.Req r, OutputStream out) throws IOException {
            seen.add(r.method + " " + r.target + " via=" + r.via + " token=" + r.token + " name=" + r.q("name"));
            String name = r.q("name");
            if (name.equals("boom")) {
                throw new IllegalStateException("handler broke");
            }
            if (name.equals("ioboom")) {
                throw new IOException("handler io broke");
            }
            if (name.equals("half")) {
                out.write("HTTP/1.0 200 OK\r\nContent-Type: application/json\r\nContent-Length: 500\r\n\r\nonly a little".getBytes(StandardCharsets.US_ASCII));
                throw new IllegalStateException("broke after half a reply");
            }
            if (name.startsWith("big")) {
                int n = Integer.parseInt(name.substring(3));
                StringBuilder sb = new StringBuilder(n);
                for (int i = 0; i < n; i++) {
                    sb.append((char) ('a' + i % 26));
                }
                Http.text(out, 200, sb.toString());
                return;
            }
            if (name.equals("utf")) {
                Http.json(out, 200, "{\"out\":\"grüße ✓ 你好\"}");
                return;
            }
            if (name.equals("conflict")) {
                Http.err(out, 409, "Shizuku is not running");
                return;
            }
            Http.json(out, 200, "{\"ok\":true,\"echo\":" + Json.quote(r.target) + "}");
        }

        int port() {
            return ss.getLocalPort();
        }

        /** Waits until the connection that was being served has ended; returns how many requests reached the handler. */
        int waitServed() throws InterruptedException {
            for (int i = 0; i < 400 && served.get() == -1; i++) {
                Thread.sleep(10);
            }
            return served.getAndSet(-1);
        }

        @Override
        public void close() throws IOException {
            open = false;
            ss.close();
        }
    }

    /** Remembers every byte the client writes, to prove what does and does not go over the air. */
    static final class Tap extends FilterOutputStream {
        final ByteArrayOutputStream copy = new ByteArrayOutputStream();

        Tap(OutputStream o) {
            super(o);
        }

        @Override
        public void write(int b) throws IOException {
            copy.write(b);
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            copy.write(b, off, len);
            out.write(b, off, len);
        }
    }

    static Socket dial(Phone p) throws IOException {
        Socket s = new Socket("127.0.0.1", p.port());
        s.setSoTimeout(4000);
        return s;
    }

    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int x;
        while ((x = in.read()) >= 0 && x != '\n') {
            sb.append((char) x);
        }
        return sb.toString();
    }

    /** A raw signed request, the way an attacker (or the tests of single rules) builds it by hand. */
    static String raw(String secret, String nonce, long seq, String method, String target, String signedTarget, String extra) {
        return method + " " + target + " HTTP/1.0\r\nX-Seq: " + seq + "\r\nX-Sig: "
                + LinkAuth.sign(secret, nonce, seq, method, signedTarget) + "\r\n" + extra + "\r\n";
    }

    static Client.Resp rawCall(Socket s, String request) throws IOException {
        s.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        s.getOutputStream().flush();
        return Client.readFramed(s.getInputStream(), 4 * 1024 * 1024);
    }

    static boolean dead(Socket s) {
        try {
            s.setSoTimeout(1500);
            return s.getInputStream().read() < 0;
        } catch (IOException e) {
            return true;
        }
    }

    // ------------------------------------------------------------------------------------------------ the pure rules

    static void auth() {
        String n1 = LinkAuth.newNonce();
        String n2 = LinkAuth.newNonce();
        check(n1.matches("[0-9a-f]{32}"), "a nonce is 32 hex digits: " + n1);
        check(!n1.equals(n2), "two nonces differ");
        check(n1.equals(LinkAuth.parseGreeting(LinkAuth.greeting(n1))), "a greeting is read back");
        check(n1.equals(LinkAuth.parseGreeting("LM1 " + n1 + "\r")), "a greeting with a carriage return is read back");
        for (String bad : new String[] {null, "", "LM1", "LM1 ", "LM2 " + n1, "LM1 " + n1.substring(1), "LM1 " + n1 + "0", "LM1 " + n1.toUpperCase(),
            "HTTP/1.0 200 OK", "lm1 " + n1, "LM1  " + n1}) {
            check(LinkAuth.parseGreeting(bad) == null, "not a greeting: " + bad);
        }
        String sig = LinkAuth.sign(SECRET, n1, 7, "POST", "/act?name=hotspot_on");
        check(sig.matches("[0-9a-f]{64}"), "a signature is 64 hex digits");
        check(sig.equals(LinkAuth.sign(SECRET, n1, 7, "POST", "/act?name=hotspot_on")), "the same request gives the same signature");
        check(!sig.equals(LinkAuth.sign(OTHER, n1, 7, "POST", "/act?name=hotspot_on")), "another secret, another signature");
        check(!sig.equals(LinkAuth.sign(SECRET, n2, 7, "POST", "/act?name=hotspot_on")), "another nonce, another signature");
        check(!sig.equals(LinkAuth.sign(SECRET, n1, 8, "POST", "/act?name=hotspot_on")), "another number, another signature");
        check(!sig.equals(LinkAuth.sign(SECRET, n1, 7, "GET", "/act?name=hotspot_on")), "another method, another signature");
        check(!sig.equals(LinkAuth.sign(SECRET, n1, 7, "POST", "/act?name=hotspot_off")), "another target, another signature");
        // fields cannot slide into each other: number 1 with method "2POST" must not sign like number 12 with method "POST",
        // nor method "GET" with target "/a/b" like method "GET/a" with target "/b"
        check(!LinkAuth.sign(SECRET, n1, 1, "2POST", "/s").equals(LinkAuth.sign(SECRET, n1, 12, "POST", "/s")), "number and method are kept apart");
        check(!LinkAuth.sign(SECRET, n1, 3, "GET", "/a/b").equals(LinkAuth.sign(SECRET, n1, 3, "GET/a", "/b")), "method and target are kept apart");
        List<String> trust = Arrays.asList(OTHER, SECRET);
        check(SECRET.equals(LinkAuth.match(trust, n1, 7, "POST", "/act?name=hotspot_on", sig)), "the secret that signed is found");
        check(OTHER.equals(LinkAuth.match(trust, n1, 7, "POST", "/x", LinkAuth.sign(OTHER, n1, 7, "POST", "/x"))), "the other secret is found");
        check(LinkAuth.match(trust, n1, 7, "POST", "/act?name=hotspot_on", LinkAuth.sign(STRANGER, n1, 7, "POST", "/act?name=hotspot_on")) == null,
                "a stranger's signature matches nothing");
        check(LinkAuth.match(Collections.<String>emptyList(), n1, 7, "POST", "/act?name=hotspot_on", sig) == null, "nobody trusted: nothing matches");
        check(LinkAuth.match(trust, n1, 7, "POST", "/act?name=hotspot_on", null) == null, "no signature");
        check(LinkAuth.match(trust, n1, 7, "POST", "/act?name=hotspot_on", "") == null, "empty signature");
        check(LinkAuth.match(trust, n1, 7, "POST", "/act?name=hotspot_on", sig.substring(1)) == null, "short signature");
        check(LinkAuth.match(trust, n1, 7, "POST", "/act?name=hotspot_on", sig.toUpperCase()) == null, "upper-case signature");
        check(LinkAuth.match(trust, n1, -1, "POST", "/act?name=hotspot_on", LinkAuth.sign(SECRET, n1, -1, "POST", "/act?name=hotspot_on")) == null,
                "a negative number is never accepted");
        check(LinkAuth.match(Arrays.asList("short", null, SECRET), n1, 7, "POST", "/act?name=hotspot_on", sig) != null, "odd entries in the trust list are skipped");
        check(LinkAuth.match(Arrays.asList("short"), n1, 7, "POST", "/x", LinkAuth.sign("short", n1, 7, "POST", "/x")) == null,
                "a secret that is not 32 characters is never trusted");
        Random rnd = new Random(5);
        for (int i = 0; i < 300; i++) {
            // one flipped character anywhere in the signature must fail
            int at = rnd.nextInt(64);
            char c = sig.charAt(at);
            char d = c == '0' ? '1' : '0';
            String bent = sig.substring(0, at) + d + sig.substring(at + 1);
            check(LinkAuth.match(trust, n1, 7, "POST", "/act?name=hotspot_on", bent) == null, "a bent signature fails at " + at);
        }
    }

    static void routes() {
        String[] yes = {"/s", "/sig", "/boot", "/act", "/probe", "/cells", "/screen/state", "/screen/on", "/screen/off", "/speed/start", "/speed/stop",
            "/speed/status"};
        for (String p : yes) {
            check(RemoteRoutes.overBluetooth("GET", p) && RemoteRoutes.overBluetooth("POST", p), "served over Bluetooth: " + p);
            check(!RemoteRoutes.overBluetooth("PUT", p) && !RemoteRoutes.overBluetooth("DELETE", p) && !RemoteRoutes.overBluetooth("", p)
                    && !RemoteRoutes.overBluetooth("get", p), "only GET and POST: " + p);
            check(!RemoteRoutes.overBluetooth("GET", p + "/") && !RemoteRoutes.overBluetooth("GET", p + "x") && !RemoteRoutes.overBluetooth("GET", "/x" + p),
                    "the path must be exact: " + p);
        }
        String[] no = {"/pair", "/apk", "/fs/list", "/fs/read", "/fs/write", "/fs/delete", "/sms/threads", "/sms/send", "/screen/video", "/screen/ctl",
            "/screen/frame", "/screen/profile", "/screen/tap", "/screen/swipe", "/screen/key", "/screen/text", "/screen/pin", "/band", "/band/keep",
            "/band/status", "/bandinfo", "/", "", "/S", "/act/", "s", "/screen", "/speed"};
        for (String p : no) {
            check(!RemoteRoutes.overBluetooth("GET", p) && !RemoteRoutes.overBluetooth("POST", p), "NOT served over Bluetooth: " + p);
        }
        // answer first, or act first
        for (String a : new String[] {"poweroff", "reboot", "airplane_on"}) {
            for (int m = 0; m < 4; m++) {
                check(RemoteRoutes.cutsLink(a, (m & 1) != 0, (m & 2) != 0), a + " always takes the link away");
            }
        }
        for (String a : new String[] {"hotspot_off", "hotspot_restart", "radio_cycle", "standby", "hotspot_on", "boot_hotspot_on", "wifi_on",
            "fast_direct_on", "fast_direct_off"}) {
            check(RemoteRoutes.cutsLink(a, false, true) && RemoteRoutes.cutsLink(a, false, false), a + " cuts the hotspot route");
            check(!RemoteRoutes.cutsLink(a, true, true) && !RemoteRoutes.cutsLink(a, true, false), a + " leaves Bluetooth up: the real result comes back");
        }
        check(!RemoteRoutes.cutsLink("wifi_off", false, true), "Wi-Fi off, asked through the hotspot: the hotspot stays");
        check(RemoteRoutes.cutsLink("wifi_off", false, false), "Wi-Fi off, asked through the Wi-Fi being switched off");
        check(!RemoteRoutes.cutsLink("wifi_off", true, true) && !RemoteRoutes.cutsLink("wifi_off", true, false), "Wi-Fi off over Bluetooth");
        check(RemoteRoutes.cutsLink("wifi_off", false, null), "Wi-Fi off, and the phone cannot tell whether its hotspot is on: answer first, the safe side");
        check(!RemoteRoutes.cutsLink("wifi_off", true, null), "Wi-Fi off over Bluetooth stays act-then-answer whatever the hotspot does");
        for (String a : new String[] {"poweroff", "reboot", "airplane_on", "hotspot_off", "hotspot_restart", "radio_cycle", "standby", "hotspot_on", "wifi_on",
            "fast_direct_on", "fast_direct_off"}) {
            check(RemoteRoutes.cutsLink(a, false, null), a + " over the network, hotspot not known: answered first as before");
        }
        // Fast Wi-Fi's own network (docs/FAST_WIFI.md): switching it on ends the hotspot the request came over, switching it off ends
        // the network the request came over; over Bluetooth both act first and answer with the real result
        check(!RemoteRoutes.cutsLink("fast_direct_on", true, true, true), "fast_direct_on over Bluetooth: the real result comes back");
        check(!RemoteRoutes.cutsLink("fast_direct_off", true, false, false), "fast_direct_off over Bluetooth: the real result comes back");
        check(!RemoteRoutes.cutsLink("fast_allow", false, true), "fast_allow cuts nothing: answered after it is done");
        // "Hotspot off" in its deep form (hotspot, location, airplane mode, screen): airplane mode can restart Bluetooth under the
        // request, so it is answered first on EVERY route, whatever the phone says about its hotspot or airplane mode
        for (int m = 0; m < 8; m++) {
            check(RemoteRoutes.cutsLink("hotspot_off_deep", (m & 1) != 0, (m & 2) != 0, (m & 4) != 0), "hotspot_off_deep always answers first");
        }
        check(RemoteRoutes.cutsLink("hotspot_off_deep", true, null) && RemoteRoutes.cutsLink("hotspot_off_deep", false, null), "hotspot_off_deep answers first, hotspot not known");
        // "Hotspot on" from airplane mode: ending airplane mode can restart Bluetooth, so over Bluetooth it answers first as well
        for (String a : new String[] {"hotspot_on", "boot_hotspot_on"}) {
            check(RemoteRoutes.cutsLink(a, true, false, true) && RemoteRoutes.cutsLink(a, true, true, true) && RemoteRoutes.cutsLink(a, true, null, true),
                    a + " over Bluetooth from airplane mode answers first");
            check(RemoteRoutes.cutsLink(a, false, false, true) && RemoteRoutes.cutsLink(a, false, null, true), a + " over the hotspot route from airplane mode: as before");
            check(!RemoteRoutes.cutsLink(a, true, false, false) && !RemoteRoutes.cutsLink(a, true, null, false), a + " over Bluetooth with no airplane mode: the real result comes back");
        }
        // airplane mode changes the answer of no other action
        for (String a : new String[] {"hotspot_off", "hotspot_restart", "radio_cycle", "standby", "wifi_on", "wifi_off", "poweroff", "reboot", "airplane_on", "safety",
            "location_on", "location_off", "screen_off", "airplane_off", "nonsense"}) {
            for (int m = 0; m < 4; m++) {
                check(RemoteRoutes.cutsLink(a, (m & 1) != 0, (m & 2) != 0, true) == RemoteRoutes.cutsLink(a, (m & 1) != 0, (m & 2) != 0, false),
                        a + ": airplane mode changes nothing");
            }
        }
        // the location switches keep the link
        for (String a : new String[] {"location_on", "location_off"}) {
            for (int m = 0; m < 8; m++) {
                check(!RemoteRoutes.cutsLink(a, (m & 1) != 0, (m & 2) != 0, (m & 4) != 0), a + " keeps the link");
            }
        }
        for (String a : new String[] {"safety", "restore", "net_lte", "net_5g", "eco_on", "eco_off", "screen_off", "bt_setup", "eco_auto_on", "", "nonsense",
            "airplane_off", "restart_modem", "hotspot_info"}) {
            for (int m = 0; m < 4; m++) {
                check(!RemoteRoutes.cutsLink(a, (m & 1) != 0, (m & 2) != 0), a + " keeps the link");
            }
            check(!RemoteRoutes.cutsLink(a, false, null) && !RemoteRoutes.cutsLink(a, true, null), a + " keeps the link, hotspot not known");
        }
    }

    /** Is the hotspot on: on, off, or not known. "Not known" must never come out as "off". */
    static void hotspot() {
        for (int announced = -3; announced <= 20; announced++) {
            for (int iface = 0; iface < 2; iface++) {
                check(Boolean.TRUE.equals(RemoteRoutes.hotspotOn(Boolean.TRUE, announced, iface == 1)), "the Wi-Fi service says on: on (" + announced + ")");
                check(Boolean.FALSE.equals(RemoteRoutes.hotspotOn(Boolean.FALSE, announced, iface == 1)),
                        "the Wi-Fi service says off: off, whatever was announced before (" + announced + ", " + iface + ")");
            }
        }
        check(Boolean.TRUE.equals(RemoteRoutes.hotspotOn(null, 13, false)) && Boolean.TRUE.equals(RemoteRoutes.hotspotOn(null, 13, true)), "announced: on");
        for (int off : new int[] {10, 11, 14}) {
            check(Boolean.FALSE.equals(RemoteRoutes.hotspotOn(null, off, false)), "announced: off, going off, or failed to start (" + off + ")");
            check(Boolean.FALSE.equals(RemoteRoutes.hotspotOn(null, off, true)), "an announcement counts for more than an interface name (" + off + ")");
        }
        check(Boolean.FALSE.equals(RemoteRoutes.hotspotOn(null, 12, false)), "a hotspot that is still coming up is not on yet");
        for (int none : new int[] {-1, 0, 1, 9, 15, 99}) {
            check(RemoteRoutes.hotspotOn(null, none, false) == null, "nothing can tell: NOT KNOWN, never 'off' (" + none + ")");
            check(Boolean.TRUE.equals(RemoteRoutes.hotspotOn(null, none, true)), "nothing announced, but a hotspot interface is up: on (" + none + ")");
        }
        // "down" for the helper that switches the hotspot: nothing to stop, and a start may be asked
        for (int state = -3; state <= 20; state++) {
            boolean down = RemoteRoutes.hotspotDown(state);
            check(down == (state == 11 || state == 14), "down is off (11) or failed to start (14), nothing else: " + state + " -> " + down);
            if (down) {
                check(Boolean.FALSE.equals(RemoteRoutes.hotspotOn(null, state, false)), "what the helper calls down, the status calls off (" + state + ")");
            }
            if (Boolean.TRUE.equals(RemoteRoutes.hotspotOn(null, state, false)) || RemoteRoutes.hotspotOn(null, state, false) == null) {
                check(!down, "a hotspot that is on, or whose state is not known, is never 'down' (" + state + ")");
            }
        }
        check(RemoteRoutes.hotspotDown(14), "a hotspot whose last start failed is down: 'Hotspot off' has nothing to do, a restart only starts");
        check(!RemoteRoutes.hotspotDown(10) && !RemoteRoutes.hotspotDown(12) && !RemoteRoutes.hotspotDown(13) && !RemoteRoutes.hotspotDown(-1),
                "still going off, coming up, on, and not known are not down");
    }

    /** The phone's screen switch, the full door, and listening again. */
    static void doorRules() {
        for (String p : new String[] {"/screen/state", "/screen/on", "/screen/off"}) {
            check(RemoteRoutes.screenPower(p), "a power switch, not behind the screen switch: " + p);
            check(RemoteRoutes.overBluetooth("POST", p), "and the same three are the screen routes served over Bluetooth: " + p);
        }
        for (String p : new String[] {"/screen/frame", "/screen/video", "/screen/ctl", "/screen/tap", "/screen/swipe", "/screen/key", "/screen/text", "/screen/pin",
            "/screen/profile", "/screen/on/", "/screen/offx", "/screen", "/screen/", "/screen/State", "", "/s", "/act"}) {
            check(!RemoteRoutes.screenPower(p), "seeing, touching and typing stay behind the screen switch: " + p);
        }
        // which connection gives way: an unproven one before a proven one, the oldest among equals
        check(RemoteRoutes.givesWay(new boolean[] {false, false}) == 0, "two strangers: the older one goes");
        check(RemoteRoutes.givesWay(new boolean[] {true, false}) == 1, "the tablet (older, proven) stays; the stranger goes");
        check(RemoteRoutes.givesWay(new boolean[] {false, true}) == 0, "the stranger (older) goes; the tablet stays");
        check(RemoteRoutes.givesWay(new boolean[] {true, true}) == 0, "both proven: the older one goes (a connection the tablet has dropped)");
        check(RemoteRoutes.givesWay(new boolean[] {true, true, false, false}) == 2, "the oldest of the unproven");
        check(RemoteRoutes.givesWay(new boolean[] {true}) == 0 && RemoteRoutes.givesWay(new boolean[] {false}) == 0, "one connection: that one");
        for (int mask = 0; mask < 64; mask++) {
            boolean[] proven = new boolean[6];
            int firstUnproven = -1;
            for (int i = 0; i < 6; i++) {
                proven[i] = (mask & (1 << i)) != 0;
                if (!proven[i] && firstUnproven < 0) {
                    firstUnproven = i;
                }
            }
            int g = RemoteRoutes.givesWay(proven);
            check(g >= 0 && g < 6 && (firstUnproven < 0 ? g == 0 : g == firstUnproven), "a proven connection never goes while an unproven one is open (" + mask + ")");
        }
        // listening again by itself
        for (String cure : new String[] {"perm", "error", "off"}) {
            check(RemoteRoutes.listenAgain(cure, RemoteRoutes.LISTEN_AGAIN_MS) && RemoteRoutes.listenAgain(cure, Long.MAX_VALUE), "a new try can cure '" + cure + "'");
            check(!RemoteRoutes.listenAgain(cure, RemoteRoutes.LISTEN_AGAIN_MS - 1) && !RemoteRoutes.listenAgain(cure, 0) && !RemoteRoutes.listenAgain(cure, -5),
                    "but not sooner than half a minute after the last one: " + cure);
        }
        for (String leave : new String[] {"on", "disabled", "none", "", "ON", null}) {
            check(!RemoteRoutes.listenAgain(leave, Long.MAX_VALUE), "nothing to cure, or the user's own switch: left alone (" + leave + ")");
        }
        check(RemoteRoutes.LISTEN_AGAIN_MS >= 30_000L, "the phone is not kept busy with tries");
    }

    static void addresses() {
        check(BtAddr.valid("5C:F3:70:8A:1B:2C"), "a real address is an address");
        for (String bad : new String[] {null, "", "5c:f3:70:8a:1b:2c", "5C:F3:70:8A:1B", "5C:F3:70:8A:1B:2C:3D", "5C-F3-70-8A-1B-2C", "5CF3708A1B2C",
            "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "02:00:00:00:00:00", "XX:XX:XX:XX:1B:2C", "5C:F3:70:8A:1B:2G", " 5C:F3:70:8A:1B:2C"}) {
            check(!BtAddr.valid(bad), "not a usable address: " + bad);
        }
        check(BtAddr.own("5c:f3:70:8a:1b:2c\n").equals("5C:F3:70:8A:1B:2C"), "the setting's answer, in small letters");
        check(BtAddr.own("null\n  address: 5C:F3:70:8A:1B:2C\n  name: Galaxy A15 5G\n").equals("5C:F3:70:8A:1B:2C"), "the setting says null, the report has it");
        check(BtAddr.own("null\r\n  Address: 5c:f3:70:8a:1b:2c\r\n").equals("5C:F3:70:8A:1B:2C"), "a capital A, small letters and Windows line ends");
        check(BtAddr.own("null\n  address: XX:XX:XX:XX:1B:2C\n").isEmpty(), "a hidden address is not an address");
        check(BtAddr.own("02:00:00:00:00:00\n  address: 5C:F3:70:8A:1B:2C").equals("5C:F3:70:8A:1B:2C"), "Android's placeholder is skipped, the real one is taken");
        check(BtAddr.own("00:00:00:00:00:00").isEmpty() && BtAddr.own("").isEmpty() && BtAddr.own(null).isEmpty(), "nothing usable: empty");
        check(BtAddr.own("Bluetooth Status\n  enabled: true\n  address: 5C:F3:70:8A:1B:2C:99\n").isEmpty(), "seven pairs are not an address (no part of it is taken)");
        check(BtAddr.own("uuid 0000110a-0000-1000-8000-00805f9b34fb time 12:34:56").isEmpty(), "a time or a service number is not an address");
        // the phone's OWN address or none: never another device's
        String headset = "AA:BB:CC:DD:EE:01";
        check(BtAddr.own("null\n  address: XX:XX:XX:XX:1B:2C\n  Address: " + headset + "\n").isEmpty(),
                "the phone hides its own address: a later 'address:' line is another device's and is NOT taken");
        check(BtAddr.own("null\n  address: XX:XX:XX:XX:1B:2C\n" + headset + "\n").isEmpty(), "nor a bare address after the hidden one");
        check(BtAddr.own("null\n  Peer address: " + headset + "\n  mRemoteAddress: " + headset + "\n  connected to " + headset + "\n").isEmpty(),
                "lines that merely contain an address are not the phone's own line");
        check(BtAddr.own("null\n  bonded address: " + headset + "\n  address: 5C:F3:70:8A:1B:2C\n").equals("5C:F3:70:8A:1B:2C"),
                "other devices' lines before the phone's own line are passed over");
        check(BtAddr.own("null\n  address: 5C:F3:70:8A:1B:2C\n  address: " + headset + "\n").equals("5C:F3:70:8A:1B:2C"), "the first 'address:' line is the phone's own");
        check(BtAddr.own("null\n  address: 5C:F3:70:8A:1B:2C and more\n").isEmpty(), "a line with more on it is not trusted");
        check(BtAddr.own("address:5C:F3:70:8A:1B:2C").equals("5C:F3:70:8A:1B:2C") && BtAddr.own("address:").isEmpty(), "with or without the space; an empty line gives nothing");
    }

    // ------------------------------------------------------------------------------------------------ over a connection

    static void goodCalls() throws Exception {
        try (Phone p = new Phone(); Socket s = dial(p)) {
            Tap tap = new Tap(s.getOutputStream());
            LinkClient c = new LinkClient(s.getInputStream(), tap, SECRET);
            for (int i = 0; i < 60; i++) {
                String target = (i % 3 == 0 ? "/s" : "/act?name=step" + i + "&arg=a%20b");
                String method = i % 3 == 0 ? "GET" : "POST";
                Client.Resp r = c.call(method, target);
                check(r.code == 200, "call " + i + " answered 200, got " + r.code);
                check(r.body.equals("{\"ok\":true,\"echo\":" + Json.quote(target) + "}"), "call " + i + " got its own answer: " + r.body);
            }
            check(p.seen.size() == 60, "all 60 requests reached the handler, each once: " + p.seen.size());
            check(p.seen.get(0).equals("GET /s via=bt token=" + SECRET + " name="), "the handler sees the route, the link and the secret that signed: " + p.seen.get(0));
            check(p.seen.get(1).contains("POST /act?name=step1&arg=a%20b via=bt") && p.seen.get(1).endsWith("name=step1"), "the query is parsed: " + p.seen.get(1));
            // sizes
            for (int n : new int[] {0, 1, 2, 4095, 4096, 4097, 8192, 100000}) {
                Client.Resp r = c.call("POST", "/act?name=big" + n);
                check(r.code == 200 && r.body.length() == n, "a reply of " + n + " bytes arrives whole: " + r.body.length());
                check(n == 0 || (r.body.charAt(0) == 'a' && r.body.charAt(n - 1) == (char) ('a' + (n - 1) % 26)), "and is the right text (" + n + ")");
            }
            Client.Resp u = c.call("POST", "/act?name=utf");
            check(u.body.equals("{\"out\":\"grüße ✓ 你好\"}"), "a reply with letters of several bytes: " + u.body);
            Client.Resp e = c.call("POST", "/act?name=conflict");
            check(e.code == 409 && e.body.contains("Shizuku is not running"), "the handler's own error comes through: " + e.code + " " + e.body);
            check(c.call("GET", "/s").code == 200, "and the connection goes on after it");
            // what went over the air
            String sent = tap.copy.toString("ISO-8859-1");
            check(!sent.contains(SECRET), "the secret is never sent");
            check(!sent.toLowerCase().contains("x-token"), "no token header on this link");
            check(sent.contains("X-Seq: 1\r\n") && sent.contains("X-Seq: 2\r\n") && sent.contains("X-Sig: "), "requests carry a number and a signature");
            s.close();
            check(p.waitServed() == 71, "serve() ends when the tablet goes and says how many requests it served");
            // the watch (looked at once the connection is over, so the phone's side has finished writing it down):
            // first the short wait, then working / the long wait for every request
            List<String> w = new ArrayList<>(p.watch);
            check(w.size() == 1 + 2 * 71, "one short wait, then two notes per request: " + w.size());
            check(w.get(0).equals("waiting " + LinkServer.FIRST_MS), "a new caller gets the short wait: " + w.get(0));
            boolean pairs = true;
            for (int i = 1; i + 1 < w.size(); i += 2) {
                pairs &= w.get(i).equals("working") && w.get(i + 1).equals("waiting " + LinkServer.IDLE_MS);
            }
            check(pairs, "every request: working, then the long wait");
        }
    }

    static void wrongSecret() throws Exception {
        try (Phone p = new Phone(); Socket s = dial(p)) {
            LinkClient c = new LinkClient(s.getInputStream(), s.getOutputStream(), STRANGER);
            for (int i = 0; i < LinkServer.MAX_BAD; i++) {
                Client.Resp r = c.call("POST", "/act?name=poweroff");
                check(r.code == 401 && r.body.contains("not paired"), "a stranger is refused (" + i + "): " + r.code);
            }
            boolean closed = false;
            try {
                c.call("GET", "/s");
            } catch (IOException e) {
                closed = true;
            }
            check(closed, "after " + LinkServer.MAX_BAD + " refusals the connection is closed");
            check(p.waitServed() == 0 && p.seen.isEmpty(), "nothing of a stranger's reaches the handler");
            boolean shortWaits = true;
            for (String w : new ArrayList<>(p.watch)) {
                shortWaits &= !w.equals("waiting " + LinkServer.IDLE_MS);
            }
            check(shortWaits, "a caller that never proved itself never gets the long wait: " + p.watch);
        }
        // a trusted tablet that is forgotten in the middle of a connection
        try (Phone p = new Phone(); Socket s = dial(p)) {
            LinkClient c = new LinkClient(s.getInputStream(), s.getOutputStream(), SECRET);
            check(c.call("GET", "/s").code == 200, "trusted: served");
            p.trusted.remove(SECRET);
            check(c.call("GET", "/s").code == 401, "forgotten on the phone: refused at once, on the same connection");
            p.trusted.add(SECRET);
            check(c.call("GET", "/s").code == 200, "trusted again: served");
            check(p.seen.size() == 2, "the refused one did not reach the handler");
        }
        try (Phone p = new Phone(); Socket s = dial(p)) {
            LinkClient c = new LinkClient(s.getInputStream(), s.getOutputStream(), OTHER);
            check(c.call("GET", "/s").code == 200 && p.seen.get(0).contains("token=" + OTHER), "the second paired tablet is told apart from the first");
        }
    }

    static void replayAndTamper() throws Exception {
        try (Phone p = new Phone(); Socket s = dial(p)) {
            String nonce = LinkAuth.parseGreeting(readLine(s.getInputStream()));
            check(nonce != null && nonce.equals(p.nonces.get(0)), "the phone greets with its random number");
            String req = raw(SECRET, nonce, 5, "POST", "/act?name=hotspot_off", "/act?name=hotspot_off", "Content-Length: 0\r\n");
            check(rawCall(s, req).code == 200, "a properly signed request is served");
            check(rawCall(s, req).code == 401, "the very same bytes again are refused (the number was used)");
            check(rawCall(s, raw(SECRET, nonce, 4, "POST", "/act?name=hotspot_off", "/act?name=hotspot_off", "")).code == 401, "a lower number is refused");
            check(p.seen.size() == 1, "the replays did not reach the handler: " + p.seen.size());
            check(rawCall(s, raw(SECRET, nonce, 6, "POST", "/act?name=poweroff", "/act?name=hotspot_off", "")).code == 401,
                    "a changed target under an old signature is refused");
            // the connection was closed after three refusals
            check(dead(s), "three refusals end the connection");
            check(p.waitServed() == 1, "one request was served on it");

            // the recorded request on a new connection: another random number, so it is worthless
            try (Socket s2 = dial(p)) {
                String nonce2 = LinkAuth.parseGreeting(readLine(s2.getInputStream()));
                check(nonce2 != null && !nonce2.equals(nonce), "a new connection has a new random number");
                check(rawCall(s2, req).code == 401, "a request recorded on one connection is refused on the next");
                check(rawCall(s2, raw(SECRET, nonce2, 1, "GET", "/act?name=hotspot_off", "/act?name=hotspot_off", "").replace("GET", "POST")).code == 401,
                        "a changed method is refused");
                check(rawCall(s2, raw(SECRET, nonce2, 9, "POST", "/act?name=hotspot_off", "/act?name=hotspot_off", "")).code == 200,
                        "the right request for THIS connection is served (2 refusals do not close it)");
                check(p.seen.size() == 2, "only the two honest requests reached the handler");
                // numbers need not be consecutive, only rising; and a huge one is fine
                check(rawCall(s2, raw(SECRET, nonce2, 9, "GET", "/s", "/s", "")).code == 401, "the same number with another request is refused");
                check(dead(s2), "that was the third refusal on this connection");
            }
            try (Socket s3 = dial(p)) {
                String nonce3 = LinkAuth.parseGreeting(readLine(s3.getInputStream()));
                check(rawCall(s3, raw(SECRET, nonce3, 4000000000L, "GET", "/s", "/s", "")).code == 200, "a big number is fine");
                String noSig = "GET /s HTTP/1.0\r\nX-Seq: 4000000001\r\n\r\n";
                check(rawCall(s3, noSig).code == 401, "no signature at all is refused");
                String tokenInstead = "GET /s HTTP/1.0\r\nX-Token: " + SECRET + "\r\n\r\n";
                check(rawCall(s3, tokenInstead).code == 401, "the hotspot's token header does not open the Bluetooth door");
                check(p.seen.size() == 3, "still only the honest requests");
            }
        }
    }

    static void refusedThings() throws Exception {
        try (Phone p = new Phone(); Socket s = dial(p)) {
            LinkClient c = new LinkClient(s.getInputStream(), s.getOutputStream(), SECRET);
            String[] no = {"/pair", "/apk", "/fs/list?path=%2F", "/sms/threads", "/screen/video", "/screen/ctl", "/screen/frame", "/screen/pin", "/band?set=lte:5"};
            for (String t : no) {
                Client.Resp r = c.call(t.equals("/pair") || t.equals("/apk") ? "POST" : "GET", t);
                check(r.code == 404 && r.body.contains("hotspot"), t + " is refused over Bluetooth, with a reason: " + r.code + " " + r.body);
            }
            check(p.seen.isEmpty(), "none of them reached the handler");
            check(c.call("GET", "/s").code == 200, "and a served route still works afterwards (a refused route is not a strike)");
            // handlers that fail
            Client.Resp b = c.call("POST", "/act?name=boom");
            check(b.code == 500 && b.body.contains("handler broke"), "a handler that throws gives a clean 500: " + b.code + " " + b.body);
            Client.Resp io = c.call("POST", "/act?name=ioboom");
            check(io.code == 500 && io.body.contains("handler io broke"), "so does one with an I/O error: " + io.code);
            Client.Resp h = c.call("POST", "/act?name=half");
            check(h.code == 500 && h.body.contains("half a reply"), "half a reply never reaches the line: " + h.code + " " + h.body);
            Client.Resp ok = c.call("POST", "/act?name=after");
            check(ok.code == 200 && ok.body.contains("after"), "and the connection is still in step afterwards: " + ok.body);
        }
        // a body cannot travel here
        try (Phone p = new Phone(); Socket s = dial(p)) {
            String nonce = LinkAuth.parseGreeting(readLine(s.getInputStream()));
            Client.Resp r = rawCall(s, raw(SECRET, nonce, 1, "POST", "/act?name=x", "/act?name=x", "Content-Length: 5\r\n") + "hello");
            check(r.code == 400, "a request with a body is refused: " + r.code);
            check(dead(s), "and the connection ends (the next request could not be found)");
            check(p.waitServed() == 0 && p.seen.isEmpty(), "it did not reach the handler");
        }
    }

    static void garbage() throws Exception {
        Random rnd = new Random(11);
        for (int round = 0; round < 40; round++) {
            try (Phone p = new Phone(); Socket s = dial(p)) {
                readLine(s.getInputStream());
                byte[] junk;
                if (round == 0) {
                    junk = new byte[9000]; // a "header" that never ends
                    Arrays.fill(junk, (byte) 'A');
                } else if (round == 1) {
                    junk = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
                } else if (round == 2) {
                    junk = "GET\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
                } else if (round == 3) {
                    junk = "POST /act?name=poweroff HTTP/1.0\r\nX-Seq: banana\r\nX-Sig: \r\nContent-Length: -4\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
                } else {
                    junk = new byte[1 + rnd.nextInt(3000)];
                    rnd.nextBytes(junk);
                }
                try {
                    s.getOutputStream().write(junk);
                    s.getOutputStream().flush();
                    s.shutdownOutput();
                } catch (IOException ignored) {
                    // the phone hung up while we were still sending: fine
                }
                int n = p.waitServed();
                check(n == 0, "garbage " + round + " ends the connection without serving anything (got " + n + ")");
                check(p.seen.isEmpty(), "garbage " + round + " never reaches the handler: " + p.seen);
            }
        }
    }

    static void silentCaller() throws Exception {
        try (Phone p = new Phone()) {
            p.closeAfterMs = 150; // the wrapper's job, here in fast motion: close when the allowed silence is over
            try (Socket s = dial(p)) {
                String g = readLine(s.getInputStream());
                check(LinkAuth.parseGreeting(g) != null, "the silent caller was greeted");
                check(dead(s), "a caller that says nothing is hung up on");
                check(p.waitServed() == 0, "serve() returns when the watch closes the connection");
                List<String> w = new ArrayList<>(p.watch);
                check(w.size() == 1 && w.get(0).equals("waiting " + LinkServer.FIRST_MS), "it only ever got the short wait: " + w);
            }
        }
    }

    static void clientRules() throws Exception {
        // not LinkMeter on the other end
        for (final String hello : new String[] {"HTTP/1.0 200 OK\r\n\r\n", "SSH-2.0-OpenSSH_9.6\n", "LM2 00112233445566778899aabbccddeeff\n",
            "LM1 short\n", repeat('x', 200) + "\n", ""}) {
            try (ServerSocket ss = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))) {
                Thread t = new Thread(() -> {
                    try (Socket a = ss.accept()) {
                        a.getOutputStream().write(hello.getBytes(StandardCharsets.US_ASCII));
                        a.getOutputStream().flush();
                    } catch (IOException ignored) {
                        // done
                    }
                });
                t.setDaemon(true);
                t.start();
                try (Socket s = new Socket("127.0.0.1", ss.getLocalPort())) {
                    s.setSoTimeout(3000);
                    boolean refused = false;
                    try {
                        new LinkClient(s.getInputStream(), s.getOutputStream(), SECRET);
                    } catch (IOException e) {
                        refused = true;
                    }
                    check(refused, "the tablet does not talk to something that does not greet properly: "
                            + (hello.length() > 20 ? hello.substring(0, 20) : hello).trim());
                }
            }
        }
        // replies the tablet must not accept, and one it must
        String[][] cases = {
            {"HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\n\r\nno length", "bad"},
            {"HTTP/1.0 200 OK\r\nContent-Length: 50\r\n\r\ntoo short", "bad"},
            {"HTTP/1.0 200 OK\r\nContent-Length: 99999999\r\n\r\n", "bad"},
            {"nonsense\r\n\r\n", "bad"},
            {"HTTP/1.0 200 OK\r\nContent-Length: 2\r\n\r\nok", "ok"},
        };
        for (final String[] cs : cases) {
            try (ServerSocket ss = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))) {
                Thread t = new Thread(() -> {
                    try (Socket a = ss.accept()) {
                        a.getOutputStream().write(LinkAuth.greeting(LinkAuth.newNonce()).getBytes(StandardCharsets.US_ASCII));
                        a.getOutputStream().flush();
                        Http.readHead(new java.io.BufferedInputStream(a.getInputStream()));
                        a.getOutputStream().write(cs[0].getBytes(StandardCharsets.US_ASCII));
                        a.getOutputStream().flush();
                    } catch (IOException ignored) {
                        // done
                    }
                });
                t.setDaemon(true);
                t.start();
                try (Socket s = new Socket("127.0.0.1", ss.getLocalPort())) {
                    s.setSoTimeout(3000);
                    LinkClient c = new LinkClient(s.getInputStream(), s.getOutputStream(), SECRET);
                    String got;
                    try {
                        Client.Resp r = c.call("GET", "/s");
                        got = r.code == 200 && r.body.equals("ok") ? "ok" : "odd";
                    } catch (IOException e) {
                        got = "bad";
                    }
                    check(got.equals(cs[1]), "reply " + cs[0].replace("\r\n", "|") + " -> " + got);
                }
            }
        }
    }

    static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    /** Random stories on one connection, against a model of what must come back. */
    static void randomStories() throws Exception {
        Random rnd = new Random(2026);
        String[] served = {"/s", "/boot", "/act?name=a", "/act?name=hotspot_on", "/cells", "/screen/state", "/speed/status?from=3"};
        String[] refused = {"/fs/list", "/sms/threads", "/pair", "/screen/video", "/band?set=lte:5", "/apk"};
        for (int story = 0; story < 60; story++) {
            try (Phone p = new Phone(); Socket s = dial(p)) {
                String nonce = LinkAuth.parseGreeting(readLine(s.getInputStream()));
                long seq = 0;       // the next numbers are taken above this
                long accepted = -1; // the highest number the phone has accepted (a refused request does not use its number up)
                int bad = 0;
                int want = 0;
                List<String> wantSeen = new ArrayList<>();
                boolean alive = true;
                for (int step = 0; step < 25 && alive; step++) {
                    int kind = rnd.nextInt(10);
                    if (kind < 5 || (kind == 7 && accepted < 0)) { // honest, served (also when there is nothing yet to replay)
                        String t = served[rnd.nextInt(served.length)];
                        seq += 1 + rnd.nextInt(5);
                        check(rawCall(s, raw(SECRET, nonce, seq, "GET", t, t, "")).code == 200, "story " + story + ": honest request served");
                        accepted = seq;
                        want++;
                        wantSeen.add("GET " + t);
                    } else if (kind < 7) { // honest, refused route (it is signed properly, so its number counts as used)
                        String t = refused[rnd.nextInt(refused.length)];
                        seq += 1 + rnd.nextInt(5);
                        check(rawCall(s, raw(SECRET, nonce, seq, "GET", t, t, "")).code == 404, "story " + story + ": refused route");
                        accepted = seq;
                    } else { // an attack: a replay, a stranger, or a changed target
                        String req;
                        if (kind == 7) {
                            req = raw(SECRET, nonce, Math.max(0, accepted - rnd.nextInt(3)), "GET", "/s", "/s", "");
                        } else if (kind == 8) {
                            seq += 1;
                            req = raw(STRANGER, nonce, seq, "POST", "/act?name=poweroff", "/act?name=poweroff", "");
                        } else {
                            seq += 1;
                            req = raw(SECRET, nonce, seq, "POST", "/act?name=poweroff", "/act?name=hotspot_on", "");
                        }
                        check(rawCall(s, req).code == 401, "story " + story + ": attack refused");
                        bad++;
                        if (bad >= LinkServer.MAX_BAD) {
                            alive = false;
                        }
                    }
                }
                s.close();
                int n = p.waitServed();
                check(n == want, "story " + story + ": the handler ran exactly for the honest served requests (" + n + " vs " + want + ")");
                boolean same = p.seen.size() == wantSeen.size();
                for (int i = 0; same && i < wantSeen.size(); i++) {
                    same = p.seen.get(i).startsWith(wantSeen.get(i) + " via=bt token=" + SECRET);
                }
                check(same, "story " + story + ": in order, as the right tablet: " + p.seen);
            }
        }
    }

    public static void main(String[] a) throws Exception {
        auth();
        routes();
        hotspot();
        doorRules();
        addresses();
        goodCalls();
        wrongSecret();
        replayAndTamper();
        refusedThings();
        garbage();
        silentCaller();
        clientRules();
        randomStories();
        System.out.println(checks + " checks, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
