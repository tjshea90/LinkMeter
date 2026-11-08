package com.tj.linkmeter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * One side of the file manager: this device or the other one. It holds where you are, what is listed and what is selected, and does
 * the reading (on a worker thread). The screen ({@link FilesTab}) only draws it. Because this class has the I/O, the screen has none.
 */
final class FileBrowser {
    static final int THIS = 0;
    static final int OTHER = 1;

    final int side;
    final String label;
    String path = "/";
    /** "" for a folder; otherwise the kind of the "Recent" list being shown: "images" or "all". */
    String recent = "";
    /** Not empty: the results of a search for this text below {@link #path}. */
    String query = "";
    List<FsEntry> all = new ArrayList<>();
    List<FsEntry> shown = new ArrayList<>();
    final LinkedHashSet<String> selected = new LinkedHashSet<>();
    boolean selecting;
    boolean loading;
    /** Must be read again the next time it is shown. */
    boolean dirty = true;
    boolean truncated;
    String error = "";
    /** Counts loads, so an answer that arrives after a newer request is ignored. */
    int seq;
    long free = -1;
    long total = -1;
    final List<String[]> shortcuts = new ArrayList<>();
    /** The other device runs an older LinkMeter without the newer file routes. */
    boolean oldPeer;

    private DiskStore disk;
    private RemoteStore remote;
    private String remoteKey = "";

    FileBrowser(int side, String label) {
        this.side = side;
        this.label = label;
    }

    String id() {
        return side == THIS ? "this" : "other";
    }

    boolean isFolderView() {
        return recent.isEmpty() && query.isEmpty();
    }

    boolean atRoot() {
        return path.equals("/") && isFolderView();
    }

    static String noPeer(MainActivity act) {
        return Cfg.PHONE.equals(Cfg.mode(act))
                ? "The phone hasn't seen the tablet yet. Open LinkMeter on the tablet and turn on \"Let the phone browse this "
                        + "tablet's files\" in its Settings."
                : "The phone can't be reached. Is the tablet on the phone's hotspot, and has it been paired?";
    }

    synchronized DiskStore disk() throws IOException {
        if (disk == null) {
            disk = new DiskStore(Files.root(), "this");
        }
        return disk;
    }

    /** This device's storage, or the other device's (rebuilt when its address or secret changed). */
    synchronized Store store(MainActivity act) throws IOException {
        if (side == THIS) {
            return disk();
        }
        return remoteStore(act);
    }

    synchronized RemoteStore remoteStore(MainActivity act) throws IOException {
        String host = Net.peerHost(act);
        if (host.isEmpty()) {
            throw new IOException(noPeer(act));
        }
        String token = Net.peerToken(act);
        String key = host + "|" + token;
        if (remote == null || !key.equals(remoteKey)) {
            remote = new RemoteStore(new PeerLink(host, token), "other");
            remoteKey = key;
        }
        return remote;
    }

    /** The full path of a listed entry (search results and recent files carry their own). */
    String full(FsEntry e) {
        return e.path.isEmpty() ? FsPath.join(path, e.name) : e.path;
    }

    /** Reads what is to be shown now. Slow: a worker thread only. */
    List<FsEntry> fetch(MainActivity act) throws IOException {
        Store s = store(act);
        if (!recent.isEmpty()) {
            return side == THIS ? new AndroidMedia(act).recent(recent, 150) : remoteStore(act).recent(recent, 150);
        }
        if (!query.isEmpty()) {
            if (side == THIS) {
                return FsSearch.run(s, path, query.toLowerCase(Locale.US), 300, 5000).found;
            }
            return remoteStore(act).search(path, query);
        }
        List<FsEntry> l = s.list(path, true);
        truncated = s instanceof DiskStore ? ((DiskStore) s).truncated : ((RemoteStore) s).truncated;
        return l;
    }

    /** Free space, shortcut folders, and whether the other device is an older version. Failures leave what was known. Worker thread only. */
    void fetchInfo(MainActivity act) {
        try {
            if (side == THIS) {
                long[] sp = disk().space();
                free = sp[0];
                total = sp[1];
                shortcuts.clear();
                shortcuts.addAll(FsApi.shortcuts(disk()));
            } else {
                RemoteStore.Info i = remoteStore(act).info();
                free = i.free;
                total = i.total;
                oldPeer = i.version < FsApi.VERSION;
                shortcuts.clear();
                shortcuts.addAll(i.shortcuts);
            }
        } catch (IOException ignored) {
            // the listing itself reports a problem; the space line just stays as it was
        }
    }

    /** Sorts and filters what was read into what is shown. Recent files and search results keep their own order. */
    void applyView(FilesPrefs p) {
        List<FsEntry> l = FileSort.visible(all, p.hidden());
        if (isFolderView()) {
            FileSort.sort(l, p.order(), p.ascending());
        }
        shown = l;
    }

    boolean isSelected(FsEntry e) {
        return selected.contains(full(e));
    }

    void toggle(FsEntry e) {
        String k = full(e);
        if (!selected.remove(k)) {
            selected.add(k);
        }
        if (selected.isEmpty()) {
            selecting = false;
        }
    }

    void clearSelection() {
        selected.clear();
        selecting = false;
    }

    List<FsEntry> selectedEntries() {
        List<FsEntry> out = new ArrayList<>();
        for (FsEntry e : shown) {
            if (selected.contains(full(e))) {
                out.add(e);
            }
        }
        return out;
    }

    List<String> selectedPaths() {
        List<String> out = new ArrayList<>();
        for (FsEntry e : selectedEntries()) {
            out.add(full(e));
        }
        return out;
    }
}
