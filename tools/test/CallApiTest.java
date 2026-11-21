package com.tj.linkmeter;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The Calls routes offline, over a real socket: the real server code (CallApi) and the real client code (RemoteCalls, PeerLink,
 * Client) against a fake phone. What matters most: nothing dials unless the switch is on and the number passed the guard (an
 * emergency number or a code never reaches the phone), the answers say what the phone did, the long poll waits for news, an old
 * phone and a stranger are told apart. Run by tools/test.sh.
 */
public final class CallApiTest {
    static int checks;
    static int failed;
    static final String TOKEN = "0123456789abcdef0123456789abcdef";

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

    static Calls.Volume pretendVolumes() {
        Calls.Volume v = new Calls.Volume();
        v.media.min = 0;
        v.media.max = 15;
        v.media.now = 6;
        v.call.min = 1;
        v.call.max = 5;
        v.call.now = 3;
        v.mediaOnHeadset = true;
        v.callOnHeadset = true;
        return v;
    }

    /** A phone that does what it is told and writes it down. */
    static final class Fake implements CallApi.Backend {
        volatile boolean on = true;
        volatile Runnable listener;
        final List<String> dialed = new CopyOnWriteArrayList<>();
        final List<String> done = new CopyOnWriteArrayList<>();
        volatile boolean dialWorks = true;
        volatile boolean throwOnNow;
        volatile int missed;
        volatile Calls.Now now = new Calls.Now();
        final List<Calls.Entry> log = new ArrayList<>();
        final List<Sms.Person> people = new ArrayList<>();
        volatile int lastLogLimit;
        volatile long lastLogBefore;
        volatile String lastQuery = "";
        volatile int lastContactLimit;
        volatile Calls.Headsets headsets = new Calls.Headsets();
        volatile Calls.Found found = new Calls.Found();
        volatile boolean granted;
        /** The headset's volumes of this pretend phone: media 0..15 at 6, call 1..5 at 3, a call's sound on the headset. */
        volatile Calls.Volume volumes = pretendVolumes();
        final List<String> volumeAsked = new CopyOnWriteArrayList<>();

        @Override
        public boolean on() {
            return on;
        }

        @Override
        public Calls.State state() {
            Calls.State s = new Calls.State();
            s.call = true;
            s.answer = granted;
            s.phone = true;
            s.log = true;
            s.contacts = true;
            s.bluetooth = true;
            s.shell = true;
            s.note = granted ? "" : "answer is missing";
            return s;
        }

        @Override
        public Calls.Now now() {
            if (throwOnNow) {
                throw new IllegalStateException("the phone broke");
            }
            Calls.Now n = new Calls.Now();
            n.calls.addAll(now.calls);
            n.muted = now.muted;
            n.route = now.route;
            n.missed = missed;
            n.headset = now.headset;
            n.headsetBattery = now.headsetBattery;
            n.volume = volumes;
            return n;
        }

        @Override
        public void setChangeListener(Runnable r) {
            listener = r;
        }

        void bump() {
            Runnable r = listener;
            if (r != null) {
                r.run();
            }
        }

        @Override
        public Calls.Result dial(String number) {
            dialed.add(number);
            return dialWorks ? Calls.Result.ok("Calling " + number) : Calls.Result.fail("A call is already on.");
        }

        @Override
        public Calls.Result answer() {
            done.add("answer");
            return Calls.Result.ok("");
        }

        @Override
        public Calls.Result hangup() {
            done.add("hangup");
            return Calls.Result.ok("");
        }

        @Override
        public Calls.Result mute(boolean on) {
            done.add("mute" + (on ? 1 : 0));
            return Calls.Result.ok(on ? "Muted" : "Not muted");
        }

        @Override
        public Calls.Result volume(String stream, int by) {
            volumeAsked.add(stream + ":" + by);
            Calls.Volume v = volumes;
            VolumeRules.Plan p = VolumeRules.plan(stream, by, v, !now.headset.isEmpty());
            if (!p.ok) {
                return Calls.Result.fail(p.why);
            }
            VolumeRules.level(v, stream).now = p.target; // this phone obeys; the read-back is the new level
            bump();
            return VolumeRules.verify(stream, p, VolumeRules.level(v, stream));
        }

        @Override
        public Calls.Result route(String to) {
            done.add("route:" + to);
            return to.equals(Calls.ROUTE_HEADSET) ? Calls.Result.fail("No headset is connected.") : Calls.Result.ok("Sound on " + to);
        }

