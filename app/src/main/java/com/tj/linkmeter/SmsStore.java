package com.tj.linkmeter;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.ContactsContract;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phone only: reads the texts and picture messages in the phone's own database (the same one the phone's Messages app uses; any app
 * with the READ_SMS permission may read it), and the contacts. Conversations are found through the "conversations" view, whose
 * recipients are numbers kept in the "canonical addresses" table. Every column is read by name and may be missing: phones differ.
 *
 * Speed: the database is watched (a ContentObserver), so "did anything change?" is a counter and not a scan; the conversation list
 * is scanned once per change and shared by the list, the header of a conversation and the search; the canonical addresses are kept;
 * the pictures and addresses of a page of picture messages are read with one query each, not one per message; queries ask the
 * database for only as many rows as are needed. Without a working observer everything still works, with a short-lived cache.
 */
final class SmsStore {
    private static final Uri SMS = Uri.parse("content://sms");
    private static final Uri MMS = Uri.parse("content://mms");
    private static final Uri THREADS = Uri.parse("content://mms-sms/conversations?simple=true");
    private static final Uri CANONICAL = Uri.parse("content://mms-sms/canonical-addresses");
    private static final Uri MMS_PART = Uri.parse("content://mms/part");
    private static final int MAX_PART_BYTES = 24 * 1024 * 1024;
    /** Observers can miss a change on some phones: the database is looked at anyway this often. */
    private static final long SAFETY_MS = 30_000;
    /** Without a working observer a scan is reused this long. */
    private static final long UNWATCHED_MS = 2_000;

    private static final String[] THREAD_COLS = {"_id", "date", "message_count", "recipient_ids", "snippet", "read", "has_attachment"};
    private static final String[] SMS_COLS = {"_id", "address", "body", "date", "type", "read", "status", "thread_id"};
    private static final String[] MMS_COLS = {"_id", "date", "msg_box", "m_type", "read", "sub", "thread_id"};
    private static final String[] PART_COLS = {"_id", "mid", "ct", "text", "name", "cl"};

