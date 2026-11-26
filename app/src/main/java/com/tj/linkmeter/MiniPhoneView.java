package com.tj.linkmeter;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Tablet only: the floating phone's face, a small dark glass card (docs/FLOATING_PHONE.md): who and how long, the headset's battery,
 * the Bluetooth buttons for the phone and for this tablet, a green Call and a red End button, and a dial pad that opens and closes
 * (the "mini phone"). It decides nothing: what to show comes in as a {@link Model} (built by the service from the phone's picture and
 * {@link FloatRules}), what the user presses goes out through the {@link Host}. A drag on the top strip moves the whole card.
 */
final class MiniPhoneView extends LinearLayout {
    /** What the floating phone does for the user. All called on the screen's thread. */
    interface Host {
        void answer();

        void hangup();

        /** The number on the pad passed {@link CallRules#check} already; the phone checks again. */
        void dial(String number);

        /** The headset is not connected to the phone and the user asked to be warned: show the warning and wait for a second press. */
        void warnHeadset();

        /** The digits on the pad changed (a warning that was shown no longer fits). */
        void typedChanged();

        /** The headset's link to the phone: connect it when it is not, disconnect it when it is. */
        void phoneBluetooth();

        /** The headset's link to this tablet: the same. */
        void tabletBluetooth();

        /** The card was dragged by this much (pixels). */
        void dragBy(int dx, int dy);

        /** The finger was lifted after a drag. */
        void dragEnd();

        /** The pad opened or closed: the window has another size. */
        void sizeChanged(boolean expanded);

        /** The user pressed the cross (only offered when no call is on). */
        void closed();

        /** A volume button: -1 quieter, +1 louder, for the volume the row is on. Also called when the row is off, so the card can say why. */
        void volumeStep(int by);

        /** The chip of the volume row was pressed: switch between the media and the call volume. */
        void volumeSwitch();

        /** The Recents tab was opened (force: the user asked for a fresh list). The answer comes back through {@link MiniPhoneView#setRecents}. */
        void needRecents(boolean force);

        /** The Contacts tab was opened. The answer comes back through {@link MiniPhoneView#setContacts}. */
        void needContacts(boolean force);

        /** The Text button (number "") or a line's message button: open the small texting card, at that person's conversation when there is a number. */
        void openTexts(String number);

        /** The contacts search box wants Android's keyboard (true), or is done with it (false): the window must be able to take the keyboard meanwhile. */
        void wantKeyboard(boolean on);
    }

    /** Everything the card shows. */
    static final class Model {
        /** The call's state: "", {@link Calls#RINGING}, {@link Calls#DIALING}, {@link Calls#ACTIVE} or {@link Calls#HELD}. */
        String call = "";
        String title = "Phone";
        String subtitle = "";
        /** The watch could not hear the phone (so the call buttons are off). */
        boolean offline;
        boolean headsetOnPhone;
        String headsetName = "";
        /** The battery icon shows: the headset is connected to the phone (or to this tablet) and a level, or its absence, is worth saying. */
        boolean batteryShown;
        int battery = Calls.BATTERY_UNKNOWN;
        boolean phoneBusy;
        /** The headset is connected to THIS tablet / the tablet's Bluetooth is able to carry it (paired and on). */
        boolean tabletOn;
        boolean tabletAble;
        boolean tabletBusy;
        /** The "ask before calling without the headset" setting, and whether the user already saw the warning. */
        boolean askFirst;
        boolean armed;
        /** A line to read (a refusal, a warning, what a switch did): "" for none. */
        String message = "";
        boolean messageIsBad;
        /** The volume row: shown while the headset is connected to the phone and the phone knows about volume. */
        boolean volumeShown;
        /** {@link Calls#STREAM_MEDIA} or {@link Calls#STREAM_CALL}: the volume the row moves now. */
        String volumeStream = Calls.STREAM_MEDIA;
        VolumeRules.Row volume = new VolumeRules.Row();
        /** A step is on its way to the phone. */
        boolean volumeBusy;
    }

    static final int WIDTH_DP = 264;
    /** The three lists of the open card. */
    static final int TAB_KEYPAD = 0;
    static final int TAB_RECENTS = 1;
    static final int TAB_CONTACTS = 2;
    private static final String[] TAB_NAMES = {"Keypad", "Recents", "Contacts"};
    /** The height of the Recents and Contacts lists (the keypad is about as tall). */
    private static final int LIST_DP = 232;
    private static final int ACCENT_ON = 0xFF2F6BFF;

