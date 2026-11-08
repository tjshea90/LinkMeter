package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Paths inside a device's shared storage: always "/" separated, always starting with "/", "/" is the storage root.
 * No Android in it (tested offline). Nothing here touches the disk.
 */
final class FsPath {
    private FsPath() {}

    /** "/a" + "b" is "/a/b"; "/" + "b" is "/b". */
    static String join(String dir, String name) {
        if (dir == null || dir.isEmpty() || dir.equals("/")) {
            return "/" + name;
        }
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    /** "/a/b" is "/a"; "/a" is "/"; "/" stays "/". */
    static String parent(String path) {
        String p = clean(path);
        int i = p.lastIndexOf('/');
        return i <= 0 ? "/" : p.substring(0, i);
    }

    /** The last part: "/a/b.txt" is "b.txt"; "/" is "". */
    static String name(String path) {
        String p = clean(path);
        int i = p.lastIndexOf('/');
        return p.substring(i + 1);
    }

    /** The extension in lower case without the dot: "a.JPG" is "jpg"; "a.tar.gz" is "gz"; ".bashrc" and "a" are "". */
    static String ext(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    /** The name without its extension: "a.jpg" is "a"; ".bashrc" is ".bashrc". */
    static String base(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    /**
     * Tidies a path: one leading "/", no doubled or trailing "/", "." and ".." resolved. A ".." that would go above the root
     * stops at the root, so a cleaned path can never point outside the storage.
     */
    static String clean(String path) {
        if (path == null) {
            return "/";
        }
        List<String> out = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!out.isEmpty()) {
                    out.remove(out.size() - 1);
                }
                continue;
            }
            out.add(part);
        }
        StringBuilder sb = new StringBuilder();
        for (String s : out) {
            sb.append('/').append(s);
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    /** Is path the same as dir, or somewhere inside it? "/ab" is NOT inside "/a". */
    static boolean isSameOrUnder(String path, String dir) {
        String p = clean(path);
        String d = clean(dir);
        if (d.equals("/")) {
            return true;
        }
        return p.equals(d) || p.startsWith(d + "/");
    }

    /** The part of path below dir, without a leading "/": "/a/b/c" relative to "/a" is "b/c". Empty if path is dir itself. */
    static String relativeTo(String path, String dir) {
        String p = clean(path);
        String d = clean(dir);
        if (p.equals(d)) {
            return "";
        }
        if (d.equals("/")) {
            return p.substring(1);
        }
        return p.startsWith(d + "/") ? p.substring(d.length() + 1) : p;
    }

    /** Breadcrumbs for a path bar: [label, full path] pairs from the root down; the root is labelled rootLabel. */
    static List<String[]> crumbs(String path, String rootLabel) {
        List<String[]> out = new ArrayList<>();
        out.add(new String[] {rootLabel, "/"});
        String p = clean(path);
        if (p.equals("/")) {
            return out;
        }
        String acc = "";
        for (String part : p.substring(1).split("/")) {
            acc = acc + "/" + part;
            out.add(new String[] {part, acc});
        }
        return out;
    }

    /**
     * A name that is not taken: "a.zip" if free, else "a (1).zip", "a (2).zip"... taken says whether a name is already used in the
     * folder. A folder name has no extension to keep apart, so pass dir = true for it.
     */
    static String unique(String name, boolean dir, java.util.function.Predicate<String> taken) {
        if (!taken.test(name)) {
            return name;
        }
        String base = dir ? name : base(name);
        String ext = dir ? "" : name.substring(base.length());
        for (int i = 1; i < 10000; i++) {
            String n = base + " (" + i + ")" + ext;
            if (!taken.test(n)) {
                return n;
            }
        }
        return base + "-" + System.currentTimeMillis() + ext;
    }
}
