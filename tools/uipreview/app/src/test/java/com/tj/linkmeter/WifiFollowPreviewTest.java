package com.tj.linkmeter;

import static com.tj.linkmeter.PreviewTest.later;
import static com.tj.linkmeter.PreviewTest.linkProved;
import static com.tj.linkmeter.PreviewTest.press;
import static com.tj.linkmeter.PreviewTest.settle;
import static com.tj.linkmeter.PreviewTest.shot;
import static com.tj.linkmeter.PreviewTest.tablet;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.tj.linkmeter.PreviewTest.Pretend;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.shadows.ShadowNetworkInfo;

/**
 * The tablet's own Wi-Fi follows the phone's hotspot, against the REAL Home tab and the real TabletWifi code: a pretend phone
 * behind {@link Call#reach}, a pretend Wi-Fi manager (it, unlike Android 10, lets the app switch Wi-Fi: {@link TabletWifi#refusedForTest}
 * stands for the real Android), a pretend list of Wi-Fi networks. Pictures go to tools/uipreview/out/state-home-wifi-*.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 29, qualifiers = "w565dp-h916dp-port-170dpi")
public class WifiFollowPreviewTest {
    private static final String LATER = "{\"ok\":true,\"later\":true,\"out\":\"Sent. The phone will do it in a moment.\"}";

    @After
    public void tearDown() {
        TabletWifi.refusedForTest = false;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    static android.app.Application app() {
        return RuntimeEnvironment.getApplication();
    }

    static WifiManager wifi() {
        return (WifiManager) app().getSystemService(Context.WIFI_SERVICE);
    }

    @SuppressWarnings("deprecation")
    static void wifiIs(boolean on) {
        wifi().setWifiEnabled(on);
    }

    static boolean wifiIsOn() {
        return wifi().isWifiEnabled();
    }

    static Network addWifiNetwork(int id) {
        ConnectivityManager cm = (ConnectivityManager) app().getSystemService(Context.CONNECTIVITY_SERVICE);
        ShadowConnectivityManager sc = shadowOf(cm);
        Network n = ShadowNetwork.newInstance(id);
        NetworkCapabilities nc = ShadowNetworkCapabilities.newInstance();
        ((ShadowNetworkCapabilities) Shadow.extract(nc)).addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        sc.addNetwork(n, ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true,
                NetworkInfo.State.CONNECTED));
        sc.setNetworkCapabilities(n, nc);
        return n;
    }

    static void noNetworks() {
        shadowOf((ConnectivityManager) app().getSystemService(Context.CONNECTIVITY_SERVICE)).clearAllNetworks();
    }

    static void dropNetwork(Network n) {
        shadowOf((ConnectivityManager) app().getSystemService(Context.CONNECTIVITY_SERVICE)).removeNetwork(n);
    }

    static TextView textContaining(View v, String part) {
        if (v instanceof TextView && v.isShown() && ((TextView) v).getText().toString().contains(part)) {
            return (TextView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView t = textContaining(g.getChildAt(i), part);
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    static void mustShow(MainActivity a, String part) {
        if (textContaining(a.getWindow().getDecorView(), part) == null) {
            throw new AssertionError("the screen does not say: " + part);
        }
    }

    static void mustNotShow(MainActivity a, String part) {
        if (textContaining(a.getWindow().getDecorView(), part) != null) {
            throw new AssertionError("the screen says, but should not: " + part);
        }
    }

    static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("FAILED: " + what);
        }
    }

    /** Was Android's own Wi-Fi switch opened (the panel)? Consumes the screens that were started. */
    static boolean panelWasOpened(MainActivity a) {
        Intent i = shadowOf(a).getNextStartedActivity();
        return i != null && Settings.Panel.ACTION_INTERNET_CONNECTIVITY.equals(i.getAction());
    }

    static boolean nothingWasOpened(MainActivity a) {
        return shadowOf(a).getNextStartedActivity() == null;
    }

    /** The phone's hotspot is off and the phone is reached over Bluetooth, as on a morning with the tablet's Wi-Fi off. */
    static Pretend hotspotOffOverBluetooth() {
        Pretend p = new Pretend();
        p.via = "bt";
        p.status = Pretend.status(false, true);
        p.install();
        linkProved();
        noNetworks();
        return p;
    }

    /** The tablet is on the phone's hotspot (network 100) and the link back over Bluetooth is proved. */
    static Pretend onTheHotspot() {
        Pretend p = new Pretend();
        p.status = Pretend.status(true, true);
        p.bluetoothToo = true;
        p.actReply = LATER;
        p.install();
        linkProved();
        noNetworks();
        return p;
    }

    /** Presses Hotspot off, answers the question with Yes, and lets the phone's "Sent" arrive. */
    static void hotspotOffYes(MainActivity a, String button) throws Exception {
        settle();
        press(a, button);
        settle();
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        check(d != null && d.isShowing(), "the question is open");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
    }

    // ------------------------------------------------------------------------------------------------ Hotspot on

    /** Where Android lets the app switch Wi-Fi (an older Android, or Shizuku on the tablet): Wi-Fi comes on by itself, and Home says so. */
    @Test
    public void hotspotOnSwitchesTheWifiOn() throws Exception {
        hotspotOffOverBluetooth();
        wifiIs(false);
        MainActivity a = tablet();
        settle();
        press(a, "Turn the hotspot on");
        settle();
        check(wifiIsOn(), "the tablet's Wi-Fi is on after Hotspot on");
        mustShow(a, "Done. The tablet's Wi-Fi is on.");
        check(nothingWasOpened(a), "no panel when the switch worked");
        shot(a, "state-home-wifi-switched-on");
    }

    /** On Android 10 the app is refused: Android's own Wi-Fi switch is opened, and Home says what it did and did not do. */
    @Test
    public void hotspotOnOpensAndroidsSwitchWhenAndroidRefuses() throws Exception {
        TabletWifi.refusedForTest = true;
        hotspotOffOverBluetooth();
        wifiIs(false);
        MainActivity a = tablet();
        settle();
        press(a, "Turn the hotspot on");
        settle();
        check(!wifiIsOn(), "nothing was switched: the app was refused");
        check(panelWasOpened(a), "Android's Wi-Fi switch was opened for the user's tap");
        mustShow(a, "Switch the tablet's Wi-Fi on in the panel that opened");
        mustNotShow(a, "The tablet's Wi-Fi is on.");
        shot(a, "state-home-wifi-asked");
    }

    /** The same button on the Home card's twin ("Hotspot on" in the Switches card) after the card was used once: still one switch. */
    @Test
    public void hotspotOnFromTheSwitchesCard() throws Exception {
        TabletWifi.refusedForTest = true;
        Pretend p = hotspotOffOverBluetooth();
        p.status = Pretend.status(false, false);
        wifiIs(false);
        MainActivity a = tablet();
        settle();
        press(a, "Hotspot on");
        settle();
        check(panelWasOpened(a), "Android's Wi-Fi switch was opened");
        mustShow(a, "Switch the tablet's Wi-Fi on in the panel that opened");
    }

    /** A switch the phone refused: nothing is done to the tablet's Wi-Fi. */
    @Test
    public void aSwitchThatDidNotMoveLeavesTheWifiAlone() throws Exception {
        TabletWifi.refusedForTest = true;
        Pretend p = hotspotOffOverBluetooth();
        p.actCode = 409;
        p.actReply = "{\"err\":\"The hotspot could not be switched on: the phone refused the request.\"}";
        wifiIs(false);
        MainActivity a = tablet();
        settle();
        press(a, "Turn the hotspot on");
        settle();
        check(!wifiIsOn(), "the Wi-Fi was not switched");
        check(nothingWasOpened(a), "no panel for a switch that failed");
        mustShow(a, "The hotspot could not be switched on");
        mustNotShow(a, "The tablet's Wi-Fi is on");
        mustNotShow(a, "in the panel that opened");
    }

    /** The setting is off: the buttons work as they always did. */
    @Test
    public void theSettingOffLeavesTheWifiAlone() throws Exception {
        hotspotOffOverBluetooth();
        Cfg.setWifiFollow(app(), false);
        wifiIs(false);
        MainActivity a = tablet();
        settle();
        press(a, "Turn the hotspot on");
        settle();
        check(!wifiIsOn(), "the Wi-Fi was not switched");
        check(nothingWasOpened(a), "nothing opened");
        mustShow(a, "Done.");
        mustNotShow(a, "The tablet's Wi-Fi is on");
    }

    /** The tablet's Wi-Fi was on already: nothing to do, nothing said. */
    @Test
    public void aWifiThatIsOnAlreadyIsLeftAlone() throws Exception {
        TabletWifi.refusedForTest = true;
        hotspotOffOverBluetooth();
        wifiIs(true);
        MainActivity a = tablet();
        settle();
        press(a, "Turn the hotspot on");
        settle();
        check(nothingWasOpened(a), "no panel when Wi-Fi is on already");
        mustShow(a, "Done.");
        mustNotShow(a, "Wi-Fi is on.");
        mustNotShow(a, "in the panel");
    }

    // ------------------------------------------------------------------------------------------------ Hotspot off and Standby

    /** On the phone's hotspot, Hotspot off: once the phone says it is off, the tablet's Wi-Fi goes off too. */
    @Test
    public void hotspotOffSwitchesTheWifiOff() throws Exception {
        Pretend p = onTheHotspot();
        wifiIs(true);
        Network hotspot = addWifiNetwork(100);
        MainActivity a = tablet();
        hotspotOffYes(a, "Hotspot off");
        check(wifiIsOn(), "not before the phone has said anything");
        dropNetwork(hotspot); // the hotspot is gone, and the phone answers over Bluetooth that it is off
        p.via = "bt";
        p.status = Pretend.status(false, true);
        later(3100);
        check(!wifiIsOn(), "the tablet's Wi-Fi is off after Hotspot off");
        mustShow(a, "The hotspot is off. The tablet's Wi-Fi is off.");
        shot(a, "state-home-wifi-switched-off");
    }

    /** Android refuses: its switch is opened, and Home does not claim the Wi-Fi is off. */
    @Test
    public void hotspotOffOpensAndroidsSwitchWhenAndroidRefuses() throws Exception {
        TabletWifi.refusedForTest = true;
        Pretend p = onTheHotspot();
        wifiIs(true);
        Network hotspot = addWifiNetwork(100);
        MainActivity a = tablet();
        hotspotOffYes(a, "Hotspot off");
        dropNetwork(hotspot);
        p.via = "bt";
        p.status = Pretend.status(false, true);
        later(3100);
        check(wifiIsOn(), "the app was refused: still on");
        check(panelWasOpened(a), "Android's Wi-Fi switch was opened");
        mustShow(a, "Switch the tablet's Wi-Fi off in the panel that opened");
        mustNotShow(a, "The tablet's Wi-Fi is off.");
        shot(a, "state-home-wifi-off-asked");
    }

    /** The hotspot's network is still there (it takes a moment to be given up) when the phone confirms: still the hotspot's. */
    @Test
    public void theDyingHotspotNetworkIsTheHotspots() throws Exception {
        Pretend p = onTheHotspot();
        wifiIs(true);
        addWifiNetwork(100);
        MainActivity a = tablet();
        hotspotOffYes(a, "Hotspot off");
        p.via = "bt";
        p.status = Pretend.status(false, true);
        later(3100);
        check(!wifiIsOn(), "the same network as at the press is still the hotspot's: Wi-Fi off");
    }

    /** The tablet fell onto another network (the home router) after the hotspot went away: that network is not ours to cut. */
    @Test
    public void hotspotOffLeavesAnotherNetworkAlone() throws Exception {
        Pretend p = onTheHotspot();
        wifiIs(true);
        Network hotspot = addWifiNetwork(100);
        MainActivity a = tablet();
        hotspotOffYes(a, "Hotspot off");
        dropNetwork(hotspot);
        addWifiNetwork(101); // the home router
        p.via = "bt";
        p.status = Pretend.status(false, true);
        later(3100);
        check(wifiIsOn(), "the tablet is on another network: Wi-Fi stays on");
        check(nothingWasOpened(a), "no panel either");
        mustShow(a, "The hotspot is off. The tablet's Wi-Fi was left on: the tablet is on another network.");
        shot(a, "state-home-wifi-left-on");
    }

    /** Hotspot also on, but the tablet is on the home router (the phone is reached over Bluetooth): Hotspot off must not cut the router. */
    @Test
    public void hotspotOffWhileOnTheRouterLeavesIt() throws Exception {
        Pretend p = new Pretend();
        p.via = "bt";
        p.status = Pretend.status(true, true);
        p.actReply = "{\"ok\":true,\"out\":\"The hotspot is off.\"}";
        p.install();
        linkProved();
        noNetworks();
        wifiIs(true);
        addWifiNetwork(101); // the home router
        MainActivity a = tablet();
        settle();
        press(a, "Hotspot off");
        settle();
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        check(d != null && d.isShowing(), "the question is open");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        check(wifiIsOn(), "the router keeps its Wi-Fi");
        check(nothingWasOpened(a), "no panel");
        mustShow(a, "The hotspot is off. The tablet's Wi-Fi was left on: the tablet is on another network.");
    }

    /** Standby switches the hotspot off with the screen: the tablet's Wi-Fi goes off like after Hotspot off. */
    @Test
    public void standbySwitchesTheWifiOffToo() throws Exception {
        Pretend p = onTheHotspot();
        wifiIs(true);
        Network hotspot = addWifiNetwork(100);
        MainActivity a = tablet();
        hotspotOffYes(a, "Standby");
        dropNetwork(hotspot);
        p.via = "bt";
        p.status = Pretend.status(false, false);
        later(3100);
        check(!wifiIsOn(), "the tablet's Wi-Fi is off after Standby");
        mustShow(a, "The tablet's Wi-Fi is off.");
    }

    /** The screen is left before the phone has confirmed: nothing is known, so the tablet's Wi-Fi is not touched. */
    @Test
    public void leavingTheScreenConfirmsNothing() throws Exception {
        TabletWifi.refusedForTest = false;
        Pretend p = onTheHotspot();
        wifiIs(true);
        addWifiNetwork(100);
        MainActivity a = tablet();
        hotspotOffYes(a, "Hotspot off");
        a.openTab(Tab.SPEED);
        settle();
        p.via = "bt";
        p.status = Pretend.status(false, true);
        later(3100);
        a.openTab(Tab.HOME);
        later(3100);
        check(wifiIsOn(), "nobody confirmed the switch: Wi-Fi stays on");
        check(nothingWasOpened(a), "no panel");
        mustNotShow(a, "The tablet's Wi-Fi is off");
    }

    /** The phone never confirmed (its status still says the hotspot is on): the follow-up gives up, and the Wi-Fi stays. */
    @Test
    public void aGivenUpFollowUpLeavesTheWifiAlone() throws Exception {
        onTheHotspot();
        wifiIs(true);
        addWifiNetwork(100);
        MainActivity a = tablet();
        hotspotOffYes(a, "Hotspot off");
        for (int i = 0; i < 14; i++) { // 14 x 3.1 s: past the 40 s the follow-up waits, with the phone still saying the hotspot is on
            later(3100);
        }
        check(wifiIsOn(), "never confirmed: Wi-Fi stays on");
        mustNotShow(a, "The tablet's Wi-Fi is off");
    }

    // ------------------------------------------------------------------------------------------------ the card, the meter, Settings

    /** The hotspot is on but the tablet's Wi-Fi is off: the card says why the tablet has not joined, and has the switch. */
    @Test
    public void theCardWhenTheHotspotIsOnAndTheTabletsWifiIsOff() throws Exception {
        TabletWifi.refusedForTest = true;
        Pretend p = new Pretend();
        p.via = "bt";
        p.status = Pretend.status(true, true);
        p.install();
        linkProved();
        noNetworks();
        wifiIs(false);
        MainActivity a = tablet();
        settle();
        mustShow(a, "The hotspot is on, but the tablet's Wi-Fi is off");
        mustNotShow(a, "joins the phone's hotspot by itself");
        // (the meter "Tablet Wi-Fi to phone: Off" is drawn, not a text view: look at the picture)
        shot(a, "state-home-wifi-off-card");
        press(a, "Switch Wi-Fi on");
        settle();
        check(panelWasOpened(a), "Android's Wi-Fi switch was opened");
        mustShow(a, "Switch the tablet's Wi-Fi on in the panel that opened");
    }

    /** The same card where the app is allowed to switch: Wi-Fi comes on, and the card gives way to the usual one. */
    @Test
    public void theCardsButtonSwitchesTheWifiOnWhereAndroidAllows() throws Exception {
        Pretend p = new Pretend();
        p.via = "bt";
        p.status = Pretend.status(true, true);
        p.install();
        linkProved();
        noNetworks();
        wifiIs(false);
        MainActivity a = tablet();
        settle();
        press(a, "Switch Wi-Fi on");
        settle();
        check(wifiIsOn(), "the tablet's Wi-Fi is on");
        mustShow(a, "The tablet's Wi-Fi is on.");
        mustNotShow(a, "The hotspot is on, but the tablet's Wi-Fi is off");
    }

    /** With the Wi-Fi on but not joined the old card stays (the tablet really does join by itself then). */
    @Test
    public void theUsualCardWhenTheWifiIsOn() throws Exception {
        Pretend p = new Pretend();
        p.via = "bt";
        p.status = Pretend.status(true, true);
        p.install();
        linkProved();
        noNetworks();
        wifiIs(true);
        MainActivity a = tablet();
        settle();
        mustShow(a, "The hotspot is on, the tablet has not joined it yet");
        mustNotShow(a, "Wi-Fi is off");
    }

    /** The switch in Settings: on by default, and what it stores. */
    @Test
    public void theSettingsSwitch() throws Exception {
        Pretend p = new Pretend();
        p.install();
        linkProved();
        MainActivity a = tablet();
        a.openTab(Tab.SETTINGS);
        settle();
        TextView title = textContaining(a.getWindow().getDecorView(), "Switch the tablet's Wi-Fi with the hotspot");
        check(title != null, "the switch is in Settings");
        check(Cfg.wifiFollow(app()), "on by default");
        shot(a, "state-settings-wifi-follow");
        ((View) title.getParent().getParent()).performClick(); // the whole line is the switch
        check(!Cfg.wifiFollow(app()), "the tap switched it off");
        ((View) title.getParent().getParent()).performClick();
        check(Cfg.wifiFollow(app()), "and on again");
    }

    // ------------------------------------------------------------------------------------------------ edges found by reading the code

    /**
     * "Sent" is not a result. Hotspot off over the hotspot is answered "Sent" (the phone acts afterwards); if the Home screen is
     * already left when that answer arrives, nobody looks for the result, so the tablet's Wi-Fi must not be touched.
     */
    @Test
    public void aSentAnswerAfterTheScreenWasLeftConfirmsNothing() throws Exception {
        Pretend p = onTheHotspot();
        wifiIs(true);
        addWifiNetwork(100);
        MainActivity a = tablet();
        settle();
        press(a, "Hotspot off");
        settle();
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        check(d != null && d.isShowing(), "the question is open");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        a.openTab(Tab.SPEED); // the screen is left at once: the phone's "Sent" arrives after that
        settle();
        p.via = "bt";
        p.status = Pretend.status(false, true);
        later(3100);
        a.openTab(Tab.HOME);
        later(3100);
        check(wifiIsOn(), "only \"Sent\" arrived, nobody confirmed it: the Wi-Fi stays on");
        check(nothingWasOpened(a), "no panel");
        mustNotShow(a, "The tablet's Wi-Fi is off");
    }

    /** The app goes out of sight while the phone is working: Android 10 would not show a screen, so none is opened (and it says so). */
    @Test
    public void theWifiSwitchIsNotOpenedWhileTheAppIsOutOfSight() throws Exception {
        TabletWifi.refusedForTest = true;
        Pretend p = hotspotOffOverBluetooth();
        p.btDelayMs = 1500;
        wifiIs(false);
        MainActivity a = tablet();
        Thread.sleep(2000); // the first status comes over Bluetooth, which takes 1.5 s here
        settle();
        press(a, "Turn the hotspot on");
        a.onPause(); // the user went to another app; the phone's answer arrives afterwards
        Thread.sleep(2000);
        settle();
        check(!wifiIsOn(), "the app was refused: still off");
        check(nothingWasOpened(a), "no screen opened by an app that is out of sight");
        mustShow(a, "could not switch the tablet's Wi-Fi on");
    }

    /** The card's "Switch Wi-Fi on" waits while a switch of the phone is on its way (the panel would pause the app and end that call). */
    @Test
    public void theCardsButtonWaitsForASwitchOnItsWay() throws Exception {
        Pretend p = new Pretend();
        p.via = "bt";
        p.status = Pretend.status(true, true);
        p.btDelayMs = 2500;
        p.install();
        linkProved();
        noNetworks();
        wifiIs(false);
        MainActivity a = tablet();
        Thread.sleep(3000); // the first status comes over Bluetooth, which takes 2.5 s here
        settle();
        check(PreviewTest.findButton(a.getWindow().getDecorView(), "Switch Wi-Fi on").isEnabled(), "the card's button works when nothing is on its way");
        press(a, "Standby");
        Thread.sleep(3000); // the live test of the link before Standby
        settle();
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        check(d != null && d.isShowing(), "the question is open");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle(); // Standby is on its way now (2.5 s)
        android.widget.Button b = PreviewTest.findButton(a.getWindow().getDecorView(), "Switch Wi-Fi on");
        check(b != null && !b.isEnabled(), "the card's button is greyed out while Standby is on its way");
        Thread.sleep(3000);
        settle();
    }
}
