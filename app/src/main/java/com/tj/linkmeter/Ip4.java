package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/FastCodecTest.java). The IPv4 packets the tablet's Fast Wi-Fi VPN interface hands over and
 * takes back: reading one into a reused {@link Pkt} (no allocation per packet), and writing TCP, UDP and ICMP packets with correct
 * checksums. Only what the engine ({@link TunStack}) needs: no IP options are written, fragments are reported (and dropped by the caller).
 *
 * Addresses are ints in network order (192.168.49.1 = 0xC0A83101).
 */
final class Ip4 {
    private Ip4() {}

    static final int ICMP = 1;
    static final int TCP = 6;
    static final int UDP = 17;

    static final int FIN = 0x01;
    static final int SYN = 0x02;
    static final int RST = 0x04;
    static final int PSH = 0x08;
    static final int ACK = 0x10;

    /** IP header (20) + TCP header without options (20): where the payload of every data segment starts. */
    static final int TCP_HDR = 40;
    static final int UDP_HDR = 28;

    /** One parsed packet. The engine keeps one and refills it for every packet. */
    static final class Pkt {
        byte[] b;
        /** Start of the IP header in b, and the packet's length as the IP header says (never more than was read). */
        int off;
        int len;
        int proto;
        int src;
        int dst;
        /** Start of the TCP/UDP/ICMP header in b. */
        int l4;
        int sport;
        int dport;
        int seq;
        int ack;
        int flags;
        /** The window field as sent (not yet scaled). */
        int window;
        /** Start and length of the payload in b. */
        int data;
        int dataLen;
        /** From a SYN's options: -1 when absent. */
        int mss;
        int wscale;
        /** A piece of a fragmented packet: the engine does not put those back together. */
        boolean fragment;
    }

    /**
     * Reads the packet b[off, off+n) into p. False for anything that is not a whole, sane IPv4 TCP/UDP/ICMP packet (the caller drops it).
     */
    static boolean parse(byte[] b, int off, int n, Pkt p) {
        if (n < 20 || (b[off] & 0xF0) != 0x40) {
            return false;
        }
        int ihl = (b[off] & 0x0F) * 4;
        int total = u16(b, off + 2);
        if (ihl < 20 || total < ihl || total > n) {
            return false;
        }
        p.b = b;
        p.off = off;
        p.len = total;
        p.proto = b[off + 9] & 0xFF;
        p.src = i32(b, off + 12);
        p.dst = i32(b, off + 16);
        int frag = u16(b, off + 6);
        p.fragment = (frag & 0x2000) != 0 || (frag & 0x1FFF) != 0;
        p.l4 = off + ihl;
        p.mss = -1;
        p.wscale = -1;
        p.sport = 0;
        p.dport = 0;
        p.flags = 0;
        int l4len = total - ihl;
        if (p.fragment) {
            return true;
        }
        if (p.proto == TCP) {
            if (l4len < 20) {
                return false;
            }
            int t = p.l4;
            p.sport = u16(b, t);
            p.dport = u16(b, t + 2);
            p.seq = i32(b, t + 4);
            p.ack = i32(b, t + 8);
            int doff = ((b[t + 12] & 0xF0) >>> 4) * 4;
            if (doff < 20 || doff > l4len) {
                return false;
            }
            p.flags = b[t + 13] & 0x3F;
            p.window = u16(b, t + 14);
            p.data = t + doff;
            p.dataLen = l4len - doff;
            if ((p.flags & SYN) != 0) {
                options(b, t + 20, t + doff, p);
            }
            return true;
        }
        if (p.proto == UDP) {
            if (l4len < 8) {
                return false;
            }
            int u = p.l4;
            p.sport = u16(b, u);
            p.dport = u16(b, u + 2);
            int ulen = u16(b, u + 4);
            if (ulen < 8 || ulen > l4len) {
                return false;
            }
            p.data = u + 8;
            p.dataLen = ulen - 8;
            return true;
        }
        if (p.proto == ICMP) {
            p.data = p.l4;
            p.dataLen = l4len;
            return l4len >= 8;
        }
        return false;
    }

