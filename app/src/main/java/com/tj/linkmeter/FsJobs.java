package com.tj.linkmeter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Copies that the device does by itself on its own storage (the phone copying a folder to another folder on the phone, so
 * nothing has to cross the hotspot). The request returns at once with a job id; the tablet asks for progress and can cancel.
 * All or nothing: a failed or cancelled copy removes what it had made. No Android in it (tested offline).
 */
final class FsJobs {
    /** One copy. Fields are read by the thread that answers progress requests. */
    static final class Job {
        final String id;
        volatile String state = "run"; // run, done, err, cancelled
        volatile long done;
        volatile long total;
        volatile String err = "";
        volatile boolean cancel;
        final long started = System.nanoTime();
        volatile long finished;

        Job(String id) {
            this.id = id;
        }
    }

    private static final long KEEP_NANOS = 10L * 60 * 1_000_000_000L;
    private static final int MAX_KEPT = 12;

    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private int counter;

    /** Starts copying from to to (which must not exist). after runs when the copy has ended, whatever the outcome. */
    synchronized Job start(final DiskStore store, final String from, final String to, final Runnable after) {
        prune();
        final Job j = new Job(Long.toHexString(System.nanoTime()) + (counter++));
        jobs.put(j.id, j);
        Thread t = new Thread(() -> run(j, store, from, to, after), "linkmeter-fs-job");
        t.setDaemon(true);
        t.start();
        return j;
    }

    private void run(Job j, DiskStore store, String from, String to, Runnable after) {
        try {
            j.total = store.sizeOf(from);
            store.copyTree(from, to, n -> {
                j.done += n;
                if (j.cancel) {
                    throw new Store.Cancelled();
                }
            });
            j.state = j.cancel ? "cancelled" : "done";
        } catch (Store.Cancelled c) {
            j.state = "cancelled";
            removeQuietly(store, to);
        } catch (IOException e) {
            j.state = "err";
            j.err = String.valueOf(e.getMessage());
            removeQuietly(store, to);
        } catch (RuntimeException e) {
            j.state = "err";
            j.err = String.valueOf(e);
            removeQuietly(store, to);
        } finally {
            j.finished = System.nanoTime();
            if (after != null) {
                try {
                    after.run();
                } catch (RuntimeException ignored) {
                    // the job is over either way
                }
            }
        }
    }

    private static void removeQuietly(DiskStore store, String path) {
        try {
            if (store.stat(path) != null) {
                store.delete(path);
            }
        } catch (IOException ignored) {
            // nothing more can be done
        }
    }

    synchronized Job get(String id) {
        return jobs.get(id);
    }

    synchronized boolean cancel(String id) {
        Job j = jobs.get(id);
        if (j == null) {
            return false;
        }
        j.cancel = true;
        return true;
    }

    /** Forgets old finished jobs, and the oldest finished ones when too many are kept. */
    private void prune() {
        long now = System.nanoTime();
        List<String> drop = new ArrayList<>();
        for (Job j : jobs.values()) {
            if (j.finished != 0 && now - j.finished > KEEP_NANOS) {
                drop.add(j.id);
            }
        }
        for (String id : drop) {
            jobs.remove(id);
        }
        while (jobs.size() >= MAX_KEPT) {
            String oldest = null;
            for (Job j : jobs.values()) {
                if (j.finished != 0) {
                    oldest = j.id;
                    break;
                }
            }
            if (oldest == null) {
                break;
            }
            jobs.remove(oldest);
        }
    }
}
