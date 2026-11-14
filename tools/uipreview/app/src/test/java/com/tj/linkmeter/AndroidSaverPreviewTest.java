package com.tj.linkmeter;

import static com.tj.linkmeter.PreviewTest.findButton;
import static com.tj.linkmeter.PreviewTest.phoneReady;
import static com.tj.linkmeter.PreviewTest.phoneScreen;
import static com.tj.linkmeter.PreviewTest.phoneStatus;
import static com.tj.linkmeter.PreviewTest.phoneTab;
import static com.tj.linkmeter.PreviewTest.press;
import static com.tj.linkmeter.PreviewTest.settle;
import static com.tj.linkmeter.PreviewTest.shot;
import static com.tj.linkmeter.PreviewTest.tablet;
import static com.tj.linkmeter.WifiFollowPreviewTest.check;
import static com.tj.linkmeter.WifiFollowPreviewTest.mustNotShow;
import static com.tj.linkmeter.WifiFollowPreviewTest.mustShow;
import static com.tj.linkmeter.WifiFollowPreviewTest.textContaining;

import android.view.View;
import android.widget.TextView;
import com.tj.linkmeter.PreviewTest.Pretend;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * LinkMeter's saver and Android's OWN Battery saver, said apart (the user's report of 2026-11-14: "I pressed automatic ... the phone
 * control said battery saver was on, but it wasn't"). The tablet's Phone tab, Home and the phone's own screen, against a pretend phone
 * ({@link Pretend}): the two lines, what a press sends, what the screen says for a phone whose app is older. Pictures:
 * tools/uipreview/out/state-android-saver-*.png and phone-android-saver-*.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 29, qualifiers = "w565dp-h916dp-port-170dpi")
public class AndroidSaverPreviewTest {
    /** The new fields as a phone with this version of the app says them. */
    static String withAndroid(String psm, boolean option, boolean ours) {
        return "\"ecoAuto\":true,\"psm\":" + psm + ",\"ecoSys\":" + option + ",\"esys\":" + ours + ",";
    }

    static Pretend pretend(String... edits) {
        Pretend p = new Pretend();
        p.status = phoneStatus(true, edits);
        return p;
    }

