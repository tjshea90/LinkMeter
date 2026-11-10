package com.tj.linkmeter;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSystemClock;

/**
 * Draws the app's screens to PNG files (tools/uipreview/out), so they can be looked at without a device. The user's tablet is
 * 600 x 973 pixels at 170 dpi with font scale 1.2, and that is what is drawn. A pretend phone stands behind {@link Call#reach},
 * so the screens can be shown in their real states: reached over the hotspot, over Bluetooth, or not at all.
 * Long screens are drawn a second time scrolled to their end ("-2").
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 29, qualifiers = "w565dp-h916dp-port-170dpi")
public class PreviewTest {
    static final String STATUS = "{\"bat\":76,\"chg\":false,\"tmp\":29.5,\"vc\":120,\"vn\":\"0.2.120\",\"priv\":2,\"eco\":true,\"ecoAuto\":true,"
            + "\"hs\":%HS%,\"wifi\":false,\"scr\":%SCR%,\"air\":false,\"bt\":\"on\",\"btmac\":\"5C:F3:70:8A:1B:2C\",\"btname\":\"Galaxy A15 5G\","
            + "\"lvl\":2,\"dbm\":-104,\"net\":\"5G (NSA)\"}";
    static final String CELLS = "{\"carriers\":[{\"rat\":\"LTE\",\"band\":66,\"ch\":66611,\"bw\":15000,\"primary\":1},"
            + "{\"rat\":\"NR\",\"band\":77,\"ch\":650000,\"bw\":60000,\"primary\":0}],\"display\":\"NR_ADVANCED\",\"ca\":true,\"nr\":\"CONNECTED\","
            + "\"cells\":[{\"t\":\"LTE\",\"reg\":1,\"bands\":[66],\"earfcn\":66611,\"rsrp\":-104,\"rsrq\":-11,\"lvl\":2},"
            + "{\"t\":\"NR\",\"reg\":0,\"bands\":[77],\"rsrp\":-110}]}";

    static final String MAC = "5C:F3:70:8A:1B:2C";

    /** The pretend phone behind {@link Call#reach}: what it answers, and over which route. Change the fields, then {@link #install}. */
    static final class Pretend {
        /** "wifi", "bt" or "": the route the tablet has to the phone. */
        String via = "wifi";
        /** With via "wifi": the Bluetooth link is set up as well (for the live test before the hotspot is switched off). */
        boolean bluetoothToo;
        /** With via "wifi": the hotspot route cannot connect (nothing is sent), as when the phone is busy or out of reach. */
        boolean wifiFails;
        /** A call over Bluetooth takes this long, and fails if btFails. */
        long btDelayMs;
        boolean btFails;
        /** The tablet's own Bluetooth is switched off: a status read leaves it off. */
        boolean btOffHere;
        String status = status(true, true);
        int statusCode = 200;
        String actReply = "{\"ok\":true,\"out\":\"Done.\"}";
        int actCode = 200;
        /** How often the status was asked for. */
        final java.util.concurrent.atomic.AtomicInteger statusAsked = new java.util.concurrent.atomic.AtomicInteger();

        static String status(boolean hotspotOn, boolean screenOn) {
            return STATUS.replace("%HS%", String.valueOf(hotspotOn)).replace("%SCR%", String.valueOf(screenOn));
        }

        void install() {
            final Pretend p = this;
            Call.reach = new Reach(new Reach.Wifi() {
                @Override
                public boolean known() {
                    return p.via.equals("wifi");
                }

                @Override
                public Client.Resp call(String method, String target, int readMs) throws IOException {
                    if (p.wifiFails) {
                        throw new Client.ConnectFailed(new IOException("failed to connect"));
                    }
                    return p.answer(target);
                }
            }, new Reach.Bluetooth() {
                @Override
                public boolean ready() {
                    return p.via.equals("bt") || p.bluetoothToo;
                }

                @Override
                public Client.Resp call(String method, String target, int readMs, boolean pressed) throws IOException {
                    if (p.btOffHere && !pressed) {
                        throw new Reach.BluetoothOff();
                    }
                    try {
                        Thread.sleep(p.btDelayMs);
                    } catch (InterruptedException e) {
                        throw new IOException("interrupted");
                    }
                    if (p.btFails) {
                        throw new IOException("the phone did not answer the Bluetooth call (is it in range, with Bluetooth on and LinkMeter running?)");
                    }
                    return p.answer(target);
                }
            }, android.os.SystemClock::elapsedRealtime); // the clock the app itself gives Reach (Call.reach)
        }

        Client.Resp answer(String target) {
            if (target.equals("/s")) {
                statusAsked.incrementAndGet();
            }
            if (target.equals("/s") && statusCode != 200) {
                Client.Resp r = new Client.Resp();
                r.code = statusCode;
                r.body = "{\"err\":\"not paired\"}";
                return r;
            }
            if (target.startsWith("/act?name=hotspot") || target.startsWith("/act?name=standby")) {
                Client.Resp r = new Client.Resp();
                r.code = actCode;
                r.body = actReply;
                return r;
            }
            return PreviewTest.answer(target, status);
        }
    }

    /** via: "wifi", "bt" or "" (the phone cannot be reached). */
    static void pretendPhone(final String via, final boolean hotspotOn, final boolean screenOn) {
        Pretend p = new Pretend();
        p.via = via;
        p.status = Pretend.status(hotspotOn, screenOn);
        p.install();
    }

    static Client.Resp answer(String target, String status) {
        Client.Resp r = new Client.Resp();
        r.code = 200;
        if (target.equals("/s")) {
            r.body = status;
        } else if (target.equals("/cells")) {
            r.body = CELLS;
        } else if (target.startsWith("/act?name=net_get")) {
            r.body = "{\"ok\":true,\"out\":\"Allowed network types: GPRS|EDGE|UMTS|HSDPA|HSUPA|HSPA|LTE|HSPAP|GSM|LTE_CA|NR (5G is allowed)\"}";
        } else if (target.startsWith("/boot")) {
            r.body = "{\"priv\":2,\"wifi\":false,\"ip\":\"\",\"wadb\":false,\"dev\":true,\"write\":true,\"hotspot\":true,\"assist\":true,\"keep\":false,\"note\":\"\",\"vc\":120}";
        } else if (target.startsWith("/act")) {
            r.body = "{\"ok\":true,\"out\":\"Done.\"}";
        } else {
            r.code = 404;
            r.body = "{\"err\":\"unknown request\"}";
        }
        return r;
    }

    /** Lets the background questions finish and their answers be drawn. */
    static void settle() throws Exception {
        for (int i = 0; i < 4; i++) {
            Thread.sleep(250);
            ShadowLooper.idleMainLooper();
        }
    }

    /**
     * Lets this much time pass. The pretend clock stands still by itself, so anything a screen does "in a few seconds" (asking
     * the phone again) only happens when the test moves the clock.
     */
    static void later(long ms) throws Exception {
        ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(ms));
        settle();
    }

    static void shot(Activity a, String name) throws Exception {
        settle();
        View v = a.getWindow().getDecorView();
        save(v, name);
        // a long screen: also its end
        ScrollView sv = findScroll(v);
        if (sv != null && sv.getChildCount() > 0 && sv.getChildAt(0).getHeight() > sv.getHeight() + 40) {
            int page = sv.getHeight() - 60;
            int total = sv.getChildAt(0).getHeight();
            int n = 2;
            for (int y = page; y < total - 40 && n <= 6; y += page, n++) {
                sv.scrollTo(0, Math.min(y, total - sv.getHeight()));
                ShadowLooper.idleMainLooper();
                save(v, name + "-" + n);
            }
            sv.scrollTo(0, 0);
        }
    }

    static ScrollView findScroll(View v) {
        if (v instanceof ScrollView) {
            return (ScrollView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ScrollView s = findScroll(g.getChildAt(i));
                if (s != null) {
                    return s;
                }
            }
        }
        return null;
    }

    static void save(View v, String name) throws Exception {
        Bitmap b = Bitmap.createBitmap(Math.max(1, v.getWidth()), Math.max(1, v.getHeight()), Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        File dir = new File(System.getProperty("preview.out", "out"));
        dir.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(dir, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
        System.out.println("drew " + name);
    }

    static MainActivity tablet() {
        RuntimeEnvironment.setFontScale(1.2f);
        Cfg.setMode(RuntimeEnvironment.getApplication(), Cfg.TABLET);
        return Robolectric.buildActivity(MainActivity.class).setup().get();
    }

    /** The Bluetooth link as it is after a Set up that worked: the phone's address stored, and a call to it answered. */
    static void linkProved() {
        Cfg.setBtMac(RuntimeEnvironment.getApplication(), MAC);
        Cfg.setBtCall(RuntimeEnvironment.getApplication(), true, System.currentTimeMillis());
        Cfg.setStr(RuntimeEnvironment.getApplication(), "btPhone", "on");
    }

    static Button findButton(View v, String label) {
        if (v instanceof Button && label.contentEquals(((Button) v).getText()) && v.isShown()) {
            return (Button) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                Button b = findButton(g.getChildAt(i), label);
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }

    /** Presses the button with this label, as a finger would (a button that is greyed out does nothing). */
    static void press(Activity a, String label) {
        Button b = findButton(a.getWindow().getDecorView(), label);
        if (b == null) {
            throw new AssertionError("no button \"" + label + "\" on the screen");
        }
        if (!b.isEnabled()) {
            throw new AssertionError("the button \"" + label + "\" is greyed out");
        }
        b.performClick();
    }

    /** Draws the question that is open on top of the screen. */
    static void dialogShot(String name) throws Exception {
        settle();
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        if (d == null || !d.isShowing()) {
            throw new AssertionError("no question is open for " + name);
        }
        save(d.getWindow().getDecorView(), name);
    }

    @Test
    public void tabletOnTheHotspot() throws Exception {
        pretendPhone("wifi", true, true);
        MainActivity a = tablet();
        for (String t : new String[] {Tab.HOME, Tab.SPEED, Tab.BANDS, Tab.PHONE, Tab.SCREEN, Tab.FILES, Tab.TEXTS, Tab.SETTINGS}) {
            try {
                a.openTab(t);
                if (a.shownTitle().equals(t)) {
                    shot(a, "tablet-" + t.toLowerCase());
                }
            } catch (Throwable e) {
                System.out.println("FAILED " + t + ": " + e);
                e.printStackTrace(System.out);
            }
        }
    }

    @Test
    public void homeOverBluetooth() throws Exception {
        pretendPhone("bt", false, false);
        linkProved();
        MainActivity a = tablet();
        shot(a, "state-home-bluetooth");
    }

    @Test
    public void homeUnreachable() throws Exception {
        pretendPhone("", false, false);
        MainActivity a = tablet();
        shot(a, "state-home-unreachable");
    }

    /** The phone is asked over both routes and does not answer. */
    @Test
    public void homeNoAnswer() throws Exception {
        Pretend p = new Pretend();
        p.via = "bt";
        p.btFails = true;
        p.install();
        linkProved();
        MainActivity a = tablet();
        shot(a, "state-home-no-answer");
    }

    /** The phone answers, with an error: the card must not say it cannot be reached. */
    @Test
    public void homePhoneError() throws Exception {
        Pretend p = new Pretend();
        p.statusCode = 500;
        p.install();
        MainActivity a = tablet();
        shot(a, "state-home-phone-error");
    }

    /** The phone answers over Bluetooth that it does not know this tablet. */
    @Test
    public void homeNotPaired() throws Exception {
        Pretend p = new Pretend();
        p.via = "bt";
        p.statusCode = 401;
        p.install();
        linkProved();
        MainActivity a = tablet();
        shot(a, "state-home-not-paired");
    }

    /** Off the hotspot with the tablet's own Bluetooth switched off: the status read leaves it off and offers the button. */
    @Test
    public void homeBluetoothOffHere() throws Exception {
        Pretend p = new Pretend();
        p.via = "bt";
        p.btOffHere = true;
        p.install();
        linkProved();
        MainActivity a = tablet();
        shot(a, "state-home-bluetooth-off-here");
    }

    /** The phone cannot tell whether its hotspot is on ("hs" is left out of its status): not known, and both buttons. */
    @Test
    public void homeHotspotNotKnown() throws Exception {
        Pretend p = new Pretend();
        p.status = Pretend.status(true, true).replace("\"hs\":true,", "");
        p.install();
        linkProved();
        MainActivity a = tablet();
        shot(a, "state-home-hotspot-not-known");
        p.via = "bt";
        a.openTab(Tab.SPEED);
        a.openTab(Tab.HOME);
        shot(a, "state-home-hotspot-not-known-bluetooth");
    }

    /** On the hotspot with a Bluetooth link that has never been tried: Home says so while it is easy to mend. */
    @Test
    public void homeLinkNotTested() throws Exception {
        pretendPhone("wifi", true, true);
        MainActivity a = tablet();
        shot(a, "state-home-link-not-tested");
    }

    /** A switch that did not move comes back as an error: shown as a failure, in the phone's own words. */
    @Test
    public void homeSwitchFailed() throws Exception {
        Pretend p = new Pretend();
        p.via = "bt";
        p.status = Pretend.status(false, true);
        p.actCode = 409;
        p.actReply = "{\"err\":\"The hotspot could not be switched on: the phone refused the request.\"}";
        p.install();
        linkProved();
        MainActivity a = tablet();
        settle();
        press(a, "Turn the hotspot on");
        shot(a, "state-home-switch-failed");
    }

    /** A switch the phone only answered with "Sent": Home looks at the status until it shows the result. */
    @Test
    public void homeSwitchSentThenConfirmed() throws Exception {
        Pretend p = new Pretend();
        p.status = Pretend.status(true, true);
        p.bluetoothToo = true;
        p.actReply = "{\"ok\":true,\"later\":true,\"out\":\"Sent. The phone will do it in a moment.\"}";
        p.install();
        linkProved();
        MainActivity a = tablet();
        settle();
        press(a, "Hotspot off");
        dialogShot("state-home-off-confirm");
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shot(a, "state-home-switch-sent");
        p.via = "bt"; // the hotspot is gone; the phone answers over Bluetooth that it is off
        p.status = Pretend.status(false, true);
        later(3100);
        shot(a, "state-home-switch-confirmed");
    }

    /** The same switch with no Bluetooth link to fall back on: the phone simply stops answering, and Home says exactly that. */
    @Test
    public void homeSwitchSentThenSilence() throws Exception {
        Pretend p = new Pretend();
        p.status = Pretend.status(true, true).replace("\"btmac\":\"" + MAC + "\",", ""); // the link was never set up
        p.actReply = "{\"ok\":true,\"later\":true,\"out\":\"Sent. The phone will do it in a moment.\"}";
        p.install();
        MainActivity a = tablet();
        shot(a, "state-home-link-offer");
        press(a, "Not now");
        press(a, "Hotspot off");
        dialogShot("state-home-off-no-link");
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        p.via = "";
        later(3100);
        later(3100);
        shot(a, "state-home-switch-phone-gone");
    }

    /** The screen is left while Home is still looking for the result of a "Sent" switch, and opened again: what it says then. */
    @Test
    public void homeLeftDuringFollowUp() throws Exception {
        Pretend p = new Pretend();
        p.status = Pretend.status(true, true).replace("\"btmac\":\"" + MAC + "\",", ""); // the link was never set up
        p.actReply = "{\"ok\":true,\"later\":true,\"out\":\"Sent. The phone will do it in a moment.\"}";
        p.install();
        MainActivity a = tablet();
        settle();
        press(a, "Hotspot off");
        settle();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        p.via = ""; // the hotspot is gone, and there is no other way to the phone
        a.openTab(Tab.SETTINGS);
        a.openTab(Tab.HOME);
        shot(a, "state-home-left-during-follow-up");
    }

    /**
     * Not a picture but a check: a press while a question is on its way must not start a second chain of questions (each
     * chain would ask at the full rate, and over Bluetooth every question wakes the phone).
     */
    @Test
    public void homeAsksInOneChain() throws Exception {
        Pretend p = new Pretend();
        p.via = "bt";
        p.status = Pretend.status(false, true);
        p.install();
        linkProved();
        MainActivity a = tablet();
        settle(); // the first question is answered
        p.btDelayMs = 700; // from now on a question takes a while
        press(a, "Ask again"); // one is on its way ...
        Thread.sleep(200);
        ShadowLooper.idleMainLooper();
        press(a, "Ask again"); // ... and the user presses again
        Thread.sleep(2500);
        settle();
        p.btDelayMs = 0;
        for (int round = 1; round <= 3; round++) {
            int before = p.statusAsked.get();
            later(15_000); // in the first minute after a press: one question every 15 s
            int asked = p.statusAsked.get() - before;
            if (asked != 1) {
                throw new AssertionError("15 s later (round " + round + ") the phone was asked " + asked
                        + " times, not once: more than one chain of questions is running");
            }
        }
    }

    /**
     * Not a picture but a check: Home has been open on the hotspot, untouched, for longer than it would keep asking over
     * Bluetooth. Then ONE question cannot connect over the hotspot and is answered over Bluetooth. Home must go on asking (the
     * time on the hotspot is not Bluetooth time), so that it is back on the hotspot with the next question.
     */
    @Test
    public void homeKeepsAskingAfterOneAnswerOverBluetooth() throws Exception {
        Pretend p = new Pretend();
        p.bluetoothToo = true;
        p.install();
        linkProved();
        MainActivity a = tablet();
        settle();
        later(6 * 60 * 1000); // six minutes on the hotspot, nobody touches the screen
        p.wifiFails = true;
        later(5_000); // the next question: the hotspot route fails, Bluetooth answers
        if (findButton(a.getWindow().getDecorView(), "Ask again") == null) {
            throw new AssertionError("the question was not answered over Bluetooth: the check below would prove nothing");
        }
        shot(a, "state-home-one-answer-over-bluetooth");
        p.wifiFails = false;
        int before = p.statusAsked.get();
        later(15_000);
        later(5_000);
        int asked = p.statusAsked.get() - before;
        if (asked < 2) {
            throw new AssertionError("after one answer over Bluetooth Home asked " + asked + " times in the next 20 s: it has "
                    + "stopped asking although the hotspot works");
        }
        shot(a, "state-home-back-on-hotspot");
    }

    /** Reached over Bluetooth and left alone for more than five minutes: Home stops asking and says so. */
    @Test
    public void homeStoppedAsking() throws Exception {
        pretendPhone("bt", false, false);
        linkProved();
        MainActivity a = tablet();
        settle();
        later(6 * 60 * 1000);
        shot(a, "state-home-stopped-asking");
    }

    /**
     * A picture and a check. Reached over Bluetooth and left alone, and the question at the five minute mark is the FIRST one
     * the phone does not answer: Home stops asking, and "Ask again" must be on the screen (it once was not: no card, every
     * switch grey, and nothing to press).
     */
    @Test
    public void homeStoppedOnAMiss() throws Exception {
        Pretend p = new Pretend();
        p.via = "bt";
        p.status = Pretend.status(false, false);
        p.install();
        linkProved();
        MainActivity a = tablet();
        settle();
        p.btFails = true;
        later(6 * 60 * 1000);
        shot(a, "state-home-stopped-on-a-miss");
        press(a, "Ask again");
    }

    /** The same for ONE miss over Bluetooth early on: the next question is half a minute away, so the button must be there now. */
    @Test
    public void homeOneMissOverBluetooth() throws Exception {
        Pretend p = new Pretend();
        p.via = "bt";
        p.status = Pretend.status(false, false);
        p.install();
        linkProved();
        MainActivity a = tablet();
        settle();
        p.btFails = true;
        later(15_000);
        shot(a, "state-home-one-miss-over-bluetooth");
        press(a, "Ask again");
    }

    /** Every button that sends a switch must be greyed out now (there, and not to be pressed). */
    static void allSwitchesWait(Activity a, String when, String... labels) {
        ShadowLooper.idleMainLooper();
        for (String label : labels) {
            Button b = findButton(a.getWindow().getDecorView(), label);
            if (b == null) {
                throw new AssertionError(when + ": no button \"" + label + "\" on the screen");
            }
            if (b.isEnabled()) {
                throw new AssertionError(when + ": \"" + label + "\" can still be pressed, so a second switch could be sent "
                        + "behind the first");
            }
        }
    }

    /**
     * Not a picture but a check: one switch at a time. While the Bluetooth check before Standby runs, and while a screen switch
     * is on its way, the card's "Turn the hotspot on" waits like the three switches below it. (It once stayed live, and the
     * first request's return then freed the switches while the second was still being carried out.)
     */
    @Test
    public void homeOneSwitchAtATime() throws Exception {
        Pretend p = new Pretend();
        p.via = "bt";
        p.status = Pretend.status(false, true);
        p.install();
        linkProved();
        MainActivity a = tablet();
        settle();
        p.btDelayMs = 2500; // from now on a call over Bluetooth takes a while
        press(a, "Standby");
        allSwitchesWait(a, "during the Bluetooth check", "Turn the hotspot on", "Hotspot on", "Sleep screen", "Standby");
        Thread.sleep(2500);
        settle();
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        if (d == null || !d.isShowing()) {
            throw new AssertionError("the question after the Bluetooth check did not open");
        }
        d.dismiss();
        press(a, "Turn the hotspot on"); // free again once the check is over and the question was cancelled
        allSwitchesWait(a, "while the hotspot is being switched on", "Turn the hotspot on", "Hotspot on", "Sleep screen", "Standby");
        Thread.sleep(2500);
        settle();
        settle(); // the status question that follows a switch also takes a while
        Thread.sleep(2500);
        settle();
        press(a, "Sleep screen");
        allSwitchesWait(a, "while the screen is being put to sleep", "Turn the hotspot on", "Hotspot on", "Sleep screen", "Standby");
    }

    /** Before the hotspot goes off the tablet calls the phone over Bluetooth: the state while it does, and the question after a call that failed. */
    @Test
    public void homeOffAnyway() throws Exception {
        Pretend p = new Pretend();
        p.bluetoothToo = true;
        p.btDelayMs = 3000;
        p.btFails = true;
        p.install();
        linkProved();
        MainActivity a = tablet();
        settle();
        press(a, "Hotspot off");
        shot(a, "state-home-checking-link");
        Thread.sleep(3000);
        dialogShot("state-home-off-anyway");
        ShadowAlertDialog.getLatestAlertDialog().dismiss();
        p.btDelayMs = 0;
        press(a, "Standby");
        dialogShot("state-home-standby-anyway");
    }

    /** The Bluetooth card in Settings in its states: ready, never tested, and a phone that says its Bluetooth is off. */
    @Test
    public void settingsLinkStates() throws Exception {
        pretendPhone("wifi", true, true);
        linkProved();
        MainActivity a = tablet();
        a.openTab(Tab.SETTINGS);
        shot(a, "state-settings-link-ready");
        Cfg.setBtCall(RuntimeEnvironment.getApplication(), false, 0);
        a.openTab(Tab.HOME);
        a.openTab(Tab.SETTINGS);
        shot(a, "state-settings-link-not-reached");
        Cfg.setBtCall(RuntimeEnvironment.getApplication(), true, System.currentTimeMillis());
        Cfg.setStr(RuntimeEnvironment.getApplication(), "btPhone", "off");
        a.openTab(Tab.SPEED);
        a.openTab(Tab.SETTINGS);
        shot(a, "state-settings-link-phone-off");
    }

    @Test
    public void phone() throws Exception {
        Cfg.setMode(RuntimeEnvironment.getApplication(), Cfg.PHONE);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        shot(a, "phone-phone");
    }
}
