package com.tj.linkmeter;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The other device's storage as a {@link Store}: every method is one or a few requests to the /fs/* routes that {@link FsApi}
 * answers. Pure Java (the requests go through a {@link Link}), so it is tested against the real server code over a real socket.
 * An older app on the other side answers 404 "unknown request" to the newer routes; each method then falls back to the older
 * way or says "not supported" (false) so that {@link TransferEngine} copies the file by streaming instead.
 */
final class RemoteStore implements Store {
    /** How requests get to the other device. {@link PeerLink} is the real one. */
    interface Link {
        Client.Resp call(String method, String target, int readMs) throws IOException;

        Client.Stream get(String target, long offset) throws IOException;

        Client.Resp put(String target, InputStream src, long len, Client.Progress p) throws IOException;
    }

    /** What /fs/info said. */
    static final class Info {
        int version = 1;
        long free = -1;
        long total = -1;
        String root = "";
        /** name, path, how many items */
        final List<String[]> shortcuts = new ArrayList<>();
    }

    private final Link link;
    private final String id;
    private Info info;

    RemoteStore(Link link, String id) {
        this.link = link;
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }

    private static String enc(String s) {
        return Http.enc(s);
    }

    /** The reply as an object; an error code becomes an IOException with the other device's reason. */
    private static Map<String, Object> obj(Client.Resp r) throws IOException {
        Map<String, Object> m;
        try {
            m = Json.parseObject(r.body);
        } catch (IOException e) {
            if (r.code == 401) {
                throw new IOException("the other device does not know this one (not paired)");
            }
            throw new IOException("the other device sent something unreadable");
        }
        if (r.code != 200) {
            throw new IOException(Json.str(m, "err", "error " + r.code));
        }
        return m;
    }

    private static boolean unknownRoute(Client.Resp r) {
        return r.code == 404 && r.body.contains("unknown request");
    }

    private static FsEntry entry(Map<String, Object> o) {
        FsEntry e = new FsEntry();
        e.name = Json.str(o, "n", "");
        e.dir = Json.num(o, "d", 0) == 1;
        e.size = Json.num(o, "s", 0);
        e.time = Json.num(o, "t", 0);
        e.count = (int) Json.num(o, "c", -1);
        e.path = Json.str(o, "r", "");
        return e;
    }

    // ------------------------------------------------------------ what the other device is

    /** Version, free space and shortcut folders. Cached; an app too old for /fs/info is version 1 with nothing else known. */
    synchronized Info info() throws IOException {
        if (info != null) {
            return info;
        }
        Client.Resp r = link.call("GET", "/fs/info", 20000);
        Info i = new Info();
        if (!unknownRoute(r)) {
            Map<String, Object> m = obj(r);
            i.version = (int) Json.num(m, "v", 1);
            i.free = Json.num(m, "free", -1);
            i.total = Json.num(m, "total", -1);
            i.root = Json.str(m, "root", "");
            for (Map<String, Object> s : Json.objects(m, "short")) {
                i.shortcuts.add(new String[] {Json.str(s, "n", ""), Json.str(s, "p", ""), String.valueOf(Json.num(s, "c", -1))});
            }
        }
        info = i;
        return i;
    }

    /** Forgets the cached info (free space changed). */
    synchronized void refreshInfo() {
        info = null;
    }

    private boolean modern() throws IOException {
        return info().version >= 2;
    }

    // ------------------------------------------------------------ Store

    @Override
    public FsEntry stat(String path) throws IOException {
        Client.Resp r = link.call("GET", "/fs/stat?p=" + enc(path), 20000);
        if (unknownRoute(r)) {
            return statByListing(path);
        }
        if (r.code == 404) {
            return null;
        }
        return entry(obj(r));
    }

    /** For an app without /fs/stat: look the name up in its folder. */
    private FsEntry statByListing(String path) throws IOException {
        String p = FsPath.clean(path);
        if (p.equals("/")) {
            FsEntry root = new FsEntry("", true, 0, 0);
            return root;
        }
        for (FsEntry e : list(FsPath.parent(p), false)) {
            if (e.name.equals(FsPath.name(p))) {
                return e;
            }
        }
        return null;
    }

