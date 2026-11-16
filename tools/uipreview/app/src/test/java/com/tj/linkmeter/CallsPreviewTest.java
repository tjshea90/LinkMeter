package com.tj.linkmeter;

import static com.tj.linkmeter.PreviewTest.pretendPhone;
import static com.tj.linkmeter.PreviewTest.settle;
import static com.tj.linkmeter.PreviewTest.shot;
import static com.tj.linkmeter.PreviewTest.tablet;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * Pictures and behaviour of the Calls tab, against a pretend phone that runs the REAL phone-side code (CallApi): the keypad, the
 * guard that refuses emergency numbers and codes, recents, contacts shared with Texts, the call screen, the strip on other tabs, the
 * headset screen, and every notice (switched off, too old, not reached, not allowed). Run with
 * tools/preview.sh --tests "com.tj.linkmeter.CallsPreviewTest"; the pictures are tools/uipreview/out/calls-*.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 29, qualifiers = "w565dp-h916dp-port-170dpi")
public class CallsPreviewTest {
    @After
    public void tearDown() {
        CallWatch.testLink = null;
        TextsTab.testLink = null;
        TextsVault.testKey = null;
        TextsTab.relock();
    }

    static PretendCalls phone() throws Exception {
        TextsVault.testKey = new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");
        TextsTab.relock();
        pretendPhone("wifi", true, true);
        PretendCalls pc = new PretendCalls();
        CallWatch.testLink = pc.link();
        TextsTab.testLink = new PretendSms(PretendSms.gradientJpeg()).link();
        Cfg.setCallsWatch(RuntimeEnvironment.getApplication(), true);
        return pc;
    }

    static View decor(MainActivity a) {
        return a.getWindow().getDecorView();
    }

