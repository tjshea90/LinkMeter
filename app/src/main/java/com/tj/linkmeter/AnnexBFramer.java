package com.tj.linkmeter;

import java.io.IOException;

/**
 * Cuts a raw H.264 byte stream (Annex B: NAL units behind 00 00 01 start codes) into whole pictures
 * (access units), whatever way the bytes were chunked on their way here.
 *
 * Why this exists: a hardware decoder wants exactly one picture per input buffer. The phone's screenrecord writes
 * a plain byte stream in which nothing says where a picture ends, so the phone could only guess ("the pipe has
 * been quiet for 3 ms"). On a busy phone a big picture then arrives in two pieces, or two pictures arrive as one,
 * and the decoder is left with a wrong reference picture: blocks of the old and the new screen mixed together
 * until the next keyframe, which screenrecord sends only every ten seconds.
 *
 * The boundaries are in the stream itself (H.264 clause 7.4.1.2.3): a picture ends where an access unit
 * delimiter, a parameter set or an SEI starts after a picture slice, or where a slice with first_mb_in_slice == 0
 * starts after a slice. Only the LAST picture of a burst is unknown until the next one starts, and that is
 * exactly the one that must not wait: so the phone's packet end ("the stream went quiet here") releases it, after
 * a check that it cannot be in the middle of a NAL unit. If that guess was wrong, the rest of the picture comes
 * next and does not start with a start code (or is a slice from the middle of a picture): that is reported as
 * damage, so the owner can restart the stream from a keyframe instead of showing garbage for ten seconds.
 *
 * Every unit handed on starts with a start code and holds all the NAL units of one picture, with any parameter
 * sets and SEI that came before it. One thread only. No Android classes here, so it is tested on a computer
 * (tools/test/test_framer.py).
 */
final class AnnexBFramer {
    interface Sink {
        /**
         * One whole picture. d[0, n) starts at a start code; it is only valid during the call.
         * key: an IDR picture. sps: the unit holds a sequence parameter set. at: where the unit starts in the
         * whole stream (for logs and tests).
         */
        void unit(byte[] d, int n, boolean key, boolean sps, long at) throws IOException;

        /** The stream no longer fits together (a picture was handed on before all of it had arrived). */
        void damaged(String why, long at) throws IOException;
    }

    /** The most one picture may grow to before the stream is given up on. */
    private static final int MAX_PENDING = 8 << 20;

    private final Sink sink;
    private byte[] buf = new byte[1 << 17];
    /** buf[0, len) is the unit being built (maybe with zero bytes in front); it starts at a start code. */
    private int len;
    /** buf[0, scan) has been searched for start codes. */
    private int scan;
    /** Where the start code of the most recent NAL unit is in buf (-1: none). */
    private int lastStart = -1;
    /** The unit being built already has a picture slice / that picture is an IDR picture / it has an SPS. */
    private boolean vcl;
    private boolean key;
    private boolean sps;
    /** The last unit was handed on at a packet end: what follows must begin with a start code. */
    private boolean released;
    /** ... and the NAL unit it ended with was a picture slice (if not, losing the rest of it loses no picture). */
    private boolean tailSlice;
    /** After damage (and at the very start): throw bytes away until something that can begin a picture. */
    private boolean skipping = true;
    /** A start code at the very end of buf cannot be told yet (its NAL header has not arrived). */
    private boolean pending;
    /** Stream offset of buf[0]. */
    private long base;

    AnnexBFramer(Sink sink) {
        this.sink = sink;
    }

    /** Adds bytes that arrived (any amount, any chunking) and hands on every picture that is now known to be whole. */
    void push(byte[] d, int off, int n) throws IOException {
        if (n <= 0) {
            return;
        }
        if ((long) len + n > MAX_PENDING) {
            base += len;
            len = 0;
            scan = 0;
            lastStart = -1;
            damage("no end of a picture within " + (MAX_PENDING >> 20) + " MB");
            if (n > MAX_PENDING) {
                base += n;
                return;
            }
        }
        if (len + n > buf.length) {
            byte[] bigger = new byte[Math.min(MAX_PENDING, Math.max(buf.length * 2, len + n))];
            System.arraycopy(buf, 0, bigger, 0, len);
            buf = bigger;
        }
        System.arraycopy(d, off, buf, len, n);
        len += n;
        process();
    }

