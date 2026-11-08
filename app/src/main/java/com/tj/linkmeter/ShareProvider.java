package com.tj.linkmeter;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lets another app read one file that this app chose to hand over (opening a file with another app, sharing it). The same job as
 * androidx FileProvider, in a few lines, because the app uses no libraries. Only files registered through {@link #uriFor} can be
 * read, each under a random token, and only for reading; the provider is not exported, so other apps get access only through the
 * temporary grant that comes with the intent.
 */
public final class ShareProvider extends ContentProvider {
    private static final Map<String, File> FILES = new LinkedHashMap<>();
    private static final SecureRandom RND = new SecureRandom();

    static String authority(Context c) {
        return c.getPackageName() + ".share";
    }

    /** A content:// address through which the file can be read by the app the caller sends it to (with FLAG_GRANT_READ_URI_PERMISSION). */
    static Uri uriFor(Context c, File f) {
        byte[] b = new byte[12];
        RND.nextBytes(b);
        StringBuilder token = new StringBuilder();
        for (byte x : b) {
            token.append(String.format("%02x", x));
        }
        synchronized (FILES) {
            FILES.put(token.toString(), f);
            while (FILES.size() > 100) {
                FILES.remove(FILES.keySet().iterator().next());
            }
        }
        return new Uri.Builder().scheme("content").authority(authority(c)).appendPath("f").appendPath(token.toString())
                .appendPath(f.getName()).build();
    }

    private static File fileOf(Uri uri) throws FileNotFoundException {
        List<String> seg = uri.getPathSegments();
        File f = null;
        if (seg.size() >= 3 && seg.get(0).equals("f")) {
            synchronized (FILES) {
                f = FILES.get(seg.get(1));
            }
        }
        if (f == null || !f.isFile()) {
            throw new FileNotFoundException("no such file");
        }
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        List<String> seg = uri.getPathSegments();
        return seg.size() >= 3 ? FileKind.mime(seg.get(2)) : null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("read only");
        }
        return ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = fileOf(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            row[i] = OpenableColumns.DISPLAY_NAME.equals(cols[i]) ? (Object) f.getName()
                    : OpenableColumns.SIZE.equals(cols[i]) ? (Object) f.length() : null;
        }
        c.addRow(row);
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }
}