    /** MSS (kind 2) and window scale (kind 3) from a SYN's options; anything else is skipped. */
    private static void options(byte[] b, int i, int end, Pkt p) {
        while (i < end) {
            int kind = b[i] & 0xFF;
            if (kind == 0) {
                return; // end of options
            }
            if (kind == 1) {
                i++; // no-op
                continue;
            }
            if (i + 1 >= end) {
                return;
            }
            int len = b[i + 1] & 0xFF;
            if (len < 2 || i + len > end) {
                return; // malformed: keep what was read so far
            }
            if (kind == 2 && len == 4) {
                p.mss = u16(b, i + 2);
            } else if (kind == 3 && len == 3) {
                p.wscale = Math.min(14, b[i + 2] & 0xFF);
            }
            i += len;
        }
    }

    // ------------------------------------------------------------------------------------------- writing

    /**
     * Writes the IP and TCP headers into out[0, 40) (or more with options) for a segment whose payload the caller has ALREADY put at
     * out[{@link #TCP_HDR}, TCP_HDR+dataLen). Options are only written on a SYN (dataLen must then be 0): mss > 0 adds MSS, wscale >= 0
     * adds window scale. Returns the packet's length.
     */
    static int tcp(byte[] out, int src, int sport, int dst, int dport, int seq, int ack, int flags, int window, int mss, int wscale,
            int dataLen) {
        int opt = 0;
        if ((flags & SYN) != 0) {
            if (mss > 0) {
                opt += 4;
            }
            if (wscale >= 0) {
                opt += 4;
            }
        }
        int th = 20 + opt;
        int total = 20 + th + dataLen;
        if (opt > 0 && dataLen > 0) {
            throw new IllegalArgumentException("a SYN carries no data here");
        }
        ip(out, total, TCP, src, dst);
        int t = 20;
        w16(out, t, sport);
        w16(out, t + 2, dport);
        w32(out, t + 4, seq);
        w32(out, t + 8, ack);
        out[t + 12] = (byte) ((th / 4) << 4);
        out[t + 13] = (byte) flags;
        w16(out, t + 14, Math.max(0, Math.min(0xFFFF, window)));
        out[t + 16] = 0;
        out[t + 17] = 0;
        out[t + 18] = 0;
        out[t + 19] = 0;
        int o = t + 20;
        if (opt > 0) {
            if (mss > 0) {
                out[o] = 2;
                out[o + 1] = 4;
                w16(out, o + 2, mss);
                o += 4;
            }
            if (wscale >= 0) {
                out[o] = 1; // no-op, so the option ends on a 4-byte boundary
                out[o + 1] = 3;
                out[o + 2] = 3;
                out[o + 3] = (byte) wscale;
            }
        }
        int sum = l4Checksum(out, 20, th + dataLen, TCP, src, dst);
        w16(out, t + 16, sum);
        return total;
    }

    /** Writes a UDP packet whose payload the caller has ALREADY put at out[{@link #UDP_HDR}, UDP_HDR+dataLen). Returns its length. */
    static int udp(byte[] out, int src, int sport, int dst, int dport, int dataLen) {
        int total = UDP_HDR + dataLen;
        ip(out, total, UDP, src, dst);
        w16(out, 20, sport);
        w16(out, 22, dport);
        w16(out, 24, 8 + dataLen);
        out[26] = 0;
        out[27] = 0;
        int sum = l4Checksum(out, 20, 8 + dataLen, UDP, src, dst);
        w16(out, 26, sum == 0 ? 0xFFFF : sum); // 0 means "no checksum" in UDP
        return total;
    }

    /**
     * Writes an ICMP "destination unreachable" (code 3 = port unreachable) from src about the packet p, so the app's socket fails at once
     * (used to turn QUIC away: the app then uses TCP). Returns its length.
     */
    static int unreachable(byte[] out, int src, Pkt p, int code) {
        int quote = Math.min(p.len, (p.l4 - p.off) + 8); // the original IP header and the first 8 bytes after it
        int total = 20 + 8 + quote;
        ip(out, total, ICMP, src, p.src);
        out[20] = 3;
        out[21] = (byte) code;
        out[22] = 0;
        out[23] = 0;
        out[24] = 0;
        out[25] = 0;
        out[26] = 0;
        out[27] = 0;
        System.arraycopy(p.b, p.off, out, 28, quote);
        w16(out, 22, fold(sum(out, 20, 8 + quote, 0)) ^ 0xFFFF);
        return total;
    }

