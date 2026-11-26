package com.tj.linkmeter;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;

/**
 * No Android in here (tested in tools/test/SocketPoolTest.java). A few sockets for Fast Wi-Fi's relay connections, made BEFORE they are
 * needed (docs/FAST_WIFI.md, "Audit of 2026-11-26"). On the tablet every relay socket has to be protected from the VPN and bound to the
 * Wi-Fi, two calls into Android's system services (binder), a millisecond or more each on a slow tablet; made inside the engine's one
 * thread they stop every other connection for that long, for every connection a page opens. Here they are made on a helper thread, and
 * the engine takes a finished one without waiting ({@link #take}); when none is ready it makes one itself, as before.
 *
 * Nothing runs while nothing is used: a taken socket asks for a replacement ({@link #refill}), the helper makes it and ends, and the sockets
 * that wait are not connected (they cost a file descriptor, no traffic and no wake-up). {@link #close} closes all of them for good.
 */
final class SocketPool {
    /** Makes one socket the way the engine needs it (protected, bound): the slow part. */
    interface Maker {
        SocketChannel make() throws IOException;
    }

    private final Maker maker;
    private final int size;
    private final Executor helper;
    private final ArrayDeque<SocketChannel> ready = new ArrayDeque<>();
    private boolean filling;
    private boolean closed;
    private long hits;
    private long misses;

    /**
     * @param size how many sockets wait at most
     * @param helper where the sockets are made (one thread; never the engine's)
     */
    SocketPool(Maker maker, int size, Executor helper) {
        this.maker = maker;
        this.size = Math.max(1, size);
        this.helper = helper;
    }

    /** A made socket, or null when none is ready or the pool is closed. Never waits. Asks for a replacement. */
    synchronized SocketChannel take() {
        SocketChannel c = closed ? null : ready.poll();
        if (c == null) {
            misses++;
        } else {
            hits++;
        }
        refill();
        return c;
    }

    /** Makes up the sockets that are missing, in the background. Cheap to call when the pool is full. */
    synchronized void refill() {
        if (closed || filling || ready.size() >= size) {
            return;
        }
        filling = true;
        try {
            helper.execute(this::fill);
        } catch (RuntimeException e) {
            filling = false; // the helper is gone (the app is closing): the engine makes its own sockets
        }
    }

    private void fill() {
        while (true) {
            synchronized (this) {
                if (closed || ready.size() >= size) {
                    filling = false;
                    return;
                }
            }
            SocketChannel c;
            try {
                c = maker.make(); // outside the lock: this is the slow part
            } catch (IOException | RuntimeException e) {
                synchronized (this) {
                    filling = false; // the next take() tries again
                }
                return;
            }
            synchronized (this) {
                if (closed) {
                    filling = false;
                    closeQuietly(c);
                    return;
                }
                ready.add(c);
            }
        }
    }

    /** Closes every waiting socket and refuses new ones. */
    synchronized void close() {
        closed = true;
        for (SocketChannel c : ready) {
            closeQuietly(c);
        }
        ready.clear();
    }

    synchronized int waiting() {
        return ready.size();
    }

    /** How many sockets were handed out ready, and how many times none was ready (for the screen's numbers and the tests). */
    synchronized long hits() {
        return hits;
    }

    synchronized long misses() {
        return misses;
    }

    private static void closeQuietly(SocketChannel c) {
        try {
            c.close();
        } catch (IOException | RuntimeException ignored) {
            // closed
        }
    }
}
