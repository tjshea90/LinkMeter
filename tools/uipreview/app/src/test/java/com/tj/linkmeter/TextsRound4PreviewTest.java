package com.tj.linkmeter;

import static com.tj.linkmeter.PreviewTest.later;
import static com.tj.linkmeter.PreviewTest.settle;
import static com.tj.linkmeter.PreviewTest.shot;
import static com.tj.linkmeter.PreviewTest.tablet;
import static com.tj.linkmeter.TextsPreviewTest.check;
import static com.tj.linkmeter.TextsPreviewTest.click;
import static com.tj.linkmeter.TextsPreviewTest.decor;
import static com.tj.linkmeter.TextsPreviewTest.findEdit;
import static com.tj.linkmeter.TextsPreviewTest.findText;
import static com.tj.linkmeter.TextsPreviewTest.phone;
import static com.tj.linkmeter.TextsPreviewTest.tapRow;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * The user's report of 2026-11-12 about Texts, driven through the REAL screens against the pretend phone ({@link PretendSms}): a
 * conversation opens at its newest message and keeps it in sight when the keyboard opens; scrolling up yourself is respected (a new
 * text does not pull you down, the jump button counts it); a text sent after the app was away (the photo picker, the screen going off)
 * goes from "Sending…" to "Sent" by itself. Pictures: tools/uipreview/out/texts4-*.png.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 29, qualifiers = "w565dp-h916dp-port-170dpi")
public class TextsRound4PreviewTest {
    static final String ALEX = "5551234567";
    static final String ALEX_NUMBER = "+15551234567";

    @After
    public void tearDown() {
        TextsTab.testLink = null;
        TextsVault.testKey = null;
        TextsTab.relock();
    }

