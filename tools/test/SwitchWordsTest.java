package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline test of SwitchWords: what the phone answers after it was asked to move a switch. The rule that must never break: the
 * phone says "is on", "is off" or "was restarted" only when its helper said OK; a switch that did not move is always a failure
 * (the tablet then gets an error, not "done"); and "asked, but the phone does not report the state" claims neither.
 * Run by tools/test.sh. Prints "N checks, M failed". ASCII only.
 */
public final class SwitchWordsTest {
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

    static final String[] OK_LINES = {"OK the hotspot is on", "OK the hotspot is off (it already was)", "OK the hotspot is back on", "OK", "  OK the hotspot is off  "};
    static final String[] UNSURE_LINES = {"UNSURE the phone took the request to switch the hotspot on; this phone does not report the hotspot's state here",
        "UNSURE the hotspot was asked to stop; this phone does not report the hotspot's state here", "UNSURE"};
    /** Everything that is not a clear "done": the helper's own errors, a crash, a killed process, nothing at all, look-alikes. */
    static final String[] BAD_LINES = {"ERR the hotspot failed to start (code 2); switch it on by hand on the phone", "ERR no answer when starting the hotspot; check the phone",
        "ERR the hotspot did not switch off (it is still on)", "ERR java.lang.SecurityException: not allowed", "ERR", "", "Killed", "OKAY", "OK.", "ok the hotspot is on",
        "Error: OK", "the hotspot is on"};
    /** Lines that only look like a verdict. They are failures too (their text is passed on as the phone's reason, word for word). */
    static final String[] LOOK_ALIKES = {"UNSURELY", "ERROR OK the hotspot is on", "OKthe hotspot is on", "ERRthe hotspot is on", "UNSUREthe hotspot"};

    /** The claims that may only follow an OK. */
    static boolean claims(String text) {
        return text.contains("The hotspot is on.") || text.contains("The hotspot is off.") || text.contains("The hotspot was restarted.")
                || text.contains("The screen is off.");
    }

    /** Whole sentences, in plain words: ends with a full stop, no verdict word left in it, no doubled full stop or blank. */
    static void wellFormed(String text, String from) {
        check(text.endsWith("."), "ends as a sentence: [" + text + "] from [" + from + "]");
        check(!text.contains("UNSURE") && !text.contains("ERR ") && !text.contains(" OK ") && !text.startsWith("OK"), "no verdict word left: [" + text + "]");
        check(!text.contains("..") && !text.contains("  ") && !text.contains(" ."), "no doubled full stop or blank: [" + text + "]");
        check(Character.isUpperCase(text.charAt(0)), "starts with a capital: [" + text + "]");
    }

    static void verdictLine() {
        check(SwitchWords.resultLine("OK the hotspot is on").equals("OK the hotspot is on"), "one line");
        check(SwitchWords.resultLine("WARNING: linker: something\nOK the hotspot is on\n").equals("OK the hotspot is on"), "a warning before the verdict");
        check(SwitchWords.resultLine("OK the hotspot is on\nsome trailing noise\n").equals("OK the hotspot is on"), "noise after the verdict");
        check(SwitchWords.resultLine("OK first\nERR second").equals("ERR second"), "the last verdict counts");
        check(SwitchWords.resultLine("ERR first\r\nUNSURE second\r\n").equals("UNSURE second"), "UNSURE is a verdict; Windows line ends");
        check(SwitchWords.resultLine("Killed\n").equals("Killed"), "no verdict at all: the last line, which is then a failure");
        check(SwitchWords.resultLine("OKAY then\nnothing").equals("nothing"), "a word that merely starts with OK is not a verdict");
        check(SwitchWords.resultLine("").isEmpty() && SwitchWords.resultLine("\n\n").isEmpty(), "nothing printed: empty");
        check(SwitchWords.afterWord("OK the hotspot is on").equals("the hotspot is on") && SwitchWords.afterWord("ERR no answer").equals("no answer")
                && SwitchWords.afterWord("UNSURE asked").equals("asked"), "the verdict word is taken off");
        check(SwitchWords.afterWord("ERR").equals("the phone gave no reason") && SwitchWords.afterWord("").equals("the phone gave no reason")
                && SwitchWords.afterWord("ERR   ").equals("the phone gave no reason"), "no reason given: said so");
        check(SwitchWords.afterWord("Killed").equals("Killed"), "a line without a verdict is the reason itself");
        check(SwitchWords.dot("a").equals("a.") && SwitchWords.dot("a.").equals("a.") && SwitchWords.dot(" a? ").equals("a?") && SwitchWords.dot("a!").equals("a!"),
                "a sentence gets exactly one end");
    }