    private static int ipId;

    private static void ip(byte[] out, int total, int proto, int src, int dst) {
        out[0] = 0x45;
        out[1] = 0;
        w16(out, 2, total);
        w16(out, 4, (ipId++) & 0xFFFF);
        out[6] = 0x40; // don't fragment
        out[7] = 0;
        out[8] = 64;
        out[9] = (byte) proto;
        out[10] = 0;
        out[11] = 0;
        w32(out, 12, src);
        w32(out, 16, dst);
        w16(out, 10, fold(sum(out, 0, 20, 0)) ^ 0xFFFF);
    }

    // ------------------------------------------------------------------------------------------- checksums

    /** The TCP or UDP checksum over the pseudo header and b[off, off+len). */
    static int l4Checksum(byte[] b, int off, int len, int proto, int src, int dst) {
        long s = ((src >>> 16) & 0xFFFF) + (src & 0xFFFF) + ((dst >>> 16) & 0xFFFF) + (dst & 0xFFFF) + proto + len;
        return fold(sum(b, off, len, s)) ^ 0xFFFF;
    }

    /** The 16-bit one's complement sum of b[off, off+len), added to s, not yet folded. */
    static long sum(byte[] b, int off, int len, long s) {
        int i = off;
        int end = off + len;
        for (; i + 3 < end; i += 4) {
            s += (((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF)) + (((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF));
        }
        for (; i + 1 < end; i += 2) {
            s += ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
        }
        if (i < end) {
            s += (b[i] & 0xFF) << 8;
        }
        return s;
    }

    static int fold(long s) {
        while ((s >>> 16) != 0) {
            s = (s & 0xFFFF) + (s >>> 16);
        }
        return (int) s;
    }

    /** True if the IP header checksum and the TCP/UDP checksum of the packet are right (for the tests). */
    static boolean checksumsOk(byte[] b, int off) {
        int ihl = (b[off] & 0x0F) * 4;
        if (fold(sum(b, off, ihl, 0)) != 0xFFFF) {
            return false;
        }
        int total = u16(b, off + 2);
        int proto = b[off + 9] & 0xFF;
        int src = i32(b, off + 12);
        int dst = i32(b, off + 16);
        int l4 = off + ihl;
        int len = total - ihl;
        if (proto == TCP || proto == UDP) {
            long s = ((src >>> 16) & 0xFFFF) + (src & 0xFFFF) + ((dst >>> 16) & 0xFFFF) + (dst & 0xFFFF) + proto + len;
            return fold(sum(b, l4, len, s)) == 0xFFFF;
        }
        return fold(sum(b, l4, len, 0)) == 0xFFFF;
    }

    // ------------------------------------------------------------------------------------------- bytes

    static int u16(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    static int i32(byte[] b, int i) {
        return ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
    }

    static void w16(byte[] b, int i, int v) {
        b[i] = (byte) (v >>> 8);
        b[i + 1] = (byte) v;
    }

    static void w32(byte[] b, int i, int v) {
        b[i] = (byte) (v >>> 24);
        b[i + 1] = (byte) (v >>> 16);
        b[i + 2] = (byte) (v >>> 8);
        b[i + 3] = (byte) v;
    }

    /** "a.b.c.d" for an address int. */
    static String str(int a) {
        return ((a >>> 24) & 0xFF) + "." + ((a >>> 16) & 0xFF) + "." + ((a >>> 8) & 0xFF) + "." + (a & 0xFF);
    }

    /** The int of "a.b.c.d", or 0 if it is not one. */
    static int addr(String s) {
        String[] p = s.trim().split("\\.", -1);
        if (p.length != 4) {
            return 0;
        }
        int a = 0;
        for (String x : p) {
            if (x.isEmpty() || x.length() > 3) {
                return 0;
            }
            int v;
            try {
                v = Integer.parseInt(x);
            } catch (NumberFormatException e) {
                return 0;
            }
            if (v < 0 || v > 255) {
                return 0;
            }
            a = (a << 8) | v;
        }
        return a;
    }
}
