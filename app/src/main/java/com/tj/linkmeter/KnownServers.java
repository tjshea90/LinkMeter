package com.tj.linkmeter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * No Android in here (tested in tools/test/KnownServersTest.java). The internet addresses and ports that the phone reached for the tablet
 * a short while ago (docs/FAST_WIFI.md, "Audit of 2026-11-26"). A connection to one of them is answered to the app at once, without waiting
 * for the phone to reach the server again: a page that opens a dozen connections to the same few servers saves one whole round trip (the
 * Wi-Fi's and the mobile network's) on all but the first of each. An address that was never reached, or not lately, is still answered
 * only when the phone has reached it, so a refused or unreachable server looks to the app exactly as on a normal network. A server that
 * then fails after an early answer is {@link #forget forgotten} at once. Used on the engine's thread only.
 */
final class KnownServers {
    private final long ttlMs;
    private final LinkedHashMap<Long, Long> seen;

    /** @param max how many servers are remembered (the least recently used go first), @param ttlMs how long a success is believed */
    KnownServers(final int max, long ttlMs) {
        this.ttlMs = ttlMs;
        this.seen = new LinkedHashMap<Long, Long>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
                return size() > max;
            }
        };
    }

    private static long key(int addr, int port) {
        return ((addr & 0xFFFFFFFFL) << 16) | (port & 0xFFFF);
    }

    /** The phone reached this server a moment ago, within the time that is believed. */
    boolean known(int addr, int port, long now) {
        Long at = seen.get(key(addr, port));
        if (at == null) {
            return false;
        }
        if (now - at > ttlMs || now < at) {
            seen.remove(key(addr, port)); // too old (or the clock went back): not believed
            return false;
        }
        return true;
    }

    /** The phone reached this server just now. */
    void note(int addr, int port, long now) {
        seen.put(key(addr, port), now);
    }

    /** This server failed after it was answered early: the next connection waits for the phone again. */
    void forget(int addr, int port) {
        seen.remove(key(addr, port));
    }

    int size() {
        return seen.size();
    }

    void clear() {
        seen.clear();
    }
}