        @Override
        public List<Calls.Entry> log(int limit, long before) {
            lastLogLimit = limit;
            lastLogBefore = before;
            return log;
        }

        @Override
        public void logSeen() {
            done.add("seen");
        }

        @Override
        public List<Sms.Person> contacts(String query, int limit) {
            lastQuery = query;
            lastContactLimit = limit;
            return people;
        }

        @Override
        public Sms.ContactResult saveContact(String name, String number) {
            done.add("save:" + name + "|" + number);
            Sms.ContactResult r = new Sms.ContactResult();
            r.ok = true;
            r.how = "created";
            r.where = "the phone";
            return r;
        }

        @Override
        public Calls.Headsets headsets() {
            return headsets;
        }

        @Override
        public Calls.Result headsetChoose(String addr) {
            done.add("choose:" + addr);
            return Calls.Result.ok("");
        }

        @Override
        public Calls.Result headsetConnect(String addr) {
            done.add("connect:" + addr);
            return Calls.Result.ok("Connected");
        }

        @Override
        public Calls.Result headsetDisconnect() {
            done.add("disconnect");
            return Calls.Result.ok("");
        }

        @Override
        public Calls.Result headsetScan() {
            done.add("scan");
            return Calls.Result.ok("");
        }

        @Override
        public Calls.Found headsetFound() {
            return found;
        }

        @Override
        public Calls.Result headsetPair(String addr) {
            done.add("pair:" + addr);
            return Calls.Result.ok("Paired");
        }

        @Override
        public Calls.Result bluetoothSettings() {
            done.add("settings");
            return Calls.Result.ok("");
        }

        @Override
        public void grant() throws IOException {
            granted = true;
        }
    }

    static final class Server implements Closeable {
        final ServerSocket ss;
        final Fake fake = new Fake();
        final CallApi api;
        volatile boolean legacy;
        volatile long clock = 1_700_000_000_000L;

        Server() throws IOException {
            api = new CallApi(fake, () -> clock);
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
                    FsHttp.write(api.handle(r.path, r.query, r.body, r.length), out);
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

        RemoteCalls calls() {
            return new RemoteCalls(new PeerLink("127.0.0.1", TOKEN));
        }

        @Override
        public void close() throws IOException {
            ss.close();
        }
    }

    static void expect(Class<? extends IOException> type, String name, RunIo r) {
        try {
            r.run();
            check(false, name + ": should have failed");
        } catch (IOException e) {
            check(type.isInstance(e), name + ": wrong kind of failure " + e);
        }
    }

    interface RunIo {
        void run() throws IOException;
    }

    static Calls.Call ring(String number) {
        Calls.Call c = new Calls.Call();
        c.id = "c1";
        c.state = Calls.RINGING;
        c.incoming = true;
        c.number = number;
        c.since = 1_699_999_990_000L;
        return c;
    }

