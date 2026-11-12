package com.tj.linkmeter;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * No Android in here (tested in tools/test/FastCodecTest.java). The Fast Wi-Fi relay protocol between the tablet's engine
 * ({@link TunStack}) and the phone's relay ({@link FastRelayCore}), described in docs/FAST_WIFI.md, plus the small rules both sides
 * share: the key a paired tablet proves itself with, which addresses may use the relay, and the routes the tablet's VPN takes.
 */
final class FastProto {
    private FastProto() {}

    /** The phone's relay port, for TCP and for UDP (8765 is the phone's control port). */
    static final int PORT = 8767;
    static final int VERSION = 1;

    /** TCP: what the tablet sends first on every relay connection. */
    static final int HELLO_LEN = 27;
    static final int CMD_TCP4 = 1;
    /** "Do you know my key?": answered OK or DENIED at once, then closed (the tablet asks before it puts its VPN up). */
    static final int CMD_PING = 2;

    /** TCP: the phone's one-byte answer. */
    static final int OK = 0;
    static final int REFUSED = 1;
    static final int UNREACHABLE = 2;
    static final int DENIED = 3;

    /** UDP headers: tablet to phone (key prefix, flow, address, port) and phone to tablet (flow, address, port). */
    static final int UP_HDR = 16;
    static final int DOWN_HDR = 8;
    static final int KEY_LEN = 16;
    static final int UDP_KEY_LEN = 8;

    /** The tablet's VPN: its own address, the DNS server address the phone answers for, and the interface's MTU. */
    static final int VPN_ADDR = 0x0A6FDE02; // 10.111.222.2
    static final int VPN_DNS = 0x0A6FDE35; // 10.111.222.53
    static final int VPN_PREFIX = 24;
    static final int MTU = 10000;

    // ------------------------------------------------------------------------------------------- the key

