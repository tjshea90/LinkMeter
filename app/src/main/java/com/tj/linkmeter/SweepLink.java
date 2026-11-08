package com.tj.linkmeter;

import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;

/** Tablet side: the real phone behind BandSweep. Every call is a request to the phone (see Call) answered on the UI thread. */
final class SweepLink implements BandSweep.Link {
    private final MainActivity act;

    SweepLink(MainActivity act) {
        this.act = act;
    }

    @Override
    public void fiveGOff(final BandSweep.Done<Boolean> d) {
        Call.run(act, "POST", "/act?name=net_get", 30000, (ok, text, json) -> {
            boolean known = ok && (text.contains("5G is off") || text.contains("5G is allowed"));
            d.done(known, text, text.contains("5G is off"));
        });
    }

    @Override
    public void switchFiveG(final boolean off, final BandSweep.Done<Boolean> d) {
        Call.run(act, "POST", "/act?name=" + (off ? "net_lte" : "net_5g"), 60000, (ok, text, json) -> {
            // the phone's own words: "Forced to 4G..." / "5G is already off..." and "5G is allowed again..." / "5G is already allowed..."
            boolean done = ok && (off ? (text.contains("Forced to 4G") || text.contains("already off"))
                    : (text.contains("allowed again") || text.contains("already allowed")));
            d.done(done, text, done ? Boolean.valueOf(off) : null);
        });
    }

    @Override
    public void setLock(String spec, final BandSweep.Done<Void> d) {
        Call.run(act, "POST", "/band?set=" + Http.enc(spec), 50000, (ok, text, json) -> d.done(ok, text, null));
    }

    @Override
    public void observe(final BandSweep.Done<BandSweep.Observation> d) {
        Call.run(act, "GET", "/cells", 35000, (ok, text, json) -> {
            if (ok && json != null) {
                d.done(true, text, new BandSweep.Observation(stateOf(json), rsrpOf(json)));
            } else {
                d.done(false, text, null);
            }
        });
    }

    @Override
    public void startUpload(final BandSweep.Done<Void> d) {
        Call.run(act, "POST", "/speed/start?only=up", 8000, (ok, text, json) -> d.done(ok, text, null));
    }

    @Override
    public void uploadStatus(final BandSweep.Done<BandSweep.Upload> d) {
        Call.run(act, "GET", "/speed/status", 6000, (ok, text, json) -> {
            if (ok && json != null) {
                d.done(true, text, new BandSweep.Upload(json.optInt("phase"), json.optDouble("up", -1),
                        json.optDouble("ping", -1), json.optDouble("live", 0), json.optString("error", ""),
                        json.optString("warn", "")));
            } else {
                d.done(false, text, null);
            }
        });
    }

    @Override
    public void stopUpload(final BandSweep.Done<Void> d) {
        Call.run(act, "POST", "/speed/stop", 4000, (ok, text, json) -> d.done(ok, text, null));
    }

    @Override
    public void lockSpec(final BandSweep.Done<String> d) {
        Call.run(act, "GET", "/band/status", 8000, (ok, text, json) ->
                d.done(ok && json != null, text, json == null ? null : json.optString("lock", "")));
    }

    @Override
    public void later(long ms, Runnable r) {
        act.ui.postDelayed(r, ms);
    }

    @Override
    public long now() {
        return SystemClock.elapsedRealtime();
    }

    @Override
    public void awake(boolean on) {
        act.setHoldAwake(on);
    }

    // ------------------------------------------------------------ reading what the phone sent

    /** The carriers and labels in a /cells reply. */
    static RadioState stateOf(JSONObject json) {
        RadioState s = new RadioState();
        JSONArray car = json.optJSONArray("carriers");
        for (int i = 0; car != null && i < car.length(); i++) {
            JSONObject c = car.optJSONObject(i);
            if (c != null) {
                s.carriers.add(new RadioState.Carrier(c.optString("rat"), c.optInt("band"), c.optInt("ch", -1),
                        c.optInt("bw", -1), c.optInt("primary") == 1));
            }
        }
        s.display = json.optString("display", "");
        s.ca = json.optBoolean("ca", false);
        JSONArray bws = json.optJSONArray("bws");
        for (int i = 0; bws != null && i < bws.length(); i++) {
            s.bandwidths.add(bws.optInt(i));
        }
        s.nrState = json.optString("nr", "");
        s.rawConfigs = json.optString("cfgRaw", "");
        return s;
    }

    /** The signal of the registered cell in a /cells reply, or Integer.MIN_VALUE. */
    static int rsrpOf(JSONObject json) {
        JSONArray cells = json.optJSONArray("cells");
        for (int i = 0; cells != null && i < cells.length(); i++) {
            JSONObject c = cells.optJSONObject(i);
            if (c != null && c.optInt("reg") == 1 && c.has("rsrp")) {
                return c.optInt("rsrp");
            }
        }
        return Integer.MIN_VALUE;
    }
}
