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
    }

    @Test
    public void settingsCard() throws Exception {
        phone(true, READY);
        MainActivity a = tablet();
        a.openTab(Tab.SETTINGS);
        settle();
        check(findText(root(a), "Steer apps to their faster way (recommended)") != null, "the QUIC choice is in Settings");
        PreviewTest.shot(a, "fast-settings");
    }
}