    static void hotspot() {
        for (String line : OK_LINES) {
            SwitchWords.Said on = SwitchWords.hotspotOn(line);
            SwitchWords.Said off = SwitchWords.hotspotOff(line, false);
            SwitchWords.Said offBt = SwitchWords.hotspotOff(line, true);
            SwitchWords.Said again = SwitchWords.hotspotRestart(line);
            check(on.done && on.text.equals("The hotspot is on."), "OK: on [" + line + "]");
            check(off.done && off.text.equals("The hotspot is off.") && offBt.done && offBt.text.equals(off.text), "OK: off [" + line + "]");
            check(again.done && again.text.equals("The hotspot was restarted."), "OK: restarted [" + line + "]");
        }
        for (String line : UNSURE_LINES) {
            for (SwitchWords.Said s : new SwitchWords.Said[] {SwitchWords.hotspotOn(line), SwitchWords.hotspotOff(line, false), SwitchWords.hotspotOff(line, true),
                SwitchWords.hotspotRestart(line)}) {
                check(s.done, "asked and not known is not a failure [" + line + "]");
                check(!claims(s.text), "asked and not known claims nothing: [" + s.text + "]");
                check(s.text.contains("was asked") && s.text.contains("does not say whether"), "and says exactly that: [" + s.text + "]");
                wellFormed(s.text, line);
            }
        }
        for (String line : BAD_LINES) {
            for (SwitchWords.Said s : new SwitchWords.Said[] {SwitchWords.hotspotOn(line), SwitchWords.hotspotOff(line, false), SwitchWords.hotspotOff(line, true),
                SwitchWords.hotspotRestart(line)}) {
                check(!s.done, "anything but OK or UNSURE is a failure, never 'done': [" + line + "] gave [" + s.text + "]");
                check(!claims(s.text), "a failure never claims the switch moved: [" + s.text + "]");
                check(s.text.contains("could not be"), "a failure says so: [" + s.text + "]");
                check(s.text.contains(SwitchWords.afterWord(line)), "and gives the phone's reason: [" + s.text + "]");
                wellFormed(s.text, line);
            }
            check(SwitchWords.hotspotOff(line, true).text.contains("not used over Bluetooth"), "over Bluetooth the failure says why the other way is not tried");
            check(!SwitchWords.hotspotOff(line, false).text.contains("Bluetooth"), "and not when the request came over the hotspot");
        }
        for (String line : LOOK_ALIKES) {
            for (SwitchWords.Said s : new SwitchWords.Said[] {SwitchWords.hotspotOn(line), SwitchWords.hotspotOff(line, false), SwitchWords.hotspotOff(line, true),
                SwitchWords.hotspotRestart(line)}) {
                check(!s.done && s.text.contains("could not be"), "a line that only looks like a verdict is a failure: [" + line + "] gave [" + s.text + "]");
            }
        }
        // after the airplane-mode way
        for (String direct : BAD_LINES) {
            SwitchWords.Said off = SwitchWords.hotspotOffByAirplane(direct, Boolean.TRUE);
            SwitchWords.Said still = SwitchWords.hotspotOffByAirplane(direct, Boolean.FALSE);
            SwitchWords.Said unknown = SwitchWords.hotspotOffByAirplane(direct, null);
            check(off.done && off.text.startsWith("The hotspot is off.") && off.text.contains("airplane mode"), "off after airplane mode, and it says how: [" + off.text + "]");
            check(!still.done && !claims(still.text) && still.text.contains("could not be switched off"), "still on after airplane mode: a failure [" + still.text + "]");
            check(unknown.done && !claims(unknown.text) && unknown.text.contains("does not say whether"), "not known after airplane mode: claims nothing [" + unknown.text + "]");
            for (SwitchWords.Said s : new SwitchWords.Said[] {off, still, unknown}) {
                check(s.text.contains(SwitchWords.afterWord(direct)), "the reason the direct way failed is kept: [" + s.text + "]");
                wellFormed(s.text, direct);
            }
        }
        check(SwitchWords.screenOff(true, "the power service").done && SwitchWords.screenOff(true, "x").text.equals("The screen is off."), "the screen is off");
        SwitchWords.Said lit = SwitchWords.screenOff(false, "the key was pressed twice");
        check(!lit.done && !claims(lit.text) && lit.text.contains("the key was pressed twice"), "the screen did not stay off: a failure with what was tried");
    }

    /** Standby: every combination of what became of the hotspot and of the screen, over both links. */
    static void standby() {
        List<SwitchWords.Said> hotspots = new ArrayList<>();
        for (String[] set : new String[][] {OK_LINES, UNSURE_LINES, BAD_LINES}) {
            for (String line : set) {
                hotspots.add(SwitchWords.hotspotOff(line, false));
                hotspots.add(SwitchWords.hotspotOff(line, true));
            }
        }
        for (String direct : BAD_LINES) {
            hotspots.add(SwitchWords.hotspotOffByAirplane(direct, Boolean.TRUE));
            hotspots.add(SwitchWords.hotspotOffByAirplane(direct, Boolean.FALSE));
            hotspots.add(SwitchWords.hotspotOffByAirplane(direct, null));
        }
        SwitchWords.Said[] screens = {SwitchWords.screenOff(true, "the power service"), SwitchWords.screenOff(false, "the power service refused; the key did nothing")};
        for (SwitchWords.Said h : hotspots) {
            for (SwitchWords.Said s : screens) {
                for (int bt = 0; bt < 2; bt++) {
                    SwitchWords.Said all = SwitchWords.standby(h, s, bt == 1);
                    String at = " [" + h.text + "] + [" + s.text + "] bt=" + bt;
                    check(all.done == (h.done && s.done), "Standby is done only when BOTH switches moved" + at);
                    check(all.text.contains(h.text) && all.text.contains(s.text), "the answer says what became of each" + at);
                    check(all.text.indexOf(h.text) < all.text.indexOf(s.text), "the hotspot first, then the screen" + at);
                    check(all.text.startsWith("Standby is only half done. ") == (h.done != s.done), "exactly one of the two: 'only half done'" + at);
                    check(all.text.startsWith("Standby did not work. ") == (!h.done && !s.done), "neither: 'did not work'" + at);
                    check(all.text.contains("To wake the phone: Hotspot on.") == (all.done && bt == 1),
                            "how to wake it is said only when it is on standby and was asked over Bluetooth" + at);
                    wellFormed(all.text, at);
                }
            }
        }
    }

    public static void main(String[] a) {
        verdictLine();
        hotspot();
        standby();
        System.out.println(checks + " checks, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
