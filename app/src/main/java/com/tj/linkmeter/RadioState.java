package com.tj.linkmeter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phone side, but with no Android in it so it can be tested on a computer (tools/test/BandToolsTest.java): reads what
 * the phone's radio is really using out of `dumpsys telephony.registry`.
 *
 * Why: the cell list in that dump holds the cell the phone is registered on (and a few it measured), so a scan of it shows
 * ONE band even when the phone is using several at once. With LTE carrier aggregation there are two or more LTE carriers, and
 * with 5G on Verizon there is an LTE carrier AND an NR one (non-standalone). The list of carriers in use is
 * "mPhysicalChannelConfigs", and the 5G label the phone shows ("5G UW") is "overrideNetwork=NR_ADVANCED" in
 * "mTelephonyDisplayInfos". Every field is optional: phones differ in what they print.
 */
final class RadioState {
    /** One carrier (component carrier) the radio is using right now. */
    static final class Carrier {
        /** "LTE", "NR", or whatever else the phone printed. */
        final String rat;
        /** 0 = the phone did not say and it could not be worked out. */
        final int band;
        /** Downlink channel number (EARFCN for LTE, NR-ARFCN for 5G), -1 if unknown. */
        final int channel;
        /** Bandwidth in kHz, -1 if unknown. */
        final int bwKhz;
        final boolean primary;

        Carrier(String rat, int band, int channel, int bwKhz, boolean primary) {
            this.rat = rat;
            this.band = band;
            this.channel = channel;
            this.bwKhz = bwKhz;
            this.primary = primary;
        }

        boolean nr() {
            return "NR".equals(rat);
        }

        /** "B66" or "n77". */
        String bandName() {
            return band <= 0 ? (nr() ? "n?" : "B?") : (nr() ? "n" : "B") + band;
        }
    }

    final List<Carrier> carriers = new ArrayList<>();
    /** The phone's own label for what it shows: "NR_ADVANCED" (5G UW), "NR_NSA", "LTE_CA", "NONE"... or "". */
    String display = "";
    /** isUsingCarrierAggregation, as the phone says. */
    boolean ca;
    /** The bandwidths of the aggregated carriers in kHz, as the phone says (one per carrier). */
    final List<Integer> bandwidths = new ArrayList<>();
    /** Empty if the phone did not say. */
    String nrState = "";
    /** The carrier list's own text when it was there but nothing could be read from it (so it can be looked at). */
    String rawConfigs = "";

    /** Separates the two parts of the survey's output: the cell list, then the lines this class reads. */
    static final String EXTRA_MARKER = "##EXTRA";

    /**
     * The shell command whose output Cells.survey hands to parse(). Two plain pipelines that each ask dumpsys themselves.
     * NOT one dump held in a variable and handed to printf: the dump is far over 128 KB (the most one argument can hold),
     * and Android's shell has no built-in printf, so that failed with "Argument list too long" and the whole scan came back
     * empty. tools/test/SurveyCommandTest.java runs this command against a 400 KB fake dump with the built-in printf off.
     */
    static final String SURVEY_COMMAND = "dumpsys telephony.registry 2>&1 | grep -iE 'CellInfo|mServiceState' | cut -c1-9000 | head -8; "
            + "echo '" + EXTRA_MARKER + "'; "
            + "dumpsys telephony.registry 2>&1 | grep -E 'mPhysicalChannelConfigs|mTelephonyDisplayInfo' | cut -c1-6000 | head -4";

    private static final Pattern CFG_START = Pattern.compile("m(?:Cell)?ConnectionStatus=");
    private static final Pattern DISPLAY = Pattern.compile("overrideNetwork=([A-Za-z_0-9]+)");
    private static final Pattern CA = Pattern.compile("[Uu]singCarrierAggregation=(true|false)");
    private static final Pattern BANDWIDTHS = Pattern.compile("mCellBandwidths=\\[([0-9, ]*)]");
    private static final Pattern NRSTATE = Pattern.compile("nrState=([A-Z_]+)");

    /** LTE downlink EARFCN ranges: low, high, band. Used when the phone does not name the band. */
    private static final int[][] LTE = {
        {0, 599, 1}, {600, 1199, 2}, {1200, 1949, 3}, {1950, 2399, 4}, {2400, 2649, 5},
        {2750, 3449, 7}, {3450, 3799, 8}, {5010, 5179, 12}, {5180, 5279, 13}, {5280, 5379, 14},
        {5730, 5849, 17}, {6150, 6449, 20}, {8040, 8689, 25}, {8690, 9039, 26}, {9210, 9659, 28},
        {9770, 9869, 30}, {37750, 38249, 38}, {38650, 39649, 40}, {39650, 41589, 41},
        {55240, 56739, 48}, {66436, 67335, 66}, {68586, 68935, 71},
    };

    /** The LTE band of a downlink channel number (EARFCN), 0 if it is in none of the known ranges. */
    static int lteBand(int earfcn) {
        for (int[] r : LTE) {
            if (earfcn >= r[0] && earfcn <= r[1]) {
                return r[2];
            }
        }
        return 0;
    }

    /** What Android shows for a "display" label, in the carrier's words where there are any. */
    static String displayName(String display) {
        switch (display == null ? "" : display) {
            case "NR_ADVANCED":
                return "5G UW (5G Ultra Wideband)";
            case "NR_NSA":
                return "5G (non-standalone)";
            case "LTE_ADVANCED_PRO":
                return "LTE Advanced Pro (5G E)";
            case "LTE_CA":
                return "LTE with carrier aggregation";
            case "NONE":
                return "no extra label";
            case "":
                return "unknown";
            default:
                return display;
        }
    }

