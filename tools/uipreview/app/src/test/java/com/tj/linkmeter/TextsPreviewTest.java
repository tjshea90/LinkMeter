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
    }

    static PretendSms phone() throws Exception {
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
}
