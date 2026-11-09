package com.tj.linkmeter;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Phone side: reads battery and cell signal, only when the tablet asks.
 * Nothing here runs on a timer, so between requests the phone does no work.
 */
final class Status {
    private Status() {}

    static String json(Context c) {
        JSONObject o = new JSONObject();
        try {
            // Sticky broadcast: a plain read, no receiver is registered.
            Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                int temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                o.put("bat", (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1);
                o.put("chg", status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL);
                if (temp != Integer.MIN_VALUE) {
                    o.put("tmp", temp / 10.0);
                }
            }
            String upd = Updater.lastResult(c);
            if (!upd.isEmpty()) {
                o.put("upd", upd.length() > 200 ? upd.substring(0, 200) : upd);
            }
            o.put("vc", Updater.versionCode(c));
            o.put("vn", Updater.versionName(c));
            o.put("priv", Priv.state());
            o.put("eco", Eco.isOn(c));
            cell(c, o);
        } catch (JSONException ignored) {
            // Nothing to do: the tablet just gets whatever was filled in so far.
        }
        return o.toString();
    }

    /**
     * The 4G and 5G signal readings for the tablet's placement helper (SignalHunt reads them): power, quality and signal-to-noise
     * of the 4G cell that carries the upload, and of the 5G cell. Whole numbers as Android reports them; a value the phone does not
     * know is Integer.MAX_VALUE, which SignalHunt turns into "not reported". No Shizuku needed.
     */
    static String sigJson(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("t", SystemClock.elapsedRealtime());
            if (c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                TelephonyManager tm = c.getSystemService(TelephonyManager.class);
                SignalStrength ss = tm == null ? null : tm.getSignalStrength();
                if (ss != null) {
                    for (CellSignalStrength s : ss.getCellSignalStrengths()) {
                        if (s instanceof CellSignalStrengthLte) {
                            CellSignalStrengthLte l = (CellSignalStrengthLte) s;
                            o.put("lteRsrp", l.getRsrp());
                            o.put("lteRsrq", l.getRsrq());
                            o.put("lteSinr", l.getRssnr());
                            o.put("lteCqi", l.getCqi());
                        } else if (s instanceof CellSignalStrengthNr) {
                            CellSignalStrengthNr n = (CellSignalStrengthNr) s;
                            o.put("nrRsrp", n.getSsRsrp());
                            o.put("nrRsrq", n.getSsRsrq());
                            o.put("nrSinr", n.getSsSinr());
                        }
                    }
                }
                if (tm != null) {
                    o.put("net", netName(tm.getDataNetworkType()));
                }
            } else {
                o.put("err", "the phone app has no phone permission");
            }
        } catch (JSONException | RuntimeException e) {
            // the tablet gets whatever was filled in so far
        }
        return o.toString();
    }

    private static void cell(Context c, JSONObject o) throws JSONException {
        o.put("lvl", -1);
        boolean allowed = c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
        boolean got = false;
        String net = "?";
        if (allowed) {
            TelephonyManager tm = c.getSystemService(TelephonyManager.class);
            if (tm != null) {
                try {
                    net = netName(tm.getDataNetworkType());
                    SignalStrength ss = tm.getSignalStrength();
                    if (ss != null) {
                        CellSignalStrength best = null;
                        List<CellSignalStrength> all = ss.getCellSignalStrengths();
                        for (CellSignalStrength s : all) {
                            int d = s.getDbm();
                            if (d == Integer.MAX_VALUE || d == 0) {
                                continue; // not reported
                            }
                            boolean nr = s instanceof CellSignalStrengthNr;
                            if (best == null || (nr && !(best instanceof CellSignalStrengthNr))) {
                                best = s;
                            }
                        }
                        if (best != null) {
                            o.put("dbm", best.getDbm());
                            o.put("lvl", best.getLevel());
                            got = true;
                            if (best instanceof CellSignalStrengthNr && "LTE".equals(net)) {
                                net = "5G (NSA)";
                            }
                        } else if (ss.getLevel() > 0) {
                            o.put("lvl", ss.getLevel());
                            got = true;
                        }
                    }
                } catch (SecurityException e) {
                    allowed = false;
                }
            }
        }
        o.put("net", net);
        if (!got) {
            // No permission, or Android gave no reading: ask the system's own dump through Shizuku.
            fromRegistry(o);
            if (!o.has("dbm") && o.optInt("lvl", -1) < 0 && !allowed) {
                o.put("net", "no phone permission");
            }
        }
    }

    private static volatile String registry = "";
    private static volatile long registryAt;

    /** The phone's own signal and network lines, read at most every 15 seconds. */
    private static void fromRegistry(JSONObject o) throws JSONException {
        if (!Priv.ready()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - registryAt > 15000L) {
            registryAt = now;
            try {
                registry = Priv.run("dumpsys telephony.registry 2>&1 | grep -E 'mSignalStrength|mServiceState' "
                        + "| cut -c1-1500 | head -4", 10).out;
            } catch (IOException e) {
                registry = "";
            }
        }
        String dump = registry;
        Matcher r = Pattern.compile("(?<![A-Za-z])rsrp=(-?\\d+)").matcher(dump);
        Matcher l = Pattern.compile("(?<![A-Za-z])level=(\\d)").matcher(dump);
        Matcher t = Pattern.compile("getRilDataRadioTechnology=\\d+\\((\\w+)\\)").matcher(dump);
        if (r.find()) {
            long v = Long.parseLong(r.group(1));
            if (v < 0 && v > -200) {
                o.put("dbm", v);
            }
        }
        if (l.find()) {
            o.put("lvl", Integer.parseInt(l.group(1)));
        }
        if (t.find()) {
            o.put("net", t.group(1));
        }
    }

    private static String netName(int type) {
        switch (type) {
            case TelephonyManager.NETWORK_TYPE_NR:
                return "5G";
            case TelephonyManager.NETWORK_TYPE_LTE:
                return "LTE";
            case TelephonyManager.NETWORK_TYPE_HSPAP:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_UMTS:
                return "3G";
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_GPRS:
                return "2G";
            case TelephonyManager.NETWORK_TYPE_UNKNOWN:
                return "no data";
            default:
                return "other";
        }
    }
}
