package com.tj.linkmeter;

import android.content.Context;

/**
 * Tablet: the remote screen's settings, their defaults and the quality presets. Stored through Cfg.
 * A preset sets four numbers (video width and bitrate, picture width and quality); changing any of them by
 * hand makes the preset "Custom" unless the numbers happen to match another preset.
 */
final class ScreenPrefs {
    static final String[] PRESETS = QualityPresets.NAMES;
    private static final int[] P_VID_W = QualityPresets.VID_W;
    private static final int[] P_VID_K = QualityPresets.VID_K;
    private static final int[] P_PIC_W = QualityPresets.PIC_W;
    private static final int[] P_PIC_Q = QualityPresets.PIC_Q;

    /** Steps offered by the sliders. The phone never encodes below 500 kbit/s (Screen.java), so a lower step would only be a copy of the first. */
    static final int[] WIDTHS = {288, 360, 432, 540, 630, 720, 900, 1080};
    static final int[] KBPS = {500, 800, 1000, 1500, 2000, 3000, 4000, 5000, 6000, 8000, 10000, 12000};
    /** Pictures per second in Pictures mode; 0 = as fast as the phone can. */
    static final int[] PIC_FPS = {0, 1, 2, 4, 8};
    /** Minutes the phone's screen stays on while the remote screen is open; 0 = leave the phone's timeout alone. */
    static final int[] KEEP_MIN = {0, 1, 2, 5, 10, 30};
    static final String[] DIM_NAMES = {"Lowest", "Very low", "Low", "Don't change"};
    static final int[] DIM_LEVEL = {0, 13, 38, -1};
    static final String[] FIX_NAMES = {"Automatic", "Always", "Never"};

    private final Context c;

    ScreenPrefs(Context c) {
        this.c = c.getApplicationContext();
    }

    // ------------------------------------------------------------ picture

    /** 0 = live video, 1 = still pictures. */
    int mode() {
        return Cfg.getInt(c, "scrMode", 0);
    }

    void setMode(int m) {
        Cfg.setInt(c, "scrMode", m);
    }

    int preset() {
        return Math.max(0, Math.min(4, Cfg.getInt(c, "scrPreset", 2)));
    }

    /**
     * Chooses a preset. The user's own numbers (Custom) are kept apart, so choosing a preset does not lose them and Custom can be
     * chosen again; choosing Custom when there are no custom numbers does nothing.
     */
    void setPreset(int p) {
        if (p == QualityPresets.CUSTOM) {
            if (hasCustom()) {
                Cfg.setInt(c, "vidW", Cfg.getInt(c, "cVidW", 0));
                Cfg.setInt(c, "vidK", Cfg.getInt(c, "cVidK", 0));
                Cfg.setInt(c, "picW", Cfg.getInt(c, "cPicW", 0));
                Cfg.setInt(c, "picQ", Cfg.getInt(c, "cPicQ", 0));
                Cfg.setInt(c, "scrPreset", p);
            }
            return;
        }
        if (preset() == QualityPresets.CUSTOM) {
            saveCustom(); // leaving Custom: the numbers are about to be overwritten, so keep them (also those an older version left)
        }
        Cfg.setInt(c, "scrPreset", p);
        if (p >= 0 && p < P_VID_W.length) {
            Cfg.setInt(c, "vidW", P_VID_W[p]);
            Cfg.setInt(c, "vidK", P_VID_K[p]);
            Cfg.setInt(c, "picW", P_PIC_W[p]);
            Cfg.setInt(c, "picQ", P_PIC_Q[p]);
        }
    }

    private void matchPreset() {
        int found = QualityPresets.match(vidWidth(), vidKbps(), picWidth(), picQuality());
        Cfg.setInt(c, "scrPreset", found);
        if (found == QualityPresets.CUSTOM) {
            saveCustom();
        }
    }

    /** The four numbers as they are now, kept as the user's own (Custom) in keys no preset touches. */
    private void saveCustom() {
        Cfg.setInt(c, "cVidW", vidWidth());
        Cfg.setInt(c, "cVidK", vidKbps());
        Cfg.setInt(c, "cPicW", picWidth());
        Cfg.setInt(c, "cPicQ", picQuality());
    }

    /** True when the user has numbers of their own (Custom) that the quality chip can go back to. */
    boolean hasCustom() {
        return Cfg.getInt(c, "cVidW", 0) > 0;
    }

    int vidWidth() {
        return Cfg.getInt(c, "vidW", P_VID_W[2]);
    }