    /**
     * The relay key of a paired tablet: the first 16 bytes of HMAC-SHA256(pairing secret, "linkmeter fast v1"). Both devices know the
     * secret, so nothing has to be exchanged, and a new pairing gives a new key. The secret itself never travels with relay traffic.
     */
    static byte[] key(String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] full = mac.doFinal("linkmeter fast v1".getBytes(StandardCharsets.US_ASCII));
            byte[] k = new byte[KEY_LEN];
            System.arraycopy(full, 0, k, 0, KEY_LEN);
            return k;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is missing", e);
        }
    }

    /** True if b[off, off+len) equals the first len bytes of one of the keys (time does not depend on where they differ). */
    static boolean knownKey(List<byte[]> keys, byte[] b, int off, int len) {
        byte[] got = new byte[len];
        System.arraycopy(b, off, got, 0, len);
        boolean ok = false;
        for (byte[] k : keys) {
            byte[] want = new byte[len];
            System.arraycopy(k, 0, want, 0, len);
            ok |= MessageDigest.isEqual(got, want);
        }
        return ok;
    }

    // ------------------------------------------------------------------------------------------- TCP hello

    /** The 27 bytes that open a relay connection to addr:port. */
    static byte[] hello(byte[] key, int addr, int port) {
        return hello(key, CMD_TCP4, addr, port);
    }

    /** The 27 bytes of a ping: the phone only says whether it knows the key. */
    static byte[] ping(byte[] key) {
        return hello(key, CMD_PING, 0, 0);
    }

    private static byte[] hello(byte[] key, int cmd, int addr, int port) {
        byte[] h = new byte[HELLO_LEN];
        h[0] = 'L';
        h[1] = 'M';
        h[2] = 'F';
        h[3] = VERSION;
        System.arraycopy(key, 0, h, 4, KEY_LEN);
        h[20] = (byte) cmd;
        Ip4.w32(h, 21, addr);
        Ip4.w16(h, 25, port);
        return h;
    }

    /**
     * The phone's check of a hello: OK, or DENIED for a wrong version, an unknown key or a destination that must not be reached through
     * the relay (the phone itself, a private network, multicast). On OK, target[0] is the address and target[1] the port, both 0 for a
     * ping (which asks for no connection).
     */
    static int readHello(byte[] h, List<byte[]> keys, int[] target) {
        if (h.length < HELLO_LEN || h[0] != 'L' || h[1] != 'M' || h[2] != 'F' || h[3] != VERSION
                || (h[20] != CMD_TCP4 && h[20] != CMD_PING)) {
            return DENIED;
        }
        if (!knownKey(keys, h, 4, KEY_LEN)) {
            return DENIED;
        }
        if (h[20] == CMD_PING) {
            target[0] = 0;
            target[1] = 0;
            return OK;
        }
        int addr = Ip4.i32(h, 21);
        int port = Ip4.u16(h, 25);
        if (port == 0 || !publicAddress(addr)) {
            return DENIED;
        }
        target[0] = addr;
        target[1] = port;
        return OK;
    }

    // ------------------------------------------------------------------------------------------- UDP

    /** Writes the tablet-to-phone UDP header at out[0, 16); the payload follows. */
    static void upHeader(byte[] out, byte[] key, int flow, int addr, int port) {
        System.arraycopy(key, 0, out, 0, UDP_KEY_LEN);
        Ip4.w16(out, 8, flow);
        Ip4.w32(out, 10, addr);
        Ip4.w16(out, 14, port);
    }

    /** Writes the phone-to-tablet UDP header at out[off, off+8); the payload follows. */
    static void downHeader(byte[] out, int off, int flow, int addr, int port) {
        Ip4.w16(out, off, flow);
        Ip4.w32(out, off + 2, addr);
        Ip4.w16(out, off + 6, port);
    }

    /**
     * A SERVFAIL answer to the DNS question q (its number, its question, no answers), so the tablet's resolver gives up at once instead
     * of waiting when the phone's resolver has no answer. Anything after the question (an EDNS record) is left out.
     */
    static byte[] servfail(byte[] q) {
        int end = questionEnd(q);
        byte[] a = new byte[Math.max(12, end)];
        System.arraycopy(q, 0, a, 0, Math.min(q.length, a.length));
        a[2] = (byte) ((a[2] & 0x01) | 0x80); // a response; recursion desired kept
        a[3] = (byte) 0x82; // recursion available, SERVFAIL
        Ip4.w16(a, 4, end > 12 ? 1 : 0);
        Ip4.w16(a, 6, 0);
        Ip4.w16(a, 8, 0);
        Ip4.w16(a, 10, 0);
        return a;
    }

    /** Where the first question of a DNS message ends, or 12 (the header alone) when it cannot be read. */
    static int questionEnd(byte[] q) {
        if (q.length < 12 || Ip4.u16(q, 4) < 1) {
            return 12;
        }
        int i = 12;
        while (true) {
            if (i >= q.length) {
                return 12;
            }
            int l = q[i] & 0xFF;
            if (l == 0) {
                i++;
                break;
            }
            if ((l & 0xC0) == 0xC0) {
                i += 2;
                break;
            }
            if ((l & 0xC0) != 0) {
                return 12;
            }
            i += 1 + l;
        }
        i += 4;
        return i <= q.length ? i : 12;
    }

    /** Address 0.0.0.0 port 53 in a UDP header: "a DNS question for the phone's own resolver". */
    static boolean isDnsAsk(int addr, int port) {
        return addr == 0 && port == 53;
    }

    // ------------------------------------------------------------------------------------------- addresses

    /** 10/8, 172.16/12, 192.168/16: where the tablet and the phone meet (the hotspot, Wi-Fi Direct). */
    static boolean privateAddress(int a) {
        return (a >>> 24) == 10 || (a >>> 20) == ((172 << 4) | 1) || (a >>> 16) == ((192 << 8) | 168);
    }

    /**
     * May the relay connect to this address for the tablet? Not to "this network" (0/8), private networks, loopback, link-local,
     * multicast or reserved space: the relay is for the internet only, never a way into the phone or a network it is on.
     */
    static boolean publicAddress(int a) {
        for (int[] ex : EXCLUDED) {
            if (inPrefix(a, ex[0], ex[1])) {
                return false;
            }
        }
        return true;
    }

    static boolean inPrefix(int a, int net, int bits) {
        if (bits == 0) {
            return true;
        }
        int mask = -1 << (32 - bits);
        return (a & mask) == (net & mask);
    }

    /** What the VPN leaves alone and the relay refuses. */
    static final int[][] EXCLUDED = {
        {0x00000000, 8}, // "this network"
        {0x0A000000, 8}, // 10/8 private
        {0x7F000000, 8}, // loopback
        {0xA9FE0000, 16}, // 169.254/16 link-local
        {0xAC100000, 12}, // 172.16/12 private
        {0xC0A80000, 16}, // 192.168/16 private
        {0xE0000000, 3}, // multicast and reserved (224/4, 240/4)
    };

    /**
     * The tablet VPN's routes: all of IPv4 except {@link #EXCLUDED}, as few prefixes as possible. Each entry is {address, bits}. The VPN
     * also routes its own small network (address and DNS) itself.
     */
    static List<int[]> publicRoutes() {
        List<int[]> out = new ArrayList<>();
        split(0, 0, out);
        return out;
    }

    /** Adds net/bits if no excluded prefix touches it; drops it if one covers it; otherwise looks at its two halves. */
    private static void split(int net, int bits, List<int[]> out) {
        boolean touched = false;
        for (int[] ex : EXCLUDED) {
            if (bits >= ex[1] ? inPrefix(net, ex[0], ex[1]) : inPrefix(ex[0], net, bits)) {
                if (bits >= ex[1]) {
                    return; // wholly excluded
                }
                touched = true;
            }
        }
        if (!touched) {
            out.add(new int[] {net, bits});
            return;
        }
        split(net, bits + 1, out);
        split(net | (1 << (31 - bits)), bits + 1, out);
    }

    // ------------------------------------------------------------------------------------------- interfaces

    /**
     * Is a network interface of this name one the tablet can reach the phone on (Wi-Fi, the hotspot, Wi-Fi Direct, USB or Bluetooth
     * tethering)? Never the mobile data interface (rmnet, ccmni, ...), so the relay never answers anyone on the carrier's side.
     */
    static boolean lanInterface(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(java.util.Locale.US);
        return n.startsWith("wlan") || n.startsWith("swlan") || n.startsWith("ap") || n.startsWith("softap") || n.startsWith("p2p")
                || n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("eth") || n.startsWith("bt-pan") || n.startsWith("bnep");
    }
}
