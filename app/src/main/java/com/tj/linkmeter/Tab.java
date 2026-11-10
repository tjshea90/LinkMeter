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
    static final String BANDS = "Bands";
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
}
