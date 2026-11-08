package com.tj.linkmeter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Offline tests of TransferEngine against real folders (DiskStore) and an independent in-memory model of what a copy or move
 * must produce: name clashes under every policy, move, cancel at every moment, failing stores, progress, and random stories.
 * Run by tools/test.sh. Prints "N checks, M failed".
 */
public final class TransferEngineTest {
    static int checks;
    static int failed;

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            failed++;
            if (failed <= 40) {
                System.out.println("  FAIL: " + msg);
            }
        }
    }

    // ---------------------------------------------------------------- the model

    /** A folder tree in memory: what the disk should look like. */
    static final class Model {
        final TreeMap<String, String> files = new TreeMap<>();
        final TreeSet<String> dirs = new TreeSet<>();

        static Model scan(File root) throws IOException {
            Model m = new Model();
            scan(root, "", m);
            return m;
        }

        private static void scan(File d, String rel, Model m) throws IOException {
            File[] fs = d.listFiles();
            if (fs == null) {
                return;
            }
            for (File f : fs) {
                String p = rel + "/" + f.getName();
                if (f.isDirectory()) {
                    m.dirs.add(p);
                    scan(f, p, m);
                } else {
                    m.files.put(p, new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                }
            }
        }

        boolean isDir(String p) {
            return p.equals("/") || dirs.contains(p);
        }

        boolean exists(String p) {
            return isDir(p) || files.containsKey(p);
        }

        void mkdirs(String p) {
            String c = FsPath.clean(p);
            while (!c.equals("/")) {
                dirs.add(c);
                c = FsPath.parent(c);
            }
        }

        List<String> children(String dir) {
            TreeSet<String> out = new TreeSet<>();
            String prefix = dir.equals("/") ? "/" : dir + "/";
            for (String f : files.keySet()) {
                if (f.startsWith(prefix) && f.indexOf('/', prefix.length()) < 0) {
                    out.add(f.substring(prefix.length()));
                }
            }
            for (String d : dirs) {
                if (d.startsWith(prefix) && d.indexOf('/', prefix.length()) < 0) {
                    out.add(d.substring(prefix.length()));
                }
            }
            return new ArrayList<>(out);
        }

        void removeTree(String p) {
            files.keySet().removeIf(f -> f.equals(p) || f.startsWith(p + "/"));
            dirs.removeIf(d -> d.equals(p) || d.startsWith(p + "/"));
        }

        void removeEmptyDirsUnder(String root) {
            List<String> ds = new ArrayList<>();
            for (String d : dirs) {
                if (d.equals(root) || d.startsWith(root + "/")) {
                    ds.add(d);
                }
            }
            Collections.sort(ds, Collections.reverseOrder());
            for (String d : ds) {
                if (children(d).isEmpty()) {
                    dirs.remove(d);
                }
            }
        }

        String unique(String dir, String name, boolean isDir) {
            return FsPath.unique(name, isDir, n -> exists(FsPath.join(dir, n)));
        }

        @Override
        public String toString() {
            return "files=" + files + " dirs=" + dirs;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Model && ((Model) o).files.equals(files) && ((Model) o).dirs.equals(dirs);
        }

        @Override
        public int hashCode() {
            return files.hashCode();
        }
    }

    static void modelTransfer(Model src, List<String> roots, Model dst, String dstDir, boolean move, TransferEngine.Policy pol) {
        dst.mkdirs(dstDir);
        for (String root : roots) {
            boolean[] incomplete = {false};
            String name = FsPath.name(root);
            boolean isDir = src.isDir(root);
            String destTop = FsPath.join(dstDir, name);
            String destName = name;
            boolean clash = dst.exists(destTop) && !(isDir && dst.isDir(destTop));
            if (clash) {
                if (pol == TransferEngine.Policy.SKIP) {
                    continue;
                }
                if (pol == TransferEngine.Policy.BOTH) {
                    destName = dst.unique(dstDir, name, isDir);
                } else {
                    if (dst.isDir(destTop)) {
                        continue; // a folder is in the way of a file: failed
                    }
                    if (isDir) {
                        dst.files.remove(destTop);
                    }
                }
            }
            copyNode(src, root, dst, FsPath.join(dstDir, destName), pol, move, incomplete);
            if (move && isDir) {
                if (!incomplete[0]) {
                    src.removeTree(root);
                } else {
                    src.removeEmptyDirsUnder(root);
                }
            }
        }
    }

    static void copyNode(Model src, String s, Model dst, String d, TransferEngine.Policy pol, boolean move, boolean[] incomplete) {
        if (src.isDir(s)) {
            dst.mkdirs(d);
            for (String child : src.children(s)) {
                String cs = FsPath.join(s, child);
                String cd = FsPath.join(d, child);
                boolean childDir = src.isDir(cs);
                if (dst.exists(cd) && !(childDir && dst.isDir(cd))) {
                    if (pol == TransferEngine.Policy.SKIP) {
                        incomplete[0] = true;
                        continue;
                    }
                    if (pol == TransferEngine.Policy.BOTH) {
                        cd = FsPath.join(d, dst.unique(d, child, childDir));
                    } else {
                        if (dst.isDir(cd)) {
                            incomplete[0] = true;
                            continue;
                        }
                        if (childDir) {
                            dst.files.remove(cd);
                        }
                    }
                }
                copyNode(src, cs, dst, cd, pol, move, incomplete);
            }
        } else {
            dst.files.put(d, src.files.get(s));
            if (move) {
                src.files.remove(s);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    static File tmp() throws IOException {
        return java.nio.file.Files.createTempDirectory("engine").toFile();
    }

    static void put(File root, String rel, String text) throws IOException {
        File f = new File(root, rel);
        f.getParentFile().mkdirs();
        java.nio.file.Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    static void mkdir(File root, String rel) {
        new File(root, rel).mkdirs();
    }

    static String text(String ch, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(ch);
        }
        return sb.toString();
    }

    static List<String> parts(File root) {
        return FilesCoreTest.noParts(root);
    }

    /** A store that passes everything through and can misbehave on purpose. */
    static class Flaky implements Store {
        final Store in;
        Predicate<String> failRead = p -> false;
        Predicate<String> failWrite = p -> false;
        Predicate<String> cutRead = p -> false;
        boolean noRename;
        String writeError = "boom";
        boolean ownCopy;
        int ownCopies;

        Flaky(Store in) {
            this.in = in;
        }

        @Override
        public String id() {
            return in.id();
        }

        @Override
        public FsEntry stat(String path) throws IOException {
            return in.stat(path);
        }

        @Override
        public List<FsEntry> list(String dir, boolean counts) throws IOException {
            return in.list(dir, counts);
        }

        @Override
        public InputStream read(String path, long offset) throws IOException {
            if (failRead.test(path)) {
                throw new IOException("cannot read " + FsPath.name(path));
            }
            final InputStream real = in.read(path, offset);
            if (!cutRead.test(path)) {
                return real;
            }
            return new InputStream() {
                int n;

                @Override
                public int read() throws IOException {
                    if (++n > 3) {
                        throw new IOException("the connection dropped");
                    }
                    return real.read();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (n >= 3) {
                        throw new IOException("the connection dropped");
                    }
                    int r = real.read(b, off, Math.min(len, 3 - n));
                    if (r > 0) {
                        n += r;
                    }
                    return r;
                }

                @Override
                public void close() throws IOException {
                    real.close();
                }
            };
        }

        @Override
        public void write(String path, InputStream src, long len, long mtimeSec, Progress p) throws IOException {
            if (failWrite.test(path)) {
                throw new IOException(writeError);
            }
            in.write(path, src, len, mtimeSec, p);
        }

        @Override
        public void mkdirs(String dir) throws IOException {
            in.mkdirs(dir);
        }

        @Override
        public void delete(String path) throws IOException {
            in.delete(path);
        }

        @Override
        public boolean rename(String from, String to) throws IOException {
            return !noRename && in.rename(from, to);
        }

        @Override
        public boolean copyWithin(String from, String to, Progress p) throws IOException {
            if (!ownCopy) {
                return false;
            }
            ownCopies++;
            FsEntry e = in.stat(from);
            copyTree(from, to, p, e);
            return true;
        }

        private void copyTree(String from, String to, Progress p, FsEntry e) throws IOException {
            if (e.dir) {
                in.mkdirs(to);
                for (FsEntry k : in.list(from, false)) {
                    copyTree(FsPath.join(from, k.name), FsPath.join(to, k.name), p, k);
                }
            } else {
                try (InputStream r = in.read(from, 0)) {
                    in.write(to, r, e.size, e.time, p);
                }
            }
        }
    }

    static TransferEngine.Result run(Store a, Store b, List<String> roots, String dstDir, boolean move, TransferEngine.Policy pol,
            TransferEngine.Resolver res, TransferEngine.Cancel cancel, List<TransferEngine.Snapshot> seen) throws IOException {
        TransferEngine.Plan plan = TransferEngine.plan(a, roots);
        return TransferEngine.run(plan, a, b, dstDir, move, pol, res, s -> {
            if (seen != null) {
                TransferEngine.Snapshot c = new TransferEngine.Snapshot();
                c.bytesDone = s.bytesDone;
                c.bytesTotal = s.bytesTotal;
                c.filesDone = s.filesDone;
                c.filesTotal = s.filesTotal;
                seen.add(c);
            }
        }, cancel);
    }

    static List<String> list(String... p) {
        return new ArrayList<>(Arrays.asList(p));
    }

    // ---------------------------------------------------------------- tests

    public static void main(String[] args) throws Exception {
        basics();
        clashes();
        selfAndGuards();
        failures();
        cancelEverywhere();
        progressAndMisc();
        randomStories();
        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static void basics() throws Exception {
        // copy a file and a folder tree, across two stores
        File ra = tmp();
        File rb = tmp();
        DiskStore a = new DiskStore(ra, "A");
        DiskStore b = new DiskStore(rb, "B");
        put(ra, "docs/a.txt", "AAA");
        put(ra, "docs/sub/b.txt", "BBBB");
        mkdir(ra, "docs/empty");
        put(ra, "pic.jpg", "JPG");
        TransferEngine.Result r = run(a, b, list("/docs", "/pic.jpg"), "/in/here", false, TransferEngine.Policy.BOTH, null, null, null);
        Model want = new Model();
        want.mkdirs("/in/here/docs/sub");
        want.mkdirs("/in/here/docs/empty");
        want.files.put("/in/here/docs/a.txt", "AAA");
        want.files.put("/in/here/docs/sub/b.txt", "BBBB");
        want.files.put("/in/here/pic.jpg", "JPG");
        check(Model.scan(rb).equals(want), "copy tree: " + Model.scan(rb));
        check(r.copied == 3 && r.failed == 0 && r.skipped == 0 && !r.cancelled && r.bytes == 10, "copy counts: " + r.summary(false));
        check(r.created.contains("/in/here/docs") && r.created.contains("/in/here/pic.jpg"), "created: " + r.created);
        check(new File(ra, "docs/a.txt").exists() && new File(ra, "pic.jpg").exists(), "a copy leaves the originals");
        check(parts(rb).isEmpty(), "no partial files");

        // move across stores: originals are gone, empty folder too
        TransferEngine.Result m = run(a, b, list("/docs", "/pic.jpg"), "/moved", true, TransferEngine.Policy.BOTH, null, null, null);
        check(m.copied == 3 && m.failed == 0, "move counts: " + m.summary(true));
        check(!new File(ra, "docs").exists() && !new File(ra, "pic.jpg").exists(), "a move removes the originals, folder included");
        check(new File(rb, "moved/docs/empty").isDirectory() && new File(rb, "moved/docs/sub/b.txt").exists(), "the moved tree is complete, empty folder too");

        // move within one store: instant rename, even for a folder
        File rs = tmp();
        DiskStore s = new DiskStore(rs, "S");
        put(rs, "x/1.txt", "1");
        put(rs, "x/y/2.txt", "2");
        mkdir(rs, "dest");
        TransferEngine.Result sm = run(s, s, list("/x"), "/dest", true, TransferEngine.Policy.BOTH, null, null, null);
        check(sm.copied == 2 && new File(rs, "dest/x/y/2.txt").exists() && !new File(rs, "x").exists(), "rename move: " + sm.summary(true));

        // copy within one store
        TransferEngine.Result sc = run(s, s, list("/dest/x"), "/", false, TransferEngine.Policy.BOTH, null, null, null);
        check(sc.copied == 2 && new File(rs, "x/1.txt").exists() && new File(rs, "dest/x/1.txt").exists(), "same-store copy: " + sc.summary(false));

        // a store that copies by itself (the phone doing a copy on its own storage)
        File rf = tmp();
        Flaky f = new Flaky(new DiskStore(rf, "F"));
        f.ownCopy = true;
        put(rf, "p/1.txt", "11");
        put(rf, "p/q/2.txt", "222");
        List<TransferEngine.Snapshot> seen = new ArrayList<>();
        TransferEngine.Result oc = run(f, f, list("/p"), "/out", false, TransferEngine.Policy.BOTH, null, null, seen);
        check(f.ownCopies == 1 && oc.copied == 2 && oc.bytes == 5, "the device copied by itself: " + f.ownCopies + " " + oc.summary(false));
        check(new File(rf, "out/p/q/2.txt").exists(), "and the result is there");
        check(!seen.isEmpty() && seen.get(seen.size() - 1).bytesDone == seen.get(seen.size() - 1).bytesTotal, "progress reaches the end");

        // no rename on a store: a move falls back to copy and delete with the same result
        File rn = tmp();
        Flaky nf = new Flaky(new DiskStore(rn, "N"));
        nf.noRename = true;
        put(rn, "x/1.txt", "1");
        put(rn, "x/y/2.txt", "2");
        mkdir(rn, "dest");
        TransferEngine.Result nm = run(nf, nf, list("/x"), "/dest", true, TransferEngine.Policy.BOTH, null, null, null);
        check(nm.copied == 2 && new File(rn, "dest/x/y/2.txt").exists() && !new File(rn, "x").exists(), "move without rename: " + nm.summary(true));
        check(parts(rn).isEmpty(), "no partial files");

        // names with spaces, brackets, ampersands and apostrophes
        File r1 = tmp();
        File r2 = tmp();
        put(r1, "my file (1).txt", "sp");
        put(r1, "odd & #1 [x]/it's.txt", "odd");
        TransferEngine.Result u = run(new DiskStore(r1, "A"), new DiskStore(r2, "B"), list("/my file (1).txt", "/odd & #1 [x]"), "/", false,
                TransferEngine.Policy.BOTH, null, null, null);
        check(u.copied == 2 && new File(r2, "my file (1).txt").exists() && new File(r2, "odd & #1 [x]/it's.txt").exists(), "unusual names");

        // delete
        File rd = tmp();
        DiskStore d = new DiskStore(rd, "D");
        put(rd, "k/1.txt", "1");
        put(rd, "k/2/3.txt", "3");
        put(rd, "z.txt", "z");
        TransferEngine.Result dr = TransferEngine.deleteAll(d, list("/k", "/z.txt", "/missing"), null, null);
        check(dr.copied == 2 && dr.failed == 1 && !new File(rd, "k").exists() && !new File(rd, "z.txt").exists(), "delete: " + dr.copied + "/" + dr.failed);
        final int[] n = {0};
        TransferEngine.Result dc = TransferEngine.deleteAll(d, list("/a", "/b"), null, () -> ++n[0] > 1);
        check(dc.cancelled, "delete can be cancelled");
    }

    static void clashes() throws Exception {
        // every policy on a file clash, and on a folder merge
        for (TransferEngine.Policy pol : new TransferEngine.Policy[] {TransferEngine.Policy.REPLACE, TransferEngine.Policy.SKIP, TransferEngine.Policy.BOTH}) {
            File ra = tmp();
            File rb = tmp();
            DiskStore a = new DiskStore(ra, "A");
            DiskStore b = new DiskStore(rb, "B");
            put(ra, "f.txt", "NEW");
            put(rb, "f.txt", "OLD");
            put(ra, "d/x.txt", "NEWX");
            put(ra, "d/y.txt", "NEWY");
            put(rb, "d/x.txt", "OLDX");
            put(rb, "d/keep.txt", "KEEP");
            TransferEngine.Result r = run(a, b, list("/f.txt", "/d"), "/", false, pol, null, null, null);
            Model got = Model.scan(rb);
            Model want = new Model();
            want.mkdirs("/d");
            want.files.put("/d/keep.txt", "KEEP");
            want.files.put("/d/y.txt", "NEWY");
            switch (pol) {
                case REPLACE:
                    want.files.put("/f.txt", "NEW");
                    want.files.put("/d/x.txt", "NEWX");
                    break;
                case SKIP:
                    want.files.put("/f.txt", "OLD");
                    want.files.put("/d/x.txt", "OLDX");
                    break;
                default:
                    want.files.put("/f.txt", "OLD");
                    want.files.put("/f (1).txt", "NEW");
                    want.files.put("/d/x.txt", "OLDX");
                    want.files.put("/d/x (1).txt", "NEWX");
                    break;
            }
            check(got.equals(want), pol + ": " + got + " wanted " + want);
            check(pol != TransferEngine.Policy.SKIP || r.skipped == 2, pol + ": skipped " + r.skipped);
            check(parts(rb).isEmpty(), pol + ": no partial files");
        }

        // keep both repeatedly: a (1), a (2), a (3)
        File ra = tmp();
        File rb = tmp();
        DiskStore a = new DiskStore(ra, "A");
        DiskStore b = new DiskStore(rb, "B");
        put(ra, "a.txt", "1");
        put(rb, "a.txt", "0");
        for (int i = 1; i <= 3; i++) {
            run(a, b, list("/a.txt"), "/", false, TransferEngine.Policy.BOTH, null, null, null);
        }
        check(new File(rb, "a (1).txt").exists() && new File(rb, "a (2).txt").exists() && new File(rb, "a (3).txt").exists(), "keep both numbers up");

        // a folder where a file goes, and a file where a folder goes
        File r1 = tmp();
        File r2 = tmp();
        DiskStore s1 = new DiskStore(r1, "A");
        DiskStore s2 = new DiskStore(r2, "B");
        put(r1, "n", "FILE");
        put(r1, "m/z.txt", "Z");
        put(r2, "n/inner.txt", "I");
        put(r2, "m", "MFILE");
        TransferEngine.Result rr = run(s1, s2, list("/n", "/m"), "/", false, TransferEngine.Policy.REPLACE, null, null, null);
        check(rr.failed == 1 && new File(r2, "n/inner.txt").exists(), "replace refuses to put a file over a folder");
        check(new File(r2, "m").isDirectory() && new File(r2, "m/z.txt").exists(), "replace puts a folder over a file");
        TransferEngine.Result rb2 = run(s1, s2, list("/n"), "/", false, TransferEngine.Policy.BOTH, null, null, null);
        check(rb2.failed == 0 && new File(r2, "n (1)").isFile(), "keep both next to a folder of the same name");

        // nested clash: a file stands where a folder of the copy goes
        File q1 = tmp();
        File q2 = tmp();
        put(q1, "top/d1/x.txt", "X");
        put(q1, "top/y.txt", "Y");
        put(q2, "top/d1", "FILE");
        TransferEngine.Result nb = run(new DiskStore(q1, "A"), new DiskStore(q2, "B"), list("/top"), "/", false, TransferEngine.Policy.BOTH, null, null, null);
        check(new File(q2, "top/d1").isFile() && new File(q2, "top/d1 (1)/x.txt").exists() && new File(q2, "top/y.txt").exists() && nb.failed == 0,
                "nested clash, keep both: " + Model.scan(q2));
        File q3 = tmp();
        put(q3, "top/d1", "FILE");
        TransferEngine.Result ns = run(new DiskStore(q1, "A"), new DiskStore(q3, "B"), list("/top"), "/", false, TransferEngine.Policy.SKIP, null, null, null);
        check(new File(q3, "top/d1").isFile() && !new File(q3, "top/d1 (1)").exists() && new File(q3, "top/y.txt").exists() && ns.skipped == 1,
                "nested clash, skip: " + Model.scan(q3) + " " + ns.summary(false));
        File q4 = tmp();
        put(q4, "top/d1", "FILE");
        run(new DiskStore(q1, "A"), new DiskStore(q4, "B"), list("/top"), "/", false, TransferEngine.Policy.REPLACE, null, null, null);
        check(new File(q4, "top/d1").isDirectory() && new File(q4, "top/d1/x.txt").exists(), "nested clash, replace: the file makes way for the folder");

        // ASK: the resolver is asked once per clash, "for all" answers the rest, stop cancels
        File s3 = tmp();
        File s4 = tmp();
        DiskStore a3 = new DiskStore(s3, "A");
        DiskStore b3 = new DiskStore(s4, "B");
        for (String n : new String[] {"1", "2", "3", "4"}) {
            put(s3, n + ".txt", "NEW" + n);
            put(s4, n + ".txt", "OLD" + n);
        }
        final List<String> asked = new ArrayList<>();
        TransferEngine.Result ask1 = run(a3, b3, list("/1.txt", "/2.txt", "/3.txt", "/4.txt"), "/", false, TransferEngine.Policy.ASK, (dest, ex, inc) -> {
            asked.add(dest);
            return asked.size() == 1 ? TransferEngine.Decision.of(TransferEngine.Policy.REPLACE, false)
                    : asked.size() == 2 ? TransferEngine.Decision.of(TransferEngine.Policy.SKIP, true) : TransferEngine.Decision.stop();
        }, null, null);
        check(asked.size() == 2, "ask: two questions, the second said 'for all': " + asked);
        Model m3 = Model.scan(s4);
        check(m3.files.get("/1.txt").equals("NEW1") && m3.files.get("/2.txt").equals("OLD2") && m3.files.get("/3.txt").equals("OLD3")
                && m3.files.get("/4.txt").equals("OLD4") && r1 != null, "ask: replace, then skip for all: " + m3);
        check(ask1.skipped == 3 && ask1.copied == 1, "ask counts: " + ask1.summary(false));
        asked.clear();
        TransferEngine.Result ask2 = run(a3, b3, list("/1.txt", "/2.txt", "/3.txt"), "/", false, TransferEngine.Policy.ASK, (dest, ex, inc) -> {
            asked.add(dest);
            return TransferEngine.Decision.stop();
        }, null, null);
        check(ask2.cancelled && asked.size() == 1 && ask2.copied == 0, "ask: stop cancels the whole transfer");
        TransferEngine.Result ask3 = run(a3, b3, list("/1.txt"), "/", false, TransferEngine.Policy.ASK, null, null, null);
        check(ask3.skipped == 1, "ask without a resolver is the safe answer: skip");
    }

    static void selfAndGuards() throws Exception {
        File r = tmp();
        DiskStore s = new DiskStore(r, "S");
        put(r, "a/f.txt", "F");
        put(r, "a/sub/g.txt", "G");
        // a copy into the folder it is already in makes "name (1)", whatever the policy
        TransferEngine.Result c = run(s, s, list("/a/f.txt"), "/a", false, TransferEngine.Policy.REPLACE, null, null, null);
        check(c.copied == 1 && new File(r, "a/f (1).txt").exists() && slurp(new File(r, "a/f.txt")).equals("F"), "copy onto itself: " + c.summary(false));
        TransferEngine.Result cd = run(s, s, list("/a"), "/", false, TransferEngine.Policy.REPLACE, null, null, null);
        check(cd.copied >= 3 && new File(r, "a (1)/sub/g.txt").exists() && new File(r, "a/sub/g.txt").exists(), "copy a folder onto itself: " + Model.scan(r));
        // a move onto itself is nothing
        TransferEngine.Result mv = run(s, s, list("/a/f.txt"), "/a", true, TransferEngine.Policy.REPLACE, null, null, null);
        check(new File(r, "a/f.txt").exists() && mv.copied == 0 && mv.failed == 0, "move onto itself changes nothing: " + mv.summary(true));
        // a folder into itself, or into its own sub-folder
        Model before = Model.scan(r);
        TransferEngine.Result in1 = run(s, s, list("/a"), "/a/sub", false, TransferEngine.Policy.BOTH, null, null, null);
        TransferEngine.Result in2 = run(s, s, list("/a"), "/a/sub/deeper", true, TransferEngine.Policy.BOTH, null, null, null);
        check(in1.failed == 1 && in2.failed == 1 && Model.scan(r).equals(before) || Model.scan(r).files.equals(before.files), "a folder cannot go inside itself: " + in1.errors + in2.errors);
        check(!new File(r, "a/sub/a").exists() && !new File(r, "a/sub/deeper").exists(), "nothing was made inside it");
        // a missing source
        TransferEngine.Result miss = run(s, new DiskStore(tmp(), "B"), list("/nothing", "/a/f.txt"), "/", false, TransferEngine.Policy.BOTH, null, null, null);
        check(miss.failed == 1 && miss.copied == 1, "a path that vanished is counted: " + miss.errors);
        // the root
        boolean threw = false;
        try {
            TransferEngine.plan(s, list("/"));
        } catch (IOException e) {
            threw = true;
        }
        check(threw, "the whole storage cannot be copied");
    }

    static String slurp(File f) throws IOException {
        return FilesCoreTest.slurp(f);
    }

    static void failures() throws Exception {
        // one unreadable file: the others are copied, the failure is counted, a move keeps that original
        File ra = tmp();
        File rb = tmp();
        Flaky a = new Flaky(new DiskStore(ra, "A"));
        DiskStore b = new DiskStore(rb, "B");
        for (int i = 1; i <= 5; i++) {
            put(ra, "d/f" + i + ".txt", "content" + i);
        }
        a.failRead = p -> p.endsWith("f3.txt");
        TransferEngine.Result r = run(a, b, list("/d"), "/", false, TransferEngine.Policy.BOTH, null, null, null);
        check(r.copied == 4 && r.failed == 1 && !new File(rb, "d/f3.txt").exists() && new File(rb, "d/f4.txt").exists(), "one bad file: " + r.summary(false) + r.errors);
        check(parts(rb).isEmpty(), "no partial file after the failure");
        File rb2 = tmp();
        TransferEngine.Result mv = run(a, new DiskStore(rb2, "B2"), list("/d"), "/", true, TransferEngine.Policy.BOTH, null, null, null);
        check(mv.copied == 4 && mv.failed == 1 && new File(ra, "d/f3.txt").exists() && !new File(ra, "d/f1.txt").exists(), "a move keeps the file that failed: " + Model.scan(ra));

        // a connection that drops in the middle of a file
        File rc = tmp();
        File rd = tmp();
        Flaky c = new Flaky(new DiskStore(rc, "C"));
        put(rc, "big.txt", text("x", 100));
        put(rc, "ok.txt", "fine");
        c.cutRead = p -> p.endsWith("big.txt");
        TransferEngine.Result cr = run(c, new DiskStore(rd, "D"), list("/big.txt", "/ok.txt"), "/", true, TransferEngine.Policy.BOTH, null, null, null);
        check(cr.failed == 1 && cr.copied == 1 && new File(rc, "big.txt").exists() && !new File(rd, "big.txt").exists() && parts(rd).isEmpty(),
                "a dropped connection leaves no half file and keeps the original: " + cr.errors);

        // three failures in a row: stop instead of trying thousands of times
        File re = tmp();
        Flaky e = new Flaky(new DiskStore(re, "E"));
        for (int i = 0; i < 20; i++) {
            put(re, "m/" + (100 + i) + ".txt", "z");
        }
        e.failRead = p -> true;
        TransferEngine.Result er = run(e, new DiskStore(tmp(), "F"), list("/m"), "/", false, TransferEngine.Policy.BOTH, null, null, null);
        check(er.failed >= 3 && er.failed < 20 && er.errors.get(er.errors.size() - 1).contains("repeated errors"), "stops after repeated errors: " + er.failed + " " + er.errors);

        // a full destination stops at once
        File rf = tmp();
        Flaky f = new Flaky(new DiskStore(tmp(), "F"));
        f.failWrite = p -> true;
        f.writeError = "No space left on device";
        for (int i = 0; i < 10; i++) {
            put(rf, "s/" + i + ".txt", "z");
        }
        TransferEngine.Result fr = run(new DiskStore(rf, "G"), f, list("/s"), "/", false, TransferEngine.Policy.BOTH, null, null, null);
        check(fr.failed == 1 && fr.errors.get(fr.errors.size() - 1).contains("full"), "a full destination stops at once: " + fr.failed + " " + fr.errors);
    }

    /** Counts how many times the engine asks "cancelled?", then cancels from that call on. */
    static final class CancelAt implements TransferEngine.Cancel {
        final int at;
        int calls;

        CancelAt(int at) {
            this.at = at;
        }

        @Override
        public boolean cancelled() {
            return ++calls > at;
        }
    }

    static void cancelEverywhere() throws Exception {
        for (int variant = 0; variant < 4; variant++) {
            boolean move = variant % 2 == 1;
            boolean same = variant >= 2;
            int total = -1;
            for (int at = 0; at < 400; at++) {
                File ra = tmp();
                File rb = same ? ra : tmp();
                DiskStore a = new DiskStore(ra, "A");
                a.bufferSize = 512;
                DiskStore b = same ? a : new DiskStore(rb, "B");
                b.bufferSize = 512;
                put(ra, "src/one.txt", text("1", 1800));
                put(ra, "src/sub/two.txt", text("2", 1300));
                put(ra, "src/sub/deep/three.txt", text("3", 700));
                mkdir(ra, "src/empty");
                put(ra, "loose.txt", text("L", 900));
                Model original = Model.scan(ra);
                String dstDir = same ? "/target" : "/target";
                CancelAt ca = new CancelAt(at);
                // for the same-store case use a store that cannot rename, so that the byte-by-byte path is the one being cancelled
                Store sa = a;
                Store sb = b;
                if (same) {
                    Flaky fl = new Flaky(a);
                    fl.noRename = true;
                    sa = fl;
                    sb = fl;
                }
                TransferEngine.Result r = run(sa, sb, list("/src", "/loose.txt"), dstDir, move, TransferEngine.Policy.BOTH, null, ca, null);
                String tag = "cancel@" + at + " move=" + move + " same=" + same;
                check(parts(ra).isEmpty() && parts(rb).isEmpty(), tag + ": no partial files");
                Model srcNow = Model.scan(ra);
                Model dstNow = Model.scan(rb);
                // everything at the destination is complete and identical to the original
                for (String f : dstNow.files.keySet()) {
                    if (!f.startsWith("/target/")) {
                        continue; // (on one store the originals are in the same tree)
                    }
                    String rel = f.substring("/target".length());
                    check(original.files.containsKey(rel) && original.files.get(rel).equals(dstNow.files.get(f)), tag + ": the copy of " + f + " is complete");
                }
                if (!move) {
                    for (String f : original.files.keySet()) {
                        check(original.files.get(f).equals(srcNow.files.get(f)), tag + ": a copy never changes the original " + f);
                    }
                } else {
                    // nothing lost: every original file is still at the source or already at the destination, with its content
                    for (String f : original.files.keySet()) {
                        boolean atSrc = original.files.get(f).equals(srcNow.files.get(f));
                        boolean atDst = original.files.get(f).equals(dstNow.files.get("/target" + f));
                        check(atSrc || atDst, tag + ": " + f + " is not lost");
                    }
                }
                if (!r.cancelled) {
                    total = at;
                    // it ran to the end: the result is complete
                    check(r.copied == 4 && r.failed == 0, tag + ": a full run: " + r.summary(move));
                    break;
                }
            }
            check(total > 10, "cancel loop: the run was cancelled at many different moments before it completed (" + total + ")");
        }
    }

    static void progressAndMisc() throws Exception {
        File ra = tmp();
        File rb = tmp();
        DiskStore a = new DiskStore(ra, "A");
        a.bufferSize = 512;
        DiskStore b = new DiskStore(rb, "B");
        b.bufferSize = 512;
        put(ra, "p/a.bin", text("a", 3000));
        put(ra, "p/b.bin", text("b", 5000));
        put(ra, "p/c/d.bin", text("d", 800));
        List<TransferEngine.Snapshot> seen = new ArrayList<>();
        TransferEngine.Result r = run(a, b, list("/p"), "/", false, TransferEngine.Policy.BOTH, null, null, seen);
        check(r.copied == 3, "progress run");
        long last = -1;
        boolean mono = true;
        for (TransferEngine.Snapshot s : seen) {
            mono &= s.bytesDone >= last && s.bytesDone <= s.bytesTotal && s.filesDone <= s.filesTotal;
            last = s.bytesDone;
        }
        TransferEngine.Snapshot end = seen.get(seen.size() - 1);
        check(mono, "progress only goes forward and never past the total");
        check(end.bytesTotal == 8800 && end.bytesDone == 8800 && end.filesTotal == 3 && end.filesDone == 3, "progress ends at the total: " + end.bytesDone + "/" + end.bytesTotal);

        // skipped files still let the bar reach the end
        File rb3 = tmp();
        DiskStore b3 = new DiskStore(rb3, "B3");
        put(rb3, "p/a.bin", "old");
        List<TransferEngine.Snapshot> seen2 = new ArrayList<>();
        TransferEngine.Result r2 = run(a, b3, list("/p"), "/", false, TransferEngine.Policy.SKIP, null, null, seen2);
        TransferEngine.Snapshot end2 = seen2.get(seen2.size() - 1);
        check(r2.skipped == 1 && r2.copied == 2 && end2.bytesDone == end2.bytesTotal && end2.filesDone == end2.filesTotal,
                "skipped files count as done: " + r2.summary(false) + " " + end2.bytesDone + "/" + end2.bytesTotal);

        // the summary line
        TransferEngine.Result s = new TransferEngine.Result();
        s.copied = 3;
        s.bytes = 4 * 1024 * 1024;
        s.skipped = 1;
        s.failed = 1;
        check(s.summary(false).equals("Copied 3 files (4.0 MB). 1 skipped. 1 failed."), "summary text: " + s.summary(false));
        s.cancelled = true;
        check(s.summary(true).startsWith("Moved 3 files") && s.summary(true).endsWith("Stopped."), "summary of a stopped move");

        // a big tree with many small files
        File big1 = tmp();
        File big2 = tmp();
        Random rnd = new Random(5);
        for (int i = 0; i < 300; i++) {
            put(big1, "t/dir" + (i % 20) + "/file" + i + ".txt", "x" + rnd.nextInt());
        }
        Model before = Model.scan(big1);
        TransferEngine.Result big = run(new DiskStore(big1, "A"), new DiskStore(big2, "B"), list("/t"), "/", true, TransferEngine.Policy.BOTH, null, null, null);
        check(big.copied == 300 && Model.scan(big2).files.size() == 300 && Model.scan(big1).files.isEmpty() && !new File(big1, "t").exists(), "300 files moved: " + big.summary(true));
        check(before.files.values().containsAll(Model.scan(big2).files.values()), "with their contents");
    }

    // ---------------------------------------------------------------- random stories against the model

    static void randomTree(File root, Random rnd, String prefix) throws IOException {
        int n = 2 + rnd.nextInt(7);
        for (int i = 0; i < n; i++) {
            String name = rnd.nextBoolean() ? "f" + rnd.nextInt(4) + (rnd.nextBoolean() ? ".txt" : ".jpg") : "d" + rnd.nextInt(3);
            File f = new File(root, prefix + name);
            if (name.startsWith("d")) {
                if (f.isFile()) {
                    continue;
                }
                f.mkdirs();
                if (prefix.length() < 8 && rnd.nextInt(3) > 0) {
                    randomTree(root, rnd, prefix + name + "/");
                }
            } else if (!f.isDirectory()) {
                put(root, prefix + name, "c" + rnd.nextInt(1000));
            }
        }
    }

    static void randomStories() throws Exception {
        TransferEngine.Policy[] pols = {TransferEngine.Policy.REPLACE, TransferEngine.Policy.SKIP, TransferEngine.Policy.BOTH};
        for (int seed = 0; seed < 1500; seed++) {
            Random rnd = new Random(seed);
            boolean same = rnd.nextInt(3) == 0;
            boolean move = rnd.nextBoolean();
            File ra = tmp();
            File rb = same ? ra : tmp();
            randomTree(ra, rnd, "");
            if (!same) {
                randomTree(rb, rnd, "");
            }
            DiskStore a = new DiskStore(ra, "A");
            DiskStore b = same ? a : new DiskStore(rb, "B");
            Model ms = Model.scan(ra);
            Model md = same ? ms : Model.scan(rb);
            // the selection: one to three paths, none inside another
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
            // the destination folder: an existing folder of the other store, or a new one; on one store: not inside a selected
            // folder and not the folder the items are already in (those cases have their own tests)
            List<String> dirs = new ArrayList<>(md.dirs);
            dirs.add("/");
            dirs.add("/new/place");
            String dstDir = dirs.get(rnd.nextInt(dirs.size()));
            if (same) {
                boolean bad = false;
                for (String o : roots) {
                    bad |= FsPath.isSameOrUnder(dstDir, o) || FsPath.parent(o).equals(FsPath.clean(dstDir));
                }
                if (bad) {
                    dstDir = "/new/place";
                }
            }
            TransferEngine.Policy pol = pols[rnd.nextInt(pols.length)];
            Model expectSrc = ms;
            Model expectDst = md;
            Model wantSrc = Model.scan(ra);
            Model wantDst = same ? wantSrc : Model.scan(rb);
            modelTransfer(wantSrc, roots, wantDst, dstDir, move, pol);
            Store sa = a;
            Store sb = b;
            if (same && rnd.nextBoolean()) {
                Flaky fl = new Flaky(a);
                fl.noRename = true; // also exercise move by copy and delete
                sa = fl;
                sb = fl;
            }
            TransferEngine.Result r = run(sa, sb, roots, dstDir, move, pol, null, null, null);
            Model gotSrc = Model.scan(ra);
            Model gotDst = Model.scan(rb);
            String tag = "story " + seed + " (" + pol + ", " + (move ? "move" : "copy") + ", " + (same ? "one store" : "two stores") + ", " + roots + " -> " + dstDir + ")";
            check(parts(ra).isEmpty() && parts(rb).isEmpty(), tag + ": no partial files");
            if (same) {
                check(gotDst.equals(wantDst), tag + ":\n    got  " + gotDst + "\n    want " + wantDst + "\n    from " + expectSrc);
            } else {
                check(gotDst.equals(wantDst), tag + ": destination\n    got  " + gotDst + "\n    want " + wantDst + "\n    was  " + expectDst);
                check(gotSrc.equals(wantSrc), tag + ": source\n    got  " + gotSrc + "\n    want " + wantSrc);
            }
        }
    }
}
