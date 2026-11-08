package com.tj.linkmeter;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Copies or moves files and folders from one {@link Store} to another (or within one): works out what is inside the folders,
 * settles name clashes (replace, keep both, skip, or ask), moves only delete the original after the copy worked, a cancel at any
 * moment leaves no half-written file, and one failing file does not stop the others. No Android in it (tested offline).
 */
final class TransferEngine {
    private TransferEngine() {}

    /** What to do when something with the same name is already there. ASK calls the {@link Resolver}. */
    enum Policy { ASK, REPLACE, SKIP, BOTH }

    /** The answer to a clash. */
    static final class Decision {
        final Policy policy;
        /** Use this answer for every clash that comes later in this transfer. */
        final boolean forAll;
        /** Stop the whole transfer. */
        final boolean cancel;

        private Decision(Policy policy, boolean forAll, boolean cancel) {
            this.policy = policy;
            this.forAll = forAll;
            this.cancel = cancel;
        }

        static Decision of(Policy policy, boolean forAll) {
            return new Decision(policy, forAll, false);
        }

        static Decision stop() {
            return new Decision(Policy.SKIP, false, true);
        }
    }

    /** Asked (on the transfer's own thread, it may block on a dialog) when a name is taken and the policy is ASK. */
    interface Resolver {
        Decision resolve(String destPath, FsEntry existing, FsEntry incoming);
    }

    interface Cancel {
        boolean cancelled();
    }

    /** How far along it is. */
    static final class Snapshot {
        long bytesDone;
        long bytesTotal;
        int filesDone;
        int filesTotal;
        /** The file being copied now. */
        String current = "";
    }

    interface Listener {
        void progress(Snapshot s);
    }

    /** One thing to copy: a file or a folder (a folder is created, its contents are separate items after it). */
    static final class Item {
        final String src;
        /** Its path below the destination folder, e.g. "photos/a.jpg". */
        final String rel;
        final boolean dir;
        final long size;
        final long mtime;
        /** Which of the selected paths it belongs to. */
        final int root;

        Item(String src, String rel, boolean dir, long size, long mtime, int root) {
            this.src = src;
            this.rel = rel;
            this.dir = dir;
            this.size = size;
            this.mtime = mtime;
            this.root = root;
        }
    }

    static final class Plan {
        final List<Item> items = new ArrayList<>();
        /** The selected paths that exist, in order (Item.root indexes this). */
        final List<String> roots = new ArrayList<>();
        /** Selected paths that were not there any more. */
        final List<String> missing = new ArrayList<>();
        long bytes;
        int files;
    }

    static final class Result {
        int copied;
        int skipped;
        int failed;
        long bytes;
        boolean cancelled;
        final List<String> errors = new ArrayList<>();
        /** The top-level paths that now exist at the destination because of this transfer. */
        final List<String> created = new ArrayList<>();

