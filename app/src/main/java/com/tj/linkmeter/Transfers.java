package com.tj.linkmeter;

import android.os.SystemClock;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Runs one copy, move or delete at a time on its own thread and reports to the screen (always on the UI thread). The work itself is
 * {@link TransferEngine}; this class only turns its progress into words, asks the screen what to do about a name clash, and
 * delivers the result. A clash question blocks the transfer's thread, not the screen.
 */
final class Transfers {
    /** What the screen has to do. All three are called on the UI thread. */
    interface Listener {
        void onProgress(String text, int permille);

        void onDone(Job job, TransferEngine.Result result);

        void ask(String destPath, FsEntry existing, FsEntry incoming, Answer answer);
    }

    interface Answer {
        void set(TransferEngine.Decision d);
    }

    /** One piece of work. Fill the fields, then {@link Transfers#start}. */
    static final class Job {
        /** "Copying", "Moving", "Deleting", "Downloading" */
        String verb = "Copying";
        Store src;
        Store dst;
        List<String> paths;
        String dstDir = "/";
        boolean move;
        boolean delete;
        TransferEngine.Policy policy = TransferEngine.Policy.ASK;
        /** Where the items were (for "return to source folder"). */
        String srcDir = "/";
        String srcSide = "";
        /** Run on the UI thread after a transfer that finished without failures or a cancel. */
        Runnable onSuccess;
    }

    private final MainActivity act;
    private final Listener ui;
    private volatile boolean cancel;
    private volatile boolean busy;

    Transfers(MainActivity act, Listener ui) {
        this.act = act;
        this.ui = ui;
    }

    boolean busy() {
        return busy;
    }

    void cancel() {
        cancel = true;
    }

    void start(final Job job) {
        if (busy) {
            return;
        }
        busy = true;
        cancel = false;
        Thread t = new Thread(() -> run(job), "linkmeter-transfer");
        t.setDaemon(true);
        t.start();
    }

    private void run(final Job job) {
        TransferEngine.Result result;
        try {
            final long started = SystemClock.elapsedRealtime();
            final TransferEngine.Listener listener = s -> {
                long ms = Math.max(1, SystemClock.elapsedRealtime() - started);
                final String text = describe(job, s, ms);
                final int permille = s.bytesTotal > 0 ? (int) Math.min(1000, s.bytesDone * 1000 / s.bytesTotal)
                        : (s.filesTotal > 0 ? Math.min(1000, s.filesDone * 1000 / s.filesTotal) : 0);
                act.ui.post(() -> ui.onProgress(text, permille));
            };
            TransferEngine.Cancel stop = () -> cancel;
            if (job.delete) {
                result = TransferEngine.deleteAll(job.dst, job.paths, listener, stop);
            } else {
                act.ui.post(() -> ui.onProgress("Getting ready…", 0));
                TransferEngine.Plan plan = TransferEngine.plan(job.src, job.paths);
                result = TransferEngine.run(plan, job.src, job.dst, job.dstDir, job.move, job.policy, (dest, existing, incoming) -> {
                    final CountDownLatch latch = new CountDownLatch(1);
                    final TransferEngine.Decision[] out = {TransferEngine.Decision.stop()};
                    act.ui.post(() -> ui.ask(dest, existing, incoming, d -> {
                        out[0] = d;
                        latch.countDown();
                    }));
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return TransferEngine.Decision.stop();
                    }
                    return out[0];
                }, listener, stop);
            }
        } catch (IOException e) {
            result = new TransferEngine.Result();
            result.failed = 1;
            result.errors.add(String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
            result = new TransferEngine.Result();
            result.failed = 1;
            result.errors.add(String.valueOf(e));
        }
        final TransferEngine.Result fin = result;
        act.ui.post(() -> {
            busy = false;
            ui.onDone(job, fin);
            if (job.onSuccess != null && fin.failed == 0 && !fin.cancelled) {
                job.onSuccess.run();
            }
        });
    }

    /** "Copying photo.jpg (3 of 12)  ·  4.2 MB/s  ·  1 min left" */
    static String describe(Job job, TransferEngine.Snapshot s, long elapsedMs) {
        StringBuilder sb = new StringBuilder(job.verb);
        if (!s.current.isEmpty()) {
            sb.append(' ').append(s.current);
        }
        if (s.filesTotal > 1) {
            sb.append(" (").append(Math.min(s.filesTotal, s.filesDone + 1)).append(" of ").append(s.filesTotal).append(')');
        }
        if (s.bytesTotal > 0) {
            sb.append("  ·  ").append(FsFormat.size(s.bytesDone)).append(" of ").append(FsFormat.size(s.bytesTotal));
            if (elapsedMs > 1500 && s.bytesDone > 0) {
                double rate = s.bytesDone * 1000.0 / elapsedMs;
                sb.append("  ·  ").append(FsFormat.rate(rate));
                if (s.bytesDone < s.bytesTotal) {
                    sb.append("  ·  ").append(FsFormat.eta((long) ((s.bytesTotal - s.bytesDone) / rate))).append(" left");
                }
            }
        }
        return sb.toString();
    }
}
