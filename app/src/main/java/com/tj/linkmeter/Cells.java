package com.tj.linkmeter;

import android.content.Context;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Phone only: which cells and bands the phone can see, and the way to run ShellMain. (Versions up to v0.2.102 also tried to lock
 * the modem to chosen bands; this modem never obeyed, so that is gone. The phone still removes a lock such a version left: see
 * Actions.cleanOldFlags and ShellMain "bands-clear".)
 */
final class Cells {
    private static final int NONE = Integer.MIN_VALUE;
    private static final Pattern START = Pattern.compile("CellInfo(Lte|Nr|Wcdma|Gsm|Tdscdma|Cdma)");
    private static final Pattern BANDS = Pattern.compile("mBands=\\[([0-9, ]*)]");

    private Cells() {}

    /** Runs a ShellMain command as the shell user (see ShellMain for the commands). */
    static Priv.Result shellMain(Context c, String args) throws IOException {
        return shellMain(c, args, 40);
    }

    /** The same, for a command that needs longer than the usual 40 seconds. */
    static Priv.Result shellMain(Context c, String args, int timeoutSec) throws IOException {
        return Priv.run(mainCommand(c, args), timeoutSec);
    }

    /** The shell command line that starts ShellMain with the given arguments. */
    static String mainCommand(Context c, String args) {
        return "CLASSPATH=$(pm path " + c.getPackageName() + " | head -1 | cut -d: -f2) "
                + "app_process /system/bin com.tj.linkmeter.ShellMain " + args;
    }

    /**
     * Like {@link #mainCommand} but the shell turns into the helper (exec), for long-running helpers
     * that are stopped later with Process.destroy(). Not for Priv.run, which needs the shell to
     * live on and print its exit code.
     */
    static String mainCommandExec(Context c, String args) {
        return "export CLASSPATH=$(pm path " + c.getPackageName() + " | head -1 | cut -d: -f2); "
                + "exec app_process /system/bin com.tj.linkmeter.ShellMain " + args;
    }

    // ------------------------------------------------------------ survey

    static String survey() throws IOException {
        // The cell list (the cell the phone is registered on, and a few it measured) AND, after the marker, the carriers the
        // radio is really using (several with carrier aggregation or 5G) and the label the phone shows for them.
        Priv.Result r = Priv.run(RadioState.SURVEY_COMMAND, 25);
        int marker = r.out.indexOf(RadioState.EXTRA_MARKER);
        String cellPart = marker >= 0 ? r.out.substring(0, marker) : r.out;
        // This phone hides each cell's channel in the cell list but shows the serving one here.
        Matcher ch = Pattern.compile("mChannelNumber=(\\d+)").matcher(cellPart);
        int servingChannel = ch.find() ? Integer.parseInt(ch.group(1)) : NONE;
        JSONObject o = new JSONObject();
        JSONArray arr = new JSONArray();
        Set<String> seen = new HashSet<>();
        try {
            RadioState rs = RadioState.parse(r.out);
            JSONArray car = new JSONArray();
            for (RadioState.Carrier cr : rs.carriers) {
                JSONObject j = new JSONObject();
                j.put("rat", cr.rat);
                j.put("band", cr.band);
                j.put("ch", cr.channel);
                j.put("bw", cr.bwKhz);
                j.put("primary", cr.primary ? 1 : 0);
                car.put(j);
            }
            o.put("carriers", car);
            o.put("display", rs.display);
            o.put("ca", rs.ca);
            if (!rs.bandwidths.isEmpty()) {
                o.put("bws", new JSONArray(rs.bandwidths));
            }
            if (!rs.nrState.isEmpty()) {
                o.put("nr", rs.nrState);
            }
            if (car.length() == 0 && !rs.rawConfigs.isEmpty()) {
                o.put("cfgRaw", rs.rawConfigs);
            }
            for (JSONObject cell : parse(cellPart)) {
                if (cell.optInt("reg") == 1 && !cell.has("earfcn") && servingChannel != NONE) {
                    cell.put("earfcn", servingChannel);
                    if (cell.optJSONArray("bands") != null && cell.optJSONArray("bands").length() == 0
                            && cell.optString("t").equals("LTE") && lteBand(servingChannel) > 0) {
                        cell.optJSONArray("bands").put(lteBand(servingChannel));
                    }
                }
                // Channels and cell ids are hidden on some phones, so the key uses what is left.
                String key = cell.optString("t") + "|" + cell.optInt("reg") + "|"
                        + cell.optJSONArray("bands") + "|" + cell.optInt("earfcn", -1) + "|"
                        + cell.optInt("pci", -1) + "|" + cell.optInt("rsrp", 0) + "|"
                        + cell.optInt("rsrq", 0);
                if (seen.add(key)) {
                    arr.put(cell);
                }
            }
            o.put("cells", arr);
            if (arr.length() == 0) {
                o.put("raw", cellPart.length() > 1500 ? cellPart.substring(0, 1500) : cellPart);
            }
        } catch (JSONException ignored) {
            // numbers and short text only
        }
        return o.toString();
    }

