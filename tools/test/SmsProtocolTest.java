package com.tj.linkmeter;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * The whole texts conversation between the two devices, offline: the real server code (SmsApi through SmsHttp, on a real socket)
 * and the real client code (RemoteSms, PeerLink, Client) against a fake phone. Privacy while the switch is off, permissions, the
 * lists and threads with the tablet's own record merged in, sending (SMS, group, with files), staging, kept pictures, contacts, the
 * stamp, an old phone app, and the outbox log and staging area on their own. Run by tools/test.sh.
 */
public final class SmsProtocolTest {
    static int checks;
    static int failed;
    static final String TOKEN = "0123456789abcdef0123456789abcdef";
    static final long T0 = 1_700_000_000_000L;

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            failed++;
            if (failed <= 40) {
                System.out.println("  FAIL: " + msg);
            }
        }
    }

    interface Call {
        void run() throws Exception;
    }

    static boolean throwsOf(Class<? extends Throwable> kind, Call c) {
        try {
            c.run();
            return false;
        } catch (Throwable t) {
            return kind.isInstance(t);
        }
    }

    static String messageOf(Call c) {
        try {
            c.run();
            return null;
        } catch (Throwable t) {
            return String.valueOf(t.getMessage());
        }
    }

    // ---------------------------------------------------------------- the fake phone

    static final class Clock implements LongSupplier {
        volatile long now = T0;

        @Override
        public long getAsLong() {
            return now;
        }
    }

    static final class Sent {
        final List<String> to = new ArrayList<>();
        String text = "";
        String subject = "";
        String msgId = "";
        final List<byte[]> files = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final List<String> types = new ArrayList<>();
    }

    static final class Fake implements SmsApi.Backend {
        volatile boolean on = true;
        volatile boolean read = true;
        volatile boolean send = true;
        volatile boolean contacts = true;
        volatile String note = "";
        volatile String failSend;
        volatile String stamp = "s1";
        final AtomicInteger grants = new AtomicInteger();
        final List<Sms.Msg> db = new CopyOnWriteArrayList<>();
        final Map<String, Sms.PartData> parts = new HashMap<>();
        final Map<String, String> names = new HashMap<>();
        final List<Sms.Person> people = new ArrayList<>();
        final List<Sent> sent = new CopyOnWriteArrayList<>();

        @Override
        public boolean on() {
            return on;
        }

        @Override
        public boolean canRead() {
            return read;
        }

        @Override
        public boolean canSend() {
            return send;
        }

        @Override
        public boolean canContacts() {
            return contacts;
        }

        @Override
        public String note() {
            return note;
        }

        @Override
        public void grant() {
            grants.incrementAndGet();
            read = true;
            send = true;
            contacts = true;
            note = "";
        }

        @Override
        public String stamp() {
            return stamp;
        }

        private List<Sms.Msg> sorted() {
            List<Sms.Msg> l = new ArrayList<>(db);
            Collections.sort(l, new Comparator<Sms.Msg>() {
                @Override
                public int compare(Sms.Msg a, Sms.Msg b) {
                    int c = Long.compare(a.time, b.time);
                    return c != 0 ? c : a.id.compareTo(b.id);
                }
            });
            return l;
        }

        private Map<String, Sms.Thread> grouped() {
            Map<String, Sms.Thread> by = new LinkedHashMap<>();
            long pid = 1;
            for (Sms.Msg m : sorted()) {
                Sms.Thread t = by.get(m.key);
                if (t == null) {
                    t = new Sms.Thread();
                    t.key = m.key;
                    t.providerId = pid++;
                    by.put(m.key, t);
                }
                t.count++;
                t.snippet = m.body.isEmpty() && !m.parts.isEmpty() ? "Attachment" : m.body;
                t.time = m.time;
                t.unread |= m.unread;
                t.attachment |= !m.parts.isEmpty();
                if (!t.addresses.contains(m.address)) {
                    t.addresses.add(m.address);
                }
            }
            for (Sms.Thread t : by.values()) {
                String n = names.get(t.key);
                t.name = n != null ? n : PhoneNumbers.pretty(t.addresses.get(0));
            }
            return by;
        }

        @Override
        public List<Sms.Thread> threads(int limit) {
            List<Sms.Thread> l = new ArrayList<>(grouped().values());
            Collections.sort(l, new Comparator<Sms.Thread>() {
                @Override
                public int compare(Sms.Thread a, Sms.Thread b) {
                    return Long.compare(b.time, a.time);
                }
            });
            return new ArrayList<>(l.subList(0, Math.min(limit, l.size())));
        }

        @Override
        public List<Sms.Msg> messages(String key, int limit) {
            List<Sms.Msg> l = new ArrayList<>();
            for (Sms.Msg m : sorted()) {
                if (m.key.equals(key)) {
                    l.add(m.copy());
                }
            }
            l = new ArrayList<>(l.subList(Math.max(0, l.size() - limit), l.size()));
            Collections.reverse(l); // the API must not depend on the order
            return l;
        }

        @Override
        public Sms.Thread header(String key) {
            return grouped().get(key);
        }

        @Override
        public String nameFor(String address) {
            String n = names.get(PhoneNumbers.key(address));
            return n == null ? "" : n;
        }

        @Override
        public List<Sms.Person> contacts(String query, int limit) {
            List<Sms.Person> out = new ArrayList<>();
            for (Sms.Person p : people) {
                if (out.size() < limit && (p.name.toLowerCase().contains(query.toLowerCase()) || p.number.contains(query))) {
                    out.add(p);
                }
            }
            return out;
        }

        @Override
        public Sms.PartData part(String id) {
            Sms.PartData d = parts.get(id);
            if (d == null) {
                return null;
            }
            Sms.PartData c = new Sms.PartData();
            c.type = d.type;
            c.name = d.name;
            c.bytes = d.bytes.clone();
            return c;
        }

        @Override
        public void send(Sms.Outgoing out, String msgId) throws IOException {
            if (failSend != null) {
                throw new IOException(failSend);
            }
            Sent s = new Sent();
            s.to.addAll(out.to);
            s.text = out.text;
            s.subject = out.subject;
            s.msgId = msgId;
            for (Sms.Staged st : out.files) {
                s.files.add(Files.readAllBytes(st.file.toPath())); // the staged copy is deleted right after this returns
                s.names.add(st.name);
                s.types.add(st.type);
            }
            sent.add(s);
        }

        @Override
        public byte[] scale(byte[] image, int px) {
            return image.length == 0 ? null : new byte[] {(byte) 0xff, (byte) 0xd8, (byte) px, (byte) image.length};
        }
    }

    // ---------------------------------------------------------------- a test server

    static final class Server implements Closeable {
        final ServerSocket ss;
        final Fake fake = new Fake();
        final Clock clock = new Clock();
        final SmsOutbox outbox;
        final SmsStaging staging;
        final SmsApi api;
        final File dir;
        /** An app too old for texts. */
        volatile boolean legacy;

        Server() throws IOException {
            dir = Files.createTempDirectory("sms").toFile();
            outbox = new SmsOutbox(new File(dir, "outbox"), clock);
            staging = new SmsStaging(new File(dir, "staging"), clock);
            api = new SmsApi(fake, outbox, staging, clock);
            ss = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread t = new Thread(() -> {
                while (!ss.isClosed()) {
                    try {
                        final Socket s = ss.accept();
                        Thread h = new Thread(() -> handle(s));
                        h.setDaemon(true);
                        h.start();
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            t.setDaemon(true);
            t.start();
            Client.portOverride = ss.getLocalPort();
        }

        void handle(Socket s) {
            try {
                Http.Req r = Http.read(s);
                OutputStream out = s.getOutputStream();
                if (!TOKEN.equals(r.token)) {
                    Http.err(out, 401, "not paired");
                } else if (legacy) {
                    Http.err(out, 404, "unknown request");
                } else {
                    SmsHttp.serve(api, r, out);
                }
                out.flush();
            } catch (IOException e) {
                // the client went away
            } finally {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // closed
                }
            }
        }

        RemoteSms sms() {
            return new RemoteSms(new PeerLink("127.0.0.1", TOKEN));
        }

        @Override
        public void close() throws IOException {
            ss.close();
            deleteTree(dir);
        }
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }

    static Sms.Msg msg(String id, String key, String address, boolean mine, String body, long time) {
        Sms.Msg m = new Sms.Msg();
        m.id = id;
        m.key = key;
        m.address = address;
        m.mine = mine;
        m.body = body;
        m.time = time;
        return m;
    }

    static InputStream stream(byte[] b) {
        return new ByteArrayInputStream(b);
    }

    static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    static List<String> list(String... s) {
        return new ArrayList<>(Arrays.asList(s));
    }

    static String ids(List<Sms.Msg> l) {
        StringBuilder sb = new StringBuilder();
        for (Sms.Msg m : l) {
            sb.append(sb.length() > 0 ? "," : "").append(m.id);
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        stateAndPrivacy();
        listsAndThreads();
        sending();
        parts();
        contactsAndStamp();
        outboxOnItsOwn();
        stagingOnItsOwn();
        jsonRoundTrips();
        randomStories();
        parallelSends();
        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------- state, privacy, permissions, an old app

    static void stateAndPrivacy() throws Exception {
        try (Server srv = new Server()) {
            final RemoteSms sms = srv.sms();
            Sms.State st = sms.state();
            check(st.supported && st.on && st.read && st.send && st.contacts && st.note.isEmpty(), "state: everything is on");

            // the switch is off: only the state is answered, nothing is read, sent or recorded
            srv.fake.on = false;
            st = sms.state();
            check(st.supported && !st.on, "state with the switch off still answers, and says it is off");
            check(throwsOf(RemoteSms.Off.class, () -> sms.threads(10)), "off: threads are refused");
            check(throwsOf(RemoteSms.Off.class, () -> sms.thread("555", 10)), "off: a thread is refused");
            check(throwsOf(RemoteSms.Off.class, sms::stamp), "off: the stamp is refused");
            check(throwsOf(RemoteSms.Off.class, () -> sms.attach("a.txt", "text/plain", stream(new byte[] {1, 2}), 2, null)), "off: attach is refused");
            check(throwsOf(RemoteSms.Off.class, () -> sms.send(list("+15551234567"), "", "hi", new ArrayList<String>())), "off: send is refused");
            check(throwsOf(RemoteSms.Off.class, () -> sms.part("p1", 0, 1000)), "off: parts are refused");
            check(throwsOf(RemoteSms.Off.class, () -> sms.contacts("a")), "off: contacts are refused");
            check(throwsOf(RemoteSms.Off.class, sms::grant) && srv.fake.grants.get() == 0, "off: even granting permissions is refused");
            check(srv.fake.sent.isEmpty() && srv.outbox.size() == 0 && srv.staging.count() == 0, "off: nothing was sent, recorded or kept");
            Client.Resp raw = Client.call("127.0.0.1", "GET", "/sms/threads", TOKEN, 3000, 5000);
            check(raw.code == 403 && raw.body.contains("\"off\""), "off: a plain 403 with the reason: " + raw.code + " " + raw.body);
            check(new RemoteSms.Off().getMessage().contains("Let the tablet use my texts"), "off: the message tells where the switch is");
            srv.fake.on = true;

            // not paired
            Client.Resp unpaired = Client.call("127.0.0.1", "GET", "/sms/state", "wrong", 3000, 5000);
            check(unpaired.code == 401, "an unpaired tablet is refused: " + unpaired.code);
            RemoteSms stranger = new RemoteSms(new PeerLink("127.0.0.1", "wrong"));
            String why = messageOf(stranger::state);
            check(why != null && why.contains("not paired"), "an unpaired tablet gets a clear message: " + why);

            // permissions missing: the note is the reason; contacts just come back empty
            Sms.Person someone = new Sms.Person();
            someone.name = "Ann";
            someone.number = "+15551234567";
            srv.fake.people.add(someone);
            check(sms.contacts("ann").size() == 1, "with permission the contact is found");
            srv.fake.read = false;
            srv.fake.send = false;
            srv.fake.contacts = false;
            srv.fake.note = "Texts need permission on the phone.";
            st = sms.state();
            check(st.on && !st.read && !st.send && !st.contacts && st.note.equals("Texts need permission on the phone."), "state: permissions missing, with the note");
            check("Texts need permission on the phone.".equals(messageOf(() -> sms.threads(10))), "no permission: threads say why");
            check("Texts need permission on the phone.".equals(messageOf(() -> sms.thread("555", 10))), "no permission: a thread says why");
            check("Texts need permission on the phone.".equals(messageOf(() -> sms.part("p1", 0, 1000))), "no permission: parts say why");
            check("Texts need permission on the phone.".equals(messageOf(() -> sms.attach("a", "x/y", stream(new byte[] {1}), 1, null))), "no permission: attach says why");
            check("Texts need permission on the phone.".equals(messageOf(() -> sms.send(list("+15551234567"), "", "hi", new ArrayList<String>()))), "no permission: send says why");
            check(sms.contacts("a").isEmpty(), "no permission: contacts are empty, not an error");
            check(srv.fake.sent.isEmpty() && srv.outbox.size() == 0, "no permission: nothing was recorded");
            st = sms.grant();
            check(srv.fake.grants.get() == 1 && st.read && st.send && st.contacts && st.note.isEmpty(), "grant: the phone is asked and answers with the new state");

            // an app too old for texts
            srv.legacy = true;
            st = sms.state();
            check(!st.supported, "an old app: state says not supported instead of failing");
            check(throwsOf(RemoteSms.TooOld.class, () -> sms.threads(10)) && throwsOf(RemoteSms.TooOld.class, () -> sms.thread("5", 10)), "an old app: the rest say so");
            check(new RemoteSms.TooOld().getMessage().contains("Update phone from this tablet"), "an old app: the message tells what to do");
            srv.legacy = false;

            // an unknown route and a missing argument
            Client.Resp unknown = Client.call("127.0.0.1", "GET", "/sms/nothing", TOKEN, 3000, 5000);
            check(unknown.code == 404 && unknown.body.contains("unknown request"), "an unknown texts route is 404 unknown request");
            Client.Resp noKey = Client.call("127.0.0.1", "GET", "/sms/thread", TOKEN, 3000, 5000);
            check(noKey.code == 409 && noKey.body.contains("which conversation"), "a thread without a key is refused: " + noKey.body);
        }
    }

    // ---------------------------------------------------------------- the list and the thread, with the tablet's own record merged in

    static void listsAndThreads() throws Exception {
        try (Server srv = new Server()) {
            RemoteSms sms = srv.sms();
            Fake f = srv.fake;
            f.names.put("5551234567", "Ann Lee");
            f.names.put("5559990000", "Bob");
            f.names.put("5550001111", "Cy");
            f.db.add(msg("s1", "5551234567", "+15551234567", false, "hello", T0 - 600_000));
            f.db.add(msg("s2", "5551234567", "+15551234567", true, "hi there", T0 - 590_000));
            Sms.Msg unread = msg("s3", "5559990000", "+15559990000", false, "yo", T0 - 300_000);
            unread.unread = true;
            f.db.add(unread);
            Sms.Msg pic = msg("m4", "5559990000", "+15559990000", false, "", T0 - 200_000);
            pic.mms = true;
            Sms.Part pp = new Sms.Part();
            pp.id = "p7";
            pp.type = "image/jpeg";
            pp.name = "a.jpg";
            pp.size = 3;
            pic.parts.add(pp);
            f.db.add(pic);
            f.db.add(msg("s5", "5553334444,5557778888", "+15553334444", false, "group hello", T0 - 100_000));

            List<Sms.Thread> ts = sms.threads(60);
            check(ts.size() == 3 && ts.get(0).key.equals("5553334444,5557778888") && ts.get(1).key.equals("5559990000") && ts.get(2).key.equals("5551234567"),
                    "threads: newest first: " + ts.size());
            Sms.Thread bob = ts.get(1);
            check(bob.name.equals("Bob") && bob.snippet.equals("Attachment") && bob.attachment && bob.count == 2 && bob.unread && bob.providerId > 0
                    && bob.addresses.equals(Arrays.asList("+15559990000")), "threads: one thread's fields survive the trip");
            check(sms.threads(2).size() == 2, "threads: the limit is applied");

            // the tablet sends something: it appears at the top of its conversation, a new person gets a conversation of their own
            f.names.put("5550001111", "Cy");
            f.names.remove("5550001111");
            Sms.SendResult r1 = sms.send(list("(555) 123-4567"), "", "from the tablet", new ArrayList<String>());
            check(r1.ok && r1.id.equals("o1"), "send: ok with an id: " + r1.ok + " " + r1.id);
            srv.clock.now = T0 + 5_000;
            Sms.SendResult r2 = sms.send(list("555-000-1111"), "", "to somebody new", new ArrayList<String>());
            check(r2.ok && r2.id.equals("o2"), "send to a new person");
            ts = sms.threads(60);
            check(ts.size() == 4 && ts.get(0).key.equals("5550001111") && ts.get(1).key.equals("5551234567"), "threads: the sent messages move their conversations up: " + ts.get(0).key);
            check(ts.get(0).providerId == 0 && ts.get(0).count == 1 && ts.get(0).name.equals("(555) 000-1111") && ts.get(0).snippet.equals("to somebody new"),
                    "threads: a person only the tablet's record knows: " + ts.get(0).name);
            check(ts.get(1).name.equals("Ann Lee") && ts.get(1).snippet.equals("from the tablet") && ts.get(1).time == T0, "threads: a sent message takes over the snippet, the name stays");
            f.names.put("5550001111", "Cy");
            check(sms.threads(60).get(0).name.equals("Cy"), "threads: a contact's name is used for a person only the record knows");
            check(sms.threads(2).size() == 2 && sms.threads(3).size() == 3, "threads: the limit applies to the merged list, record-only people included");

            // the thread: oldest first, both sources merged, the header, the limit
            Sms.Convo c = sms.thread("5551234567", 100);
            check(c.head.name.equals("Ann Lee") && c.head.addresses.equals(Arrays.asList("+15551234567")) && c.head.key.equals("5551234567"), "thread: the header");
            check(ids(c.msgs).equals("s1,s2,o1"), "thread: merged oldest first: " + ids(c.msgs));
            Sms.Msg mine = c.msgs.get(2);
            check(mine.mine && mine.body.equals("from the tablet") && mine.state == Sms.STATE_SENDING && !mine.mms && mine.key.equals("5551234567"), "thread: the sent message's fields");
            check(ids(sms.thread("5551234567", 2).msgs).equals("s2,o1"), "thread: the limit keeps the newest messages: " + ids(sms.thread("5551234567", 2).msgs));
            Sms.Convo bobs = sms.thread("5559990000", 100);
            check(bobs.msgs.size() == 2 && bobs.msgs.get(1).parts.size() == 1 && bobs.msgs.get(1).parts.get(0).id.equals("p7") && bobs.msgs.get(1).parts.get(0).type.equals("image/jpeg")
                    && bobs.msgs.get(1).mms && bobs.msgs.get(0).unread, "thread: a multimedia message and its part, unread flag");
            Sms.Convo cy = sms.thread("5550001111", 100);
            check(cy.head.name.equals("Cy") && cy.head.addresses.equals(Arrays.asList("+15550001111")) && cy.msgs.size() == 1, "thread: a conversation only the record has: " + cy.head.name);
            Sms.Convo nobody = sms.thread("123", 100);
            check(nobody.msgs.isEmpty() && nobody.head.name.equals("123") && nobody.head.addresses.equals(Arrays.asList("123")), "thread: an unknown conversation is empty with a plain header");
            Sms.Convo group = sms.thread("5553334444,5557778888", 100);
            check(group.msgs.size() == 1 && group.msgs.get(0).body.equals("group hello"), "thread: a group's key travels intact");

            // the phone's database also holds the message the tablet sent (some phones do): it is not shown twice
            f.db.add(msg("s9", "5551234567", "+15551234567", true, "from the tablet", T0 + 2_000));
            check(ids(sms.thread("5551234567", 100).msgs).equals("s1,s2,s9"), "thread: a copy the phone's database has too is not shown twice: " + ids(sms.thread("5551234567", 100).msgs));
            Sms.Thread t0 = null;
            for (Sms.Thread t : sms.threads(60)) {
                if (t.key.equals("5551234567")) {
                    t0 = t;
                }
            }
            check(t0 != null && t0.count == 3, "threads: the count is the phone's own (three messages in its database): " + (t0 == null ? -1 : t0.count));
        }
    }

    // ---------------------------------------------------------------- sending

    static void sending() throws Exception {
        try (Server srv = new Server()) {
            RemoteSms sms = srv.sms();
            Fake f = srv.fake;

            // a plain text to one person
            Sms.SendResult r = sms.send(list("(555) 123-4567"), "", "Hello there  \n", new ArrayList<String>());
            check(r.ok && r.id.equals("o1") && r.error.isEmpty(), "send: ok");
            check(f.sent.size() == 1 && f.sent.get(0).to.equals(Arrays.asList("+15551234567")) && f.sent.get(0).text.equals("Hello there") && f.sent.get(0).files.isEmpty()
                    && f.sent.get(0).msgId.equals("o1"), "send: the phone is handed a clean number, the text without trailing blanks, and the record's id");
            Sms.Msg m = sms.thread("5551234567", 10).msgs.get(0);
            check(m.state == Sms.STATE_SENDING && !m.mms && m.mine, "send: recorded as sending, not multimedia");
            String stamp1 = sms.stamp();
            srv.outbox.setState("o1", Sms.STATE_SENT, "");
            check(sms.thread("5551234567", 10).msgs.get(0).state == Sms.STATE_SENT, "send: a result from the phone's receiver shows");
            check(!sms.stamp().equals(stamp1), "send: the stamp changed with it");
            srv.outbox.setState("o1", Sms.STATE_FAILED, "no signal");
            m = sms.thread("5551234567", 10).msgs.get(0);
            check(m.state == Sms.STATE_FAILED && m.error.equals("no signal"), "send: a failure is kept with its reason");
            srv.outbox.setState("nothing", Sms.STATE_SENT, "");
            check(srv.outbox.size() == 1, "send: a result for an unknown id changes nothing");

            // refusals, each with its reason, and nothing is recorded
            int sentBefore = f.sent.size();
            int loggedBefore = srv.outbox.size();
            String[][] bad = {
                {"", "Who is it for"}, {"abc", "is not a phone number"}, {"12", "is not a phone number"}, {"+1234567890123456789", "is not a phone number"},
            };
            for (String[] b : bad) {
                Sms.SendResult x = sms.send(b[0].isEmpty() ? new ArrayList<String>() : list(b[0]), "", "hi", new ArrayList<String>());
                check(!x.ok && x.error.contains(b[1]), "send refused (" + b[0] + "): " + x.error);
            }
            Sms.SendResult nothing = sms.send(list("+15551234567"), "", "   \n ", new ArrayList<String>());
            check(!nothing.ok && nothing.error.contains("nothing to send"), "send refused: nothing to send: " + nothing.error);
            Sms.SendResult lost = sms.send(list("+15551234567"), "", "hi", list("a999"));
            check(!lost.ok && lost.error.contains("no longer on the phone"), "send refused: a file that is not staged: " + lost.error);
            List<String> many = new ArrayList<>();
            for (int i = 0; i < SmsApi.MAX_RECIPIENTS + 1; i++) {
                many.add("+1555000" + (1000 + i));
            }
            check(!sms.send(many, "", "hi", new ArrayList<String>()).ok, "send refused: too many people");
            StringBuilder longText = new StringBuilder();
            for (int i = 0; i < SmsApi.MAX_TEXT + 1; i++) {
                longText.append('x');
            }
            check(!sms.send(list("+15551234567"), "", longText.toString(), new ArrayList<String>()).ok, "send refused: a text that is too long");
            check(f.sent.size() == sentBefore && srv.outbox.size() == loggedBefore, "refusals: nothing was sent or recorded");

            // the same person spelled two ways is one recipient
            Sms.SendResult dup = sms.send(list("(555) 222-3333", "+1 555 222 3333", "5552223333"), "", "once", new ArrayList<String>());
            check(dup.ok && f.sent.get(f.sent.size() - 1).to.equals(Arrays.asList("+15552223333")), "send: one person under three spellings is one recipient: " + f.sent.get(f.sent.size() - 1).to);
            check(!sms.thread("5552223333", 10).msgs.get(0).mms, "send: and so it is a plain text");

            // files: attach, then send; the phone gets every byte; the record keeps copies; the staged copies go
            byte[] jpg = randomBytes(100_000, 1);
            byte[] pdf = randomBytes(33_333, 2);
            final long[] last = {0};
            final boolean[] rising = {true};
            String a1 = sms.attach("My Photo (1).jpg", "image/jpeg", stream(jpg), jpg.length, done -> {
                rising[0] &= done >= last[0];
                last[0] = done;
            });
            check(last[0] == jpg.length && rising[0], "attach: progress reaches the size, never going back: " + last[0]);
            String a2 = sms.attach("doc.pdf", "application/pdf", stream(pdf), pdf.length, null);
            check(a1.startsWith("a") && a2.startsWith("a") && !a1.equals(a2) && srv.staging.count() == 2, "attach: two staged files with their own ids");
            Sms.SendResult mm = sms.send(list("+15553334444"), "", "see attached", list(a1, a2));
            check(mm.ok, "send with files: ok " + mm.error);
            Sent s = f.sent.get(f.sent.size() - 1);
            check(s.files.size() == 2 && Arrays.equals(s.files.get(0), jpg) && Arrays.equals(s.files.get(1), pdf), "send with files: the phone gets every byte, in order");
            check(s.names.get(0).equals("My Photo (1).jpg") && s.types.get(0).equals("image/jpeg") && s.types.get(1).equals("application/pdf"), "send with files: names and types: " + s.names);
            check(srv.staging.count() == 0, "send with files: the staged copies are gone");
            Sms.Msg sentMsg = sms.thread("5553334444", 10).msgs.get(0);
            check(sentMsg.mms && sentMsg.parts.size() == 2 && sentMsg.parts.get(0).id.equals(mm.id + ".0") && sentMsg.parts.get(1).size == pdf.length
                    && sentMsg.parts.get(0).type.equals("image/jpeg") && sentMsg.parts.get(0).name.equals("My Photo (1).jpg"), "send with files: recorded as multimedia with its parts");
            check(Arrays.equals(sms.part(mm.id + ".0", 0, 1_000_000), jpg) && Arrays.equals(sms.part(mm.id + ".1", 0, 1_000_000), pdf), "send with files: the pictures are kept and can be fetched whole");
            byte[] thumb = sms.part(mm.id + ".0", 64, 1_000_000);
            check(thumb.length == 4 && thumb[0] == (byte) 0xff && thumb[1] == (byte) 0xd8 && thumb[2] == 64, "send with files: a small picture is made on the phone for an image");
            check(Arrays.equals(sms.part(mm.id + ".1", 64, 1_000_000), pdf), "send with files: a file that is not an image is not scaled");

            // a group is one multimedia message to everybody, one conversation
            Sms.SendResult grp = sms.send(list("+15551110000", "(555) 222-0000"), "", "group text", new ArrayList<String>());
            check(grp.ok && f.sent.get(f.sent.size() - 1).to.equals(Arrays.asList("+15551110000", "+15552220000")), "group: both recipients");
            Sms.Msg gm = sms.thread("5551110000,5552220000", 10).msgs.get(0);
            check(gm.mms && gm.key.equals("5551110000,5552220000") && gm.address.equals("+15551110000,+15552220000"), "group: one multimedia conversation with a sorted key: " + gm.key);
            Sms.Thread inList = null;
            for (Sms.Thread t : sms.threads(60)) {
                if (t.key.equals("5551110000,5552220000")) {
                    inList = t;
                }
            }
            check(inList != null && inList.addresses.equals(Arrays.asList("+15551110000", "+15552220000")) && inList.name.equals("(555) 111-0000, (555) 222-0000"),
                    "group: the list shows everybody too: " + (inList == null ? "missing" : inList.name + " " + inList.addresses));
            Sms.Convo gc = sms.thread("5551110000,5552220000", 10);
            check(gc.head.addresses.equals(Arrays.asList("+15551110000", "+15552220000")) && gc.head.name.equals("(555) 111-0000, (555) 222-0000"), "group: the header lists everybody: " + gc.head.name);
            // a group typed in another order is the same conversation
            Sms.SendResult grp2 = sms.send(list("(555) 222-0000", "+15551110000"), "", "again", new ArrayList<String>());
            check(grp2.ok && sms.thread("5551110000,5552220000", 10).msgs.size() == 2, "group: the same people in another order are the same conversation");

            // the phone cannot send: the record says failed, the answer says why, and the files do not pile up
            f.failSend = "no signal";
            byte[] small = randomBytes(1000, 3);
            String a3 = sms.attach("x.jpg", "image/jpeg", stream(small), small.length, null);
            Sms.SendResult bad2 = sms.send(list("+15554445555"), "", "will fail", list(a3));
            check(!bad2.ok && bad2.error.equals("no signal") && !bad2.id.isEmpty(), "failed send: the answer says why and gives the id: " + bad2.error);
            Sms.Msg fm = sms.thread("5554445555", 10).msgs.get(0);
            check(fm.state == Sms.STATE_FAILED && fm.error.equals("no signal") && fm.body.equals("will fail"), "failed send: the record shows a failed message");
            check(srv.staging.count() == 0, "failed send: the staged file is released");
            f.failSend = null;

            // attach refusals
            check(messageOf(() -> sms.attach("e", "x/y", stream(new byte[0]), 0, null)).contains("empty"), "attach: an empty file is refused");
            check(srv.staging.count() == 0, "attach: nothing was kept");
        }
    }

    // ---------------------------------------------------------------- parts and contacts

    static void parts() throws Exception {
        try (Server srv = new Server()) {
            RemoteSms sms = srv.sms();
            Sms.PartData pic = new Sms.PartData();
            pic.type = "image/png";
            pic.name = "p.png";
            pic.bytes = randomBytes(5000, 5);
            srv.fake.parts.put("p1", pic);
            Sms.PartData doc = new Sms.PartData();
            doc.type = "application/pdf";
            doc.bytes = randomBytes(300, 6);
            srv.fake.parts.put("p2", doc);
            check(Arrays.equals(sms.part("p1", 0, 100_000), pic.bytes), "part: the whole picture");
            byte[] small = sms.part("p1", 160, 100_000);
            check(small.length == 4 && small[2] == (byte) 160, "part: a picture scaled on the phone");
            Client.Resp scaled = Client.call("127.0.0.1", "GET", "/sms/part?id=p1&s=160", TOKEN, 3000, 5000);
            Client.Stream st = Client.get("127.0.0.1", "/sms/part?id=p1&s=160", TOKEN, 3000, 5000);
            check(scaled.code == 200 && st.code == 200, "part: a plain 200");
            st.close();
            check(Arrays.equals(sms.part("p2", 160, 100_000), doc.bytes), "part: a file is not scaled");
            check(messageOf(() -> sms.part("nope", 0, 1000)).contains("not there any more"), "part: an unknown id says so");
            check(messageOf(() -> sms.part("o99.0", 0, 1000)).contains("not there any more"), "part: an unknown kept id says so");
            check(messageOf(() -> sms.part("o1.0/../../x", 0, 1000)).contains("not there any more"), "part: a made-up kept id is not looked up as a path");
            check(messageOf(() -> sms.part("p1", 0, 100)).contains("too big"), "part: the tablet's own size limit is applied");
            Client.Resp huge = Client.call("127.0.0.1", "GET", "/sms/part?id=p1&s=99999", TOKEN, 3000, 5000);
            check(huge.code == 200, "part: a huge size is clamped, not an error");
        }
    }

    static void contactsAndStamp() throws Exception {
        try (Server srv = new Server()) {
            RemoteSms sms = srv.sms();
            String[][] people = {{"Ann Lee", "+15551234567"}, {"Anna Bell", "+15559998888"}, {"Bob", "+15550001111"}, {"Zed", "+442079460958"}};
            for (String[] p : people) {
                Sms.Person x = new Sms.Person();
                x.name = p[0];
                x.number = p[1];
                srv.fake.people.add(x);
            }
            List<Sms.Person> an = sms.contacts("ann");
            check(an.size() == 2 && an.get(0).name.equals("Ann Lee") && an.get(0).number.equals("+15551234567"), "contacts: a search by name");
            check(sms.contacts("0001").size() == 1 && sms.contacts("0001").get(0).name.equals("Bob"), "contacts: a search by number");
            check(sms.contacts("").size() == 4, "contacts: an empty search lists some");
            check(sms.contacts("qqq").isEmpty(), "contacts: nothing found");
            check(sms.contacts("Ann Lee & \u00e9").isEmpty(), "contacts: odd characters in the search are fine");
            for (int i = 0; i < 50; i++) {
                Sms.Person x = new Sms.Person();
                x.name = "Many " + i;
                x.number = "+1555000" + (2000 + i);
                srv.fake.people.add(x);
            }
            check(sms.contacts("many").size() == 20, "contacts: at most 20");

            // the stamp changes with the phone's database and with the tablet's record
            String s0 = sms.stamp();
            check(sms.stamp().equals(s0), "stamp: steady while nothing changes");
            srv.fake.stamp = "s2";
            String s1 = sms.stamp();
            check(!s1.equals(s0), "stamp: changes with the phone's database");
            sms.send(list("+15551234567"), "", "x", new ArrayList<String>());
            check(!sms.stamp().equals(s1), "stamp: changes when the tablet sends");
        }
    }

    // ---------------------------------------------------------------- the outbox log on its own

    static void outboxOnItsOwn() throws Exception {
        File dir = Files.createTempDirectory("outbox").toFile();
        try {
            Clock clock = new Clock();
            SmsOutbox ob = new SmsOutbox(dir, clock);
            File a = new File(dir, "src-a.bin");
            Files.write(a.toPath(), randomBytes(2000, 9));
            Sms.Msg m = msg("", "555", "+1555", true, "with a file \u00e9 \"quoted\"\nnew line", T0);
            m.mms = true;
            Sms.Part p = new Sms.Part();
            p.type = "image/jpeg";
            p.name = "a.jpg";
            m.parts.add(p);
            Sms.Msg e1 = ob.add(m, Arrays.asList(a));
            check(e1.id.equals("o1") && e1.parts.get(0).id.equals("o1.0") && e1.parts.get(0).size == 2000 && e1.state == Sms.STATE_NONE, "outbox: ids and sizes are made on add");
            check(m.id.isEmpty() && m.parts.get(0).id.isEmpty(), "outbox: the caller's message is not changed");
            a.delete();
            check(Arrays.equals(ob.part("o1.0").bytes, randomBytes(2000, 9)), "outbox: the file was copied, the source may go");
            Sms.Msg m2 = msg("", "777", "+1777", true, "second", T0 + 1000);
            Sms.Msg e2 = ob.add(m2, new ArrayList<File>());
            ob.setState(e2.id, Sms.STATE_DELIVERED, "");
            ob.setState(e1.id, Sms.STATE_FAILED, "boom \"x\"");

            // results that arrive in any order never move a message backwards (on an outbox of its own)
            File dirA = Files.createTempDirectory("outboxA").toFile();
            try {
                SmsOutbox oa = new SmsOutbox(dirA, clock);
                Sms.Msg adv = oa.add(msg("", "adv", "+1999", true, "advance", T0 + 5000), new ArrayList<File>());
                oa.advance(adv.id, Sms.STATE_DELIVERED, "");
                oa.advance(adv.id, Sms.STATE_SENT, "");
                check(oa.forKey("adv").get(0).state == Sms.STATE_DELIVERED, "outbox: a late 'sent' does not undo 'delivered'");
                oa.advance(adv.id, Sms.STATE_FAILED, "late failure");
                check(oa.forKey("adv").get(0).state == Sms.STATE_DELIVERED && oa.forKey("adv").get(0).error.isEmpty(), "outbox: a late failure does not undo 'delivered'");
                Sms.Msg adv2 = oa.add(msg("", "adv2", "+1998", true, "advance 2", T0 + 5001), new ArrayList<File>());
                oa.advance(adv2.id, Sms.STATE_SENT, "");
                check(oa.forKey("adv2").get(0).state == Sms.STATE_SENT, "outbox: sending moves to sent");
                oa.advance(adv2.id, Sms.STATE_FAILED, "no service");
                check(oa.forKey("adv2").get(0).state == Sms.STATE_FAILED && oa.forKey("adv2").get(0).error.equals("no service"), "outbox: a failure after 'sent' is shown");
                oa.advance(adv2.id, Sms.STATE_SENT, "");
                oa.advance(adv2.id, Sms.STATE_DELIVERED, "");
                check(oa.forKey("adv2").get(0).state == Sms.STATE_FAILED, "outbox: neither a late 'sent' nor 'delivered' undoes a failure");
                oa.advance("nothing", Sms.STATE_SENT, "");
                Sms.Msg adv3 = oa.add(msg("", "adv3", "+1997", true, "advance 3", T0 + 5002), new ArrayList<File>());
                oa.advance(adv3.id, Sms.STATE_SENT, "");
                oa.advance(adv3.id, Sms.STATE_SENT, "");
                oa.advance(adv3.id, Sms.STATE_DELIVERED, "");
                check(oa.forKey("adv3").get(0).state == Sms.STATE_DELIVERED, "outbox: sent, sent again, then delivered");
            } finally {
                deleteTree(dirA);
            }

            // the log survives a restart
            SmsOutbox again = new SmsOutbox(dir, clock);
            List<Sms.Msg> all = again.all();
            check(all.size() == 2 && all.get(0).id.equals("o1") && all.get(0).state == Sms.STATE_FAILED && all.get(0).error.equals("boom \"x\"")
                    && all.get(0).body.equals("with a file \u00e9 \"quoted\"\nnew line") && all.get(0).parts.size() == 1 && all.get(0).mms, "outbox: reloaded with states, text and parts");
            check(all.get(1).state == Sms.STATE_DELIVERED, "outbox: a reloaded state");
            check(again.add(msg("", "888", "+1888", true, "third", T0 + 2000), new ArrayList<File>()).id.equals("o3"), "outbox: ids go on counting after a restart");
            check(Arrays.equals(again.part("o1.0").bytes, randomBytes(2000, 9)), "outbox: kept files survive a restart");
            check(again.part("o1.0").type.equals("image/jpeg") && again.part("o1.0").name.equals("a.jpg"), "outbox: and their type and name");
            check(again.part("o1.5") == null && again.part("o9.0") == null && again.part("../x") == null && again.part("o1.0/x") == null, "outbox: unknown or odd part ids give null");
            check(again.forKey("555").size() == 1 && again.forKey("nobody").isEmpty(), "outbox: forKey");
            List<Sms.Msg> copyList = again.all();
            copyList.get(0).body = "changed outside";
            check(again.all().get(0).body.startsWith("with a file"), "outbox: lists are copies");

            // a damaged line and a stray file
            try (OutputStream out = new FileOutputStream(new File(dir, "outbox.jsonl"), true)) {
                out.write("{this is not json\n\n[1,2]\n".getBytes(StandardCharsets.UTF_8));
            }
            File stray = new File(new File(dir, "files"), "o77_0");
            Files.write(stray.toPath(), new byte[] {1});
            SmsOutbox damaged = new SmsOutbox(dir, clock);
            check(damaged.size() == 3, "outbox: a damaged line costs only itself: " + damaged.size());
            check(!stray.exists(), "outbox: a file no message points to is removed at start");
            check(damaged.part("o1.0") != null, "outbox: a file that is pointed to stays");

            // limits: by count, by age, by bytes, with the files of dropped messages
            File dir2 = Files.createTempDirectory("outbox2").toFile();
            try {
                Clock c2 = new Clock();
                SmsOutbox small = new SmsOutbox(dir2, c2, 5, 10_000, 3000);
                for (int i = 0; i < 8; i++) {
                    c2.now = T0 + i;
                    small.add(msg("", "k", "+1", true, "n" + i, c2.now), new ArrayList<File>());
                }
                List<Sms.Msg> kept = small.all();
                check(kept.size() == 5 && kept.get(0).body.equals("n3") && kept.get(4).body.equals("n7"), "outbox: only the newest " + 5 + " are kept");
                c2.now = T0 + 7 + 20_000;
                small.add(msg("", "k", "+1", true, "late", c2.now), new ArrayList<File>());
                check(small.size() == 1 && small.all().get(0).body.equals("late"), "outbox: messages older than the age limit are dropped: " + small.size());
                // bytes: three 1500-byte files with a 3000-byte limit: the oldest goes, its file with it
                c2.now = T0 + 100_000;
                for (int i = 0; i < 3; i++) {
                    File src = new File(dir2, "s" + i);
                    Files.write(src.toPath(), randomBytes(1500, 20 + i));
                    Sms.Msg mm = msg("", "k", "+1", true, "f" + i, c2.now + i);
                    Sms.Part pt = new Sms.Part();
                    pt.type = "image/jpeg";
                    mm.parts.add(pt);
                    small.add(mm, Arrays.asList(src));
                    src.delete();
                }
                List<Sms.Msg> left = small.all();
                check(left.size() == 2 && left.get(0).body.equals("f1") && left.get(1).body.equals("f2"), "outbox: over the byte limit the oldest message goes: " + left.size());
                File[] fileList = new File(dir2, "files").listFiles();
                check(fileList != null && fileList.length == 2, "outbox: and its file is deleted with it: " + (fileList == null ? -1 : fileList.length));
                // one message alone over the limit is still kept
                File big = new File(dir2, "big");
                Files.write(big.toPath(), randomBytes(5000, 30));
                Sms.Msg bm = msg("", "k", "+1", true, "big", c2.now + 10);
                bm.parts.add(new Sms.Part());
                small.add(bm, Arrays.asList(big));
                check(small.size() == 1 && small.all().get(0).body.equals("big"), "outbox: the newest message is kept even if it alone is over the limit: " + small.size());
            } finally {
                deleteTree(dir2);
            }

            // a failing copy leaves nothing behind
            Sms.Msg broken = msg("", "k", "+1", true, "x", T0);
            broken.parts.add(new Sms.Part());
            int before = new File(dir, "files").list().length;
            check(throwsOf(IOException.class, () -> damaged.add(broken, Arrays.asList(new File(dir, "does-not-exist")))), "outbox: a missing source file is an error");
            check(new File(dir, "files").list().length == before, "outbox: and leaves no partial file");
            File okSrc = new File(dir, "ok-src.bin");
            Files.write(okSrc.toPath(), randomBytes(500, 31));
            final Sms.Msg twoParts = msg("", "k", "+1", true, "two", T0);
            twoParts.parts.add(new Sms.Part());
            twoParts.parts.add(new Sms.Part());
            int entriesBefore = damaged.size();
            check(throwsOf(IOException.class, () -> damaged.add(twoParts, Arrays.asList(okSrc, new File(dir, "missing-too")))), "outbox: the second file missing is an error");
            check(new File(dir, "files").list().length == before && damaged.size() == entriesBefore, "outbox: the first file's copy is removed again and no entry is made");
        } finally {
            deleteTree(dir);
        }
    }

    // ---------------------------------------------------------------- the staging area on its own

    static void stagingOnItsOwn() throws Exception {
        File dir = Files.createTempDirectory("staging").toFile();
        try {
            Files.write(new File(dir, "old1.bin").toPath(), new byte[] {1});
            Clock clock = new Clock();
            SmsStaging st = new SmsStaging(dir, clock);
            check(dir.list().length == 0, "staging: files left over from an earlier run are removed at start");
            byte[] data = randomBytes(4321, 40);
            Sms.Staged s = st.put("a/b\\c:d.jpg", "IMAGE/JPEG", stream(data), data.length);
            check(s.size == 4321 && s.id.equals("a1") && !s.name.contains("/") && !s.name.contains("\\") && !s.name.contains(":") && s.name.endsWith(".jpg"), "staging: a safe name: " + s.name);
            check(s.type.equals("image/jpeg"), "staging: the type in lower case: " + s.type);
            check(Arrays.equals(Files.readAllBytes(s.file.toPath()), data) && st.get("a1") == s, "staging: every byte, and found again by id");
            check(SmsStaging.safeType("not a type").equals("application/octet-stream") && SmsStaging.safeType(null).equals("application/octet-stream")
                    && SmsStaging.safeType("x/../y").equals("application/octet-stream"), "staging: a type that is not one becomes octet-stream");
            check(SmsStaging.safeName("").equals("file") && SmsStaging.safeName(null).equals("file") && SmsStaging.safeName("  ").equals("file"), "staging: an empty name becomes file");
            StringBuilder longName = new StringBuilder();
            for (int i = 0; i < 300; i++) {
                longName.append('n');
            }
            String cut = SmsStaging.safeName(longName + ".jpeg");
            check(cut.length() <= 80 && cut.endsWith(".jpeg"), "staging: a long name is cut but keeps its extension: " + cut.length());
            check(throwsOf(IOException.class, () -> st.put("x", "x/y", stream(new byte[0]), 0)), "staging: empty is refused");
            check(messageOf(() -> st.put("x", "x/y", stream(new byte[1]), SmsStaging.MAX_FILE + 1)).contains("too big"), "staging: too big is refused before reading, with that reason");
            int filesBefore = dir.list().length;
            check(throwsOf(IOException.class, () -> st.put("x", "x/y", stream(new byte[10]), 100)), "staging: a body that stops early is refused");
            check(dir.list().length == filesBefore, "staging: and leaves no partial file");
            Sms.Staged s2 = st.put("y", "x/y", stream(new byte[] {7}), 1);
            check(!s2.id.equals(s.id) && st.count() == 2, "staging: ids are unique");
            st.release(Arrays.asList("a1", "zzz"));
            check(st.count() == 1 && !s.file.exists() && st.get("a1") == null, "staging: release removes the file and forgets the id");
            clock.now += SmsStaging.KEEP_MS + 1;
            check(st.get(s2.id) == null && st.count() == 0 && !s2.file.exists(), "staging: a file is forgotten after an hour");
        } finally {
            deleteTree(dir);
        }
    }

    // ---------------------------------------------------------------- the wire format on its own

    static boolean sameMsg(Sms.Msg a, Sms.Msg b) {
        if (!(a.id.equals(b.id) && a.key.equals(b.key) && a.address.equals(b.address) && a.body.equals(b.body) && a.time == b.time && a.mine == b.mine
                && a.state == b.state && a.error.equals(b.error) && a.unread == b.unread && a.mms == b.mms && a.parts.size() == b.parts.size())) {
            return false;
        }
        for (int i = 0; i < a.parts.size(); i++) {
            Sms.Part x = a.parts.get(i);
            Sms.Part y = b.parts.get(i);
            if (!(x.id.equals(y.id) && x.type.equals(y.type) && x.name.equals(y.name) && x.size == y.size)) {
                return false;
            }
        }
        return true;
    }

    static String randomText(Random rnd) {
        String[] bits = {"hello", " ", "\n", "\t", "\"", "\\", "/", "caf\u00e9", "\u2764\ufe0f", "\ud83d\ude00", "\u0001", "{", "}", "[", "]", ",", ":", "\u2028", "x"};
        StringBuilder sb = new StringBuilder();
        for (int i = rnd.nextInt(12); i > 0; i--) {
            sb.append(bits[rnd.nextInt(bits.length)]);
        }
        return sb.toString();
    }

    static void jsonRoundTrips() throws Exception {
        Random rnd = new Random(11);
        for (int round = 0; round < 1500; round++) {
            Sms.Msg m = msg("o" + rnd.nextInt(1000), randomText(rnd), randomText(rnd), rnd.nextBoolean(), randomText(rnd), rnd.nextLong() >> rnd.nextInt(40));
            m.state = rnd.nextInt(5);
            m.error = randomText(rnd);
            m.unread = rnd.nextBoolean();
            m.mms = rnd.nextBoolean();
            for (int i = rnd.nextInt(4); i > 0; i--) {
                Sms.Part p = new Sms.Part();
                p.id = randomText(rnd);
                p.type = randomText(rnd);
                p.name = randomText(rnd);
                p.size = rnd.nextLong() >> rnd.nextInt(50);
                m.parts.add(p);
            }
            Sms.Msg back = SmsJson.msgFrom(Json.parseObject(Json.write(SmsJson.msg(m))));
            check(sameMsg(m, back), "message round trip " + round);
            check(sameMsg(m, m.copy()) && m.copy() != m, "message copy " + round);

            Sms.Thread t = new Sms.Thread();
            t.key = randomText(rnd);
            t.name = randomText(rnd);
            for (int i = rnd.nextInt(4); i > 0; i--) {
                t.addresses.add(randomText(rnd));
            }
            t.snippet = randomText(rnd);
            t.time = rnd.nextLong() >> rnd.nextInt(40);
            t.count = rnd.nextInt(100000);
            t.unread = rnd.nextBoolean();
            t.attachment = rnd.nextBoolean();
            t.providerId = rnd.nextInt(100000);
            Sms.Thread tb = SmsJson.threadFrom(Json.parseObject(Json.write(SmsJson.thread(t))));
            check(t.key.equals(tb.key) && t.name.equals(tb.name) && t.addresses.equals(tb.addresses) && t.snippet.equals(tb.snippet) && t.time == tb.time && t.count == tb.count
                    && t.unread == tb.unread && t.attachment == tb.attachment && t.providerId == tb.providerId, "thread round trip " + round);
            Sms.Thread tc = t.copy();
            check(tc != t && tc.addresses.equals(t.addresses) && tc.addresses != t.addresses && tc.snippet.equals(t.snippet) && tc.providerId == t.providerId
                    && tc.key.equals(t.key) && tc.name.equals(t.name) && tc.time == t.time && tc.count == t.count && tc.unread == t.unread
                    && tc.attachment == t.attachment, "thread copy " + round);

            Sms.Person pr = new Sms.Person();
            pr.name = randomText(rnd);
            pr.number = randomText(rnd);
            Sms.Person pb = SmsJson.personFrom(Json.parseObject(Json.write(SmsJson.person(pr))));
            check(pr.name.equals(pb.name) && pr.number.equals(pb.number), "person round trip " + round);

            Sms.State s = new Sms.State();
            s.on = rnd.nextBoolean();
            s.read = rnd.nextBoolean();
            s.send = rnd.nextBoolean();
            s.contacts = rnd.nextBoolean();
            s.note = randomText(rnd);
            Sms.State sb = SmsJson.stateFrom(Json.parseObject(Json.write(SmsJson.state(s))));
            check(s.on == sb.on && s.read == sb.read && s.send == sb.send && s.contacts == sb.contacts && s.note.equals(sb.note) && sb.supported, "state round trip " + round);
        }
        // missing or odd fields come out as harmless defaults
        Sms.Msg empty = SmsJson.msgFrom(Json.parseObject("{}"));
        check(empty.id.isEmpty() && empty.body.isEmpty() && empty.time == 0 && !empty.mine && empty.parts.isEmpty(), "an empty message object gives defaults");
        Sms.Msg odd = SmsJson.msgFrom(Json.parseObject("{\"id\":5,\"body\":null,\"parts\":[1,\"x\",{\"id\":\"p\"}],\"time\":\"soon\",\"mine\":1}"));
        check(odd.id.isEmpty() && odd.body.isEmpty() && odd.time == 0 && odd.mine && odd.parts.size() == 1 && odd.parts.get(0).id.equals("p"), "odd values are skipped, not fatal");
    }

    // ---------------------------------------------------------------- random stories against a model

    static void randomStories() throws Exception {
        for (int seed = 0; seed < 25; seed++) {
            Random rnd = new Random(900 + seed);
            try (Server srv = new Server()) {
                RemoteSms sms = srv.sms();
                String[] numbers = {"+15550000001", "+15550000002", "+15550000003", "+15550000004"};
                // the model: every conversation's messages as (id, body, mine)
                Map<String, List<String[]>> model = new LinkedHashMap<>();
                int dbId = 0;
                long now = T0 - 10_000_000;
                for (int step = 0; step < 60; step++) {
                    now += 1000 + rnd.nextInt(4000) * 1000L;
                    int who = rnd.nextInt(numbers.length);
                    String key = PhoneNumbers.key(numbers[who]);
                    int act = rnd.nextInt(3);
                    if (act == 0) { // a text arrives on the phone
                        Sms.Msg m = msg("s" + (++dbId), key, numbers[who], false, "in" + step, now);
                        srv.fake.db.add(m);
                        model.computeIfAbsent(key, k -> new ArrayList<>()).add(new String[] {m.id, m.body, "0"});
                    } else { // the tablet sends
                        srv.clock.now = now;
                        String body = "out" + step;
                        List<String> to = list(numbers[who]);
                        Sms.SendResult r = sms.send(to, "", body, new ArrayList<String>());
                        check(r.ok, "story " + seed + ": send ok");
                        model.computeIfAbsent(key, k -> new ArrayList<>()).add(new String[] {r.id, body, "1"});
                        if (act == 2 && rnd.nextBoolean()) {
                            srv.outbox.setState(r.id, Sms.STATE_DELIVERED, "");
                        }
                    }
                }
                for (Map.Entry<String, List<String[]>> e : model.entrySet()) {
                    Sms.Convo c = sms.thread(e.getKey(), 500);
                    boolean same = c.msgs.size() == e.getValue().size();
                    for (int i = 0; same && i < c.msgs.size(); i++) {
                        same = c.msgs.get(i).id.equals(e.getValue().get(i)[0]) && c.msgs.get(i).body.equals(e.getValue().get(i)[1])
                                && c.msgs.get(i).mine == e.getValue().get(i)[2].equals("1");
                    }
                    check(same, "story " + seed + ": thread " + e.getKey() + " is what the model says (" + c.msgs.size() + " of " + e.getValue().size() + ")");
                }
                List<Sms.Thread> ts = sms.threads(300);
                check(ts.size() == model.size(), "story " + seed + ": one thread per conversation");
                for (int i = 0; i + 1 < ts.size(); i++) {
                    check(ts.get(i).time >= ts.get(i + 1).time, "story " + seed + ": threads are newest first");
                }
                for (Sms.Thread t : ts) {
                    List<String[]> ms = model.get(t.key);
                    check(ms != null && t.snippet.equals(ms.get(ms.size() - 1)[1]), "story " + seed + ": the snippet is the newest message: " + t.key);
                }
            }
        }
    }

    static void parallelSends() throws Exception {
        try (Server srv = new Server()) {
            final RemoteSms sms = srv.sms();
            final Set<String> idsSeen = Collections.synchronizedSet(new HashSet<String>());
            final AtomicInteger bad = new AtomicInteger();
            List<Thread> ts = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                final int tt = t;
                Thread th = new Thread(() -> {
                    for (int i = 0; i < 12; i++) {
                        try {
                            Sms.SendResult r = sms.send(list("+1555000" + (1000 + tt)), "", "t" + tt + "m" + i, new ArrayList<String>());
                            if (!r.ok || !idsSeen.add(r.id)) {
                                bad.incrementAndGet();
                            }
                        } catch (IOException e) {
                            bad.incrementAndGet();
                        }
                    }
                });
                ts.add(th);
                th.start();
            }
            for (Thread th : ts) {
                th.join();
            }
            check(bad.get() == 0 && idsSeen.size() == 96 && srv.outbox.size() == 96 && srv.fake.sent.size() == 96, "parallel: 96 sends, every id unique, every one recorded and handed over: " + bad.get() + " " + idsSeen.size());
            int total = 0;
            for (Sms.Thread t : sms.threads(300)) {
                total += t.count;
            }
            check(total == 96, "parallel: the list counts all of them: " + total);
            SmsOutbox reloaded = new SmsOutbox(new File(srv.dir, "outbox"), srv.clock);
            check(reloaded.size() == 96, "parallel: the saved log holds all of them after a restart: " + reloaded.size());
        }
    }
}
