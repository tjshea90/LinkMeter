package com.tj.linkmeter;

/**
 * Tablet side: the real phone behind {@link LeverRun}. Everything the band comparison already does (5G on/off, looking at the
 * carriers, the upload test) is {@link SweepLink}; only the 5G leg is new. Every call is a request to the phone (see Call).
 */
final class LeverLink implements LeverRun.Link {
    private final SweepLink inner;
    private final MainActivity act;

    LeverLink(MainActivity act) {
        this.act = act;
        this.inner = new SweepLink(act);
    }

    @Override
    public void fiveGOff(BandSweep.Done<Boolean> d) {
        inner.fiveGOff(d);
    }

    @Override
    public void switchFiveG(boolean off, BandSweep.Done<Boolean> d) {
        inner.switchFiveG(off, d);
    }

    @Override
    public void legOff(final BandSweep.Done<Boolean> d) {
        Call.run(act, "POST", "/act?name=nrleg_get", 30000, (ok, text, json) -> {
            Boolean off = ok ? NrLeg.offFromText(text) : null;
            d.done(off != null, text, off);
        });
    }

    @Override
    public void switchLeg(String how, final BandSweep.Done<Boolean> d) {
        String name = how.equals("on") ? "nrleg_on" : how.equals("off-now") ? "nrleg_off_now" : "nrleg_off";
        Call.run(act, "POST", "/act?name=" + name, 40000, (ok, text, json) -> {
            if (!ok && text.contains("unknown action")) {
                // a phone app from before the 5G leg existed: the same as a modem that cannot do it
                d.done(false, "5G leg: the phone's app is too old to switch the 5G leg (cannot switch the 5G leg until it is updated)", null);
                return;
            }
            d.done(ok && NrLeg.accepted(text), text, NrLeg.offFromText(text));
        });
    }

    @Override
    public void observe(BandSweep.Done<BandSweep.Observation> d) {
        inner.observe(d);
    }

    @Override
    public void startUpload(BandSweep.Done<Void> d) {
        inner.startUpload(d);
    }

    @Override
    public void uploadStatus(BandSweep.Done<BandSweep.Upload> d) {
        inner.uploadStatus(d);
    }

    @Override
    public void stopUpload(BandSweep.Done<Void> d) {
        inner.stopUpload(d);
    }

    @Override
    public void later(long ms, Runnable r) {
        inner.later(ms, r);
    }

    @Override
    public long now() {
        return inner.now();
    }

    @Override
    public void awake(boolean on) {
        inner.awake(on);
    }
}
