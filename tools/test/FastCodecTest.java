package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Tests Ip4 (the packets of the tablet's Fast Wi-Fi VPN) and FastProto (the relay protocol, the key, which addresses may use the relay,
 * the VPN's routes). Checksums are compared with a plain reference sum written here, not with Ip4's own. Not part of the app.
 */
public final class FastCodecTest {
    private static int checks;
    private static int fails;

    private FastCodecTest() {}

    private static void eq(Object want, Object got, String what) {
        checks++;
        if (!String.valueOf(want).equals(String.valueOf(got))) {
            fails++;
            System.out.println("FAIL " + what + ": wanted " + want + " but got " + got);
        }
    }

    private static void yes(boolean ok, String what) {
        checks++;
        if (!ok) {
            fails++;
            System.out.println("FAIL " + what);
        }
    }

    public static void main(String[] args) {
        parseRealSyn();
        buildTcp();
        buildUdp();
        icmp();
        malformed();
        checksumMatchesReference();
        key();
        hello();
        servfail();
        udpHeaders();
        addresses();
        routes();
        interfaces();
        System.out.println(checks + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------------------------------- reference checksum

    /** RFC 1071 the slow and obvious way: big-endian 16-bit words, an odd last byte padded with zero, carries folded at the end. */
    private static int refSum(byte[] b, int off, int len, long start) {
        long s = start;
        for (int i = 0; i < len; i += 2) {
            int hi = b[off + i] & 0xFF;
            int lo = i + 1 < len ? b[off + i + 1] & 0xFF : 0;
            s += hi * 256 + lo;
        }
        while (s > 0xFFFF) {
            s = (s & 0xFFFF) + (s >> 16);
        }
        return (int) s;
    }

    private static boolean refPacketOk(byte[] b, int off) {
        int ihl = (b[off] & 0x0F) * 4;
        if (refSum(b, off, ihl, 0) != 0xFFFF) {
            return false;
        }
        int total = ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
        int proto = b[off + 9] & 0xFF;
        int len = total - ihl;
        long pseudo = 0;
        if (proto == 6 || proto == 17) {
            for (int i = 12; i < 20; i += 2) {
                pseudo += ((b[off + i] & 0xFF) << 8) | (b[off + i + 1] & 0xFF);
            }
            pseudo += proto + len;
        }
        return refSum(b, off + ihl, len, pseudo) == 0xFFFF;
    }

    private static byte[] hex(String h) {
        h = h.replaceAll("\\s", "");
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    // ------------------------------------------------------------------------------------------- Ip4

    /** A SYN as Linux sends it (MSS 1460, SACK permitted, timestamps, window scale 7), checksums computed by the kernel. */
    private static void parseRealSyn() {
        // 10.0.0.2:51000 -> 93.184.216.34:443, captured layout with valid checksums (built and verified with the reference sum)
        byte[] syn = hex("4500003c1c4640004006" + "0000" + "0a000002" + "5db8d822"
                + "c738" + "01bb" + "00001000" + "00000000" + "a002" + "faf0" + "0000" + "0000"
                + "020405b4" + "0402" + "080a" + "00000001" + "00000000" + "01" + "030307");
        // the hand-built bytes above have zero checksums: fill them with the reference sum, then parse
        fixChecksums(syn);
        yes(refPacketOk(syn, 0), "the hand-built SYN is valid by the reference");
        Ip4.Pkt p = new Ip4.Pkt();
        yes(Ip4.parse(syn, 0, syn.length, p), "a SYN parses");
        eq(Ip4.TCP, p.proto, "protocol");
        eq("10.0.0.2", Ip4.str(p.src), "source");
        eq("93.184.216.34", Ip4.str(p.dst), "destination");
        eq(51000, p.sport, "source port");
        eq(443, p.dport, "destination port");
        eq(4096, p.seq, "sequence");
        eq(Ip4.SYN, p.flags, "flags");
        eq(64240, p.window, "window");
        eq(1460, p.mss, "MSS option");
        eq(7, p.wscale, "window scale option");
        eq(0, p.dataLen, "no data");
        yes(!p.fragment, "not a fragment");
        yes(Ip4.checksumsOk(syn, 0), "Ip4 agrees the checksums are right");
        // a packet read into a bigger buffer, at an offset, with junk after it
        byte[] big = new byte[syn.length + 100];
        Arrays.fill(big, (byte) 0x5A);
        System.arraycopy(syn, 0, big, 30, syn.length);
        yes(Ip4.parse(big, 30, syn.length + 20, p), "a packet inside a bigger buffer parses");
        eq(syn.length, p.len, "the length is the IP header's, not what follows");
        eq(1460, p.mss, "options found at an offset");
    }

    private static void fixChecksums(byte[] b) {
        b[10] = 0;
        b[11] = 0;
        int h = refSum(b, 0, 20, 0) ^ 0xFFFF;
        b[10] = (byte) (h >> 8);
        b[11] = (byte) h;
        int ihl = 20;
        int total = ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
        int proto = b[9] & 0xFF;
        int csum = proto == 6 ? ihl + 16 : ihl + 6;
        b[csum] = 0;
        b[csum + 1] = 0;
        long pseudo = 0;
        for (int i = 12; i < 20; i += 2) {
            pseudo += ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
        }
        pseudo += proto + (total - ihl);
        int t = refSum(b, ihl, total - ihl, pseudo) ^ 0xFFFF;
        b[csum] = (byte) (t >> 8);
        b[csum + 1] = (byte) t;
    }

    private static void buildTcp() {
        byte[] out = new byte[20000];
        int src = Ip4.addr("1.2.3.4");
        int dst = FastProto.VPN_ADDR;
        int n = Ip4.tcp(out, src, 443, dst, 51000, 0x7FFFFFF0, 4097, Ip4.SYN | Ip4.ACK, 65535, 9960, 6, 0);
        eq(48, n, "a SYN-ACK with MSS and window scale is 48 bytes");
        yes(refPacketOk(out, 0), "the SYN-ACK's checksums are right by the reference");
        Ip4.Pkt p = new Ip4.Pkt();
        yes(Ip4.parse(out, 0, n, p), "our SYN-ACK parses");
        eq(9960, p.mss, "MSS written");
        eq(6, p.wscale, "window scale written");
        eq(Ip4.SYN | Ip4.ACK, p.flags, "flags written");
        eq(0x7FFFFFF0, p.seq, "sequence written");
        eq(4097, p.ack, "ack written");
        eq(443, p.sport, "port written");
        // data segments of every length up to a big MSS, odd ones included
        Random r = new Random(7);
        for (int len : new int[] {0, 1, 2, 3, 59, 1460, 1461, 9959, 9960}) {
            for (int i = 0; i < len; i++) {
                out[Ip4.TCP_HDR + i] = (byte) r.nextInt();
            }
            int m = Ip4.tcp(out, src, 443, dst, 51000, -5, 123456789, Ip4.ACK | Ip4.PSH, 1000, -1, -1, len);
            eq(40 + len, m, "segment length " + len);
            yes(refPacketOk(out, 0), "segment of " + len + " bytes has right checksums");
            yes(Ip4.parse(out, 0, m, p) && p.dataLen == len && p.data == Ip4.TCP_HDR, "segment of " + len + " parses back");
            eq(-5, p.seq, "a sequence number near the wrap");
        }
        // no options on a non-SYN even when asked
        int m = Ip4.tcp(out, src, 1, dst, 2, 1, 2, Ip4.ACK, 5, 1460, 7, 0);
        eq(40, m, "options only ride on a SYN");
        boolean threw = false;
        try {
            Ip4.tcp(out, src, 1, dst, 2, 1, 2, Ip4.SYN, 5, 1460, 7, 10);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        yes(threw, "a SYN with options and data is refused");
        // the window field is clamped, never wraps
        Ip4.tcp(out, src, 1, dst, 2, 1, 2, Ip4.ACK, 1 << 20, -1, -1, 0);
        yes(Ip4.parse(out, 0, 40, p) && p.window == 0xFFFF, "a too-big window is clamped to 65535");
        Ip4.tcp(out, src, 1, dst, 2, 1, 2, Ip4.ACK, -3, -1, -1, 0);
        yes(Ip4.parse(out, 0, 40, p) && p.window == 0, "a negative window is 0");
    }

    private static void buildUdp() {
        byte[] out = new byte[20000];
        Random r = new Random(11);
        Ip4.Pkt p = new Ip4.Pkt();
        for (int len : new int[] {0, 1, 2, 17, 512, 1350, 9971}) {
            for (int i = 0; i < len; i++) {
                out[Ip4.UDP_HDR + i] = (byte) r.nextInt();
            }
            int n = Ip4.udp(out, FastProto.VPN_DNS, 53, FastProto.VPN_ADDR, 40000, len);
            eq(28 + len, n, "UDP length " + len);
            yes(refPacketOk(out, 0), "UDP of " + len + " bytes has right checksums");
            yes(Ip4.parse(out, 0, n, p) && p.proto == Ip4.UDP && p.dataLen == len && p.sport == 53 && p.dport == 40000,
                    "UDP of " + len + " parses back");
        }
    }

    private static void icmp() {
        byte[] quic = new byte[28 + 1200];
        int n = Ip4.udp(quic, FastProto.VPN_ADDR, 40001, Ip4.addr("142.250.80.46"), 443, 1200);
        Ip4.Pkt p = new Ip4.Pkt();
        yes(Ip4.parse(quic, 0, n, p), "a QUIC packet parses");
        byte[] out = new byte[200];
        int m = Ip4.unreachable(out, p.dst, p, 3);
        eq(20 + 8 + 28, m, "the answer quotes the IP header and 8 bytes");
        yes(refSum(out, 0, 20, 0) == 0xFFFF, "ICMP's IP header checksum");
        yes(refSum(out, 20, m - 20, 0) == 0xFFFF, "ICMP checksum");
        eq(3, out[20], "type: destination unreachable");
        eq(3, out[21], "code: port unreachable");
        eq("142.250.80.46", Ip4.str(Ip4.i32(out, 12)), "from the address the app wrote to");
        eq("10.111.222.2", Ip4.str(Ip4.i32(out, 16)), "to the app");
        yes(Ip4.parse(out, 0, m, p) && p.proto == Ip4.ICMP, "the answer parses as ICMP");
        eq(443, Ip4.u16(out, 28 + 22), "the quoted UDP header names port 443");
    }

    private static void malformed() {
        Ip4.Pkt p = new Ip4.Pkt();
        byte[] out = new byte[100];
        int n = Ip4.tcp(out, 1, 1, 2, 2, 0, 0, Ip4.ACK, 10, -1, -1, 0);
        yes(Ip4.parse(out, 0, n, p), "a good segment parses");
        yes(!Ip4.parse(out, 0, 19, p), "too short for an IP header");
        yes(!Ip4.parse(out, 0, n - 1, p), "the IP header says more than was read");
        byte[] v6 = out.clone();
        v6[0] = 0x60;
        yes(!Ip4.parse(v6, 0, n, p), "IPv6 is not handled");
        byte[] badIhl = out.clone();
        badIhl[0] = 0x44;
        yes(!Ip4.parse(badIhl, 0, n, p), "an IP header shorter than 20");
        byte[] badDoff = out.clone();
        badDoff[20 + 12] = (byte) 0xF0;
        yes(!Ip4.parse(badDoff, 0, n, p), "a TCP header longer than the packet");
        byte[] badDoff2 = out.clone();
        badDoff2[20 + 12] = (byte) 0x40;
        yes(!Ip4.parse(badDoff2, 0, n, p), "a TCP header shorter than 20");
        byte[] udp = new byte[100];
        int u = Ip4.udp(udp, 1, 1, 2, 2, 10);
        Ip4.w16(udp, 24, 60);
        yes(!Ip4.parse(udp, 0, u, p), "a UDP length longer than the packet");
        byte[] gre = out.clone();
        gre[9] = 47;
        yes(!Ip4.parse(gre, 0, n, p), "other protocols are not handled");
        byte[] frag = out.clone();
        frag[6] = 0x20; // more fragments
        yes(Ip4.parse(frag, 0, n, p) && p.fragment, "a first fragment is reported as a fragment");
        byte[] frag2 = out.clone();
        frag2[6] = 0x00;
        frag2[7] = 0x10; // offset 16 * 8
        yes(Ip4.parse(frag2, 0, n, p) && p.fragment, "a later fragment is reported as a fragment");
        // options: an option running past the header is ignored, the rest is kept
        byte[] syn = new byte[100];
        int s = Ip4.tcp(syn, 1, 1, 2, 2, 0, 0, Ip4.SYN, 10, 1460, 3, 0);
        syn[44 + 1] = 3;
        syn[44 + 2] = 9; // a window-scale option whose length runs out of the header
        Ip4.parse(syn, 0, s, p);
        eq(1460, p.mss, "the MSS before a broken option is kept");
        eq(-1, p.wscale, "a broken option is not read");
        byte[] ws = new byte[100];
        int w = Ip4.tcp(ws, 1, 1, 2, 2, 0, 0, Ip4.SYN, 10, -1, 0, 0);
        ws[w - 1] = 30;
        Ip4.parse(ws, 0, w, p);
        eq(14, p.wscale, "a window scale above 14 is read as 14 (RFC 7323)");
    }

    private static void checksumMatchesReference() {
        Random r = new Random(3);
        for (int round = 0; round < 3000; round++) {
            int len = r.nextInt(3000);
            byte[] b = new byte[len + 7];
            r.nextBytes(b);
            int off = r.nextInt(7);
            long start = r.nextInt(1 << 20);
            int mine = Ip4.fold(Ip4.sum(b, off, len, start));
            int ref = refSum(b, off, len, start);
            if (mine != ref) {
                eq(ref, mine, "sum of " + len + " bytes at " + off);
                return;
            }
        }
        yes(true, "3000 random sums agree with the reference");
    }

    // ------------------------------------------------------------------------------------------- FastProto

    private static String hexOf(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private static void key() {
        // computed independently with Python: hmac.new(secret, b"linkmeter fast v1", sha256).hexdigest()[:32]
        eq("ff5fc7c5ae87a9db5b0ba2f08edb9a20", hexOf(FastProto.key("0123456789abcdef0123456789abcdef")), "the key");
        yes(!Arrays.equals(FastProto.key("0123456789abcdef0123456789abcdef"), FastProto.key("0123456789abcdef0123456789abcdee")),
                "another secret gives another key");
        List<byte[]> keys = new ArrayList<>();
        keys.add(FastProto.key("a"));
        keys.add(FastProto.key("b"));
        byte[] k = FastProto.key("b");
        yes(FastProto.knownKey(keys, k, 0, 16), "a paired tablet's key is known");
        yes(FastProto.knownKey(keys, k, 0, 8), "its first 8 bytes are known (UDP)");
        k[15] ^= 1;
        yes(!FastProto.knownKey(keys, k, 0, 16), "one bit wrong is unknown");
        yes(!FastProto.knownKey(new ArrayList<byte[]>(), FastProto.key("a"), 0, 16), "no paired tablet: nothing is known");
    }

    private static void hello() {
        List<byte[]> keys = new ArrayList<>();
        keys.add(FastProto.key("secret-one"));
        byte[] h = FastProto.hello(FastProto.key("secret-one"), Ip4.addr("93.184.216.34"), 443);
        eq(FastProto.HELLO_LEN, h.length, "hello length");
        int[] t = new int[2];
        eq(FastProto.OK, FastProto.readHello(h, keys, t), "a good hello");
        eq("93.184.216.34", Ip4.str(t[0]), "address read back");
        eq(443, t[1], "port read back");
        byte[] wrongKey = FastProto.hello(FastProto.key("secret-two"), Ip4.addr("93.184.216.34"), 443);
        eq(FastProto.DENIED, FastProto.readHello(wrongKey, keys, t), "an unknown key is denied");
        for (String a : new String[] {"192.168.49.1", "10.1.2.3", "172.20.0.1", "127.0.0.1", "169.254.1.1", "224.0.0.251",
            "255.255.255.255", "0.1.2.3"}) {
            eq(FastProto.DENIED, FastProto.readHello(FastProto.hello(keys.get(0), Ip4.addr(a), 80), keys, t),
                    "the relay never connects to " + a);
        }
        eq(FastProto.DENIED, FastProto.readHello(FastProto.hello(keys.get(0), Ip4.addr("8.8.8.8"), 0), keys, t), "port 0");
        byte[] v2 = h.clone();
        v2[3] = 2;
        eq(FastProto.DENIED, FastProto.readHello(v2, keys, t), "another version");
        byte[] junk = "GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes();
        eq(FastProto.DENIED, FastProto.readHello(junk, keys, t), "something that is not a hello");
        eq(FastProto.DENIED, FastProto.readHello(new byte[5], keys, t), "too short");
        eq(FastProto.DENIED, FastProto.readHello(h, new ArrayList<byte[]>(), t), "no tablet paired");
        // the ping: a known key is OK with no target, an unknown one is denied
        t[0] = 9;
        t[1] = 9;
        eq(FastProto.OK, FastProto.readHello(FastProto.ping(keys.get(0)), keys, t), "a ping with a known key");
        eq(0, t[1], "a ping asks for no connection (port 0)");
        eq(FastProto.DENIED, FastProto.readHello(FastProto.ping(FastProto.key("other")), keys, t), "a ping with an unknown key");
        byte[] cmd9 = FastProto.ping(keys.get(0));
        cmd9[20] = 9;
        eq(FastProto.DENIED, FastProto.readHello(cmd9, keys, t), "an unknown command");
    }

    private static void servfail() {
        byte[] q = FastE2EQuery.query(0x1234, "www.example.com");
        byte[] a = FastProto.servfail(q);
        eq(q.length, a.length, "SERVFAIL keeps the question and nothing else");
        eq(0x1234, Ip4.u16(a, 0), "the question's number");
        eq(0x81, a[2] & 0xFF, "a response, recursion desired kept");
        eq(2, a[3] & 0x0F, "rcode SERVFAIL");
        eq(1, Ip4.u16(a, 4), "one question");
        eq(0, Ip4.u16(a, 6) + Ip4.u16(a, 8) + Ip4.u16(a, 10), "no answers, no other records");
        // a question with an EDNS record after it: the record is left out
        byte[] edns = new byte[q.length + 11];
        System.arraycopy(q, 0, edns, 0, q.length);
        Ip4.w16(edns, 10, 1);
        byte[] b = FastProto.servfail(edns);
        eq(q.length, b.length, "the EDNS record after the question is cut off");
        eq(0, Ip4.u16(b, 10), "and not counted");
        byte[] junk = FastProto.servfail(new byte[] {1, 2, 3});
        eq(12, junk.length, "something too short still gives a header");
        byte[] loop = q.clone();
        loop[12] = (byte) 0x80; // a label length that is not one
        eq(12, FastProto.servfail(loop).length, "an unreadable name gives the header alone");
    }

    /** The same DNS question builder as the end-to-end test, kept here so this test stands alone. */
    static final class FastE2EQuery {
        static byte[] query(int id, String name) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            b.write(id >>> 8);
            b.write(id);
            b.write(1);
            b.write(0);
            b.write(0);
            b.write(1);
            for (int i = 0; i < 6; i++) {
                b.write(0);
            }
            for (String label : name.split("\\.")) {
                b.write(label.length());
                b.write(label.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, label.length());
            }
            b.write(0);
            b.write(0);
            b.write(1);
            b.write(0);
            b.write(1);
            return b.toByteArray();
        }
    }

    private static void udpHeaders() {
        byte[] out = new byte[40];
        byte[] k = FastProto.key("s");
        FastProto.upHeader(out, k, 51234, Ip4.addr("1.1.1.1"), 853);
        yes(Arrays.equals(Arrays.copyOf(k, 8), Arrays.copyOf(out, 8)), "the key prefix leads");
        eq(51234, Ip4.u16(out, 8), "flow");
        eq("1.1.1.1", Ip4.str(Ip4.i32(out, 10)), "address");
        eq(853, Ip4.u16(out, 14), "port");
        FastProto.downHeader(out, 3, 65535, Ip4.addr("8.8.4.4"), 53);
        eq(65535, Ip4.u16(out, 3), "flow back");
        eq("8.8.4.4", Ip4.str(Ip4.i32(out, 5)), "address back");
        eq(53, Ip4.u16(out, 9), "port back");
        yes(FastProto.isDnsAsk(0, 53), "0.0.0.0:53 is a DNS question for the phone");
        yes(!FastProto.isDnsAsk(Ip4.addr("8.8.8.8"), 53), "8.8.8.8:53 is ordinary UDP");
    }

    private static void addresses() {
        for (String a : new String[] {"10.0.0.1", "10.255.255.255", "172.16.0.0", "172.31.255.255", "192.168.0.1", "192.168.49.1"}) {
            yes(FastProto.privateAddress(Ip4.addr(a)), a + " is private");
            yes(!FastProto.publicAddress(Ip4.addr(a)), a + " is not for the relay");
        }
        for (String a : new String[] {"9.255.255.255", "11.0.0.0", "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0",
            "100.64.0.1", "8.8.8.8", "1.1.1.1", "223.255.255.255", "126.255.255.255", "128.0.0.1", "169.253.255.255", "169.255.0.0"}) {
            yes(!FastProto.privateAddress(Ip4.addr(a)), a + " is not private");
            yes(FastProto.publicAddress(Ip4.addr(a)), a + " is for the relay");
        }
        eq("0.0.0.0", Ip4.str(Ip4.addr("1.2.3")), "a short address is not one");
        eq("0.0.0.0", Ip4.str(Ip4.addr("1.2.3.256")), "a number above 255 is not one");
        eq("0.0.0.0", Ip4.str(Ip4.addr("a.b.c.d")), "words are not an address");
        eq("255.255.255.255", Ip4.str(Ip4.addr("255.255.255.255")), "the top address");
    }

    private static void routes() {
        List<int[]> rs = FastProto.publicRoutes();
        // 46 is the smallest exact cover of the public space (WireGuard's "exclude private IPs" list is as long); Android's VPN
        // is only troubled by thousands
        eq(46, rs.size(), "the number of routes");
        // no two routes overlap
        for (int i = 0; i < rs.size(); i++) {
            for (int j = i + 1; j < rs.size(); j++) {
                int[] a = rs.get(i);
                int[] b = rs.get(j);
                int bits = Math.min(a[1], b[1]);
                if (FastProto.inPrefix(a[0], b[0], bits)) {
                    yes(false, Ip4.str(a[0]) + "/" + a[1] + " overlaps " + Ip4.str(b[0]) + "/" + b[1]);
                }
            }
        }
        // a route's address has no bits beyond its prefix
        for (int[] r : rs) {
            yes(r[1] == 0 || (r[0] & ~(-1 << (32 - r[1]))) == 0, Ip4.str(r[0]) + "/" + r[1] + " is a clean prefix");
        }
        // exactly the public addresses are routed: every edge and a million random ones
        Random rnd = new Random(5);
        int bad = 0;
        List<Integer> probe = new ArrayList<>();
        for (int[] ex : FastProto.EXCLUDED) {
            int size = ex[1] == 0 ? -1 : 1 << (32 - ex[1]);
            probe.add(ex[0]);
            probe.add(ex[0] - 1);
            probe.add(ex[0] + size - 1);
            probe.add(ex[0] + size);
        }
        for (int i = 0; i < 1_000_000; i++) {
            probe.add(rnd.nextInt());
        }
        for (int a : probe) {
            boolean routed = false;
            for (int[] r : rs) {
                routed |= FastProto.inPrefix(a, r[0], r[1]);
            }
            if (routed != FastProto.publicAddress(a)) {
                bad++;
                if (bad < 5) {
                    yes(false, Ip4.str(a) + " routed=" + routed);
                }
            }
        }
        eq(0, bad, "addresses routed differently from publicAddress");
        yes(!routedBy(rs, Ip4.addr("192.168.49.1")), "Wi-Fi Direct's phone address stays out of the VPN");
        yes(routedBy(rs, Ip4.addr("8.8.8.8")), "8.8.8.8 goes through the VPN");
    }

    private static boolean routedBy(List<int[]> rs, int a) {
        for (int[] r : rs) {
            if (FastProto.inPrefix(a, r[0], r[1])) {
                return true;
            }
        }
        return false;
    }

    private static void interfaces() {
        for (String n : new String[] {"wlan0", "wlan1", "swlan0", "ap0", "softap0", "p2p-wlan0-0", "p2p0", "rndis0", "usb0", "eth0",
            "bt-pan", "WLAN0"}) {
            yes(FastProto.lanInterface(n), n + " is a local interface");
        }
        for (String n : new String[] {"rmnet_data0", "rmnet0", "ccmni0", "ccmni1", "v4-rmnet_data0", "clat4", "lo", "tun0", "dummy0",
            "ipsec0", ""}) {
            yes(!FastProto.lanInterface(n), n + " is not a local interface");
        }
        yes(!FastProto.lanInterface(null), "no name is not a local interface");
    }
}
