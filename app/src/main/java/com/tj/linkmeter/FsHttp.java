package com.tj.linkmeter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Puts {@link FsApi} on the wire: reads what {@link Http.Req} carries, writes the reply. No Android in it, so tests use the same code as the phone. */
final class FsHttp {
    private FsHttp() {}

    static void serve(FsApi api, Http.Req r, OutputStream out) throws IOException {
        write(api.handle(r.path, r.query, r.rangeStart, r.body, r.length), out);
    }

    /** Sends a reply: a small body, or a file (a stream with its length), or the tail of a file for a ranged request. */
    static void write(FsApi.Reply rep, OutputStream out) throws IOException {
        if (rep.stream == null) {
            Http.send(out, rep.code, rep.type, rep.body);
            return;
        }
        try (InputStream in = rep.stream) {
            if (rep.code == 206) {
                Http.headPartial(out, rep.type, rep.rangeStart, rep.total);
            } else {
                Http.head(out, rep.code, rep.type, rep.length);
            }
            byte[] buf = new byte[32 * 1024];
            long left = rep.length;
            int n;
            while (left > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                out.write(buf, 0, n);
                left -= n;
            }
            out.flush();
        }
    }
}
