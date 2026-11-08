package com.tj.linkmeter;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthNr;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import java.util.List;
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

    private static void cell(Context c, JSONObject o) throws JSONException {
        o.put("lvl", -1);
        if (c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            o.put("net", "no permission");
            return;
        }
        TelephonyManager tm = c.getSystemService(TelephonyManager.class);
        if (tm == null) {
            o.put("net", "?");
            return;
        }
        String net = "?";
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
                    if (best instanceof CellSignalStrengthNr && "LTE".equals(net)) {
                        net = "5G (NSA)";
                    }
                } else {
                    o.put("lvl", ss.getLevel());
                }
            }
        } catch (SecurityException e) {
            net = "no permission";
        }
        o.put("net", net);
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
