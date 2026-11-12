package com.tj.linkmeter;

import java.util.List;

/**
 * No Android in here (tested in tools/test/TextsRound4Test.java). The rules of a conversation that keeps its newest message in sight, as
 * the messaging apps do: while it "follows", the last row is held against the bottom edge whatever changes the room the list has (the
 * keyboard, the tab bar, the reply chips, a status line) and whatever arrives; scrolling up yourself stops that, and coming back to the
 * end (or the jump button) starts it again. {@link ChatList} is the Android side.
 */
final class ChatFollow {
    private ChatFollow() {}

    /** {@link #pinBy}: the last row is not laid out at all, the list must jump to it. */
    static final int JUMP = -1;

    /**
     * Is the end of the conversation in sight? The last row is on screen and its bottom is no more than {@code slop} pixels below the
     * list's bottom edge (a list with no rows is at its end).
     *
     * @param lastVisible the position of the last row on screen
     * @param count       how many rows there are
     * @param lastBottom  where the last row on screen ends (pixels from the list's top)
     * @param edge        the list's bottom edge (its height minus its bottom padding)
     */
    static boolean atEnd(int lastVisible, int count, int lastBottom, int edge, int slop) {
        return count <= 0 || (lastVisible >= count - 1 && lastBottom <= edge + slop);
    }

    /**
     * What following needs after the list has been laid out: 0 = nothing (the end is where it should be, or a short conversation does
     * not fill the list), {@link #JUMP} = the last row is not laid out at all, more than 0 = move the rows up by that many pixels.
     */
    static int pinBy(int lastVisible, int count, int lastBottom, int edge) {
        if (count <= 0) {
            return 0;
        }
        if (lastVisible < count - 1) {
            return JUMP;
        }
        return Math.max(0, lastBottom - edge);
    }

    /**
     * The number on the jump button: messages from other people that came after {@code since} (the newest time that was in sight when
     * you scrolled away), counted as the screen shows them (a reaction is not a message). 0 when you never scrolled away.
     */
    static int newSince(List<ThreadRows.Row> rows, long since) {
        if (since <= 0) {
            return 0;
        }
        int n = 0;
        for (ThreadRows.Row r : rows) {
            if (r.kind == ThreadRows.MSG && !r.msg.mine && r.msg.time > since) {
                n++;
            }
        }
        return n;
    }

    /** The newest time among the rows (0 for none): what "in sight" meant when you scrolled away. */
    static long newest(List<ThreadRows.Row> rows) {
        long t = 0;
        for (ThreadRows.Row r : rows) {
            if (r.kind == ThreadRows.MSG) {
                t = Math.max(t, r.msg.time);
            }
        }
        return t;
    }

    /** The words on the jump button's badge ("" for none, "99+" above 99). */
    static String badge(int n) {
        return n <= 0 ? "" : n > 99 ? "99+" : String.valueOf(n);
    }
}
