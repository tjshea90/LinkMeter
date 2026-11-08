package com.tj.linkmeter;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

/** Phone only: the one set of Texts objects in this process (the tablet's record of what it sent, the staging area, the API) and the entry point for /sms/* requests. */
final class SmsService {
    private SmsService() {}

    private static SmsOutbox outbox;
    private static SmsApi api;

    /** The record of the texts the tablet sent (in the app's private files). */
    static synchronized SmsOutbox outbox(Context c) throws IOException {
        if (outbox == null) {
            outbox = new SmsOutbox(new File(c.getApplicationContext().getFilesDir(), "sms"), System::currentTimeMillis);
        }
        return outbox;
    }

    private static synchronized SmsApi api(Context c) throws IOException {
        if (api == null) {
            Context app = c.getApplicationContext();
            api = new SmsApi(new SmsBackend(app), outbox(app), new SmsStaging(new File(app.getCacheDir(), "sms-staging"), System::currentTimeMillis),
                    System::currentTimeMillis);
        }
        return api;
    }

    /** Answers every /sms/* request (see SmsApi). The caller has already checked that the other device is paired. */
    static void serve(Context c, Http.Req r, OutputStream out) throws IOException {
        SmsHttp.serve(api(c), r, out);
    }
}
