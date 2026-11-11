package com.tj.linkmeter;

import java.io.IOException;
import java.io.InputStream;

/** {@link RemoteStore.Link} over the real connection to the other device: its address and the shared secret. No Android in it. */
final class PeerLink implements RemoteStore.Link {
    private final String host;
    private final String token;
    private final int connectMs;

    PeerLink(String host, String token) {
        this(host, token, 3000);
    }

    /** {@code connectMs}: how long to wait for the connection itself (a phone that just woke up can need a few seconds). */
    PeerLink(String host, String token, int connectMs) {
        this.host = host;
        this.token = token;
        this.connectMs = connectMs;
    }

    @Override
    public Client.Resp call(String method, String target, int readMs) throws IOException {
        return Client.call(host, method, target, token, connectMs, readMs);
    }

    @Override
    public Client.Stream get(String target, long offset) throws IOException {
        return Client.get(host, target, token, connectMs, 30000, offset);
    }

    @Override
    public Client.Resp put(String target, InputStream src, long len, Client.Progress p) throws IOException {
        return Client.put(host, target, token, src, len, p);
    }
}
