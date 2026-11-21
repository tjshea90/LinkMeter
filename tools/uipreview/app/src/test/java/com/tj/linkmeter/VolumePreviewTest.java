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
 * Pictures and behaviour of the headset's volume (docs/PHONE_CALLS.md, "Headset volume"): the row of the floating phone and the card of the
 * Phone tab, against a pretend phone that runs the REAL phone-side route code ({@link CallApi}) and the REAL rules ({@link VolumeRules}).
 * Run with tools/preview.sh --tests "com.tj.linkmeter.VolumePreviewTest"; pictures: out/volume-*.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 29, qualifiers = "w565dp-h916dp-port-170dpi")
public class VolumePreviewTest {
    private static final String LOUDER = "Headset volume louder";
    private static final String QUIETER = "Headset volume quieter";
    private static final String CHIP = "Volume of the headset. Press to switch between the media and the call volume.";

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
        CallWatch.dropForTest();
    }

    private static Context ctx() {
        return RuntimeEnvironment.getApplication();
    }

    // ------------------------------------------------------------ pictures of the card

    private static void draw(MiniPhoneView v, String name) throws Exception {
        int w = Ui.dp(ctx(), MiniPhoneView.WIDTH_DP);
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
        int sw = 600;
        int sh = Math.max(500, v.getMeasuredHeight() + 160);
        Bitmap b = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF2B3A55);
        c.drawRect(0, 0, sw, sh, p);
        p.setColor(0xFFE9EEF7);
        for (int i = 0; i < 8; i++) {
            c.drawRect(30, 60 + i * 48, 30 + (i % 3 == 0 ? 340 : 480), 76 + i * 48, p);
        }
        c.save();
        c.translate(sw - v.getMeasuredWidth() - 22, 60);
        v.draw(c);
        c.restore();
        File dir = new File(System.getProperty("preview.out", "out"));
        dir.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(dir, "volume-" + name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
        System.out.println("drew volume-" + name);
    }

    private MiniPhoneView.Model model(String call, String stream, int now, int min, int max, boolean on, String why) {
        MiniPhoneView.Model m = new MiniPhoneView.Model();
        m.call = call;
        m.title = call.isEmpty() ? "Phone" : "Mom";
        m.subtitle = call.isEmpty() ? "Ready" : "3:12";
        m.headsetOnPhone = true;
        m.batteryShown = true;
        m.headsetName = "Sony WH-1000XM4";
        m.battery = 78;
        m.tabletAble = true;
        m.volumeShown = true;
        m.volumeStream = stream;
        Calls.Volume v = new Calls.Volume();
        Calls.Level l = VolumeRules.level(v, stream);
        l.now = now;
        l.min = min;
        l.max = max;
        v.mediaOnHeadset = on;
        v.callOnHeadset = on;
        m.volume = VolumeRules.row(v, stream, true, true, !call.isEmpty());
        if (!why.isEmpty()) {
            m.message = why;
        }
        return m;
    }

    @Test
    public void thePictures() throws Exception {
        draw(card(model("", Calls.STREAM_MEDIA, 6, 0, 15, true, "")), "card-media");
        draw(card(model(Calls.ACTIVE, Calls.STREAM_CALL, 3, 1, 5, true, "")), "card-call");
        draw(card(model("", Calls.STREAM_MEDIA, 15, 0, 15, true, "")), "card-media-loudest");
        draw(card(model("", Calls.STREAM_MEDIA, 0, 0, 15, true, "")), "card-media-silent");
        draw(card(model("", Calls.STREAM_CALL, 3, 1, 5, true, VolumeRules.CALL_OFF)), "card-call-without-a-call");
        MiniPhoneView.Model elsewhere = model("", Calls.STREAM_MEDIA, 6, 0, 15, false, VolumeRules.MEDIA_ELSEWHERE);
        draw(card(elsewhere), "card-media-elsewhere");
        MiniPhoneView.Model busy = model("", Calls.STREAM_MEDIA, 6, 0, 15, true, "");
        busy.volumeBusy = true;
        draw(card(busy), "card-busy");
        MiniPhoneView.Model gone = model("", Calls.STREAM_MEDIA, 6, 0, 15, true, "");
        gone.volumeShown = false;
        MiniPhoneView hidden = card(gone);
        draw(hidden, "card-no-row");
        View chip = FloatingPreviewTest.find(hidden, CHIP);
        assertNotNull(chip);
        assertFalse("no volume row while the headset is not on the phone", rowShown(hidden));
    }

    private static boolean rowShown(MiniPhoneView v) {
        View b = FloatingPreviewTest.find(v, LOUDER);
        return b != null && b.isShown();
    }

    private MiniPhoneView card(MiniPhoneView.Model m) {
        MiniPhoneView v = new MiniPhoneView(ctx(), quiet);
        v.bind(m);
        return v;
    }

    // ------------------------------------------------------------ the floating phone, end to end

    private ServiceController<FloatingPhoneService> service() throws Exception {
        Cfg.setMode(ctx(), Cfg.TABLET);
        Cfg.setCallsWatch(ctx(), true);
        Cfg.setFloatOn(ctx(), true);
        Cfg.clearFloatPos(ctx());
        ServiceController<FloatingPhoneService> sc = Robolectric.buildService(FloatingPhoneService.class).create();
        settle();
        return sc;
    }

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

    private static void press(FloatingPhoneController s, String description) throws Exception {
        View b = FloatingPreviewTest.find(viewOf(s), description);
        assertNotNull("the card has a button called " + description, b);
        assertTrue(description + " can be pressed", b.isEnabled());
        b.performClick();
        settle();
    }

    @Test
    public void theFloatingPhoneMovesTheHeadsetVolumeOneStepAtATime() throws Exception {
        PretendCalls pc = CallsPreviewTest.phone();
        ServiceController<FloatingPhoneService> sc = service();
        FloatingPhoneController s = FloatingPhoneController.active();
        s.command(FloatingPhoneController.ACTION_SHOW); // the user asked to see the phone: no call is needed
        settle();
        assertNotNull(viewOf(s));
        assertTrue("the row shows while the headset is on the phone", modelOf(s).volumeShown);
        assertEquals("no call: the media volume first", Calls.STREAM_MEDIA, modelOf(s).volumeStream);
        assertEquals("6 of 15", modelOf(s).volume.words);
        assertTrue(modelOf(s).volume.enabled);
        draw(viewOf(s), "service-media");

        press(s, LOUDER);
        assertTrue("one step up reached the phone: " + pc.told, pc.told.contains("volume:media:1"));
        assertEquals(7, pc.volumes.media.now);
        assertEquals("the card shows what the phone has now", "7 of 15", modelOf(s).volume.words);
        press(s, QUIETER);
        press(s, QUIETER);
        assertEquals(5, pc.volumes.media.now);
        assertEquals("5 of 15", modelOf(s).volume.words);

        // the chip switches to the call volume: no call, so the buttons only say why and nothing moves
        press(s, CHIP);
        assertEquals(Calls.STREAM_CALL, modelOf(s).volumeStream);
        assertFalse(modelOf(s).volume.enabled);
        press(s, LOUDER);
        assertFalse("nothing was asked of the phone: " + pc.told, pc.told.contains("volume:call:1"));
        assertEquals(VolumeRules.CALL_OFF, modelOf(s).message);
        assertEquals(3, pc.volumes.call.now);
        draw(viewOf(s), "service-call-without-a-call");

        // a call begins: the row goes to the call volume by itself and works
        pc.onCall("+15551234567", "Mom", true, 5_000);
        settle();
        assertEquals(Calls.STREAM_CALL, modelOf(s).volumeStream);
        assertTrue(modelOf(s).volume.enabled);
        press(s, LOUDER);
        assertTrue("the call volume went up one step: " + pc.told, pc.told.contains("volume:call:1"));
        assertEquals(4, pc.volumes.call.now);
        assertEquals("4 of 5", modelOf(s).volume.words);
        draw(viewOf(s), "service-call");
        // the sound moves to the phone's own speaker: the headset's call volume is left alone
        pc.live.route = Calls.ROUTE_SPEAKER;
        pc.bump();
        settle();
        assertFalse(modelOf(s).volume.enabled);
        press(s, LOUDER);
        assertEquals("a call that is not on the headset moved nothing", 4, pc.volumes.call.now);
        pc.live.route = Calls.ROUTE_HEADSET;
        pc.hangUp();
        settle();
        assertEquals("the call ended: back to the media volume", Calls.STREAM_MEDIA, modelOf(s).volumeStream);

        // the phone's own keys change the volume: the card follows
        pc.volumes.media.now = 9;
        pc.bump();
        settle();
        assertEquals("9 of 15", modelOf(s).volume.words);

        // the headset goes away: the row goes away
        pc.live.headset = "";
        pc.bump();
        settle();
        assertFalse("no headset on the phone, no volume row", modelOf(s).volumeShown);
        sc.destroy();
    }

    @Test
    public void aRefusalOfTheLastStepIsSaid() throws Exception {
        PretendCalls pc = CallsPreviewTest.phone();
        pc.volumes.media.now = 15;
        ServiceController<FloatingPhoneService> sc = service();
        FloatingPhoneController s = FloatingPhoneController.active();
        s.command(FloatingPhoneController.ACTION_SHOW);
        settle();
        assertEquals("15 of 15", modelOf(s).volume.words);
        assertFalse("already loudest: the louder button has nothing to do", FloatingPreviewTest.find(viewOf(s), LOUDER).isEnabled());
        assertTrue(FloatingPreviewTest.find(viewOf(s), QUIETER).isEnabled());
        press(s, QUIETER);
        assertEquals(14, pc.volumes.media.now);
        sc.destroy();
    }

    // ------------------------------------------------------------ the Phone tab

    @Test
    public void thePhoneTabHasTheHeadsetVolumeCard() throws Exception {
        PretendCalls pc = CallsPreviewTest.phone();
        MainActivity a = PreviewTest.tablet();
        a.openTab(Tab.PHONE);
        settle();
        PreviewTest.shot(a, "volume-phone-tab");
        assertNotNull("the card is there", CallsPreviewTest.text(a, "Headset volume"));
        assertNotNull("with the headset's name", CallsPreviewTest.text(a, "Sony WH-1000XM4"));
        assertNotNull("media 6 of 15", CallsPreviewTest.text(a, "6 of 15"));
        assertNotNull("call 3 of 5", CallsPreviewTest.text(a, "3 of 5"));

        CallsPreviewTest.tapDescription(a, "Media volume louder");
        settle();
        assertTrue("the press reached the phone: " + pc.told, pc.told.contains("volume:media:1"));
        assertEquals(7, pc.volumes.media.now);
        assertNotNull("and the card shows what the phone has now", CallsPreviewTest.text(a, "7 of 15"));
        assertNotNull("the answer is in the strip", CallsPreviewTest.text(a, "Media volume 7 of 15."));
        PreviewTest.shot(a, "volume-phone-tab-after");

        // the call volume says why it is off while no call is on, and nothing is sent
        CallsPreviewTest.tapDescription(a, "Call volume louder");
        settle();
        assertFalse("nothing was asked of the phone", pc.told.contains("volume:call:1"));
        assertNotNull("the card says why", CallsPreviewTest.text(a, VolumeRules.CALL_OFF));

        // a call: the call volume works
        pc.onCall("+15551234567", "Mom", true, 4_000);
        settle();
        CallsPreviewTest.tapDescription(a, "Call volume quieter");
        settle();
        assertTrue(pc.told.contains("volume:call:-1"));
        assertEquals(2, pc.volumes.call.now);
        pc.hangUp();
        settle();

        // no headset: the card says so and the buttons only explain
        pc.live.headset = "";
        pc.bump();
        settle();
        PreviewTest.shot(a, "volume-phone-tab-no-headset");
        assertNotNull(CallsPreviewTest.text(a, VolumeRules.NO_HEADSET));
        int before = pc.told.size();
        CallsPreviewTest.tapDescription(a, "Media volume louder");
        settle();
        assertEquals("with no headset nothing is sent", before, pc.told.size());
        assertEquals(7, pc.volumes.media.now);
    }

    @Test
    public void thePhoneTabSaysWhyWhenThePhoneCannotBeHeard() throws Exception {
        PretendCalls pc = CallsPreviewTest.phone();
        pc.old = true;
        MainActivity a = PreviewTest.tablet();
        a.openTab(Tab.PHONE);
        settle();
        PreviewTest.shot(a, "volume-phone-tab-old-phone");
        assertNotNull("an old phone app: update it", CallsPreviewTest.text(a, "The phone's LinkMeter app is too old for calls. On this tablet: Phone tab > Update the phone."));
        assertNull("nothing claims a volume", CallsPreviewTest.text(a, "6 of 15"));
    }
}
