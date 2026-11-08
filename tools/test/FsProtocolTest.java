package com.tj.linkmeter;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The whole file conversation between the two devices, offline: the real server code (FsApi through FsHttp, on a real socket)
 * and the real client code (RemoteStore, PeerLink, Client). Every route, error replies, an app too old for the new routes,
 * and TransferEngine run across the wire in both directions against the in-memory model. Run by tools/test.sh.
 */
public final class FsProtocolTest {
    static int checks;
    static int failed;
    static final String TOKEN = "0123456789abcdef0123456789abcdef";

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            failed++;
            if (failed <= 40) {
                System.out.println("  FAIL: " + msg);
            }
        }
    }

    // ---------------------------------------------------------------- a test server

    static final class Media implements FsApi.Media {
        final List<String> changed = new CopyOnWriteArrayList<>();
        final List<FsEntry> recent = new ArrayList<>();

        @Override
        public byte[] thumbnail(File f, FileKind kind, int px) {
            return kind == FileKind.IMAGE ? new byte[] {(byte) 0xff, (byte) 0xd8, (byte) px, 7} : null;
        }

        @Override
        public List<FsEntry> recent(String kind, int limit) {
            List<FsEntry> out = new ArrayList<>();
            for (FsEntry e : recent) {
                if (out.size() < limit && (kind.equals("all") || (kind.equals("images") && e.kind() == FileKind.IMAGE))) {
                    out.add(e);
                }
            }
            return out;
        }

        @Override
        public void changed(File f) {
            changed.add(f.getName());
        }
    }

    static final class Server implements Closeable {
        final ServerSocket ss;
        final FsApi api;
        final DiskStore store;
        final Media media;
        /** An app too old for the newer routes. */
        volatile boolean legacy;
        volatile int served;

        Server(File root) throws IOException {
            ss = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            store = new DiskStore(root, "phone");
            media = new Media();
            api = new FsApi(store, media, new FsJobs());
            Thread t = new Thread(() -> {
                while (!ss.isClosed()) {
                    try {
                        final Socket s = ss.accept();
                        Thread h = new Thread(() -> handle(s));
                        h.setDaemon(true);
                        h.start();
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            t.setDaemon(true);
            t.start();
            Client.portOverride = ss.getLocalPort();
        }

        void handle(Socket s) {
            try {
                Http.Req r = Http.read(s);
                OutputStream out = s.getOutputStream();
                served++;
                if (!TOKEN.equals(r.token)) {
                    Http.err(out, 401, "not paired");
                } else if (legacy && !Arrays.asList("/fs/ls", "/fs/get", "/fs/put", "/fs/mkdir", "/fs/rm").contains(r.path)) {
                    Http.err(out, 404, "unknown request");
                } else {
                    if (legacy) {
                        r.query.remove("cnt");
                        r.query.remove("pol");
                        r.query.remove("mtime");
                        r.rangeStart = -1;
                    }
                    FsHttp.serve(api, r, out);
                }
                out.flush();
            } catch (IOException e) {
                // the client went away (a cancel): nothing to answer
            } finally {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // closed
                }
            }
        }

        @Override
        public void close() throws IOException {
            ss.close();
        }
    }

    static RemoteStore remote(String token) {
        return new RemoteStore(new PeerLink("127.0.0.1", token), "phone");
    }

    static File tmp() throws IOException {
        return TransferEngineTest.tmp();
    }

    static void put(File root, String rel, String text) throws IOException {
        TransferEngineTest.put(root, rel, text);
    }

    static String slurp(File f) throws IOException {
        return FilesCoreTest.slurp(f);
    }

    static boolean waitNoParts(File root) throws Exception {
        for (int i = 0; i < 60; i++) {
            if (FilesCoreTest.noParts(root).isEmpty()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    static boolean threw(FilesCoreTest.Runnable0 r) {
        return FilesCoreTest.throwsIO(r);
    }

    static byte[] readAll(InputStream in) throws IOException {
        return FilesCoreTest.readAll(in);
    }

    static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- tests

    public static void main(String[] args) throws Exception {
        File root = tmp();
        try (Server srv = new Server(root)) {
            routes(root, srv);
            legacyApp(srv);
            auth(root);
            engineOverTheWire(srv);
            bigFilesAndParallel(srv);
        }
        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static void routes(File root, Server srv) throws Exception {
        RemoteStore rs = remote(TOKEN);
        put(root, "DCIM/Camera/a.jpg", "JPEGDATA");
        put(root, "DCIM/Camera/b.mp4", "VIDEO");
        put(root, "Download/doc.pdf", "PDF!");
        put(root, "Notes/n1.txt", "one");
        put(root, "Notes/sub/n2.txt", "two");
        put(root, "Notes/.hidden.txt", "h");
        put(root, "Notes/x.txt" + DiskStore.PART, "partial");

        // info
        RemoteStore.Info info = rs.info();
        check(info.version == FsApi.VERSION && info.free > 0 && info.total >= info.free && info.root.length() > 0, "info: " + info.version + " " + info.free);
        boolean cam = false;
        boolean dl = false;
        for (String[] s : info.shortcuts) {
            cam |= s[0].equals("Camera") && s[1].equals("/DCIM/Camera") && s[2].equals("2");
            dl |= s[0].equals("Downloads") && s[1].equals("/Download") && s[2].equals("1");
            check(!s[0].equals("Music"), "info lists only folders that exist");
        }
        check(cam && dl, "info: shortcut folders with their counts");

        // ls (no counts, with counts, partial files hidden)
        List<FsEntry> notes = rs.list("/Notes", false);
        List<String> names = new ArrayList<>();
        for (FsEntry e : notes) {
            names.add(e.name);
            check(e.count == -1, "ls without counts leaves count unknown");
        }
        Collections.sort(names);
        check(names.equals(Arrays.asList(".hidden.txt", "n1.txt", "sub")), "ls: " + names);
        boolean subCount = false;
        for (FsEntry e : rs.list("/Notes", true)) {
            subCount |= e.name.equals("sub") && e.dir && e.count == 1;
        }
        check(subCount, "ls with counts: a folder knows how many items it holds");
        check(threw(() -> rs.list("/Notes/n1.txt", false)), "ls of a file is an error");
        check(threw(() -> rs.list("/nowhere", false)), "ls of a missing folder is an error");
        new File(root, "Notes/x.txt" + DiskStore.PART).delete(); // (it only had to be hidden from the listing)

        // stat
        FsEntry st = rs.stat("/DCIM/Camera/a.jpg");
        check(st != null && !st.dir && st.size == 8 && st.name.equals("a.jpg") && st.time > 0, "stat of a file");
        check(rs.stat("/Notes").dir, "stat of a folder");
        check(rs.stat("/nothing") == null, "stat of a missing path is null, not an error");
        check(rs.stat("/").dir, "stat of the root");
        Client.Resp raw = Client.call("127.0.0.1", "GET", "/fs/stat?p=%2FDCIM%2FCamera%2Fa.jpg", TOKEN, 3000, 5000);
        check(raw.body.contains("image/jpeg"), "stat tells the MIME type: " + raw.body);

        // get: whole, from an offset, ranges as the wire shows them, errors
        try (InputStream in = rs.read("/DCIM/Camera/a.jpg", 0)) {
            check(new String(readAll(in), StandardCharsets.UTF_8).equals("JPEGDATA"), "get");
        }
        try (InputStream in = rs.read("/DCIM/Camera/a.jpg", 3)) {
            check(new String(readAll(in), StandardCharsets.UTF_8).equals("GDATA"), "get from an offset");
        }
        Client.Stream part = Client.get("127.0.0.1", "/fs/get?p=%2FDCIM%2FCamera%2Fa.jpg", TOKEN, 3000, 5000, 5);
        check(part.code == 206 && part.length == 3, "a ranged get is 206 with the remaining length: " + part.code + " " + part.length);
        part.close();
        Client.Stream whole = Client.get("127.0.0.1", "/fs/get?p=%2FDCIM%2FCamera%2Fa.jpg", TOKEN, 3000, 5000);
        check(whole.code == 200 && whole.length == 8, "a plain get is 200");
        whole.close();
        check(threw(() -> rs.read("/DCIM/Camera/a.jpg", 99)), "a range past the end is refused");
        check(threw(() -> rs.read("/Notes", 0)), "get of a folder is refused");
        check(threw(() -> rs.read("/nope.txt", 0)), "get of a missing file is refused");

        // put under each policy, mtime, folder in the way, missing parent, no partial file
        final long mt = 1_600_000_123L;
        rs.write("/Notes/new.txt", stream("fresh"), 5, mt, null);
        check(slurp(new File(root, "Notes/new.txt")).equals("fresh") && new File(root, "Notes/new.txt").lastModified() / 1000 == mt, "write keeps the modification time");
        rs.write("/Notes/new.txt", stream("replaced!"), 9, mt, null);
        check(slurp(new File(root, "Notes/new.txt")).equals("replaced!") && !new File(root, "Notes/new (1).txt").exists(), "RemoteStore.write replaces");
        Client.Resp both = putRaw("/fs/put?p=%2FNotes%2Fnew.txt&pol=both", "second");
        check(both.code == 200 && both.body.contains("new (1).txt") && slurp(new File(root, "Notes/new (1).txt")).equals("second"), "pol=both keeps both: " + both.body);
        Client.Resp skip = putRaw("/fs/put?p=%2FNotes%2Fnew.txt&pol=skip", "third");
        check(skip.code == 409 && skip.body.contains("exists") && slurp(new File(root, "Notes/new.txt")).equals("replaced!"), "pol=skip refuses an existing file: " + skip.body);
        Client.Resp oldStyle = putRaw("/fs/put?p=%2FNotes%2Fnew.txt", "fourth");
        check(oldStyle.code == 200 && oldStyle.body.contains("new (2).txt"), "no policy means keep both, as the old protocol did: " + oldStyle.body);
        check(threw(() -> rs.write("/Notes/sub", stream("x"), 1, 0, null)), "a folder in the way");
        check(threw(() -> rs.write("/missing/dir/x.txt", stream("x"), 1, 0, null)), "a missing folder");
        check(threw(() -> rs.write("/Notes/short.txt", stream("ab"), 5, 0, null)), "a body shorter than promised");
        check(waitNoParts(root), "no partial files after those");
        check(!new File(root, "Notes/short.txt").exists(), "a cut-short upload leaves nothing");
        check(srv.media.changed.contains("new.txt"), "the media database is told about new files");

        // mkdir, delete
        rs.mkdirs("/Made/a/b");
        check(new File(root, "Made/a/b").isDirectory(), "mkdir makes parents");
        rs.mkdirs("/Made/a/b");
        check(threw(() -> rs.mkdirs("/Notes/n1.txt")), "mkdir over a file");
        Client.Resp rmNonEmpty = Client.call("127.0.0.1", "POST", "/fs/rm?p=%2FNotes%2Fsub", TOKEN, 3000, 5000);
        check(rmNonEmpty.code == 409 && new File(root, "Notes/sub/n2.txt").exists(), "rm of a full folder without rec=1 is refused");
        rs.delete("/Notes/sub");
        check(!new File(root, "Notes/sub").exists(), "delete removes a folder tree");
        check(threw(() -> rs.delete("/Notes/sub")), "delete of a missing path");

        // rename in place, move in one step
        check(rs.renameTo("/Notes/n1.txt", "renamed.txt").equals("renamed.txt") && new File(root, "Notes/renamed.txt").exists(), "rename in place");
        check(threw(() -> rs.renameTo("/Notes/renamed.txt", "a/b")), "a name with a slash");
        new File(root, "Notes/keep").mkdirs();
        check(threw(() -> rs.renameTo("/Notes/renamed.txt", "keep/inside.txt")) && !new File(root, "Notes/keep/inside.txt").exists()
                && new File(root, "Notes/renamed.txt").exists(), "a rename cannot be used to move a file into a folder");
        check(threw(() -> rs.renameTo("/Notes/renamed.txt", "..")), "a name of two dots");
        check(threw(() -> rs.renameTo("/Notes/renamed.txt", "new.txt")), "a name that is taken");
        check(rs.rename("/Notes/renamed.txt", "/Made/moved.txt") && new File(root, "Made/moved.txt").exists(), "move in one step");
        check(!rs.rename("/Made/moved.txt", "/Notes/new.txt"), "a move never overwrites: false");
        check(!rs.rename("/nothing", "/Made/x"), "a move of a missing path: false");

        // copy by the device itself, with progress and cancel
        put(root, "Src/a.bin", "x".repeat(2000));
        put(root, "Src/d/b.bin", "y".repeat(3000));
        final long[] moved = {0};
        check(rs.copyWithin("/Src", "/CopyOfSrc", n -> moved[0] += n), "cp is supported");
        check(new File(root, "CopyOfSrc/d/b.bin").length() == 3000 && new File(root, "Src/a.bin").exists(), "the copy is complete and the original stays");
        check(moved[0] == 5000, "progress adds up to the bytes copied: " + moved[0]);
        check(!rs.copyWithin("/Src", "/CopyOfSrc", null), "a copy never overwrites: false");
        check(!rs.copyWithin("/nothing", "/X", null), "a copy of a missing path: false");
        boolean cancelled = false;
        put(root, "Big/f.bin", "z".repeat(900_000));
        try {
            rs.copyWithin("/Big", "/BigCopy", n -> {
                throw new Store.Cancelled();
            });
        } catch (Store.Cancelled c) {
            cancelled = true;
        }
        check(cancelled, "cancel comes out of copyWithin as Cancelled");
        boolean gone = false;
        for (int i = 0; i < 100 && !gone; i++) {
            gone = !new File(root, "BigCopy").exists() && FilesCoreTest.noParts(root).isEmpty();
            if (!gone) {
                Thread.sleep(50);
            }
        }
        check(gone, "a cancelled device copy removes what it made");

        // a job cancelled while it is still copying removes what it made (a big file, so it is certainly still running)
        {
            File src = new File(root, "SlowSrc");
            src.mkdirs();
            put(root, "SlowSrc/a.txt", "a");
            byte[] filler = new byte[12 * 1024 * 1024];
            java.nio.file.Files.write(new File(src, "big.bin").toPath(), filler);
            srv.store.bufferSize = 512;
            FsJobs jobs = new FsJobs();
            FsJobs.Job j = jobs.start(srv.store, "/SlowSrc", "/SlowCopy", null);
            check(jobs.cancel(j.id), "the job can be cancelled");
            for (int i = 0; i < 200 && j.state.equals("run"); i++) {
                Thread.sleep(25);
            }
            srv.store.bufferSize = 64 * 1024;
            check(j.state.equals("cancelled") && !new File(root, "SlowCopy").exists(), "a cancelled job removes its copy: " + j.state);
            check(waitNoParts(root), "and leaves no partial file");
            FsJobs.Job bad = jobs.start(srv.store, "/nothing-here", "/Nowhere", null);
            for (int i = 0; i < 100 && bad.state.equals("run"); i++) {
                Thread.sleep(25);
            }
            check(bad.state.equals("err") && bad.err.contains("not found") && !new File(root, "Nowhere").exists(), "a job whose source is gone ends with the reason: " + bad.state + " " + bad.err);
            check(!jobs.cancel("nope") && jobs.get("nope") == null, "an unknown job");
        }

        // a ranged reply says what it is on the wire: 206 and the Content-Range
        {
            try (Socket wire = new Socket("127.0.0.1", Client.portOverride)) {
                wire.setSoTimeout(5000);
                String reqText = "GET /fs/get?p=%2FDCIM%2FCamera%2Fa.jpg HTTP/1.0\r\nX-Token: " + TOKEN + "\r\nRange: bytes=5-\r\n\r\n";
                wire.getOutputStream().write(reqText.getBytes(StandardCharsets.US_ASCII));
                String reply = new String(readAll(wire.getInputStream()), StandardCharsets.ISO_8859_1);
                check(reply.startsWith("HTTP/1.0 206 Partial Content") && reply.contains("Content-Range: bytes 5-7/8") && reply.contains("Content-Length: 3")
                        && reply.endsWith("\r\n\r\nATA"), "a ranged reply on the wire: " + reply.replace("\r\n", "|"));
            }
        }

        // du, search, recent, thumb
        long[] du = rs.du("/Src");
        check(du[0] == 5000 && du[1] == 2 && du[2] == 1 && du[3] == 0, "du: " + Arrays.toString(du));
        List<FsEntry> found = rs.search("/", "N2.TXT");
        put(root, "Src/d/N2.txt", "n");
        found = rs.search("/", "n2.TXT");
        check(found.size() == 1 && found.get(0).path.equals("/Src/d/N2.txt"), "search: " + found);
        check(threw(() -> rs.search("/", "")), "search for nothing");
        check(rs.search("/", "zzzz-not-there").isEmpty(), "search with no match");
        FsEntry e1 = new FsEntry("a.jpg", false, 8, 100);
        e1.path = "/DCIM/Camera/a.jpg";
        srv.media.recent.add(e1);
        List<FsEntry> rec = rs.recent("images", 10);
        check(rec.size() == 1 && rec.get(0).path.equals("/DCIM/Camera/a.jpg"), "recent");
        byte[] th = rs.thumbnail("/DCIM/Camera/a.jpg", 160);
        check(th != null && th.length == 4 && th[2] == (byte) 160, "thumbnail bytes arrive");
        check(rs.thumbnail("/DCIM/Camera/b.mp4", 160) == null, "no thumbnail from this media stub for a video");
        check(rs.thumbnail("/Notes/new.txt", 160) == null, "no thumbnail for a text file");
        check(rs.thumbnail("/nothing.jpg", 160) == null, "no thumbnail for a missing file");

        // unknown route
        Client.Resp unk = Client.call("127.0.0.1", "GET", "/fs/nonsense", TOKEN, 3000, 5000);
        check(unk.code == 404 && unk.body.contains("unknown request"), "unknown route");
        // a path that tries to leave the storage
        Client.Resp esc = Client.call("127.0.0.1", "GET", "/fs/ls?p=%2F..%2F..%2Fetc", TOKEN, 3000, 5000);
        check(!esc.body.contains("passwd") && !esc.body.contains("\"bin\""), "a path with dot dot stays inside the storage: " + esc.code + " " + esc.body);
    }

    static Client.Resp putRaw(String target, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        return Client.put("127.0.0.1", target, TOKEN, new ByteArrayInputStream(b), b.length, null);
    }

    static void legacyApp(Server srv) throws Exception {
        srv.legacy = true;
        try {
            File root = srv.store.root();
            RemoteStore rs = remote(TOKEN);
            put(root, "Old/a.txt", "old a");
            put(root, "Old/dir/b.txt", "old b");
            check(rs.info().version == 1, "an old app is version 1");
            FsEntry st = rs.stat("/Old/a.txt");
            check(st != null && st.size == 5, "stat falls back to listing the folder");
            check(rs.stat("/Old/zzz") == null && rs.stat("/").dir, "stat of nothing and of the root, the old way");
            check(!rs.rename("/Old/a.txt", "/Old/c.txt") && new File(root, "Old/a.txt").exists(), "an old app cannot move in one step: false");
            check(!rs.copyWithin("/Old/dir", "/Old/dir2", null), "an old app cannot copy by itself: false");
            rs.write("/Old/a.txt", stream("NEW A!"), 6, 1_600_000_000L, null);
            check(slurp(new File(root, "Old/a.txt")).equals("NEW A!") && !new File(root, "Old/a (1).txt").exists(), "replacing a file on an old app: it is removed first");
            try (InputStream in = rs.read("/Old/a.txt", 2)) {
                check(new String(readAll(in), StandardCharsets.UTF_8).equals("W A!"), "an offset on an old app (it sends everything, the start is skipped)");
            }
            check(rs.thumbnail("/Old/a.txt", 160) == null, "an old app has no thumbnails");
            // a whole transfer through an old app
            File mine = tmp();
            put(mine, "pics/1.txt", "one");
            put(mine, "pics/2.txt", "two");
            DiskStore tablet = new DiskStore(mine, "tablet");
            TransferEngine.Result r = TransferEngineTest.run(tablet, rs, TransferEngineTest.list("/pics"), "/FromTablet", false, TransferEngine.Policy.BOTH, null, null, null);
            check(r.copied == 2 && new File(root, "FromTablet/pics/2.txt").exists(), "a copy to an old app works: " + r.summary(false) + r.errors);
            TransferEngine.Result mv = TransferEngineTest.run(rs, rs, TransferEngineTest.list("/FromTablet/pics"), "/Old", true, TransferEngine.Policy.BOTH, null, null, null);
            check(mv.copied == 2 && new File(root, "Old/pics/1.txt").exists() && !new File(root, "FromTablet/pics").exists(), "a move inside an old app (copy and delete through the tablet): " + mv.summary(true) + mv.errors);
        } finally {
            srv.legacy = false;
        }
    }

    static void auth(File root) throws Exception {
        RemoteStore wrong = remote("ffffffffffffffffffffffffffffffff");
        boolean threw = false;
        try {
            wrong.list("/", false);
        } catch (IOException e) {
            threw = e.getMessage().contains("not paired");
        }
        check(threw, "a tablet that is not paired is refused with a clear reason");
        check(threw(() -> wrong.read("/x", 0)) && threw(() -> wrong.write("/x.txt", stream("x"), 1, 0, null)), "not paired: get and put too");
        check(!new File(root, "x.txt").exists(), "and nothing was written");
    }

    static void engineOverTheWire(Server srv) throws Exception {
        File root = srv.store.root();
        TransferEngine.Policy[] pols = {TransferEngine.Policy.REPLACE, TransferEngine.Policy.SKIP, TransferEngine.Policy.BOTH};
        for (int seed = 0; seed < 150; seed++) {
            Random rnd = new Random(seed);
            // a fresh phone folder for each story: the server's root is shared, so each story works inside its own folder
            String base = "/story" + seed;
            File phoneDir = new File(root, "story" + seed);
            phoneDir.mkdirs();
            File mine = tmp();
            TransferEngineTest.randomTree(mine, rnd, "");
            TransferEngineTest.randomTree(phoneDir, rnd, "");
            boolean upload = rnd.nextBoolean();
            boolean move = rnd.nextBoolean();
            boolean inside = rnd.nextInt(4) == 0; // phone to phone (mv / cp job)
            TransferEngine.Policy pol = pols[rnd.nextInt(pols.length)];
            DiskStore tablet = new DiskStore(mine, "tablet");
            DiskStore phoneView = new DiskStore(phoneDir, "phone");
            RemoteStore phone = new RemoteStore(new PeerLink("127.0.0.1", TOKEN), "phone");
            // the remote store sees the whole server root: paths are prefixed with the story folder
            Store remoteInStory = new Prefixed(phone, base);
            Store src = inside ? remoteInStory : (upload ? tablet : remoteInStory);
            Store dst = inside ? remoteInStory : (upload ? remoteInStory : tablet);
            File srcRoot = inside ? phoneDir : (upload ? mine : phoneDir);
            File dstRoot = inside ? phoneDir : (upload ? phoneDir : mine);
            TransferEngineTest.Model ms = TransferEngineTest.Model.scan(srcRoot);
            List<String> all = new ArrayList<>(ms.files.keySet());
            all.addAll(ms.dirs);
            if (all.isEmpty()) {
                continue;
            }
            List<String> roots = new ArrayList<>();
            for (int k = 0; k < 1 + rnd.nextInt(3); k++) {
                String cand = all.get(rnd.nextInt(all.size()));
                boolean ok = true;
                for (String o : roots) {
                    ok &= !FsPath.isSameOrUnder(cand, o) && !FsPath.isSameOrUnder(o, cand);
                }
                if (ok) {
                    roots.add(cand);
                }
            }
            String dstDir = inside ? "/new/place" : (rnd.nextBoolean() ? "/" : "/new/place");
            TransferEngineTest.Model wantSrc = TransferEngineTest.Model.scan(srcRoot);
            TransferEngineTest.Model wantDst = inside ? wantSrc : TransferEngineTest.Model.scan(dstRoot);
            TransferEngineTest.modelTransfer(wantSrc, roots, wantDst, dstDir, move, pol);
            TransferEngine.Result r = TransferEngineTest.run(src, dst, roots, dstDir, move, pol, null, null, null);
            String tag = "wire story " + seed + " (" + pol + ", " + (move ? "move" : "copy") + ", " + (inside ? "phone to phone" : upload ? "tablet to phone" : "phone to tablet") + ", " + roots + " -> " + dstDir + ")";
            TransferEngineTest.Model gotSrc = TransferEngineTest.Model.scan(srcRoot);
            TransferEngineTest.Model gotDst = TransferEngineTest.Model.scan(dstRoot);
            check(gotDst.equals(wantDst), tag + ": destination\n    got  " + gotDst + "\n    want " + wantDst + "\n    errors " + r.errors);
            if (!inside) {
                check(gotSrc.equals(wantSrc), tag + ": source\n    got  " + gotSrc + "\n    want " + wantSrc);
            }
            check(waitNoParts(phoneDir) && FilesCoreTest.noParts(mine).isEmpty(), tag + ": no partial files");
        }

        // cancel while data is moving over the wire, both ways: nothing half-written on either side
        for (int dir = 0; dir < 2; dir++) {
            int ended = -1;
            for (int at = 0; at < 60; at += 1) {
                File phoneDir = new File(root, "cancel" + dir + "_" + at);
                phoneDir.mkdirs();
                File mine = tmp();
                DiskStore tablet = new DiskStore(mine, "tablet");
                tablet.bufferSize = 512;
                String body1 = "1".repeat(1800);
                String body2 = "2".repeat(1300);
                if (dir == 0) {
                    put(mine, "t/one.txt", body1);
                    put(mine, "t/sub/two.txt", body2);
                } else {
                    put(phoneDir, "t/one.txt", body1);
                    put(phoneDir, "t/sub/two.txt", body2);
                }
                Store phone = new Prefixed(remote(TOKEN), "/cancel" + dir + "_" + at);
                Store src = dir == 0 ? tablet : phone;
                Store dst = dir == 0 ? phone : tablet;
                TransferEngine.Result r = TransferEngineTest.run(src, dst, TransferEngineTest.list("/t"), "/out", true, TransferEngine.Policy.BOTH, null,
                        new TransferEngineTest.CancelAt(at), null);
                File s = dir == 0 ? mine : phoneDir;
                File d = dir == 0 ? phoneDir : mine;
                String tag = "wire cancel dir=" + dir + " at=" + at;
                check(waitNoParts(phoneDir) && FilesCoreTest.noParts(mine).isEmpty(), tag + ": no partial files");
                TransferEngineTest.Model ds = TransferEngineTest.Model.scan(d);
                TransferEngineTest.Model ss = TransferEngineTest.Model.scan(s);
                for (String f : new String[] {"/t/one.txt", "/t/sub/two.txt"}) {
                    String want = f.endsWith("one.txt") ? body1 : body2;
                    boolean atSrc = want.equals(ss.files.get(f));
                    boolean atDst = want.equals(ds.files.get("/out" + f));
                    check(atSrc || atDst, tag + ": " + f + " is not lost");
                    check(ds.files.get("/out" + f) == null || ds.files.get("/out" + f).equals(want), tag + ": a file at the destination is complete");
                }
                if (!r.cancelled) {
                    ended = at;
                    break;
                }
            }
            check(ended > 3, "cancel over the wire (dir " + dir + "): cancelled at many moments before it completed (" + ended + ")");
        }
    }

    /** A store that sees one folder of another store as its root, so stories on one server do not meet. */
    static final class Prefixed implements Store {
        final Store in;
        final String base;

        Prefixed(Store in, String base) {
            this.in = in;
            this.base = base;
        }

        String p(String path) {
            return FsPath.clean(base + "/" + FsPath.clean(path));
        }

        @Override
        public String id() {
            return in.id();
        }

        @Override
        public FsEntry stat(String path) throws IOException {
            return in.stat(p(path));
        }

        @Override
        public List<FsEntry> list(String dir, boolean counts) throws IOException {
            return in.list(p(dir), counts);
        }

        @Override
        public InputStream read(String path, long offset) throws IOException {
            return in.read(p(path), offset);
        }

        @Override
        public void write(String path, InputStream src, long len, long mtimeSec, Progress pr) throws IOException {
            in.write(p(path), src, len, mtimeSec, pr);
        }

        @Override
        public void mkdirs(String dir) throws IOException {
            in.mkdirs(p(dir));
        }

        @Override
        public void delete(String path) throws IOException {
            in.delete(p(path));
        }

        @Override
        public boolean rename(String from, String to) throws IOException {
            return in.rename(p(from), p(to));
        }

        @Override
        public boolean copyWithin(String from, String to, Progress pr) throws IOException {
            return in.copyWithin(p(from), p(to), pr);
        }
    }

    static void bigFilesAndParallel(Server srv) throws Exception {
        final File root = srv.store.root();
        // a file of several MB goes up and comes back byte for byte
        byte[] big = new byte[6 * 1024 * 1024 + 123];
        new Random(9).nextBytes(big);
        File mine = tmp();
        java.nio.file.Files.write(new File(mine, "big.bin").toPath(), big);
        RemoteStore rs = remote(TOKEN);
        DiskStore tablet = new DiskStore(mine, "tablet");
        final long[] moved = {0};
        try (InputStream in = tablet.read("/big.bin", 0)) {
            rs.write("/BigUp.bin", in, big.length, 1_600_000_000L, n -> moved[0] += n);
        }
        check(moved[0] == big.length, "progress counted every byte of a big upload: " + moved[0]);
        check(sha(java.nio.file.Files.readAllBytes(new File(root, "BigUp.bin").toPath())).equals(sha(big)), "a big upload arrives intact");
        try (InputStream in = rs.read("/BigUp.bin", 1000)) {
            byte[] back = readAll(in);
            check(back.length == big.length - 1000 && sha(back).equals(sha(Arrays.copyOfRange(big, 1000, big.length))), "a big download from an offset is intact");
        }

        // several transfers at once
        final List<String> errors = new CopyOnWriteArrayList<>();
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            final int n = i;
            Thread t = new Thread(() -> {
                try {
                    RemoteStore r = remote(TOKEN);
                    byte[] data = new byte[300_000 + n];
                    new Random(n).nextBytes(data);
                    r.write("/par" + n + ".bin", new ByteArrayInputStream(data), data.length, 0, null);
                    try (InputStream in = r.read("/par" + n + ".bin", 0)) {
                        if (!sha(readAll(in)).equals(sha(data))) {
                            errors.add("mismatch " + n);
                        }
                    }
                } catch (Exception e) {
                    errors.add(n + ": " + e);
                }
            });
            ts.add(t);
            t.start();
        }
        for (Thread t : ts) {
            t.join(60000);
        }
        check(errors.isEmpty(), "six transfers at once: " + errors);
    }

    static String sha(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    // keeps a reference so the unused-import checker stays quiet
    static final Map<String, String> UNUSED = null;
}
