package com.tj.linkmeter;

import java.io.IOException;
import java.io.InputStream;

/** {@link RemoteStore.Link} over the real connection to the other device: its address and the shared secret. No Android in it. */
final class PeerLink implements RemoteStore.Link {
    private final String host;
    private final String token;

    PeerLink(String host, String token) {
        this.host = host;
        this.token = token;
    }

    @Override
    public Client.Resp call(String method, String target, int readMs) throws IOException {
        return Client.call(host, method, target, token, 3000, readMs);
    }

    @Override
    public Client.Stream get(String target, long offset) throws IOException {
        return Client.get(host, target, token, 3000, 30000, offset);
    }

    @Override
    public Client.Resp put(String target, InputStream src, long len, Client.Progress p) throws IOException {
        return Client.put(host, target, token, src, len, p);
    }
}
