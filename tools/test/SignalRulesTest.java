package com.tj.linkmeter;

/**
 * Offline test of SignalRules (what the Signal tab says and when it asks). Run by tools/test.sh. Prints "N checks, M failed".
 * What matters: the 4G or 5G switch and the chip never claim a state the phone's words do not say, an out-of-date carrier list is
 * not 5G, the tab never scans while a comparison runs, and the comparison is never started over Bluetooth.
 */
public final class SignalRulesTest {
    static int checks;
    static int failed;

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            failed++;
            System.out.println("  FAIL: " + msg);
        }
    }

    static RadioState state(String display, String nrState, boolean lte, boolean nr) {
        RadioState s = new RadioState();
        s.display = display;
        s.nrState = nrState;
        if (lte) {
            s.carriers.add(new RadioState.Carrier("LTE", 66, 66611, 20000, true));
        }
        if (nr) {
            s.carriers.add(new RadioState.Carrier("NR", 77, 650000, 100000, false));
        }
        return s;
    }

    public static void main(String[] a) {
        // ---- the mode, from the phone's own sentences (Actions.netGet, setNr, setNrViaApi)
        check(SignalRules.modeOf("Allowed network types: GPRS|EDGE|UMTS|LTE|NR (5G is allowed)") == SignalRules.Mode.FIVE_G_ALLOWED, "net_get with 5G");
        check(SignalRules.modeOf("Allowed network types: GPRS|EDGE|UMTS|LTE (5G is off)") == SignalRules.Mode.FOUR_G_ONLY, "net_get without 5G");
        check(SignalRules.modeOf("Forced to 4G. The phone is now on GPRS|EDGE|LTE.") == SignalRules.Mode.FOUR_G_ONLY, "net_lte done");
        check(SignalRules.modeOf("5G is already off. The phone is on GPRS|LTE.") == SignalRules.Mode.FOUR_G_ONLY, "net_lte when it was already 4G only");
        check(SignalRules.modeOf("5G is allowed again (GPRS|LTE|NR).") == SignalRules.Mode.FIVE_G_ALLOWED, "net_5g done");
        check(SignalRules.modeOf("5G is already allowed (GPRS|LTE|NR).") == SignalRules.Mode.FIVE_G_ALLOWED, "net_5g when it was already allowed");
        check(SignalRules.modeOf(null) == SignalRules.Mode.UNKNOWN && SignalRules.modeOf("") == SignalRules.Mode.UNKNOWN, "nothing is not a mode");
        check(SignalRules.modeOf("Couldn't read the phone's current network types, so nothing was changed.") == SignalRules.Mode.UNKNOWN, "a failure is not a mode");
        check(SignalRules.modeOf("The phone did not accept the change.") == SignalRules.Mode.UNKNOWN, "a refusal is not a mode");
        check(SignalRules.modeOf("5G is off and 5G is allowed") == SignalRules.Mode.UNKNOWN, "words that say both are not a mode");
        check(SignalRules.modeOf("Unknown action: net_get") == SignalRules.Mode.UNKNOWN, "an error is not a mode");

        // ---- the chip
        check(SignalRules.chipOf(null) == SignalRules.Chip.NOT_SCANNED, "before the first scan");
        check(SignalRules.chipOf(state("NR_ADVANCED", "CONNECTED", true, true)) == SignalRules.Chip.FIVE_G_UW, "5G UW from the phone's label");
        check(SignalRules.chipOf(state("NR_NSA", "CONNECTED", true, true)) == SignalRules.Chip.FIVE_G, "5G from the label");
        check(SignalRules.chipOf(state("NR_ADVANCED", "", true, false)) == SignalRules.Chip.FIVE_G_UW, "the phone's own 5G UW label settles it, whatever its list says");
        check(SignalRules.chipOf(state("NR_NSA", "", true, false)) == SignalRules.Chip.FIVE_G, "and so does the plain 5G label");
        check(SignalRules.chipOf(state("NONE", "NONE", true, false)) == SignalRules.Chip.FOUR_G, "4G: LTE only and no 5G label");
        check(SignalRules.chipOf(state("NONE", "NONE", true, true)) == SignalRules.Chip.FOUR_G, "an out-of-date 5G list is not 5G (the user's phone after 4G only)");
        check(SignalRules.chipOf(state("", "", true, true)) == SignalRules.Chip.FIVE_G, "a phone that says nothing else: the list is believed");
        check(SignalRules.chipOf(state("LTE_CA", "", true, false)) == SignalRules.Chip.FOUR_G, "LTE with carrier aggregation is 4G");
        check(SignalRules.chipOf(state("LTE_CA", "", false, false)) == SignalRules.Chip.FOUR_G, "and the label alone is enough");
        check(SignalRules.chipOf(state("", "", false, false)) == SignalRules.Chip.UNKNOWN, "nothing said, nothing listed: not known");
        check(SignalRules.chipOf(state("NONE", "", false, true)) == SignalRules.Chip.FIVE_G, "only a 5G carrier listed and a label that does not contradict: 5G");
        String[] texts = {"--", "No answer", "5G UW", "5G", "4G", "Not known"};
        SignalRules.Chip[] chips = SignalRules.Chip.values();
        check(chips.length == texts.length, "every chip has words");
        for (int i = 0; i < chips.length; i++) {
            check(SignalRules.chipText(chips[i]).equals(texts[i]), "chip words: " + chips[i]);
        }

        // ---- the Network line
        check(SignalRules.networkWords(null).equals("not scanned yet"), "network before a scan");
        check(SignalRules.networkWords(state("NR_ADVANCED", "CONNECTED", true, true)).startsWith("5G UW"), "network 5G UW");
        check(SignalRules.networkWords(state("NR_NSA", "CONNECTED", true, true)).equals("5G"), "network 5G");
        check(SignalRules.networkWords(state("NONE", "NONE", true, false)).equals("4G (LTE)"), "network 4G");
        check(SignalRules.networkWords(state("NONE", "NONE", true, true)).contains("old 5G cells"), "an out-of-date list is named, not shown as 5G");
        check(SignalRules.networkWords(state("LTE_ADVANCED_PRO", "", true, false)).contains("5G E"), "5G E is named for what it is");
        RadioState ca = state("LTE_CA", "", true, false);
        check(SignalRules.networkWords(ca).contains("carriers combined"), "carrier aggregation is said");
        check(SignalRules.networkWords(state("", "", false, false)).equals("not known"), "nothing said: not known");

        // ---- the signal
        check(SignalRules.dbm(-104).equals("-104 dBm") && SignalRules.dbm(Integer.MIN_VALUE).equals("not known"), "dBm");

        // ---- when the tab scans by itself
        check(SignalRules.scanOnShow(1000, -1, false), "never scanned: scan when shown");
        check(!SignalRules.scanOnShow(100_000, 80_000, false), "a scan 20 s old is not repeated");
        check(SignalRules.scanOnShow(100_000, 40_000, false), "a scan 60 s old is repeated");
        check(SignalRules.scanOnShow(100_000, 10_000, false), "an old scan is repeated");
        check(!SignalRules.scanOnShow(100_000, -1, true) && !SignalRules.scanOnShow(100_000, 10_000, true), "never while a comparison runs");
        check(SignalRules.scanOnShow(5_000, 900_000, false), "a clock that went back is not a young scan");
        check(SignalRules.RESCAN_MS == 60_000L && SignalRules.AFTER_CHANGE_MS == 8_000L, "the waits");

        // ---- the comparison
        check(SignalRules.comparisonBlocked(true, false).isEmpty(), "over the hotspot it can start");
        check(SignalRules.comparisonBlocked(true, true).contains("hotspot") && SignalRules.comparisonBlocked(true, true).contains("Bluetooth"),
                "over Bluetooth it says why it cannot");
        check(!SignalRules.comparisonBlocked(false, false).isEmpty() && !SignalRules.comparisonBlocked(false, true).isEmpty(), "with no phone it cannot");
        check(SignalRules.keepLabel("4G only (5G off)").equals("Keep: 4G only (5G off)"), "the Keep button");
        check(SignalRules.keepNote(true).contains("down for some minutes") && SignalRules.keepNote(true).contains("Restore everything")
                && SignalRules.keepNote(true).contains("switch it back"), "4G only: when it ends (the safety net is named)");
        check(!SignalRules.keepNote(false).contains("some minutes"), "5G allowed: no safety net to wait for");

        // ---- the search
        String w = SignalRules.searchWords("B66 + n77 (5G UW)", "B66 + n2 (5G UW)");
        check(w.contains("Before: B66 + n77 (5G UW)") && w.contains("Now: B66 + n2 (5G UW)") && !w.contains("same cells"), "before and after: " + w);
        check(SignalRules.searchWords("B66", "B66").contains("same cells"), "the same cells are said");
        check(SignalRules.searchWords(null, "").contains("Before: not known") && SignalRules.searchWords(null, "").contains("Now: not known"),
                "a missing scan is not known, and is not 'the same cells'");
        check(!SignalRules.searchWords("", "").contains("same cells") && !SignalRules.searchWords(null, null).contains("same cells"),
                "two unknowns are not 'the same cells'");

        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
