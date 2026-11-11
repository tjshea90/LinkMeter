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

    static final String CELLS_4G = "{\"carriers\":[{\"rat\":\"LTE\",\"band\":66,\"ch\":66611,\"bw\":15000,\"primary\":1}],\"display\":\"NONE\",\"ca\":false,"
            + "\"nr\":\"NONE\",\"cells\":[{\"t\":\"LTE\",\"reg\":1,\"bands\":[66],\"earfcn\":66611,\"rsrp\":-108,\"rsrq\":-12,\"lvl\":2}]}";
    static final String SIG = "{\"lteRsrp\":-104,\"lteRsrq\":-11,\"lteSinr\":120,\"lteCqi\":8,\"nrRsrp\":-110,\"nrSinr\":50,\"net\":\"5G (NSA)\"}";

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
        /** An older phone app: it does not know the actions that came later (the battery saver's one switch). */
        boolean olderPhone;
        /** What the phone answers to GET /boot (null: the usual answer, Shizuku running). */
        String bootBody;
        int statusCode = 200;
        String actReply = "{\"ok\":true,\"out\":\"Done.\"}";
        int actCode = 200;
        /** The phone is set to 4G only: net_get, net_lte, net_5g and /cells follow it. */
        boolean fourGOnly;
        /** The phone takes this long to answer a scan (for the picture before the first answer). */
        long cellsDelayMs;
        /** What /sig answers (the best spot). */
        String sigBody = SIG;
        /** What radio_cycle_state answers, one per question; the last one stays. */
        String[] cycleStates = {"running", "done"};
        int cycleAsked;
        /** How often the status was asked for. */
        final java.util.concurrent.atomic.AtomicInteger statusAsked = new java.util.concurrent.atomic.AtomicInteger();
        /** How often the phone was called over Bluetooth, for anything (each such call wakes it). */
        final java.util.concurrent.atomic.AtomicInteger btCalls = new java.util.concurrent.atomic.AtomicInteger();

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
                    p.btCalls.incrementAndGet();
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
            if (bootBody != null && target.startsWith("/boot")) {
                Client.Resp r = new Client.Resp();
                r.code = 200;
                r.body = bootBody;
                return r;
            }
            if (olderPhone && target.startsWith("/act?name=eco_auto")) {
                Client.Resp r = new Client.Resp();
                r.code = 409;
                r.body = "{\"err\":\"unknown action: " + target.substring("/act?name=".length()) + "\"}";
                return r;
            }
            if (target.startsWith("/act?name=safety")) {
                Client.Resp r = new Client.Resp();
                r.code = 200;
                r.body = "{\"ok\":true,\"out\":\"Safety check\\nMobile data: working\\nNetwork types: GPRS|EDGE|UMTS|HSDPA|HSUPA|HSPA|LTE|HSPAP|GSM|LTE_CA|NR\\n"
                        + "\\nLinkMeter has nothing changed on this phone.\"}";
                return r;
            }
            if (target.startsWith("/act?name=hotspot_info")) {
                Client.Resp r = new Client.Resp();
                r.code = 200;
                r.body = "{\"ok\":true,\"out\":\"OK hotspot\\nband=1 channel=6 security=WPA2 state=on\"}";
                return r;
            }
            if (target.equals("/probe")) {
                Client.Resp r = new Client.Resp();
                r.code = 200;
                r.body = "$ getprop ro.product.model\nSM-S156V\n\n$ id\nuid=2000(shell) gid=2000(shell)\n\n$ settings get system aod_mode\nnull\n";
                return r;
            }
            if (target.equals("/cells") || target.startsWith("/act?name=net_") || target.equals("/sig")
                    || target.startsWith("/act?name=menu_open") || target.startsWith("/act?name=radio_cycle")) {
                Client.Resp r = new Client.Resp();
                r.code = 200;
                if (target.equals("/cells")) {
                    if (cellsDelayMs > 0) {
                        try {
                            Thread.sleep(cellsDelayMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    r.body = fourGOnly ? CELLS_4G : CELLS;
                } else if (target.startsWith("/act?name=net_get")) {
                    r.body = "{\"ok\":true,\"out\":\"Allowed network types: GPRS|EDGE|UMTS|HSDPA|HSUPA|HSPA|LTE|HSPAP|GSM|LTE_CA"
                            + (fourGOnly ? " (5G is off)" : "|NR (5G is allowed)") + "\"}";
                } else if (target.startsWith("/act?name=net_lte")) {
                    fourGOnly = true;
                    r.body = "{\"ok\":true,\"out\":\"Forced to 4G. The phone is now on GPRS|EDGE|UMTS|HSDPA|HSUPA|HSPA|LTE|HSPAP|GSM|LTE_CA.\"}";
                } else if (target.startsWith("/act?name=net_5g")) {
                    fourGOnly = false;
                    r.body = "{\"ok\":true,\"out\":\"5G is allowed again (GPRS|EDGE|UMTS|HSDPA|HSUPA|HSPA|LTE|HSPAP|GSM|LTE_CA|NR).\"}";
                } else if (target.equals("/sig")) {
                    r.body = sigBody;
                } else if (target.startsWith("/act?name=menu_open")) {
                    r.body = "{\"ok\":true,\"out\":\"Opened com.android.phone/.settings.RadioInfo. Look at the phone's screen (the Screen tab shows it; "
                            + "unlock the phone first if it is locked).\"}";
                } else if (target.startsWith("/act?name=radio_cycle_state")) {
                    String st = cycleStates[Math.min(cycleAsked++, cycleStates.length - 1)];
                    r.body = "{\"ok\":true,\"out\":\"" + st + "\"}";
                } else if (via.equals("wifi")) {
                    r.body = "{\"ok\":true,\"later\":true,\"out\":\"Sent. The phone will do it in a moment.\"}";
                } else {
                    r.body = "{\"ok\":true,\"out\":\"OK the radio was switched off for 4 seconds and on again\"}";
                }
                return r;
            }
            boolean cuts = target.startsWith("/act?name=reboot") || target.startsWith("/act?name=poweroff") || target.startsWith("/act?name=wifi_")
                    || target.startsWith("/act?name=hotspot_restart") || target.startsWith("/act?name=hotspot_off") || target.startsWith("/act?name=standby");
            if (cuts && via.equals("wifi") && actCode == 200) {
                // an action that takes away the link it was asked over is answered first, and carried out a moment later
                Client.Resp r = new Client.Resp();
                r.code = 200;
                r.body = "{\"ok\":true,\"later\":true,\"out\":\"Sent. The phone will do it in a moment.\"}";
                return r;
            }
            if (target.startsWith("/act?name=hotspot") || target.startsWith("/act?name=standby") || target.startsWith("/act?name=wifi_")
                    || target.startsWith("/act?name=reboot") || target.startsWith("/act?name=poweroff")) {
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

    /** The text view that shows exactly these words, or null. */
    static android.widget.TextView findText(View v, String words) {
        if (v instanceof android.widget.TextView && words.contentEquals(((android.widget.TextView) v).getText()) && v.isShown()) {
            return (android.widget.TextView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.widget.TextView t = findText(g.getChildAt(i), words);
                if (t != null) {
                    return t;
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
        for (String t : new String[] {Tab.HOME, Tab.SPEED, Tab.SIGNAL, Tab.PHONE, Tab.SCREEN, Tab.FILES, Tab.TEXTS, Tab.SETTINGS}) {
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

    /**
     * The battery saver line on Home in the states where its words are long: Automatic but not on (the usual state while the hotspot
     * is off), and Automatic while Shizuku is not running. Home never says "on" from the stored choice alone.
     */
    @Test
    public void homeSaverWords() throws Exception {
        Pretend p = new Pretend();
        p.via = "bt";
        p.status = Pretend.status(false, false).replace("\"eco\":true", "\"eco\":false");
        p.install();
        linkProved();
        MainActivity a = tablet();
        settle();
        shot(a, "state-home-saver-automatic");
        Pretend q = new Pretend();
        q.via = "bt";
        q.status = Pretend.status(false, false).replace("\"eco\":true", "\"eco\":false").replace("\"priv\":2", "\"priv\":0");
        q.install();
        linkProved();
        MainActivity b = tablet();
        settle();
        shot(b, "state-home-saver-needs-shizuku");
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

    /**
     * Not a picture but a check: the Screen tab, left open while the phone is only reachable over Bluetooth (it is on Standby),
     * must not call it there. Its own question, every 3 s, is for the hotspot only.
     */
    @Test
    public void screenTabLeavesBluetoothAlone() throws Exception {
        Pretend p = new Pretend();
        p.via = "bt";
        p.status = Pretend.status(false, false);
        p.install();
        linkProved();
        MainActivity a = tablet();
        settle();
        a.openTab(Tab.SCREEN);
        settle();
        int before = p.btCalls.get();
        for (int i = 0; i < 10; i++) {
            later(3000);
        }
        int calls = p.btCalls.get() - before;
        if (calls != 0) {
            throw new AssertionError("with the Screen tab open for 30 s the phone was called " + calls + " times over Bluetooth");
        }
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
        settle();
        // one failed call after the link had worked (the phone was out of range once): Settings says so, Home has no card for it
        if (findButton(a.getWindow().getDecorView(), "Open " + Tab.SETTINGS) != null) {
            throw new AssertionError("Home shows 'The Bluetooth link is not ready' for one call that failed");
        }
        a.openTab(Tab.SETTINGS);
        shot(a, "state-settings-link-not-reached");
        Cfg.setBtCall(RuntimeEnvironment.getApplication(), true, System.currentTimeMillis());
        Cfg.setStr(RuntimeEnvironment.getApplication(), "btPhone", "off");
        a.openTab(Tab.SPEED);
        a.openTab(Tab.SETTINGS);
        shot(a, "state-settings-link-phone-off");
    }

    /** A picture and a check: "Pair again (new secret)" takes the link's "Ready" away at once. It was made with the old secret. */
    @Test
    public void settingsNewSecret() throws Exception {
        pretendPhone("wifi", true, true);
        linkProved();
        MainActivity a = tablet();
        a.openTab(Tab.SETTINGS);
        settle();
        if (findText(a.getWindow().getDecorView(), "Ready") == null) {
            throw new AssertionError("the link is not shown as Ready to begin with: the check below would prove nothing");
        }
        press(a, "Pair again (new secret)");
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        if (findText(a.getWindow().getDecorView(), "Ready") != null) {
            throw new AssertionError("after a new secret the Bluetooth link still says Ready");
        }
        shot(a, "state-settings-link-after-new-secret");
    }

    // ---------------------------------------------------------------------------------------------- the Phone tab

    /**
     * The phone's status for the Phone tab: the version of this tablet's own app (under Robolectric it is not the 0.2.120 of the other
     * screens' pretend phone, and a different version would put the update banner in every picture), with some fields changed.
     * edits come in pairs: the text to find, and what replaces it.
     */
    static String phoneStatus(boolean hotspotOn, String... edits) {
        return phoneStatusAs(hotspotOn, -1, null, edits);
    }

    /** The same, for a phone whose app has another version (vcOverride -1: the tablet's own). */
    static String phoneStatusAs(boolean hotspotOn, long vcOverride, String vnOverride, String... edits) {
        android.content.Context c = RuntimeEnvironment.getApplication();
        String vn = "0.2.preview";
        long vc = 1;
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            vn = pi.versionName;
            vc = pi.getLongVersionCode();
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            // the defaults above
        }
        if (vcOverride >= 0) {
            vc = vcOverride;
            vn = vnOverride;
        }
        String s = Pretend.status(hotspotOn, true).replace("\"vc\":120,\"vn\":\"0.2.120\"", "\"vc\":" + vc + ",\"vn\":\"" + vn + "\"");
        for (int i = 0; i + 1 < edits.length; i += 2) {
            if (!s.contains(edits[i])) {
                throw new AssertionError("the pretend status has no " + edits[i]);
            }
            s = s.replace(edits[i], edits[i + 1]);
        }
        return s;
    }

    /** The positive button of the question that is open: "Do it". */
    static void answerYes() {
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        if (d == null || !d.isShowing()) {
            throw new AssertionError("no question is open");
        }
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper(); // the dialog is dismissed by a message, as it is for a finger: nobody taps faster than that
    }

    static void answerNo() {
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        if (d != null && d.isShowing()) {
            d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            ShadowLooper.idleMainLooper();
        }
    }

    static MainActivity phoneTab(Pretend p) throws Exception {
        // a new boot driver for each picture: the flow of an earlier picture (a run in the middle) must not leak into this one
        java.lang.reflect.Field inst = BootDriver.class.getDeclaredField("instance");
        inst.setAccessible(true);
        inst.set(null, null);
        p.install();
        linkProved();
        MainActivity a = tablet();
        a.openTab(Tab.PHONE);
        settle();
        if (!a.shownTitle().equals(Tab.PHONE)) {
            throw new AssertionError("the Phone tab did not open: " + a.shownTitle());
        }
        return a;
    }

    /**
     * The boot flow in a state the tablet cannot be put in under test (a run needs the tablet's real Wi-Fi and its location
     * permission): its own steps are run on the pure flow, the driver's flow, with times that make it go on or time out.
     */
    static void bootRun(MainActivity a, boolean timeOut) throws Exception {
        BootDriver driver = BootDriver.get(a);
        java.lang.reflect.Field f = BootDriver.class.getDeclaredField("flow");
        f.setAccessible(true);
        BootFlow flow = (BootFlow) f.get(driver);
        BootFlow.Obs o = new BootFlow.Obs();
        o.now = 1000;
        o.hotspot = BootFlow.Hotspot.ON;
        o.hotspotInfo = "DIRECT-LM-Tablet";
        flow.start(o);
        o.now = 20000;
        flow.tick(o);
        if (timeOut) {
            o.now = 31 * 60 * 1000L;
            flow.tick(o);
        }
        later(1500); // the card repaints itself every second while a run is on
    }

    /** Shizuku is running on the phone: the card "After a phone restart" at rest, with nothing to do. */
    @Test
    public void phoneTabBootAtRest() throws Exception {
        Pretend p = new Pretend();
        p.status = phoneStatus(true);
        MainActivity a = phoneTab(p);
        shot(a, "state-phone-boot-rest");
    }

    /** The phone was restarted: Shizuku is not running, the card says so, and the details are one tap away. */
    @Test
    public void phoneTabBootNoShizuku() throws Exception {
        Pretend p = new Pretend();
        p.via = "bt";
        p.status = phoneStatus(false, "\"priv\":2", "\"priv\":0");
        p.bootBody = "{\"priv\":0,\"wifi\":true,\"ip\":\"\",\"wadb\":false,\"dev\":true,\"write\":true,\"hotspot\":false,\"assist\":true,\"keep\":false,\"note\":\"\",\"vc\":1}";
        MainActivity a = phoneTab(p);
        press(a, "After a phone restart");
        settle();
        shot(a, "state-phone-boot-noshizuku");
        press(a, "Tablet hotspot and one-time setup");
        settle();
        shot(a, "state-phone-boot-details");
    }

    /** A run in the middle: its steps, and the details opened by themselves. */
    @Test
    public void phoneTabBootRunning() throws Exception {
        Pretend p = new Pretend();
        p.via = "bt";
        p.status = phoneStatus(false, "\"priv\":2", "\"priv\":0");
        p.bootBody = "{\"priv\":0,\"wifi\":true,\"ip\":\"\",\"wadb\":false,\"dev\":true,\"write\":true,\"hotspot\":false,\"assist\":true,\"keep\":false,\"note\":\"\",\"vc\":1}";
        MainActivity a = phoneTab(p);
        bootRun(a, false);
        shot(a, "state-phone-boot-running");
    }

    /** A run that did not get anywhere: what it says, and the steps it got through. */
    @Test
    public void phoneTabBootFailed() throws Exception {
        Pretend p = new Pretend();
        p.via = "bt";
        p.status = phoneStatus(false, "\"priv\":2", "\"priv\":0");
        p.bootBody = "{\"priv\":0,\"wifi\":true,\"ip\":\"\",\"wadb\":false,\"dev\":true,\"write\":true,\"hotspot\":false,\"assist\":true,\"keep\":false,\"note\":\"\",\"vc\":1}";
        MainActivity a = phoneTab(p);
        bootRun(a, true);
        shot(a, "state-phone-boot-failed");
    }

    /** Reached over the hotspot, the saver on, the battery current known: the tab as it is on a good day. */
    @Test
    public void phoneTabOnTheHotspot() throws Exception {
        Pretend p = new Pretend();
        p.status = phoneStatus(true, "\"lvl\":2", "\"cur\":-312,\"lvl\":2");
        MainActivity a = phoneTab(p);
        shot(a, "state-phone-hotspot");
    }

    /** Over Bluetooth: the chip says so, Update is greyed with its reason. */
    @Test
    public void phoneTabOverBluetooth() throws Exception {
        Pretend p = new Pretend();
        p.via = "bt";
        p.status = phoneStatus(false, "\"eco\":true", "\"eco\":false");
        MainActivity a = phoneTab(p);
        shot(a, "state-phone-bluetooth");
    }

    @Test
    public void phoneTabUnreachable() throws Exception {
        Pretend p = new Pretend();
        p.via = "";
        MainActivity a = phoneTab(p);
        shot(a, "state-phone-unreachable");
    }

    /** Shizuku is not running: the banner says so and points to the steps further down. */
    @Test
    public void phoneTabNoShizuku() throws Exception {
        Pretend p = new Pretend();
        p.via = "bt";
        p.status = phoneStatus(false, "\"priv\":2", "\"priv\":0", "\"eco\":true", "\"eco\":false");
        MainActivity a = phoneTab(p);
        shot(a, "state-phone-noshizuku");
    }

    /** An older phone app: no battery switch, no Wi-Fi fact, a different version; pressing the battery switch gets its plain answer. */
    @Test
    public void phoneTabOlderApp() throws Exception {
        Pretend p = new Pretend();
        p.olderPhone = true;
        p.status = phoneStatusAs(true, 100, "0.2.100", "\"ecoAuto\":true,", "", "\"wifi\":false,", "");
        MainActivity a = phoneTab(p);
        shot(a, "state-phone-older");
        press(a, "Automatic");
        settle();
        shot(a, "state-phone-older-pressed");
    }

    @Test
    public void phoneTabAirplane() throws Exception {
        Pretend p = new Pretend();
        p.status = phoneStatus(true, "\"air\":false", "\"air\":true");
        MainActivity a = phoneTab(p);
        shot(a, "state-phone-airplane");
    }

    /** The phone's app has a different version from the tablet's: the first banner after the ones above. */
    @Test
    public void phoneTabVersionDiffers() throws Exception {
        Pretend p = new Pretend();
        p.status = phoneStatusAs(true, 120, "0.2.120");
        MainActivity a = phoneTab(p);
        shot(a, "state-phone-version");
    }

    /** A switch the phone could not move: its words, in the warning colour, in the strip. */
    @Test
    public void phoneTabFailedSwitch() throws Exception {
        Pretend p = new Pretend();
        p.via = "bt";
        p.status = phoneStatus(false);
        p.actCode = 409;
        p.actReply = "{\"err\":\"The phone's Wi-Fi did not switch on.\"}";
        MainActivity a = phoneTab(p);
        press(a, "Wi-Fi on");
        settle();
        shot(a, "state-phone-failed");
    }

    /** More, opened: the hotspot band (read from the phone), Restart hotspot, Prepare and Probe. */
    @Test
    public void phoneTabMore() throws Exception {
        Pretend p = new Pretend();
        MainActivity a = phoneTab(p);
        press(a, "More");
        settle();
        shot(a, "state-phone-more");
    }

    /** The safety check's answer in the strip, and Details open. */
    @Test
    public void phoneTabResult() throws Exception {
        Pretend p = new Pretend();
        MainActivity a = phoneTab(p);
        press(a, "Safety check");
        settle();
        shot(a, "state-phone-result");
        ((View) findText(a.getWindow().getDecorView(), "Details").getParent()).performClick(); // the strip is what listens, not its label
        dialogShot("state-phone-result-details");
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    }

    /** Each question before a switch that cannot be undone from the tablet, and what Restart shows after it was confirmed. */
    @Test
    public void phoneTabQuestions() throws Exception {
        Pretend p = new Pretend();
        p.status = phoneStatus(true, "\"lvl\":2", "\"cur\":-312,\"lvl\":2");
        MainActivity a = phoneTab(p);
        press(a, "Restart");
        dialogShot("state-phone-ask-restart");
        answerNo();
        press(a, "Power off");
        dialogShot("state-phone-ask-poweroff");
        answerNo();
        press(a, "Wi-Fi on");
        dialogShot("state-phone-ask-wifi-on");
        answerNo();
        press(a, "Restart");
        answerYes();
        settle();
        shot(a, "state-phone-sent");
    }

    /** Switching the phone's Wi-Fi off asks only when the tablet is not on the phone's own hotspot (then it may be reaching it through that Wi-Fi). */
    @Test
    public void phoneTabWifiOffQuestion() throws Exception {
        Pretend p = new Pretend();
        p.status = phoneStatus(false, "\"wifi\":false", "\"wifi\":true");
        MainActivity a = phoneTab(p);
        press(a, "Wi-Fi off");
        dialogShot("state-phone-ask-wifi-off");
        answerNo();
    }

    /** Restart hotspot: the tablet calls the phone over Bluetooth first, and the question says what that showed. */
    @Test
    public void phoneTabRestartHotspot() throws Exception {
        Pretend p = new Pretend();
        p.bluetoothToo = true;
        MainActivity a = phoneTab(p);
        press(a, "More");
        settle();
        press(a, "Restart hotspot");
        dialogShot("state-phone-ask-hotspot-restart");
        answerNo();
        Pretend q = new Pretend();
        q.bluetoothToo = true;
        q.btFails = true;
        q.install();
        press(a, "Restart hotspot");
        dialogShot("state-phone-ask-hotspot-restart-anyway");
        answerNo();
    }

    // ---------------------------------------------------------------------------------------------- the Signal tab

    static MainActivity signalTab(Pretend p) throws Exception {
        p.install();
        linkProved();
        MainActivity a = tablet();
        a.openTab(Tab.SIGNAL);
        settle();
        if (!a.shownTitle().equals(Tab.SIGNAL)) {
            throw new AssertionError("the Signal tab did not open: " + a.shownTitle());
        }
        return a;
    }

    /** The tab that is on show (the activity keeps it private). */
    static Tab current(MainActivity a) throws Exception {
        java.lang.reflect.Field f = MainActivity.class.getDeclaredField("cur");
        f.setAccessible(true);
        return (Tab) f.get(a);
    }

    static Object field(Object o, String name) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    static void setField(Object o, String name, Object v) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }

    /** A phone that answers at once, so a comparison is over (or stalled in the middle) in a blink and the tab can be shown what it left. */
    static final class QuickLink implements LeverRun.Link {
        final double[] normal;
        final double[] lte;
        /** After this many speed tests the phone stops answering (the run is "in the middle"). */
        final int stallAfter;
        int ni;
        int li;
        int started;
        boolean off;
        long clock;

        QuickLink(double[] normal, double[] lte, int stallAfter) {
            this.normal = normal;
            this.lte = lte;
            this.stallAfter = stallAfter;
        }

        @Override
        public void fiveGOff(LeverRun.Done<Boolean> d) {
            d.done(true, "", off);
        }

        @Override
        public void switchFiveG(boolean o, LeverRun.Done<Boolean> d) {
            off = o;
            d.done(true, "", o);
        }

        @Override
        public void observe(LeverRun.Done<LeverRun.Observation> d) {
            RadioState s = new RadioState();
            s.carriers.add(new RadioState.Carrier("LTE", 66, 66611, 15000, true));
            if (!off) {
                s.carriers.add(new RadioState.Carrier("NR", 77, 650000, 60000, false));
            }
            s.display = off ? "NONE" : "NR_ADVANCED";
            s.nrState = off ? "NONE" : "CONNECTED";
            d.done(true, "", new LeverRun.Observation(s, -104));
        }

        @Override
        public void startUpload(LeverRun.Done<Void> d) {
            started++;
            d.done(true, "", null);
        }

        @Override
        public void uploadStatus(LeverRun.Done<LeverRun.Upload> d) {
            double up = off ? lte[Math.min(li++, lte.length - 1)] : normal[Math.min(ni++, normal.length - 1)];
            d.done(true, "", new LeverRun.Upload(4, up, 40, up, ""));
        }

        @Override
        public void stopUpload(LeverRun.Done<Void> d) {
            d.done(true, "", null);
        }

        @Override
        public void later(long ms, Runnable r) {
            if (started > stallAfter) {
                return; // the phone has gone quiet: this run stays where it is
            }
            clock += ms;
            r.run();
        }

        @Override
        public long now() {
            return clock;
        }

        @Override
        public void awake(boolean on) {
            // nothing to keep awake here
        }
    }

    /** The tab is shown a comparison: the table and the progress line as the real run made them. */
    static void showRun(MainActivity a, double[] normal, double[] lte, int rounds, int stallAfter, long seed) throws Exception {
        final String[] progress = {""};
        LeverRun run = new LeverRun(new QuickLink(normal, lte, stallAfter), new LeverRun.Listener() {
            @Override
            public void progress(String text) {
                progress[0] = text;
            }

            @Override
            public void rows(java.util.List<LeverRun.Row> rows, int best, boolean running) {
                // the tab is given the table below
            }
        }, LeverRun.settings(true, true), rounds, Tab.SIGNAL, new java.util.Random(seed));
        run.start();
        Tab tab = current(a);
        setField(tab, "run", run);
        setField(tab, "progressMsg", progress[0]);
        setField(tab, "keepNote", ""); // what starting a comparison does
        java.lang.reflect.Method m = SignalTab.class.getDeclaredMethod("renderRows", java.util.List.class, int.class, boolean.class);
        m.setAccessible(true);
        m.invoke(tab, run.rows(), run.best(), run.running());
        settle();
    }

    /** Before the first scan has been answered. */
    @Test
    public void signalNotScannedYet() throws Exception {
        Pretend p = new Pretend();
        p.cellsDelayMs = 1500; // the scan is still out when the picture is taken
        p.install();
        linkProved();
        MainActivity a = tablet();
        a.openTab(Tab.SIGNAL);
        ShadowLooper.idleMainLooper(); // one layout, and no answer yet
        save(a.getWindow().getDecorView(), "state-signal-not-scanned");
        settle();
        later(2000); // the answer comes in before the next test starts
    }

    /** After a scan on 5G UW, and on 4G only (the phone's label and cells follow the switch). */
    @Test
    public void signalOn4GOnly() throws Exception {
        Pretend p = new Pretend();
        p.fourGOnly = true;
        MainActivity a = signalTab(p);
        shot(a, "state-signal-4g-only");
        // the switch back to 5G: the answer is in the strip, and the tab looks again a few seconds later
        press(a, "5G allowed");
        settle();
        shot(a, "state-signal-switched-to-5g");
        later(9000);
        shot(a, "state-signal-looked-again");
    }

    /** The phone does not answer. */
    @Test
    public void signalUnreachable() throws Exception {
        Pretend p = new Pretend();
        p.via = "";
        MainActivity a = signalTab(p);
        shot(a, "state-signal-unreachable");
    }

    /** Over Bluetooth: scanning works, the comparison says why it cannot start. */
    @Test
    public void signalOverBluetooth() throws Exception {
        Pretend p = new Pretend();
        p.via = "bt";
        MainActivity a = signalTab(p);
        shot(a, "state-signal-bluetooth");
        Button start = findButton(a.getWindow().getDecorView(), "Start");
        if (start == null || start.isEnabled()) {
            throw new AssertionError("the comparison can be started over Bluetooth");
        }
    }

    /** A comparison in the middle, one that is over with a recommendation, and one with no difference. */
    @Test
    public void signalComparisons() throws Exception {
        MainActivity a = signalTab(new Pretend());
        showRun(a, new double[] {4.1, 3.2, 5.0, 3.6, 4.4}, new double[] {2.0, 1.4, 2.6, 1.9, 2.2}, 5, 3, 11);
        shot(a, "state-signal-compare-middle");
        // the phone is better off on 4G here: 4G only wins every round
        showRun(a, new double[] {2.0, 1.4, 2.6, 1.9, 2.2}, new double[] {4.1, 3.2, 5.0, 3.6, 4.4}, 5, 1000, 12);
        shot(a, "state-signal-compare-recommended");
        press(a, "Keep: 4G only (5G off)");
        settle();
        shot(a, "state-signal-compare-kept");
        // nothing clearly better
        showRun(a, new double[] {3.1, 3.4, 2.9, 3.3, 3.0}, new double[] {3.0, 3.5, 3.0, 3.2, 3.1}, 5, 1000, 13);
        shot(a, "state-signal-compare-no-difference");
    }

    /** The best spot, running: the score, the lines, a marked spot. The beep stays off (the pretend tablet has no speaker). */
    @Test
    public void signalBestSpot() throws Exception {
        MainActivity a = signalTab(new Pretend());
        Tab tab = current(a);
        setField(tab, "beepOn", false);
        ((Button) field(tab, "bHunt")).performClick();
        for (int i = 0; i < 4; i++) {
            later(1000);
        }
        ((android.widget.EditText) field(tab, "labelField")).setText("window");
        press(a, "Mark");
        settle();
        shot(a, "state-signal-best-spot");
    }

    /** The tools: each asks first, and says what happened. */
    @Test
    public void signalTools() throws Exception {
        Pretend p = new Pretend();
        MainActivity a = signalTab(p);
        press(a, "Search for a network again");
        dialogShot("state-signal-ask-search");
        answerYes();
        settle();
        shot(a, "state-signal-searching");
        for (int i = 0; i < 4; i++) {
            later(3000);
        }
        shot(a, "state-signal-searched");
        press(a, "Phone info screen");
        dialogShot("state-signal-ask-phone-info");
        answerYes();
        settle();
        shot(a, "state-signal-phone-info-opened");
        press(a, "Safety check");
        settle();
        shot(a, "state-signal-safety-check");
    }

    // ---------------------------------------------------------------------------- the phone's own screen
    //
    // Drawn at a phone's size (384 x 832 dp) on Android 15, the newest Robolectric image that is available offline (the Android 12 and
    // later states of the Bluetooth permission exist only there). The phone's Shizuku, its Bluetooth listener and its hotspot cannot be
    // faked, so the screen reads what it shows through PhoneTab.source and the test puts the facts in its place.

    /** A phone where everything is fine and everything is allowed: the everyday state, which should look calm. */
    static PhoneScreenFacts phoneReady() {
        PhoneScreenFacts f = new PhoneScreenFacts();
        f.running = true;
        f.tablets = 1;
        f.requests = 57;
        f.lastNote = "192.168.43.2 /s: ok";
        f.lastAtText = "14:02";
        f.shizuku = PhoneScreenFacts.Shizuku.READY;
        f.phoneStateAllowed = true;
        f.notificationsAllowed = true;
        f.filesAllowed = true;
        f.btPermission = true;
        f.batteryUnrestricted = true;
        f.btSwitch = true;
        f.bt = PhoneScreenFacts.Bt.ON;
        f.btAsked = "last asked over Bluetooth at 13:58";
        f.hotspot = Boolean.TRUE;
        f.hotspotAddress = "192.168.43.1";
        f.saverAuto = true;
        f.saverOn = true;
        f.screenControl = true;
        f.autoStart = true;
        f.bootAssist = true;
        f.bootPermission = true;
        return f;
    }

    /** A freshly installed phone: nothing started, nobody paired, Shizuku off, nothing allowed. */
    static PhoneScreenFacts phoneFresh() {
        PhoneScreenFacts f = new PhoneScreenFacts();
        f.btSwitch = true;
        f.saverAuto = true;
        f.screenControl = true;
        f.autoStart = true;
        return f;
    }

    static MainActivity phoneScreen(PhoneScreenFacts f, float font) throws Exception {
        final PhoneScreenFacts shownFacts = f;
        PhoneTab.source = a -> shownFacts;
        RuntimeEnvironment.setFontScale(font);
        Cfg.setMode(RuntimeEnvironment.getApplication(), Cfg.PHONE);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        if (!a.shownTitle().equals(Tab.PHONE)) {
            throw new AssertionError("the phone's own screen did not open: " + a.shownTitle());
        }
        ((PhoneTab) current(a)).refreshNow();
        return a;
    }

    /** The names the rules quote for the tablet's tabs are the tabs' own names. */
    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void phoneNamesAgree() {
        if (!PhoneScreenRules.TABLET_PHONE_TAB.equals(Tab.PHONE) || !PhoneScreenRules.TABLET_SETTINGS_TAB.equals(Tab.SETTINGS)) {
            throw new AssertionError("PhoneScreenRules names the tablet's tabs differently from Tab");
        }
    }

    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void phoneFreshInstall() throws Exception {
        shot(phoneScreen(phoneFresh(), 1.0f), "phone-fresh");
    }

    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void phoneReadyAndCalm() throws Exception {
        shot(phoneScreen(phoneReady(), 1.0f), "phone-ready");
    }

    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void phoneReadyBigFont() throws Exception {
        shot(phoneScreen(phoneReady(), 1.3f), "phone-ready-font13");
    }

    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void phoneBluetoothNotAllowed() throws Exception {
        PhoneScreenFacts f = phoneReady();
        f.btPermission = false;
        f.bt = PhoneScreenFacts.Bt.PERM;
        f.btAsked = "";
        shot(phoneScreen(f, 1.0f), "phone-bt-permission");
    }

    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void phoneBluetoothOff() throws Exception {
        PhoneScreenFacts f = phoneReady();
        f.bt = PhoneScreenFacts.Bt.OFF;
        f.btAsked = "";
        shot(phoneScreen(f, 1.0f), "phone-bt-off");
    }

    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void phoneBluetoothLinkSwitchedOff() throws Exception {
        PhoneScreenFacts f = phoneReady();
        f.btSwitch = false;
        f.bt = PhoneScreenFacts.Bt.DISABLED;
        f.btAsked = "";
        shot(phoneScreen(f, 1.0f), "phone-bt-disabled");
    }

    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void phoneBluetoothError() throws Exception {
        PhoneScreenFacts f = phoneReady();
        f.bt = PhoneScreenFacts.Bt.ERROR;
        f.btNote = "the listener keeps ending";
        f.btAsked = "";
        shot(phoneScreen(f, 1.0f), "phone-bt-error");
    }

    /** The phone was restarted: Shizuku is not running, the hotspot is off, Boot assist is on. */
    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void phoneAfterRestart() throws Exception {
        PhoneScreenFacts f = phoneReady();
        f.shizuku = PhoneScreenFacts.Shizuku.OFF;
        f.hotspot = Boolean.FALSE;
        f.hotspotAddress = "";
        f.requests = 0;
        f.btAsked = "";
        shot(phoneScreen(f, 1.0f), "phone-after-restart");
    }

    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void phoneShizukuNotAllowed() throws Exception {
        PhoneScreenFacts f = phoneReady();
        f.shizuku = PhoneScreenFacts.Shizuku.NOT_ALLOWED;
        shot(phoneScreen(f, 1.0f), "phone-shizuku-not-allowed");
    }

    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void phoneTextsNotReady() throws Exception {
        PhoneScreenFacts f = phoneReady();
        f.textsOn = true;
        f.textsMissing = "read texts, send texts, see contact names";
        shot(phoneScreen(f, 1.0f), "phone-texts-not-ready");
    }

    /** Shizuku is ready and some permissions are still missing: one button gives them all. */
    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void phoneAllowEverything() throws Exception {
        PhoneScreenFacts f = phoneReady();
        f.filesAllowed = false;
        f.notificationsAllowed = false;
        f.batteryUnrestricted = false;
        shot(phoneScreen(f, 1.0f), "phone-allow-everything");
    }

    /** No Shizuku, and permissions missing: one button for each that can be asked here. */
    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void phoneMissingWithoutShizuku() throws Exception {
        PhoneScreenFacts f = phoneReady();
        f.shizuku = PhoneScreenFacts.Shizuku.OFF;
        f.phoneStateAllowed = false;
        f.filesAllowed = false;
        f.notificationsAllowed = false;
        shot(phoneScreen(f, 1.0f), "phone-missing-without-shizuku");
    }

    /** Boot assist is on, but the start after a restart is off: it cannot run, and the screen says so. */
    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void phoneBootAssistNeedsAutostart() throws Exception {
        PhoneScreenFacts f = phoneReady();
        f.autoStart = false;
        f.bootAssist = true;
        shot(phoneScreen(f, 1.0f), "phone-assist-needs-autostart");
    }

    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void phonePairingOpen() throws Exception {
        PhoneScreenFacts f = phoneFresh();
        f.running = true;
        f.pairingLeftSec = 100;
        shot(phoneScreen(f, 1.0f), "phone-pairing-open");
    }

    /** Each question that guards a button which cuts the tablet off. */
    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void phoneQuestions() throws Exception {
        PhoneScreenFacts f = phoneReady();
        f.saverOn = false;
        MainActivity a = phoneScreen(f, 1.0f);
        press(a, "Stop");
        dialogShot("phone-ask-stop");
        answerNo();
        press(a, "Forget all tablets");
        dialogShot("phone-ask-forget");
        answerNo();
        press(a, "Switch role");
        dialogShot("phone-ask-role");
        answerNo();
        press(a, "Without Shizuku: what to do");
        dialogShot("phone-without-shizuku");
        answerYes();
    }

    /** The saver's changes are in force: Stop and Switch role say that they stay. */
    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void phoneQuestionsWithSaverOn() throws Exception {
        PhoneScreenFacts f = phoneReady();
        f.saverOn = true;
        MainActivity a = phoneScreen(f, 1.0f);
        press(a, "Stop");
        dialogShot("phone-ask-stop-saver");
        answerNo();
    }

    /** Restore everything without Shizuku: the answer is the list of what to do by hand, in the strip at the bottom. */
    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void phoneRestoreWithoutShizuku() throws Exception {
        PhoneScreenFacts f = phoneReady();
        f.shizuku = PhoneScreenFacts.Shizuku.OFF;
        MainActivity a = phoneScreen(f, 1.0f);
        press(a, "Restore everything");
        settle();
        shot(a, "phone-restore-result");
    }

    /** (Android 10: Robolectric's Android 15 cannot answer "may this app see all files", which the Files tab asks; the tab has no newer states.) */
    @Test
    @Config(sdk = 29, qualifiers = "w384dp-h832dp-port-hdpi")
    public void phoneFilesTab() throws Exception {
        MainActivity a = phoneScreen(phoneReady(), 1.0f);
        a.openTab(Tab.FILES);
        settle();
        shot(a, "phone-files");
    }

    /**
     * The remote keyboard's field (the Screen tab's Type button): a keyboard such as Microsoft SwiftKey switches glide typing off in a
     * password field and in a field that asks for no suggestions, so the default field is neither; and what a glide writes (a draft
     * that is then committed) reaches the listener as the word once and the space. Not a picture: the facts of the field.
     */
    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void typingFieldAllowsGlide() throws Exception {
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        final java.util.List<String> got = new java.util.ArrayList<>();
        TypingView v = new TypingView(ctx);
        v.setListener(new TypingView.Listener() {
            @Override
            public void text(String t) {
                got.add("t:" + t);
            }

            @Override
            public void key(int code) {
                got.add("k:" + code);
            }
        });
        // the default: suggestions and glide
        android.view.inputmethod.EditorInfo on = new android.view.inputmethod.EditorInfo();
        android.view.inputmethod.InputConnection ic = v.onCreateInputConnection(on);
        int variation = on.inputType & android.text.InputType.TYPE_MASK_VARIATION;
        check(on.inputType == android.text.InputType.TYPE_CLASS_TEXT, "a plain text field, got inputType " + on.inputType);
        check(variation == 0, "no password or other variation");
        check((on.inputType & android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0, "suggestions are not switched off");
        check((on.imeOptions & android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0, "the keyboard is asked not to learn");
        check(on.initialSelStart == 0 && on.initialSelEnd == 0, "the cursor is reported (an empty field, cursor at 0)");
        // what a glide writes: the word as a draft, then the word and a space committed
        ic.beginBatchEdit();
        ic.setComposingText("hello", 1);
        ic.commitText("hello ", 1);
        ic.endBatchEdit();
        check(got.toString().equals("[t:hello, t: ]"), "a glided word reaches the phone once, then the space: " + got);
        got.clear();
        ic.setComposingText("teh", 1);
        got.clear();
        ic.commitText("the ", 1);
        check(got.toString().equals("[k:67, k:67, t:he ]"), "a correction deletes only what differs: " + got);
        got.clear();
        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL));
        check(got.toString().equals("[k:67]"), "Backspace is one Backspace: " + got);
        // the exact mode, for codes and passwords
        v.setSuggestions(false);
        android.view.inputmethod.EditorInfo off = new android.view.inputmethod.EditorInfo();
        v.onCreateInputConnection(off);
        check((off.inputType & android.text.InputType.TYPE_MASK_VARIATION) == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                && (off.inputType & android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0, "exact mode: no suggestions, so the keyboard sends the letters as tapped");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    /** The first screen, before a role is chosen. */
    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void roleChooser() throws Exception {
        RuntimeEnvironment.setFontScale(1.0f);
        Cfg.setMode(RuntimeEnvironment.getApplication(), "");
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        shot(a, "chooser");
    }
}