    /** The text of the line that starts with this label (a Fact is two views side by side: the label, then the value). */
    static String valueOf(MainActivity a, String label) {
        TextView t = PreviewTest.findText(a.getWindow().getDecorView(), label); // exactly the label: a hint may start with the same words
        if (t == null) {
            throw new AssertionError("the screen has no line called " + label);
        }
        View row = (View) t.getParent();
        if (row instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) row;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (g.getChildAt(i) == t && i + 1 < g.getChildCount() && g.getChildAt(i + 1) instanceof TextView) {
                    return ((TextView) g.getChildAt(i + 1)).getText().toString();
                }
            }
        }
        throw new AssertionError("no value next to " + label);
    }

    /** Is this choice button the selected one? A selected button has the dark text on the accent colour ({@link Ui.Choice#select}). */
    static boolean selected(MainActivity a, String label) {
        android.widget.Button b = findButton(a.getWindow().getDecorView(), label);
        if (b == null) {
            throw new AssertionError("no button " + label);
        }
        return b.getCurrentTextColor() == Ui.ON_ACCENT;
    }

    // ------------------------------------------------------------------------------------------------ the tablet's Phone tab

    /** The user's own situation: LinkMeter's saver is on, Android's own Battery saver is off, and the screen says both, apart. */
    @Test
    public void phoneTabSaysBothSavers() throws Exception {
        Pretend p = pretend("\"ecoAuto\":true,", withAndroid("false", false, false));
        MainActivity a = phoneTab(p);
        check(valueOf(a, "LinkMeter's saver").equals("on"), "LinkMeter's saver is on: " + valueOf(a, "LinkMeter's saver"));
        check(valueOf(a, "Android's Battery saver").equals("off (LinkMeter leaves it alone)"),
                "Android's own Battery saver is off, and says LinkMeter leaves it alone: " + valueOf(a, "Android's Battery saver"));
        check(selected(a, "Leave alone") && !selected(a, "Also switch on"), "the option is off: 'Leave alone' is the selected button");
        check(selected(a, "Automatic") && !selected(a, "Off"), "LinkMeter's saver is Automatic: that button is the selected one");
        mustShow(a, "It is not Android's own Battery saver");
        mustShow(a, "Samsung: Power saving");
        mustShow(a, "Not tried on this phone yet");
        check(findButton(a.getWindow().getDecorView(), "Also switch on") != null && findButton(a.getWindow().getDecorView(), "Leave alone") != null,
                "the option has its two buttons");
        shot(a, "state-android-saver-off");
    }

    /** The option is on and Android says its Battery saver is on, switched on by LinkMeter. */
    @Test
    public void phoneTabAndroidSaverOn() throws Exception {
        Pretend p = pretend("\"ecoAuto\":true,", withAndroid("true", true, true));
        MainActivity a = phoneTab(p);
        check(valueOf(a, "Android's Battery saver").equals("on (LinkMeter switched it on, and puts it back)"),
                "says it is on and whose it is: " + valueOf(a, "Android's Battery saver"));
        check(selected(a, "Also switch on") && !selected(a, "Leave alone"), "the option is on: 'Also switch on' is the selected button");
        shot(a, "state-android-saver-on");
    }

    /** Somebody else's Battery saver is on: the screen does not call it LinkMeter's. */
    @Test
    public void phoneTabAndroidSaverIsTheUsers() throws Exception {
        Pretend p = pretend("\"ecoAuto\":true,", withAndroid("true", false, false));
        MainActivity a = phoneTab(p);
        check(valueOf(a, "Android's Battery saver").startsWith("on (not by LinkMeter"), "not LinkMeter's: " + valueOf(a, "Android's Battery saver"));
    }

    /** Asked for, LinkMeter's saver is on, and Android's own Battery saver is off: said in amber, never as "on". */
    @Test
    public void phoneTabAndroidSaverDidNotSwitchOn() throws Exception {
        Pretend p = pretend("\"ecoAuto\":true,", withAndroid("false", true, false));
        MainActivity a = phoneTab(p);
        check(valueOf(a, "Android's Battery saver").equals("off (it did not switch on)"), "said as it is: " + valueOf(a, "Android's Battery saver"));
        TextView t = textContaining(a.getWindow().getDecorView(), "it did not switch on");
        check(t != null && t.getCurrentTextColor() == Ui.AMBER, "in amber");
        shot(a, "state-android-saver-failed");
    }

    /** A phone that will not say: "not known", never "off". */
    @Test
    public void phoneTabAndroidSaverNotKnown() throws Exception {
        Pretend p = pretend("\"ecoAuto\":true,", "\"ecoAuto\":true,\"ecoSys\":false,\"esys\":false,");
        MainActivity a = phoneTab(p);
        check(valueOf(a, "Android's Battery saver").equals("not known"), "not known: " + valueOf(a, "Android's Battery saver"));
    }

    /** An older phone app has no such option: the tablet says so and does not offer buttons that would only fail. */
    @Test
    public void phoneTabOlderPhoneApp() throws Exception {
        Pretend p = pretend(); // the pretend phone's usual status has none of the new fields
        MainActivity a = phoneTab(p);
        check(valueOf(a, "Android's Battery saver").equals("update the phone's app"), "asks to update: " + valueOf(a, "Android's Battery saver"));
        check(!findButton(a.getWindow().getDecorView(), "Also switch on").isEnabled(), "the option's buttons wait for the phone's app");
        check(!findButton(a.getWindow().getDecorView(), "Leave alone").isEnabled(), "...both of them");
        shot(a, "state-android-saver-older-app");
    }

    /** "Also switch on" sends the action by its name, and the answer is the phone's own sentence. */
    @Test
    public void phoneTabPressSendsTheAction() throws Exception {
        Pretend p = pretend("\"ecoAuto\":true,", withAndroid("false", false, false));
        p.actReply = "{\"ok\":true,\"out\":\"Android's own Battery saver is on.\"}";
        MainActivity a = phoneTab(p);
        check(valueOf(a, "Android's Battery saver").equals("off (LinkMeter leaves it alone)"), "before the press: off");
        // what the phone will say once it has done it: the tab asks for the status again after every press
        p.status = phoneStatus(true, "\"ecoAuto\":true,", withAndroid("true", true, true));
        press(a, "Also switch on");
        settle();
        check(p.asked("/act?name=" + SystemSaver.ACTION_ON), "the action was sent: " + p.targets);
        check(!p.asked("/act?name=" + SystemSaver.ACTION_OFF), "and only that one");
        mustShow(a, "Android's own Battery saver is on.");
        check(valueOf(a, "Android's Battery saver").startsWith("on (LinkMeter switched it on"), "after the press the line follows the phone: " + valueOf(a, "Android's Battery saver"));
        shot(a, "state-android-saver-pressed");
        press(a, "Leave alone");
        settle();
        check(p.asked("/act?name=" + SystemSaver.ACTION_OFF), "the other button sends the other action: " + p.targets);
    }

    /** A failed switch is an error answer from the phone: the screen shows its sentence as an error. */
    @Test
    public void phoneTabPressFailsHonestly() throws Exception {
        Pretend p = pretend("\"ecoAuto\":true,", withAndroid("false", false, false));
        p.actCode = 409;
        p.actReply = "{\"err\":\"" + SystemSaver.STAYED_OFF + "\"}";
        MainActivity a = phoneTab(p);
        press(a, "Also switch on");
        settle();
        mustShow(a, "the phone took the switch but still says it is off");
        shot(a, "state-android-saver-press-failed");
    }

    // ------------------------------------------------------------------------------------------------ Home

    @Test
    public void homeSaysBothSavers() throws Exception {
        Pretend p = pretend("\"ecoAuto\":true,", withAndroid("false", false, false));
        p.install();
        PreviewTest.linkProved();
        MainActivity a = tablet();
        a.openTab(Tab.HOME);
        settle();
        check(valueOf(a, "LinkMeter's saver").equals("on"), "Home: LinkMeter's saver is on: " + valueOf(a, "LinkMeter's saver"));
        check(valueOf(a, "Android's Battery saver").equals("off (LinkMeter leaves it alone)"),
                "Home: Android's own Battery saver is off: " + valueOf(a, "Android's Battery saver"));
        mustNotShow(a, "Battery saver: on"); // the old line that read as Android's
        shot(a, "state-android-saver-home");
    }

    // ------------------------------------------------------------------------------------------------ the phone's own screen

    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void phoneScreenSaysBothSavers() throws Exception {
        PhoneScreenFacts f = phoneReady();
        f.androidOn = Boolean.FALSE;
        MainActivity a = phoneScreen(f, 1.0f);
        mustShow(a, "LinkMeter's saver while the tablet is connected");
        mustShow(a, "Also switch on Android's own Battery saver");
        check(valueOf(a, "LinkMeter's saver right now").equals("on"), "the phone: LinkMeter's saver is on");
        check(valueOf(a, "Android's Battery saver right now").equals("off (LinkMeter leaves it alone)"), "the phone: Android's is off and left alone: "
                + valueOf(a, "Android's Battery saver right now"));
        shot(a, "phone-android-saver-off");
    }

    /** The phone's own switch: stored at once (Shizuku cannot run here), the answer says what happens next, and the line follows the choice. */
    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void phoneScreenSwitchStoresTheOption() throws Exception {
        PhoneScreenFacts f = phoneReady();
        f.androidOn = Boolean.FALSE;
        MainActivity a = phoneScreen(f, 1.0f);
        check(!Cfg.ecoSystem(a), "the option starts off");
        TextView title = textContaining(a.getWindow().getDecorView(), PhoneScreenRules.ANDROID_SAVER_SWITCH);
        View v = title;
        while (v != null && !v.hasOnClickListeners()) {
            v = (View) v.getParent();
        }
        check(v != null, "the line is a switch");
        v.performClick();
        PreviewTest.later(800); // the work runs on a thread of its own
        settle();
        check(Cfg.ecoSystem(a), "the option is stored");
        mustShow(a, "Stored. Android's own Battery saver switches on with LinkMeter's saver.");
        shot(a, "phone-android-saver-stored");
    }

    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void phoneScreenAndroidSaverOn() throws Exception {
        PhoneScreenFacts f = phoneReady();
        f.androidOn = Boolean.TRUE;
        f.androidWanted = true;
        f.androidOurs = true;
        MainActivity a = phoneScreen(f, 1.0f);
        check(valueOf(a, "Android's Battery saver right now").equals("on (LinkMeter switched it on, and puts it back)"), "on, and whose: "
                + valueOf(a, "Android's Battery saver right now"));
        shot(a, "phone-android-saver-on");
    }

    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void phoneScreenAndroidSaverFailed() throws Exception {
        PhoneScreenFacts f = phoneReady();
        f.androidOn = Boolean.FALSE;
        f.androidWanted = true;
        MainActivity a = phoneScreen(f, 1.0f);
        check(valueOf(a, "Android's Battery saver right now").equals("off (it did not switch on)"), "off, and it did not switch on: "
                + valueOf(a, "Android's Battery saver right now"));
        shot(a, "phone-android-saver-failed");
    }

    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void phoneScreenAndroidSaverBigFont() throws Exception {
        PhoneScreenFacts f = phoneReady();
        f.androidOn = Boolean.TRUE;
        f.androidWanted = true;
        f.androidOurs = true;
        shot(phoneScreen(f, 1.3f), "phone-android-saver-font13");
    }
}