    private static String field(String seg, String name) {
        Matcher m = Pattern.compile(Pattern.quote(name) + "=([^,}\\]\\s]+)").matcher(seg);
        return m.find() ? m.group(1) : null;
    }

    private static int number(String seg, String name) {
        String v = field(seg, name);
        if (v == null) {
            return -1;
        }
        try {
            long n = Long.parseLong(v);
            return n > Integer.MAX_VALUE || n < Integer.MIN_VALUE ? -1 : (int) n;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String ratOf(String v) {
        if (v == null) {
            return "";
        }
        String u = v.toUpperCase(java.util.Locale.US);
        return u.startsWith("LTE") ? "LTE" : u.startsWith("NR") ? "NR" : u;
    }

    /** The carriers listed on one "mPhysicalChannelConfigs=[...]" line (the text after the "="), the primary one first. */
    private static List<Carrier> carriersIn(String line) {
        List<Carrier> out = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        Matcher m = CFG_START.matcher(line);
        while (m.find()) {
            starts.add(m.start());
        }
        for (int i = 0; i < starts.size(); i++) {
            String seg = line.substring(starts.get(i), i + 1 < starts.size() ? starts.get(i + 1) : line.length());
            String status = field(seg, "mConnectionStatus");
            if (status == null) {
                status = field(seg, "mCellConnectionStatus");
            }
            boolean primary = status != null && (status.startsWith("Primary") || status.equals("1"));
            String rat = ratOf(field(seg, "mRat"));
            if (rat.isEmpty() || rat.equals("UNKNOWN")) {
                rat = ratOf(field(seg, "mNetworkType"));
            }
            int channel = number(seg, "mDownlinkChannelNumber");
            int band = Math.max(0, number(seg, "mBand"));
            if (band == 0 && rat.equals("LTE") && channel >= 0) {
                band = lteBand(channel);
            }
            Carrier c = new Carrier(rat, band, channel, number(seg, "mCellBandwidthDownlinkKhz"), primary);
            boolean dup = false;
            for (Carrier o : out) {
                if (o.rat.equals(c.rat) && o.band == c.band && o.channel == c.channel && o.primary == c.primary) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                if (primary) {
                    out.add(0, c);
                } else {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /** The text after "mPhysicalChannelConfigs=" on a line. */
    private static String configsOf(String line) {
        int at = line.indexOf("mPhysicalChannelConfigs=");
        return line.substring(at + "mPhysicalChannelConfigs=".length());
    }

    /**
     * Reads the carriers, the display label and the aggregation facts out of a dump (any part of it; missing lines are fine).
     *
     * A phone that can hold two SIMs prints each of these fields once per SIM slot, the slots in the same order for every
     * field, and the slot that is not in use prints an empty carrier list. So the carriers are read from every slot's line
     * and the slot that lists the most is taken (the first on a tie); its display label and its aggregation facts are the
     * ones at the same position, when every field has a line for every slot, and the first ones otherwise.
     */
    static RadioState parse(String dump) {
        RadioState s = new RadioState();
        if (dump == null) {
            return s;
        }
        List<String> cfgLines = new ArrayList<>();
        List<String> svcLines = new ArrayList<>();
        List<String> dispLines = new ArrayList<>();
        for (String l : dump.split("\n")) {
            if (l.contains("mPhysicalChannelConfigs=")) {
                cfgLines.add(l);
            }
            if (l.contains("mServiceState=")) {
                svcLines.add(l);
            }
            if (l.contains("overrideNetwork=")) {
                dispLines.add(l);
            }
        }
        int slot = 0;
        List<Carrier> best = new ArrayList<>();
        for (int i = 0; i < cfgLines.size(); i++) {
            List<Carrier> c = carriersIn(configsOf(cfgLines.get(i)));
            if (c.size() > best.size()) {
                best = c;
                slot = i;
            }
        }
        s.carriers.addAll(best);
        if (best.isEmpty()) {
            // nothing could be read: keep the first list that has text in it, so it can be looked at (and its slot is the one to read)
            for (int i = 0; i < cfgLines.size(); i++) {
                String t = configsOf(cfgLines.get(i)).trim();
                if (!t.isEmpty() && !t.equals("[]") && !t.equals("null")) {
                    s.rawConfigs = t.length() > 600 ? t.substring(0, 600) : t;
                    slot = i;
                    break;
                }
            }
        }
        boolean paired = cfgLines.size() > 1;
        String disp = paired && dispLines.size() == cfgLines.size() ? dispLines.get(slot) : dump;
        Matcher d = DISPLAY.matcher(disp);
        if (d.find()) {
            s.display = d.group(1);
        }
        String svc = paired && svcLines.size() == cfgLines.size() ? svcLines.get(slot) : dump;
        Matcher ca = CA.matcher(svc);
        if (ca.find()) {
            s.ca = ca.group(1).equals("true");
        }
        Matcher bw = BANDWIDTHS.matcher(svc);
        if (bw.find()) {
            for (String b : bw.group(1).split(",")) {
                String t = b.trim();
                if (!t.isEmpty()) {
                    try {
                        s.bandwidths.add(Integer.parseInt(t));
                    } catch (NumberFormatException ignored) {
                        // not a number: leave it out
                    }
                }
            }
        }
        // the 5G state is printed once for every registration (voice, data, ...): the first one that says something is the data one
        Matcher nr = NRSTATE.matcher(svc);
        while (nr.find()) {
            if (s.nrState.isEmpty() || (s.nrState.equals("NONE") && !nr.group(1).equals("NONE"))) {
                s.nrState = nr.group(1);
            }
        }
        return s;
    }
}
