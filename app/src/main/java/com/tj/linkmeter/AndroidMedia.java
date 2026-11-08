package com.tj.linkmeter;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** The Android-only parts of the file service: thumbnails, the newest files from the media database, and telling that database about changes. */
final class AndroidMedia implements FsApi.Media {
    private final Context ctx;

    AndroidMedia(Context c) {
        ctx = c.getApplicationContext();
    }

    @Override
    public byte[] thumbnail(File f, FileKind kind, int px) {
        android.graphics.Bitmap b = ThumbMaker.bitmap(ctx, f, kind, px);
        if (b == null) {
            return null;
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream(16 * 1024);
        b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bo);
        b.recycle();
        return bo.toByteArray();
    }

    @Override
    @SuppressWarnings("deprecation")
    public List<FsEntry> recent(String kind, int limit) {
        List<FsEntry> out = new ArrayList<>();
        String root = Environment.getExternalStorageDirectory().getPath();
        String sel;
        switch (kind) {
            case "images":
                sel = MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE;
                break;
            case "videos":
                sel = MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
                break;
            case "audio":
                sel = MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO;
                break;
            case "docs":
                sel = MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_NONE
                        + " AND (" + MediaStore.Files.FileColumns.MIME_TYPE + " LIKE 'application/%' OR "
                        + MediaStore.Files.FileColumns.MIME_TYPE + " LIKE 'text/%')";
                break;
            default:
                sel = MediaStore.Files.FileColumns.MEDIA_TYPE + " IN (1,2,3)";
                break;
        }
        Bundle args = new Bundle();
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel);
        args.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, new String[] {MediaStore.Files.FileColumns.DATE_MODIFIED});
        args.putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);
        args.putInt(ContentResolver.QUERY_ARG_LIMIT, limit * 2); // some rows are not usable (hidden, outside storage): ask for extra
        Uri uri = MediaStore.Files.getContentUri("external");
        String[] proj = {MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.DATE_MODIFIED};
        try (Cursor c = ctx.getContentResolver().query(uri, proj, args, null)) {
            while (c != null && c.moveToNext() && out.size() < limit) {
                String data = c.getString(0);
                if (data == null || !data.startsWith(root + "/")) {
                    continue;
                }
                File f = new File(data);
                String name = f.getName();
                if (name.startsWith(".") || !f.isFile()) {
                    continue; // pending or trashed items, and rows whose file is gone
                }
                FsEntry e = new FsEntry(name, false, c.getLong(1), c.getLong(2));
                e.path = FsPath.clean(data.substring(root.length()));
                out.add(e);
            }
        } catch (RuntimeException e) {
            // no access to the media database: the screen just shows nothing
        }
        return out;
    }

    @Override
    public void changed(File f) {
        try {
            MediaScannerConnection.scanFile(ctx, new String[] {f.getAbsolutePath()}, null, null);
        } catch (RuntimeException ignored) {
            // the gallery will notice the file later
        }
    }
}
