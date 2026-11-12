package com.tj.linkmeter;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * No Android in here (tested by tools/test/FastRelayTest.java over real sockets, and end to end with the tablet's engine on a real
 * Linux tun by tools/fast_e2e.sh). The phone's side of Fast Wi-Fi (docs/FAST_WIFI.md): the paired tablet asks for a connection to an
 * internet address, and the phone opens it ITSELF, so the carrier sees the phone's own traffic, then passes the bytes both ways. UDP
 * the same way, one socket per flow of the tablet; DNS questions go to the phone's own resolver.
 *
 * Only a paired tablet's key opens it, only from a private address on the phone's Wi-Fi side, and it only ever connects to public
 * internet addresses (never to the phone itself or a network it is on).
 *
 * ONE thread does everything and sleeps in the selector when nothing happens. {@link Busy} is told when relayed traffic starts and
 * when it has been quiet for {@link #QUIET_MS}, so the Android side can hold a wake lock only while it is really needed.
 */
final class FastRelayCore {
    /** The keys of the tablets paired now (asked for every new connection and datagram, so a forgotten tablet is out at once). */
    interface Keys {
        List<byte[]> keys();
    }

    /** The phone's own resolver. answer may be called on any thread, once, with the reply (null: no answer). */
    interface Dns {
        void ask(byte[] query, Answer answer);
    }

    interface Answer {
        void done(byte[] reply);
    }

    /** Where a connection for addr:port really goes (the address itself on the phone; a local test server in the tests). */
    interface Dialer {
        InetSocketAddress target(int addr, int port);

        /** The address the tablet knows for an answer that came from real (the same address on the phone). */
        default int back(int real) {
            return real;
        }
    }

    /** Relayed traffic began (true) or has been quiet a while (false). Called on the relay's thread: return at once. */
    interface Busy {
        void busy(boolean on);
    }

    static final int BUF = 64 * 1024;
    static final long HELLO_MS = 10_000L;
    static final long CONNECT_MS = 15_000L;
    static final long QUIET_MS = 30_000L;
    static final long FLOW_IDLE_MS = 120_000L;
    static final int MAX_PAIRS = 1024;
    static final int MAX_FLOWS = 512;

    private static final int HELLO = 0;
    private static final int CONNECTING = 1;
    private static final int OPEN = 2;
    private static final int DONE = 3;

    private final int port;
    private final Keys keys;
    private final Dns dns;
    private final Dialer dialer;
    private final Busy busy;
    private Selector selector;
    private ServerSocketChannel server;
    private DatagramChannel udp;
    private SelectionKey udpKey;
    private volatile boolean running;
    private Thread thread;

    private final List<Pair> pending = new ArrayList<>();
    private final Map<Long, Flow> flows = new HashMap<>();
    private final ConcurrentLinkedQueue<Object[]> dnsDone = new ConcurrentLinkedQueue<>();
    private final ArrayDeque<ByteBuffer> pool = new ArrayDeque<>();
    private final ByteBuffer dgram = ByteBuffer.allocateDirect(65536);
    private final byte[] dgramArr = new byte[65536];
    private final int[] target = new int[2];
    /** Tests only: the tablet's engine talks to the relay over the loopback address there. Never set on the phone. */
    boolean loopbackOk;
    private boolean busyOn;
    private long lastTraffic;
    private long nextFlowSweep = Long.MAX_VALUE;
    private int pairCount;

    /** For the phone's screen and status (only the relay's thread writes them). */
    volatile long bytesUp;
    volatile long bytesDown;
    volatile int pairs;
    volatile int flowCount;
    volatile long accepted;
    volatile long denied;
    volatile long lastTabletAt;
    volatile String lastTablet = "";

    FastRelayCore(int port, Keys keys, Dns dns, Dialer dialer, Busy busy) {
        this.port = port;
        this.keys = keys;
        this.dns = dns;
        this.dialer = dialer;
        this.busy = busy;
    }

    /** Opens the TCP and UDP ports on every interface and starts the thread. Throws when a port cannot be had. */
    synchronized void start() throws IOException {
        if (running) {
            return;
        }
        Selector sel = Selector.open();
        ServerSocketChannel ss = ServerSocketChannel.open();
        DatagramChannel u = DatagramChannel.open();
        try {
            ss.socket().setReuseAddress(true);
            ss.bind(new InetSocketAddress(port), 64);
            ss.configureBlocking(false);
            ss.register(sel, SelectionKey.OP_ACCEPT);
            u.socket().setReuseAddress(true);
            u.bind(new InetSocketAddress(port));
            u.configureBlocking(false);
            udpKey = u.register(sel, SelectionKey.OP_READ);
        } catch (IOException | RuntimeException e) {
            ss.close();
            u.close();
            sel.close();
            throw e;
        }
        selector = sel;
        server = ss;
        udp = u;
        running = true;
        thread = new Thread(this::run, "fast-relay");
        thread.start();
    }

    void stop() {
        running = false;
        Selector s = selector;
        if (s != null) {
            try {
                s.wakeup();
            } catch (RuntimeException ignored) {
                // closed
            }
        }
    }

    boolean running() {
        return running;
    }

    void join(long ms) throws InterruptedException {
        Thread t = thread;
        if (t != null) {
            t.join(ms);
        }
    }

    // ------------------------------------------------------------------------------------------- the loop

    private void run() {
        try {
            while (running) {
                long now = System.currentTimeMillis();
                long wake = Long.MAX_VALUE;
                for (Pair p : pending) {
                    wake = Math.min(wake, p.deadline);
                }
                wake = Math.min(wake, nextFlowSweep);
                if (busyOn) {
                    wake = Math.min(wake, lastTraffic + QUIET_MS);
                }
                if (wake == Long.MAX_VALUE) {
                    selector.select();
                } else {
                    selector.select(Math.max(1, wake - now));
                }
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey k = it.next();
                    it.remove();
                    if (!k.isValid()) {
                        continue;
                    }
                    Object a = k.attachment();
                    if (k.isAcceptable()) {
                        accept();
                    } else if (k == udpKey) {
                        udpIn();
                    } else if (a instanceof Pair) {
                        pairEvent((Pair) a, k);
                    } else if (a instanceof Flow) {
                        flowIn((Flow) a);
                    }
                }
                dnsReplies();
                now = System.currentTimeMillis();
                deadlines(now);
                if (now >= nextFlowSweep) {
                    sweepFlows(now);
                }
                if (busyOn && now - lastTraffic >= QUIET_MS) {
                    busyOn = false;
                    busy.busy(false);
                }
            }
        } catch (IOException | ClosedSelectorException e) {
            // the selector broke: stop
        } catch (RuntimeException e) {
            // a bug must not keep the ports half alive: stop, the service sees it
        } finally {
            running = false;
            shutdown();
        }
    }

    private void traffic() {
        lastTraffic = System.currentTimeMillis();
        if (!busyOn) {
            busyOn = true;
            busy.busy(true);
        }
    }

    // ------------------------------------------------------------------------------------------- TCP

    private final class Pair {
        final SocketChannel t;
        SocketChannel s;
        SelectionKey tk;
        SelectionKey sk;
        int state = HELLO;
        final ByteBuffer hello = ByteBuffer.allocate(FastProto.HELLO_LEN);
        ByteBuffer t2s;
        ByteBuffer s2t;
        boolean tEof;
        boolean sEof;
        boolean tShut;
        boolean sShut;
        long deadline;

        Pair(SocketChannel t) {
            this.t = t;
        }
    }

    private void accept() {
        for (int i = 0; i < 16; i++) {
            SocketChannel c;
            try {
                c = server.accept();
            } catch (IOException e) {
                return;
            }
            if (c == null) {
                return;
            }
            try {
                InetSocketAddress ra = (InetSocketAddress) c.getRemoteAddress();
                InetSocketAddress la = (InetSocketAddress) c.getLocalAddress();
                if (!allowed(ra.getAddress()) || !allowed(la.getAddress()) || pairCount >= MAX_PAIRS) {
                    denied++;
                    c.close(); // not from the phone's own Wi-Fi side: no word at all
                    continue;
                }
                c.configureBlocking(false);
                c.setOption(StandardSocketOptions.TCP_NODELAY, true);
                Pair p = new Pair(c);
                p.deadline = System.currentTimeMillis() + HELLO_MS;
                p.tk = c.register(selector, SelectionKey.OP_READ, p);
                pending.add(p);
                pairCount++;
                pairs = pairCount;
                lastTablet = ra.getAddress().getHostAddress();
                lastTabletAt = System.currentTimeMillis();
                traffic();
            } catch (IOException | RuntimeException e) {
                closeQuietly(c);
            }
        }
    }

    private boolean allowed(InetAddress a) {
        return lanSide(a) || (loopbackOk && a != null && a.isLoopbackAddress());
    }

    /** A private IPv4 address: the tablet on the hotspot or Wi-Fi Direct, never someone on the carrier's side. */
    static boolean lanSide(InetAddress a) {
        if (!(a instanceof Inet4Address)) {
            return false;
        }
        byte[] b = a.getAddress();
        return FastProto.privateAddress(Ip4.i32(b, 0));
    }

    private void pairEvent(Pair p, SelectionKey k) {
        try {
            if (p.state == HELLO) {
                readHello(p);
            } else if (p.state == CONNECTING) {
                if (k == p.sk && k.isConnectable()) {
                    connected(p);
                }
            } else if (p.state == OPEN) {
                if (k.isReadable()) {
                    if (k == p.tk) {
                        pump(p, true);
                    } else {
                        pump(p, false);
                    }
                }
                if (p.state == OPEN && k.isValid() && k.isWritable()) {
                    if (k == p.tk) {
                        flush(p, false);
                    } else {
                        flush(p, true);
                    }
                }
                if (p.state == OPEN) {
                    ops(p);
                    maybeDone(p);
                }
            }
        } catch (IOException e) {
            if (p.state == CONNECTING) {
                fail(p, e instanceof ConnectException ? FastProto.REFUSED : FastProto.UNREACHABLE);
            } else {
                reset(p);
            }
        }
    }

    private void readHello(Pair p) throws IOException {
        int r = p.t.read(p.hello);
        if (r < 0) {
            drop(p);
            return;
        }
        if (p.hello.hasRemaining()) {
            return;
        }
        int verdict = FastProto.readHello(p.hello.array(), keys.keys(), target);
        if (verdict != FastProto.OK) {
            denied++;
            fail(p, FastProto.DENIED);
            return;
        }
        accepted++;
        p.state = CONNECTING;
        p.deadline = System.currentTimeMillis() + CONNECT_MS;
        p.tk.interestOps(0);
        SocketChannel s = SocketChannel.open();
        p.s = s;
        s.configureBlocking(false);
        s.setOption(StandardSocketOptions.TCP_NODELAY, true);
        boolean now = s.connect(dialer.target(target[0], target[1]));
        p.sk = s.register(selector, SelectionKey.OP_CONNECT, p);
        if (now) {
            connected(p);
        }
    }

    private void connected(Pair p) throws IOException {
        if (!p.s.finishConnect()) {
            return;
        }
        pending.remove(p);
        p.state = OPEN;
        p.t2s = buffer();
        p.s2t = buffer();
        p.s2t.put((byte) FastProto.OK); // the answer goes first, through the same path as the bytes after it
        flush(p, false);
        if (p.state == OPEN) {
            ops(p);
        }
    }

    /** Reads what one side sent into its buffer and writes on at once what the other side takes. */
    private void pump(Pair p, boolean fromTablet) throws IOException {
        SocketChannel from = fromTablet ? p.t : p.s;
        ByteBuffer buf = fromTablet ? p.t2s : p.s2t;
        if (!buf.hasRemaining() || (fromTablet ? p.tEof : p.sEof)) {
            return;
        }
        int r = from.read(buf);
        if (r < 0) {
            if (fromTablet) {
                p.tEof = true;
            } else {
                p.sEof = true;
            }
        } else if (r > 0) {
            if (fromTablet) {
                bytesUp += r;
            } else {
                bytesDown += r;
            }
            traffic();
        }
        flush(p, fromTablet);
    }

    /** Writes the buffered bytes of one direction on; once that side ended and all is written, ends the other side's writing. */
    private void flush(Pair p, boolean towardServer) throws IOException {
        ByteBuffer buf = towardServer ? p.t2s : p.s2t;
        SocketChannel to = towardServer ? p.s : p.t;
        if (buf.position() > 0) {
            buf.flip();
            to.write(buf);
            buf.compact();
        }
        if (buf.position() == 0) {
            if (towardServer && p.tEof && !p.sShut) {
                p.sShut = true;
                p.s.shutdownOutput(); // the tablet's app closed its side: so does the phone toward the server
            } else if (!towardServer && p.sEof && !p.tShut) {
                p.tShut = true;
                p.t.shutdownOutput(); // the server closed its side: the tablet hears it and closes the app's side
            }
        }
    }

    private void ops(Pair p) {
        int to = 0;
        int so = 0;
        if (!p.tEof && p.t2s.hasRemaining()) {
            to |= SelectionKey.OP_READ;
        }
        if (p.s2t.position() > 0) {
            to |= SelectionKey.OP_WRITE;
        }
        if (!p.sEof && p.s2t.hasRemaining()) {
            so |= SelectionKey.OP_READ;
        }
        if (p.t2s.position() > 0) {
            so |= SelectionKey.OP_WRITE;
        }
        if (p.tk.isValid() && p.tk.interestOps() != to) {
            p.tk.interestOps(to);
        }
        if (p.sk.isValid() && p.sk.interestOps() != so) {
            p.sk.interestOps(so);
        }
    }

    private void maybeDone(Pair p) {
        if (p.tShut && p.sShut) {
            finish(p, false);
        }
    }

    /** The answer could not be "connected": say why in the one byte, then close. */
    private void fail(Pair p, int code) {
        try {
            if (p.t.isOpen()) {
                p.t.write(ByteBuffer.wrap(new byte[] {(byte) code})); // one byte into an empty send buffer: always taken at once
            }
        } catch (IOException | RuntimeException ignored) {
            // the tablet is gone
        }
        finish(p, false);
    }

    /** One side failed: both ends get a reset, so the app on the tablet sees exactly that. */
    private void reset(Pair p) {
        finish(p, true);
    }

    private void drop(Pair p) {
        finish(p, false);
    }

    private void finish(Pair p, boolean rst) {
        if (p.state == DONE) {
            return;
        }
        p.state = DONE;
        pending.remove(p);
        for (SocketChannel c : new SocketChannel[] {p.t, p.s}) {
            if (c == null) {
                continue;
            }
            if (rst) {
                try {
                    c.setOption(StandardSocketOptions.SO_LINGER, 0);
                } catch (IOException | RuntimeException ignored) {
                    // closing anyway
                }
            }
            closeQuietly(c);
        }
        if (p.tk != null) {
            p.tk.cancel();
        }
        if (p.sk != null) {
            p.sk.cancel();
        }
        recycle(p.t2s);
        recycle(p.s2t);
        p.t2s = null;
        p.s2t = null;
        pairCount--;
        pairs = pairCount;
    }

    private void deadlines(long now) {
        for (int i = pending.size() - 1; i >= 0; i--) {
            Pair p = pending.get(i);
            if (now >= p.deadline) {
                if (p.state == HELLO) {
                    drop(p); // said nothing in time
                } else {
                    fail(p, FastProto.UNREACHABLE);
                }
            }
        }
    }

    private ByteBuffer buffer() {
        ByteBuffer b = pool.poll();
        if (b == null) {
            b = ByteBuffer.allocateDirect(BUF);
        }
        b.clear();
        return b;
    }

    private void recycle(ByteBuffer b) {
        if (b != null && pool.size() < 64) {
            pool.push(b);
        }
    }

    // ------------------------------------------------------------------------------------------- UDP

    private final class Flow {
        final long id;
        final SocketAddress tablet;
        final int flow;
        final DatagramChannel ch;
        SelectionKey key;
        long last;

        Flow(long id, SocketAddress tablet, int flow, DatagramChannel ch) {
            this.id = id;
            this.tablet = tablet;
            this.flow = flow;
            this.ch = ch;
        }
    }

    private void udpIn() {
        for (int i = 0; i < 64; i++) {
            SocketAddress from;
            dgram.clear();
            try {
                from = udp.receive(dgram);
            } catch (IOException e) {
                return;
            }
            if (from == null) {
                return;
            }
            dgram.flip();
            int n = dgram.remaining();
            if (n < FastProto.UP_HDR || !(from instanceof InetSocketAddress) || !allowed(((InetSocketAddress) from).getAddress())) {
                denied++;
                continue;
            }
            dgram.get(dgramArr, 0, n);
            if (!FastProto.knownKey(keys.keys(), dgramArr, 0, FastProto.UDP_KEY_LEN)) {
                denied++;
                continue;
            }
            int flow = Ip4.u16(dgramArr, 8);
            int addr = Ip4.i32(dgramArr, 10);
            int dport = Ip4.u16(dgramArr, 14);
            int len = n - FastProto.UP_HDR;
            bytesUp += len;
            traffic();
            if (FastProto.isDnsAsk(addr, dport)) {
                byte[] q = new byte[len];
                System.arraycopy(dgramArr, FastProto.UP_HDR, q, 0, len);
                askDns(q, from, flow);
                continue;
            }
            if (!FastProto.publicAddress(addr) || dport == 0) {
                denied++;
                continue;
            }
            long id = flowId((InetSocketAddress) from, flow);
            Flow f = flows.get(id);
            try {
                if (f == null) {
                    if (flows.size() >= MAX_FLOWS) {
                        continue;
                    }
                    DatagramChannel ch = DatagramChannel.open();
                    ch.configureBlocking(false);
                    f = new Flow(id, from, flow, ch);
                    f.key = ch.register(selector, SelectionKey.OP_READ, f);
                    flows.put(id, f);
                    flowCount = flows.size();
                    if (nextFlowSweep == Long.MAX_VALUE) {
                        nextFlowSweep = System.currentTimeMillis() + FLOW_IDLE_MS / 4;
                    }
                }
                f.last = System.currentTimeMillis();
                f.ch.send(ByteBuffer.wrap(dgramArr, FastProto.UP_HDR, len), dialer.target(addr, dport));
            } catch (IOException | RuntimeException e) {
                if (f != null) {
                    closeFlow(f);
                }
            }
        }
    }

    private static long flowId(InetSocketAddress tablet, int flow) {
        byte[] a = tablet.getAddress().getAddress();
        return ((long) Ip4.i32(a, 0) << 32) | ((long) (tablet.getPort() & 0xFFFF) << 16) | flow;
    }

    /** An answer from the internet for one flow: back to the tablet with where it came from. */
    private void flowIn(Flow f) {
        for (int i = 0; i < 32; i++) {
            SocketAddress from;
            dgram.clear();
            dgram.position(FastProto.DOWN_HDR);
            try {
                from = f.ch.receive(dgram);
            } catch (IOException e) {
                closeFlow(f);
                return;
            }
            if (!(from instanceof InetSocketAddress)) {
                return;
            }
            InetAddress ra = ((InetSocketAddress) from).getAddress();
            if (!(ra instanceof Inet4Address)) {
                continue;
            }
            int len = dgram.position() - FastProto.DOWN_HDR;
            dgram.flip();
            dgram.get(dgramArr, 0, dgram.limit());
            FastProto.downHeader(dgramArr, 0, f.flow, dialer.back(Ip4.i32(ra.getAddress(), 0)), ((InetSocketAddress) from).getPort());
            f.last = System.currentTimeMillis();
            bytesDown += len;
            traffic();
            try {
                udp.send(ByteBuffer.wrap(dgramArr, 0, FastProto.DOWN_HDR + len), f.tablet);
            } catch (IOException ignored) {
                // the tablet is not there now: UDP may lose a datagram
            }
        }
    }

    private void askDns(byte[] q, SocketAddress tablet, int flow) {
        final int id = q.length >= 2 ? Ip4.u16(q, 0) : 0;
        try {
            dns.ask(q, reply -> {
                if (reply != null && reply.length >= 2) {
                    Ip4.w16(reply, 0, id); // the answer carries the question's number, whatever the resolver did with it
                }
                dnsDone.add(new Object[] {reply, tablet, flow});
                Selector s = selector;
                if (s != null) {
                    s.wakeup();
                }
            });
        } catch (RuntimeException e) {
            // no resolver: the tablet's resolver asks again by itself
        }
    }

    private void dnsReplies() {
        Object[] r;
        while ((r = dnsDone.poll()) != null) {
            byte[] reply = (byte[]) r[0];
            if (reply == null || reply.length + FastProto.DOWN_HDR > dgramArr.length) {
                continue;
            }
            FastProto.downHeader(dgramArr, 0, (Integer) r[2], 0, 53);
            System.arraycopy(reply, 0, dgramArr, FastProto.DOWN_HDR, reply.length);
            bytesDown += reply.length;
            try {
                udp.send(ByteBuffer.wrap(dgramArr, 0, FastProto.DOWN_HDR + reply.length), (SocketAddress) r[1]);
            } catch (IOException ignored) {
                // lost: the tablet asks again
            }
        }
    }

    private void sweepFlows(long now) {
        for (Flow f : new ArrayList<>(flows.values())) {
            if (now - f.last >= FLOW_IDLE_MS) {
                closeFlow(f);
            }
        }
        nextFlowSweep = flows.isEmpty() ? Long.MAX_VALUE : now + FLOW_IDLE_MS / 4;
    }

    private void closeFlow(Flow f) {
        flows.remove(f.id);
        flowCount = flows.size();
        if (f.key != null) {
            f.key.cancel();
        }
        try {
            f.ch.close();
        } catch (IOException ignored) {
            // closed
        }
    }

    // ------------------------------------------------------------------------------------------- the end

    private void shutdown() {
        try {
            for (SelectionKey k : new ArrayList<>(selector.keys())) {
                Object a = k.attachment();
                if (a instanceof Pair) {
                    finish((Pair) a, true);
                } else if (a instanceof Flow) {
                    closeFlow((Flow) a);
                }
            }
        } catch (RuntimeException ignored) {
            // the selector is already closed
        }
        closeQuietly(server);
        closeQuietly(udp);
        try {
            selector.close();
        } catch (IOException ignored) {
            // closed
        }
        if (busyOn) {
            busyOn = false;
            busy.busy(false);
        }
        pairs = 0;
        flowCount = 0;
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (IOException ignored) {
            // closed
        }
    }
}
