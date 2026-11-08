package com.tj.linkmeter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Random;

/** Command line driver for tools/test/test_spsfix.py. Not part of the app. */
public final class SpsFixTest {
    private SpsFixTest() {}

    public static void main(String[] a) throws IOException {
        switch (a[0]) {
            case "selftest":
                selftest();
                break;
            case "describe": {
                byte[] d = Files.readAllBytes(Paths.get(a[1]));
                System.out.println(SpsFix.describe(d, d.length));
                break;
            }
            case "positions": {
                byte[] d = Files.readAllBytes(Paths.get(a[1]));
                SpsFix.mapSps(d, d.length, (rbsp, s) -> {
                    System.out.println("SPS vui_flag=" + s.vuiFlagBit + " restriction_flag=" + s.restrictionFlagBit
                            + " reorder_bit=" + s.reorderBit + " reorder=" + s.numReorder + " dpb=" + s.maxDecBuf
                            + " refs=" + s.refFrames + " profile=" + s.profile + " size=" + s.width() + "x" + s.height());
                    return null;
                });
                break;
            }
            case "fix": {
                byte[] d = Files.readAllBytes(Paths.get(a[1]));
                byte[] r = SpsFix.fix(d, d.length);
                Files.write(Paths.get(a[2]), r == null ? d : r);
                System.out.println(r == null ? "unchanged" : "patched");
                break;
            }
            case "strip": { // remove the whole VUI
                byte[] d = Files.readAllBytes(Paths.get(a[1]));
                byte[] r = SpsFix.mapSps(d, d.length, (rbsp, s) -> {
                    SpsFix.Out o = new SpsFix.Out();
                    o.copy(rbsp, 0, s.vuiFlagBit);
                    o.bit(0);
                    return o.finish();
                });
                Files.write(Paths.get(a[2]), r == null ? d : r);
                System.out.println("stripped");
                break;
            }
            case "strip-restriction": { // keep the VUI but say there are no bitstream restrictions
                byte[] d = Files.readAllBytes(Paths.get(a[1]));
                byte[] r = SpsFix.mapSps(d, d.length, (rbsp, s) -> {
                    if (!s.vui) {
                        return null;
                    }
                    SpsFix.Out o = new SpsFix.Out();
                    o.copy(rbsp, 0, s.restrictionFlagBit);
                    o.bit(0);
                    return o.finish();
                });
                Files.write(Paths.get(a[2]), r == null ? d : r);
                System.out.println(r == null ? "unchanged" : "restriction removed");
                break;
            }
            case "craft-scaling": { // give the SPS scaling lists of every kind, to test the parser's skipping of them
                byte[] d = Files.readAllBytes(Paths.get(a[1]));
                byte[] r = SpsFix.mapSps(d, d.length, (rbsp, s) -> {
                    if (s.scalingFlagBit < 0) {
                        return null;
                    }
                    SpsFix.Out o = new SpsFix.Out();
                    o.copy(rbsp, 0, s.scalingFlagBit);
                    o.bit(1); // seq_scaling_matrix_present_flag
                    int lists = s.chroma != 3 ? 8 : 12;
                    for (int i = 0; i < lists; i++) {
                        int size = i < 6 ? 16 : 64;
                        switch (i % 4) {
                            case 0: // a few explicit values, then "the rest repeats" (nextScale becomes 0)
                                o.bit(1);
                                o.se(4);
                                o.se(2);
                                o.se(-14);
                                break;
                            case 1: // not present
                                o.bit(0);
                                break;
                            case 2: // use the default list (nextScale becomes 0 at once)
                                o.bit(1);
                                o.se(-8);
                                break;
                            default: // every value explicit
                                o.bit(1);
                                for (int j = 0; j < size; j++) {
                                    o.se(1);
                                }
                                break;
                        }
                    }
                    o.copy(rbsp, s.afterScalingBit, stopBit(rbsp)); // the rest of the SPS, without its trailing bits
                    return o.finish();
                });
                Files.write(Paths.get(a[2]), r == null ? d : r);
                System.out.println(r == null ? "unchanged" : "scaling lists added");
                break;
            }
            default:
                System.err.println("unknown command " + a[0]);
                System.exit(2);
        }
    }

    /** Position of the rbsp_stop_one_bit: the last 1 bit of the RBSP. */
    private static int stopBit(byte[] rbsp) {
        for (int i = rbsp.length * 8 - 1; i >= 0; i--) {
            if (((rbsp[i >> 3] >> (7 - (i & 7))) & 1) == 1) {
                return i;
            }
        }
        throw new IllegalStateException("no stop bit");
    }

    private static void fail(String msg) {
        System.err.println("SELFTEST FAILED: " + msg);
        System.exit(1);
    }

    private static void selftest() {
        // Exp-Golomb round trip, including values that need many bits.
        int[] big = {0, 1, 2, 3, 4, 7, 8, 254, 255, 256, 65535, 65536, 1 << 20, (1 << 30) - 2};
        for (int v = 0; v < 70000; v++) {
            check(v);
        }
        for (int v : big) {
            check(v);
        }
        // Emulation prevention: escaping then unescaping gives the same bytes, and never leaves 00 00 0x.
        Random rnd = new Random(7);
        for (int t = 0; t < 2000; t++) {
            byte[] raw = new byte[1 + rnd.nextInt(40)];
            for (int i = 0; i < raw.length; i++) {
                raw[i] = (byte) (rnd.nextInt(4) == 0 ? rnd.nextInt(256) : rnd.nextInt(4));
            }
            raw[raw.length - 1] = (byte) (raw[raw.length - 1] | 0x80); // an RBSP never ends in zero
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            SpsFix.escape(bo, raw);
            byte[] esc = bo.toByteArray();
            for (int i = 0; i + 2 < esc.length; i++) {
                if (esc[i] == 0 && esc[i + 1] == 0 && (esc[i + 2] & 0xff) <= 2) {
                    fail("escaped data still has 00 00 0" + esc[i + 2]);
                }
            }
            byte[] back = SpsFix.unescape(esc, 0, esc.length);
            if (!Arrays.equals(raw, back)) {
                fail("escape/unescape round trip " + Arrays.toString(raw));
            }
        }
        System.out.println("selftest ok");
    }

    private static void check(int v) {
        SpsFix.Out o = new SpsFix.Out();
        o.ue(v);
        byte[] b = o.finish();
        SpsFix.Bits r = new SpsFix.Bits(b, b.length);
        int got = r.ue();
        if (got != v) {
            fail("ue(" + v + ") read back " + got);
        }
    }
}
