package com.tj.linkmeter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a device answers when the other device asks about its files (the /fs/* requests). Everything here is plain Java so the
 * whole conversation can be tested on a computer against {@link RemoteStore}; the things only Android can do (thumbnails, the media
 * database) are handed in as {@link Media}. The caller has already checked that the other device is paired.
 *
 * Routes: info, ls, stat, get (honours "Range: bytes=N-"), put, mkdir, rm, rename, mv, cp + job + job/cancel, du, search, recent, thumb.
 * An older app answers 404 "unknown request" to the newer ones; {@link RemoteStore} copes with that.
 */
final class FsApi {
    /** Protocol version reported by /fs/info. 1 was ls, get, put, mkdir, rm. */
    static final int VERSION = 2;

    /** The Android-only parts. */
    interface Media {
        /** A small JPEG of the file's content, or null if it has none. */
        byte[] thumbnail(File f, FileKind kind, int px);

        /** Newest files first. kind is "images", "videos", "audio", "docs" or "all". Each entry's path is its path inside the storage. */
        List<FsEntry> recent(String kind, int limit);

        /** A file was added, changed or removed here: tell the media database so galleries and pickers show it. */
        void changed(File f);
    }

    /** For tests and for devices with no media database. */
    static final Media NO_MEDIA = new Media() {
        @Override
        public byte[] thumbnail(File f, FileKind kind, int px) {
            return null;
        }

        @Override
        public List<FsEntry> recent(String kind, int limit) {
            return new ArrayList<>();
        }

        @Override
        public void changed(File f) {
            // nothing to tell
        }
    };

    static final class Reply {
        int code = 200;
        String type = "application/json";
        byte[] body = new byte[0];
        /** When set, this is sent instead of body (a file). */
        InputStream stream;
        long length = -1;
        /** For code 206: the first byte sent, and the size of the whole file. */
        long rangeStart = -1;
        long total = -1;

        static Reply json(Object v) {
            Reply r = new Reply();
            r.body = Json.write(v).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return r;
        }

        static Reply err(int code, String msg) {
            Reply r = json(Json.obj("err", msg));
            r.code = code;
            return r;
        }

        static Reply ok() {
            return json(Json.obj("ok", true));
        }
    }

    private static final String[][] SHORTCUTS = {
        {"Camera", "/DCIM/Camera"}, {"Screenshots", "/Pictures/Screenshots"}, {"Screenshots", "/DCIM/Screenshots"},
        {"Pictures", "/Pictures"}, {"Downloads", "/Download"}, {"Documents", "/Documents"}, {"Music", "/Music"},
        {"Movies", "/Movies"}, {"Recordings", "/Recordings"},
    };

    private final DiskStore store;
    private final Media media;
    private final FsJobs jobs;

    FsApi(DiskStore store, Media media, FsJobs jobs) {
        this.store = store;
        this.media = media == null ? NO_MEDIA : media;
        this.jobs = jobs;
    }

    private static String p(Map<String, String> q, String key, String def) {
        String v = q.get(key);
        return v == null || v.isEmpty() ? def : v;
    }

    private static long num(Map<String, String> q, String key, long def) {
        try {
            return Long.parseLong(q.get(key));
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static Map<String, Object> item(FsEntry e) {
        Map<String, Object> o = Json.obj("n", e.name, "d", e.dir ? 1 : 0, "s", e.size, "t", e.time);
        if (e.count >= 0) {
            o.put("c", e.count);
        }
        if (!e.path.isEmpty()) {
            o.put("r", e.path);
        }
        return o;
    }

    /** Answers one request. Never throws: a failure is a 409 with the reason. */
    Reply handle(String route, Map<String, String> q, long rangeStart, InputStream body, long bodyLen) {
        try {
            switch (route) {
                case "/fs/info":
                    return info();
                case "/fs/ls":
                    return ls(q);
                case "/fs/stat":
                    return stat(q);
                case "/fs/get":
                    return get(q, rangeStart);
                case "/fs/put":
                    return put(q, body, bodyLen);
                case "/fs/mkdir":
                    return mkdir(q);
                case "/fs/rm":
                    return rm(q);
                case "/fs/rename":
                    return rename(q);
                case "/fs/mv":
                    return mv(q);
                case "/fs/cp":
                    return cp(q);
                case "/fs/job":
                    return job(q);
                case "/fs/job/cancel":
                    return jobCancel(q);
                case "/fs/du":
                    return du(q);
                case "/fs/search":
                    return search(q);
                case "/fs/recent":
                    return recent(q);
                case "/fs/thumb":
                    return thumb(q);
                default:
                    return Reply.err(404, "unknown request");
            }
        } catch (IOException e) {
            return Reply.err(409, String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
            return Reply.err(500, String.valueOf(e));
        }
    }

    // ------------------------------------------------------------ routes

    /** The shortcut folders that exist on this storage: {name, path, item count}. Used for /fs/info and by this device's own file screen. */
    static List<String[]> shortcuts(DiskStore store) throws IOException {
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String[] s : SHORTCUTS) {
            if (seen.contains(s[0])) {
                continue;
            }
            FsEntry e = store.stat(s[1]);
            if (e != null && e.dir) {
                seen.add(s[0]);
                String[] kids = store.resolve(s[1]).list();
                out.add(new String[] {s[0], s[1], String.valueOf(kids == null ? -1 : kids.length)});
            }
        }
        return out;
    }

    private Reply info() throws IOException {
        long[] space = store.space();
        List<Object> shorts = new ArrayList<>();
        for (String[] s : shortcuts(store)) {
            shorts.add(Json.obj("n", s[0], "p", s[1], "c", Long.parseLong(s[2])));
        }
        return Reply.json(Json.obj("v", VERSION, "root", store.root().getPath(), "free", space[0], "total", space[1], "short", shorts));
    }

    private Reply ls(Map<String, String> q) throws IOException {
        List<Object> items = new ArrayList<>();
        for (FsEntry e : store.list(p(q, "p", "/"), "1".equals(q.get("cnt")))) {
            items.add(item(e));
        }
        return Reply.json(Json.obj("items", items, "more", store.truncated ? 1 : 0));
    }

    private Reply stat(Map<String, String> q) throws IOException {
        String path = p(q, "p", "/");
        FsEntry e = store.stat(path);
        if (e == null) {
            return Reply.err(404, "not found");
        }
        Map<String, Object> o = item(e);
        o.put("mime", e.dir ? "" : FileKind.mime(e.name));
        o.put("path", FsPath.clean(path));
        if (e.dir) {
            String[] kids = store.resolve(path).list();
            o.put("c", kids == null ? -1 : kids.length);
        }
        return Reply.json(o);
    }

    private Reply get(Map<String, String> q, long rangeStart) throws IOException {
        String path = p(q, "p", "/");
        FsEntry e = store.stat(path);
        if (e == null || e.dir) {
            return Reply.err(404, "not a file");
        }
        long start = Math.max(0, rangeStart);
        if (start > e.size) {
            return Reply.err(409, "that starts after the end of the file");
        }
        Reply r = new Reply();
        r.type = "application/octet-stream";
        r.stream = store.read(path, start);
        r.length = e.size - start;
        if (rangeStart > 0) {
            r.code = 206;
            r.rangeStart = start;
            r.total = e.size;
        }
        return r;
    }

    private Reply put(Map<String, String> q, InputStream body, long bodyLen) throws IOException {
        String path = FsPath.clean(p(q, "p", "/"));
        String pol = p(q, "pol", "both");
        long mtime = num(q, "mtime", 0);
        if (path.equals("/")) {
            return Reply.err(409, "that is the storage root");
        }
        String dir = FsPath.parent(path);
        FsEntry d = store.stat(dir);
        if (d == null || !d.dir) {
            return Reply.err(404, "the folder doesn't exist");
        }
        FsEntry ex = store.stat(path);
        String target = path;
        if (ex != null) {
            if (ex.dir) {
                return Reply.err(409, "a folder with this name is in the way");
            }
            if (pol.equals("skip")) {
                Reply r = Reply.json(Json.obj("err", "exists", "exists", 1));
                r.code = 409;
                return r;
            }
            if (pol.equals("both")) {
                final String name = FsPath.name(path);
                String unique = FsPath.unique(name, false, n -> {
                    try {
                        return store.stat(FsPath.join(dir, n)) != null;
                    } catch (IOException e) {
                        return true;
                    }
                });
                target = FsPath.join(dir, unique);
            }
        }
        store.write(target, body, bodyLen, mtime, null);
        media.changed(store.resolve(target));
        return Reply.json(Json.obj("ok", true, "name", FsPath.name(target)));
    }

    private Reply mkdir(Map<String, String> q) throws IOException {
        String path = p(q, "p", "/");
        if (store.stat(path) != null) {
            return Reply.err(409, "already exists");
        }
        store.mkdirs(path);
        return Reply.ok();
    }

    private Reply rm(Map<String, String> q) throws IOException {
        String path = p(q, "p", "/");
        FsEntry e = store.stat(path);
        if (e == null) {
            return Reply.err(404, "not found");
        }
        if (e.dir && !"1".equals(q.get("rec")) && !store.list(path, false).isEmpty()) {
            return Reply.err(409, "the folder is not empty");
        }
        File f = store.resolve(path);
        store.delete(path);
        media.changed(f);
        return Reply.ok();
    }

    private Reply rename(Map<String, String> q) throws IOException {
        String path = p(q, "p", "/");
        String name = p(q, "name", "");
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\u0000")) {
            return Reply.err(409, "that is not a valid name");
        }
        if (store.stat(path) == null) {
            return Reply.err(404, "not found");
        }
        String to = FsPath.join(FsPath.parent(path), name);
        if (FsPath.clean(to).equals(FsPath.clean(path))) {
            return Reply.json(Json.obj("ok", true, "name", name));
        }
        if (store.stat(to) != null) {
            return Reply.err(409, "something with that name already exists");
        }
        File old = store.resolve(path);
        if (!store.rename(path, to)) {
            return Reply.err(409, "couldn't rename it");
        }
        media.changed(old);
        media.changed(store.resolve(to));
        return Reply.json(Json.obj("ok", true, "name", name));
    }

    private Reply mv(Map<String, String> q) throws IOException {
        String from = p(q, "from", "/");
        String to = p(q, "to", "/");
        if (store.stat(from) == null) {
            return Reply.err(404, "not found");
        }
        File old = store.resolve(from);
        if (!store.rename(from, to)) {
            return Reply.err(409, "couldn't move it in one step");
        }
        media.changed(old);
        media.changed(store.resolve(to));
        return Reply.ok();
    }

    private Reply cp(Map<String, String> q) throws IOException {
        final String from = p(q, "from", "/");
        final String to = p(q, "to", "/");
        if (store.stat(from) == null) {
            return Reply.err(404, "not found");
        }
        if (store.stat(to) != null) {
            return Reply.err(409, "already exists");
        }
        store.mkdirs(FsPath.parent(to));
        final File target = store.resolve(to);
        FsJobs.Job j = jobs.start(store, from, to, () -> media.changed(target));
        return Reply.json(Json.obj("job", j.id));
    }

    private Reply job(Map<String, String> q) {
        FsJobs.Job j = jobs.get(p(q, "id", ""));
        if (j == null) {
            return Reply.err(404, "no such job");
        }
        return Reply.json(Json.obj("state", j.state, "done", j.done, "total", j.total, "err", j.err));
    }

    private Reply jobCancel(Map<String, String> q) {
        return jobs.cancel(p(q, "id", "")) ? Reply.ok() : Reply.err(404, "no such job");
    }

    private Reply du(Map<String, String> q) throws IOException {
        String path = p(q, "p", "/");
        FsEntry e = store.stat(path);
        if (e == null) {
            return Reply.err(404, "not found");
        }
        long[] t = {e.dir ? 0 : e.size, e.dir ? 0 : 1, 0, 0}; // bytes, files, folders, visited
        boolean partial = false;
        if (e.dir) {
            partial = !duWalk(path, t, System.nanoTime() + 3_000_000_000L);
        }
        return Reply.json(Json.obj("size", t[0], "files", t[1], "dirs", t[2], "partial", partial ? 1 : 0));
    }

    /** False if it had to stop early (too many entries, or out of time). */
    private boolean duWalk(String dir, long[] t, long deadline) throws IOException {
        for (FsEntry k : store.list(dir, false)) {
            if (++t[3] > 200000 || System.nanoTime() > deadline) {
                return false;
            }
            if (k.dir) {
                t[2]++;
                if (!duWalk(FsPath.join(dir, k.name), t, deadline)) {
                    return false;
                }
            } else {
                t[0] += k.size;
                t[1]++;
            }
        }
        return true;
    }

    private Reply search(Map<String, String> q) throws IOException {
        String dir = p(q, "p", "/");
        String needle = p(q, "q", "").toLowerCase(Locale.US);
        int max = (int) Math.max(1, Math.min(500, num(q, "max", 200)));
        if (needle.isEmpty()) {
            return Reply.err(409, "nothing to search for");
        }
        FsSearch.Result res = FsSearch.run(store, dir, needle, max, 4000);
        List<Object> items = new ArrayList<>();
        for (FsEntry e : res.found) {
            items.add(item(e));
        }
        return Reply.json(Json.obj("items", items, "partial", res.partial ? 1 : 0));
    }

    private Reply recent(Map<String, String> q) {
        int limit = (int) Math.max(1, Math.min(300, num(q, "limit", 120)));
        List<Object> items = new ArrayList<>();
        for (FsEntry e : media.recent(p(q, "kind", "all"), limit)) {
            items.add(item(e));
        }
        return Reply.json(Json.obj("items", items));
    }

    private Reply thumb(Map<String, String> q) throws IOException {
        String path = p(q, "p", "/");
        FsEntry e = store.stat(path);
        if (e == null || e.dir || !e.kind().hasThumbnail()) {
            return Reply.err(404, "no thumbnail");
        }
        int px = (int) Math.max(48, Math.min(512, num(q, "s", 160)));
        byte[] jpg = media.thumbnail(store.resolve(path), e.kind(), px);
        if (jpg == null || jpg.length == 0) {
            return Reply.err(404, "no thumbnail");
        }
        Reply r = new Reply();
        r.type = "image/jpeg";
        r.body = jpg;
        return r;
    }
}
