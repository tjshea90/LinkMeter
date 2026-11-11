package com.tj.linkmeter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** No Android in here (tested in tools/test/TextsLogicTest.java). {@link BlobStore} as files in one folder; a write is all or nothing. */
final class FileBlobStore implements BlobStore {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    private final File dir;

    FileBlobStore(File dir) throws IOException {
        this.dir = dir;
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("cannot make " + dir);
        }
    }

    private File file(String name) throws IOException {
        if (!NAME.matcher(name).matches() || name.endsWith(".tmp") || name.startsWith(".")) {
            throw new IOException("not a name for a saved blob: " + name);
        }
        return new File(dir, name);
    }

    @Override
    public synchronized byte[] read(String name) throws IOException {
        File f = file(name);
        if (!f.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(f)) {
            long len = f.length();
            if (len > 64L * 1024 * 1024) {
                throw new IOException("that saved blob is too big");
            }
            byte[] b = new byte[(int) len];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            return off == b.length ? b : java.util.Arrays.copyOf(b, off);
        }
    }

    @Override
    public synchronized void write(String name, byte[] data) throws IOException {
        File f = file(name);
        File tmp = new File(dir, name + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        }
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public synchronized void delete(String name) throws IOException {
        File f = file(name);
        if (f.exists() && !f.delete()) {
            throw new IOException("cannot delete " + name);
        }
    }

    @Override
    public synchronized List<String> names() {
        List<String> out = new ArrayList<>();
        File[] all = dir.listFiles();
        if (all != null) {
            for (File f : all) {
                if (f.isFile() && !f.getName().endsWith(".tmp")) {
                    out.add(f.getName());
                }
            }
        }
        java.util.Collections.sort(out);
        return out;
    }
}
