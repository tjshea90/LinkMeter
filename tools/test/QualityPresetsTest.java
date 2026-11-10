package com.tj.linkmeter;

/** Tests QualityPresets, the preset tables and the order of the quality chip. Not part of the app. */
public final class QualityPresetsTest {
    private static int checks;
    private static int fails;

    private QualityPresetsTest() {}

    private static void yes(boolean ok, String what) {
        checks++;
        if (!ok) {
            fails++;
            System.out.println("FAIL " + what);
        }
    }

    private static void eq(Object want, Object got, String what) {
        checks++;
        if (!String.valueOf(want).equals(String.valueOf(got))) {
            fails++;
            System.out.println("FAIL " + what + ": wanted " + want + " but got " + got);
        }
    }

    public static void main(String[] args) {
        eq(5, QualityPresets.NAMES.length, "four presets and Custom");
        eq("Custom", QualityPresets.NAMES[QualityPresets.CUSTOM], "the last name is Custom");
        eq("Balanced", QualityPresets.NAMES[QualityPresets.BALANCED], "Balanced is the default");
        eq(4, QualityPresets.VID_W.length, "four video widths");
        eq(4, QualityPresets.VID_K.length, "four video bitrates");
        eq(4, QualityPresets.PIC_W.length, "four picture widths");
        eq(4, QualityPresets.PIC_Q.length, "four picture qualities");
        for (int p = 0; p < 4; p++) {
            yes(QualityPresets.VID_K[p] >= PHONE_FLOOR_KBPS, "the phone uses at least 500 kbit/s, so no preset asks for less: " + QualityPresets.VID_K[p]);
            eq(p, QualityPresets.match(QualityPresets.VID_W[p], QualityPresets.VID_K[p], QualityPresets.PIC_W[p], QualityPresets.PIC_Q[p]),
                    "the numbers of preset " + p + " are preset " + p);
            // any one number different: Custom
            eq(QualityPresets.CUSTOM, QualityPresets.match(QualityPresets.VID_W[p] + 1, QualityPresets.VID_K[p], QualityPresets.PIC_W[p], QualityPresets.PIC_Q[p]), "video width differs");
            eq(QualityPresets.CUSTOM, QualityPresets.match(QualityPresets.VID_W[p], QualityPresets.VID_K[p] + 1, QualityPresets.PIC_W[p], QualityPresets.PIC_Q[p]), "video bitrate differs");
            eq(QualityPresets.CUSTOM, QualityPresets.match(QualityPresets.VID_W[p], QualityPresets.VID_K[p], QualityPresets.PIC_W[p] + 1, QualityPresets.PIC_Q[p]), "picture width differs");
            eq(QualityPresets.CUSTOM, QualityPresets.match(QualityPresets.VID_W[p], QualityPresets.VID_K[p], QualityPresets.PIC_W[p], QualityPresets.PIC_Q[p] + 1), "picture quality differs");
        }
        // numbers taken from two different presets are Custom
        eq(QualityPresets.CUSTOM, QualityPresets.match(QualityPresets.VID_W[0], QualityPresets.VID_K[1], QualityPresets.PIC_W[2], QualityPresets.PIC_Q[3]), "a mix of presets is Custom");
        eq(QualityPresets.CUSTOM, QualityPresets.match(0, 0, 0, 0), "nothing is Custom");

        // the chip's order
        eq(1, QualityPresets.next(0, false), "Saver -> Fast");
        eq(2, QualityPresets.next(1, false), "Fast -> Balanced");
        eq(3, QualityPresets.next(2, false), "Balanced -> Sharp");
        eq(0, QualityPresets.next(3, false), "Sharp -> Saver when there are no custom numbers");
        eq(QualityPresets.CUSTOM, QualityPresets.next(3, true), "Sharp -> Custom when there are custom numbers");
        eq(0, QualityPresets.next(QualityPresets.CUSTOM, true), "Custom -> Saver");
        eq(0, QualityPresets.next(QualityPresets.CUSTOM, false), "Custom -> Saver, with or without custom numbers");
        eq(1, QualityPresets.next(0, true), "custom numbers do not change the steps before Sharp");
        eq(2, QualityPresets.next(1, true), "...");
        eq(3, QualityPresets.next(2, true), "...");
        eq(0, QualityPresets.next(-1, true), "a value out of range starts again");
        eq(0, QualityPresets.next(9, true), "...from either side");
        // a full lap visits every preset once and comes back
        for (boolean custom : new boolean[] {false, true}) {
            int p = 0;
            int seen = 0;
            for (int i = 0; i < 10 && !(i > 0 && p == 0); i++) {
                seen |= 1 << p;
                p = QualityPresets.next(p, custom);
            }
            eq(custom ? 31 : 15, seen, "one lap through the chip " + (custom ? "with" : "without") + " custom numbers");
        }

        System.out.println(checks + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }

    /** The phone's floor for the video bitrate (Screen.java: Math.max(500000, ...)), in kbit/s. */
    private static final int PHONE_FLOOR_KBPS = 500;
}
