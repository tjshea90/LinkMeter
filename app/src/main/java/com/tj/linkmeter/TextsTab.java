package com.tj.linkmeter;

import android.app.AlertDialog;
import android.content.Context;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.SecretKey;

/**
 * Tablet only: the phone's texts. A list of conversations (with search, selecting several, pin, archive, delete, unread filter), a
 * conversation with its bubbles ({@link TextThread}), and a new-message screen with contact search. On a wide screen the list stays
 * on the left and the conversation opens beside it. Everything goes through {@link RemoteSms}; the phone keeps its texts private
 * unless its owner switched "Let the tablet use my texts" on, and this screen says so plainly when it is off.
 *
 * Fast and live: the tab opens at once with the tablet's saved copy ({@link TextsCache}) and asks the phone in the background; a
 * phone that speaks protocol 2 is asked to say when something changes (a request that waits for news), an older one is asked every
 * 3 seconds as before. What the tablet decides itself (pins, archive, delete, drafts, what was read) lives in {@link TextsLocal}.
 */
final class TextsTab extends Tab {
    /** For the pictures made by tools/uipreview: a link that answers like a phone (null: the real link to the phone). */
    static RemoteStore.Link testLink;

    /** How long one request for news may wait at the phone. */
    private static final int POLL_WAIT_S = 20;
    private static final int WIDE_DP = 720;
    private static final int LIST_DP = 340;

    /** Reads from the phone (lists, conversations, searches): three at once, so that opening a conversation does not wait for a slow list. */
    private final ExecutorService net = Executors.newFixedThreadPool(3, daemon("linkmeter-texts"));
    /** Things that change something (mark read, delete, grant, saving the copy): one at a time, in the order they were asked. */
    private final ExecutorService out = Executors.newSingleThreadExecutor(daemon("linkmeter-texts-out"));
    /**
     * The texts being sent, one after the other in the order they were written (a picture's upload can take a while: it holds up the next
     * text, as in any messaging app, but nothing else).
     */
    private final ExecutorService send = Executors.newSingleThreadExecutor(daemon("linkmeter-texts-send"));

    private static java.util.concurrent.ThreadFactory daemon(final String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    /** What the tablet asked of the phone for Texts and how long it took (Texts menu > Connection details). */
    static final LinkLog LOG = new LinkLog(40);
    private volatile long pollGen;
    private boolean shown;
    /** Changes whenever this tab is rebuilt or left, so that an answer that arrives late is ignored. */
    private long session;

    private TextsPrefs prefs;
    private BlobStore blobs;
    private TextsLocal local;
    private TextsCache cache;
    /** Which erasing ({@link TextsVault#epoch}) the open files belong to. */
    private long storageEpoch = -1;
    /** The tablet's Keystore did not work, so nothing is written to the tablet (the choices last until the app closes). */
    private boolean memoryOnly;

    /** When the screen lock was last passed and when the tab was left; it lives as long as the app does, not as long as one screen. */
    private static final TextsLock LOCK = new TextsLock();
    /** The lock screen is up and no text is on screen. */
    private boolean locked;
    /** The tablet's screen-lock question was already put in this visit (it is not put again by itself after a Cancel). */
    private boolean askedUnlock;

    private Sms.State state;
    private boolean v2;
    /** What the phone itself calls each person (before this tablet's own names are put over it), by number key. */
    private final java.util.Map<String, String> phoneNames = new java.util.HashMap<>();
    private final List<Sms.Thread> threads = new ArrayList<>();
    private List<ThreadListRules.Item> items = new ArrayList<>();
    private final List<Object> rows = new ArrayList<>();
    private final List<Sms.Hit> hits = new ArrayList<>();
    /** What is shown is the saved copy, not what the phone said just now. */
    private boolean fromCopy;
    private long copyAt;
    private long updatedAt;
    private String problem = "";
    private boolean readRefused;

    private String query = "";
    private boolean unreadOnly;
    private boolean archivedView;
    private boolean searching;
    private boolean selecting;
    private final Set<String> selected = new LinkedHashSet<>();
    private long searchGen;
    private Runnable searchTask;

    // views
    private FrameLayout root;
    private FrameLayout detail;
    private FrameLayout listPane;
    private FrameLayout barHolder;
    private LinearLayout normalBar;
    private LinearLayout searchBar;
    private LinearLayout selectBar;
    private TextView titleView;
    private TextView subtitleView;
    private EditText searchInput;
    private TextView selectCount;
    private TextView banner;
    private Button bannerButton;
    private ListView listView;
    private RowAdapter adapter;
    private LinearLayout emptyView;
    private TextView emptyTitle;
    private TextView emptyText;
    private IconButton fab;
    private LinearLayout undoBar;
    private TextView undoText;
    private Button undoButton;
    private Runnable undoAction;
    private Runnable hideUndo;
    private boolean wide;
    private boolean detailShown;
    private boolean messageMode;
    private TextThread open;
    private String openKey = "";
    private boolean composing;
    private String forwardText;

    // the new-message screen
    private final List<Sms.Person> picked = new ArrayList<>();
    private LinearLayout pickedBox;
    private LinearLayout suggestBox;
    private EditText toInput;
    private Button nextBtn;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!shown) {
                return;
            }
            renderSubtitle(); // "Updated 2 min ago" moves on by itself
            act.ui.postDelayed(this, 30_000);
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

    ExecutorService out() {
        return out;
    }

    ExecutorService sendQueue() {
        return send;
    }

    long session() {
        return session;
    }

    TextsLocal local() {
        return local;
    }

    TextsCache cache() {
        return prefs != null && prefs.keepCopy() ? cache : null;
    }

    TextsPrefs prefs() {
        return prefs;
    }

    /** The conversation that is open (null when none is). For the pictures made by tools/uipreview. */
    TextThread current() {
        return open;
    }

    boolean phoneIsV2() {
        return v2;
    }

    RemoteSms remote() throws IOException {
        if (testLink != null) {
            return new RemoteSms(new TimedLink(testLink, LOG, System::currentTimeMillis));
        }
        String host = Net.peerHost(act);
        if (host.isEmpty()) {
            throw new IOException(FileBrowser.noPeer(act));
        }
        // 8 s to make the connection: a phone that just woke up can need a few seconds (3 s showed "no answer" for a phone that was there)
        return new RemoteSms(new TimedLink(new PeerLink(host, Net.peerToken(act), 8000), LOG, System::currentTimeMillis));
    }

    /** The start of the sentence for a phone that is there but did not answer in time (it is not "can't reach"). */
    static final String SLOW = "The phone is slow to answer";

