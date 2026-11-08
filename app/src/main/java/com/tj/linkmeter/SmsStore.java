package com.tj.linkmeter;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
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

/**
 * Phone only: reads the texts and picture messages in the phone's own database (the same one the phone's Messages app uses; any app
 * with the READ_SMS permission may read it), and the contacts. Conversations are found through the "conversations" view, whose
 * recipients are numbers kept in the "canonical addresses" table. Every column is read by name and may be missing: phones differ.
 */
final class SmsStore {
    private static final Uri SMS = Uri.parse("content://sms");
    private static final Uri MMS = Uri.parse("content://mms");
    private static final Uri THREADS = Uri.parse("content://mms-sms/conversations?simple=true");
    private static final Uri CANONICAL = Uri.parse("content://mms-sms/canonical-addresses");
    private static final Uri MMS_PART = Uri.parse("content://mms/part");
    private static final int MAX_PART_BYTES = 24 * 1024 * 1024;

    private final ContentResolver cr;
    private final Map<String, String> names = new LinkedHashMap<String, String>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 500;
        }
    };
    /** conversation key -> thread ids in the database (usually one) */
    private final Map<String, List<Long>> idsByKey = new HashMap<>();

    SmsStore(Context c) {
        cr = c.getApplicationContext().getContentResolver();
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? "" : c.getString(i);
    }

    private static long lng(Cursor c, String col, long def) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? def : c.getLong(i);
    }

    // ------------------------------------------------------------ names and contacts

    synchronized String nameFor(String address) {
        if (address == null || address.isEmpty()) {
            return "";
        }
        String cached = names.get(address);
        if (cached != null) {
            return cached;
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
        names.put(address, name);
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

    private Map<Long, String> canonical() {
        Map<Long, String> m = new HashMap<>();
        try (Cursor c = cr.query(CANONICAL, null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                m.put(lng(c, "_id", -1), str(c, "address"));
            }
        } catch (RuntimeException e) {
            // unreadable: the conversations are then worked out from their messages
        }
        return m;
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

    /** The conversations as the database has them, merged by key, newest first, names not filled in yet. */
    private synchronized List<Sms.Thread> rawThreads(int max) {
        Map<Long, String> canon = canonical();
        Map<String, Sms.Thread> byKey = new LinkedHashMap<>();
        Map<String, List<Long>> ids = new HashMap<>();
        try (Cursor c = cr.query(THREADS, null, null, null, "date DESC")) {
            int n = 0;
            while (c != null && c.moveToNext() && n++ < max) {
                long id = lng(c, "_id", 0);
                List<String> addrs = new ArrayList<>();
                for (String rid : str(c, "recipient_ids").trim().split("\\s+")) {
                    if (rid.isEmpty()) {
                        continue;
                    }
                    try {
                        String a = canon.get(Long.parseLong(rid));
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
        return out;
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
        List<Sms.Thread> raw = rawThreads(limit + 20);
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
        for (Sms.Thread t : rawThreads(300)) {
            if (t.key.equals(key)) {
                t.name = namesOf(t.addresses);
                return t;
            }
        }
        return null;
    }

    /** Changes whenever a conversation's newest message, size or read state changes. */
    String stamp() {
        StringBuilder sb = new StringBuilder();
        for (Sms.Thread t : rawThreads(40)) {
            sb.append(t.providerId).append(':').append(t.time).append(':').append(t.count).append(':').append(t.unread ? 1 : 0).append(';');
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    // ------------------------------------------------------------ messages

    private synchronized List<Long> threadIds(String key) {
        List<Long> ids = idsByKey.get(key);
        if (ids == null) {
            rawThreads(300);
            ids = idsByKey.get(key);
        }
        return ids == null ? new ArrayList<Long>() : new ArrayList<>(ids);
    }

    List<Sms.Msg> messages(String key, int limit) {
        List<Sms.Msg> out = new ArrayList<>();
        for (long tid : threadIds(key)) {
            textsOf(tid, key, limit, out);
            picturesOf(tid, key, limit, out);
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

    private void textsOf(long threadId, String key, int limit, List<Sms.Msg> out) {
        try (Cursor c = cr.query(SMS, null, "thread_id=?", new String[] {String.valueOf(threadId)}, "date DESC")) {
            int n = 0;
            while (c != null && c.moveToNext() && n++ < limit) {
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
                out.add(m);
            }
        } catch (RuntimeException e) {
            // unreadable: the conversation shows what could be read
        }
    }

    private void picturesOf(long threadId, String key, int limit, List<Sms.Msg> out) {
        try (Cursor c = cr.query(MMS, null, "thread_id=?", new String[] {String.valueOf(threadId)}, "date DESC")) {
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
                m.time = lng(c, "date", 0) * 1000L; // this table counts in seconds
                int box = (int) lng(c, "msg_box", 1);
                m.mine = box != 1;
                m.unread = lng(c, "read", 1) == 0;
                m.state = m.mine ? (box == 4 ? Sms.STATE_SENDING : Sms.STATE_SENT) : Sms.STATE_NONE;
                m.address = mmsAddress(mid, m.mine ? 151 : 137);
                loadParts(mid, m);
                String sub = str(c, "sub");
                if (!sub.isEmpty()) {
                    m.body = sub + (m.body.isEmpty() ? "" : "\n" + m.body);
                }
                out.add(m);
            }
        } catch (RuntimeException e) {
            // unreadable
        }
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

    private void loadParts(long mid, Sms.Msg m) {
        StringBuilder text = new StringBuilder();
        try (Cursor c = cr.query(MMS_PART, null, "mid=?", new String[] {String.valueOf(mid)}, null)) {
            while (c != null && c.moveToNext()) {
                String type = str(c, "ct");
                if (type.equals("application/smil")) {
                    continue;
                }
                if (type.equals("text/plain")) {
                    String t = str(c, "text");
                    if (!t.isEmpty()) {
                        text.append(text.length() > 0 ? "\n" : "").append(t);
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
        m.body = text.toString();
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
