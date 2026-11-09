package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;

/**
 * No Android in here (tested in tools/test/SubnetTest.java). Where to look for the phone once it has joined the tablet's hotspot: every
 * host address of the hotspot's subnet, except the tablet itself, the network address and the broadcast address, lowest first (a hotspot
 * hands out its addresses from the bottom, so the phone is found in the first few tries).
 */
final class Subnet {
    private Subnet() {}

    /** The most addresses ever tried on one subnet (a /22): a hotspot is never bigger, and a wrong guess must not scan a /8. */
    static final int MAX_HOSTS = 1022;

    /** "192.168.49.1" as a number, or -1 if it is not four numbers from 0 to 255. */
    static long parse(String s) {
        if (s == null) {
            return -1;
        }
        String[] p = s.trim().split("\\.", -1);
        if (p.length != 4) {
            return -1;
        }
        long v = 0;
        for (String part : p) {
            if (part.isEmpty() || part.length() > 3) {
                return -1;
            }
            int n;
            try {
                n = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return -1;
            }
            if (n < 0 || n > 255) {
                return -1;
            }
            v = (v << 8) | n;
        }
        return v;
    }

    static String format(long ip) {
        return ((ip >> 24) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + (ip & 0xFF);
    }

    /** Is this a private (home network) address: 10.x, 172.16 to 172.31, 192.168.x? A hotspot always uses one. */
    static boolean isPrivate(long ip) {
        long a = (ip >> 24) & 0xFF;
        long b = (ip >> 16) & 0xFF;
        return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
    }

    /**
     * The addresses to try, lowest first: every host of the network that ip belongs to (prefix bits long), without the network address,
     * the broadcast address and ip itself. Empty if the prefix is nonsense (under 22 bits it is cut to the {@link #MAX_HOSTS} lowest).
     */
    static List<String> candidates(String ip, int prefix) {
        List<String> out = new ArrayList<>();
        long self = parse(ip);
        if (self < 0 || prefix < 8 || prefix > 30 || !isPrivate(self)) {
            return out;
        }
        long mask = (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        long network = self & mask;
        long broadcast = network | (~mask & 0xFFFFFFFFL);
        for (long a = network + 1; a < broadcast && out.size() < MAX_HOSTS; a++) {
            if (a != self) {
                out.add(format(a));
            }
        }
        return out;
    }
}
