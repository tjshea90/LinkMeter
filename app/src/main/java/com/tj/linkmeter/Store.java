package com.tj.linkmeter;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * One device's shared storage, as far as copying, moving and deleting need it. The tablet's own storage is a {@link DiskStore};
 * the other device is a RemoteStore (the same thing over HTTP). {@link TransferEngine} works with any two of them. No Android in it.
 * Paths are "/"-separated and start with "/" (see {@link FsPath}). Every method may be slow (it can be a network call): never on the UI thread.
 */
interface Store {
    /** Thrown when the user cancels. A writer that sees it removes its half-written file before letting it go. */
    final class Cancelled extends IOException {
        Cancelled() {
            super("cancelled");
        }
    }

    /** Called as bytes move; may throw {@link Cancelled}. n is the number of bytes since the last call. */
    interface Progress {
        void bytes(long n) throws IOException;
    }

    /** One thing found by {@link #walk}: its path below the folder that was walked ("a/b.txt"). */
    final class Found {
        final String rel;
        final boolean dir;
        final long size;
        final long time;

        Found(String rel, boolean dir, long size, long time) {
            this.rel = rel;
            this.dir = dir;
            this.size = size;
            this.time = time;
        }
    }

    /** Which device this is. Two stores with the same id are the same storage (a copy between them stays on one device). */
    String id();

    /** The entry at a path, or null if there is none. */
    FsEntry stat(String path) throws IOException;

    /** The entries of a folder, unsorted. counts: fill {@link FsEntry#count} for folders. */
    List<FsEntry> list(String dir, boolean counts) throws IOException;

    /** The file's bytes from offset on. The caller closes the stream. */
    InputStream read(String path, long offset) throws IOException;

    /**
     * Writes len bytes from src to path: into a temporary file first, so a failed or cancelled write leaves nothing behind, and
     * the finished file replaces whatever file was there. Keeps the modification time. The folder must exist. Throws if path is a folder.
     */
    void write(String path, InputStream src, long len, long mtimeSec, Progress p) throws IOException;

    /** Creates the folder and any missing parents. Fine if it already exists. */
    void mkdirs(String dir) throws IOException;

    /** Deletes a file, or a folder with everything in it. Never the storage root. */
    void delete(String path) throws IOException;

    /** Moves within this device in one step (instant, no copying). false = could not, the caller copies and deletes instead. */
    boolean rename(String from, String to) throws IOException;

    /** Copies within this device by the device itself (so nothing crosses the hotspot). false = not supported here. */
    boolean copyWithin(String from, String to, Progress p) throws IOException;

    /**
     * Everything below a folder, folders before what is in them, in name order. The default asks folder by folder; a remote
     * store answers with one request. Stops with an IOException past 200000 entries.
     */
    default List<Found> walk(String root) throws IOException {
        List<Found> out = new ArrayList<>();
        walkInto(root, "", out);
        return out;
    }

    /** Helper for {@link #walk}. */
    default void walkInto(String dir, String relDir, List<Found> out) throws IOException {
        List<FsEntry> kids = list(dir, false);
        FileSort.sort(kids, FileSort.Order.NAME, true);
        for (FsEntry e : kids) {
            if (out.size() > 200000) {
                throw new IOException("too many files to copy in one go");
            }
            String rel = relDir.isEmpty() ? e.name : relDir + "/" + e.name;
            out.add(new Found(rel, e.dir, e.size, e.time));
            if (e.dir) {
                walkInto(FsPath.join(dir, e.name), rel, out);
            }
        }
    }
}
