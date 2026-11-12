package com.tj.linkmeter;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One conversation on the tablet: the messages as bubbles in groups with days and a "new messages" line (pictures as thumbnails
 * fetched from the phone, reactions on the message they are about), quick replies, and the box to write in with an Attach button for
 * photos and files from this tablet. Opens at once with the saved copy, fetches the newest page and the older ones as you scroll up.
 * Talks to the phone only through {@link RemoteSms}; the rules of what is shown are in {@link ThreadRows}.
 */
final class TextThread {
    private static final int PAGE = 40;
    private static final int BUBBLE_ME = 0xFF2F6FE0;
    private static final int BUBBLE_FAILED = 0xFF8A2F2F;

    private final TextsTab tab;
    private final MainActivity act;
    final String key;
    private String name;
    private final List<String> addresses;
    private final boolean group;
    private final long seenAtOpen;

    /** The phone's messages, oldest first, as fetched so far. */
    private final List<Sms.Msg> msgs = new ArrayList<>();
    /** Messages this tablet just sent, shown at once before the phone has them in its record. */
    private final List<Sms.Msg> temps = new ArrayList<>();
    private boolean dropTemps;
    private final List<Object> rows = new ArrayList<>();
    private final Set<String> tapped = new HashSet<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean selecting;
    private boolean more;
    private boolean loadingMore;
    private boolean firstLoad = true;
    private boolean scrollNext;
    private boolean sending;
    private boolean paused;
    private List<String> replies = new ArrayList<>();
    /** The names of the people in a group, by number key. */
    private final java.util.Map<String, String> people = new java.util.HashMap<>();
    private final List<Attach.Item> pending = new ArrayList<>();

    private LinearLayout root;
    private FrameLayout barHolder;
    private LinearLayout normalBar;
    private LinearLayout selectBar;
    private TextView title;
    private TextView subtitle;
    private TextView avatar;
    private TextView selectCount;
    private TextView status;
    private ListView list;
    private RowAdapter adapter;
    private EditText input;
    private IconButton sendBtn;
    private TextView counter;
    private LinearLayout chipBox;
    private HorizontalScrollView chipScroll;
    private LinearLayout replyBox;
    private HorizontalScrollView replyScroll;
    /** Your texts translated into Spanish (Mexico) before they go: what Send does (TranslateFlow), and the strip above the box. */
    private final TranslateFlow flow = new TranslateFlow();
    private LinearLayout transBox;
    private TextView transTitle;
    private TextView transText;
    private LinearLayout transButtons;
    private Button transUndo;
    private Button transEnglish;
    /** Received messages translated into English: message id -> the line under it (memory only, never saved). */
    private final Map<String, String> englishOf = new HashMap<>();
    private final Set<String> englishFailed = new HashSet<>();

    TextThread(TextsTab tab, String key, String name, List<String> addresses) {
        this.tab = tab;
        this.act = tab.act;
        this.key = key;
        this.name = name;
        this.addresses = new ArrayList<>(addresses);
        this.group = key.indexOf(',') >= 0 || addresses.size() > 1;
        this.seenAtOpen = tab.local().seenAt(key);
    }

    private TextTime.Env env() {
        return tab.env();
    }

    // ------------------------------------------------------------ the screen

