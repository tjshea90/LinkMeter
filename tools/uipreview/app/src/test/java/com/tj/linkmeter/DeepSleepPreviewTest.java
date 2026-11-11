package com.tj.linkmeter;

import static com.tj.linkmeter.PreviewTest.dialogShot;
import static com.tj.linkmeter.PreviewTest.findButton;
import static com.tj.linkmeter.PreviewTest.later;
import static com.tj.linkmeter.PreviewTest.linkProved;
import static com.tj.linkmeter.PreviewTest.press;
import static com.tj.linkmeter.PreviewTest.settle;
import static com.tj.linkmeter.PreviewTest.shot;
import static com.tj.linkmeter.PreviewTest.tablet;
import static com.tj.linkmeter.WifiFollowPreviewTest.addWifiNetwork;
import static com.tj.linkmeter.WifiFollowPreviewTest.app;
import static com.tj.linkmeter.WifiFollowPreviewTest.check;
import static com.tj.linkmeter.WifiFollowPreviewTest.dropNetwork;
import static com.tj.linkmeter.WifiFollowPreviewTest.mustNotShow;
import static com.tj.linkmeter.WifiFollowPreviewTest.mustShow;
import static com.tj.linkmeter.WifiFollowPreviewTest.noNetworks;
import static com.tj.linkmeter.WifiFollowPreviewTest.panelWasOpened;
import static com.tj.linkmeter.WifiFollowPreviewTest.textContaining;
import static com.tj.linkmeter.WifiFollowPreviewTest.wifiIs;
import static com.tj.linkmeter.WifiFollowPreviewTest.wifiIsOn;

import android.app.AlertDialog;
import android.net.Network;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import com.tj.linkmeter.PreviewTest.Pretend;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;

