package com.tj.linkmeter;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
 * Phone only: which cells and bands the phone can see, and an attempt to restrict
 * the modem to chosen bands. Whether the modem obeys depends on the phone, so every
 * answer is passed back to the tablet as it came. A band lock undoes itself after two
 * minutes unless the tablet confirms that mobile data still works.
 */
final class Cells {
    private static final int NONE = Integer.MIN_VALUE;
    private static final long REVERT_MS = 120000L;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Pattern START = Pattern.compile("CellInfo(Lte|Nr|Wcdma|Gsm|Tdscdma|Cdma)");
    private static final Pattern BANDS = Pattern.compile("mBands=\\[([0-9, ]*)]");

    /** LTE downlink EARFCN ranges: low, high, band. Used when the phone does not name the band. */
    private static final int[][] LTE = {
        {0, 599, 1}, {600, 1199, 2}, {1200, 1949, 3}, {1950, 2399, 4}, {2400, 2649, 5},
        {2750, 3449, 7}, {3450, 3799, 8}, {5010, 5179, 12}, {5180, 5279, 13}, {5280, 5379, 14},
        {5730, 5849, 17}, {6150, 6449, 20}, {8040, 8689, 25}, {8690, 9039, 26}, {9210, 9659, 28},
        {9770, 9869, 30}, {37750, 38249, 38}, {38650, 39649, 40}, {39650, 41589, 41},
        {55240, 56739, 48}, {66436, 67335, 66}, {68586, 68935, 71},
    };

    private static volatile Runnable pending;
    private static volatile String lockSpec = "";
    private static volatile boolean kept;
    private static volatile long revertAt;

    private Cells() {}

    /** Runs a ShellMain command as the shell user (see ShellMain for the commands). */
    static Priv.Result shellMain(Context c, String args) throws IOException {
        return Priv.run(mainCommand(c, args), 40);
    }

    /** The shell command line that starts ShellMain with the given arguments. */
    static String mainCommand(Context c, String args) {
        return "CLASSPATH=$(pm path " + c.getPackageName() + " | head -1 | cut -d: -f2) "
                + "app_process /system/bin com.tj.linkmeter.ShellMain " + args;
    }

    // ------------------------------------------------------------ survey

    static String survey() throws IOException {
        Priv.Result r = Priv.run("dumpsys telephony.registry 2>&1 | grep -iE 'CellInfo|mServiceState' "
                + "| cut -c1-9000 | head -8", 25);
        // This phone hides each cell's channel in the cell list but shows the serving one here.
        Matcher ch = Pattern.compile("mChannelNumber=(\\d+)").matcher(r.out);
        int servingChannel = ch.find() ? Integer.parseInt(ch.group(1)) : NONE;
        JSONObject o = new JSONObject();
        JSONArray arr = new JSONArray();
        Set<String> seen = new HashSet<>();
        try {
            for (JSONObject cell : parse(r.out)) {
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
                o.put("raw", r.out.length() > 1500 ? r.out.substring(0, 1500) : r.out);
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
        for (int[] r : LTE) {
            if (earfcn >= r[0] && earfcn <= r[1]) {
                return r[2];
            }
        }
        return 0;
    }

    // ------------------------------------------------------------ band lock

    /** spec: "lte:5,12;nr:71", or empty to clear. The caller has already checked the characters. */
    static String applyBands(Context c, String spec) throws IOException {
        Priv.Result r = shellMain(c, spec.isEmpty() ? "bands-clear" : "bands-set '" + spec + "'");
        boolean ok = r.out.contains("OK ");
        if (ok && !spec.isEmpty()) {
            lockSpec = spec;
            kept = false;
            revertAt = SystemClock.elapsedRealtime() + REVERT_MS;
            armRevert(c);
            Guard.arm(c);
        } else if (ok) {
            clearState();
        }
        return r.out;
    }

    /** What is locked right now, for the tablet: {"lock":"lte:5","kept":false,"left":97}. */
    static String status() {
        JSONObject o = new JSONObject();
        try {
            o.put("lock", lockSpec);
            o.put("kept", kept);
            o.put("left", (kept || lockSpec.isEmpty()) ? 0
                    : Math.max(0, (revertAt - SystemClock.elapsedRealtime()) / 1000));
        } catch (JSONException ignored) {
            // plain values only
        }
        return o.toString();
    }

    /** The lock is gone (cleared by hand, undone by the timer, or by Restore). */
    static void clearState() {
        cancelRevert();
        lockSpec = "";
        kept = false;
        revertAt = 0;
    }

    private static void armRevert(Context c) {
        final Context app = c.getApplicationContext();
        cancelRevert();
        Runnable undo = () -> new Thread(() -> {
            try {
                if (shellMain(app, "bands-clear").out.contains("OK ")) {
                    clearState();
                }
            } catch (IOException ignored) {
                // Shizuku is off; nothing more can be done from here
            }
        }, "linkmeter-band").start();
        pending = undo;
        H.postDelayed(undo, REVERT_MS);
    }

    private static void cancelRevert() {
        Runnable p = pending;
        if (p != null) {
            H.removeCallbacks(p);
            pending = null;
        }
    }

    /** The tablet confirmed that data works: leave the lock in place. */
    static void keep() {
        cancelRevert();
        if (!lockSpec.isEmpty()) {
            kept = true;
        }
    }
}
