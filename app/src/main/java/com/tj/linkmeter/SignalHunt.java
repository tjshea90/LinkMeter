package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * No Android in here (tested in tools/test/SignalHuntTest.java). The maths behind "find the best spot": the phone is moved around
 * (a window, higher up, outside, the case off) while the tablet shows how good its signal is for UPLOAD.
 *
 * Why not just the dBm: in 5G UW the upload rides on the 4G cell (the anchor), whose signal-to-noise ratio (SINR) says more about
 * the upload than its power does, and the 5G cell's power says nothing about how weak the upload is. Readings jitter by several dB
 * from one second to the next, so the score uses the average of the last 10 seconds. A spot can be marked and compared later.
 */
final class SignalHunt {
    /** "The phone did not report it." */
    static final int NA = Integer.MIN_VALUE;
    /** How long a reading counts. */
    static final long WINDOW_MS = 10_000;
    /** The most readings kept (a minute at one per second, with room). */
    private static final int KEEP = 120;
    /** The most spots kept. */
    static final int MAX_SPOTS = 20;

    /** One reading from the phone. Values the phone did not report are NA. */
    static final class Sample {
        final long t;
        final int lteRsrp;
        final int lteRsrq;
        final int lteSinr;
        final int lteCqi;
        final int nrRsrp;
        final int nrSinr;

        Sample(long t, int lteRsrp, int lteRsrq, int lteSinr, int lteCqi, int nrRsrp, int nrSinr) {
            this.t = t;
            this.lteRsrp = lteRsrp;
            this.lteRsrq = lteRsrq;
            this.lteSinr = lteSinr;
            this.lteCqi = lteCqi;
            this.nrRsrp = nrRsrp;
            this.nrSinr = nrSinr;
        }
    }

    /** Power of a cell in dBm: -140 to -40 is real. Android's "unavailable" (Integer.MAX_VALUE) and anything else is NA. */
    static int rsrp(int v) {
        return v >= -140 && v <= -40 ? v : NA;
    }

    static int rsrq(int v) {
        return v >= -43 && v <= 20 ? v : NA;
    }

    /**
     * Signal-to-noise ratio in dB. Android documents -200..+300 in tenths of a dB at the radio and most phones hand over whole dB
     * (-20..+30); a value that cannot be dB is read as tenths. Anything else is NA.
     */
    static int sinr(int v) {
        if (v >= -30 && v <= 40) {
            return v;
        }
        if (v >= -300 && v <= 400) {
            return Math.round(v / 10f);
        }
        return NA;
    }

    static int cqi(int v) {
        return v >= 0 && v <= 15 ? v : NA;
    }

    /** Average of the readings in a window. A metric nobody reported stays NA. */
    static final class Avg {
        int count;
        int lteRsrp = NA;
        int lteRsrq = NA;
        int lteSinr = NA;
        int lteCqi = NA;
        int nrRsrp = NA;
        int nrSinr = NA;
    }

    private final List<Sample> samples = new ArrayList<>();
    private final List<Spot> spots = new ArrayList<>();
    private int bestScore = -1;
    private long bestAt = -1;

    void add(Sample s) {
        samples.add(s);
        while (samples.size() > KEEP) {
            samples.remove(0);
        }
        Avg a = average(s.t, WINDOW_MS);
        int sc = score(a);
        if (a.count >= 5 && sc > bestScore) {
            bestScore = sc;
            bestAt = s.t;
        }
    }

    Sample last() {
        return samples.isEmpty() ? null : samples.get(samples.size() - 1);
    }

    int bestScore() {
        return bestScore;
    }

    long bestAt() {
        return bestAt;
    }

    void reset() {
        samples.clear();
        spots.clear();
        bestScore = -1;
        bestAt = -1;
    }

    private static int mean(long sum, int n) {
        return n == 0 ? NA : (int) Math.round((double) sum / n);
    }

    /** The average of the readings taken in the windowMs before nowMs (inclusive). */
    Avg average(long nowMs, long windowMs) {
        Avg a = new Avg();
        long[] sum = new long[6];
        int[] n = new int[6];
        for (Sample s : samples) {
            if (s.t > nowMs || s.t < nowMs - windowMs) {
                continue;
            }
            a.count++;
            int[] v = {s.lteRsrp, s.lteRsrq, s.lteSinr, s.lteCqi, s.nrRsrp, s.nrSinr};
            for (int i = 0; i < v.length; i++) {
                if (v[i] != NA) {
                    sum[i] += v[i];
                    n[i]++;
                }
            }
        }
        a.lteRsrp = mean(sum[0], n[0]);
        a.lteRsrq = mean(sum[1], n[1]);
        a.lteSinr = mean(sum[2], n[2]);
        a.lteCqi = mean(sum[3], n[3]);
        a.nrRsrp = mean(sum[4], n[4]);
        a.nrSinr = mean(sum[5], n[5]);
        return a;
    }

    private static double clamp01(double x) {
        return x < 0 ? 0 : x > 1 ? 1 : x;
    }