    /**
     * The sender says the stream went quiet here, which for screenrecord means the picture is over. If a picture
     * is waiting and the last NAL unit looks whole, it is handed on now instead of waiting for the next picture.
     */
    void endOfPacket() throws IOException {
        if (!vcl || skipping || released || pending || lastStart < 0 || lastStart + 3 >= len) {
            return;
        }
        int type = buf[lastStart + 3] & 0x1F;
        boolean slice = type >= 1 && type <= 5;
        if (slice && len - (lastStart + 4) < 2) {
            return; // a slice with (almost) nothing in it cannot be all of a slice
        }
        int e = len;
        while (e > 0 && buf[e - 1] == 0) {
            e--; // zero bytes at the end are padding or the first bytes of the next start code
        }
        if (e == 0) {
            return;
        }
        sink.unit(buf, e, key, sps, base);
        drop(e);
        vcl = false;
        key = false;
        sps = false;
        released = true;
        tailSlice = slice;
        lastStart = -1;
        scan = 0;
    }

    // ------------------------------------------------------------------------------------ the parsing

    private void process() throws IOException {
        pending = false;
        if (released) {
            int z = 0;
            while (z < len && buf[z] == 0) {
                z++;
            }
            if (z == len) {
                pending = true;
                return; // only zero bytes so far
            }
            released = false;
            if (!(z >= 2 && buf[z] == 1)) {
                if (tailSlice) {
                    damage("a picture arrived in two pieces");
                } else {
                    skipping = true; // the rest of a filler or end-of-sequence NAL unit: nothing to show is lost
                }
            }
        }
        int i = scan;
        for (;;) {
            int s = nextStart(i);
            if (s < 0) {
                if (skipping) {
                    int e = len; // nothing worth keeping but zero bytes at the end: they may begin a start code
                    while (e > 0 && buf[e - 1] == 0) {
                        e--;
                    }
                    drop(e);
                    scan = 0;
                } else {
                    scan = Math.max(i, len - 2);
                }
                return;
            }
            int h = s + 3; // the NAL header byte
            if (h >= len) {
                scan = s;
                pending = true;
                return;
            }
            int type = buf[h] & 0x1F;
            boolean slice = type >= 1 && type <= 5;
            if (slice && h + 1 >= len) {
                scan = s;
                pending = true;
                return; // the first bit after the header (first_mb_in_slice) is not here yet
            }
            boolean first = slice && (buf[h + 1] & 0x80) != 0; // first_mb_in_slice == 0: the picture's first slice
            boolean starter = type == 6 || type == 7 || type == 8 || type == 9 || (type >= 14 && type <= 18);
            int c = s; // zero bytes in front of a start code belong to it, not to the NAL unit before it
            while (c > 0 && buf[c - 1] == 0) {
                c--;
            }
            if (skipping) {
                if (!(starter || first)) {
                    i = s + 3;
                    continue;
                }
                drop(c);
                s -= c;
                c = 0;
                skipping = false;
            }
            if (vcl && (first || starter)) {
                sink.unit(buf, c, key, sps, base);
                drop(c);
                s -= c;
                vcl = false;
                key = false;
                sps = false;
            } else if (slice && !first && !vcl) {
                damage("a slice arrived without the start of its picture");
                i = s + 3;
                continue;
            }
            if (slice) {
                if (!vcl) {
                    vcl = true;
                    key = type == 5;
                }
            } else if (type == 7) {
                sps = true;
            }
            lastStart = s;
            i = s + 3;
        }
    }

    /** Position of the next 00 00 01 at or after from (all three bytes inside buf), or -1. */
    private int nextStart(int from) {
        for (int i = Math.max(from, 0); i + 2 < len; i++) {
            if ((buf[i + 2] & 0xFF) > 1) {
                i += 2; // no start code can begin at i, i + 1 or i + 2
                continue;
            }
            if (buf[i] == 0 && buf[i + 1] == 0 && buf[i + 2] == 1) {
                return i;
            }
        }
        return -1;
    }

    /** Throws away buf[0, n). */
    private void drop(int n) {
        if (n <= 0) {
            return;
        }
        System.arraycopy(buf, n, buf, 0, len - n);
        len -= n;
        base += n;
        scan = Math.max(0, scan - n);
        lastStart = lastStart >= n ? lastStart - n : -1;
    }

    private void damage(String why) throws IOException {
        vcl = false;
        key = false;
        sps = false;
        released = false;
        skipping = true;
        sink.damaged(why, base);
    }
}
