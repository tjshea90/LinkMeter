package com.tj.linkmeter;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests SocketPool (docs/FAST_WIFI.md, "Audit of 2026-11-26"): sockets are made ahead on a helper and handed out without waiting; an empty
 * pool says so at once and refills; it never holds more than its size; a maker that fails does not wedge it; a maker that is slow never
 * delays take(); close() closes everything and nothing is made after it. Not part of the app.
 */
public final class SocketPoolTest {
    private static int checks;
    private static int fails;

    private SocketPoolTest() {}

    private static void yes(boolean ok, String what) {
        checks++;
        if (!ok) {
            fails++;
            System.out.println("FAIL " + what);
        }
    }

    private static void eq(Object want, Object got, String what) {
        checks++;
        if (!String.valueOf(want).equals(String.valueOf(got))) {
            fails++;
            System.out.println("FAIL " + what + ": wanted " + want + " but got " + got);
        }
    }

    public static void main(String[] args) throws Exception {
        direct();
        failingMaker();
        slowMaker();
        closing();
        manyTakers();
        System.out.println(checks + " checks, " + fails + " failed");
        if (fails > 0) {
            System.exit(1);
        }
    }

    private static SocketPool.Maker counting(final AtomicInteger made, final List<SocketChannel> all) {
        return () -> {
            SocketChannel c = SocketChannel.open();
            made.incrementAndGet();
            synchronized (all) {
                all.add(c);
            }
            return c;
        };
    }

