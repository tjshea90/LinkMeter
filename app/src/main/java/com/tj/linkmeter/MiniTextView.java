package com.tj.linkmeter;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/**
 * Tablet only: the small texting card of the floating phone (docs/FLOATING_PHONE.md), a second dark glass card like the phone's, drawn over
 * every app. Three screens: the list of conversations (with a button for a new message), one conversation (the newest messages, a box and
 * Send), and "New message" (who: a number, or a name from the contacts). It draws what {@link MiniTexts} holds and sends what the user
 * does back to it; nothing here reaches the phone. A drag on the top strip moves the whole card, and it takes Android's keyboard only
 * while somebody types in a box ({@link FloatKeyboard}).
 */
final class MiniTextView extends LinearLayout {
    /** What the card needs of its window. */
    interface Host extends FloatKeyboard.Window {
        void dragBy(int dx, int dy);

        void dragEnd();

        /** The user pressed the cross. */
        void closed();

        /** The call button in a conversation: dial this number through the phone's keypad guard. */
        void callNumber(String number);

        /** The screen has another height (another screen of the card is on). */
        void sizeChanged();
    }

    static final int WIDTH_DP = 288;
    /** The room for the lists and the messages. */
    private static final int BODY_DP = 292;

    private final Host host;
    private final MiniTexts texts;
    private final FloatKeyboard kb;
    private final GradientDrawable bg;
    private final TextView title;
    private final TextView sub;
    private final IconButton back;
    private final IconButton plus;
    private final IconButton call;
    private final IconButton close;

    private final LinearLayout listBox;
    private final TextView listStatus;
    private final ListView list;
    private final BaseAdapter listAdapter;

    private final LinearLayout threadBox;
    private final ScrollView scroll;
    private final LinearLayout bubbles;
    private final TextView threadStatus;
    private final EditText input;
    private final IconButton sendBtn;

    private final LinearLayout newBox;
    private final EditText whoBox;
    private final TextView newStatus;
    private final ListView picksList;
    private final BaseAdapter picksAdapter;

    private int touchSlop;
    private int shownCount = -1;
    private String shownKey = "";
    private MiniTexts.Mode shownMode;

