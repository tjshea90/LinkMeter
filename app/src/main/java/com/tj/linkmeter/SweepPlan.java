package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * No Android in here (tested in tools/test/BandToolsTest.java). The list of things the Bands tab tries, one after the other,
 * when it looks for the best upload: first how the phone is now, then locks to single bands. 5G is switched off only once,
 * in the middle, so there is one switch there and one back at the end, not one per step.
 */
final class SweepPlan {
    private SweepPlan() {}

    /** One thing to try. fiveG: TRUE = 5G must be on for it, FALSE = off, null = leave as it is. */
    static final class Step {
        final String label;
        /** "lte:13", "nr:77", or "" for no lock. */
        final String spec;
        final Boolean fiveG;

        Step(String label, String spec, Boolean fiveG) {
            this.label = label;
            this.spec = spec;
            this.fiveG = fiveG;
        }
    }

    /** The most steps one run will have (each takes about half a minute). */
    static final int MAX_STEPS = 14;

    /** "13, 5 66" becomes [13, 5, 66]. Junk, duplicates, and numbers outside 1..300 are dropped; at most max are kept. */
    static int[] parseBands(String text, int max) {
        Set<Integer> out = new LinkedHashSet<>();
        if (text != null) {
            for (String t : text.split("[^0-9]+")) {
                if (t.isEmpty() || t.length() > 3) {
                    continue;
                }
                int b = Integer.parseInt(t);
                if (b >= 1 && b <= 300 && out.size() < max) {
                    out.add(b);
                }
            }
        }
        int[] a = new int[out.size()];
        int i = 0;
        for (int b : out) {
            a[i++] = b;
        }
        return a;
    }

    static List<Step> build(int[] lteBands, int[] nrBands, boolean tryFiveGOff) {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step("As it is now", "", null));
        for (int b : nrBands) {
            steps.add(new Step("5G band n" + b, "nr:" + b, Boolean.TRUE));
        }
        if (tryFiveGOff) {
            steps.add(new Step("5G off, no lock", "", Boolean.FALSE));
        }
        for (int b : lteBands) {
            steps.add(new Step(tryFiveGOff ? "4G band B" + b + ", 5G off" : "4G band B" + b, "lte:" + b,
                    tryFiveGOff ? Boolean.FALSE : null));
        }
        while (steps.size() > MAX_STEPS) {
            steps.remove(steps.size() - 1);
        }
        return steps;
    }
}
