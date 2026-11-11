package com.tj.linkmeter;

import android.content.Context;

/** The tablet's choices for the Texts tab (Texts > menu > Texts settings). */
final class TextsPrefs {
    private final Context c;

    TextsPrefs(Context c) {
        this.c = c.getApplicationContext();
    }

    /** Keep a saved copy of the texts on this tablet: the tab opens at once and shows them when the phone cannot be reached. */
    boolean keepCopy() {
        return Cfg.getBool(c, "textsKeep", true);
    }

    void setKeepCopy(boolean v) {
        Cfg.setBool(c, "textsKeep", v);
    }

    /** Show "New message" instead of the words of a text in the list. */
    boolean hidePreviews() {
        return Cfg.getBool(c, "textsHide", false);
    }

    void setHidePreviews(boolean v) {
        Cfg.setBool(c, "textsHide", v);
    }

    /** Ask for the tablet's screen lock before showing the texts. */
    boolean lock() {
        return Cfg.getBool(c, "textsLock", false);
    }

    void setLock(boolean v) {
        Cfg.setBool(c, "textsLock", v);
    }

    /** No screenshots of the Texts tab, and a blank picture in the recent apps. */
    boolean secureWindow() {
        return Cfg.getBool(c, "textsSecure", false);
    }

    void setSecureWindow(boolean v) {
        Cfg.setBool(c, "textsSecure", v);
    }
}
