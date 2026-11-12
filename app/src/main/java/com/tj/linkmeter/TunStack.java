package com.tj.linkmeter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/**
 * No Android in here (tested in tools/test/TunStackTest.java against a simulated app, and by tools/fast_e2e.sh on a REAL Linux tun
 * device with the Linux kernel's own TCP as the app). The tablet's side of Fast Wi-Fi (docs/FAST_WIFI.md): it reads the packets every
 * app sends into the VPN interface and carries them to LinkMeter on the phone, which opens the real connections there.
 *
 * TCP: each app connection is answered HERE (handshake, data, closing), and its bytes travel over one ordinary TCP connection to the
 * phone's relay ({@link FastProto}). The answer to the app's SYN waits until the phone has reached the server, so a refused or
 * unreachable server looks to the app exactly as it would on a normal network. Between this engine and the app nothing is lost or
 * reordered (it is a local interface), so only what that needs is here: MSS and window scaling, honest windows both ways (an app that
 * reads slowly slows the phone's download down, end to end), a plain go-back retransmit as a safety net, half-close and reset passed
 * through. UDP goes to the phone in datagrams; DNS questions to the VPN's DNS address are answered by the phone's own resolver; QUIC
 * (UDP 443) is refused at once when blocking is on, so apps use TCP, which costs both devices far less.
 *
 * Two threads: one blocks reading the interface and hands the packets over; ONE thread does everything else (all state lives there,
 * no locks), sleeping in the selector when nothing happens. No timer runs while the engine is idle.
 */
final class TunStack {
    /** The VPN interface: one whole packet per read and per write. read blocks; it returns -1 once the interface is closed. */
    interface Tun {
        int read(byte[] b, int off, int len) throws IOException;

        void write(byte[] b, int off, int len) throws IOException;
    }

    /** How the engine reaches the phone. Called on the engine's thread. */
    interface Link {
        /** The phone's relay now, or null when it is not known (no Wi-Fi, no phone). */
        InetSocketAddress relay();

        /** A new TCP socket that will not go through the VPN itself (protected, over Wi-Fi), not connected yet. */
        SocketChannel tcp() throws IOException;

        /** A new UDP socket the same way. */
        DatagramChannel udp() throws IOException;
    }

    /** What the screen and the notification may want to know. Called on the engine's thread: return at once. */
    interface Events {
        /** Several connections in a row could not reach the phone's relay (why, in words for the user). */
        void relayTrouble(String why);

        /** A relay connection worked again after trouble. */
        void relayFine();

        /** The engine stopped by itself (the interface closed, or a fault): the VPN must not stay up without it. */
        void stopped();
    }

    // ------------------------------------------------------------------------------------------- tuning

    static final int MSS = FastProto.MTU - Ip4.TCP_HDR;
    /** Our window scale shift: windows up to 2 MB can be said. */
    static final int OUR_WS = 5;
    /** Per connection: bytes from the app waiting for the phone, and bytes from the phone not yet acknowledged by the app. */
    static final int UP_MAX = 256 * 1024;
    static final int DOWN_MAX = 256 * 1024;
    /** All connections together never hold more than this (the tablet's app may use 128 MB in all). */
    static final long BUDGET = 24L * 1024 * 1024;
    static final int MAX_CONNS = 1024;
    static final long CONNECT_MS = 25_000L;
    static final int RTO_MIN = 300;
    static final int RTO_MAX = 4_000;
    static final int MAX_RETRIES = 10;
    static final long IDLE_MS = 2L * 60 * 60 * 1000;
    static final int SLOTS = 64;
    static final int TROUBLE_AFTER = 3;

    private static final int CONNECTING = 0;
    private static final int SYN_RCVD = 1;
    private static final int OPEN = 2;
    private static final int CLOSED = 3;

    private static final int T_NONE = 0;
    private static final int T_CONNECT = 1;
    private static final int T_SYNACK = 2;
    private static final int T_RTX = 3;
    private static final int T_PERSIST = 4;
    private static final int T_IDLE = 5;

    // ------------------------------------------------------------------------------------------- state

    private final Tun tun;
    private final Link link;
    private final Events events;
    private final byte[] key;
    private final Selector selector;
    private final SecureRandom random = new SecureRandom();
    private final Map<Long, Conn> conns = new HashMap<>();
    private final List<Conn> ackList = new ArrayList<>();
    private final Ip4.Pkt pkt = new Ip4.Pkt();
    /** Packets are built here (one thread writes them all). */
    private final byte[] out = new byte[65536 + Ip4.UDP_HDR];
    private final ByteBuffer one = ByteBuffer.allocate(1);
    private final byte[] udpBuf = new byte[65536];
    private final ByteBuffer udpBB = ByteBuffer.wrap(udpBuf);