        /** One line for a message: "Copied 3 files (4.2 MB). 1 skipped. 1 failed." */
        String summary(boolean move) {
            StringBuilder sb = new StringBuilder(move ? "Moved " : "Copied ").append(FsFormat.files(copied));
            if (bytes > 0) {
                sb.append(" (").append(FsFormat.size(bytes)).append(')');
            }
            sb.append('.');
            if (skipped > 0) {
                sb.append(' ').append(skipped).append(" skipped.");
            }
            if (failed > 0) {
                sb.append(' ').append(failed).append(" failed.");
            }
            if (cancelled) {
                sb.append(" Stopped.");
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------ planning

    /** Lists everything that a copy of these paths would involve. Throws for the storage root. */
    static Plan plan(Store src, List<String> paths) throws IOException {
        Plan p = new Plan();
        for (String raw : paths) {
            String r = FsPath.clean(raw);
            if (r.equals("/")) {
                throw new IOException("can't copy the whole storage");
            }
            FsEntry e = src.stat(r);
            if (e == null) {
                p.missing.add(r);
                continue;
            }
            p.roots.add(r);
            int ri = p.roots.size() - 1;
            String name = FsPath.name(r);
            p.items.add(new Item(r, name, e.dir, e.size, e.time, ri));
            if (e.dir) {
                for (Store.Found f : src.walk(r)) {
                    p.items.add(new Item(FsPath.join(r, f.rel), name + "/" + f.rel, f.dir, f.size, f.time, ri));
                }
            }
        }
        for (Item it : p.items) {
            if (!it.dir) {
                p.files++;
                p.bytes += it.size;
            }
        }
        return p;
    }

    // ------------------------------------------------------------ running

    /**
     * Does the transfer. dstDir is the destination folder (created if missing). move = delete the originals after they were copied.
     * policy: what to do with clashes (ASK uses resolver; with no resolver ASK means skip). Never throws for a file that fails:
     * that is counted in the result.
     */
    static Result run(Plan plan, Store src, Store dst, String dstDir, boolean move, Policy policy, Resolver resolver,
            Listener listener, Cancel cancel) {
        return new Run(plan, src, dst, FsPath.clean(dstDir), move, policy, resolver, listener, cancel).go();
    }

    /** Deletes paths one by one (a folder with everything in it). Counts deleted in Result.copied. */
    static Result deleteAll(Store s, List<String> paths, Listener listener, Cancel cancel) {
        Result res = new Result();
        Snapshot snap = new Snapshot();
        snap.filesTotal = paths.size();
        for (String p : paths) {
            if (cancel != null && cancel.cancelled()) {
                res.cancelled = true;
                break;
            }
            snap.current = FsPath.name(p);
            if (listener != null) {
                listener.progress(snap);
            }
            try {
                s.delete(p);
                res.copied++;
            } catch (IOException e) {
                res.failed++;
                res.errors.add(FsPath.name(p) + ": " + e.getMessage());
            }
            snap.filesDone++;
        }
        if (listener != null) {
            listener.progress(snap);
        }
        return res;
    }

    private static final class Run {
        final Plan plan;
        final Store src;
        final Store dst;
        final String dstDir;
        final boolean move;
        final Policy base;
        final Resolver resolver;
        final Listener listener;
        final Cancel cancel;
        final boolean sameStore;
        final Result res = new Result();
        final Snapshot snap = new Snapshot();
        Policy sticky;
        boolean fatal;
        int consecutiveFailures;
        long lastNotify;
        /** Per selected path: something in it was skipped or failed, so the original is not removed as a whole. */
        final boolean[] incomplete;
        /** Directory renames made inside a root (a file was in the way): [original rel prefix, new rel prefix]. */
        final List<String[]> remap = new ArrayList<>();
        final List<String> skippedPrefixes = new ArrayList<>();
        long itemMoved;
        /** The folder that was selected exists at the destination (made, or merged into). */
        boolean topFolderOk;

        Run(Plan plan, Store src, Store dst, String dstDir, boolean move, Policy base, Resolver resolver, Listener listener,
                Cancel cancel) {
            this.plan = plan;
            this.src = src;
            this.dst = dst;
            this.dstDir = dstDir;
            this.move = move;
            this.base = base;
            this.resolver = resolver;
            this.listener = listener;
            this.cancel = cancel;
            this.sameStore = src.id().equals(dst.id());
            this.incomplete = new boolean[plan.roots.size()];
            snap.bytesTotal = plan.bytes;
            snap.filesTotal = plan.files;
        }

        Result go() {
            for (String m : plan.missing) {
                res.failed++;
                res.errors.add(FsPath.name(m) + ": not found any more");
            }
            try {
                dst.mkdirs(dstDir);
                for (int ri = 0; ri < plan.roots.size() && !fatal; ri++) {
                    doRoot(ri);
                }
            } catch (Store.Cancelled c) {
                res.cancelled = true;
            } catch (IOException e) {
                res.failed++;
                res.errors.add(e.getMessage());
            }
            notifyNow();
            return res;
        }

        void check() throws Store.Cancelled {
            if (cancel != null && cancel.cancelled()) {
                throw new Store.Cancelled();
            }
        }

        void notifyNow() {
            if (listener != null) {
                listener.progress(snap);
            }
        }

        void notifyMaybe() {
            long now = System.nanoTime();
            if (now - lastNotify > 150_000_000L) {
                lastNotify = now;
                notifyNow();
            }
        }

        /** The items of one selected path, in plan order (the first one is the path itself). */
        List<Item> itemsOf(int ri) {
            List<Item> out = new ArrayList<>();
            for (Item it : plan.items) {
                if (it.root == ri) {
                    out.add(it);
                }
            }
            return out;
        }

        Policy choose(String destPath, FsEntry existing, FsEntry incoming, Policy forced) throws Store.Cancelled {
            if (forced != null) {
                return forced;
            }
            if (sticky != null) {
                return sticky;
            }
            if (base != Policy.ASK) {
                return base;
            }
            if (resolver == null) {
                return Policy.SKIP;
            }
            Decision d = resolver.resolve(destPath, existing, incoming);
            if (d == null || d.cancel) {
                throw new Store.Cancelled();
            }
            if (d.forAll) {
                sticky = d.policy;
            }
            return d.policy;
        }

        /** A name not taken in dir (on the destination), also counting names this transfer already made. */
        String uniqueIn(String dir, String name, boolean isDir) throws IOException {
            Set<String> taken = new HashSet<>();
            FsEntry d = dst.stat(dir);
            if (d != null && d.dir) {
                for (FsEntry e : dst.list(dir, false)) {
                    taken.add(e.name);
                }
            }
            return FsPath.unique(name, isDir, taken::contains);
        }

        void account(Item it, boolean copied) {
            if (!it.dir) {
                snap.filesDone++;
                if (copied) {
                    res.copied++;
                    res.bytes += it.size;
                }
            }
        }

        /** Counts a file as handled without moving it (skipped or failed) so the progress bar still reaches the end. */
        void passOver(Item it) {
            if (!it.dir) {
                snap.bytesDone += Math.max(0, it.size - itemMoved);
                snap.filesDone++;
            }
            itemMoved = 0;
            notifyMaybe();
        }

        void skipRoot(List<Item> items, int ri, boolean error) {
            for (Item it : items) {
                if (!it.dir) {
                    if (!error) {
                        res.skipped++;
                    }
                    snap.bytesDone += it.size;
                    snap.filesDone++;
                }
            }
            incomplete[ri] = true;
            notifyMaybe();
        }

        void fail(String what, String why) {
            res.failed++;
            res.errors.add(what + ": " + why);
        }

        void doRoot(int ri) throws IOException {
            check();
            List<Item> items = itemsOf(ri);
            Item top = items.get(0);
            String srcRoot = plan.roots.get(ri);
            String name = top.rel;
            remap.clear();
            skippedPrefixes.clear();
            String destName = name;
            boolean topDecided = false; // a file at the destination is to be replaced: already agreed
            FsEntry incoming = new FsEntry(name, top.dir, top.size, top.mtime);

            if (sameStore && top.dir && FsPath.isSameOrUnder(dstDir, srcRoot)) {
                fail(name, "a folder can't be put inside itself");
                skipRoot(items, ri, true);
                return;
            }
            String destTop = FsPath.join(dstDir, name);
            boolean selfCopy = sameStore && FsPath.clean(destTop).equals(srcRoot);
            if (selfCopy && move) {
                res.skipped++; // already there
                skipRoot(items, ri, true);
                return;
            }
            FsEntry existing;
            try {
                existing = dst.stat(destTop);
            } catch (IOException e) {
                fail(name, e.getMessage());
                skipRoot(items, ri, true);
                return;
            }
            if (existing != null && (selfCopy || !(top.dir && existing.dir))) {
                Policy pol = choose(destTop, existing, incoming, selfCopy ? Policy.BOTH : null);
                if (pol == Policy.SKIP) {
                    skipRoot(items, ri, false);
                    return;
                }
                if (pol == Policy.BOTH) {
                    destName = uniqueIn(dstDir, name, top.dir);
                } else { // REPLACE
                    if (existing.dir) {
                        fail(name, "a folder with this name is in the way");
                        skipRoot(items, ri, true);
                        return;
                    }
                    if (top.dir) {
                        dst.delete(destTop); // a file stands where the folder goes
                    } else {
                        topDecided = true; // the write replaces the file
                    }
                }
            }
            String destFinal = FsPath.join(dstDir, destName);

            // one step on the same device, when nothing has to be merged
            if (sameStore && !topDecided && dst.stat(destFinal) == null) {
                try {
                    if (move && src.rename(srcRoot, destFinal)) {
                        for (Item it : items) {
                            snap.bytesDone += it.dir ? 0 : it.size;
                            account(it, true);
                        }
                        res.created.add(destFinal);
                        notifyMaybe();
                        return;
                    }
                    final long[] reported = {0};
                    long rootBytes = 0;
                    for (Item it : items) {
                        rootBytes += it.dir ? 0 : it.size;
                    }
                    if (!move && src.copyWithin(srcRoot, destFinal, n -> {
                        snap.bytesDone += n;
                        reported[0] += n;
                        check();
                        notifyMaybe();
                    })) {
                        snap.bytesDone += Math.max(0, rootBytes - reported[0]);
                        for (Item it : items) {
                            account(it, true);
                        }
                        res.created.add(destFinal);
                        notifyNow();
                        return;
                    }
                } catch (Store.Cancelled c) {
                    throw c;
                } catch (IOException e) {
                    // fall through: copy it file by file instead
                }
            }

            int copiedBefore = res.copied;
            topFolderOk = false;
            for (Item it : items) {
                if (fatal) {
                    break;
                }
                check();
                String rel = destName + it.rel.substring(name.length());
                rel = remapped(rel);
                if (underSkipped(it.rel)) {
                    if (!it.dir) {
                        res.skipped++;
                    }
                    passOver(it);
                    incomplete[ri] = true;
                    continue;
                }
                String d = FsPath.join(dstDir, rel);
                if (it.dir) {
                    makeFolder(it, d, ri);
                    if (it == top && !incomplete[ri]) {
                        topFolderOk = true;
                    }
                } else {
                    copyFile(it, d, ri, it == top && topDecided);
                }
            }
            if (res.copied > copiedBefore || topFolderOk) {
                res.created.add(destFinal);
            }
            if (move) {
                cleanUpSource(items, ri, srcRoot);
            }
        }

        String remapped(String rel) {
            for (int pass = 0; pass < 20; pass++) {
                String best = null;
                String bestTo = null;
                for (String[] m : remap) {
                    if ((rel.equals(m[0]) || rel.startsWith(m[0] + "/")) && (best == null || m[0].length() > best.length())) {
                        best = m[0];
                        bestTo = m[1];
                    }
                }
                if (best == null) {
                    return rel;
                }
                rel = bestTo + rel.substring(best.length());
            }
            return rel;
        }

        boolean underSkipped(String srcRel) {
            for (String p : skippedPrefixes) {
                if (srcRel.equals(p) || srcRel.startsWith(p + "/")) {
                    return true;
                }
            }
            return false;
        }

        void makeFolder(Item it, String d, int ri) throws IOException {
            try {
                FsEntry ex = dst.stat(d);
                if (ex == null) {
                    dst.mkdirs(d);
                } else if (!ex.dir) {
                    // a file is in the way of this folder
                    Policy pol = choose(d, ex, new FsEntry(it.rel, true, 0, it.mtime), null);
                    if (pol == Policy.SKIP) {
                        skippedPrefixes.add(it.rel);
                        incomplete[ri] = true;
                        return;
                    }
                    if (pol == Policy.BOTH) {
                        String parent = FsPath.parent(d);
                        String nn = uniqueIn(parent, FsPath.name(d), true);
                        String relParent = FsPath.relativeTo(parent, dstDir);
                        String newRel = (relParent.isEmpty() ? "" : relParent + "/") + nn;
                        String oldRel = FsPath.relativeTo(d, dstDir);
                        remap.add(new String[] {oldRel, newRel});
                        dst.mkdirs(FsPath.join(parent, nn));
                    } else {
                        dst.delete(d);
                        dst.mkdirs(d);
                    }
                }
            } catch (Store.Cancelled c) {
                throw c;
            } catch (IOException e) {
                fail(it.rel, e.getMessage());
                skippedPrefixes.add(it.rel);
                incomplete[ri] = true;
            }
        }

        void copyFile(Item it, String d, int ri, boolean decidedReplace) throws IOException {
            itemMoved = 0;
            snap.current = it.rel;
            String target = d;
            try {
                if (!decidedReplace) {
                    FsEntry ex = dst.stat(d);
                    if (ex != null) {
                        Policy pol = choose(d, ex, new FsEntry(FsPath.name(d), false, it.size, it.mtime), null);
                        if (pol == Policy.SKIP) {
                            res.skipped++;
                            passOver(it);
                            incomplete[ri] = true;
                            return;
                        }
                        if (pol == Policy.BOTH) {
                            String parent = FsPath.parent(d);
                            target = FsPath.join(parent, uniqueIn(parent, FsPath.name(d), false));
                        } else if (ex.dir) {
                            fail(it.rel, "a folder with this name is in the way");
                            passOver(it);
                            incomplete[ri] = true;
                            return;
                        }
                    }
                }
                try (InputStream in = src.read(it.src, 0)) {
                    dst.write(target, in, it.size, it.mtime, n -> {
                        snap.bytesDone += n;
                        itemMoved += n;
                        check();
                        notifyMaybe();
                    });
                }
            } catch (Store.Cancelled c) {
                throw c;
            } catch (IOException e) {
                fail(it.rel, String.valueOf(e.getMessage()));
                passOver(it);
                incomplete[ri] = true;
                consecutiveFailures++;
                String m = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.US);
                if (m.contains("space") || m.contains("enospc")) {
                    fatal = true;
                    res.errors.add("stopped: the destination is full");
                } else if (consecutiveFailures >= 3) {
                    fatal = true;
                    res.errors.add("stopped after repeated errors");
                }
                return;
            }
            consecutiveFailures = 0;
            snap.filesDone++;
            res.copied++;
            res.bytes += it.size;
            itemMoved = 0;
            if (move) {
                try {
                    src.delete(it.src);
                } catch (IOException e) {
                    res.errors.add(it.rel + ": copied, but the original could not be removed (" + e.getMessage() + ")");
                    incomplete[ri] = true;
                }
            }
            notifyMaybe();
        }

        /** After a move: the original folder goes if everything in it was copied; otherwise only empty folders go. */
        void cleanUpSource(List<Item> items, int ri, String srcRoot) {
            try {
                if (!items.get(0).dir) {
                    return; // a file was deleted right after it was copied
                }
                if (!incomplete[ri]) {
                    src.delete(srcRoot);
                    return;
                }
                for (int i = items.size() - 1; i >= 0; i--) {
                    Item it = items.get(i);
                    if (it.dir && src.list(it.src, false).isEmpty()) {
                        src.delete(it.src);
                    }
                }
            } catch (IOException e) {
                res.errors.add(FsPath.name(srcRoot) + ": the original folder could not be removed (" + e.getMessage() + ")");
            }
        }
    }
}