    /** The helper runs the work at once, on the caller's thread: every step is exact. */
    private static void direct() throws Exception {
        AtomicInteger made = new AtomicInteger();
        List<SocketChannel> all = new ArrayList<>();
        SocketPool pool = new SocketPool(counting(made, all), 4, Runnable::run);
        eq(0, pool.waiting(), "direct: nothing is made before it is asked");
        SocketChannel first = pool.take();
        yes(first == null, "direct: the first take finds nothing ready and does not wait");
        eq(1L, pool.misses(), "direct: that was a miss");
        eq(4, pool.waiting(), "direct: but a refill made the whole pool");
        eq(4, made.get(), "direct: exactly four were made");
        Set<SocketChannel> seen = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            SocketChannel c = pool.take();
            yes(c != null && c.isOpen(), "direct: a ready socket " + i + " is open");
            yes(seen.add(c), "direct: every socket is a different one");
            yes(!c.isConnected(), "direct: it is not connected (the engine does that)");
        }
        eq(4L, pool.hits(), "direct: four hits");
        eq(4, pool.waiting(), "direct: replaced after every take");
        eq(8, made.get(), "direct: eight made in all, never more than asked for");
        pool.refill();
        pool.refill();
        eq(8, made.get(), "direct: a full pool makes nothing more");
        pool.close();
        for (SocketChannel c : all) {
            if (!seen.contains(c)) {
                yes(!c.isOpen(), "direct: close() closes what waited");
            }
        }
        for (SocketChannel c : seen) {
            c.close();
        }
    }

    private static void failingMaker() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final boolean[] broken = {true};
        SocketPool pool = new SocketPool(() -> {
            calls.incrementAndGet();
            if (broken[0]) {
                throw new IOException("Android refused");
            }
            return SocketChannel.open();
        }, 3, Runnable::run);
        yes(pool.take() == null, "failing: nothing to give");
        eq(0, pool.waiting(), "failing: and nothing was kept");
        int callsWhenBroken = calls.get();
        yes(callsWhenBroken >= 1, "failing: the maker was tried");
        broken[0] = false;
        pool.take();
        eq(3, pool.waiting(), "failing: after the fault is gone the next take refills (it is not wedged)");
        SocketChannel c = pool.take();
        yes(c != null, "failing: and then a socket is ready");
        c.close();
        pool.close();
        // a maker that throws a RuntimeException is the same
        SocketPool rt = new SocketPool(() -> {
            throw new IllegalStateException("boom");
        }, 2, Runnable::run);
        yes(rt.take() == null, "failing: a runtime fault gives nothing and throws nothing");
        // a helper that refuses work (the app is closing) leaves the engine to make its own
        SocketPool gone = new SocketPool(SocketChannel::open, 2, r -> {
            throw new java.util.concurrent.RejectedExecutionException("closed");
        });
        yes(gone.take() == null, "failing: a helper that is gone gives nothing and throws nothing");
        yes(gone.take() == null, "failing: and asking again is just as quiet");
    }

    /** A maker that takes a long time: take() does not wait for it. */
    private static void slowMaker() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        ExecutorService helper = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "pool-test-helper");
            t.setDaemon(true);
            return t;
        });
        SocketPool pool = new SocketPool(() -> {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return SocketChannel.open();
        }, 2, helper);
        long t0 = System.nanoTime();
        yes(pool.take() == null, "slow: nothing ready yet");
        yes(started.await(5, TimeUnit.SECONDS), "slow: the helper began");
        yes(pool.take() == null, "slow: still nothing, and still no waiting");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        yes(ms < 1000, "slow: take() never waited for the maker (" + ms + " ms)");
        release.countDown();
        long end = System.currentTimeMillis() + 5000;
        while (pool.waiting() < 2 && System.currentTimeMillis() < end) {
            Thread.sleep(5);
        }
        eq(2, pool.waiting(), "slow: the helper filled the pool when it was done");
        pool.close();
        helper.shutdownNow();
    }

    private static void closing() throws Exception {
        final AtomicInteger made = new AtomicInteger();
        final List<SocketChannel> all = new ArrayList<>();
        // close() while the helper is in the middle of making one: that socket is closed too and nothing more is made
        final CountDownLatch inside = new CountDownLatch(1);
        final CountDownLatch go = new CountDownLatch(1);
        ExecutorService helper = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "pool-test-helper2");
            t.setDaemon(true);
            return t;
        });
        SocketPool pool = new SocketPool(() -> {
            inside.countDown();
            try {
                go.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            SocketChannel c = SocketChannel.open();
            made.incrementAndGet();
            synchronized (all) {
                all.add(c);
            }
            return c;
        }, 3, helper);
        pool.take();
        yes(inside.await(5, TimeUnit.SECONDS), "closing: the helper is inside the maker");
        pool.close();
        go.countDown();
        Thread.sleep(300);
        eq(1, made.get(), "closing: nothing more was made after close()");
        synchronized (all) {
            for (SocketChannel c : all) {
                yes(!c.isOpen(), "closing: the socket that was being made is closed");
            }
        }
        yes(pool.take() == null, "closing: a closed pool gives nothing");
        eq(0, pool.waiting(), "closing: and holds nothing");
        helper.shutdownNow();
    }

    /** Several threads take at once: nobody gets the same socket twice, and the pool never goes over its size. */
    private static void manyTakers() throws Exception {
        final AtomicInteger made = new AtomicInteger();
        final List<SocketChannel> all = new ArrayList<>();
        ExecutorService helper = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "pool-test-helper3");
            t.setDaemon(true);
            return t;
        });
        final SocketPool pool = new SocketPool(counting(made, all), 8, helper);
        pool.take();
        long end = System.currentTimeMillis() + 5000;
        while (pool.waiting() < 8 && System.currentTimeMillis() < end) {
            Thread.sleep(5);
        }
        final Set<SocketChannel> taken = java.util.Collections.synchronizedSet(new HashSet<SocketChannel>());
        final AtomicInteger dup = new AtomicInteger();
        final AtomicInteger over = new AtomicInteger();
        Thread[] ts = new Thread[6];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> {
                for (int k = 0; k < 200; k++) {
                    SocketChannel c = pool.take();
                    if (c != null && !taken.add(c)) {
                        dup.incrementAndGet();
                    }
                    if (pool.waiting() > 8) {
                        over.incrementAndGet();
                    }
                    if (c != null) {
                        try {
                            c.close();
                        } catch (IOException ignored) {
                            // closed
                        }
                    }
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) {
            t.join();
        }
        eq(0, dup.get(), "many: no socket was given to two takers");
        eq(0, over.get(), "many: the pool never went over its size");
        yes(pool.hits() + pool.misses() == 1 + 6 * 200, "many: every take was counted once");
        pool.close();
        helper.shutdownNow();
    }
}
