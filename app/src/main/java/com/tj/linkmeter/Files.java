package com.tj.linkmeter;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Android's side of the file service: where shared storage is, whether this app may use it, and the entry point for the other
 * device's /fs/* requests. The listing, copying and the rest are plain Java: see {@link FsApi}, {@link DiskStore}, {@link TransferEngine}.
 */
final class Files {
    private Files() {}

    /** Shared storage ("/storage/emulated/0"). */
    static File root() {
        return Environment.getExternalStorageDirectory();
    }

    /** Does the app have permission to read and write shared storage? */
    static boolean hasAccess(Context c) {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static FsApi api;

    /** The file service for this device's shared storage (one per process). */
    private static synchronized FsApi api(Context c) throws IOException {
        if (api == null) {
            api = new FsApi(new DiskStore(root(), "this"), new AndroidMedia(c), new FsJobs());
        }
        return api;
    }

    /** Answers every /fs/* request (see FsApi for the list). The caller has already checked that the other device is paired. */
    static void serve(Context c, Http.Req r, OutputStream out) throws IOException {
        if (!hasAccess(c)) {
            Http.err(out, 403, "This device has not allowed LinkMeter to use its storage yet.");
            return;
        }
        FsHttp.serve(api(c), r, out);
    }
}
