package com.tj.linkmeter;

/**
 * No Android in here (tested in tools/test/TypesFlagTest.java).
 *
 * The phone's memory "the allowed network types were changed" ({@link Saved} "nt"): the mask the phone had before LinkMeter's first
 * change, kept so that Restore everything and the safety net can put it back. While it is set the safety net keeps watching and the
 * Safety check says something is changed, so it must be set only when a change may really have been made, and forgotten as soon as
 * the phone's mask is the remembered one again. (Audit D2: a press that changed nothing, and every fair comparison, which forces 4G
 * and then allows 5G again, used to leave it set for good.)
 */
final class TypesFlag {
    private TypesFlag() {}

    /**
     * What to store just before a REAL write over the phone's mask `mask` (the target differs from it): the first mask that was ever
     * remembered stays, because it is the one before LinkMeter's first change. Never called for a press that would change nothing.
     *
     * @param remembered the stored mask, or a value of 0 or less when nothing is stored
     */
    static long beforeWrite(long remembered, long mask) {
        return remembered > 0 ? remembered : mask;
    }

    /**
     * Is the stored mask still needed once the phone's mask has been read as `now`? False means forget it: nothing is stored, or the
     * phone is exactly as it was. A mask that could not be read (a value below 0) keeps what is stored.
     */
    static boolean stillNeeded(long remembered, long now) {
        return remembered > 0 && (now < 0 || now != remembered);
    }
}
