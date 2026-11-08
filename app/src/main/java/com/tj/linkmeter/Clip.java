package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;

/**
 * The clipboard of the file manager: what was copied or cut, and from where. One clipboard serves both devices, so something
 * copied on the phone can be pasted on the tablet. No Android in it.
 */
final class Clip {
    enum Mode { COPY, CUT }

    private Mode mode = Mode.COPY;
    private String side = "";
    private String dir = "/";
    private final List<String> paths = new ArrayList<>();
    private final List<FsEntry> entries = new ArrayList<>();

    /** Puts items on the clipboard, replacing what was there. side is the id of the store they are in. */
    void set(Mode mode, String side, String dir, List<String> paths, List<FsEntry> entries) {
        this.mode = mode;
        this.side = side;
        this.dir = dir;
        this.paths.clear();
        this.paths.addAll(paths);
        this.entries.clear();
        this.entries.addAll(entries);
    }

    void clear() {
        paths.clear();
        entries.clear();
    }

    boolean isEmpty() {
        return paths.isEmpty();
    }

    int count() {
        return paths.size();
    }

    Mode mode() {
        return mode;
    }

    /** The id of the store the items are in. */
    String side() {
        return side;
    }

    /** The folder they were taken from (for "return to source folder"). */
    String dir() {
        return dir;
    }

    List<String> paths() {
        return new ArrayList<>(paths);
    }

    List<FsEntry> entries() {
        return new ArrayList<>(entries);
    }

    /** After a paste: a cut can be pasted only once (the items moved), a copy stays for "Copy again". */
    void afterPaste() {
        if (mode == Mode.CUT) {
            clear();
        }
    }

    /** "3 items copied" or "report.pdf cut". */
    String summary() {
        String what = paths.size() == 1 && !entries.isEmpty() ? entries.get(0).name : FsFormat.items(paths.size());
        return what + (mode == Mode.CUT ? " cut" : " copied");
    }
}
