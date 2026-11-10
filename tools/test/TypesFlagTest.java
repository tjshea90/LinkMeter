package com.tj.linkmeter;

import java.util.Random;

/**
 * Offline test of TypesFlag (when the phone's "the allowed network types were changed" memory is set and when it is forgotten).
 * Run by tools/test.sh. Prints "N checks, M failed".
 *
 * Audit D2: the memory used to be set by a press that changed nothing and to stay set after 5G was allowed again, so every fair
 * comparison (force 4G, then allow 5G) left it set for good: the Safety check said something was changed, the safety net kept
 * looking, and two minutes without mobile data brought a "LinkMeter undid a network change" notification for nothing.
 */
public final class TypesFlagTest {
    static int checks;
    static int failed;

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            failed++;
            System.out.println("  FAIL: " + msg);
        }
    }

    static final long NR = 1L << 19;
    static final long LTE = 1L << 12;
    static final long WCDMA = 1L << 3;
    static final long GSM = 1L << 0;
    /** What this phone normally uses (Actions.DEFAULT_TYPES is 840583 = GSM, WCDMA..., LTE and NR bits). */
    static final long NORMAL = 840583L;

    /** A phone and its memory, driven with the same steps as Actions.setNrViaApi / setNr: read, decide, remember just before a real write, write, read back, forget. */
    static final class World {
        long mask;
        long nt = -1;
        boolean takesWrites = true;
        boolean readFails;

        World(long mask) {
            this.mask = mask;
        }

        long read() {
            return readFails ? -1 : mask;
        }

        void forgetIfBack(long now) {
            if (nt > 0 && !TypesFlag.stillNeeded(nt, now)) {
                nt = -1;
            }
        }

        /** Returns "unreadable", "already", "done" or "refused". */
        String setNr(boolean allow5g) {
            long m = read();
            if (m < 0 || (m & LTE) == 0) {
                return "unreadable";
            }
            long target = allow5g ? (m | NR) : (m & ~NR);
            if (target == m) {
                forgetIfBack(m);
                return "already";
            }
            nt = TypesFlag.beforeWrite(nt, m);
            if (takesWrites) {
                mask = target;
            }
            long now = read();
            forgetIfBack(now);
            return now == target ? "done" : "refused";
        }
    }

    public static void main(String[] a) {
        // ---- beforeWrite: the first mask ever remembered stays
        check(TypesFlag.beforeWrite(-1, 100) == 100, "nothing stored: the phone's mask is stored");
        check(TypesFlag.beforeWrite(0, 100) == 100, "a stored 0 is nothing");
        check(TypesFlag.beforeWrite(840583L, 100) == 840583L, "the first mask stays, whatever the phone says now");
        check(TypesFlag.beforeWrite(840583L, 840583L) == 840583L, "the same mask stays");

        // ---- stillNeeded: a table
        long[] stored = {-1, 0, 5, 840583L};
        for (long was : stored) {
            check(!TypesFlag.stillNeeded(was, was), "never needed when the phone is as remembered (or nothing is stored): " + was);
        }
        check(!TypesFlag.stillNeeded(-1, 840583L), "nothing stored: nothing needed");
        check(!TypesFlag.stillNeeded(0, 5), "a stored 0 is nothing");
        check(!TypesFlag.stillNeeded(-1, -1), "nothing stored and nothing read: nothing needed");
        check(!TypesFlag.stillNeeded(840583L, 840583L), "the phone is as it was: forget");
        check(TypesFlag.stillNeeded(840583L, 840583L & ~NR), "5G is off, it was on: keep");
        check(TypesFlag.stillNeeded(840583L, -1), "the phone's mask could not be read: keep");
        check(TypesFlag.stillNeeded(840583L, 0), "a mask of 0 is not the remembered one: keep");
        check(TypesFlag.stillNeeded(840583L, 840583L | (1L << 4)), "any other difference: keep");

        // ---- stories
        {
            World w = new World(NORMAL);
            check(w.setNr(true).equals("already") && w.nt == -1, "Allow 5G on a phone that allows 5G stores nothing");
        }
        {
            World w = new World(NORMAL & ~NR);
            check(w.setNr(false).equals("already") && w.nt == -1, "Force 4G on a phone that is already 4G only stores nothing");
        }
        {
            World w = new World(NORMAL);
            check(w.setNr(false).equals("done") && w.mask == (NORMAL & ~NR) && w.nt == NORMAL, "Force 4G stores the mask from before");
            check(w.setNr(false).equals("already") && w.nt == NORMAL, "a second Force 4G keeps it (the phone is still changed)");
            check(w.setNr(true).equals("done") && w.mask == NORMAL && w.nt == -1, "Allow 5G puts the phone back: the memory is forgotten");
        }
        {
            // the fair comparison: five rounds of 4G only and 5G allowed in random order, ending with 5G allowed as at the start
            Random r = new Random(7);
            for (int run = 0; run < 200; run++) {
                World w = new World(NORMAL);
                for (int round = 0; round < 5; round++) {
                    boolean fourGFirst = r.nextBoolean();
                    w.setNr(!fourGFirst);
                    w.setNr(fourGFirst);
                }
                w.setNr(true);
                check(w.mask == NORMAL, "the run ends with the phone as it was");
                check(w.nt == -1, "and the memory is forgotten after a whole comparison (run " + run + ")");
            }
        }
        {
            // a phone that did not have 5G to begin with: allowing it IS a change, and the memory keeps the original
            World w = new World(NORMAL & ~NR);
            check(w.setNr(true).equals("done") && w.nt == (NORMAL & ~NR), "5G switched on over an original without it is remembered");
            check(w.setNr(false).equals("done") && w.nt == -1, "and forgotten when 5G is off again");
        }
        {
            // the user changed the allowed types by hand in between: 5G is allowed, but not as it was
            World w = new World(NORMAL);
            w.setNr(false);
            w.mask = (NORMAL & ~NR) | (1L << 20); // some other type the user added by hand
            check(w.setNr(true).equals("done") && w.nt == NORMAL, "a phone that differs from the original keeps the memory");
        }
        {
            // a write the phone did not take
            World w = new World(NORMAL);
            w.takesWrites = false;
            check(w.setNr(false).equals("refused") && w.nt == -1, "a write that was refused leaves nothing to put back");
        }
        {
            // a write after which the mask cannot be read: nobody knows, so the memory stays
            World w = new World(NORMAL);
            w.takesWrites = true;
            long m = w.read();
            long target = m & ~NR;
            w.nt = TypesFlag.beforeWrite(w.nt, m);
            w.mask = target;
            w.readFails = true;
            w.forgetIfBack(w.read());
            check(w.nt == NORMAL, "an unreadable mask after a write keeps the memory");
        }
        {
            // a memory left by an older version although 5G is back (what the Safety check and the start-up clean-up fix)
            World w = new World(NORMAL);
            w.nt = NORMAL;
            w.forgetIfBack(w.read());
            check(w.nt == -1, "a stale memory is forgotten as soon as the phone is read as it was");
            World v = new World(NORMAL & ~NR);
            v.nt = NORMAL;
            v.forgetIfBack(v.read());
            check(v.nt == NORMAL, "a memory of a real change stays");
        }
        {
            // random walks: the memory is set exactly when the phone differs from the first mask ever seen, as long as every write is read back
            Random r = new Random(11);
            long[] starts = {NORMAL, NORMAL & ~NR, NORMAL | GSM, LTE | NR, LTE | WCDMA};
            for (int run = 0; run < 400; run++) {
                World w = new World(starts[r.nextInt(starts.length)]);
                long first = w.mask;
                for (int step = 0; step < 12; step++) {
                    w.setNr(r.nextBoolean());
                    boolean differs = w.mask != first;
                    check((w.nt > 0) == differs, "the memory is set exactly when the phone differs from its first mask (run " + run + " step " + step + ")");
                    check(!differs || w.nt == first, "and then it holds the first mask");
                }
            }
        }

        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