    void setVidWidth(int v) {
        Cfg.setInt(c, "vidW", v);
        matchPreset();
    }

    int vidKbps() {
        return Math.max(KBPS[0], Cfg.getInt(c, "vidK", P_VID_K[2])); // never below what the phone uses (an older version offered 300)
    }

    void setVidKbps(int v) {
        Cfg.setInt(c, "vidK", v);
        matchPreset();
    }

    int picWidth() {
        return Cfg.getInt(c, "picW", P_PIC_W[2]);
    }

    void setPicWidth(int v) {
        Cfg.setInt(c, "picW", v);
        matchPreset();
    }

    int picQuality() {
        return Cfg.getInt(c, "picQ", P_PIC_Q[2]);
    }

    void setPicQuality(int v) {
        Cfg.setInt(c, "picQ", v);
        matchPreset();
    }

    /** Index into PIC_FPS. */
    int picFpsIndex() {
        return Math.max(0, Math.min(PIC_FPS.length - 1, Cfg.getInt(c, "picF", 0)));
    }

    void setPicFpsIndex(int i) {
        Cfg.setInt(c, "picF", i);
    }

    /** Index into FIX_NAMES: whether the video header is rewritten so the decoder does not hold pictures back. */
    int lowDelay() {
        return Math.max(0, Math.min(2, Cfg.getInt(c, "lowDelay", 0)));
    }

    void setLowDelay(int v) {
        Cfg.setInt(c, "lowDelay", v);
    }

    /** Name of the video decoder to use; empty = the tablet's default. */
    String decoder() {
        return Cfg.getStr(c, "decoder", "");
    }

    void setDecoder(String name) {
        Cfg.setStr(c, "decoder", name);
    }

    /** Show a crisp still picture whenever the phone's screen stops changing. */
    boolean stillImage() {
        return Cfg.getBool(c, "still", true);
    }

    void setStillImage(boolean v) {
        Cfg.setBool(c, "still", v);
    }

    boolean showStats() {
        return Cfg.getBool(c, "stats", true);
    }

    void setShowStats(boolean v) {
        Cfg.setBool(c, "stats", v);
    }

    // ------------------------------------------------------------ this tablet while remote

    /**
     * Keep this tablet's own screen on and bright while the remote screen is open. Watching the phone does not
     * touch the tablet, so without this the tablet dims and turns off after its normal screen timeout.
     */
    boolean tabletAwake() {
        return Cfg.getBool(c, "tabAwake", true);
    }

    void setTabletAwake(boolean v) {
        Cfg.setBool(c, "tabAwake", v);
    }

    // ------------------------------------------------------------ the phone while remote

    /** Index into KEEP_MIN. */
    int keepIndex() {
        return Math.max(0, Math.min(KEEP_MIN.length - 1, Cfg.getInt(c, "keepIdx", 2)));
    }

    void setKeepIndex(int i) {
        Cfg.setInt(c, "keepIdx", i);
    }

    /** Index into DIM_NAMES. */
    int dimIndex() {
        return Math.max(0, Math.min(DIM_NAMES.length - 1, Cfg.getInt(c, "dimIdx", 0)));
    }

    void setDimIndex(int i) {
        Cfg.setInt(c, "dimIdx", i);
    }

    boolean sleepOnLeave() {
        return Cfg.getBool(c, "sleepLeave", true);
    }

    void setSleepOnLeave(boolean v) {
        Cfg.setBool(c, "sleepLeave", v);
    }

    boolean tapToWake() {
        return Cfg.getBool(c, "tapWake", true);
    }

    void setTapToWake(boolean v) {
        Cfg.setBool(c, "tapWake", v);
    }

    /** The query for the phone's profile call: how long to keep the screen on and how bright to leave it. */
    String profileQuery() {
        return "keep=" + (KEEP_MIN[keepIndex()] * 60000L) + "&dim=" + DIM_LEVEL[dimIndex()];
    }

    /** Puts every remote screen setting back to its default. */
    void reset() {
        setPreset(2);
        Cfg.setInt(c, "cVidW", 0); // a reset forgets the user's own numbers too
        Cfg.setInt(c, "cVidK", 0);
        Cfg.setInt(c, "cPicW", 0);
        Cfg.setInt(c, "cPicQ", 0);
        setMode(0);
        setPicFpsIndex(0);
        setLowDelay(0);
        setDecoder("");
        setStillImage(true);
        setShowStats(true);
        setKeepIndex(2);
        setDimIndex(0);
        setSleepOnLeave(true);
        setTapToWake(true);
        setTabletAwake(true);
    }
}
