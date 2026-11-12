package com.tj.linkmeter;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.ContactsContract;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /** How many conversations get their grey line read from the messages themselves in one scan (the newest ones; the tablet shows 100). */
    private static final int PREVIEW_BATCH = 150;
    /** How many times the messages are asked for again, without the busy conversations that used up an answer. */
    private static final int PREVIEW_ROUNDS = 4;

    private static final String[] THREAD_COLS = {"_id", "date", "message_count", "recipient_ids", "snippet", "read", "has_attachment"};
    private static final String[] SMS_COLS = {"_id", "address", "body", "date", "type", "read", "status", "thread_id"};
    private static final String[] MMS_COLS = {"_id", "date", "msg_box", "m_type", "read", "sub", "thread_id"};
    private static final String[] PART_COLS = {"_id", "mid", "ct", "text", "name", "cl"};

    private final ContentResolver cr;
    /** Every contact number with its name, read with ONE query (null until first read). Replaced when the contacts really changed. */
    private volatile ContactBook book;
    private final Object bookLock = new Object();
    private long bookFailedAt = -1_000_000;
    private boolean bookRefreshPending;
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
                        scheduleBookRefresh(); // not a rescan of the texts: the contacts change all the time (sync, "last contacted")
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
        ContactBook b = book();
        return b == null ? "" : b.nameFor(address);
    }

    /** All contact numbers with their names, read once and kept; null while the contacts cannot be read (tried again after a few seconds). */
    private ContactBook book() {
        ContactBook b = book;
        if (b != null) {
            return b;
        }
        synchronized (bookLock) {
            if (book == null && SystemClock.elapsedRealtime() - bookFailedAt > 10_000) {
                ContactBook fresh = readBook();
                if (fresh == null) {
                    bookFailedAt = SystemClock.elapsedRealtime();
                } else {
                    book = fresh;
                }
            }
            return book;
        }
    }

    private ContactBook readBook() {
        ContactBook b = new ContactBook();
        try (Cursor c = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, new String[] {ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME}, null, null, null)) {
            if (c == null) {
                return null;
            }
            while (c.moveToNext()) {
                String name = c.isNull(2) ? "" : c.getString(2);
                b.add(c.isNull(0) ? "" : c.getString(0), name);
                b.add(c.isNull(1) ? "" : c.getString(1), name);
            }
        } catch (RuntimeException e) {
            return null; // no permission to read contacts: no names
        }
        return b;
    }

    /** The contacts changed (or something touched them): look again soon, at most every few seconds, and say so only if a name or number differs. */
    private void scheduleBookRefresh() {
        Handler h = handler;
        if (h == null) {
            return;
        }
        synchronized (bookLock) {
            if (bookRefreshPending) {
                return;
            }
            bookRefreshPending = true;
        }
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (bookLock) {
                    bookRefreshPending = false;
                }
                ContactBook fresh = readBook();
                boolean changed;
                synchronized (bookLock) {
                    changed = fresh != null && (book == null || !book.equals(fresh));
                    if (fresh != null) {
                        book = fresh;
                    }
                }
                if (changed) {
                    messagesChanged(); // the tablet hears that names changed
                }
            }
        }, 4000);
    }

    // ------------------------------------------------------------ saving a contact

    private long contactIdFor(String number) {
        try (Cursor c = cr.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                new String[] {ContactsContract.PhoneLookup._ID}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getLong(0);
            }
        } catch (RuntimeException e) {
            // no such contact, or the lookup is not possible
        }
        return 0;
    }

    /** The first raw contact of a contact and its account type ("" for a contact that lives on the phone only); id 0 when there is none. */
    private String[] firstRawContact(long contactId) {
        try (Cursor c = cr.query(ContactsContract.RawContacts.CONTENT_URI, new String[] {ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_TYPE},
                ContactsContract.RawContacts.CONTACT_ID + "=? AND " + ContactsContract.RawContacts.DELETED + "=0", new String[] {String.valueOf(contactId)},
                ContactsContract.RawContacts._ID + " ASC")) {
            if (c != null && c.moveToFirst()) {
                return new String[] {String.valueOf(c.getLong(0)), c.isNull(1) ? "" : c.getString(1)};
            }
        } catch (RuntimeException e) {
            // unreadable
        }
        return new String[] {"0", ""};
    }

    /**
     * The account a new contact goes to, like the phone's own Contacts app would: the one that most of the contacts already live in,
     * preferring a Google account (so that it syncs). {null, null} = a contact on the phone only.
     */
    private String[] defaultAccount() {
        Map<String, Integer> counts = new HashMap<>();
        try (Cursor c = cr.query(ContactsContract.RawContacts.CONTENT_URI, new String[] {ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.ACCOUNT_NAME},
                ContactsContract.RawContacts.DELETED + "=0 AND " + ContactsContract.RawContacts.ACCOUNT_TYPE + " IS NOT NULL", null,
                ContactsContract.RawContacts._ID + " DESC LIMIT 300")) {
            while (c != null && c.moveToNext()) {
                String type = c.isNull(0) ? "" : c.getString(0);
                String name = c.isNull(1) ? "" : c.getString(1);
                if (type.isEmpty() || name.isEmpty()) {
                    continue;
                }
                String k = type + "\n" + name;
                Integer n = counts.get(k);
                counts.put(k, n == null ? 1 : n + 1);
            }
        } catch (RuntimeException e) {
            // nothing to go by: a contact on the phone only
        }
        String best = null;
        int bestN = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            int n = e.getValue() + (e.getKey().startsWith("com.google\n") ? 100000 : 0); // a Google account first
            if (n > bestN) {
                bestN = n;
                best = e.getKey();
            }
        }
        return best == null ? new String[] {null, null} : best.split("\n", 2);
    }

    private static String whereLabel(String accountType) {
        if (accountType == null || accountType.isEmpty()) {
            return "the phone";
        }
        return accountType.equals("com.google") ? "your Google account" : "an account on the phone";
    }

    /**
     * Saves a contact, or gives the name to the contact that already has the number (the same as renaming it in the phone's Contacts
     * app). The phone must have allowed LinkMeter to change contacts; without that the result says so. Never throws.
     */
    Sms.ContactResult saveContact(String name, String number) {
        Sms.ContactResult r = new Sms.ContactResult();
        try {
            long contactId = contactIdFor(number);
            String[] raw = contactId > 0 ? firstRawContact(contactId) : new String[] {"0", ""};
            long rawId = Long.parseLong(raw[0]);
            ArrayList<ContentProviderOperation> ops = new ArrayList<>();
            if (rawId > 0) { // somebody has this number already: the name changes
                ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                        .withSelection(ContactsContract.Data.RAW_CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
                                new String[] {String.valueOf(rawId), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE})
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name).build());
                ContentProviderResult[] res = cr.applyBatch(ContactsContract.AUTHORITY, ops);
                if (res.length == 0 || res[0].count == null || res[0].count == 0) { // no name row yet: add one
                    ops.clear();
                    ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name).build());
                    cr.applyBatch(ContactsContract.AUTHORITY, ops);
                }
                r.how = "renamed";
                r.where = whereLabel(raw[1]);
            } else { // a new contact, in the account the phone's contacts mostly live in
                String[] account = defaultAccount();
                ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, account[0])
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account[1]).build());
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name).build());
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).build());
                cr.applyBatch(ContactsContract.AUTHORITY, ops);
                r.how = "created";
                r.where = whereLabel(account[0]);
            }
            r.ok = true;
            contactsChanged(); // the next name lookup reads the contacts again, and the tablet is told
        } catch (SecurityException e) {
            r.needPermission = true;
            r.why = "The phone has not allowed LinkMeter to change contacts yet.";
        } catch (RemoteException | OperationApplicationException | RuntimeException e) {
            r.why = "The phone's contacts refused it (" + e.getClass().getSimpleName() + ").";
        }
        return r;
    }

    /** The contacts changed because of something this phone did (a contact was saved): the next look is at once. */
    void contactsChanged() {
        synchronized (bookLock) {
            book = null;
            bookFailedAt = -1_000_000;
        }
        messagesChanged();
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
                        t.snippet = str(c, "snippet"); // empty for a picture message without a subject: fillPreviews reads the message itself
                    }
                }
            } catch (RuntimeException e) {
                // the conversations view is not readable here: the fallback below
            }
            if (byKey.isEmpty()) {
                fromMessages(byKey, ids, max);
            }
            fillPreviews(byKey.values());
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

    private static String idList(Collection<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            sb.append(sb.length() > 0 ? "," : "").append(id.longValue());
        }
        return sb.toString();
    }

    /**
     * The grey line of a conversation is the database's snippet, and Android fills that from the SUBJECT of a picture message, so a
     * picture message without a subject (every group text, a picture with a caption) leaves it empty although the conversation is
     * unread. For those conversations the newest message itself is read, for all of them together: a few queries, never one per
     * conversation. Nothing is kept between scans: the parts of a picture message are written after its row, so a kept answer could
     * stay empty for good, while the next change of the database always makes a new scan.
     */
    private void fillPreviews(Collection<Sms.Thread> all) {
        Map<Long, Sms.Thread> blank = new LinkedHashMap<>(); // database thread id -> conversation, newest first
        for (Sms.Thread t : all) {
            if (blank.size() < PREVIEW_BATCH && t.providerId > 0 && SmsPreview.blank(t.snippet)) {
                blank.put(t.providerId, t);
            }
        }
        if (!blank.isEmpty()) {
            Map<Long, Sms.Msg> texts = newestTexts(blank.keySet());
            Map<Long, Sms.Msg> pictures = newestPictures(blank.keySet());
            for (Map.Entry<Long, Sms.Thread> e : blank.entrySet()) {
                Sms.Msg m = SmsPreview.newer(texts.get(e.getKey()), pictures.get(e.getKey()));
                String line = SmsPreview.line(m);
                if (!line.isEmpty()) {
                    e.getValue().snippet = line;
                    e.getValue().attachment |= !m.parts.isEmpty();
                }
            }
        }
        for (Sms.Thread t : all) { // what could not be read keeps the old rule: only files in it
            if (t.attachment && SmsPreview.blank(t.snippet)) {
                t.snippet = SmsPreview.ATTACHMENT;
            }
        }
    }

    /**
     * The newest text of each of the given conversations. The rows come newest first, so the first one seen of a conversation is its
     * newest. A very busy conversation can use up the whole answer; the ones that did not show up are then asked for again without it.
     */
    private Map<Long, Sms.Msg> newestTexts(Set<Long> threadIds) {
        Map<Long, Sms.Msg> out = new HashMap<>();
        Set<Long> left = new LinkedHashSet<>(threadIds);
        for (int round = 0; round < PREVIEW_ROUNDS && !left.isEmpty(); round++) {
            int limit = Math.min(300, left.size() * 4 + 20);
            int rows = 0;
            try (Cursor c = query(SMS, SMS_COLS, "thread_id IN (" + idList(left) + ")", null, "date DESC", limit)) {
                while (c != null && rows < limit && c.moveToNext()) {
                    rows++;
                    long tid = lng(c, "thread_id", -1);
                    if (left.contains(tid) && !out.containsKey(tid)) {
                        out.put(tid, textFrom(c, ""));
                    }
                }
            } catch (RuntimeException e) {
                break; // unreadable: those conversations keep what the database says
            }
            if (rows < limit) {
                break; // the answer was not cut short, so the conversations that did not show up have no text
            }
            left.removeAll(out.keySet());
        }
        return out;
    }

    /**
     * The newest picture message of each of the given conversations, with its words and files, as {@link #newestTexts} finds the texts.
     * The notices that a picture message is waiting are not messages and are passed over, as in a conversation.
     */
    private Map<Long, Sms.Msg> newestPictures(Set<Long> threadIds) {
        Map<Long, Sms.Msg> byThread = new HashMap<>();
        Map<Long, Sms.Msg> byMid = new LinkedHashMap<>();
        Map<Long, String> subjects = new HashMap<>();
        Set<Long> left = new LinkedHashSet<>(threadIds);
        for (int round = 0; round < PREVIEW_ROUNDS && !left.isEmpty(); round++) {
            int limit = Math.min(300, left.size() * 4 + 20);
            int rows = 0;
            try (Cursor c = query(MMS, MMS_COLS, "thread_id IN (" + idList(left) + ")", null, "date DESC", limit)) {
                while (c != null && rows < limit && c.moveToNext()) {
                    rows++;
                    long tid = lng(c, "thread_id", -1);
                    int mtype = (int) lng(c, "m_type", 132);
                    if (!left.contains(tid) || byThread.containsKey(tid) || mtype == 130 || mtype == 131) {
                        continue;
                    }
                    long mid = lng(c, "_id", 0);
                    Sms.Msg m = new Sms.Msg();
                    m.id = "m" + mid;
                    m.mms = true;
                    m.time = lng(c, "date", 0) * 1000L; // this table counts in seconds
                    byThread.put(tid, m);
                    byMid.put(mid, m);
                    subjects.put(mid, str(c, "sub"));
                }
            } catch (RuntimeException e) {
                break;
            }
            if (rows < limit) {
                break;
            }
            left.removeAll(byThread.keySet());
        }
        if (!byMid.isEmpty()) {
            loadParts(byMid, subjects);
        }
        return byThread;
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

    /**
     * A cheap look at the newest conversations (one small query: no scan, no names, no lock), for a change the observer missed. It is
     * only compared with the last look, so any difference means "something changed".
     */
    private String scanStamp() {
        StringBuilder sb = new StringBuilder();
        try (Cursor c = query(THREADS, new String[] {"_id", "date", "message_count", "read"}, null, null, "date DESC", 30)) {
            while (c != null && c.moveToNext()) {
                sb.append(lng(c, "_id", 0)).append(':').append(lng(c, "date", 0)).append(':').append(lng(c, "message_count", 0)).append(':')
                        .append(lng(c, "read", 1)).append(';');
            }
        } catch (RuntimeException e) {
            // not readable now: the look says the same as before
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
