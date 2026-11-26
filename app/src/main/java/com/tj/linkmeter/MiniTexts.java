package com.tj.linkmeter;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tablet only: what the floating phone's small texting card knows and does (docs/FLOATING_PHONE.md). It asks the phone through the same
 * {@link RemoteSms} routes as the Texts tab: the list of conversations, the newest messages of one conversation, contacts for the
 * recipient box, and Send. It listens for news at the phone (the waiting stamp request) only while the card is up, so the phone and the
 * tablet are quiet otherwise. What is shown is decided by {@link MiniTextRules}; {@link MiniTextView} draws the state this holds and calls
 * back into it. Everything here is used on the screen's thread; the requests run on threads of their own and come back through {@link #ui}.
 */
final class MiniTexts {
    enum Mode { LIST, THREAD, NEW }

    /** The state changed: draw it again. */
    interface Sink {
        void changed();
    }

    private final Context ctx;
    private final Sink sink;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor(r -> daemon(r, "linkmeter-minitexts"));
    private final ExecutorService outbox = Executors.newSingleThreadExecutor(r -> daemon(r, "linkmeter-minitexts-send"));

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    // ---- the state the card draws
    Mode mode = Mode.LIST;
    final List<Sms.Thread> threads = new ArrayList<>();
    boolean threadsReady;
    String threadsProblem = "";
    String key = "";
    String name = "";
    final List<String> to = new ArrayList<>();
    /** What the phone sent of the open conversation, oldest first. */
    private final List<Sms.Msg> loaded = new ArrayList<>();
    /** My texts that are on their way (or did not go). */
    private final List<Sms.Msg> temps = new ArrayList<>();
    boolean threadReady;
    String threadProblem = "";
    List<Sms.Person> people = new ArrayList<>();
    boolean peopleReady;
    String toTyped = "";
    List<MiniTextRules.Pick> picks = new ArrayList<>();

    private int threadsGen;
    private int threadGen;
    private boolean peopleAsked;
    private volatile int watchGen;
    private volatile boolean watching;

    MiniTexts(Context c, Sink s) {
        ctx = c.getApplicationContext();
        sink = s;
    }

    private void changed() {
        sink.changed();
    }

    /** The messages to draw: the phone's, then mine that are on their way. */
    List<Sms.Msg> messages() {
        return MiniTextRules.merge(loaded, temps);
    }

    /** How many conversations have news. */
    int unread() {
        int n = 0;
        for (Sms.Thread t : threads) {
            n += t.unread ? 1 : 0;
        }
        return n;
    }

    // ------------------------------------------------------------------ going from screen to screen

    /** The card was opened: at the list, or at the conversation with this number when one is given. */
    void open(String number) {
        String send = MiniTextRules.recipient(number);
        if (number == null || number.isEmpty()) {
            showList();
        } else if (send.isEmpty()) {
            showNew();
            setToTyped(number);
        } else {
            showThread(PhoneNumbers.key(send), nameOf(send), Collections.singletonList(send));
        }
    }

    void showList() {
        mode = Mode.LIST;
        loadThreads();
        changed();
    }

    void showNew() {
        mode = Mode.NEW;
        toTyped = "";
        picks = new ArrayList<>();
        loadPeople();
        changed();
    }

    void showThread(String threadKey, String who, List<String> addresses) {
        if (!threadKey.equals(key)) { // another conversation: nothing of the last one stays
            loaded.clear();
            temps.clear();
            threadReady = false;
            threadProblem = "";
        }
        key = threadKey;
        name = who == null ? "" : who;
        to.clear();
        to.addAll(addresses);
        mode = Mode.THREAD;
        loadThread();
        changed();
    }

    /** The conversation of a row of the list. */
    void openThread(Sms.Thread t) {
        showThread(t.key, MiniTextRules.title(t), t.addresses);
    }