    /** The inbox: the reader thread fills slots at tail, the engine empties them from head. */
    private final byte[][] slots = new byte[SLOTS][];
    private final ByteBuffer[] slotBB = new ByteBuffer[SLOTS];
    private final int[] slotLen = new int[SLOTS];
    private volatile int inHead;
    private volatile int inTail;
    private volatile boolean sleeping;
    private volatile boolean running;
    /** stop() was called (an end that nobody asked for is reported through {@link Events#stopped}). */
    private volatile boolean asked;
    private volatile boolean blockQuic = true;

    private DatagramChannel udp;
    private SelectionKey udpKey;
    private InetSocketAddress udpTo;
    private long nextTimer = Long.MAX_VALUE;
    private long allocated;
    private int relayFails;
    private boolean inTrouble;
    private Thread reader;
    private Thread loop;

    /** Counters for the screen (read from other threads; only the engine writes them). */
    volatile long tcpUp;
    volatile long tcpDown;
    volatile long udpUp;
    volatile long udpDown;
    volatile int open;
    volatile long opened;
    volatile long refused;
    volatile long dropped;

    TunStack(Tun tun, Link link, Events events, byte[] key) throws IOException {
        this.tun = tun;
        this.link = link;
        this.events = events;
        this.key = key.clone();
        this.selector = Selector.open();
        for (int i = 0; i < SLOTS; i++) {
            slots[i] = new byte[FastProto.MTU + 64];
            slotBB[i] = ByteBuffer.wrap(slots[i]);
        }
    }

    /** QUIC refused (the default) or carried like any other UDP. Takes effect for the next packet. */
    void blockQuic(boolean b) {
        blockQuic = b;
    }

    synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        loop = new Thread(this::runLoop, "fast-engine");
        reader = new Thread(this::runReader, "fast-tun");
        loop.start();
        reader.start();
    }

    /** Stops both threads soon; every relay connection is closed (the apps' connections end with the VPN). */
    void stop() {
        asked = true;
        running = false;
        try {
            selector.wakeup();
        } catch (RuntimeException ignored) {
            // already closed
        }
    }

    boolean running() {
        return running;
    }

    /** Waits up to ms for the engine thread to end (for tests and for the service's clean stop). */
    void join(long ms) throws InterruptedException {
        Thread t = loop;
        if (t != null) {
            t.join(ms);
        }
    }

    // ------------------------------------------------------------------------------------------- the reader thread

    private void runReader() {
        try {
            while (running) {
                int t = inTail;
                while (t - inHead >= SLOTS) {
                    if (!running) {
                        return;
                    }
                    LockSupport.parkNanos(200_000L); // the engine is behind: wait (the apps' TCP slows down by itself)
                }
                int s = t % SLOTS;
                int n = tun.read(slots[s], 0, slots[s].length);
                if (n < 0) {
                    break; // the interface was closed
                }
                if (n == 0) {
                    continue;
                }
                slotLen[s] = n;
                inTail = t + 1;
                if (sleeping) {
                    selector.wakeup();
                }
            }
        } catch (IOException | RuntimeException e) {
            // the interface is gone: the engine stops with it
        }
        running = false;
        try {
            selector.wakeup();
        } catch (RuntimeException ignored) {
            // closed
        }
    }

    // ------------------------------------------------------------------------------------------- the engine thread

    private void runLoop() {
        try {
            while (running) {
                long now = System.currentTimeMillis();
                long wait = nextTimer == Long.MAX_VALUE ? 0 : Math.max(1, nextTimer - now);
                sleeping = true;
                if (inHead == inTail) {
                    if (wait == 0) {
                        selector.select();
                    } else {
                        selector.select(wait);
                    }
                } else {
                    selector.selectNow();
                }
                sleeping = false;
                drainInbox();
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey k = it.next();
                    it.remove();
                    if (k == udpKey) {
                        udpReadable();
                    } else {
                        Object a = k.attachment();
                        if (a instanceof Conn) {
                            event((Conn) a, k);
                        }
                    }
                }
                timers(System.currentTimeMillis());
                flushAcks();
            }
        } catch (IOException | ClosedSelectorException e) {
            // the selector broke: stop
        } catch (RuntimeException e) {
            // a bug must not leave the tablet without internet and without a word: stop, the service sees it and says so
        } finally {
            running = false;
            closeAll();
            if (!asked) {
                events.stopped();
            }
        }
    }

    private void drainInbox() {
        int h = inHead;
        int t = inTail;
        while (h != t) {
            int s = h % SLOTS;
            packet(s, slots[s], slotLen[s]);
            h++;
            inHead = h;
            if (h == t) {
                t = inTail;
            }
        }
    }

    private void packet(int slot, byte[] b, int n) {
        Ip4.Pkt p = pkt;
        if (!Ip4.parse(b, 0, n, p) || p.src != FastProto.VPN_ADDR) {
            dropped++;
            return;
        }
        if (p.fragment) {
            dropped++; // a fragment: apps do not send them through a 10,000-byte interface; the sender's own timers will notice
            return;
        }
        if (p.proto == Ip4.TCP) {
            tcp(p, slotBB[slot]);
        } else if (p.proto == Ip4.UDP) {
            udpOut(p);
        } else {
            dropped++; // ping and the rest: not carried
        }
    }

    // ------------------------------------------------------------------------------------------- TCP: one connection

    private final class Conn {
        final long id;
        final int sport;
        final int dst;
        final int dport;
        SocketChannel ch;
        SelectionKey sk;
        int state = CONNECTING;
        byte[] hello;
        int helloSent;
        // from the app (to the phone)
        int rcvNxt;
        final Ring up = new Ring(UP_MAX);
        boolean appFin;
        boolean upShut;
        int ourWs;
        /** The right edge of the window in our last ACK: a window update is sent when it can move on a good deal. */
        int advEdge;
        boolean ackNow;
        boolean inAckList;
        // to the app (from the phone)
        int iss;
        int sndUna;
        int sndNxt;
        int appWnd;
        int appWs;
        int appMss;
        final Ring down = new Ring(DOWN_MAX);
        boolean relayEof;
        boolean finSent;
        boolean finAcked;
        int dupAcks;
        // the one timer
        long timerAt;
        int timerKind;
        int rto = RTO_MIN;
        int retries;
        long lastSeen;

        Conn(long id, int sport, int dst, int dport) {
            this.id = id;
            this.sport = sport;
            this.dst = dst;
            this.dport = dport;
        }

        /** The window we offer the app, in bytes. */
        int window() {
            return up.room();
        }

        /** Bytes after sndNxt that are waiting in down (sent ones are kept until the app acknowledges them). */
        int unsent() {
            return down.size() - (sndNxt - sndUna - (finSent ? 1 : 0));
        }
    }

    private static long id(int sport, int dst, int dport) {
        return ((long) sport << 48) | ((dst & 0xFFFFFFFFL) << 16) | dport;
    }

    private static boolean after(int a, int b) {
        return a - b > 0;
    }

    private void tcp(Ip4.Pkt p, ByteBuffer bb) {
        if (p.dst == FastProto.VPN_DNS) {
            rstFor(p); // DNS over TLS or TCP to our DNS address: refused at once, so Android falls back to plain DNS without waiting
            return;
        }
        long id = id(p.sport, p.dst, p.dport);
        Conn c = conns.get(id);
        if (c == null) {
            if ((p.flags & (Ip4.SYN | Ip4.ACK | Ip4.RST | Ip4.FIN)) == Ip4.SYN) {
                open(id, p);
            } else if ((p.flags & Ip4.RST) == 0) {
                rstFor(p); // a connection we do not know (it ended, or the VPN restarted): tell the app at once
            }
            return;
        }
        c.lastSeen = System.currentTimeMillis();
        if ((p.flags & Ip4.RST) != 0) {
            abort(c, false); // the app gave up: the phone's side goes too
            return;
        }
        if (c.state == CONNECTING) {
            return; // the app repeats its SYN while the phone is still reaching the server
        }
        if (c.state == SYN_RCVD) {
            if ((p.flags & Ip4.SYN) != 0) {
                if ((p.flags & Ip4.ACK) == 0) {
                    synAck(c); // our answer was lost: say it again
                }
                return;
            }
            if ((p.flags & Ip4.ACK) == 0 || p.ack != c.iss + 1) {
                return;
            }
            c.state = OPEN;
            c.sndUna = c.iss + 1;
            c.sndNxt = c.iss + 1;
            c.appWnd = scaled(c, p.window);
            timer(c, T_IDLE, c.lastSeen + IDLE_MS);
            interest(c);
        }
        if ((p.flags & Ip4.ACK) != 0) {
            ack(c, p);
            if (c.state == CLOSED) {
                return;
            }
        }
        if (p.dataLen > 0 || (p.flags & Ip4.FIN) != 0) {
            data(c, p, bb);
        }
        push(c);
        finish(c);
    }

    private int scaled(Conn c, int w) {
        return c.appWs >= 0 ? w << c.appWs : w;
    }

    private void open(long id, Ip4.Pkt p) {
        InetSocketAddress relay = link.relay();
        if (relay == null || conns.size() >= MAX_CONNS) {
            refused++;
            rstFor(p);
            if (relay == null) {
                trouble("The phone cannot be found on this Wi-Fi.");
            }
            return;
        }
        Conn c = new Conn(id, p.sport, p.dst, p.dport);
        c.rcvNxt = p.seq + 1;
        c.appMss = p.mss > 0 ? Math.min(p.mss, MSS) : 536;
        c.appWs = p.wscale;
        c.ourWs = p.wscale >= 0 ? OUR_WS : -1;
        c.appWnd = p.window;
        c.iss = random.nextInt();
        c.sndUna = c.iss;
        c.sndNxt = c.iss;
        c.lastSeen = System.currentTimeMillis();
        c.hello = FastProto.hello(key, p.dst, p.dport);
        try {
            SocketChannel ch = link.tcp();
            c.ch = ch;
            ch.configureBlocking(false);
            ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
            boolean now = ch.connect(relay);
            c.sk = ch.register(selector, now ? SelectionKey.OP_WRITE : SelectionKey.OP_CONNECT, c);
        } catch (IOException | RuntimeException e) {
            closeQuietly(c);
            refused++;
            rstFor(p);
            relayFailed("The phone's relay could not be reached: " + e.getMessage());
            return;
        }
        conns.put(id, c);
        open = conns.size();
        opened++;
        timer(c, T_CONNECT, c.lastSeen + CONNECT_MS);
    }

    /** Socket readiness for one connection. */
    private void event(Conn c, SelectionKey k) {
        try {
            if (!k.isValid()) {
                return;
            }
            if (k.isConnectable()) {
                if (!c.ch.finishConnect()) {
                    return;
                }
                k.interestOps(SelectionKey.OP_WRITE);
                return; // the hello goes when the socket says it can be written (now, as a rule)
            }
            if (c.hello != null && k.isWritable()) {
                ByteBuffer h = ByteBuffer.wrap(c.hello, c.helloSent, c.hello.length - c.helloSent);
                c.helloSent += c.ch.write(h);
                if (c.helloSent == c.hello.length) {
                    c.hello = null;
                    k.interestOps(SelectionKey.OP_READ); // now the phone's one-byte answer
                }
                return;
            }
            if (c.state == CONNECTING) {
                if (k.isReadable()) {
                    answer(c);
                }
                return;
            }
            if (k.isWritable()) {
                flushUp(c);
            }
            if (c.state != CLOSED && k.isReadable()) {
                readDown(c);
            }
            if (c.state != CLOSED) {
                finish(c);
            }
        } catch (IOException e) {
            if (c.state == CONNECTING) {
                relayFailed("The phone's relay did not answer: " + e.getMessage());
                refuse(c);
            } else {
                abort(c, true); // the server or the phone reset the connection: the app gets a reset too
            }
        }
    }

    /** The phone's one-byte answer: the server was reached (answer the app's SYN), or not (refuse it). */
    private void answer(Conn c) throws IOException {
        one.clear();
        int r = c.ch.read(one);
        if (r == 0) {
            return;
        }
        if (r < 0) {
            relayFailed("The phone's relay closed the connection without an answer.");
            refuse(c);
            return;
        }
        relayWorked();
        int st = one.get(0) & 0xFF;
        if (st != FastProto.OK) {
            if (st == FastProto.DENIED) {
                trouble("The phone refused the tablet. Pair the tablet with the phone again.");
            }
            refuse(c);
            return;
        }
        c.state = SYN_RCVD;
        c.sndNxt = c.iss + 1;
        c.sk.interestOps(0);
        synAck(c);
    }

    private void synAck(Conn c) {
        int w = Math.min(0xFFFF, c.window()); // never scaled in a SYN
        writeTcp(c, c.iss, Ip4.SYN | Ip4.ACK, w, MSS, c.ourWs, 0);
        c.advEdge = c.rcvNxt + w;
        c.ackNow = false;
        if (c.timerKind != T_SYNACK) {
            c.rto = RTO_MIN;
            c.retries = 0;
        }
        timer(c, T_SYNACK, System.currentTimeMillis() + c.rto);
    }

    /** The app acknowledged data (and tells its window). */
    private void ack(Conn c, Ip4.Pkt p) {
        int a = p.ack;
        int wnd = scaled(c, p.window);
        if (after(a, c.sndNxt)) {
            want(c); // acknowledges what was never sent: answer with where we are
            return;
        }
        if (after(a, c.sndUna)) {
            int n = a - c.sndUna;
            if (c.finSent && a == c.sndNxt) {
                c.finAcked = true;
                n--;
            }
            c.down.drop(Math.min(n, c.down.size()));
            c.sndUna = a;
            c.appWnd = wnd;
            c.dupAcks = 0;
            c.retries = 0;
            c.rto = RTO_MIN;
            if (c.sndUna == c.sndNxt) {
                timer(c, T_IDLE, c.lastSeen + IDLE_MS);
            } else {
                timer(c, T_RTX, System.currentTimeMillis() + c.rto);
            }
            interest(c); // room in down again: read from the phone again
        } else if (a == c.sndUna) {
            boolean same = wnd == c.appWnd;
            c.appWnd = wnd;
            if (same && p.dataLen == 0 && (p.flags & (Ip4.SYN | Ip4.FIN)) == 0 && c.sndNxt != c.sndUna) {
                if (++c.dupAcks == 3) {
                    goBack(c); // a segment was lost on the way into the app: send again from the first unacknowledged byte
                }
            }
            if (c.appWnd > 0 && c.timerKind == T_PERSIST) {
                timer(c, c.sndNxt == c.sndUna ? T_IDLE : T_RTX, System.currentTimeMillis() + c.rto);
            }
        }
    }

    /** Data (and maybe FIN) from the app. Only the next bytes in order are taken; a gap waits for the app to send them again. */
    private void data(Conn c, Ip4.Pkt p, ByteBuffer bb) {
        boolean fin = (p.flags & Ip4.FIN) != 0;
        if (c.appFin) {
            want(c); // after its FIN anything is a repeat
            return;
        }
        int skip = c.rcvNxt - p.seq;
        if (skip < 0) {
            want(c); // a gap: say again what we expect
            return;
        }
        int len = p.dataLen - skip;
        if (len < 0 || (len == 0 && !fin)) {
            want(c); // all of it was here already
            return;
        }
        int off = p.data + skip;
        int took = 0;
        if (len > 0) {
            if (c.up.size() == 0 && c.state == OPEN && !c.upShut) {
                // nothing waits before it: hand it straight to the phone's socket, keep only what does not fit
                try {
                    bb.limit(off + len).position(off);
                    took = c.ch.write(bb);
                } catch (IOException e) {
                    abort(c, true);
                    return;
                }
            }
            if (took < len) {
                int rest = Math.min(len - took, c.up.room());
                int grow = c.up.growthFor(c.up.size() + rest);
                if (grow > 0 && allocated + grow > BUDGET) {
                    rest = Math.min(rest, c.up.capacity() - c.up.size()); // over the budget: only what fits now
                } else if (grow > 0) {
                    allocated += grow;
                    c.up.grow(c.up.size() + rest);
                }
                took += c.up.put(p.b, off + took, rest);
            }
            c.rcvNxt += took;
            tcpUp += took;
            if (c.up.size() > 0) {
                interest(c);
            }
        }
        if (fin && took == len) {
            c.rcvNxt += 1;
            c.appFin = true;
            if (c.up.size() == 0) {
                shutUp(c);
            }
        }
        want(c);
    }

    /** Bytes from the phone into down, then on to the app. */
    private void readDown(Conn c) throws IOException {
        for (int i = 0; i < 4 && !c.relayEof; i++) {
            int room = c.down.room();
            if (room <= 0 || !budgetFor(c.down)) {
                break;
            }
            int r = c.down.fill(c.ch, room);
            if (r < 0) {
                c.relayEof = true;
                break;
            }
            if (r == 0) {
                break;
            }
        }
        push(c);
        interest(c);
    }

    /** Sends what the app's window allows, then FIN once the phone has closed its side and everything is out. */
    private void push(Conn c) {
        if (c.state != OPEN) {
            return;
        }
        int inFlight = c.sndNxt - c.sndUna;
        int unsent = c.unsent();
        while (unsent > 0) {
            int left = c.appWnd - inFlight;
            if (left <= 0) {
                if (c.appWnd == 0 && inFlight == 0 && c.timerKind != T_PERSIST) {
                    timer(c, T_PERSIST, System.currentTimeMillis() + c.rto);
                }
                break;
            }
            int n = Math.min(Math.min(unsent, c.appMss), left);
            c.down.get(inFlight, out, Ip4.TCP_HDR, n);
            writeTcp(c, c.sndNxt, Ip4.ACK | (n == unsent ? Ip4.PSH : 0), advertise(c), -1, -1, n);
            c.sndNxt += n;
            inFlight += n;
            unsent -= n;
            tcpDown += n;
        }
        if (c.relayEof && unsent == 0 && !c.finSent) {
            writeTcp(c, c.sndNxt, Ip4.FIN | Ip4.ACK, advertise(c), -1, -1, 0);
            c.finSent = true;
            c.sndNxt += 1;
            inFlight += 1;
        }
        if (inFlight > 0 && c.timerKind != T_RTX && c.timerKind != T_PERSIST) {
            timer(c, T_RTX, System.currentTimeMillis() + c.rto);
        }
    }

    /** Go back to the first byte the app has not acknowledged and send from there again (the FIN too, if it was sent). */
    private void goBack(Conn c) {
        c.dupAcks = 0;
        if (c.finSent && !c.finAcked) {
            c.finSent = false;
        }
        c.sndNxt = c.sndUna;
        push(c);
    }

    /** Bytes waiting for the phone: write what its socket takes. */
    private void flushUp(Conn c) throws IOException {
        if (c.up.size() > 0 && !c.upShut) {
            c.up.drain(c.ch);
        }
        if (c.up.size() == 0 && c.appFin && !c.upShut) {
            shutUp(c);
        }
        interest(c);
        // the window may have opened a good deal: tell the app so it sends again
        int edge = c.rcvNxt + c.window();
        int moved = edge - c.advEdge;
        if (moved >= Math.min(2 * c.appMss, UP_MAX / 4) || (c.advEdge == c.rcvNxt && moved > 0)) {
            want(c);
        }
    }

    private void shutUp(Conn c) {
        if (c.upShut) {
            return;
        }
        c.upShut = true;
        try {
            c.ch.shutdownOutput(); // the app closed its side: the phone closes its side toward the server
        } catch (IOException ignored) {
            // the socket is going anyway
        }
    }

    /** Both sides closed and everything acknowledged: the connection is done. */
    private void finish(Conn c) {
        if (c.state == OPEN && c.appFin && c.upShut && c.finSent && c.finAcked) {
            if (c.ackNow) {
                sendAck(c); // the last ACK, for the app's FIN
            }
            close(c, false);
        }
    }

    /** The phone's answer says no (or never came): the app's SYN is answered with a reset, as a refusing server would. */
    private void refuse(Conn c) {
        refused++;
        writeTcp(c, 0, Ip4.RST | Ip4.ACK, 0, -1, -1, 0);
        close(c, false);
    }

    /** Ends a connection at once: a reset to the app (if asked) and to the phone. */
    private void abort(Conn c, boolean tellApp) {
        if (tellApp && c.state != CLOSED) {
            writeTcp(c, c.sndNxt, Ip4.RST | Ip4.ACK, 0, -1, -1, 0);
        }
        close(c, true);
    }

    private void close(Conn c, boolean reset) {
        if (c.state == CLOSED) {
            return;
        }
        c.state = CLOSED;
        if (reset && c.ch != null) {
            try {
                c.ch.setOption(StandardSocketOptions.SO_LINGER, 0);
            } catch (IOException | RuntimeException ignored) {
                // closing anyway
            }
        }
        closeQuietly(c);
        allocated -= c.up.release() + c.down.release();
        conns.remove(c.id);
        open = conns.size();
        c.timerKind = T_NONE;
        c.timerAt = 0;
    }

    private void closeQuietly(Conn c) {
        try {
            if (c.sk != null) {
                c.sk.cancel();
            }
            if (c.ch != null) {
                c.ch.close();
            }
        } catch (IOException | RuntimeException ignored) {
            // closed
        }
    }

    /** Which socket events this connection waits for now. */
    private void interest(Conn c) {
        if (c.state != OPEN || c.sk == null || !c.sk.isValid()) {
            return;
        }
        int ops = 0;
        if (!c.relayEof && c.down.room() > 0) {
            ops |= SelectionKey.OP_READ;
        }
        if (c.up.size() > 0 && !c.upShut) {
            ops |= SelectionKey.OP_WRITE;
        }
        if (c.sk.interestOps() != ops) {
            c.sk.interestOps(ops);
        }
    }

    /** May this ring grow? Only within the budget for all connections together. */
    private boolean budgetFor(Ring r) {
        int grow = r.growthFor(r.size() + 1);
        if (grow == 0) {
            return true;
        }
        if (allocated + grow > BUDGET) {
            return r.capacity() > r.size(); // no growth: what is already there must do
        }
        allocated += grow;
        r.grow(r.size() + 1);
        return true;
    }

    // ------------------------------------------------------------------------------------------- TCP: writing

    private int advertise(Conn c) {
        int w = Math.max(0, c.window());
        c.advEdge = c.rcvNxt + w;
        return c.ourWs >= 0 ? Math.min(0xFFFF, w >>> c.ourWs) : Math.min(0xFFFF, w);
    }

    /** An ACK is owed: it is sent once at the end of this round, unless a data segment carries it first. */
    private void want(Conn c) {
        c.ackNow = true;
        if (!c.inAckList) {
            c.inAckList = true;
            ackList.add(c);
        }
    }

    private void flushAcks() {
        for (int i = 0; i < ackList.size(); i++) {
            Conn c = ackList.get(i);
            c.inAckList = false;
            if (c.ackNow && c.state != CLOSED) {
                sendAck(c);
            }
        }
        ackList.clear();
    }

    private void sendAck(Conn c) {
        writeTcp(c, c.sndNxt, Ip4.ACK, advertise(c), -1, -1, 0);
    }

    /** A segment to the app (payload already at out[40]). Every segment carries our ACK, so a pending one is no longer owed. */
    private void writeTcp(Conn c, int seq, int flags, int window, int mss, int ws, int len) {
        int n = Ip4.tcp(out, c.dst, c.dport, FastProto.VPN_ADDR, c.sport, seq, c.rcvNxt, flags, window, mss, ws, len);
        if ((flags & Ip4.ACK) != 0) {
            c.ackNow = false;
        }
        write(n);
    }

    /** A reset for a packet that belongs to no connection (RFC 793's rules for its numbers). */
    private void rstFor(Ip4.Pkt p) {
        int n;
        if ((p.flags & Ip4.ACK) != 0) {
            n = Ip4.tcp(out, p.dst, p.dport, p.src, p.sport, p.ack, 0, Ip4.RST, 0, -1, -1, 0);
        } else {
            int seg = p.dataLen + ((p.flags & Ip4.SYN) != 0 ? 1 : 0) + ((p.flags & Ip4.FIN) != 0 ? 1 : 0);
            n = Ip4.tcp(out, p.dst, p.dport, p.src, p.sport, 0, p.seq + seg, Ip4.RST | Ip4.ACK, 0, -1, -1, 0);
        }
        write(n);
    }

    private int writeFails;

    private void write(int n) {
        try {
            tun.write(out, 0, n);
            writeFails = 0;
        } catch (IOException e) {
            // one lost packet is repaired by TCP; a closed interface fails every time and ends the engine
            if (++writeFails > 1000) {
                running = false;
            }
        }
    }

    // ------------------------------------------------------------------------------------------- timers

    private void timer(Conn c, int kind, long at) {
        c.timerKind = kind;
        c.timerAt = at;
        if (at < nextTimer) {
            nextTimer = at;
        }
    }

    private void timers(long now) {
        if (now < nextTimer) {
            return;
        }
        long next = Long.MAX_VALUE;
        List<Conn> due = null;
        for (Conn c : conns.values()) {
            if (c.timerKind == T_NONE) {
                continue;
            }
            if (c.timerAt <= now) {
                if (due == null) {
                    due = new ArrayList<>();
                }
                due.add(c);
            } else if (c.timerAt < next) {
                next = c.timerAt;
            }
        }
        nextTimer = next;
        if (due != null) {
            for (Conn c : due) {
                if (c.state != CLOSED) {
                    fire(c, now);
                }
            }
        }
    }

    private void fire(Conn c, long now) {
        switch (c.timerKind) {
            case T_CONNECT:
                relayFailed("The phone did not reach the server in time.");
                refuse(c);
                break;
            case T_SYNACK:
                if (++c.retries > MAX_RETRIES) {
                    abort(c, true);
                } else {
                    c.rto = Math.min(RTO_MAX, c.rto * 2);
                    synAck(c);
                }
                break;
            case T_RTX:
                if (++c.retries > MAX_RETRIES) {
                    abort(c, true); // the app no longer answers
                } else {
                    c.rto = Math.min(RTO_MAX, c.rto * 2);
                    timer(c, T_RTX, now + c.rto);
                    goBack(c);
                }
                break;
            case T_PERSIST:
                if (c.appWnd == 0 && c.unsent() > 0) {
                    // a zero-window probe: an old sequence number makes the app answer with its window now
                    writeTcp(c, c.sndUna - 1, Ip4.ACK, advertise(c), -1, -1, 0);
                    c.rto = Math.min(RTO_MAX, c.rto * 2);
                    timer(c, T_PERSIST, now + c.rto);
                } else {
                    timer(c, T_IDLE, c.lastSeen + IDLE_MS);
                    push(c);
                }
                break;
            case T_IDLE:
                if (now - c.lastSeen >= IDLE_MS) {
                    abort(c, true);
                } else {
                    timer(c, T_IDLE, c.lastSeen + IDLE_MS);
                }
                break;
            default:
                c.timerKind = T_NONE;
                break;
        }
    }

    private void relayFailed(String why) {
        if (++relayFails >= TROUBLE_AFTER) {
            trouble(why);
        }
    }

    private void relayWorked() {
        relayFails = 0;
        if (inTrouble) {
            inTrouble = false;
            events.relayFine();
        }
    }

    private void trouble(String why) {
        inTrouble = true;
        events.relayTrouble(why);
    }

    // ------------------------------------------------------------------------------------------- UDP

    private void udpOut(Ip4.Pkt p) {
        int addr = p.dst;
        int port = p.dport;
        if (p.dst == FastProto.VPN_DNS) {
            if (p.dport != 53) {
                icmpBack(p);
                return;
            }
            addr = 0; // "answer with the phone's resolver"
        } else if (port == 443 && blockQuic) {
            icmpBack(p); // QUIC: refused at once, the app uses TCP
            return;
        } else if (!FastProto.publicAddress(addr)) {
            dropped++; // broadcast, multicast, a private network: not for the phone
            return;
        }
        InetSocketAddress relay = link.relay();
        if (relay == null) {
            dropped++;
            return;
        }
        try {
            if (udp == null || !relay.equals(udpTo)) {
                openUdp(relay);
            }
            FastProto.upHeader(udpBuf, key, p.sport, addr, port);
            System.arraycopy(p.b, p.data, udpBuf, FastProto.UP_HDR, p.dataLen);
            udpBB.clear();
            udpBB.limit(FastProto.UP_HDR + p.dataLen);
            udp.write(udpBB);
            udpUp += p.dataLen;
        } catch (IOException | RuntimeException e) {
            dropped++;
            closeUdp(); // opened again for the next datagram
        }
    }

    private void openUdp(InetSocketAddress relay) throws IOException {
        closeUdp();
        DatagramChannel ch = link.udp();
        ch.configureBlocking(false);
        ch.connect(relay);
        udp = ch;
        udpTo = relay;
        udpKey = ch.register(selector, SelectionKey.OP_READ);
    }

    private void closeUdp() {
        try {
            if (udpKey != null) {
                udpKey.cancel();
            }
            if (udp != null) {
                udp.close();
            }
        } catch (IOException | RuntimeException ignored) {
            // closed
        }
        udp = null;
        udpKey = null;
        udpTo = null;
    }

    private void udpReadable() {
        for (int i = 0; i < 64 && udp != null; i++) {
            int n;
            try {
                udpBB.clear();
                n = udp.read(udpBB);
            } catch (IOException e) {
                closeUdp(); // the phone is gone from this address (the port is unreachable): opened again when needed
                return;
            }
            if (n <= 0) {
                return;
            }
            if (n < FastProto.DOWN_HDR) {
                continue;
            }
            int flow = Ip4.u16(udpBuf, 0);
            int addr = Ip4.i32(udpBuf, 2);
            int port = Ip4.u16(udpBuf, 6);
            int len = n - FastProto.DOWN_HDR;
            if (len > FastProto.MTU - Ip4.UDP_HDR) {
                dropped++;
                continue;
            }
            int src = FastProto.isDnsAsk(addr, port) ? FastProto.VPN_DNS : addr;
            System.arraycopy(udpBuf, FastProto.DOWN_HDR, out, Ip4.UDP_HDR, len);
            write(Ip4.udp(out, src, port, FastProto.VPN_ADDR, flow, len));
            udpDown += len;
        }
    }

    private void icmpBack(Ip4.Pkt p) {
        write(Ip4.unreachable(out, p.dst, p, 3));
    }

    // ------------------------------------------------------------------------------------------- the end

    private void closeAll() {
        for (Conn c : new ArrayList<>(conns.values())) {
            close(c, true);
        }
        closeUdp();
        try {
            selector.close();
        } catch (IOException ignored) {
            // closed
        }
        open = 0;
    }

    // ------------------------------------------------------------------------------------------- the ring buffer

    /**
     * Bytes waiting in one direction of one connection: a circular buffer that grows (16 KB, 64 KB, then its maximum) only when a
     * connection really has that much in flight, so the hundreds of quiet connections of a browser cost almost nothing.
     */
    static final class Ring {
        private final int max;
        private byte[] a;
        private ByteBuffer bb;
        private int head;
        private int size;

        Ring(int max) {
            this.max = max;
        }

        int size() {
            return size;
        }

        int capacity() {
            return a == null ? 0 : a.length;
        }

        /** How many more bytes may come in (it grows to make room). */
        int room() {
            return max - size;
        }

        /** How many bytes the array must grow by to hold need bytes (0: it already can). */
        int growthFor(int need) {
            int cap = capacity();
            if (need <= cap) {
                return 0;
            }
            return target(need) - cap;
        }

        private int target(int need) {
            int t = capacity() == 0 ? 16 * 1024 : capacity();
            while (t < need && t < max) {
                t = Math.min(max, t * 4);
            }
            return t;
        }

        void grow(int need) {
            if (need <= capacity()) {
                return;
            }
            byte[] n = new byte[target(need)];
            if (a != null && size > 0) {
                get(0, n, 0, size);
            }
            a = n;
            bb = ByteBuffer.wrap(a);
            head = 0;
        }

        /** Copies in as much of src as fits in the array as it is (the caller grows it first, within the budget). */
        int put(byte[] src, int off, int len) {
            int take = Math.min(len, capacity() - size);
            if (take <= 0) {
                return 0;
            }
            int tail = (head + size) % a.length;
            int first = Math.min(take, a.length - tail);
            System.arraycopy(src, off, a, tail, first);
            if (take > first) {
                System.arraycopy(src, off + first, a, 0, take - first);
            }
            size += take;
            return take;
        }

        /** Reads from the channel into the free space, up to limit bytes (one read). Returns what read() said. */
        int fill(SocketChannel ch, int limit) throws IOException {
            if (a == null || size == a.length) {
                return 0; // the caller grows it first, within the budget
            }
            int tail = (head + size) % a.length;
            int contiguous = Math.min(a.length - size, a.length - tail);
            int n = Math.min(contiguous, limit);
            if (n <= 0) {
                return 0;
            }
            bb.limit(tail + n).position(tail);
            int r = ch.read(bb);
            if (r > 0) {
                size += r;
            }
            return r;
        }

        /** Copies len bytes starting at logical position from into dst. */
        void get(int from, byte[] dst, int off, int len) {
            int start = (head + from) % a.length;
            int first = Math.min(len, a.length - start);
            System.arraycopy(a, start, dst, off, first);
            if (len > first) {
                System.arraycopy(a, 0, dst, off + first, len - first);
            }
        }

        void drop(int n) {
            if (n <= 0) {
                return;
            }
            size -= n;
            head = size == 0 ? 0 : (head + n) % a.length;
        }

        /** Writes from the front into the channel, as much as it takes; what was written is dropped. */
        int drain(SocketChannel ch) throws IOException {
            int total = 0;
            while (size > 0) {
                int first = Math.min(size, a.length - head);
                bb.limit(head + first).position(head);
                int w = ch.write(bb);
                if (w <= 0) {
                    break;
                }
                drop(w);
                total += w;
                if (w < first) {
                    break;
                }
            }
            return total;
        }

        /** Lets the array go; returns how many bytes it held (for the budget). */
        int release() {
            int c = capacity();
            a = null;
            bb = null;
            head = 0;
            size = 0;
            return c;
        }
    }
}