/**
 * "Hotspot off" puts the phone to sleep (hotspot, location, airplane mode, screen) and "Hotspot on" wakes it, as the tablet's
 * screens show it: the question with its third button, which action is sent, what the follow-up says, the patience when the phone
 * is in airplane mode, the Airplane mode and Location lines, the Settings switch and the Phone tab's Location buttons. The real
 * HomeTab, SettingsTab and ControlTab against a pretend phone ({@link Pretend}). Pictures: tools/uipreview/out/state-*deep*.png,
 * state-home-asleep.png, state-phone-location.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 29, qualifiers = "w565dp-h916dp-port-170dpi")
public class DeepSleepPreviewTest {
    private static final String LATER = "{\"ok\":true,\"later\":true,\"out\":\"Sent. The phone will do it in a moment.\"}";

    @After
    public void tearDown() {
        TabletWifi.refusedForTest = false;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** The phone's status: hotspot, screen, airplane mode, location (null: not said), and whether its app knows the deep form. */
    static String status(boolean hs, boolean scr, boolean air, Boolean loc, boolean deep) {
        String s = Pretend.status(hs, scr).replace("\"air\":false", "\"air\":" + air);
        String extra = (loc == null ? "" : ",\"loc\":" + loc) + (deep ? ",\"deep\":1" : "");
        return s.replace("\"bt\":\"on\"", "\"bt\":\"on\"" + extra);
    }

    /** The tablet is on the phone's hotspot, the Bluetooth link has just been proved, the phone answers "Sent" to an action that cuts the link. */
    static Pretend onTheHotspot(boolean deep) {
        Pretend p = new Pretend();
        p.status = status(true, true, false, Boolean.TRUE, deep);
        p.bluetoothToo = true;
        p.actReply = LATER;
        p.install();
        linkProved();
        noNetworks();
        return p;
    }

    /** Presses the button and lets the live Bluetooth call before the question finish: the question is then on the screen. */
    static AlertDialog question(MainActivity a, String button) throws Exception {
        settle();
        press(a, button);
        settle();
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        check(d != null && d.isShowing(), "the question is open");
        return d;
    }

    static boolean saysInQuestion(AlertDialog d, String part) {
        return textContaining(d.getWindow().getDecorView(), part) != null;
    }

    // ------------------------------------------------------------------------------------------------ the question

    /** The deep form: the question says what happens, and has "Turn it off", "Hotspot only" and "Cancel". */
    @Test
    public void theQuestionOffersTheDeepFormAndHotspotOnly() throws Exception {
        onTheHotspot(true);
        MainActivity a = tablet();
        AlertDialog d = question(a, "Hotspot off");
        check(saysInQuestion(d, "location goes off (if it is on), airplane mode goes on and the screen goes off"), "says what the deep form does");
        check(saysInQuestion(d, "gets no calls or texts"), "says that calls and texts stop");
        check(saysInQuestion(d, "only stays in airplane mode while Bluetooth can stay on"), "says why it is safe: Bluetooth has to stay on");
        check(saysInQuestion(d, "leaves location and the screen alone"), "says what Hotspot on leaves alone");
        check("Turn it off".contentEquals(d.getButton(AlertDialog.BUTTON_POSITIVE).getText()), "the main button");
        check(d.getButton(AlertDialog.BUTTON_NEUTRAL) != null && "Hotspot only".contentEquals(d.getButton(AlertDialog.BUTTON_NEUTRAL).getText()),
                "the third button: Hotspot only");
        check("Cancel".contentEquals(d.getButton(AlertDialog.BUTTON_NEGATIVE).getText()), "Cancel");
        dialogShot("state-home-deep-question");
    }

    @Test
    public void turnItOffSendsTheDeepAction() throws Exception {
        Pretend p = onTheHotspot(true);
        MainActivity a = tablet();
        AlertDialog d = question(a, "Hotspot off");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        check(p.asked("/act?name=hotspot_off_deep"), "the deep action was sent");
        check(!p.asked("/act?name=hotspot_off"), "and not the plain one");
        mustShow(a, "Sent. Looking at the phone's status");
        shot(a, "state-home-deep-sent");
    }

    @Test
    public void hotspotOnlySendsThePlainAction() throws Exception {
        Pretend p = onTheHotspot(true);
        MainActivity a = tablet();
        AlertDialog d = question(a, "Hotspot off");
        d.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        settle();
        check(p.asked("/act?name=hotspot_off") && !p.asked("/act?name=hotspot_off_deep"), "only the plain action was sent");
    }

    /** The setting is off: the question and the action are what they always were. */
    @Test
    public void theSettingOffKeepsThePlainHotspotOff() throws Exception {
        Pretend p = onTheHotspot(true);
        Cfg.setDeepSleep(app(), false);
        MainActivity a = tablet();
        AlertDialog d = question(a, "Hotspot off");
        check(d.getButton(AlertDialog.BUTTON_NEUTRAL) == null, "no third button");
        check(saysInQuestion(d, "The phone's hotspot goes off, and the tablet loses its internet."), "the old words");
        check(!saysInQuestion(d, "airplane mode"), "airplane mode is not mentioned");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        check(p.asked("/act?name=hotspot_off") && !p.asked("/act?name=hotspot_off_deep"), "the plain action was sent");
    }

    /** An older phone app does not know the deep form: the plain action, and the question says why. */
    @Test
    public void anOlderPhoneAppGetsThePlainHotspotOff() throws Exception {
        Pretend p = onTheHotspot(false);
        MainActivity a = tablet();
        AlertDialog d = question(a, "Hotspot off");
        check(d.getButton(AlertDialog.BUTTON_NEUTRAL) == null, "no third button");
        check(saysInQuestion(d, "This phone's app is older, so it only switches the hotspot off"), "says why");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        check(p.asked("/act?name=hotspot_off") && !p.asked("/act?name=hotspot_off_deep"), "the plain action was sent");
    }

    /** No working Bluetooth link: airplane mode is never asked for, because the tablet could not wake the phone again. */
    @Test
    public void withoutABluetoothLinkTheHotspotOffStaysPlain() throws Exception {
        Pretend p = new Pretend();
        p.status = status(true, true, false, Boolean.TRUE, true);
        p.actReply = LATER;
        p.install();
        noNetworks();
        MainActivity a = tablet();
        AlertDialog d = question(a, "Hotspot off");
        check(d.getButton(AlertDialog.BUTTON_NEUTRAL) == null, "no third button");
        check(saysInQuestion(d, "Location, airplane mode and the screen are left alone this time"), "says they are left alone");
        check(saysInQuestion(d, "may have to be picked up"), "and the old warning");
        dialogShot("state-home-deep-no-link");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        check(p.asked("/act?name=hotspot_off") && !p.asked("/act?name=hotspot_off_deep"), "the plain action was sent");
    }

    // ------------------------------------------------------------------------------------------------ what the follow-up says

    /** The phone went to sleep as asked: Home says all of it from the status, and the tablet's own Wi-Fi goes off too. */
    @Test
    public void theDeepHotspotOffIsConfirmedFromTheStatus() throws Exception {
        Pretend p = onTheHotspot(true);
        wifiIs(true);
        Network hotspot = addWifiNetwork(100);
        MainActivity a = tablet();
        AlertDialog d = question(a, "Hotspot off");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        // the hotspot is gone; the phone answers over Bluetooth: airplane mode on, screen and location off
        dropNetwork(hotspot);
        p.via = "bt";
        p.status = status(false, false, true, Boolean.FALSE, true);
        later(3100);
        mustShow(a, "The hotspot and the screen are off and airplane mode is on. Location is off. The tablet's Wi-Fi is off.");
        check(!wifiIsOn(), "the tablet's Wi-Fi went off with it");
        shot(a, "state-home-deep-done");
    }

    /** The phone kept airplane mode off (Bluetooth could not stay on): the follow-up does not claim it, and gives the phone's own words. */
    @Test
    public void whenThePhoneKeptAirplaneModeOffTheTabletSaysSoWithThePhonesWords() throws Exception {
        Pretend p = onTheHotspot(true);
        wifiIs(true);
        addWifiNetwork(100);
        MainActivity a = tablet();
        AlertDialog d = question(a, "Hotspot off");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        p.via = "bt";
        p.status = status(false, false, false, Boolean.FALSE, true).replace("\"deep\":1", "\"deep\":1,\"dres\":\"Airplane mode was switched off again: Bluetooth could not stay on, and then the tablet could not wake the phone.\"");
        for (int i = 0; i < 21; i++) { // 21 x 3.1 s: past the 60 s the deep follow-up waits
            later(3100);
        }
        mustShow(a, "The phone has not confirmed it yet: its status says the hotspot is off, airplane mode is off and the screen is off, location is off. "
                + "The phone says: Airplane mode was switched off again: Bluetooth could not stay on, and then the tablet could not wake the phone.");
        mustNotShow(a, "airplane mode is on");
        check(wifiIsOn(), "nothing was confirmed: the tablet's Wi-Fi is left alone");
        shot(a, "state-home-deep-kept-awake");
    }

    // ------------------------------------------------------------------------------------------------ waking up

    /** A phone in airplane mode: Home says so, shows the two lines, and "Turn the hotspot on" ends airplane mode first. */
    @Test
    public void hotspotOnFromAirplaneModeWaitsLongerThanTheUsualForty() throws Exception {
        TabletWifi.refusedForTest = true;
        Pretend p = new Pretend();
        p.via = "bt";
        p.status = status(false, false, true, Boolean.FALSE, true);
        p.actReply = LATER;
        p.install();
        linkProved();
        noNetworks();
        wifiIs(false);
        MainActivity a = tablet();
        settle();
        mustShow(a, "The phone's hotspot is off and airplane mode is on");
        mustShow(a, "ends airplane mode first and then switches the hotspot on");
        shot(a, "state-home-asleep");
        press(a, "Turn the hotspot on");
        settle();
        check(p.asked("/act?name=hotspot_on"), "the plain Hotspot on is sent (the phone ends airplane mode itself)");
        mustShow(a, "Sent. Looking at the phone's status");
        for (int i = 0; i < 14; i++) { // 14 x 3.1 s = 43 s: past the usual 40
            later(3100);
        }
        mustShow(a, "Sent. Looking at the phone's status");
        mustNotShow(a, "The phone has not confirmed it yet");
        p.status = status(true, false, false, Boolean.FALSE, true);
        later(3100);
        mustShow(a, "The hotspot is on.");
        check(panelWasOpened(a), "and the tablet's Wi-Fi switch was opened (Android refuses the app)");
        shot(a, "state-home-woken");
    }

    /** Not in airplane mode: the usual forty seconds. */
    @Test
    public void hotspotOnWithoutAirplaneModeGivesUpAfterTheUsualForty() throws Exception {
        TabletWifi.refusedForTest = true;
        Pretend p = new Pretend();
        p.via = "bt";
        p.status = status(false, true, false, Boolean.TRUE, true);
        p.actReply = LATER;
        p.install();
        linkProved();
        noNetworks();
        wifiIs(false);
        MainActivity a = tablet();
        settle();
        press(a, "Turn the hotspot on");
        settle();
        for (int i = 0; i < 14; i++) {
            later(3100);
        }
        mustShow(a, "The phone has not confirmed it yet");
        mustNotShow(a, "Sent. Looking at the phone's status");
    }

    // ------------------------------------------------------------------------------------------------ Settings and the Phone tab

    @Test
    public void theSettingsSwitch() throws Exception {
        onTheHotspot(true);
        MainActivity a = tablet();
        a.openTab(Tab.SETTINGS);
        settle();
        TextView title = textContaining(a.getWindow().getDecorView(), "Hotspot off also puts the phone to sleep");
        check(title != null, "the switch is in Settings");
        check(Cfg.deepSleep(app()), "on by default");
        shot(a, "state-settings-deep");
        ((View) title.getParent().getParent()).performClick();
        check(!Cfg.deepSleep(app()), "the tap switched it off");
        ((View) title.getParent().getParent()).performClick();
        check(Cfg.deepSleep(app()), "and on again");
    }

    @Test
    public void thePhoneTabHasLocationButtons() throws Exception {
        Pretend p = new Pretend();
        p.status = status(true, true, false, Boolean.TRUE, true);
        p.install();
        noNetworks();
        MainActivity a = tablet();
        a.openTab(Tab.PHONE);
        settle();
        Button off = findButton(a.getWindow().getDecorView(), "Location off");
        check(off != null && off.isEnabled(), "Location off works with a phone that knows it");
        check(findButton(a.getWindow().getDecorView(), "Location on").isEnabled(), "Location on too");
        press(a, "Location off");
        settle();
        check(p.asked("/act?name=location_off"), "location_off was sent");
        shot(a, "state-phone-location");
        press(a, "Location on");
        settle();
        check(p.asked("/act?name=location_on"), "location_on was sent");
    }

    @Test
    public void anOlderPhoneAppHasNoLocationButtons() throws Exception {
        Pretend p = new Pretend();
        p.status = status(true, true, false, null, false);
        p.install();
        noNetworks();
        MainActivity a = tablet();
        a.openTab(Tab.PHONE);
        settle();
        Button off = findButton(a.getWindow().getDecorView(), "Location off");
        check(off != null && !off.isEnabled(), "Location off is greyed out for an older phone app");
        check(!findButton(a.getWindow().getDecorView(), "Location on").isEnabled(), "Location on too");
        mustShow(a, "update the phone's app");
    }
}