    /** The recipient box changed: the people it offers follow. */
    void setToTyped(String typed) {
        toTyped = typed == null ? "" : typed;
        picks = MiniTextRules.suggestions(people, toTyped, 6);
        changed();
    }

    /** A person of the recipient box was chosen (or the typed number): their conversation opens. */
    void pick(MiniTextRules.Pick p) {
        showThread(PhoneNumbers.key(p.number), p.name, Collections.singletonList(p.number));
    }

    /** What a number is called here: the contact's name when the phone listed one, else the conversation's. */
    private String nameOf(String send) {
        String k = ContactBook.matchKey(send);
        for (Sms.Person p : people) {
            if (k.equals(ContactBook.matchKey(p.number)) && !p.name.trim().isEmpty()) {
                return p.name.trim();
            }
        }
        for (Sms.Thread t : threads) {
            if (k.equals(ContactBook.matchKey(t.key)) && !t.name.trim().isEmpty() && !PhoneNumbers.looksLikeNumber(t.name)) {
                return t.name.trim();
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ asking the phone

    private void loadThreads() {
        final int my = ++threadsGen;
        net.execute(() -> {
            List<Sms.Thread> got = null;
            String bad = "";
            try {
                got = TextsTab.remoteFor(ctx).threads(MiniTextRules.THREADS);
            } catch (IOException e) {
                bad = TextsTab.explain(e);
            }
            final List<Sms.Thread> fg = got;
            final String fb = bad;
            ui.post(() -> {
                if (my != threadsGen) {
                    return;
                }
                if (fg != null) {
                    threads.clear();
                    threads.addAll(fg);
                    threadsReady = true;
                }
                threadsProblem = fb;
                changed();
            });
        });
    }

    private void loadThread() {
        final int my = ++threadGen;
        final String k = key;
        net.execute(() -> {
            Sms.Convo c = null;
            String bad = "";
            try {
                RemoteSms r = TextsTab.remoteFor(ctx);
                c = r.thread(k, MiniTextRules.SHOW);
                boolean news = false;
                for (Sms.Msg m : c.msgs) {
                    news |= m.unread;
                }
                if (news) {
                    try {
                        r.markRead(Collections.singletonList(k)); // looked at: the phone's own Messages app stops showing it as new
                    } catch (IOException ignored) {
                        // it stays new until the next look
                    }
                }
            } catch (IOException e) {
                bad = TextsTab.explain(e);
            }
            final Sms.Convo fc = c;
            final String fb = bad;
            ui.post(() -> {
                if (my != threadGen || !k.equals(key)) {
                    return;
                }
                if (fc != null) {
                    loaded.clear();
                    loaded.addAll(fc.msgs);
                    threadReady = true;
                    if (name.isEmpty() && !fc.head.name.trim().isEmpty() && !PhoneNumbers.looksLikeNumber(fc.head.name)) {
                        name = fc.head.name.trim();
                    }
                    if (to.isEmpty()) {
                        to.addAll(fc.head.addresses);
                    }
                    // what the phone has now replaces what was drawn at once
                    List<Sms.Msg> shown = MiniTextRules.merge(loaded, temps);
                    temps.retainAll(shown);
                }
                threadProblem = fb;
                changed();
            });
        });
    }

    private void loadPeople() {
        if (peopleAsked) {
            return;
        }
        peopleAsked = true;
        net.execute(() -> {
            List<Sms.Person> got = null;
            try {
                got = TextsTab.remoteFor(ctx).contacts("", 1000);
            } catch (IOException ignored) {
                // a number can still be typed
            }
            final List<Sms.Person> fg = got;
            ui.post(() -> {
                if (fg == null) {
                    peopleAsked = false; // asked again the next time the box opens
                    return;
                }
                people = fg;
                peopleReady = true;
                if (mode == Mode.NEW) {
                    picks = MiniTextRules.suggestions(people, toTyped, 6);
                    changed();
                }
            });
        });
    }

    // ------------------------------------------------------------------ sending

    /**
     * Sends {@code text} to the open conversation: it is in the conversation at once as "Sending…" and goes to the phone in the background,
     * in the order written. Returns false (and does nothing) when there is nothing to send or nobody to send it to.
     */
    boolean send(String text) {
        if (!MiniTextRules.canSend(text, to)) {
            return false;
        }
        final Sms.Msg temp = new Sms.Msg();
        temp.id = "t" + System.nanoTime();
        temp.key = key;
        temp.mine = true;
        temp.body = text.replaceAll("\\s+$", "");
        temp.time = System.currentTimeMillis();
        temp.state = Sms.STATE_SENDING;
        temps.add(temp);
        changed();
        final List<String> addresses = new ArrayList<>(to);
        final String k = key;
        outbox.execute(() -> {
            String error = null;
            boolean recorded = false;
            try {
                Sms.SendResult r = TextsTab.remoteFor(ctx).send(addresses, "", temp.body, new ArrayList<String>());
                recorded = r.ok || !r.id.isEmpty();
                if (!r.ok) {
                    error = r.error;
                }
            } catch (IOException e) {
                error = TextsTab.explain(e);
            }
            final String fe = error;
            final boolean rec = recorded;
            ui.post(() -> sent(temp, k, rec, fe));
        });
        return true;
    }

    private void sent(Sms.Msg temp, String k, boolean recorded, String error) {
        if (!recorded) {
            temp.state = Sms.STATE_FAILED;
            temp.error = error == null || error.isEmpty() ? "the phone could not be reached" : error;
            changed();
            return;
        }
        if (k.equals(key) && mode == Mode.THREAD) {
            loadThread(); // the phone has it now: its copy takes the bubble's place
        }
        loadThreads(); // the list's newest line follows
        changed();
    }

    // ------------------------------------------------------------------ listening for news

    /** The card is up: listen for news at the phone (a request that waits), and look at once. */
    void start() {
        if (watching) {
            return;
        }
        watching = true;
        final int my = ++watchGen;
        Thread t = new Thread(() -> watch(my), "linkmeter-minitexts-watch");
        t.setDaemon(true);
        t.start();
        if (mode == Mode.LIST) {
            loadThreads();
        } else if (mode == Mode.THREAD) {
            loadThread();
        }
    }

    /** The card is gone: the listening ends (the request that is held ends by itself within half a minute). */
    void stop() {
        watching = false;
        watchGen++;
    }

    /** The floating phone itself is gone: the helper threads end too. */
    void close() {
        stop();
        net.shutdown();
        outbox.shutdown();
    }

    private void watch(int my) {
        String since = "";
        boolean v2 = false;
        boolean checked = false;
        long backoff = 5_000;
        while (watching && my == watchGen) {
            try {
                RemoteSms r = TextsTab.remoteFor(ctx);
                if (!checked) {
                    Sms.State st = r.state();
                    if (!st.supported || !st.on || !st.read) {
                        final String why = !st.supported ? new RemoteSms.TooOld().getMessage() : !st.on ? new RemoteSms.Off().getMessage()
                                : "The phone has not allowed LinkMeter to read texts yet. " + st.note;
                        ui.post(() -> {
                            if (my == watchGen) {
                                threadsProblem = why;
                                threadProblem = why;
                                changed();
                            }
                        });
                        return;
                    }
                    v2 = st.version >= 2;
                    checked = true;
                }
                String now = v2 && !since.isEmpty() ? r.stamp(since, 20) : r.stamp();
                if (my != watchGen) {
                    return;
                }
                boolean news = !since.isEmpty() && !now.equals(since);
                since = now;
                backoff = 5_000;
                if (news) {
                    ui.post(() -> {
                        if (my == watchGen) {
                            loadThreads();
                            if (mode == Mode.THREAD) {
                                loadThread();
                            }
                        }
                    });
                }
                Thread.sleep(v2 ? (news ? 0 : 150) : 15_000);
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
}
