package com.tj.linkmeter;

import java.util.List;
import java.util.Random;

/**
 * Offline test of SignalHunt (the maths of "find the best spot"). Run by tools/test.sh. Prints "N checks, M failed".
 * What matters: a better signal never scores lower, a reading the phone did not give is never taken for a number, the average is
 * over the last 10 seconds only, and nothing throws on any input.
 */
public final class SignalHuntTest {
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

    static final int NA = SignalHunt.NA;

    static SignalHunt.Sample s(long t, int rsrp, int sinr) {
        return new SignalHunt.Sample(t, rsrp, -12, sinr, 6, NA, NA);
    }

    static SignalHunt.Avg avg(int rsrp, int sinr, int nrRsrp) {
        SignalHunt.Avg a = new SignalHunt.Avg();
        a.count = 1;
        a.lteRsrp = rsrp;
        a.lteSinr = sinr;
        a.nrRsrp = nrRsrp;
        return a;
    }

    public static void main(String[] args) {
        // ---- what the phone says, made into numbers
        check(SignalHunt.rsrp(-104) == -104 && SignalHunt.rsrp(Integer.MAX_VALUE) == NA && SignalHunt.rsrp(0) == NA
                && SignalHunt.rsrp(-141) == NA && SignalHunt.rsrp(-140) == -140 && SignalHunt.rsrp(-40) == -40 && SignalHunt.rsrp(-39) == NA, "RSRP range");
        check(SignalHunt.rsrq(-12) == -12 && SignalHunt.rsrq(Integer.MAX_VALUE) == NA && SignalHunt.rsrq(-44) == NA && SignalHunt.rsrq(21) == NA, "RSRQ range");
        check(SignalHunt.sinr(4) == 4 && SignalHunt.sinr(-12) == -12 && SignalHunt.sinr(30) == 30, "whole dB stay");
        check(SignalHunt.sinr(55) == 6 && SignalHunt.sinr(-150) == -15 && SignalHunt.sinr(120) == 12, "tenths of a dB are turned into dB: " + SignalHunt.sinr(55));
        check(SignalHunt.sinr(Integer.MAX_VALUE) == NA && SignalHunt.sinr(1000) == NA && SignalHunt.sinr(-1000) == NA, "SINR out of range is NA");
        check(SignalHunt.cqi(0) == 0 && SignalHunt.cqi(15) == 15 && SignalHunt.cqi(16) == NA && SignalHunt.cqi(Integer.MAX_VALUE) == NA && SignalHunt.cqi(-1) == NA, "CQI range");

        // ---- the score
        check(SignalHunt.score(avg(-125, -5, NA)) == 0, "the worst is 0");
        check(SignalHunt.score(avg(-80, 20, NA)) == 100, "the best is 100");
        check(SignalHunt.score(avg(-150, -30, NA)) == 0 && SignalHunt.score(avg(-40, 50, NA)) == 100, "clamped");
        check(SignalHunt.score(avg(-104, NA, NA)) == Math.round((21 / 45.0) * 100), "power only");
        check(SignalHunt.score(avg(NA, 4, NA)) == Math.round((9 / 25.0) * 100), "SINR only");
        check(SignalHunt.score(avg(NA, NA, -110)) == Math.round((15 / 45.0) * 100), "with no 4G reading the 5G cell stands in");
        check(SignalHunt.score(avg(NA, NA, NA)) == -1, "nothing to judge by");
        check(SignalHunt.score(avg(-104, 4, -80)) == SignalHunt.score(avg(-104, 4, NA)), "a strong 5G cell does not lift the score of a weak 4G anchor");
        for (int p = -130; p < -75; p++) {
            check(SignalHunt.score(avg(p + 1, 4, NA)) >= SignalHunt.score(avg(p, 4, NA)), "more power never scores lower at " + p);
        }
        for (int q = -10; q < 25; q++) {
            check(SignalHunt.score(avg(-100, q + 1, NA)) >= SignalHunt.score(avg(-100, q, NA)), "more SINR never scores lower at " + q);
        }
        check(SignalHunt.rating(-1).equals("no reading") && SignalHunt.rating(10).equals("very weak") && SignalHunt.rating(30).equals("weak")
                && SignalHunt.rating(60).equals("fair") && SignalHunt.rating(90).equals("good"), "ratings");

        // ---- the tone
        check(SignalHunt.toneHz(-1) == 0, "no beep without a reading");
        check(SignalHunt.toneHz(0) == 220 && SignalHunt.toneHz(100) == 1100 && SignalHunt.toneHz(500) == 1100, "pitch range");
        for (int i = 0; i < 100; i++) {
            check(SignalHunt.toneHz(i + 1) >= SignalHunt.toneHz(i), "a better score never sounds lower at " + i);
        }

        // ---- the beep samples
        {
            short[] b = SignalHunt.tone(440, 8000, 150);
            check(b.length == 1200, "150 ms at 8000 Hz is 1200 samples: " + b.length);
            int crossings = 0;
            int peak = 0;
            for (int i = 1; i < b.length; i++) {
                if ((b[i - 1] < 0) != (b[i] < 0)) {
                    crossings++;
                }
                peak = Math.max(peak, Math.abs(b[i]));
            }
            // a 440 Hz tone for 0.15 s crosses zero about 2 x 440 x 0.15 = 132 times
            check(crossings >= 120 && crossings <= 140, "the pitch is right (zero crossings): " + crossings);
            check(peak <= 12000 && peak > 11000, "the volume is moderate and not clipped: " + peak);
            check(Math.abs(b[0]) <= 200 && Math.abs(b[b.length - 1]) <= 200, "it fades in and out (no click): " + b[0] + " " + b[b.length - 1]);
            check(SignalHunt.tone(0, 8000, 150).length == 0 && SignalHunt.tone(440, 0, 150).length == 0 && SignalHunt.tone(440, 8000, 0).length == 0,
                    "no tone for no pitch, no rate, no time");
            check(SignalHunt.tone(5000, 8000, 150).length == 0, "a pitch the rate cannot play gives nothing");
            for (int hz = 220; hz <= 1100; hz += 110) {
                short[] t2 = SignalHunt.tone(hz, 8000, 150);
                int cr = 0;
                for (int i = 1; i < t2.length; i++) {
                    if ((t2[i - 1] < 0) != (t2[i] < 0)) {
                        cr++;
                    }
                }
                double expect = 2.0 * hz * 0.15;
                check(Math.abs(cr - expect) <= expect * 0.12 + 3, "the pitch is right at " + hz + " Hz: " + cr + " crossings, expected about " + expect);
            }
        }

        // ---- the window
        {
            SignalHunt h = new SignalHunt();
            for (int i = 0; i < 30; i++) {
                h.add(s(i * 1000L, i < 20 ? -120 : -100, i < 20 ? -3 : 8)); // bad for 20 s, then good
            }
            SignalHunt.Avg last10 = h.average(29_000, SignalHunt.WINDOW_MS);
            check(last10.count == 11 && last10.lteRsrp == -102, "the last 10 s: one bad reading and ten good ones average -102: " + last10.lteRsrp + " (" + last10.count + " readings)");
            SignalHunt.Avg old = h.average(10_000, SignalHunt.WINDOW_MS);
            check(old.lteRsrp == -120, "an earlier window is all bad: " + old.lteRsrp);
            check(h.average(100_000, SignalHunt.WINDOW_MS).count == 0 && h.average(100_000, SignalHunt.WINDOW_MS).lteRsrp == NA, "an empty window has no numbers");
            check(h.last().t == 29_000, "the last reading");
            check(h.bestScore() >= 0 && h.bestAt() >= 0, "a best was kept");
            check(h.bestScore() == SignalHunt.score(h.average(29_000, SignalHunt.WINDOW_MS)) || h.bestScore() >= SignalHunt.score(h.average(29_000, SignalHunt.WINDOW_MS)),
                    "the best is at least the latest");
            h.reset();
            check(h.last() == null && h.bestScore() == -1 && h.spots().isEmpty(), "reset forgets everything");
        }
        // missing values are skipped, not counted as zero
        {
            SignalHunt h = new SignalHunt();
            h.add(new SignalHunt.Sample(0, -100, NA, NA, NA, NA, NA));
            h.add(new SignalHunt.Sample(1000, -110, NA, 4, NA, NA, NA));
            SignalHunt.Avg a = h.average(1000, 10_000);
            check(a.lteRsrp == -105 && a.lteSinr == 4 && a.lteRsrq == NA && a.lteCqi == NA && a.count == 2, "NA is left out of the average: " + a.lteRsrp + " " + a.lteSinr);
        }
        // a hundred and fifty readings: only the recent ones are kept
        {
            SignalHunt h = new SignalHunt();
            for (int i = 0; i < 400; i++) {
                h.add(s(i * 100L, -100, 5));
            }
            check(h.average(39_900, 1_000_000).count <= 120, "old readings are dropped: " + h.average(39_900, 1_000_000).count);
        }

        // ---- the trend
        {
            SignalHunt h = new SignalHunt();
            for (int i = 0; i < 6; i++) {
                h.add(s(i * 1000L, i < 3 ? -118 : -96, i < 3 ? -2 : 10));
            }
            check(h.trend(5_000).equals("up"), "getting better: " + h.trend(5_000));
            SignalHunt g = new SignalHunt();
            for (int i = 0; i < 6; i++) {
                g.add(s(i * 1000L, i < 3 ? -96 : -118, i < 3 ? 10 : -2));
            }
            check(g.trend(5_000).equals("down"), "getting worse: " + g.trend(5_000));
            SignalHunt f = new SignalHunt();
            for (int i = 0; i < 6; i++) {
                f.add(s(i * 1000L, -100, 5));
            }
            check(f.trend(5_000).equals("same"), "steady");
            check(new SignalHunt().trend(0).equals("same"), "no readings: same");
        }

        // ---- spots
        {
            SignalHunt h = new SignalHunt();
            check(h.mark("x", 0) == null, "nothing to mark without a reading");
            for (int i = 0; i < 12; i++) {
                h.add(s(i * 1000L, -115, 0));
            }
            SignalHunt.Spot a = h.mark("  by the door  ", 11_000);
            check(a != null && a.label.equals("by the door") && a.avg.lteRsrp == -115, "a spot has the average of the last 10 s and a trimmed label");
            for (int i = 12; i < 24; i++) {
                h.add(s(i * 1000L, -92, 12));
            }
            SignalHunt.Spot b = h.mark("", 23_000);
            check(b != null && b.label.equals("spot 2"), "an empty label gets a number: " + (b == null ? null : b.label));
            SignalHunt.Spot c = h.mark("123456789012345678901234567890", 23_000);
            check(c.label.length() == 24, "a long label is cut");
            check(h.spots().size() == 3 && h.bestSpot() == 1, "the best spot is found: " + h.bestSpot());
            check(h.spots().get(1).line(true).startsWith("★ spot 2") && h.spots().get(0).line(false).startsWith("  by the door"), "the lines mark the best");
            check(b.score > a.score, "the better place scores higher");
            for (int i = 0; i < 40; i++) {
                h.mark("k" + i, 23_000);
            }
            check(h.spots().size() == SignalHunt.MAX_SPOTS, "at most " + SignalHunt.MAX_SPOTS + " spots");
        }

        // ---- words
        {
            SignalHunt.Avg a = avg(-104, 4, -112);
            a.lteRsrq = -12;
            a.lteCqi = 6;
            a.nrSinr = -3;
            String d = SignalHunt.describe(a);
            check(d.equals("4G -104 dBm, SINR 4 dB, RSRQ -12, CQI 6 | 5G -112 dBm, SINR -3 dB"), "describe: " + d);
            check(SignalHunt.describe(new SignalHunt.Avg()).equals("4G -, SINR -, RSRQ -"), "describe with nothing: " + SignalHunt.describe(new SignalHunt.Avg()));
        }

        // ---- anything goes in, nothing throws, numbers stay in range
        Random g = new Random(99);
        for (int run = 0; run < 300; run++) {
            SignalHunt h = new SignalHunt();
            long t = 0;
            int n = g.nextInt(60);
            int lo = Integer.MAX_VALUE;
            int hi = Integer.MIN_VALUE;
            for (int i = 0; i < n; i++) {
                t += g.nextInt(3000);
                int rsrp = g.nextInt(4) == 0 ? NA : -140 + g.nextInt(100);
                int sinr = g.nextInt(4) == 0 ? NA : -20 + g.nextInt(50);
                if (rsrp != NA) {
                    lo = Math.min(lo, rsrp);
                    hi = Math.max(hi, rsrp);
                }
                h.add(new SignalHunt.Sample(t, rsrp, NA, sinr, NA, g.nextInt(3) == 0 ? NA : -140 + g.nextInt(100), NA));
                SignalHunt.Avg a = h.average(t, SignalHunt.WINDOW_MS);
                int sc = SignalHunt.score(a);
                check(sc >= -1 && sc <= 100, "score in range");
                check(a.lteRsrp == NA || (a.lteRsrp >= -140 && a.lteRsrp <= -40), "average in range");
                check(SignalHunt.toneHz(sc) >= 0 && SignalHunt.toneHz(sc) <= 1100, "tone in range");
                check(h.trend(t) != null, "trend never null");
            }
            SignalHunt.Spot sp = h.mark(null, t);
            check(sp == null || sp.label.length() > 0, "a spot always has a label");
            if (n > 0 && lo != Integer.MAX_VALUE) {
                SignalHunt.Avg all = h.average(t, Long.MAX_VALUE / 4);
                check(all.lteRsrp == NA || (all.lteRsrp >= lo && all.lteRsrp <= hi), "an average lies between the smallest and largest reading");
            }
        }

        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