    /**
     * How good this is for upload, 0 to 100, or -1 when there is nothing to judge by. The 4G anchor decides: its power (-125 dBm is
     * worth 0, -80 dBm is worth 100) and its SINR (-5 dB is 0, +20 dB is 100) count half each, or whichever there is. With no 4G
     * reading at all the 5G cell's power stands in for it, which is a worse guide.
     */
    static int score(Avg a) {
        double p = a.lteRsrp != NA ? clamp01((a.lteRsrp + 125) / 45.0) : -1;
        double q = a.lteSinr != NA ? clamp01((a.lteSinr + 5) / 25.0) : -1;
        double s;
        if (p >= 0 && q >= 0) {
            s = (p + q) / 2;
        } else if (p >= 0) {
            s = p;
        } else if (q >= 0) {
            s = q;
        } else if (a.nrRsrp != NA) {
            s = clamp01((a.nrRsrp + 125) / 45.0);
        } else {
            return -1;
        }
        return (int) Math.round(s * 100);
    }

    /** The pitch of the beep for a score: 220 Hz (poor) to 1100 Hz (good); 0 = no beep. */
    static int toneHz(int score) {
        if (score < 0) {
            return 0;
        }
        return 220 + (int) Math.round(880 * Math.min(100, score) / 100.0);
    }

    /** "up", "down" or "same": the last three seconds against the three before them. */
    String trend(long nowMs) {
        Avg now = average(nowMs, 3000);
        Avg before = average(nowMs - 3000, 3000);
        int a = score(now);
        int b = score(before);
        if (now.count < 2 || before.count < 2 || a < 0 || b < 0) {
            return "same";
        }
        return a - b >= 4 ? "up" : b - a >= 4 ? "down" : "same";
    }

    // ------------------------------------------------------------ spots

    /** A place the phone was held, with its average reading. */
    static final class Spot {
        final String label;
        final Avg avg;
        final int score;

        Spot(String label, Avg avg, int score) {
            this.label = label;
            this.avg = avg;
            this.score = score;
        }

        String line(boolean best) {
            return (best ? "★ " : "  ") + label + ": " + describe(avg) + (score >= 0 ? String.format(Locale.US, "  score %d", score) : "");
        }
    }

    /** Marks the place the phone is in now: the average of the last 10 seconds. Returns null if there is nothing to mark. */
    Spot mark(String label, long nowMs) {
        Avg a = average(nowMs, WINDOW_MS);
        if (a.count == 0) {
            return null;
        }
        String l = label == null ? "" : label.trim();
        if (l.length() > 24) {
            l = l.substring(0, 24);
        }
        if (l.isEmpty()) {
            l = "spot " + (spots.size() + 1);
        }
        Spot s = new Spot(l, a, score(a));
        spots.add(s);
        while (spots.size() > MAX_SPOTS) {
            spots.remove(0);
        }
        return s;
    }

    List<Spot> spots() {
        return new ArrayList<>(spots);
    }

    /** Index of the spot with the best score (the first one on a tie), or -1. */
    int bestSpot() {
        int best = -1;
        for (int i = 0; i < spots.size(); i++) {
            if (spots.get(i).score >= 0 && (best < 0 || spots.get(i).score > spots.get(best).score)) {
                best = i;
            }
        }
        return best;
    }

    // ------------------------------------------------------------ the beep

    /**
     * 16-bit mono samples of a sine tone: hz, rate samples per second, ms long, with a short fade in and out so it does not click.
     * Empty for a pitch of 0 (no reading) or a pitch the rate cannot play.
     */
    static short[] tone(int hz, int rate, int ms) {
        if (hz <= 0 || rate <= 0 || ms <= 0 || hz * 2 > rate) {
            return new short[0];
        }
        int n = (int) ((long) rate * ms / 1000);
        short[] b = new short[n];
        int fade = Math.max(1, Math.min(n / 2, rate / 100)); // 10 ms
        for (int i = 0; i < n; i++) {
            double env = i < fade ? (double) i / fade : (n - 1 - i) < fade ? (double) (n - 1 - i) / fade : 1.0;
            b[i] = (short) Math.round(Math.sin(2 * Math.PI * hz * i / rate) * 12000 * env);
        }
        return b;
    }

    // ------------------------------------------------------------ words

    private static String v(int x, String unit) {
        return x == NA ? "-" : x + unit;
    }

    /** "4G -104 dBm, SINR 4 dB, RSRQ -12, CQI 6 | 5G -112 dBm, SINR -3 dB". */
    static String describe(Avg a) {
        StringBuilder sb = new StringBuilder("4G ").append(v(a.lteRsrp, " dBm")).append(", SINR ").append(v(a.lteSinr, " dB"))
                .append(", RSRQ ").append(v(a.lteRsrq, ""));
        if (a.lteCqi != NA) {
            sb.append(", CQI ").append(a.lteCqi);
        }
        if (a.nrRsrp != NA || a.nrSinr != NA) {
            sb.append(" | 5G ").append(v(a.nrRsrp, " dBm")).append(", SINR ").append(v(a.nrSinr, " dB"));
        }
        return sb.toString();
    }

    /** What is said about a score. */
    static String rating(int score) {
        if (score < 0) {
            return "no reading";
        }
        return score >= 75 ? "good" : score >= 50 ? "fair" : score >= 25 ? "weak" : "very weak";
    }
}
