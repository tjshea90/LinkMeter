package com.tj.linkmeter;

import android.content.Context;
import java.io.IOException;
import java.io.OutputStream;

/** Phone only: the one set of Calls objects in this process and the entry point for /call/* requests (see CallApi). */
final class CallService {
    private CallService() {}

    private static CallBackend backend;
    private static CallApi api;

    private static synchronized CallBackend backend(Context c) {
        if (backend == null) {
            Context app = c.getApplicationContext();
            backend = new CallBackend(app, SmsService.backend(app));
        }
        return backend;
    }

    private static synchronized CallApi api(Context c) {
        if (api == null) {
            api = new CallApi(backend(c), System::currentTimeMillis);
        }
        return api;
    }

    /** Starts listening to the phone's state (calls that ring while the tablet is not asking are still seen). Called when the phone's service starts. */
    static void ensure(Context c) {
        if (Cfg.callsOn(c)) {
            api(c);
            backend(c).start();
        }
    }

    /** Answers every /call/* request. The caller has already checked that the other device is paired. */
    static void serve(Context c, Http.Req r, OutputStream out) throws IOException {
        api(c); // makes sure the change gate is wired to the backend
        FsHttp.write(api(c).handle(r.path, r.query, r.body, r.length), out);
    }
}
