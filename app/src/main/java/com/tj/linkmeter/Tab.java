package com.tj.linkmeter;

import android.view.View;

/** One screen of the tablet app. Views are built when the tab is shown and dropped when it is left. */
abstract class Tab {
    final MainActivity act;

    /*
     * The names of the screens, in ONE place. Each tab's title(), MainActivity.openTab and every sentence that sends the user to
     * a tab use these, so a renamed tab cannot leave a button that opens nothing or a sentence that names a tab that is gone.
     */
    static final String HOME = "Home";
    static final String SPEED = "Speed";
    /** What the phone is on, 4G only or 5G, the comparison, the best spot (SignalTab; it was called Bands up to v0.2.144). */
    static final String SIGNAL = "Signal";
    /** On the tablet: the remote controls for the phone (the class is ControlTab). On the phone: its own screen (PhoneTab). */
    static final String PHONE = "Phone";
    static final String SCREEN = "Screen";
    static final String FILES = "Files";
    static final String TEXTS = "Texts";
    static final String SETTINGS = "Settings";

    Tab(MainActivity a) {
        act = a;
    }

    abstract String title();

    /** The picture for this screen in the bottom bar. */
    Icons.Kind icon() {
        return Icons.Kind.RECENTS;
    }

    abstract View build();

    /** The tab is on screen and the app is in front. Start any work here. */
    void onShow() {}

    /** The tab left the screen or the app went to the background. Stop all work here. */
    void onHide() {}

    /**
     * True while the app is in front but ANOTHER tab is on show; false as soon as that ends (this tab is shown, or the app went to the
     * background). A tab may keep a small watch meanwhile: the Texts tab keeps the number of new texts on its button. Most do nothing.
     */
    void watchWhileHidden(boolean on) {}

    /** Keep this tab out of screenshots, screen recordings and the recent apps list while it is the one in front? */
    boolean secure() {
        return false;
    }

    /** Somebody touched the tablet (any touch, key or trackball event of the app) while this tab is the current one. */
    void onUserInteraction() {}
}
