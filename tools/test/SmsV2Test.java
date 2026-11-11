package com.tj.linkmeter;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Texts protocol 2, offline, over a real socket: waiting for news (long polling) and the gate behind it, paging back through a
 * conversation, search, delete and mark read (what the phone refuses, what the tablet's own record loses for good), privacy while
 * the switch is off, an old phone. The real server code (SmsApi through SmsHttp) and the real client code (RemoteSms, PeerLink,
 * Client) against a fake phone. Run by tools/test.sh.
 */
public final class SmsV2Test {
    static int checks;
    static int failed;
    static final String TOKEN = SmsProtocolTest.TOKEN;
    static final long T0 = SmsProtocolTest.T0;

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            failed++;
            if (failed <= 40) {
                System.out.println("  FAIL: " + msg);
            }
        }
    }

    static void eq(Object want, Object got, String msg) {
        check(String.valueOf(want).equals(String.valueOf(got)), msg + ": wanted '" + want + "' but got '" + got + "'");
    }

    // ---------------------------------------------------------------- a fake phone that knows protocol 2

    static final class Fake2 extends SmsProtocolTest.Fake {
        volatile Runnable listener;
        volatile boolean allowDelete;
        volatile boolean allowContacts;
        volatile int manyContacts;
        final List<String> savedContacts = new CopyOnWriteArrayList<>();
        volatile boolean allowRead;
        final List<String> askedIds = new CopyOnWriteArrayList<>();
        final List<String> askedKeys = new CopyOnWriteArrayList<>();
        final List<String> readAsked = new CopyOnWriteArrayList<>();

        @Override
        public void setChangeListener(Runnable r) {
            listener = r;
        }

        @Override
        public List<Sms.Person> contacts(String query, int limit) {
            if (manyContacts == 0) {
                return super.contacts(query, limit);
            }
            List<Sms.Person> out = new ArrayList<>();
            for (int i = 0; i < manyContacts && out.size() < limit; i++) {
                Sms.Person p = new Sms.Person();
                p.name = "Person " + i;
                p.number = "+1555000" + String.format("%04d", i);
                out.add(p);
            }
            return out;
        }

        @Override
        public boolean canWriteContacts() {
            return allowContacts;
        }

        @Override
        public Sms.ContactResult saveContact(String name, String number) {
            Sms.ContactResult r = new Sms.ContactResult();
            if (!allowContacts) {
                r.needPermission = true;
                r.why = "no permission";
                return r;
            }
            savedContacts.add(name + "|" + number);
            r.ok = true;
            r.how = savedContacts.size() % 2 == 1 ? "created" : "renamed";
            r.where = "the phone";
            return r;
        }

        /** The phone's database changed: a new stamp and the wake-up call. */
        void changed(String newStamp) {
            stamp = newStamp;
            Runnable r = listener;
            if (r != null) {
                r.run();
            }
        }

        @Override
        public List<Sms.Msg> search(String q, int limit) {
            List<Sms.Msg> all = new ArrayList<>(db);
            Collections.sort(all, new Comparator<Sms.Msg>() {
                @Override
                public int compare(Sms.Msg a, Sms.Msg b) {
                    return Long.compare(b.time, a.time);
                }
            });
            List<Sms.Msg> out = new ArrayList<>();
            for (Sms.Msg m : all) {
                if (out.size() < limit && m.body.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))) {
                    out.add(m.copy());
                }
            }
            return out;
        }

        @Override
        public Sms.DeleteResult delete(List<String> ids, List<String> keys) {
            askedIds.addAll(ids);
            askedKeys.addAll(keys);
            Sms.DeleteResult r = new Sms.DeleteResult();
            if (!allowDelete) {
                r.failedIds.addAll(ids);
                r.failedKeys.addAll(keys);
                r.why = "the phone said no";
                return r;
            }
            for (String id : ids) {
                boolean gone = false;
                for (Sms.Msg m : db) {
                    if (m.id.equals(id)) {
                        db.remove(m);
                        gone = true;
                    }
                }
                (gone ? r.doneIds : r.failedIds).add(id);
            }
            for (String k : keys) {
                for (Sms.Msg m : db) {
                    if (m.key.equals(k)) {
                        db.remove(m);
                    }
                }
                r.doneKeys.add(k);
            }
            stamp = stamp + "d";
            return r;
        }

        @Override
        public Sms.DeleteResult markRead(List<String> keys) {
            readAsked.addAll(keys);
            Sms.DeleteResult r = new Sms.DeleteResult();
            if (!allowRead) {
                r.failedKeys.addAll(keys);
                r.why = "no read";
                return r;
            }
            for (String k : keys) {
                for (Sms.Msg m : db) {
                    if (m.key.equals(k)) {
                        m.unread = false;
                    }
                }
                r.doneKeys.add(k);
            }
            return r;
        }
    }

    static final class Srv implements Closeable {
        final ServerSocket ss;
        final Fake2 fake = new Fake2();
        final SmsProtocolTest.Clock clock = new SmsProtocolTest.Clock();
        final SmsOutbox outbox;
        final SmsApi api;
        final File dir;
        volatile boolean legacy;

        Srv() throws IOException {
            dir = Files.createTempDirectory("sms2").toFile();
            outbox = new SmsOutbox(new File(dir, "outbox"), clock);
            api = new SmsApi(fake, outbox, new SmsStaging(new File(dir, "staging"), clock), clock);
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
            SmsProtocolTest.deleteTree(dir);
        }
    }

    static final String K = "5551234567";
    static final String ADDR = "+15551234567";

    static void fill(Fake2 f, int n) {
        for (int i = 0; i < n; i++) {
            f.db.add(SmsProtocolTest.msg("s" + (i + 1), K, ADDR, i % 3 == 0, "msg " + (i + 1), T0 + i * 1000L));
        }
        f.names.put(K, "Alex");
    }

    // ---------------------------------------------------------------- the gate

    static void gate() throws Exception {
        final ChangeGate g = new ChangeGate();
        long v = g.version();
        long t = System.nanoTime();
        check(g.await(v - 1, 5000) == v, "a version that is not the one seen answers at once");
        check((System.nanoTime() - t) / 1_000_000 < 500, "and does not wait");
        t = System.nanoTime();
        check(g.await(v, 0) == v, "no time to wait: answers with the same version");
        check((System.nanoTime() - t) / 1_000_000 < 500, "at once");
        t = System.nanoTime();
        check(g.await(v, 200) == v, "nothing happens: the same version after the time");
        long ms = (System.nanoTime() - t) / 1_000_000;
        check(ms >= 150 && ms < 1500, "and it did wait about that long: " + ms);
        final long[] woke = new long[2];
        Thread[] waiters = new Thread[2];
        for (int i = 0; i < 2; i++) {
            final int n = i;
            waiters[i] = new Thread(() -> {
                try {
                    long s = System.nanoTime();
                    g.await(g.version(), 10_000);
                    woke[n] = (System.nanoTime() - s) / 1_000_000;
                } catch (InterruptedException e) {
                    woke[n] = -1;
                }
            });
            waiters[i].start();
        }
        Thread.sleep(150);
        long before = g.version();
        g.bump();
        for (Thread w : waiters) {
            w.join(3000);
        }
        check(g.version() == before + 1, "a bump is one step");
        check(woke[0] > 100 && woke[0] < 2000 && woke[1] > 100 && woke[1] < 2000, "both waiters woke soon after the bump: " + woke[0] + ", " + woke[1]);
        check(g.await(before, 5000) == before + 1, "a waiter that arrives late finds the change");
    }

    // ---------------------------------------------------------------- state, waiting for news

    static void waitingForNews() throws Exception {
        eq(25, SmsApi.waitSeconds(Collections.singletonMap("wait", "999")), "the longest wait is capped");
        eq(0, SmsApi.waitSeconds(Collections.singletonMap("wait", "-5")), "a negative wait is none");
        eq(0, SmsApi.waitSeconds(Collections.singletonMap("wait", "abc")), "a wait that is not a number is none");
        eq(7, SmsApi.waitSeconds(Collections.singletonMap("wait", "7")), "a normal wait is kept");
        eq(0, SmsApi.waitSeconds(new HashMap<String, String>()), "no wait is none");
        try (Srv srv = new Srv()) {
            RemoteSms sms = srv.sms();
            Sms.State st = sms.state();
            eq(3, st.version, "the phone says it speaks protocol 3");
            String s0 = sms.stamp();
            check(!s0.isEmpty(), "there is a stamp");

            long t = System.nanoTime();
            eq(s0, sms.stamp("something else", 10), "a stamp that differs from the one asked about is answered at once");
            check((System.nanoTime() - t) / 1_000_000 < 1500, "without waiting");

            t = System.nanoTime();
            eq(s0, sms.stamp(s0, 1), "nothing happens: the same stamp after the wait");
            long ms = (System.nanoTime() - t) / 1_000_000;
            check(ms >= 800 && ms < 4000, "the request held on for about a second: " + ms);

            // news with the wake-up call
            final Fake2 f = srv.fake;
            Thread news = new Thread(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                    return;
                }
                f.changed("s2");
            });
            news.start();
            t = System.nanoTime();
            String s1 = sms.stamp(s0, 10);
            ms = (System.nanoTime() - t) / 1_000_000;
            check(!s1.equals(s0), "news: a different stamp");
            check(ms >= 200 && ms < 900, "and it came at once after the change (not at the next look a second later): " + ms);
            news.join();

            // news without the wake-up call (a missed notification): found at the next look, within about a second
            f.stamp = "s3";
            t = System.nanoTime();
            String s2 = sms.stamp(s1, 10);
            ms = (System.nanoTime() - t) / 1_000_000;
            check(!s2.equals(s1), "a change that nobody announced is found");
            check(ms < 2500, "within about a second: " + ms);

            // the tablet's own record changing wakes the request too
            Thread own = new Thread(() -> {
                try {
                    Thread.sleep(300);
                    srv.outbox.add(SmsProtocolTest.msg("", K, ADDR, true, "from the tablet", T0), new ArrayList<File>());
                } catch (Exception ignored) {
                    // the check below fails
                }
            });
            own.start();
            t = System.nanoTime();
            String s3 = sms.stamp(s2, 10);
            ms = (System.nanoTime() - t) / 1_000_000;
            check(!s3.equals(s2) && ms < 900, "a text the tablet sent wakes a waiting request at once: " + ms);
            own.join();

            // the switch off: nothing to wait for
            srv.fake.on = false;
            check(SmsProtocolTest.throwsOf(RemoteSms.Off.class, () -> sms.stamp("x", 5)), "off: the long poll is refused too");
        }
    }

    // ---------------------------------------------------------------- paging back through a conversation

    static void paging() throws Exception {
        try (Srv srv = new Srv()) {
            RemoteSms sms = srv.sms();
            fill(srv.fake, 25);
            Sms.Msg logged = srv.outbox.add(SmsProtocolTest.msg("", K, ADDR, true, "from the tablet", T0 + 12_500), new ArrayList<File>());

            Sms.Convo c = sms.thread(K, 10);
            eq(10, c.msgs.size(), "the newest ten");
            eq("s25", c.msgs.get(9).id, "the newest is last");
            check(c.more, "there are older ones");
            Sms.Convo p2 = sms.thread(K, 10, c.msgs.get(0).time);
            eq(10, p2.msgs.size(), "the ten before");
            check(p2.more, "still older ones");
            for (Sms.Msg m : p2.msgs) {
                check(m.time < c.msgs.get(0).time, "every message of the page is older than the one asked about: " + m.id);
            }

            for (int limit : new int[] {1, 3, 7, 10, 30}) {
                List<String> all = new ArrayList<>();
                long before = 0;
                int pages = 0;
                while (pages++ < 100) {
                    Sms.Convo page = sms.thread(K, limit, before);
                    check(page.msgs.size() <= limit, "a page is not longer than asked: " + page.msgs.size() + " > " + limit);
                    if (page.msgs.isEmpty()) {
                        check(!page.more, "an empty page has nothing older");
                        break;
                    }
                    List<String> ids = new ArrayList<>();
                    for (Sms.Msg m : page.msgs) {
                        ids.add(m.id);
                    }
                    all.addAll(0, ids);
                    before = page.msgs.get(0).time;
                    if (!page.more) {
                        break;
                    }
                }
                Set<String> unique = new HashSet<>(all);
                eq(26, all.size(), "limit " + limit + ": paging back reaches every message (25 + the tablet's own)");
                eq(26, unique.size(), "limit " + limit + ": none twice");
                check(all.contains(logged.id), "limit " + limit + ": the tablet's own message is among them");
                eq("s1", all.get(0), "limit " + limit + ": the oldest first");
                eq("s25", all.get(all.size() - 1), "limit " + limit + ": the newest last");
            }
            eq(0, sms.thread(K, 10, T0).msgs.size(), "nothing is older than the oldest");
            check(!sms.thread(K, 10, T0).more, "and it says so");
            srv.fake.on = false;
            check(SmsProtocolTest.throwsOf(RemoteSms.Off.class, () -> sms.thread(K, 10, 5)), "off: refused");
        }
    }

    // ---------------------------------------------------------------- the names of the people in a group

    static void groupNames() throws Exception {
        try (Srv srv = new Srv()) {
            RemoteSms sms = srv.sms();
            String g = "5551111111,5552222222";
            srv.fake.db.add(SmsProtocolTest.msg("s1", g, "+15551111111", false, "hi all", T0 + 1000));
            srv.fake.db.add(SmsProtocolTest.msg("s2", g, "+15552222222", false, "hello", T0 + 2000));
            srv.fake.names.put("5551111111", "Alex");
            srv.fake.names.put("5552222222", "Sam");
            Sms.Convo c = sms.thread(g, 10);
            eq("Alex|Sam", c.people.get("5551111111") + "|" + c.people.get("5552222222"), "a group's thread says who is who, by number key");
            fill(srv.fake, 2);
            eq(0, sms.thread(K, 10).people.size(), "a conversation with one person has no list of people");
        }
    }

    // ---------------------------------------------------------------- search

    static void searching() throws Exception {
        try (Srv srv = new Srv()) {
            RemoteSms sms = srv.sms();
            srv.fake.db.add(SmsProtocolTest.msg("s1", K, ADDR, false, "Pizza tonight?", T0 + 1000));
            srv.fake.db.add(SmsProtocolTest.msg("s2", K, ADDR, true, "yes, pizza at 7", T0 + 2000));
            srv.fake.db.add(SmsProtocolTest.msg("s3", "5559876543", "+15559876543", false, "no match here", T0 + 3000));
            srv.fake.names.put(K, "Alex");
            srv.outbox.add(SmsProtocolTest.msg("", "5559876543", "+15559876543", true, "I love PIZZA too", T0 + 4000), new ArrayList<File>());

            List<Sms.Hit> hits = sms.search("pizza", 10);
            eq(3, hits.size(), "three texts mention pizza (two on the phone, one only in the tablet's record)");
            eq("I love PIZZA too", hits.get(0).msg.body, "newest first");
            eq("yes, pizza at 7", hits.get(1).msg.body, "then the one before it");
            eq("Alex", hits.get(1).name, "a hit carries the name of its conversation");
            check(!hits.get(0).name.isEmpty(), "also for a conversation the phone does not have");
            eq(1, sms.search("pizza", 1).size(), "the limit is kept");
            eq(0, sms.search("p", 10).size(), "one letter is not a search");
            eq(0, sms.search("nothing like this", 10).size(), "no match, no hits");
            eq(1, sms.search("MATCH here", 10).size(), "capital letters do not matter");
            srv.fake.on = false;
            check(SmsProtocolTest.throwsOf(RemoteSms.Off.class, () -> sms.search("pizza", 10)), "off: refused");
        }
    }

    // ---------------------------------------------------------------- delete and mark read

    static void deleting() throws Exception {
        try (Srv srv = new Srv()) {
            RemoteSms sms = srv.sms();
            fill(srv.fake, 5);
            Sms.Msg own = srv.outbox.add(SmsProtocolTest.msg("", K, ADDR, true, "mine", T0 + 9000), new ArrayList<File>());
            Sms.Msg own2 = srv.outbox.add(SmsProtocolTest.msg("", "5550000000", "+15550000000", true, "other chat", T0 + 9100), new ArrayList<File>());

            // the phone refuses
            Sms.DeleteResult r = sms.delete(SmsProtocolTest.list("s1", "m2", own.id, "x9", "../etc"), SmsProtocolTest.list());
            check(r.doneIds.contains(own.id), "what the tablet sent is its own record: deleted for good");
            check(!srv.outbox.forKey(K).contains(own) && srv.outbox.forKey(K).isEmpty(), "and gone from the record");
            check(r.failedIds.contains("s1") && r.failedIds.contains("m2"), "the phone's messages were refused by the phone");
            check(r.failedIds.contains("x9") && r.failedIds.contains("../etc"), "an id of no known kind is refused before anything is asked");
            eq("[s1, m2]", srv.fake.askedIds, "only well formed provider ids reach the phone's database");
            eq("the phone said no", r.why, "and the reason is passed on");
            eq(5, srv.fake.db.size(), "nothing was deleted on the phone");

            // a whole conversation: the record loses it even when the phone refuses
            srv.outbox.add(SmsProtocolTest.msg("", K, ADDR, true, "another", T0 + 9200), new ArrayList<File>());
            r = sms.delete(SmsProtocolTest.list(), SmsProtocolTest.list(K));
            check(r.failedKeys.contains(K) && r.doneKeys.isEmpty(), "the phone refused the conversation");
            eq(0, srv.outbox.forKey(K).size(), "but the tablet's record of it is gone");
            eq(1, srv.outbox.forKey("5550000000").size(), "another conversation's record is untouched");

            // the phone agrees
            srv.fake.allowDelete = true;
            r = sms.delete(SmsProtocolTest.list("s1", "s2"), SmsProtocolTest.list());
            eq("[s1, s2]", r.doneIds, "the phone deleted them");
            eq(3, srv.fake.db.size(), "really");
            r = sms.delete(SmsProtocolTest.list(), SmsProtocolTest.list(K));
            eq("[" + K + "]", r.doneKeys, "and the whole conversation");
            eq(0, srv.fake.db.size(), "really");
            check(sms.threads(10).size() <= 1, "the conversation is gone from the list (only the tablet's record of another chat is left)");

            // limits
            List<String> many = new ArrayList<>();
            for (int i = 0; i < 501; i++) {
                many.add("s" + i);
            }
            check(SmsProtocolTest.throwsOf(java.io.IOException.class, () -> sms.delete(many, SmsProtocolTest.list())), "too many ids at once are refused");
            check(own2.id.startsWith("o"), "(a record id looks like o12)");

            // mark read
            fill(srv.fake, 3);
            for (Sms.Msg m : srv.fake.db) {
                m.unread = true;
            }
            r = sms.markRead(SmsProtocolTest.list(K));
            check(r.failedKeys.contains(K) && r.why.equals("no read"), "the phone refused to mark read: said so");
            srv.fake.allowRead = true;
            r = sms.markRead(SmsProtocolTest.list(K));
            eq("[" + K + "]", r.doneKeys, "the phone marked it read");
            check(!srv.fake.db.get(0).unread, "really");

            // privacy and an old phone
            srv.fake.on = false;
            check(SmsProtocolTest.throwsOf(RemoteSms.Off.class, () -> sms.delete(SmsProtocolTest.list("s1"), SmsProtocolTest.list())), "off: delete is refused");
            check(SmsProtocolTest.throwsOf(RemoteSms.Off.class, () -> sms.markRead(SmsProtocolTest.list(K))), "off: mark read is refused");
            srv.fake.on = true;
            srv.legacy = true;
            check(SmsProtocolTest.throwsOf(RemoteSms.TooOld.class, () -> sms.delete(SmsProtocolTest.list("s1"), SmsProtocolTest.list())), "an old phone: update it");
        }
    }

    // ---------------------------------------------------------------- saving a contact on the phone

    static void savingContacts() throws Exception {
        try (Srv srv = new Srv()) {
            RemoteSms sms = srv.sms();
            check(!sms.state().contactsWrite, "the phone may not change contacts yet");
            Sms.ContactResult r = sms.saveContact("Alex Rivera", "555-123-4567");
            check(!r.ok && r.needPermission && r.why.length() > 0, "without the permission it says so, and that asking the phone to allow it may help");
            eq("[]", srv.fake.savedContacts, "nothing reached the phone's contacts");

            srv.fake.allowContacts = true;
            check(sms.state().contactsWrite, "the state says it may change contacts now");
            r = sms.saveContact("  Alex   Rivera ", "(555) 123-4567");
            check(r.ok && r.how.equals("created") && r.where.equals("the phone") && !r.needPermission, "saved: " + r.how + " " + r.where);
            eq("[Alex Rivera|+15551234567]", srv.fake.savedContacts, "the name is tidied and the number is in the form the network uses");
            r = sms.saveContact("Alex R", "+1 555 123 4567");
            check(r.ok && r.how.equals("renamed"), "the phone says it renamed the one that has the number");
            eq(2, srv.fake.savedContacts.size(), "both reached the phone");

            // what is wrong never reaches the phone's contacts
            r = sms.saveContact("", "5551234567");
            check(!r.ok && r.why.toLowerCase(Locale.ROOT).contains("name"), "no name: refused with a sentence about the name: " + r.why);
            r = sms.saveContact("   ", "5551234567");
            check(!r.ok, "a name of spaces is no name");
            r = sms.saveContact("Somebody", "hello");
            check(!r.ok && r.why.toLowerCase(Locale.ROOT).contains("phone number"), "not a number: refused with a sentence about the number: " + r.why);
            StringBuilder longName = new StringBuilder();
            for (int i = 0; i <= SmsApi.MAX_NAME; i++) {
                longName.append('x');
            }
            r = sms.saveContact(longName.toString(), "5551234567");
            check(!r.ok, "a name that is too long is refused");
            eq(2, srv.fake.savedContacts.size(), "none of those reached the phone");

            // the list of contacts can be long now
            srv.fake.manyContacts = 300;
            eq(300, sms.contacts("", 1000).size(), "a long list can be asked for");
            eq(20, sms.contacts("").size(), "the suggestions still ask for 20");
            eq(7, sms.contacts("", 7).size(), "and a number in between is kept");
            srv.fake.manyContacts = 5000;
            eq(SmsApi.MAX_CONTACTS, sms.contacts("", 100000).size(), "the phone never answers more than its limit");
            srv.fake.manyContacts = 0;

            // privacy and an old phone
            srv.fake.on = false;
            check(SmsProtocolTest.throwsOf(RemoteSms.Off.class, () -> sms.saveContact("Alex", "5551234567")), "off: saving a contact is refused");
            srv.fake.on = true;
            srv.legacy = true;
            r = sms.saveContact("Alex", "5551234567");
            check(!r.ok && r.why.contains("too old"), "an old phone: the tablet is told to update it: " + r.why);
        }
    }

    // ---------------------------------------------------------------- the record on its own

    static void outboxRemoval() throws Exception {
        File dir = Files.createTempDirectory("outbox2").toFile();
        try {
            SmsProtocolTest.Clock clock = new SmsProtocolTest.Clock();
            SmsOutbox ob = new SmsOutbox(dir, clock);
            final int[] calls = {0};
            ob.setOnChange(() -> calls[0]++);
            File src = new File(dir, "src.bin");
            Files.write(src.toPath(), new byte[] {1, 2, 3});
            Sms.Msg withFile = SmsProtocolTest.msg("", K, ADDR, true, "picture", T0);
            Sms.Part part = new Sms.Part();
            part.type = "image/jpeg";
            part.name = "a.jpg";
            withFile.parts.add(part);
            List<File> srcs = new ArrayList<>();
            srcs.add(src);
            Sms.Msg a = ob.add(withFile, srcs);
            Sms.Msg b = ob.add(SmsProtocolTest.msg("", K, ADDR, true, "two", T0 + 1), new ArrayList<File>());
            Sms.Msg c = ob.add(SmsProtocolTest.msg("", "5550000000", "+15550000000", true, "three", T0 + 2), new ArrayList<File>());
            eq(3, calls[0], "adding tells the listener each time");
            ob.setState(b.id, Sms.STATE_SENT, "");
            eq(4, calls[0], "a new state too");
            check(ob.part(a.parts.get(0).id) != null, "the picture is kept");
            eq(1, ob.remove(SmsProtocolTest.list(a.id)), "one message removed");
            check(ob.part(a.parts.get(0).id) == null, "and its picture with it");
            eq(5, calls[0], "removing tells the listener");
            eq(0, ob.remove(SmsProtocolTest.list(a.id, "o999")), "removing what is not there removes nothing");
            eq(5, calls[0], "and tells nobody");
            eq(1, ob.removeKey(K), "a conversation's messages are removed");
            eq(1, ob.size(), "the other conversation stays");
            check(ob.forKey("5550000000").get(0).id.equals(c.id), "the right one");
            eq(0, ob.removeKey("nobody"), "nothing to remove");
            SmsOutbox again = new SmsOutbox(dir, clock);
            eq(1, again.size(), "the removal survives a restart");
        } finally {
            SmsProtocolTest.deleteTree(dir);
        }
    }

    static void jsonRoundTrips() throws Exception {
        Sms.State s = new Sms.State();
        s.version = 2;
        s.on = true;
        eq(2, SmsJson.stateFrom(SmsJson.state(s)).version, "the version survives");
        Sms.State old = SmsJson.stateFrom(Json.obj("on", true));
        eq(1, old.version, "a state without a version is protocol 1");
        check(!old.contactsWrite, "an old phone cannot change contacts");
        s.contactsWrite = true;
        check(SmsJson.stateFrom(SmsJson.state(s)).contactsWrite, "the permission to change contacts survives");
        Sms.ContactResult cr = new Sms.ContactResult();
        cr.ok = true;
        cr.how = "created";
        cr.where = "your Google account";
        cr.why = "w";
        cr.needPermission = true;
        Sms.ContactResult crb = SmsJson.contactResultFrom(Json.parseObject(Json.write(SmsJson.contactResult(cr))));
        eq("true|created|your Google account|w|true", crb.ok + "|" + crb.how + "|" + crb.where + "|" + crb.why + "|" + crb.needPermission, "a contact result");
        Sms.DeleteResult r = new Sms.DeleteResult();
        r.doneIds.add("s1");
        r.failedIds.add("m2");
        r.doneKeys.add("k1");
        r.failedKeys.add("k2");
        r.why = "because";
        Sms.DeleteResult back = SmsJson.deleteResultFrom(Json.parseObject(Json.write(SmsJson.deleteResult(r))));
        eq("[s1]|[m2]|[k1]|[k2]|because", back.doneIds + "|" + back.failedIds + "|" + back.doneKeys + "|" + back.failedKeys + "|" + back.why, "a delete result");
        Sms.Hit h = new Sms.Hit();
        h.msg = SmsProtocolTest.msg("s9", K, ADDR, false, "hello", T0);
        h.name = "Alex";
        Sms.Hit hb = SmsJson.hitFrom(Json.parseObject(Json.write(SmsJson.hit(h))));
        eq("s9|hello|Alex", hb.msg.id + "|" + hb.msg.body + "|" + hb.name, "a search hit");
    }

    public static void main(String[] args) throws Exception {
        gate();
        waitingForNews();
        paging();
        groupNames();
        searching();
        deleting();
        savingContacts();
        outboxRemoval();
        jsonRoundTrips();
        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