    private final Host host;
    private final GradientDrawable bg;
    private final View dot;
    private final TextView title;
    private final TextView sub;
    private final BatteryIconView battery;
    private final IconButton chevron;
    private final IconButton close;
    private final LinearLayout header;
    private final LinearLayout padBox;
    /** What opens with the arrow: the strip of three tabs and the list of the tab that is on. */
    private final LinearLayout openBox;
    private final TextView[] tabViews = new TextView[3];
    private int tab = TAB_KEYPAD;
    private LinearLayout recentsBox;
    private ListView recentsList;
    private TextView recentsStatus;
    private final List<MiniLists.Recent> recents = new ArrayList<>();
    private boolean recentsReady;
    private String recentsProblem = "";
    private LinearLayout contactsBox;
    private ListView contactsList;
    private TextView contactsStatus;
    private EditText find;
    private List<Sms.Person> people = new ArrayList<>();
    private List<Object> contactRows = new ArrayList<>();
    private boolean contactsReady;
    private String contactsProblem = "";
    private BaseAdapter recentsAdapter;
    private BaseAdapter contactsAdapter;
    private final FloatKeyboard kb;
    private boolean rowCallsOff;
    private final TextView typedView;
    private final TextView message;
    private IconButton btPhone;
    private IconButton btTablet;
    private IconButton green;
    private IconButton red;
    private TextView btPhoneLabel;
    private TextView btTabletLabel;
    private TextView greenLabel;
    private TextView redLabel;
    private IconButton textBtn;
    private TextView textLabel;
    private LinearLayout volumeRow;
    private TextView volumeChip;
    private VolumeView volumeView;
    private boolean expanded;
    private String typed = "";
    private Model model = new Model();
    private ValueAnimator pulse;
    private ValueAnimator halo;
    private int touchSlop;

