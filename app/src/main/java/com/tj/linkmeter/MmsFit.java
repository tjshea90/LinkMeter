package com.tj.linkmeter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes the pictures of an outgoing multimedia message small enough for the carrier. Carriers refuse a message over a size limit
 * (often 300 KB to 1.2 MB; the phone reports it), and a phone photo is several MB. Pictures are shrunk step by step (smaller and
 * a little more compressed each time) until the whole message fits; files that are not pictures are never changed, and if they
 * alone are over the limit the message cannot be sent as a multimedia message. No Android in it: the shrinking itself is handed
 * in as {@link Shrinker}, so the decisions are tested offline.
 */
final class MmsFit {
    private MmsFit() {}

    interface Shrinker {
        /** The picture as a JPEG whose longer side is at most maxSide pixels, at this quality (1 to 100); null if it cannot be read. */
        byte[] shrink(byte[] image, int maxSide, int quality);
    }

    /** What is tried, from the gentlest to the smallest. */
    static final int[][] STEPS = {{1600, 80}, {1280, 72}, {1024, 65}, {800, 58}, {640, 50}, {480, 42}, {320, 35}};

    /** Room for the headers, the text and the layout part that go with the pictures. */
    static final long OVERHEAD = 4 * 1024;

    static boolean shrinkable(MmsPdu.Part p) {
        return p.type.startsWith("image/") && !p.type.equals("image/gif");
    }

    private static long total(List<MmsPdu.Part> parts) {
        long t = 0;
        for (MmsPdu.Part p : parts) {
            t += p.data.length;
        }
        return t;
    }

    private static String jpgName(String name) {
        String base = FsPath.base(name);
        return (base.isEmpty() ? "image" : base) + ".jpg";
    }

    /**
     * The attachments, shrunk if needed so that together with textBytes and the overhead they are at most budget bytes. Throws with a
     * plain reason when that cannot be done.
     */
    static List<MmsPdu.Part> fit(List<MmsPdu.Part> parts, long textBytes, long budget, Shrinker s) throws IOException {
        long room = budget - OVERHEAD - textBytes;
        if (room <= 0) {
            throw new IOException("That message is too big for the carrier's limit of " + budget / 1024 + " KB.");
        }
        if (total(parts) <= room) {
            return new ArrayList<>(parts);
        }
        long fixed = 0;
        int images = 0;
        for (MmsPdu.Part p : parts) {
            if (shrinkable(p)) {
                images++;
            } else {
                fixed += p.data.length;
            }
        }
        if (images == 0 || fixed >= room) {
            throw new IOException("Those files are too big for a picture message (the carrier's limit is " + budget / 1024
                    + " KB). Send them another way, for example from the Files tab.");
        }
        for (int[] step : STEPS) {
            List<MmsPdu.Part> out = new ArrayList<>();
            for (MmsPdu.Part p : parts) {
                if (!shrinkable(p)) {
                    out.add(p);
                    continue;
                }
                byte[] small = s.shrink(p.data, step[0], step[1]);
                if (small == null || small.length == 0) {
                    throw new IOException("A picture could not be read, so it cannot be shrunk to fit.");
                }
                if (small.length >= p.data.length && p.type.equals("image/jpeg")) {
                    out.add(p); // already smaller than what shrinking gives
                } else {
                    out.add(new MmsPdu.Part("image/jpeg", jpgName(p.name), small, p.cid, false));
                }
            }
            if (total(out) <= room) {
                return out;
            }
        }
        throw new IOException("Those pictures are too big even when shrunk (the carrier's limit is " + budget / 1024 + " KB).");
    }
}
