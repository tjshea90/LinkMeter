package com.tj.linkmeter;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.LongSupplier;

/** No Android in here. A {@link RemoteStore.Link} that writes what each request cost into a {@link LinkLog} (the waiting stamp request only when it failed). */
final class TimedLink implements RemoteStore.Link {
    private final RemoteStore.Link inner;
    private final LinkLog log;
    private final LongSupplier clock;

    TimedLink(RemoteStore.Link inner, LinkLog log, LongSupplier clock) {
        this.inner = inner;
        this.log = log;
        this.clock = clock;
    }

    @Override
    public Client.Resp call(String method, String target, int readMs) throws IOException {
        long t0 = clock.getAsLong();
        String route = LinkLog.route(target);
        boolean waits = route.equals("/sms/stamp");
        try {
            Client.Resp r = inner.call(method, target, readMs);
            if (!waits || r.code >= 400) {
                log.add(t0, method + " " + route, clock.getAsLong() - t0, r.code >= 400 ? "answered " + r.code : "ok");
            }
            return r;
        } catch (IOException e) {
            log.add(t0, method + " " + route, clock.getAsLong() - t0, LinkLog.outcome(e));
            throw e;
        }
    }

    @Override
    public Client.Stream get(String target, long offset) throws IOException {
        long t0 = clock.getAsLong();
        try {
            Client.Stream s = inner.get(target, offset);
            log.add(t0, "GET " + LinkLog.route(target), clock.getAsLong() - t0, s.code >= 400 ? "answered " + s.code : "ok");
            return s;
        } catch (IOException e) {
            log.add(t0, "GET " + LinkLog.route(target), clock.getAsLong() - t0, LinkLog.outcome(e));
            throw e;
        }
    }

    @Override
    public Client.Resp put(String target, InputStream src, long len, Client.Progress p) throws IOException {
        long t0 = clock.getAsLong();
        try {
            Client.Resp r = inner.put(target, src, len, p);
            log.add(t0, "PUT " + LinkLog.route(target), clock.getAsLong() - t0, r.code >= 400 ? "answered " + r.code : "ok");
            return r;
        } catch (IOException e) {
            log.add(t0, "PUT " + LinkLog.route(target), clock.getAsLong() - t0, LinkLog.outcome(e));
            throw e;
        }
    }
}
