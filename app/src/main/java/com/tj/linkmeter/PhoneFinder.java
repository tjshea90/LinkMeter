package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * No Android in here (tested in tools/test/PhoneFinderTest.java over the loopback network). Tablet side: finds the phone on the tablet's
 * own hotspot. The phone has joined that Wi-Fi network and got an address from it, and nobody tells the tablet which one, so every host
 * of the hotspot's subnet is asked the question only a paired LinkMeter phone can answer (GET /boot with the tablet's secret): the first
 * that answers like one is the phone. Blocking: call it off the UI thread.
 */
final class PhoneFinder {
    private PhoneFinder() {}

    /** The phone's address and what it said (the JSON of GET /boot). */
    static final class Found {
        final String host;
        final String body;

        Found(String host, String body) {
            this.host = host;
            this.body = body;
        }
    }

    /** Asks one address. null unless it is a LinkMeter phone that knows this tablet (200 and a /boot answer). */
    static Found ask(String host, String token, int connectMs, int readMs) {
        try {
            Client.Resp r = Client.call(host, "GET", "/boot", token, connectMs, readMs);
            if (r.code == 200 && r.body != null && r.body.contains("\"priv\"")) {
                return new Found(host, r.body);
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            // nothing there, or not ours
        }
        return null;
    }

    /** Asks every address on several threads and returns the first phone that answers, or null. Stops asking as soon as one does. */
    static Found find(List<String> hosts, final String token, final int connectMs, final int readMs, int threads) {
        if (hosts.isEmpty()) {
            return null;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(threads, hosts.size())), new java.util.concurrent.ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "linkmeter-find-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        try {
            CompletionService<Found> done = new ExecutorCompletionService<>(pool);
            List<Future<Found>> futures = new ArrayList<>();
            for (final String h : hosts) {
                futures.add(done.submit(new java.util.concurrent.Callable<Found>() {
                    @Override
                    public Found call() {
                        return ask(h, token, connectMs, readMs);
                    }
                }));
            }
            for (int i = 0; i < hosts.size(); i++) {
                try {
                    Found f = done.take().get();
                    if (f != null) {
                        return f;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                } catch (java.util.concurrent.ExecutionException e) {
                    // that address failed in an odd way: the next one
                }
            }
            return null;
        } finally {
            pool.shutdownNow();
        }
    }
}