    /** An error in words for the user: a connection that failed says why (the hotspot, Shizuku...). */
    static String explain(IOException e) {
        if (e instanceof java.net.SocketTimeoutException) {
            return SLOW + " (it did not reply in time). LinkMeter keeps trying; tap Try again to ask now.";
        }
        if (e instanceof Client.ConnectFailed) {
            String w = Client.why((Client.ConnectFailed) e);
            return w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1);
        }
        String m = e.getMessage();
        return m == null || m.isEmpty() ? "The phone could not be reached." : Character.toUpperCase(m.charAt(0)) + m.substring(1);
    }

    TextTime.Env env() {
        return TextTime.Env.device(android.text.format.DateFormat.is24HourFormat(act));
    }

    /** The conversation was shown up to {@code time} (it holds {@code count} messages): remembered here, and asked of the phone once. */
    void markSeen(String key, long time, int count) {
        if (local != null && local.markSeen(key, time, count)) {
            renderList();
        }
    }

    /** Opened a conversation that the phone calls unread: ask it to say read (it may refuse; then it is not asked again). */
    void tellPhoneRead(final String key) {
        if (!v2 || readRefused) {
            return;
        }
        out.execute(() -> {
            try {
                Sms.DeleteResult r = remote().markRead(java.util.Collections.singletonList(key));
                if (r.doneKeys.isEmpty()) {
                    readRefused = true;
                }
            } catch (IOException ignored) {
                // not now
            }
        });
    }

    private void post(Runnable r) {
        act.ui.post(r);
    }

    // ------------------------------------------------------------ the tab

    @Override
    View build() {
        resetScreen();
        prefs = new TextsPrefs(act);
        root = new FrameLayout(act);
        root.setBackgroundColor(Ui.BG);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            boolean w = (r - l) >= Ui.dp(act, WIDE_DP);
            if (w != wide && listPane != null && !messageMode) {
                wide = w;
                arrange();
            }
        });
        wide = act.getResources().getConfiguration().screenWidthDp >= WIDE_DP;
        undoBar = buildUndoBar();
        if (mustUnlock()) {
            locked = true;
            showLocked(false);
        } else {
            locked = false;
            begin();
        }
        return root;
    }

    /** A clean screen: nothing open, nothing selected, answers that are still on their way are ignored (a rebuilt tab, or the lock). */
    private void resetScreen() {
        session++;
        pollGen++;
        open = null;
        openKey = "";
        composing = false;
        detailShown = false;
        messageMode = false;
        listPane = null;
        searching = false;
        selecting = false;
        selected.clear();
        query = "";
        askedUnlock = false;
    }

    /** The tab, unlocked: the saved copy at once; the phone is asked in the background once the tab is shown. */
    private void begin() {
        openStorage();
        TextsCache.Saved<List<Sms.Thread>> saved = prefs.keepCopy() && cache != null ? cache.loadThreads() : null;
        if (saved != null) { // the saved copy at once; the phone is asked in the background
            applyNames(saved.value);
            threads.clear();
            threads.addAll(saved.value);
            fromCopy = true;
            copyAt = saved.at;
            ensureListUi();
            renderList();
            arrange();
        } else {
            showMessage("Texts", "Checking the phone…", null, null, null, null);
        }
    }

    @Override
    boolean secure() {
        return new TextsPrefs(act).secureWindow();
    }

    private void openStorage() {
        if (local != null && cache != null && storageEpoch == TextsVault.epoch()) {
            return;
        }
        storageEpoch = TextsVault.epoch();
        TextsVault.sweepOpened(act, 60 * 60_000L);
        BlobStore store = null;
        SecretKey key = TextsVault.key();
        if (key != null) {
            try {
                store = new SealedBlobStore(new FileBlobStore(TextsVault.dir(act)), new AesGcmSealer(key));
            } catch (IOException ignored) {
                // no folder to write in
            }
        }
        memoryOnly = store == null;
        if (store == null) { // nothing can be saved in a sealed form: nothing is written; the choices last until the app closes
            store = new BlobStore() {
                private final java.util.Map<String, byte[]> m = new java.util.HashMap<>();

                @Override
                public synchronized byte[] read(String name) {
                    return m.get(name);
                }

                @Override
                public synchronized void write(String name, byte[] data) {
                    m.put(name, data);
                }

                @Override
                public synchronized void delete(String name) {
                    m.remove(name);
                }

                @Override
                public synchronized List<String> names() {
                    return new ArrayList<>(m.keySet());
                }
            };
        }
        blobs = store;
        local = new TextsLocal(blobs);
        cache = new TextsCache(blobs, System::currentTimeMillis, 30, 80);
    }

    // ------------------------------------------------------------ the Texts lock

    /** Forget that the screen lock was passed (the next visit asks again). For the pictures made by tools/uipreview. */
    static void relock() {
        LOCK.relock();
    }

    private boolean mustUnlock() {
        return prefs != null && LOCK.needsUnlock(prefs.lock(), TextsVault.deviceSecure(act), SystemClock.elapsedRealtime());
    }

    /** The lock screen. {@code cannotAsk}: the tablet did not show its screen-lock question; the way out is to switch the lock off. */
    private void showLocked(boolean cannotAsk) {
        messageMode = true;
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        int pad = Ui.dp(act, 24);
        col.setPadding(pad, pad, pad, pad * 2);
        IconButton lockIcon = new IconButton(act, Icons.Kind.LOCK, Ui.ACCENT, 0x264C8DFF, "Locked", null);
        lockIcon.setScale(0.42f);
        col.addView(lockIcon, new LinearLayout.LayoutParams(Ui.dp(act, 92), Ui.dp(act, 92)));
        TextView head = Ui.text(act, "Texts are locked", 22, Ui.FG, true);
        head.setGravity(Gravity.CENTER);
        col.addView(head, Ui.lp(act, 18));
        TextView t = Ui.text(act, cannotAsk
                ? "This tablet did not show its screen-lock question, so LinkMeter cannot check that it is you."
                : "Unlock with this tablet's screen lock to see your texts. Nobody can read them here until you do.", 15, Ui.DIM, false);
        t.setGravity(Gravity.CENTER);
        col.addView(t, Ui.lp(act, 10));
        col.addView(Ui.primary(act, cannotAsk ? "Try again" : "Unlock", v -> askUnlock()), Ui.lp(act, 18));
        if (cannotAsk) {
            col.addView(Ui.button(act, "Switch the Texts lock off", v -> {
                prefs.setLock(false);
                unlockedNow();
            }), Ui.lp(act, 8));
        }
        root.removeAllViews();
        root.addView(Ui.scroll(act, col), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Puts the tablet's own screen-lock question. A Cancel leaves the lock screen up with its Unlock button. */
    private void askUnlock() {
        boolean asked = act.confirmDeviceCredential("Texts", "Unlock to see your texts.", passed -> {
            if (passed && locked) {
                unlockedNow();
            }
        });
        if (!asked && locked) {
            showLocked(true);
        }
    }

    private void unlockedNow() {
        LOCK.unlocked(SystemClock.elapsedRealtime());
        locked = false;
        restart();
    }

    /** The screen from the start: the saved copy if there is one, then the phone is asked. */
    private void restart() {
        resetScreen();
        root.removeAllViews();
        threads.clear();
        begin();
        if (shown) {
            startWork();
        }
    }

    /** Everything on screen goes and the lock screen takes its place (the tab was away longer than the lock allows). */
    private void lockNow() {
        TextsViews.closeAll();
        hideKeyboard();
        if (open != null) {
            open.pause();
        }
        resetScreen();
        hideUndoNow();
        threads.clear();
        items = new ArrayList<>();
        rows.clear();
        hits.clear();
        act.backHandler = null;
        act.setTabBarVisible(true);
        locked = true;
        showLocked(false);
    }

    @Override
    void onShow() {
        shown = true;
        if (!locked && mustUnlock()) {
            lockNow();
        }
        if (locked) {
            if (!askedUnlock) {
                askedUnlock = true;
                askUnlock();
            }
            return;
        }
        if (open != null) {
            open.resume(); // paused while the app was away (the screen went off, a photo was picked): it asks the phone what changed
        }
        startWork();
    }

    private void startWork() {
        loadState();
        act.ui.removeCallbacks(tick);
        act.ui.postDelayed(tick, 30_000);
    }

    @Override
    void onHide() {
        shown = false;
        LOCK.left(SystemClock.elapsedRealtime());
        session++;
        pollGen++;
        act.ui.removeCallbacks(tick);
        act.backHandler = null;
        act.setTabBarVisible(true);
        hideKeyboard();
        if (local != null) {
            local.flush();
        }
        if (open != null) {
            open.pause();
        }
    }

    // ------------------------------------------------------------ the number on the Texts button while another tab is in front

    private volatile long watchGen;
    private boolean watching;

    /**
     * While the app is in front on another tab, the Texts button in the bottom bar shows how many new texts there are: the phone is
     * asked to say when something changes (the same waiting request this tab uses, so the phone does next to nothing meanwhile), and
     * only then is the list fetched and counted the way the list counts (what was opened on this tablet is read). Nothing runs when the
     * app is in the background, and nothing at all when texts are switched off on the phone or not allowed there.
     */
    @Override
    void watchWhileHidden(boolean on) {
        if (on == watching) {
            return;
        }
        watching = on;
        final long gen = ++watchGen;
        if (!on) {
            return;
        }
        Thread t = new Thread(() -> watchLoop(gen), "linkmeter-texts-badge");
        t.setDaemon(true);
        t.start();
    }

    /** How long the watch waits before it asks again after the phone could not be reached (it doubles up to a minute). */
    static final long WATCH_RETRY_MS = 5_000;

    private void watchLoop(final long gen) {
        String since = "";
        boolean v2Phone = false;
        boolean checked = false;
        long backoff = WATCH_RETRY_MS;
        while (gen == watchGen) {
            try {
                RemoteSms r = remote();
                if (!checked) {
                    Sms.State st = r.state();
                    if (!st.supported || !st.on || !st.read) {
                        post(() -> {
                            if (gen == watchGen) {
                                act.setBadge(TEXTS, 0);
                            }
                        });
                        return; // nothing to count until texts are set up (the Texts tab says what is missing)
                    }
                    v2Phone = st.version >= 2;
                    checked = true;
                }
                String now = v2Phone && !since.isEmpty() ? r.stamp(since, POLL_WAIT_S) : r.stamp();
                if (gen != watchGen) {
                    return;
                }
                boolean changed = !now.equals(since);
                if (changed) {
                    since = now;
                    final List<Sms.Thread> l = r.threads(100);
                    post(() -> {
                        if (gen == watchGen) {
                            badgeFromWatch(l);
                        }
                    });
                }
                backoff = WATCH_RETRY_MS;
                if (!v2Phone) {
                    Thread.sleep(30_000); // an older phone cannot hold the request: a look now and then is enough for a number
                } else if (!changed) {
                    Thread.sleep(150);
                }
            } catch (RemoteSms.Off e) {
                post(() -> {
                    if (gen == watchGen) {
                        act.setBadge(TEXTS, 0);
                    }
                });
                return;
            } catch (IOException e) {
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    return;
                }
                backoff = Math.min(backoff * 2, 60_000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** The watch fetched the list: the number on the button, and the saved copy (so that the tab opens with it). */
    private void badgeFromWatch(final List<Sms.Thread> l) {
        if (prefs == null) {
            prefs = new TextsPrefs(act);
        }
        openStorage();
        act.setBadge(TEXTS, ThreadListRules.badge(l, local));
        final TextsCache c = cache();
        if (c != null) {
            out.execute(() -> c.saveThreads(l));
        }
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null && root != null) {
            im.hideSoftInputFromWindow(root.getWindowToken(), 0);
        }
    }

    // ------------------------------------------------------------ what the phone says it is ready for

    private void showMessage(String head, String text, String b1, Runnable r1, String b2, Runnable r2) {
        messageMode = true;
        open = null;
        openKey = "";
        pollGen++;
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
            col.addView(Ui.primary(act, b1, v -> r1.run()), Ui.lp(act, 16));
        }
        if (b2 != null) {
            col.addView(Ui.button(act, b2, v -> r2.run()), Ui.lp(act, 8));
        }
        if (root != null) {
            root.removeAllViews();
            root.addView(Ui.scroll(act, col), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private void loadState() {
        final long my = session;
        net.execute(() -> {
            Sms.State st = null;
            String err = null;
            try {
                st = remote().state();
            } catch (IOException e) {
                err = explain(e);
            }
            final Sms.State fs = st;
            final String fe = err;
            post(() -> {
                if (my == session && shown) {
                    applyState(fs, fe);
                }
            });
        });
    }

    private void applyState(Sms.State st, String err) {
        state = st;
        if (st == null) {
            if (!threads.isEmpty() && listPane != null) { // the saved copy stays readable
                setProblem(err == null ? "The phone could not be reached." : err);
                return;
            }
            showMessage("Texts", err == null ? "The phone could not be reached." : err, "Try again", this::loadState, null, null);
        } else if (!st.supported) {
            showMessage("Update the phone", new RemoteSms.TooOld().getMessage(), "Check again", this::loadState, null, null);
        } else if (!st.on) {
            wipeSavedCopy(); // the phone's owner switched texts off: nothing of them stays here
            showMessage("Texts are switched off", new RemoteSms.Off().getMessage()
                    + "\n\nYour texts stay private until you do. Only this paired tablet can ask for them.", "Check again", this::loadState, null, null);
        } else if (!st.read || !st.send) {
            showMessage("The phone needs permission", st.note.isEmpty() ? "The phone has not allowed LinkMeter to read and send texts yet." : st.note,
                    "Ask the phone to allow it", this::grant, "Check again", this::loadState);
        } else {
            v2 = st.version >= 2;
            problem = "";
            ensureListUi();
            if (messageMode) {
                messageMode = false;
                arrange();
            }
            loadThreads();
            startPolling();
        }
    }

    private void wipeSavedCopy() {
        threads.clear();
        items = new ArrayList<>();
        fromCopy = false;
        act.setBadge(TEXTS, 0);
        if (cache != null) {
            cache.clear();
        }
        if (local != null) {
            local.forgetAll();
        }
        listPane = null;
    }

    private void grant() {
        showMessage("Texts", "Asking the phone to allow texts (it uses Shizuku)…", null, null, null, null);
        final long my = session;
        out.execute(() -> {
            Sms.State st = null;
            String err = null;
            try {
                st = remote().grant();
            } catch (IOException e) {
                err = e.getMessage();
            }
            final Sms.State fs = st;
            final String fe = err;
            post(() -> {
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

    // ------------------------------------------------------------ keeping up with the phone

    /** The newest list request, and the newest answer that was used: a slow older answer must not replace a newer one. */
    private long listGen;
    private long appliedListGen;

    private void loadThreads() {
        final long my = session;
        final long gen = ++listGen;
        net.execute(() -> {
            List<Sms.Thread> l = null;
            IOException err = null;
            try {
                l = remote().threads(100);
                TextsCache c = cache();
                if (c != null) {
                    c.saveThreads(l);
                }
            } catch (IOException e) {
                err = e;
            }
            final List<Sms.Thread> fl = l;
            final IOException fe = err;
            post(() -> {
                if (my != session || !shown) {
                    return;
                }
                if (fl != null) {
                    if (gen < appliedListGen) {
                        return; // a newer answer was shown already
                    }
                    appliedListGen = gen;
                    applyNames(fl);
                    threads.clear();
                    threads.addAll(fl);
                    fromCopy = false;
                    problem = "";
                    updatedAt = System.currentTimeMillis();
                    renderList();
                } else if (fe instanceof RemoteSms.Off) {
                    loadState(); // switched off since: say so
                } else if (fe != null) {
                    setProblem(explain(fe));
                }
            });
        });
    }

    private void setProblem(String text) {
        problem = text == null || text.isEmpty() ? "The phone could not be reached." : text;
        renderSubtitle();
        renderBanner();
        if (open != null) {
            open.showStatus(problem);
        }
    }

    /** Asks the phone to say when anything changes, for as long as this tab is on screen. */
    private void startPolling() {
        final long gen = ++pollGen;
        Thread t = new Thread(() -> {
            String since = "";
            long backoff = 2000;
            int failures = 0;
            while (gen == pollGen) {
                try {
                    RemoteSms r = remote();
                    String now = v2 && !since.isEmpty() ? r.stamp(since, POLL_WAIT_S) : r.stamp();
                    if (gen != pollGen) {
                        return;
                    }
                    final boolean first = since.isEmpty();
                    final boolean changed = !now.equals(since);
                    since = now;
                    backoff = 2000;
                    failures = 0;
                    if (changed || !problem.isEmpty()) {
                        post(() -> {
                            if (gen == pollGen) {
                                onNews(first, changed);
                            }
                        });
                    }
                    if (!v2) {
                        Thread.sleep(3000); // an older phone cannot hold the request: ask every 3 seconds
                    } else if (!changed) {
                        Thread.sleep(150); // a phone that answers a waiting request at once must not be hammered
                    }
                } catch (RemoteSms.Off e) {
                    post(() -> {
                        if (gen == pollGen) {
                            loadState();
                        }
                    });
                    return;
                } catch (IOException e) {
                    failures++;
                    final String why = explain(e);
                    if (failures >= 2) { // one miss is a hiccup (a phone that was busy for a moment): ask again quietly first
                        post(() -> {
                            if (gen == pollGen) {
                                setProblem(why);
                            }
                        });
                    }
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        return;
                    }
                    backoff = Math.min(backoff * 2, 20_000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "linkmeter-texts-poll");
        t.setDaemon(true);
        t.start();
    }

    private void onNews(boolean first, boolean changed) {
        if (!problem.isEmpty()) {
            problem = "";
            renderSubtitle();
            renderBanner();
            if (open != null) {
                open.showStatus(null);
            }
        }
        if (!changed || first) {
            return; // the first answer is the starting point: the list was asked for already
        }
        loadThreads();
        if (open != null) {
            open.reload();
        }
        if (searching && query.length() >= 2) {
            searchPhone(query);
        }
    }

    // ------------------------------------------------------------ the layout: list, conversation, undo

    private void arrange() {
        if (root == null) {
            return;
        }
        if (listPane == null) {
            return;
        }
        root.removeAllViews();
        detach(listPane);
        detach(detail);
        detach(undoBar);
        if (wide) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(listPane, new LinearLayout.LayoutParams(Ui.dp(act, LIST_DP), ViewGroup.LayoutParams.MATCH_PARENT));
            View div = new View(act);
            div.setBackgroundColor(Ui.LINE);
            row.addView(div, new LinearLayout.LayoutParams(Ui.dp(act, 1), ViewGroup.LayoutParams.MATCH_PARENT));
            row.addView(detail, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            root.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            root.addView(detailShown ? detail : listPane,
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        FrameLayout.LayoutParams up = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        up.bottomMargin = Ui.dp(act, 84);
        root.addView(undoBar, up);
        act.setTabBarVisible(!composing);
        updateBackHandler();
    }

    private static void detach(View v) {
        if (v != null && v.getParent() instanceof ViewGroup) {
            ((ViewGroup) v.getParent()).removeView(v);
        }
    }

    private void updateBackHandler() {
        boolean needed = selecting || searching || detailShown || (wide && open != null);
        act.backHandler = needed ? this::onBack : null;
    }

    private void onBack() {
        if (selecting) {
            endSelecting();
        } else if (searching && !detailShown) {
            closeSearch();
        } else if (detailShown || open != null) {
            closeDetail();
        } else {
            act.backHandler = null;
            act.onBackPressed();
        }
    }

    // ------------------------------------------------------------ the list

    private void ensureListUi() {
        if (listPane != null) {
            return;
        }
        int pad = Ui.dp(act, 8);
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.BG);

        barHolder = new FrameLayout(act);
        barHolder.setBackgroundColor(Ui.CARD);
        col.addView(barHolder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 60)));
        buildBars();

        banner = Ui.text(act, "", 13, Ui.AMBER, false);
        banner.setBackgroundColor(Ui.CARD);
        banner.setPadding(Ui.dp(act, 16), pad, Ui.dp(act, 16), pad);
        banner.setVisibility(View.GONE);
        LinearLayout bannerRow = new LinearLayout(act);
        bannerRow.setOrientation(LinearLayout.HORIZONTAL);
        bannerRow.setGravity(Gravity.CENTER_VERTICAL);
        bannerRow.setBackgroundColor(Ui.CARD);
        bannerRow.addView(banner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bannerButton = Ui.quiet(act, "Try again", v -> retryNow());
        bannerButton.setVisibility(View.GONE);
        bannerRow.addView(bannerButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(bannerRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout body = new FrameLayout(act);
        listView = new ListView(act);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setSelector(android.R.color.transparent);
        adapter = new RowAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((p, v, i, id) -> onRowClick(i));
        listView.setOnItemLongClickListener((p, v, i, id) -> onRowLong(i));
        body.addView(listView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyView = new LinearLayout(act);
        emptyView.setOrientation(LinearLayout.VERTICAL);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(Ui.dp(act, 32), 0, Ui.dp(act, 32), Ui.dp(act, 80));
        IconButton big = new IconButton(act, Icons.Kind.CHAT, Ui.DIM, 0, "", null);
        big.setScale(0.8f);
        emptyView.addView(big, new LinearLayout.LayoutParams(Ui.dp(act, 72), Ui.dp(act, 72)));
        emptyTitle = Ui.text(act, "No texts yet", 18, Ui.FG, true);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyView.addView(emptyTitle, Ui.lp(act, 10));
        emptyText = Ui.text(act, "", 14, Ui.DIM, false);
        emptyText.setGravity(Gravity.CENTER);
        emptyView.addView(emptyText, Ui.lp(act, 6));
        emptyView.setVisibility(View.GONE);
        body.addView(emptyView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        col.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView note = Ui.text(act, "Shows texts and picture messages. Chats that use RCS (chat features in Google Messages) are not shown.",
                11, Ui.DIM, false);
        note.setPadding(Ui.dp(act, 16), Ui.dp(act, 4), Ui.dp(act, 16), Ui.dp(act, 6));
        col.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        listPane = new FrameLayout(act);
        listPane.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fab = new IconButton(act, Icons.Kind.PLUS, Ui.ON_ACCENT, Ui.ACCENT, "New message", v -> showNew());
        fab.setScale(0.46f);
        fab.setElevation(Ui.dp(act, 6));
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(Ui.dp(act, 58), Ui.dp(act, 58), Gravity.BOTTOM | Gravity.END);
        fp.rightMargin = Ui.dp(act, 18);
        fp.bottomMargin = Ui.dp(act, 34);
        listPane.addView(fab, fp);

        detail = new FrameLayout(act);
        detail.setBackgroundColor(Ui.BG);
        showPlaceholder();
        showBar(normalBar);
    }

    private void showPlaceholder() {
        detail.removeAllViews();
        LinearLayout ph = new LinearLayout(act);
        ph.setOrientation(LinearLayout.VERTICAL);
        ph.setGravity(Gravity.CENTER);
        IconButton big = new IconButton(act, Icons.Kind.CHAT, Ui.LINE, 0, "", null);
        big.setScale(0.8f);
        ph.addView(big, new LinearLayout.LayoutParams(Ui.dp(act, 96), Ui.dp(act, 96)));
        TextView t = Ui.text(act, "Pick a conversation", 16, Ui.DIM, false);
        t.setGravity(Gravity.CENTER);
        ph.addView(t, Ui.lp(act, 8));
        detail.addView(ph, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void buildBars() {
        int pad = Ui.dp(act, 14);
        // ---- normal: title and subtitle, search, more
        normalBar = new LinearLayout(act);
        normalBar.setOrientation(LinearLayout.HORIZONTAL);
        normalBar.setGravity(Gravity.CENTER_VERTICAL);
        normalBar.setPadding(pad, 0, Ui.dp(act, 6), 0);
        LinearLayout titles = new LinearLayout(act);
        titles.setOrientation(LinearLayout.VERTICAL);
        titleView = Ui.text(act, "Texts", 21, Ui.FG, true);
        subtitleView = Ui.text(act, "", 12, Ui.DIM, false);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(titleView);
        titles.addView(subtitleView);
        normalBar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        normalBar.addView(TextsViews.icon(act, Icons.Kind.SEARCH, "Search", v -> openSearch()));
        normalBar.addView(TextsViews.icon(act, Icons.Kind.MORE, "More", v -> showMenu()));

        // ---- searching
        searchBar = new LinearLayout(act);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setPadding(Ui.dp(act, 6), 0, Ui.dp(act, 6), 0);
        searchBar.addView(TextsViews.icon(act, Icons.Kind.BACK, "Close search", v -> closeSearch()));
        searchInput = new EditText(act);
        searchInput.setHint("Search names, numbers and texts");
        searchInput.setHintTextColor(Ui.DIM);
        searchInput.setTextColor(Ui.FG);
        searchInput.setTextSize(16);
        searchInput.setSingleLine(true);
        searchInput.setBackground(null);
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.addTextChangedListener(new TextWatcher() {
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
                onQuery(s.toString());
            }
        });
        searchBar.addView(searchInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        searchBar.addView(TextsViews.icon(act, Icons.Kind.CLOSE, "Clear", v -> searchInput.setText("")));

        // ---- selecting
        selectBar = new LinearLayout(act);
        selectBar.setOrientation(LinearLayout.HORIZONTAL);
        selectBar.setGravity(Gravity.CENTER_VERTICAL);
        selectBar.setPadding(Ui.dp(act, 6), 0, Ui.dp(act, 6), 0);
        selectBar.addView(TextsViews.icon(act, Icons.Kind.CLOSE, "Stop selecting", v -> endSelecting()));
        selectCount = Ui.text(act, "", 18, Ui.FG, true);
        selectBar.addView(selectCount, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        selectBar.addView(TextsViews.icon(act, Icons.Kind.PIN, "Pin", v -> pinSelected()));
        selectBar.addView(TextsViews.icon(act, Icons.Kind.CHECK, "Mark read", v -> readSelected()));
        selectBar.addView(TextsViews.icon(act, Icons.Kind.ARCHIVE, "Archive", v -> archiveSelected()));
        selectBar.addView(TextsViews.icon(act, Icons.Kind.TRASH, "Delete", v -> deleteSelected()));
    }

    private void showBar(View bar) {
        barHolder.removeAllViews();
        barHolder.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fab.setVisibility(bar == normalBar ? View.VISIBLE : View.GONE);
    }

    private static String ago(long then, long now) {
        long s = Math.max(0, (now - then) / 1000);
        if (s < 15) {
            return "just now";
        }
        if (s < 60) {
            return s + " s ago";
        }
        if (s < 3600) {
            return (s / 60) + " min ago";
        }
        return TextTime.clock(then, TextTime.Env.device(true));
    }

    private void renderSubtitle() {
        if (subtitleView == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String s;
        int color = Ui.DIM;
        if (!problem.isEmpty()) {
            String head = problem.startsWith(SLOW) ? "Phone is slow to answer" : "Can't reach the phone";
            s = threads.isEmpty() ? head : head + " · saved copy from " + ago(fromCopy ? copyAt : updatedAt, now);
            color = Ui.AMBER;
        } else if (fromCopy) {
            s = "Updating…";
        } else if (updatedAt > 0) {
            s = (archivedView ? "Archived · " : unreadOnly ? "Unread · " : "") + "Updated " + ago(updatedAt, now);
        } else {
            s = "";
        }
        subtitleView.setText(s);
        subtitleView.setTextColor(color);
        subtitleView.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void renderBanner() {
        if (banner == null) {
            return;
        }
        boolean show = !problem.isEmpty();
        banner.setText(show ? problem : "");
        banner.setVisibility(show ? View.VISIBLE : View.GONE);
        bannerButton.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void retryNow() {
        loadState();
    }

    /** Works out what the list shows from the phone's conversations, the tablet's choices and the search, and draws it. */
    private void renderList() {
        if (listPane == null || adapter == null) {
            return;
        }
        items = ThreadListRules.build(threads, local, searching ? query : "", unreadOnly, archivedView);
        rows.clear();
        if (searching && !hits.isEmpty() && !items.isEmpty()) {
            rows.add("Conversations");
        }
        rows.addAll(items);
        if (searching && !hits.isEmpty()) {
            rows.add("Texts");
            rows.addAll(hits);
        }
        // a selection only holds conversations that are still there
        Set<String> alive = new LinkedHashSet<>();
        for (ThreadListRules.Item i : items) {
            alive.add(i.t.key);
        }
        selected.retainAll(alive);
        if (selecting && selected.isEmpty()) {
            selecting = false;
            showBar(searching ? searchBar : normalBar);
        }
        adapter.notifyDataSetChanged();
        boolean empty = rows.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            if (searching && query.trim().length() > 0) {
                emptyTitle.setText("Nothing found");
                emptyText.setText("No conversation or text matches “" + query.trim() + "”.");
            } else if (archivedView) {
                emptyTitle.setText("Nothing archived");
                emptyText.setText("Conversations you archive wait here. A new text brings one back to the list.");
            } else if (unreadOnly) {
                emptyTitle.setText("All read");
                emptyText.setText("No conversation has news.");
            } else if (fromCopy) {
                emptyTitle.setText("Nothing saved yet");
                emptyText.setText("The phone's texts appear here once it has answered.");
            } else {
                emptyTitle.setText("No texts yet");
                emptyText.setText("Tap + to write one.");
            }
        }
        if (selecting) {
            selectCount.setText(selected.size() + " selected");
        }
        renderSubtitle();
        renderBanner();
        act.setBadge(TEXTS, ThreadListRules.badge(threads, local)); // the number on the Texts button follows the list
        askForWords();
    }

    // ------------------------------------------------------------ the newest line of every row

    /** Conversations whose newest message this tablet knows itself, by key ({@link ThreadListRules.Known}). */
    private final java.util.Map<String, ThreadListRules.Known> known = new java.util.HashMap<>();
    /** Conversations whose newest messages were asked for, with the list's time then: each is asked once per new message. */
    private final java.util.Map<String, Long> askedWords = new java.util.HashMap<>();
    private boolean askingWords;

    /** A conversation's newest message as this tablet has it (open here, or just sent from here): its row says it at once. */
    void noteNewest(String key, Sms.Msg m) {
        if (m == null) {
            return;
        }
        ThreadListRules.Known k = known.get(key);
        if (k == null || m.time >= k.time) {
            known.put(key, ThreadListRules.Known.of(m));
        }
    }

    /**
     * The phone's list gave no words for some conversations (an older phone app, a picture message): their newest messages are asked
     * for, a few at a time and one after the other (the conversation you open does not wait for them), so that every row shows its last
     * message as the messaging apps do.
     */
    private void askForWords() {
        if (askingWords || fromCopy || searching) {
            return;
        }
        final List<Sms.Thread> want = new ArrayList<>();
        for (int i = 0; i < items.size() && i < 40 && want.size() < 6; i++) {
            Sms.Thread t = items.get(i).t;
            Long at = askedWords.get(t.key);
            if (ThreadListRules.needsWords(t, known.get(t.key)) && (at == null || at < t.time)) {
                want.add(t);
                askedWords.put(t.key, t.time);
            }
        }
        if (want.isEmpty()) {
            return;
        }
        askingWords = true;
        final long my = session;
        net.execute(() -> {
            final java.util.Map<String, Sms.Msg> got = new java.util.HashMap<>();
            final List<String> notAsked = new ArrayList<>();
            boolean down = false;
            for (Sms.Thread t : want) {
                if (down) {
                    notAsked.add(t.key);
                    continue;
                }
                try {
                    Sms.Convo c = remote().thread(t.key, 3);
                    if (!c.msgs.isEmpty()) {
                        got.put(t.key, c.msgs.get(c.msgs.size() - 1));
                    }
                } catch (IOException e) {
                    down = true; // the phone is not answering: the rest are asked at the next change
                    notAsked.add(t.key);
                }
            }
            post(() -> {
                askingWords = false;
                for (String k : notAsked) {
                    askedWords.remove(k);
                }
                if (my != session) {
                    return;
                }
                for (java.util.Map.Entry<String, Sms.Msg> e : got.entrySet()) {
                    noteNewest(e.getKey(), e.getValue());
                }
                if (!got.isEmpty()) {
                    renderList();
                }
            });
        });
    }

    // ------------------------------------------------------------ the rows

    Sms.Thread threadOf(String key) {
        for (Sms.Thread t : threads) {
            if (t.key.equals(key)) {
                return t;
            }
        }
        return null;
    }

    /** How many messages the phone says the conversation holds (0 when the list does not have it). */
    int threadCount(String key) {
        Sms.Thread t = threadOf(key);
        return t == null ? 0 : t.count;
    }

    /** Does the phone call the conversation unread? */
    boolean threadUnread(String key) {
        Sms.Thread t = threadOf(key);
        return t != null && t.unread;
    }

    /** The tablet's choices changed (a pin, a delete, a hidden message): draw the list again. */
    void refreshList() {
        renderList();
    }

    boolean isWide() {
        return wide;
    }

    /** Starts a new message with this text in the box (a forwarded message). */
    void forward(String text) {
        forwardText = text;
        showNew();
    }

    private void onRowClick(int i) {
        if (i < 0 || i >= rows.size()) {
            return;
        }
        Object o = rows.get(i);
        if (o instanceof ThreadListRules.Item) {
            Sms.Thread t = ((ThreadListRules.Item) o).t;
            if (selecting) {
                toggle(t.key);
            } else {
                openThread(t.key, ThreadListRules.title(t), t.addresses);
            }
        } else if (o instanceof Sms.Hit) {
            Sms.Hit h = (Sms.Hit) o;
            Sms.Thread t = threadOf(h.msg.key);
            List<String> addrs = new ArrayList<>();
            if (t != null) {
                addrs.addAll(t.addresses);
            } else if (!h.msg.address.isEmpty()) {
                addrs.add(h.msg.address);
            }
            openThread(h.msg.key, h.name, addrs);
        }
    }

    private boolean onRowLong(int i) {
        if (i < 0 || i >= rows.size() || !(rows.get(i) instanceof ThreadListRules.Item)) {
            return false;
        }
        if (!selecting) {
            startSelecting();
        }
        toggle(((ThreadListRules.Item) rows.get(i)).t.key);
        return true;
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
            return 3;
        }

        @Override
        public int getItemViewType(int i) {
            Object o = rows.get(i);
            return o instanceof ThreadListRules.Item ? 0 : o instanceof Sms.Hit ? 1 : 2;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Object o = rows.get(i);
            if (o instanceof String) {
                TextView h = convert instanceof TextView ? (TextView) convert : Ui.text(act, "", 12, Ui.ACCENT, true);
                h.setPadding(Ui.dp(act, 16), Ui.dp(act, 14), Ui.dp(act, 16), Ui.dp(act, 6));
                h.setText(((String) o).toUpperCase(java.util.Locale.ROOT));
                return h;
            }
            Holder h;
            View row;
            if (convert == null) {
                h = new Holder(act);
                row = h.row;
                row.setTag(h);
            } else {
                row = convert;
                h = (Holder) row.getTag();
            }
            if (o instanceof ThreadListRules.Item) {
                h.bind((ThreadListRules.Item) o);
            } else {
                h.bindHit((Sms.Hit) o);
            }
            return row;
        }
    }

    /** One line of the list, built once and filled again for each conversation. */
    private final class Holder {
        final LinearLayout row;
        final TextView avatar;
        final TextView name;
        final TextView preview;
        final TextView time;
        final TextView badge;
        final TextView copyCode;
        final IconButton pinned;
        final IconButton muted;

        Holder(Context c) {
            row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(c, 14), Ui.dp(c, 9), Ui.dp(c, 14), Ui.dp(c, 9));
            avatar = TextsViews.avatar(c, 48);
            row.addView(avatar, new LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48)));
            LinearLayout mid = new LinearLayout(c);
            mid.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            mp.leftMargin = Ui.dp(c, 14);
            // first line: the name, what is set for it, and when
            LinearLayout nameRow = new LinearLayout(c);
            nameRow.setOrientation(LinearLayout.HORIZONTAL);
            nameRow.setGravity(Gravity.CENTER_VERTICAL);
            name = Ui.text(c, "", 16, Ui.FG, true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            nameRow.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            pinned = new IconButton(c, Icons.Kind.PIN, Ui.DIM, 0, "Pinned", null);
            pinned.setScale(0.9f);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(Ui.dp(c, 14), Ui.dp(c, 14));
            pp.leftMargin = Ui.dp(c, 6);
            nameRow.addView(pinned, pp);
            muted = new IconButton(c, Icons.Kind.MUTE, Ui.DIM, 0, "Muted", null);
            muted.setScale(0.9f);
            LinearLayout.LayoutParams mu = new LinearLayout.LayoutParams(Ui.dp(c, 14), Ui.dp(c, 14));
            mu.leftMargin = Ui.dp(c, 4);
            nameRow.addView(muted, mu);
            time = Ui.text(c, "", 12, Ui.DIM, false);
            time.setSingleLine(true);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.leftMargin = Ui.dp(c, 8);
            nameRow.addView(time, tp);
            mid.addView(nameRow);
            // second line: the last text, and the unread mark
            LinearLayout previewRow = new LinearLayout(c);
            previewRow.setOrientation(LinearLayout.HORIZONTAL);
            previewRow.setGravity(Gravity.CENTER_VERTICAL);
            preview = Ui.text(c, "", 14, Ui.DIM, false);
            preview.setSingleLine(true);
            preview.setEllipsize(TextUtils.TruncateAt.END);
            previewRow.addView(preview, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            copyCode = TextsViews.pill(c);
            LinearLayout.LayoutParams cc = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cc.leftMargin = Ui.dp(c, 8);
            previewRow.addView(copyCode, cc);
            badge = TextsViews.badge(c);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(c, 20));
            bp.leftMargin = Ui.dp(c, 8);
            previewRow.addView(badge, bp);
            mid.addView(previewRow);
            row.addView(mid, mp);
        }

        void bind(ThreadListRules.Item it) {
            Sms.Thread t = it.t;
            boolean sel = selected.contains(t.key);
            boolean current = wide && t.key.equals(openKey);
            row.setBackgroundColor(sel ? Ui.CARD_HI : current ? Ui.CARD : 0);
            TextsViews.bindAvatar(avatar, ThreadListRules.title(t), t.key, sel);
            name.setText(ThreadListRules.title(t));
            name.setTypeface(null, it.unread ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            preview.setText(ThreadListRules.preview(t, prefs.hidePreviews(), known.get(t.key)));
            preview.setTextColor(it.unread ? Ui.FG : Ui.DIM);
            time.setText(TextTime.stamp(t.time, System.currentTimeMillis(), env()));
            time.setTextColor(it.unread && !it.muted ? Ui.ACCENT : Ui.DIM);
            pinned.setVisibility(it.pinned ? View.VISIBLE : View.GONE);
            muted.setVisibility(it.muted ? View.VISIBLE : View.GONE);
            TextsViews.bindBadge(badge, it.unread && !it.muted ? it.newCount : -1);
            badge.setBackground(TextsViews.shape(Ui.ACCENT, Ui.dp(act, 10)));
            final String code = OtpCodes.forList(t.snippet, it.unread, t.time, System.currentTimeMillis());
            if (code.isEmpty()) {
                copyCode.setVisibility(View.GONE);
            } else {
                copyCode.setVisibility(View.VISIBLE);
                copyCode.setOnClickListener(v -> TextsViews.copyCode(act, code));
            }
        }

        void bindHit(Sms.Hit hit) {
            row.setBackgroundColor(0);
            TextsViews.bindAvatar(avatar, hit.name, hit.msg.key, false);
            name.setText(hit.name);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            preview.setText((hit.msg.mine ? "You: " : "") + hit.msg.body.replaceAll("\\s+", " ").trim());
            preview.setTextColor(Ui.DIM);
            time.setText(TextTime.stamp(hit.msg.time, System.currentTimeMillis(), env()));
            time.setTextColor(Ui.DIM);
            pinned.setVisibility(View.GONE);
            muted.setVisibility(View.GONE);
            copyCode.setVisibility(View.GONE);
            TextsViews.bindBadge(badge, -1);
        }
    }

    // ------------------------------------------------------------ selecting several conversations

    private void startSelecting() {
        selecting = true;
        selected.clear();
        showBar(selectBar);
        updateBackHandler();
    }

    private void endSelecting() {
        selecting = false;
        selected.clear();
        showBar(searching ? searchBar : normalBar);
        updateBackHandler();
        renderList();
    }

    private void toggle(String key) {
        if (!selected.remove(key)) {
            selected.add(key);
        }
        if (selected.isEmpty()) {
            endSelecting();
        } else {
            renderList();
        }
    }

    private List<Sms.Thread> selectedThreads() {
        List<Sms.Thread> out = new ArrayList<>();
        for (String k : selected) {
            Sms.Thread t = threadOf(k);
            if (t != null) {
                out.add(t);
            }
        }
        return out;
    }

    private void pinSelected() {
        boolean allPinned = true;
        for (Sms.Thread t : selectedThreads()) {
            allPinned &= local.isPinned(t.key);
        }
        long now = System.currentTimeMillis();
        for (Sms.Thread t : selectedThreads()) {
            local.setPinned(t.key, !allPinned, now);
        }
        endSelecting();
    }

    private void readSelected() {
        List<String> keys = new ArrayList<>();
        for (Sms.Thread t : selectedThreads()) {
            local.markSeen(t.key, t.time, t.count);
            keys.add(t.key);
        }
        askPhoneRead(keys);
        endSelecting();
    }

    private void askPhoneRead(final List<String> keys) {
        if (!v2 || readRefused || keys.isEmpty()) {
            return;
        }
        out.execute(() -> {
            try {
                Sms.DeleteResult r = remote().markRead(keys);
                if (r.doneKeys.isEmpty()) {
                    readRefused = true;
                }
            } catch (IOException ignored) {
                // marked read here anyway
            }
        });
    }

    private void archiveSelected() {
        final List<Sms.Thread> list = selectedThreads();
        for (Sms.Thread t : list) {
            local.archive(t.key, t.time);
        }
        endSelecting();
        showUndo(list.size() == 1 ? "Conversation archived" : list.size() + " conversations archived", () -> {
            for (Sms.Thread t : list) {
                local.unarchive(t.key);
            }
            renderList();
        });
    }

    private void deleteSelected() {
        final List<Sms.Thread> list = selectedThreads();
        if (list.isEmpty()) {
            return;
        }
        TextsViews.show(new AlertDialog.Builder(act)
                .setTitle(list.size() == 1 ? "Delete this conversation?" : "Delete " + list.size() + " conversations?")
                .setMessage("It is removed from this tablet. LinkMeter also asks the phone to delete it, but a phone usually lets only its "
                        + "own Messages app do that; if it refuses, the texts stay on the phone and only disappear here.")
                .setPositiveButton("Delete", (d, w) -> deleteConversations(list))
                .setNegativeButton("Cancel", null));
    }

    /** Hides the conversations here at once, then asks the phone to delete them and says what it did. */
    void deleteConversations(final List<Sms.Thread> list) {
        final List<String> keys = new ArrayList<>();
        final List<Long> before = new ArrayList<>();
        for (Sms.Thread t : list) {
            keys.add(t.key);
            before.add(local.clear(t.key, t.time));
        }
        if (open != null && keys.contains(openKey)) {
            closeDetail();
        }
        endSelecting();
        renderList();
        if (!v2) {
            showUndo("Removed from this tablet. This phone's app is too old to delete texts on the phone.", () -> undoClear(list, before));
            return;
        }
        final long my = session;
        out.execute(() -> {
            Sms.DeleteResult r = null;
            try {
                r = remote().delete(new ArrayList<String>(), keys);
            } catch (IOException ignored) {
                // the phone could not be asked: removed here only
            }
            final Sms.DeleteResult fr = r;
            post(() -> {
                if (my != session) {
                    return;
                }
                if (fr != null && fr.failedKeys.isEmpty()) {
                    showUndo("Deleted on the phone and on this tablet", null);
                } else {
                    showUndo("Removed from this tablet. The phone kept " + (fr == null ? "them (it could not be asked)" : "them: " + fr.why), () -> undoClear(list, before));
                }
                loadThreads();
            });
        });
    }

    private void undoClear(List<Sms.Thread> list, List<Long> before) {
        for (int i = 0; i < list.size(); i++) {
            local.setCleared(list.get(i).key, before.get(i));
        }
        renderList();
    }

    // ------------------------------------------------------------ search

    private void openSearch() {
        searching = true;
        hits.clear();
        query = "";
        showBar(searchBar);
        searchInput.setText("");
        searchInput.requestFocus();
        InputMethodManager im = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) {
            im.showSoftInput(searchInput, 0);
        }
        updateBackHandler();
        renderList();
    }

    private void closeSearch() {
        searching = false;
        query = "";
        hits.clear();
        searchGen++;
        hideKeyboard();
        showBar(selecting ? selectBar : normalBar);
        updateBackHandler();
        renderList();
    }

    private void onQuery(String q) {
        if (!searching) {
            return;
        }
        query = q.trim();
        if (query.length() < 2) {
            hits.clear();
            searchGen++;
        }
        renderList();
        if (searchTask != null) {
            act.ui.removeCallbacks(searchTask);
        }
        if (query.length() >= 2 && v2) {
            final String qq = query;
            searchTask = () -> searchPhone(qq);
            act.ui.postDelayed(searchTask, 350);
        }
    }

    private void searchPhone(final String q) {
        final long gen = ++searchGen;
        final long my = session;
        net.execute(() -> {
            List<Sms.Hit> found = new ArrayList<>();
            try {
                found = remote().search(q, 30);
            } catch (IOException ignored) {
                // the conversations that match still show
            }
            final List<Sms.Hit> ff = found;
            post(() -> {
                if (my == session && gen == searchGen && searching && q.equals(query)) {
                    hits.clear();
                    hits.addAll(ff);
                    renderList();
                }
            });
        });
    }

    // ------------------------------------------------------------ the menu and the settings

    private void showMenu() {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        labels.add("Select conversations");
        acts.add(() -> {
            startSelecting();
            Toast.makeText(act, "Tap the conversations to select.", Toast.LENGTH_SHORT).show();
        });
        labels.add(unreadOnly ? "Show all conversations" : "Show unread only");
        acts.add(() -> {
            unreadOnly = !unreadOnly;
            archivedView = false;
            renderList();
        });
        int archived = ThreadListRules.archivedCount(threads, local);
        labels.add(archivedView ? "Back to all conversations" : "Archived (" + archived + ")");
        acts.add(() -> {
            archivedView = !archivedView;
            unreadOnly = false;
            renderList();
        });
        labels.add("Mark all as read");
        acts.add(this::readAll);
        labels.add("Refresh now");
        acts.add(this::retryNow);
        labels.add("Contacts");
        acts.add(this::showContacts);
        labels.add("Connection details");
        acts.add(this::showConnectionDetails);
        labels.add("Texts settings");
        acts.add(this::showSettings);
        TextsViews.menu(act, null, labels.toArray(new String[0]), acts.toArray(new Runnable[0]));
    }

    private void readAll() {
        List<String> keys = new ArrayList<>();
        for (ThreadListRules.Item i : items) {
            if (i.unread) {
                local.markSeen(i.t.key, i.t.time, i.t.count);
                keys.add(i.t.key);
            }
        }
        askPhoneRead(keys);
        renderList();
    }

    private void showSettings() {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(act, 20), Ui.dp(act, 8), Ui.dp(act, 20), Ui.dp(act, 8));
        col.addView(new Ui.Toggle(act, "Keep a saved copy on this tablet", "The tab opens at once and still shows your texts when the phone is out of reach. "
                + (memoryOnly
                ? "This tablet's Keystore does not work, so nothing is written to the tablet: the copy lasts until the app is closed."
                : "The copy is encrypted with a key that stays inside this tablet's Keystore. Switch off to keep nothing here."),
                prefs.keepCopy(), (b, on) -> {
            prefs.setKeepCopy(on);
            if (!on && cache != null) {
                cache.clear();
            }
        }).view);
        col.addView(new Ui.Toggle(act, "Hide message previews", "The list says “New message” instead of showing the words.", prefs.hidePreviews(),
                (b, on) -> {
                    prefs.setHidePreviews(on);
                    renderList();
                }).view, Ui.lp(act, 6));
        col.addView(new Ui.Toggle(act, "Lock Texts with the screen lock", TextsVault.deviceSecure(act)
                ? "Asks for this tablet's screen lock (PIN, pattern, password or fingerprint) when you open Texts after being away for more than "
                + TextsLock.GRACE_MS / 60_000L + " minutes."
                : "This tablet has no screen lock yet. Set a PIN, pattern or password in Android's Settings first.",
                prefs.lock(), (b, on) -> setLockOption(b, on)).view, Ui.lp(act, 6));
        col.addView(new Ui.Toggle(act, "Hide Texts from screenshots", "No screenshots or screen recordings of the Texts tab, and a blank picture of it "
                + "in the recent apps list.", prefs.secureWindow(), (b, on) -> {
            prefs.setSecureWindow(on);
            act.applySecure();
        }).view, Ui.lp(act, 6));
        col.addView(Ui.hint(act, "Texts reach this tablet from the phone over the phone's own hotspot. Ordinary texts (SMS and MMS) are not end-to-end "
                + "encrypted by any app: the phone company can read them. RCS chats stay inside Google Messages."), Ui.lp(act, 12));
        col.addView(Ui.button(act, "Clear the saved copy now", v -> {
            if (cache != null) {
                cache.clear();
            }
            Toast.makeText(act, "The saved copy was cleared.", Toast.LENGTH_SHORT).show();
        }), Ui.lp(act, 12));
        col.addView(Ui.button(act, "Forget my pins, archive and drafts", v -> TextsViews.show(new AlertDialog.Builder(act)
                .setTitle("Forget them?")
                .setMessage("Pinned, muted and archived conversations, drafts, and the conversations you deleted on this tablet come back as the phone has them.")
                .setPositiveButton("Forget", (d, w) -> {
                    local.forgetChoices();
                    renderList();
                }).setNegativeButton("Cancel", null))), Ui.lp(act, 8));
        col.addView(Ui.danger(act, "Erase everything saved here", v -> TextsViews.show(new AlertDialog.Builder(act)
                .setTitle("Erase everything saved here?")
                .setMessage("The saved copy of your texts, your pins, archive, drafts and hidden conversations, and the key that protects them are "
                        + "erased from this tablet. Nothing is deleted on the phone; the texts load again from it.")
                .setPositiveButton("Erase", (d, w) -> eraseSaved()).setNegativeButton("Cancel", null))), Ui.lp(act, 8));
        TextsViews.show(new AlertDialog.Builder(act).setTitle("Texts settings").setView(Ui.scroll(act, col)).setPositiveButton("Done", null));
    }

    /** What the tablet asked of the phone and how long each request took: it tells "slow" from "cannot be reached". Never holds a text. */
    private void showConnectionDetails() {
        final String text = LOG.summary(System.currentTimeMillis());
        TextView t = Ui.text(act, text, 12.5f, Ui.FG, false);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(act, 20), Ui.dp(act, 8), Ui.dp(act, 20), Ui.dp(act, 8));
        col.addView(t);
        TextsViews.show(new AlertDialog.Builder(act).setTitle("Connection details").setView(Ui.scroll(act, col))
                .setNeutralButton("Copy", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("LinkMeter connection details", text));
                        Toast.makeText(act, "Copied", Toast.LENGTH_SHORT).show();
                    }
                }).setPositiveButton("Close", null));
    }

    /** The lock switch: it needs a screen lock to ask for, and it asks the next time Texts is opened after being away. */
    private void setLockOption(CompoundButton b, boolean on) {
        if (!on) {
            prefs.setLock(false);
            return;
        }
        if (!TextsVault.deviceSecure(act)) {
            b.setChecked(false);
            Toast.makeText(act, "This tablet has no screen lock yet. Set a PIN, pattern or password in Android's Settings, then switch this on.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        prefs.setLock(true);
        LOCK.unlocked(SystemClock.elapsedRealtime()); // you are here now: it asks after you have been away
    }

    /** Erases the saved copy, the tablet's choices and the key; the texts are then loaded from the phone again. */
    private void eraseSaved() {
        TextsVault.wipe(act);
        local = null;
        cache = null;
        blobs = null;
        Toast.makeText(act, "Everything saved here was erased.", Toast.LENGTH_SHORT).show();
        restart();
    }

    // ------------------------------------------------------------ contacts: names this tablet gives, saved on the phone when it lets us

    /** The phone's own name for each conversation is kept apart, so that this tablet's names can be put over it (and taken off again). */
    private void applyNames(List<Sms.Thread> l) {
        if (local == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Sms.Thread t : l) {
            if (t.key.indexOf(',') >= 0) {
                continue; // a group has no contact name
            }
            phoneNames.put(t.key, t.name);
            local.settle(t.key, t.name);
            t.name = local.display(t.key, t.name, now);
        }
    }

    /** The name to show for a person, from what the phone calls them ({@code phoneName}; "" when this does not know). */
    String shownName(String key, String phoneName) {
        if (local == null || key.indexOf(',') >= 0) {
            return phoneName == null ? "" : phoneName;
        }
        String base = phoneName;
        if (base == null || base.isEmpty()) {
            base = phoneNames.get(key);
        } else {
            phoneNames.put(key, base);
        }
        return local.display(key, base == null ? "" : base, System.currentTimeMillis());
    }

    /** Does this person have a name (this tablet's, or the phone's contact name rather than their number)? */
    boolean hasContactName(String key, String shown) {
        return (local != null && !local.nameOf(key).isEmpty()) || (shown != null && !shown.isEmpty() && !PhoneNumbers.looksLikeNumber(shown));
    }

    /** The names changed (a contact was saved, renamed or given up): the list, the open conversation and the Contacts screen show them. */
    private void refreshNames() {
        if (local != null) {
            long now = System.currentTimeMillis();
            for (Sms.Thread t : threads) {
                if (t.key.indexOf(',') < 0) {
                    String base = phoneNames.get(t.key);
                    t.name = local.display(t.key, base == null ? t.name : base, now);
                }
            }
        }
        renderList();
        if (open != null) {
            open.setDisplayName(shownName(openKey, ""));
        }
        if (contactsView != null) {
            renderContacts();
        }
    }

    private int contactPhoneMode() {
        if (state == null || state.version < 3) {
            return ContactDialog.PHONE_CANNOT;
        }
        return state.contactsWrite ? ContactDialog.PHONE_READY : ContactDialog.PHONE_NEEDS_PERMISSION;
    }

    private String contactPhoneNote() {
        switch (contactPhoneMode()) {
            case ContactDialog.PHONE_READY:
                return "The contact is saved in the phone's contacts too, so it is there on the phone as well.";
            case ContactDialog.PHONE_NEEDS_PERMISSION:
                return "The phone has not allowed LinkMeter to change its contacts yet. LinkMeter will ask the phone to allow it (this needs Shizuku on the phone).";
            default:
                return state == null ? "The phone cannot be reached now, so this is saved on this tablet only."
                        : "The phone's LinkMeter app is too old to save contacts: install the newest release on the phone. Until then this is saved on this tablet only.";
        }
    }

    /** Asks for the name of a person who has a conversation: a new contact when they have none yet, a rename when they have. */
    void editContact(final String key, final String number, final String shown, final boolean named) {
        if (local == null) {
            return;
        }
        final boolean mine = !local.nameOf(key).isEmpty();
        ContactDialog.show(act, named ? "Rename contact" : "Add to contacts", named ? shown : "", number, false, contactPhoneMode(), contactPhoneNote(),
                mine, (n, num, onPhone) -> saveContact(num, n, onPhone), () -> {
                    local.forgetName(key);
                    refreshNames();
                    Toast.makeText(act, "Using the phone's name.", Toast.LENGTH_SHORT).show();
                });
    }

    private void newContact() {
        ContactDialog.show(act, "New contact", "", "", true, contactPhoneMode(), contactPhoneNote(), false, (n, num, onPhone) -> saveContact(num, n, onPhone), null);
    }

    /**
     * Saves a contact: on this tablet at once (it shows everywhere straight away), then on the phone when asked to and the phone lets
     * LinkMeter (if the phone only lacks the permission, it is asked to allow it and the save is tried once more). Says plainly where it ended up.
     */
    void saveContact(final String number, final String name, final boolean alsoPhone) {
        final String sendNumber = PhoneNumbers.forSending(number);
        if (local == null || sendNumber.isEmpty() || name.trim().isEmpty()) {
            Toast.makeText(act, "Not saved: it needs a name and a phone number.", Toast.LENGTH_SHORT).show();
            return;
        }
        final String key = PhoneNumbers.threadKey(java.util.Collections.singletonList(sendNumber));
        local.setName(key, number, name, 0);
        refreshNames();
        if (!alsoPhone) {
            Toast.makeText(act, "Saved on this tablet only.", Toast.LENGTH_SHORT).show();
            return;
        }
        out.execute(() -> {
            Sms.ContactResult r = null;
            String err = null;
            try {
                r = remote().saveContact(name, sendNumber);
                if (!r.ok && r.needPermission) {
                    post(() -> Toast.makeText(act, "Asking the phone to allow it…", Toast.LENGTH_SHORT).show());
                    remote().grant();
                    r = remote().saveContact(name, sendNumber);
                }
            } catch (IOException e) {
                err = explain(e);
            }
            final Sms.ContactResult fr = r;
            final String fe = err;
            post(() -> {
                if (fr != null && fr.ok) {
                    local.setName(key, number, name, System.currentTimeMillis());
                    if (state != null) {
                        state.contactsWrite = true;
                    }
                    refreshNames();
                    Toast.makeText(act, (fr.how.equals("renamed") ? "Renamed" : "Saved") + " on the phone too" + (fr.where.isEmpty() ? "." : " (" + fr.where + ")."),
                            Toast.LENGTH_LONG).show();
                } else {
                    String why = fr != null ? fr.why : fe;
                    Toast.makeText(act, "Saved on this tablet only. " + (why == null ? "" : why), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    // ---- the Contacts screen

    private static final class ContactEntry {
        final String key;
        final String name;
        final String number;
        /** On the phone's list of contacts (not only a name this tablet gave). */
        final boolean onPhone;
        /** This tablet has its own name for them. */
        final boolean tabletName;
        /** The name lives on this tablet only. */
        final boolean tabletOnly;

        ContactEntry(String key, String name, String number, boolean onPhone, boolean tabletName, boolean tabletOnly) {
            this.key = key;
            this.name = name;
            this.number = number;
            this.onPhone = onPhone;
            this.tabletName = tabletName;
            this.tabletOnly = tabletOnly;
        }
    }

    private ListView contactsView;
    private BaseAdapter contactsAdapter;
    private final List<ContactEntry> contactRows = new ArrayList<>();
    private List<Sms.Person> phonePeople = new ArrayList<>();
    private boolean contactsLoaded;
    private String contactsProblem = "";
    private String contactsQuery = "";
    private TextView contactsStatus;

    private void showContacts() {
        if (open != null) {
            open.pause();
        }
        open = null;
        openKey = "";
        contactsQuery = "";
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(act, 14);
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(Ui.CARD);
        head.setPadding(Ui.dp(act, 6), 0, Ui.dp(act, 6), 0);
        head.addView(TextsViews.icon(act, Icons.Kind.BACK, "Back", v -> closeContacts()));
        head.addView(Ui.text(act, "Contacts", 20, Ui.FG, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(TextsViews.icon(act, Icons.Kind.PLUS, "New contact", v -> newContact()));
        col.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 60)));

        EditText find = new EditText(act);
        find.setHint("Search contacts");
        find.setHintTextColor(Ui.DIM);
        find.setTextColor(Ui.FG);
        find.setTextSize(16);
        find.setSingleLine(true);
        find.setInputType(InputType.TYPE_CLASS_TEXT);
        find.setBackground(TextsViews.shape(Ui.CARD_HI, Ui.dp(act, 12)));
        find.setPadding(Ui.dp(act, 14), Ui.dp(act, 10), Ui.dp(act, 14), Ui.dp(act, 10));
        find.addTextChangedListener(new TextWatcher() {
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
                contactsQuery = s.toString().trim();
                renderContacts();
            }
        });
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.setMargins(pad, Ui.dp(act, 10), pad, Ui.dp(act, 4));
        col.addView(find, fp);
        contactsStatus = Ui.text(act, "", 12, Ui.DIM, false);
        contactsStatus.setPadding(pad, Ui.dp(act, 2), pad, Ui.dp(act, 4));
        col.addView(contactsStatus);

        contactsView = new ListView(act);
        contactsView.setDivider(null);
        contactsView.setBackgroundColor(Ui.BG);
        contactsView.setPadding(pad, 0, pad, Ui.dp(act, 8));
        contactsView.setClipToPadding(false);
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
            public View getView(int i, View convert, ViewGroup parent) {
                final ContactEntry e = contactRows.get(i);
                return personRow(e.name, PhoneNumbers.pretty(e.number) + (e.tabletOnly ? "  ·  on this tablet only" : ""), null);
            }
        };
        contactsView.setAdapter(contactsAdapter);
        contactsView.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < contactRows.size()) {
                contactMenu(contactRows.get(pos));
            }
        });
        col.addView(contactsView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        detail.removeAllViews();
        detail.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        detailShown = true;
        arrange();
        renderContacts();
        loadContacts();
    }

    private void closeContacts() {
        contactsView = null;
        contactsAdapter = null;
        contactsStatus = null;
        closeDetail();
    }

    private void loadContacts() {
        final long my = session;
        net.execute(() -> {
            List<Sms.Person> found = null;
            String err = "";
            try {
                found = remote().contacts("", 1000);
            } catch (IOException e) {
                err = explain(e);
            }
            final List<Sms.Person> ff = found;
            final String fe = err;
            post(() -> {
                if (my != session || contactsView == null) {
                    return;
                }
                if (ff != null) {
                    phonePeople = ff;
                    contactsLoaded = true;
                    contactsProblem = "";
                } else {
                    contactsProblem = fe;
                }
                renderContacts();
            });
        });
    }

    /** The phone's contacts with this tablet's own names over them, and the names only this tablet has; by name, narrowed by the search. */
    private void renderContacts() {
        if (contactsView == null || local == null) {
            return;
        }
        long now = System.currentTimeMillis();
        java.util.Map<String, ContactEntry> byKey = new java.util.LinkedHashMap<>();
        for (Sms.Person p : phonePeople) {
            String key = PhoneNumbers.key(p.number);
            if (key.isEmpty() || byKey.containsKey(key)) {
                continue;
            }
            byKey.put(key, new ContactEntry(key, local.display(key, p.name, now), p.number, true, !local.nameOf(key).isEmpty(), local.nameIsLocal(key)));
        }
        for (String key : local.nameKeys()) {
            if (!byKey.containsKey(key)) {
                String number = local.numberOf(key).isEmpty() ? key : local.numberOf(key);
                byKey.put(key, new ContactEntry(key, local.nameOf(key), number, false, true, local.nameIsLocal(key)));
            }
        }
        String q = contactsQuery.toLowerCase(java.util.Locale.ROOT);
        String qd = contactsQuery.replaceAll("[^0-9]", "");
        contactRows.clear();
        for (ContactEntry e : byKey.values()) {
            if (q.isEmpty() || e.name.toLowerCase(java.util.Locale.ROOT).contains(q) || (qd.length() >= 3 && e.key.contains(qd))) {
                contactRows.add(e);
            }
        }
        java.util.Collections.sort(contactRows, (a, b) -> a.name.compareToIgnoreCase(b.name));
        contactsAdapter.notifyDataSetChanged();
        String status;
        if (!contactsProblem.isEmpty()) {
            status = (contactsLoaded || !contactRows.isEmpty() ? "Showing what was loaded before. " : "") + contactsProblem;
        } else if (!contactsLoaded) {
            status = "Loading the phone's contacts…";
        } else if (state != null && state.version < 3 && phonePeople.size() >= 20) { // an older phone answers 20 at most
            status = "Showing 20 contacts. Install the newest release on the phone to list all of them and to save contacts there.";
        } else {
            status = contactRows.size() + (contactRows.size() == 1 ? " contact" : " contacts");
        }
        contactsStatus.setText(status);
        contactsStatus.setTextColor(contactsProblem.isEmpty() ? Ui.DIM : Ui.AMBER);
    }

    private void contactMenu(final ContactEntry e) {
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        labels.add("Send a text");
        acts.add(() -> {
            Sms.Person p = new Sms.Person();
            p.name = e.name;
            p.number = PhoneNumbers.forSending(e.number);
            picked.clear();
            picked.add(p);
            contactsView = null;
            startNew();
        });
        labels.add("Rename");
        acts.add(() -> editContact(e.key, e.number, e.name, true));
        if (e.tabletOnly) {
            labels.add("Save on the phone too");
            acts.add(() -> saveContact(e.number, e.name, true));
        }
        if (e.tabletName) {
            labels.add(e.onPhone ? "Use the phone's name" : "Forget this contact");
            acts.add(() -> {
                local.forgetName(e.key);
                refreshNames();
            });
        }
        TextsViews.menu(act, e.name, labels.toArray(new String[0]), acts.toArray(new Runnable[0]));
    }

    // ------------------------------------------------------------ the undo strip

    private LinearLayout buildUndoBar() {
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(TextsViews.shape(0xF2323843, Ui.dp(act, 12)));
        bar.setPadding(Ui.dp(act, 16), Ui.dp(act, 4), Ui.dp(act, 6), Ui.dp(act, 4));
        bar.setElevation(Ui.dp(act, 8));
        undoText = Ui.text(act, "", 14, Ui.FG, false);
        undoText.setMaxWidth(Ui.dp(act, 420));
        bar.addView(undoText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        undoButton = Ui.quiet(act, "Undo", v -> {
            Runnable a = undoAction;
            hideUndoNow();
            if (a != null) {
                a.run();
            }
        });
        LinearLayout.LayoutParams ub = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ub.leftMargin = Ui.dp(act, 10);
        bar.addView(undoButton, ub);
        bar.setVisibility(View.GONE);
        return bar;
    }

    /** A message at the bottom for a few seconds, with Undo when there is something to undo. */
    void showUndo(String text, Runnable undo) {
        if (undoBar == null) {
            return;
        }
        undoText.setText(text);
        undoAction = undo;
        undoButton.setVisibility(undo == null ? View.GONE : View.VISIBLE);
        undoBar.setVisibility(View.VISIBLE);
        if (hideUndo != null) {
            act.ui.removeCallbacks(hideUndo);
        }
        hideUndo = this::hideUndoNow;
        act.ui.postDelayed(hideUndo, undo == null ? 4000 : 8000);
    }

    private void hideUndoNow() {
        if (undoBar != null) {
            undoBar.setVisibility(View.GONE);
        }
        undoAction = null;
    }

    // ------------------------------------------------------------ one conversation

    void openThread(String key, String name, List<String> addresses) {
        if (composing) {
            composing = false;
        }
        if (open != null) {
            open.pause();
        }
        open = new TextThread(this, key, name, addresses);
        openKey = key;
        detail.removeAllViews();
        detail.addView(open.build(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (forwardText != null) {
            open.prefill(forwardText);
            forwardText = null;
        }
        detailShown = true;
        arrange();
        open.reload();
        renderList();
    }

    /** The conversation was closed (back, or deleted): the list again, or the empty pane beside it. */
    void closeThread() {
        closeDetail();
    }

    private void closeDetail() {
        contactsView = null; // the Contacts screen, if that is what was open, is gone
        hideKeyboard();
        if (open != null) {
            open.pause();
        }
        open = null;
        openKey = "";
        composing = false;
        detailShown = false;
        showPlaceholder();
        arrange();
        renderList();
    }

    /** The compose box has the keyboard: the tab bar gets out of the way. */
    void composeFocus(boolean on) {
        composing = on;
        act.setTabBarVisible(!on);
    }

    // ------------------------------------------------------------ a new message

    private void showNew() {
        if (open != null) {
            open.pause();
        }
        open = null;
        openKey = "";
        picked.clear();
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(act, 14);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(Ui.CARD);
        head.setPadding(Ui.dp(act, 6), 0, pad, 0);
        head.addView(TextsViews.icon(act, Icons.Kind.BACK, "Back", v -> closeDetail()));
        TextView t = Ui.text(act, "New message", 20, Ui.FG, true);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nextBtn = Ui.primary(act, "Next", v -> startNew());
        nextBtn.setEnabled(false);
        head.addView(nextBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 60)));

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
        toInput.setOnFocusChangeListener((v, focus) -> composeFocus(focus));
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
                searchPeople(s.toString().trim());
            }
        });
        body.addView(toInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        suggestBox = new LinearLayout(act);
        suggestBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(suggestBox, Ui.lp(act, 4));
        col.addView(Ui.scroll(act, body), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        detail.removeAllViews();
        detail.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        detailShown = true;
        arrange();
        showPicked();
        searchPeople("");
    }

    private void searchPeople(final String q) {
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
            post(() -> {
                if (my == session && toInput != null && toInput.getText().toString().trim().equals(q)) {
                    showSuggestions(q, ff);
                }
            });
        });
        act.ui.postDelayed(searchTask, q.isEmpty() ? 0 : 250);
    }

    private View personRow(final String name, final String number, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(act, 2), Ui.dp(act, 8), Ui.dp(act, 2), Ui.dp(act, 8));
        TextView av = TextsViews.avatar(act, 40);
        TextsViews.bindAvatar(av, name, number.isEmpty() ? name : PhoneNumbers.key(number), false);
        row.addView(av, new LinearLayout.LayoutParams(Ui.dp(act, 40), Ui.dp(act, 40)));
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(act, name, 16, Ui.FG, true));
        if (!number.isEmpty()) {
            col.addView(Ui.text(act, number, 13, Ui.DIM, false));
        }
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.leftMargin = Ui.dp(act, 12);
        row.addView(col, cp);
        row.setOnClickListener(l);
        return row;
    }

    private void showSuggestions(String q, List<Sms.Person> people) {
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
        for (final Sms.Person p : people) {
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
            row.addView(TextsViews.icon(act, Icons.Kind.CLOSE, "Remove", v -> {
                picked.remove(p);
                showPicked();
            }));
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
                openThread(t.key, ThreadListRules.title(t), t.addresses);
                return;
            }
        }
        hideKeyboard();
        openThread(key, name.toString(), addresses);
    }
}
