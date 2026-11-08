package com.tj.linkmeter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Store} on a real folder (shared storage on a device, a temp folder in the tests). A path may never leave the root,
 * not even through ".." or a symbolic link. Plain java.io: no Android in it.
 */
final class DiskStore implements Store {
    /** A file being written has this ending, and is hidden from listings, until it is complete. */
    static final String PART = ".lmpart";
    /** Most entries one listing returns. */
    static final int MAX_LIST = 12000;

    private final File root;
    private final String id;
    /** How many bytes are copied at a time (a field so tests can make cancelling in the middle of a file easy to hit). */
    int bufferSize = 64 * 1024;
    /** Set when the last listing was cut at {@link #MAX_LIST}. */
    volatile boolean truncated;

    DiskStore(File root, String id) throws IOException {
        this.root = root.getCanonicalFile();
        this.id = id;
    }

    File root() {
        return root;
    }

    @Override
    public String id() {
        return id;
    }

    /** The File for a path inside the storage; refuses anything that ends up outside it. */
    File resolve(String path) throws IOException {
        File f = new File(root, FsPath.clean(path).substring(1)).getCanonicalFile();
        String rp = root.getPath();
        if (!f.getPath().equals(rp) && !f.getPath().startsWith(rp + File.separator)) {
            throw new IOException("outside storage");
        }
        return f;
    }

    private static boolean isLink(File f) {
        try {
            File parent = f.getAbsoluteFile().getParentFile();
            return parent != null && !new File(parent.getCanonicalFile(), f.getName()).equals(f.getCanonicalFile());
        } catch (IOException e) {
            return true;
        }
    }

    private static FsEntry entry(File f, boolean counts) {
        FsEntry e = new FsEntry();
        e.name = f.getName();
        e.dir = f.isDirectory();
        e.size = e.dir ? 0 : f.length();
        e.time = f.lastModified() / 1000;
        if (e.dir && counts) {
            String[] kids = f.list();
            e.count = kids == null ? -1 : kids.length;
        }
        return e;
    }

    @Override
    public FsEntry stat(String path) throws IOException {
        File f = resolve(path);
        if (!f.exists()) {
            return null;
        }
        FsEntry e = entry(f, false);
        if (f.equals(root)) {
            e.name = "";
        }
        return e;
    }

    @Override
    public List<FsEntry> list(String dir, boolean counts) throws IOException {
        File d = resolve(dir);
        if (!d.isDirectory()) {
            throw new IOException("not a folder");
        }
        File[] fs = d.listFiles();
        if (fs == null) {
            throw new IOException("can't read this folder (is All files access allowed?)");
        }
        truncated = false;
        List<FsEntry> out = new ArrayList<>();
        for (File f : fs) {
            if (f.getName().endsWith(PART)) {
                continue;
            }
            if (out.size() >= MAX_LIST) {
                truncated = true;
                break;
            }
            out.add(entry(f, counts));
        }
        return out;
    }

    @Override
    public InputStream read(String path, long offset) throws IOException {
        File f = resolve(path);
        if (!f.isFile()) {
            throw new IOException("not a file");
        }
        FileInputStream in = new FileInputStream(f);
        try {
            if (offset > 0) {
                in.getChannel().position(offset);
            }
        } catch (IOException e) {
            in.close();
            throw e;
        }
        return in;
    }

    @Override
    public void write(String path, InputStream src, long len, long mtimeSec, Progress p) throws IOException {
        File dest = resolve(path);
        File dir = dest.getParentFile();
        if (dir == null || !dir.isDirectory()) {
            throw new IOException("the folder doesn't exist");
        }
        if (dest.isDirectory()) {
            throw new IOException("a folder with this name is in the way");
        }
        if (len > dir.getUsableSpace() && dir.getUsableSpace() > 0) {
            throw new IOException("not enough free space (" + FsFormat.size(len) + " needed)");
        }
        File part = new File(dir, dest.getName() + PART);
        boolean done = false;
        try {
            long left = len;
            byte[] buf = new byte[Math.max(512, bufferSize)];
            try (OutputStream out = new FileOutputStream(part)) {
                while (left > 0) {
                    int n = src.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) {
                        throw new IOException("the file was cut short");
                    }
                    out.write(buf, 0, n);
                    left -= n;
                    if (p != null) {
                        p.bytes(n);
                    }
                }
            }
            if (part.length() != len) {
                throw new IOException("the file was cut short");
            }
            if (dest.exists() && !dest.delete()) {
                throw new IOException("couldn't replace " + dest.getName());
            }
            if (!part.renameTo(dest)) {
                throw new IOException("couldn't finish " + dest.getName());
            }
            done = true;
            if (mtimeSec > 0) {
                dest.setLastModified(mtimeSec * 1000);
            }
        } finally {
            if (!done) {
                part.delete();
            }
        }
    }

    @Override
    public void mkdirs(String dir) throws IOException {
        File d = resolve(dir);
        if (d.isDirectory()) {
            return;
        }
        if (d.exists() || !d.mkdirs()) {
            throw new IOException("couldn't create the folder " + FsPath.name(dir));
        }
    }

    @Override
    public void delete(String path) throws IOException {
        File f = resolve(path);
        if (f.equals(root)) {
            throw new IOException("won't delete the storage root");
        }
        if (!f.exists()) {
            throw new IOException("not found");
        }
        deleteTree(f);
    }

    private void deleteTree(File f) throws IOException {
        if (f.isDirectory() && !isLink(f)) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteTree(k);
                }
            }
        }
        if (!f.delete()) {
            throw new IOException("couldn't delete " + f.getName());
        }
    }

    @Override
    public boolean rename(String from, String to) throws IOException {
        File a = resolve(from);
        File b = resolve(to);
        if (a.equals(root) || b.exists() || !a.exists()) {
            return false;
        }
        File dir = b.getParentFile();
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        return a.renameTo(b);
    }

    @Override
    public boolean copyWithin(String from, String to, Progress p) {
        return false; // the engine copies on this device with the same loop it uses between devices
    }

    /** Free and total bytes of the storage, or {-1, -1}. */
    long[] space() {
        try {
            return new long[] {root.getUsableSpace(), root.getTotalSpace()};
        } catch (RuntimeException e) {
            return new long[] {-1, -1};
        }
    }
}
