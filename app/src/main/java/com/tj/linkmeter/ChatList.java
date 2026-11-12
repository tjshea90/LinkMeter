package com.tj.linkmeter;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AbsListView;
import android.widget.ListView;

/**
 * The list of a conversation's bubbles, made to behave like the messaging apps. It opens at the newest message and keeps it in sight
 * while it "follows": a plain ListView keeps its FIRST row where it was when its height changes, so the keyboard opening, the tab bar
 * hiding, the reply chips or a status line appearing all pushed the newest messages below the edge. Here, after every layout, the last
 * row is put back against the bottom edge. Scrolling up yourself stops that (nothing pulls the list back while you read older
 * messages); scrolling back to the end, or {@link #toEnd}, starts it again. A short conversation sits at the bottom, next to the box.
 * The decisions are in {@link ChatFollow} (pure, tested).
 */
final class ChatList extends ListView {
    interface Listener {
        /** Following was switched on or off: by your own scrolling, or by {@link #toEnd}. */
        void following(boolean on);

        /** The first row is in sight (the time to fetch older messages). */
        void atTop();
    }

    private boolean follow = true;
    /** A finger is on the list. */
    private boolean touching;
    /** The finger has moved the rows since it came down (a scroll of yours, not a tap). */
    private boolean dragged;
    /** What the list says it is doing: still, moved by a finger, or flinging on after the finger let go. */
    private int scrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE;
    private Listener listener;

    ChatList(Context c) {
        super(c);
        setStackFromBottom(true);
        super.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int state) {
                scrollState = state;
                if (!moving()) {
                    setFollow(atEnd()); // a fling came to rest: at the end it follows again, anywhere else it does not
                }
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                if (moving()) {
                    setFollow(atEnd()); // the jump button comes as soon as you leave the end, and goes when you are back
                }
                if (first == 0 && total > 0) {
                    top();
                }
            }
        });
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Does it keep the newest message in sight? */
    boolean following() {
        return follow;
    }

    /** Back to the newest message, and keep it in sight from now on (opening, sending, the jump button). */
    void toEnd() {
        setFollow(true);
        if (getCount() > 0) {
            setSelection(getCount() - 1);
        }
    }

    private boolean moving() {
        return touching || scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE;
    }

    private void setFollow(boolean on) {
        if (on != follow) {
            follow = on;
            if (listener != null) {
                listener.following(on);
            }
        }
    }

    private void top() {
        if (listener != null && getCount() > 0 && getFirstVisiblePosition() == 0) {
            listener.atTop();
        }
    }

    private boolean atEnd() {
        int kids = getChildCount();
        if (kids == 0) {
            return true;
        }
        View last = getChildAt(kids - 1);
        return ChatFollow.atEnd(getFirstVisiblePosition() + kids - 1, getCount(), last.getBottom(), getHeight() - getListPaddingBottom(),
                (int) (24 * getResources().getDisplayMetrics().density));
    }

    /** Where the rows are: changes when, and only when, they moved. */
    private long rowsAt() {
        return getChildCount() == 0 ? Long.MIN_VALUE : getFirstVisiblePosition() * 1_000_000L + getChildAt(0).getTop();
    }

    /**
     * Every touch of the list passes here (also one that starts on a bubble). Only a finger that MOVES the rows is a scroll of yours: a
     * tap or a press on a bubble changes nothing about following.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            touching = true;
            dragged = false;
        }
        long before = rowsAt();
        boolean handled = super.dispatchTouchEvent(e);
        if (a == MotionEvent.ACTION_MOVE && rowsAt() != before) {
            dragged = true;
            setFollow(atEnd());
            if (getFirstVisiblePosition() == 0) {
                top();
            }
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            touching = false;
            if (dragged && !moving()) { // let go without a fling: decided now (a fling is decided when it comes to rest)
                setFollow(atEnd());
            }
            dragged = false;
        }
        return handled;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (follow && !moving()) {
            pin();
        }
    }

    /**
     * Puts the last row against the bottom edge (after every layout while following). It moves the rows itself, in this same layout:
     * asking for another layout from inside one is put off to the next frame by Android, and the keyboard's resize would be drawn wrong
     * for a frame or more.
     */
    private void pin() {
        int page = Math.max(1, getHeight() - getListPaddingTop() - getListPaddingBottom() - 1);
        for (int guard = 0; guard < 200; guard++) {
            int kids = getChildCount();
            int n = getCount();
            if (kids == 0 || n == 0) {
                return;
            }
            int by = ChatFollow.pinBy(getFirstVisiblePosition() + kids - 1, n, getChildAt(kids - 1).getBottom(), getHeight() - getListPaddingBottom());
            if (by == 0) {
                return;
            }
            long before = rowsAt();
            scrollListBy(by == ChatFollow.JUMP ? page : by); // the last row is not even laid out: a page at a time until it is
            if (rowsAt() == before) {
                break; // the list did not move (it always should): the slow way below
            }
        }
        if (getCount() > 0) {
            setSelection(getCount() - 1);
        }
    }
}