    private final ContentResolver cr;
    private final Map<String, String> names = new LinkedHashMap<String, String>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 500;
        }
    };
    /** Goes up whenever a change is seen (or found by the safety look). */
    private final AtomicLong dirty = new AtomicLong(1);
    /** Guards the scan cache, the canonical addresses and {@link #idsByKey}. */
    private final Object scanLock = new Object();
    /** conversation key -> thread ids in the database (usually one) */
    private final Map<String, List<Long>> idsByKey = new HashMap<>();
    private Map<Long, String> canon = new HashMap<>();
    private List<Sms.Thread> cache;
    private int cacheMax;
    private long cacheDirty = -1;
    private long cacheAt;

    private final Object watchLock = new Object();
    private volatile boolean watching;
    private volatile Runnable listener;
    private Handler handler;
    private long lastSafety;
    private String lastScanStamp = "";
    private final Runnable notifier = new Runnable() {
        @Override
        public void run() {
            Runnable l = listener;
            if (l != null) {
                l.run();
            }
        }
    };

    SmsStore(Context c) {
        cr = c.getApplicationContext().getContentResolver();
    }

    // ------------------------------------------------------------ watching the database

    /** Who to tell when the phone's messages or contacts changed (the tablet's waiting request answers at once). */
    void setListener(Runnable r) {
        listener = r;
    }

    private void ensureWatching() {
        if (watching) {
            return;
        }
        synchronized (watchLock) {
            if (watching) {
                return;
            }
            try {
                if (handler == null) { // made once, even when the first tries fail (no permission yet)
                    HandlerThread t = new HandlerThread("linkmeter-sms-watch");
                    t.start();
                    handler = new Handler(t.getLooper());
                }
                ContentObserver texts = new ContentObserver(handler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        messagesChanged();
                    }

                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        messagesChanged();
                    }
                };
                cr.registerContentObserver(SMS, true, texts);
                cr.registerContentObserver(MMS, true, texts);
                cr.registerContentObserver(Uri.parse("content://mms-sms"), true, texts);
                ContentObserver people = new ContentObserver(handler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        synchronized (names) {
                            names.clear();
                        }
                        messagesChanged();
                    }
                };
                cr.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, people);
                watching = true;
            } catch (RuntimeException e) {
                watching = false; // no permission yet, or a phone that refuses: every request then looks at the database itself
            }
        }
    }

    /** A change was seen: the scans are stale, and (after a short pause, so that a burst makes one call) the listener is told. */
    private void messagesChanged() {
        dirty.incrementAndGet();
        Handler h = handler;
        if (h != null) {
            h.removeCallbacks(notifier);
            h.postDelayed(notifier, 250);
        }
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? "" : c.getString(i);
    }

    private static long lng(Cursor c, String col, long def) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? def : c.getLong(i);
    }

    /**
     * A query that asks the database for only {@code limit} rows (a LIMIT after the sort order, which the messages database takes) and
     * for only the columns needed. A phone that refuses either is asked the old way: every column, every row; the callers stop
     * reading at their own limit anyway.
     */
    private Cursor query(Uri uri, String[] cols, String sel, String[] args, String order, int limit) {
        try {
            return cr.query(uri, cols, sel, args, limit > 0 ? order + " LIMIT " + limit : order);
        } catch (RuntimeException e) {
            return cr.query(uri, null, sel, args, order);
        }
    }

    // ------------------------------------------------------------ names and contacts

    String nameFor(String address) {
        if (address == null || address.isEmpty()) {
            return "";
        }
        synchronized (names) {
            String cached = names.get(address);
            if (cached != null) {
                return cached;
            }
        }
        String name = "";
        try (Cursor c = cr.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
                new String[] {ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                name = c.getString(0);
            }
        } catch (RuntimeException e) {
            // no permission to read contacts, or no such number: no name
        }
        synchronized (names) {
            names.put(address, name);
        }
        return name;
    }

    private String namesOf(List<String> addresses) {
        StringBuilder sb = new StringBuilder();
        for (String a : addresses) {
            String n = nameFor(a);
            sb.append(sb.length() > 0 ? ", " : "").append(n.isEmpty() ? PhoneNumbers.pretty(a) : n);
        }
        return sb.toString();
    }

    List<Sms.Person> contacts(String query, int limit) {
        List<Sms.Person> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String sel = query.isEmpty() ? null : ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ? OR "
                + ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ?";
        String[] args = query.isEmpty() ? null : new String[] {"%" + query + "%", "%" + query + "%"};
        try (Cursor c = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[] {ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER}, sel, args,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC")) {
            while (c != null && c.moveToNext() && out.size() < limit) {
                String number = c.isNull(1) ? "" : c.getString(1);
                if (PhoneNumbers.forSending(number).isEmpty() || !seen.add(PhoneNumbers.key(number))) {
                    continue;
                }
                Sms.Person p = new Sms.Person();
                p.name = c.isNull(0) ? "" : c.getString(0);
                p.number = number;
                out.add(p);
            }
        } catch (RuntimeException e) {
            // no permission: no suggestions
        }
        return out;
    }

    // ------------------------------------------------------------ conversations

    /** The recipients' numbers by id; read again only when a conversation names an id that is not in it. Call with scanLock held. */
    private void loadCanonical() {
        Map<Long, String> m = new HashMap<>();
        try (Cursor c = cr.query(CANONICAL, null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                m.put(lng(c, "_id", -1), str(c, "address"));
            }
        } catch (RuntimeException e) {
            // unreadable: the conversations are then worked out from their messages
        }
        canon = m;
    }

    private String newestAddress(long threadId) {
        try (Cursor c = cr.query(SMS, new String[] {"address"}, "thread_id=?", new String[] {String.valueOf(threadId)}, "date DESC")) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getString(0);
            }
        } catch (RuntimeException e) {
            // nothing to go by
        }
        return "";
    }

    private List<Sms.Thread> copies(List<Sms.Thread> l, int max) {
        List<Sms.Thread> out = new ArrayList<>();
        for (Sms.Thread t : l) {
            if (out.size() >= max) {
                break;
            }
            out.add(t.copy());
        }
        return out;
    }

    /**
     * The conversations as the database has them, merged by key, newest first, names not filled in yet. The scan is kept until
     * something changes; {@code force} looks at the database whatever the cache says.
     */
    private List<Sms.Thread> rawThreads(int max, boolean force) {
        ensureWatching();
        synchronized (scanLock) {
            long now = SystemClock.elapsedRealtime();
            long ttl = watching ? SAFETY_MS : UNWATCHED_MS;
            if (!force && cache != null && cacheDirty == dirty.get() && cacheMax >= max && now - cacheAt < ttl) {
                return copies(cache, max);
            }
            long seenDirty = dirty.get(); // a change during the scan makes the result stale at once
            boolean reloaded = canon.isEmpty();
            if (reloaded) {
                loadCanonical();
            }
            Map<String, Sms.Thread> byKey = new LinkedHashMap<>();
            Map<String, List<Long>> ids = new HashMap<>();
            try (Cursor c = query(THREADS, THREAD_COLS, null, null, "date DESC", max)) {
                int n = 0;
                while (c != null && c.moveToNext() && n++ < max) {
                    long id = lng(c, "_id", 0);
                    List<String> addrs = new ArrayList<>();
                    for (String rid : str(c, "recipient_ids").trim().split("\\s+")) {
                        if (rid.isEmpty()) {
                            continue;
                        }
                        try {
                            long rl = Long.parseLong(rid);
                            String a = canon.get(rl);
                            if (a == null && !reloaded) { // a recipient the kept table does not know: read it again, once
                                loadCanonical();
                                reloaded = true;
                                a = canon.get(rl);
                            }
                            if (a != null && !a.isEmpty()) {
                                addrs.add(a);
                            }
                        } catch (NumberFormatException ignored) {
                            // not an id
                        }
                    }
                    if (addrs.isEmpty()) {
                        String a = newestAddress(id);
                        if (!a.isEmpty()) {
                            addrs.add(a);
                        }
                    }
                    String key = PhoneNumbers.threadKey(addrs);
                    if (key.isEmpty()) {
                        continue;
                    }
                    List<Long> l = ids.get(key);
                    if (l == null) {
                        l = new ArrayList<>();
                        ids.put(key, l);
                    }
                    l.add(id);
                    long date = lng(c, "date", 0);
                    Sms.Thread t = byKey.get(key);
                    boolean fresh = t == null;
                    if (fresh) {
                        t = new Sms.Thread();
                        t.key = key;
                        t.addresses.addAll(addrs);
                        byKey.put(key, t);
                    }
                    t.count += (int) lng(c, "message_count", 0);
                    t.unread |= lng(c, "read", 1) == 0;
                    t.attachment |= lng(c, "has_attachment", 0) == 1;
                    if (fresh || date > t.time) {
                        t.time = date;
                        t.providerId = id;
                        String snip = str(c, "snippet");
                        t.snippet = snip.isEmpty() && lng(c, "has_attachment", 0) == 1 ? "Attachment" : snip;
                    }
                }
            } catch (RuntimeException e) {
                // the conversations view is not readable here: the fallback below
            }
            if (byKey.isEmpty()) {
                fromMessages(byKey, ids, max);
            }
            idsByKey.putAll(ids); // not cleared: a short look at the newest conversations must not forget the older ones
            List<Sms.Thread> out = new ArrayList<>(byKey.values());
            Collections.sort(out, new Comparator<Sms.Thread>() {
                @Override
                public int compare(Sms.Thread a, Sms.Thread b) {
                    return Long.compare(b.time, a.time);
                }
            });
            cache = out;
            cacheMax = max;
            cacheDirty = seenDirty;
            cacheAt = now;
            return copies(out, max);
        }
    }

    /** When the conversations view cannot be read: conversations worked out from the newest texts (picture messages are then not listed). */
    private void fromMessages(Map<String, Sms.Thread> byKey, Map<String, List<Long>> ids, int max) {
        try (Cursor c = cr.query(SMS, null, null, null, "date DESC")) {
            int n = 0;
            while (c != null && c.moveToNext() && n++ < 600 && byKey.size() < max) {
                String key = PhoneNumbers.key(str(c, "address"));
                if (key.isEmpty()) {
                    continue;
                }
                Sms.Thread t = byKey.get(key);
                if (t == null) {
                    t = new Sms.Thread();
                    t.key = key;
                    t.addresses.add(str(c, "address"));
                    t.time = lng(c, "date", 0);
                    t.snippet = str(c, "body");
                    t.providerId = lng(c, "thread_id", 0);
                    t.unread = lng(c, "read", 1) == 0;
                    byKey.put(key, t);
                    List<Long> l = new ArrayList<>();
                    l.add(t.providerId);
                    ids.put(key, l);
                }
                t.count++;
            }
        } catch (RuntimeException e) {
            // nothing readable at all
        }
    }

    List<Sms.Thread> threads(int limit) {
        List<Sms.Thread> raw = rawThreads(limit + 20, false);
        List<Sms.Thread> out = new ArrayList<>();
        for (Sms.Thread t : raw) {
            if (out.size() >= limit) {
                break;
            }
            t.name = namesOf(t.addresses);
            out.add(t);
        }
        return out;
    }

    Sms.Thread header(String key) {
        for (int max : new int[] {100, 300}) { // the newest conversations first: they are almost always the ones asked for
            for (Sms.Thread t : rawThreads(max, false)) {
                if (t.key.equals(key)) {
                    t.name = namesOf(t.addresses);
                    return t;
                }
            }
        }
        return null;
    }

    private String scanStamp() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Sms.Thread t : rawThreads(120, true)) { // the size of the list the tablet asks for, so that this look refreshes the shared scan
            if (n++ >= 40) {
                break; // but only the newest conversations decide whether something changed
            }
            sb.append(t.providerId).append(':').append(t.time).append(':').append(t.count).append(':').append(t.unread ? 1 : 0).append(';');
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    /**
     * Changes whenever a conversation's newest message, size or read state changes. With a working observer this is a counter (and a
     * look at the database every 30 seconds, for a change the observer missed); without one, a scan.
     */
    String stamp() {
        ensureWatching();
        if (!watching) {
            return scanStamp();
        }
        long now = SystemClock.elapsedRealtime();
        boolean look;
        synchronized (watchLock) {
            look = now - lastSafety > SAFETY_MS;
            if (look) {
                lastSafety = now;
            }
        }
        if (look) {
            String s = scanStamp();
            synchronized (watchLock) {
                if (!lastScanStamp.isEmpty() && !s.equals(lastScanStamp)) {
                    dirty.incrementAndGet(); // the observer missed it
                }
                lastScanStamp = s;
            }
        }
        return "w" + dirty.get();
    }

    // ------------------------------------------------------------ messages

    private List<Long> threadIds(String key) {
        synchronized (scanLock) {
            List<Long> ids = idsByKey.get(key);
            if (ids == null) {
                rawThreads(300, false);
                ids = idsByKey.get(key);
            }
            return ids == null ? new ArrayList<Long>() : new ArrayList<>(ids);
        }
    }

    List<Sms.Msg> messages(String key, int limit) {
        return messagesBefore(key, 0, limit);
    }

    /** The newest {@code limit} messages older than {@code beforeMs} (0 = no limit in time), oldest first. */
    List<Sms.Msg> messagesBefore(String key, long beforeMs, int limit) {
        List<Sms.Msg> out = new ArrayList<>();
        for (long tid : threadIds(key)) {
            textsOf(tid, key, beforeMs, limit, out);
            picturesOf(tid, key, beforeMs, limit, out);
        }
        Collections.sort(out, new Comparator<Sms.Msg>() {
            @Override
            public int compare(Sms.Msg a, Sms.Msg b) {
                int c = Long.compare(a.time, b.time);
                return c != 0 ? c : a.id.compareTo(b.id);
            }
        });
        return out.size() > limit ? new ArrayList<>(out.subList(out.size() - limit, out.size())) : out;
    }

    private static int stateOf(int type, int status) {
        switch (type) {
            case 2: // sent
                return status == 0 ? Sms.STATE_DELIVERED : status == 64 ? Sms.STATE_FAILED : Sms.STATE_SENT;
            case 4: // outbox
            case 6: // queued
                return Sms.STATE_SENDING;
            case 5: // failed
                return Sms.STATE_FAILED;
            default:
                return Sms.STATE_NONE;
        }
    }

    private Sms.Msg textFrom(Cursor c, String key) {
        Sms.Msg m = new Sms.Msg();
        m.id = "s" + lng(c, "_id", 0);
        m.key = key;
        m.address = str(c, "address");
        m.body = str(c, "body");
        m.time = lng(c, "date", 0);
        int type = (int) lng(c, "type", 1);
        m.mine = type != 1;
        m.unread = lng(c, "read", 1) == 0;
        m.state = m.mine ? stateOf(type, (int) lng(c, "status", -1)) : Sms.STATE_NONE;
        if (m.state == Sms.STATE_FAILED) {
            m.error = type == 5 ? "the phone could not send it" : "it was not delivered";
        }
        return m;
    }

    private void textsOf(long threadId, String key, long beforeMs, int limit, List<Sms.Msg> out) {
        String sel = beforeMs > 0 ? "thread_id=? AND date<?" : "thread_id=?";
        String[] args = beforeMs > 0 ? new String[] {String.valueOf(threadId), String.valueOf(beforeMs)} : new String[] {String.valueOf(threadId)};
        try (Cursor c = query(SMS, SMS_COLS, sel, args, "date DESC", limit)) {
            int n = 0;
            while (c != null && c.moveToNext() && n++ < limit) {
                out.add(textFrom(c, key));
            }
        } catch (RuntimeException e) {
            // unreadable: the conversation shows what could be read
        }
    }

    private void picturesOf(long threadId, String key, long beforeMs, int limit, List<Sms.Msg> out) {
        // this table counts in seconds: "older than beforeMs" is "at most the second before it"
        String sel = beforeMs > 0 ? "thread_id=? AND date<=?" : "thread_id=?";
        String[] args = beforeMs > 0 ? new String[] {String.valueOf(threadId), String.valueOf((beforeMs - 1) / 1000)}
                : new String[] {String.valueOf(threadId)};
        Map<Long, Sms.Msg> byMid = new LinkedHashMap<>();
        Map<Long, String> subjects = new HashMap<>();
        try (Cursor c = query(MMS, MMS_COLS, sel, args, "date DESC", limit)) {
            int n = 0;
            while (c != null && c.moveToNext() && n++ < limit) {
                int mtype = (int) lng(c, "m_type", 132);
                if (mtype == 130 || mtype == 131) {
                    continue; // "a picture message is waiting" notices, not messages
                }
                long mid = lng(c, "_id", 0);
                Sms.Msg m = new Sms.Msg();
                m.id = "m" + mid;
                m.key = key;
                m.mms = true;
                m.time = lng(c, "date", 0) * 1000L;
                if (beforeMs > 0 && m.time >= beforeMs) {
                    continue; // the same second as the message asked about, but not older
                }
                int box = (int) lng(c, "msg_box", 1);
                m.mine = box != 1;
                m.unread = lng(c, "read", 1) == 0;
                m.state = m.mine ? (box == 4 ? Sms.STATE_SENDING : Sms.STATE_SENT) : Sms.STATE_NONE;
                byMid.put(mid, m);
                subjects.put(mid, str(c, "sub"));
            }
        } catch (RuntimeException e) {
            // unreadable
        }
        if (byMid.isEmpty()) {
            return;
        }
        loadParts(byMid, subjects);
        if (key.indexOf(',') >= 0) { // a group: who sent each one is needed; in a conversation with one person it is that person
            for (Map.Entry<Long, Sms.Msg> e : byMid.entrySet()) {
                if (!e.getValue().mine) {
                    e.getValue().address = mmsAddress(e.getKey(), 137);
                }
            }
        }
        out.addAll(byMid.values());
    }

    /** 137 = who sent it, 151 = who it went to. */
    private String mmsAddress(long mid, int type) {
        try (Cursor c = cr.query(Uri.parse("content://mms/" + mid + "/addr"), null, "type=?", new String[] {String.valueOf(type)}, null)) {
            while (c != null && c.moveToNext()) {
                String a = str(c, "address");
                if (!a.isEmpty() && !a.contains("insert-address-token")) {
                    return a;
                }
            }
        } catch (RuntimeException e) {
            // no address: the conversation's own is used
        }
        return "";
    }

    /** The text and the files of a page of picture messages, with one query for all of them. */
    private void loadParts(Map<Long, Sms.Msg> byMid, Map<Long, String> subjects) {
        StringBuilder in = new StringBuilder();
        for (Long mid : byMid.keySet()) {
            in.append(in.length() > 0 ? "," : "").append(mid.longValue());
        }
        Map<Long, StringBuilder> text = new HashMap<>();
        try (Cursor c = query(MMS_PART, PART_COLS, "mid IN (" + in + ")", null, "_id ASC", 0)) {
            while (c != null && c.moveToNext()) {
                Sms.Msg m = byMid.get(lng(c, "mid", -1));
                if (m == null) {
                    continue;
                }
                String type = str(c, "ct");
                if (type.equals("application/smil")) {
                    continue;
                }
                if (type.equals("text/plain")) {
                    String t = str(c, "text");
                    if (!t.isEmpty()) {
                        StringBuilder sb = text.get(lng(c, "mid", -1));
                        if (sb == null) {
                            sb = new StringBuilder();
                            text.put(lng(c, "mid", -1), sb);
                        }
                        sb.append(sb.length() > 0 ? "\n" : "").append(t);
                    }
                    continue;
                }
                Sms.Part p = new Sms.Part();
                p.id = "p" + lng(c, "_id", 0);
                p.type = type;
                String name = str(c, "name");
                if (name.isEmpty()) {
                    name = str(c, "cl");
                }
                p.name = name.isEmpty() ? "attachment" : name;
                m.parts.add(p);
            }
        } catch (RuntimeException e) {
            // no parts could be read
        }
        for (Map.Entry<Long, Sms.Msg> e : byMid.entrySet()) {
            StringBuilder sb = text.get(e.getKey());
            String body = sb == null ? "" : sb.toString();
            String sub = subjects.get(e.getKey());
            e.getValue().body = sub == null || sub.isEmpty() ? body : sub + (body.isEmpty() ? "" : "\n" + body);
        }
    }

    // ------------------------------------------------------------ search

    private static String likeOf(String q) {
        return "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private String keyOfThread(long threadId, String address) {
        synchronized (scanLock) {
            for (Map.Entry<String, List<Long>> e : idsByKey.entrySet()) {
                if (e.getValue().contains(threadId)) {
                    return e.getKey();
                }
            }
        }
        return PhoneNumbers.key(address);
    }

    /** Texts and picture messages whose text contains the query (letters of any case), newest first. */
    List<Sms.Msg> search(String q, int limit) {
        rawThreads(300, false); // so that a message can be given the key of its conversation
        List<Sms.Msg> out = new ArrayList<>();
        try (Cursor c = query(SMS, SMS_COLS, "body LIKE ? ESCAPE '\\'", new String[] {likeOf(q)}, "date DESC", limit)) {
            int n = 0;
            while (c != null && c.moveToNext() && n++ < limit) {
                String key = keyOfThread(lng(c, "thread_id", 0), str(c, "address"));
                if (!key.isEmpty()) {
                    out.add(textFrom(c, key));
                }
            }
        } catch (RuntimeException e) {
            // unreadable: no hits from the texts
        }
        Map<Long, String> texts = new LinkedHashMap<>();
        try (Cursor c = query(MMS_PART, PART_COLS, "ct='text/plain' AND text LIKE ? ESCAPE '\\'", new String[] {likeOf(q)}, "_id DESC", limit)) {
            int n = 0;
            while (c != null && c.moveToNext() && n++ < limit) {
                texts.put(lng(c, "mid", -1), str(c, "text"));
            }
        } catch (RuntimeException e) {
            // unreadable: no hits from the picture messages
        }
        if (!texts.isEmpty()) {
            StringBuilder in = new StringBuilder();
            for (Long mid : texts.keySet()) {
                in.append(in.length() > 0 ? "," : "").append(mid.longValue());
            }
            try (Cursor c = query(MMS, MMS_COLS, "_id IN (" + in + ")", null, "date DESC", 0)) {
                while (c != null && c.moveToNext()) {
                    long mid = lng(c, "_id", 0);
                    String key = keyOfThread(lng(c, "thread_id", 0), "");
                    if (key.isEmpty()) {
                        continue;
                    }
                    Sms.Msg m = new Sms.Msg();
                    m.id = "m" + mid;
                    m.key = key;
                    m.mms = true;
                    m.time = lng(c, "date", 0) * 1000L;
                    m.mine = lng(c, "msg_box", 1) != 1;
                    m.unread = lng(c, "read", 1) == 0;
                    m.body = texts.get(mid);
                    out.add(m);
                }
            } catch (RuntimeException e) {
                // unreadable
            }
        }
        Collections.sort(out, new Comparator<Sms.Msg>() {
            @Override
            public int compare(Sms.Msg a, Sms.Msg b) {
                int c = Long.compare(b.time, a.time);
                return c != 0 ? c : a.id.compareTo(b.id);
            }
        });
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    // ------------------------------------------------------------ deleting and marking read (as far as the phone lets us)

    static final String REFUSED = "The phone did not let LinkMeter change its texts (only the phone's own messaging app may).";

    /** Runs one command as the shell user through Shizuku; false when that was not possible. */
    private static boolean shell(String cmd) {
        try {
            Priv.run(cmd, 20);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private int rows(Uri uri, String where, String arg) {
        try (Cursor c = cr.query(uri, new String[] {"_id"}, where, arg == null ? null : new String[] {arg}, null)) {
            return c == null ? -1 : c.getCount();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private int messagesIn(long threadId) {
        int a = rows(SMS, "thread_id=?", String.valueOf(threadId));
        int b = rows(MMS, "thread_id=?", String.valueOf(threadId));
        return a < 0 || b < 0 ? -1 : a + b;
    }

    private int unreadIn(long threadId) {
        int a = rows(SMS, "thread_id=? AND read=0", String.valueOf(threadId));
        int b = rows(MMS, "thread_id=? AND read=0", String.valueOf(threadId));
        return a < 0 || b < 0 ? -1 : a + b;
    }

    /**
     * Asks the phone's database to delete messages and conversations, as the shell user (the app itself may not: only the default
     * messaging app may write that database). Whether it worked is checked by looking again; what stayed is reported as failed.
     */
    Sms.DeleteResult delete(List<String> ids, List<String> keys) {
        Sms.DeleteResult r = new Sms.DeleteResult();
        if (!Priv.ready()) {
            r.failedIds.addAll(ids);
            r.failedKeys.addAll(keys);
            r.why = "LinkMeter could not try: " + Priv.stateText(Priv.state()) + ".";
            return r;
        }
        boolean any = false;
        for (String key : keys) {
            boolean ok = true;
            for (long tid : threadIds(key)) {
                shell("content delete --uri content://mms-sms/conversations/" + tid);
                if (messagesIn(tid) == 0) {
                    any = true;
                } else {
                    ok = false;
                }
            }
            (ok ? r.doneKeys : r.failedKeys).add(key);
        }
        for (String id : ids) {
            Uri uri;
            try {
                uri = Uri.parse((id.startsWith("s") ? "content://sms/" : "content://mms/") + Long.parseLong(id.substring(1)));
            } catch (RuntimeException e) {
                r.failedIds.add(id);
                continue;
            }
            shell("content delete --uri " + uri);
            if (rows(uri, null, null) == 0) {
                r.doneIds.add(id);
                any = true;
            } else {
                r.failedIds.add(id);
            }
        }
        if (any) {
            messagesChanged();
        }
        if (!r.failedIds.isEmpty() || !r.failedKeys.isEmpty()) {
            r.why = REFUSED;
        }
        return r;
    }

    /** Asks the phone's database to mark conversations as read (the same limits as {@link #delete}). */
    Sms.DeleteResult markRead(List<String> keys) {
        Sms.DeleteResult r = new Sms.DeleteResult();
        if (!Priv.ready()) {
            r.failedKeys.addAll(keys);
            r.why = "LinkMeter could not try: " + Priv.stateText(Priv.state()) + ".";
            return r;
        }
        boolean any = false;
        for (String key : keys) {
            boolean ok = true;
            for (long tid : threadIds(key)) {
                shell("content update --uri content://sms --bind read:i:1 --where 'thread_id=" + tid + " AND read=0'");
                shell("content update --uri content://mms --bind read:i:1 --where 'thread_id=" + tid + " AND read=0'");
                if (unreadIn(tid) == 0) {
                    any = true;
                } else {
                    ok = false;
                }
            }
            (ok ? r.doneKeys : r.failedKeys).add(key);
        }
        if (any) {
            messagesChanged();
        }
        if (!r.failedKeys.isEmpty()) {
            r.why = REFUSED;
        }
        return r;
    }

    /** The bytes of one picture or file of a picture message ("p123"). */
    Sms.PartData part(String id) throws IOException {
        if (!id.startsWith("p")) {
            return null;
        }
        long pid;
        try {
            pid = Long.parseLong(id.substring(1));
        } catch (NumberFormatException e) {
            return null;
        }
        Sms.PartData d = new Sms.PartData();
        try (Cursor c = cr.query(MMS_PART, new String[] {"ct", "name", "cl"}, "_id=?", new String[] {String.valueOf(pid)}, null)) {
            if (c == null || !c.moveToFirst()) {
                return null;
            }
            d.type = str(c, "ct");
            d.name = str(c, "name").isEmpty() ? str(c, "cl") : str(c, "name");
        } catch (RuntimeException e) {
            return null;
        }
        try (InputStream in = cr.openInputStream(Uri.parse("content://mms/part/" + pid))) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > MAX_PART_BYTES) {
                    throw new IOException("that file is too big to open here");
                }
            }
            d.bytes = bo.toByteArray();
        } catch (SecurityException e) {
            throw new IOException("the phone would not let LinkMeter open it");
        }
        return d;
    }
}