    public static void main(String[] args) throws Exception {
        try (Server s = new Server()) {
            RemoteCalls rc = s.calls();
            Fake f = s.fake;

            // ---- the state answers even while the switch is off; nothing else does
            f.on = false;
            Calls.State st = rc.state();
            check(!st.on && st.version == Calls.VERSION && st.call, "state while off: " + st.on + " v" + st.version);
            expect(RemoteCalls.Off.class, "now while off", () -> rc.now());
            expect(RemoteCalls.Off.class, "dial while off", () -> rc.dial("5551234567"));
            expect(RemoteCalls.Off.class, "answer while off", rc::answer);
            expect(RemoteCalls.Off.class, "hangup while off", rc::hangup);
            expect(RemoteCalls.Off.class, "log while off", () -> rc.log(10, 0));
            expect(RemoteCalls.Off.class, "contacts while off", () -> rc.contacts("", 10));
            expect(RemoteCalls.Off.class, "headset while off", rc::headsets);
            expect(RemoteCalls.Off.class, "connect while off", () -> rc.headsetConnect(""));
            expect(RemoteCalls.Off.class, "grant while off", rc::grant);
            check(f.dialed.isEmpty() && f.done.isEmpty(), "nothing reached the phone while the switch was off: " + f.dialed + f.done);
            f.on = true;
            check(rc.state().on, "state with the switch on");

            // ---- the grant
            check(!rc.state().answer, "answer missing before the grant");
            Calls.State after = rc.grant();
            check(after.answer && f.granted, "the grant gave the permission");

            // ---- dialing: only what passed the guard reaches the phone
            Calls.Result r = rc.dial("(555) 123-4567");
            check(r.ok && r.out.equals("Calling 5551234567"), "a number is dialed cleaned: " + r.out);
            eq("5551234567", f.dialed.get(0), "what reached the phone");
            f.dialed.clear();
            String[] refused = {"911", "9-1-1", "112", "+1911", "*67911", "#225#", "*#06#", "5551234#", "555*1234", "", "1", "abc", "123456789012345678901"};
            for (String bad : refused) {
                Calls.Result x = rc.dial(bad);
                check(!x.ok && !x.why.isEmpty(), "refused with a sentence: '" + bad + "'");
            }
            check(f.dialed.isEmpty(), "no refused number reached the phone: " + f.dialed);
            f.dialWorks = false;
            r = rc.dial("5551234567");
            check(!r.ok && r.why.equals("A call is already on."), "the phone's own refusal comes through: " + r.why);
            f.dialWorks = true;
            check(rc.dial("*675551234567").ok && f.dialed.contains("*675551234567"), "a service prefix with a number is dialed");

            // ---- the simple actions
            check(rc.answer().ok && rc.hangup().ok, "answer, hangup");
            check(rc.mute(true).ok && rc.mute(false).ok, "mute");
            check(rc.route(Calls.ROUTE_SPEAKER).ok, "route speaker");
            r = rc.route(Calls.ROUTE_HEADSET);
            check(!r.ok && r.why.equals("No headset is connected."), "route headset says what the phone said");
            expect(IOException.class, "an unknown route", () -> rc.route("moon"));
            expect(IOException.class, "an empty route", () -> rc.route(""));
            check(f.done.containsAll(java.util.Arrays.asList("answer", "hangup", "mute1", "mute0", "route:speaker", "route:headset")), "the phone did them: " + f.done);
            check(!f.done.contains("route:moon"), "an unknown route was not passed on");
            rc.logSeen();
            check(f.done.contains("seen"), "seen");

            // ---- the headset's volume (docs/PHONE_CALLS.md "Headset volume")
            f.now = new Calls.Now();
            f.now.headset = "Sony";
            Calls.Now vn = rc.now();
            check(!vn.volume.old && vn.volume.media.now == 6 && vn.volume.media.max == 15 && vn.volume.call.now == 3 && vn.volume.call.min == 1,
                    "the headset's volumes reach the tablet in the live picture");
            check(vn.volume.mediaOnHeadset && vn.volume.callOnHeadset, "and where each one goes");
            r = rc.volume(Calls.STREAM_MEDIA, 1);
            check(r.ok && r.out.equals("Media volume 7 of 15."), "one step up, said as the phone read it back: " + r.out + r.why);
            check(rc.now().volume.media.now == 7, "the next picture has the new level");
            r = rc.volume(Calls.STREAM_MEDIA, -1);
            check(r.ok && f.volumes.media.now == 6, "one step down");
            r = rc.volume(Calls.STREAM_CALL, -3);
            check(r.ok && r.out.equals("Call volume 1 of 5."), "the call volume stops at its own floor of 1: " + r.out + r.why);
            r = rc.volume(Calls.STREAM_CALL, -1);
            check(!r.ok && r.why.equals(VolumeRules.QUIETEST), "already as quiet as it goes: " + r.why);
            expect(IOException.class, "no stream", () -> rc.volume("", 1));
            expect(IOException.class, "an unknown stream (the ringer is not the headset's)", () -> rc.volume("ring", 1));
            expect(IOException.class, "no step", () -> rc.volume(Calls.STREAM_MEDIA, 0));
            expect(IOException.class, "a jump to full loudness is not accepted", () -> rc.volume(Calls.STREAM_MEDIA, 9));
            expect(IOException.class, "nor a jump down", () -> rc.volume(Calls.STREAM_MEDIA, -4));
            check(!f.volumeAsked.contains("ring:1") && !f.volumeAsked.contains("media:0") && !f.volumeAsked.contains("media:9") && !f.volumeAsked.contains("media:-4"),
                    "a request that is not a volume step never reached the phone: " + f.volumeAsked);
            f.now.headset = "";
            r = rc.volume(Calls.STREAM_MEDIA, 1);
            check(!r.ok && r.why.contains(VolumeRules.NO_HEADSET) && f.volumes.media.now == 6, "no headset: nothing moved, the phone said why: " + r.why);
            f.now.headset = "Sony";
            f.volumes.mediaOnHeadset = false;
            r = rc.volume(Calls.STREAM_MEDIA, 1);
            check(!r.ok && r.why.equals(VolumeRules.MEDIA_ELSEWHERE) && f.volumes.media.now == 6, "media on the phone's own speaker is left alone: " + r.why);
            f.volumes = pretendVolumes();
            f.on = false;
            expect(RemoteCalls.Off.class, "volume is a call route: off while the switch is off", () -> rc.volume(Calls.STREAM_MEDIA, 1));
            f.on = true;

            // ---- the live picture
            f.now = new Calls.Now();
            f.now.calls.add(ring("+15551234567"));
            f.now.route = Calls.ROUTE_HEADSET;
            f.now.headset = "Sony";
            f.now.headsetBattery = 64;
            f.missed = 2;
            Calls.Now n = rc.now();
            check(n.calls.size() == 1 && n.calls.get(0).state.equals(Calls.RINGING) && n.calls.get(0).incoming, "a ringing call");
            check(n.route.equals(Calls.ROUTE_HEADSET) && n.headset.equals("Sony") && n.missed == 2, "route, headset, missed");
            check(n.headsetBattery == 64, "the headset's battery reaches the tablet through the real routes");
            eq(s.clock, n.at, "the phone's clock is in the answer");
            check(!n.stamp.isEmpty(), "a stamp");

            // ---- the long poll: waits for news, answers at once when the stamp is old, gives up after the wait
            String stamp = n.stamp;
            long t0 = System.nanoTime();
            Calls.Now same = rc.now(stamp, 1);
            long waited = (System.nanoTime() - t0) / 1_000_000;
            check(waited >= 800 && waited < 4000, "no news: held for the wait (" + waited + " ms)");
            eq(stamp, same.stamp, "and the same stamp came back");
            t0 = System.nanoTime();
            Calls.Now quick = rc.now("not-the-stamp", 5);
            check((System.nanoTime() - t0) / 1_000_000 < 1500, "an old stamp is answered at once");
            final String s1 = stamp;
            final Calls.Now[] got = new Calls.Now[1];
            Thread waiter = new Thread(() -> {
                try {
                    got[0] = rc.now(s1, 10);
                } catch (IOException e) {
                    got[0] = null;
                }
            });
            t0 = System.nanoTime();
            waiter.start();
            Thread.sleep(400);
            check(got[0] == null && waiter.isAlive(), "the request is still held before the news");
            f.now = new Calls.Now();
            f.bump();
            waiter.join(5000);
            long took = (System.nanoTime() - t0) / 1_000_000;
            check(got[0] != null && !got[0].stamp.equals(stamp), "the news ended the wait with a new stamp");
            check(got[0] != null && got[0].calls.isEmpty(), "and the new picture");
            check(took < 3000, "within a moment (" + took + " ms)");
            f.throwOnNow = true;
            expect(IOException.class, "a phone that breaks answers with an error", () -> rc.now());
            f.throwOnNow = false;

            // ---- the call log
            Calls.Entry e = new Calls.Entry();
            e.id = "1";
            e.number = "5551234567";
            e.name = "Mom";
            e.type = Calls.MISSED;
            e.time = 5000;
            e.isNew = true;
            f.log.add(e);
            List<Calls.Entry> got2 = rc.log(60, 0);
            check(got2.size() == 1 && got2.get(0).name.equals("Mom") && got2.get(0).isNew && got2.get(0).type.equals(Calls.MISSED), "the log");
            eq(60, f.lastLogLimit, "the limit");
            rc.log(100000, 4000);
            eq(CallApi.MAX_LOG, f.lastLogLimit, "the limit is capped");
            eq(4000L, f.lastLogBefore, "the paging time");

            // ---- contacts, shared with texts: the same person form
            Sms.Person p = new Sms.Person();
            p.name = "Zoë \"Z\"";
            p.number = "+15551234567";
            f.people.add(p);
            List<Sms.Person> ppl = rc.contacts("zo é", 5000);
            check(ppl.size() == 1 && ppl.get(0).name.equals(p.name) && ppl.get(0).number.equals(p.number), "contacts");
            eq("zo é", f.lastQuery, "the query");
            eq(CallApi.MAX_CONTACTS, f.lastContactLimit, "the contact limit is capped");
            Sms.ContactResult cr = rc.saveContact("  New   Name ", "(555) 123-4567");
            check(cr.ok && cr.how.equals("created"), "a contact is saved");
            check(f.done.stream().anyMatch(x -> x.startsWith("save:New Name|")), "with the spaces tidied: " + f.done);
            cr = rc.saveContact("", "5551234567");
            check(!cr.ok && !cr.why.isEmpty(), "no name is refused");
            cr = rc.saveContact("A", "hello");
            check(!cr.ok && !cr.why.isEmpty(), "no number is refused");

            // ---- the headset
            Calls.Device d = new Calls.Device();
            d.addr = "AA:BB:CC:DD:EE:FF";
            d.name = "Sony";
            d.connected = true;
            f.headsets = new Calls.Headsets();
            f.headsets.devices.add(d);
            f.headsets.allowed = true;
            f.headsets.bluetoothOn = true;
            f.headsets.chosen = d.addr;
            Calls.Headsets hs = rc.headsets();
            check(hs.devices.size() == 1 && hs.devices.get(0).connected && hs.chosen.equals(d.addr), "the headsets");
            check(rc.headsetChoose("aa:bb:cc:dd:ee:ff").ok && f.done.contains("choose:AA:BB:CC:DD:EE:FF"), "choose passes an upper case address");
            expect(IOException.class, "a wrong address", () -> rc.headsetChoose("not an address"));
            expect(IOException.class, "an address with a shell trick", () -> rc.headsetChoose("AA:BB:CC:DD:EE:FF; reboot"));
            expect(IOException.class, "pair needs an address", () -> rc.headsetPair("x"));
            check(!f.done.contains("choose:not an address"), "a wrong address was not passed on");
            check(rc.headsetConnect("").ok && f.done.contains("connect:"), "connect the chosen one");
            check(rc.headsetConnect("11:22:33:44:55:66").ok && f.done.contains("connect:11:22:33:44:55:66"), "connect a given one");
            expect(IOException.class, "connect with a wrong address", () -> rc.headsetConnect("zz"));
            check(rc.headsetDisconnect().ok && f.done.contains("disconnect"), "disconnect");
            check(rc.headsetScan().ok && f.done.contains("scan"), "scan");
            f.found = new Calls.Found();
            f.found.scanning = true;
            f.found.devices.add(d);
            Calls.Found fd = rc.headsetFound();
            check(fd.scanning && fd.devices.size() == 1, "what the scan found");
            check(rc.headsetPair("AA:BB:CC:DD:EE:FF").ok && f.done.contains("pair:AA:BB:CC:DD:EE:FF"), "pair");
            check(rc.bluetoothSettings().ok && f.done.contains("settings"), "settings");

            // ---- an old phone, a stranger
            s.legacy = true;
            expect(RemoteCalls.TooOld.class, "now on an old phone", () -> rc.now());
            expect(RemoteCalls.TooOld.class, "dial on an old phone", () -> rc.dial("5551234567"));
            check(rc.state().version == 0, "an old phone reads as version 0");
            s.legacy = false;
            RemoteCalls stranger = new RemoteCalls(new PeerLink("127.0.0.1", "wrong"));
            expect(IOException.class, "a stranger", () -> stranger.now());
            expect(IOException.class, "a stranger dialing", () -> stranger.dial("5551234567"));
            check(!f.dialed.contains("5559999999"), "the stranger dialed nothing");

            // ---- the handler on its own: odd requests never throw
            CallApi bare = new CallApi(new Fake(), () -> 1L);
            check(bare.handle("/sms/state", java.util.Collections.emptyMap(), null, 0).code == 404, "not a call route");
            check(bare.handle("/call/nothing", java.util.Collections.emptyMap(), null, 0).code == 404, "an unknown call route");
            check(bare.handle("/call/dial", java.util.Collections.emptyMap(), null, 0).code == 409, "a dial without a body is a broken request");
            check(bare.handle("/call/dial", java.util.Collections.emptyMap(), new java.io.ByteArrayInputStream(new byte[0]), 10_000_000).code == 409, "a huge body is refused");
            eq(0, CallApi.waitSeconds(Collections.singletonMap("wait", "-4")), "negative wait");
            eq(CallApi.MAX_WAIT_S, CallApi.waitSeconds(Collections.singletonMap("wait", "9999")), "huge wait");
            eq(0, CallApi.waitSeconds(Collections.singletonMap("wait", "x")), "wait that is not a number");
        }
        System.out.println(checks + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