    @Override
    public List<FsEntry> list(String dir, boolean counts) throws IOException {
        Map<String, Object> m = obj(link.call("GET", "/fs/ls?p=" + enc(dir) + (counts ? "&cnt=1" : ""), 30000));
        List<FsEntry> out = new ArrayList<>();
        for (Map<String, Object> o : Json.objects(m, "items")) {
            out.add(entry(o));
        }
        truncated = Json.num(m, "more", 0) == 1;
        return out;
    }

    /** The last listing was cut short by the other device. */
    volatile boolean truncated;

    @Override
    public InputStream read(String path, long offset) throws IOException {
        final Client.Stream st = link.get("/fs/get?p=" + enc(path), offset);
        if (st.code != 200 && st.code != 206) {
            String body = "";
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[1024];
                int n;
                while (bo.size() < 4096 && (n = st.in.read(b)) > 0) {
                    bo.write(b, 0, n);
                }
                body = bo.toString("UTF-8");
            } catch (IOException ignored) {
                // the reason is only a nicety
            }
            st.close();
            String why = "the other device said no (" + st.code + ")";
            try {
                why = Json.str(Json.parseObject(body), "err", why);
            } catch (IOException ignored) {
                // not JSON: keep the generic reason
            }
            throw new IOException(why);
        }
        InputStream in = new FilterInputStream(st.in) {
            @Override
            public void close() {
                st.close();
            }
        };
        if (offset > 0 && st.code == 200) {
            // an older app ignores ranges and sends the whole file: skip what was already there
            long left = offset;
            while (left > 0) {
                long n = in.skip(left);
                if (n <= 0) {
                    if (in.read() < 0) {
                        in.close();
                        throw new IOException("the file is shorter than expected");
                    }
                    n = 1;
                }
                left -= n;
            }
        }
        return in;
    }

    @Override
    public void write(String path, InputStream src, long len, long mtimeSec, final Progress p) throws IOException {
        if (!modern()) {
            // an older app always keeps both: so what is to be replaced is removed first
            FsEntry ex = stat(path);
            if (ex != null && !ex.dir) {
                delete(path);
            }
        }
        final long[] last = {0};
        String target = "/fs/put?p=" + enc(path) + (modern() ? "&pol=replace&mtime=" + mtimeSec : "");
        Client.Resp r = link.put(target, src, len, done -> {
            long n = done - last[0];
            last[0] = done;
            if (p != null && n > 0) {
                p.bytes(n);
            }
        });
        if (r.code != 200) {
            String why = r.body;
            try {
                why = Json.str(Json.parseObject(r.body), "err", r.body);
            } catch (IOException ignored) {
                // plain text reply
            }
            throw new IOException(why);
        }
        refreshInfo();
    }

    @Override
    public void mkdirs(String dir) throws IOException {
        Client.Resp r = link.call("POST", "/fs/mkdir?p=" + enc(dir), 20000);
        if (r.code == 409) {
            FsEntry e = stat(dir);
            if (e != null && e.dir) {
                return;
            }
        }
        obj(r);
    }

    @Override
    public void delete(String path) throws IOException {
        obj(link.call("POST", "/fs/rm?p=" + enc(path) + "&rec=1", 60000));
        refreshInfo();
    }

    @Override
    public boolean rename(String from, String to) throws IOException {
        Client.Resp r = link.call("POST", "/fs/mv?from=" + enc(from) + "&to=" + enc(to), 30000);
        if (r.code == 200) {
            return true;
        }
        if (unknownRoute(r) || r.code == 409 || r.code == 404) {
            return false;
        }
        obj(r);
        return false;
    }

    @Override
    public boolean copyWithin(String from, String to, Progress p) throws IOException {
        Client.Resp r = link.call("POST", "/fs/cp?from=" + enc(from) + "&to=" + enc(to), 30000);
        if (unknownRoute(r) || r.code == 409 || r.code == 404) {
            return false;
        }
        String job = Json.str(obj(r), "job", "");
        if (job.isEmpty()) {
            return false;
        }
        long reported = 0;
        try {
            while (true) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
                Map<String, Object> m = obj(link.call("GET", "/fs/job?id=" + enc(job), 20000));
                long done = Json.num(m, "done", 0);
                if (p != null && done > reported) {
                    p.bytes(done - reported);
                }
                reported = Math.max(reported, done);
                String state = Json.str(m, "state", "run");
                if (state.equals("done")) {
                    return true;
                }
                if (state.equals("err")) {
                    throw new IOException(Json.str(m, "err", "the copy failed"));
                }
                if (state.equals("cancelled")) {
                    throw new Cancelled();
                }
            }
        } catch (Cancelled c) {
            cancelJob(job, to);
            throw c;
        }
    }

    /**
     * Cancels a copy the other device is doing and makes sure nothing of it is left: the other device removes a copy it cancels, but a
     * copy that had already finished by the time the cancel arrived is removed here. All or nothing.
     */
    private void cancelJob(String job, String to) {
        try {
            link.call("POST", "/fs/job/cancel?id=" + enc(job), 10000);
            for (int i = 0; i < 40; i++) {
                Map<String, Object> m = Json.parseObject(link.call("GET", "/fs/job?id=" + enc(job), 10000).body);
                String state = Json.str(m, "state", "run");
                if (state.equals("done")) {
                    link.call("POST", "/fs/rm?p=" + enc(to) + "&rec=1", 60000);
                    return;
                }
                if (!state.equals("run")) {
                    return;
                }
                Thread.sleep(150);
            }
        } catch (IOException e) {
            // the other device is gone: nothing more can be done from here
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------ extras for the screen

    /** Renames in place. Returns the new name. */
    String renameTo(String path, String name) throws IOException {
        Map<String, Object> m = obj(link.call("POST", "/fs/rename?p=" + enc(path) + "&name=" + enc(name), 30000));
        return Json.str(m, "name", name);
    }

    /** A small JPEG of the file, or null if there is none (or the other device is too old to make one). */
    byte[] thumbnail(String path, int px) throws IOException {
        Client.Stream st = link.get("/fs/thumb?p=" + enc(path) + "&s=" + px, 0);
        try {
            if (st.code != 200) {
                return null;
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = st.in.read(b)) > 0) {
                bo.write(b, 0, n);
                if (bo.size() > 600 * 1024) {
                    return null;
                }
            }
            return bo.toByteArray();
        } finally {
            st.close();
        }
    }

    /** Files whose name contains q, below dir. */
    List<FsEntry> search(String dir, String q) throws IOException {
        Map<String, Object> m = obj(link.call("GET", "/fs/search?p=" + enc(dir) + "&q=" + enc(q), 30000));
        List<FsEntry> out = new ArrayList<>();
        for (Map<String, Object> o : Json.objects(m, "items")) {
            out.add(entry(o));
        }
        return out;
    }

    /** The newest files of a kind: "images", "videos", "audio", "docs" or "all". */
    List<FsEntry> recent(String kind, int limit) throws IOException {
        Map<String, Object> m = obj(link.call("GET", "/fs/recent?kind=" + enc(kind) + "&limit=" + limit, 30000));
        List<FsEntry> out = new ArrayList<>();
        for (Map<String, Object> o : Json.objects(m, "items")) {
            out.add(entry(o));
        }
        return out;
    }

    /** Total size, files and folders below a path: {bytes, files, folders, partial (0 or 1)}. */
    long[] du(String path) throws IOException {
        Map<String, Object> m = obj(link.call("GET", "/fs/du?p=" + enc(path), 30000));
        return new long[] {Json.num(m, "size", 0), Json.num(m, "files", 0), Json.num(m, "dirs", 0), Json.num(m, "partial", 0)};
    }
}
