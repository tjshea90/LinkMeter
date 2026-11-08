package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** How a folder listing is ordered and filtered. No Android in it (tested offline). Folders always come first. */
final class FileSort {
    private FileSort() {}

    enum Order { NAME, DATE, SIZE, KIND }

    /**
     * Case-insensitive, and a run of digits counts as a number, so "img2" comes before "img10" (a plain text sort puts it after).
     * Equal numbers with different leading zeros ("a01", "a1") fall back to plain text order so the result is a strict order.
     */
    static int naturalCompare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i);
            char y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int si = i;
                int sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                String na = stripZeros(a.substring(si, i));
                String nb = stripZeros(b.substring(sj, j));
                if (na.length() != nb.length()) {
                    return na.length() < nb.length() ? -1 : 1;
                }
                int c = na.compareTo(nb);
                if (c != 0) {
                    return c;
                }
            } else {
                int c = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y));
                if (c != 0) {
                    return c;
                }
                i++;
                j++;
            }
        }
        if (i < a.length()) {
            return 1;
        }
        if (j < b.length()) {
            return -1;
        }
        return a.compareTo(b);
    }

    private static String stripZeros(String digits) {
        int k = 0;
        while (k < digits.length() - 1 && digits.charAt(k) == '0') {
            k++;
        }
        return digits.substring(k);
    }

    /**
     * Sorts in place: folders first, then by the chosen order. ascending = false reverses the chosen key (newest first, largest
     * first, Z to A), but folders stay first, and folders are always put in name order when the key is size (they have none).
     */
    static void sort(List<FsEntry> list, final Order order, final boolean ascending) {
        Collections.sort(list, new Comparator<FsEntry>() {
            @Override
            public int compare(FsEntry a, FsEntry b) {
                if (a.dir != b.dir) {
                    return a.dir ? -1 : 1;
                }
                int c = 0;
                boolean usesKey = true;
                switch (order) {
                    case DATE:
                        c = Long.compare(a.time, b.time);
                        break;
                    case SIZE:
                        if (a.dir) {
                            usesKey = false;
                        } else {
                            c = Long.compare(a.size, b.size);
                        }
                        break;
                    case KIND:
                        c = a.kind().label.compareTo(b.kind().label);
                        if (c == 0) {
                            c = FsPath.ext(a.name).compareTo(FsPath.ext(b.name));
                        }
                        break;
                    default:
                        usesKey = false;
                        c = naturalCompare(a.name, b.name);
                        break;
                }
                if (usesKey && order != Order.NAME) {
                    if (c != 0) {
                        return ascending ? c : -c;
                    }
                    return naturalCompare(a.name, b.name);
                }
                if (order == Order.NAME) {
                    return ascending ? c : -c;
                }
                return naturalCompare(a.name, b.name); // folders under SIZE
            }
        });
    }

    /** The entries to show: hidden ones (names that start with ".") only if asked for. */
    static List<FsEntry> visible(List<FsEntry> list, boolean showHidden) {
        if (showHidden) {
            return new ArrayList<>(list);
        }
        List<FsEntry> out = new ArrayList<>();
        for (FsEntry e : list) {
            if (!e.hidden()) {
                out.add(e);
            }
        }
        return out;
    }
}
