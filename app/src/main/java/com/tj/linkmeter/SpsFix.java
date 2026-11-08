package com.tj.linkmeter;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * Pure Java (no Android classes), so it is tested on a computer: tools/test-spsfix.sh.
 *
 * A hardware H.264 decoder usually holds several decoded pictures back before showing them, because
 * the stream does not say that pictures come out in the order they were decoded (the "bitstream
 * restriction" part of the SPS, the stream's header). On a phone screen that goes still, the last
 * pictures then sit inside the decoder until the screen changes again. This rewrites the SPS to say
 * "no reordering, keep only as many pictures as the references need", which decoders follow.
 * Everything else in the stream is copied unchanged, and if anything about the SPS is unexpected,
 * or the rewritten SPS does not read back as intended, the original bytes are used.
 */
final class SpsFix {
    private SpsFix() {}

    // ------------------------------------------------------------ bit reading and writing

    /** Reads bits from an RBSP (the NAL payload with its emulation-prevention bytes removed). */
    static final class Bits {
        private final byte[] b;
        private final int bitLen;
        int pos;

        Bits(byte[] b, int len) {
            this.b = b;
            this.bitLen = len * 8;
        }

        int bit() {
            if (pos >= bitLen) {
                throw new IllegalStateException("ran out of bits");
            }
            int v = (b[pos >> 3] >> (7 - (pos & 7))) & 1;
            pos++;
            return v;
        }

        long bits(int n) {
            long v = 0;
            for (int i = 0; i < n; i++) {
                v = (v << 1) | bit();
            }
            return v;
        }

        /** Unsigned Exp-Golomb. */
        int ue() {
            int zeros = 0;
            while (bit() == 0) {
                if (++zeros > 31) {
                    throw new IllegalStateException("bad exp-golomb");
                }
            }
            return zeros == 0 ? 0 : (int) (((1L << zeros) - 1) + bits(zeros));
        }

        /** Signed Exp-Golomb. */
        int se() {
            int k = ue();
            return (k & 1) == 1 ? (k + 1) / 2 : -(k / 2);
        }
    }

    /** Writes bits, ending with the RBSP trailing bits. */
    static final class Out {
        private final ByteArrayOutputStream bo = new ByteArrayOutputStream();
        private int cur;
        private int n;

        void bit(int v) {
            cur = (cur << 1) | (v & 1);
            if (++n == 8) {
                bo.write(cur);
                cur = 0;
                n = 0;
            }
        }

        void bits(long v, int cnt) {
            for (int i = cnt - 1; i >= 0; i--) {
                bit((int) ((v >> i) & 1));
            }
        }

        void ue(int v) {
            long x = (long) v + 1;
            int len = 64 - Long.numberOfLeadingZeros(x);
            bits(0, len - 1);
            bits(x, len);
        }

        void se(int v) {
            ue(v > 0 ? 2 * v - 1 : -2 * v);
        }

        /** Copies bits [from, to) of another RBSP. */
        void copy(byte[] src, int from, int to) {
            for (int i = from; i < to; i++) {
                bit((src[i >> 3] >> (7 - (i & 7))) & 1);
            }
        }

        byte[] finish() {
            bit(1); // rbsp_stop_one_bit
            while (n != 0) {
                bit(0);
            }
            return bo.toByteArray();
        }
    }

    // ------------------------------------------------------------ the SPS

    /** What was read from one SPS, with the bit positions needed to rewrite it. */
    static final class Sps {
        int profile;
        int constraint;
        int level;
        int id;
        int chroma = 1;
        boolean separateColour;
        int log2FrameNum;
        int pocType;
        int refFrames;
        int widthMbs;
        int heightUnits;
        boolean frameMbsOnly;
        int cropL;
        int cropR;
        int cropT;
        int cropB;
        boolean vui;
        boolean restriction;
        int numReorder = -1;
        int maxDecBuf = -1;
        /** Position of seq_scaling_matrix_present_flag (high profiles only), and of the bit after the lists. */
        int scalingFlagBit = -1;
        int afterScalingBit = -1;
        /** Position of vui_parameters_present_flag. */
        int vuiFlagBit = -1;
        /** Position of bitstream_restriction_flag (only when there is a VUI). */
        int restrictionFlagBit = -1;
        /** First bit after bitstream_restriction_flag (only when it is set). */
        int restrictionBodyBit = -1;
        /** First bit of max_num_reorder_frames (only when bitstream_restriction_flag is set). */
        int reorderBit = -1;

        int width() {
            int unitX = (chroma == 0 || separateColour || chroma == 3) ? 1 : 2;
            return widthMbs * 16 - unitX * (cropL + cropR);
        }

        int height() {
            int unitY = ((chroma == 0 || separateColour || chroma != 1) ? 1 : 2) * (frameMbsOnly ? 1 : 2);
            return (frameMbsOnly ? 1 : 2) * heightUnits * 16 - unitY * (cropT + cropB);
        }

        /** True when the decoder is already told what we want. */
        boolean alreadyGood() {
            return vui && restriction && numReorder == 0 && maxDecBuf >= 0
                    && maxDecBuf <= Math.max(1, refFrames);
        }
    }

    private static void skipScalingList(Bits r, int size) {
        int last = 8;
        int next = 8;
        for (int j = 0; j < size; j++) {
            if (next != 0) {
                next = (last + r.se() + 256) % 256;
            }
            last = next == 0 ? last : next;
        }
    }

    private static void skipHrd(Bits r) {
        int cnt = r.ue() + 1;
        if (cnt > 32) {
            throw new IllegalStateException("bad hrd");
        }
        r.bits(4);
        r.bits(4);
        for (int i = 0; i < cnt; i++) {
            r.ue();
            r.ue();
            r.bit();
        }
        r.bits(20); // four 5-bit length fields
    }

    private static void parseVui(Bits r, Sps s) {
        if (r.bit() == 1) { // aspect_ratio_info_present_flag
            if (r.bits(8) == 255) {
                r.bits(32);
            }
        }
        if (r.bit() == 1) { // overscan_info_present_flag
            r.bit();
        }
        if (r.bit() == 1) { // video_signal_type_present_flag
            r.bits(3);
            r.bit();
            if (r.bit() == 1) {
                r.bits(24);
            }
        }
        if (r.bit() == 1) { // chroma_loc_info_present_flag
            r.ue();
            r.ue();
        }
        if (r.bit() == 1) { // timing_info_present_flag
            r.bits(32);
            r.bits(32);
            r.bit();
        }
        boolean nal = r.bit() == 1;
        if (nal) {
            skipHrd(r);
        }
        boolean vcl = r.bit() == 1;
        if (vcl) {
            skipHrd(r);
        }
        if (nal || vcl) {
            r.bit(); // low_delay_hrd_flag
        }
        r.bit(); // pic_struct_present_flag
        s.restrictionFlagBit = r.pos;
        s.restriction = r.bit() == 1;
        if (s.restriction) {
            s.restrictionBodyBit = r.pos;
            r.bit(); // motion_vectors_over_pic_boundaries_flag
            r.ue();
            r.ue();
            r.ue();
            r.ue();
            s.reorderBit = r.pos;
            s.numReorder = r.ue();
            s.maxDecBuf = r.ue();
        }
    }

    static Sps parse(byte[] rbsp, int len) {
        Bits r = new Bits(rbsp, len);
        Sps s = new Sps();
        s.profile = (int) r.bits(8);
        s.constraint = (int) r.bits(8);
        s.level = (int) r.bits(8);
        s.id = r.ue();
        int p = s.profile;
        if (p == 100 || p == 110 || p == 122 || p == 244 || p == 44 || p == 83 || p == 86
                || p == 118 || p == 128 || p == 138 || p == 139 || p == 134 || p == 135) {
            s.chroma = r.ue();
            if (s.chroma == 3) {
                s.separateColour = r.bit() == 1;
            }
            r.ue(); // bit_depth_luma_minus8
            r.ue(); // bit_depth_chroma_minus8
            r.bit(); // qpprime_y_zero_transform_bypass_flag
            s.scalingFlagBit = r.pos;
            if (r.bit() == 1) { // seq_scaling_matrix_present_flag
                int lists = s.chroma != 3 ? 8 : 12;
                for (int i = 0; i < lists; i++) {
                    if (r.bit() == 1) {
                        skipScalingList(r, i < 6 ? 16 : 64);
                    }
                }
            }
            s.afterScalingBit = r.pos;
        }
        s.log2FrameNum = r.ue() + 4;
        s.pocType = r.ue();
        if (s.pocType == 0) {
            r.ue(); // log2_max_pic_order_cnt_lsb_minus4
        } else if (s.pocType == 1) {
            r.bit();
            r.se();
            r.se();
            int n = r.ue();
            if (n > 255) {
                throw new IllegalStateException("bad poc cycle");
            }
            for (int i = 0; i < n; i++) {
                r.se();
            }
        }
        s.refFrames = r.ue();
        r.bit(); // gaps_in_frame_num_value_allowed_flag
        s.widthMbs = r.ue() + 1;
        s.heightUnits = r.ue() + 1;
        s.frameMbsOnly = r.bit() == 1;
        if (!s.frameMbsOnly) {
            r.bit(); // mb_adaptive_frame_field_flag
        }
        r.bit(); // direct_8x8_inference_flag
        if (r.bit() == 1) { // frame_cropping_flag
            s.cropL = r.ue();
            s.cropR = r.ue();
            s.cropT = r.ue();
            s.cropB = r.ue();
        }
        s.vuiFlagBit = r.pos;
        s.vui = r.bit() == 1;
        if (s.vui) {
            parseVui(r, s);
        }
        return s;
    }

    /** The SPS as it should be: same stream, but telling the decoder not to hold pictures back. */
    static byte[] rewrite(byte[] rbsp, Sps s) {
        Out o = new Out();
        o.copy(rbsp, 0, s.vuiFlagBit);
        o.bit(1); // vui_parameters_present_flag
        if (!s.vui) {
            for (int i = 0; i < 8; i++) {
                o.bit(0); // aspect, overscan, video signal, chroma loc, timing, nal hrd, vcl hrd, pic struct
            }
            o.bit(1); // bitstream_restriction_flag
            o.bit(1); // motion_vectors_over_pic_boundaries_flag
            o.ue(2);
            o.ue(1);
            o.ue(16);
            o.ue(16);
        } else {
            o.copy(rbsp, s.vuiFlagBit + 1, s.restrictionFlagBit); // the VUI up to the restriction flag
            o.bit(1); // bitstream_restriction_flag
            if (s.restriction) {
                o.copy(rbsp, s.restrictionBodyBit, s.reorderBit); // keep the motion vector fields
            } else {
                o.bit(1);
                o.ue(2);
                o.ue(1);
                o.ue(16);
                o.ue(16);
            }
        }
        o.ue(0); // max_num_reorder_frames
        o.ue(Math.max(1, s.refFrames)); // max_dec_frame_buffering
        return o.finish();
    }

    // ------------------------------------------------------------ NAL units

    static byte[] unescape(byte[] d, int from, int to) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(to - from);
        int zeros = 0;
        for (int i = from; i < to; i++) {
            int b = d[i] & 0xff;
            if (zeros >= 2 && b == 3) {
                zeros = 0;
                continue; // emulation_prevention_three_byte
            }
            o.write(b);
            zeros = (b == 0) ? zeros + 1 : 0;
        }
        return o.toByteArray();
    }

    static void escape(ByteArrayOutputStream out, byte[] rbsp) {
        int zeros = 0;
        for (byte x : rbsp) {
            int b = x & 0xff;
            if (zeros >= 2 && b <= 3) {
                out.write(3);
                zeros = 0;
            }
            out.write(b);
            zeros = (b == 0) ? zeros + 1 : 0;
        }
    }

    private static int findStart(byte[] d, int from, int len) {
        for (int i = from; i + 2 < len; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
                return i;
            }
        }
        return -1;
    }

    /** What to do with each SPS found: return a replacement RBSP, or null to keep it. */
    interface SpsMapper {
        byte[] map(byte[] rbsp, Sps parsed);
    }

    /** True if the chunk holds an SPS NAL unit (type 7). */
    static boolean hasSps(byte[] d, int len) {
        for (int i = findStart(d, 0, len); i >= 0; i = findStart(d, i + 3, len)) {
            if (i + 3 < len && (d[i + 3] & 0x1F) == 7) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rebuilds the chunk with every SPS replaced as the mapper says. Returns null if nothing changed.
     * Bytes between NAL units (start codes, zero padding) are copied as they were.
     */
    static byte[] mapSps(byte[] d, int len, SpsMapper mapper) {
        int first = findStart(d, 0, len);
        if (first < 0) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(len + 64);
        boolean changed = false;
        int copiedTo = 0; // d[0, copiedTo) is already in out
        int s = first;
        while (s >= 0) {
            int p = s + 3; // first byte of the NAL unit
            int next = findStart(d, p, len);
            int end = next < 0 ? len : next;
            int pe = end;
            while (pe > p && d[pe - 1] == 0) {
                pe--; // zero bytes that belong to the next start code or to padding
            }
            if (pe > p && (d[p] & 0x1F) == 7 && pe - p > 1) {
                byte[] repl = null;
                try {
                    byte[] rbsp = unescape(d, p + 1, pe);
                    repl = mapper.map(rbsp, parse(rbsp, rbsp.length));
                } catch (RuntimeException e) {
                    repl = null; // not an SPS we understand: keep it
                }
                if (repl != null) {
                    out.write(d, copiedTo, p - copiedTo);
                    out.write(d[p]); // NAL header byte unchanged
                    escape(out, repl);
                    copiedTo = pe;
                    changed = true;
                }
            }
            s = next;
        }
        if (!changed) {
            return null;
        }
        out.write(d, copiedTo, len - copiedTo);
        return out.toByteArray();
    }

    // ------------------------------------------------------------ public use

    /**
     * Returns the chunk with its SPS rewritten so the decoder shows each picture at once, or null if
     * there was nothing to do (no SPS, already fine, or not understood).
     */
    static byte[] fix(byte[] d, int len) {
        return mapSps(d, len, (rbsp, s) -> {
            if (s.alreadyGood()) {
                return null;
            }
            byte[] out = rewrite(rbsp, s);
            Sps t = parse(out, out.length);
            boolean ok = t.vui && t.restriction && t.numReorder == 0
                    && t.maxDecBuf == Math.max(1, s.refFrames)
                    && t.profile == s.profile && t.level == s.level && t.id == s.id
                    && t.widthMbs == s.widthMbs && t.heightUnits == s.heightUnits
                    && t.refFrames == s.refFrames && t.pocType == s.pocType
                    && t.log2FrameNum == s.log2FrameNum && t.width() == s.width()
                    && t.height() == s.height();
            return ok ? out : null;
        });
    }

    /** A one-line description of the first SPS in the chunk, for the diagnostics panel. */
    static String describe(byte[] d, int len) {
        for (int i = findStart(d, 0, len); i >= 0; i = findStart(d, i + 3, len)) {
            int p = i + 3;
            if (p >= len || (d[p] & 0x1F) != 7) {
                continue;
            }
            int next = findStart(d, p, len);
            int pe = next < 0 ? len : next;
            while (pe > p && d[pe - 1] == 0) {
                pe--;
            }
            try {
                byte[] rbsp = unescape(d, p + 1, pe);
                Sps s = parse(rbsp, rbsp.length);
                return String.format(Locale.US, "H.264 profile %d level %d.%d, %dx%d, %d reference%s, poc %d, %s",
                        s.profile, s.level / 10, s.level % 10, s.width(), s.height(), s.refFrames,
                        s.refFrames == 1 ? "" : "s", s.pocType,
                        !s.vui ? "no VUI" : (!s.restriction ? "VUI without reorder limits"
                                : "reorder " + s.numReorder + ", buffer " + s.maxDecBuf));
            } catch (RuntimeException e) {
                return "H.264 (header not understood)";
            }
        }
        return "";
    }
}
