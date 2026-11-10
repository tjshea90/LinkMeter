package com.tj.linkmeter;

/**
 * Pure Java (no Android classes), so it is tested on a computer: tools/test/QualityPresetsTest.java.
 *
 * The Screen tab's quality presets: the four numbers each one sets (video width and bitrate, picture width and quality), which
 * preset a set of numbers is, and the order the quality chip goes through. A set of numbers that is none of the presets is Custom:
 * the user's own. ScreenPrefs keeps those numbers apart, so choosing a preset (or tapping the chip) never loses them, and the chip
 * can go back to them.
 */
final class QualityPresets {
    private QualityPresets() {}

    static final String[] NAMES = {"Saver", "Fast", "Balanced", "Sharp", "Custom"};
    static final int BALANCED = 2;
    static final int CUSTOM = 4;
    static final int[] VID_W = {432, 540, 720, 1080};
    static final int[] VID_K = {800, 1500, 3000, 6000};
    static final int[] PIC_W = {288, 360, 540, 720};
    static final int[] PIC_Q = {35, 40, 55, 70};

    /** Which preset these four numbers are: 0 to 3, or CUSTOM when they are none of them. */
    static int match(int vidW, int vidK, int picW, int picQ) {
        for (int p = 0; p < VID_W.length; p++) {
            if (vidW == VID_W[p] && vidK == VID_K[p] && picW == PIC_W[p] && picQ == PIC_Q[p]) {
                return p;
            }
        }
        return CUSTOM;
    }

    /** The preset the chip shows after this one: Saver, Fast, Balanced, Sharp, then Custom if there are custom numbers, then Saver. */
    static int next(int current, boolean haveCustom) {
        if (current >= 0 && current < CUSTOM - 1) {
            return current + 1;
        }
        return current == CUSTOM - 1 && haveCustom ? CUSTOM : 0;
    }
}