    /** The first view under {@code v} (shown) whose content description is exactly {@code d}. */
    static View byDescription(View v, String d) {
        if (v.isShown() && d.contentEquals(String.valueOf(v.getContentDescription()))) {
            return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View b = byDescription(g.getChildAt(i), d);
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }

    static void tapDescription(MainActivity a, String d) {
        View v = byDescription(decor(a), d);
        if (v == null) {
            throw new AssertionError("nothing on the screen is called \"" + d + "\"");
        }
        v.performClick();
    }

    static void typeNumber(MainActivity a, String digits) {
        for (char c : digits.toCharArray()) {
            tapDescription(a, c == '*' ? "star" : c == '#' ? "pound" : String.valueOf(c));
        }
    }

    static TextView text(MainActivity a, String s) {
        return TextsPreviewTest.findText(decor(a), s);
    }

    static void tapText(MainActivity a, String s) {
        TextView t = text(a, s);
        if (t == null) {
            throw new AssertionError("no text \"" + s + "\" on the screen");
        }
        t.performClick();
    }

    static MainActivity calls() throws Exception {
        MainActivity a = tablet();
        a.openTab(Tab.CALLS);
        settle();
        return a;
    }

    // ------------------------------------------------------------ the keypad

    @Test
    public void keypad() throws Exception {
        PretendCalls pc = phone();
        MainActivity a = calls();
        shot(a, "calls-keypad");
        typeNumber(a, "5551234567");
        settle();
        assertNotNull("the number is shown as it is typed", text(a, "(555) 123-4567"));
        shot(a, "calls-keypad-typed");

        tapDescription(a, "Delete the last digit");
        tapDescription(a, "Delete the last digit");
        assertNotNull(text(a, "(555) 123-45"));
        // a name on the keypad: 666 is Mom
        tapDescription(a, "Delete the last digit");
        for (int i = 0; i < 9; i++) {
            tapDescription(a, "Delete the last digit");
        }
        typeNumber(a, "666");
        settle();
        assertNotNull("typing 666 finds Mom", text(a, "Mom · +1 (555) 123-4567"));
        shot(a, "calls-keypad-match");

        // a number nobody has: add to contacts is offered
        for (int i = 0; i < 3; i++) {
            tapDescription(a, "Delete the last digit");
        }
        typeNumber(a, "5558080");
        settle();
        assertNotNull("an unknown number can be added", text(a, "Add to contacts"));
        shot(a, "calls-keypad-add");
        assertTrue("nothing was dialed yet", pc.told.isEmpty());
    }

    @Test
    public void keypadRefusesWhatMustNotBeDialed() throws Exception {
        PretendCalls pc = phone();
        MainActivity a = calls();
        typeNumber(a, "911");
        tapDescription(a, "Call");
        settle();
        assertNotNull("911 is refused with a sentence", text(a, "Emergency numbers cannot be dialed"));
        shot(a, "calls-keypad-emergency");
        tapDescription(a, "Delete the last digit");
        tapDescription(a, "Delete the last digit");
        tapDescription(a, "Delete the last digit");
        typeNumber(a, "*#06#");
        tapDescription(a, "Call");
        settle();
        assertNotNull("a code is refused", text(a, "Codes with #"));
        shot(a, "calls-keypad-code");
        assertTrue("the phone was never asked to dial: " + pc.told, pc.told.isEmpty());
    }

    @Test
    public void keypadDials() throws Exception {
        PretendCalls pc = phone();
        MainActivity a = calls();
        typeNumber(a, "5551234567");
        tapDescription(a, "Call");
        settle();
        settle();
        assertTrue("the phone was asked to dial the number: " + pc.told, pc.told.contains("dial:5551234567"));
        assertNotNull("the call screen follows", text(a, "End call"));
        shot(a, "calls-dialing");
    }

    // ------------------------------------------------------------ recents and contacts

    @Test
    public void recents() throws Exception {
        PretendCalls pc = phone();
        MainActivity a = calls();
        tapText(a, "Recents");
        settle();
        TextView mom = text(a, "Mom (2)");
        assertNotNull("two missed calls from Mom are one line", mom);
        assertEquals("a missed call is red", Ui.RED, mom.getCurrentTextColor());
        assertNotNull(text(a, "Dad"));
        assertNotNull("a number with no name is shown as a number", text(a, "(800) 555-0199"));
        shot(a, "calls-recents");
        assertTrue("looking at the missed calls marks them seen", pc.told.contains("seen"));
        TextsPreviewTest.tapRow(a, 0);
        settle();
        assertNotNull("a tap on a row opens its menu", org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
        PreviewTest.dialogShot("calls-recents-menu");
        TextsPreviewTest.closeDialog();
    }

    @Test
    public void contacts() throws Exception {
        PretendCalls pc = phone();
        MainActivity a = calls();
        tapText(a, "Contacts");
        settle();
        assertNotNull(text(a, "Alice Johnson"));
        assertNotNull("sections have letters", text(a, "A"));
        shot(a, "calls-contacts");
        TextsPreviewTest.findEdit(decor(a), "Search by name or number").setText("ali");
        settle();
        assertNotNull(text(a, "Alice Johnson"));
        assertNull("only matches stay", text(a, "Bob Smith"));
        shot(a, "calls-contacts-search");
        TextsPreviewTest.findEdit(decor(a), "Search by name or number").setText("7");
        settle();
        assertNotNull("a typed number finds a contact by its keypad letters or digits", text(a, "Pizza Place"));
    }

    @Test
    public void contactsAreSharedWithTexts() throws Exception {
        phone();
        MainActivity a = calls();
        TextsTab texts = a.tabOf(TextsTab.class);
        assertNotNull(texts);
        // a name given on the tablet (here, through the Texts side) shows on the Calls side, and the other way round
        texts.rememberName("5559990000", "Sammy From Texts", false);
        tapText(a, "Contacts");
        settle();
        assertNotNull("the name given in Texts shows in Calls", text(a, "Sammy From Texts"));
        assertEquals("and Texts gives the same name to the number", "Sammy From Texts", texts.nameForNumber("+1 (555) 999-0000", "Sam"));
        shot(a, "calls-contacts-shared");
    }

    // ------------------------------------------------------------ a call

    @Test
    public void incomingCallShowsOnEveryTab() throws Exception {
        PretendCalls pc = phone();
        MainActivity a = tablet();
        a.openTab(Tab.HOME);
        settle();
        pc.ring("+15551234567", "Mom");
        Thread.sleep(600);
        settle();
        assertNotNull("the strip names the caller on the Home tab", text(a, "Mom"));
        assertNotNull(text(a, "Incoming call"));
        assertNotNull("with an Answer button", byDescription(decor(a), "Answer"));
        shot(a, "calls-strip-ringing");

        a.openTab(Tab.CALLS);
        settle();
        assertNotNull(text(a, "Decline"));
        assertNotNull(text(a, "Answer"));
        shot(a, "calls-incoming");

        tapDescription(a, "Answer");
        Thread.sleep(600);
        settle();
        assertTrue("the phone was told to answer: " + pc.told, pc.told.contains("answer"));
        assertNotNull(text(a, "End call"));
        shot(a, "calls-incall");

        a.openTab(Tab.HOME);
        settle();
        assertNotNull("a call that is on shows in the strip", byDescription(decor(a), "End call"));
        shot(a, "calls-strip-active");
        tapDescription(a, "End call");
        Thread.sleep(600);
        settle();
        assertTrue(pc.told.contains("hangup"));
        assertNull("the strip is gone when the call ends", byDescription(decor(a), "End call"));
    }

    @Test
    public void callControls() throws Exception {
        PretendCalls pc = phone();
        MainActivity a = calls();
        pc.onCall("+15551234567", "Mom", true, 83_000);
        Thread.sleep(600);
        settle();
        assertNotNull("the length of the call", text(a, "1:2"));
        assertNotNull("the sound goes to the headset", text(a, "Sound on Sony WH-1000XM4"));
        shot(a, "calls-incall-headset");
        tapDescription(a, "Mute");
        Thread.sleep(600);
        settle();
        assertTrue(pc.told.contains("mute:true"));
        assertNotNull("the button says what it does now", text(a, "Unmute"));
        shot(a, "calls-incall-muted");
        tapDescription(a, "Where the sound goes");
        Thread.sleep(600);
        settle();
        assertTrue("the next place after the headset is the speaker: " + pc.told, pc.told.contains("route:speaker"));
        assertNotNull(text(a, "Sound on the phone's speaker"));
        shot(a, "calls-incall-speaker");
    }

    @Test
    public void callFromTheTabletWithoutNumberShowsAsPhoneCall() throws Exception {
        PretendCalls pc = phone();
        MainActivity a = calls();
        pc.onCall("", "", false, 20_000);
        Thread.sleep(600);
        settle();
        assertNotNull("a call placed on the phone itself has no number", text(a, "Phone call"));
        shot(a, "calls-incall-unknown");
    }

    @Test
    public void missedCallsAreOnTheButton() throws Exception {
        PretendCalls pc = phone();
        MainActivity a = tablet();
        a.openTab(Tab.HOME);
        Thread.sleep(800);
        settle();
        assertEquals("two missed calls nobody has looked at", 2, a.badge(Tab.CALLS));
        shot(a, "calls-badge");
        a.openTab(Tab.CALLS);
        settle();
        tapText(a, "Recents");
        Thread.sleep(800);
        settle();
        assertEquals("looking at them clears the number", 0, a.badge(Tab.CALLS));
    }

    // ------------------------------------------------------------ the headset

    @Test
    public void headsetConnected() throws Exception {
        PretendCalls pc = phone();
        MainActivity a = calls();
        assertNotNull("the chip says the headset is connected", text(a, "Sony WH-1000XM4 · connected"));
        shot(a, "calls-chip");
        tapText(a, "Sony WH-1000XM4 · connected");
        settle();
        assertNotNull(text(a, "Sony WH-1000XM4 is connected"));
        assertNotNull("the screen says where the headset belongs", text(a, "The headset connects to the phone"));
        shot(a, "calls-headset");
    }

    @Test
    public void headsetConnectButton() throws Exception {
        PretendCalls pc = phone();
        pc.headsets.devices.get(0).connected = false;
        pc.live.headset = "";
        MainActivity a = calls();
        tapText(a, "Sony WH-1000XM4 · not connected");
        settle();
        assertNotNull(text(a, "Sony WH-1000XM4 is not connected"));
        shot(a, "calls-headset-off");
        PreviewTest.press(a, "Connect headset");
        settle();
        settle();
        assertTrue("the phone was asked to connect: " + pc.told, pc.told.contains("connect:"));
        assertNotNull("and now it is", text(a, "Sony WH-1000XM4 is connected"));
        shot(a, "calls-headset-connected");
    }

    @Test
    public void headsetCouldNotConnect() throws Exception {
        PretendCalls pc = phone();
        pc.headsets.devices.get(0).connected = false;
        pc.live.headset = "";
        pc.connectSays = "Sony WH-1000XM4 did not connect (Bluetooth status 4). Turn it off and on, or use the phone's Bluetooth settings.";
        MainActivity a = calls();
        tapText(a, "Sony WH-1000XM4 · not connected");
        settle();
        PreviewTest.press(a, "Connect headset");
        settle();
        settle();
        assertNotNull("what the phone said is shown", text(a, "did not connect (Bluetooth status 4)"));
        assertNotNull("and the button offers another try", PreviewTest.findButton(decor(a), "Try again"));
        shot(a, "calls-headset-failed");
    }

    @Test
    public void headsetNoneAndPairing() throws Exception {
        PretendCalls pc = phone();
        pc.headsets.devices.clear();
        pc.headsets.chosen = "";
        pc.live.headset = "";
        MainActivity a = calls();
        tapText(a, "No headset");
        settle();
        assertNotNull(text(a, "No headset paired yet"));
        shot(a, "calls-headset-none");
        Calls.Device d = new Calls.Device();
        d.addr = "11:22:33:44:55:66";
        d.name = "JBL Tune 230NC";
        pc.found.scanning = false;
        pc.found.devices.add(d);
        PreviewTest.press(a, "Pair a headset");
        Thread.sleep(1800);
        settle();
        assertTrue(pc.told.contains("scan"));
        assertNotNull("the headset that was found", text(a, "JBL Tune 230NC") != null ? text(a, "JBL Tune 230NC") : PreviewTest.findButton(decor(a), "Pair JBL Tune 230NC"));
        shot(a, "calls-headset-found");
        PreviewTest.press(a, "Pair JBL Tune 230NC");
        settle();
        settle();
        assertTrue(pc.told.contains("pair:11:22:33:44:55:66"));
    }

    @Test
    public void headsetNotAllowed() throws Exception {
        PretendCalls pc = phone();
        pc.headsets.allowed = false;
        pc.headsets.devices.clear();
        pc.live.headset = "";
        MainActivity a = calls();
        tapText(a, "Headset: not allowed");
        settle();
        assertNotNull(text(a, "Bluetooth is not allowed yet"));
        shot(a, "calls-headset-notallowed");
    }


    @Test
    public void callingWithoutTheHeadsetAsksFirst() throws Exception {
        PretendCalls pc = phone();
        pc.headsets.devices.get(0).connected = false;
        pc.live.headset = "";
        MainActivity a = calls();
        typeNumber(a, "5551234567");
        tapDescription(a, "Call");
        settle();
        android.app.AlertDialog d = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("a question is asked", d);
        assertEquals("it names the headset", "Headset not connected", org.robolectric.Shadows.shadowOf(d).getTitle().toString());
        assertTrue("nothing is dialed before the answer: " + pc.told, pc.told.isEmpty());
        PreviewTest.dialogShot("calls-headset-ask");
        // connect and call: the phone connects the headset, then dials
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        Thread.sleep(800);
        settle();
        assertTrue("the headset was connected first: " + pc.told, pc.told.indexOf("connect:") >= 0 && pc.told.indexOf("connect:") < pc.told.indexOf("dial:5551234567"));
        assertNotNull(text(a, "End call"));
    }

    @Test
    public void callingAnywayAndNotAsking() throws Exception {
        PretendCalls pc = phone();
        pc.headsets.devices.get(0).connected = false;
        pc.live.headset = "";
        MainActivity a = calls();
        typeNumber(a, "5551234567");
        tapDescription(a, "Call");
        settle();
        android.app.AlertDialog d = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        d.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick();
        Thread.sleep(800);
        settle();
        assertTrue("Call anyway dials without connecting: " + pc.told, pc.told.contains("dial:5551234567") && !pc.told.contains("connect:"));
        // the question can be switched off on the headset screen
        pc.hangUp();
        Thread.sleep(600);
        settle();
        Cfg.setCallsHeadsetAsk(RuntimeEnvironment.getApplication(), false);
        pc.told.clear();
        typeNumber(a, "5551234567");
        tapDescription(a, "Call");
        Thread.sleep(800);
        settle();
        assertTrue("with the question off the call is placed at once: " + pc.told, pc.told.contains("dial:5551234567"));
    }

    @Test
    public void headsetNotConnectedWhenConnectingFailsDoesNotDial() throws Exception {
        PretendCalls pc = phone();
        pc.headsets.devices.get(0).connected = false;
        pc.live.headset = "";
        pc.connectSays = "Sony WH-1000XM4 did not connect.";
        MainActivity a = calls();
        typeNumber(a, "5551234567");
        tapDescription(a, "Call");
        settle();
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        Thread.sleep(800);
        settle();
        assertTrue("no call is placed when the headset could not be connected: " + pc.told, !pc.told.contains("dial:5551234567"));
    }

    // ------------------------------------------------------------ what stands in the way

    @Test
    public void switchedOffOnThePhone() throws Exception {
        PretendCalls pc = phone();
        pc.on = false;
        Cfg.setCallsWatch(RuntimeEnvironment.getApplication(), false);
        MainActivity a = calls();
        assertNotNull(text(a, "Calls are switched off on the phone"));
        assertNotNull("it says where the switch is", text(a, PhoneScreenRules.CALLS_SWITCH));
        shot(a, "calls-off");
        assertFalse("a tablet that never used calls did not start to listen", Cfg.callsWatch(RuntimeEnvironment.getApplication()));
        pc.on = true;
        PreviewTest.press(a, "Check again");
        settle();
        settle();
        assertNull("once it is on, the notice goes", text(a, "Calls are switched off on the phone"));
        assertTrue("and the tablet now listens", Cfg.callsWatch(RuntimeEnvironment.getApplication()));
    }

    @Test
    public void oldPhone() throws Exception {
        PretendCalls pc = phone();
        pc.old = true;
        MainActivity a = calls();
        assertNotNull(text(a, "too old for calls"));
        shot(a, "calls-old");
    }

    @Test
    public void phoneNotReached() throws Exception {
        PretendCalls pc = phone();
        pc.offline = true;
        MainActivity a = calls();
        assertNotNull(text(a, "The phone can't be reached"));
        shot(a, "calls-unreached");
    }

    @Test
    public void phoneMustAllowCalls() throws Exception {
        PretendCalls pc = phone();
        pc.state.answer = false;
        pc.state.note = "The phone has not allowed LinkMeter to answer or end calls yet.";
        MainActivity a = calls();
        assertNotNull(text(a, "The phone still has to allow calls"));
        shot(a, "calls-allow");
        typeNumber(a, "5551234567");
        tapDescription(a, "Call");
        settle();
        assertTrue("no call is placed while the phone is not ready: " + pc.told, pc.told.isEmpty());
        PreviewTest.press(a, "Allow it for me");
        settle();
        settle();
        assertTrue(pc.told.contains("grant"));
        assertNull("the notice goes once allowed", text(a, "The phone still has to allow calls"));
    }


    // ------------------------------------------------------------ the phone's own screen

    @Test
    @Config(sdk = 35, qualifiers = "w384dp-h832dp-port-hdpi")
    public void phoneScreenCallsCard() throws Exception {
        PhoneScreenFacts f = PreviewTest.phoneReady();
        f.callsOn = false;
        MainActivity a = PreviewTest.phoneScreen(f, 1.0f);
        assertNotNull("the card and its switch are there", text(a, PhoneScreenRules.CALLS_SWITCH));
        assertNull("off: no button", PreviewTest.findButton(decor(a), PhoneScreenRules.CALLS_BUTTON));
        shot(a, "phone-calls-off");

        f = PreviewTest.phoneReady();
        f.callsOn = true;
        f.callsMissing = "place calls, use the headset";
        a = PreviewTest.phoneScreen(f, 1.0f);
        assertNotNull("on but not allowed: the line says what is missing", text(a, "On, but not ready. LinkMeter may not place calls, use the headset yet."));
        assertNotNull("and the button to allow it", PreviewTest.findButton(decor(a), PhoneScreenRules.CALLS_BUTTON));
        shot(a, "phone-calls-missing");

        f = PreviewTest.phoneReady();
        f.callsOn = true;
        a = PreviewTest.phoneScreen(f, 1.0f);
        assertNull("on and ready: no complaint", text(a, "On, but not ready. LinkMeter may not"));
        assertNull("and no button", PreviewTest.findButton(decor(a), PhoneScreenRules.CALLS_BUTTON));
        shot(a, "phone-calls-ready");
    }

    // ------------------------------------------------------------ Texts and Calls know each other

    @Test
    public void callButtonInAConversation() throws Exception {
        PretendCalls pc = phone();
        MainActivity a = tablet();
        a.openTab(Tab.TEXTS);
        settle();
        TextsPreviewTest.tapRow(a, 1); // Mom
        settle();
        IconButton call = TextsPreviewTest.findIcon(decor(a), "Call");
        assertNotNull("a conversation with one person has a Call button", call);
        shot(a, "calls-texts-header");
        call.performClick();
        settle();
        PreviewTest.dialogShot("calls-texts-ask");
        android.app.AlertDialog d = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(d);
        CharSequence title = org.robolectric.Shadows.shadowOf(d).getTitle();
        assertTrue("the question names the person and ends with a question mark: " + title, title.toString().startsWith("Call ") && title.toString().endsWith("?"));
        assertTrue("nothing is dialed before the question is answered: " + pc.told, pc.told.isEmpty());
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        Thread.sleep(600);
        settle();
        assertTrue("after Call, the phone was asked to dial the person of the conversation: " + pc.told, pc.told.stream().anyMatch(x -> x.startsWith("dial:")));
        assertEquals("and the Calls tab shows the call", Tab.CALLS, a.shownTitle());
    }

    @Test
    public void messageButtonOpensTheConversation() throws Exception {
        phone();
        MainActivity a = calls();
        tapText(a, "Contacts");
        settle();
        TextsPreviewTest.click(a, "Message");
        settle();
        assertEquals("the Messages button goes to Texts", Tab.TEXTS, a.shownTitle());
    }
}
