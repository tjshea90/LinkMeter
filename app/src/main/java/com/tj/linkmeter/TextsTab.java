package com.tj.linkmeter;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tablet only: the phone's texts. A list of conversations, a conversation with its bubbles and a box to write in (see
 * {@link TextThread}), and a new-message screen with contact search. Everything goes through {@link RemoteSms}; the phone keeps its
 * texts private unless its owner switched "Let the tablet use my texts" on, and this screen says so plainly when it is off.
 */
final class TextsTab extends Tab {
    private static final int[] AVATARS = {0xFF3F7CE0, 0xFF2E9E6B, 0xFFC2662D, 0xFF8E5BD6, 0xFFC24A7B, 0xFF2C93A8, 0xFF9A8A2A};

    private final ExecutorService net = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "linkmeter-texts");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean polling = new AtomicBoolean();
    private FrameLayout root;
    private boolean shown;
    /** Changes whenever this tab is rebuilt or left, so that an answer that arrives late is ignored. */
    private long session;
    private Sms.State state;
    private final List<Sms.Thread> threads = new ArrayList<>();
    private String stamp = "";
    private SharedPreferences seen;

    private View listScreen;
    private ListView listView;
    private ThreadAdapter adapter;
    private TextView banner;
    private TextThread open;
    private View newScreen;

    // the new-message screen
    private final List<Sms.Person> picked = new ArrayList<>();
    private final List<Sms.Person> suggestions = new ArrayList<>();
    private LinearLayout pickedBox;
    private LinearLayout suggestBox;
    private EditText toInput;
    private Button nextBtn;
    private Runnable searchTask;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!shown) {
                return;
            }
            if (state != null && state.on && state.read) {
                pollStamp();
            }
            act.ui.postDelayed(this, 3000);
        }
    };

    TextsTab(MainActivity a) {
        super(a);
    }

    @Override
    String title() {
        return TEXTS;
    }

    @Override
    Icons.Kind icon() {
        return Icons.Kind.CHAT;
    }

    // ------------------------------------------------------------ what the conversation screens use

    ExecutorService net() {
        return net;
    }

    long session() {
        return session;
    }

    RemoteSms remote() throws IOException {
        String host = Net.peerHost(act);
        if (host.isEmpty()) {
            throw new IOException(FileBrowser.noPeer(act));
        }
        return new RemoteSms(new PeerLink(host, Net.peerToken(act)));
    }

    /** The tablet remembers what it has shown (the phone's own "unread" can only be cleared in the phone's Messages app). */
    void markSeen(String key, long time) {
        if (seen != null && time > seen.getLong(key, 0)) {
            seen.edit().putLong(key, time).apply();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }
    }

    private boolean unread(Sms.Thread t) {
        return t.unread && seen != null && t.time > seen.getLong(t.key, 0);
    }

    private TextTime.Env env() {
        return TextTime.Env.device(android.text.format.DateFormat.is24HourFormat(act));
    }

    // ------------------------------------------------------------ the tab

    @Override
    View build() {
        session++;
        open = null;
        newScreen = null;
        listScreen = null;
        seen = act.getSharedPreferences("texts_seen", Context.MODE_PRIVATE);
        root = new FrameLayout(act);
        root.setBackgroundColor(Ui.BG);
        showMessage("Texts", "Checking the phone…", null, null, null, null);
        return root;
    }

    @Override
    void onShow() {
        shown = true;
        loadState();
        act.ui.removeCallbacks(tick);
        act.ui.postDelayed(tick, 3000);
    }

    @Override
    void onHide() {
        shown = false;
        session++;
        act.ui.removeCallbacks(tick);
        act.backHandler = null;
        act.setTabBarVisible(true);
        hideKeyboard();
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null && root != null) {
            im.hideSoftInputFromWindow(root.getWindowToken(), 0);
        }
    }

    // ------------------------------------------------------------ what the phone says it is ready for

    private void showMessage(String head, String text, String b1, Runnable r1, String b2, Runnable r2) {
        open = null; // a message replaces whatever was on screen
        newScreen = null;
        act.backHandler = null;
        act.setTabBarVisible(true);
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(act, 24);
        col.setPadding(pad, pad * 2, pad, pad);
        col.addView(Ui.text(act, head, 22, Ui.FG, true));
        TextView t = Ui.text(act, text, 15, Ui.DIM, false);
        t.setGravity(Gravity.CENTER);
        col.addView(t, Ui.lp(act, 12));
        if (b1 != null) {
            col.addView(Ui.button(act, b1, v -> r1.run()), Ui.lp(act, 16));
        }
        if (b2 != null) {
            col.addView(Ui.button(act, b2, v -> r2.run()), Ui.lp(act, 8));
        }
        setScreen(Ui.scroll(act, col));
    }

    private void setScreen(View v) {
        if (root == null) {
            return;
        }
        root.removeAllViews();
        root.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void loadState() {
        final long my = session;
        net.execute(() -> {
            Sms.State st = null;
            String err = null;
            try {
                st = remote().state();
            } catch (IOException e) {
                err = e.getMessage();
            }
            final Sms.State fs = st;
            final String fe = err;
            act.ui.post(() -> {
                if (my == session && shown) {
                    applyState(fs, fe);
                }
            });
        });
    }

    private void applyState(Sms.State st, String err) {
        state = st;
        if (st == null) {
            showMessage("Texts", err == null ? "The phone could not be reached." : err, "Try again", this::loadState, null, null);
        } else if (!st.supported) {
            showMessage("Update the phone", new RemoteSms.TooOld().getMessage(), "Check again", this::loadState, null, null);
        } else if (!st.on) {
            showMessage("Texts are switched off", new RemoteSms.Off().getMessage()
                    + "\n\nYour texts stay private until you do. Only this paired tablet can ask for them.", "Check again", this::loadState, null, null);
        } else if (!st.read || !st.send) {
            showMessage("The phone needs permission", st.note.isEmpty() ? "The phone has not allowed LinkMeter to read and send texts yet." : st.note,
                    "Ask the phone to allow it", this::grant, "Check again", this::loadState);
        } else if (open == null && newScreen == null) {
            showList();
            loadThreads();
        }
    }

    private void grant() {
        showMessage("Texts", "Asking the phone to allow texts (it uses Shizuku)…", null, null, null, null);
        final long my = session;
        net.execute(() -> {
            Sms.State st = null;
            String err = null;
            try {
                st = remote().grant();
            } catch (IOException e) {
                err = e.getMessage();
            }
            final Sms.State fs = st;
            final String fe = err;
            act.ui.post(() -> {
                if (my == session && shown) {
                    if (fs != null && (!fs.read || !fs.send)) {
                        showMessage("Still not allowed", (fs.note.isEmpty() ? "" : fs.note + "\n\n")
                                + "Open LinkMeter on the phone, Phone tab, and tap \"" + PhoneScreenRules.TEXTS_BUTTON + "\". Shizuku has to be "
                                + "running on the phone for this to work from here.", "Check again", this::loadState, null, null);
                    } else {
                        applyState(fs, fe);
                    }
                }
            });
        });
    }

    // ------------------------------------------------------------ the list of conversations

    private void showList() {
        act.backHandler = null;
        act.setTabBarVisible(true);
        open = null;
        newScreen = null;
        if (listScreen == null) {
            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setBackgroundColor(Ui.BG);
            int pad = Ui.dp(act, 12);

            LinearLayout head = new LinearLayout(act);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setBackgroundColor(Ui.CARD);
            head.setPadding(pad, Ui.dp(act, 8), pad, Ui.dp(act, 8));
            head.addView(Ui.text(act, "Texts", 22, Ui.FG, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(Ui.button(act, "New message", v -> showNew()), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            col.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            banner = Ui.text(act, "", 13, Ui.AMBER, false);
            banner.setPadding(pad, Ui.dp(act, 6), pad, Ui.dp(act, 6));
            banner.setVisibility(View.GONE);
            col.addView(banner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            listView = new ListView(act);
            listView.setDivider(null);
            listView.setDividerHeight(0);
            adapter = new ThreadAdapter();
            listView.setAdapter(adapter);
            listView.setOnItemClickListener((p, v, i, id) -> {
                Sms.Thread t = threads.get(i);
                openThread(t.key, t.name, t.addresses);
            });
            col.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            TextView note = Ui.text(act, "Shows texts and picture messages. Chats that use RCS (chat features in Google Messages) are not shown.",
                    11, Ui.DIM, false);
            note.setPadding(pad, Ui.dp(act, 4), pad, Ui.dp(act, 6));
            col.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            listScreen = col;
        }
        setScreen(listScreen);
    }

    private void showBanner(String text) {
        if (banner != null) {
            banner.setText(text == null ? "" : text);
            banner.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private void loadThreads() {
        final long my = session;
        net.execute(() -> {
            List<Sms.Thread> l = null;
            IOException err = null;
            try {
                l = remote().threads(100);
            } catch (IOException e) {
                err = e;
            }
            final List<Sms.Thread> fl = l;
            final IOException fe = err;
            act.ui.post(() -> {
                if (my != session || !shown) {
                    return;
                }
                if (fl != null) {
                    threads.clear();
                    threads.addAll(fl);
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                    showBanner(null);
                } else if (fe instanceof RemoteSms.Off) {
                    loadState(); // switched off since: say so
                } else {
                    showBanner(fe == null ? null : fe.getMessage());
                }
            });
        });
    }

    private void pollStamp() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        final long my = session;
        net.execute(() -> {
            String st = null;
            IOException err = null;
            try {
                st = remote().stamp();
            } catch (IOException e) {
                err = e;
            }
            final String fs = st;
            final IOException fe = err;
            act.ui.post(() -> {
                polling.set(false);
                if (my != session || !shown) {
                    return;
                }
                if (fs != null && !fs.equals(stamp)) {
                    stamp = fs;
                    loadThreads();
                    if (open != null) {
                        open.reload();
                    }
                } else if (fe instanceof RemoteSms.Off) {
                    loadState();
                } else if (fe != null) {
                    showBanner(fe.getMessage());
                    if (open != null) {
                        open.showStatus(fe.getMessage());
                    }
                }
            });
        });
    }

    private int colorOf(String key) {
        return AVATARS[(key.hashCode() & 0x7fffffff) % AVATARS.length];
    }

    private final class ThreadAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return threads.size();
        }

        @Override
        public Object getItem(int i) {
            return threads.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Sms.Thread t = threads.get(i);
            LinearLayout row;
            TextView avatar;
            TextView nameView;
            TextView snippet;
            TextView time;
            if (convert == null) {
                row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10));
                avatar = Ui.text(act, "", 17, Color.WHITE, true);
                avatar.setGravity(Gravity.CENTER);
                row.addView(avatar, new LinearLayout.LayoutParams(Ui.dp(act, 46), Ui.dp(act, 46)));
                LinearLayout mid = new LinearLayout(act);
                mid.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                mp.leftMargin = Ui.dp(act, 12);
                mp.rightMargin = Ui.dp(act, 8);
                nameView = Ui.text(act, "", 16, Ui.FG, true);
                nameView.setSingleLine(true);
                nameView.setEllipsize(TextUtils.TruncateAt.END);
                snippet = Ui.text(act, "", 13, Ui.DIM, false);
                snippet.setSingleLine(true);
                snippet.setEllipsize(TextUtils.TruncateAt.END);
                mid.addView(nameView);
                mid.addView(snippet);
                row.addView(mid, mp);
                time = Ui.text(act, "", 12, Ui.DIM, false);
                row.addView(time, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                row.setTag(new TextView[] {avatar, nameView, snippet, time});
            } else {
                row = (LinearLayout) convert;
            }
            TextView[] v = (TextView[]) row.getTag();
            avatar = v[0];
            nameView = v[1];
            snippet = v[2];
            time = v[3];
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(colorOf(t.key));
            avatar.setBackground(g);
            avatar.setText(PhoneNumbers.initials(t.name));
            boolean un = unread(t);
            nameView.setText(t.name);
            snippet.setText(t.snippet.isEmpty() ? (t.attachment ? "Attachment" : "") : t.snippet);
            snippet.setTextColor(un ? Ui.FG : Ui.DIM);
            snippet.setTypeface(null, un ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            time.setText(TextTime.stamp(t.time, System.currentTimeMillis(), env()));
            time.setTextColor(un ? Ui.ACCENT : Ui.DIM);
            return row;
        }
    }

    // ------------------------------------------------------------ one conversation

    private void openThread(String key, String name, List<String> addresses) {
        open = new TextThread(this, key, name, addresses);
        newScreen = null;
        act.backHandler = this::onBack;
        setScreen(open.build());
        open.reload();
    }

    void closeThread() {
        hideKeyboard();
        showList();
        loadThreads();
    }

    private void onBack() {
        if (newScreen != null || open != null) {
            hideKeyboard();
            showList();
            loadThreads();
        } else {
            act.backHandler = null;
            act.onBackPressed();
        }
    }

    // ------------------------------------------------------------ a new message

    private void showNew() {
        picked.clear();
        suggestions.clear();
        act.backHandler = this::onBack;
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(act, 12);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(Ui.CARD);
        head.setPadding(pad, Ui.dp(act, 8), pad, Ui.dp(act, 8));
        head.addView(Ui.button(act, "Back", v -> onBack()), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView t = Ui.text(act, "New message", 20, Ui.FG, true);
        t.setPadding(pad, 0, 0, 0);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nextBtn = Ui.button(act, "Next", v -> startNew());
        nextBtn.setEnabled(false);
        head.addView(nextBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, pad, pad, pad);
        body.addView(Ui.text(act, "To", 13, Ui.DIM, true));
        pickedBox = new LinearLayout(act);
        pickedBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(pickedBox);
        toInput = new EditText(act);
        toInput.setHint("Type a name or a number");
        toInput.setTextColor(Ui.FG);
        toInput.setHintTextColor(Ui.DIM);
        toInput.setSingleLine(true);
        toInput.setInputType(InputType.TYPE_CLASS_TEXT);
        toInput.setOnFocusChangeListener((v, focus) -> act.setTabBarVisible(!focus));
        toInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                // nothing
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                // nothing
            }

            @Override
            public void afterTextChanged(Editable s) {
                search(s.toString().trim());
            }
        });
        body.addView(toInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        suggestBox = new LinearLayout(act);
        suggestBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(suggestBox, Ui.lp(act, 4));
        col.addView(Ui.scroll(act, body), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        newScreen = col;
        open = null;
        setScreen(col);
        showPicked();
        search("");
    }

    private void search(final String q) {
        if (searchTask != null) {
            act.ui.removeCallbacks(searchTask);
        }
        final long my = session;
        searchTask = () -> net.execute(() -> {
            List<Sms.Person> found = new ArrayList<>();
            try {
                found = remote().contacts(q);
            } catch (IOException e) {
                // no suggestions: the typed number can still be used
            }
            final List<Sms.Person> ff = found;
            act.ui.post(() -> {
                if (my == session && newScreen != null && toInput != null && toInput.getText().toString().trim().equals(q)) {
                    suggestions.clear();
                    suggestions.addAll(ff);
                    showSuggestions(q);
                }
            });
        });
        act.ui.postDelayed(searchTask, q.isEmpty() ? 0 : 250);
    }

    private View personRow(String name, String number, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(Ui.dp(act, 4), Ui.dp(act, 10), Ui.dp(act, 4), Ui.dp(act, 10));
        row.addView(Ui.text(act, name, 16, Ui.FG, true));
        if (!number.isEmpty()) {
            row.addView(Ui.text(act, number, 13, Ui.DIM, false));
        }
        row.setOnClickListener(l);
        return row;
    }

    private void showSuggestions(String q) {
        suggestBox.removeAllViews();
        if (PhoneNumbers.looksLikeNumber(q) && !PhoneNumbers.forSending(q).isEmpty()) {
            final String number = q;
            suggestBox.addView(personRow("Send to " + q, "", v -> {
                Sms.Person p = new Sms.Person();
                p.name = PhoneNumbers.pretty(PhoneNumbers.forSending(number));
                p.number = PhoneNumbers.forSending(number);
                addPicked(p);
            }));
        }
        for (final Sms.Person p : suggestions) {
            suggestBox.addView(personRow(p.name.isEmpty() ? p.number : p.name, p.name.isEmpty() ? "" : p.number, v -> addPicked(p)));
        }
        if (suggestBox.getChildCount() == 0 && !q.isEmpty()) {
            suggestBox.addView(Ui.text(act, "No contact found. Type the whole number to send to it.", 13, Ui.DIM, false));
        }
    }

    private void addPicked(Sms.Person p) {
        String key = PhoneNumbers.key(p.number);
        for (Sms.Person x : picked) {
            if (PhoneNumbers.key(x.number).equals(key)) {
                toInput.setText("");
                return;
            }
        }
        picked.add(p);
        toInput.setText("");
        showPicked();
    }

    private void showPicked() {
        pickedBox.removeAllViews();
        for (final Sms.Person p : picked) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = Ui.text(act, (p.name.isEmpty() ? p.number : p.name + "  " + p.number), 15, Ui.FG, true);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(Ui.button(act, "Remove", v -> {
                picked.remove(p);
                showPicked();
            }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            pickedBox.addView(row);
        }
        nextBtn.setEnabled(!picked.isEmpty());
    }

    private void startNew() {
        if (picked.isEmpty()) {
            Toast.makeText(act, "Add who it is for first.", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> numbers = new ArrayList<>();
        StringBuilder name = new StringBuilder();
        for (Sms.Person p : picked) {
            numbers.add(p.number);
            name.append(name.length() > 0 ? ", " : "").append(p.name.isEmpty() ? PhoneNumbers.pretty(p.number) : p.name);
        }
        String key = PhoneNumbers.threadKey(numbers);
        List<String> addresses = new ArrayList<>();
        for (String n : numbers) {
            addresses.add(PhoneNumbers.forSending(n));
        }
        for (Sms.Thread t : threads) {
            if (t.key.equals(key)) { // they already have a conversation: open it
                openThread(t.key, t.name, t.addresses);
                return;
            }
        }
        hideKeyboard();
        openThread(key, name.toString(), addresses);
    }
}
