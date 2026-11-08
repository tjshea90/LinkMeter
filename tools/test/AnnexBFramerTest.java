package com.tj.linkmeter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Offline test driver for AnnexBFramer (run by tools/test/test_framer.py).
 *   real <stream.h264> <sizes.txt> <seed>   a real x264 stream; sizes.txt holds ffprobe's size of every picture
 *   synthetic <seed> <rounds>               streams built by hand, so the right answer is known exactly
 * Every stream is fed in many ways (as pictures, in groups, in odd pieces, byte by byte, without the "quiet"
 * hints, with hints in the middle of start codes, with false hints in the middle of pictures, joined in the
 * middle, with junk in front). Prints "N checks, M failed".
 */
public final class AnnexBFramerTest {
    static int checks;
    static final List<String> fails = new ArrayList<>();

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            fails.add(msg);
            if (fails.size() <= 25) {
                System.out.println("  FAIL: " + msg);
            }
        }
    }

    /** What the framer reported, in order. */
    static final class Ev {
        boolean damage;
        long at;
        int n;
        boolean key;
        boolean sps;
        String why;
        byte[] data;
    }

    static final class Rec implements AnnexBFramer.Sink {
        final List<Ev> ev = new ArrayList<>();

        @Override
        public void unit(byte[] d, int n, boolean key, boolean sps, long at) {
            Ev e = new Ev();
            e.at = at;
            e.n = n;
            e.key = key;
            e.sps = sps;
            e.data = Arrays.copyOf(d, n);
            ev.add(e);
        }

        @Override
        public void damaged(String why, long at) {
            Ev e = new Ev();
            e.damage = true;
            e.at = at;
            e.why = why;
            ev.add(e);
        }

        int damages() {
            int c = 0;
            for (Ev e : ev) {
                if (e.damage) {
                    c++;
                }
            }
            return c;
        }
    }

    /** The right answer: where every picture starts and how long it is. */
    static final class Truth {
        final byte[] s;
        final int n;
        final long[] off;
        final int[] sz;
        final boolean[] sps;
        final boolean[] idr;

        Truth(byte[] s, int[] sizes) {
            this.s = s;
            n = sizes.length;
            off = new long[n];
            sz = sizes;
            sps = new boolean[n];
            idr = new boolean[n];
            long p = 0;
            for (int i = 0; i < n; i++) {
                off[i] = p;
                p += sizes[i];
                for (int j = (int) off[i]; j + 3 < off[i] + sizes[i]; j++) {
                    if (s[j] == 0 && s[j + 1] == 0 && s[j + 2] == 1) {
                        int t = s[j + 3] & 0x1F;
                        if (t == 7) {
                            sps[i] = true;
                        }
                        if (t == 5) {
                            idr[i] = true;
                        }
                        j += 2;
                    }
                }
            }
            check(p == s.length, "the pictures must tile the stream: " + p + " vs " + s.length);
        }

        long end(int i) {
            return off[i] + sz[i];
        }
    }

    // ------------------------------------------------------------------------------------ comparing

    /** Pictures from..n-1 exactly, in order, nothing else. shift: bytes in front of the stream that the framer saw. */
    static String exact(Rec r, Truth t, int from, long shift) {
        int want = t.n - from;
        if (r.damages() != 0) {
            return "unexpected damage report: " + r.ev.get(firstDamage(r)).why;
        }
        if (r.ev.size() != want) {
            return "got " + r.ev.size() + " pictures, wanted " + want + describeFirstDiff(r, t, from, shift);
        }
        for (int k = 0; k < want; k++) {
            Ev e = r.ev.get(k);
            int i = from + k;
            if (e.at != t.off[i] + shift || e.n != t.sz[i]) {
                return "picture " + i + " is at " + e.at + "+" + e.n + ", wanted " + (t.off[i] + shift) + "+" + t.sz[i];
            }
            for (int b = 0; b < e.n; b++) {
                if (e.data[b] != t.s[(int) t.off[i] + b]) {
                    return "picture " + i + " differs at byte " + b;
                }
            }
            if (e.key && !t.idr[i]) {
                return "picture " + i + " was flagged key but has no IDR slice";
            }
            if (!e.key && t.idr[i]) {
                return "picture " + i + " has an IDR slice but was not flagged key";
            }
            if (e.sps != t.sps[i]) {
                return "picture " + i + " sps flag " + e.sps + ", wanted " + t.sps[i];
            }
        }
        return null;
    }

    static int firstDamage(Rec r) {
        for (int i = 0; i < r.ev.size(); i++) {
            if (r.ev.get(i).damage) {
                return i;
            }
        }
        return -1;
    }

    static String describeFirstDiff(Rec r, Truth t, int from, long shift) {
        for (int k = 0; k < Math.min(r.ev.size(), t.n - from); k++) {
            Ev e = r.ev.get(k);
            int i = from + k;
            if (e.at != t.off[i] + shift || e.n != t.sz[i]) {
                return " (first difference: picture " + i + " at " + e.at + "+" + e.n + ", wanted "
                        + (t.off[i] + shift) + "+" + t.sz[i] + ")";
            }
        }
        return "";
    }

    // ------------------------------------------------------------------------------------ feeding

    static void feed(AnnexBFramer f, byte[] s, long from, long to, Random r, int maxChunk) throws IOException {
        long p = from;
        while (p < to) {
            int n = (int) Math.min(to - p, 1 + r.nextInt(maxChunk));
            f.push(s, (int) p, n);
            p += n;
        }
    }

    // ------------------------------------------------------------------------------------ scenarios

    static void scenarios(String label, Truth t, long seed) throws IOException {
        Random r = new Random(seed);
        byte[] s = t.s;

        // 1. every picture is a packet of its own (what the phone sends when it guesses right)
        {
            Rec rec = new Rec();
            AnnexBFramer f = new AnnexBFramer(rec);
            for (int i = 0; i < t.n; i++) {
                f.push(s, (int) t.off[i], t.sz[i]);
                f.endOfPacket();
            }
            String m = exact(rec, t, 0, 0);
            check(m == null, label + " [one picture per packet] " + m);
        }
        // 2. several pictures in a packet
        {
            Rec rec = new Rec();
            AnnexBFramer f = new AnnexBFramer(rec);
            int i = 0;
            while (i < t.n) {
                int g = Math.min(t.n - i, 1 + r.nextInt(6));
                f.push(s, (int) t.off[i], (int) (t.end(i + g - 1) - t.off[i]));
                f.endOfPacket();
                i += g;
            }
            String m = exact(rec, t, 0, 0);
            check(m == null, label + " [pictures merged into packets] " + m);
        }
        // 3. odd pieces inside the packets, hint only where a picture really ends
        for (int maxChunk : new int[] {1, 7, 100, 3000}) {
            if (maxChunk == 1 && s.length > 600000) {
                continue;
            }
            Rec rec = new Rec();
            AnnexBFramer f = new AnnexBFramer(rec);
            int i = 0;
            while (i < t.n) {
                int g = Math.min(t.n - i, 1 + r.nextInt(4));
                feed(f, s, t.off[i], t.end(i + g - 1), r, maxChunk);
                f.endOfPacket();
                i += g;
            }
            String m = exact(rec, t, 0, 0);
            check(m == null, label + " [pieces of up to " + maxChunk + " bytes] " + m);
        }
        // 4. no hints at all: every picture but the last comes out by itself, the last on the final hint
        {
            Rec rec = new Rec();
            AnnexBFramer f = new AnnexBFramer(rec);
            feed(f, s, 0, s.length, r, 2000);
            check(rec.ev.size() == t.n - 1 && rec.damages() == 0,
                    label + " [no hints] before the last hint: " + rec.ev.size() + " pictures, wanted " + (t.n - 1));
            f.endOfPacket();
            String m = exact(rec, t, 0, 0);
            check(m == null, label + " [no hints, then one] " + m);
        }
        // 5. hints inside the next start code or header: they must change nothing
        {
            Rec rec = new Rec();
            AnnexBFramer f = new AnnexBFramer(rec);
            long pos = 0;
            for (int i = 1; i <= t.n; i++) {
                long cut;
                if (i == t.n) {
                    cut = s.length;
                } else {
                    int p = (int) t.off[i];
                    int z = 0;
                    while (s[p + z] == 0) {
                        z++;
                    }
                    int scLen = z + 1; // zeros and the 01
                    cut = t.off[i] + r.nextInt(scLen + 3); // up to the header byte and one byte after it
                }
                if (cut > pos) {
                    feed(f, s, pos, cut, r, 500);
                    pos = cut;
                    f.endOfPacket();
                }
            }
            if (pos < s.length) {
                feed(f, s, pos, s.length, r, 500);
                f.endOfPacket();
            }
            String m = exact(rec, t, 0, 0);
            check(m == null, label + " [hints inside start codes] " + m);
        }
        // 6. junk in front of the stream
        {
            Rec rec = new Rec();
            AnnexBFramer f = new AnnexBFramer(rec);
            int junk = 1 + r.nextInt(200);
            byte[] j = new byte[junk];
            for (int i = 0; i < junk; i++) {
                j[i] = (byte) (1 + r.nextInt(255));
            }
            f.push(j, 0, junk);
            f.endOfPacket();
            for (int i = 0; i < t.n; i++) {
                f.push(s, (int) t.off[i], t.sz[i]);
                f.endOfPacket();
            }
            String m = exact(rec, t, 0, junk);
            check(m == null, label + " [junk in front] " + m);
        }
        // 7. joining in the middle of a picture
        if (t.n >= 4) {
            for (int tries = 0; tries < 3; tries++) {
                int m = 1 + r.nextInt(t.n - 2);
                long cut = t.off[m] + 1 + r.nextInt(Math.max(1, t.sz[m] - 1));
                Rec rec = new Rec();
                AnnexBFramer f = new AnnexBFramer(rec);
                f.push(s, (int) cut, (int) (t.end(m) - cut));
                f.endOfPacket();
                for (int i = m + 1; i < t.n; i++) {
                    f.push(s, (int) t.off[i], t.sz[i]);
                    f.endOfPacket();
                }
                // the framer counts its own stream from where it joined. If what is left of picture m still holds
                // a whole start of a picture (a first slice), a unit made of just that rest is fine too.
                String msg = exact(rec, t, m + 1, -cut);
                if (msg != null && !rec.ev.isEmpty() && rec.damages() == 0) {
                    Ev e0 = rec.ev.get(0);
                    if (e0.at + cut >= t.off[m] && e0.at + e0.n + cut == t.end(m)) {
                        Rec rest = new Rec();
                        rest.ev.addAll(rec.ev.subList(1, rec.ev.size()));
                        msg = exact(rest, t, m + 1, -cut);
                    }
                }
                check(msg == null, label + " [joined mid-picture " + m + "] " + msg);
            }
        }
        // 8. false hints in the middle of pictures: every wrong picture must be followed by a damage report,
        //    nothing may be reported without one, and the stream must be right again afterwards
        for (int round = 0; round < 3; round++) {
            Rec rec = new Rec();
            AnnexBFramer f = new AnnexBFramer(rec);
            int falseHints = 0;
            for (int i = 0; i < t.n; i++) {
                long p = t.off[i];
                long end = t.end(i);
                while (p < end) {
                    long n = Math.min(end - p, 1 + r.nextInt(1500));
                    f.push(s, (int) p, (int) n);
                    p += n;
                    if (p < end && r.nextInt(25) == 0) {
                        f.endOfPacket();
                        falseHints++;
                    }
                }
                f.endOfPacket();
            }
            String m = falseHintsOk(rec, t);
            check(m == null, label + " [false hints, " + falseHints + " of them] " + m);
        }
    }

    /** Describes the true picture a unit lies in: its NAL units with their sizes, and where the unit ends. */
    static String whereIs(Ev e, Truth t) {
        for (int i = 0; i < t.n; i++) {
            if (e.at >= t.off[i] && e.at < t.end(i)) {
                StringBuilder sb = new StringBuilder("true picture " + i + " is " + t.off[i] + "+" + t.sz[i] + ", NALs:");
                int lastType = -1;
                int lastAt = -1;
                for (int j = (int) t.off[i]; j + 3 < t.end(i); j++) {
                    if (t.s[j] == 0 && t.s[j + 1] == 0 && t.s[j + 2] == 1) {
                        if (lastType >= 0) {
                            sb.append(" ").append(lastType).append("@").append(lastAt - t.off[i]).append("(").append(j - lastAt).append(")");
                        }
                        lastType = t.s[j + 3] & 0x1F;
                        lastAt = j;
                        j += 2;
                    }
                }
                sb.append(" ").append(lastType).append("@").append(lastAt - t.off[i]).append("(").append(t.end(i) - lastAt).append(")");
                sb.append("; the unit ends at +").append(e.at + e.n - t.off[i]);
                return sb.toString();
            }
        }
        return "(not inside a true picture)";
    }

    /** The picture slices (NAL types 1 to 5) inside d[from, to), each as its own byte string. */
    static List<byte[]> sliceList(byte[] d, int from, int to) {
        List<byte[]> out = new ArrayList<>();
        int j = from;
        while (j + 3 < to) {
            if (d[j] == 0 && d[j + 1] == 0 && d[j + 2] == 1) {
                int h = j + 3;
                int next = h;
                while (next + 2 < to && !(d[next] == 0 && d[next + 1] == 0 && d[next + 2] == 1)) {
                    next++;
                }
                int end = next + 2 < to ? next : to;
                while (end > h && d[end - 1] == 0) {
                    end--;
                }
                int type = d[h] & 0x1F;
                if (type >= 1 && type <= 5) {
                    out.add(Arrays.copyOfRange(d, h, end));
                }
                j = h;
            } else {
                j++;
            }
        }
        return out;
    }

    /** Which true picture the first slice of this unit lies in (-1: none). */
    static int owner(Ev e, Truth t) {
        for (int p = 0; p + 3 < e.n; p++) {
            if (e.data[p] == 0 && e.data[p + 1] == 0 && e.data[p + 2] == 1) {
                int type = e.data[p + 3] & 0x1F;
                if (type >= 1 && type <= 5) {
                    long abs = e.at + p;
                    for (int i = 0; i < t.n; i++) {
                        if (abs >= t.off[i] && abs < t.end(i)) {
                            return i;
                        }
                    }
                    return -1;
                }
                p += 2;
            }
        }
        return -1;
    }

    /**
     * A unit is good if it carries exactly the slices of one true picture: that is all a decoder needs. (A unit that
     * only lacks a trailing end-of-sequence or filler NAL unit is good; one that lacks half a slice, or holds two
     * pictures, is not.)
     */
    static boolean acceptable(Ev e, Truth t) {
        int i = owner(e, t);
        if (i < 0) {
            return false;
        }
        List<byte[]> a = sliceList(e.data, 0, e.n);
        List<byte[]> b = sliceList(t.s, (int) t.off[i], (int) t.end(i));
        if (a.size() != b.size()) {
            return false;
        }
        for (int k = 0; k < a.size(); k++) {
            if (!Arrays.equals(a.get(k), b.get(k))) {
                return false;
            }
        }
        return true;
    }

    static boolean isExactUnit(Ev e, Truth t) {
        for (int i = 0; i < t.n; i++) {
            if (t.off[i] == e.at) {
                return t.sz[i] == e.n && Arrays.equals(e.data, Arrays.copyOfRange(t.s, (int) t.off[i], (int) t.end(i)));
            }
        }
        return false;
    }

    static String falseHintsOk(Rec rec, Truth t) {
        long lastDamageAt = -1;
        Ev last = null;
        for (int k = 0; k < rec.ev.size(); k++) {
            Ev e = rec.ev.get(k);
            if (e.damage) {
                lastDamageAt = e.at;
                if (k == 0 || rec.ev.get(k - 1).damage || acceptable(rec.ev.get(k - 1), t)) {
                    return "damage reported (" + e.why + ") after a picture that was right";
                }
            } else {
                last = e;
                if (!acceptable(e, t) && (k + 1 >= rec.ev.size() || !rec.ev.get(k + 1).damage)) {
                    return "a wrong picture at " + e.at + "+" + e.n + " was not followed by a damage report; "
                            + whereIs(e, t);
                }
            }
        }
        if (t.n >= 4 && lastDamageAt < t.off[t.n - 3]) {
            if (last == null || !acceptable(last, t) || owner(last, t) != t.n - 1) {
                return "the stream did not recover: last picture at " + (last == null ? -1 : last.at)
                        + ", wanted " + t.off[t.n - 1];
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------ special cases

    static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            b[i] = (byte) v[i];
        }
        return b;
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.write(p, 0, p.length);
        }
        return o.toByteArray();
    }

    static void special() throws IOException {
        byte[] sps = bytes(0, 0, 0, 1, 0x67, 0x42, 0x11, 0x22, 0x33);
        byte[] pps = bytes(0, 0, 0, 1, 0x68, 0xCE, 0x11);
        byte[] idr = bytes(0, 0, 0, 1, 0x65, 0x88, 0x84, 0x21, 0x7F, 0x55);
        byte[] p1 = bytes(0, 0, 1, 0x41, 0x9A, 0x24, 0x6C, 0x42);
        byte[] p2 = bytes(0, 0, 0, 1, 0x41, 0x9B, 0x2A, 0x77, 0x13);
        byte[] slice2 = bytes(0, 0, 1, 0x41, 0x1A, 0x24, 0x6C); // first_mb_in_slice != 0
        byte[] tiny = bytes(0, 0, 0, 1, 0x41, 0x9A); // header and one byte: not all of a slice

        // a picture that is only a stub of a slice is not handed on at a hint, but by the next picture
        {
            Rec rec = new Rec();
            AnnexBFramer f = new AnnexBFramer(rec);
            byte[] a = cat(sps, pps, idr);
            f.push(a, 0, a.length);
            f.push(tiny, 0, tiny.length);
            check(rec.ev.size() == 1 && rec.ev.get(0).n == a.length, "special: the stub's header ends the picture before it");
            f.endOfPacket();
            check(rec.ev.size() == 1, "special: a stub slice is not handed on at a hint");
            f.push(p2, 0, p2.length);
            check(rec.ev.size() == 2 && rec.ev.get(1).n == tiny.length && rec.damages() == 0,
                    "special: ... but when the next picture starts");
        }
        // a hint right behind a start code (header not here yet) changes nothing
        {
            Rec rec = new Rec();
            AnnexBFramer f = new AnnexBFramer(rec);
            byte[] a = cat(sps, pps, idr);
            f.push(a, 0, a.length);
            f.push(bytes(0, 0, 1), 0, 3);
            f.endOfPacket();
            check(rec.ev.isEmpty(), "special: a hint behind a bare start code holds everything");
            f.push(bytes(0x41, 0x9A, 0x24, 0x6C), 0, 4);
            check(rec.ev.size() == 1 && rec.damages() == 0, "special: the picture before it comes out when its successor shows");
        }
        // zero bytes of padding, a start code cut in two by a hint, and the picture continued
        {
            Rec rec = new Rec();
            AnnexBFramer f = new AnnexBFramer(rec);
            byte[] a = cat(sps, pps, idr);
            f.push(a, 0, a.length);
            f.push(bytes(0, 0), 0, 2);
            f.endOfPacket(); // the picture is over; "00 00" belongs to the next start code
            f.push(bytes(0, 1, 0x41, 0x9A, 0x24, 0x6C), 0, 6);
            f.endOfPacket();
            check(rec.damages() == 0 && rec.ev.size() == 2 && rec.ev.get(0).n == a.length && rec.ev.get(1).n == 8,
                    "special: a start code cut in two by a hint is put back together (" + rec.ev.size() + " pictures, "
                            + rec.damages() + " damage)");
        }
        // a picture cut in the middle of a NAL unit: the rest is reported as damage, and the stream goes on at the next picture
        {
            Rec rec = new Rec();
            AnnexBFramer f = new AnnexBFramer(rec);
            byte[] a = cat(sps, pps, idr);
            f.push(a, 0, a.length);
            f.endOfPacket();
            f.push(p1, 0, p1.length);
            f.endOfPacket(); // a good picture
            byte[] big = bytes(0, 0, 0, 1, 0x41, 0x9B, 0x2A, 0x77, 0x13, 0x45, 0x46, 0x47);
            f.push(big, 0, 8);
            f.endOfPacket(); // wrong: cut after four payload bytes
            f.push(big, 8, 4); // the rest, without a start code
            check(rec.damages() == 1 && rec.ev.size() == 4 && rec.ev.get(3).damage, "special: a picture cut mid-NAL is reported");
            f.push(p2, 0, p2.length);
            f.endOfPacket();
            check(rec.ev.size() == 5 && !rec.ev.get(4).damage && rec.ev.get(4).n == p2.length,
                    "special: the stream goes on at the next picture after damage");
        }
        // a multi-slice picture cut between its slices
        {
            Rec rec = new Rec();
            AnnexBFramer f = new AnnexBFramer(rec);
            byte[] a = cat(sps, pps, idr);
            f.push(a, 0, a.length);
            f.endOfPacket();
            f.push(p1, 0, p1.length);
            f.endOfPacket(); // looks like a whole picture
            f.push(slice2, 0, slice2.length); // but this belonged to it
            check(rec.damages() == 1, "special: a slice from the middle of a picture is reported");
        }
        // a stream that never ends a picture is given up on instead of eating memory
        {
            Rec rec = new Rec();
            AnnexBFramer f = new AnnexBFramer(rec);
            f.push(cat(sps, pps, idr), 0, cat(sps, pps, idr).length);
            byte[] junk = new byte[1 << 20];
            Arrays.fill(junk, (byte) 7);
            for (int i = 0; i < 10; i++) {
                f.push(junk, 0, junk.length);
            }
            check(rec.damages() >= 1, "special: no end of a picture within 8 MB is reported");
        }
    }

    // ------------------------------------------------------------------------------------ fuzzing

    /**
     * Random bytes that are mostly 0, 1 and NAL headers, so start codes and odd NAL units turn up all the time, cut
     * into random pieces with random hints. Whatever comes in, the framer must not throw, and every unit it hands
     * on must begin with zero bytes and a start code, and must not be empty.
     */
    static void fuzz(long seed, int rounds) throws IOException {
        for (int round = 0; round < rounds; round++) {
            Random r = new Random(seed + round);
            final int[] bad = new int[1];
            final long[] emitted = new long[1];
            AnnexBFramer f = new AnnexBFramer(new AnnexBFramer.Sink() {
                @Override
                public void unit(byte[] d, int n, boolean key, boolean sps, long at) {
                    emitted[0] += n;
                    int z = 0;
                    while (z < n && d[z] == 0) {
                        z++;
                    }
                    if (n < 4 || z < 2 || z >= n || d[z] != 1) {
                        bad[0]++;
                    }
                }

                @Override
                public void damaged(String why, long at) {
                    // fine: random data is full of damage
                }
            });
            int total = 0;
            int bursts = 5 + r.nextInt(40);
            for (int b = 0; b < bursts; b++) {
                int n = 1 + r.nextInt(r.nextInt(8) == 0 ? 400000 : 3000);
                byte[] d = new byte[n];
                int mode = r.nextInt(4);
                for (int i = 0; i < n; i++) {
                    int k = r.nextInt(10);
                    if (mode == 0) {
                        d[i] = (byte) r.nextInt(256);
                    } else if (mode == 1) {
                        d[i] = (byte) (k < 5 ? 0 : (k < 8 ? 1 : (0x40 + r.nextInt(32))));
                    } else if (mode == 2) {
                        d[i] = (byte) (k < 3 ? 0 : (k < 5 ? 1 : (k < 7 ? (0x41 + r.nextInt(2) * 0x24) : r.nextInt(256))));
                    } else {
                        d[i] = (byte) (k < 8 ? 0 : 1);
                    }
                }
                int p = 0;
                while (p < n) {
                    int c = Math.min(n - p, 1 + r.nextInt(1 + r.nextInt(2000)));
                    f.push(d, p, c);
                    p += c;
                    total += c;
                    if (r.nextInt(3) == 0) {
                        f.endOfPacket();
                    }
                }
            }
            f.endOfPacket();
            check(bad[0] == 0, "fuzz " + (seed + round) + ": " + bad[0] + " units that do not begin with a start code");
            check(emitted[0] <= total, "fuzz " + (seed + round) + ": more bytes out (" + emitted[0] + ") than in (" + total + ")");
        }
    }

    // ------------------------------------------------------------------------------------ synthetic streams

    static void nal(ByteArrayOutputStream o, Random r, int type, int nri, int payload, int firstSlice, boolean fourByte) {
        if (fourByte) {
            o.write(0);
        }
        o.write(0);
        o.write(0);
        o.write(1);
        o.write((nri << 5) | type);
        for (int i = 0; i < payload; i++) {
            int b = 1 + r.nextInt(255);
            if (i == 0 && firstSlice >= 0) {
                b = firstSlice == 1 ? (0x80 | (1 + r.nextInt(127))) : (1 + r.nextInt(127));
            }
            o.write(b);
        }
    }

    static int slicePayload(Random r) {
        int k = r.nextInt(100);
        if (k < 20) {
            return 3 + r.nextInt(4);
        }
        if (k < 98) {
            return 10 + r.nextInt(3000);
        }
        return 150000 + r.nextInt(150000);
    }

    /** Builds a stream picture by picture; fills sizes with the length of every picture. */
    static byte[] synth(Random r, int[][] sizesOut) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int count = 30 + r.nextInt(40);
        boolean aud = r.nextBoolean();
        boolean multi = r.nextBoolean();
        int[] sizes = new int[count];
        for (int a = 0; a < count; a++) {
            int start = o.size();
            int pad = r.nextInt(4) == 0 ? 1 + r.nextInt(3) : 0; // zero bytes in front of the picture
            for (int i = 0; i < pad; i++) {
                o.write(0);
            }
            boolean idr = a % 17 == 0;
            if (aud) {
                nal(o, r, 9, 0, 1, -1, true);
            }
            if (idr || a % 29 == 5) {
                nal(o, r, 7, 3, 8 + r.nextInt(22), -1, r.nextBoolean());
                nal(o, r, 8, 3, 3 + r.nextInt(8), -1, r.nextBoolean());
            }
            if (r.nextInt(10) < 3) {
                nal(o, r, 6, 0, 5 + r.nextInt(36), -1, r.nextBoolean());
            }
            int ns = multi ? 1 + r.nextInt(4) : 1;
            for (int k = 0; k < ns; k++) {
                nal(o, r, idr ? 5 : 1, idr ? 3 : 2, slicePayload(r), k == 0 ? 1 : 0, k == 0 ? r.nextInt(4) != 0 : r.nextBoolean());
            }
            if (r.nextInt(10) == 0) {
                nal(o, r, 10, 0, 0, -1, r.nextBoolean()); // end of sequence
            }
            if (r.nextInt(10) == 0) {
                nal(o, r, 12, 0, 4 + r.nextInt(47), -1, r.nextBoolean()); // filler data
            }
            sizes[a] = o.size() - start;
        }
        sizesOut[0] = sizes;
        return o.toByteArray();
    }

    // ------------------------------------------------------------------------------------ main

    static int[] readSizes(String file) throws IOException {
        List<Integer> v = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(file))) {
            line = line.trim();
            if (!line.isEmpty()) {
                v.add(Integer.parseInt(line.split(",")[0]));
            }
        }
        int[] out = new int[v.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = v.get(i);
        }
        return out;
    }

    public static void main(String[] a) throws Exception {
        if (a[0].equals("real")) {
            byte[] s = Files.readAllBytes(Paths.get(a[1]));
            Truth t = new Truth(s, readSizes(a[2]));
            long seed = Long.parseLong(a[3]);
            for (int k = 0; k < 4; k++) {
                scenarios("real " + Paths.get(a[1]).getFileName() + " seed " + (seed + k), t, seed + k);
            }
        } else {
            special();
            fuzz(Long.parseLong(a[1]) * 7, 120);
            long seed = Long.parseLong(a[1]);
            int rounds = Integer.parseInt(a[2]);
            for (int k = 0; k < rounds; k++) {
                int[][] sz = new int[1][];
                byte[] s = synth(new Random(seed + k), sz);
                scenarios("synthetic " + (seed + k), new Truth(s, sz[0]), seed + k);
            }
        }
        System.out.println(checks + " checks, " + fails.size() + " failed");
        System.exit(fails.isEmpty() ? 0 : 1);
    }
}
