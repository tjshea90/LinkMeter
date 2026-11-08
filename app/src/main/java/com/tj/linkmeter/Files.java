package com.tj.linkmeter;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * File access for both devices: the same code lists local folders for the screen and
 * answers the other device's requests. Everything is confined to shared storage
 * ("/storage/emulated/0"), and a path that tries to leave it is refused.
 */
final class Files {
    private Files() {}

    static final class Entry {
        String name = "";
        boolean dir;
        long size;
        long time;
    }

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

    /** Turns a path relative to storage ("/Download/a.zip") into a File, refusing anything outside. */
    static File resolve(String p) throws IOException {
        File root = root().getCanonicalFile();
        File f = new File(root, p).getCanonicalFile();
        String rp = root.getPath();
        if (!f.getPath().equals(rp) && !f.getPath().startsWith(rp + File.separator)) {
            throw new IOException("outside storage");
        }
        return f;
    }

    static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    static String parent(String path) {
        int i = path.lastIndexOf('/');
        return i <= 0 ? "/" : path.substring(0, i);
    }

    static List<Entry> list(File dir) throws IOException {
        if (!dir.isDirectory()) {
            throw new IOException("not a folder");
        }
        File[] fs = dir.listFiles();
        if (fs == null) {
            throw new IOException("can't read this folder (is All files access allowed?)");
        }
        List<Entry> out = new ArrayList<>();
        for (File f : fs) {
            if (out.size() >= 2000) {
                break;
            }
            Entry e = new Entry();
            e.name = f.getName();
            e.dir = f.isDirectory();
            e.size = e.dir ? 0 : f.length();
            e.time = f.lastModified() / 1000;
            out.add(e);
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.dir != b.dir) {
                    return a.dir ? -1 : 1;
                }
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return out;
    }

    /** A file name that doesn't exist yet: "a.zip", then "a (1).zip", "a (2).zip"… */
    static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) {
            return f;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) {
                return f;
            }
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    static void delete(File f, boolean recursive) throws IOException {
        if (f.equals(root().getCanonicalFile())) {
            throw new IOException("won't delete the storage root");
        }
        if (f.isDirectory()) {
            String[] kids = f.list();
            if (kids != null && kids.length > 0) {
                if (!recursive) {
                    throw new IOException("the folder is not empty");
                }
                for (String k : kids) {
                    delete(new File(f, k), true);
                }
            }
        }
        if (!f.delete()) {
            throw new IOException("couldn't delete " + f.getName());
        }
    }

    static String size(long b) {
        if (b < 1024) {
            return b + " B";
        }
        if (b < 1024L * 1024) {
            return String.format(Locale.US, "%.0f KB", b / 1024.0);
        }
        if (b < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024));
        }
        return String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024));
    }

    // ------------------------------------------------------------ answering the other device

    /** Handles /fs/ls, /fs/get, /fs/put, /fs/mkdir and /fs/rm. */
    static void serve(Context c, Http.Req r, OutputStream out) throws IOException {
        if (!hasAccess(c)) {
            Http.err(out, 403, "This device has not allowed LinkMeter to use its storage yet.");
            return;
        }
        try {
            File f = resolve(r.q("p").isEmpty() ? "/" : r.q("p"));
            switch (r.path) {
                case "/fs/ls":
                    ls(f, out);
                    break;
                case "/fs/get":
                    get(f, out);
                    break;
                case "/fs/put":
                    put(f, r, out);
                    break;
                case "/fs/mkdir":
                    if (f.exists()) {
                        Http.err(out, 409, "already exists");
                    } else if (f.mkdirs()) {
                        Http.json(out, 200, "{\"ok\":true}");
                    } else {
                        Http.err(out, 500, "couldn't create the folder");
                    }
                    break;
                default: // /fs/rm
                    if (!f.exists()) {
                        Http.err(out, 404, "not found");
                    } else {
                        delete(f, "1".equals(r.q("rec")));
                        Http.json(out, 200, "{\"ok\":true}");
                    }
                    break;
            }
        } catch (IOException e) {
            Http.err(out, 409, String.valueOf(e.getMessage()));
        }
    }

    private static void ls(File dir, OutputStream out) throws IOException {
        JSONArray items = new JSONArray();
        try {
            for (Entry e : list(dir)) {
                JSONObject o = new JSONObject();
                o.put("n", e.name);
                o.put("d", e.dir ? 1 : 0);
                o.put("s", e.size);
                o.put("t", e.time);
                items.put(o);
            }
            JSONObject res = new JSONObject();
            res.put("items", items);
            Http.json(out, 200, res.toString());
        } catch (JSONException e) {
            Http.err(out, 500, "couldn't build the list");
        }
    }

    private static void get(File f, OutputStream out) throws IOException {
        if (!f.isFile()) {
            Http.err(out, 404, "not a file");
            return;
        }
        Http.head(out, 200, "application/octet-stream", f.length());
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        out.flush();
    }

    private static void put(File target, Http.Req r, OutputStream out) throws IOException {
        File dir = target.getParentFile();
        if (dir == null || !dir.isDirectory()) {
            Http.err(out, 404, "the folder doesn't exist");
            return;
        }
        File dest = unique(dir, target.getName());
        File part = new File(dir, dest.getName() + ".part");
        long left = r.length;
        try (OutputStream fo = new FileOutputStream(part)) {
            byte[] buf = new byte[32 * 1024];
            while (left > 0) {
                int n = r.body.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) {
                    throw new IOException("the upload was cut short");
                }
                fo.write(buf, 0, n);
                left -= n;
            }
        } catch (IOException e) {
            part.delete();
            throw e;
        }
        if (!part.renameTo(dest)) {
            part.delete();
            throw new IOException("couldn't finish the file");
        }
        Http.json(out, 200, "{\"ok\":true,\"name\":" + JSONObject.quote(dest.getName()) + "}");
    }
}