    View build() {
        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        barHolder = new FrameLayout(act);
        barHolder.setBackgroundColor(Ui.CARD);
        root.addView(barHolder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 60)));
        buildBars();
        showBar(normalBar);

        status = Ui.text(act, "", 13, Ui.AMBER, false);
        status.setBackgroundColor(Ui.CARD);
        status.setPadding(Ui.dp(act, 16), Ui.dp(act, 6), Ui.dp(act, 16), Ui.dp(act, 6));
        status.setVisibility(View.GONE);
        root.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        list = new ListView(act);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 6));
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                // nothing
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                if (first == 0 && more && !loadingMore && !firstLoad && total > 0) {
                    loadEarlier();
                }
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        replyScroll = new HorizontalScrollView(act);
        replyScroll.setHorizontalScrollBarEnabled(false);
        replyBox = new LinearLayout(act);
        replyBox.setOrientation(LinearLayout.HORIZONTAL);
        replyBox.setPadding(Ui.dp(act, 10), Ui.dp(act, 6), Ui.dp(act, 10), Ui.dp(act, 2));
        replyScroll.addView(replyBox);
        replyScroll.setVisibility(View.GONE);
        root.addView(replyScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        chipScroll = new HorizontalScrollView(act);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setBackgroundColor(Ui.CARD);
        chipBox = new LinearLayout(act);
        chipBox.setOrientation(LinearLayout.HORIZONTAL);
        chipBox.setPadding(Ui.dp(act, 10), Ui.dp(act, 8), Ui.dp(act, 10), 0);
        chipScroll.addView(chipBox);
        chipScroll.setVisibility(View.GONE);
        root.addView(chipScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buildTranslateStrip();

        counter = Ui.text(act, "", 11, Ui.DIM, false);
        counter.setGravity(Gravity.END);
        counter.setBackgroundColor(Ui.CARD);
        counter.setPadding(Ui.dp(act, 16), Ui.dp(act, 4), Ui.dp(act, 70), 0);
        counter.setVisibility(View.GONE);
        root.addView(counter, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.BOTTOM);
        bar.setBackgroundColor(Ui.CARD);
        bar.setPadding(Ui.dp(act, 6), Ui.dp(act, 6), Ui.dp(act, 8), Ui.dp(act, 8));
        IconButton attach = TextsViews.icon(act, Icons.Kind.PAPERCLIP, "Attach", v -> attach());
        bar.addView(attach);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(TextsViews.shape(Ui.CARD_HI, Ui.dp(act, 22)));
        input = new EditText(act);
        input.setHint("Text message");
        input.setTextColor(Ui.FG);
        input.setHintTextColor(Ui.DIM);
        input.setTextSize(16);
        input.setBackground(null);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(5);
        input.setPadding(Ui.dp(act, 16), Ui.dp(act, 10), Ui.dp(act, 16), Ui.dp(act, 10));
        input.setOnFocusChangeListener((v, focus) -> tab.composeFocus(focus));
        input.addTextChangedListener(new TextWatcher() {
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
                tab.local().setDraft(key, s.toString());
                flow.boxChanged(s.toString());
                paintStrip();
                updateSend();
            }
        });
        box.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bp.leftMargin = Ui.dp(act, 4);
        bp.rightMargin = Ui.dp(act, 8);
        bar.addView(box, bp);
        sendBtn = new IconButton(act, Icons.Kind.SEND, Ui.ON_ACCENT, Ui.ACCENT, "Send", v -> send());
        sendBtn.setScale(0.5f);
        bar.addView(sendBtn, new LinearLayout.LayoutParams(Ui.dp(act, 46), Ui.dp(act, 46)));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String draft = tab.local().draft(key);
        if (!draft.isEmpty()) {
            input.setText(draft);
            input.setSelection(draft.length());
        }
        flow.setOn(tab.local().isTranslated(key));
        paintStrip();
        updateSend();
        showSaved();
        return root;
    }

    private void buildBars() {
        normalBar = new LinearLayout(act);
        normalBar.setOrientation(LinearLayout.HORIZONTAL);
        normalBar.setGravity(Gravity.CENTER_VERTICAL);
        normalBar.setPadding(Ui.dp(act, 4), 0, Ui.dp(act, 4), 0);
        if (!tab.isWide()) { // beside the list there is nothing to go back to
            normalBar.addView(TextsViews.icon(act, Icons.Kind.BACK, "Back", v -> tab.closeThread()));
        } else {
            normalBar.setPadding(Ui.dp(act, 14), 0, Ui.dp(act, 4), 0);
        }
        avatar = TextsViews.avatar(act, 38);
        TextsViews.bindAvatar(avatar, name, key, false);
        normalBar.addView(avatar, new LinearLayout.LayoutParams(Ui.dp(act, 38), Ui.dp(act, 38)));
        LinearLayout titles = new LinearLayout(act);
        titles.setOrientation(LinearLayout.VERTICAL);
        title = Ui.text(act, name, 18, Ui.FG, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        subtitle = Ui.text(act, subtitleText(), 12, Ui.DIM, false);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(title);
        titles.addView(subtitle);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = Ui.dp(act, 12);
        normalBar.addView(titles, tp);
        normalBar.addView(TextsViews.icon(act, Icons.Kind.MORE, "More", v -> showMenu()));

        selectBar = new LinearLayout(act);
        selectBar.setOrientation(LinearLayout.HORIZONTAL);
        selectBar.setGravity(Gravity.CENTER_VERTICAL);
        selectBar.setPadding(Ui.dp(act, 4), 0, Ui.dp(act, 4), 0);
        selectBar.addView(TextsViews.icon(act, Icons.Kind.CLOSE, "Stop selecting", v -> endSelecting()));
        selectCount = Ui.text(act, "", 18, Ui.FG, true);
        selectBar.addView(selectCount, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        selectBar.addView(TextsViews.icon(act, Icons.Kind.SEND, "Forward", v -> forwardSelected()));
        selectBar.addView(TextsViews.icon(act, Icons.Kind.CHECK, "Copy", v -> copySelected()));
        selectBar.addView(TextsViews.icon(act, Icons.Kind.TRASH, "Delete", v -> deleteSelected()));
    }

    private void showBar(View bar) {
        barHolder.removeAllViews();
        barHolder.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private String subtitleText() {
        if (addresses.size() > 1) {
            return addresses.size() + " people";
        }
        return addresses.isEmpty() ? "" : PhoneNumbers.pretty(addresses.get(0));
    }

    void showStatus(String text) {
        showStatus(text, Ui.AMBER);
    }

    /** The line above the messages: a problem in amber, or a quiet "Loading…" in grey. */
    private void showStatus(String text, int color) {
        if (status == null) {
            return;
        }
        status.setText(text == null ? "" : text);
        status.setTextColor(color);
        status.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** The tab was left or this conversation closed: the draft is written down. */
    void pause() {
        paused = true;
        if (input != null) {
            tab.local().setDraft(key, input.getText().toString());
        }
        tab.local().flush();
    }

    /** Text to put in the box (a forwarded message). */
    void prefill(String text) {
        if (input != null && input.getText().length() == 0) {
            input.setText(text);
            input.setSelection(text.length());
        }
    }

    private void updateSend() {
        if (sendBtn == null) {
            return;
        }
        String text = input.getText().toString();
        boolean can = !sending && !flow.translating() && (text.trim().length() > 0 || !pending.isEmpty());
        sendBtn.set(Icons.Kind.SEND, can ? Ui.ON_ACCENT : Ui.DIM, can ? Ui.ACCENT : Ui.CARD_HI);
        sendBtn.setEnabled(can);
        // the length: shown when it matters (a long text is more than one message; pictures make it a picture message)
        if (!pending.isEmpty()) {
            counter.setText("Picture message");
            counter.setVisibility(View.VISIBLE);
        } else {
            SmsLength.Count c = SmsLength.of(text);
            if (c.parts > 1) {
                counter.setText(c.parts + " texts · " + c.left + " left" + (c.unicode ? " (emoji and accents make texts shorter)" : ""));
                counter.setVisibility(View.VISIBLE);
            } else if (c.parts == 1 && c.left < 30) {
                counter.setText(c.left + " left");
                counter.setVisibility(View.VISIBLE);
            } else {
                counter.setVisibility(View.GONE);
            }
        }
        showReplies();
    }

    // ------------------------------------------------------------ loading

    /** The saved copy at once, so that the conversation is there while the phone answers. */
    private void showSaved() {
        TextsCache c = tab.cache();
        TextsCache.Saved<Sms.Convo> s = c == null ? null : c.loadConvo(key);
        if (s != null) {
            msgs.clear();
            msgs.addAll(s.value.msgs);
            more = s.value.more;
            render(true);
        }
    }

    /** How many messages one request asks for: a page; a phone of the first kind cannot page back, so it is asked for many at once. */
    private int pageSize() {
        return tab.phoneIsV2() ? PAGE : 200;
    }

    /** Asks the phone for the newest page and joins it with what is shown. */
    void reload() {
        final long my = tab.session();
        final int size = pageSize();
        if (firstLoad && msgs.isEmpty() && temps.isEmpty()) {
            showStatus("Loading messages…", Ui.DIM); // a slow phone: say that something is happening
        }
        tab.net().execute(() -> {
            try {
                final Sms.Convo c = tab.remote().thread(key, size);
                TextsCache cache = tab.cache();
                act.ui.post(() -> {
                    if (my == tab.session() && !paused) {
                        apply(c, cache);
                    }
                });
            } catch (final IOException e) {
                act.ui.post(() -> {
                    if (my == tab.session() && !paused) {
                        showStatus(TextsTab.explain(e));
                    }
                });
            }
        });
    }

    private void apply(Sms.Convo c, TextsCache cache) {
        showStatus(null);
        if (!c.head.name.isEmpty()) {
            setDisplayName(tab.shownName(key, c.head.name)); // this tablet's own name for the person, if it has one
        }
        people.putAll(c.people);
        boolean gap = c.msgs.size() >= pageSize() && !msgs.isEmpty() && c.msgs.get(0).time > msgs.get(msgs.size() - 1).time;
        if (gap || firstLoad) {
            msgs.clear();
            msgs.addAll(c.msgs);
            more = c.more;
        } else {
            List<Sms.Msg> merged = ThreadMerge.merge(msgs, c.msgs, true);
            msgs.clear();
            msgs.addAll(merged);
            if (msgs.size() <= c.msgs.size()) {
                more = c.more;
            }
        }
        if (dropTemps) {
            temps.clear();
            dropTemps = false;
        }
        if (cache != null) {
            Sms.Convo save = new Sms.Convo();
            save.head.name = name;
            save.head.addresses.addAll(addresses);
            save.msgs.addAll(msgs);
            save.more = more;
            final TextsCache fc = cache;
            final Sms.Convo fs = save;
            tab.out().execute(() -> fc.saveConvo(key, fs));
        }
        render(firstLoad || scrollNext);
        firstLoad = false;
        scrollNext = false;
        long newest = 0;
        for (Sms.Msg m : msgs) {
            newest = Math.max(newest, m.time);
        }
        int count = Math.max(msgs.size(), tab.threadCount(key));
        tab.markSeen(key, newest, count);
        if (tab.threadUnread(key)) {
            tab.tellPhoneRead(key);
        }
    }

    private void loadEarlier() {
        if (msgs.isEmpty()) {
            return;
        }
        loadingMore = true;
        final long before = msgs.get(0).time;
        final long my = tab.session();
        tab.net().execute(() -> {
            Sms.Convo c = null;
            try {
                c = tab.remote().thread(key, PAGE, before);
            } catch (IOException ignored) {
                // tried again when the list is at the top next time
            }
            final Sms.Convo fc = c;
            act.ui.post(() -> {
                loadingMore = false;
                if (fc == null || my != tab.session() || paused) {
                    return;
                }
                int before1 = list.getFirstVisiblePosition();
                View first = list.getChildAt(0);
                int top = first == null ? 0 : first.getTop();
                int oldCount = rows.size();
                List<Sms.Msg> merged = ThreadMerge.merge(msgs, fc.msgs, false);
                msgs.clear();
                msgs.addAll(merged);
                more = fc.more;
                render(false);
                // stay on the message that was at the top
                list.setSelectionFromTop(before1 + (rows.size() - oldCount), top);
            });
        });
    }

    /** Works out the rows and draws them; stays at the bottom when asked to, or when it was at the bottom. */
    private void render(boolean toBottom) {
        boolean atBottom = adapter.getCount() == 0 || list.getLastVisiblePosition() >= adapter.getCount() - 1;
        List<Sms.Msg> all = new ArrayList<>(msgs);
        all.addAll(temps);
        List<ThreadRows.Row> built = ThreadRows.build(all, tab.local(), key, group, seenAtOpen, System.currentTimeMillis(), env());
        rows.clear();
        if (more) {
            rows.add("more");
        }
        rows.addAll(built);
        replies = SmartReply.suggest(visibleMessages(all), System.currentTimeMillis());
        adapter.notifyDataSetChanged();
        if ((toBottom || atBottom) && !rows.isEmpty()) {
            list.setSelection(rows.size() - 1);
        }
        showReplies();
    }

    private List<Sms.Msg> visibleMessages(List<Sms.Msg> all) {
        List<Sms.Msg> out = new ArrayList<>();
        long cleared = tab.local().clearedAt(key);
        for (Sms.Msg m : all) {
            if (m.time > cleared && !tab.local().isHidden(m.id)) {
                out.add(m);
            }
        }
        return out;
    }

    private void showReplies() {
        if (replyBox == null) {
            return;
        }
        replyBox.removeAllViews();
        boolean show = !replies.isEmpty() && input.getText().toString().trim().isEmpty() && pending.isEmpty() && !selecting;
        replyScroll.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            return;
        }
        for (final String s : replies) {
            TextView chip = Ui.text(act, s, 14, Ui.FG, false);
            chip.setBackground(roundStroke(Ui.BG, Ui.LINE, 18));
            chip.setPadding(Ui.dp(act, 14), Ui.dp(act, 8), Ui.dp(act, 14), Ui.dp(act, 8));
            chip.setOnClickListener(v -> {
                input.setText(s);
                send();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(act, 8);
            replyBox.addView(chip, lp);
        }
    }

    private GradientDrawable roundStroke(int fill, int line, int radiusDp) {
        GradientDrawable g = TextsViews.shape(fill, Ui.dp(act, radiusDp));
        g.setStroke(Ui.dp(act, 1), line);
        return g;
    }

    // ------------------------------------------------------------ the menu of the conversation

    /** A new name for the person (a contact was saved or renamed, or the phone told it): the title and the picture show it. */
    void setDisplayName(String shown) {
        if (shown == null || shown.isEmpty() || shown.equals(name)) {
            return;
        }
        name = shown;
        title.setText(name);
        TextsViews.bindAvatar(avatar, name, key, false);
    }

    private void showMenu() {
        final TextsLocal local = tab.local();
        final Sms.Thread t = tab.threadOf(key);
        final long time = t == null ? System.currentTimeMillis() : t.time;
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        if (addresses.size() == 1 && !PhoneNumbers.forSending(addresses.get(0)).isEmpty()) { // one person with a real number: a contact
            final boolean named = tab.hasContactName(key, name);
            labels.add(named ? "Rename contact" : "Add to contacts");
            acts.add(() -> tab.editContact(key, addresses.get(0), name, named));
        }
        labels.add(local.isPinned(key) ? "Unpin" : "Pin to the top");
        acts.add(() -> {
            local.setPinned(key, !local.isPinned(key), System.currentTimeMillis());
            tab.refreshList();
        });
        labels.add(local.isMuted(key) ? "Unmute" : "Mute");
        acts.add(() -> {
            local.setMuted(key, !local.isMuted(key));
            tab.refreshList();
        });
        final boolean translating = local.isTranslated(key);
        labels.add(translating ? "Stop translating to Spanish" : "Translate my texts to Spanish (Mexico)");
        acts.add(() -> {
            if (translating) {
                setTranslated(false);
            } else {
                askTranslate();
            }
        });
        labels.add("Select messages");
        acts.add(this::startSelecting);
        labels.add("Archive");
        acts.add(() -> {
            local.archive(key, time);
            tab.closeThread();
            tab.showUndo("Conversation archived", () -> {
                local.unarchive(key);
                tab.refreshList();
            });
        });
        labels.add("Delete conversation");
        acts.add(() -> {
            if (t == null) {
                Toast.makeText(act, "There is nothing to delete yet.", Toast.LENGTH_SHORT).show();
                return;
            }
            final List<Sms.Thread> one = new ArrayList<>();
            one.add(t);
            TextsViews.show(new AlertDialog.Builder(act).setTitle("Delete this conversation?")
                    .setMessage("It is removed from this tablet. LinkMeter also asks the phone to delete it, but a phone usually lets only its "
                            + "own Messages app do that; if it refuses, the texts stay on the phone and only disappear here.")
                    .setPositiveButton("Delete", (d, w) -> tab.deleteConversations(one)).setNegativeButton("Cancel", null));
        });
        TextsViews.menu(act, null, labels.toArray(new String[0]), acts.toArray(new Runnable[0]));
    }

    // ------------------------------------------------------------ selecting messages

    private void startSelecting() {
        selecting = true;
        selected.clear();
        showBar(selectBar);
        showReplies();
        Toast.makeText(act, "Tap the messages to select.", Toast.LENGTH_SHORT).show();
    }

    private void endSelecting() {
        selecting = false;
        selected.clear();
        showBar(normalBar);
        adapter.notifyDataSetChanged();
        showReplies();
    }

    private void toggle(String id) {
        if (!selected.remove(id)) {
            selected.add(id);
        }
        if (selected.isEmpty()) {
            endSelecting();
        } else {
            selectCount.setText(selected.size() + " selected");
            adapter.notifyDataSetChanged();
        }
    }

    private List<Sms.Msg> selectedMessages() {
        List<Sms.Msg> out = new ArrayList<>();
        for (Object o : rows) {
            if (o instanceof ThreadRows.Row && ((ThreadRows.Row) o).kind == ThreadRows.MSG && selected.contains(((ThreadRows.Row) o).msg.id)) {
                out.add(((ThreadRows.Row) o).msg);
            }
        }
        return out;
    }

    private void copySelected() {
        StringBuilder sb = new StringBuilder();
        for (Sms.Msg m : selectedMessages()) {
            if (!m.body.isEmpty()) {
                sb.append(sb.length() > 0 ? "\n" : "").append(m.body);
            }
        }
        copy(sb.toString());
        endSelecting();
    }

    private void forwardSelected() {
        StringBuilder sb = new StringBuilder();
        for (Sms.Msg m : selectedMessages()) {
            if (!m.body.isEmpty()) {
                sb.append(sb.length() > 0 ? "\n" : "").append(m.body);
            }
        }
        endSelecting();
        tab.forward(sb.toString());
    }

    private void deleteSelected() {
        deleteMessages(selectedMessages());
        endSelecting();
    }

    /** Asks first; then hides the messages here at once, asks the phone to delete them, and says what it did. */
    private void deleteMessages(final List<Sms.Msg> list) {
        if (list.isEmpty()) {
            return;
        }
        TextsViews.show(new AlertDialog.Builder(act).setTitle(list.size() == 1 ? "Delete this message?" : "Delete " + list.size() + " messages?")
                .setMessage("They are removed from this tablet. LinkMeter also asks the phone to delete them, but a phone usually lets only its "
                        + "own Messages app do that; if it refuses, they stay on the phone and only disappear here.")
                .setPositiveButton("Delete", (d, w) -> doDelete(list)).setNegativeButton("Cancel", null));
    }

    private void doDelete(final List<Sms.Msg> list) {
        final List<String> ids = new ArrayList<>();
        final List<String> phoneIds = new ArrayList<>();
        for (Sms.Msg m : list) {
            ids.add(m.id);
            if (!m.id.startsWith("t")) {
                phoneIds.add(m.id);
            }
        }
        temps.removeAll(list);
        tab.local().hide(ids);
        render(false);
        tab.refreshList();
        if (phoneIds.isEmpty()) {
            return;
        }
        final long my = tab.session();
        tab.out().execute(() -> {
            Sms.DeleteResult r = null;
            if (tab.phoneIsV2()) {
                try {
                    r = tab.remote().delete(phoneIds, new ArrayList<String>());
                } catch (IOException ignored) {
                    // removed here only
                }
            }
            final Sms.DeleteResult fr = r;
            act.ui.post(() -> {
                if (my != tab.session()) {
                    return;
                }
                if (fr != null && fr.failedIds.isEmpty()) {
                    tab.showUndo("Deleted on the phone and on this tablet", null);
                } else {
                    tab.showUndo("Removed from this tablet. The phone kept " + (list.size() == 1 ? "it" : "them")
                            + (fr == null ? "." : ": " + fr.why), () -> {
                        tab.local().unhide(ids);
                        render(false);
                        tab.refreshList();
                    });
                }
                reload();
            });
        });
    }

    // ------------------------------------------------------------ writing

    private void attach() {
        AttachDialog.choose(act, this::addAttachments);
    }

    /** These files are what the message will carry (shown as large cards above the box, each of which opens full screen). */
    void addAttachments(List<Attach.Item> items) {
        pending.addAll(items);
        showChips();
        updateSend();
    }

    /** The size of a card in the strip above the box: large, so that the right photo can be told at a glance. */
    private static final int CARD_DP = 150;

    private void showChips() {
        chipBox.removeAllViews();
        for (final Attach.Item it : pending) {
            final FileKind kind = FileKind.of(it.name, false);
            FrameLayout card = new FrameLayout(act);
            card.setBackground(TextsViews.shape(Ui.CARD_HI, Ui.dp(act, 14)));
            card.setClipToOutline(true);
            card.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Ui.dp(act, 14));
                }
            });
            android.widget.ImageView pic = new android.widget.ImageView(act);
            pic.setScaleType(kind.hasThumbnail() ? android.widget.ImageView.ScaleType.CENTER_CROP : android.widget.ImageView.ScaleType.CENTER_INSIDE);
            pic.setImageDrawable(new FileIcon(kind, it.name));
            pic.setPadding(kind.hasThumbnail() ? 0 : Ui.dp(act, 28), kind.hasThumbnail() ? 0 : Ui.dp(act, 20), kind.hasThumbnail() ? 0 : Ui.dp(act, 28),
                    kind.hasThumbnail() ? 0 : Ui.dp(act, 40));
            card.addView(pic, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (kind.hasThumbnail()) {
                Thumbs.get(act).load(pic, AttachPreview.key(it) + "|" + CARD_DP, "", kind, Ui.dp(act, CARD_DP) * 2, AttachPreview.loader(act, it));
            }
            // the name and size along the bottom edge
            TextView cap = Ui.text(act, it.name + (it.size >= 0 ? "\n" + FsFormat.size(it.size) : ""), 11, Color.WHITE, false);
            cap.setSingleLine(false);
            cap.setMaxLines(2);
            cap.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            cap.setBackgroundColor(0xB3000000);
            cap.setPadding(Ui.dp(act, 8), Ui.dp(act, 4), Ui.dp(act, 8), Ui.dp(act, 4));
            card.addView(cap, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
            // the round remove button, top right
            TextView x = Ui.text(act, "×", 20, Color.WHITE, true);
            x.setGravity(Gravity.CENTER);
            x.setBackground(TextsViews.shape(0xCC000000, Ui.dp(act, 16)));
            x.setContentDescription("Remove " + it.name);
            x.setOnClickListener(v -> {
                pending.remove(it);
                showChips();
                updateSend();
            });
            FrameLayout.LayoutParams xp = new FrameLayout.LayoutParams(Ui.dp(act, 32), Ui.dp(act, 32), Gravity.TOP | Gravity.END);
            xp.topMargin = Ui.dp(act, 6);
            xp.rightMargin = Ui.dp(act, 6);
            card.addView(x, xp);
            card.setContentDescription("Preview " + it.name);
            card.setOnClickListener(v -> AttachPreview.show(act, it, () -> {
                pending.remove(it);
                showChips();
                updateSend();
            }));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(act, CARD_DP), Ui.dp(act, CARD_DP));
            lp.rightMargin = Ui.dp(act, 8);
            lp.bottomMargin = Ui.dp(act, 8);
            chipBox.addView(card, lp);
        }
        chipScroll.setVisibility(pending.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Puts one picked file on the phone and returns its id for the message. */
    private String upload(RemoteSms sms, Attach.Item it) throws IOException {
        InputStream in;
        long size = it.size;
        File tmp = null;
        if (!it.path.isEmpty()) {
            DiskStore local = new DiskStore(Files.root(), "this");
            FsEntry fe = local.stat(it.path);
            if (fe == null) {
                throw new IOException(it.name + " is gone");
            }
            size = fe.size;
            in = local.read(it.path, 0);
        } else if (size < 0) {
            tmp = Attach.copyToCache(act, it);
            size = tmp.length();
            in = new FileInputStream(tmp);
        } else {
            in = act.getContentResolver().openInputStream(it.uri);
        }
        if (in == null) {
            throw new IOException("couldn't read " + it.name);
        }
        try (InputStream src = in) {
            return sms.attach(it.name, FileKind.mime(it.name), src, size, null);
        } finally {
            if (tmp != null) {
                tmp.delete();
            }
        }
    }

    private void send() {
        final String text = input.getText().toString();
        if (sending || (text.trim().isEmpty() && pending.isEmpty())) {
            return;
        }
        if (!text.trim().isEmpty()) {
            TranslateFlow.Do d = flow.send(text);
            if (d == TranslateFlow.Do.NOTHING) {
                return;
            }
            if (d == TranslateFlow.Do.TRANSLATE) {
                translateOut(text);
                return;
            }
        }
        sendNow(text);
    }

    /** Sends this text with what is attached, as it is (a translation, if any, has been checked already). */
    private void sendNow(String text) {
        flow.sent();
        final List<Attach.Item> items = new ArrayList<>(pending);
        input.setText("");
        pending.clear();
        showChips();
        paintStrip();
        sendMessage(text, items);
    }

    /** Puts a message in the conversation at once, as "Sending...", and sends it to the phone in the background. */
    private void sendMessage(final String text, final List<Attach.Item> items) {
        sending = true;
        // the message is in the conversation at once, as "Sending..."
        final Sms.Msg temp = new Sms.Msg();
        temp.id = "t" + System.nanoTime();
        temp.key = key;
        temp.mine = true;
        temp.body = text.replaceAll("\\s+$", "");
        temp.time = System.currentTimeMillis();
        temp.state = Sms.STATE_SENDING;
        temp.mms = !items.isEmpty();
        for (Attach.Item it : items) {
            Sms.Part p = new Sms.Part();
            p.name = it.name;
            p.type = FileKind.mime(it.name);
            temp.parts.add(p);
        }
        temps.add(temp);
        updateSend();
        scrollNext = true;
        render(true);
        showStatus(items.isEmpty() ? null : "Sending " + items.size() + (items.size() == 1 ? " file" : " files") + " to the phone…");
        final long my = tab.session();
        Thread t = new Thread(() -> {
            String error = null;
            boolean recorded = false;
            try {
                RemoteSms sms = tab.remote();
                List<String> ids = new ArrayList<>();
                for (Attach.Item it : items) {
                    ids.add(upload(sms, it));
                }
                Sms.SendResult r = sms.send(addresses, "", text, ids);
                recorded = r.ok || !r.id.isEmpty();
                if (!r.ok) {
                    error = r.error;
                }
            } catch (IOException e) {
                error = e.getMessage();
            }
            final String fe = error;
            final boolean rec = recorded;
            act.ui.post(() -> {
                sending = false;
                if (my != tab.session()) {
                    return;
                }
                if (rec) { // the phone has it in its record now (as sent, or as failed with its reason): the real one replaces this one
                    dropTemps = true;
                    reload();
                } else {
                    temp.state = Sms.STATE_FAILED;
                    temp.error = fe == null ? "the phone could not be reached" : fe;
                    render(false);
                }
                showStatus(rec ? fe : null);
                updateSend();
            });
        }, "linkmeter-texts-send");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------ translating into Spanish (Mexico) and into English

    private void buildTranslateStrip() {
        transBox = new LinearLayout(act);
        transBox.setOrientation(LinearLayout.VERTICAL);
        transBox.setBackgroundColor(Ui.CARD);
        transBox.setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), 0);
        transTitle = Ui.text(act, "", 13, Ui.DIM, false);
        transBox.addView(transTitle);
        transText = Ui.text(act, "", 13, Ui.DIM, false);
        transText.setMaxLines(3);
        transText.setEllipsize(TextUtils.TruncateAt.END);
        transBox.addView(transText, Ui.lp(act, 2));
        transButtons = new LinearLayout(act);
        transButtons.setOrientation(LinearLayout.HORIZONTAL);
        transUndo = Ui.quiet(act, "Undo", v -> undoTranslation());
        transEnglish = Ui.quiet(act, "Send my English", v -> sendEnglish());
        transButtons.addView(transUndo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ep.leftMargin = Ui.dp(act, 8);
        transButtons.addView(transEnglish, ep);
        transBox.addView(transButtons, Ui.lp(act, 4));
        transBox.setVisibility(View.GONE);
        root.addView(transBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** The strip above the box, from the state of the translation; and the box's hint. */
    private void paintStrip() {
        if (transBox == null || input == null) {
            return;
        }
        if (!flow.on()) {
            transBox.setVisibility(View.GONE);
            input.setHint("Text message");
            return;
        }
        input.setHint("Write in English");
        transBox.setVisibility(View.VISIBLE);
        transText.setVisibility(View.GONE);
        transButtons.setVisibility(View.GONE);
        transTitle.setTextColor(Ui.DIM);
        if (flow.translating()) {
            transTitle.setText("Translating into Spanish (Mexico)…");
        } else if (flow.checking()) {
            transTitle.setText("This is the Spanish (Mexico)" + (flow.by().isEmpty() ? "" : ", by " + flow.by())
                    + ". Check it, then press Send to send it.");
            transText.setText("Your English: \u201c" + flow.english() + "\u201d");
            transText.setVisibility(View.VISIBLE);
            transUndo.setVisibility(View.VISIBLE);
            transButtons.setVisibility(View.VISIBLE);
        } else if (!flow.why().isEmpty()) {
            transTitle.setText(flow.why() + " Press Send to try again.");
            transTitle.setTextColor(Ui.AMBER);
            transUndo.setVisibility(View.GONE);
            transButtons.setVisibility(Translation.hasWords(input.getText().toString()) ? View.VISIBLE : View.GONE);
        } else {
            transTitle.setText("Your texts here are translated into Spanish (Mexico). Press Send to see the Spanish before it goes.");
        }
    }

    /** The switch in the conversation's menu: what it does and what leaves the tablet, asked once each time it is switched on. */
    private void askTranslate() {
        TextsViews.show(new AlertDialog.Builder(act).setTitle("Translate your texts to Spanish (Mexico)?")
                .setMessage("When you press Send, LinkMeter translates your English into Spanish the way it is spoken in Mexico and puts it in "
                        + "the box for you to check. Press Send again to send it. Undo gives your English back, and \"Send my English\" "
                        + "sends it as you wrote it.\n\nTo translate, the words of the text go over the internet to MyMemory, a translation "
                        + "service, or to Google Translate when MyMemory is busy. Nothing else is sent with them: no name, no number.\n\n"
                        + "A text you receive can be translated into English too: press and hold it.")
                .setPositiveButton("Translate", (d, w) -> setTranslated(true))
                .setNegativeButton("Cancel", null));
    }

    private void setTranslated(boolean on) {
        tab.local().setTranslated(key, on);
        flow.setOn(on);
        paintStrip();
        updateSend();
    }

    /** Your English into Spanish (Mexico): the answer goes into the box for you to check (TranslateFlow). */
    private void translateOut(final String text) {
        final int n = flow.start(text);
        paintStrip();
        updateSend();
        Translator.translate(act, text, Translation.Way.TO_MEXICAN, a -> {
            if (input == null) {
                return;
            }
            String spanish = flow.answer(n, input.getText().toString(), a.ok, a.text, a.by, a.why);
            if (spanish != null) {
                input.setText(spanish);
                input.setSelection(spanish.length());
            }
            paintStrip();
            updateSend();
        });
    }

    private void undoTranslation() {
        String english = flow.undo();
        if (english != null) {
            input.setText(english);
            input.setSelection(english.length());
        }
        paintStrip();
        updateSend();
    }

    /** "Send my English": your words as you wrote them, not the Spanish. */
    private void sendEnglish() {
        if (sending) {
            return;
        }
        String english = flow.sendEnglish(input.getText().toString());
        if (english != null && !english.trim().isEmpty()) {
            sendNow(english);
        } else {
            paintStrip();
        }
    }

    /** A received text into English, shown under it (asked for with "Translate to English" in its menu). */
    private void translateIn(final Sms.Msg m) {
        englishFailed.remove(m.id);
        englishOf.put(m.id, "Translating into English…");
        adapter.notifyDataSetChanged();
        Translator.translate(act, m.body, Translation.Way.TO_ENGLISH, a -> {
            if (a.ok) {
                englishOf.put(m.id, "English" + (a.by.isEmpty() ? "" : ", by " + a.by) + ": " + a.text);
            } else {
                englishOf.put(m.id, a.why);
                englishFailed.add(m.id);
            }
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        });
    }

    private void askResend(final Sms.Msg m) {
        if (!m.parts.isEmpty()) {
            Toast.makeText(act, "Pictures and files can't be sent again from here. Attach them and send a new text.", Toast.LENGTH_LONG).show();
            return;
        }
        TextsViews.show(new AlertDialog.Builder(act).setTitle("Send again?").setMessage(m.body)
                .setPositiveButton("Send again", (d, w) -> {
                    temps.remove(m);
                    input.setText(m.body);
                    if (!sending) {
                        sendNow(m.body); // as it was written the first time: a Spanish text is not translated again
                    }
                }).setNeutralButton("Delete", (d, w) -> doDelete(java.util.Collections.singletonList(m)))
                .setNegativeButton("Cancel", null));
    }

    // ------------------------------------------------------------ one picture or file

    private void open(final Sms.Part p) {
        Toast.makeText(act, "Opening " + p.name + "…", Toast.LENGTH_SHORT).show();
        Thread t = new Thread(() -> {
            try {
                byte[] bytes = tab.remote().part(p.id, 0, 24 * 1024 * 1024);
                File dir = new File(act.getCacheDir(), "texts-open");
                dir.mkdirs();
                String safe = p.name.replaceAll("[^A-Za-z0-9._-]", "_");
                final File f = new File(dir, (safe.isEmpty() ? "attachment" : safe));
                try (FileOutputStream os = new FileOutputStream(f)) {
                    os.write(bytes);
                }
                final String mime = p.type.isEmpty() ? FileKind.mime(f.getName()) : p.type;
                act.ui.post(() -> {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(ShareProvider.uriFor(act, f), mime);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        act.startActivity(i);
                    } catch (RuntimeException e) {
                        Toast.makeText(act, "No app on this tablet can open that.", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (final IOException e) {
                act.ui.post(() -> Toast.makeText(act, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show());
            }
        }, "linkmeter-texts-open");
        t.setDaemon(true);
        t.start();
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && !text.isEmpty()) {
            cm.setPrimaryClip(ClipData.newPlainText("text", text));
            Toast.makeText(act, "Copied", Toast.LENGTH_SHORT).show();
        }
    }

    private void showDetails(Sms.Msg m) {
        String when = new java.text.SimpleDateFormat("EEEE, MMM d, yyyy 'at' " + (android.text.format.DateFormat.is24HourFormat(act) ? "HH:mm" : "h:mm a"),
                java.util.Locale.getDefault()).format(new java.util.Date(m.time));
        String who = m.mine ? "To: " + (addresses.size() > 1 ? addresses.size() + " people" : name) : "From: " + (m.address.isEmpty() ? name : PhoneNumbers.pretty(m.address));
        String kind = m.mms || !m.parts.isEmpty() ? "Picture message" : "Text message";
        String state = m.mine ? stateText(m) : "";
        TextsViews.show(new AlertDialog.Builder(act).setTitle("Message details")
                .setMessage(who + "\n" + when + "\n" + kind + (state.isEmpty() ? "" : "\n" + state))
                .setPositiveButton("Close", null));
    }

    // ------------------------------------------------------------ the bubbles

    private static String stateText(Sms.Msg m) {
        switch (m.state) {
            case Sms.STATE_SENDING:
                return "Sending…";
            case Sms.STATE_SENT:
                return "Sent";
            case Sms.STATE_DELIVERED:
                return "Delivered";
            case Sms.STATE_FAILED:
                return "Not sent" + (m.error.isEmpty() ? "" : ": " + m.error);
            default:
                return "";
        }
    }

    private void onMessageTap(Sms.Msg m) {
        if (selecting) {
            toggle(m.id);
        } else if (m.mine && m.state == Sms.STATE_FAILED) {
            askResend(m);
        } else if (!tapped.remove(m.id)) {
            tapped.add(m.id); // show or hide the time under this message
            adapter.notifyDataSetChanged();
        } else {
            adapter.notifyDataSetChanged();
        }
    }

    private boolean onMessageLong(final Sms.Msg m) {
        if (selecting) {
            toggle(m.id);
            return true;
        }
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        if (!m.body.isEmpty()) {
            labels.add("Copy text");
            acts.add(() -> copy(m.body));
        }
        labels.add("Select");
        acts.add(() -> {
            startSelecting();
            toggle(m.id);
        });
        if (!m.body.isEmpty()) {
            labels.add("Forward");
            acts.add(() -> tab.forward(m.body));
        }
        if (!m.mine && Translation.hasWords(m.body) && (!englishOf.containsKey(m.id) || englishFailed.contains(m.id))) {
            labels.add("Translate to English");
            acts.add(() -> translateIn(m));
        }
        labels.add("Delete");
        acts.add(() -> deleteMessages(java.util.Collections.singletonList(m)));
        labels.add("Details");
        acts.add(() -> showDetails(m));
        // a message that was sent to me can be answered with one tap on an emoji
        TextsViews.messageMenu(act, m.mine ? null : EmojiText.QUICK, this::quickReply, labels.toArray(new String[0]), acts.toArray(new Runnable[0]));
        return true;
    }

    /** Sends one emoji as a text of its own (the box and what is attached stay as they are). */
    private void quickReply(String emoji) {
        if (sending) {
            Toast.makeText(act, "Wait a moment: a message is still being sent.", Toast.LENGTH_SHORT).show();
            return;
        }
        sendMessage(emoji, new ArrayList<Attach.Item>());
    }

    private final class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int i) {
            return rows.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public int getViewTypeCount() {
            return 4;
        }

        @Override
        public int getItemViewType(int i) {
            Object o = rows.get(i);
            if (o instanceof String) {
                return 3;
            }
            return ((ThreadRows.Row) o).kind; // 0 day, 1 message, 2 new-messages line
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Object o = rows.get(i);
            if (o instanceof String) {
                TextView t = convert instanceof TextView ? (TextView) convert : Ui.text(act, "", 12, Ui.DIM, false);
                t.setGravity(Gravity.CENTER);
                t.setPadding(0, Ui.dp(act, 10), 0, Ui.dp(act, 10));
                t.setText("Loading earlier messages…");
                return t;
            }
            ThreadRows.Row r = (ThreadRows.Row) o;
            if (r.kind == ThreadRows.DAY) {
                TextView h = convert instanceof TextView ? (TextView) convert : Ui.text(act, "", 12, Ui.DIM, true);
                h.setGravity(Gravity.CENTER);
                h.setPadding(0, Ui.dp(act, 16), 0, Ui.dp(act, 8));
                h.setText(r.label);
                return h;
            }
            if (r.kind == ThreadRows.NEW_MESSAGES) {
                TextView h = convert instanceof TextView ? (TextView) convert : Ui.text(act, "", 12, Ui.ACCENT, true);
                h.setGravity(Gravity.CENTER);
                h.setPadding(0, Ui.dp(act, 12), 0, Ui.dp(act, 6));
                h.setText("——  " + r.label + "  ——");
                return h;
            }
            Bubble b;
            if (convert == null || !(convert.getTag() instanceof Bubble)) {
                b = new Bubble();
                convert = b.outer;
                convert.setTag(b);
            } else {
                b = (Bubble) convert.getTag();
            }
            b.bind(r);
            return convert;
        }
    }

    /** One message, built once and filled again for each. */
    private final class Bubble {
        final LinearLayout outer;
        final LinearLayout column;
        final TextView who;
        final LinearLayout bubble;
        final LinearLayout partsBox;
        final TextView body;
        final TextView reactions;
        final TextView copyCode;
        final TextView english;
        final TextView meta;

        Bubble() {
            outer = new LinearLayout(act);
            outer.setOrientation(LinearLayout.HORIZONTAL);
            column = new LinearLayout(act);
            column.setOrientation(LinearLayout.VERTICAL);
            who = Ui.text(act, "", 11, Ui.DIM, true);
            who.setPadding(Ui.dp(act, 12), 0, 0, Ui.dp(act, 2));
            column.addView(who, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            bubble = new LinearLayout(act);
            bubble.setOrientation(LinearLayout.VERTICAL);
            bubble.setPadding(Ui.dp(act, 12), Ui.dp(act, 8), Ui.dp(act, 12), Ui.dp(act, 8));
            partsBox = new LinearLayout(act);
            partsBox.setOrientation(LinearLayout.VERTICAL);
            bubble.addView(partsBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            body = Ui.text(act, "", 15.5f, Ui.FG, false);
            bubble.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            column.addView(bubble, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            reactions = Ui.text(act, "", 13, Ui.FG, false);
            reactions.setBackground(roundStroke(Ui.CARD_HI, Ui.BG, 12));
            reactions.setPadding(Ui.dp(act, 8), Ui.dp(act, 2), Ui.dp(act, 8), Ui.dp(act, 2));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = -Ui.dp(act, 8);
            rp.leftMargin = Ui.dp(act, 10);
            rp.rightMargin = Ui.dp(act, 10);
            column.addView(reactions, rp);
            copyCode = TextsViews.pill(act);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.topMargin = Ui.dp(act, 4);
            cp.leftMargin = Ui.dp(act, 4);
            column.addView(copyCode, cp);
            english = Ui.text(act, "", 13, Ui.DIM, false);
            english.setPadding(Ui.dp(act, 6), Ui.dp(act, 3), Ui.dp(act, 6), 0);
            column.addView(english, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            meta = Ui.text(act, "", 11, Ui.DIM, false);
            meta.setPadding(Ui.dp(act, 6), Ui.dp(act, 3), Ui.dp(act, 6), 0);
            column.addView(meta, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            outer.addView(column, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        private float[] radii(boolean mine, boolean start, boolean end) {
            float big = Ui.dp(act, 18);
            float small = Ui.dp(act, 5);
            float tl = big, tr = big, br = big, bl = big;
            if (mine) { // the right side is the tail side
                if (!start) {
                    tr = small;
                }
                if (!end) {
                    br = small;
                }
            } else {
                if (!start) {
                    tl = small;
                }
                if (!end) {
                    bl = small;
                }
            }
            return new float[] {tl, tl, tr, tr, br, br, bl, bl};
        }

        void bind(final ThreadRows.Row r) {
            final Sms.Msg m = r.msg;
            int side = Ui.dp(act, 10);
            outer.setGravity(m.mine ? Gravity.END : Gravity.START);
            outer.setPadding(side, r.groupStart ? Ui.dp(act, 7) : Ui.dp(act, 1), side, 0);
            column.setGravity(m.mine ? Gravity.END : Gravity.START);
            final boolean isSel = selected.contains(m.id);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadii(radii(m.mine, r.groupStart, r.groupEnd));
            // a text of only emoji is drawn big, without the bubble (a failed one keeps its colour: it needs to be seen)
            final boolean big = EmojiText.only(m.body, 3) && m.parts.isEmpty() && !(m.mine && m.state == Sms.STATE_FAILED);
            g.setColor(big && !isSel ? Color.TRANSPARENT : m.mine ? (m.state == Sms.STATE_FAILED ? BUBBLE_FAILED : BUBBLE_ME) : Ui.CARD_HI);
            if (isSel) {
                g.setStroke(Ui.dp(act, 2), Ui.FG);
            }
            bubble.setBackground(g);
            int ink = m.mine ? Color.WHITE : Ui.FG;
            final int maxText = (int) (act.getResources().getDisplayMetrics().widthPixels * (tab.isWide() ? 0.34f : 0.72f));

            if (group && !m.mine && r.groupStart && !r.sender.isEmpty()) {
                String known = people.get(PhoneNumbers.key(r.sender));
                who.setText(known != null ? known : PhoneNumbers.pretty(r.sender));
                who.setVisibility(View.VISIBLE);
            } else {
                who.setVisibility(View.GONE);
            }

            partsBox.removeAllViews();
            for (final Sms.Part p : m.parts) {
                if (p.image() && !p.id.isEmpty()) {
                    ImageView iv = new ImageView(act);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    iv.setImageDrawable(new FileIcon(FileKind.IMAGE, p.name));
                    iv.setOnClickListener(v -> open(p));
                    LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(act, 200), Ui.dp(act, 200));
                    ip.bottomMargin = Ui.dp(act, 4);
                    partsBox.addView(iv, ip);
                    Thumbs.get(act).load(iv, "sms|" + p.id, p.id, FileKind.IMAGE, 360, (key1, path, kind, px) -> {
                        byte[] bytes = tab.remote().part(path, px, 4 * 1024 * 1024);
                        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    });
                } else {
                    TextView file = Ui.text(act, (p.image() ? "Picture: " : "Attachment: ") + p.name, 14, ink, true);
                    file.setPaintFlags(file.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
                    file.setMaxWidth(maxText);
                    file.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 4));
                    if (!p.id.isEmpty()) {
                        file.setOnClickListener(v -> open(p));
                    }
                    partsBox.addView(file);
                }
            }
            partsBox.setVisibility(m.parts.isEmpty() ? View.GONE : View.VISIBLE);

            body.setText(m.body);
            body.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, big ? 40f : 15.5f);
            body.setTextColor(ink);
            body.setMaxWidth(maxText);
            body.setVisibility(m.body.isEmpty() ? View.GONE : View.VISIBLE);
            body.setMovementMethod(null);
            if (m.body.contains("://") || m.body.contains("www.") || m.body.contains("@")) {
                Linkify.addLinks(body, Linkify.WEB_URLS | Linkify.EMAIL_ADDRESSES); // never phone numbers: nothing here dials
                body.setLinkTextColor(m.mine ? Color.WHITE : Ui.ACCENT);
            }
            View.OnClickListener tap = v -> onMessageTap(m);
            View.OnLongClickListener hold = v -> onMessageLong(m);
            bubble.setOnClickListener(tap);
            bubble.setOnLongClickListener(hold);
            body.setOnClickListener(tap);
            body.setOnLongClickListener(hold);

            final String code = OtpCodes.forBubble(m.body, m.mine, m.time, System.currentTimeMillis());
            if (code.isEmpty()) {
                copyCode.setVisibility(View.GONE);
            } else {
                copyCode.setText("Copy code");
                copyCode.setOnClickListener(v -> TextsViews.copyCode(act, code));
                copyCode.setVisibility(View.VISIBLE);
            }

            String tr = englishOf.get(m.id);
            if (tr == null) {
                english.setVisibility(View.GONE);
            } else {
                english.setText(tr);
                english.setTextColor(englishFailed.contains(m.id) ? Ui.AMBER : Ui.DIM);
                english.setMaxWidth(maxText);
                english.setVisibility(View.VISIBLE);
            }

            if (r.reactions.isEmpty()) {
                reactions.setVisibility(View.GONE);
            } else {
                StringBuilder sb = new StringBuilder();
                for (ThreadRows.Applied a : r.reactions) {
                    sb.append(sb.length() > 0 ? " " : "").append(a.emoji);
                }
                reactions.setText(sb.toString());
                reactions.setVisibility(View.VISIBLE);
            }

            boolean showMeta = r.groupEnd || tapped.contains(m.id) || (m.mine && m.state == Sms.STATE_FAILED);
            String st = m.mine && (r.lastOfMine || m.state == Sms.STATE_FAILED || m.state == Sms.STATE_SENDING) ? stateText(m) : "";
            if (showMeta) {
                meta.setText(TextTime.clock(m.time, env()) + (st.isEmpty() ? "" : "  ·  " + st)
                        + (m.mine && m.state == Sms.STATE_FAILED ? "  ·  tap to try again" : ""));
                meta.setTextColor(m.mine && m.state == Sms.STATE_FAILED ? Ui.RED : Ui.DIM);
                meta.setVisibility(View.VISIBLE);
            } else {
                meta.setVisibility(View.GONE);
            }
        }
    }
}