    private static List<JSONObject> parse(String dump) throws JSONException {
        List<Integer> starts = new ArrayList<>();
        List<String> types = new ArrayList<>();
        Matcher m = START.matcher(dump);
        while (m.find()) {
            starts.add(m.start());
            types.add(m.group(1).toUpperCase(Locale.US));
        }
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : dump.length();
            String seg = dump.substring(starts.get(i), end);
            String type = types.get(i);
            boolean nr = type.equals("NR");
            boolean reg = seg.contains("mRegistered=YES");
            if (!reg && !nr && !type.equals("LTE")) {
                continue;
            }
            int earfcn = intAfter(seg, nr ? "mNrArfcn=" : "mEarfcn=", false);
            JSONObject c = new JSONObject();
            c.put("t", type);
            c.put("reg", reg ? 1 : 0);
            JSONArray bands = new JSONArray();
            Matcher bm = BANDS.matcher(seg);
            if (bm.find()) {
                for (String b : bm.group(1).split(",")) {
                    String t = b.trim();
                    if (!t.isEmpty()) {
                        bands.put(Integer.parseInt(t));
                    }
                }
            }
            if (bands.length() == 0 && type.equals("LTE") && earfcn != NONE) {
                int b = lteBand(earfcn);
                if (b > 0) {
                    bands.put(b);
                }
            }
            c.put("bands", bands);
            put(c, "earfcn", earfcn);
            put(c, "pci", intAfter(seg, "mPci=", false));
            put(c, "rsrp", intAfter(seg, nr ? "ssRsrp=" : "rsrp=", !nr));
            put(c, "rsrq", intAfter(seg, nr ? "ssRsrq=" : "rsrq=", !nr));
            put(c, "sinr", intAfter(seg, nr ? "ssSinr=" : "rssnr=", !nr));
            put(c, "lvl", intAfter(seg, "level=", true));
            out.add(c);
        }
        return out;
    }

    private static void put(JSONObject o, String k, int v) throws JSONException {
        if (v != NONE) {
            o.put(k, v);
        }
    }

    /** The number after key, or NONE if absent or reported as "unavailable". */
    private static int intAfter(String seg, String key, boolean wholeWord) {
        String re = (wholeWord ? "(?<![A-Za-z])" : "") + Pattern.quote(key) + "(-?\\d+)";
        Matcher m = Pattern.compile(re).matcher(seg);
        if (!m.find()) {
            return NONE;
        }
        try {
            long v = Long.parseLong(m.group(1));
            return v >= Integer.MAX_VALUE || v <= Integer.MIN_VALUE
                    ? NONE : (int) v;
        } catch (NumberFormatException e) {
            return NONE;
        }
    }

    private static int lteBand(int earfcn) {
        return RadioState.lteBand(earfcn);
    }
}
