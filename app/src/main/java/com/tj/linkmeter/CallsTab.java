package com.tj.linkmeter;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The tablet's Calls tab (docs/PHONE_CALLS.md): the phone's own phone app, from the tablet. A large dial pad, the recent calls, the
 * contacts (the same ones the Texts tab uses; a name given in either place shows in both), the headset, and the call itself while one
 * rings or is on. Everything is a request to the phone, which places, answers and ends the call; the voice never passes through the
 * tablet, because the headset is connected to the PHONE.
 */
final class CallsTab extends Tab {
    private static final int KEYPAD = 0;
    private static final int RECENTS = 1;
    private static final int CONTACTS = 2;
    private static final String[] SEGMENT_NAMES = {"Keypad", "Recents", "Contacts"};

    private final ExecutorService net = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "linkmeter-calls-tab");
        t.setDaemon(true);
        return t;
    });
    private final CallWatch.Listener watchListener = w -> onWatch();

    private FrameLayout root;
    private TextView chip;
    private BatteryIconView battery;
    private LinearLayout noticeBox;
    private LinearLayout segRow;
    private final TextView[] segs = new TextView[3];
    private FrameLayout body;
    private HeadsetPanel panel;
    private InCallView inCall;
    private int segment = KEYPAD;
    private int session;
    private boolean lastCallOn;

    private Calls.State state;
    private String stateProblem = "";
    private Calls.Headsets headsets;
    private String headsetProblem = "";

    // keypad
    private String typed = "";
    private TextView typedView;
    private TextView matchView;
    private boolean dialing;

    // recents
    private final List<Calls.Entry> recents = new ArrayList<>();
    private final List<CallRules.Group> groups = new ArrayList<>();
    private boolean recentsLoaded;
    private boolean recentsMore;
    private String recentsProblem = "";
    private BaseAdapter recentsAdapter;
    private ListView recentsList;

    // contacts
    private final List<Sms.Person> contacts = new ArrayList<>();
    private boolean contactsLoaded;
    private String contactsProblem = "";
    private String contactsQuery = "";
    private final List<Object> contactRows = new ArrayList<>();
    private BaseAdapter contactsAdapter;
    private ListView contactsList;
    private TextView contactsStatus;

    CallsTab(MainActivity a) {
        super(a);
    }

    @Override
    String title() {
        return Tab.CALLS;
    }

    @Override
    Icons.Kind icon() {
        return Icons.Kind.HANDSET;
    }

    // ------------------------------------------------------------------ for the pieces of this tab

    ExecutorService net() {
        return net;
    }

    RemoteCalls remote() throws IOException {
        return act.callWatch().remote();
    }

    Calls.Headsets headsets() {
        return headsets;
    }

    void setHeadsets(Calls.Headsets h) {
        headsets = h;
        paintChip();
    }

    String headsetProblem() {
        return headsetProblem;
    }

    void setHeadsetProblem(String s) {
        headsetProblem = s == null ? "" : s;
        paintChip();
    }

    void closeHeadset() {
        panel = null;
        showBody();
        paintChip();
    }

    private void openHeadset() {
        panel = new HeadsetPanel(this);
        showBody();
    }

    // ------------------------------------------------------------------ the screen

    @Override
    View build() {
        session++;
        root = new FrameLayout(act);
        root.setBackgroundColor(Ui.BG);
        LinearLayout main = new LinearLayout(act);
        main.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(act, 16), Ui.dp(act, 12), Ui.dp(act, 12), Ui.dp(act, 4));
        head.addView(Ui.title(act, "Calls"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        battery = new BatteryIconView(act, 1f); // the headset's battery, while it is connected to the phone
        battery.setVisibility(View.GONE);
        battery.setClickable(true);
        battery.setOnClickListener(v -> openHeadset());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.rightMargin = Ui.dp(act, 8);
        head.addView(battery, bp);
        chip = Ui.chip(act, "Headset", Ui.DIM);
        chip.setClickable(true);
        chip.setOnClickListener(v -> openHeadset());
        head.addView(chip);
        main.addView(head);

        noticeBox = new LinearLayout(act);
        noticeBox.setOrientation(LinearLayout.VERTICAL);
        noticeBox.setPadding(Ui.dp(act, 12), 0, Ui.dp(act, 12), 0);
        main.addView(noticeBox);

        segRow = new LinearLayout(act);
        segRow.setOrientation(LinearLayout.HORIZONTAL);
        segRow.setPadding(Ui.dp(act, 12), Ui.dp(act, 6), Ui.dp(act, 12), Ui.dp(act, 6));
        for (int i = 0; i < segs.length; i++) {
            final int idx = i;
            TextView s = Ui.text(act, SEGMENT_NAMES[i], 15, Ui.FG, true);
            s.setGravity(Gravity.CENTER);
            s.setClickable(true);
            s.setOnClickListener(v -> {
                segment = idx;
                showBody();
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Ui.dp(act, 44), 1f);
            p.setMargins(Ui.dp(act, 3), 0, Ui.dp(act, 3), 0);
            segRow.addView(s, p);
            segs[i] = s;
        }
        main.addView(segRow);

        body = new FrameLayout(act);
        main.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(main, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        lastCallOn = callOn();
        showBody();
        paintChip();
        paintNotice();
        return root;
    }

    private boolean callOn() {
        CallWatch w = act.callWatch();
        return w != null && w.status() == CallWatch.OK && w.lead() != null;
    }

    private void paintSegments() {
        for (int i = 0; i < segs.length; i++) {
            boolean on = i == segment;
            segs[i].setBackground(Ui.round(on ? Ui.ACCENT : Ui.CARD_HI, Ui.dp(act, 12)));
            segs[i].setTextColor(on ? Ui.ON_ACCENT : Ui.FG);
        }
    }

    private void showBody() {
        if (body == null) {
            return;
        }
        body.removeAllViews();
        inCall = null;
        recentsList = null;
        contactsList = null;
        typedView = null;
        matchView = null;
        if (panel != null) {
            segRow.setVisibility(View.GONE);
            body.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }
        if (callOn()) {
            segRow.setVisibility(View.GONE);
            inCall = new InCallView(act, act.callWatch());
            body.addView(inCall, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }
        segRow.setVisibility(View.VISIBLE);
        paintSegments();
        View v;
        switch (segment) {
            case RECENTS:
                v = buildRecents();
                break;
            case CONTACTS:
                v = buildContacts();
                break;
            default:
                v = buildKeypad();
                break;
        }
        body.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    void onShow() {
        final int my = session;
        CallWatch w = act.callWatch();
        w.addListener(watchListener);
        if (Cfg.callsWatch(act)) {
            w.wake();
        }
        loadState(my);
        paintChip();
        paintNotice();
    }

    @Override
    void onHide() {
        session++;
        CallWatch w = act.callWatch();
        if (w != null) {
            w.removeListener(watchListener);
        }
    }

    private boolean ready() {
        return state != null && state.canCall();
    }

    /** Asks the phone whether calls are allowed there; when they are, the tablet starts to listen for them (once, for good). */
    private void loadState(final int my) {
        net.execute(() -> {
            Calls.State st = null;
            String bad = "";
            try {
                st = remote().state();
            } catch (IOException e) {
                bad = TextsTab.explain(e);
            }
            final Calls.State fs = st;
            final String fb = bad;
            act.ui.post(() -> {
                if (my != session) {
                    return;
                }
                state = fs;
                stateProblem = fb;
                if (fs != null && fs.version > 0 && fs.on) {
                    if (!Cfg.callsWatch(act)) {
                        Cfg.setCallsWatch(act, true);
                    }
                    act.callWatch().wake();
                }
                paintNotice();
                if (ready()) {
                    loadHeadsets(my);
                    loadContacts(my, false);
                    if (segment == RECENTS) {
                        loadRecents(my, false);
                    }
                }
            });
        });
    }

    private void loadHeadsets(final int my) {
        net.execute(() -> {
            Calls.Headsets h = null;
            try {
                h = remote().headsets();
            } catch (IOException ignored) {
                // the chip stays as it was
            }
            final Calls.Headsets fh = h;
            act.ui.post(() -> {
                if (my == session && fh != null) {
                    setHeadsets(fh);
                }
            });
        });
    }

    /** The phone's picture changed (a call came, ended, the headset moved, the phone could not be reached). */
    private void onWatch() {
        boolean on = callOn();
        if (on != lastCallOn) {
            lastCallOn = on;
            showBody();
            if (!on) {
                recentsLoaded = false; // the call that just ended is in the list now
                if (segment == RECENTS) {
                    loadRecents(session, false);
                }
            }
        } else if (inCall != null) {
            inCall.update();
        }
        paintChip();
        paintNotice();
    }

    private void paintChip() {
        if (chip == null) {
            return;
        }
        HeadsetRules.View v;
        if (headsets != null) {
            v = HeadsetRules.of(headsets, headsetProblem);
        } else {
            v = new HeadsetRules.View();
            String connected = act.callWatch() == null ? "" : act.callWatch().latest().headset;
            if (!connected.isEmpty()) {
                v.chip = connected + " · connected";
                v.tone = HeadsetRules.Tone.GOOD;
            }
        }
        Ui.paintChip(chip, v.chip, v.tone == HeadsetRules.Tone.GOOD ? Ui.GREEN : v.tone == HeadsetRules.Tone.WARN ? Ui.AMBER : Ui.DIM);
        paintBattery();
    }

    /** The headset's battery beside the chip: only while the phone says the headset is connected to it (the live picture carries the level). */
    private void paintBattery() {
        if (battery == null) {
            return;
        }
        CallWatch w = act.callWatch();
        Calls.Now n = w == null ? null : w.latest();
        boolean on = n != null && w.status() == CallWatch.OK && !n.headset.isEmpty();
        battery.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            battery.setLevel(n.headsetBattery, true);
            battery.setContentDescription(BatteryRules.words(n.headsetBattery, n.headset));
        }
    }

    private void paintNotice() {
        if (noticeBox == null) {
            return;
        }
        noticeBox.removeAllViews();
        CallWatch w = act.callWatch();
        int status = w == null ? Calls.WATCH_IDLE : w.status();
        String problem = w == null ? "" : w.problem();
        if (state == null && !stateProblem.isEmpty() && status != Calls.WATCH_OK) {
            status = Calls.WATCH_UNREACHED;
            problem = stateProblem;
        }
        if (status == Calls.WATCH_OK) {
            problem = "";
        }
        CallRules.Notice n = CallRules.notice(status, problem, state);
        if (n == null) {
            return;
        }
        LinearLayout card = Ui.card(act, n.title, null);
        card.addView(Ui.body(act, n.line), Ui.lp(act, 4));
        if (!n.button.isEmpty()) {
            final boolean allow = n.action.equals("allow");
            Button b = Ui.primary(act, n.button, v -> {
                if (allow) {
                    allowCalls();
                } else {
                    state = null;
                    stateProblem = "";
                    CallWatch cw = act.callWatch();
                    if (cw != null) {
                        cw.wake();
                    }
                    loadState(session);
                    paintNotice();
                }
            });
            card.addView(b, Ui.lp(act, 8));
        }
        noticeBox.addView(card, Ui.lp(act, 6));
    }

    private void allowCalls() {
        final int my = session;
        Toast.makeText(act, "Asking the phone to allow calls…", Toast.LENGTH_SHORT).show();
        net.execute(() -> {
            Calls.State st = null;
            IOException bad = null;
            try {
                st = remote().grant();
            } catch (IOException e) {
                bad = e;
            }
            final Calls.State fs = st;
            final IOException fb = bad;
            act.ui.post(() -> {
                if (my != session) {
                    return;
                }
                if (fs != null) {
                    state = fs;
                } else {
                    CallsViews.report(act, null, fb);
                }
                paintNotice();
                if (ready()) {
                    loadHeadsets(my);
                    loadContacts(my, true);
                }
            });
        });
    }

    // ------------------------------------------------------------------ calling

    /**
     * Dials a number. When the headset the user chose is not connected to the phone, asks first (the call would otherwise ring on the
     * phone's own earpiece, which may be across the room): connect it and call, call anyway, or cancel.
     */
    void dial(final String number) {
        if (dialing) {
            return;
        }
        final Calls.Device cur = headsets == null || !headsets.allowed ? null : headsets.current();
        if (cur != null && !cur.connected && Cfg.callsHeadsetAsk(act)) {
            TextsViews.show(new android.app.AlertDialog.Builder(act).setTitle("Headset not connected")
                    .setMessage(cur.name + " is not connected to the phone, so the call would play on the phone itself. Connect the headset first?")
                    .setPositiveButton("Connect and call", (x, i) -> connectThenDial(number))
                    .setNeutralButton("Call anyway", (x, i) -> startCall(number))
                    .setNegativeButton("Cancel", null));
            return;
        }
        startCall(number);
    }

    private void connectThenDial(final String number) {
        final int my = session;
        Toast.makeText(act, "Connecting the headset to the phone… (up to 25 seconds)", Toast.LENGTH_LONG).show();
        net.execute(() -> {
            Calls.Result r = null;
            IOException bad = null;
            try {
                r = remote().headsetConnect("");
            } catch (IOException e) {
                bad = e;
            }
            final Calls.Result fr = r;
            final IOException fb = bad;
            act.ui.post(() -> {
                if (fr != null && fr.ok) {
                    if (my == session) {
                        loadHeadsets(my);
                    }
                    startCall(number);
                } else {
                    setHeadsetProblem(fr != null ? fr.why : TextsTab.explain(fb));
                    Toast.makeText(act, "The call was not placed. " + (fr != null ? fr.why : TextsTab.explain(fb)), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /** Places the call: says what the phone answered, and the call screen follows by itself. */
    private void startCall(final String number) {
        if (dialing) {
            return;
        }
        dialing = true;
        act.callWatch().dial(number, (r, e) -> {
            dialing = false;
            if (r != null && r.ok) {
                typed = "";
                paintTyped();
                if (!Cfg.callsWatch(act)) {
                    Cfg.setCallsWatch(act, true);
                }
            } else {
                String say = e != null ? TextsTab.explain(e) : r.why;
                if (matchView != null) {
                    matchView.setText(say);
                    matchView.setTextColor(Ui.RED);
                } else {
                    Toast.makeText(act, say, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /** From a conversation or a list: "Call Mom?" and then the call. Opens this tab, where the call is shown. */
    void callWithAsk(final String number, String shown) {
        CallRules.Dial d = CallRules.check(number);
        if (!d.ok()) {
            Toast.makeText(act, d.why(), Toast.LENGTH_LONG).show();
            return;
        }
        final String who = shown == null || shown.isEmpty() ? CallRules.pretty(number) : shown;
        TextsViews.show(new android.app.AlertDialog.Builder(act).setTitle("Call " + who + "?")
                .setMessage(CallRules.pretty(d.number) + "\n\nThe phone places the call. Your headset must be connected to the phone.")
                .setPositiveButton("Call", (x, i) -> {
                    act.openTab(Tab.CALLS);
                    dial(d.number);
                }).setNegativeButton("Cancel", null));
    }

    private void openMessage(String number) {
        act.openTab(Tab.TEXTS);
        TextsTab texts = act.tabOf(TextsTab.class);
        if (texts == null) {
            return;
        }
        if (texts.canOpenWithoutLock()) {
            texts.openWith(number);
        } else {
            Toast.makeText(act, "Unlock Texts first, then open the conversation.", Toast.LENGTH_LONG).show();
        }
    }

    private String shown(String number, String phoneName) {
        TextsTab texts = act.tabOf(TextsTab.class);
        String n = texts == null ? (phoneName == null ? "" : phoneName) : texts.nameForNumber(number, phoneName);
        return n == null ? "" : n;
    }

    // ------------------------------------------------------------------ the keypad

    private View buildKeypad() {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(Ui.dp(act, 14), Ui.dp(act, 4), Ui.dp(act, 14), Ui.dp(act, 8));

        typedView = Ui.text(act, "", 36, Ui.FG, false);
        typedView.setGravity(Gravity.CENTER);
        typedView.setSingleLine(true);
        typedView.setHint("Enter a number");
        typedView.setHintTextColor(Ui.DIM);
        typedView.setOnLongClickListener(v -> {
            paste();
            return true;
        });
        col.addView(typedView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 60)));
        matchView = Ui.text(act, "", 14, Ui.DIM, false);
        matchView.setGravity(Gravity.CENTER);
        matchView.setSingleLine(true);
        matchView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(matchView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 34)));

        DialPad pad = new DialPad(act, new DialPad.Listener() {
            @Override
            public void key(char c) {
                typed = CallRules.press(typed, c);
                paintTyped();
            }

            @Override
            public void longZero() {
                typed = CallRules.afterLongZero(typed);
                paintTyped();
            }

            @Override
            public void backspace() {
                typed = CallRules.backspace(typed);
                paintTyped();
            }

            @Override
            public void clear() {
                typed = "";
                paintTyped();
            }

            @Override
            public void call() {
                callTyped();
            }
        });
        col.addView(pad, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        paintTyped();
        if (ready()) {
            loadContacts(session, false);
        }
        return Ui.scroll(act, col);
    }

    private void paste() {
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData d = cm == null ? null : cm.getPrimaryClip();
        CharSequence t = d == null || d.getItemCount() == 0 ? null : d.getItemAt(0).coerceToText(act);
        StringBuilder keep = new StringBuilder();
        if (t != null) {
            for (int i = 0; i < t.length() && keep.length() < CallRules.MAX_DIGITS + 2; i++) {
                char c = t.charAt(i);
                if ((c >= '0' && c <= '9') || (keep.length() == 0 && (c == '+' || c == '*'))) {
                    keep.append(c);
                }
            }
        }
        if (keep.length() == 0) {
            Toast.makeText(act, "There is no number to paste.", Toast.LENGTH_SHORT).show();
            return;
        }
        typed = keep.toString();
        paintTyped();
    }

    private void paintTyped() {
        if (typedView == null) {
            return;
        }
        typedView.setText(CallRules.pretty(typed));
        int len = typedView.getText().length();
        typedView.setTextSize(len <= 12 ? 36 : len <= 17 ? 30 : 24);
        matchView.setTextColor(Ui.DIM);
        matchView.setOnClickListener(null);
        matchView.setClickable(false);
        matchView.setText("");
        String digits = CallRules.digitsOf(typed);
        if (digits.isEmpty()) {
            return;
        }
        List<CallRules.Match> m = CallRules.search(contacts, typed, 1);
        if (!m.isEmpty()) {
            final CallRules.Match best = m.get(0);
            String name = shown(best.number, best.name);
            matchView.setText(name + " · " + CallRules.pretty(best.number));
            matchView.setTextColor(Ui.ACCENT);
            matchView.setClickable(true);
            matchView.setOnClickListener(v -> {
                typed = CallRules.check(best.number).number;
                paintTyped();
            });
        } else if (digits.length() >= 3 && CallRules.check(typed).ok()) {
            matchView.setText("Add to contacts");
            matchView.setTextColor(Ui.ACCENT);
            matchView.setClickable(true);
            matchView.setOnClickListener(v -> newContact(typed));
        }
    }

    private void callTyped() {
        if (typed.isEmpty()) { // like every phone: Call on an empty pad brings back the last number dialed
            for (Calls.Entry e : recents) {
                if (Calls.OUT.equals(e.type) && !e.number.isEmpty()) {
                    typed = CallRules.check(e.number).ok() ? CallRules.check(e.number).number : "";
                    paintTyped();
                    return;
                }
            }
            matchView.setText("Type a number first.");
            matchView.setTextColor(Ui.DIM);
            return;
        }
        CallRules.Dial d = CallRules.check(typed);
        if (!d.ok()) {
            matchView.setText(d.why());
            matchView.setTextColor(Ui.RED);
            matchView.setSingleLine(false);
            return;
        }
        if (!ready()) {
            matchView.setText("The phone is not ready for calls yet. See the note above.");
            matchView.setTextColor(Ui.RED);
            return;
        }
        dial(d.number);
    }

    // ------------------------------------------------------------------ recents

    private void loadRecents(final int my, final boolean older) {
        final long before = older && !recents.isEmpty() ? recents.get(recents.size() - 1).time : 0;
        net.execute(() -> {
            List<Calls.Entry> got = null;
            String bad = "";
            try {
                got = remote().log(80, before);
            } catch (IOException e) {
                bad = TextsTab.explain(e);
            }
            final List<Calls.Entry> fg = got;
            final String fb = bad;
            act.ui.post(() -> {
                if (my != session) {
                    return;
                }
                if (fg != null) {
                    if (!older) {
                        recents.clear();
                    }
                    recents.addAll(fg);
                    recentsMore = fg.size() >= 80;
                    recentsProblem = "";
                    recentsLoaded = true;
                    groups.clear();
                    groups.addAll(CallRules.group(recents));
                    boolean anyNew = false;
                    for (Calls.Entry e : fg) {
                        anyNew |= e.isNew;
                    }
                    if (anyNew && segment == RECENTS && !older) {
                        markSeen();
                    }
                } else {
                    recentsProblem = fb;
                }
                if (recentsAdapter != null) {
                    recentsAdapter.notifyDataSetChanged();
                }
                paintRecentsStatus();
            });
        });
    }

    /** The user looked at the list: the missed calls on it are no longer new (the number on the Calls button goes away). */
    private void markSeen() {
        net.execute(() -> {
            try {
                remote().logSeen();
            } catch (IOException ignored) {
                // it stays new until the next look
            }
            act.callWatch().wake();
        });
    }

    private TextView recentsStatus;

    private void paintRecentsStatus() {
        if (recentsStatus == null) {
            return;
        }
        if (!recentsProblem.isEmpty()) {
            recentsStatus.setText(recentsProblem);
        } else if (!recentsLoaded) {
            recentsStatus.setText(ready() ? "Loading calls…" : "");
        } else {
            recentsStatus.setText(groups.isEmpty() ? "No calls yet." : "");
        }
        recentsStatus.setVisibility(recentsStatus.getText().length() == 0 ? View.GONE : View.VISIBLE);
    }

    private View buildRecents() {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        recentsStatus = Ui.text(act, "", 14, Ui.DIM, false);
        recentsStatus.setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), Ui.dp(act, 8));
        col.addView(recentsStatus);
        recentsList = new ListView(act);
        recentsList.setDivider(null);
        recentsList.setBackgroundColor(Ui.BG);
        recentsList.setPadding(Ui.dp(act, 10), 0, Ui.dp(act, 10), Ui.dp(act, 8));
        recentsList.setClipToPadding(false);
        recentsAdapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return groups.size() + (recentsMore ? 1 : 0);
            }

            @Override
            public Object getItem(int i) {
                return i < groups.size() ? groups.get(i) : null;
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                if (i >= groups.size()) {
                    Button more = Ui.button(act, "Show older calls", v -> loadRecents(session, true));
                    return more;
                }
                CallsViews.Row r = convert instanceof CallsViews.Row ? (CallsViews.Row) convert : new CallsViews.Row(act);
                bindRecent(r, groups.get(i));
                return r;
            }

            @Override
            public int getViewTypeCount() {
                return 2;
            }

            @Override
            public int getItemViewType(int i) {
                return i < groups.size() ? 0 : 1;
            }
        };
        recentsList.setAdapter(recentsAdapter);
        recentsList.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < groups.size()) {
                entryMenu(groups.get(pos).first);
            }
        });
        col.addView(recentsList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        paintRecentsStatus();
        if (ready() && !recentsLoaded) {
            loadRecents(session, false);
        } else if (recentsLoaded && !groups.isEmpty()) {
            boolean anyNew = false;
            for (Calls.Entry e : recents) {
                anyNew |= e.isNew;
            }
            if (anyNew) {
                markSeen();
            }
        }
        return col;
    }

    private void bindRecent(CallsViews.Row r, final CallRules.Group g) {
        final Calls.Entry e = g.first;
        String name = shown(e.number, e.name);
        String label = name.isEmpty() ? (e.number.isEmpty() ? "Unknown" : CallRules.pretty(e.number)) : name;
        boolean missed = CallRules.kind(e.type).equals(Calls.MISSED);
        r.title.setText(label + (g.count() > 1 ? " (" + g.count() + ")" : ""));
        r.title.setTextColor(missed ? Ui.RED : Ui.FG);
        r.detail.setText(CallRules.detail(e, System.currentTimeMillis(), TextTime.Env.device(android.text.format.DateFormat.is24HourFormat(act))));
        TextsViews.bindAvatar(r.avatar, label, e.number.isEmpty() ? label : ContactBook.matchKey(e.number), false);
        r.actions.removeAllViews();
        if (!e.number.isEmpty()) {
            r.actions.addView(CallsViews.round(act, Icons.Kind.HANDSET, CallsViews.CALL_GREEN, 44, 0.46f, "Call", v -> dial(CallRules.check(e.number).number)));
        }
    }

    private void entryMenu(final Calls.Entry e) {
        if (e.number.isEmpty()) {
            Toast.makeText(act, "That caller's number is hidden.", Toast.LENGTH_SHORT).show();
            return;
        }
        final String name = shown(e.number, e.name);
        contactMenu(name, e.number);
    }

    // ------------------------------------------------------------------ contacts

    private void loadContacts(final int my, final boolean force) {
        if (contactsLoaded && !force) {
            return;
        }
        contactsLoaded = true;
        net.execute(() -> {
            List<Sms.Person> got = null;
            String bad = "";
            try {
                got = remote().contacts("", CallApi.MAX_CONTACTS);
            } catch (IOException e) {
                bad = TextsTab.explain(e);
            }
            final List<Sms.Person> fg = got;
            final String fb = bad;
            act.ui.post(() -> {
                if (my != session) {
                    return;
                }
                if (fg != null) {
                    contacts.clear();
                    contacts.addAll(fg);
                    contactsProblem = "";
                } else {
                    contactsProblem = fb;
                    contactsLoaded = false; // ask again next time
                }
                renderContacts();
                paintTyped();
            });
        });
    }

    private View buildContacts() {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(act, 12), Ui.dp(act, 2), Ui.dp(act, 4), Ui.dp(act, 4));
        EditText find = new EditText(act);
        find.setHint("Search by name or number");
        find.setHintTextColor(Ui.DIM);
        find.setTextColor(Ui.FG);
        find.setTextSize(16);
        find.setSingleLine(true);
        find.setInputType(InputType.TYPE_CLASS_TEXT);
        find.setBackground(TextsViews.shape(Ui.CARD_HI, Ui.dp(act, 12)));
        find.setPadding(Ui.dp(act, 14), Ui.dp(act, 10), Ui.dp(act, 14), Ui.dp(act, 10));
        find.setText(contactsQuery);
        find.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                contactsQuery = s.toString().trim();
                renderContacts();
            }
        });
        bar.addView(find, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(TextsViews.icon(act, Icons.Kind.PLUS, "New contact", v -> newContact("")));
        col.addView(bar);
        contactsStatus = Ui.text(act, "", 13, Ui.DIM, false);
        contactsStatus.setPadding(Ui.dp(act, 16), Ui.dp(act, 2), Ui.dp(act, 16), Ui.dp(act, 4));
        col.addView(contactsStatus);

        contactsList = new ListView(act);
        contactsList.setDivider(null);
        contactsList.setBackgroundColor(Ui.BG);
        contactsList.setPadding(Ui.dp(act, 10), 0, Ui.dp(act, 10), Ui.dp(act, 8));
        contactsList.setClipToPadding(false);
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
                    return CallsViews.sectionHeader(act, (String) o);
                }
                CallsViews.Row r = convert instanceof CallsViews.Row ? (CallsViews.Row) convert : new CallsViews.Row(act);
                bindContact(r, (CallRules.Match) o);
                return r;
            }
        };
        contactsList.setAdapter(contactsAdapter);
        contactsList.setOnItemClickListener((p, v, pos, id) -> {
            Object o = pos >= 0 && pos < contactRows.size() ? contactRows.get(pos) : null;
            if (o instanceof CallRules.Match) {
                CallRules.Match m = (CallRules.Match) o;
                contactMenu(shown(m.number, m.name), m.number);
            }
        });
        col.addView(contactsList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        renderContacts();
        if (ready()) {
            loadContacts(session, false);
        }
        return col;
    }

    /** Builds the rows: the contacts by their shown names with a letter above each group; or what a typed name or number finds. */
    private void renderContacts() {
        if (contactsList == null) {
            return;
        }
        contactRows.clear();
        List<CallRules.Match> list = new ArrayList<>();
        String q = contactsQuery;
        boolean digitsOnly = !q.isEmpty() && q.matches("[0-9+*#() .-]+");
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (digitsOnly) {
            list.addAll(CallRules.search(contacts, q, 200));
        } else {
            for (Sms.Person p : contacts) {
                String key = ContactBook.matchKey(p.number);
                if (!seen.add(key.isEmpty() ? p.number : key)) {
                    continue;
                }
                String name = shown(p.number, p.name);
                if (q.isEmpty() || name.toLowerCase(java.util.Locale.US).contains(q.toLowerCase(java.util.Locale.US))) {
                    list.add(new CallRules.Match(name, p.number, 0));
                }
            }
            Collections.sort(list, (a, b) -> a.name.compareToIgnoreCase(b.name));
        }
        String letter = "";
        for (CallRules.Match m : list) {
            if (!digitsOnly) {
                String l = CallRules.initial(m.name);
                if (!l.equals(letter)) {
                    letter = l;
                    contactRows.add(l);
                }
            }
            contactRows.add(m);
        }
        if (contactsStatus != null) {
            if (!contactsProblem.isEmpty()) {
                contactsStatus.setText(contactsProblem);
            } else if (!ready() && contacts.isEmpty()) {
                contactsStatus.setText("The contacts come from the phone once calls are allowed there.");
            } else if (list.isEmpty()) {
                contactsStatus.setText(contacts.isEmpty() ? "The phone has no contacts to show." : "Nobody found.");
            } else {
                contactsStatus.setText(list.size() + (list.size() == 1 ? " contact" : " contacts"));
            }
        }
        if (contactsAdapter != null) {
            contactsAdapter.notifyDataSetChanged();
        }
    }

    private void bindContact(CallsViews.Row r, final CallRules.Match m) {
        String name = m.name.isEmpty() ? CallRules.pretty(m.number) : m.name;
        r.title.setText(name);
        r.title.setTextColor(Ui.FG);
        r.detail.setText(CallRules.pretty(m.number));
        TextsViews.bindAvatar(r.avatar, name, ContactBook.matchKey(m.number), false);
        r.actions.removeAllViews();
        r.actions.addView(CallsViews.round(act, Icons.Kind.CHAT, Ui.CARD_HI, 44, 0.46f, "Message", v -> openMessage(m.number)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(act, 44), Ui.dp(act, 44));
        p.leftMargin = Ui.dp(act, 8);
        IconButton call = CallsViews.round(act, Icons.Kind.HANDSET, CallsViews.CALL_GREEN, 44, 0.46f, "Call", v -> {
            CallRules.Dial d = CallRules.check(m.number);
            if (d.ok()) {
                dial(d.number);
            } else {
                Toast.makeText(act, d.why(), Toast.LENGTH_LONG).show();
            }
        });
        call.setLayoutParams(p);
        r.actions.addView(call);
    }

    // ------------------------------------------------------------------ one person: the menu, the contact dialog

    private void contactMenu(final String name, final String number) {
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        labels.add("Call");
        acts.add(() -> {
            CallRules.Dial d = CallRules.check(number);
            if (d.ok()) {
                dial(d.number);
            } else {
                Toast.makeText(act, d.why(), Toast.LENGTH_LONG).show();
            }
        });
        labels.add("Message");
        acts.add(() -> openMessage(number));
        final boolean named = !name.isEmpty() && !PhoneNumbers.looksLikeNumber(name);
        labels.add(named ? "Rename contact" : "Add to contacts");
        acts.add(() -> editContact(number, named ? name : ""));
        labels.add("Copy number");
        acts.add(() -> Ui.copy(act, number));
        TextsViews.menu(act, name.isEmpty() ? CallRules.pretty(number) : name, labels.toArray(new String[0]), acts.toArray(new Runnable[0]));
    }

    private int phoneMode() {
        return state != null && state.contacts ? ContactDialog.PHONE_READY : ContactDialog.PHONE_CANNOT;
    }

    private static final String PHONE_NOTE = "The contact is saved in the phone's contacts too, so it is there on the phone and in Texts. "
            + "If the phone does not allow that, it is saved on this tablet only.";

    private void newContact(String number) {
        ContactDialog.show(act, "New contact", "", number, true, phoneMode(), PHONE_NOTE, false, (n, num, onPhone) -> saveContact(num, n, onPhone), null);
    }

    private void editContact(final String number, String name) {
        final TextsTab texts = act.tabOf(TextsTab.class);
        final boolean mine = texts != null && texts.hasTabletName(number);
        ContactDialog.show(act, name.isEmpty() ? "Add to contacts" : "Rename contact", name, number, false, phoneMode(), PHONE_NOTE, mine,
                (n, num, onPhone) -> saveContact(num, n, onPhone), () -> {
                    if (texts != null) {
                        texts.forgetTabletName(number);
                    }
                    renderContacts();
                    if (recentsAdapter != null) {
                        recentsAdapter.notifyDataSetChanged();
                    }
                    Toast.makeText(act, "Using the phone's name.", Toast.LENGTH_SHORT).show();
                });
    }

    /** Saves a contact on this tablet at once (Texts shows it too) and on the phone when asked; says where it ended up. */
    private void saveContact(final String number, final String name, final boolean alsoPhone) {
        final String send = PhoneNumbers.forSending(number);
        if (send.isEmpty() || name.trim().isEmpty()) {
            Toast.makeText(act, "Not saved: it needs a name and a phone number.", Toast.LENGTH_SHORT).show();
            return;
        }
        final TextsTab texts = act.tabOf(TextsTab.class);
        if (texts != null) {
            texts.rememberName(number, name, false);
        }
        refreshNames();
        if (!alsoPhone) {
            Toast.makeText(act, "Saved on this tablet only.", Toast.LENGTH_SHORT).show();
            return;
        }
        final int my = session;
        net.execute(() -> {
            Sms.ContactResult r = null;
            String err = "";
            try {
                r = remote().saveContact(name, send);
                if (!r.ok && r.needPermission) {
                    remote().grant();
                    r = remote().saveContact(name, send);
                }
            } catch (IOException e) {
                err = TextsTab.explain(e);
            }
            final Sms.ContactResult fr = r;
            final String fe = err;
            act.ui.post(() -> {
                if (fr != null && fr.ok) {
                    if (texts != null) {
                        texts.rememberName(number, name, true);
                    }
                    if (my == session) {
                        loadContacts(my, true);
                    }
                    Toast.makeText(act, (fr.how.equals("renamed") ? "Renamed" : "Saved") + " on the phone too" + (fr.where.isEmpty() ? "." : " (" + fr.where + ")."),
                            Toast.LENGTH_LONG).show();
                } else {
                    String why = fr != null ? fr.why : fe;
                    Toast.makeText(act, "Saved on this tablet only. " + (why == null ? "" : why), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void refreshNames() {
        renderContacts();
        if (recentsAdapter != null) {
            recentsAdapter.notifyDataSetChanged();
        }
        paintTyped();
    }
}
