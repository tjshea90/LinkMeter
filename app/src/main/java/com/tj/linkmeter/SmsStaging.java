package com.tj.linkmeter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Where the files for a message wait on the phone between the tablet putting them there (/sms/attach) and the message being sent
 * (/sms/send). Each is kept for an hour at most. No Android in it (tested offline).
 */
final class SmsStaging {
    static final long MAX_FILE = 25L * 1024 * 1024;
    static final long KEEP_MS = 60L * 60 * 1000;

    private final File dir;
    private final LongSupplier clock;
    private final Map<String, Sms.Staged> items = new HashMap<>();
    private final Map<String, Long> born = new HashMap<>();
    private long next = 1;

    SmsStaging(File dir, LongSupplier clock) throws IOException {
        this.dir = dir;
        this.clock = clock;
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot make " + dir);
        }
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                f.delete(); // left over from an earlier run of the app
            }
        }
    }

    /** A name that is safe to show and to keep: no path, no control characters, at most 80 characters. */
    static String safeName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\u0000-\\u001f]", "_").trim();
        if (n.length() > 80) {
            String ext = FsPath.ext(n);
            n = n.substring(0, 80 - (ext.isEmpty() ? 0 : ext.length() + 1)) + (ext.isEmpty() ? "" : "." + ext);
        }
        return n.isEmpty() ? "file" : n;
    }

    static String safeType(String type) {
        String t = type == null ? "" : type.trim().toLowerCase(java.util.Locale.US);
        return t.matches("[a-z0-9][a-z0-9.+-]{0,60}/[a-z0-9][a-z0-9.+-]{0,60}") ? t : "application/octet-stream";
    }

    /** Takes exactly len bytes from in. */
    synchronized Sms.Staged put(String name, String type, InputStream in, long len) throws IOException {
        sweep();
        if (len <= 0) {
            throw new IOException("that file is empty");
        }
        if (len > MAX_FILE) {
            throw new IOException("that file is too big to send (the limit is 25 MB)");
        }
        String id = "a" + next++;
        File f = new File(dir, id + ".bin");
        File tmp = new File(dir, id + ".part");
        try (OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[32 * 1024];
            long left = len;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) {
                    throw new IOException("the file stopped before its end");
                }
                out.write(buf, 0, n);
                left -= n;
            }
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new IOException("could not keep the file");
        }
        Sms.Staged st = new Sms.Staged();
        st.id = id;
        st.name = safeName(name);
        st.type = safeType(type);
        st.size = len;
        st.file = f;
        items.put(id, st);
        born.put(id, clock.getAsLong());
        return st;
    }

    synchronized Sms.Staged get(String id) {
        sweep();
        return items.get(id);
    }

    synchronized void release(Collection<String> ids) {
        for (String id : ids) {
            Sms.Staged st = items.remove(id);
            born.remove(id);
            if (st != null) {
                st.file.delete();
            }
        }
    }

    synchronized int count() {
        return items.size();
    }

    private void sweep() {
        long now = clock.getAsLong();
        for (Iterator<Map.Entry<String, Long>> it = born.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Long> e = it.next();
            if (now - e.getValue() > KEEP_MS) {
                Sms.Staged st = items.remove(e.getKey());
                if (st != null) {
                    st.file.delete();
                }
                it.remove();
            }
        }
    }
}
