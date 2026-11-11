package com.tj.linkmeter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * What the phone answers when the tablet asks about texts (the /sms/* requests). It is private: while the switch "Let the tablet use
 * my texts" in the phone's app is off, everything but /sms/state is refused. The logic (merging the phone's messages with the
 * tablet's own record, checking numbers, staging files, recording what was sent) is plain Java and tested offline; what only Android
 * can do (read the messages database, send, look up contacts, scale a picture) is handed in as {@link Backend}.
 *
 * Routes: state, grant, stamp (can wait for news), threads, thread (pages back in time), search, attach, send, part, contacts, delete, read
 * (see docs/FILES_AND_TEXTS_PLAN.md and docs/TEXTS_OVERHAUL.md). The caller has
 * already checked that the other device is paired. An older app answers 404 "unknown request" to all of them.
 */
final class SmsApi {
    /** 2 = /sms/stamp can wait for news, /sms/thread pages back in time, /sms/search, /sms/delete, /sms/read. */
    static final int VERSION = 2;
    /** The longest a /sms/stamp request may wait for news, in seconds. */
    static final int MAX_WAIT_S = 25;
    private static final Pattern PROVIDER_ID = Pattern.compile("[sm][0-9]{1,12}");
    private static final Pattern OUTBOX_ID = Pattern.compile("o[0-9]{1,12}");
    static final long MAX_BODY = 256 * 1024;
    static final int MAX_TEXT = 20_000;
    static final int MAX_RECIPIENTS = 20;
    static final int MAX_FILES = 10;
    static final long MAX_TOTAL_FILES = 20L * 1024 * 1024;
    static final long MAX_PART = 24L * 1024 * 1024;

    /** The Android-only parts. */
    interface Backend {
        /** The switch in the phone's app. */
        boolean on();

        boolean canRead();

        boolean canSend();

        boolean canContacts();

        /** A sentence about what is missing (permissions), "" when all is well. */
        String note();

        /** Tries to give the app the permissions it needs (through Shizuku). Takes a few seconds. */
        void grant() throws IOException;

        /** Changes whenever a message arrives or changes in the phone's database. */
        String stamp();

        /** The phone's newest conversations, newest first. */
        List<Sms.Thread> threads(int limit) throws IOException;

        /** The newest messages of one conversation (any order). */
        List<Sms.Msg> messages(String key, int limit) throws IOException;

        /** Name and numbers of one conversation, or null if the phone has none. */
        Sms.Thread header(String key) throws IOException;

        /** The contact's name for a number, "" if unknown. */
        String nameFor(String address);

        List<Sms.Person> contacts(String query, int limit);

        /** A picture or file of a message in the phone's database, or null. */
        Sms.PartData part(String id) throws IOException;

        /** Starts sending. msgId is the entry already made in the outbox: later results go to {@link SmsOutbox#setState}. */
        void send(Sms.Outgoing out, String msgId) throws IOException;

        /** The image as a JPEG no larger than px on its longer side, or null if it cannot be read. */
        byte[] scale(byte[] image, int px);

        // ---- protocol 2. Defaults, so that a backend that does not know them still works (and answers "cannot").

        /** The newest {@code limit} messages older than {@code before} (milliseconds) of one conversation (any order). */
        default List<Sms.Msg> messagesBefore(String key, long before, int limit) throws IOException {
            List<Sms.Msg> out = new ArrayList<>();
            for (Sms.Msg m : messages(key, 500)) {
                if (m.time < before) {
                    out.add(m);
                }
            }
            Collections.sort(out, new Comparator<Sms.Msg>() {
                @Override
                public int compare(Sms.Msg a, Sms.Msg b) {
                    return Long.compare(a.time, b.time);
                }
            });
            return out.size() > limit ? new ArrayList<>(out.subList(out.size() - limit, out.size())) : out;
        }

        /** Messages whose text contains the query, newest first, at most {@code limit}. */
        default List<Sms.Msg> search(String query, int limit) throws IOException {
            return new ArrayList<>();
        }

        /**
         * Tries to delete messages ("s12", "m34") and whole conversations (keys) from the phone's database. Only the phone's default
         * messaging app may write that database; what could not be deleted is reported as failed, with a sentence about why.
         */
        default Sms.DeleteResult delete(List<String> ids, List<String> keys) throws IOException {
            Sms.DeleteResult r = new Sms.DeleteResult();
            r.failedIds.addAll(ids);
            r.failedKeys.addAll(keys);
            r.why = "This phone does not let LinkMeter delete texts.";
            return r;
        }

        /** Tries to mark conversations as read on the phone (the same limits as {@link #delete}); the keys are in doneKeys or failedKeys. */
        default Sms.DeleteResult markRead(List<String> keys) throws IOException {
            Sms.DeleteResult r = new Sms.DeleteResult();
            r.failedKeys.addAll(keys);
            r.why = "This phone does not let LinkMeter mark texts as read.";
            return r;
        }

        /** The backend calls this when the phone's messages or contacts changed (so that a waiting request answers at once). */
        default void setChangeListener(Runnable listener) {}
    }

    private final Backend backend;
    private final SmsOutbox outbox;
    private final SmsStaging staging;
    private final LongSupplier clock;
    /** Goes up when the phone's messages or the tablet's record change: wakes the requests that wait for news. */
    private final ChangeGate gate = new ChangeGate();

    SmsApi(Backend backend, SmsOutbox outbox, SmsStaging staging, LongSupplier clock) {
        this.backend = backend;
        this.outbox = outbox;
        this.staging = staging;
        this.clock = clock;
        backend.setChangeListener(gate::bump);
        outbox.setOnChange(gate::bump);
    }

    private static String p(Map<String, String> q, String key, String def) {
        String v = q.get(key);
        return v == null || v.isEmpty() ? def : v;
    }

    private static long num(Map<String, String> q, String key, long def) {
        try {
            return Long.parseLong(q.get(key));
        } catch (RuntimeException e) {
            return def;
        }
    }

    /** How many seconds a /sms/stamp request may hold on: what was asked, between none and {@link #MAX_WAIT_S}. */
    static int waitSeconds(Map<String, String> q) {
        return clamp(num(q, "wait", 0), 0, MAX_WAIT_S);
    }

    private static int clamp(long v, int lo, int hi) {
        return (int) Math.max(lo, Math.min(hi, v));
    }

    private Sms.State state() {
        Sms.State s = new Sms.State();
        s.version = VERSION;
        s.on = backend.on();
        s.read = backend.canRead();
        s.send = backend.canSend();
        s.contacts = backend.canContacts();
        s.note = backend.note();
        return s;
    }

    /** Answers one request. Never throws: a failure is a 409 with the reason. */
    FsApi.Reply handle(String route, Map<String, String> q, InputStream body, long bodyLen) {
        try {
            if (route.equals("/sms/state")) {
                return FsApi.Reply.json(SmsJson.state(state()));
            }
            if (!route.startsWith("/sms/")) {
                return FsApi.Reply.err(404, "unknown request");
            }
            if (!backend.on()) {
                return FsApi.Reply.err(403, "off");
            }
            switch (route) {
                case "/sms/grant":
                    try {
                        backend.grant();
                    } catch (IOException e) {
                        // the state below says what is still missing
                    }
                    return FsApi.Reply.json(SmsJson.state(state()));
                case "/sms/stamp":
                    return stamp(q);
                case "/sms/search":
                    return search(q);
                case "/sms/delete":
                    return delete(body, bodyLen);
                case "/sms/read":
                    return markRead(body, bodyLen);
                case "/sms/threads":
                    return threads(q);
                case "/sms/thread":
                    return thread(q);
                case "/sms/attach":
                    return attach(q, body, bodyLen);
                case "/sms/send":
                    return send(body, bodyLen);
                case "/sms/part":
                    return part(q);
                case "/sms/contacts":
                    return contacts(q);
                default:
                    return FsApi.Reply.err(404, "unknown request");
            }
        } catch (IOException | RuntimeException e) {
            return FsApi.Reply.err(409, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private FsApi.Reply needRead() {
        if (backend.canRead()) {
            return null;
        }
        String n = backend.note();
        return FsApi.Reply.err(403, n.isEmpty() ? "this app is not allowed to read texts" : n);
    }

    /** The names of everybody in a conversation, from the contacts (or the numbers, nicely written). */
    private String namesOf(List<String> addresses) {
        StringBuilder sb = new StringBuilder();
        for (String a : addresses) {
            String n = backend.nameFor(a);
            sb.append(sb.length() > 0 ? ", " : "").append(n == null || n.isEmpty() ? PhoneNumbers.pretty(a) : n);
        }
        return sb.toString();
    }

    private FsApi.Reply threads(Map<String, String> q) throws IOException {
        FsApi.Reply no = needRead();
        if (no != null) {
            return no;
        }
        int limit = clamp(num(q, "limit", 60), 1, 300);
        List<Sms.Thread> merged = SmsMerge.threads(backend.threads(limit), outbox.all());
        List<Object> out = new ArrayList<>();
        for (Sms.Thread t : merged) {
            if (out.size() >= limit) {
                break;
            }
            if (t.providerId == 0 && !t.addresses.isEmpty()) {
                t.name = namesOf(t.addresses); // a person only the tablet's record knows
            }
            out.add(SmsJson.thread(t));
        }
        return FsApi.Reply.json(Json.obj("threads", out));
    }

    private FsApi.Reply thread(Map<String, String> q) throws IOException {
        FsApi.Reply no = needRead();
        if (no != null) {
            return no;
        }
        String key = p(q, "key", "");
        if (key.isEmpty()) {
            return FsApi.Reply.err(409, "which conversation?");
        }
        int limit = clamp(num(q, "limit", 100), 1, 500);
        long before = num(q, "before", 0);
        List<Sms.Msg> logged = outbox.forKey(key);
        List<Sms.Msg> asked;
        if (before > 0) { // the page before the one the tablet has
            asked = backend.messagesBefore(key, before, limit + 1);
            List<Sms.Msg> older = new ArrayList<>();
            for (Sms.Msg m : logged) {
                if (m.time < before) {
                    older.add(m);
                }
            }
            logged = older;
        } else {
            asked = backend.messages(key, limit + 1);
        }
        List<Sms.Msg> merged = SmsMerge.thread(asked, logged);
        boolean more = false; // one message more than asked for was fetched, to find out
        if (merged.size() > limit) {
            merged = new ArrayList<>(merged.subList(merged.size() - limit, merged.size()));
            more = true;
        }
        Sms.Thread head = backend.header(key);
        if (head == null) {
            head = new Sms.Thread();
            head.key = key;
            if (!logged.isEmpty()) {
                for (String a : logged.get(0).address.split(",")) {
                    if (!a.trim().isEmpty()) {
                        head.addresses.add(a.trim());
                    }
                }
            }
            if (head.addresses.isEmpty()) {
                head.addresses.add(key);
            }
            head.name = namesOf(head.addresses);
        }
        List<Object> msgs = new ArrayList<>();
        for (Sms.Msg m : merged) {
            msgs.add(SmsJson.msg(m));
        }
        return FsApi.Reply.json(Json.obj("key", key, "name", head.name, "addr", new ArrayList<>(head.addresses), "msgs", msgs, "more", more));
    }

    private String currentStamp() {
        return backend.stamp() + "|" + outbox.stamp();
    }

    /**
     * The stamp changes whenever any message arrives or changes. With {@code since} and {@code wait} the request holds on (at most
     * {@link #MAX_WAIT_S} seconds) until it differs from {@code since}: the tablet hears about news at once and asks far less often.
     */
    private FsApi.Reply stamp(Map<String, String> q) {
        String since = p(q, "since", "");
        long wait = waitSeconds(q);
        long end = System.nanoTime() + wait * 1_000_000_000L;
        String cur;
        try {
            while (true) {
                long seen = gate.version();
                cur = currentStamp();
                long left = (end - System.nanoTime()) / 1_000_000L;
                if (wait == 0 || since.isEmpty() || !cur.equals(since) || left <= 0) {
                    break;
                }
                gate.await(seen, Math.min(left, 1000)); // at least once a second the stamp is looked at again
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cur = currentStamp();
        }
        return FsApi.Reply.json(Json.obj("stamp", cur));
    }

    private FsApi.Reply search(Map<String, String> q) throws IOException {
        FsApi.Reply no = needRead();
        if (no != null) {
            return no;
        }
        String text = p(q, "q", "").trim();
        int limit = clamp(num(q, "limit", 30), 1, 100);
        List<Object> out = new ArrayList<>();
        if (text.length() >= 2) {
            List<Sms.Msg> found = new ArrayList<>(backend.search(text, limit));
            String low = text.toLowerCase(Locale.ROOT);
            for (Sms.Msg m : outbox.all()) { // what the tablet sent is only in its record
                if (m.body.toLowerCase(Locale.ROOT).contains(low) && !SmsMerge.duplicate(m, found)) {
                    found.add(m);
                }
            }
            Collections.sort(found, new Comparator<Sms.Msg>() {
                @Override
                public int compare(Sms.Msg a, Sms.Msg b) {
                    int c = Long.compare(b.time, a.time);
                    return c != 0 ? c : a.id.compareTo(b.id);
                }
            });
            Map<String, String> names = new HashMap<>();
            for (Sms.Thread t : SmsMerge.threads(backend.threads(300), outbox.all())) {
                names.put(t.key, t.name);
            }
            for (Sms.Msg m : found) {
                if (out.size() >= limit) {
                    break;
                }
                Sms.Hit h = new Sms.Hit();
                h.msg = m;
                String n = names.get(m.key);
                h.name = n != null && !n.isEmpty() ? n : PhoneNumbers.pretty(m.address);
                out.add(SmsJson.hit(h));
            }
        }
        return FsApi.Reply.json(Json.obj("hits", out));
    }

    private static void addAll(Sms.DeleteResult into, Sms.DeleteResult from) {
        into.doneIds.addAll(from.doneIds);
        into.failedIds.addAll(from.failedIds);
        into.doneKeys.addAll(from.doneKeys);
        into.failedKeys.addAll(from.failedKeys);
        if (!from.why.isEmpty()) {
            into.why = from.why;
        }
    }

    /**
     * Deletes messages (ids) and whole conversations (keys). What the tablet itself sent is only in its own record, which is deleted
     * here for good; the rest is up to the phone's database, which only the default messaging app may write, so the backend tries and
     * says what it could not do. The tablet hides on its own side what the phone kept.
     */
    private FsApi.Reply delete(InputStream body, long len) throws IOException {
        FsApi.Reply no = needRead();
        if (no != null) {
            return no;
        }
        Map<String, Object> m = Json.parseObject(new String(readBody(body, len, MAX_BODY), StandardCharsets.UTF_8));
        List<String> ids = SmsJson.strings(m, "ids");
        List<String> keys = SmsJson.strings(m, "keys");
        if (ids.size() > 500 || keys.size() > 50) {
            return FsApi.Reply.err(409, "That is too many at once.");
        }
        Sms.DeleteResult r = new Sms.DeleteResult();
        List<String> providerIds = new ArrayList<>();
        List<String> logIds = new ArrayList<>();
        for (String id : ids) {
            if (PROVIDER_ID.matcher(id).matches()) {
                providerIds.add(id);
            } else if (OUTBOX_ID.matcher(id).matches()) {
                logIds.add(id);
            } else {
                r.failedIds.add(id);
            }
        }
        outbox.remove(logIds);
        r.doneIds.addAll(logIds); // gone from the record, or never there: either way gone
        List<String> askKeys = new ArrayList<>();
        for (String k : keys) {
            if (k.isEmpty() || k.length() > 400) {
                r.failedKeys.add(k);
                continue;
            }
            outbox.removeKey(k);
            askKeys.add(k);
        }
        if (!providerIds.isEmpty() || !askKeys.isEmpty()) {
            addAll(r, backend.delete(providerIds, askKeys));
        }
        return FsApi.Reply.json(SmsJson.deleteResult(r));
    }

    private FsApi.Reply markRead(InputStream body, long len) throws IOException {
        FsApi.Reply no = needRead();
        if (no != null) {
            return no;
        }
        Map<String, Object> m = Json.parseObject(new String(readBody(body, len, MAX_BODY), StandardCharsets.UTF_8));
        List<String> keys = SmsJson.strings(m, "keys");
        if (keys.size() > 100) {
            return FsApi.Reply.err(409, "That is too many at once.");
        }
        return FsApi.Reply.json(SmsJson.deleteResult(backend.markRead(keys)));
    }

    private static byte[] readBody(InputStream body, long len, long max) throws IOException {
        if (len < 0 || len > max || body == null) {
            throw new IOException("that request is too large");
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream((int) len);
        byte[] buf = new byte[8192];
        long left = len;
        while (left > 0) {
            int n = body.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) {
                throw new IOException("the request stopped before its end");
            }
            bo.write(buf, 0, n);
            left -= n;
        }
        return bo.toByteArray();
    }

    private FsApi.Reply needSend() {
        if (backend.canSend()) {
            return null;
        }
        String n = backend.note();
        return FsApi.Reply.err(403, n.isEmpty() ? "this app is not allowed to send texts" : n);
    }

    private FsApi.Reply attach(Map<String, String> q, InputStream body, long len) throws IOException {
        FsApi.Reply no = needSend();
        if (no != null) {
            return no;
        }
        Sms.Staged st = staging.put(p(q, "name", "file"), p(q, "type", ""), body, len);
        return FsApi.Reply.json(Json.obj("id", st.id, "size", st.size, "name", st.name, "type", st.type));
    }

    private static FsApi.Reply failed(String why) {
        return FsApi.Reply.json(Json.obj("ok", false, "error", why));
    }

    private FsApi.Reply send(InputStream body, long len) throws IOException {
        FsApi.Reply no = needSend();
        if (no != null) {
            return no;
        }
        Map<String, Object> m = Json.parseObject(new String(readBody(body, len, MAX_BODY), StandardCharsets.UTF_8));
        List<String> rawTo = SmsJson.strings(m, "to");
        String text = Json.str(m, "text", "").replaceAll("\\s+$", "");
        String subject = Json.str(m, "subject", "").trim();
        List<String> ids = SmsJson.strings(m, "att");
        if (rawTo.isEmpty()) {
            return failed("Who is it for? Add a name or a number.");
        }
        if (rawTo.size() > MAX_RECIPIENTS) {
            return failed("That is too many people (the limit is " + MAX_RECIPIENTS + ").");
        }
        if (text.length() > MAX_TEXT) {
            return failed("That message is too long.");
        }
        Sms.Outgoing out = new Sms.Outgoing();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : rawTo) {
            String n = PhoneNumbers.forSending(raw);
            if (n.isEmpty()) {
                return failed("\"" + raw.trim() + "\" is not a phone number.");
            }
            if (seen.add(PhoneNumbers.key(n))) {
                out.to.add(n);
            }
        }
        long total = 0;
        if (ids.size() > MAX_FILES) {
            return failed("That is too many pictures or files (the limit is " + MAX_FILES + ").");
        }
        for (String id : ids) {
            Sms.Staged st = staging.get(id);
            if (st == null) {
                return failed("A picture or file is no longer on the phone. Add it again.");
            }
            total += st.size;
            out.files.add(st);
        }
        if (total > MAX_TOTAL_FILES) {
            return failed("The pictures and files together are too big (the limit is 20 MB).");
        }
        if (text.trim().isEmpty() && out.files.isEmpty()) {
            return failed("There is nothing to send.");
        }
        out.text = text;
        out.subject = subject;
        boolean mms = !out.files.isEmpty() || out.to.size() > 1 || !subject.isEmpty();

        Sms.Msg msg = new Sms.Msg();
        msg.key = PhoneNumbers.threadKey(out.to);
        msg.address = String.join(",", out.to);
        msg.body = text;
        msg.time = clock.getAsLong();
        msg.mine = true;
        msg.state = Sms.STATE_SENDING;
        msg.mms = mms;
        List<File> sources = new ArrayList<>();
        for (Sms.Staged st : out.files) {
            Sms.Part part = new Sms.Part();
            part.type = st.type;
            part.name = st.name;
            part.size = st.size;
            msg.parts.add(part);
            sources.add(st.file);
        }
        Sms.Msg logged = outbox.add(msg, sources);
        try {
            backend.send(out, logged.id);
        } catch (IOException | RuntimeException e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            outbox.setState(logged.id, Sms.STATE_FAILED, why);
            staging.release(ids);
            return FsApi.Reply.json(Json.obj("ok", false, "id", logged.id, "error", why));
        }
        staging.release(ids);
        return FsApi.Reply.json(Json.obj("ok", true, "id", logged.id));
    }

    private FsApi.Reply part(Map<String, String> q) throws IOException {
        FsApi.Reply no = needRead();
        if (no != null) {
            return no;
        }
        String id = p(q, "id", "");
        Sms.PartData d = id.startsWith("o") ? outbox.part(id) : backend.part(id);
        if (d == null) {
            return FsApi.Reply.err(404, "that picture or file is not there any more");
        }
        if (d.bytes.length > MAX_PART) {
            return FsApi.Reply.err(409, "that file is too big to open here");
        }
        int px = clamp(num(q, "s", 0), 0, 4096);
        if (px > 0 && d.type.startsWith("image/")) {
            byte[] scaled = backend.scale(d.bytes, px);
            if (scaled != null) {
                d.bytes = scaled;
                d.type = "image/jpeg";
            }
        }
        FsApi.Reply r = new FsApi.Reply();
        r.type = d.type.isEmpty() ? "application/octet-stream" : d.type;
        r.body = d.bytes;
        return r;
    }

    private FsApi.Reply contacts(Map<String, String> q) {
        List<Object> out = new ArrayList<>();
        if (backend.canContacts()) {
            for (Sms.Person p : backend.contacts(p(q, "q", ""), clamp(num(q, "limit", 20), 1, 20))) {
                out.add(SmsJson.person(p));
            }
        }
        return FsApi.Reply.json(Json.obj("people", out));
    }
}