    static ChatList findChat(View v) {
        if (v instanceof ChatList && v.isShown()) {
            return (ChatList) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ChatList l = findChat(g.getChildAt(i));
                if (l != null) {
                    return l;
                }
            }
        }
        return null;
    }

    /** The last row is on screen and ends at the list's bottom edge (within 2 pixels). */
    static boolean atEnd(ChatList l) {
        int kids = l.getChildCount();
        if (kids == 0) {
            return false;
        }
        int edge = l.getHeight() - l.getListPaddingBottom();
        int bottom = l.getChildAt(kids - 1).getBottom();
        return l.getFirstVisiblePosition() + kids - 1 == l.getCount() - 1 && Math.abs(bottom - edge) <= 2;
    }

    static String where(ChatList l) {
        int kids = l.getChildCount();
        return "first " + l.getFirstVisiblePosition() + ", " + kids + " on screen of " + l.getCount() + ", last ends at "
                + (kids == 0 ? -1 : l.getChildAt(kids - 1).getBottom()) + ", edge " + (l.getHeight() - l.getListPaddingBottom());
    }

    /** The on-screen keyboard as Android does it with adjustResize: the window's content gets this much shorter (0 = closed). */
    static void keyboard(MainActivity a, int px) {
        View content = a.findViewById(android.R.id.content);
        content.setPadding(0, 0, 0, px);
        content.requestLayout();
    }

    /** A finger moves the list slowly from one height to another and lets go (no fling). */
    static void drag(View v, float fromY, float toY) {
        long t = SystemClock.uptimeMillis();
        float x = v.getWidth() / 2f;
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, fromY, 0));
        int steps = 12;
        long at = t;
        for (int i = 1; i <= steps; i++) {
            at += 120;
            float y = fromY + (toY - fromY) * i / steps;
            v.dispatchTouchEvent(MotionEvent.obtain(t, at, MotionEvent.ACTION_MOVE, x, y, 0));
        }
        at += 400; // the finger rests before it lets go: no fling
        v.dispatchTouchEvent(MotionEvent.obtain(t, at, MotionEvent.ACTION_MOVE, x, toY, 0));
        v.dispatchTouchEvent(MotionEvent.obtain(t, at + 10, MotionEvent.ACTION_UP, x, toY, 0));
    }

    static MainActivity openAlex(PretendSms ph) throws Exception {
        MainActivity a = tablet();
        a.openTab(Tab.TEXTS);
        settle();
        tapRow(a, 0); // Alex: the newest conversation
        settle();
        return a;
    }

    @Test
    public void opensAtTheNewestAndTheKeyboardKeepsIt() throws Exception {
        PretendSms ph = phone();
        ph.longTalk(ALEX, ALEX_NUMBER, 40);
        MainActivity a = openAlex(ph);
        ChatList l = findChat(decor(a));
        check(l != null, "the conversation is on screen");
        check(atEnd(l), "it opens at the newest message: " + where(l));
        check(findText(decor(a), "Let me know if 7 still works") != null, "the newest text is in sight");
        shot(a, "texts4-open-long");

        // the box gets the keyboard: the tab bar goes, then the window gets shorter
        EditText box = findEdit(decor(a), "Text message");
        box.requestFocus();
        settle();
        keyboard(a, 420);
        settle();
        check(atEnd(l), "with the keyboard open the newest message is still in sight: " + where(l));
        check(findText(decor(a), "Let me know if 7 still works") != null, "the newest text is in sight above the keyboard");
        shot(a, "texts4-keyboard");

        keyboard(a, 0);
        settle();
        check(atEnd(l), "and when the keyboard closes again: " + where(l));
    }

    @Test
    public void scrollingUpIsRespectedAndTheButtonCountsWhatCame() throws Exception {
        PretendSms ph = phone();
        ph.longTalk(ALEX, ALEX_NUMBER, 40);
        MainActivity a = openAlex(ph);
        ChatList l = findChat(decor(a));
        check(atEnd(l), "starts at the newest message: " + where(l));

        drag(l, 200, 700); // the finger pulls the messages down: older ones come into sight
        settle();
        check(!l.following(), "scrolling up stops following: " + where(l));
        check(!atEnd(l), "the end is out of sight: " + where(l));
        int firstBefore = l.getFirstVisiblePosition();
        int topBefore = l.getChildAt(0).getTop();
        check(TextsPreviewTest.findIcon(decor(a), "Jump to the newest message") != null, "the jump button shows");

        ph.arrives(ALEX, ALEX_NUMBER, "Hello? Still there?");
        settle();
        settle();
        check(l.getFirstVisiblePosition() == firstBefore && l.getChildAt(0).getTop() == topBefore,
                "a new text does not move what you are reading: " + where(l) + " (was first " + firstBefore + " top " + topBefore + ")");
        check(TextsPreviewTest.countExact(decor(a), "1") >= 1, "the jump button counts the new text");
        shot(a, "texts4-scrolled-up-new");

        click(a, "Jump to the newest message");
        settle();
        check(l.following() && atEnd(l), "the button goes back to the newest message: " + where(l));
        check(findText(decor(a), "Hello? Still there?") != null, "the new text is in sight");
        check(TextsPreviewTest.findIcon(decor(a), "Jump to the newest message") == null, "and the button goes");

        // following again: the next text keeps the end in sight by itself
        ph.arrives(ALEX, ALEX_NUMBER, "OK see you there");
        settle();
        settle();
        check(atEnd(l) && findText(decor(a), "OK see you there") != null, "a text that comes while following is in sight: " + where(l));
    }

    /** The report: a text sent after the app was away said "Sending…" for ever. */
    @Test
    public void aTextSentAfterTheAppWasAwayGoesToSent() throws Exception {
        PretendSms ph = phone();
        MainActivity a = openAlex(ph);
        // the photo picker, or the screen going off: Android pauses the app and gives it back
        PreviewTest.lastTablet.pause();
        settle();
        PreviewTest.lastTablet.resume();
        settle();

        EditText box = findEdit(decor(a), "Text message");
        box.setText("On my way");
        click(a, "Send");
        settle();
        settle();
        check(ph.outbox.size() == 1, "the phone has it in its record");
        check(findText(decor(a), "Sending…") != null, "it says Sending while the phone has not sent it yet");

        ph.outbox.advance(ph.outbox.all().get(0).id, Sms.STATE_SENT, ""); // the phone's radio says it went
        settle();
        settle();
        check(findText(decor(a), "Sending…") == null, "nothing says Sending any more");
        check(findText(decor(a), "Sent") != null, "it says Sent");
        check(TextsPreviewTest.countExact(decor(a), "On my way") == 1, "one bubble, not two");
        shot(a, "texts4-sent-after-away");
    }

    /** The number on the Texts button in the bottom bar: kept while another tab is in front, and following the list on the Texts tab. */
    @Test
    public void theTextsButtonCountsNewTexts() throws Exception {
        PretendSms ph = phone();
        MainActivity a = tablet(); // Home is in front
        settle();
        settle();
        check(a.badge(Tab.TEXTS) == 2, "two unread conversations (Alex, Mom), never opened here: " + a.badge(Tab.TEXTS));
        shot(a, "texts4-badge-home");

        ph.arrives("5551112222", "+15551112222", "Are you free on Sunday?"); // Taylor writes: a third conversation with news
        settle();
        settle();
        check(a.badge(Tab.TEXTS) == 3, "a new text while on Home: " + a.badge(Tab.TEXTS));

        a.openTab(Tab.TEXTS);
        settle();
        check(a.badge(Tab.TEXTS) == 3, "the Texts tab keeps the same number: " + a.badge(Tab.TEXTS));
        tapRow(a, 0); // Taylor's conversation is the newest
        settle();
        check(a.badge(Tab.TEXTS) == 2, "opening it reads it: " + a.badge(Tab.TEXTS));

        a.openTab(Tab.HOME);
        settle();
        ph.arrives(ALEX, ALEX_NUMBER, "Hello? Still there?");
        settle();
        settle();
        check(a.badge(Tab.TEXTS) == 2, "Alex was unread already: still one each for Alex and Mom: " + a.badge(Tab.TEXTS));

        // the app goes to the background: no watch; back in front: it counts again
        PreviewTest.lastTablet.pause();
        settle();
        ph.arrives("5552223333", "+15552223333", "Thanks again!");
        settle();
        check(a.badge(Tab.TEXTS) == 2, "nothing is asked while the app is in the background: " + a.badge(Tab.TEXTS));
        PreviewTest.lastTablet.resume();
        settle();
        settle();
        check(a.badge(Tab.TEXTS) == 3, "back in front it counts Sam's text too: " + a.badge(Tab.TEXTS));
    }

    /** Two texts written quickly one after the other both go, in order, and each says what became of it. */
    @Test
    public void twoTextsInARow() throws Exception {
        PretendSms ph = phone();
        MainActivity a = openAlex(ph);
        EditText box = findEdit(decor(a), "Text message");
        box.setText("First one");
        click(a, "Send");
        box.setText("Second one");
        click(a, "Send"); // no waiting for the first
        settle();
        settle();
        check(ph.outbox.size() == 2, "both reached the phone: " + ph.outbox.size());
        check("First one".equals(ph.outbox.all().get(0).body) && "Second one".equals(ph.outbox.all().get(1).body), "in the order they were written");
        check(TextsPreviewTest.countExact(decor(a), "First one") == 1 && TextsPreviewTest.countExact(decor(a), "Second one") == 1,
                "one bubble each");
        for (Sms.Msg m : ph.outbox.all()) {
            ph.outbox.advance(m.id, Sms.STATE_DELIVERED, "");
        }
        later(500);
        settle();
        check(findText(decor(a), "Sending…") == null, "nothing is left saying Sending");
        check(findText(decor(a), "Delivered") != null, "the newest says Delivered");
        shot(a, "texts4-two-in-a-row");
    }
}