    /** The look of a press on every button of the card (PressFeedback): this window is not the app's screen, so it lights its own. */
    private final PressFeedback press = new PressFeedback();

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        press.onTouch(this, e);
        return super.dispatchTouchEvent(e);
    }

    MiniPhoneView(Context c, Host h) {
        super(c);
        host = h;
        kb = new FloatKeyboard(this, h::wantKeyboard);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        setOrientation(VERTICAL);
        setPadding(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 8));
        bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xF51C2330, 0xF50E1218});
        bg.setCornerRadius(Ui.dp(c, 22));
        bg.setStroke(Ui.dp(c, 1), 0x44FFFFFF);
        setBackground(bg);
        setElevation(Ui.dp(c, 8));

        // ---- the top strip: a grip, who, how long, the battery, the pad's arrow, the cross
        header = new LinearLayout(c);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(Ui.dp(c, 40));
        header.setOnTouchListener(this::dragTouch);
        header.setContentDescription("Floating phone. Drag to move, tap to open the dial pad, recents and contacts.");
        dot = new View(c);
        dot.setBackground(Ui.round(0xFF8A8F98, Ui.dp(c, 6)));
        header.addView(dot, new LayoutParams(Ui.dp(c, 12), Ui.dp(c, 12)));
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(VERTICAL);
        title = Ui.text(c, "Phone", 14.5f, Ui.FG, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        sub = Ui.text(c, "", 12, Ui.DIM, false);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(title);
        words.addView(sub);
        LayoutParams wp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wp.leftMargin = Ui.dp(c, 9);
        header.addView(words, wp);
        battery = new BatteryIconView(c, 0.9f);
        battery.setVisibility(GONE);
        LayoutParams bp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.leftMargin = Ui.dp(c, 4);
        header.addView(battery, bp);
        chevron = icon(c, Icons.Kind.CHEVRON_DOWN, 0, 0.62f, "Open the dial pad, recents and contacts", v -> setExpanded(!expanded));
        LayoutParams cp = new LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28));
        cp.leftMargin = Ui.dp(c, 4);
        header.addView(chevron, cp);
        close = icon(c, Icons.Kind.CLOSE, 0, 0.55f, "Close the floating phone", v -> host.closed());
        LayoutParams xp = new LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24));
        xp.leftMargin = Ui.dp(c, 2);
        header.addView(close, xp);
        addView(header, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- what opens with the arrow: three tabs (Keypad, Recents, Contacts) and the list of the one that is on
        openBox = new LinearLayout(c);
        openBox.setOrientation(VERTICAL);
        openBox.setVisibility(GONE);
        openBox.addView(buildTabStrip(c), new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- the dial pad: the number, then twelve keys
        padBox = new LinearLayout(c);
        padBox.setOrientation(VERTICAL);
        LinearLayout numRow = new LinearLayout(c);
        numRow.setOrientation(HORIZONTAL);
        numRow.setGravity(Gravity.CENTER_VERTICAL);
        typedView = Ui.text(c, "", 22, Ui.FG, false);
        typedView.setMaxLines(1); // not setSingleLine: that makes a very wide layout and a centred text is then drawn off to the side
        typedView.setEllipsize(TextUtils.TruncateAt.START);
        typedView.setGravity(Gravity.CENTER);
        numRow.addView(typedView, new LayoutParams(0, Ui.dp(c, 40), 1f));
        IconButton back = icon(c, Icons.Kind.BACKSPACE, 0, 0.6f, "Delete a digit", v -> {
            typed = CallRules.backspace(typed);
            typedChanged();
        });
        back.setOnLongClickListener(v -> {
            typed = "";
            typedChanged();
            return true;
        });
        numRow.addView(back, new LayoutParams(Ui.dp(c, 34), Ui.dp(c, 34)));
        padBox.addView(numRow);
        for (char[] row : CallRules.KEYS) {
            LinearLayout r = new LinearLayout(c);
            r.setOrientation(HORIZONTAL);
            for (final char k : row) {
                r.addView(key(c, k), keyParams(c));
            }
            padBox.addView(r, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        openBox.addView(padBox, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        buildRecents(c);
        buildContacts(c);
        openBox.addView(recentsBox, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, LIST_DP)));
        openBox.addView(contactsBox, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, LIST_DP)));
        addView(openBox, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        message = Ui.text(c, "", 11.5f, Ui.AMBER, false);
        message.setGravity(Gravity.CENTER);
        message.setVisibility(GONE);
        LayoutParams mp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = Ui.dp(c, 4);
        addView(message, mp);

        buildActions(c);
        buildVolume(c);
        paintTyped();
        paintTabs();
        bind(model);
    }

    // ------------------------------------------------------------------ building

    private static IconButton icon(Context c, Icons.Kind kind, int bgColor, float scale, String label, View.OnClickListener l) {
        IconButton b = new IconButton(c, kind, Ui.FG, bgColor, label, l);
        b.setScale(scale);
        return b;
    }

    private static LayoutParams keyParams(Context c) {
        LayoutParams p = new LayoutParams(0, Ui.dp(c, 44), 1f);
        p.setMargins(Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3));
        return p;
    }

    private View key(Context c, final char k) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(VERTICAL);
        v.setGravity(Gravity.CENTER);
        v.setBackground(CallsViews.ripple(0xFF262C36, Ui.dp(c, 14)));
        v.setContentDescription(k == '*' ? "star" : k == '#' ? "pound" : String.valueOf(k));
        TextView digit = Ui.text(c, String.valueOf(k), 20, Ui.FG, false);
        digit.setGravity(Gravity.CENTER);
        v.addView(digit);
        String letters = k == '0' ? "+" : CallRules.letters(k);
        if (!letters.isEmpty()) {
            TextView s = Ui.text(c, letters, 8, Ui.DIM, true);
            s.setGravity(Gravity.CENTER);
            v.addView(s);
        }
        v.setClickable(true);
        v.setOnClickListener(x -> {
            typed = CallRules.press(typed, k);
            typedChanged();
        });
        if (k == '0') {
            v.setOnLongClickListener(x -> {
                typed = CallRules.afterLongZero(typed); // a long press on 0 types the plus, as on the Calls tab
                typedChanged();
                return true;
            });
        }
        return v;
    }

    private LinearLayout column(Context c, IconButton b, TextView label) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(b, new LayoutParams(Ui.dp(c, 42), Ui.dp(c, 42)));
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        col.addView(label, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return col;
    }

    private void buildActions(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        btPhone = icon(c, Icons.Kind.BLUETOOTH, Ui.CARD_HI, 0.5f, "Headset Bluetooth link to the phone", v -> host.phoneBluetooth());
        btPhoneLabel = Ui.text(c, "Phone", 10, Ui.DIM, true);
        green = icon(c, Icons.Kind.HANDSET, CallsViews.CALL_GREEN, 0.52f, "Call", v -> greenPressed());
        greenLabel = Ui.text(c, "Call", 10, Ui.DIM, true);
        red = icon(c, Icons.Kind.HANGUP, CallsViews.END_RED, 0.52f, "End call", v -> host.hangup());
        redLabel = Ui.text(c, "End", 10, Ui.DIM, true);
        btTablet = icon(c, Icons.Kind.BLUETOOTH, Ui.CARD_HI, 0.5f, "Headset Bluetooth link to this tablet", v -> host.tabletBluetooth());
        btTabletLabel = Ui.text(c, "Tablet", 10, Ui.DIM, true);
        textBtn = icon(c, Icons.Kind.CHAT, Ui.CARD_HI, 0.5f, "Text messages", v -> host.openTexts(""));
        textLabel = Ui.text(c, "Text", 10, Ui.DIM, true);
        LayoutParams cell = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(column(c, btPhone, btPhoneLabel), cell);
        row.addView(column(c, green, greenLabel), new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(column(c, red, redLabel), new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(column(c, textBtn, textLabel), new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(column(c, btTablet, btTabletLabel), new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LayoutParams rp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = Ui.dp(c, 6);
        addView(row, rp);
    }

    /** The headset's volume: a chip that says which volume (and switches it), then quieter, the bars and louder. Shown while the headset is on the phone. */
    private void buildVolume(Context c) {
        volumeRow = new LinearLayout(c);
        volumeRow.setOrientation(HORIZONTAL);
        volumeRow.setGravity(Gravity.CENTER_VERTICAL);
        volumeChip = Ui.text(c, "Media", 11.5f, Ui.FG, true);
        volumeChip.setGravity(Gravity.CENTER);
        volumeChip.setPadding(Ui.dp(c, 8), Ui.dp(c, 5), Ui.dp(c, 8), Ui.dp(c, 5));
        volumeChip.setBackground(CallsViews.ripple(Ui.CARD_HI, Ui.dp(c, 14)));
        volumeChip.setMinWidth(Ui.dp(c, 54));
        volumeChip.setClickable(true);
        volumeChip.setContentDescription("Volume of the headset. Press to switch between the media and the call volume.");
        volumeChip.setOnClickListener(v -> host.volumeSwitch());
        volumeRow.addView(volumeChip, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        volumeView = new VolumeView(c, true, "Headset", by -> host.volumeStep(by));
        LayoutParams vp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        vp.leftMargin = Ui.dp(c, 6);
        volumeRow.addView(volumeView, vp);
        volumeRow.setVisibility(GONE);
        LayoutParams rp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = Ui.dp(c, 6);
        addView(volumeRow, rp);
    }

    // ------------------------------------------------------------------ the pad

    boolean expanded() {
        return expanded;
    }

    /** Opens the card at the keypad with this number typed (the texting card's call button): the green button then calls it, with the same guard and warning. */
    void prefill(String number) {
        setExpanded(true);
        showTab(TAB_KEYPAD);
        typed = FloatRules.clean(number);
        typedChanged();
    }

    String typed() {
        return typed;
    }

    void clearTyped() {
        typed = "";
        paintTyped();
    }

    void setExpanded(boolean open) {
        if (expanded == open) {
            return;
        }
        expanded = open;
        openBox.setVisibility(open ? VISIBLE : GONE);
        chevron.set(open ? Icons.Kind.UP : Icons.Kind.CHEVRON_DOWN, Ui.FG, 0);
        chevron.setContentDescription(open ? "Close the dial pad, recents and contacts" : "Open the dial pad, recents and contacts");
        if (!open) {
            typed = "";
            paintTyped();
            kb.close();
        } else {
            showTab(tab); // the list of the tab that was on is asked for again when it is out of date
        }
        bind(model);
        host.sizeChanged(open);
    }

    private void typedChanged() {
        model.message = ""; // what was said about the old number no longer fits
        model.armed = false;
        paintTyped();
        host.typedChanged();
    }

    private void paintTyped() {
        String shown = CallRules.pretty(typed);
        typedView.setText(shown.isEmpty() ? "Enter a number" : shown);
        typedView.setTextColor(shown.isEmpty() ? Ui.DIM : Ui.FG);
        typedView.setTextSize(shown.isEmpty() ? 14 : shown.length() > 14 ? 17 : 22);
        bind(model);
    }

    private void greenPressed() {
        FloatRules.Green g = FloatRules.green(model.call, expanded, typed, model.headsetOnPhone, model.askFirst, model.armed);
        switch (g) {
            case ANSWER:
                host.answer();
                break;
            case OPEN_PAD:
                setExpanded(true);
                break;
            case DIAL:
            case ASK_HEADSET: {
                CallRules.Dial d = CallRules.check(typed);
                if (!d.ok()) {
                    model.message = d.why(); // an emergency number, a code, letters: said here, nothing is sent
                    model.messageIsBad = true;
                    bind(model);
                    return;
                }
                if (g == FloatRules.Green.ASK_HEADSET) {
                    host.warnHeadset();
                } else {
                    host.dial(d.number);
                }
                break;
            }
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ the tabs: Keypad, Recents, Contacts

    private View buildTabStrip(Context c) {
        LinearLayout strip = new LinearLayout(c);
        strip.setOrientation(HORIZONTAL);
        strip.setPadding(0, Ui.dp(c, 2), 0, Ui.dp(c, 4));
        for (int i = 0; i < 3; i++) {
            final int which = i;
            TextView t = Ui.text(c, TAB_NAMES[i], 12.5f, Ui.DIM, true);
            t.setGravity(Gravity.CENTER);
            t.setMaxLines(1);
            t.setPadding(Ui.dp(c, 4), Ui.dp(c, 7), Ui.dp(c, 4), Ui.dp(c, 7));
            t.setClickable(true);
            t.setContentDescription(TAB_NAMES[i]);
            t.setOnClickListener(v -> showTab(which));
            tabViews[i] = t;
            LayoutParams p = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            p.setMargins(Ui.dp(c, 2), 0, Ui.dp(c, 2), 0);
            strip.addView(t, p);
        }
        return strip;
    }

    private void paintTabs() {
        for (int i = 0; i < 3; i++) {
            boolean on = i == tab;
            tabViews[i].setTextColor(on ? Ui.FG : Ui.DIM);
            tabViews[i].setBackground(on ? Ui.round(Ui.CARD_HI, Ui.dp(getContext(), 14)) : null);
        }
        padBox.setVisibility(tab == TAB_KEYPAD ? VISIBLE : GONE);
        recentsBox.setVisibility(tab == TAB_RECENTS ? VISIBLE : GONE);
        contactsBox.setVisibility(tab == TAB_CONTACTS ? VISIBLE : GONE);
    }

    /** The tab that is on: {@link #TAB_KEYPAD}, {@link #TAB_RECENTS} or {@link #TAB_CONTACTS}. */
    int tab() {
        return tab;
    }

    /** Switches to a tab and asks for its list when it needs one. */
    void showTab(int which) {
        if (which < 0 || which > 2) {
            which = TAB_KEYPAD;
        }
        if (tab != which) {
            kb.close();
        }
        tab = which;
        paintTabs();
        if (expanded) {
            if (which == TAB_RECENTS) {
                host.needRecents(false);
            } else if (which == TAB_CONTACTS) {
                host.needContacts(false);
            }
        }
    }

    /** A line of Recents or Contacts was pressed: its number goes onto the keypad, where the green button calls it. */
    private void useNumber(String number) {
        String n = FloatRules.clean(number);
        if (n.isEmpty()) {
            return;
        }
        typed = n;
        showTab(TAB_KEYPAD);
        typedChanged();
    }

    /** The green button of a line: its number goes onto the keypad and the green button is pressed for it (the same guard and headset warning). */
    private void callNumber(String number) {
        if (rowCallsOff) {
            return;
        }
        useNumber(number);
        greenPressed();
    }

    private void bindButtons(MiniRow r, final String who, final String number) {
        r.actions.removeAllViews();
        if (number == null || number.isEmpty()) {
            return;
        }
        r.actions.addView(MiniRow.button(getContext(), Icons.Kind.CHAT, Ui.CARD_HI, "Text " + who, v -> host.openTexts(number)));
        IconButton call = MiniRow.button(getContext(), Icons.Kind.HANDSET, CallsViews.CALL_GREEN, "Call " + who, v -> callNumber(number));
        call.setAlpha(rowCallsOff ? 0.35f : 1f);
        call.setEnabled(!rowCallsOff);
        r.actions.addView(call);
    }

    private void buildRecents(Context c) {
        recentsBox = new LinearLayout(c);
        recentsBox.setOrientation(VERTICAL);
        recentsStatus = Ui.text(c, "Loading calls…", 12.5f, Ui.DIM, false);
        recentsStatus.setGravity(Gravity.CENTER);
        recentsStatus.setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 4));
        recentsBox.addView(recentsStatus);
        recentsList = new ListView(c);
        recentsList.setDivider(null);
        recentsList.setSelector(android.R.color.transparent);
        recentsAdapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return recents.size();
            }

            @Override
            public Object getItem(int i) {
                return recents.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                MiniRow r = convert instanceof MiniRow ? (MiniRow) convert : new MiniRow(getContext());
                MiniLists.Recent e = recents.get(i);
                r.title.setText(e.title());
                r.title.setTextColor(e.missed ? Ui.RED : Ui.FG);
                r.detail.setText(e.detail);
                TextsViews.bindAvatar(r.avatar, e.label, e.number.isEmpty() ? e.label : ContactBook.matchKey(e.number), false);
                bindButtons(r, e.label, e.number);
                return r;
            }
        };
        recentsList.setAdapter(recentsAdapter);
        recentsList.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < recents.size()) {
                useNumber(recents.get(pos).number);
            }
        });
        recentsBox.addView(recentsList, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        recentsBox.setVisibility(GONE);
    }

    /** The phone's call list arrived ({@code log}), or could not be got ({@code problem}, a sentence; the list shown stays). */
    void setRecents(List<Calls.Entry> log, String problem) {
        recentsProblem = problem == null ? "" : problem;
        if (log != null) {
            recents.clear();
            recents.addAll(MiniLists.recents(log, System.currentTimeMillis(),
                    TextTime.Env.device(android.text.format.DateFormat.is24HourFormat(getContext()))));
            recentsReady = true;
        }
        recentsStatus.setText(!recentsProblem.isEmpty() ? recentsProblem : !recentsReady ? "Loading calls…" : recents.isEmpty() ? "No calls yet." : "");
        recentsStatus.setVisibility(recentsStatus.getText().length() == 0 ? GONE : VISIBLE);
        recentsAdapter.notifyDataSetChanged();
    }

    private void buildContacts(Context c) {
        contactsBox = new LinearLayout(c);
        contactsBox.setOrientation(VERTICAL);
        find = new EditText(c);
        find.setHint("Search name or number");
        find.setHintTextColor(Ui.DIM);
        find.setTextColor(Ui.FG);
        find.setTextSize(14);
        find.setSingleLine(true);
        find.setInputType(InputType.TYPE_CLASS_TEXT); // a normal text field: the keyboard's suggestions and glide typing work
        find.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        find.setBackground(TextsViews.shape(Ui.CARD_HI, Ui.dp(c, 12)));
        find.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8));
        find.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int n) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int n) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                renderContacts();
            }
        });
        kb.bind(find); // this window may take the keyboard only while somebody types in it
        LayoutParams fp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.topMargin = Ui.dp(c, 2);
        contactsBox.addView(find, fp);
        contactsStatus = Ui.text(c, "Loading contacts…", 12, Ui.DIM, false);
        contactsStatus.setPadding(Ui.dp(c, 6), Ui.dp(c, 4), Ui.dp(c, 6), Ui.dp(c, 2));
        contactsBox.addView(contactsStatus);
        contactsList = new ListView(c);
        contactsList.setDivider(null);
        contactsList.setSelector(android.R.color.transparent);
        contactsList.setFastScrollEnabled(true);
        contactsAdapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return contactRows.size();
            }

            @Override
            public Object getItem(int i) {
                return contactRows.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public int getViewTypeCount() {
                return 2;
            }

            @Override
            public int getItemViewType(int i) {
                return contactRows.get(i) instanceof String ? 0 : 1;
            }

            @Override
            public boolean isEnabled(int i) {
                return !(contactRows.get(i) instanceof String);
            }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                Object o = contactRows.get(i);
                if (o instanceof String) {
                    if (convert instanceof TextView) {
                        ((TextView) convert).setText((String) o);
                        return convert;
                    }
                    return CallsViews.sectionHeader(getContext(), (String) o);
                }
                CallRules.Match m = (CallRules.Match) o;
                MiniRow r = convert instanceof MiniRow ? (MiniRow) convert : new MiniRow(getContext());
                String name = m.name.isEmpty() ? CallRules.pretty(m.number) : m.name;
                r.title.setText(name);
                r.title.setTextColor(Ui.FG);
                r.detail.setText(CallRules.pretty(m.number));
                TextsViews.bindAvatar(r.avatar, name, ContactBook.matchKey(m.number), false);
                bindButtons(r, name, m.number);
                return r;
            }
        };
        contactsList.setAdapter(contactsAdapter);
        contactsList.setOnItemClickListener((p, v, pos, id) -> {
            Object o = pos >= 0 && pos < contactRows.size() ? contactRows.get(pos) : null;
            if (o instanceof CallRules.Match) {
                useNumber(((CallRules.Match) o).number);
            }
        });
        contactsBox.addView(contactsList, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        contactsBox.setVisibility(GONE);
    }

    /** The phone's contacts arrived, or could not be got ({@code problem}). */
    void setContacts(List<Sms.Person> got, String problem) {
        contactsProblem = problem == null ? "" : problem;
        if (got != null) {
            people = new ArrayList<>(got);
            contactsReady = true;
        }
        renderContacts();
    }

    private void renderContacts() {
        contactRows = MiniLists.contacts(people, find.getText().toString());
        int n = MiniLists.people(contactRows);
        contactsStatus.setText(!contactsProblem.isEmpty() ? contactsProblem : !contactsReady ? "Loading contacts…"
                : n == 0 ? (people.isEmpty() ? "The phone has no contacts to show." : "Nobody found.") : n + (n == 1 ? " contact" : " contacts"));
        contactsAdapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------------ the keyboard (only the contacts search uses it)

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        kb.outside(e); // the user touched something else: the keyboard goes and the window stops taking it
        return super.onTouchEvent(e);
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
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
                if (moved) {
                    host.dragEnd();
                } else {
                    setExpanded(!expanded);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (moved) {
                    host.dragEnd();
                }
                return true;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------ painting what the model says

    /** Shows what the model says. Cheap: called at every change and once a second during a call. */
    void bind(Model m) {
        model = m;
        boolean ringing = Calls.RINGING.equals(m.call);
        boolean inCall = !m.call.isEmpty();
        title.setText(m.title);
        sub.setText(m.subtitle);
        sub.setTextColor(ringing ? Ui.AMBER : inCall ? Ui.GREEN : m.offline ? Ui.AMBER : Ui.DIM);
        dot.setBackground(Ui.round(ringing ? Ui.AMBER : inCall ? Ui.GREEN : m.offline ? Ui.RED : 0xFF8A8F98, Ui.dp(getContext(), 6)));
        battery.setVisibility(m.batteryShown ? VISIBLE : GONE);
        if (m.batteryShown) {
            battery.setLevel(m.battery, true);
            battery.setContentDescription(BatteryRules.words(m.battery, m.headsetName));
        }
        close.setVisibility(inCall ? GONE : VISIBLE);

        // the two Bluetooth buttons: lit blue while the headset is connected there
        btPhone.set(Icons.Kind.BLUETOOTH, m.headsetOnPhone ? 0xFFFFFFFF : Ui.DIM, m.headsetOnPhone ? ACCENT_ON : Ui.CARD_HI);
        btPhone.setAlpha(m.phoneBusy ? 0.5f : 1f);
        btPhone.setEnabled(!m.phoneBusy && !m.offline);
        // five buttons share the row: the label is one short word, the state is its colour
        btPhoneLabel.setText(m.phoneBusy ? "…" : "Phone");
        btPhoneLabel.setTextColor(m.headsetOnPhone ? 0xFF8FB0FF : Ui.DIM);
        btTablet.set(Icons.Kind.BLUETOOTH, m.tabletOn ? 0xFFFFFFFF : Ui.DIM, m.tabletOn ? ACCENT_ON : Ui.CARD_HI);
        btTablet.setAlpha(m.tabletBusy ? 0.5f : m.tabletAble ? 1f : 0.55f);
        btTablet.setEnabled(!m.tabletBusy);
        btTabletLabel.setText(m.tabletBusy ? "…" : "Tablet");
        btTabletLabel.setTextColor(m.tabletOn ? 0xFF8FB0FF : Ui.DIM);

        // the green and the red button
        FloatRules.Green g = FloatRules.green(m.call, expanded, typed, m.headsetOnPhone, m.askFirst, m.armed);
        boolean greenOn = !m.offline && g != FloatRules.Green.NONE;
        green.setAlpha(greenOn ? 1f : 0.35f);
        green.setEnabled(greenOn);
        green.setContentDescription(FloatRules.greenLabel(g));
        greenLabel.setText(g == FloatRules.Green.ANSWER ? "Answer" : g == FloatRules.Green.ASK_HEADSET ? "Anyway" : g == FloatRules.Green.OPEN_PAD ? "Dial" : "Call");
        greenLabel.setTextColor(greenOn ? 0xFF7BE0A4 : Ui.DIM);
        boolean redOn = !m.offline && FloatRules.redOn(m.call);
        red.setAlpha(redOn ? 1f : 0.35f);
        red.setEnabled(redOn);
        red.setContentDescription(FloatRules.redLabel(m.call));
        redLabel.setText(ringing ? "Decline" : "End");
        redLabel.setTextColor(redOn ? 0xFFFF8A8A : Ui.DIM);

        textBtn.set(Icons.Kind.CHAT, Ui.FG, Ui.CARD_HI);
        textLabel.setTextColor(Ui.DIM);
        boolean rowsOff = !m.call.isEmpty() || m.offline;
        if (rowsOff != rowCallsOff) { // the call buttons of the lists are dimmed while a call is on or the phone cannot be heard
            rowCallsOff = rowsOff;
            if (recentsAdapter != null) {
                recentsAdapter.notifyDataSetChanged();
                contactsAdapter.notifyDataSetChanged();
            }
        }

        volumeRow.setVisibility(m.volumeShown ? VISIBLE : GONE);
        if (m.volumeShown) {
            volumeChip.setText(VolumeRules.shortName(m.volumeStream));
            volumeChip.setTextColor(m.volume.enabled ? Ui.FG : Ui.DIM);
            volumeView.bind(m.volume, m.volumeBusy);
        }

        message.setText(m.message);
        message.setTextColor(m.messageIsBad ? Ui.RED : Ui.AMBER);
        message.setVisibility(m.message.isEmpty() ? GONE : VISIBLE);
        animate(ringing);
    }

    /** The card breathes while the phone rings: an amber rim, and the green button swells. */
    private void animate(boolean ringing) {
        if (!ringing || !isAttachedToWindow()) {
            if (pulse != null) {
                pulse.cancel();
                pulse = null;
                bg.setStroke(Ui.dp(getContext(), 1), 0x44FFFFFF);
            }
            if (halo != null) {
                halo.cancel();
                halo = null;
                green.setScaleX(1f);
                green.setScaleY(1f);
            }
            return;
        }
        if (pulse == null) {
            pulse = ValueAnimator.ofObject(new ArgbEvaluator(), 0x44FFFFFF, 0xFFFFB300);
            pulse.setDuration(650);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.addUpdateListener(a -> bg.setStroke(Ui.dp(getContext(), 2), (Integer) a.getAnimatedValue()));
            pulse.start();
        }
        if (halo == null) {
            halo = ValueAnimator.ofFloat(1f, 1.16f);
            halo.setDuration(500);
            halo.setRepeatMode(ValueAnimator.REVERSE);
            halo.setRepeatCount(ValueAnimator.INFINITE);
            halo.addUpdateListener(a -> {
                float s = (Float) a.getAnimatedValue();
                green.setScaleX(s);
                green.setScaleY(s);
            });
            halo.start();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animate(Calls.RINGING.equals(model.call));
    }

    @Override
    protected void onDetachedFromWindow() {
        press.unlightNow();
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        if (halo != null) {
            halo.cancel();
            halo = null;
        }
        super.onDetachedFromWindow();
    }
}
