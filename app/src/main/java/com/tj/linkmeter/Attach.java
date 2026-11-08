package com.tj.linkmeter;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends photos and files from this tablet to the phone so they can be attached in ANY app on it while the phone is used remotely.
 * They are put where every picker on the phone looks: photos in Pictures/LinkMeter, videos in Movies/LinkMeter, music in
 * Music/LinkMeter, the rest in Download/LinkMeter. The phone tells its media database about each file as it arrives, so they show
 * up at once in the Gallery, the photo picker and the Files app (under Recent). No per-app code is needed: every app's attach
 * button opens one of those pickers.
 */
final class Attach {
    private Attach() {}

    /** One thing to send: a file in this tablet's storage (path) or something the system picker gave (uri). */
    static final class Item {
        final String name;
        final long size;
        final long mtime;
        final String path;
        final Uri uri;

        Item(String name, long size, long mtime, String path, Uri uri) {
            this.name = name;
            this.size = size;
            this.mtime = mtime;
            this.path = path;
            this.uri = uri;
        }
    }

    static final class Result {
        int sent;
        long bytes;
        boolean cancelled;
        final List<String> where = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
    }

    interface Listener {
        /** On the UI thread. */
        void progress(String text, int permille);

        /** On the UI thread. */
        void done(Result r);
    }

    static final class Job {
        volatile boolean cancel;

        void cancel() {
            cancel = true;
        }
    }

    /** The phone folder a file of this kind goes to. */
    static String folderFor(String name) {
        switch (FileKind.of(name, false)) {
            case IMAGE:
                return "/Pictures/LinkMeter";
            case VIDEO:
                return "/Movies/LinkMeter";
            case AUDIO:
                return "/Music/LinkMeter";
            default:
                return "/Download/LinkMeter";
        }
    }

    /** What the system file picker returned (one file or several). */
    static List<Item> fromPicker(Context c, Intent data) {
        List<Item> out = new ArrayList<>();
        if (data == null) {
            return out;
        }
        ClipData cd = data.getClipData();
        if (cd != null) {
            for (int i = 0; i < cd.getItemCount(); i++) {
                addUri(c, cd.getItemAt(i).getUri(), out);
            }
        } else if (data.getData() != null) {
            addUri(c, data.getData(), out);
        }
        return out;
    }

    private static void addUri(Context c, Uri u, List<Item> out) {
        if (u == null) {
            return;
        }
        String name = "";
        long size = -1;
        long mtime = 0;
        try (Cursor q = c.getContentResolver().query(u, null, null, null, null)) {
            if (q != null && q.moveToFirst()) {
                int ni = q.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ni >= 0 && !q.isNull(ni)) {
                    name = q.getString(ni);
                }
                int si = q.getColumnIndex(OpenableColumns.SIZE);
                if (si >= 0 && !q.isNull(si)) {
                    size = q.getLong(si);
                }
                int mi = q.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                if (mi >= 0 && !q.isNull(mi)) {
                    mtime = q.getLong(mi) / 1000;
                }
            }
        } catch (RuntimeException ignored) {
            // the picker gave no details: the name is made up below
        }
        if (name == null || name.isEmpty()) {
            String last = u.getLastPathSegment();
            name = last == null || last.isEmpty() ? "file" : last;
        }
        out.add(new Item(name.replace('/', '_'), size, mtime, "", u));
    }

    /** Sends the items on a background thread. Cancel through the returned job. */
    static Job send(final MainActivity act, final List<Item> items, final Listener l) {
        final Job job = new Job();
        Thread t = new Thread(() -> run(act, items, l, job), "linkmeter-attach");
        t.setDaemon(true);
        t.start();
        return job;
    }

    private static void run(final MainActivity act, List<Item> items, final Listener l, final Job job) {
        final Result r = new Result();
        long total = 0;
        for (Item it : items) {
            total += Math.max(0, it.size);
        }
        final long grand = total;
        final long[] done = {0};
        final long[] lastPost = {0};
        DiskStore local = null;
        try {
            String host = Net.peerHost(act);
            if (host.isEmpty()) {
                throw new IOException(FileBrowser.noPeer(act));
            }
            final RemoteStore rs = new RemoteStore(new PeerLink(host, Net.peerToken(act)), "other");
            int n = 0;
            for (Item it : items) {
                if (job.cancel) {
                    r.cancelled = true;
                    break;
                }
                n++;
                final int idx = n;
                final int count = items.size();
                final String name = it.name;
                File tmp = null;
                try {
                    final String dir = folderFor(it.name);
                    rs.mkdirs(dir);
                    String unique = FsPath.unique(it.name, false, nm -> {
                        try {
                            return rs.stat(FsPath.join(dir, nm)) != null;
                        } catch (IOException e) {
                            return true;
                        }
                    });
                    String dest = FsPath.join(dir, unique);
                    InputStream in;
                    long size = it.size;
                    long mtime = it.mtime;
                    if (!it.path.isEmpty()) {
                        if (local == null) {
                            local = new DiskStore(Files.root(), "this");
                        }
                        FsEntry fe = local.stat(it.path);
                        if (fe == null) {
                            throw new IOException("the file is gone");
                        }
                        size = fe.size;
                        mtime = fe.time;
                        in = local.read(it.path, 0);
                    } else if (size < 0) {
                        tmp = copyToCache(act, it);
                        size = tmp.length();
                        in = new FileInputStream(tmp);
                    } else {
                        in = act.getContentResolver().openInputStream(it.uri);
                    }
                    if (in == null) {
                        throw new IOException("couldn't read it");
                    }
                    try (InputStream src = in) {
                        rs.write(dest, src, size, mtime, b -> {
                            if (job.cancel) {
                                throw new Store.Cancelled();
                            }
                            done[0] += b;
                            long now = SystemClock.elapsedRealtime();
                            if (now - lastPost[0] > 150) {
                                lastPost[0] = now;
                                final int pm = grand > 0 ? (int) Math.min(1000, done[0] * 1000 / grand) : 0;
                                final String text = "Sending " + name + (count > 1 ? " (" + idx + " of " + count + ")" : "") + "  ·  "
                                        + FsFormat.size(done[0]) + (grand > 0 ? " of " + FsFormat.size(grand) : "");
                                act.ui.post(() -> l.progress(text, pm));
                            }
                        });
                    }
                    r.sent++;
                    r.bytes += size;
                    r.where.add(dest);
                } catch (Store.Cancelled c) {
                    r.cancelled = true;
                    break;
                } catch (IOException e) {
                    r.errors.add(name + ": " + e.getMessage());
                } finally {
                    if (tmp != null) {
                        tmp.delete();
                    }
                }
            }
        } catch (IOException e) {
            r.errors.add(String.valueOf(e.getMessage()));
        }
        act.ui.post(() -> l.done(r));
    }

    /** For a picked file whose size the picker did not say: copy it aside to learn it. */
    private static File copyToCache(Context c, Item it) throws IOException {
        File dir = new File(c.getCacheDir(), "attach");
        dir.mkdirs();
        File f = File.createTempFile("pick", ".tmp", dir);
        try (InputStream in = c.getContentResolver().openInputStream(it.uri); OutputStream out = new FileOutputStream(f)) {
            if (in == null) {
                throw new IOException("couldn't read it");
            }
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            f.delete();
            throw e;
        }
        return f;
    }
}
