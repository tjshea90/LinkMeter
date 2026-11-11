package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * No Android in here (tested in tools/test/TextsLogicTest.java). Finds the one-time code in a text such as "Your verification code is
 * 482913" so that the Texts screens can offer "Copy code". Careful on purpose: a number is only taken for a code when the words next to
 * it say so, and a telephone number, a time, an amount or a long number is never one.
 */
final class OtpCodes {
    /** The newest conversation of the list offers "Copy code" only while it is this young (a code is no use for long). */
    static final long RECENT_MS = 30 * 60_000L;
    /** A text this old no longer offers "Copy code" in a conversation. */
    static final long BUBBLE_MS = 24 * 60 * 60_000L;
    private static final int NEAR = 40;

    private OtpCodes() {}

    /** Words that say a number is a code. The short ones must stand alone ("pin" is not "pinned"). */
    private static final String[] WHOLE = {"code", "codes", "otp", "pin", "passcode", "password", "2fa"};
    private static final String[] PART = {"verif", "authentic", "security", "login", "log in", "sign in", "sign-in", "one-time", "one time", "confirm"};

    /** The code in this text, or "" when there is none. */
    static String find(String body) {
        if (body == null || body.length() < 6 || body.length() > 600) {
            return "";
        }
        String lower = body.toLowerCase(Locale.ROOT);
        List<int[]> words = words(lower);
        if (words.isEmpty()) {
            return "";
        }
        String best = "";
        int bestDistance = Integer.MAX_VALUE;
        int n = body.length();
        int i = 0;
        while (i < n) {
            if (!digit(body.charAt(i))) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && digit(body.charAt(j))) {
                j++;
            }
            int start = i;
            int end = j;
            String digits = body.substring(i, j);
            // a code written in two groups of three: 123-456 or 123 456
            if (digits.length() == 3 && j + 3 < n + 0 && (body.charAt(j) == '-' || body.charAt(j) == ' ')
                    && digit(body.charAt(j + 1)) && digit(body.charAt(j + 2)) && digit(body.charAt(j + 3))) {
                int k = j + 4;
                if (k >= n || !digit(body.charAt(k))) {
                    digits = digits + body.substring(j + 1, j + 4);
                    end = k;
                }
            }
            i = end;
            if (digits.length() < 4 || digits.length() > 8 || !standsAlone(body, start, end)) {
                continue;
            }
            int d = distance(words, start, end);
            if (d < bestDistance) {
                bestDistance = d;
                best = digits;
            }
        }
        return bestDistance <= NEAR ? best : "";
    }

    /** What the list offers for a conversation: only an unread one, and only while the code is young. */
    static String forList(String snippet, boolean unread, long time, long now) {
        if (!unread || time <= 0 || now - time > RECENT_MS || now < time) {
            return "";
        }
        return find(snippet);
    }

    /** What a conversation offers under an incoming text: only while the text is a day old at most. */
    static String forBubble(String body, boolean mine, long time, long now) {
        if (mine || time <= 0 || now - time > BUBBLE_MS || now < time) {
            return "";
        }
        return find(body);
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    /** Not part of a longer number, a time, an amount, a word or a link. */
    private static boolean standsAlone(String s, int start, int end) {
        char before = start > 0 ? s.charAt(start - 1) : ' ';
        char after = end < s.length() ? s.charAt(end) : ' ';
        if (Character.isLetter(before) || Character.isLetter(after)) {
            return false;
        }
        if ("$€£¥@/%".indexOf(before) >= 0 || after == '%') {
            return false;
        }
        if ("-.:/,+".indexOf(before) >= 0 && start >= 2 && digit(s.charAt(start - 2))) {
            return false; // 555-1234, 12:30, 1,234, 3.14
        }
        if ("-.:/,".indexOf(after) >= 0 && end + 1 < s.length() && digit(s.charAt(end + 1))) {
            return false;
        }
        return before != '+'; // +4155551234
    }

    private static List<int[]> words(String lower) {
        List<int[]> out = new ArrayList<>();
        for (String w : WHOLE) {
            for (int at = lower.indexOf(w); at >= 0; at = lower.indexOf(w, at + 1)) {
                int e = at + w.length();
                boolean left = at == 0 || !Character.isLetterOrDigit(lower.charAt(at - 1));
                boolean right = e >= lower.length() || !Character.isLetterOrDigit(lower.charAt(e));
                if (left && right) {
                    out.add(new int[] {at, e});
                }
            }
        }
        for (String w : PART) {
            for (int at = lower.indexOf(w); at >= 0; at = lower.indexOf(w, at + 1)) {
                out.add(new int[] {at, at + w.length()});
            }
        }
        return out;
    }

    private static int distance(List<int[]> words, int start, int end) {
        int best = Integer.MAX_VALUE;
        for (int[] w : words) {
            int gap = Math.max(w[0] - end, start - w[1]);
            best = Math.min(best, Math.max(gap, 0));
        }
        return best;
    }
}
