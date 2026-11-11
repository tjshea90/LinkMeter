package com.tj.linkmeter;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * No Android in here (tested in tools/test/TextsLogicTest.java). The last requests the tablet made to the phone for Texts, with how
 * long each took and how it ended, so that "it is slow" and "read timed out" can be told apart from a phone that cannot be reached
 * (Texts menu > Connection details). It keeps ONLY the route (never the part after a ?, which holds numbers and search words) and
 * never a text.
 */
final class LinkLog {
    static final class Entry {
        final long at;
        final String what;
        final long ms;
        final String result;

        Entry(long at, String what, long ms, String result) {
            this.at = at;
            this.what = what;
            this.ms = ms;
            this.result = result;
        }
    }

    private final Entry[] ring;
    private int next;
    private int count;

    LinkLog(int capacity) {
        ring = new Entry[Math.max(1, capacity)];
    }

    synchronized void add(long at, String what, long ms, String result) {
        ring[next] = new Entry(at, what, ms, result);
        next = (next + 1) % ring.length;
        count = Math.min(count + 1, ring.length);
    }

    synchronized void clear() {
        for (int i = 0; i < ring.length; i++) {
            ring[i] = null;
        }
        next = 0;
        count = 0;
    }

    /** The entries, newest first. */
    synchronized List<Entry> newestFirst() {
        List<Entry> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            out.add(ring[(next - i + ring.length * 2) % ring.length]);
        }
        return out;
    }

    /** "/sms/thread?key=5551234567&limit=40" is "/sms/thread": the question part is never kept. */
    static String route(String target) {
        if (target == null) {
            return "";
        }
        int q = target.indexOf('?');
        return q < 0 ? target : target.substring(0, q);
    }

    /** How a request ended, in a word. */
    static String outcome(Exception e) {
        if (e instanceof Client.ConnectFailed) {
            return e.getCause() instanceof SocketTimeoutException ? "no connection in time" : "no connection";
        }
        if (e instanceof SocketTimeoutException) {
            return "timed out waiting for the phone";
        }
        if (e instanceof RemoteSms.Off) {
            return "texts are off";
        }
        return "failed";
    }

    static String seconds(long ms) {
        if (ms < 1000) {
            return ms + " ms";
        }
        return String.format(Locale.US, "%.1f s", ms / 1000.0);
    }

    static String line(Entry e, long now) {
        long ago = Math.max(0, now - e.at) / 1000;
        String when = ago < 90 ? ago + " s ago" : ago / 60 + " min ago";
        return e.what + " · " + seconds(e.ms) + " · " + e.result + " · " + when;
    }

    /** The lines of the Connection details dialog; the slowest request that worked is named first. */
    synchronized String summary(long now) {
        List<Entry> all = newestFirst();
        if (all.isEmpty()) {
            return "Nothing was asked of the phone yet.";
        }
        Entry slow = null;
        int bad = 0;
        for (Entry e : all) {
            if (e.result.equals("ok")) {
                if (slow == null || e.ms > slow.ms) {
                    slow = e;
                }
            } else {
                bad++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(all.size()).append(" requests, ").append(bad).append(bad == 1 ? " did not work" : " did not work").append('\n');
        if (slow != null) {
            sb.append("Slowest that worked: ").append(line(slow, now)).append('\n');
        }
        sb.append('\n');
        for (Entry e : all) {
            sb.append(line(e, now)).append('\n');
        }
        return sb.toString().trim();
    }
}
