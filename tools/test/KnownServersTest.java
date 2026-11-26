package com.tj.linkmeter;

/**
 * Tests KnownServers (docs/FAST_WIFI.md, "Audit of 2026-11-26"): a server is known only after the phone reached it, only for the time that
 * is believed, forgotten at once when asked, and the memory never grows past its size (the least recently used go first). Not part of
 * the app.
 */
public final class KnownServersTest {
    private static int checks;
    private static int fails;

    private KnownServersTest() {}

    private static void yes(boolean ok, String what) {
        checks++;
        if (!ok) {
            fails++;
            System.out.println("FAIL " + what);
        }
    }

    public static void main(String[] args) {
        long t = 1_000_000L;
        KnownServers k = new KnownServers(4, 10_000);
        yes(!k.known(1, 443, t), "nothing is known at first");
        k.note(1, 443, t);
        yes(k.known(1, 443, t), "a server the phone reached is known");
        yes(k.known(1, 443, t + 10_000), "and still at the end of the time that is believed");
        yes(!k.known(1, 443, t + 10_001), "and not a moment after");
        yes(k.size() == 0, "an old entry is dropped when it is found out of date");
        k.note(1, 443, t);
        yes(!k.known(1, 80, t), "another port of the same address is not known");
        yes(!k.known(2, 443, t), "another address with the same port is not known");
        k.forget(1, 443);
        yes(!k.known(1, 443, t), "a forgotten server is not known");
        k.forget(9, 9); // forgetting what is not there does nothing
        yes(k.size() == 0, "forgetting an unknown server changes nothing");
        // a clock that went back is not believed
        k.note(5, 5, t);
        yes(!k.known(5, 5, t - 1), "a clock that went back believes nothing");
        // the memory is bounded and keeps the servers used most recently
        k.clear();
        for (int i = 1; i <= 4; i++) {
            k.note(i, 443, t);
        }
        yes(k.known(1, 443, t), "1 is known (and is now the most recently used)");
        k.note(5, 443, t); // pushes out the least recently used: 2
        yes(k.size() == 4, "the memory holds no more than its size");
        yes(k.known(1, 443, t) && k.known(3, 443, t) && k.known(4, 443, t) && k.known(5, 443, t), "the recently used ones stay");
        yes(!k.known(2, 443, t), "the least recently used is the one that went");
        // addresses with the high bit set (negative ints) and the highest ports do not clash
        k.clear();
        k.note(0xC0A80001, 65535, t);
        k.note(0xC0A80001, 65534, t);
        k.note(0x40A80001, 65535, t);
        yes(k.known(0xC0A80001, 65535, t) && k.known(0xC0A80001, 65534, t) && k.known(0x40A80001, 65535, t), "big addresses and ports are told apart");
        yes(!k.known(0xC0A80001, 1, t), "and nothing else is known");
        // a very long run of servers never grows the memory
        KnownServers big = new KnownServers(512, 600_000);
        for (int i = 0; i < 100_000; i++) {
            big.note(i * 2654435761L > 0 ? (int) (i * 2654435761L) : i, 443 + (i & 7), t + i);
        }
        yes(big.size() <= 512, "100,000 servers later the memory still holds no more than 512 (" + big.size() + ")");
        System.out.println(checks + " checks, " + fails + " failed");
        if (fails > 0) {
            System.exit(1);
        }
    }
}
