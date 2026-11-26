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
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * The floating phone's Recents and Contacts tabs, its Text button and the small texting card (docs/FLOATING_PHONE.md), and the fix for a
 * call list that stayed old after the app came back (CallWatch.refresh). The service runs against the pretend phone that executes the REAL
 * phone-side code (CallApi, SmsApi); only Android's window and keyboard are not there. Pictures: out/float-recents.png, float-contacts.png,
 * float-texts-*.png. Run with tools/preview.sh --tests "com.tj.linkmeter.FloatingExtrasPreviewTest".
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 29, qualifiers = "w565dp-h916dp-port-170dpi")
public class FloatingExtrasPreviewTest {
    private PretendSms sms;

    @Before
    public void setUp() throws Exception {
        CallWatch.dropForTest();
        RuntimeEnvironment.setFontScale(1.2f);
        FloatingPhone.canDrawForTest = true;
    }

    @After
    public void tearDown() {
        CallWatch.testLink = null;
        TextsTab.testLink = null;
        FloatingPhone.canDrawForTest = null;
        FloatingPhone.accessForTest = null;
        FloatingPhone.setAppInFront(false);
        CallWatch.dropForTest();
    }

    private static Context ctx() {
        return RuntimeEnvironment.getApplication();
    }

    // ------------------------------------------------------------ helpers

    private static Object field(Object o, String name) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static MiniPhoneView viewOf(FloatingPhoneController s) throws Exception {
        return (MiniPhoneView) field(s, "view");
    }

    private static MiniTextView textViewOf(FloatingPhoneController s) throws Exception {
        return (MiniTextView) field(s, "textView");
    }

    private static MiniTexts textsOf(FloatingPhoneController s) throws Exception {
        return (MiniTexts) field(s, "texts");
    }

    private static WindowManager.LayoutParams phoneParams(FloatingPhoneController s) throws Exception {
        return (WindowManager.LayoutParams) field(s, "lp");
    }

    private static WindowManager.LayoutParams textParams(FloatingPhoneController s) throws Exception {
        return (WindowManager.LayoutParams) field(s, "textLp");
    }

