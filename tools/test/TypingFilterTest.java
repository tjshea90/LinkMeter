package com.tj.linkmeter;

import java.text.Normalizer;
import java.util.Random;

/**
 * Offline test of TypingFilter: the remote screen never types * or # (the characters every dangerous dialer code needs).
 * Run by tools/test.sh. Prints "N checks, M failed". Every one of the 65,536 characters is tried.
 */
public final class TypingFilterTest {
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

    static String hex(String s) {
        StringBuilder sb = new StringBuilder("t ");
        for (int i = 0; i < s.length(); i++) {
            sb.append(String.format("%04x", (int) s.charAt(i)));
        }
        return sb.toString();
    }

    static String unhex(String line) {
        StringBuilder sb = new StringBuilder();
        for (int i = 2; i + 4 <= line.length(); i += 4) {
            sb.append((char) Integer.parseInt(line.substring(i, i + 4), 16));
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        // ---- every character that exists
        int blocked = 0;
        for (int i = 0; i <= 0xFFFF; i++) {
            char c = (char) i;
            boolean t = TypingFilter.typable(c);
            if (!t) {
                blocked++;
            }
            String n = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFKC);
            boolean star = c == '*' || c == '#' || n.indexOf('*') >= 0 || n.indexOf('#') >= 0;
            check(!star || !t, "SAFE: " + String.format("U+%04X", i) + " becomes a * or # and must not be typable");
        }
        check(!TypingFilter.typable('*') && !TypingFilter.typable('#'), "SAFE: * and # are never typed");
        check(blocked >= 2 && blocked < 40, "and only a handful of characters are affected: " + blocked);
        // ordinary text is untouched
        for (char c = 0x20; c < 0x7F; c++) {
            if (c != '*' && c != '#') {
                check(TypingFilter.typable(c), "ordinary character kept: " + c);
            }
        }
        check(TypingFilter.typable('\n') && TypingFilter.typable('\t') && TypingFilter.typable('\u00E9') && TypingFilter.typable('\u4E2D'),
                "newline, tab, accented and other letters are kept");
        for (char c : "\uFF0A\uFF03\uFE61\uFE5F\u2217\u204E\u2731\u066D\u266F\u2317".toCharArray()) {
            check(!TypingFilter.typable(c), "SAFE: look-alike refused " + String.format("U+%04X", (int) c));
        }

        // ---- the key codes a keyboard map could use for them
        check(!TypingFilter.typableKey(17) && !TypingFilter.typableKey(18) && !TypingFilter.typableKey(155), "SAFE: star, pound and numpad-multiply keys refused");
        check(TypingFilter.typableKey(8) && TypingFilter.typableKey(66) && TypingFilter.typableKey(29), "other keys are fine");

        // ---- text
        check(TypingFilter.keep("P#ss*w0rd").equals("Pssw0rd"), "keep: " + TypingFilter.keep("P#ss*w0rd"));
        check(TypingFilter.keep("*#06#").equals("06") && TypingFilter.keep("*2767*3855#").equals("27673855"), "SAFE: a service code loses its * and #");
        check(TypingFilter.keep("*#*#4636#*#*").equals("4636"), "SAFE: the Android secret code too");
        check(TypingFilter.keep("##72786#").equals("72786"), "SAFE: the carrier reset code too");
        check(TypingFilter.keep("hello world 123").equals("hello world 123") && TypingFilter.keep("").isEmpty() && TypingFilter.keep(null).isEmpty(), "plain text and nothing");
        check(TypingFilter.keep("a\nb").equals("a\nb"), "newlines stay");
        check(TypingFilter.hasBlockedSymbol("call #1") && TypingFilter.hasBlockedSymbol("*") && !TypingFilter.hasBlockedSymbol("call 1") && !TypingFilter.hasBlockedSymbol(""),
                "the tablet can tell when a symbol was left out");

        // ---- control lines as the phone receives them
        check(TypingFilter.filterLine(hex("hello")).equals(hex("hello")), "a clean line passes unchanged");
        check(TypingFilter.filterLine(hex("a#b")).equals(hex("ab")), "# is removed from a line");
        check(TypingFilter.filterLine(hex("*2767*3855#")).equals(hex("27673855")), "SAFE: a factory-reset code can only arrive as plain digits");
        check(TypingFilter.filterLine(hex("*#")) == null && TypingFilter.filterLine(hex("#")) == null, "a line of only blocked characters is dropped");
        check(TypingFilter.filterLine("t ") == null && TypingFilter.filterLine("t 002") == null && TypingFilter.filterLine("t zzzz") == null, "malformed lines are dropped");
        check(TypingFilter.filterLine("x 0041") == null && TypingFilter.filterLine(null) == null && TypingFilter.filterLine("") == null, "other lines are not text lines");
        check(TypingFilter.filterLine("t 002A") == null && TypingFilter.filterLine("t 0023") == null, "the hex for * and # is dropped, upper case too");
        check(TypingFilter.filterLine("t 0041002a0042").equals("t 00410042"), "lower-case hex for * is dropped: " + TypingFilter.filterLine("t 0041002a0042"));
        check(TypingFilter.filterLine("t 0041002A0042").equals("t 00410042"), "upper-case hex too");
        check(TypingFilter.filterLine("t FF0A") == null, "the full-width star in hex is dropped");

        // ---- random text: nothing with a * or # ever gets through
        Random g = new Random(77);
        for (int run = 0; run < 3000; run++) {
            StringBuilder sb = new StringBuilder();
            int n = g.nextInt(60);
            for (int i = 0; i < n; i++) {
                int r = g.nextInt(6);
                sb.append(r == 0 ? "*" : r == 1 ? "#" : r == 2 ? String.valueOf((char) g.nextInt(0x10000)) : String.valueOf((char) (0x20 + g.nextInt(0x5F))));
            }
            String s = sb.toString();
            String kept = TypingFilter.keep(s);
            check(kept.indexOf('*') < 0 && kept.indexOf('#') < 0, "SAFE: no * or # survives [run " + run + "]");
            check(TypingFilter.keep(kept).equals(kept), "keeping twice changes nothing [run " + run + "]");
            String line = hex(s);
            String f = s.isEmpty() ? null : TypingFilter.filterLine(line);
            if (s.isEmpty()) {
                check(f == null, "an empty line is dropped");
            } else if (kept.isEmpty()) {
                check(f == null, "a line with nothing left is dropped [run " + run + "]");
            } else {
                check(f != null && unhex(f).equals(kept), "the filtered line says exactly the kept text [run " + run + "]");
            }
        }

        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
