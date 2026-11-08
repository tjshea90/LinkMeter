package com.tj.linkmeter;

import java.util.Locale;

/** Numbers as people read them. No Android in it. */
final class FsFormat {
    private FsFormat() {}

    /** 1536 is "2 KB"; 5242880 is "5.0 MB". */
    static String size(long b) {
        if (b < 0) {
            return "?";
        }
        if (b < 1024) {
            return b + " B";
        }
        if (b < 1024L * 1024) {
            return String.format(Locale.US, "%.0f KB", b / 1024.0);
        }
        if (b < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024));
        }
        return String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024));
    }

    /** Bytes per second as "4.2 MB/s". */
    static String rate(double bytesPerSecond) {
        return size((long) bytesPerSecond) + "/s";
    }

    /** "1 item" or "3 items". */
    static String items(int n) {
        return n + (n == 1 ? " item" : " items");
    }

    /** "1 file" or "3 files". */
    static String files(int n) {
        return n + (n == 1 ? " file" : " files");
    }

    /** Seconds left as "12 s", "3 min", "1 h 5 min". */
    static String eta(long seconds) {
        if (seconds < 0) {
            return "";
        }
        if (seconds < 90) {
            return Math.max(1, seconds) + " s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + " min";
        }
        return (seconds / 3600) + " h " + ((seconds % 3600) / 60) + " min";
    }
}
