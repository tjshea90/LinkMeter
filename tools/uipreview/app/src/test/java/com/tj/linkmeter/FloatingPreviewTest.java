package com.tj.linkmeter;

import static com.tj.linkmeter.PreviewTest.settle;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * Pictures and behaviour of the floating phone, the headset's battery and the headset's link to the tablet (docs/FLOATING_PHONE.md).
 * The card is drawn over a pretend app screen in every state; the service runs against a pretend phone that executes the REAL
 * phone-side code (CallApi), so Answer, End, Call, the Bluetooth buttons and the pop-up rules are tried end to end except for
 * Android's window itself. Run with tools/preview.sh --tests "com.tj.linkmeter.FloatingPreviewTest"; pictures: out/float-*.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 29, qualifiers = "w565dp-h916dp-port-170dpi")
public class FloatingPreviewTest {
    private final MiniPhoneView.Host quiet = new MiniPhoneView.Host() {
        @Override
        public void answer() {}

        @Override
        public void hangup() {}

        @Override
        public void dial(String number) {}

        @Override
        public void warnHeadset() {}

        @Override
        public void typedChanged() {}

        @Override
        public void phoneBluetooth() {}

        @Override
        public void tabletBluetooth() {}

        @Override
        public void dragBy(int dx, int dy) {}

        @Override
        public void dragEnd() {}

        @Override
        public void sizeChanged(boolean expanded) {}

        @Override
        public void closed() {}

        @Override
        public void volumeStep(int by) {}

        @Override
        public void volumeSwitch() {}

        @Override
        public void needRecents(boolean force) {}

        @Override
        public void needContacts(boolean force) {}

        @Override
        public void openTexts(String number) {}

        @Override
        public void wantKeyboard(boolean on) {}
    };

    @Before
    public void setUp() {
        CallWatch.dropForTest();
        RuntimeEnvironment.setFontScale(1.2f);
        FloatingPhone.canDrawForTest = true;
    }

    @After
    public void tearDown() {
        CallWatch.testLink = null;
        FloatingPhone.canDrawForTest = null;
        FloatingPhone.accessForTest = null;
        FloatingPhone.setAppInFront(false);
        TabletHeadset.pretend = null;
        TabletHeadset.refusedForTest = false;
        CallWatch.dropForTest();
    }

    // ------------------------------------------------------------ pictures of the card over a pretend app

    private static Context ctx() {
        return RuntimeEnvironment.getApplication();
    }

    /** Draws the card at 252 dp wide over a pretend app screen, and writes out/float-NAME.png. Returns the card's size in pixels. */
    private static int[] draw(MiniPhoneView v, String name) throws Exception {
        int w = Ui.dp(ctx(), MiniPhoneView.WIDTH_DP);
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
        int sw = 600;
        int sh = Math.max(973, v.getMeasuredHeight() + 160);
        Bitmap b = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        // a pretend app behind it: a blue-grey page with a few paragraphs, so the card's glass can be judged
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF2B3A55);
        c.drawRect(0, 0, sw, sh, p);
        p.setColor(0xFFE9EEF7);
        for (int i = 0; i < 18; i++) {
            c.drawRect(30, 60 + i * 48, 30 + (i % 3 == 0 ? 340 : 480), 76 + i * 48, p);
        }
        p.setColor(0xFF4C8DFF);
        c.drawRect(30, 20, 220, 44, p);
        c.save();
        c.translate(sw - v.getMeasuredWidth() - 22, 120);
        v.draw(c);
        c.restore();
        File dir = new File(System.getProperty("preview.out", "out"));
        dir.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(dir, "float-" + name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
        System.out.println("drew float-" + name);
        return new int[] {v.getMeasuredWidth(), v.getMeasuredHeight()};
    }

