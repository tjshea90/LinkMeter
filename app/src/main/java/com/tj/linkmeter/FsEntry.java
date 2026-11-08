package com.tj.linkmeter;

/** One file or folder in a listing, the same on both devices. No Android in it (tested offline). */
final class FsEntry {
    String name = "";
    boolean dir;
    /** Bytes (0 for a folder). */
    long size;
    /** Last modified, in seconds. */
    long time;
    /** For a folder: how many items are inside, or -1 if that was not asked for. */
    int count = -1;
    /** Where it was found, for search results and "Recent" (a full path); empty in a plain folder listing. */
    String path = "";

    FsEntry() {}

    FsEntry(String name, boolean dir, long size, long time) {
        this.name = name;
        this.dir = dir;
        this.size = size;
        this.time = time;
    }

    boolean hidden() {
        return name.startsWith(".");
    }

    FileKind kind() {
        return FileKind.of(name, dir);
    }

    @Override
    public String toString() {
        return (dir ? "[" + name + "]" : name) + (dir ? "" : " " + size);
    }
}
