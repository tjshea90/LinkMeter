package com.tj.linkmeter;

import java.util.List;
import java.util.Random;

/**
 * Offline test of SafetyReport (the words of the "Safety check" button). Run by tools/test.sh. Prints "N checks, M failed".
 * What matters: it never says "nothing is changed" while something is, never invents a change, and says plainly when it cannot
 * tell. Every combination of facts is tried.
 */
public final class SafetyReportTest {
    static int checks;
    static int failed;

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            failed++;
            if (failed <= 25) {
                System.out.println("  FAIL: " + msg);
            }
        }
    }

    static SafetyReport.Facts base() {
        SafetyReport.Facts f = new SafetyReport.Facts();
        f.types = 840583L;
        f.typesText = "GPRS|EDGE|UMTS|HSDPA|HSUPA|HSPA|LTE|HSPAP|GSM|LTE_CA|NR";
        f.airplaneOn = Boolean.FALSE;
        f.dataEnabled = Boolean.TRUE;
        f.cellular = "working";
        return f;
    }

    public static void main(String[] args) {
        // ---- a clean phone
        {
            SafetyReport.Facts f = base();
            String r = SafetyReport.report(f);
            check(r.contains("LinkMeter has nothing changed on this phone."), "a clean phone says so: " + r);
            check(!r.contains("Still changed"), "and lists no change");
            check(r.contains("Mobile data: working") && r.contains("Network types: GPRS"), "and shows the facts");
            check(!r.contains("5G leg:") && !r.contains("Band lock:"), "and has no lines for a leg or a lock, which this version cannot set (audit D4)");
            check(SafetyReport.changes(f).isEmpty() && !SafetyReport.radioChanged(f), "no change, nothing for the safety net");
        }

        // ---- every single change is reported, and reported as a change
        String[] names = {"types", "leg", "lockFlag", "blip", "airplane", "hotspot", "menu", "secure", "pairing", "assist"};
        for (int i = 0; i < names.length; i++) {
            SafetyReport.Facts f = base();
            switch (i) {
                case 0: f.typesChanged = true; f.types = 840583L & ~(1L << 19); break;
                case 1: f.legChanged = true; break;
                case 2: f.lockFlag = true; break;
                case 3: f.radioBlip = true; break;
                case 4: f.airplaneTimer = true; break;
                case 5: f.hotspotBandChanged = true; break;
                case 6: f.menuRecent = true; break;
                case 7: f.secureGranted = true; break;
                case 8: f.pairingKept = true; break;
                default: f.bootAssist = true; break;
            }
            String r = SafetyReport.report(f);
            check(r.contains("Still changed by LinkMeter") && !r.contains("nothing changed on this phone"), "SAFE: a change is never reported as 'nothing' [" + names[i] + "]: " + r);
            check(SafetyReport.changes(f).size() == 1, "exactly one line for one change [" + names[i] + "]");
            if (i < 7) {
                check(r.contains("Restore everything puts it back"), "and says how to put it back [" + names[i] + "]");
                check(!r.contains("Undo the setup"), "a network change does not talk about the boot setup [" + names[i] + "]");
            } else {
                check(r.contains("Undo the setup") && r.contains("Restore everything leaves them alone"), "a boot item says how to undo it, and that Restore everything does not [" + names[i] + "]");
                check(!r.contains("Restore everything puts it back") && !r.contains("safety net"), "and promises neither a restore nor a safety net, which do not apply [" + names[i] + "]");
                check(!SafetyReport.radioChanged(f), "a boot item is not a radio change [" + names[i] + "]");
            }
        }
        {
            SafetyReport.Facts f = base();
            f.typesChanged = true;
            f.types = 840583L & ~(1L << 19);
            check(SafetyReport.report(f).contains("(5G is off)"), "5G off is named when the types were changed");
            // the two memories an older version may have left are said to be from an older version (this one never sets them)
            SafetyReport.Facts g = base();
            g.legChanged = true;
            check(SafetyReport.changes(g).get(0).contains("older version"), "the 5G leg note names the older version");
            g = base();
            g.lockFlag = true;
            check(SafetyReport.changes(g).get(0).contains("older version"), "the band lock note names the older version");
            check(SafetyReport.radioChanged(g) && SafetyReport.otherChanged(g), "both are something the safety net and Restore everything clear");
        }

        // ---- the safety net line
        {
            SafetyReport.Facts f = base();
            f.lockFlag = true;
            f.guardWatching = true;
            check(SafetyReport.report(f).contains("The safety net is watching"), "it says when the safety net is watching");
            f.guardWatching = false;
            check(SafetyReport.report(f).contains("The safety net is not running right now"), "and when it is not");
            SafetyReport.Facts g = base();
            g.hotspotBandChanged = true; // does not affect mobile data: no safety-net line
            check(!SafetyReport.report(g).contains("safety net"), "a change that cannot stop mobile data does not talk about the safety net");
            check(!SafetyReport.radioChanged(g), "and is not a radio change");
        }

        // ---- mobile data wording
        {
            SafetyReport.Facts f = base();
            f.cellular = "not working";
            check(SafetyReport.report(f).contains("NOT working right now"), "data down is said loudly");
            f.cellular = "";
            check(SafetyReport.report(f).contains("Mobile data: cannot tell"), "unknown is said as unknown");
            f.dataEnabled = Boolean.FALSE;
            check(SafetyReport.report(f).contains("switched off on the phone (by you)"), "data switched off by the user");
            f.dataEnabled = Boolean.TRUE;
            f.airplaneOn = Boolean.TRUE;
            check(SafetyReport.report(f).contains("airplane mode is on"), "airplane mode");
            f.types = -1;
            check(SafetyReport.report(f).contains("Network types: cannot read"), "unreadable values are said as such");
        }

        // ---- every combination of the change flags: never 'nothing' with a change, never a change from nothing
        int combos = 0;
        for (int m = 0; m < (1 << 11); m++) {
            SafetyReport.Facts f = base();
            f.typesChanged = (m & 1) != 0;
            f.legChanged = (m & 2) != 0;
            f.lockFlag = (m & 4) != 0;
            f.radioBlip = (m & 8) != 0;
            f.airplaneTimer = (m & 16) != 0;
            f.hotspotBandChanged = (m & 32) != 0;
            f.menuRecent = (m & 64) != 0;
            f.batterySaver = (m & 128) != 0;
            f.secureGranted = (m & 256) != 0;
            f.pairingKept = (m & 512) != 0;
            f.bootAssist = (m & 1024) != 0;
            f.guardWatching = (m & 1) != 0;
            boolean any = m != 0;
            String r = SafetyReport.report(f);
            combos++;
            check(any == r.contains("Still changed by LinkMeter"), "a change is reported exactly when there is one [mask " + m + "]");
            check(any != r.contains("nothing changed on this phone"), "'nothing changed' exactly when there is nothing [mask " + m + "]");
            check(SafetyReport.changes(f).size() == Integer.bitCount(m), "one line per change [mask " + m + "]");
            check(r.contains("the battery saver is on") == ((m & 128) != 0), "the battery saver is a change exactly when it is on [mask " + m + "]");
            check(r.contains("switches the battery saver's Automatic choice off") == ((m & 128) != 0),
                    "Restore everything says it also switches the saver's Automatic choice off, exactly when the saver is on [mask " + m + "]");
            boolean boot = (m & (256 | 512 | 1024)) != 0;
            boolean other = (m & (1 | 2 | 4 | 8 | 16 | 32 | 64 | 128)) != 0;
            check(boot == r.contains("Undo the setup"), "the boot setup is mentioned exactly when one of its items is in place [mask " + m + "]");
            check(other == r.contains("Restore everything puts it back"), "Restore everything is promised exactly when there is something it puts back [mask " + m + "]");
        }
        check(combos == 2048, "all combinations tried");

        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
