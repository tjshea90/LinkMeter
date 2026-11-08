package com.tj.linkmeter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * No Android in here, so it is tested on a computer (tools/test/BandToolsTest.java). Answers the questions the Bands tab
 * could not answer before:
 * - did the phone really move to the bands that were asked for? ("The modem accepted it" only means the modem
 *   acknowledged the request; many phones acknowledge any request and then do nothing.)
 * - what is the phone using right now, in words, with every carrier and not just the first?
 * - which of several measured upload speeds is the best?
 */
final class BandCheck {
    private BandCheck() {}

    enum Verdict { OBEYED, NOT_OBEYED, UNKNOWN }

    static final class Result {
        final Verdict verdict;
        final String text;

        Result(Verdict verdict, String text) {
            this.verdict = verdict;
            this.text = text;
        }
    }

    /** "lte:5,12;nr:71" becomes {"lte": {5, 12}, "nr": {71}}. Anything that is not a number is left out. */
    static Map<String, Set<Integer>> parse(String spec) {
        Map<String, Set<Integer>> out = new HashMap<>();
        if (spec == null) {
            return out;
        }
        for (String part : spec.split(";")) {
            String[] kv = part.split(":");
            if (kv.length != 2 || kv[0].trim().isEmpty()) {
                continue;
            }
            Set<Integer> bands = out.computeIfAbsent(kv[0].trim().toLowerCase(Locale.US), k -> new HashSet<>());
            for (String b : kv[1].split(",")) {
                try {
                    bands.add(Integer.parseInt(b.trim()));
                } catch (NumberFormatException ignored) {
                    // not a band
                }
            }
        }
        return out;
    }

    private static String names(List<RadioState.Carrier> carriers) {
        StringBuilder sb = new StringBuilder();
        for (RadioState.Carrier c : carriers) {
            sb.append(sb.length() > 0 ? ", " : "").append(c.bandName());
        }
        return sb.toString();
    }

    /** "B13" or "B13, B5" or "n77" for the bands of a lock. */
    private static String lockNames(Map<String, Set<Integer>> m) {
        StringBuilder sb = new StringBuilder();
        for (String rat : new String[] {"lte", "nr"}) {
            Set<Integer> s = m.get(rat);
            if (s == null) {
                continue;
            }
            for (int b : new java.util.TreeSet<>(s)) {
                sb.append(sb.length() > 0 ? ", " : "").append(rat.equals("nr") ? "n" : "B").append(b);
            }
        }
        return sb.toString();
    }

    /** Did the phone move to what was asked for? spec is the lock ("lte:13"), carriers what the radio is using now. */
    static Result verify(String spec, List<RadioState.Carrier> carriers) {
        Map<String, Set<Integer>> want = parse(spec);
        if (want.isEmpty()) {
            return new Result(Verdict.OBEYED, "No lock was asked for.");
        }
        if (carriers == null || carriers.isEmpty()) {
            return new Result(Verdict.UNKNOWN, "This phone does not list the carriers it is using, so whether it obeyed "
                    + "cannot be checked this way. Judge by the upload test.");
        }
        boolean outside = false;
        boolean unknownBand = false;
        boolean nrOn = false;
        for (RadioState.Carrier c : carriers) {
            Set<Integer> allowed = want.get(c.nr() ? "nr" : c.rat.toLowerCase(Locale.US));
            if (c.nr()) {
                nrOn = true;
            }
            if (allowed == null || allowed.isEmpty()) {
                continue; // nothing was asked about this kind of carrier
            }
            if (c.band <= 0) {
                unknownBand = true;
            } else if (!allowed.contains(c.band)) {
                outside = true;
            }
        }
        if (outside) {
            return new Result(Verdict.NOT_OBEYED, "You asked for " + lockNames(want) + " but the phone is on "
                    + names(carriers) + ". The modem acknowledged the request and then did not obey it.");
        }
        if (unknownBand) {
            return new Result(Verdict.UNKNOWN, "The phone is using " + names(carriers) + ", and one carrier's band "
                    + "is not shown, so it cannot be told whether the lock held.");
        }
        String t = "The phone is on " + names(carriers) + ", inside what you asked for (" + lockNames(want) + ").";
        if (nrOn && !want.containsKey("nr")) {
            t += " 5G is still on: switch 5G off for a pure 4G test.";
        }
        return new Result(Verdict.OBEYED, t);
    }

    /** The text for "Now using": the phone's label, then every carrier, the primary one first. */
    static String describe(RadioState s) {
        StringBuilder sb = new StringBuilder();
        if (!s.display.isEmpty()) {
            sb.append(RadioState.displayName(s.display)).append('\n');
        }
        if (s.carriers.isEmpty()) {
            sb.append("The phone does not list the carriers it is using.");
            if (s.ca) {
                sb.append(" It does say it combines carriers (carrier aggregation)");
                if (!s.bandwidths.isEmpty()) {
                    sb.append(": ").append(s.bandwidths.size()).append(" of ");
                    for (int i = 0; i < s.bandwidths.size(); i++) {
                        sb.append(i > 0 ? " + " : "").append(s.bandwidths.get(i) / 1000);
                    }
                    sb.append(" MHz");
                }
                sb.append('.');
            }
            return sb.toString().trim();
        }
        for (RadioState.Carrier c : s.carriers) {
            sb.append(c.primary ? "★ " : "  ").append(c.nr() ? "5G " : c.rat + " ");
            sb.append(String.format(Locale.US, "%-4s", c.bandName()));
            if (c.channel >= 0) {
                sb.append("  ch ").append(c.channel);
            }
            if (c.bwKhz > 0) {
                sb.append("  ").append(c.bwKhz / 1000).append(" MHz");
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /** Index of the largest value that is at least 0 (a failed test is -1), or -1 if there is none. First one wins a tie. */
    static int best(double[] values) {
        int best = -1;
        for (int i = 0; i < values.length; i++) {
            if (values[i] >= 0 && (best < 0 || values[i] > values[best])) {
                best = i;
            }
        }
        return best;
    }
}
