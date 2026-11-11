package com.tj.linkmeter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * No Android in here (tested in tools/test/TextsLogicTest.java). A {@link BlobStore} that keeps every blob sealed: a first byte that
 * says so, a version, then what {@link Sealer} made of the bytes with the blob's name bound to them (a blob copied under another
 * name does not open). A blob that is plain JSON, from before sealing existed, is still read (and is sealed by the next write).
 */
final class SealedBlobStore implements BlobStore {
    static final byte MAGIC = (byte) 0xA7;
    static final byte VERSION = 1;

    private final BlobStore inner;
    private final Sealer sealer;

    SealedBlobStore(BlobStore inner, Sealer sealer) {
        this.inner = inner;
        this.sealer = sealer;
    }

    private static byte[] aad(String name) {
        return ("linkmeter-texts-v1|" + name).getBytes(StandardCharsets.UTF_8);
    }

    /** The store underneath (for tests: to open the same files with another key). */
    BlobStore inner() {
        return inner;
    }

    @Override
    public byte[] read(String name) throws IOException {
        byte[] raw = inner.read(name);
        if (raw == null) {
            return null;
        }
        if (raw.length > 2 && raw[0] == MAGIC && raw[1] == VERSION) {
            byte[] body = new byte[raw.length - 2];
            System.arraycopy(raw, 2, body, 0, body.length);
            return sealer.open(body, aad(name));
        }
        if (raw.length > 0 && (raw[0] == '{' || raw[0] == '[')) {
            return raw; // written before sealing existed
        }
        throw new IOException("that saved blob is not readable");
    }

    @Override
    public void write(String name, byte[] data) throws IOException {
        byte[] sealed = sealer.seal(data, aad(name));
        byte[] out = new byte[sealed.length + 2];
        out[0] = MAGIC;
        out[1] = VERSION;
        System.arraycopy(sealed, 0, out, 2, sealed.length);
        inner.write(name, out);
    }

    @Override
    public void delete(String name) throws IOException {
        inner.delete(name);
    }

    @Override
    public List<String> names() throws IOException {
        return inner.names();
    }
}