    /** The first view under {@code v} whose content description is exactly {@code d} (the card is not attached to a window here). */
    static View find(View v, String d) {
        if (d.contentEquals(String.valueOf(v.getContentDescription()))) {
            return v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = find(g.getChildAt(i), d);
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    private MiniPhoneView card(MiniPhoneView.Model m) {
        MiniPhoneView v = new MiniPhoneView(ctx(), quiet);
        v.bind(m);
        return v;
    }

    private static MiniPhoneView.Model model(String call, String title, String sub, boolean headset, int battery) {
        MiniPhoneView.Model m = new MiniPhoneView.Model();
        m.call = call;
        m.title = title;
        m.subtitle = sub;
        m.headsetOnPhone = headset;
        m.batteryShown = headset;
        m.headsetName = "Sony WH-1000XM4";
        m.battery = battery;
        m.tabletAble = true;
        m.askFirst = true;
        return m;
    }

    @Test
    public void pictures() throws Exception {
        int[] size;
        draw(card(model("", "Phone", "Ready", true, 78)), "idle");
        MiniPhoneView.Model tab = model("", "Phone", "Ready", true, 64);
        tab.tabletOn = true;
        draw(card(tab), "idle-both-bluetooth");
        draw(card(model(Calls.RINGING, "Mom", "Incoming call", true, 78)), "ringing");
        draw(card(model(Calls.ACTIVE, "Mom", "3:12", true, 78)), "active");
        draw(card(model(Calls.ACTIVE, "Alice Johnson", "12:03", true, 41)), "active-medium");
        draw(card(model(Calls.ACTIVE, "(555) 123-4567", "0:07", true, 18)), "active-low");
        draw(card(model(Calls.ACTIVE, "Dentist", "1:02:03", true, 6)), "active-critical");
        draw(card(model(Calls.ACTIVE, "Dad", "0:44", true, 100)), "active-full");
        draw(card(model(Calls.ACTIVE, "Dad", "0:44", true, Calls.BATTERY_UNKNOWN)), "battery-not-reported");
        draw(card(model(Calls.ACTIVE, "Dad", "0:44", true, Calls.BATTERY_OLD_PHONE)), "battery-old-phone");
        draw(card(model("", "Phone", "Ready", false, Calls.BATTERY_UNKNOWN)), "headset-off");
        MiniPhoneView.Model off = model("", "Phone", "Can't reach the phone", false, Calls.BATTERY_UNKNOWN);
        off.offline = true;
        draw(card(off), "offline");
        MiniPhoneView.Model ended = model("", "Phone", "Call ended · 3:12", true, 78);
        draw(card(ended), "ended");
        MiniPhoneView.Model busy = model("", "Phone", "Ready", false, Calls.BATTERY_UNKNOWN);
        busy.phoneBusy = true;
        busy.message = "Connecting the headset to the phone… (up to 25 seconds)";
        draw(card(busy), "connecting");

        MiniPhoneView pad = card(model("", "Phone", "Ready", true, 78));
        pad.setExpanded(true);
        size = draw(pad, "pad");
        assertTrue("the open pad is taller than the closed card", size[1] > Ui.dp(ctx(), 200));
        MiniPhoneView typed = card(model("", "Phone", "Ready", true, 78));
        typed.setExpanded(true);
        for (char k : "5551234567".toCharArray()) {
            View key = find(typed, String.valueOf(k));
            assertNotNull("the pad has a key " + k, key);
            key.performClick();
        }
        assertEquals("the keys type the number", "5551234567", typed.typed());
        draw(typed, "pad-typed");
        MiniPhoneView warn = card(model("", "Phone", "Ready", false, Calls.BATTERY_UNKNOWN));
        warn.setExpanded(true);
        MiniPhoneView.Model wm = model("", "Phone", "Ready", false, Calls.BATTERY_UNKNOWN);
        wm.armed = true;
        wm.message = FloatRules.headsetWarning("Sony WH-1000XM4");
        warn.bind(wm);
        draw(warn, "pad-warning");
        MiniPhoneView.Model bad = model("", "Phone", "Ready", true, 78);
        bad.message = "That is an emergency number. Use the phone itself.";
        bad.messageIsBad = true;
        MiniPhoneView refuse = card(bad);
        refuse.setExpanded(true);
        draw(refuse, "pad-refused");
    }

    // ------------------------------------------------------------ the service, end to end against the pretend phone

    private static Object field(Object o, String name) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static MiniPhoneView viewOf(FloatingPhoneController s) throws Exception {
        return (MiniPhoneView) field(s, "view");
    }

    private static MiniPhoneView.Model modelOf(FloatingPhoneController s) throws Exception {
        return (MiniPhoneView.Model) field(s, "model");
    }

    private static android.view.WindowManager.LayoutParams paramsOf(FloatingPhoneController s) throws Exception {
        return (android.view.WindowManager.LayoutParams) field(s, "lp");
    }

    private static void press(MiniPhoneView v, String description) throws Exception {
        View b = find(v, description);
        assertNotNull("the card has a button called " + description, b);
        assertTrue(description + " is enabled", b.isEnabled());
        b.performClick();
        settle();
    }

    private ServiceController<FloatingPhoneService> service(PretendCalls pc) throws Exception {
        Cfg.setMode(ctx(), Cfg.TABLET);
        Cfg.setCallsWatch(ctx(), true);
        Cfg.setFloatOn(ctx(), true);
        Cfg.clearFloatPos(ctx());
        ServiceController<FloatingPhoneService> sc = Robolectric.buildService(FloatingPhoneService.class).create();
        settle();
        return sc;
    }

    private static void stop(ServiceController<FloatingPhoneService> sc) {
        sc.destroy();
    }

    @Test
    public void popsUpOnARingAnswersAndHidesAfterTheCall() throws Exception {
        PretendCalls pc = CallsPreviewTest.phone();
        ServiceController<FloatingPhoneService> sc = service(pc);
        FloatingPhoneController s = FloatingPhoneController.active();
        assertNull("nothing shows while the phone is idle", viewOf(s));

        pc.ring("+15551234567", "Mom");
        settle();
        MiniPhoneView v = viewOf(s);
        assertNotNull("a ringing phone pops the card up by itself", v);
        assertEquals(Calls.RINGING, modelOf(s).call);
        assertEquals("Mom", modelOf(s).title);
        assertEquals("the phone's headset battery reaches the card", 78, modelOf(s).battery);
        assertTrue(modelOf(s).headsetOnPhone && modelOf(s).batteryShown);
        press(v, "Answer");
        assertTrue("Answer went to the phone: " + pc.told, pc.told.contains("answer"));
        assertEquals(Calls.ACTIVE, modelOf(s).call);
        draw(viewOf(s), "service-active");

        press(viewOf(s), "End call");
        assertTrue("End went to the phone: " + pc.told, pc.told.contains("hangup"));
        assertNotNull("the card stays a moment so the length can be read", viewOf(s));
        assertTrue(modelOf(s).subtitle.startsWith("Call ended"));
        PreviewTest.later(FloatRules.LINGER_MS + 1000);
        assertNull("and goes away by itself", viewOf(s));
        stop(sc);
    }

    @Test
    public void doesNotPopUpWhenSwitchedOffOrWithoutAndroidsPermission() throws Exception {
        PretendCalls pc = CallsPreviewTest.phone();
        ServiceController<FloatingPhoneService> sc = service(pc);
        FloatingPhoneController s = FloatingPhoneController.active();
        Cfg.setFloatOn(ctx(), false);
        pc.ring("+15551234567", "Mom");
        settle();
        assertNull("the automatic pop-up is off: a ring does not show it", viewOf(s));
        s.command(FloatingPhoneController.ACTION_SHOW);
        settle();
        assertNotNull("but the user asked to see it", viewOf(s));
        s.closed();
        assertNull("the cross takes it away again", viewOf(s));
        stop(sc);
        // without Android's permission the service has nothing to draw: it ends
        Cfg.setFloatOn(ctx(), true);
        FloatingPhone.canDrawForTest = false;
        assertFalse(FloatingPhone.wanted(ctx()));
        FloatingPhone.sync(ctx());
        assertNull("no service is started without the permission", org.robolectric.Shadows.shadowOf((android.app.Application) ctx()).getNextStartedService());
        FloatingPhone.canDrawForTest = true;
        assertTrue(FloatingPhone.wanted(ctx()));
        FloatingPhone.sync(ctx());
        android.content.Intent started = org.robolectric.Shadows.shadowOf((android.app.Application) ctx()).getNextStartedService();
        assertNotNull("the service is started when everything allows it", started);
        assertEquals(FloatingPhoneService.class.getName(), started.getComponent().getClassName());
        Cfg.setCallsWatch(ctx(), false);
        assertFalse("calls that never worked do not start it", FloatingPhone.wanted(ctx()));
    }

    @Test
    public void theDialPadCallsThroughThePhonesGuard() throws Exception {
        PretendCalls pc = CallsPreviewTest.phone();
        ServiceController<FloatingPhoneService> sc = service(pc);
        FloatingPhoneController s = FloatingPhoneController.active();
        s.command(FloatingPhoneController.ACTION_SHOW);
        settle();
        MiniPhoneView v = viewOf(s);
        assertNotNull(v);
        press(v, "Open the dial pad"); // the green button opens the pad when nothing is typed
        assertTrue(v.expanded());
        for (char k : "5551234567".toCharArray()) {
            press(v, String.valueOf(k));
        }
        draw(v, "service-pad-typed");
        press(v, "Call");
        assertTrue("the call was placed by the phone: " + pc.told, pc.told.contains("dial:5551234567"));
        assertFalse("the pad closes and the call's own line takes over", v.expanded());
        assertEquals("", v.typed());
        assertEquals(Calls.ACTIVE, modelOf(s).call);
        press(viewOf(s), "End call");

        // an emergency number is refused on the card and nothing is sent
        pc.told.clear();
        v = viewOf(s);
        v.setExpanded(true);
        for (char k : "911".toCharArray()) {
            press(v, String.valueOf(k));
        }
        press(v, "Call");
        assertTrue("nothing was dialed: " + pc.told, pc.told.isEmpty());
        assertTrue("the card says why: " + modelOf(s).message, modelOf(s).message.toLowerCase().contains("emergency"));
        stop(sc);
    }

    @Test
    public void callingWithoutTheHeadsetWarnsOnceThenCalls() throws Exception {
        PretendCalls pc = CallsPreviewTest.phone();
        pc.headsets.devices.get(0).connected = false;
        pc.live.headset = "";
        ServiceController<FloatingPhoneService> sc = service(pc);
        FloatingPhoneController s = FloatingPhoneController.active();
        s.command(FloatingPhoneController.ACTION_SHOW);
        settle();
        MiniPhoneView v = viewOf(s);
        assertFalse("the battery is not shown without a headset", modelOf(s).batteryShown);
        press(v, "Open the dial pad");
        for (char k : "5551234567".toCharArray()) {
            press(v, String.valueOf(k));
        }
        press(v, "Call anyway");
        assertTrue("the first press only warns: " + pc.told, pc.told.isEmpty());
        assertTrue(modelOf(s).message.contains("is not connected to the phone"));
        draw(v, "service-warning");
        press(v, "Call");
        assertTrue("the second press calls: " + pc.told, pc.told.contains("dial:5551234567"));
        stop(sc);
    }

    private static TabletHeadsetRules.Dev pretendTablet(boolean connected) {
        TabletHeadsetRules.Facts f = new TabletHeadsetRules.Facts();
        f.bluetoothOn = true;
        TabletHeadsetRules.Dev d = new TabletHeadsetRules.Dev();
        d.addr = "AA:BB:CC:DD:EE:01";
        d.name = "Sony WH-1000XM4";
        d.connected = connected;
        f.bonded.add(d);
        TabletHeadset.pretend = f;
        return d;
    }

    private MiniPhoneView shown(FloatingPhoneController s) throws Exception {
        s.command(FloatingPhoneController.ACTION_SHOW);
        settle();
        MiniPhoneView v = viewOf(s);
        assertNotNull(v);
        return v;
    }

    @Test
    public void connectingToTheTabletLetsTheHeadsetGoFromThePhoneFirst() throws Exception {
        PretendCalls pc = CallsPreviewTest.phone();
        TabletHeadsetRules.Dev d = pretendTablet(false);
        ServiceController<FloatingPhoneService> sc = service(pc);
        FloatingPhoneController s = FloatingPhoneController.active();
        shown(s);
        assertTrue("the headset is on the phone", modelOf(s).headsetOnPhone);
        assertFalse(modelOf(s).tabletOn);

        press(viewOf(s), "Headset Bluetooth link to this tablet");
        PreviewTest.later(1000);
        assertTrue("the phone was asked to let go first: " + pc.told, pc.told.contains("disconnect"));
        assertFalse("it is not on both: the tablet waits until the phone has let go", d.connected && modelOf(s).headsetOnPhone);
        PreviewTest.later(4000);
        assertTrue("then it connected to the tablet", d.connected);
        assertFalse("and it is no longer on the phone", modelOf(s).headsetOnPhone);
        assertTrue(modelOf(s).tabletOn);
        draw(viewOf(s), "service-handover-tablet");

        // and back: connecting it to the phone lets the tablet go first
        pc.told.clear();
        press(viewOf(s), "Headset Bluetooth link to the phone");
        PreviewTest.later(4000);
        assertFalse("the tablet let go", d.connected);
        assertTrue("then the phone connected it: " + pc.told, pc.told.stream().anyMatch(x -> x.startsWith("connect:")));
        assertTrue(modelOf(s).headsetOnPhone);
        assertFalse(modelOf(s).tabletOn);
        stop(sc);
    }

    @Test
    public void aHeadsetThatKeepsBothIsNotDisconnected() throws Exception {
        PretendCalls pc = CallsPreviewTest.phone();
        TabletHeadsetRules.Dev d = pretendTablet(false);
        Cfg.setHeadsetBoth(ctx(), true);
        ServiceController<FloatingPhoneService> sc = service(pc);
        FloatingPhoneController s = FloatingPhoneController.active();
        shown(s);
        press(viewOf(s), "Headset Bluetooth link to this tablet");
        PreviewTest.later(2000);
        assertTrue(d.connected);
        assertFalse("the phone was NOT asked to let go: " + pc.told, pc.told.contains("disconnect"));
        assertTrue("it is on both", modelOf(s).headsetOnPhone && modelOf(s).tabletOn);
        draw(viewOf(s), "service-both-bluetooth");
        stop(sc);
        Cfg.setHeadsetBoth(ctx(), false);
    }

    @Test
    public void aCallsSoundIsNotPulledAwayFromThePhone() throws Exception {
        PretendCalls pc = CallsPreviewTest.phone();
        TabletHeadsetRules.Dev d = pretendTablet(false);
        ServiceController<FloatingPhoneService> sc = service(pc);
        FloatingPhoneController s = FloatingPhoneController.active();
        pc.onCall("+15551234567", "Mom", true, 5000);
        settle();
        press(viewOf(s), "Headset Bluetooth link to this tablet");
        PreviewTest.later(3000);
        assertFalse("nothing was disconnected: " + pc.told, pc.told.contains("disconnect"));
        assertFalse(d.connected);
        assertTrue("it says to end the call first: " + modelOf(s).message, modelOf(s).message.contains("End the call first"));
        assertTrue("the call is still on the headset", modelOf(s).headsetOnPhone);
        stop(sc);
    }

    @Test
    public void thePhoneDoesNotConnectWhileTheTabletStillHoldsTheHeadset() throws Exception {
        PretendCalls pc = CallsPreviewTest.phone();
        pc.headsets.devices.get(0).connected = false;
        pc.live.headset = "";
        TabletHeadsetRules.Dev d = pretendTablet(true);
        TabletHeadset.refusedForTest = true; // Android only opens its list for a tap
        ServiceController<FloatingPhoneService> sc = service(pc);
        FloatingPhoneController s = FloatingPhoneController.active();
        shown(s);
        assertTrue(modelOf(s).tabletOn);
        press(viewOf(s), "Headset Bluetooth link to the phone");
        PreviewTest.later(4000);
        assertTrue("the tablet still holds it", d.connected);
        assertTrue("the phone was NOT asked to connect: " + pc.told, pc.told.stream().noneMatch(x -> x.startsWith("connect:")));
        assertTrue("it says what to do: " + modelOf(s).message, modelOf(s).message.contains("press the Phone button again"));
        assertNotNull("Android's Bluetooth list was opened for the tap", org.robolectric.Shadows.shadowOf((android.app.Application) ctx()).getNextStartedActivity());
        stop(sc);
    }

    @Test
    public void thePhoneAloneDisconnectsAndConnects() throws Exception {
        PretendCalls pc = CallsPreviewTest.phone();
        pretendTablet(false);
        ServiceController<FloatingPhoneService> sc = service(pc);
        FloatingPhoneController s = FloatingPhoneController.active();
        shown(s);
        press(viewOf(s), "Headset Bluetooth link to the phone");
        PreviewTest.later(2000);
        assertTrue("Disconnect reached the phone: " + pc.told, pc.told.contains("disconnect"));
        assertFalse(modelOf(s).headsetOnPhone);
        draw(viewOf(s), "service-phone-bt-off");
        press(viewOf(s), "Headset Bluetooth link to the phone");
        PreviewTest.later(2000);
        assertTrue("Connect reached the phone: " + pc.told, pc.told.stream().anyMatch(x -> x.startsWith("connect:")));
        assertTrue(modelOf(s).headsetOnPhone);
        // Android refuses on the tablet: said as a tap in Android's list, never as done
        TabletHeadset.refusedForTest = true;
        press(viewOf(s), "Headset Bluetooth link to this tablet");
        PreviewTest.later(4000);
        assertFalse(modelOf(s).tabletOn);
        stop(sc);
    }

    @Test
    public void theSameCardIsDrawnByTheAccessibilityServiceWhereAndroidTurnsOverlaysOff() throws Exception {
        PretendCalls pc = CallsPreviewTest.phone();
        FloatingPhone.canDrawForTest = false; // Android 10 Go: "Feature not available"
        Cfg.setMode(ctx(), Cfg.TABLET);
        Cfg.setCallsWatch(ctx(), true);
        Cfg.setFloatOn(ctx(), true);
        Cfg.clearFloatPos(ctx());
        assertFalse("without the service nothing can be shown", FloatingPhone.canShow(ctx()));
        assertFalse(FloatingPhone.wanted(ctx()));
        ServiceController<FloatingPhoneAccess> sc = Robolectric.buildService(FloatingPhoneAccess.class).create();
        FloatingPhoneAccess a = sc.get();
        a.onServiceConnected(); // Android connects an accessibility service the user switched on
        settle();
        assertTrue("the accessibility service is on", FloatingPhone.accessOn());
        assertTrue("so the card can be shown without Android's overlay permission", FloatingPhone.canShow(ctx()));
        assertFalse("and no foreground service is wanted", FloatingPhone.wanted(ctx()));
        assertTrue(FloatingPhone.stateLine(ctx()).startsWith("On, through Accessibility"));
        FloatingPhoneController s = FloatingPhoneController.active();
        assertNotNull(s);
        assertNull("idle: nothing shows", viewOf(s));

        pc.ring("+15551234567", "Mom");
        settle();
        assertNotNull("a ring pops the card up", viewOf(s));
        assertEquals("it is an accessibility overlay window", android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, paramsOf(s).type);
        press(viewOf(s), "Answer");
        assertTrue("Answer reached the phone: " + pc.told, pc.told.contains("answer"));
        draw(viewOf(s), "access-active");
        press(viewOf(s), "End call");
        assertTrue(pc.told.contains("hangup"));
        PreviewTest.later(FloatRules.LINGER_MS + 1000);
        assertNull(viewOf(s));
        sc.destroy();
        assertFalse("switched off in Accessibility: it is gone", FloatingPhone.accessOn());
        assertNull(FloatingPhoneController.active());
    }

    @Test
    public void theAccessibilityServiceReplacesTheForegroundService() throws Exception {
        PretendCalls pc = CallsPreviewTest.phone();
        Cfg.setMode(ctx(), Cfg.TABLET);
        Cfg.setCallsWatch(ctx(), true);
        Cfg.setFloatOn(ctx(), true);
        FloatingPhone.canDrawForTest = true; // Android allows overlays here too
        assertTrue(FloatingPhone.wanted(ctx()));
        FloatingPhone.accessForTest = true; // and the accessibility service is also on
        assertFalse("then only one of the two runs: the foreground service is not wanted", FloatingPhone.wanted(ctx()));
    }

    @Test
    public void aPhoneThatCannotBeHeardMidCallIsNotACallThatEnded() throws Exception {
        PretendCalls pc = CallsPreviewTest.phone();
        ServiceController<FloatingPhoneService> sc = service(pc);
        FloatingPhoneController s = FloatingPhoneController.active();
        pc.onCall("+15551234567", "Mom", true, 5000);
        settle();
        assertEquals(Calls.ACTIVE, modelOf(s).call);
        pc.offline = true; // the hotspot went away: the phone cannot be heard any more
        pc.bump();
        settle();
        PreviewTest.later(1500);
        assertNotNull("the card stays", viewOf(s));
        assertTrue("it says so, and its buttons are off", modelOf(s).offline);
        assertEquals("Can't reach the phone", modelOf(s).subtitle);
        assertFalse("it does NOT claim that the call ended", modelOf(s).subtitle.startsWith("Call ended"));
        draw(viewOf(s), "service-offline-midcall");
        PreviewTest.later(25_000);
        assertNull("after a while it goes without a word about the call", viewOf(s));
        stop(sc);
    }

    @Test
    public void staysOutOfTheWayOfLinkMeterItselfUnlessAsked() throws Exception {
        PretendCalls pc = CallsPreviewTest.phone();
        ServiceController<FloatingPhoneService> sc = service(pc);
        FloatingPhoneController s = FloatingPhoneController.active();
        pc.onCall("+15551234567", "Mom", true, 5000);
        settle();
        assertNotNull("a call over another app shows the card", viewOf(s));
        FloatingPhone.setAppInFront(true); // the user came back to LinkMeter, which shows the call itself
        settle();
        assertNull("the card steps aside", viewOf(s));
        FloatingPhone.setAppInFront(false);
        settle();
        assertNotNull("and is back when LinkMeter goes away", viewOf(s));
        s.command(FloatingPhoneController.ACTION_SHOW); // "Show the phone now"
        FloatingPhone.setAppInFront(true);
        settle();
        assertNotNull("unless the user asked to see it", viewOf(s));
        stop(sc);
    }

    @Test
    public void dragSnapAndRememberThePlace() throws Exception {
        PretendCalls pc = CallsPreviewTest.phone();
        ServiceController<FloatingPhoneService> sc = service(pc);
        FloatingPhoneController s = FloatingPhoneController.active();
        s.command(FloatingPhoneController.ACTION_SHOW);
        settle();
        android.view.WindowManager.LayoutParams lp = paramsOf(s);
        int sw = ctx().getResources().getDisplayMetrics().widthPixels;
        int sh = ctx().getResources().getDisplayMetrics().heightPixels;
        s.dragBy(-100000, -100000);
        assertTrue("it cannot be dragged off the top left: " + lp.x + "," + lp.y, lp.x >= 0 && lp.y >= 0);
        s.dragBy(100000, 100000);
        assertTrue("nor off the bottom right: " + lp.x + "," + lp.y, lp.x <= sw && lp.y <= sh);
        s.dragBy(-(lp.x - 40), -(lp.y - 300));
        s.dragEnd();
        assertEquals("it slides to the nearer side edge", Ui.dp(ctx(), 6), lp.x);
        assertEquals("and the place is remembered", lp.x, Cfg.floatX(ctx()));
        assertEquals(lp.y, Cfg.floatY(ctx()));
        stop(sc);
        // the next start puts it back where it was left
        ServiceController<FloatingPhoneService> sc2 = service(pc);
        Cfg.setFloatPos(ctx(), Ui.dp(ctx(), 6), 300);
        FloatingPhoneController.active().command(FloatingPhoneController.ACTION_SHOW);
        settle();
        assertEquals(300, paramsOf(FloatingPhoneController.active()).y);
        stop(sc2);
    }

    @Test
    public void thePhonesBatteryShowsOnTheCallsTabAndTheHeadsetScreen() throws Exception {
        PretendCalls pc = CallsPreviewTest.phone();
        MainActivity a = CallsPreviewTest.calls();
        PreviewTest.shot(a, "float-calls-battery");
        View icon = null;
        for (BatteryIconView b : batteryViews(CallsPreviewTest.decor(a))) {
            icon = b;
        }
        assertNotNull("the Calls tab shows the headset's battery while it is connected", icon);
        assertEquals(78, ((BatteryIconView) icon).level());
        pc.live.headsetBattery = 33;
        pc.bump();
        settle();
        assertEquals("a new level arrives through the long request", 33, ((BatteryIconView) icon).level());
        // the Headset screen: the battery card and the tablet card
        CallsPreviewTest.tapText(a, "Sony WH-1000XM4 · connected");
        settle();
        PreviewTest.shot(a, "float-headset-screen");
        assertNotNull("the battery card", CallsPreviewTest.text(a, "Headset battery"));
        assertNotNull("the tablet card", CallsPreviewTest.text(a, "This tablet"));
        // the phone does not report: said in words, nothing invented
        pc.live.headsetBattery = Calls.BATTERY_UNKNOWN;
        pc.headsets.devices.get(0).battery = Calls.BATTERY_UNKNOWN;
        pc.bump();
        settle();
    }

    private static java.util.List<BatteryIconView> batteryViews(View v) {
        java.util.List<BatteryIconView> out = new java.util.ArrayList<>();
        if (v instanceof BatteryIconView && v.isShown()) {
            out.add((BatteryIconView) v);
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                out.addAll(batteryViews(g.getChildAt(i)));
            }
        }
        return out;
    }

    @Test
    public void theCardOnTheRealTabletScreens() throws Exception {
        // the same card is used by the Headset screen: the battery card and the tablet card
        PretendCalls pc = CallsPreviewTest.phone();
        MainActivity a = CallsPreviewTest.calls();
        PreviewTest.shot(a, "float-calls-with-battery");
        CallsPreviewTest.tapDescription(a, "Show the floating phone");
        settle();
    }
}