    private static View find(View v, String d) {
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

    private static void press(View root, String description) throws Exception {
        View b = find(root, description);
        assertNotNull("there is a button called " + description, b);
        assertTrue(description + " is enabled", b.isEnabled());
        b.performClick();
        settle();
    }

    /** Lays the card out at its width (the card is not in a window here) so that its lists have their rows. */
    private static void layout(View v, int widthDp) {
        int w = Ui.dp(ctx(), widthDp);
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
    }

    /** Draws a card over a pretend app screen and writes out/float-NAME.png. */
    private static void draw(View v, int widthDp, String name) throws Exception {
        layout(v, widthDp);
        int sw = 600;
        int sh = Math.max(973, v.getMeasuredHeight() + 160);
        Bitmap b = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF2B3A55);
        c.drawRect(0, 0, sw, sh, p);
        p.setColor(0xFFE9EEF7);
        for (int i = 0; i < 18; i++) {
            c.drawRect(30, 60 + i * 48, 30 + (i % 3 == 0 ? 340 : 480), 76 + i * 48, p);
        }
        c.save();
        c.translate(sw - v.getMeasuredWidth() - 22, 60);
        v.draw(c);
        c.restore();
        File dir = new File(System.getProperty("preview.out", "out"));
        dir.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(dir, "float-" + name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
        System.out.println("drew float-" + name);
    }

    private PretendCalls phone() throws Exception {
        PretendCalls pc = CallsPreviewTest.phone();
        sms = new PretendSms(PretendSms.gradientJpeg());
        TextsTab.testLink = sms.link();
        return pc;
    }

    private ServiceController<FloatingPhoneService> service() throws Exception {
        Cfg.setMode(ctx(), Cfg.TABLET);
        Cfg.setCallsWatch(ctx(), true);
        Cfg.setFloatOn(ctx(), true);
        Cfg.clearFloatPos(ctx());
        ServiceController<FloatingPhoneService> sc = Robolectric.buildService(FloatingPhoneService.class).create();
        settle();
        return sc;
    }

    /** The card up and its pad open. */
    private MiniPhoneView openCard(FloatingPhoneController s) throws Exception {
        s.command(FloatingPhoneController.ACTION_SHOW);
        settle();
        MiniPhoneView v = viewOf(s);
        assertNotNull(v);
        press(v, "Open the dial pad, recents and contacts");
        assertTrue(v.expanded());
        return v;
    }

    private static int rows(MiniPhoneView v, String list) throws Exception {
        android.widget.BaseAdapter a = (android.widget.BaseAdapter) field(v, list);
        return a.getCount();
    }

    // ------------------------------------------------------------ Recents and Contacts

    @Test
    public void recentsAndContactsShowThePhonesCallsAndPeople() throws Exception {
        PretendCalls pc = phone();
        ServiceController<FloatingPhoneService> sc = service();
        FloatingPhoneController s = FloatingPhoneController.active();
        MiniPhoneView v = openCard(s);
        assertEquals("the pad is the first tab", MiniPhoneView.TAB_KEYPAD, v.tab());
        draw(v, MiniPhoneView.WIDTH_DP, "keypad-tabs");

        press(v, "Recents");
        assertEquals(MiniPhoneView.TAB_RECENTS, v.tab());
        int recents = rows(v, "recentsAdapter");
        assertTrue("the phone's recent calls are listed: " + recents, recents >= 3);
        layout(v, MiniPhoneView.WIDTH_DP);
        draw(v, MiniPhoneView.WIDTH_DP, "recents");

        press(v, "Contacts");
        assertEquals(MiniPhoneView.TAB_CONTACTS, v.tab());
        int contacts = rows(v, "contactsAdapter");
        assertTrue("the phone's contacts are listed with letters above them: " + contacts, contacts >= 4);
        draw(v, MiniPhoneView.WIDTH_DP, "contacts");

        // a line's number goes onto the keypad, and the green button then calls it with the usual guard
        List<Calls.Entry> log = pc.log;
        String number = log.get(0).number;
        press(v, "Recents");
        layout(v, MiniPhoneView.WIDTH_DP);
        String label = log.get(0).name.isEmpty() ? CallRules.pretty(number) : log.get(0).name;
        press(v, "Call " + label);
        assertEquals("the call button of a line is pressed", MiniPhoneView.TAB_KEYPAD, v.tab());
        assertTrue("the phone was told to dial it: " + pc.told, pc.told.contains("dial:" + CallRules.check(number).number));
        stopService(sc);
    }

    @Test
    public void theContactsSearchTakesTheKeyboardOnlyWhileTyping() throws Exception {
        phone();
        ServiceController<FloatingPhoneService> sc = service();
        FloatingPhoneController s = FloatingPhoneController.active();
        MiniPhoneView v = openCard(s);
        press(v, "Contacts");
        android.widget.EditText find = (android.widget.EditText) field(v, "find");
        int before = phoneParams(s).flags;
        assertTrue("the card never takes the keyboard by itself", (before & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0);
        int yBefore = phoneParams(s).y;

        long t = SystemClock.uptimeMillis();
        find.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 5, 5, 0));
        settle();
        int during = phoneParams(s).flags;
        assertEquals("touching the search box lets the window take the keyboard", 0, during & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        assertTrue("and it hears the touches outside", (during & WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH) != 0);
        assertEquals("the card is lifted out of the keyboard's way", Ui.dp(ctx(), 8), phoneParams(s).y);

        // typing filters the list; a touch outside ends the typing
        int everybody = rows(v, "contactsAdapter");
        find.setText("Bob");
        settle();
        int found = rows(v, "contactsAdapter");
        assertTrue("typing narrows the contacts: " + found + " of " + everybody, found >= 1 && found < everybody);
        long t2 = SystemClock.uptimeMillis();
        v.onTouchEvent(MotionEvent.obtain(t2, t2, MotionEvent.ACTION_OUTSIDE, 0, 0, 0));
        settle();
        assertTrue("a touch outside gives the keyboard up", (phoneParams(s).flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0);
        assertEquals("and the card goes back where it was", yBefore, phoneParams(s).y);
        stopService(sc);
    }

    // ------------------------------------------------------------ the Text button and the small texting card

    @Test
    public void theTextButtonOpensTheTextingCardAndAMessageGoesToThePhone() throws Exception {
        phone();
        ServiceController<FloatingPhoneService> sc = service();
        FloatingPhoneController s = FloatingPhoneController.active();
        s.command(FloatingPhoneController.ACTION_SHOW);
        settle();
        MiniPhoneView v = viewOf(s);
        assertNull("the texting card is not there until asked for", textViewOf(s));
        press(v, "Text messages");
        MiniTextView t = textViewOf(s);
        assertNotNull("the Text button opens the texting card", t);
        MiniTexts texts = textsOf(s);
        assertEquals(MiniTexts.Mode.LIST, texts.mode);
        assertTrue("the phone's conversations are listed: " + texts.threads.size(), texts.threads.size() >= 3);
        draw(t, MiniTextView.WIDTH_DP, "texts-list");

        // a conversation opens with its newest messages
        press(t, "Texts. Drag to move."); // (the header only drags: nothing happens)
        layout(t, MiniTextView.WIDTH_DP);
        Sms.Thread first = texts.threads.get(0);
        texts.openThread(first);
        settle();
        assertEquals(MiniTexts.Mode.THREAD, texts.mode);
        assertFalse("its messages are there: " + texts.messages().size(), texts.messages().isEmpty());
        draw(t, MiniTextView.WIDTH_DP, "texts-thread");

        // a message is in the conversation at once and goes to the phone, whose copy then takes the bubble's place
        int before = texts.messages().size();
        t.typeForTest("on my way from the floating card");
        t.sendForTest();
        assertEquals("the bubble is there at once", before + 1, texts.messages().size());
        settle();
        settle();
        int copies = 0;
        for (Sms.Msg m : texts.messages()) {
            copies += m.body.equals("on my way from the floating card") ? 1 : 0;
        }
        assertEquals("the phone's copy took the bubble's place (one, not two)", 1, copies);
        boolean onPhone = false;
        for (Sms.Msg m : TextsTab.remoteFor(ctx()).thread(first.key, 100).msgs) {
            onPhone |= m.mine && m.body.equals("on my way from the floating card");
        }
        assertTrue("the phone has the message (read back through the same routes as the Texts tab)", onPhone);
        draw(t, MiniTextView.WIDTH_DP, "texts-sent");

        // back to the list; the list's newest line follows
        press(t, "Back to the conversations");
        assertEquals(MiniTexts.Mode.LIST, texts.mode);
        // the cross closes the card and gives the keyboard up
        press(t, "Close the texts");
        assertNull("the cross closes the card", textViewOf(s));
        stopService(sc);
    }

    @Test
    public void aNewMessageFindsAContactByNameOrTakesANumber() throws Exception {
        phone();
        ServiceController<FloatingPhoneService> sc = service();
        FloatingPhoneController s = FloatingPhoneController.active();
        s.command(FloatingPhoneController.ACTION_SHOW);
        settle();
        press(viewOf(s), "Text messages");
        MiniTextView t = textViewOf(s);
        MiniTexts texts = textsOf(s);
        press(t, "New message");
        assertEquals(MiniTexts.Mode.NEW, texts.mode);
        settle();
        texts.setToTyped("tay");
        assertFalse("a name finds the contact: " + texts.picks.size(), texts.picks.isEmpty());
        assertEquals("Taylor Brooks", texts.picks.get(0).name);
        draw(t, MiniTextView.WIDTH_DP, "texts-new");
        texts.pick(texts.picks.get(0));
        settle();
        assertEquals(MiniTexts.Mode.THREAD, texts.mode);
        assertEquals("Taylor Brooks", texts.name);
        assertEquals("+15551112222", texts.to.get(0));
        assertTrue("a message goes to that number", texts.send("hello Taylor"));
        settle();

        // a typed number is taken as it is; a name that is not a number and not a contact offers nobody
        texts.showNew();
        texts.setToTyped("(555) 987-6543");
        assertEquals("+15559876543", texts.picks.get(0).number);
        texts.setToTyped("qqqq");
        assertTrue(texts.picks.isEmpty());
        assertFalse("nothing is sent to nobody", texts.send("lost"));
        stopService(sc);
    }

    @Test
    public void aNumberOnARecentsLineOpensItsConversationAndTheCardHidesBehindTheApp() throws Exception {
        phone();
        ServiceController<FloatingPhoneService> sc = service();
        FloatingPhoneController s = FloatingPhoneController.active();
        MiniPhoneView v = openCard(s);
        press(v, "Contacts");
        ((android.widget.EditText) field(v, "find")).setText("Mom"); // the list shows five lines at a time: search for the one wanted
        settle();
        layout(v, MiniPhoneView.WIDTH_DP);
        press(v, "Text Mom");
        MiniTextView t = textViewOf(s);
        assertNotNull("a contact's message button opens the texting card", t);
        MiniTexts texts = textsOf(s);
        assertEquals(MiniTexts.Mode.THREAD, texts.mode);
        assertEquals("+15551234567", texts.to.get(0));
        String keyBefore = texts.key;

        // LinkMeter's own screens show the texts themselves: the card steps aside and comes back
        FloatingPhone.setAppInFront(true);
        settle();
        assertNull("the card steps aside for the app", textViewOf(s));
        FloatingPhone.setAppInFront(false);
        settle();
        assertNotNull("and is back when the app goes away", textViewOf(s));
        assertEquals("with the same conversation", keyBefore, textsOf(s).key);

        // its call button hands the number to the phone card's keypad (same guard, same headset warning)
        press(textViewOf(s), "Call");
        MiniPhoneView pv = viewOf(s);
        assertNotNull(pv);
        assertTrue(pv.expanded());
        assertEquals("+15551234567", pv.typed());
        stopService(sc);
    }

    // ------------------------------------------------------------ the call list that stayed old

    /**
     * A link that goes dead after it was told to: a request that waits for news (it carries "since=") is no longer answered once {@code dead} is
     * set, even when it is already open at the phone; it ends late with a timeout, the way a dead connection does. Everything else goes through.
     */
    private static RemoteStore.Link deadAfter(final RemoteStore.Link inner, final AtomicBoolean dead) {
        return new RemoteStore.Link() {
            @Override
            public Client.Resp call(String method, String target, int readMs) throws IOException {
                if (!target.contains("since=")) {
                    return inner.call(method, target, readMs);
                }
                final Client.Resp[] answer = new Client.Resp[1];
                final IOException[] failed = new IOException[1];
                final boolean[] done = {false};
                Thread t = new Thread(() -> {
                    try {
                        answer[0] = inner.call(method, target, readMs);
                    } catch (IOException e) {
                        failed[0] = e;
                    }
                    synchronized (done) {
                        done[0] = true;
                    }
                }, "dead-link-inner");
                t.setDaemon(true);
                t.start();
                while (true) {
                    synchronized (done) {
                        if (done[0] && !dead.get()) {
                            break;
                        }
                    }
                    if (dead.get()) {
                        try {
                            Thread.sleep(8000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        throw new java.net.SocketTimeoutException("Read timed out");
                    }
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted");
                    }
                }
                if (failed[0] != null) {
                    throw failed[0];
                }
                return answer[0];
            }

            @Override
            public Client.Stream get(String target, long offset) throws IOException {
                return inner.get(target, offset);
            }

            @Override
            public Client.Resp put(String target, InputStream src, long len, Client.Progress p) throws IOException {
                return inner.put(target, src, len, p);
            }
        };
    }

    @Test
    public void aRequestLeftOpenWhileTheAppWasAwayDoesNotKeepAnEndedCallOnTheScreen() throws Exception {
        PretendCalls pc = phone();
        AtomicBoolean dead = new AtomicBoolean();
        CallWatch.testLink = deadAfter(pc.link(), dead);
        pc.ring("+15551234567", "Mom");
        CallWatch w = CallWatch.shared(ctx());
        w.start();
        settle();
        assertEquals(CallWatch.OK, w.status());
        assertNotNull("the call is on the screen", w.lead());

        // the connection dies (the tablet slept, the Wi-Fi changed): the request that waits for news never gets its answer
        dead.set(true);
        PreviewTest.later(500);
        pc.hangup(); // the call ends on the phone meanwhile
        settle();
        assertNotNull("the open request hears nothing: the ended call is still drawn", w.lead());

        // the app comes back (a good while later: a look that began a moment ago is not repeated): the old request is dropped and the
        // phone is asked at once
        PreviewTest.later(3000);
        w.refresh();
        dead.set(false);
        settle();
        assertNull("the call that ended is gone within a moment, without waiting for the dead request", w.lead());
        assertEquals(CallWatch.OK, w.status());
        w.stop();
    }

    @Test
    public void stoppingTheWatchForgetsWhatItWaitedFor() throws Exception {
        PretendCalls pc = phone();
        pc.ring("+15551234567", "Mom");
        CallWatch w = CallWatch.shared(ctx());
        w.start();
        settle();
        assertNotNull(w.lead());
        w.stop(); // the app went away: nothing listens
        pc.hangup();
        w.start(); // and came back: the first look is a full one, not "tell me when it changes"
        settle();
        assertNull("the call that ended while nobody listened is not shown", w.lead());
        w.stop();
    }

    private static void stopService(ServiceController<FloatingPhoneService> sc) {
        sc.destroy();
    }
}
