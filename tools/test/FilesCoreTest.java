package com.tj.linkmeter;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Offline tests of the file manager's pure parts: paths, file kinds, sorting, the clipboard, number formatting and DiskStore (on a
 * temp folder, including a symbolic link that tries to leave the storage). Run by tools/test.sh. Prints "N checks, M failed".
 */
public final class FilesCoreTest {
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

    static void eq(Object got, Object want, String msg) {
        check(got == null ? want == null : got.equals(want), msg + ": got " + got + ", wanted " + want);
    }

    // ---------------------------------------------------------------- helpers for the disk tests

    static File tmp() throws IOException {
        return java.nio.file.Files.createTempDirectory("filescore").toFile();
    }

    static void put(File root, String rel, String text) throws IOException {
        File f = new File(root, rel);
        f.getParentFile().mkdirs();
        java.nio.file.Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    static InputStream in(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    static String slurp(File f) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static boolean throwsIO(Runnable0 r) {
        try {
            r.run();
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    interface Runnable0 {
        void run() throws IOException;
    }

    static List<String> noParts(File root) {
        List<String> found = new ArrayList<>();
        walk(root, found);
        return found;
    }

    private static void walk(File d, List<String> found) {
        File[] fs = d.listFiles();
        if (fs == null) {
            return;
        }
        for (File f : fs) {
            if (f.getName().endsWith(DiskStore.PART)) {
                found.add(f.getPath());
            }
            if (f.isDirectory()) {
                walk(f, found);
            }
        }
    }

    // ---------------------------------------------------------------- tests

    public static void main(String[] args) throws Exception {
        paths();
        kinds();
        sorting();
        clip();
        format();
        disk();
        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static void paths() {
        eq(FsPath.join("/", "a"), "/a", "join root");
        eq(FsPath.join("/a", "b"), "/a/b", "join");
        eq(FsPath.join("/a/", "b"), "/a/b", "join trailing slash");
        eq(FsPath.join("", "b"), "/b", "join empty");
        eq(FsPath.parent("/a/b"), "/a", "parent");
        eq(FsPath.parent("/a"), "/", "parent of top");
        eq(FsPath.parent("/"), "/", "parent of root");
        eq(FsPath.parent("/a/b/"), "/a", "parent with trailing slash");
        eq(FsPath.name("/a/b.txt"), "b.txt", "name");
        eq(FsPath.name("/"), "", "name of root");
        eq(FsPath.name("/a/"), "a", "name with trailing slash");
        eq(FsPath.ext("a.JPG"), "jpg", "ext lower case");
        eq(FsPath.ext("a.tar.gz"), "gz", "ext last part");
        eq(FsPath.ext(".bashrc"), "", "dot file has no extension");
        eq(FsPath.ext("a"), "", "no extension");
        eq(FsPath.ext("a."), "", "trailing dot");
        eq(FsPath.base("a.jpg"), "a", "base");
        eq(FsPath.base(".bashrc"), ".bashrc", "base of dot file");
        eq(FsPath.base("a.tar.gz"), "a.tar", "base of double extension");
        eq(FsPath.clean("//a///b/./c/../d/"), "/a/b/d", "clean");
        eq(FsPath.clean("../../x"), "/x", "clean cannot go above the root");
        eq(FsPath.clean(""), "/", "clean empty");
        eq(FsPath.clean("/.."), "/", "clean dot dot at root");
        eq(FsPath.clean(null), "/", "clean null");
        check(FsPath.isSameOrUnder("/a/b", "/a"), "under");
        check(FsPath.isSameOrUnder("/a", "/a"), "same");
        check(!FsPath.isSameOrUnder("/ab", "/a"), "/ab is not inside /a");
        check(FsPath.isSameOrUnder("/a", "/"), "everything is under the root");
        check(!FsPath.isSameOrUnder("/", "/a"), "the root is not inside /a");
        eq(FsPath.relativeTo("/a/b/c", "/a"), "b/c", "relative");
        eq(FsPath.relativeTo("/a", "/a"), "", "relative to itself");
        eq(FsPath.relativeTo("/a/b", "/"), "a/b", "relative to the root");
        List<String[]> c = FsPath.crumbs("/a/b", "Tablet");
        check(c.size() == 3 && c.get(0)[0].equals("Tablet") && c.get(0)[1].equals("/") && c.get(2)[0].equals("b") && c.get(2)[1].equals("/a/b"),
                "crumbs");
        check(FsPath.crumbs("/", "Phone").size() == 1, "crumbs of the root");
        Set<String> taken = new HashSet<>(Arrays.asList("a.zip", "a (1).zip"));
        eq(FsPath.unique("a.zip", false, taken::contains), "a (2).zip", "unique skips taken numbers");
        eq(FsPath.unique("b.zip", false, taken::contains), "b.zip", "unique keeps a free name");
        eq(FsPath.unique("Photos", true, new HashSet<>(Collections.singleton("Photos"))::contains), "Photos (1)", "unique folder");
        eq(FsPath.unique("v1.2", true, new HashSet<>(Collections.singleton("v1.2"))::contains), "v1.2 (1)", "a folder with a dot keeps it whole");
        eq(FsPath.unique(".bashrc", false, new HashSet<>(Collections.singleton(".bashrc"))::contains), ".bashrc (1)", "unique dot file");
    }

    static void kinds() {
        eq(FileKind.of("a.JPG", false), FileKind.IMAGE, "jpg is an image");
        eq(FileKind.of("movie.MKV", false), FileKind.VIDEO, "mkv is a video");
        eq(FileKind.of("x.apk", false), FileKind.APK, "apk");
        eq(FileKind.of("x", false), FileKind.OTHER, "no extension");
        eq(FileKind.of("a.jpg", true), FileKind.FOLDER, "a folder named like an image is a folder");
        eq(FileKind.of("notes.txt", false), FileKind.TEXT, "text");
        eq(FileKind.of("a.zip", false), FileKind.ARCHIVE, "zip");
        eq(FileKind.of("a.pdf", false), FileKind.PDF, "pdf");
        for (FileKind k : FileKind.values()) {
            boolean thumb = k == FileKind.IMAGE || k == FileKind.VIDEO || k == FileKind.PDF || k == FileKind.APK;
            check(k.hasThumbnail() == thumb, "thumbnail flag of " + k);
        }
        eq(FileKind.mime("a.jpg"), "image/jpeg", "mime of jpg");
        eq(FileKind.mime("a.MP4"), "video/mp4", "mime of mp4");
        eq(FileKind.mime("x.unknown"), "application/octet-stream", "unknown mime");
        eq(FileKind.mime("x.apks"), "application/octet-stream", "apks mime");
    }

    static FsEntry e(String n, boolean dir, long size, long time) {
        return new FsEntry(n, dir, size, time);
    }

    static void sorting() {
        check(FileSort.naturalCompare("img2", "img10") < 0, "img2 before img10");
        check(FileSort.naturalCompare("IMG2", "img10") < 0, "case does not matter");
        check(FileSort.naturalCompare("a01", "a1") != 0, "different leading zeros are still ordered");
        check(Integer.signum(FileSort.naturalCompare("a01", "a1")) == -Integer.signum(FileSort.naturalCompare("a1", "a01")), "antisymmetric");
        check(FileSort.naturalCompare("a", "a") == 0, "equal");
        check(FileSort.naturalCompare("a", "ab") < 0 && FileSort.naturalCompare("ab", "a") > 0, "prefix first");
        check(FileSort.naturalCompare("file 9.txt", "file 10.txt") < 0, "numbers inside names");
        check(FileSort.naturalCompare("99999999999999999999999", "100000000000000000000000") < 0, "huge numbers do not overflow");

        List<FsEntry> l = new ArrayList<>(Arrays.asList(e("b.txt", false, 5, 30), e("Zed", true, 0, 10), e("a.txt", false, 50, 20), e("alpha", true, 0, 40)));
        FileSort.sort(l, FileSort.Order.NAME, true);
        eq(names(l), "alpha,Zed,a.txt,b.txt", "name ascending, folders first");
        FileSort.sort(l, FileSort.Order.NAME, false);
        eq(names(l), "Zed,alpha,b.txt,a.txt", "name descending, folders still first");
        FileSort.sort(l, FileSort.Order.DATE, false);
        eq(names(l), "alpha,Zed,b.txt,a.txt", "newest first");
        FileSort.sort(l, FileSort.Order.DATE, true);
        eq(names(l), "Zed,alpha,a.txt,b.txt", "oldest first");
        FileSort.sort(l, FileSort.Order.SIZE, false);
        eq(names(l), "alpha,Zed,a.txt,b.txt", "largest first, folders by name");
        FileSort.sort(l, FileSort.Order.SIZE, true);
        eq(names(l), "alpha,Zed,b.txt,a.txt", "smallest first");
        List<FsEntry> k = new ArrayList<>(Arrays.asList(e("x.zip", false, 1, 1), e("a.jpg", false, 1, 1), e("b.jpg", false, 1, 1), e("m.mp4", false, 1, 1)));
        FileSort.sort(k, FileSort.Order.KIND, true);
        eq(names(k), "x.zip,a.jpg,b.jpg,m.mp4", "kind order: Archive, Image, Video; by name inside a kind");
        check(names(k).indexOf("a.jpg") < names(k).indexOf("b.jpg"), "kind: same kind by name");
        List<FsEntry> hid = Arrays.asList(e(".hidden", false, 1, 1), e("shown", false, 1, 1), e(".cache", true, 0, 1));
        eq(names(FileSort.visible(hid, false)), "shown", "hidden are left out");
        eq(FileSort.visible(hid, true).size(), 3, "hidden shown on request");

        // random lists: folders first, the key never goes the wrong way, and sorting twice changes nothing
        Random rnd = new Random(1);
        for (int round = 0; round < 300; round++) {
            List<FsEntry> r = new ArrayList<>();
            for (int i = 0; i < 1 + rnd.nextInt(30); i++) {
                r.add(e("n" + rnd.nextInt(40) + (rnd.nextBoolean() ? ".jpg" : ".txt"), rnd.nextInt(4) == 0, rnd.nextInt(1000), rnd.nextInt(100)));
            }
            for (FileSort.Order o : FileSort.Order.values()) {
                for (boolean asc : new boolean[] {true, false}) {
                    List<FsEntry> s = new ArrayList<>(r);
                    FileSort.sort(s, o, asc);
                    boolean seenFile = false;
                    boolean ok = true;
                    for (FsEntry x : s) {
                        if (!x.dir) {
                            seenFile = true;
                        } else if (seenFile) {
                            ok = false;
                        }
                    }
                    check(ok, "random: folders first");
                    for (int i = 0; i + 1 < s.size(); i++) {
                        FsEntry a = s.get(i);
                        FsEntry b = s.get(i + 1);
                        if (a.dir != b.dir) {
                            continue;
                        }
                        if (o == FileSort.Order.DATE) {
                            check(asc ? a.time <= b.time : a.time >= b.time, "random: date order");
                        } else if (o == FileSort.Order.SIZE && !a.dir) {
                            check(asc ? a.size <= b.size : a.size >= b.size, "random: size order");
                        }
                    }
                    List<FsEntry> again = new ArrayList<>(s);
                    Collections.shuffle(again, rnd);
                    FileSort.sort(again, o, asc);
                    check(names(again).equals(names(s)), "random: the order does not depend on the starting order (" + o + ", " + asc + ")");
                }
            }
        }
    }

    static String names(List<FsEntry> l) {
        StringBuilder sb = new StringBuilder();
        for (FsEntry x : l) {
            sb.append(sb.length() > 0 ? "," : "").append(x.name);
        }
        return sb.toString();
    }

    static void clip() {
        Clip c = new Clip();
        check(c.isEmpty() && c.count() == 0, "starts empty");
        c.set(Clip.Mode.COPY, "tablet", "/DCIM", Arrays.asList("/DCIM/a.jpg", "/DCIM/b.jpg"),
                Arrays.asList(e("a.jpg", false, 1, 1), e("b.jpg", false, 1, 1)));
        check(!c.isEmpty() && c.count() == 2 && c.side().equals("tablet") && c.dir().equals("/DCIM") && c.mode() == Clip.Mode.COPY, "set");
        eq(c.summary(), "2 items copied", "summary of two");
        c.afterPaste();
        check(c.count() == 2, "a copy stays after a paste (Copy again)");
        c.set(Clip.Mode.CUT, "phone", "/Download", Collections.singletonList("/Download/r.pdf"), Collections.singletonList(e("r.pdf", false, 1, 1)));
        eq(c.summary(), "r.pdf cut", "summary of one");
        c.afterPaste();
        check(c.isEmpty(), "a cut can be pasted once");
        List<String> p = c.paths();
        p.add("x");
        check(c.paths().isEmpty(), "paths() is a copy");
    }

    static void format() {
        eq(FsFormat.size(0), "0 B", "0 bytes");
        eq(FsFormat.size(1023), "1023 B", "1023 bytes");
        eq(FsFormat.size(1024), "1 KB", "1 KB");
        eq(FsFormat.size(1536), "2 KB", "1.5 KB rounds up");
        eq(FsFormat.size(5L * 1024 * 1024), "5.0 MB", "5 MB");
        eq(FsFormat.size(3L * 1024 * 1024 * 1024), "3.00 GB", "3 GB");
        eq(FsFormat.size(-1), "?", "unknown");
        eq(FsFormat.items(1), "1 item", "one item");
        eq(FsFormat.items(3), "3 items", "items");
        eq(FsFormat.files(1), "1 file", "one file");
        eq(FsFormat.files(0), "0 files", "no files");
        eq(FsFormat.eta(5), "5 s", "eta seconds");
        eq(FsFormat.eta(100), "1 min", "eta minutes");
        eq(FsFormat.eta(4000), "1 h 6 min", "eta hours");
        eq(FsFormat.eta(-1), "", "eta unknown");
    }

    static void disk() throws Exception {
        File root = tmp();
        DiskStore ds = new DiskStore(root, "t");
        put(root, "a/b.txt", "hello");
        put(root, "a/c/d.txt", "deep");
        put(root, "top.txt", "top");
        new File(root, ".hid").mkdirs();

        // confinement
        check(ds.resolve("/../x").getPath().startsWith(ds.root().getPath()), "dot dot cannot leave the root");
        File outside = tmp();
        put(outside, "secret.txt", "secret");
        java.nio.file.Files.createSymbolicLink(new File(root, "link").toPath(), outside.toPath());
        check(throwsIO(() -> ds.list("/link", false)), "a symbolic link out of the storage is refused (list)");
        check(throwsIO(() -> ds.read("/link/secret.txt", 0)), "a symbolic link out of the storage is refused (read)");
        check(throwsIO(() -> ds.stat("/link/secret.txt")), "a symbolic link out of the storage is refused (stat)");

        // list, stat, counts
        List<FsEntry> top = ds.list("/", true);
        Set<String> names = new HashSet<>();
        for (FsEntry x : top) {
            names.add(x.name);
        }
        check(names.contains("a") && names.contains("top.txt") && names.contains(".hid"), "list shows what is there");
        for (FsEntry x : top) {
            if (x.name.equals("a")) {
                check(x.dir && x.count == 2, "a folder's child count: " + x.count);
            }
            if (x.name.equals("top.txt")) {
                check(!x.dir && x.size == 3 && x.time > 0, "file size and time");
            }
        }
        check(ds.stat("/nothing") == null, "stat of a missing path is null");
        check(ds.stat("/a/b.txt").size == 5, "stat size");
        check(ds.stat("/").dir, "stat of the root");
        check(throwsIO(() -> ds.list("/top.txt", false)), "list of a file");

        // read with an offset
        try (InputStream r = ds.read("/a/b.txt", 2)) {
            eq(new String(readAll(r), StandardCharsets.UTF_8), "llo", "read from an offset");
        }

        // write: new, replace, mtime, folder in the way, missing parent
        final long[] moved = {0};
        ds.write("/a/new.txt", in("12345"), 5, 1_600_000_000L, n -> moved[0] += n);
        eq(slurp(new File(root, "a/new.txt")), "12345", "write");
        check(moved[0] == 5, "progress reports every byte: " + moved[0]);
        check(new File(root, "a/new.txt").lastModified() / 1000 == 1_600_000_000L, "modification time is kept");
        ds.write("/a/new.txt", in("abc"), 3, 0, null);
        eq(slurp(new File(root, "a/new.txt")), "abc", "write replaces a file");
        check(throwsIO(() -> ds.write("/a/c", in("x"), 1, 0, null)), "a folder in the way");
        check(throwsIO(() -> ds.write("/nope/x.txt", in("x"), 1, 0, null)), "a missing folder");
        check(noParts(root).isEmpty(), "no partial files after those");

        // write that fails or is cancelled leaves nothing behind and keeps the old file
        check(throwsIO(() -> ds.write("/a/b.txt", in("12"), 5, 0, null)), "a source that is too short");
        eq(slurp(new File(root, "a/b.txt")), "hello", "the old file survives a failed replace");
        check(noParts(root).isEmpty(), "no partial file after a short source");
        ds.bufferSize = 512;
        final int[] calls = {0};
        boolean cancelled = false;
        try {
            ds.write("/a/b.txt", in(repeat("z", 5000)), 5000, 0, n -> {
                if (++calls[0] == 3) {
                    throw new Store.Cancelled();
                }
            });
        } catch (Store.Cancelled c) {
            cancelled = true;
        }
        check(cancelled, "a cancel comes out as Cancelled");
        eq(slurp(new File(root, "a/b.txt")), "hello", "the old file survives a cancelled replace");
        check(noParts(root).isEmpty(), "no partial file after a cancel");
        ds.write("/a/long.txt", in(repeat("y", 3000) + "EXTRA"), 3000, 0, null);
        eq(new File(root, "a/long.txt").length(), 3000L, "only len bytes are written");
        ds.bufferSize = 64 * 1024;

        // listing hides partial files
        put(root, "a/hidden.txt" + DiskStore.PART, "half");
        for (FsEntry x : ds.list("/a", false)) {
            check(!x.name.endsWith(DiskStore.PART), "a partial file is not listed");
        }
        new File(root, "a/hidden.txt" + DiskStore.PART).delete();

        // mkdirs, rename, delete
        ds.mkdirs("/x/y/z");
        check(new File(root, "x/y/z").isDirectory(), "mkdirs makes parents");
        ds.mkdirs("/x/y/z");
        check(throwsIO(() -> ds.mkdirs("/top.txt")), "mkdirs over a file");
        check(ds.rename("/top.txt", "/x/top2.txt") && new File(root, "x/top2.txt").exists() && !new File(root, "top.txt").exists(), "rename moves");
        check(!ds.rename("/x/top2.txt", "/a/b.txt"), "rename never overwrites");
        check(!ds.rename("/x/top2.txt", "/nope/dir/x.txt"), "rename into a missing folder is false");
        check(!ds.rename("/missing", "/x/m"), "rename of a missing path is false");
        check(!ds.copyWithin("/a/b.txt", "/a/q.txt", null), "a disk store has no same-device copy of its own");

        // delete: recursive, and a symbolic link inside is removed, not followed
        java.nio.file.Files.createSymbolicLink(new File(root, "a/c/lnk").toPath(), outside.toPath());
        ds.delete("/a/c");
        check(!new File(root, "a/c").exists(), "folder deleted");
        check(new File(outside, "secret.txt").exists(), "deleting a folder never deletes what a link inside points to");
        check(throwsIO(() -> ds.delete("/")), "the root cannot be deleted");
        check(throwsIO(() -> ds.delete("/missing")), "deleting a missing path");

        // walk: folders before their contents, in name order
        File w = tmp();
        DiskStore wd = new DiskStore(w, "w");
        put(w, "b/2.txt", "2");
        put(w, "b/1.txt", "1");
        put(w, "a.txt", "a");
        put(w, "b/sub/z.txt", "z");
        StringBuilder sb = new StringBuilder();
        for (Store.Found f : wd.walk("/")) {
            sb.append(f.rel).append(f.dir ? "/" : "").append(' ');
        }
        // inside each folder: its sub-folders first (each followed by its own contents), then its files by name
        eq(sb.toString().trim(), "b/ b/sub/ b/sub/z.txt b/1.txt b/2.txt a.txt", "walk order");
    }

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) {
            bo.write(b, 0, n);
        }
        return bo.toByteArray();
    }
}
