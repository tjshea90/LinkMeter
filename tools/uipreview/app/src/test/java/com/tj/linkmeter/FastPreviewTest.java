package com.tj.linkmeter;

import static com.tj.linkmeter.PreviewTest.findText;
import static com.tj.linkmeter.PreviewTest.press;
import static com.tj.linkmeter.PreviewTest.settle;
import static com.tj.linkmeter.PreviewTest.shot;
import static com.tj.linkmeter.PreviewTest.tablet;

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

/**
 * Fast Wi-Fi's card on the tablet's Home tab (docs/FAST_WIFI.md), against the REAL Home tab and a pretend phone: each state the card
 * can be in, what its button sends, and that "Hotspot" switches Fast Wi-Fi off. The VPN service itself cannot run here (Robolectric
 * has no VPN): its state is set as the service would set it. Pictures go to tools/uipreview/out/fast-*.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 29, qualifiers = "w565dp-h916dp-port-170dpi")
public class FastPreviewTest {
    @After
    public void tearDown() {
        FastVpn.state = FastVpn.State.OFF;
        FastVpn.detail = "";
        Cfg.setFastWanted(RuntimeEnvironment.getApplication(), false);
        Cfg.setFastLink(RuntimeEnvironment.getApplication(), FastRules.LINK_HOTSPOT);
        Cfg.setFastLog(RuntimeEnvironment.getApplication(), "");
    }

    /** The pretend phone's status with Fast Wi-Fi's part (null: an older phone app without it). */
    static Pretend phone(boolean hotspot, String fast) {
        Pretend p = new Pretend();
        String s = Pretend.status(hotspot, true);
        if (fast != null) {
            s = s.replace("\"bt\":\"on\"", "\"bt\":\"on\",\"fast\":" + fast);
        }
        p.status = s;
        p.install();
        return p;
    }

    static final String READY = "{\"v\":1,\"port\":8767,\"allowed\":true,\"on\":true,\"conns\":0}";

    static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    /** Some visible text on the screen contains these words. */
    static boolean shows(View v, String words) {
        if (v instanceof TextView && v.isShown() && ((TextView) v).getText().toString().contains(words)) {
            return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (shows(g.getChildAt(i), words)) {
                    return true;
                }
            }
        }
        return false;
    }

    static View root(MainActivity a) {
        return a.getWindow().getDecorView();
    }

    @Test
    public void offShowsTheNormalHotspot() throws Exception {
        phone(true, READY);
        MainActivity a = tablet();
        settle();
        check(shows(root(a), "uses the phone's hotspot as usual"), "Hotspot chosen: the card says the normal hotspot is used");
        check(!shows(root(a), "Update LinkMeter on the phone"), "a phone that can relay is not told to update");
        shot(a, "fast-off");
    }

    @Test
    public void onSaysSo() throws Exception {
        phone(true, READY);
        Cfg.setFastWanted(RuntimeEnvironment.getApplication(), true);
        FastVpn.state = FastVpn.State.ON;
        MainActivity a = tablet();
        settle();
        check(shows(root(a), "Fast Wi-Fi is on"), "on: the card says so");
        shot(a, "fast-on");
    }

    @Test
    public void hotspotOffOffersTheHotspot() throws Exception {
        Pretend p = phone(false, READY);
        Cfg.setFastWanted(RuntimeEnvironment.getApplication(), true);
        FastVpn.state = FastVpn.State.WAITING;
        FastVpn.detail = "The phone does not answer.";
        MainActivity a = tablet();
        settle();
        check(shows(root(a), "the hotspot is off"), "hotspot off: the card says so");
        shot(a, "fast-hotspot-off");
        press(a, "Turn the hotspot on");
        settle();
        check(p.asked("/act?name=hotspot_on"), "its button sends Home's own Hotspot on");
    }

    @Test
    public void needsAndroidsOk() throws Exception {
        phone(true, READY);
        Cfg.setFastWanted(RuntimeEnvironment.getApplication(), true);
        FastVpn.state = FastVpn.State.PERMISSION;
        MainActivity a = tablet();
        settle();
        check(shows(root(a), "Android asks once"), "the VPN question is explained");
        check(PreviewTest.findButton(root(a), "Give the OK") != null, "with its button");
        shot(a, "fast-permission");
    }

    @Test
    public void olderPhoneApp() throws Exception {
        phone(true, null);
        Cfg.setFastWanted(RuntimeEnvironment.getApplication(), true);
        FastVpn.state = FastVpn.State.WAITING;
        FastVpn.detail = "LinkMeter on the phone does not offer Fast Wi-Fi yet.";
        MainActivity a = tablet();
        settle();
        check(shows(root(a), "Update LinkMeter on the phone"), "an older phone app: update it");
        shot(a, "fast-old-phone");
    }

    @Test
    public void switchedOffOnThePhone() throws Exception {
        Pretend p = phone(true, "{\"v\":1,\"port\":8767,\"allowed\":false,\"on\":false}"); // the pretend phone answers this action "Done."
        Cfg.setFastWanted(RuntimeEnvironment.getApplication(), true);
        FastVpn.state = FastVpn.State.WAITING;
        MainActivity a = tablet();
        settle();
        check(shows(root(a), "switched off on the phone"), "the phone's switch is off: said");
        shot(a, "fast-not-allowed");
        press(a, "Allow it on the phone");
        settle();
        check(p.asked("/act?name=fast_allow"), "its button asks the phone to allow it");
        check(shows(root(a), "Done."), "the phone's answer is shown");
    }

    @Test
    public void hotspotChoiceSwitchesFastWifiOff() throws Exception {
        phone(true, READY);
        Cfg.setFastWanted(RuntimeEnvironment.getApplication(), true);
        FastVpn.state = FastVpn.State.WAITING;
        MainActivity a = tablet();
        settle();
        press(a, "Hotspot");
        settle();
        check(!Cfg.fastWanted(RuntimeEnvironment.getApplication()), "Hotspot switches Fast Wi-Fi off (it stays off after a restart)");
        check(shows(root(a), "uses the phone's hotspot as usual"), "and the card says so at once");
        check(Cfg.fastLog(RuntimeEnvironment.getApplication()).contains("Switched off by you (Home)"), "the history says the user did it");
    }

    // ------------------------------------------------------------------------------------------------ stays on until the user switches it off

    @Test
    public void androidTookTheVpnAwayTheChoiceStays() throws Exception {
        phone(true, READY);
        Cfg.setFastWanted(RuntimeEnvironment.getApplication(), true);
        FastVpn.state = FastVpn.State.PERMISSION;
        FastVpn.detail = FastRules.revokedWords(false);
        MainActivity a = tablet();
        settle();
        check(shows(root(a), "Android turned Fast Wi-Fi's VPN off"), "the card says what Android did");
        check(shows(root(a), "Your choice is kept"), "and that the choice is kept");
        check(PreviewTest.findButton(root(a), "Give the OK") != null, "with the button that brings it back");
        check(!shows(root(a), "uses the phone's hotspot as usual"), "it does not pretend Hotspot was chosen");
        check(Cfg.fastWanted(RuntimeEnvironment.getApplication()), "the choice is still on");
        shot(a, "fast-revoked");
    }

    /** The REAL service: Android calls onRevoke; the choice must survive it (it used to be cleared, and the service stopped). */
    @Test
    public void onRevokeKeepsTheChoice() throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication();
        Cfg.setFastWanted(app, true);
        FastVpn svc = org.robolectric.Robolectric.buildService(FastVpn.class).create().get();
        svc.onRevoke();
        long end = System.currentTimeMillis() + 8000;
        while (FastVpn.state == FastVpn.State.OFF && System.currentTimeMillis() < end) {
            Thread.sleep(20); // the service's own thread answers
        }
        boolean wanted = Cfg.fastWanted(app);
        FastVpn.State st = FastVpn.state;
        String detail = FastVpn.detail;
        String log = Cfg.fastLog(app);
        svc.onDestroy();
        check(wanted, "after Android took the VPN away the choice is still on");
        check(st != FastVpn.State.PERMISSION || detail.contains("Your choice is kept"), "if it asks for the OK, it says why: " + detail);
        check(st == FastVpn.State.WAITING || st == FastVpn.State.PERMISSION, "and the service says it is putting it back or asks for the OK, not 'off': " + st);
        check(log.contains("Android"), "the history says what Android did: " + log);
    }

    /** The notification's "Turn off" is the user's own switch: it clears the choice, stops the service and says who did it. */
    @Test
    public void notificationTurnOffSwitchesItOff() throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication();
        Cfg.setFastWanted(app, true);
        FastVpn svc = org.robolectric.Robolectric.buildService(FastVpn.class).create().get();
        svc.onStartCommand(new android.content.Intent(app, FastVpn.class).setAction(FastVpn.STOP).putExtra("by", "the notification"), 0, 1);
        boolean wanted = Cfg.fastWanted(app);
        String log = Cfg.fastLog(app);
        svc.onDestroy();
        check(!wanted, "'Turn off' in the notification switches the choice off");
        check(log.contains("Switched off by you (the notification)"), "and the history says who: " + log);
    }

    /** "No" to Android's VPN question is the user's answer: it stays off. (Cancelled while Android allows it anyway: it goes on.) */
    @Test
    public void answeringNoKeepsItOff() throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication();
        phone(true, READY);
        MainActivity a = tablet();
        settle();
        Cfg.setFastWanted(app, true);
        boolean allowed = FastVpn.consent(app);
        a.onActivityResult(11, android.app.Activity.RESULT_CANCELED, null); // REQ_VPN
        if (allowed) {
            check(Cfg.fastWanted(app), "Android allows it anyway: a 'cancelled' of an older question does not switch it off");
        } else {
            check(!Cfg.fastWanted(app), "No to Android's question: Fast Wi-Fi stays off");
            check(Cfg.fastLog(app).contains("You answered No"), "and the history says so: " + Cfg.fastLog(app));
        }
    }

    /** The service is gone but the choice is on (the app was stopped): opening the app starts it again, or asks for the OK. */
    @Test
    public void wantedButGoneComesBack() throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication();
        phone(true, READY);
        Cfg.setFastWanted(app, true);
        FastVpn.state = FastVpn.State.OFF;
        MainActivity a = tablet();
        settle();
        boolean started = org.robolectric.Shadows.shadowOf(app).peekNextStartedService() != null;
        boolean asks = shows(root(a), "Give the OK");
        check(started || asks, "the service was started again, or the card asks for Android's OK (not 'starting' for ever)");
        check(Cfg.fastWanted(app), "the choice is still on");
    }

    @Test
    public void settingsSaysWhatChangedItLately() throws Exception {
        phone(true, READY);
        Cfg.setFastLog(RuntimeEnvironment.getApplication(), "15 Nov 14:32 Android took the VPN away (another VPN app, or Android's VPN settings): putting it back.");
        MainActivity a = tablet();
        a.openTab(Tab.SETTINGS);
        settle();
        check(findText(root(a), "What changed it lately") != null, "Settings has the history");
        check(shows(root(a), "15 Nov 14:32 Android took the VPN away"), "with its lines");
        PreviewTest.shot(a, "fast-settings-history");
    }

    // ------------------------------------------------------------------------------------------------ stage 2: the phone's own network

    static final String DIRECT_OFF = "{\"v\":1,\"port\":8767,\"allowed\":true,\"on\":true,\"direct\":false,\"dup\":false,"
            + "\"ssid\":\"DIRECT-LM-LinkMeter\",\"pass\":\"abcdefgh2345mnpq\"}";
    static final String DIRECT_UP = DIRECT_OFF.replace("\"direct\":false,\"dup\":false", "\"direct\":true,\"dup\":true");

    @Test
    public void directLinkSwitchesThePhone() throws Exception {
        Pretend p = phone(true, DIRECT_OFF);
        Cfg.setFastLink(RuntimeEnvironment.getApplication(), FastRules.LINK_DIRECT);
        MainActivity a = tablet();
        settle();
        check(Cfg.fastSsid(RuntimeEnvironment.getApplication()).equals("DIRECT-LM-LinkMeter"), "the tablet keeps the network's name");
        check(Cfg.fastPass(RuntimeEnvironment.getApplication()).equals("abcdefgh2345mnpq"), "and its password");
        press(a, "Fast Wi-Fi");
        settle();
        check(Cfg.fastWanted(RuntimeEnvironment.getApplication()), "Fast Wi-Fi is wanted");
        check(p.asked("/act?name=fast_direct_on"), "the phone is asked to switch its hotspot off and its own network on");
        shot(a, "fast-direct-switching");
    }

    @Test
    public void directNetworkUpTabletJoining() throws Exception {
        phone(false, DIRECT_UP);
        Cfg.setFastLink(RuntimeEnvironment.getApplication(), FastRules.LINK_DIRECT);
        Cfg.setFastWanted(RuntimeEnvironment.getApplication(), true);
        FastVpn.state = FastVpn.State.WAITING;
        MainActivity a = tablet();
        settle();
        check(shows(root(a), "The phone's own Wi-Fi network (DIRECT-LM-LinkMeter) is on"), "the card names the network");
        check(PreviewTest.findButton(root(a), "Wi-Fi settings") != null, "with Wi-Fi settings at hand");
        check(!shows(root(a), "Fast Wi-Fi runs over the phone's hotspot, and the hotspot is off"), "no hotspot complaint over this link");
        shot(a, "fast-direct-joining");
    }

    @Test
    public void hotspotChoiceEndsTheOwnNetwork() throws Exception {
        Pretend p = phone(false, DIRECT_UP);
        Cfg.setFastLink(RuntimeEnvironment.getApplication(), FastRules.LINK_DIRECT);
        Cfg.setFastWanted(RuntimeEnvironment.getApplication(), true);
        FastVpn.state = FastVpn.State.ON;
        MainActivity a = tablet();
        settle();
        press(a, "Hotspot");
        settle();
        check(!Cfg.fastWanted(RuntimeEnvironment.getApplication()), "Fast Wi-Fi is off");
        check(p.asked("/act?name=fast_direct_off"), "the phone is asked to switch its own network off and the hotspot on");
    }

    @Test
    public void joiningBannerOverBluetooth() throws Exception {
        Pretend p = phone(false, DIRECT_UP);
        p.via = "bt";
        PreviewTest.linkProved();
        Cfg.setFastLink(RuntimeEnvironment.getApplication(), FastRules.LINK_DIRECT);
        Cfg.setFastWanted(RuntimeEnvironment.getApplication(), true);
        FastVpn.state = FastVpn.State.WAITING;
        MainActivity a = tablet();
        settle();
        check(shows(root(a), "The tablet is joining Fast Wi-Fi's network"), "over Bluetooth: the banner says the hotspot is off on purpose");
        check(findText(root(a), "The phone's hotspot is off") == null, "and not the old title that calls the hotspot off a problem");
        check(PreviewTest.findButton(root(a), "Turn the hotspot on") == null, "nor its button");
        shot(a, "fast-direct-banner");
    }

    @Test
    public void settingsCard() throws Exception {
        phone(true, READY);
        MainActivity a = tablet();
        a.openTab(Tab.SETTINGS);
        settle();
        check(findText(root(a), "Steer apps to their faster way (recommended)") != null, "the QUIC choice is in Settings");
        check(PreviewTest.findButton(root(a), "Its own Wi-Fi") != null, "and the choice of Fast Wi-Fi's Wi-Fi");
        PreviewTest.shot(a, "fast-settings");
    }
}