    /** The look of a press on every button of the card (PressFeedback): this window is not the app's screen, so it lights its own. */
    private final PressFeedback press = new PressFeedback();

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        press.onTouch(this, e);
        return super.dispatchTouchEvent(e);
    }

    MiniTextView(Context c, Host h, MiniTexts t) {
        super(c);
        host = h;
        texts = t;
        kb = new FloatKeyboard(this, h);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        setOrientation(VERTICAL);
        setPadding(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 8));
        bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xF51C2330, 0xF50E1218});
        bg.setCornerRadius(Ui.dp(c, 22));
        bg.setStroke(Ui.dp(c, 1), 0x44FFFFFF);
        setBackground(bg);
        setElevation(Ui.dp(c, 8));

        // ---- the top strip: back, who, new message / call, the cross; a drag here moves the card
        LinearLayout header = new LinearLayout(c);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(Ui.dp(c, 40));
        header.setOnTouchListener(this::dragTouch);
        header.setContentDescription("Texts. Drag to move.");
        back = icon(c, Icons.Kind.LEFT, "Back to the conversations", v -> {
            kb.close();
            texts.showList();
        });
        header.addView(back, new LayoutParams(Ui.dp(c, 30), Ui.dp(c, 30)));
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(VERTICAL);
        title = Ui.text(c, "Texts", 14.5f, Ui.FG, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        sub = Ui.text(c, "", 12, Ui.DIM, false);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(title);
        words.addView(sub);
        LayoutParams wp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wp.leftMargin = Ui.dp(c, 6);
        header.addView(words, wp);
        plus = icon(c, Icons.Kind.PLUS, "New message", v -> {
            kb.close();
            texts.showNew();
        });
        header.addView(plus, new LayoutParams(Ui.dp(c, 30), Ui.dp(c, 30)));
        call = icon(c, Icons.Kind.HANDSET, "Call", v -> {
            if (!texts.to.isEmpty()) {
                host.callNumber(texts.to.get(0));
            }
        });
        header.addView(call, new LayoutParams(Ui.dp(c, 30), Ui.dp(c, 30)));
        close = icon(c, Icons.Kind.CLOSE, "Close the texts", v -> {
            kb.close();
            host.closed();
        });
        LayoutParams xp = new LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28));
        xp.leftMargin = Ui.dp(c, 2);
        header.addView(close, xp);
        addView(header, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- the list of conversations
        listBox = new LinearLayout(c);
        listBox.setOrientation(VERTICAL);
        listStatus = Ui.text(c, "Loading texts…", 12.5f, Ui.DIM, false);
        listStatus.setGravity(Gravity.CENTER);
        listStatus.setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 4));
        listBox.addView(listStatus);
        list = new ListView(c);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        listAdapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return texts.threads.size();
            }

            @Override
            public Object getItem(int i) {
                return texts.threads.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                MiniRow r = convert instanceof MiniRow ? (MiniRow) convert : new MiniRow(getContext());
                Sms.Thread th = texts.threads.get(i);
                String who = MiniTextRules.title(th);
                r.title.setText(who);
                r.title.setTextColor(th.unread ? Ui.FG : 0xFFD6DBE4);
                r.detail.setText(MiniTextRules.preview(th));
                r.detail.setTextColor(th.unread ? Ui.FG : Ui.DIM);
                r.stamp.setText(TextTime.stamp(th.time, System.currentTimeMillis(), env()));
                r.stamp.setVisibility(th.time > 0 ? VISIBLE : GONE);
                r.dot.setVisibility(th.unread ? VISIBLE : GONE);
                TextsViews.bindAvatar(r.avatar, who, th.key.isEmpty() ? who : th.key, false);
                return r;
            }
        };
        list.setAdapter(listAdapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < texts.threads.size()) {
                texts.openThread(texts.threads.get(pos));
            }
        });
        listBox.addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        addView(listBox, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, BODY_DP)));

        // ---- one conversation: the messages, then the box and Send
        threadBox = new LinearLayout(c);
        threadBox.setOrientation(VERTICAL);
        scroll = new ScrollView(c);
        scroll.setFillViewport(false);
        bubbles = new LinearLayout(c);
        bubbles.setOrientation(VERTICAL);
        bubbles.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
        scroll.addView(bubbles, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        threadBox.addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        threadStatus = Ui.text(c, "", 11.5f, Ui.AMBER, false);
        threadStatus.setGravity(Gravity.CENTER);
        threadStatus.setVisibility(GONE);
        threadBox.addView(threadStatus);
        LinearLayout compose = new LinearLayout(c);
        compose.setOrientation(HORIZONTAL);
        compose.setGravity(Gravity.BOTTOM);
        input = new EditText(c);
        input.setHint("Message");
        input.setHintTextColor(Ui.DIM);
        input.setTextColor(Ui.FG);
        input.setTextSize(14);
        input.setMaxLines(3);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setBackground(TextsViews.shape(Ui.CARD_HI, Ui.dp(c, 18)));
        input.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8));
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int n) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int n) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                paintSend();
            }
        });
        kb.bind(input);
        compose.addView(input, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sendBtn = icon(c, Icons.Kind.SEND, "Send", v -> sendPressed());
        LayoutParams sp = new LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40));
        sp.leftMargin = Ui.dp(c, 6);
        compose.addView(sendBtn, sp);
        LayoutParams cp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = Ui.dp(c, 4);
        threadBox.addView(compose, cp);
        threadBox.setVisibility(GONE);
        addView(threadBox, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, BODY_DP)));

        // ---- a new message: who
        newBox = new LinearLayout(c);
        newBox.setOrientation(VERTICAL);
        whoBox = new EditText(c);
        whoBox.setHint("To: a name or a number");
        whoBox.setHintTextColor(Ui.DIM);
        whoBox.setTextColor(Ui.FG);
        whoBox.setTextSize(14);
        whoBox.setSingleLine(true);
        whoBox.setInputType(InputType.TYPE_CLASS_TEXT);
        whoBox.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        whoBox.setBackground(TextsViews.shape(Ui.CARD_HI, Ui.dp(c, 12)));
        whoBox.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8));
        whoBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int n) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int n) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!s.toString().equals(texts.toTyped)) {
                    texts.setToTyped(s.toString());
                }
            }
        });
        kb.bind(whoBox);
        newBox.addView(whoBox, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        newStatus = Ui.text(c, "", 12, Ui.DIM, false);
        newStatus.setPadding(Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 2));
        newBox.addView(newStatus);
        picksList = new ListView(c);
        picksList.setDivider(null);
        picksList.setSelector(android.R.color.transparent);
        picksAdapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return texts.picks.size();
            }

            @Override
            public Object getItem(int i) {
                return texts.picks.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                MiniRow r = convert instanceof MiniRow ? (MiniRow) convert : new MiniRow(getContext());
                MiniTextRules.Pick p = texts.picks.get(i);
                r.title.setText(p.shown());
                r.detail.setText(PhoneNumbers.pretty(p.number));
                TextsViews.bindAvatar(r.avatar, p.shown(), ContactBook.matchKey(p.number), false);
                return r;
            }
        };
        picksList.setAdapter(picksAdapter);
        picksList.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < texts.picks.size()) {
                kb.close();
                texts.pick(texts.picks.get(pos));
            }
        });
        newBox.addView(picksList, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        newBox.setVisibility(GONE);
        addView(newBox, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, BODY_DP)));

        bind();
    }

    private static IconButton icon(Context c, Icons.Kind kind, String label, View.OnClickListener l) {
        IconButton b = new IconButton(c, kind, Ui.FG, 0, label, l);
        b.setScale(0.62f);
        return b;
    }

    private TextTime.Env env() {
        return TextTime.Env.device(android.text.format.DateFormat.is24HourFormat(getContext()));
    }

    // ------------------------------------------------------------------ drawing what the state says

    /** Draws the state of {@link MiniTexts}. Cheap: called at every change. */
    void bind() {
        MiniTexts.Mode mode = texts.mode;
        boolean modeChanged = mode != shownMode;
        shownMode = mode;
        listBox.setVisibility(mode == MiniTexts.Mode.LIST ? VISIBLE : GONE);
        threadBox.setVisibility(mode == MiniTexts.Mode.THREAD ? VISIBLE : GONE);
        newBox.setVisibility(mode == MiniTexts.Mode.NEW ? VISIBLE : GONE);
        back.setVisibility(mode == MiniTexts.Mode.LIST ? GONE : VISIBLE);
        plus.setVisibility(mode == MiniTexts.Mode.LIST ? VISIBLE : GONE);
        call.setVisibility(mode == MiniTexts.Mode.THREAD && texts.to.size() == 1 ? VISIBLE : GONE);

        if (mode == MiniTexts.Mode.LIST) {
            title.setText("Texts");
            int n = texts.unread();
            sub.setText(n > 0 ? n + (n == 1 ? " conversation with news" : " conversations with news") : "");
            sub.setVisibility(n > 0 ? VISIBLE : GONE);
            sub.setTextColor(Ui.ACCENT);
            listStatus.setText(!texts.threadsProblem.isEmpty() ? texts.threadsProblem : !texts.threadsReady ? "Loading texts…"
                    : texts.threads.isEmpty() ? "No conversations yet. Press + to write one." : "");
            listStatus.setVisibility(listStatus.getText().length() == 0 ? GONE : VISIBLE);
            listAdapter.notifyDataSetChanged();
        } else if (mode == MiniTexts.Mode.NEW) {
            title.setText("New message");
            sub.setVisibility(GONE);
            if (modeChanged) {
                whoBox.setText(texts.toTyped); // empty for a new message; a half-typed number when the card was opened with one
            }
            String typed = texts.toTyped.trim();
            newStatus.setText(typed.isEmpty() ? "Type a name or a number." : texts.picks.isEmpty() ? "Nobody found. A number needs at least three digits." : "");
            newStatus.setVisibility(newStatus.getText().length() == 0 ? GONE : VISIBLE);
            picksAdapter.notifyDataSetChanged();
        } else {
            title.setText(MiniTextRules.threadTitle(texts.name, texts.to));
            String number = texts.to.size() == 1 ? PhoneNumbers.pretty(texts.to.get(0)) : "";
            boolean namedAndNumber = !texts.name.isEmpty() && !number.isEmpty() && !number.equals(texts.name);
            sub.setText(namedAndNumber ? number : "");
            sub.setTextColor(Ui.DIM);
            sub.setVisibility(namedAndNumber ? VISIBLE : GONE);
            drawMessages(modeChanged);
        }
        paintSend();
        if (modeChanged) {
            host.sizeChanged();
        }
    }

    /** The messages of the open conversation as bubbles; the card follows the newest unless the reader is up the page. */
    private void drawMessages(boolean modeChanged) {
        List<Sms.Msg> msgs = texts.messages();
        boolean nearEnd = scroll.getChildCount() == 0 || scroll.getScrollY() + scroll.getHeight() >= bubbles.getHeight() - Ui.dp(getContext(), 60);
        bubbles.removeAllViews();
        long now = System.currentTimeMillis();
        TextTime.Env env = env();
        long last = 0;
        Context c = getContext();
        for (int i = 0; i < msgs.size(); i++) {
            Sms.Msg m = msgs.get(i);
            if (last == 0 || m.time - last >= 15 * 60_000L) {
                TextView when = Ui.text(c, TextTime.stamp(m.time, now, env), 11, Ui.DIM, false);
                when.setGravity(Gravity.CENTER);
                when.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 2));
                bubbles.addView(when, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            last = m.time;
            TextView b = Ui.text(c, bodyOf(m), 13.5f, m.mine ? Ui.ON_ACCENT : Ui.FG, false);
            b.setBackground(TextsViews.shape(m.mine ? (m.state == Sms.STATE_FAILED ? 0xFF8E3A3A : Ui.ACCENT) : Ui.CARD_HI, Ui.dp(c, 16)));
            b.setPadding(Ui.dp(c, 11), Ui.dp(c, 6), Ui.dp(c, 11), Ui.dp(c, 6));
            b.setMaxWidth(Ui.dp(c, 210));
            LayoutParams p = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.gravity = m.mine ? Gravity.END : Gravity.START;
            p.topMargin = Ui.dp(c, 3);
            bubbles.addView(b, p);
        }
        Sms.Msg lastMsg = msgs.isEmpty() ? null : msgs.get(msgs.size() - 1);
        String line = MiniTextRules.sendLine(lastMsg);
        threadStatus.setText(!texts.threadProblem.isEmpty() ? texts.threadProblem : line);
        threadStatus.setTextColor(!texts.threadProblem.isEmpty() || (lastMsg != null && lastMsg.state == Sms.STATE_FAILED) ? Ui.RED : Ui.DIM);
        threadStatus.setVisibility(threadStatus.getText().length() == 0 ? GONE : VISIBLE);
        if (msgs.isEmpty() && texts.threadProblem.isEmpty()) {
            TextView none = Ui.text(c, texts.threadReady ? "No messages yet. Write the first one below." : "Loading messages…", 12.5f, Ui.DIM, false);
            none.setGravity(Gravity.CENTER);
            none.setPadding(Ui.dp(c, 8), Ui.dp(c, 24), Ui.dp(c, 8), 0);
            bubbles.addView(none, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        final boolean follow = nearEnd || modeChanged || !shownKey.equals(texts.key) || msgs.size() != shownCount;
        shownCount = msgs.size();
        shownKey = texts.key;
        if (follow) {
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    /** What a bubble says: the words, or what kind of thing came instead (a picture, a file). */
    static String bodyOf(Sms.Msg m) {
        String body = m.body == null ? "" : m.body.trim();
        if (!m.parts.isEmpty()) {
            StringBuilder sb = new StringBuilder(body);
            for (Sms.Part p : m.parts) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(p.image() ? "[Picture]" : "[" + (p.name.isEmpty() ? "File" : p.name) + "]");
            }
            return sb.toString();
        }
        return body.isEmpty() ? (m.mms ? "[Message]" : "") : body;
    }

    private void paintSend() {
        boolean can = MiniTextRules.canSend(input.getText().toString(), texts.to);
        sendBtn.set(Icons.Kind.SEND, can ? Ui.ON_ACCENT : Ui.DIM, can ? Ui.ACCENT : Ui.CARD_HI);
        sendBtn.setEnabled(can);
    }

    private void sendPressed() {
        String text = input.getText().toString();
        if (texts.send(text)) {
            input.setText("");
        }
    }

    /** Empties the box and lets the keyboard go (the card is closed, or another conversation opens). */
    void reset() {
        kb.close();
        input.setText("");
        whoBox.setText("");
    }

    /** For the pictures of tools/uipreview and the tests: types into the message box as the keyboard would. */
    void typeForTest(String text) {
        input.setText(text);
    }

    void sendForTest() {
        sendPressed();
    }

    // ------------------------------------------------------------------ the keyboard

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        kb.outside(e);
        return super.onTouchEvent(e);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        return kb.key(e) || super.dispatchKeyEvent(e);
    }

    // ------------------------------------------------------------------ dragging

    private float lastRawX;
    private float lastRawY;
    private float downRawX;
    private float downRawY;
    private boolean moved;

    private boolean dragTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = lastRawX = e.getRawX();
                downRawY = lastRawY = e.getRawY();
                moved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!moved && Math.hypot(e.getRawX() - downRawX, e.getRawY() - downRawY) > touchSlop) {
                    moved = true;
                }
                if (moved) {
                    host.dragBy(Math.round(e.getRawX() - lastRawX), Math.round(e.getRawY() - lastRawY));
                    lastRawX = e.getRawX();
                    lastRawY = e.getRawY();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (moved) {
                    host.dragEnd();
                }
                return true;
            default:
                return false;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        press.unlightNow();
        super.onDetachedFromWindow();
    }
}
