package com.tj.linkmeter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Finds files and folders whose name contains some text, below a folder. The same code answers the other device's search and searches this device. No Android in it. */
final class FsSearch {
    private FsSearch() {}

    static final class Result {
        /** Each entry's path is its full path inside the storage. */
        final List<FsEntry> found = new ArrayList<>();
        /** The search stopped early (too many entries, too long, or enough found). */
        boolean partial;
    }

    /** needle must be lower case. Stops after max results, 50000 entries looked at, or budgetMs milliseconds. */
    static Result run(Store store, String dir, String needle, int max, long budgetMs) throws IOException {
        Result r = new Result();
        long[] visited = {0};
        r.partial = !walk(store, FsPath.clean(dir), needle, max, r.found, visited, System.nanoTime() + budgetMs * 1_000_000L);
        return r;
    }

    /** False when it had to stop early. */
    private static boolean walk(Store store, String dir, String needle, int max, List<FsEntry> found, long[] visited, long deadline)
            throws IOException {
        List<FsEntry> kids = store.list(dir, false);
        FileSort.sort(kids, FileSort.Order.NAME, true);
        for (FsEntry k : kids) {
            if (++visited[0] > 50000 || System.nanoTime() > deadline || found.size() >= max) {
                return false;
            }
            String full = FsPath.join(dir, k.name);
            if (k.name.toLowerCase(java.util.Locale.US).contains(needle)) {
                k.path = FsPath.clean(full);
                found.add(k);
            }
            if (k.dir && !walk(store, full, needle, max, found, visited, deadline)) {
                return false;
            }
        }
        return true;
    }
}
