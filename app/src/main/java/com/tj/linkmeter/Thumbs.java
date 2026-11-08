package com.tj.linkmeter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/**
 * Thumbnails for the file lists, built for a low-memory tablet: a small in-memory cache (4 to 10 MB), two worker threads that take
 * the NEWEST request first (what is on screen now, not what was scrolled past), and a disk cache for thumbnails fetched from the
 * other device so they are not fetched twice. A file without a thumbnail is remembered, so scrolling does not retry it.
 */
final class Thumbs {
    /** Makes a thumbnail. key identifies the file and its version; the result may be null. */
    interface Loader {
        Bitmap load(String key, String path, FileKind kind, int px) throws Exception;
    }

    private static Thumbs instance;

    static synchronized Thumbs get(Context c) {
        if (instance == null) {
            instance = new Thumbs(c.getApplicationContext());
        }
        return instance;
    }

    private final LruCache<String, Bitmap> cache;
    private final Set<String> missing = new HashSet<>();
    private final ArrayDeque<Runnable> stack = new ArrayDeque<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final File diskDir;
    private boolean pruned;

    private Thumbs(Context c) {
        int mem = (int) Math.min(10L << 20, Math.max(4L << 20, Runtime.getRuntime().maxMemory() / 12));
        cache = new LruCache<String, Bitmap>(mem) {
            @Override
            protected int sizeOf(String key, Bitmap b) {
                return b.getByteCount();
            }
        };
        diskDir = new File(c.getCacheDir(), "thumbs");
        for (int i = 0; i < 2; i++) {
            Thread t = new Thread(this::work, "linkmeter-thumbs");
            t.setDaemon(true);
            t.start();
        }
    }

    private void work() {
        while (true) {
            Runnable r;
            synchronized (stack) {
                while (stack.isEmpty()) {
                    try {
                        stack.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                r = stack.pollLast();
            }
            try {
                r.run();
            } catch (RuntimeException | OutOfMemoryError ignored) {
                // one bad file must not stop the worker
            }
        }
    }

    /**
     * Shows the thumbnail in target when it is ready. The caller has already put the file-type icon in target; target is only
     * changed if it still wants this key by then (a recycled list row may want another file).
     */
    void load(final ImageView target, final String key, final String path, final FileKind kind, final int px, final Loader loader) {
        target.setTag(key);
        Bitmap b = cache.get(key);
        if (b != null) {
            target.setImageBitmap(b);
            return;
        }
        synchronized (this) {
            if (missing.contains(key)) {
                return;
            }
        }
        Runnable task = () -> {
            if (!key.equals(target.getTag())) {
                return; // scrolled away before it was this request's turn
            }
            Bitmap made = null;
            try {
                made = loader.load(key, path, kind, px);
            } catch (Exception ignored) {
                // no thumbnail for this one
            }
            if (made == null) {
                synchronized (Thumbs.this) {
                    if (missing.size() > 3000) {
                        missing.clear();
                    }
                    missing.add(key);
                }
                return;
            }
            cache.put(key, made);
            final Bitmap shown = made;
            ui.post(() -> {
                if (key.equals(target.getTag())) {
                    target.setImageBitmap(shown);
                }
            });
        };
        synchronized (stack) {
            stack.addLast(task);
            while (stack.size() > 400) {
                stack.pollFirst();
            }
            stack.notify();
        }
    }

    /** A loader for the other device: asks it for a small JPEG, keeping a copy on disk. */
    Loader remote(final RemoteStore rs) {
        return (key, path, kind, px) -> {
            File f = new File(diskDir, sha1(key) + ".jpg");
            if (f.isFile() && f.length() > 0) {
                Bitmap b = BitmapFactory.decodeFile(f.getPath());
                if (b != null) {
                    f.setLastModified(System.currentTimeMillis());
                    return b;
                }
            }
            byte[] jpg = rs.thumbnail(path, px);
            if (jpg == null || jpg.length == 0) {
                return null;
            }
            Bitmap b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
            if (b != null) {
                save(f, jpg);
            }
            return b;
        };
    }

    private void save(File f, byte[] jpg) {
        try {
            synchronized (this) {
                if (!pruned) {
                    pruned = true;
                    prune();
                }
            }
            if (!diskDir.isDirectory() && !diskDir.mkdirs()) {
                return;
            }
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(jpg);
            }
        } catch (IOException ignored) {
            // the next request fetches it again
        }
    }

    /** Keeps the disk cache under about 40 MB: the least recently used files go first. */
    private void prune() {
        File[] fs = diskDir.listFiles();
        if (fs == null) {
            return;
        }
        long total = 0;
        for (File f : fs) {
            total += f.length();
        }
        if (total < 40L << 20) {
            return;
        }
        Arrays.sort(fs, Comparator.comparingLong(File::lastModified));
        for (File f : fs) {
            if (total < 25L << 20) {
                break;
            }
            total -= f.length();
            f.delete();
        }
    }

    static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** For the Information box: how many thumbnails are held in memory. */
    String report() {
        return cache.size() / 1024 + " KB of " + cache.maxSize() / 1024 + " KB";
    }
}
