package com.tj.linkmeter;

import static com.tj.linkmeter.PreviewTest.later;
import static com.tj.linkmeter.PreviewTest.pretendPhone;
import static com.tj.linkmeter.PreviewTest.settle;
import static com.tj.linkmeter.PreviewTest.shot;
import static com.tj.linkmeter.PreviewTest.tablet;

import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;

/**
 * Draws the Texts tab in its states (tools/uipreview/out/texts-*.png) with demo conversations behind the real phone-side code
 * ({@link PretendSms}), so the screens can be looked at without a device.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 29, qualifiers = "w565dp-h916dp-port-170dpi")
public class TextsPreviewTest {
    @After
    public void tearDown() {
        TextsTab.testLink = null;
        TextsVault.testKey = null;
        TextsTab.relock();
    }

    static PretendSms phone() throws Exception {
        TextsVault.testKey = new javax.crypto.spec.SecretKeySpec(new byte[32], "AES"); // there is no Android Keystore in this test
        TextsTab.relock();
        pretendPhone("wifi", true, true);
        PretendSms ph = new PretendSms(PretendSms.gradientJpeg());
        TextsTab.testLink = ph.link();
        return ph;
    }

    static View decor(MainActivity a) {
        return a.getWindow().getDecorView();
    }

    static ListView findList(View v) {
        if (v instanceof ListView && v.isShown()) {
            return (ListView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ListView l = findList(g.getChildAt(i));
                if (l != null) {
                    return l;
                }
            }
        }
        return null;
    }

    static IconButton findIcon(View v, String label) {
        if (v instanceof IconButton && v.isShown() && label.contentEquals(String.valueOf(v.getContentDescription()))) {
            return (IconButton) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                IconButton b = findIcon(g.getChildAt(i), label);
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }

    static EditText findEdit(View v, String hint) {
        if (v instanceof EditText && v.isShown() && hint.contentEquals(String.valueOf(((EditText) v).getHint()))) {
            return (EditText) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                EditText b = findEdit(g.getChildAt(i), hint);
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }

    static TextView findText(View v, String text) {
        if (v instanceof TextView && v.isShown() && String.valueOf(((TextView) v).getText()).contains(text)) {
            return (TextView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView b = findText(g.getChildAt(i), text);
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }

    static void click(MainActivity a, String icon) {
        IconButton b = findIcon(decor(a), icon);
        if (b == null) {
            throw new AssertionError("no button \"" + icon + "\" on the screen");
        }
        b.performClick();
    }

    static void tapRow(MainActivity a, int position) {
        ListView lv = findList(decor(a));
        lv.getOnItemClickListener().onItemClick(lv, null, position, position);
    }

    static void holdRow(MainActivity a, int position) {
        ListView lv = findList(decor(a));
        lv.getOnItemLongClickListener().onItemLongClick(lv, null, position, position);
    }

    static void dialog(String name) throws Exception {
        settle();
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        if (d == null || !d.isShowing()) {
            throw new AssertionError("no question is open for " + name);
        }
        PreviewTest.save(d.getWindow().getDecorView(), name);
    }

    static void closeDialog() {
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        if (d != null && d.isShowing()) {
            d.dismiss();
        }
    }

    // ------------------------------------------------------------ the list

    @Test
    public void list() throws Exception {
        phone();
        MainActivity a = tablet();
        a.openTab(Tab.TEXTS);
        settle();
        shot(a, "texts-list");

        holdRow(a, 1); // Mom
        tapRow(a, 3);  // the weekend crew
        settle();
        shot(a, "texts-list-select");
        click(a, "Pin");
        settle();
        shot(a, "texts-list-pinned");

        click(a, "Search");
        settle();
        findEdit(decor(a), "Search names, numbers and texts").setText("pizza");
        later(600);
        shot(a, "texts-list-search");
        click(a, "Close search");
        settle();

        click(a, "More");
        dialog("texts-menu");
        closeDialog();
    }

    static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    @Test
    public void deleteAsk() throws Exception {
        PretendSms ph = phone();
        MainActivity a = tablet();
        a.openTab(Tab.TEXTS);
        settle();
        check(findText(decor(a), "Sam Patel") != null, "Sam is in the list");
        holdRow(a, 2);
        settle();
        click(a, "Delete");
        dialog("texts-delete-ask");
        // Delete: the conversation goes from the list at once; the phone is asked and refuses, so the strip says so and offers Undo
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        later(600);
        check(findText(decor(a), "Sam Patel") == null, "Sam is gone from the list");
        TextView strip = findText(decor(a), "Removed from this tablet");
        check(strip != null, "the strip says it was removed here only");
        shot(a, "texts-deleted-undo");
        check(ph.db.size() > 0 && ph.db.get(0) != null, "the pretend phone kept the texts (it refuses to delete)");
        // Undo brings it back
        PreviewTest.press(a, "Undo");
        settle();
        check(findText(decor(a), "Sam Patel") != null, "Undo brought Sam back");
    }

    @Test
    public void emptyAndOff() throws Exception {
        PretendSms ph = phone();
        ph.db.clear();
        MainActivity a = tablet();
        a.openTab(Tab.TEXTS);
        settle();
        shot(a, "texts-empty");
    }

    @Test
    public void switchedOff() throws Exception {
        PretendSms ph = phone();
        ph.on = false;
        MainActivity a = tablet();
        a.openTab(Tab.TEXTS);
        settle();
        shot(a, "texts-off");
    }

    @Test
    public void savedCopyWhenTheLinkIsDown() throws Exception {
        PretendSms ph = phone();
        MainActivity first = tablet();
        first.openTab(Tab.TEXTS);
        settle(); // the list was fetched and saved
        first.openTab(Tab.HOME);
        ph.offline = true;
        MainActivity a = tablet();
        a.openTab(Tab.TEXTS);
        settle();
        shot(a, "texts-offline");
    }

    // ------------------------------------------------------------ a conversation

    @Test
    public void conversation() throws Exception {
        PretendSms ph = phone();
        MainActivity a = tablet();
        a.openTab(Tab.TEXTS);
        settle();
        tapRow(a, 0); // Alex
        settle();
        shot(a, "texts-thread");

        click(a, "More");
        dialog("texts-thread-menu");
        closeDialog();

        // write and send
        EditText box = findEdit(decor(a), "Text message");
        box.setText("Yes, 7 works. Let's get pizza after!");
        settle();
        shot(a, "texts-thread-typing");
        click(a, "Send");
        later(300);
        shot(a, "texts-thread-sent");
        check(findText(decor(a), "Yes, 7 works") != null, "the sent text is in the conversation");
        check(ph.outbox.size() == 1, "the phone has it in its record: " + ph.outbox.size());
        check(findEdit(decor(a), "Text message").getText().length() == 0, "the box is empty again");
    }

    @Test
    public void groupWithPicture() throws Exception {
        phone();
        MainActivity a = tablet();
        a.openTab(Tab.TEXTS);
        settle();
        tapRow(a, 3); // the weekend crew
        later(800);
        shot(a, "texts-thread-group");
    }

    @Test
    public void selectMessages() throws Exception {
        phone();
        MainActivity a = tablet();
        a.openTab(Tab.TEXTS);
        settle();
        tapRow(a, 0);
        settle();
        ListView lv = findList(decor(a));
        // the last message's bubble: press and hold it
        TextView t = findText(decor(a), "Let me know if 7");
        t.performLongClick();
        dialog("texts-message-menu");
        closeDialog();
    }

    @Test
    public void newMessage() throws Exception {
        phone();
        MainActivity a = tablet();
        a.openTab(Tab.TEXTS);
        settle();
        click(a, "New message");
        settle();
        shot(a, "texts-new");
        findEdit(decor(a), "Type a name or a number").setText("al");
        later(600);
        shot(a, "texts-new-search");
    }

    // ------------------------------------------------------------ a wide screen: the list stays, the conversation opens beside it

    @Test
    @Config(sdk = 29, qualifiers = "w916dp-h565dp-land-170dpi")
    public void wide() throws Exception {
        phone();
        MainActivity a = tablet();
        a.openTab(Tab.TEXTS);
        settle();
        shot(a, "texts-wide-list");
        tapRow(a, 0);
        settle();
        shot(a, "texts-wide-thread");
    }

    // ------------------------------------------------------------ the lock, the screenshot block and the settings

    static void secureScreen(MainActivity a, boolean secure) {
        android.app.KeyguardManager km = (android.app.KeyguardManager) a.getSystemService(android.content.Context.KEYGUARD_SERVICE);
        org.robolectric.Shadows.shadowOf(km).setIsDeviceSecure(secure);
    }

    static void lockOn(boolean on) {
        new TextsPrefs(org.robolectric.RuntimeEnvironment.getApplication()).setLock(on);
    }

    /** Answers the tablet's screen-lock question that the app put up (passed or cancelled). */
    static void answerLockQuestion(MainActivity a, boolean passed) throws Exception {
        org.robolectric.shadows.ShadowActivity sa = org.robolectric.Shadows.shadowOf(a);
        org.robolectric.shadows.ShadowActivity.IntentForResult q = sa.getNextStartedActivityForResult();
        check(q != null, "the screen-lock question was put up");
        sa.receiveResult(q.intent, passed ? android.app.Activity.RESULT_OK : android.app.Activity.RESULT_CANCELED, null);
        settle();
    }

    static boolean flagSecure(MainActivity a) {
        return (a.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0;
    }

    @Test
    public void lockedUntilTheScreenLockIsPassed() throws Exception {
        phone();
        MainActivity a = tablet();
        secureScreen(a, true);
        lockOn(true);
        a.openTab(Tab.TEXTS);
        settle();
        check(findText(decor(a), "Texts are locked") != null, "the lock screen is up");
        check(findText(decor(a), "Alex") == null && findText(decor(a), "Mom") == null, "no conversation is on screen while locked");
        shot(a, "texts-locked");

        // a Cancel leaves it locked, and the Unlock button asks again
        answerLockQuestion(a, false);
        check(findText(decor(a), "Texts are locked") != null, "still locked after a Cancel");
        PreviewTest.press(a, "Unlock");
        answerLockQuestion(a, true);
        later(600);
        check(findText(decor(a), "Texts are locked") == null, "the lock screen is gone once the screen lock was passed");
        check(findText(decor(a), "Alex") != null, "the conversations are there");

        // another tab and back at once: no new question
        a.openTab(Tab.HOME);
        a.openTab(Tab.TEXTS);
        settle();
        check(findText(decor(a), "Texts are locked") == null, "back within the grace time: not asked again");
        check(org.robolectric.Shadows.shadowOf(a).getNextStartedActivityForResult() == null, "no question was put up");

        // away for longer: locked again, and nothing of the texts stays on screen
        a.openTab(Tab.HOME);
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(TextsLock.GRACE_MS + 5_000));
        a.openTab(Tab.TEXTS);
        settle();
        check(findText(decor(a), "Texts are locked") != null, "locked again after a long time away");
        check(findText(decor(a), "Alex") == null, "no conversation is on screen");
        answerLockQuestion(a, true);
        later(600);
        check(findText(decor(a), "Alex") != null, "unlocked again");
    }

    @Test
    public void lockEngagesWhenTheAppComesBackAfterALongTime() throws Exception {
        phone();
        MainActivity a = tablet();
        secureScreen(a, true);
        lockOn(true);
        a.openTab(Tab.TEXTS);
        settle();
        answerLockQuestion(a, true);
        later(600);
        // a conversation and a question are open, then the app goes away and comes back much later
        tapRow(a, 0);
        settle();
        check(findText(decor(a), "Let me know if 7") != null, "a conversation is open");
        click(a, "More");
        settle();
        check(ShadowAlertDialog.getLatestAlertDialog() != null && ShadowAlertDialog.getLatestAlertDialog().isShowing(), "a menu is open");
        a.onPause();
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(TextsLock.GRACE_MS + 5_000));
        a.onResume();
        settle();
        check(findText(decor(a), "Texts are locked") != null, "locked when the app came back");
        check(findText(decor(a), "Let me know if 7") == null, "the conversation is gone from the screen");
        check(!ShadowAlertDialog.getLatestAlertDialog().isShowing(), "the open menu was closed");
    }

    @Test
    public void noLockWithoutAScreenLock() throws Exception {
        phone();
        MainActivity a = tablet();
        secureScreen(a, false);
        lockOn(true);
        a.openTab(Tab.TEXTS);
        settle();
        check(findText(decor(a), "Texts are locked") == null, "a tablet without a screen lock has nothing to ask for");
        check(findText(decor(a), "Alex") != null, "the conversations are there");
    }

    @Test
    public void keepsTextsOutOfScreenshots() throws Exception {
        phone();
        MainActivity a = tablet();
        a.openTab(Tab.TEXTS);
        settle();
        check(!flagSecure(a), "screenshots are allowed by default");
        new TextsPrefs(org.robolectric.RuntimeEnvironment.getApplication()).setSecureWindow(true);
        a.openTab(Tab.HOME);
        a.openTab(Tab.TEXTS);
        settle();
        check(flagSecure(a), "screenshots of the Texts tab are blocked");
        a.onPause(); // the recent apps picture is taken after this: the flag must still be set
        check(flagSecure(a), "still blocked while the app is going away");
        a.onResume();
        a.openTab(Tab.HOME);
        check(!flagSecure(a), "the other tabs can be captured again");
    }

    @Test
    public void settingsDialog() throws Exception {
        phone();
        MainActivity a = tablet();
        secureScreen(a, true);
        a.openTab(Tab.TEXTS);
        settle();
        click(a, "More");
        settle();
        // the menu's "Texts settings" entry
        AlertDialog menu = ShadowAlertDialog.getLatestAlertDialog();
        int n = menu.getListView().getCount();
        int at = -1;
        for (int i = 0; i < n; i++) {
            if (String.valueOf(menu.getListView().getAdapter().getItem(i)).contains("settings")) {
                at = i;
            }
        }
        check(at >= 0, "the menu has the settings entry");
        menu.getListView().performItemClick(null, at, at);
        dialog("texts-settings");
        closeDialog();
    }

    // ------------------------------------------------------------ one-time codes

    @Test
    public void copyCode() throws Exception {
        PretendSms ph = phone();
        ph.codeArrives();
        MainActivity a = tablet();
        a.openTab(Tab.TEXTS);
        settle();
        TextView pill = findText(decor(a), "Copy code");
        check(pill != null, "the list offers Copy code for the fresh unread code");
        shot(a, "texts-code-list");
        pill.performClick();
        android.content.ClipboardManager cm = (android.content.ClipboardManager) a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        check("583920".contentEquals(cm.getPrimaryClip().getItemAt(0).getText()), "the clipboard holds just the code");
        // the conversation: the code text has the button too (and the old one of six days ago does not: it is too old)
        tapRow(a, 0);
        settle();
        check(findText(decor(a), "G-583920") != null, "the conversation shows the text");
        shot(a, "texts-code-thread");
        check(findText(decor(a), "Copy code") != null, "the conversation offers Copy code under the new text");
    }

    @Test
    public void noCopyCodeForOldOrReadTexts() throws Exception {
        phone(); // the demo has a code text that is six days old and was read
        MainActivity a = tablet();
        a.openTab(Tab.TEXTS);
        settle();
        check(findText(decor(a), "Copy code") == null, "an old read code offers nothing in the list");
        TextView row = findText(decor(a), "Your verification code is 482913");
        check(row != null, "the old code text is in the list");
    }
}
