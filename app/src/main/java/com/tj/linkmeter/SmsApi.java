package com.tj.linkmeter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * What the phone answers when the tablet asks about texts (the /sms/* requests). It is private: while the switch "Let the tablet use
 * my texts" in the phone's app is off, everything but /sms/state is refused. The logic (merging the phone's messages with the
 * tablet's own record, checking numbers, staging files, recording what was sent) is plain Java and tested offline; what only Android
 * can do (read the messages database, send, look up contacts, scale a picture) is handed in as {@link Backend}.
 *
 * Routes: state, grant, stamp, threads, thread, attach, send, part, contacts (see docs/FILES_AND_TEXTS_PLAN.md). The caller has
 * already checked that the other device is paired. An older app answers 404 "unknown request" to all of them.
 */
final class SmsApi {
    static final int VERSION = 1;
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
    }

    private final Backend backend;
    private final SmsOutbox outbox;
    private final SmsStaging staging;
    private final LongSupplier clock;

    SmsApi(Backend backend, SmsOutbox outbox, SmsStaging staging, LongSupplier clock) {
        this.backend = backend;
        this.outbox = outbox;
        this.staging = staging;
        this.clock = clock;
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

    private static int clamp(long v, int lo, int hi) {
        return (int) Math.max(lo, Math.min(hi, v));
    }

    private Sms.State state() {
        Sms.State s = new Sms.State();
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
                    return FsApi.Reply.json(Json.obj("stamp", backend.stamp() + "|" + outbox.stamp()));
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
        List<Sms.Msg> logged = outbox.forKey(key);
        List<Sms.Msg> merged = SmsMerge.thread(backend.messages(key, limit), logged);
        if (merged.size() > limit) {
            merged = new ArrayList<>(merged.subList(merged.size() - limit, merged.size()));
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
        return FsApi.Reply.json(Json.obj("key", key, "name", head.name, "addr", new ArrayList<>(head.addresses), "msgs", msgs));
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
