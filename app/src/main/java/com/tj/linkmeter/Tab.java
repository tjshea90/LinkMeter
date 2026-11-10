package com.tj.linkmeter;

import android.view.View;

/** One screen of the tablet app. Views are built when the tab is shown and dropped when it is left. */
abstract class Tab {
    final MainActivity act;

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
