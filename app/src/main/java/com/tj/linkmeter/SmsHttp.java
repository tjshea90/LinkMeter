package com.tj.linkmeter;

import java.io.IOException;
import java.io.OutputStream;

/** Puts {@link SmsApi} on the wire. No Android in it, so tests use the same code as the phone. */
final class SmsHttp {
    private SmsHttp() {}

    static void serve(SmsApi api, Http.Req r, OutputStream out) throws IOException {
        FsHttp.write(api.handle(r.path, r.query, r.body, r.length), out);
    }
}
